package ca.justinmo.wfcbitmap

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.previewGrid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview step decides the solver grid, and the generated bitmap is that grid times its cell size.
 */
@RunWith(AndroidJUnit4::class)
class PreviewSizeTest {

  @Test
  fun theGeneratedBitmapIsThePreviewGridTimesItsCellSize() = runBlocking {
    for (preview in PreviewScale.entries) {
      val grid = previewGrid(OutputAspect.Square, preview)
      val output = WfcGenerator.generate(
        pixels = drawing(),
        sourceWidth = SIDE,
        sourceHeight = SIDE,
        seed = 7,
        columns = grid.columns,
        rows = grid.rows,
        pixelsPerCell = grid.pixelsPerCell,
      )
      assertEquals("${preview.label} width", grid.columns * grid.pixelsPerCell, output.bitmap.width)
      assertEquals("${preview.label} height", grid.rows * grid.pixelsPerCell, output.bitmap.height)
      for (y in 0 until grid.rows) {
        for (x in 0 until grid.columns) {
          val corner = output.bitmap.getPixel(
            x * grid.pixelsPerCell,
            y * grid.pixelsPerCell,
          )
          assertTrue(
            "cell ($x, $y) at ${preview.label} is a drawn colour",
            corner in DRAWN,
          )
        }
      }
    }
  }

  /** A small drawing of two colours in vertical stripes, which is a tile set that fits itself. */
  private fun drawing(): IntArray = IntArray(SIDE * SIDE) { index ->
    if ((index % SIDE) % 2 == 0) Color.BLACK else Color.WHITE
  }

  private companion object {
    const val SIDE = 6
    val DRAWN = setOf(Color.BLACK, Color.WHITE)
  }
}
