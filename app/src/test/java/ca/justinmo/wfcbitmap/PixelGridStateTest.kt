package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    val grid = BitmapEditor.PixelGridState()
    val before = grid.getPixel(3, 2)
    grid.paint(3, 2, red)
    assertEquals(red, grid.getPixel(3, 2))
    assertNotEquals(before, grid.getPixel(3, 2))
    assertEquals("one edit", 1, grid.revision)
  }

  @Test
  fun tappingTheSameCellAgainPutsThePreviousColourBack() {
    val grid = BitmapEditor.PixelGridState()
    val original = grid.getPixel(3, 2)

    grid.paint(3, 2, red)
    grid.paint(3, 2, red)

    assertEquals("the second tap reverts the first", original, grid.getPixel(3, 2))
    assertEquals("both taps are edits", 2, grid.revision)
  }

  @Test
  fun theThirdTapPaintsAgain() {
    val grid = BitmapEditor.PixelGridState()
    val original = grid.getPixel(3, 2)

    grid.paint(3, 2, red)
    grid.paint(3, 2, red)
    grid.paint(3, 2, red)

    assertEquals(red, grid.getPixel(3, 2))
    assertNotEquals(original, grid.getPixel(3, 2))
  }

  @Test
  fun revertingGoesToTheColourFromBeforeThatPaintNotToTheDefault() {
    val grid = BitmapEditor.PixelGridState()
    grid.paint(1, 1, red)

    grid.paint(1, 1, blue)
    assertEquals(blue, grid.getPixel(1, 1))
    grid.paint(1, 1, blue)

    assertEquals("reverting a blue paint restores the red underneath", red, grid.getPixel(1, 1))
  }

  @Test
  fun tappingAnotherCellInBetweenDoesNotCostTheFirstCellATap() {
    val grid = BitmapEditor.PixelGridState()
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
    val grid = BitmapEditor.PixelGridState()
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
  fun clearingResetsTheRevertState() {
    val grid = BitmapEditor.PixelGridState()
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
    val grid = BitmapEditor.PixelGridState()
    val original = grid.getPixel(4, 4)

    // The cell already shows the colour being painted and has no earlier colour remembered, so there is
    // nothing to revert to and nothing to paint: no edit, so no regeneration.
    grid.paint(4, 4, original)
    assertEquals(0, grid.revision)
    assertEquals(original, grid.getPixel(4, 4))
  }
}
