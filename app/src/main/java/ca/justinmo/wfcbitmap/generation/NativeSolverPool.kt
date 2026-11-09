package ca.justinmo.wfcbitmap.generation

/**
 * A small pool of native solver handles.
 *
 * A solver is pure scratch space: it is bound to a model by each solve and cleared by
 * [WfcNative.solverFinish] once the result has been taken ([WfcNative.solverSolve] does that itself).
 * So a handle can serve collapse after collapse, and reusing one avoids allocating and first-touching
 * ~250 KB of domains, bitsets and buffers for every worker of every keystroke.
 *
 * Handles are borrowed by [NativeTileSolver] and returned when it is closed. Everything here is
 * synchronised because the portfolio's worker threads finish on their own threads.
 */
internal object NativeSolverPool {

  /** Keep enough handles for a full portfolio and no more. */
  private const val MAX_IDLE = 8

  private val idle = ArrayDeque<Long>()

  /** @return a solver handle, or 0 when the library is unavailable. */
  @Synchronized
  fun acquire(): Long {
    if (!WfcNative.available) return 0L
    return if (idle.isEmpty()) WfcNative.solverCreate() else idle.removeLast()
  }

  /** Gives [handle] back for reuse; destroys it when the pool is already full. */
  @Synchronized
  fun release(handle: Long) {
    if (handle == 0L) return
    if (idle.size >= MAX_IDLE) {
      WfcNative.solverDestroy(handle)
    } else {
      idle.addLast(handle)
    }
  }

  /** Drops every idle handle. Only useful when the native library is going away. */
  @Synchronized
  fun clear() {
    for (handle in idle) {
      WfcNative.solverDestroy(handle)
    }
    idle.clear()
  }
}