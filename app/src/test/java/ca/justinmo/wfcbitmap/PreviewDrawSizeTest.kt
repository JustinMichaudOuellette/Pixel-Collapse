package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big the generated picture is drawn in the preview column.
 *
 * The column is always filled. A smaller preview is scaled up, one source pixel to a block of them, which is
 * what the preview size is for: 0.5x has a quarter of the pixels of 2x, so the same column shows each of them
 * four times as large and the picture reads blockier. Nothing is ever left small in the middle of the column --
 * that is the thing a preview must not do -- and nothing is cropped: a picture too big for the column is fitted
 * to it rather than cut off.
 */
class PreviewDrawSizeTest {

  /** The preview column of a phone in landscape, in device pixels: the case this has to look right on. */
  private val columnWidth = 598
  private val columnHeight = 1080

  @Test
  fun everyPreviewSizeFillsTheColumn() {
    for (preview in PreviewScale.entries) {
      val (bitmapWidth, bitmapHeight) = previewPixelSize(
        OutputAspect.Portrait9x16,
        preview,
      )
      val (width, height) = previewDrawSize(bitmapWidth, bitmapHeight, columnWidth, columnHeight)
      // One of the two sides reaches the column's edge, and neither goes past it.
      assertTrue(
        "${preview.label} fills the column: ${width}x$height in ${columnWidth}x$columnHeight",
        (width == columnWidth || height == columnHeight) &&
          width <= columnWidth && height <= columnHeight,
      )
      // And it keeps its shape, to within a pixel: a squashed picture is not a fitted one.
      assertEquals(
        "${preview.label} keeps its proportions",
        bitmapWidth.toDouble() / bitmapHeight,
        width.toDouble() / height,
        0.01,
      )
    }
  }

  @Test
  fun aSmallerPreviewIsScaledUpToFill() {
    // 0.5x is 152x270; fitted to the column it is drawn at nearly four times its own size, not left small.
    val (width, height) = previewDrawSize(152, 270, columnWidth, columnHeight)
    assertEquals("it fills the column's width", columnWidth, width)
    assertTrue("and nearly all of its height: $height", height > columnHeight * 0.95)
  }

  @Test
  fun aPictureTooBigForTheColumnIsFittedToIt() {
    val (width, height) = previewDrawSize(1216, 2160, columnWidth, columnHeight)
    assertTrue("it fits: ${width}x$height", width <= columnWidth && height <= columnHeight)
    assertTrue("it is as large as it can be", width >= columnWidth - 1 || height >= columnHeight - 1)
  }

  @Test
  fun anEmptyColumnDrawsNothing() {
    assertEquals(0 to 0, previewDrawSize(304, 540, 0, 0))
    assertEquals(0 to 0, previewDrawSize(0, 0, columnWidth, columnHeight))
  }
}
