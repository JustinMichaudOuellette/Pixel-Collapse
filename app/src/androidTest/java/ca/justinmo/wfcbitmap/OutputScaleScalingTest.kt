package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.output.ImageSaver
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.savedPixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The save size, as bitmaps rather than as arithmetic.
 *
 * The menu's numbers come from [savedPixelSize]; this checks the thing those numbers are
 * promises about -- that [ImageSaver.atSize] actually makes a picture that size, whatever the preview was
 * generated at. The factors between any preview size and any save size are whole numbers, so the file is the
 * same picture with more or fewer pixels each, and not a smeared one.
 */
@RunWith(AndroidJUnit4::class)
class OutputScaleScalingTest {

  @Test
  fun oneTimesIsTheBitmapItself() {
    val source = pattern()
    try {
      // Not a copy: at 1x the save size is the picture exactly, so there is nothing to resample or allocate.
      assertSame("1x hands back the same bitmap", source, ImageSaver.atSize(source, 304, 540))
      assertEquals(304, source.width)
      assertEquals(540, source.height)
    } finally {
      source.recycle()
    }
  }

  @Test
  fun everySizeTheMenuShowsIsTheSizeTheFileComesOut() {
    val source = pattern()
    try {
      for (aspect in OutputAspect.entries) {
        for (scale in OutputScale.entries) {
          val (width, height) = savedPixelSize(aspect, scale, PreviewScale.One)
          if (width.toLong() * height > MAX_TEST_PIXELS) continue
          val written = ImageSaver.atSize(source, width, height)
          try {
            assertEquals("${aspect.label} at ${scale.label} width", width, written.width)
            assertEquals("${aspect.label} at ${scale.label} height", height, written.height)
            if (written !== source) {
              // Nearest neighbour keeps the colours: a smooth filter would average the two-tone pattern into
              // greys, which is exactly what pixel art must not become.
              for (colour in coloursOf(written)) {
                assertTrue(
                  "a resampled pixel is one of the drawn ones: ${Integer.toHexString(colour)}",
                  colour in DRAWN,
                )
              }
            }
          } finally {
            if (written !== source) written.recycle()
          }
        }
      }
    } finally {
      source.recycle()
    }
  }

  @Test
  fun theSixteenIsEnlargedOrReportedAsTooBig() {
    val source = pattern()
    val (width, height) = savedPixelSize(
      OutputAspect.Portrait9x16,
      OutputScale.Sixteen,
      PreviewScale.One,
    )
    val outcome = runCatching { ImageSaver.atSize(source, width, height) }
    outcome.onSuccess { big ->
      try {
        println("SIXTEEN fits: ${big.width}x${big.height} ${big.byteCount} bytes")
        assertEquals(8640, big.width)
        assertEquals(15360, big.height)
      } finally {
        big.recycle()
      }
    }.onFailure { cause ->
      // 4864x8640 is 168 MB of ARGB. A device that cannot spare it fails in the scaler, and the editor
      // turns that into "Not enough memory for that size" (see MainActivity.tooBigMessage).
      println("SIXTEEN does not fit: $cause")
      assertTrue("only a failed allocation is allowed here: $cause", cause is OutOfMemoryError)
    }
    source.recycle()
  }

  /** A picture the size of the one the solver generates, in two colours so a resampler is easy to spot. */
  private fun pattern(): Bitmap {
    val bitmap = Bitmap.createBitmap(
      WfcGenerator.BITMAP_WIDTH,
      WfcGenerator.BITMAP_HEIGHT,
      Bitmap.Config.ARGB_8888,
    )
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
      }
    }
    return bitmap
  }

  private fun coloursOf(bitmap: Bitmap): Set<Int> {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels.toSet()
  }

  private companion object {
    val DRAWN = setOf(Color.BLACK, Color.WHITE)

    /** Skips the biggest sizes: a per-pixel colour check on a 42-megapixel bitmap is a waste of a device. */
    const val MAX_TEST_PIXELS = 4_000_000L
  }
}