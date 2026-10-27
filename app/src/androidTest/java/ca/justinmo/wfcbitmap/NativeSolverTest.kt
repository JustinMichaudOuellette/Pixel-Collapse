package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * The device-side half of the engine tests: everything that needs the C solver.
 *
 * The C solver is the only solver now, so these are the tests that would have caught a wrong edge
 * rule, a stale reuse of a pooled solver, or a solve that ignores its abort flag. The tile-level and
 * fallback tests stay on the JVM in [WfcEngineTest].
 */
@RunWith(AndroidJUnit4::class)
class NativeSolverTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()
  private val green = 0xFF63C74D.toInt()

  // --- drawings -----------------------------------------------------------------------------

  private fun flatDrawing(): IntArray = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }

  /** A drawn border with a motif inside: the case the edge rule exists for. */
  private fun borderedDrawing(): IntArray {
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

  private fun ringsDrawing(): IntArray {
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
   * cannot run around 135 rows: this drawing has no tiling under the edge rule.
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
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

  /** Runs one native solve on a single seed, the way one portfolio worker does. */
  private fun solve(cells: List<BitmapWaveFunctionCell>, seed: Int = 1): Pair<IntArray?, Long> {
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    assertTrue("native model could not be created", model != 0L)
    val solver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = seed)
    val startedAt = System.nanoTime()
    return try {
      solver.solve(System.nanoTime() + 60_000_000_000L, 1_000_000) to
        ((System.nanoTime() - startedAt) / 1_000_000)
    } finally {
      solver.close()
      WfcNative.modelDestroy(model)
    }
  }

  // --- the solver itself --------------------------------------------------------------------

  @Test
  fun nativeLibraryIsAvailable() {
    assertTrue("libwfc.so did not load on this device", WfcNative.available)
  }

  @Test
  fun flatDrawingCollapsesToItsEdgeVariants() {
    val cells = tiles(flatDrawing())
    assertEquals(9, cells.size)
    val (assignment, millis) = solve(cells)
    assertNotNull("the flat tile set must still tile", assignment)
    println("flat: ${cells.size} tiles in ${millis}ms")
    assertEquals(0, violations(assignment!!, cells))
    assertBorderPatternsAreBlankOutwards("flat", assignment, cells)
  }

  /**
   * The requirement, stated as a test: what is drawn on the edge of the pattern is generated on the
   * edge of the bitmap.
   */
  @Test
  fun drawnBorderIsGeneratedOnTheBitmapBorder() {
    val cells = tiles(borderedDrawing())
    val (assignment, millis) = solve(cells)
    assertNotNull("no tiling for the bordered drawing", assignment)
    assignment!!
    println("bordered: ${cells.size} tiles in ${millis}ms")
    assertBorderPatternsAreBlankOutwards("bordered", assignment, cells)

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
    assertEquals("every cell must satisfy its eight neighbours", 0, violations(assignment, cells))
  }

  /** No hole may appear inside the bitmap: only border cells may use a pattern that is blank anywhere. */
  @Test
  fun interiorCellsOnlyUseCompletePatterns() {
    val cells = tiles(ringsDrawing())
    val (assignment, millis) = solve(cells)
    assertNotNull("no tiling for the rings drawing", assignment)
    assignment!!
    println("rings: ${cells.size} tiles in ${millis}ms")
    for (y in 1 until OUTPUT_ROWS - 1) {
      for (x in 1 until OUTPUT_COLUMNS - 1) {
        val cell = cells[assignment[y * OUTPUT_COLUMNS + x]]
        assertFalse(
          "interior cell ($x,$y) used an edge pattern: ${cell.kernel}",
          cell.kernel.any { it == null },
        )
      }
    }
    assertEquals(0, violations(assignment, cells))
  }

  /**
   * The cost of the edge rule, pinned: a border that cannot repeat enough leaves no tiling at all.
   *
   * The solve has to *prove* this quickly (root propagation empties a domain) rather than burn the
   * deadline, which is what the portfolio's early exit is for.
   */
  @Test
  fun drawingWhoseEdgeCannotCloseHasNoTiling() {
    val (assignment, millis) = solve(tiles(frameDrawing()))
    assertNull("the frame drawing unexpectedly tiled", assignment)
    assertTrue("an unsatisfiable instance should be rejected quickly, not timed out (took ${millis}ms)", millis < 10_000)
    println("frame: no tiling in ${millis}ms")
  }

  @Test
  fun repetitiveDrawingsTileCleanly() {
    for ((name, drawing) in listOf("rings" to ringsDrawing(), "sprite" to spriteDrawing())) {
      val cells = tiles(drawing)
      val (assignment, millis) = solve(cells)
      assertNotNull("$name produced no tiling", assignment)
      println("$name: ${cells.size} tiles, ${OUTPUT_COLUMNS}x$OUTPUT_ROWS in ${millis}ms")
      assertEquals("$name: neighbour mismatches", 0, violations(assignment!!, cells))
      assertBorderPatternsAreBlankOutwards(name, assignment, cells)
    }
  }

  /**
   * The drawing that showed a mismatch on device: rust along the top row and bottom row for the first
   * ten columns, rust down the whole left column, ink everywhere else (including the right column).
   * Every tile sampled at x = 0 has a rust centre, so the bitmap's left column has to be rust, and
   * every tile sampled at x = 15 is ink, so its right column has to be ink.
   */
  @Test
  fun asymmetricBorderKeepsItsColoursOnTheEdges() {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (x in 0..9) {
      pixels[x] = rust
      pixels[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH + x] = rust
    }
    for (y in 0 until SOURCE_HEIGHT) pixels[y * SOURCE_WIDTH] = rust
    val cells = tiles(pixels)

    val (assignment, _) = solve(cells)
    assertNotNull("native solver found no tiling for the asymmetric border", assignment)
    assertEquals(0, violations(assignment!!, cells))

    val leftColumn = (0 until OUTPUT_ROWS).map { cells[assignment[it * OUTPUT_COLUMNS]].color }.toSet()
    val rightColumn = (0 until OUTPUT_ROWS)
      .map { cells[assignment[it * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]].color }
      .toSet()
    assertEquals("the drawing's left column is rust, so the bitmap's left column must be too", setOf(rust), leftColumn)
    assertEquals("the drawing's right column is ink, so the bitmap's right column must be too", setOf(ink), rightColumn)
    assertBorderPatternsAreBlankOutwards("asymmetric", assignment, cells)
  }

  /** The portfolio drives the native solver through the factory, one pooled solver per worker. */
  @Test
  fun portfolioRunsTheNativeSolver() {
    val cells = tiles(ringsDrawing())
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    assertTrue(model != 0L)
    try {
      val result = StrictSolverPortfolio(
        columns = OUTPUT_COLUMNS,
        rows = OUTPUT_ROWS,
        solverFactory = { seed, columns, rows -> NativeTileSolver(model, columns, rows, seed) },
      ).solve(
        seed = 1,
        workers = 4,
        deadlineNanos = System.nanoTime() + 60_000_000_000L,
        maxDecisions = 1_000_000,
      )
      val assignment = result.assignment
      assertNotNull("the portfolio found no tiling through the native solver", assignment)
      assertEquals(0, violations(assignment!!, cells))
      println("native portfolio: ${result.millis}ms, seeds=${result.entries.map { it.decisions }}")
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  /**
   * A cancelled solve has to give up rather than run to its deadline. This is what the live preview
   * depends on: every edit cancels the solve for the previous drawing.
   */
  @Test
  fun cancelledSolveGivesUpInsteadOfWaitingForItsDeadline() {
    val cells = tiles(ringsDrawing())
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    assertTrue(model != 0L)
    try {
      // One worker, aborted from the outside: the native solver polls its abort flag.
      val solver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val result = AtomicReference<IntArray?>()
      val startedAt = System.nanoTime()
      val worker = Thread { result.set(solver.solve(System.nanoTime() + 60_000_000_000L, 1_000_000)) }
      worker.isDaemon = true
      worker.start()
      Thread.sleep(60)
      solver.requestAbort()
      worker.join(5_000)
      val millis = (System.nanoTime() - startedAt) / 1_000_000
      assertFalse("the aborted solve did not return", worker.isAlive)
      assertNull("a cancelled solve must not return a tiling", result.get())
      assertTrue("cancelled solve took ${millis}ms", millis < 2_000)
      solver.close()

      // And the portfolio-level cancellation the editor actually uses.
      val portfolioStartedAt = System.nanoTime()
      val portfolioResult = StrictSolverPortfolio(
        columns = OUTPUT_COLUMNS,
        rows = OUTPUT_ROWS,
        solverFactory = { seed, columns, rows -> NativeTileSolver(model, columns, rows, seed) },
      ).solve(
        seed = 1,
        workers = 4,
        deadlineNanos = System.nanoTime() + 60_000_000_000L,
        shouldAbort = { true },
      )
      val portfolioMillis = (System.nanoTime() - portfolioStartedAt) / 1_000_000
      assertNull("a cancelled portfolio must not return a tiling", portfolioResult.assignment)
      assertTrue("cancelled portfolio took ${portfolioMillis}ms", portfolioMillis < 2_000)
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  /**
   * A pooled solver is reused across collapses, so a later solve must inherit nothing from an earlier
   * one -- not the domains, and not the result of a solve that failed.
   *
   * The pool hands the same native handle to consecutive collapses here, which is exactly what the app
   * does when the user keeps drawing.
   */
  @Test
  fun reusedSolverStartsClean() {
    val ringsCells = tiles(ringsDrawing())
    val flatCells = tiles(flatDrawing())
    val frameCells = tiles(frameDrawing())
    val models = listOf(ringsCells, flatCells, frameCells).map { cells ->
      WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    }
    assertTrue("models could not be created", models.all { it != 0L })
    val deadline = System.nanoTime() + 60_000_000_000L
    try {
      val (ringsModel, flatModel, frameModel) = models

      val firstSolver = NativeTileSolver(ringsModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val first = firstSolver.solve(deadline, 1_000_000)
      assertNotNull("the first collapse on a fresh solver must succeed", first)
      assertEquals(0, violations(first!!, ringsCells))
      firstSolver.close()

      // Same pooled handle, different drawing, flat tile set (nine patterns, no decisions to make).
      val secondSolver = NativeTileSolver(flatModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val second = secondSolver.solve(deadline, 1_000_000)
      assertNotNull("the second collapse on a reused solver must succeed", second)
      assertEquals(0, violations(second!!, flatCells))
      assertBorderPatternsAreBlankOutwards("reused-flat", second, flatCells)
      secondSolver.close()

      // And a drawing with no tiling must report exactly that, not a leftover tiling.
      val thirdSolver = NativeTileSolver(frameModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      assertNull("a reused solver must not hand back a stale tiling", thirdSolver.solve(deadline, 1_000_000))
      thirdSolver.close()

      // ...and the handle still works afterwards.
      val fourthSolver = NativeTileSolver(ringsModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 5)
      val fourth = fourthSolver.solve(deadline, 1_000_000)
      assertNotNull("the pooled solver must still solve after a failed collapse", fourth)
      assertEquals(0, violations(fourth!!, ringsCells))
      fourthSolver.close()
    } finally {
      for (model in models) WfcNative.modelDestroy(model)
    }
  }
  // --- helpers ------------------------------------------------------------------------------

  private fun assertBorderPatternsAreBlankOutwards(
    label: String,
    assignment: IntArray,
    cells: List<BitmapWaveFunctionCell>,
  ) {
    for (x in 0 until OUTPUT_COLUMNS) {
      assertEquals(
        "$label: top row $x is not a drawing-edge pattern",
        null,
        cells[assignment[x]].kernelColor(Direction2d8.UP),
      )
      assertEquals(
        "$label: bottom row $x is not a drawing-edge pattern",
        null,
        cells[assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x]].kernelColor(Direction2d8.DOWN),
      )
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertEquals(
        "$label: left column $y is not a drawing-edge pattern",
        null,
        cells[assignment[y * OUTPUT_COLUMNS]].kernelColor(Direction2d8.LEFT),
      )
      assertEquals(
        "$label: right column $y is not a drawing-edge pattern",
        null,
        cells[assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]].kernelColor(Direction2d8.RIGHT),
      )
    }
  }

  private fun violations(assignment: IntArray, cells: List<BitmapWaveFunctionCell>): Int {
    var bad = 0
    for (y in 0 until OUTPUT_ROWS) {
      for (x in 0 until OUTPUT_COLUMNS) {
        val here = cells[assignment[y * OUTPUT_COLUMNS + x]]
        for (direction in Direction2d8.entries) {
          val nx = x + direction.dx
          val ny = y + direction.dy
          if (nx < 0 || ny < 0 || nx >= OUTPUT_COLUMNS || ny >= OUTPUT_ROWS) continue
          val there = cells[assignment[ny * OUTPUT_COLUMNS + nx]]
          if (here.kernelColor(direction) != there.color ||
            there.kernelColor(direction.opposite()) != here.color
          ) {
            bad++
          }
        }
      }
    }
    return bad
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
    const val OUTPUT_COLUMNS = 76
    const val OUTPUT_ROWS = 135
  }
}
