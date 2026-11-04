package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resizing a drawing that is grouped into cells.
 *
 * A grouped kernel only sees whole cells: with 3-pixel cells, a drawing that is 28 wide has a ninth cell
 * that is one pixel wide, so its patterns are fragments of the drawing and the picture is built from
 * shapes the person never drew. The grid therefore stays a whole number of cells in both axes -- adding a
 * column adds a cell, and picking a grouping that does not divide the drawing adds the space it needs.
 *
 * The floor is [MIN_GRID_SIZE] *cells* rather than pixels: seven cells each way whatever the grouping,
 * which is 21x21 for a 9x9 kernel (3-pixel cells) and 7x7 for the ungrouped one.
 */
class GroupedGridTest {

  private val red = Color(0xFFBE4A2F)

  /** A drawing that is already a whole number of 3-pixel cells and at least seven cells each way. */
  private fun grid(columns: Int = 27, rows: Int = 21) =
    PixelGridState(initialColumns = columns, initialRows = rows)

  @Test
  fun addingAColumnAddsAWholeCell() {
    val grid = grid()
    grid.fitCells(3)
    grid.paint(0, 0, red)

    grid.addColumnRight()

    assertEquals(30, grid.columns)
    assertEquals(21, grid.rows)
    // The drawing does not move, and the new cells are blank.
    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(grid.defaultColor, grid.getPixel(27, 0))
    assertEquals(grid.defaultColor, grid.getPixel(29, 20))
  }

  @Test
  fun addingARowAddsAWholeCell() {
    val grid = grid()
    grid.fitCells(3)
    grid.paint(0, 0, red)

    grid.addRowBottom()

    assertEquals(27, grid.columns)
    assertEquals(24, grid.rows)
    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(grid.defaultColor, grid.getPixel(0, 21))
  }

  @Test
  fun removingTakesAWholeCellFromEitherSide() {
    val left = grid()
    left.fitCells(3)
    left.paint(27 - 1, 0, red)
    left.removeColumnLeft()
    assertEquals(24, left.columns)
    assertEquals(red, left.getPixel(24 - 1, 0))

    val right = grid()
    right.fitCells(3)
    right.paint(0, 0, red)
    right.removeColumnRight()
    assertEquals(24, right.columns)
    assertEquals(red, right.getPixel(0, 0))

    val top = grid(columns = 27, rows = 24)
    top.fitCells(3)
    top.paint(0, 24 - 1, red)
    top.removeRowTop()
    assertEquals(21, top.rows)
    assertEquals(red, top.getPixel(0, 21 - 1))

    val bottom = grid(columns = 27, rows = 24)
    bottom.fitCells(3)
    bottom.paint(0, 0, red)
    bottom.removeRowBottom()
    assertEquals(21, bottom.rows)
    assertEquals(red, bottom.getPixel(0, 0))
  }

  @Test
  fun aGroupedGridCannotShrinkBelowSevenCells() {
    // Seven 3-pixel cells is 21 pixels: one more cell fewer would be six cells, below the minimum.
    val smallest = grid(columns = 21, rows = 21)
    smallest.fitCells(3)
    assertFalse(smallest.canRemoveColumn())
    assertFalse(smallest.canRemoveRow())
    smallest.removeColumnLeft()
    assertEquals("a refused resize must not change the grid", 21, smallest.columns)

    val oneMore = grid(columns = 24, rows = 24)
    oneMore.fitCells(3)
    assertTrue(oneMore.canRemoveColumn())
    oneMore.removeColumnRight()
    assertEquals(21, oneMore.columns)
    assertFalse(oneMore.canRemoveColumn())
  }

  @Test
  fun aGroupedGridCannotGrowPastTheLimit() {
    val grid = grid(columns = 498, rows = 21)
    grid.fitCells(3)

    assertFalse(grid.canAddColumn())
    assertTrue(grid.canAddRow())
  }

  @Test
  fun pickingABiggerGroupingAddsTheSpaceItNeeds() {
    // 47x38 is 9.4 x 7.6 cells at 5 pixels: the drawing grows to 50x40, on the right and the bottom, so
    // nothing that was drawn moves.
    val grid = grid(columns = 47, rows = 38)
    grid.paint(46, 37, red)

    grid.fitCells(5)

    assertEquals(50, grid.columns)
    assertEquals(40, grid.rows)
    assertEquals(red, grid.getPixel(46, 37))
  }

  @Test
  fun pickingABiggerGroupingNeverLeavesFewerThanSevenCells() {
    // 12x9 is smaller than seven cells in either direction under a 9x9 kernel, so it grows to the floor
    // (21x21) rather than staying a five-cell drawing.
    val grid = grid(columns = 12, rows = 9)
    grid.fitCells(3)
    assertEquals(21, grid.columns)
    assertEquals(21, grid.rows)

    // Under 8-pixel cells seven cells is 56 pixels.
    grid.fitCells(8)
    assertEquals(56, grid.columns)
    assertEquals(56, grid.rows)
  }

  @Test
  fun aGroupingThatAlreadyDividesTheDrawingChangesNothing() {
    val grid = grid(columns = 27, rows = 21)
    grid.fitCells(3)
    val revision = grid.revision

    grid.fitCells(3)
    grid.fitCells(1)
    grid.fitCells(3)

    assertEquals(27, grid.columns)
    assertEquals(21, grid.rows)
    assertEquals("a resize that changes nothing must not be an edit", revision, grid.revision)
  }

  @Test
  fun fittingTheDrawingIsOneUndoStep() {
    val grid = grid(columns = 27, rows = 20)
    grid.fitCells(3)
    assertEquals(21, grid.rows)

    grid.undo()

    assertEquals(20, grid.rows)
    assertEquals(27, grid.columns)
    assertFalse(grid.canUndo())
  }

  @Test
  fun restoringADrawingUnderAGroupingAddsTheSpaceItNeeds() {
    val grid = grid()
    // A 27x20 drawing saved before the grouping was picked: 3-pixel cells need a whole cell of rows.
    val pixels = IntArray(27 * 20) { red.toArgb() }

    grid.restoreDrawing(columns = 27, rows = 20, pixels = pixels, cellSize = 3)

    assertEquals(27, grid.columns)
    assertEquals(21, grid.rows)
    assertEquals(red, grid.getPixel(26, 19))
    assertEquals(grid.defaultColor, grid.getPixel(0, 20))
    assertEquals("the restored drawing starts with no history", false, grid.canUndo())
  }

  @Test
  fun anUngroupedDrawingStillResizesOnePixelAtATime() {
    val grid = PixelGridState(initialColumns = 27, initialRows = 15)

    grid.addColumnRight()
    grid.addRowTop()

    assertEquals(28, grid.columns)
    assertEquals(16, grid.rows)
  }

  @Test
  fun increasingTheGroupingNeverLeavesAGridBelowSevenCells() {
    // MIN_GRID_SIZE is a count of cells: every side the grid can end up with is at least seven cells and a
    // whole number of them, whatever the drawing started as.
    for (columns in 1..12) {
      for (rows in 1..12) {
        for (kernel in KernelSize.entries) {
          val grid = PixelGridState(initialColumns = columns, initialRows = rows)
          grid.fitCells(kernel.cellSize)
          val smallest = MIN_GRID_SIZE * kernel.cellSize
          assertTrue(
            "${columns}x$rows under ${kernel.label} became ${grid.columns}x${grid.rows}, " +
              "below the $smallest-pixel minimum",
            grid.columns >= smallest && grid.rows >= smallest,
          )
          assertEquals(0, grid.columns % kernel.cellSize)
          assertEquals(0, grid.rows % kernel.cellSize)
        }
      }
    }
  }
}
