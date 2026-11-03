package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape one generation is asked for: grouping must not shrink the output grid. */
class GenerationPlanTest {

  @Test
  fun groupingLeavesTheOutputGridAlone() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val grid = previewGrid(aspect, preview)
        for (kernel in KernelSize.entries) {
          val plan = generationPlan(aspect, preview, kernel)
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} columns",
            grid.columns,
            plan.columns,
          )
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} rows",
            grid.rows,
            plan.rows,
          )
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} pixels per cell",
            grid.pixelsPerCell,
            plan.pixelsPerCell,
          )
        }
      }
    }
  }

  @Test
  fun aSixBySixRecipeIsTwoByTwoCells() {
    val plan = generationPlan(OutputAspect.Portrait9x16, PreviewScale.One, KernelSize.Six)

    assertEquals(2, plan.cellSize)
    // 2× 9:16 is 540×960: 135×240 cells at four pixels per cell.
    assertEquals(135, plan.columns)
    assertEquals(4, plan.pixelsPerCell)
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