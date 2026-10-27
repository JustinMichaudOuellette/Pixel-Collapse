package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.TileSolver

/**
 * One search on the native (C) solver.
 *
 * Every worker of a portfolio gets its own solver, all sharing one [WfcNative.modelCreate] model: the
 * compatibility tables and the per-cell starting domains depend only on the tile set and the grid, so
 * building them once instead of once per worker is free performance.
 *
 * Instances are single-shot: [solve] runs once, and the portfolio calls [close] afterwards.
 */
internal class NativeTileSolver(
  private val model: Long,
  private val columns: Int,
  private val rows: Int,
  private val seed: Int,
) : TileSolver {

  private val handle: Long = if (model == 0L) 0L else WfcNative.solverCreate(model)
  private val assignment = IntArray(columns * rows)
  private var closed = false

  override fun solve(deadlineNanos: Long, maxDecisions: Int): IntArray? {
    if (handle == 0L) return null
    val solved = WfcNative.solverSolve(handle, seed, deadlineNanos, maxDecisions, assignment)
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
    if (handle != 0L) {
      WfcNative.solverDestroy(handle)
    }
  }
}
