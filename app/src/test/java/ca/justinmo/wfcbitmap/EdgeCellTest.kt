package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which separators the edge indicator makes dotted.
 *
 * The indicator is the grid lines and nothing else: no tint, no frame, nothing over the cells. The lines
 * inside the edge band are dotted and the rest are dashed, and the band is two cells deep on every side --
 * the outer ring the edge rule constrains, plus the ring around it, so there is something to see between
 * one line and the next.
 */
class EdgeCellTest {

  @Test
  fun theBandIsTwoCellsDeepFromEverySide() {
    val columns = 16
    val rows = 11
    val dottedColumns = (0..columns).filter { BitmapEditor.isEdgeBandLine(it, columns) }
    val dottedRows = (0..rows).filter { BitmapEditor.isEdgeBandLine(it, rows) }

    // Three lines each side: the frame, one cell in, and two cells in where the band ends.
    assertEquals(listOf(0, 1, 2, 14, 15, 16), dottedColumns)
    assertEquals(listOf(0, 1, 2, 9, 10, 11), dottedRows)
  }

  @Test
  fun theMiddleOfTheGridKeepsItsPlainLines() {
    val columns = 16
    val rows = 11
    for (index in 3..(columns - 3)) {
      assertFalse("column line $index is not in the band", BitmapEditor.isEdgeBandLine(index, columns))
    }
    for (index in 3..(rows - 3)) {
      assertFalse("row line $index is not in the band", BitmapEditor.isEdgeBandLine(index, rows))
    }
  }

  @Test
  fun theBandCoversTwoCellsOfEachSide() {
    val columns = 16
    val rows = 11
    val bandCells = (0 until rows).flatMap { row -> (0 until columns).map { col -> col to row } }
      .filter { (col, row) ->
        col < BitmapEditor.EDGE_BAND_CELLS || row < BitmapEditor.EDGE_BAND_CELLS ||
          col >= columns - BitmapEditor.EDGE_BAND_CELLS || row >= rows - BitmapEditor.EDGE_BAND_CELLS
      }
    val expected = columns * rows - (columns - 2 * BitmapEditor.EDGE_BAND_CELLS) * (rows - 2 * BitmapEditor.EDGE_BAND_CELLS)
    assertEquals("the band is the grid less its inner rectangle", expected, bandCells.size)
    assertTrue("and it is not the whole grid", bandCells.size < columns * rows)
  }
}