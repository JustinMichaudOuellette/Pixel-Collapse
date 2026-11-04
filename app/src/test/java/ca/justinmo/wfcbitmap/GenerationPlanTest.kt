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

/** The shape one generation is asked for: the picture is exactly the preview's size. */
class GenerationPlanTest {

  @Test
  fun everyRecipeGeneratesExactlyTheSelectedPreviewSize() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (previewWidth, previewHeight) = previewPixelSize(aspect, preview)
        for (kernel in KernelSize.entries) {
          val plan = generationPlan(aspect, preview, kernel)
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} width",
            previewWidth,
            plan.columns * plan.pixelsPerCell,
          )
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} height",
            previewHeight,
            plan.rows * plan.pixelsPerCell,
          )
        }
      }
    }
  }

  @Test
  fun aCellIsNeverStretchedToAStepItDoesNotDivide() {
    // Stretching a block up to a step it does not divide is what tore the tiles away from each other.
    // The step is either a whole number of cells, or smaller than the cell (a uniform downsample).
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        for (kernel in KernelSize.entries) {
          val plan = generationPlan(aspect, preview, kernel)
          val step = plan.pixelsPerCell
          assertTrue(
            "${aspect.label} ${preview.label} ${kernel.label}: a ${kernel.cellSize}-pixel cell drawn " +
              "at $step",
            step % kernel.cellSize == 0 || step < kernel.cellSize,
          )
        }
      }
    }
  }

  @Test
  fun anUngroupedRecipeStillUsesThePreviewsOwnGrid() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val grid = previewGrid(aspect, preview)
        val plan = generationPlan(aspect, preview, KernelSize.Three)
        assertEquals(grid.columns, plan.columns)
        assertEquals(grid.rows, plan.rows)
        assertEquals(grid.pixelsPerCell, plan.pixelsPerCell)
      }
    }
  }

  @Test
  fun aSixBySixRecipeIsTwoByTwoCells() {
    val plan = generationPlan(OutputAspect.Portrait9x16, PreviewScale.One, KernelSize.Six)

    assertEquals(2, plan.cellSize)
    // 2× 9:16 is 540×960: 135×240 cells at four pixels per cell, i.e. two per drawing pixel.
    assertEquals(135, plan.columns)
    assertEquals(240, plan.rows)
    assertEquals(4, plan.pixelsPerCell)
  }

  @Test
  fun aNineByNineRecipeIsThreeByThreeCellsDrawnOneToOne() {
    val plan = generationPlan(OutputAspect.Portrait9x16, PreviewScale.One, KernelSize.Nine)

    assertEquals(3, plan.cellSize)
    // 3 pixels per cell is the only square that shows a 3-pixel cell without resampling it, so the
    // picture keeps its size by holding 180×320 cells instead of 135×240.
    assertEquals(3, plan.pixelsPerCell)
    assertEquals(180, plan.columns)
    assertEquals(320, plan.rows)
  }

  @Test
  fun everyRecipeNamesTheCellSideItGroupsBy() {
    assertEquals(1, generationPlan(OutputAspect.Square, PreviewScale.Half, KernelSize.Three).cellSize)
    assertEquals(2, generationPlan(OutputAspect.Square, PreviewScale.Half, KernelSize.Six).cellSize)
    assertEquals(8, generationPlan(OutputAspect.Square, PreviewScale.Half, KernelSize.TwentyFour).cellSize)
  }

  @Test
  fun aGroupedPlanStillHasAWholeOutputGrid() {
    val plan = generationPlan(OutputAspect.Portrait9x16, PreviewScale.Half, KernelSize.TwentyFour)
    assertTrue(plan.columns > 0)
    assertTrue(plan.rows > 0)
    assertTrue(plan.pixelsPerCell > 0)
  }
}