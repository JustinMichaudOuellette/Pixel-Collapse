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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's tap behaviour: a tap paints, and a tap on a cell that already shows the selected colour
 * puts that cell's previous colour back.
 *
 * The revert is per cell, not "the cell the last tap touched": painting another cell in between must not
 * cost the first cell an extra tap (it did — the second tap on it was swallowed because it was no longer
 * the most recently painted cell).
 *
 * Pure state, so it runs on the JVM with the rest of the engine tests.
 */
class PixelGridStateTest {

  private val red = Color(0xFFBE4A2F)
  private val blue = Color(0xFF5A6988)

  @Test
  fun aTapPaintsTheSelectedColour() {
    val grid = PixelGridState()
    val before = grid.getPixel(3, 2)
    grid.paint(3, 2, red)
    assertEquals(red, grid.getPixel(3, 2))
    assertNotEquals(before, grid.getPixel(3, 2))
    assertEquals("one edit", 1, grid.revision)
  }

  @Test
  fun tappingTheSameCellAgainPutsThePreviousColourBack() {
    val grid = PixelGridState()
    val original = grid.getPixel(3, 2)

    grid.paint(3, 2, red)
    grid.paint(3, 2, red)

    assertEquals("the second tap reverts the first", original, grid.getPixel(3, 2))
    assertEquals("both taps are edits", 2, grid.revision)
  }

  @Test
  fun theThirdTapPaintsAgain() {
    val grid = PixelGridState()
    val original = grid.getPixel(3, 2)

    grid.paint(3, 2, red)
    grid.paint(3, 2, red)
    grid.paint(3, 2, red)

    assertEquals(red, grid.getPixel(3, 2))
    assertNotEquals(original, grid.getPixel(3, 2))
  }

  @Test
  fun revertingGoesToTheColourFromBeforeThatPaintNotToTheDefault() {
    val grid = PixelGridState()
    grid.paint(1, 1, red)

    grid.paint(1, 1, blue)
    assertEquals(blue, grid.getPixel(1, 1))
    grid.paint(1, 1, blue)

    assertEquals("reverting a blue paint restores the red underneath", red, grid.getPixel(1, 1))
  }

  @Test
  fun tappingAnotherCellInBetweenDoesNotCostTheFirstCellATap() {
    val grid = PixelGridState()
    val original = grid.getPixel(0, 0)

    grid.paint(0, 0, red)
    grid.paint(1, 0, red)
    // Back to the first cell, same colour: one tap has to put it back even though the last tap landed
    // somewhere else. It used to take two, the first being swallowed as a no-op.
    grid.paint(0, 0, red)

    assertEquals("one tap reverts the first cell", original, grid.getPixel(0, 0))
    assertEquals("the cell in between keeps its paint", red, grid.getPixel(1, 0))
    assertEquals("all three taps are edits", 3, grid.revision)
  }

  @Test
  fun eachCellKeepsItsOwnPreviousColour() {
    val grid = PixelGridState()
    val original = grid.getPixel(0, 0)

    grid.paint(0, 0, red)
    grid.paint(1, 1, blue)
    grid.paint(1, 1, blue)
    assertEquals("(1,1) reverts on its own", original, grid.getPixel(1, 1))
    assertEquals("(0,0) is untouched by that", red, grid.getPixel(0, 0))

    grid.paint(0, 0, red)
    assertEquals("(0,0) reverts after its own second tap", original, grid.getPixel(0, 0))
  }

  @Test
  fun toggleColorPredictsWhatATapWouldDoWithoutChangingTheGrid() {
    val grid = PixelGridState()
    val original = grid.getPixel(1, 1)
    grid.paint(1, 1, red)

    assertEquals("a tap on a red cell would put the original back", original, grid.toggleColor(1, 1, red))
    assertEquals("asking must not change the cell", red, grid.getPixel(1, 1))
    assertEquals("a tap on another cell would paint the selected colour", red, grid.toggleColor(2, 2, red))
  }

  @Test
  fun clearingResetsTheRevertState() {
    val grid = PixelGridState()
    val original = grid.getPixel(2, 2)
    grid.paint(2, 2, red)
    grid.clear()

    grid.paint(2, 2, red)
    assertEquals("after a clear the tap paints, it does not revert", red, grid.getPixel(2, 2))
    grid.paint(2, 2, red)
    assertEquals(original, grid.getPixel(2, 2))
  }

  @Test
  fun paintingTheSelectedColourOnACellThatAlreadyHasItIsNotAnEdit() {
    val grid = PixelGridState()
    val original = grid.getPixel(4, 4)

    // The cell already shows the colour being painted and has no earlier colour remembered, so there is
    // nothing to revert to and nothing to paint: no edit, so no regeneration.
    grid.paint(4, 4, original)
    assertEquals(0, grid.revision)
    assertEquals(original, grid.getPixel(4, 4))
  }

  @Test
  fun gridSizeMinConstraint() {
    val min = MIN_GRID_SIZE
    val grid = PixelGridState(initialColumns = min, initialRows = min)
    assertEquals(min, grid.columns)
    assertEquals(min, grid.rows)

    grid.removeColumnLeft()
    assertEquals(min, grid.columns)
    grid.removeColumnRight()
    assertEquals(min, grid.columns)
    grid.removeRowTop()
    assertEquals(min, grid.rows)
    grid.removeRowBottom()
    assertEquals(min, grid.rows)

    // One cell above the minimum, shrinking is allowed.
    val larger = PixelGridState(initialColumns = min + 1, initialRows = min + 1)
    larger.removeColumnLeft()
    assertEquals(min, larger.columns)
    larger.removeRowTop()
    assertEquals(min, larger.rows)
  }

  @Test
  fun resizingGridPreservesPixels() {
    val grid = PixelGridState(initialColumns = 4, initialRows = 4)
    grid.paint(1, 1, red)
    assertEquals(red, grid.getPixel(1, 1))

    grid.addColumnRight()
    assertEquals(5, grid.columns)
    assertEquals(red, grid.getPixel(1, 1))

    grid.addColumnLeft()
    assertEquals(6, grid.columns)
    assertEquals(red, grid.getPixel(2, 1))

    grid.addRowBottom()
    assertEquals(5, grid.rows)
    assertEquals(red, grid.getPixel(2, 1))

    grid.addRowTop()
    assertEquals(6, grid.rows)
    assertEquals(red, grid.getPixel(2, 2))
  }

  @Test
  fun fillFloodsTheConnectedRegionAndStopsAtAWall() {
    val grid = PixelGridState(initialColumns = 4, initialRows = 4)
    // A full-height wall in column 1 splits the grid in two.
    for (row in 0 until 4) grid.paint(1, row, red)
    val revisionsBefore = grid.revision

    assertTrue("the flood filled something", grid.fill(0, 0, blue))

    assertEquals(blue, grid.getPixel(0, 0))
    assertEquals(blue, grid.getPixel(0, 3))
    assertEquals("the wall is not overwritten", red, grid.getPixel(1, 2))
    assertEquals("the far side is untouched", grid.defaultColor, grid.getPixel(2, 0))
    assertEquals("a flood is one edit", revisionsBefore + 1, grid.revision)
  }

  @Test
  fun fillDoesNotLeakThroughAMereDiagonalContact() {
    val grid = PixelGridState(initialColumns = 2, initialRows = 2)
    grid.paint(1, 0, red)
    grid.paint(0, 1, red)

    // (1,1) is only diagonally adjacent to the painted cells, so it is a different region.
    assertTrue(grid.fill(0, 0, blue))
    assertEquals(blue, grid.getPixel(0, 0))
    assertEquals("diagonal neighbour untouched", grid.defaultColor, grid.getPixel(1, 1))
  }

  @Test
  fun fillWithTheColourTheRegionAlreadyHasIsNotAnEdit() {
    val grid = PixelGridState(initialColumns = 3, initialRows = 3)
    val revisionsBefore = grid.revision

    assertFalse(grid.fill(1, 1, grid.defaultColor))

    assertEquals("nothing changed, so no regeneration", revisionsBefore, grid.revision)
  }

  @Test
  fun fillOutsideTheGridIsNotAnEdit() {
    val grid = PixelGridState(initialColumns = 3, initialRows = 3)
    val revisionsBefore = grid.revision

    assertFalse(grid.fill(-1, 0, red))
    assertFalse(grid.fill(3, 0, red))
    assertFalse(grid.fill(0, 3, red))

    assertEquals(revisionsBefore, grid.revision)
  }

  @Test
  fun fillReachesEveryCellWhenTheWholeGridIsOneRegion() {
    val grid = PixelGridState(initialColumns = 5, initialRows = 4)

    assertTrue(grid.fill(2, 2, red))

    for (row in 0 until 4) {
      for (col in 0 until 5) {
        assertEquals("cell ($col, $row)", red, grid.getPixel(col, row))
      }
    }
  }

  @Test
  fun aStrokePaintsACellWithoutTheTapToggle() {
    val grid = PixelGridState(initialColumns = 4, initialRows = 1)

    // A stroke crosses cells 0, 1 and 2 and then comes back over cell 1.
    for (col in listOf(0, 1, 2, 1)) grid.paintOver(col, 0, red)

    assertEquals(red, grid.getPixel(0, 0))
    assertEquals("crossed twice, still painted", red, grid.getPixel(1, 0))
    assertEquals(red, grid.getPixel(2, 0))
  }

  @Test
  fun aStrokeLeavesACellThatAlreadyHasTheColourAlone() {
    val grid = PixelGridState(initialColumns = 3, initialRows = 1)
    // One tap, so the cell remembers its previous colour and would revert on a second tap.
    grid.paint(1, 0, red)
    val revisionsBefore = grid.revision

    // Dragging a stroke over that cell must not count as the second tap.
    grid.paintOver(1, 0, red)

    assertEquals("the cell keeps the colour", red, grid.getPixel(1, 0))
    assertEquals("nothing changed, so no regeneration", revisionsBefore, grid.revision)
  }

  @Test
  fun aStrokeIsPaintOnlyAndDoesNotMakeTheNextTapRevert() {
    val grid = PixelGridState(initialColumns = 2, initialRows = 1)
    grid.paintOver(0, 0, red)
    val revisionsBefore = grid.revision

    // Nothing was remembered before the stroke, so a tap on the cell has nothing to put back.
    grid.paint(0, 0, red)

    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(revisionsBefore, grid.revision)
  }
}