package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** One worker's outcome, kept for the benchmark's per-worker reporting. */
class PortfolioEntry(
    val seed: Int,
    val won: Boolean,
    val millis: Long,
    val decisions: Int,
    val revises: Long,
)

/** What [StrictSolverPortfolio.solve] found, plus the per-worker detail. */
class PortfolioResult(
    val assignment: IntArray?,
    val seed: Int?,
    val millis: Long,
    val entries: List<PortfolioEntry>,
)

/**
 * Runs several independently seeded solvers concurrently and returns the first solution.
 *
 * The search is stochastic and its tail is heavy: one seed can collapse a grid in 23 decisions while
 * another exhausts the decision cap. A portfolio turns idle cores into wall-clock and removes that
 * tail. The cost is one solver's state per worker, which is why [memoryBudgetBytes] caps the width
 * instead of honouring `availableProcessors()` blindly.
 *
 * Solvers come from [solverFactory] -- the C solver, bound to the same tile set -- and each is closed
 * when the solve ends, so a pooled implementation hands its memory back rather than dropping it.
 * Losing workers are told to stop through [TileSolver.requestAbort] and give up within one clock
 * check, so a finished portfolio does not leave threads burning CPU.
 */
class StrictSolverPortfolio(
    private val columns: Int,
    private val rows: Int,
    private val memoryBudgetBytes: Long = 128L * 1024 * 1024,
    /**
     * Builds a worker solver for [seed], or null when none can be had (native library missing).
     *
     * There is no built-in solver any more: the search lives in C
     * ([ca.justinmo.wfcbitmap.WfcNative]), and this factory is how the app plugs it in while this
     * package stays free of JNI. Every solver it returns is closed by the portfolio when the solve ends.
     */
    private val solverFactory: (seed: Int, columns: Int, rows: Int) -> TileSolver?,
) {
    /** How many concurrent solvers fit the memory budget at this grid size, at least one. */
    fun workersThatFit(requested: Int): Int {
        val perWorker = (columns.toLong() * rows * BYTES_PER_VARIABLE).coerceAtLeast(1L)
        val fits = (memoryBudgetBytes / perWorker).toInt().coerceAtLeast(1)
        return requested.coerceIn(1, fits)
    }

    /** A worker solver for [seed], or null when the factory cannot provide one. */
    private fun newSolver(seed: Int): TileSolver? = solverFactory(seed, columns, rows)

    /**
     * @param seed first seed; worker `i` runs seed `seed + i`.
     * @param workers requested concurrency (reduced to what fits, see [workersThatFit]).
     * @param deadlineNanos absolute [System.nanoTime] deadline shared by every worker.
     * @param maxDecisions per-worker decision cap.
     * @param hedgeMs how long the first worker runs alone before the rest are started; pass a negative
     *   value (the default) to derive it from the grid size. Concurrent solvers saturate memory
     *   bandwidth (measured: four workers at 135x240 each take 1.67x as long as one), so a grid that
     *   solves quickly is faster with no portfolio at all and only a run that outlives the hedge is
     *   worth spreading. Pass 0 to start every worker at once.
     * @param shouldAbort the caller's own reason to stop, polled while the workers run (see
     *   [ABORT_POLL_MS]). When it turns true the workers are cancelled, unsent workers are never
     *   started, and [solve] returns null instead of waiting for the deadline -- that is what lets a
     *   live preview abandon the solve for a drawing the user has already changed. Defaults to never.
     */
    fun solve(
        seed: Int,
        workers: Int,
        deadlineNanos: Long,
        maxDecisions: Int = 2_000_000,
        hedgeMs: Long = -1,
        shouldAbort: () -> Boolean = { false },
    ): PortfolioResult {
        val width = workersThatFit(workers)
        /*
         * One code path for every width, including a single worker: the collapse runs on its own thread
         * so the waiting side can poll `shouldAbort` and abort it. That is also what makes a one-solver
         * collapse cancellable, which the removed Kotlin path could not manage.
         */
        val startedAt = System.nanoTime()
        // The hedge exists to keep a fast solve from paying for three more workers; it is short because
        // the native solver is several times faster per variable than the Kotlin one it replaced
        // (measured: the old 320 ms hedge made a 4-worker portfolio take 739 ms for a drawing the first
        // worker solves in 189 ms).
        val hedge = if (hedgeMs >= 0) hedgeMs else FAST_SOLVER_HEDGE_MS
        // Build every solver before any worker starts, so the winner can abort the whole set.
        val solverList = List(width) { index -> newSolver(seed + index) }
        if (solverList.any { it == null }) {
            for (solver in solverList) solver?.close()
            return PortfolioResult(null, null, 0, emptyList())
        }
        val solvers = solverList.filterNotNull()
        val result = AtomicReference<IntArray?>()
        val winner = AtomicInteger(-1)
        val solved = CountDownLatch(1)
        val finished = CountDownLatch(width)
        val entries = arrayOfNulls<PortfolioEntry>(width)
        val pool = Executors.newFixedThreadPool(width) { runnable ->
            Thread(runnable, "wfc-strict-portfolio").apply { isDaemon = true }
        }
        var submitted = 0
        try {
            for (index in 0 until width) {
                if (shouldAbort()) {
                    break
                }
                val solver = solvers[index]
                val workerSeed = seed + index
                pool.execute {
                    val workerStartedAt = System.nanoTime()
                    var assignment: IntArray? = null
                    try {
                        assignment = solver.solve(deadlineNanos, maxDecisions)
                    } finally {
                        if (assignment != null && result.compareAndSet(null, assignment)) {
                            winner.set(index)
                            solved.countDown()
                            for (other in solvers) {
                                if (other !== solver) {
                                    other.requestAbort()
                                }
                            }
                        }
                        entries[index] = PortfolioEntry(
                            workerSeed,
                            assignment != null,
                            (System.nanoTime() - workerStartedAt) / 1_000_000,
                            solver.decisionCount,
                            solver.revisionCount,
                        )
                        finished.countDown()
                    }
                }
                submitted++
                if (index == 0 && width > 1 && hedge > 0) {
                    // Let the lone worker try first; only fan out if it is still running.
                    if (awaitOutcome(solved, finished, hedge, shouldAbort)) {
                        break
                    }
                }
            }
            // Workers that were never submitted must not hold `finished` open.
            repeat(width - submitted) { finished.countDown() }
            if (!shouldAbort()) {
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0L)
                awaitOutcome(solved, finished, remainingMs + 1_000L, shouldAbort)
            }
            if (shouldAbort()) {
                for (solver in solvers) {
                    solver.requestAbort()
                }
            }
            // Winners abort the rest, so this normally returns at once; the grace is only for a worker that overshoots its deadline, and it is deliberately short so a failed solve returns promptly.
            finished.await(5_000L, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
            /*
             * Release the worker solvers (native ones own memory). Only once every worker has actually
             * finished: a thread that overshot the grace could still be inside its solver, and leaking
             * a handle is far better than freeing memory a live thread is reading.
             */
            if (finished.count == 0L) {
                for (solver in solvers) {
                    solver.close()
                }
            }
        }
        val assignment = result.get()
        return PortfolioResult(
            assignment,
            if (winner.get() >= 0) seed + winner.get() else null,
            (System.nanoTime() - startedAt) / 1_000_000,
            entries.filterNotNull(),
        )
    }

    /**
     * Waits for an outcome: a solution, every worker finishing without one, an abort, or [millis]
     * running out.
     *
     * @return true when a solution arrived, false otherwise.
     *
     * The wait has to be polled rather than one long [CountDownLatch.await], for two reasons. An
     * aborted or unsatisfiable instance produces no solution at all, so a single blocking wait would
     * keep the caller (the UI, in this app) stuck until the deadline -- and with the edge rule, "no
     * tiling at all" is a normal outcome that root propagation proves in milliseconds, not something
     * worth waiting minutes for. The `finished` count covers exactly that case: every worker has
     * given up, so there is nothing left to wait for.
     */
    private fun awaitOutcome(
        solved: CountDownLatch,
        finished: CountDownLatch,
        millis: Long,
        shouldAbort: () -> Boolean,
    ): Boolean {
        val endAt = System.nanoTime() + millis.coerceAtLeast(0L) * 1_000_000
        while (true) {
            if (solved.await(ABORT_POLL_MS, TimeUnit.MILLISECONDS)) {
                return true
            }
            if (finished.count == 0L) {
                return false
            }
            if (shouldAbort()) {
                return false
            }
            if (System.nanoTime() >= endAt) {
                return false
            }
        }
    }

    private companion object {
        /**
         * How often [awaitSolved] wakes up to look at the caller's abort check. Short enough that a
         * cancelled solve stops in a few tens of milliseconds, long enough that polling costs nothing.
         */
        const val ABORT_POLL_MS = 25L

        /**
         * Hedge used when a [solverFactory] supplies the worker solvers. Short enough that a fast
         * solver's first attempt is not wasted waiting, long enough that a drawing which solves
         * quickly never pays for three more workers.
         */
        const val FAST_SOLVER_HEDGE_MS = 60L

        /**
         * Bytes of solver state per variable, rounded up: 4 domain words (32), five int arrays
         * (masks and bucket links, 20), the bit-packed worklist (1) and a share of the trail.
         */
        const val BYTES_PER_VARIABLE = 128L


    }
}