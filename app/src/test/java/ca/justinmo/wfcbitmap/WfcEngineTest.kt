package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Guards the ported wave-function-collapse pipeline at the level the app uses it: a drawing becomes a
 * tile set, which is then grown into a bitmap.
 *
 * These run on the JVM (no Android or Compose types), so `:app:testDebugUnitTest` covers the part of
 * [WfcGenerator] that can silently break: tile extraction and the strict solver.
 *
 * Two extraction modes matter here:
 *
 *  - [BitmapWaveFunctionCell.createCells] samples the 3x3 windows as they are, so a window that
 *    crosses the drawing's edge carries `null`s. That yields special edge and corner tiles, which is
 *    what the older "the output is a closed picture" tests exercised.
 *  - [BitmapWaveFunctionCell.createWrappedCells] samples with wrap-around, so every kernel is
 *    complete. The periodic extension of the drawing is then a valid tiling of *any* grid -- the
 *    certificate that a solution always exists -- and this is the mode [WfcGenerator] uses, because
 *    it is what makes "draw a pattern, get a bitmap" unconditional.
 */
class WfcEngineTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()
  private val palette = intArrayOf(ink, rust, gold, slate)

  /** A 2x2-block motif, periodic in both axes, so the tile set can cycle. */
  private fun repeatingDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        pixels[y * SOURCE_WIDTH + x] = palette[((x / 2) + 2 * (y / 2)) % palette.size]
      }
    }
    return pixels
  }

  /** A one-pixel frame around noise: rich, but with no repeating vertical transition. */
  private fun frameDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        pixels[y * SOURCE_WIDTH + x] = when {
          x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1 -> rust
          (x + y) % 3 == 0 -> gold
          x % 4 == 0 || y % 5 == 0 -> slate
          (x / 2 + y / 2) % 2 == 0 -> palette[(x + y) % palette.size]
          else -> ink
        }
      }
    }
    return pixels
  }

  /** Concentric rings: a hand-drawn blob, periodic in neither axis. */
  private fun ringsDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val dx = x - 7
        val dy = y - 5
        val d = dx * dx + dy * dy
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

  /** No structure at all: every pixel random. The worst case for a tiling. */
  private fun noiseDrawing(): IntArray {
    val random = Random(7)
    return IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { palette[random.nextInt(palette.size)] }
  }

  private fun borderedTiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    // createCells mirrors the array it is given, so pass a copy exactly like the app does.
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

  private fun wrappedTiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    BitmapWaveFunctionCell.createWrappedCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

  private fun solve(
    cells: List<BitmapWaveFunctionCell>,
    budgetMs: Long = 30_000L,
  ): Pair<IntArray?, PortfolioReport> {
    val portfolio = StrictSolverPortfolio(cells, OUTPUT_COLUMNS, OUTPUT_ROWS, Direction2d8.entries)
    val result = portfolio.solve(
      seed = 1,
      workers = 4,
      deadlineNanos = System.nanoTime() + budgetMs * 1_000_000,
      maxDecisions = 1_000_000,
    )
    return result.assignment to PortfolioReport(cells.size, result.millis, result.entries.map { it.decisions })
  }

  private data class PortfolioReport(val tiles: Int, val millis: Long, val decisions: List<Int>)

  private fun assertTilesCleanly(
    assignment: IntArray,
    cells: List<BitmapWaveFunctionCell>,
  ) {
    assertEquals(OUTPUT_COLUMNS * OUTPUT_ROWS, assignment.size)
    assertEquals("every cell must satisfy its eight neighbours", 0, countViolations(assignment, cells))
  }

  // --- extraction ---------------------------------------------------------------------------

  @Test
  fun borderedExtractionKeepsTheDrawingEdgesAsTiles() {
    val cells = borderedTiles(repeatingDrawing())
    println("bordered repeatingDrawing tiles=${cells.size}")
    assertTrue("expected several distinct patterns, got ${cells.size}", cells.size >= 4)
    assertTrue(
      "a bordered extraction has to produce edge tiles (kernels with a hole): " +
        cells.none { cell -> cell.kernel.any { it == null } },
      cells.any { cell -> cell.kernel.any { it == null } },
    )
  }

  @Test
  fun uniformDrawingCollapsesToItsEdgeVariants() {
    // One pixel value still yields nine bordered windows: the interior plus the four edges and four
    // corners, the latter carrying nulls outside the source. So "flat drawing" is not an error case.
    assertEquals(9, borderedTiles(IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }).size)
  }

  @Test
  fun wrappedExtractionHasNoEdgeTilesAtAll() {
    // The property the whole guarantee rests on: no kernel has a hole, so no tile is special and the
    // periodic extension of the drawing is a legal tiling of any rectangle.
    for ((name, drawing) in drawings()) {
      val cells = wrappedTiles(drawing)
      assertTrue("$name produced no tiles", cells.isNotEmpty())
      assertTrue(
        "$name produced a kernel with a hole, so it still has special edge tiles",
        cells.none { cell -> cell.kernel.any { it == null } },
      )
    }
    // A flat drawing is a single repeated window.
    assertEquals(1, wrappedTiles(IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }).size)
  }

  // --- solving ------------------------------------------------------------------------------

  @Test
  fun repeatingDrawingSolvesTheDefaultGridWithZeroViolations() {
    val (assignment, report) = solve(borderedTiles(repeatingDrawing()))
    assertNotNull("no tiling: tiles=${report.tiles} decisions=${report.decisions}", assignment)
    println(
      "solved ${OUTPUT_COLUMNS}x$OUTPUT_ROWS from ${report.tiles} bordered tiles in " +
        "${report.millis}ms, decisions=${report.decisions}",
    )
    assertTilesCleanly(assignment!!, borderedTiles(repeatingDrawing()))
  }

  /**
   * The app's contract: whatever is drawn, the solver finds a tiling -- including drawings whose
   * transitions form a path rather than a cycle, and pure noise.
   */
  @Test
  fun everyDrawingTilesFromWrappedExtraction() {
    for ((name, drawing) in drawings()) {
      val cells = wrappedTiles(drawing)
      val (assignment, report) = solve(cells)
      assertNotNull(
        "$name produced no tiling: tiles=${report.tiles} decisions=${report.decisions}",
        assignment,
      )
      println(
        "$name: ${report.tiles} wrapped tiles, ${OUTPUT_COLUMNS}x$OUTPUT_ROWS in ${report.millis}ms, " +
          "decisions=${report.decisions}",
      )
      assertTilesCleanly(assignment!!, cells)
    }
  }

  /**
   * The crop rule: a cell on the border only has to agree with the neighbours it actually has. The
   * solver used to insist that a border cell's kernel be blank outside the grid, which made every
   * complete (wrap-around) tile set unsatisfiable -- 0 tiles fitted, so no bitmap could ever exist.
   */
  @Test
  fun borderCellsAreOnlyConstrainedInwards() {
    val cells = wrappedTiles(ringsDrawing())
    val solver = StrictBitmapSolver(
      cells,
      OUTPUT_COLUMNS,
      OUTPUT_ROWS,
      Direction2d8.entries,
      Random(1),
    )
    // Root propagation on a satisfiable instance must not empty a domain; the old rule emptied all
    // 2 * (columns + rows) - 4 border cells before the search even started.
    assertTrue(
      "root arc consistency rejected the wrapped tile set: failVar=${solver.failureVariable}",
      solver.propagateRoot(),
    )
  }

  // --- cancellation -------------------------------------------------------------------------

  /**
   * A cancelled solve has to give up rather than run to its deadline. This is what the live preview
   * depends on: every edit cancels the solve for the previous drawing, and if that solve ignored the
   * cancellation the preview would keep showing results for drawings that no longer exist.
   *
   * The deadline here is a minute, so a regression shows up as a test that hangs rather than one that
   * fails by a second.
   */
  @Test
  fun cancelledSolveGivesUpInsteadOfWaitingForItsDeadline() {
    val cells = wrappedTiles(ringsDrawing())
    val deadline = System.nanoTime() + 60_000_000_000L

    val solverStartedAt = System.nanoTime()
    val solverAssignment = StrictBitmapSolver(
      cells,
      OUTPUT_COLUMNS,
      OUTPUT_ROWS,
      Direction2d8.entries,
      Random(1),
    ) { true }.solve(deadline, Int.MAX_VALUE)
    val solverMillis = (System.nanoTime() - solverStartedAt) / 1_000_000
    assertNull("a cancelled solver must not return a tiling", solverAssignment)
    assertTrue("cancelled solver took ${solverMillis}ms", solverMillis < 2_000)

    val portfolioStartedAt = System.nanoTime()
    val result = StrictSolverPortfolio(cells, OUTPUT_COLUMNS, OUTPUT_ROWS, Direction2d8.entries)
      .solve(
        seed = 1,
        workers = 4,
        deadlineNanos = deadline,
        shouldAbort = { true },
      )
    val portfolioMillis = (System.nanoTime() - portfolioStartedAt) / 1_000_000
    assertNull("a cancelled portfolio must not return a tiling", result.assignment)
    assertTrue("cancelled portfolio took ${portfolioMillis}ms", portfolioMillis < 2_000)
  }

  /** The same tile set still solves when nothing cancels it, so the check above tests the abort path. */
  @Test
  fun theSameTileSetStillSolvesWithoutCancellation() {
    val cells = wrappedTiles(ringsDrawing())
    val result = StrictSolverPortfolio(cells, OUTPUT_COLUMNS, OUTPUT_ROWS, Direction2d8.entries)
      .solve(
        seed = 1,
        workers = 4,
        deadlineNanos = System.nanoTime() + 30_000_000_000L,
        shouldAbort = { false },
      )
    assertNotNull("the unaborted solve should still find a tiling", result.assignment)
    assertTilesCleanly(result.assignment!!, cells)
  }

  // --- the fallback -------------------------------------------------------------------------

  /**
   * [WfcGenerator]'s fallback is the drawing repeated periodically and cropped. It is not a guess:
   * it is a legal tiling of the same tile set, which is why the app can promise a bitmap even if the
   * search runs out of time. Checked here against the strict rule, independently of the solver.
   */
  @Test
  fun periodicCropOfTheDrawingIsAValidTiling() {
    for ((name, drawing) in drawings()) {
      val cells = wrappedTiles(drawing)
      // Cell (x, y) of the cropped picture is the drawing's pixel (x mod W, y mod H), but the *tile*
      // there is the wrapped 3x3 window around it -- picking a tile by centre colour alone would
      // stitch together windows that never sat next to each other.
      val assignment = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS) { index ->
        val x = index % OUTPUT_COLUMNS
        val y = index / OUTPUT_COLUMNS
        val sx = x % SOURCE_WIDTH
        val sy = y % SOURCE_HEIGHT
        val window = Array<Int?>(9) { 0 }
        for (dy in -1..1) {
          for (dx in -1..1) {
            val px = Math.floorMod(sx + dx, SOURCE_WIDTH)
            val py = Math.floorMod(sy + dy, SOURCE_HEIGHT)
            window[(dy + 1) * 3 + dx + 1] = drawing[py * SOURCE_WIDTH + px]
          }
        }
        cells.indexOfFirst { it.kernel.contentEquals(window) }
          .also { check(it >= 0) { "$name: the drawing's own window is missing from its tile set" } }
      }
      assertEquals(
        "$name: the drawing's periodic extension violates the strict rule",
        0,
        countViolations(assignment, cells),
      )
    }
  }

  // --- helpers ------------------------------------------------------------------------------

  private fun drawings(): List<Pair<String, IntArray>> = listOf(
    "repeating" to repeatingDrawing(),
    "frame" to frameDrawing(),
    "rings" to ringsDrawing(),
    "noise" to noiseDrawing(),
  )

  /**
   * Independent check of the strict rule: for every pair of adjacent cells, the pixel one kernel
   * shows towards the other must be the other's centre colour, and vice versa.
   */
  private fun countViolations(
    assignment: IntArray,
    cells: List<BitmapWaveFunctionCell>,
  ): Int {
    var violations = 0
    for (y in 0 until OUTPUT_ROWS) {
      for (x in 0 until OUTPUT_COLUMNS) {
        val here = cells[assignment[y * OUTPUT_COLUMNS + x]]
        for (direction in Direction2d8.entries) {
          val nx = x + direction.dx
          val ny = y + direction.dy
          if (nx < 0 || ny < 0 || nx >= OUTPUT_COLUMNS || ny >= OUTPUT_ROWS) {
            continue
          }
          val there = cells[assignment[ny * OUTPUT_COLUMNS + nx]]
          val edge = here.kernelColor(direction)
          val backEdge = there.kernelColor(direction.opposite())
          if (edge != there.color || backEdge != here.color) {
            violations++
          }
        }
      }
    }
    return violations
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 12
    const val OUTPUT_COLUMNS = 54
    const val OUTPUT_ROWS = 96
  }
}
