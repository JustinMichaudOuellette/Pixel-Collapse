package ca.justinmo.wfcbitmap

import android.util.Log
import androidx.annotation.Keep
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell

/**
 * The native (C) wave-function-collapse solver, and the packed tile set it works on.
 *
 * The C side is the same algorithm and the same edge rule as the Kotlin
 * [StrictBitmapSolver][net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver];
 * it exists because a 76x135 collapse is millions of bitset operations and that is the only hot code
 * in the app. The Kotlin solver is kept as the fallback: it runs wherever the library is missing (JVM
 * unit tests, an unsupported ABI) and its results are what the native one is checked against.
 *
 * `@Keep` is load-bearing: the JNI symbols are resolved by name at link time, so R8 must not rename
 * this object or its methods.
 */
@Keep
internal object WfcNative {

  private const val TAG = "WfcNative"

  /** Ints per tile in the packed array: direction mask, weight, then the nine kernel colours. */
  const val PACKED_TILE_INTS = 11

  private val loaded: Boolean = try {
    System.loadLibrary("wfc")
    Log.i(TAG, "Native WFC solver loaded")
    true
  } catch (e: Throwable) {
    // No library for this ABI (or a broken one). Everything keeps working through the Kotlin solver.
    Log.w(TAG, "Native solver unavailable, falling back to the Kotlin one", e)
    false
  }

  /** True when the native solver can be used. */
  val available: Boolean get() = loaded

  /**
   * Packs [cells] for [modelCreate]: for each tile its direction presence mask (exactly what the edge
   * rule compares), its weight, and its nine kernel colours. Directions are in `Direction2d8`
   * declaration order, which is the order the C side assumes.
   */
  fun packTiles(cells: List<BitmapWaveFunctionCell>): IntArray {
    val packed = IntArray(cells.size * PACKED_TILE_INTS)
    cells.forEachIndexed { index, cell ->
      val base = index * PACKED_TILE_INTS
      var mask = 0
      for (direction in 0 until DIRECTION_COUNT) {
        val slot = DIRECTION_KERNEL_SLOT[direction]
        val colour = cell.kernel[slot]
        if (colour != null) {
          mask = mask or (1 shl direction)
          packed[base + 2 + slot] = colour
        }
      }
      // The centre is not a direction, so the loop above never reaches it -- and it is the pixel the
      // cell actually shows, which every compatibility test compares against. Writing it explicitly is
      // not optional: without it every kernel centre reads as 0 and no two tiles ever fit.
      packed[base + 2 + CENTRE_SLOT] = cell.color
      packed[base] = mask
      packed[base + 1] = cell.weight.toInt().coerceAtLeast(1)
    }
    return packed
  }

  // --- native surface ---------------------------------------------------------------------------

  external fun modelCreate(tiles: IntArray, tileCount: Int, columns: Int, rows: Int): Long

  external fun modelDestroy(model: Long)

  /**
   * How many tiles survived the model's own consistency pass, and where each one came from in the packed
   * array. The model prunes patterns that can never be placed, so a solve result indexes *these* tiles.
   */
  external fun modelTileCount(model: Long): Int

  external fun modelTileSource(model: Long, tile: Int): Int

  /** The ARGB pixel a model tile shows. Valid only while the model is alive. */
  external fun modelTileCenter(model: Long, tile: Int): Int

  external fun solverCreate(): Long

  external fun solverDestroy(solver: Long)

  /** Asks a running solve to stop; safe to call from another thread. */
  external fun solverAbort(solver: Long)

  /** @return 1 when [out] was filled with a tiling, 0 otherwise. */
  external fun solverSolve(solver: Long, model: Long, seed: Int, deadlineNanos: Long, maxDecisions: Int, out: IntArray): Int

  external fun solverDecisions(solver: Long): Int

  external fun solverRevises(solver: Long): Long

  /** Phase timers of the last solve, in nanoseconds; see the phase constants below. */
  external fun solverPhaseNanos(solver: Long, phase: Int): Long

  /**
   * Enables the per-decision phase probes (selection and ordering).
   *
   * Off by default: they cost a clock read per decision, which is nothing where clock_gettime is a
   * vDSO call and microseconds per decision where it is a syscall. Only the benchmark turns them on.
   */
  external fun solverSetFineProfiling(solver: Long, enabled: Boolean)

  /** Indices for [solverPhaseNanos]. */
  object Phase {
    const val SETUP = 0
    const val INITIAL_PROPAGATION = 1
    const val SEARCH = 2
    const val SELECTION = 3
    const val ORDERING = 4
  }

  /**
   * Kernel index each direction reads, in `Direction2d8` declaration order:
   * LEFT, UP_LEFT, UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT.
   */
  private val DIRECTION_KERNEL_SLOT = intArrayOf(3, 0, 1, 2, 5, 8, 7, 6)

  private const val DIRECTION_COUNT = 8

  /** Kernel slot of the centre pixel, the one no direction reads. */
  private const val CENTRE_SLOT = 4
}
