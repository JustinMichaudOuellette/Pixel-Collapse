package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cropping a drawing to what is drawn on it.
 *
 * The margin is not decoration: one cell is what a wrapped pattern needs to run off the drawing and back
 * on, two is what a kept border needs for a window centred on a border cell to be complete. Cropping is
 * also where the rules about cells and the smallest grid keep applying, which is why a crop can come out
 * larger than the content and its border.
 */
class AutoCropTest {

  private val paper = Color(0xFFE8D8B0)
  private val ink = Color(0xFFBE4A2F)

  private fun drawing(columns: Int, rows: Int, paint: (PixelGridState) -> Unit): PixelGridState {
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = columns, initialRows = rows)
    for (y in 0 until rows) {
      for (x in 0 until columns) grid.setPixel(x, y, paper)
    }
    paint(grid)
    return grid
  }

  @Test
  fun itTrimsTheMarginAndKeepsOneCellOfIt() {
    // An 8x5 subject in a 16x12 drawing, cropped with the border a wrapped pattern needs.
    val grid = drawing(16, 12) { g ->
      for (y in 4..8) {
        for (x in 5..12) g.setPixel(x, y, ink)
      }
    }

    assertTrue(grid.cropToContent(borderCells = 1))

    assertEquals(10, grid.columns)
    assertEquals(7, grid.rows)
    // One cell of background round the subject, and the subject in the same shape.
    assertEquals(paper, grid.getPixel(0, 0))
    assertEquals(ink, grid.getPixel(1, 1))
    assertEquals(ink, grid.getPixel(8, 5))
    assertEquals(paper, grid.getPixel(9, 6))
    assertEquals(paper, grid.getPixel(0, 6))
  }

  @Test
  fun itKeepsTwoCellsWhenTheBorderIsKept() {
    val grid = drawing(16, 12) { g ->
      for (y in 4..8) {
        for (x in 5..12) g.setPixel(x, y, ink)
      }
    }

    assertTrue(grid.cropToContent(borderCells = 2))

    assertEquals(12, grid.columns)
    assertEquals(9, grid.rows)
    assertEquals(paper, grid.getPixel(1, 1))
    assertEquals(ink, grid.getPixel(2, 2))
    assertEquals(ink, grid.getPixel(9, 6))
  }

  @Test
  fun aSubjectAgainstTheEdgeIsGivenItsBorderAnyway() {
    // The subject touches the old drawing's corner, so the border has to be added outside it.
    val grid = drawing(12, 12) { g ->
      for (y in 0..3) {
        for (x in 0..3) g.setPixel(x, y, ink)
      }
    }

    assertTrue(grid.cropToContent(borderCells = 2))

    assertEquals(8, grid.columns)
    assertEquals(8, grid.rows)
    assertEquals(paper, grid.getPixel(0, 0))
    assertEquals(paper, grid.getPixel(1, 1))
    assertEquals(ink, grid.getPixel(2, 2))
    assertEquals(ink, grid.getPixel(5, 5))
    assertEquals(paper, grid.getPixel(6, 6))
    assertEquals(paper, grid.getPixel(7, 7))
  }

  @Test
  fun croppingIsOneUndoStep() {
    val grid = drawing(16, 12) { g ->
      for (y in 4..8) {
        for (x in 5..12) g.setPixel(x, y, ink)
      }
    }
    val steps = grid.undoDepth

    grid.cropToContent(borderCells = 1)
    assertEquals(steps + 1, grid.undoDepth)

    grid.undo()

    assertEquals(16, grid.columns)
    assertEquals(12, grid.rows)
    assertEquals(ink, grid.getPixel(5, 4))
  }

  @Test
  fun aDrawingOfNothingButBackgroundHasNothingToCrop() {
    val grid = drawing(16, 12) {}
    val revision = grid.revision

    assertFalse(grid.cropToContent(borderCells = 1))

    assertEquals(16, grid.columns)
    assertEquals(12, grid.rows)
    assertEquals(revision, grid.revision)
  }

  @Test
  fun aCropIsAWholeNumberOfGroupingCellsAndAtLeastTheSmallestGrid() {
    val grid = drawing(30, 30) { g ->
      for (y in 10..14) {
        for (x in 10..17) g.setPixel(x, y, ink)
      }
    }
    grid.fitCells(3)

    assertTrue(grid.cropToContent(borderCells = 1))

    // The content is 8x5 and one cell of border makes 10x7, but seven 3-pixel cells is 21: the crop
    // comes out at the smallest legal grid rather than below it.
    assertEquals(21, grid.columns)
    assertEquals(21, grid.rows)
    assertEquals(0, grid.columns % 3)
    assertEquals(0, grid.rows % 3)
    // The subject still sits one cell in from the new top-left.
    assertEquals(paper, grid.getPixel(0, 0))
    assertEquals(ink, grid.getPixel(1, 1))
  }

  @Test
  fun aCropThatChangesNothingIsNotAnEdit() {
    // Content with exactly the border the crop would keep, in a grid that is already the right size.
    val grid = drawing(8, 8) { g ->
      for (y in 1..6) {
        for (x in 1..6) g.setPixel(x, y, ink)
      }
    }
    val revision = grid.revision

    assertFalse(grid.cropToContent(borderCells = 1))

    assertEquals(8, grid.columns)
    assertEquals(8, grid.rows)
    assertEquals(revision, grid.revision)
  }
}
