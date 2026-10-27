package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The editor's tap behaviour: a tap paints, and a second tap on the cell the last tap painted puts the
 * previous colour back.
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
  fun tappingAnotherCellInBetweenStartsANewPaint() {
    val grid = BitmapEditor.PixelGridState()
    grid.paint(0, 0, red)

    // A different cell, then back to the first one: that is a new paint, not a revert.
    grid.paint(1, 0, red)
    grid.paint(0, 0, blue)

    assertEquals(blue, grid.getPixel(0, 0))
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
  fun paintingAColourOnACellThatAlreadyHasItIsNotAnEdit() {
    val grid = BitmapEditor.PixelGridState()
    grid.paint(4, 4, red)
    grid.paint(5, 4, blue)
    val revisionAfterTwoPaints = grid.revision

    // (4,4) is already red and is not the cell the last tap painted, so this is a no-op rather than a
    // revert: reaching back to an earlier cell must not undo anything by accident.
    grid.paint(4, 4, red)
    assertEquals(revisionAfterTwoPaints, grid.revision)
    assertEquals(red, grid.getPixel(4, 4))
  }
}
