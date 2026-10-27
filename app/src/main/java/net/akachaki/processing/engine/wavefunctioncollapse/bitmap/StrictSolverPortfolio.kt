package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.enum.Direction2d8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

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
 * Runs several independently seeded [StrictBitmapSolver] instances concurrently and returns the
 * first solution.
 *
 * The solver is stochastic and its tail is heavy: the same 135x240 grid takes 11,518 decisions on one
 * seed and can hit the 2,000,000 cap on another (270x480 seeds 1 and 2 do). A portfolio turns idle
 * cores into wall-clock and removes that tail. The cost is one solver's state per worker, which is why
 * [memoryBudgetBytes] caps the width instead of honouring `availableProcessors()` blindly.
 *
 * Losing workers are told to stop through [StrictBitmapSolver.abortRequested] and give up within one
 * clock check, so a finished portfolio does not leave threads burning CPU.
 */
class StrictSolverPortfolio(
    private val cells: List<BitmapWaveFunctionCell>,
    private val columns: Int,
    private val rows: Int,
    private val directions: List<Direction2d> = Direction2d8.entries,
    private val memoryBudgetBytes: Long = 128L * 1024 * 1024,
) {
    /** How many concurrent solvers fit the memory budget at this grid size, at least one. */
    fun workersThatFit(requested: Int): Int {
        val perWorker = (columns.toLong() * rows * BYTES_PER_VARIABLE).coerceAtLeast(1L)
        val fits = (memoryBudgetBytes / perWorker).toInt().coerceAtLeast(1)
        return requested.coerceIn(1, fits)
    }

    /** Measured strict-solver cost per variable, in microseconds (21 µs at 270x480 on a desktop). */
    fun expectedSolveMs(): Long =
        (columns.toLong() * rows * MICROS_PER_VARIABLE / 1000).coerceAtLeast(1L)

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
     */
    fun solve(
        seed: Int,
        workers: Int,
        deadlineNanos: Long,
        maxDecisions: Int = 2_000_000,
        hedgeMs: Long = -1,
    ): PortfolioResult {
        val width = workersThatFit(workers)
        if (width <= 1) {
            val solver = StrictBitmapSolver(cells, columns, rows, directions, Random(seed))
            val startedAt = System.nanoTime()
            val assignment = solver.solve(deadlineNanos, maxDecisions)
            val millis = (System.nanoTime() - startedAt) / 1_000_000
            return PortfolioResult(
                assignment,
                if (assignment != null) seed else null,
                millis,
                listOf(
                    PortfolioEntry(
                        seed,
                        assignment != null,
                        millis,
                        solver.decisionsMade,
                        solver.reviseCount,
                    ),
                ),
            )
        }

        val startedAt = System.nanoTime()
        val hedge = if (hedgeMs < 0) expectedSolveMs() * 3 / 2 else hedgeMs
        // Build every solver before any worker starts, so the winner can abort the whole set.
        val solvers = List(width) { index ->
            StrictBitmapSolver(cells, columns, rows, directions, Random(seed + index))
        }
        val result = AtomicReference<IntArray?>()
        val winner = AtomicInteger(-1)
        val solved = CountDownLatch(1)
        val finished = CountDownLatch(width)
        val entries = arrayOfNulls<PortfolioEntry>(width)
        val pool = Executors.newFixedThreadPool(width) { runnable ->
            Thread(runnable, "wfc-strict-portfolio").apply { isDaemon = true }
        }
        try {
            for (index in 0 until width) {
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
                                    other.abortRequested = true
                                }
                            }
                        }
                        entries[index] = PortfolioEntry(
                            workerSeed,
                            assignment != null,
                            (System.nanoTime() - workerStartedAt) / 1_000_000,
                            solver.decisionsMade,
                            solver.reviseCount,
                        )
                        finished.countDown()
                    }
                }
                if (index == 0 && width > 1 && hedge > 0) {
                    // Let the lone worker try first; only fan out if it is still running.
                    solved.await(hedge, TimeUnit.MILLISECONDS)
                    if (result.get() != null) {
                        // The others were never submitted, so release the latch they would have hit.
                        repeat(width - 1) { finished.countDown() }
                        break
                    }
                }
            }
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0L)
            solved.await(remainingMs + 1_000L, TimeUnit.MILLISECONDS)
            // Winners abort the rest, so this normally returns at once; the grace is only for a worker that overshoots its deadline, and it is deliberately short so a failed solve returns promptly.
            finished.await(5_000L, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        val assignment = result.get()
        return PortfolioResult(
            assignment,
            if (winner.get() >= 0) seed + winner.get() else null,
            (System.nanoTime() - startedAt) / 1_000_000,
            entries.filterNotNull(),
        )
    }

    private companion object {
        /**
         * Bytes of solver state per variable, rounded up: 4 domain words (32), five int arrays
         * (masks and bucket links, 20), the bit-packed worklist (1) and a share of the trail.
         */
        const val BYTES_PER_VARIABLE = 128L

        /** Measured cost per variable: 270x480 (129,600) solves in ~3.1 s. */
        const val MICROS_PER_VARIABLE = 21L
    }
}
