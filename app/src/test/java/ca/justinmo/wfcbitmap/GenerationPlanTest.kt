package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shape one generation is asked for: grouping must not shrink the output grid. */
class GenerationPlanTest {

  @Test
  fun groupingLeavesTheOutputGridAlone() {
    for (aspect in OutputAspect.entries) {
      for (preview in PreviewScale.entries) {
        val (expectedColumns, expectedRows) = WfcGenerator.gridForAspect(aspect.ratio, preview.cellCount)
        for (kernel in KernelSize.entries) {
          val plan = generationPlan(aspect, preview, kernel)
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} columns",
            expectedColumns,
            plan.columns,
          )
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} rows",
            expectedRows,
            plan.rows,
          )
          assertEquals(
            "${aspect.label} ${preview.label} ${kernel.label} pixels per cell",
            preview.pixelsPerCell,
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
    // 76 is the 9:16 cell count at the 2x preview; it used to be divided by 2, which shrank the picture.
    assertEquals(76, plan.columns)
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
