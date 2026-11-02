package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moving a drawing onto a new palette: every colour it holds becomes the closest colour the new
 * palette has, and the whole move is one undo step.
 *
 * Pure grid state, so it runs on the JVM with the rest of the engine tests.
 */
class PaletteMigrationTest {

  private val red = Color(0xFFFF0000)
  private val blue = Color(0xFF0000FF)
  private val nearRed = Color(0xFFEE1010)
  private val nearBlue = Color(0xFF1010EE)

  @Test
  fun everyCellMovesToItsClosestColorInTheNewPalette() {
    val grid = PixelGridState(
      palette = listOf(Color.Black),
      initialColumns = 2,
      initialRows = 1,
    )
    grid.paintOver(0, 0, nearRed)
    grid.paintOver(1, 0, nearBlue)
    grid.clearHistory()

    val changed = grid.migrateColors(listOf(red, blue))

    assertEquals("both cells changed", 2, changed)
    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(blue, grid.getPixel(1, 0))
  }

  @Test
  fun theMigrationIsOneUndoStep() {
    val grid = PixelGridState(
      palette = listOf(Color.Black),
      initialColumns = 2,
      initialRows = 1,
    )
    grid.paintOver(0, 0, nearRed)
    grid.paintOver(1, 0, nearBlue)
    grid.clearHistory()

    grid.migrateColors(listOf(red, blue))
    assertTrue("the migration can be undone", grid.canUndo())

    grid.undo()

    assertEquals(nearRed, grid.getPixel(0, 0))
    assertEquals(nearBlue, grid.getPixel(1, 0))
    assertFalse("one step, not one per cell", grid.canUndo())
  }

  @Test
  fun aDrawingAlreadyOnThePaletteIsNotAnEdit() {
    val grid = PixelGridState(palette = listOf(red, blue), initialColumns = 1, initialRows = 1)
    grid.clearHistory()

    val changed = grid.migrateColors(listOf(red, blue))

    assertEquals(0, changed)
    assertFalse("nothing changed, so nothing to undo", grid.canUndo())
  }

  @Test
  fun theDefaultColorFollowsTheNewPalette() {
    // White paper migrates to the nearer of the two; either way the paper becomes a palette colour.
    val grid = PixelGridState(palette = listOf(Color.White), initialColumns = 1, initialRows = 1)

    grid.migrateColors(listOf(red, blue))

    assertEquals("the darkest colour of the new palette", blue, grid.defaultColor)
    assertTrue("and the paper is in the palette", grid.getPixel(0, 0) in listOf(red, blue))
  }

  @Test
  fun anEmptyPaletteLeavesTheDrawingAlone() {
    val grid = PixelGridState(palette = listOf(Color.Black), initialColumns = 1, initialRows = 1)
    grid.paintOver(0, 0, nearRed)
    grid.clearHistory()

    val changed = grid.migrateColors(emptyList())

    assertEquals(0, changed)
    assertEquals(nearRed, grid.getPixel(0, 0))
    assertFalse(grid.canUndo())
  }
}
