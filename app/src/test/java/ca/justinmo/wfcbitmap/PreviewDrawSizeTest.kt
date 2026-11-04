package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import ca.justinmo.wfcbitmap.ui.previewDrawSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big the generated picture is drawn in the preview column.
 *
 * It has to be pixel perfect: every picture pixel is drawn as a whole number of screen pixels, so the
 * picture is magnified by a whole number or sampled down by a whole divisor. Fitting it to the column
 * with a fraction -- 1.107 for a 540-pixel picture in a 598-pixel column -- draws some pixels one screen
 * pixel wide and some two, which is the stretch that a pixel picture must not have. The column's leftover
 * is padding around the picture instead.
 */
class PreviewDrawSizeTest {

  /** The preview column of a phone in landscape, in device pixels: the case this has to look right on. */
  private val columnWidth = 598
  private val columnHeight = 1080

  @Test
  fun everyPreviewSizeIsDrawnAtAWholeNumberOfScreenPixelsPerPixel() {
    for (preview in PreviewScale.entries) {
      val (bitmapWidth, bitmapHeight) = previewPixelSize(OutputAspect.Portrait9x16, preview)
      val (width, height) = previewDrawSize(bitmapWidth, bitmapHeight, columnWidth, columnHeight)
      assertTrue(
        "${preview.label}: ${bitmapWidth}x$bitmapHeight drawn as ${width}x$height is not a whole " +
          "magnification or a whole division",
        width % bitmapWidth == 0 || bitmapWidth % width == 0,
      )
      // Never stretched past the column, and never distorted: the picture keeps its shape exactly.
      assertTrue("${preview.label} fits", width <= columnWidth && height <= columnHeight)
      assertEquals(
        "${preview.label} keeps its proportions",
        bitmapWidth.toDouble() / bitmapHeight,
        width.toDouble() / height,
        0.0,
      )
    }
  }

  @Test
  fun aSmallerPreviewIsMagnifiedByAWholeNumber() {
    // 1x is 270x480: two screen pixels per picture pixel fits the column, three does not.
    val (width, height) = previewDrawSize(270, 480, columnWidth, columnHeight)
    assertEquals(540, width)
    assertEquals(960, height)
  }

  @Test
  fun theLeftoverColumnIsPaddingNotStretch() {
    val (width, height) = previewDrawSize(540, 960, columnWidth, columnHeight)
    assertEquals(540, width)
    assertEquals(960, height)
    // The column is wider and taller than the picture: that difference is the padding.
    assertTrue("horizontal padding", columnWidth - width > 0)
    assertTrue("vertical padding", columnHeight - height > 0)
  }

  @Test
  fun aPictureTooBigForTheColumnIsSampledDownByAWholeStep() {
    // 1216x2160 in a 598x1080 column: a third of it fits, a half does not, so every third picture
    // pixel stands for one screen pixel.
    val (width, height) = previewDrawSize(1216, 2160, columnWidth, columnHeight)
    assertEquals(1216 / 3, width)
    assertEquals(2160 / 3, height)
    assertTrue("it fits: ${width}x$height", width <= columnWidth && height <= columnHeight)
  }

  @Test
  fun pinchZoomStepsByWholeMultiples() {
    // Zooming must not reintroduce the fraction the rest of this test file is about.
    assertEquals(540 to 960, previewDrawSize(540, 960, columnWidth, columnHeight, zoom = 1f))
    assertEquals(1080 to 1920, previewDrawSize(540, 960, columnWidth, columnHeight, zoom = 2f))
    assertEquals(1080 to 1920, previewDrawSize(540, 960, columnWidth, columnHeight, zoom = 1.6f))
    assertEquals(1620 to 2880, previewDrawSize(540, 960, columnWidth, columnHeight, zoom = 3f))
  }

  @Test
  fun anEmptyColumnDrawsNothing() {
    assertEquals(0 to 0, previewDrawSize(304, 540, 0, 0))
    assertEquals(0 to 0, previewDrawSize(0, 0, columnWidth, columnHeight))
  }
}
