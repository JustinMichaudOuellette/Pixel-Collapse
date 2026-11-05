package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import ca.justinmo.wfcbitmap.editor.DitherMode
import ca.justinmo.wfcbitmap.editor.MAX_GRID_SIZE
import ca.justinmo.wfcbitmap.editor.MIN_GRID_SIZE
import ca.justinmo.wfcbitmap.editor.PhotoPatternPipeline
import ca.justinmo.wfcbitmap.editor.PixelGridState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera pipeline's pure half: sizing, palette mapping, dither and undoable replacement. */
class PhotoPatternTest {

  private val red = 0xFFFF3B30.toInt()
  private val green = 0xFF34C759.toInt()
  private val blue = 0xFF007AFF.toInt()
  private val white = 0xFFFFFFFF.toInt()
  private val black = 0xFF000000.toInt()
  private val palette = intArrayOf(red, green, blue, white, black)

  @Test
  fun theTargetSizeFollowsTheShapeAndKernelCells() {
    val (width, height) = PhotoPatternPipeline.targetSize(
      columns = 96,
      aspect = 9f / 16f,
      cellSize = 1,
    )
    assertEquals(96, width)
    // 96 / 0.5625 = 170.6..., rounded to a whole cell.
    assertEquals(171, height)

    val grouped = PhotoPatternPipeline.targetSize(
      columns = 96,
      aspect = 9f / 16f,
      cellSize = 2,
    )
    assertEquals(0, grouped.first % 2)
    assertEquals(0, grouped.second % 2)
  }

  @Test
  fun theGridTheCameraToolOffersFitsInTheEditor() {
    // The slider's ends have to be sizes the editor can hold: a value the pipeline silently clamps would
    // be a slider that lies about the picture it is showing. The narrow end is near the editor's own
    // floor, so a photo can be reduced to the coarsest drawing the editor allows.
    val narrowest = PhotoPatternPipeline.targetSize(
      PhotoPatternPipeline.MIN_COLUMNS,
      aspect = 9f / 16f,
      cellSize = 1,
    )
    val widest = PhotoPatternPipeline.targetSize(
      PhotoPatternPipeline.MAX_COLUMNS,
      aspect = 9f / 16f,
      cellSize = 1,
    )
    assertEquals(PhotoPatternPipeline.MIN_COLUMNS, narrowest.first)
    assertEquals(PhotoPatternPipeline.MAX_COLUMNS, widest.first)
    assertTrue(narrowest.second >= MIN_GRID_SIZE)
    assertTrue(widest.second <= MAX_GRID_SIZE)
    assertTrue(PhotoPatternPipeline.MIN_COLUMNS >= MIN_GRID_SIZE)
    assertTrue(PhotoPatternPipeline.MAX_COLUMNS <= MAX_GRID_SIZE)
  }

  @Test
  fun posterizeOnlyUsesColoursFromTheSubset() {
    val pixels = gradient(32, 32)
    val subset = PhotoPatternPipeline.paletteSubset(pixels, palette, count = 3)
    assertEquals(3, subset.size)

    val mapped = PhotoPatternPipeline.posterize(pixels, subset)
    val allowed = subset.map { it and 0xFFFFFF }.toSet()
    assertTrue(mapped.all { (it and 0xFFFFFF) in allowed })
    assertTrue("a gradient should use more than one colour", mapped.distinct().size > 1)
  }

  @Test
  fun ditheringChangesAGradientAndStrengthZeroIsPlainPosterize() {
    val pixels = gradient(32, 32)
    val subset = PhotoPatternPipeline.paletteSubset(pixels, palette, count = 3)
    val plain = PhotoPatternPipeline.posterize(pixels, subset)

    val floyd = PhotoPatternPipeline.dither(
      pixels, 32, 32, subset, DitherMode.FloydSteinberg, 1f,
    )
    val bayer = PhotoPatternPipeline.dither(
      pixels, 32, 32, subset, DitherMode.Bayer4, 1f,
    )
    val off = PhotoPatternPipeline.dither(
      pixels, 32, 32, subset, DitherMode.FloydSteinberg, 0f,
    )

    assertTrue("Floyd-Steinberg changed the gradient", floyd.contentEquals(plain).not())
    assertTrue("Bayer changed the gradient", bayer.contentEquals(plain).not())
    assertTrue("strength 0 is plain posterize", off.contentEquals(plain))
  }

  @Test
  fun aCameraDrawingCanBeUndoneBackToTheOldDrawing() {
    val state = PixelGridState(
      palette = listOf(Color.Black, Color.White),
      initialColumns = 16,
      initialRows = 11,
    )
    val before = state.pixels.toList()
    assertFalse(state.canUndo())

    val photo = IntArray(10 * 10) { red }
    state.replaceDrawing(columns = 10, rows = 10, pixels = photo, cellSize = 1)

    assertEquals(10, state.columns)
    assertEquals(10, state.rows)
    assertTrue(state.pixels.all { it == Color(red) })
    assertTrue(state.canUndo())

    state.undo()

    assertEquals(16, state.columns)
    assertEquals(11, state.rows)
    assertEquals(before, state.pixels.toList())
  }

  @Test
  fun theColumnRangeIsWholeKernelCellsForEveryGrouping() {
    // The slider counts cells, and a grouped drawing is drawn in whole kernels: an end that the kernel
    // cannot divide would be rounded away, leaving slider steps that change nothing.
    // Every kernel the output menu offers: 3x3 to 24x24 patterns, i.e. cells of 1 to 8 pixels.
    for (cell in 1..8) {
      val range = PhotoPatternPipeline.columnRange(cell)
      assertEquals("cellSize $cell low end", 0, range.first % cell)
      assertEquals("cellSize $cell high end", 0, range.last % cell)
      assertEquals(
        "cellSize $cell low end survives the sizing",
        range.first,
        PhotoPatternPipeline.targetSize(range.first, 9f / 16f, cell).first,
      )
      assertEquals(
        "cellSize $cell high end survives the sizing",
        range.last,
        PhotoPatternPipeline.targetSize(range.last, 9f / 16f, cell).first,
      )
      assertTrue("cellSize $cell can hold a drawing", range.first >= MIN_GRID_SIZE * cell)
      assertTrue("cellSize $cell has room to slide", range.first < range.last)
      assertEquals(
        "cellSize $cell snaps to a whole kernel",
        range.first,
        PhotoPatternPipeline.nearestColumns(range.first - 100f, cell),
      )
      assertEquals(
        "cellSize $cell snaps to a whole kernel",
        range.last,
        PhotoPatternPipeline.nearestColumns(range.last + 100f, cell),
      )
    }
  }

  private fun gradient(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
    val x = index % width
    val y = index / width
    (0xFF shl 24) or
      ((x * 255 / (width - 1)) shl 16) or
      ((y * 255 / (height - 1)) shl 8) or
      ((x + y) * 255 / (width + height - 2))
  }
}
