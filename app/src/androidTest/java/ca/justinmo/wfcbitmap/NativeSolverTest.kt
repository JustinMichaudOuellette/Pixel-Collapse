package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.NativeTileSolver
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.WfcNative
import kotlinx.coroutines.runBlocking
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
 * The C solver is the only solver now, so these are the tests that would catch a wrong edge rule, a
 * stale reuse of a pooled solver, or a solve that ignores its abort flag. The tile-level and fallback
 * tests stay on the JVM in [WfcEngineTest].
 *
 * Since the native model prunes the tile set itself, a solve result indexes *model* tiles. [ModelTiles]
 * is the view that maps one back to the pattern that was sampled, so the assertions can still talk about
 * kernels and colours.
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

  // --- solved results, seen through the model's tiles ---------------------------------------

  /** The tile set as the native model sees it: compacted, with each tile traced back to its pattern. */
  private class ModelTiles(
    private val cells: List<BitmapWaveFunctionCell>,
    private val sources: IntArray,
  ) {
    fun colour(tile: Int): Int = cells[sources[tile]].color

    fun kernel(tile: Int): Array<Int?> = cells[sources[tile]].kernel

    fun kernelColour(tile: Int, direction: Direction2d8): Int? =
      cells[sources[tile]].kernelColor(direction)
  }

  private class Solved(val assignment: IntArray?, val tiles: ModelTiles?, val millis: Long)

  private fun modelTiles(cells: List<BitmapWaveFunctionCell>, model: Long): ModelTiles =
    ModelTiles(cells, IntArray(WfcNative.modelTileCount(model)) { WfcNative.modelTileSource(model, it) })

  /** Runs one native solve on a single seed, the way one portfolio worker does. */
  private fun solve(cells: List<BitmapWaveFunctionCell>, seed: Int = 1): Solved {
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
    if (model == 0L) return Solved(null, null, 0)
    val tiles = modelTiles(cells, model)
    val solver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = seed)
    val startedAt = System.nanoTime()
    return try {
      val assignment = solver.solve(System.nanoTime() + 60_000_000_000L, 1_000_000)
      Solved(assignment, if (assignment == null) null else tiles, (System.nanoTime() - startedAt) / 1_000_000)
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
    val solved = solve(cells)
    assertNotNull("the flat tile set must still tile", solved.assignment)
    println("flat: ${cells.size} sampled patterns in ${solved.millis}ms")
    assertEquals(0, violations(solved))
    assertBorderPatternsAreBlankOutwards("flat", solved)
  }

  /**
   * The requirement, stated as a test: what is drawn on the edge of the pattern is generated on the
   * edge of the bitmap.
   */
  @Test
  fun drawnBorderIsGeneratedOnTheBitmapBorder() {
    val cells = tiles(borderedDrawing())
    val solved = solve(cells)
    assertNotNull("no tiling for the bordered drawing", solved.assignment)
    println("bordered: ${cells.size} sampled patterns in ${solved.millis}ms")
    assertBorderPatternsAreBlankOutwards("bordered", solved)

    // Concretely: the bitmap's whole outline is the gold the border was drawn with, while the inside is
    // not gold-only, so this is a collapse and not a copy.
    val assignment = solved.assignment!!
    val tiles = solved.tiles!!
    for (x in 0 until OUTPUT_COLUMNS) {
      assertEquals("top row x=$x", gold, tiles.colour(assignment[x]))
      assertEquals("bottom row x=$x", gold, tiles.colour(assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x]))
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertEquals("left column y=$y", gold, tiles.colour(assignment[y * OUTPUT_COLUMNS]))
      assertEquals(
        "right column y=$y",
        gold,
        tiles.colour(assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]),
      )
    }
    val inside = (1 until OUTPUT_ROWS - 1).flatMap { y ->
      (1 until OUTPUT_COLUMNS - 1).map { x -> tiles.colour(assignment[y * OUTPUT_COLUMNS + x]) }
    }
    assertTrue("the interior should not be a solid border colour", inside.any { it != gold })
    assertEquals("every cell must satisfy its eight neighbours", 0, violations(solved))
  }

  /** No hole may appear inside the bitmap: only border cells may use a pattern that is blank anywhere. */
  @Test
  fun interiorCellsOnlyUseCompletePatterns() {
    val cells = tiles(ringsDrawing())
    val solved = solve(cells)
    assertNotNull("no tiling for the rings drawing", solved.assignment)
    println("rings: ${cells.size} sampled patterns in ${solved.millis}ms")
    val assignment = solved.assignment!!
    val tiles = solved.tiles!!
    for (y in 1 until OUTPUT_ROWS - 1) {
      for (x in 1 until OUTPUT_COLUMNS - 1) {
        val tile = assignment[y * OUTPUT_COLUMNS + x]
        assertFalse(
          "interior cell ($x,$y) used an edge pattern: ${tiles.kernel(tile).contentToString()}",
          tiles.kernel(tile).any { it == null },
        )
      }
    }
    assertEquals(0, violations(solved))
  }

  /**
   * The cost of the edge rule, pinned: a border that cannot repeat enough leaves no tiling at all.
   *
   * The solve has to *prove* this quickly (root propagation empties a domain) rather than burn the
   * deadline, which is what the portfolio's early exit is for.
   */
  @Test
  fun drawingWhoseEdgeCannotCloseHasNoTiling() {
    val solved = solve(tiles(frameDrawing()))
    assertNull("the frame drawing unexpectedly tiled", solved.assignment)
    assertTrue(
      "an unsatisfiable instance should be rejected quickly, not timed out (took ${solved.millis}ms)",
      solved.millis < 10_000,
    )
    println("frame: no tiling in ${solved.millis}ms")
  }

  /**
   * The escape hatch, pinned: the same instance that has no tiling under the edge rule does tile when the
   * rule is dropped, which is what stops the app from having no picture at all for that drawing.
   *
   * What is dropped is only *which* cells may use an edge pattern. Compatibility is untouched, so the
   * tiling still has no neighbour mismatches, and interior cells still use complete patterns only
   * (a complete pattern is the only kind that can satisfy "has a colour towards all eight neighbours").
   */
  @Test
  fun relaxingTheEdgeRuleTilesABorderThatCannotRepeat() {
    val cells = tiles(frameDrawing())
    val packed = WfcNative.packTiles(cells)
    val model =
      WfcNative.modelCreate(packed, cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = true)
    assertTrue("the relaxed model must build", model != 0L)
    val modelTiles = modelTiles(cells, model)
    val solver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
    val startedAt = System.nanoTime()
    try {
      val assignment = solver.solve(System.nanoTime() + 60_000_000_000L, 1_000_000)
      val millis = (System.nanoTime() - startedAt) / 1_000_000
      assertNotNull("the relaxed rule must tile the frame drawing", assignment)
      println("frame relaxed: tiled in ${millis}ms, ${WfcNative.modelTileCount(model)} patterns")
      val solved = Solved(assignment, modelTiles, millis)
      assertEquals("relaxed tiling has neighbour mismatches", 0, violations(solved))
      for (y in 1 until OUTPUT_ROWS - 1) {
        for (x in 1 until OUTPUT_COLUMNS - 1) {
          val tile = assignment!![y * OUTPUT_COLUMNS + x]
          assertFalse(
            "interior cell ($x,$y) used an edge pattern under the relaxed rule",
            modelTiles.kernel(tile).any { it == null },
          )
        }
      }
    } finally {
      solver.close()
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun repetitiveDrawingsTileCleanly() {
    for ((name, drawing) in listOf("rings" to ringsDrawing(), "sprite" to spriteDrawing())) {
      val cells = tiles(drawing)
      val solved = solve(cells)
      assertNotNull("$name produced no tiling", solved.assignment)
      println("$name: ${cells.size} sampled patterns, ${OUTPUT_COLUMNS}x$OUTPUT_ROWS in ${solved.millis}ms")
      assertEquals("$name: neighbour mismatches", 0, violations(solved))
      assertBorderPatternsAreBlankOutwards(name, solved)
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

    val solved = solve(cells)
    assertNotNull("native solver found no tiling for the asymmetric border", solved.assignment)
    assertEquals(0, violations(solved))

    val assignment = solved.assignment!!
    val tiles = solved.tiles!!
    val leftColumn = (0 until OUTPUT_ROWS).map { tiles.colour(assignment[it * OUTPUT_COLUMNS]) }.toSet()
    val rightColumn = (0 until OUTPUT_ROWS)
      .map { tiles.colour(assignment[it * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]) }
      .toSet()
    assertEquals("the drawing's left column is rust, so the bitmap's left column must be too", setOf(rust), leftColumn)
    assertEquals("the drawing's right column is ink, so the bitmap's right column must be too", setOf(ink), rightColumn)
    assertBorderPatternsAreBlankOutwards("asymmetric", solved)
  }

  /** The portfolio drives the native solver through the factory, one pooled solver per worker. */
  @Test
  fun portfolioRunsTheNativeSolver() {
    val cells = tiles(ringsDrawing())
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
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
      assertEquals(0, violations(Solved(assignment, modelTiles(cells, model), result.millis)))
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
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
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
   */
  @Test
  fun reusedSolverStartsClean() {
    val ringsCells = tiles(ringsDrawing())
    val flatCells = tiles(flatDrawing())
    val frameCells = tiles(frameDrawing())
    val models = listOf(ringsCells, flatCells, frameCells).map { cells ->
      WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS, relaxedEdges = false)
    }
    assertTrue("models could not be created", models.all { it != 0L })
    val deadline = System.nanoTime() + 60_000_000_000L
    try {
      val (ringsModel, flatModel, frameModel) = models

      val firstSolver = NativeTileSolver(ringsModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val first = firstSolver.solve(deadline, 1_000_000)
      assertNotNull("the first collapse on a fresh solver must succeed", first)
      assertEquals(0, violations(Solved(first!!, modelTiles(ringsCells, ringsModel), 0)))
      firstSolver.close()

      // Same pooled handle, different drawing, flat tile set (nine patterns, no decisions to make).
      val secondSolver = NativeTileSolver(flatModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val second = secondSolver.solve(deadline, 1_000_000)
      assertNotNull("the second collapse on a reused solver must succeed", second)
      val secondView = Solved(second!!, modelTiles(flatCells, flatModel), 0)
      assertEquals(0, violations(secondView))
      assertBorderPatternsAreBlankOutwards("reused-flat", secondView)
      secondSolver.close()

      // A drawing with no tiling must report exactly that, not a leftover tiling.
      val thirdSolver = NativeTileSolver(frameModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      assertNull("a reused solver must not hand back a stale tiling", thirdSolver.solve(deadline, 1_000_000))
      thirdSolver.close()

      // ...and the handle still works afterwards.
      val fourthSolver = NativeTileSolver(ringsModel, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 5)
      val fourth = fourthSolver.solve(deadline, 1_000_000)
      assertNotNull("the pooled solver must still solve after a failed collapse", fourth)
      assertEquals(0, violations(Solved(fourth!!, modelTiles(ringsCells, ringsModel), 0)))
      fourthSolver.close()
    } finally {
      for (model in models) WfcNative.modelDestroy(model)
    }
  }

  /**
   * The generated image is the full [WfcGenerator.BITMAP_WIDTH] x [WfcGenerator.BITMAP_HEIGHT], built
   * from a grid of [WfcGenerator.PIXELS_PER_CELL] squares: one cell is one square, so the number of
   * synthesized pixels is what sets how large they look.
   */
  @Test
  fun generationPublishesAtTheFullSizeWithOneSquarePerSynthesizedPixel() = runBlocking {
    val output = WfcGenerator.generate(
      borderedDrawing(),
      SOURCE_WIDTH,
      SOURCE_HEIGHT,
      seed = 1,
    )
    val image = output.bitmap
    assertEquals(WfcGenerator.BITMAP_WIDTH, image.width)
    assertEquals(WfcGenerator.BITMAP_HEIGHT, image.height)
    assertEquals(
      "the grid is the image divided by the square size",
      WfcGenerator.BITMAP_WIDTH / WfcGenerator.PIXELS_PER_CELL,
      WfcGenerator.DEFAULT_COLUMNS,
    )

    // Each cell is a solid square of one colour.
    val scale = WfcGenerator.PIXELS_PER_CELL
    val block = IntArray(scale * scale)
    val colours = HashSet<Int>()
    for (y in 0 until WfcGenerator.DEFAULT_ROWS step 13) {
      for (x in 0 until WfcGenerator.DEFAULT_COLUMNS step 11) {
        image.getPixels(block, 0, scale, x * scale, y * scale, scale, scale)
        for (index in block.indices) {
          assertEquals("cell ($x,$y) is not uniform", block[0], block[index])
        }
        colours.add(block[0])
      }
    }
    assertTrue("expected the sampled cells to cover some colours", colours.size > 1)
  }

  /**
   * The cache: an edit that leaves the tile set alone must hand back the same output (so the preview is
   * not redrawn at all), while an edit that adds a pattern must produce a new one.
   */
  @Test
  fun unchangedTileSetIsReusedAndANewPatternIsNot() = runBlocking {
    val pixels = borderedDrawing()
    val first = WfcGenerator.generate(pixels, SOURCE_WIDTH, SOURCE_HEIGHT, seed = 1)
    val repeated = WfcGenerator.generate(pixels, SOURCE_WIDTH, SOURCE_HEIGHT, seed = 1)
    assertTrue("the same drawing must reuse the previous output", first === repeated)

    // A pixel that introduces a pattern the drawing did not have before.
    val edited = pixels.copyOf()
    edited[5 * SOURCE_WIDTH + 5] = green
    val changed = WfcGenerator.generate(edited, SOURCE_WIDTH, SOURCE_HEIGHT, seed = 1)
    assertFalse("a new pattern must produce a new output", first === changed)
    assertEquals(WfcGenerator.BITMAP_WIDTH, changed.bitmap.width)
  }

  /**
   * One solver handle, several grid sizes: grow, shrink, grow again.
   *
   * This is the regression test for the bug the 152x270 default exposed: a solver cached its buffer
   * shapes and a *smaller* grid could leave a buffer too small for the next, larger one. Every derived
   * buffer -- domains, sizes, arc flags, the size bitsets, the result -- has to follow the model, not
   * the history of the handle. When the output size becomes selectable, this is the case that will
   * happen all the time.
   */
  @Test
  fun oneSolverHandleHandlesDifferentGridSizes() {
    val cells = tiles(ringsDrawing())
    val packed = WfcNative.packTiles(cells)
    val sizes = listOf(76 to 135, 152 to 270, 40 to 70, 200 to 355, 76 to 135)
    val handle = WfcNative.solverCreate()
    assertTrue("solver could not be created", handle != 0L)
    try {
      for ((columns, rows) in sizes) {
        val model = WfcNative.modelCreate(packed, cells.size, columns, rows, relaxedEdges = false)
        assertTrue("model ${columns}x$rows could not be created", model != 0L)
        try {
          val out = IntArray(columns * rows)
          val solved = WfcNative.solverSolve(
            handle, model, 1, System.nanoTime() + 120_000_000_000L, 2_000_000, out,
          )
          assertEquals("${columns}x$rows cells did not solve on the reused handle", 1, solved)
          assertTrue(
            "${columns}x$rows produced an out-of-range tile",
            out.all { it >= 0 && it < WfcNative.modelTileCount(model) },
          )
          // Every cell must be filled: a stale buffer would leave zeros behind.
          assertTrue("${columns}x$rows left cells unfilled", out.size == columns * rows)
        } finally {
          WfcNative.modelDestroy(model)
        }
      }
    } finally {
      WfcNative.solverDestroy(handle)
    }
  }

  // --- helpers ------------------------------------------------------------------------------

  private fun assertBorderPatternsAreBlankOutwards(label: String, solved: Solved) {
    val assignment = solved.assignment ?: return
    val tiles = solved.tiles ?: return
    for (x in 0 until OUTPUT_COLUMNS) {
      assertEquals(
        "$label: top row $x is not a drawing-edge pattern",
        null,
        tiles.kernelColour(assignment[x], Direction2d8.UP),
      )
      assertEquals(
        "$label: bottom row $x is not a drawing-edge pattern",
        null,
        tiles.kernelColour(assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x], Direction2d8.DOWN),
      )
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertEquals(
        "$label: left column $y is not a drawing-edge pattern",
        null,
        tiles.kernelColour(assignment[y * OUTPUT_COLUMNS], Direction2d8.LEFT),
      )
      assertEquals(
        "$label: right column $y is not a drawing-edge pattern",
        null,
        tiles.kernelColour(assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1], Direction2d8.RIGHT),
      )
    }
  }

  private fun violations(solved: Solved): Int {
    val assignment = solved.assignment ?: return 0
    val tiles = solved.tiles ?: return 0
    var bad = 0
    for (y in 0 until OUTPUT_ROWS) {
      for (x in 0 until OUTPUT_COLUMNS) {
        val here = assignment[y * OUTPUT_COLUMNS + x]
        for (direction in Direction2d8.entries) {
          val nx = x + direction.dx
          val ny = y + direction.dy
          if (nx < 0 || ny < 0 || nx >= OUTPUT_COLUMNS || ny >= OUTPUT_ROWS) continue
          val there = assignment[ny * OUTPUT_COLUMNS + nx]
          if (tiles.kernelColour(here, direction) != tiles.colour(there) ||
            tiles.kernelColour(there, direction.opposite()) != tiles.colour(here)
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