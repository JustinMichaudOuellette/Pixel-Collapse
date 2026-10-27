package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The model is the *overlapping, bordered* one. Windows are sampled as the drawing lies, so the ones
 * that hang over an edge carry `null`s, and the solver only accepts a tile at a border cell when it is
 * blank in the direction that leaves the grid. The consequence -- and the point of these tests -- is
 * that pixels drawn on the edge of the drawing can only be generated on the edge of the bitmap.
 *
 * That rule is also why some drawings cannot tile at all: the drawing's border has to run all the way
 * around a 76x135 grid. `frameDrawing` below is one that cannot, and the solve proves it by emptying a
 * domain during root propagation rather than by running out of time.
 */
class WfcEngineTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()
  private val green = 0xFF63C74D.toInt()

  // --- drawings -----------------------------------------------------------------------------

  /** A drawn border with a motif inside it: the case the edge rule exists for. */
  private fun borderedMotifDrawing(): IntArray {
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

  /** Concentric rings on a uniform background: edges are one colour, interior is rich. */
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

  /** A little creature with a solid floor: also an every-edge-is-ink drawing. */
  private fun spriteDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    fun plot(x: Int, y: Int, color: Int) {
      if (x in 0 until SOURCE_WIDTH && y in 0 until SOURCE_HEIGHT) pixels[y * SOURCE_WIDTH + x] = color
    }
    for (y in 3..7) {
      for (x in 4..11) {
        val edge = x == 4 || x == 11 || y == 3 || y == 7
        plot(x, y, if (edge) slate else rust)
      }
    }
    for (x in 4..11) { plot(x, 8, green); plot(x, 9, green) }
    plot(6, 5, gold); plot(7, 5, gold); plot(9, 5, gold); plot(10, 5, gold)
    return pixels
  }

  /**
   * A one-pixel frame around noise. Its vertical edge is 11 distinct colours with no repetition, so it
   * cannot be stretched around 135 rows: this drawing has no tiling under the edge rule. Pinned so the
   * app keeps falling back deliberately instead of hanging on it.
   */
  private fun frameDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        pixels[y * SOURCE_WIDTH + x] = when {
          x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1 -> rust
          (x + y) % 3 == 0 -> gold
          x % 4 == 0 || y % 5 == 0 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun tiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    // createCells mirrors the array it is given, so pass a copy exactly like WfcGenerator does.
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

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

  // --- extraction ---------------------------------------------------------------------------

  @Test
  fun extractionKeepsTheDrawingEdgesAsTiles() {
    val cells = tiles(ringsDrawing())
    println("rings tiles=${cells.size}")
    assertTrue("expected several distinct patterns, got ${cells.size}", cells.size >= 4)
    assertTrue(
      "a bordered extraction has to produce edge tiles (kernels with a hole)",
      cells.any { cell -> cell.kernel.any { it == null } },
    )
    assertTrue(
      "and complete tiles for the interior",
      cells.any { cell -> cell.kernel.all { it != null } },
    )
  }

  @Test
  fun flatDrawingCollapsesToItsEdgeVariants() {
    // One pixel value still yields nine bordered windows: the interior plus the four edges and four
    // corners, the latter carrying nulls outside the source. So "flat drawing" is not an error case.
    assertEquals(9, tiles(IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }).size)
  }

  // --- the edge rule ------------------------------------------------------------------------

  /**
   * The requirement, stated as a test: what is drawn on the edge of the pattern is generated on the
   * edge of the bitmap.
   */
  @Test
  fun aDrawnBorderIsGeneratedOnTheBitmapBorder() {
    val cells = tiles(borderedMotifDrawing())
    val (assignment, report) = solve(cells)
    assertNotNull("no tiling: tiles=${report.tiles} decisions=${report.decisions}", assignment)
    assignment!!

    // Every border cell uses a pattern that is blank outwards, i.e. one sampled on that edge of the
    // drawing -- so its centre is a drawn edge pixel.
    for (x in 0 until OUTPUT_COLUMNS) {
      assertBlankTowards(cells[assignment[x]], Direction2d8.UP, "top row x=$x")
      assertBlankTowards(cells[assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x]], Direction2d8.DOWN, "bottom row x=$x")
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertBlankTowards(cells[assignment[y * OUTPUT_COLUMNS]], Direction2d8.LEFT, "left column y=$y")
      assertBlankTowards(
        cells[assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]],
        Direction2d8.RIGHT,
        "right column y=$y",
      )
    }

    // Concretely: the bitmap's whole outline is the gold the border was drawn with, while the inside
    // is not gold-only, so this is a collapse and not a copy.
    for (x in 0 until OUTPUT_COLUMNS) {
      assertEquals("top row x=$x", gold, cells[assignment[x]].color)
      assertEquals("bottom row x=$x", gold, cells[assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x]].color)
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertEquals("left column y=$y", gold, cells[assignment[y * OUTPUT_COLUMNS]].color)
      assertEquals("right column y=$y", gold, cells[assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]].color)
    }
    val inside = (1 until OUTPUT_ROWS - 1).flatMap { y ->
      (1 until OUTPUT_COLUMNS - 1).map { x -> cells[assignment[y * OUTPUT_COLUMNS + x]].color }
    }
    assertTrue("the interior should not be a solid border colour", inside.any { it != gold })
    assertEquals("every cell must satisfy its eight neighbours", 0, countViolations(assignment, cells))
  }

  /** No hole may appear inside the bitmap: only border cells may use a pattern that is blank anywhere. */
  @Test
  fun interiorCellsOnlyUseCompletePatterns() {
    val cells = tiles(ringsDrawing())
    val (assignment, report) = solve(cells)
    assertNotNull("no tiling: tiles=${report.tiles} decisions=${report.decisions}", assignment)
    assignment!!
    for (y in 1 until OUTPUT_ROWS - 1) {
      for (x in 1 until OUTPUT_COLUMNS - 1) {
        val cell = cells[assignment[y * OUTPUT_COLUMNS + x]]
        assertFalse(
          "interior cell ($x,$y) used an edge pattern: ${cell.kernel}",
          cell.kernel.any { it == null },
        )
      }
    }
    assertEquals(0, countViolations(assignment, cells))
  }

  /**
   * The cost of the edge rule, pinned: a border that cannot repeat enough leaves no tiling at all.
   *
   * The solve has to *prove* this quickly (root propagation empties a domain) rather than burn the
   * deadline, which is what the portfolio's early exit is for.
   */
  @Test
  fun drawingWhoseEdgeCannotCloseHasNoTiling() {
    val (assignment, report) = solve(tiles(frameDrawing()), budgetMs = 30_000L)
    assertNull("frame drawing unexpectedly tiled (decisions=${report.decisions})", assignment)
    assertTrue("an unsatisfiable instance should be rejected quickly, not timed out", report.millis < 10_000)
    println("frameDrawing: no tiling in ${report.millis}ms, decisions=${report.decisions}")
  }

  @Test
  fun aRepetitiveInteriorTilesCleanly() {
    for ((name, drawing) in listOf("rings" to ringsDrawing(), "sprite" to spriteDrawing())) {
      val cells = tiles(drawing)
      val (assignment, report) = solve(cells)
      assertNotNull("$name produced no tiling (decisions=${report.decisions})", assignment)
      println("$name: ${cells.size} tiles, ${OUTPUT_COLUMNS}x$OUTPUT_ROWS in ${report.millis}ms")
      assertEquals("$name: neighbour mismatches", 0, countViolations(assignment!!, cells))
    }
  }

  // --- the fallback -------------------------------------------------------------------------

  /**
   * `WfcGenerator`'s fallback when no tiling exists is the drawing stretched across the grid. Checked
   * here for the property it was chosen for: every border cell maps to a pixel on the drawing's border,
   * so the edge rule survives even when the collapse cannot.
   */
  @Test
  fun stretchedFallbackKeepsOnlyEdgePixelsOnTheEdges() {
    for ((name, drawing) in listOf("frame" to frameDrawing(), "rings" to ringsDrawing())) {
      val drawingBorderColors = buildSet {
        for (x in 0 until SOURCE_WIDTH) {
          add(drawing[x])
          add(drawing[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH + x])
        }
        for (y in 0 until SOURCE_HEIGHT) {
          add(drawing[y * SOURCE_WIDTH])
          add(drawing[y * SOURCE_WIDTH + SOURCE_WIDTH - 1])
        }
      }
      var checked = 0
      for (y in 0 until OUTPUT_ROWS) {
        for (x in 0 until OUTPUT_COLUMNS) {
          val onBorder = x == 0 || y == 0 || x == OUTPUT_COLUMNS - 1 || y == OUTPUT_ROWS - 1
          if (!onBorder) {
            continue
          }
          checked++
          // Same mapping as WfcGenerator.stretchedDrawing.
          val sx = (x.toLong() * SOURCE_WIDTH / OUTPUT_COLUMNS).toInt()
          val sy = (y.toLong() * SOURCE_HEIGHT / OUTPUT_ROWS).toInt()
          val color = drawing[sy * SOURCE_WIDTH + sx]
          assertTrue(
            "$name: border cell ($x,$y) mapped to interior pixel ($sx,$sy)",
            color in drawingBorderColors,
          )
        }
      }
      assertTrue("expected border cells to check", checked > 0)

      // The four corners of the bitmap are the four corners of the drawing, exactly.
      val corners = listOf(
        (0 to 0) to drawing[0],
        ((OUTPUT_COLUMNS - 1) to 0) to drawing[SOURCE_WIDTH - 1],
        (0 to (OUTPUT_ROWS - 1)) to drawing[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH],
        ((OUTPUT_COLUMNS - 1) to (OUTPUT_ROWS - 1)) to
          drawing[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH + SOURCE_WIDTH - 1],
      )
      for ((position, expected) in corners) {
        val (x, y) = position
        val sx = (x.toLong() * SOURCE_WIDTH / OUTPUT_COLUMNS).toInt()
        val sy = (y.toLong() * SOURCE_HEIGHT / OUTPUT_ROWS).toInt()
        assertEquals("$name: corner ($x,$y)", expected, drawing[sy * SOURCE_WIDTH + sx])
      }
    }
  }

  // --- cancellation -------------------------------------------------------------------------

  /**
   * A cancelled solve has to give up rather than run to its deadline. This is what the live preview
   * depends on: every edit cancels the solve for the previous drawing.
   */
  @Test
  fun cancelledSolveGivesUpInsteadOfWaitingForItsDeadline() {
    val cells = tiles(ringsDrawing())
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
    val cells = tiles(ringsDrawing())
    val result = StrictSolverPortfolio(cells, OUTPUT_COLUMNS, OUTPUT_ROWS, Direction2d8.entries)
      .solve(
        seed = 1,
        workers = 4,
        deadlineNanos = System.nanoTime() + 30_000_000_000L,
        shouldAbort = { false },
      )
    assertNotNull("the unaborted solve should still find a tiling", result.assignment)
    assertEquals(0, countViolations(result.assignment!!, cells))
  }

  // --- helpers ------------------------------------------------------------------------------

  private fun assertBlankTowards(cell: BitmapWaveFunctionCell, direction: Direction2d8, where: String) {
    assertNull("$where used a pattern that is not blank towards the edge", cell.kernelColor(direction))
  }

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
    /** The editor's grid, i.e. what the app is actually asked to fold. */
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11

    /** Mirrors WfcGenerator.DEFAULT_COLUMNS / DEFAULT_ROWS, which are const and so not loadable here. */
    const val OUTPUT_COLUMNS = 76
    const val OUTPUT_ROWS = 135
  }
}
