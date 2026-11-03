package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the part of the pipeline that runs before the search: a drawing becomes a tile set, and the
 * fallback that is used when the drawing cannot tile.
 *
 * The search itself is C now, so it cannot be exercised on the JVM; the solver-level tests (edge rule
 * on a real tiling, unsatisfiable drawings, cancellation) live in
 * `androidTest/.../NativeSolverTest.kt`, where `libwfc.so` is loadable.
 *
 * The model is the *overlapping, bordered* one. Windows are sampled as the drawing lies, so the ones
 * that hang over an edge carry `null`s, and the solver only accepts a tile at a border cell when it is
 * blank in the direction that leaves the grid. The consequence these tests pin down is that pixels
 * drawn on the edge of the drawing can only be generated on the edge of the bitmap.
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

  /** A little creature with a solid floor. */
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

  private fun tiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

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
  fun flatDrawingYieldsTheNineBorderedWindows() {
    // One pixel value still yields nine bordered windows: the interior plus the four edges and four
    // corners, the latter carrying nulls outside the source. So "flat drawing" is not an error case.
    assertEquals(9, tiles(IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }).size)
  }

  // --- the edge rule, at the tile level -----------------------------------------------------

  /**
   * The rule the solver enforces, checked on the tile set itself: a pattern that is blank in a
   * direction was sampled on that edge of the drawing, so its centre is that edge's pixel, while a
   * complete pattern can only occupy an interior cell.
   */
  @Test
  fun edgePatternsAreBlankOutwardsAndInteriorPatternsAreComplete() {
    val cells = tiles(borderedMotifDrawing())

    // Every pattern sampled on the drawing's edge is blank towards it, and shows the drawn border
    // colour at its centre.
    val topEdge = cells.filter { it.kernelColor(Direction2d8.UP) == null }
    assertTrue("expected patterns blank upwards", topEdge.isNotEmpty())
    assertTrue(
      "a pattern blank upwards was sampled on the drawing's top row, whose pixels are gold",
      topEdge.all { it.color == gold },
    )

    // And the complete patterns are the interior ones (ink or the rust motif), never the border.
    val complete = cells.filter { cell -> cell.kernel.all { it != null } }
    assertTrue("expected complete patterns", complete.isNotEmpty())
    assertTrue("complete patterns are interior windows, so none is drawn border gold", complete.none { it.color == gold })

    // A direction is blank only where the drawing ends: the four corners are blank in two directions.
    val corners = cells.filter { it.kernel.any { pixel -> pixel == null } && it.kernel.count { p -> p == null } >= 3 }
    assertTrue("expected corner patterns to be blank in more than one direction", corners.isNotEmpty())
  }

  // --- no symmetry: the tile set is the drawing as it lies ------------------------------------

  /**
   * Every direction in a window reads the editor pixel that is one step in that direction.
   *
   * This is the invariant that keeps what you draw where you drew it, and the reason enumerating the
   * nine-cell groups needs no symmetry handling at all: a window is not canonicalised, rotated or
   * reflected, it is just the nine cells around one point. It is worth pinning because the packing
   * order (`kernel[slot]`, slot = x + 3y), `Direction2d8`'s (dx, dy) and the solver's
   * `WFC_DIR_SLOT` are three separate tables that have to agree; a reflection in any of them would make
   * every generated picture a mirror of the drawing, and nothing else in the suite would notice.
   */
  @Test
  fun everyDirectionReadsThePixelOnThatSideOfTheDrawing() {
    // Every pixel gets its own colour, so a wrong offset cannot match by coincidence.
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { index ->
      val x = index % SOURCE_WIDTH
      val y = index / SOURCE_WIDTH
      (0xFF shl 24) or (y shl 8) or (x shl 16) or (y * 31 + x)
    }
    val drawing = IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf())
    val centreX = 7
    val centreY = 5
    val window = BitmapWaveFunctionCell.windowAt(drawing, centreX, centreY)
    val tile = BitmapWaveFunctionCell(window.toTypedArray(), 1f)

    assertEquals("the centre is the cell itself", drawing[centreX, centreY], tile.color)
    for (direction in Direction2d8.entries) {
      assertEquals(
        "the window's $direction slot",
        drawing[centreX + direction.dx, centreY + direction.dy],
        tile.kernelColor(direction),
      )
    }

    // And a window on the drawing's edge is blank only where the drawing ends: at (0,0) that is up,
    // left and the three directions between them, and nowhere else.
    val corner = BitmapWaveFunctionCell(BitmapWaveFunctionCell.windowAt(drawing, 0, 0).toTypedArray(), 1f)
    for (direction in Direction2d8.entries) {
      val outside = direction.dx < 0 || direction.dy < 0
      if (outside) {
        assertNull("corner window must be blank towards $direction", corner.kernelColor(direction))
      } else {
        assertEquals(
          "corner window must read the pixel towards $direction",
          drawing[direction.dx, direction.dy],
          corner.kernelColor(direction),
        )
      }
    }
  }

  /**
   * A window and its mirror image are two tiles, not one folded tile.
   *
   * A left-right fold is the tempting "optimisation" here -- the tile set would halve -- and it would
   * silently make every generated picture mirror-symmetric, which is exactly what must not happen.
   */
  @Test
  fun aWindowAndItsMirrorImageStaySeparateTiles() {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    // Gold either side of the empty centre column: the windows centred left and right of it are mirror
    // images of each other, and the drawing contains both.
    pixels[5 * SOURCE_WIDTH + 5] = gold
    pixels[5 * SOURCE_WIDTH + 9] = gold
    val cells =
      BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

    val goldOnTheLeft = cells.filter { it.kernelColor(Direction2d8.LEFT) == gold }
    val goldOnTheRight = cells.filter { it.kernelColor(Direction2d8.RIGHT) == gold }
    assertTrue("expected a window with the motif on its left", goldOnTheLeft.isNotEmpty())
    assertTrue("expected a window with the motif on its right", goldOnTheRight.isNotEmpty())
    assertTrue(
      "a window must not carry the motif on both sides, which is what folding a mirror in would do",
      goldOnTheLeft.none { left -> goldOnTheRight.any { it === left } },
    )
    assertTrue(
      "the two mirror-image windows must both be tiles",
      cells.size >= 2,
    )
  }

  // --- no tiling is reported, not drawn ------------------------------------------------------

  /**
   * A drawing that cannot be tiled even with the edge rule dropped is a failure to report, and the message
   * it reports is what the preview shows in place of a picture -- so it has to say what the person can do.
   * The wording itself is pinned in `GenerationNoticeTest`; here the point is only that the failure has a
   * message at all, in both edge modes, rather than a picture that is not a tiling.
   */
  @Test
  fun aDrawingWithNoTilingExplainsItselfInBothEdgeModes() {
    for (wrap in listOf(false, true)) {
      val message = noTilingMessage(columns = OUTPUT_COLUMNS, rows = OUTPUT_ROWS, wrapEdges = wrap)
      assertTrue("wrap=$wrap: expected a message", message.isNotBlank())
      assertTrue(
        "wrap=$wrap: says something to do: $message",
        message.contains("Simplif", ignoreCase = true) || message.contains("Wrap edges", ignoreCase = true),
      )
    }
  }

  @Test
  fun aDrawingWithAPeriodicBorderIsExportedAsATileSetWithEdges() {
    // The sprite has a solid floor, so its border is one colour and it must still produce edge tiles.
    val cells = tiles(spriteDrawing())
    assertTrue(cells.size >= 4)
    assertTrue(cells.any { cell -> cell.kernelColor(Direction2d8.DOWN) == null })
  }

  // --- incremental extraction ---------------------------------------------------------------

  /**
   * The point of [WindowTally]: an edit only re-tallies the windows it touched, and the tile set it
   * produces must be exactly the one a full resample would. If this drifts, the live preview would show
   * a different picture depending on the order pixels were drawn in.
   */
  @Test
  fun incrementalSamplingMatchesAFullResample() {
    val tally = WindowTally()
    val pixels = borderedMotifDrawing()
    val first = tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

    // A few local edits: a run of single pixels, which is what drawing does.
    val edited = pixels.copyOf()
    for (step in 0 until 24) {
      val x = 2 + step % 12
      val y = 2 + (step * 7) % 7
      edited[y * SOURCE_WIDTH + x] = when (step % 3) {
        0 -> rust
        1 -> slate
        else -> gold
      }
      val incremental = tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, edited.copyOf()))
      val full = BitmapWaveFunctionCell.createCells(
        IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, edited.copyOf()),
      )
      assertEquals(
        "step ${step}: incremental tiles differ from a full resample",
        full.map { it.kernel.toList() to it.weight },
        incremental.map { it.kernel.toList() to it.weight },
      )
    }
    assertTrue("the drawing should still produce tiles", first.isNotEmpty())
  }

  /** Re-sampling an unchanged drawing must produce the identical tile set, so the result can be reused. */
  @Test
  fun resamplingAnUnchangedDrawingIsIdentical() {
    val tally = WindowTally()
    val pixels = ringsDrawing()
    val once = tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))
    val twice = tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))
    assertEquals(once.size, twice.size)
    for (i in once.indices) {
      assertEquals(once[i].kernel.toList(), twice[i].kernel.toList())
      assertEquals(once[i].weight, twice[i].weight, 0.0f)
    }
  }

  /** A window that hangs over the edge keeps its blanks, however the tally got there. */
  @Test
  fun incrementalSamplingKeepsEdgeWindowsBlank() {
    val tally = WindowTally()
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))
    pixels[5 * SOURCE_WIDTH + 5] = gold
    val cells = tally.sample(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))
    val full = BitmapWaveFunctionCell.createCells(
      IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()),
    )
    assertEquals(full.map { it.kernel.toList() }, cells.map { it.kernel.toList() })
    assertTrue("an edge window must still be blank outwards", cells.any { cell -> cell.kernel.any { it == null } })
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