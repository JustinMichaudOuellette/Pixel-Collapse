package ca.justinmo.wfcbitmap

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sizes the output menu offers, and the fact that they are two.
 *
 * The preview size is what the picture is generated at -- the bitmap on screen -- and the save size is what
 * that picture is written to a file at, worked out from the grid rather than from the preview, so choosing a
 * preview size never moves a number in the save list. Neither is a plain multiple of "the picture": the
 * solver grid keeps a fixed number of cells whatever shape is chosen, so the size is written beside each
 * option in the menu instead.
 */
class SavedSizeTest {

  @Test
  fun theDefaultShapeAtOneTimesIsTheGeneratedBitmap() {
    assertEquals(
      WfcGenerator.BITMAP_WIDTH to WfcGenerator.BITMAP_HEIGHT,
      previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.One),
    )
  }

  @Test
  fun thePreviewStepsSolveMoreCellsAsTheyGrow() {
    assertEquals("1× is a sixteenth of the base cells", WfcGenerator.DEFAULT_CELL_COUNT / 16, PreviewScale.Half.cellCount)
    assertEquals("2× is a quarter of the base cells", WfcGenerator.DEFAULT_CELL_COUNT / 4, PreviewScale.One.cellCount)
    assertEquals("4× is the base cell count", WfcGenerator.DEFAULT_CELL_COUNT, PreviewScale.Two.cellCount)
    assertTrue(PreviewScale.Half.cellCount < PreviewScale.One.cellCount)
    assertTrue(PreviewScale.One.cellCount < PreviewScale.Two.cellCount)

    for (preview in PreviewScale.entries) {
      val (columns, rows) = WfcGenerator.gridForAspect(
        OutputAspect.Portrait9x16.ratio,
        preview.cellCount,
      )
      val (width, height) = previewPixelSize(OutputAspect.Portrait9x16, preview)
      assertEquals("${preview.label} width is its grid times its cell size", columns * preview.pixelsPerCell, width)
      assertEquals("${preview.label} height is its grid times its cell size", rows * preview.pixelsPerCell, height)
    }
  }

  @Test
  fun thePreviewDefaultsToTheSmallestSize() {
    // The coarsest preview is the starting point. The bigger previews are there to be chosen.
    assertEquals(
      PreviewScale.Half,
      OutputSettings().preview,
    )
    assertEquals(
      152 to 272,
      previewPixelSize(OutputAspect.Portrait9x16, OutputSettings().preview),
    )
  }

  @Test
  fun theSaveSizeIsItsOwnScaleOfTheGrid() {
    // 1x is the picture the app has always generated, 16x is sixteen times it, and neither of those numbers
    // knows or cares what the preview is set to.
    val one = savedPixelSize(OutputAspect.Portrait9x16, OutputScale.One)
    assertEquals(WfcGenerator.BITMAP_WIDTH to WfcGenerator.BITMAP_HEIGHT, one)
    for (scale in OutputScale.entries) {
      val saved = savedPixelSize(OutputAspect.Portrait9x16, scale)
      assertEquals("width at ${scale.label}", scale.pixels(one.first), saved.first)
      assertEquals("height at ${scale.label}", scale.pixels(one.second), saved.second)
    }
  }

  @Test
  fun theSmallestSaveSizeIsOneTimes() {
    // A file smaller than the picture would be a file the drawing cannot be seen in, so 1x is the floor.
    assertEquals("1×", OutputScale.entries.first().label)
    assertEquals(1.0, OutputScale.entries.first().factor, 0.0)
  }

  @Test
  fun theTopOfTheSaveListIsTheSixteen() {
    // Four times the largest the list used to have (4x, 1216x2160): 4864x8640.
    assertEquals(
      4864 to 8640,
      savedPixelSize(OutputAspect.Portrait9x16, OutputScale.Sixteen),
    )
  }

  @Test
  fun everySizeIsAWholeNumberOfPixels() {
    // Nothing may round down to nothing: every shape at every size still has to be a picture.
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (shownWidth, shownHeight) = previewPixelSize(aspect, preview)
        assertTrue("${aspect.label} at ${preview.label} is ${shownWidth}x$shownHeight", shownWidth > 0 && shownHeight > 0)
      }
      for (scale in OutputScale.entries) {
        val (width, height) = savedPixelSize(aspect, scale)
        assertTrue("${aspect.label} saved at ${scale.label} is ${width}x$height", width > 0 && height > 0)
      }
    }
  }

  @Test
  fun theShapeDecidesTheProportions() {
    val preview = PreviewScale.One
    val scale = OutputScale.One
    val portrait = previewPixelSize(OutputAspect.Portrait9x16, preview)
    val square = previewPixelSize(OutputAspect.Square, preview)
    val landscape = previewPixelSize(OutputAspect.Landscape16x9, preview)

    // Square to within a cell: the grid keeps a fixed cell count, so the sides are whole cells and one of
    // them can round a cell further than the other (203x202 cells for a square).
    assertTrue(
      "a square picture is square to within a cell: square=$square at ${scale.label}",
      abs(square.first - square.second) <= 2 * WfcGenerator.PIXELS_PER_CELL,
    )
    assertTrue("a 9:16 picture is taller than it is wide: $portrait", portrait.second > portrait.first)
    assertTrue("a 16:9 picture is wider than it is tall: $landscape", landscape.first > landscape.second)
  }

  @Test
  fun everyShapeIsWithinACellOfItsAspectRatio() {
    for (aspect in OutputAspect.entries) {
      val (width, height) = previewPixelSize(aspect, PreviewScale.One)
      val ratio = width.toFloat() / height
      assertEquals(
        "${aspect.label} should keep its proportions: ${width}x$height",
        aspect.ratio,
        ratio,
        // Within rounding of the grid: the cell count is fixed, so the sides are integers near the ratio.
        0.02f,
      )
    }
  }
}
