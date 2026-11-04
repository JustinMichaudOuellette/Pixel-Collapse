package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a grouped kernel's cells are drawn.
 *
 * A cell is [KernelSize.cellSize] drawing pixels square. Drawing it into a square of any other size
 * resamples the drawing, and the larger the kernel the worse that gets: the 9x9 kernel (3-pixel cells)
 * is drawn into four-pixel squares, and the 24x24 kernel (8-pixel cells) is squeezed into four. The
 * picture then does not contain the drawing's pixels at all -- it contains a blend of them -- which is
 * what makes the tiles look wrongly rendered.
 *
 * So the square a cell is drawn in has to be a whole number of the cell's own pixels.
 */
class GroupedKernelTest {

  @Test
  fun aCellIsDrawnWholeWhereverThePreviewAllowsIt() {
    // The step is a whole number of the cell's pixels, or -- where the preview has no step that both
    // divides it and is a whole number of cells -- smaller than the cell, which is a uniform downsample
    // rather than the stretch that tore the tiles apart. GenerationPlanTest pins the same rule directly.
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        for (kernel in KernelSize.entries) {
          if (!kernel.grouped) continue
          val plan = generationPlan(aspect, preview, kernel)
          val step = plan.pixelsPerCell
          assertTrue(
            "${aspect.label} ${preview.label}: a ${kernel.label} cell is ${kernel.cellSize} pixels " +
              "and is drawn at $step",
            step % kernel.cellSize == 0 || step < kernel.cellSize,
          )
        }
      }
    }
  }

  /**
   * The drawing's own cells, laid out in their own order and drawn the way the preview draws them,
   * have to give the drawing back pixel for pixel.
   *
   * This is the strongest statement of "the tiles are rendered correctly" that can be made without a
   * solver: no arrangement is involved, every cell shows the block it came from, and any resampling,
   * any off-by-one in the block lookup and any wrong cell size all break it.
   */
  @Test
  fun theDrawingsOwnCellsRenderBackAsTheDrawing() {
    for (kernel in KernelSize.entries) {
      if (!kernel.grouped) continue
      for (preview in PreviewScale.entries) {
        val plan = generationPlan(OutputAspect.Portrait9x16, preview, kernel)
        // A step smaller than the cell is a downsample: the picture keeps the right pixel size by
        // dropping some of each block's pixels, so not every pixel of the drawing appears in it.
        if (plan.pixelsPerCell < kernel.cellSize) continue
        val grouped = groupCells(BaconPattern.pixels, BaconPattern.columns, BaconPattern.rows, kernel.cellSize)
        val width = grouped.width * plan.pixelsPerCell
        val height = grouped.height * plan.pixelsPerCell
        val picture = IntArray(width * height)

        renderCellCenters(
          pixels = picture,
          width = width,
          columns = grouped.width,
          rows = grouped.height,
          scale = plan.pixelsPerCell,
          centres = grouped.tokens,
          blocks = grouped.blocks,
          cell = kernel.cellSize,
        )

        // The picture is the drawing's cells at the plan's square size, so it is the drawing itself
        // when the drawing is a whole number of cells wide and tall -- which this one is.
        val cell = kernel.cellSize
        val drawnWidth = (BaconPattern.columns / cell) * cell
        val drawnHeight = (BaconPattern.rows / cell) * cell
        for (y in 0 until drawnHeight) {
          for (x in 0 until drawnWidth) {
            val shownX = x * plan.pixelsPerCell / cell
            val shownY = y * plan.pixelsPerCell / cell
            assertEquals(
              "${preview.label} ${kernel.label}: the picture is not the drawing at ($x,$y)",
              BaconPattern.pixels[y * BaconPattern.columns + x],
              picture[shownY * width + shownX],
            )
          }
        }
      }
    }
  }

  @Test
  fun aGroupedPictureIsStillThePreviewSize() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (previewWidth, previewHeight) = previewPixelSize(aspect, preview)
        for (kernel in KernelSize.entries) {
          val plan = generationPlan(aspect, preview, kernel)
          val width = plan.columns * plan.pixelsPerCell
          val height = plan.rows * plan.pixelsPerCell
          assertTrue(
            "${aspect.label} ${preview.label} ${kernel.label}: the picture is ${width}x$height, " +
              "not the preview's ${previewWidth}x$previewHeight",
            abs(width - previewWidth) < plan.pixelsPerCell &&
              abs(height - previewHeight) < plan.pixelsPerCell,
          )
        }
      }
    }
  }
}
