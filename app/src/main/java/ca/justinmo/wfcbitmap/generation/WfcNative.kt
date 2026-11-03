package ca.justinmo.wfcbitmap.generation
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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

  /**
   * @param relaxedEdges false keeps the edge rule: a cell accepts only patterns whose mask is exactly
   *   the directions it has a neighbour in, so a pattern sampled on the drawing's edge can only sit on
   *   the bitmap's edge. That rule can be *impossible* -- a drawing whose border does not repeat cannot
   *   run around a much longer bitmap border -- and then the whole instance has no tiling. True drops
   *   the "exactly": a cell accepts any pattern that has a colour towards every neighbour it has, so a
   *   border cell may use a complete pattern and the pattern's outward pixels are simply not drawn.
   */
  external fun modelCreate(
    tiles: IntArray,
    tileCount: Int,
    columns: Int,
    rows: Int,
    relaxedEdges: Boolean,
  ): Long

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

  /**
   * Like [solverSolve], with optional per-cell pins.
   *
   * @param fixedCenters [Int.MIN_VALUE] leaves a cell free and any other value restricts it to tiles
   *   whose centre is that pixel. The sentinel is not -1: opaque ARGB colours are negative ints, so -1
   *   collides with real colours.
   * @param fixedPatterns the same, for the pattern instead of the pixel: a value is a packed tile index
   *   (see [packTiles]) and restricts the cell to that one pattern. Pinning the pattern is what makes a
   *   stitched screen continue the previous screen's *shape*; a centre alone leaves every pattern that
   *   shows that colour available, so the diagonals behind it can be laid out differently.
   */
  external fun solverSolveFixed(
    solver: Long,
    model: Long,
    seed: Int,
    deadlineNanos: Long,
    maxDecisions: Int,
    fixedCenters: IntArray?,
    fixedPatterns: IntArray?,
    out: IntArray,
  ): Int

  external fun solverDecisions(solver: Long): Int

  external fun solverRevises(solver: Long): Long

  /** Decisions of the last solve that hit a contradiction and were undone, i.e. wasted branches. */
  external fun solverDeadEnds(solver: Long): Long

  /** Revisions of the last solve that actually removed a tile from a domain. */
  external fun solverShrinks(solver: Long): Long

  /**
   * Variable-selection order for the next solves on this solver. 0 is the shipping heuristic (minimum
   * domain, ties to the lowest index); 1 and 2 are the outward-in peel orders, for measurement.
   */
  external fun solverSetOrder(solver: Long, mode: Int)

  /**
   * Arc worklist discipline for the next solves: 0 pops the oldest arc (FIFO, what the app runs), 1 the
   * newest (LIFO). Measurement knob only.
   */
  external fun solverSetWorklist(solver: Long, mode: Int)

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