package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

/**
 * What [StrictSolverPortfolio] needs from a solver: one blocking search, a way to stop it from another
 * thread, the counters it reports per worker, and a way to release it.
 *
 * The portfolio does not care which implementation it is driving. `StrictBitmapSolver` is the Kotlin
 * one; the app also has a native (C) solver behind this interface, which is the one that runs when the
 * library is available.
 */
interface TileSolver {

  /**
   * Runs the search.
   *
   * @param deadlineNanos absolute [System.nanoTime] deadline.
   * @param maxDecisions cap on branching decisions.
   * @return a tile index per variable, or null when there is no tiling or the search was stopped.
   */
  fun solve(deadlineNanos: Long, maxDecisions: Int): IntArray?

  /** Asks the search to stop; safe to call from another thread while [solve] runs. */
  fun requestAbort()

  /** Branching decisions made by the last [solve]. */
  val decisionCount: Int

  /** Arc revisions performed by the last [solve]. */
  val revisionCount: Long

  /** Releases native resources, if any. Must tolerate being called once. */
  fun close() = Unit
}
