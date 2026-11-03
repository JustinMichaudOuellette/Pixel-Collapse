package ca.justinmo.wfcbitmap.generation
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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
) : TileSolver {

  private val handle: Long = if (model == 0L) 0L else NativeSolverPool.acquire()
  private val assignment = IntArray(columns * rows)
  private var closed = false

  override fun solve(deadlineNanos: Long, maxDecisions: Int): IntArray? {
    if (handle == 0L) return null
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