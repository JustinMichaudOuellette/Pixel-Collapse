package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
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

  /**
   * A one-pixel frame around noise. Its vertical edge is 11 distinct colours with no repetition, so it
   * cannot be stretched around 135 rows: this drawing has no tiling under the edge rule. The device
   * test proves that; here it only supplies the fallback's worst case.
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

  @Test
  fun aDrawingWithAPeriodicBorderIsExportedAsATileSetWithEdges() {
    // The sprite has a solid floor, so its border is one colour and it must still produce edge tiles.
    val cells = tiles(spriteDrawing())
    assertTrue(cells.size >= 4)
    assertTrue(cells.any { cell -> cell.kernelColor(Direction2d8.DOWN) == null })
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
