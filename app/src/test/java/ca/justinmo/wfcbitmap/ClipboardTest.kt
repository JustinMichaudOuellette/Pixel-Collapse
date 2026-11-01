package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copying a block of the drawing and pasting it back somewhere else.
 *
 * Plain state, so it runs on the JVM: what a paste writes into the grid is exactly the kind of thing that
 * should not need a screen to be checked. The rules pinned here are the ones a person would notice -- the
 * block is the block, a paste near an edge stays whole, and a paste is one undo step.
 */
class ClipboardTest {

  private val red = Color(0xFFBE4A2F)
  private val blue = Color(0xFF5A6988)
  private val green = Color(0xFF63C74D)

  private fun grid(columns: Int = 4, rows: Int = 3) =
    BitmapEditor.PixelGridState(initialColumns = columns, initialRows = rows)

  /** Paints a 2x2 block of distinct colours in the top-left corner and returns the grid. */
  private fun gridWithBlock(): BitmapEditor.PixelGridState {
    val grid = grid()
    grid.paintOver(0, 0, red)
    grid.paintOver(1, 0, blue)
    grid.paintOver(0, 1, green)
    grid.paintOver(1, 1, red)
    return grid
  }

  @Test
  fun aCopiedBlockKeepsTheColoursWhereTheyWereInsideIt() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))

    assertEquals(2, block.width)
    assertEquals(2, block.height)
    assertEquals(listOf(red, blue, green, red), block.colors)
  }

  @Test
  fun pastingPutsTheBlockWhereItWasAimed() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))

    assertTrue(grid.paste(2, 1, block))

    assertEquals(red, grid.getPixel(2, 1))
    assertEquals(blue, grid.getPixel(3, 1))
    assertEquals(green, grid.getPixel(2, 2))
    assertEquals(red, grid.getPixel(3, 2))
  }

  @Test
  fun aPasteNearTheEdgeDropsTheWholeBlockInsideTheGrid() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))

    // Aimed past the right and bottom edges: the corner is clamped, so the block is not cut in half.
    assertTrue(grid.paste(3, 2, block))

    assertEquals(red, grid.getPixel(2, 1))
    assertEquals(blue, grid.getPixel(3, 1))
    assertEquals(green, grid.getPixel(2, 2))
    assertEquals(red, grid.getPixel(3, 2))
  }

  @Test
  fun aPasteIsOneUndoStep() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))
    val before = List(grid.columns * grid.rows) { grid.pixels[it] }

    grid.paste(2, 1, block)
    grid.undo()

    assertEquals(before, List(grid.columns * grid.rows) { grid.pixels[it] })
  }

  @Test
  fun aPasteOntoIdenticalColoursLeavesTheHistoryAlone() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))
    val depthBefore = grid.undoDepth

    // The same block where it already is: nothing changes, so nothing is recorded. (The depth is compared
    // rather than canUndo() because drawing the block in the first place is already undoable.)
    assertFalse(grid.paste(0, 0, block))
    assertEquals("a paste that changed nothing must not add an undo step", depthBefore, grid.undoDepth)
  }

  @Test
  fun aCopyOutlivesTheDrawingItCameFrom() {
    val grid = gridWithBlock()
    val block = grid.copyBlock(BitmapEditor.CellRect(0, 0, 1, 1))

    // The clipboard is a value, not a view of the grid: clearing and undoing leave it intact.
    grid.clear()
    grid.undo()

    val second = grid(columns = 5, rows = 5)
    assertTrue(second.paste(3, 3, block))
    assertEquals(blue, second.getPixel(4, 3))
  }

  @Test
  fun anEmptyBlockIsNotPasted() {
    val grid = grid()
    val empty = BitmapEditor.CopiedBlock(width = 0, height = 0, colors = emptyList())

    assertTrue(empty.isEmpty)
    assertFalse(grid.paste(0, 0, empty))
  }
}
