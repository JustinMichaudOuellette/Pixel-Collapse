package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.ui.EDGE_BAND_CELLS
import ca.justinmo.wfcbitmap.ui.edgeBandSpans
import ca.justinmo.wfcbitmap.ui.isEdgeBandLine
import ca.justinmo.wfcbitmap.ui.showsEdgeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stretches of the grid separators the edge indicator dots.
 *
 * The band is two cells deep on every side and the dotting is per *segment*: a separator that runs out of
 * the top strip is dotted for the strip's depth and plain along the rest of its length. Dotting whole lines
 * instead -- which is what this started as -- leaves the middle of the top and bottom strips with plain
 * vertical separators, and the middle of the left and right strips with plain horizontal ones, which is a
 * band that is missing on all four sides in the places you look at most.
 */
class EdgeCellTest {

  @Test
  fun aSeparatorInsideTheBandIsDottedEndToEnd() {
    // Column 1 lies inside the left band, so all of it is dotted.
    assertEquals(listOf(0..11), edgeBandSpans(lineIndex = 1, lineCount = 16, spanCount = 11))
    // Row 1 lies inside the top band, likewise.
    assertEquals(listOf(0..16), edgeBandSpans(lineIndex = 1, lineCount = 11, spanCount = 16))
  }

  @Test
  fun aSeparatorThroughTheBandIsDottedOnlyWhereItCrossesIt() {
    // A vertical separator in the middle of the grid: dotted through the top strip and the bottom one.
    assertEquals(
      listOf(0..2, 9..11),
      edgeBandSpans(lineIndex = 8, lineCount = 16, spanCount = 11),
    )
    // A horizontal separator in the middle: dotted through the left strip and the right one.
    assertEquals(
      listOf(0..2, 14..16),
      edgeBandSpans(lineIndex = 5, lineCount = 11, spanCount = 16),
    )
  }

  @Test
  fun theBandIsTwoCellsDeepFromEverySide() {
    val columns = 16
    val rows = 11
    assertEquals(listOf(0, 1, 2, 14, 15, 16), (0..columns).filter { isEdgeBandLine(it, columns) })
    assertEquals(listOf(0, 1, 2, 9, 10, 11), (0..rows).filter { isEdgeBandLine(it, rows) })
  }

  @Test
  fun aBandDeeperThanHalfTheGridLeavesNoMiddleToLeavePlain() {
    assertEquals(listOf(0..3), edgeBandSpans(lineIndex = 1, lineCount = 3, spanCount = 3))
  }

  @Test
  fun theGridIsUniformUnlessThePictureKeepsTheDrawingBorder() {
    // Kept, and not wrapped: the band is marked, so the separators are dotted where it lies.
    assertTrue(showsEdgeBand(edgeRuleKept = true, wrapEdges = false))
    // Relaxed: the edge rule was dropped, so the drawing's border is not the picture's border.
    assertTrue(!showsEdgeBand(edgeRuleKept = false, wrapEdges = false))
    // Wrapped: the border was never one to keep, because every pattern was sampled across it. This is the
    // case that used to keep a dotted band on screen next to a wrap button that was lit for the same reason.
    assertTrue(!showsEdgeBand(edgeRuleKept = true, wrapEdges = true))
    assertTrue(!showsEdgeBand(edgeRuleKept = false, wrapEdges = true))
  }

  @Test
  fun everyCellInTheBandIsBoundedByDottedSegments() {
    val columns = 16
    val rows = 11
    val band = EDGE_BAND_CELLS

    fun dottedOnColumn(col: Int, row: Int): Boolean {
      val spans = edgeBandSpans(lineIndex = col, lineCount = columns, spanCount = rows)
      return spans.any { row in it.first until it.last }
    }
    fun dottedOnRow(row: Int, col: Int): Boolean {
      val spans = edgeBandSpans(lineIndex = row, lineCount = rows, spanCount = columns)
      return spans.any { col in it.first until it.last }
    }

    // Every cell of the band has dotted separators on the side that faces the band's outer edge, and on the
    // side between the two rings -- including the cells in the middle of each side.
    for (col in 0 until columns) {
      assertTrue("top cell ($col,0) has a dotted vertical separator below it", dottedOnRow(1, col))
      assertTrue("bottom cell ($col,${rows - 1}) likewise", dottedOnRow(rows - 1, col))
    }
    for (row in 0 until rows) {
      assertTrue("left cell (0,$row) has a dotted horizontal separator beside it", dottedOnColumn(1, row))
      assertTrue("right cell (${columns - 1},$row) likewise", dottedOnColumn(columns - 1, row))
    }
    assertTrue("the band is ${band * 2} cells across, so it is not the whole grid", band * 2 < columns)
  }
}