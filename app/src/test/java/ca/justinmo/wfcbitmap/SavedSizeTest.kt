package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.generation.generationPlan
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.OutputSettings
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.previewGrid
import ca.justinmo.wfcbitmap.output.previewPixelSize
import ca.justinmo.wfcbitmap.output.savedPixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The preview steps and the independent save sizes.
 *
 * The preview is four quarter steps of a 4× maximum: 1×, 2×, 3× and 4×. The save list keeps its own
 * historical base, so changing the preview never changes the size of a saved file.
 */
class SavedSizeTest {

  @Test
  fun theMaximumPreviewIsTheAgreedSizeForEveryShape() {
    assertEquals(1080 to 1920, previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.Two))
    assertEquals(1080 to 1440, previewPixelSize(OutputAspect.Portrait3x4, PreviewScale.Two))
    assertEquals(1080 to 1080, previewPixelSize(OutputAspect.Square, PreviewScale.Two))
    assertEquals(1080 to 810, previewPixelSize(OutputAspect.Landscape4x3, PreviewScale.Two))
    assertEquals(1080 to 608, previewPixelSize(OutputAspect.Landscape16x9, PreviewScale.Two))
  }

  @Test
  fun theSmallerPreviewsAreQuarterStepsBackFromTheMaximum() {
    // 9:16 is the clean case: every step divides exactly.
    assertEquals(270 to 480, previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.Half))
    assertEquals(540 to 960, previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.One))
    assertEquals(810 to 1440, previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.Three))
    assertEquals(1080 to 1920, previewPixelSize(OutputAspect.Portrait9x16, PreviewScale.Two))

    // 4:3's 810-pixel long side rounds at the quarter and three-quarter steps.
    assertEquals(270 to 203, previewPixelSize(OutputAspect.Landscape4x3, PreviewScale.Half))
    assertEquals(540 to 405, previewPixelSize(OutputAspect.Landscape4x3, PreviewScale.One))
    assertEquals(810 to 608, previewPixelSize(OutputAspect.Landscape4x3, PreviewScale.Three))
    assertEquals(1080 to 810, previewPixelSize(OutputAspect.Landscape4x3, PreviewScale.Two))
  }

  @Test
  fun thePreviewStepsAreLabelledOneToFourInOrder() {
    assertEquals(listOf("1×", "2×", "3×", "4×"), PreviewScale.entries.map { it.label })
    assertEquals(listOf(1, 2, 3, 4), PreviewScale.entries.map { it.factor })
  }

  @Test
  fun theGridApproachesThePreviewSizeAndGrowsWithIt() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (width, height) = previewPixelSize(aspect, preview)
        val grid = previewGrid(aspect, preview)
        // The bitmap is within a cell of the menu's size; lower steps round down a few pixels.
        assertTrue(
          "${aspect.label} ${preview.label} width ${grid.columns * grid.pixelsPerCell} vs $width",
          abs(width - grid.columns * grid.pixelsPerCell) <= grid.pixelsPerCell,
        )
        assertTrue(
          "${aspect.label} ${preview.label} height ${grid.rows * grid.pixelsPerCell} vs $height",
          abs(height - grid.rows * grid.pixelsPerCell) <= grid.pixelsPerCell,
        )
        val plan = generationPlan(aspect, preview, KernelSize.Three)
        assertEquals(grid.columns, plan.columns)
        assertEquals(grid.rows, plan.rows)
        assertEquals(grid.pixelsPerCell, plan.pixelsPerCell)
      }
    }
  }

  @Test
  fun everyPreviewSizeIsPositive() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (width, height) = previewPixelSize(aspect, preview)
        assertTrue("${aspect.label} at ${preview.label} is ${width}x$height", width > 0 && height > 0)
      }
    }
  }

  @Test
  fun thePreviewDefaultIsTwoTimes() {
    assertEquals(PreviewScale.One, OutputSettings().preview)
    assertEquals(
      540 to 960,
      previewPixelSize(OutputAspect.Portrait9x16, OutputSettings().preview),
    )
  }

  @Test
  fun theSaveSizeIsAMultipleOfThePreview() {
    val preview = PreviewScale.One // 2× gives 540×960 for 9:16.
    val (previewWidth, previewHeight) = previewPixelSize(OutputAspect.Portrait9x16, preview)
    assertEquals(540 to 960, previewWidth to previewHeight)
    assertEquals(
      previewWidth to previewHeight,
      savedPixelSize(OutputAspect.Portrait9x16, OutputScale.One, preview),
    )
    assertEquals(
      1080 to 1920,
      savedPixelSize(OutputAspect.Portrait9x16, OutputScale.Two, preview),
    )
    for (scale in OutputScale.entries) {
      val saved = savedPixelSize(OutputAspect.Portrait9x16, scale, preview)
      assertEquals(scale.pixels(previewWidth), saved.first)
      assertEquals(scale.pixels(previewHeight), saved.second)
    }
  }

  @Test
  fun everySaveSizeIsAWholeNumberOfPixels() {
    for (aspect in OutputAspect.entries) {
      for (scale in OutputScale.entries) {
        val (width, height) = savedPixelSize(aspect, scale, PreviewScale.One)
        assertTrue("${aspect.label} saved at ${scale.label} is ${width}x$height", width > 0 && height > 0)
      }
    }
  }

  @Test
  fun theShapeDecidesTheProportions() {
    val preview = PreviewScale.One
    val portrait = previewPixelSize(OutputAspect.Portrait9x16, preview)
    val square = previewPixelSize(OutputAspect.Square, preview)
    val landscape = previewPixelSize(OutputAspect.Landscape16x9, preview)

    assertTrue("square=$square", abs(square.first - square.second) <= 2)
    assertTrue("portrait=$portrait", portrait.second > portrait.first)
    assertTrue("landscape=$landscape", landscape.first > landscape.second)
  }

  @Test
  fun everyShapeIsWithinRoundingOfItsAspectRatio() {
    for (aspect in OutputAspect.entries) {
      val (width, height) = previewPixelSize(aspect, PreviewScale.Two)
      val ratio = width.toFloat() / height
      assertEquals(
        "${aspect.label} should keep its proportions: ${width}x$height",
        aspect.ratio,
        ratio,
        0.01f,
      )
    }
  }
}