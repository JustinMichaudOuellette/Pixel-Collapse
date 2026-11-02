package ca.justinmo.wfcbitmap

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview size changes how many pixels a cell is drawn with, and nothing else.
 *
 * The picture on screen is sized by this setting -- which is why it is separate from the save size, a
 * decision about a file -- but what the picture *is* comes from the solver grid, so a bigger preview must
 * show the same patterns as a smaller one, only drawn larger.
 */
@RunWith(AndroidJUnit4::class)
class PreviewSizeTest {

  @Test
  fun theGeneratedBitmapIsTheGridTimesThePixelsPerCell() = runBlocking {
    for (preview in PreviewScale.entries) {
      val output = generate(preview)
      assertEquals("${preview.label} width", COLUMNS * preview.pixelsPerCell, output.bitmap.width)
      assertEquals("${preview.label} height", ROWS * preview.pixelsPerCell, output.bitmap.height)
      // Every cell is one colour of the drawing, however many pixels it is drawn with.
      for (y in 0 until ROWS) {
        for (x in 0 until COLUMNS) {
          val corner = output.bitmap.getPixel(x * preview.pixelsPerCell, y * preview.pixelsPerCell)
          assertTrue("cell ($x, $y) at ${preview.label} is a drawn colour", corner in DRAWN)
        }
      }
    }
  }

  @Test
  fun aBiggerPreviewDoesNotChangeThePicture() = runBlocking {
    // The same drawing and seed at two preview sizes: every cell has to hold the colour the small picture
    // gives it, because the grid the solver filled is the same one in both.
    val small = generate(PreviewScale.Half)
    val large = generate(PreviewScale.Two)
    val smallScale = PreviewScale.Half.pixelsPerCell
    val largeScale = PreviewScale.Two.pixelsPerCell
    for (y in 0 until ROWS) {
      for (x in 0 until COLUMNS) {
        val atSmall = small.bitmap.getPixel(x * smallScale, y * smallScale)
        assertEquals(
          "cell ($x, $y) moved when the preview grew",
          atSmall,
          large.bitmap.getPixel(x * largeScale, y * largeScale),
        )
        // And the block is solid: a cell is drawn as one colour, not as a resampled edge.
        for (dy in 0 until largeScale) {
          for (dx in 0 until largeScale) {
            assertEquals(
              "cell ($x, $y) is solid at ${PreviewScale.Two.label}",
              atSmall,
              large.bitmap.getPixel(x * largeScale + dx, y * largeScale + dy),
            )
          }
        }
      }
    }
  }

  private suspend fun generate(preview: PreviewScale) = WfcGenerator.generate(
    pixels = drawing(),
    sourceWidth = SIDE,
    sourceHeight = SIDE,
    seed = 7,
    columns = COLUMNS,
    rows = ROWS,
    pixelsPerCell = preview.pixelsPerCell,
  )

  /** A small drawing of two colours in vertical stripes, which is a tile set that fits itself. */
  private fun drawing(): IntArray = IntArray(SIDE * SIDE) { index ->
    if ((index % SIDE) % 2 == 0) Color.BLACK else Color.WHITE
  }

  private companion object {
    const val SIDE = 6
    const val COLUMNS = 12
    const val ROWS = 9
    val DRAWN = setOf(Color.BLACK, Color.WHITE)
  }
}
