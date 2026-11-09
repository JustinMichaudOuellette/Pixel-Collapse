package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcNative
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the native solver's time goes, per drawing, over repeated solves.
 *
 * Not an assertion-heavy test: it exists to answer "what should be optimised next?" with numbers
 * instead of guesses, and to show the cost of the work *around* the search (model and solver creation,
 * which happen on every edit in the app).
 */
@RunWith(AndroidJUnit4::class)
class NativeSolverBenchmarkTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()

  private fun flat(): IntArray = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }

  private fun bordered(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val onEdge = x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1
        pixels[y * SOURCE_WIDTH + x] = when {
          onEdge -> gold
          (x - 7) * (x - 7) + (y - 5) * (y - 5) < 9 -> rust
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun rings(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val d = (x - 7) * (x - 7) + (y - 5) * (y - 5)
        pixels[y * SOURCE_WIDTH + x] = when {
          d < 6 -> gold
          d < 16 -> rust
          d < 30 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun tiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

  private class Totals {
    var wall = 0L
    var create = 0L
    var setup = 0L
    var initial = 0L
    var search = 0L
    var selection = 0L
    var ordering = 0L
    var decisions = 0L
    var revises = 0L
    var deadEnds = 0L
    var shrinks = 0L
  }

  private fun run(label: String, pixels: IntArray, repeats: Int, orderMode: Int = 0) {
    // The pipeline around the solver runs on every edit too, so time it here as well.
    val extractStartedAt = System.nanoTime()
    val cells = tiles(pixels)
    val extractNanos = System.nanoTime() - extractStartedAt
    val packStartedAt = System.nanoTime()
    val packed = WfcNative.packTiles(cells)
    val packNanos = System.nanoTime() - packStartedAt
    // Warm up once so the first-run JIT/page-fault cost does not land on the averages.
    solveOnce(packed, cells.size)?.let { }

    val totals = Totals()
    var solved = 0
    var modelTileCount = 0
    /*
     * One solver for every repeat, exactly like the pool: the app keeps its worker solvers alive across
     * collapses, so the model is the only thing built per edit.
     */
    val solver = WfcNative.solverCreate()
    check(solver != 0L)
    WfcNative.solverSetOrder(solver, orderMode)
    // Ask for the per-decision split; the extra clock reads are part of these numbers.
    WfcNative.solverSetFineProfiling(solver, true)
    repeat(repeats) {
      val createStartedAt = System.nanoTime()
      val model = WfcNative.modelCreate(packed, cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
      check(model != 0L)
      totals.create += System.nanoTime() - createStartedAt
      if (modelTileCount == 0) modelTileCount = WfcNative.modelTileCount(model)

      val startedAt = System.nanoTime()
      val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
      if (WfcNative.solverSolve(solver, model, 1, Long.MAX_VALUE, 1_000_000, out) == 1) solved++
      totals.wall += System.nanoTime() - startedAt

      totals.setup += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SETUP)
      totals.initial += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.INITIAL_PROPAGATION)
      totals.search += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SEARCH)
      totals.selection += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SELECTION)
      totals.ordering += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.ORDERING)
      totals.decisions += WfcNative.solverDecisions(solver)
      totals.revises += WfcNative.solverRevises(solver)
      totals.deadEnds += WfcNative.solverDeadEnds(solver)
      totals.shrinks += WfcNative.solverShrinks(solver)

      WfcNative.modelDestroy(model)
    }
    WfcNative.solverDestroy(solver)

    fun ms(value: Long) = "%.2f".format(value / 1_000_000.0 / repeats)
    println(
      "PIPELINE $label extraction=" + "%.2f".format(extractNanos / 1_000_000.0) +
        "ms packing=" + "%.2f".format(packNanos / 1_000_000.0) + "ms",
    )
    println(
      "PROFILE $label sampled=${cells.size} model=$modelTileCount solved=$solved/$repeats  " +
        "wall=${ms(totals.wall)}ms  create=${ms(totals.create)}ms  " +
        "setup=${ms(totals.setup)}  initial=${ms(totals.initial)}  search=${ms(totals.search)}  " +
        "of which select=${ms(totals.selection)} order=${ms(totals.ordering)}  " +
        "decisions/run=${totals.decisions / repeats} revises/run=${totals.revises / repeats} " +
        "deadends/run=${totals.deadEnds / repeats} shrinks/run=${totals.shrinks / repeats}",
    )
  }

  /**
   * How much of the search is wasted on branches that lead nowhere, and what the decision order is worth.
   *
   * Three orders, same drawings, one seed: the shipping heuristic (0), the fixed outward-in peel (1) and
   * minimum-domain with a peel tie-break (2). Each solve is given a deadline so a bad order fails fast
   * instead of burning the decision cap.
   */
  @Test
  fun compareOrders() {
    println("ORDER device=${android.os.Build.MODEL}")
    for (mode in 0..2) {
      runWith("bordered", bordered(), orderMode = mode, worklist = 0)
      runWith("rings", rings(), orderMode = mode, worklist = 0)
    }
  }

  /**
   * FIFO against LIFO arc worklists. The shrink count is the number that matters: it is how many times a
   * cell's domain narrows, and every narrowing re-revises up to eight neighbours.
   */
  @Test
  fun compareWorklists() {
    println("WORKLIST device=${android.os.Build.MODEL}")
    for (mode in 0..1) {
      runWith("bordered", bordered(), orderMode = 0, worklist = mode)
      runWith("rings", rings(), orderMode = 0, worklist = mode)
    }
  }

  /**
   * How many *distinct overlaps* the tile set has, per direction.
   *
   * Two tiles are compatible in a direction exactly when their overlapping cells agree, so the
   * compatibility relation is an equality on the overlap content: tiles sharing an overlap are
   * interchangeable in that direction. A revision could therefore be expressed as "union the groups of
   * the overlaps still present on the other side" instead of testing each of the destination's candidates
   * one at a time, which is only worth anything if the number of distinct overlaps is much smaller than
   * the number of tiles. This prints both, so the answer is a measurement rather than a guess.
   */
  @Test
  fun overlapAlphabet() {
    for (label in listOf("bordered", "rings")) {
      val pixels = if (label == "bordered") bordered() else rings()
      val cells = tiles(pixels)
      val packed = WfcNative.packTiles(cells)
      val report = StringBuilder()
      for (direction in 0 until 8) {
        val dx = DIRECTION_DX[direction]
        val dy = DIRECTION_DY[direction]
        val overlaps = HashSet<Long>()
        for (tile in 0 until cells.size) {
          val base = tile * WfcNative.PACKED_TILE_INTS
          var key = 0L
          for (y in 0 until 3) {
            for (x in 0 until 3) {
              // This slot of the tile lands on slot (x - dx, y - dy) of its neighbour, so it belongs to
              // the shared region only when that lands inside the neighbour's own 3x3 kernel.
              val nx = x - dx
              val ny = y - dy
              if (nx < 0 || nx > 2 || ny < 0 || ny > 2) continue
              key = key * 31 + (packed[base + 2 + x + 3 * y].toLong() and 0xFFFFFFFFL)
            }
          }
          overlaps.add(key)
        }
        report.append(" d$direction=${overlaps.size}")
      }
      println("SIGNATURES $label tiles=${cells.size}$report")
    }
  }

  /**
   * Why a busy border kills the edge rule, and whether padding the drawing rescues it.
   *
   * The edge rule makes the bitmap's border out of the drawing's border. `frame` has a one-colour frame
   * around noise, so its border pixels are identical everywhere except the corners -- that tiles. `frame`
   * with the frame coloured per row instead is a border that cannot repeat, and then no tiling exists at
   * all. The question this answers is whether padding the drawing with a uniform ring -- which the app
   * could do automatically when the strict rule fails -- brings the picture back.
   */
  @Test
  fun paddingRescuesAnUnrepeatableBorder() {
    println("PAD device=${android.os.Build.MODEL}")
    // A border that cannot repeat: every border pixel a different colour, noise inside.
    val stripes = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val onBorder = x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1
        stripes[y * SOURCE_WIDTH + x] = if (onBorder) {
          (0xFF000000.toInt() or (x * 16 + y * 37) * 0x010101)
        } else {
          when ((x + y) % 3) {
            0 -> gold
            1 -> rust
            else -> ink
          }
        }
      }
    }
    attempt("stripes", stripes, SOURCE_WIDTH, SOURCE_HEIGHT)
    attempt(
      "stripes+1ring",
      pad(stripes, SOURCE_WIDTH, SOURCE_HEIGHT, 1, ink),
      SOURCE_WIDTH + 2,
      SOURCE_HEIGHT + 2,
    )
    // Control: a drawing that already tiles must keep tiling when padded.
    attempt("bordered", bordered(), SOURCE_WIDTH, SOURCE_HEIGHT)
    attempt(
      "bordered+1ring",
      pad(bordered(), SOURCE_WIDTH, SOURCE_HEIGHT, 1, ink),
      SOURCE_WIDTH + 2,
      SOURCE_HEIGHT + 2,
    )
  }

  private fun pad(pixels: IntArray, width: Int, height: Int, ring: Int, colour: Int): IntArray {
    val paddedWidth = width + 2 * ring
    val paddedHeight = height + 2 * ring
    val padded = IntArray(paddedWidth * paddedHeight) { colour }
    for (y in 0 until height) {
      for (x in 0 until width) {
        padded[(y + ring) * paddedWidth + (x + ring)] = pixels[y * width + x]
      }
    }
    return padded
  }

  private fun attempt(label: String, pixels: IntArray, width: Int, height: Int) {
    val cells = BitmapWaveFunctionCell.createCells(IntArray2D(width, height, pixels.copyOf()))
    val packed = WfcNative.packTiles(cells)
    val model = WfcNative.modelCreate(packed, cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
    if (model == 0L) {
      println("PAD $label sampled=${cells.size} model=0 (nothing can be placed)")
      return
    }
    val solver = WfcNative.solverCreate()
    check(solver != 0L)
    val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
    val startedAt = System.nanoTime()
    val solved = WfcNative.solverSolve(solver, model, 1, System.nanoTime() + 8_000_000_000L, 1_000_000, out)
    val millis = (System.nanoTime() - startedAt) / 1_000_000
    println(
      "PAD $label sampled=${cells.size} model=${WfcNative.modelTileCount(model)} solved=$solved " +
        "wall=${millis}ms decisions=${WfcNative.solverDecisions(solver)}",
    )
    WfcNative.solverDestroy(solver)
    WfcNative.modelDestroy(model)
  }

  private fun runWith(label: String, pixels: IntArray, orderMode: Int, worklist: Int) {
    val repeats = 3
    val cells = tiles(pixels)
    val packed = WfcNative.packTiles(cells)
    val solver = WfcNative.solverCreate()
    check(solver != 0L)
    WfcNative.solverSetOrder(solver, orderMode)
    WfcNative.solverSetWorklist(solver, worklist)
    var solved = 0
    var wall = 0L
    var decisions = 0L
    var deadEnds = 0L
    var revises = 0L
    var shrinks = 0L
    repeat(repeats) {
      val model = WfcNative.modelCreate(packed, cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
      check(model != 0L)
      val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
      val deadline = System.nanoTime() + 5_000_000_000L
      val startedAt = System.nanoTime()
      if (WfcNative.solverSolve(solver, model, 1, deadline, 1_000_000, out) == 1) solved++
      wall += System.nanoTime() - startedAt
      decisions += WfcNative.solverDecisions(solver)
      deadEnds += WfcNative.solverDeadEnds(solver)
      revises += WfcNative.solverRevises(solver)
      shrinks += WfcNative.solverShrinks(solver)
      WfcNative.modelDestroy(model)
    }
    WfcNative.solverDestroy(solver)
    println(
      "WORKLIST $label order=$orderMode worklist=$worklist solved=$solved/$repeats " +
        "wall=${"%.2f".format(wall / 1_000_000.0 / repeats)}ms " +
        "decisions=${decisions / repeats} deadends=${deadEnds / repeats} " +
        "revises=${revises / repeats} shrinks=${shrinks / repeats}",
    )
  }

  private fun solveOnce(packed: IntArray, tileCount: Int): IntArray? {
    val model = WfcNative.modelCreate(packed, tileCount, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
    if (model == 0L) return null
    val solver = WfcNative.solverCreate()
    if (solver == 0L) {
      WfcNative.modelDestroy(model)
      return null
    }
    val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
    val result = if (WfcNative.solverSolve(solver, model, 1, Long.MAX_VALUE, 1_000_000, out) == 1) out else null
    WfcNative.solverDestroy(solver)
    WfcNative.modelDestroy(model)
    return result
  }

  @Test
  fun profilePhases() {
    println("PROFILE device=${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.first()}")
    run("flat", flat(), repeats = 20)
    run("bordered", bordered(), repeats = 10)
    run("rings", rings(), repeats = 10)
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
    const val OUTPUT_COLUMNS = 152
    const val OUTPUT_ROWS = 270

    /** Direction2d8 declaration order: LEFT, UP_LEFT, UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT. */
    val DIRECTION_DX = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    val DIRECTION_DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)
  }
}