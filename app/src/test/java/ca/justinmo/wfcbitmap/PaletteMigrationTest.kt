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

  @Test
  fun adoptingAPaletteKeepsTheDrawingAndAddsNoUndoStep() {
    // The camera tool's commit: the photo already holds the palette's own colours, so nothing is
    // recoloured -- only the colour a revealed cell falls back to moves to that palette.
    val grid = PixelGridState(palette = listOf(Color.Black), initialColumns = 2, initialRows = 1)
    grid.paintOver(0, 0, nearRed)
    grid.paintOver(1, 0, nearBlue)
    grid.clearHistory()

    grid.adoptPalette(listOf(red, blue))

    assertEquals("no cell was recoloured", nearRed, grid.getPixel(0, 0))
    assertEquals(nearBlue, grid.getPixel(1, 0))
    assertEquals("the darkest of the adopted palette", blue, grid.defaultColor)
    assertFalse("nothing was drawn, so there is nothing to undo", grid.canUndo())
  }
}