package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shapes the Line, Rectangle and Ellipse tools would paint, and the output shapes the aspect menu
 * offers. Both are pure arithmetic, so they belong here rather than on a device.
 */
class ShapeAndOutputTest {

  private fun line(from: Pair<Int, Int>, to: Pair<Int, Int>, columns: Int = 5, rows: Int = 5) =
    shapeCells(EditorMode.Line, from, to, columns, rows).toSet()

  private fun rectangle(from: Pair<Int, Int>, to: Pair<Int, Int>, columns: Int = 5, rows: Int = 5) =
    shapeCells(EditorMode.Rectangle, from, to, columns, rows).toSet()

  private fun ellipse(from: Pair<Int, Int>, to: Pair<Int, Int>, columns: Int = 5, rows: Int = 5) =
    shapeCells(EditorMode.Ellipse, from, to, columns, rows).toSet()

  private fun index(col: Int, row: Int, columns: Int = 5) = row * columns + col

  // ───────────────────────────────────────────────────────────────────────────
  // Line
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun aLineIncludesBothEnds() {
    val cells = line(1 to 1, 3 to 1)

    assertTrue(index(1, 1) in cells)
    assertTrue(index(3, 1) in cells)
  }

  @Test
  fun aLineIsUnbroken() {
    // No cell along a diagonal may be skipped, or the line would have holes in it.
    val cells = line(0 to 0, 4 to 4)
    for (i in 0..4) {
      assertTrue("diagonal cell $i missing", index(i, i) in cells)
    }
    assertEquals(5, cells.size)
  }

  @Test
  fun aLineStaysInsideTheGrid() {
    val cells = line(0 to 0, 9 to 9)

    assertTrue(cells.isNotEmpty())
    for (cell in cells) {
      val col = cell % 5
      val row = cell / 5
      assertTrue("($col, $row) is outside", col in 0 until 5 && row in 0 until 5)
    }
  }

  @Test
  fun aLineFromACellToItselfIsThatOneCell() {
    assertEquals(setOf(index(2, 3)), line(2 to 3, 2 to 3))
  }

  @Test
  fun aLineIsTheSameWhicheverEndIsDraggedFrom() {
    assertEquals(line(0 to 0, 4 to 2), line(4 to 2, 0 to 0))
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Rectangle
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun aRectangleIsAnOutlineNotAFill() {
    val cells = rectangle(1 to 1, 3 to 3)

    assertTrue("corner", index(1, 1) in cells)
    assertTrue("edge", index(2, 1) in cells)
    assertFalse("the inside is left alone", index(2, 2) in cells)
    assertEquals("eight cells around a 3x3 box", 8, cells.size)
  }

  @Test
  fun aRectangleIsTheSameWhicheverCornerIsDraggedFrom() {
    assertEquals(rectangle(1 to 1, 3 to 3), rectangle(3 to 3, 1 to 1))
  }

  @Test
  fun aOneCellRectangleIsOneCell() {
    assertEquals(setOf(index(2, 2)), rectangle(2 to 2, 2 to 2))
  }

  @Test
  fun aRectangleIsClippedToTheGrid() {
    val cells = rectangle(3 to 3, 9 to 9)

    assertTrue(cells.isNotEmpty())
    for (cell in cells) {
      assertTrue(cell % 5 in 0 until 5 && cell / 5 in 0 until 5)
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Ellipse
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun anEllipseIsAnOutlineThatTouchesAllFourSides() {
    val cells = ellipse(0 to 0, 4 to 4)

    assertTrue("left", index(0, 2) in cells)
    assertTrue("right", index(4, 2) in cells)
    assertTrue("top", index(2, 0) in cells)
    assertTrue("bottom", index(2, 4) in cells)
    assertFalse("the middle is left alone", index(2, 2) in cells)
  }

  @Test
  fun anEllipseCurveHasNoGaps() {
    // Every row the ellipse passes through must contribute at least one cell, or the outline would have a
    // hole in it where the curve turns.
    val cells = ellipse(0 to 0, 6 to 6, columns = 7, rows = 7)
    val rows = cells.map { it / 7 }.toSet()

    assertEquals("every row from top to bottom", (0..6).toSet(), rows)
  }

  @Test
  fun anEllipseStaysInsideItsBox() {
    val cells = ellipse(1 to 1, 3 to 3)

    for (cell in cells) {
      val col = cell % 5
      val row = cell / 5
      assertTrue("($col, $row) is outside the box", col in 1..3 && row in 1..3)
    }
  }

  @Test
  fun anEllipseInAOneCellBoxIsThatOneCell() {
    assertEquals(setOf(index(2, 2)), ellipse(2 to 2, 2 to 2))
  }

  @Test
  fun anEllipseIsSymmetric() {
    val cells = ellipse(0 to 0, 4 to 4)

    for (cell in cells) {
      val col = cell % 5
      val row = cell / 5
      assertTrue("($col, $row) has no mirror", index(4 - col, row) in cells)
      assertTrue("($col, $row) has no vertical mirror", index(col, 4 - row) in cells)
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Output shape
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun theSquareOutputIsSquareAndKeepsTheCellCount() {
    val (columns, rows) = WfcGenerator.gridForAspect(1f)

    assertTrue("a square is square, give or take a rounding cell", abs(columns - rows) <= 1)
    assertTrue("cell count stays near the default", abs(columns * rows - WfcGenerator.DEFAULT_CELL_COUNT) < 1000)
  }

  @Test
  fun theLandscapeAndPortraitOutputsAreTheRightWayRound() {
    val (wideColumns, wideRows) = WfcGenerator.gridForAspect(16f / 9f)
    val (tallColumns, tallRows) = WfcGenerator.gridForAspect(9f / 16f)

    assertTrue(wideColumns > wideRows)
    assertTrue(tallColumns < tallRows)
  }

  @Test
  fun changingTheShapeDoesNotChangeTheCostOfSoling() {
    // Time and memory follow the number of cells, so every offered shape has to stay near the default.
    val shapes = listOf(9f / 16f, 3f / 4f, 1f, 4f / 3f, 16f / 9f)

    for (aspect in shapes) {
      val (columns, rows) = WfcGenerator.gridForAspect(aspect)
      val cells = columns * rows
      assertTrue(
        "aspect $aspect gives $cells cells, default is ${WfcGenerator.DEFAULT_CELL_COUNT}",
        cells > WfcGenerator.DEFAULT_CELL_COUNT * 8 / 10,
      )
      assertTrue(
        "aspect $aspect gives $cells cells, default is ${WfcGenerator.DEFAULT_CELL_COUNT}",
        cells < WfcGenerator.DEFAULT_CELL_COUNT * 12 / 10,
      )
    }
  }

  @Test
  fun theDefaultPortraitShapeMatchesTheDefaultBitmap() {
    val (columns, rows) = WfcGenerator.gridForAspect(9f / 16f)

    assertEquals(WfcGenerator.DEFAULT_COLUMNS, columns)
    assertEquals(WfcGenerator.DEFAULT_ROWS, rows)
  }

  @Test
  fun anAbsurdAspectIsClampedRatherThanProducingADegenerateGrid() {
    for (aspect in listOf(0.001f, 1000f, -1f, Float.NaN)) {
      val (columns, rows) = WfcGenerator.gridForAspect(aspect)
      assertTrue("columns $columns for aspect $aspect", columns in 1..WfcGenerator.DEFAULT_CELL_COUNT)
      assertTrue("rows $rows for aspect $aspect", rows in 1..WfcGenerator.DEFAULT_CELL_COUNT)
    }
  }
}
