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
 * The edits the tools make, and the history they go into.
 *
 * Everything here is plain state, so it runs on the JVM: what a tool does to the drawing is not something
 * that needs a screen to be wrong about.
 */
class PixelGridEditsTest {

  private val red = Color(0xFFBE4A2F)
  private val blue = Color(0xFF5A6988)
  private val green = Color(0xFF63C74D)

  private fun grid(columns: Int = 4, rows: Int = 3) =
    PixelGridState(initialColumns = columns, initialRows = rows)

  // ───────────────────────────────────────────────────────────────────────────
  // Undo and redo
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun aFreshDrawingHasNothingToUndo() {
    val grid = grid()

    assertFalse(grid.canUndo())
    assertFalse(grid.canRedo())
  }

  @Test
  fun undoPutsTheDrawingBackAndRedoReplaysIt() {
    val grid = grid()
    val before = grid.getPixel(1, 1)

    grid.paintOver(1, 1, red)
    assertTrue(grid.canUndo())

    grid.undo()
    assertEquals(before, grid.getPixel(1, 1))
    assertTrue(grid.canRedo())

    grid.redo()
    assertEquals(red, grid.getPixel(1, 1))
    assertFalse(grid.canRedo())
  }

  @Test
  fun aNewEditDropsTheRedoTrail() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    grid.undo()
    assertTrue(grid.canRedo())

    grid.paintOver(2, 2, blue)

    assertFalse("the undone branch is gone", grid.canRedo())
  }

  @Test
  fun undoRestoresTheGridSizeToo() {
    val grid = grid(columns = 4, rows = 3)
    grid.addColumnRight()
    grid.addRowBottom()

    // Back to 5x3: the row was added last, so it comes off first.
    grid.undo()
    assertEquals(5, grid.columns)
    assertEquals(3, grid.rows)
    grid.undo()
    assertEquals(4, grid.columns)
    assertEquals(3, grid.rows)
  }

  @Test
  fun undoAfterRotateRestoresTheOriginalShapeAndPixels() {
    val grid = grid(columns = 4, rows = 3)
    grid.paintOver(0, 0, red)

    grid.rotateClockwise()
    assertEquals(3, grid.columns)
    assertEquals(4, grid.rows)

    grid.undo()
    assertEquals(4, grid.columns)
    assertEquals(3, grid.rows)
    assertEquals(red, grid.getPixel(0, 0))
  }

  @Test
  fun oneEditSessionIsOneUndoStep() {
    val grid = grid()

    // A drag across five cells, the way a stroke reports it.
    grid.beginEdit()
    for (col in 0 until 4) grid.paintOver(col, 0, red)
    grid.paintOver(3, 1, red)
    grid.endEdit()
    assertEquals("one step for the whole stroke", 1, grid.undoDepth)

    grid.undo()
    for (col in 0 until 4) assertEquals(grid.defaultColor, grid.getPixel(col, 0))
    assertEquals(grid.defaultColor, grid.getPixel(3, 1))
    assertFalse("nothing left to undo", grid.canUndo())
  }

  @Test
  fun anEditSessionThatChangedNothingLeavesNoStep() {
    val grid = grid()

    grid.beginEdit()
    // Painting the colour a cell already has is not an edit.
    grid.paintOver(0, 0, grid.defaultColor)
    grid.endEdit()

    assertFalse(grid.canUndo())
    assertEquals(0, grid.undoDepth)
  }

  @Test
  fun anEditSessionDropsTheRedoTrailOnlyWhenItDoesSomething() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    grid.undo()

    grid.beginEdit()
    grid.endEdit()
    assertTrue("an empty session is not an edit", grid.canRedo())

    grid.beginEdit()
    grid.paintOver(1, 1, blue)
    grid.endEdit()
    assertFalse(grid.canRedo())
  }

  @Test
  fun clearingTheHistoryLeavesNothingToUndo() {
    val grid = grid()
    grid.paintOver(0, 0, red)

    grid.clearHistory()

    assertFalse(grid.canUndo())
    assertFalse(grid.canRedo())
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Replace colour
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun replaceChangesEveryCellOfThatColourAndNothingElse() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    grid.paintOver(3, 2, red)
    grid.paintOver(1, 1, blue)

    assertEquals(2, grid.replaceColor(red, green))

    assertEquals(green, grid.getPixel(0, 0))
    assertEquals(green, grid.getPixel(3, 2))
    assertEquals("another colour is left alone", blue, grid.getPixel(1, 1))
    assertEquals(grid.defaultColor, grid.getPixel(2, 1))
  }

  @Test
  fun replaceIsOneUndoStep() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    grid.paintOver(1, 0, red)
    val stepsBefore = grid.undoDepth

    grid.replaceColor(red, green)
    assertEquals(stepsBefore + 1, grid.undoDepth)

    grid.undo()
    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(red, grid.getPixel(1, 0))
  }

  @Test
  fun replacingAColourWithItselfOrWithAnAbsentColourIsNotAnEdit() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    val stepsBefore = grid.undoDepth

    assertEquals(0, grid.replaceColor(red, red))
    assertEquals("no cell has this colour", 0, grid.replaceColor(blue, green))
    assertEquals(stepsBefore, grid.undoDepth)
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Mirror and rotate
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun flipHorizontalMirrorsColumns() {
    val grid = grid(columns = 4, rows = 2)
    grid.paintOver(0, 0, red)

    grid.flipHorizontal()

    assertEquals(grid.defaultColor, grid.getPixel(0, 0))
    assertEquals(red, grid.getPixel(3, 0))
  }

  @Test
  fun flipVerticalMirrorsRows() {
    val grid = grid(columns = 2, rows = 4)
    grid.paintOver(0, 0, red)

    grid.flipVertical()

    assertEquals(grid.defaultColor, grid.getPixel(0, 0))
    assertEquals(red, grid.getPixel(0, 3))
  }

  @Test
  fun rotateClockwiseSwapsTheSidesAndMovesTheCells() {
    val grid = grid(columns = 3, rows = 2)
    // Top-left corner of a 3x2 grid.
    grid.paintOver(0, 0, red)

    grid.rotateClockwise()

    assertEquals("columns and rows swap", 2, grid.columns)
    assertEquals(3, grid.rows)
    // Clockwise: the top-left cell ends up in the top-right corner of the turned grid.
    assertEquals(red, grid.getPixel(1, 0))
    assertEquals(grid.defaultColor, grid.getPixel(0, 0))
  }

  @Test
  fun rotatingFourTimesIsTheOriginalDrawing() {
    val grid = grid(columns = 3, rows = 2)
    grid.paintOver(0, 1, blue)
    grid.paintOver(2, 0, red)
    val original = grid.pixels.toList()

    repeat(4) { grid.rotateClockwise() }

    assertEquals(3, grid.columns)
    assertEquals(2, grid.rows)
    assertEquals(original, grid.pixels.toList())
  }

  @Test
  fun mirroringIsUndoable() {
    val grid = grid(columns = 3, rows = 2)
    grid.paintOver(0, 0, red)

    grid.flipHorizontal()
    assertEquals(red, grid.getPixel(2, 0))

    grid.undo()
    assertEquals(red, grid.getPixel(0, 0))
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Selection and moving
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun movingASelectionLeavesTheCellsBehindEmpty() {
    val grid = grid(columns = 4, rows = 2)
    grid.paintOver(0, 0, red)
    grid.paintOver(1, 0, blue)

    val moved = grid.moveCells(CellRect(0, 0, 1, 0), dCol = 2, dRow = 0)

    assertTrue(moved)
    assertEquals(grid.defaultColor, grid.getPixel(0, 0))
    assertEquals(grid.defaultColor, grid.getPixel(1, 0))
    assertEquals(red, grid.getPixel(2, 0))
    assertEquals(blue, grid.getPixel(3, 0))
  }

  @Test
  fun movingASelectionOffTheGridIsClampedNotLost() {
    val grid = grid(columns = 4, rows = 2)
    grid.paintOver(0, 0, red)

    grid.moveCells(CellRect(0, 0, 0, 0), dCol = 99, dRow = 0)

    assertEquals("the selection stops at the last column", red, grid.getPixel(3, 0))
  }

  @Test
  fun movingBackToWhereItStartedChangesNothing() {
    val grid = grid()
    grid.paintOver(1, 1, red)

    assertFalse(grid.moveCells(CellRect(1, 1, 1, 1), dCol = 0, dRow = 0))
  }

  @Test
  fun movedRectFollowsTheSameClampingAsTheMove() {
    val grid = grid(columns = 4, rows = 2)

    val target = grid.movedRect(CellRect(0, 0, 1, 0), dCol = 99, dRow = 0)

    assertEquals(2, target.left)
    assertEquals(3, target.right)
  }

  @Test
  fun aMoveIsOneUndoStep() {
    val grid = grid()
    grid.paintOver(0, 0, red)
    val stepsBefore = grid.undoDepth

    grid.moveCells(CellRect(0, 0, 0, 0), dCol = 1, dRow = 1)
    assertEquals(stepsBefore + 1, grid.undoDepth)

    grid.undo()
    assertEquals(red, grid.getPixel(0, 0))
  }

  @Test
  fun stampWritesALiftedBlockBack() {
    val grid = grid(columns = 3, rows = 1)
    grid.paintOver(0, 0, red)
    grid.paintOver(1, 0, blue)
    val lifted = grid.indicesOf(CellRect(0, 0, 1, 0)).map { grid.pixels[it] }

    grid.paintCells(grid.indicesOf(CellRect(0, 0, 1, 0)), grid.defaultColor)
    assertTrue(grid.stamp(CellRect(1, 0, 2, 0), lifted))

    assertEquals("the block moved a column right", red, grid.getPixel(1, 0))
    assertEquals(blue, grid.getPixel(2, 0))
  }

  @Test
  fun aSelectionRectangleNormalisesItsCorners() {
    val rect = CellRect.between(3 to 2, 1 to 0, columns = 4, rows = 3)

    assertEquals(CellRect(1, 0, 3, 2), rect)
  }

  @Test
  fun aSelectionRectangleOutsideTheGridIsDropped() {
    assertFalse(CellRect.between(0 to 0, 9 to 0, columns = 4, rows = 3) != null)
    assertFalse(CellRect.between(-1 to 0, 1 to 0, columns = 4, rows = 3) != null)
  }

  @Test
  fun aSelectionKnowsWhichCellsAreInsideIt() {
    val rect = CellRect(1, 1, 2, 2)

    assertTrue(1 to 1 in rect)
    assertTrue(2 to 2 in rect)
    assertFalse(0 to 1 in rect)
    assertFalse(3 to 2 in rect)
  }

  @Test
  fun paintingACellOutsideTheGridIsIgnored() {
    val grid = grid(columns = 2, rows = 2)
    grid.paintOver(5, 0, red)
    grid.paint(0, 5, red)

    assertEquals(0, grid.undoDepth)
  }

  /**
   * A tap on a cell that already shows the tapped colour takes it away. While the cell is drawn in this
   * session that means putting back what was there; a cell that was already the tapped colour -- restored
   * from a pattern, or painted before the app was closed -- has nothing to put back, so it reverts to the
   * drawing's own background.
   */
  @Test
  fun tappingAColourThatIsAlreadyThereGoesBackToTheBackground() {
    // paper is the lighter colour, so the palette's darkest -- the default -- is ink, while the drawing's
    // background is the paper. The tap must use the latter.
    val paper = Color(0xFFE8D8B0)
    val ink = Color(0xFFBE4A2F)
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = 9, initialRows = 9)
    for (y in 0 until 9) {
      for (x in 0 until 9) grid.setPixel(x, y, paper)
    }
    grid.setPixel(4, 4, ink)
    assertEquals(ink, grid.defaultColor)

    grid.paint(4, 4, ink)

    assertEquals(paper, grid.getPixel(4, 4))
  }

  @Test
  fun tappingTheBackgroundItselfChangesNothing() {
    val paper = Color(0xFFE8D8B0)
    val grid = PixelGridState(palette = listOf(red, paper), initialColumns = 9, initialRows = 9)
    for (y in 0 until 9) {
      for (x in 0 until 9) grid.setPixel(x, y, paper)
    }
    val steps = grid.undoDepth

    grid.paint(4, 4, paper)

    assertEquals(paper, grid.getPixel(4, 4))
    assertEquals("a tap on the paper is not an edit", steps, grid.undoDepth)
  }

  @Test
  fun aRememberedColourStillWinsOverTheBackground() {
    val grid = grid()
    val before = grid.getPixel(1, 1)

    grid.paint(1, 1, red)
    assertEquals(red, grid.getPixel(1, 1))

    grid.paint(1, 1, red)
    assertEquals(before, grid.getPixel(1, 1))
  }

  @Test
  fun paintCellsIsOneEditHoweverManyCellsItTouches() {
    val grid = grid()
    val stepsBefore = grid.undoDepth

    val painted = grid.paintCells(listOf(0, 1, 2, 3), red)

    assertEquals(4, painted)
    assertEquals(stepsBefore + 1, grid.undoDepth)
    assertEquals(red, grid.getPixel(0, 0))
    assertEquals(red, grid.getPixel(3, 0))
    assertNotEquals(grid.defaultColor, grid.getPixel(2, 0))
  }

  /**
   * Every edit has to record the drawing as it was *before* it ran, not after. A step that is recorded
   * once the pixels have already changed looks like an undo that does nothing, which is how a fill that
   * painted first and recorded afterwards behaved.
   */
  @Test
  fun everyKindOfEditUndoesBackToWhatWasThereBefore() {
    // Each case prepares a drawing, snapshots it, makes one edit, undoes it, and expects the snapshot back.
    data class Case(
      val name: String,
      val prepare: (PixelGridState) -> Unit = {},
      val edit: (PixelGridState) -> Unit,
    )

    val cases = listOf(
      Case("paint") { g -> g.paint(1, 1, red) },
      Case("paintOver") { g -> g.paintOver(1, 1, red) },
      Case("setPixel") { g -> g.setPixel(1, 1, red) },
      Case("fill") { g -> g.fill(0, 0, red) },
      Case("paintCells") { g -> g.paintCells(listOf(0, 1, 2), red) },
      Case("replaceColor", prepare = { g -> g.paintOver(0, 0, red) }, edit = { g -> g.replaceColor(red, green) }),
      Case("clear") { g -> g.clear() },
      Case("flipHorizontal") { g -> g.flipHorizontal() },
      Case("flipVertical") { g -> g.flipVertical() },
      Case("rotateClockwise") { g -> g.rotateClockwise() },
      Case("addColumn") { g -> g.addColumnRight() },
      Case("removeRow") { g -> g.removeRowBottom() },
      Case("moveCells") { g -> g.moveCells(CellRect(0, 0, 1, 1), dCol = 1, dRow = 1) },
      Case("stamp") { g -> g.stamp(CellRect(0, 0, 1, 1), listOf(red, red, blue, blue)) },
    )

    for (case in cases) {
      // Big enough that the shrink edits are allowed at all: the grid has a minimum size.
      val grid = grid(columns = 8, rows = 8)
      grid.paintOver(1, 1, blue)
      case.prepare(grid)
      val drawing = grid.pixels.toList()
      val columns = grid.columns
      val rows = grid.rows

      case.edit(grid)
      grid.undo()

      assertEquals("${case.name} did not undo its columns", columns, grid.columns)
      assertEquals("${case.name} did not undo its rows", rows, grid.rows)
      assertEquals("${case.name} did not undo its pixels", drawing, grid.pixels.toList())
    }
  }
}