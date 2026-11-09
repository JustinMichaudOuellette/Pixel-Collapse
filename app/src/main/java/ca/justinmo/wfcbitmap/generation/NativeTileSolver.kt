package ca.justinmo.wfcbitmap.generation

import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.TileSolver

/**
 * One search on the native (C) solver.
 *
 * The handle comes from [NativeSolverPool] and goes back there on [close], so consecutive collapses
 * reuse the same scratch memory. The model is passed per solve and never retained by the solver, which
 * is what makes that reuse safe across generations: the model is destroyed as soon as the collapse is
 * done, and [WfcNative.solverSolve] clears the solver once the result has been copied out.
 *
 * Instances are single-shot: [solve] runs once, and the portfolio calls [close] afterwards.
 */
internal class NativeTileSolver(
  private val model: Long,
  private val columns: Int,
  private val rows: Int,
  private val seed: Int,
  private val fixedCenters: IntArray? = null,
  private val fixedPatterns: IntArray? = null,
  private val traceEnabled: Boolean = false,
) : TileSolver {

  private val handle: Long = if (model == 0L) 0L else NativeSolverPool.acquire()
  private val assignment = IntArray(columns * rows)
  private var closed = false

  /** The traced solve's starting domain sizes, or null when tracing was off or the solve failed. */
  internal var traceInitial: IntArray? = null
    private set

  /** The traced solve's starting average candidate colours, one per cell. */
  internal var traceInitialColors: IntArray? = null
    private set

  /** The traced domain-size/colour transitions, or null when tracing was off or the solve failed. */
  internal var traceEvents: LongArray? = null
    private set

  /** True when the trace buffer filled up; the trace must not be used as a complete animation. */
  internal var traceOverflow: Boolean = false
    private set

  override fun solve(deadlineNanos: Long, maxDecisions: Int): IntArray? {
    if (handle == 0L) return null
    // A pooled handle may have been used for a traced solve before; set the mode on every run.
    WfcNative.solverSetTrace(handle, traceEnabled)
    if (traceEnabled) {
      var solved = 0
      try {
        solved = WfcNative.solverSolveTraced(
          handle, model, seed, deadlineNanos, maxDecisions, assignment,
        )
        if (solved == 1) {
          traceInitial = WfcNative.solverTraceInitial(handle)
          traceInitialColors = WfcNative.solverTraceInitialColors(handle)
          traceEvents = WfcNative.solverTraceEvents(handle)
          traceOverflow = WfcNative.solverTraceOverflow(handle)
        }
      } finally {
        // solveTraced deliberately leaves the solver open so the trace can be read first.
        WfcNative.solverFinish(handle)
      }
      return if (solved == 1) assignment else null
    }
    val solved = if (fixedCenters != null || fixedPatterns != null) {
      WfcNative.solverSolveFixed(
        handle, model, seed, deadlineNanos, maxDecisions, fixedCenters, fixedPatterns, assignment,
      )
    } else {
      WfcNative.solverSolve(handle, model, seed, deadlineNanos, maxDecisions, assignment)
    }
    // The array belongs to this solver, and a solver only ever runs once, so handing it back without a
    // copy is safe here.
    return if (solved == 1) assignment else null
  }

  override fun requestAbort() {
    if (handle != 0L && !closed) {
      WfcNative.solverAbort(handle)
    }
  }

  override val decisionCount: Int
    get() = if (handle == 0L) 0 else WfcNative.solverDecisions(handle)

  override val revisionCount: Long
    get() = if (handle == 0L) 0L else WfcNative.solverRevises(handle)

  override fun close() {
    if (closed) return
    closed = true
    // Back to the pool rather than destroyed: the next collapse reuses the buffers.
    NativeSolverPool.release(handle)
  }
}