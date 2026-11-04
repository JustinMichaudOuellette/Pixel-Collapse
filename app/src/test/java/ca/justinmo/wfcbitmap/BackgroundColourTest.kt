package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Finding the colour a pattern is drawn on.
 *
 * New rows and columns have to be filled with it: the palette's darkest swatch is the right answer for
 * an empty drawing, but on one with a background of its own it lays a stripe of the wrong colour along
 * the new edge.
 */
class BackgroundColourTest {

  private val paper = Color(0xFFE8D8B0)
  private val ink = Color(0xFFBE4A2F)
  private val other = Color(0xFF2F4ABE)

  private fun grid(columns: Int, rows: Int, fill: Color) = List(columns * rows) { fill }

  @Test
  fun theColourAtTheEdgeWinsOverALargerSubject() {
    // A 21x21 sheet with a 15x15 subject on it: the subject has more cells (225 to 216), but it is the
    // sheet that is the background, and only counting the edge gets that right.
    val pixels = grid(21, 21, paper).toMutableList()
    for (y in 3..17) {
      for (x in 3..17) pixels[y * 21 + x] = ink
    }

    assertEquals(paper, backgroundColour(pixels, 21, 21, fallback = other))
  }

  @Test
  fun anInteriorColourFourTimesTheRingIsStillTheSubject() {
    // The crossing point: 80 edge cells of paper are worth 320, so a subject of more than 320 cells
    // wins. 15x15 = 225 does not, which is the margin this weighting is meant to have.
    val pixels = grid(21, 21, paper).toMutableList()
    for (y in 2..18) {
      for (x in 2..18) pixels[y * 21 + x] = ink
    }

    // 17x17 = 289 subject cells against 152 paper ones, 80 of them on the edge.
    assertEquals(paper, backgroundColour(pixels, 21, 21, fallback = other))
  }

  @Test
  fun aDrawingThatIsNearlyAllOneColourIsThatColour() {
    val pixels = grid(21, 21, paper).toMutableList()
    pixels[10 * 21 + 10] = ink

    assertEquals(paper, backgroundColour(pixels, 21, 21, fallback = other))
  }

  @Test
  fun aDrawingOfOneColourIsItsOwnBackground() {
    assertEquals(ink, backgroundColour(grid(7, 7, ink), 7, 7, fallback = paper))
  }

  @Test
  fun anEmptyDrawingFallsBackToThePalettesOwn() {
    assertEquals(other, backgroundColour(emptyList(), 0, 0, fallback = other))
    assertEquals(other, backgroundColour(grid(4, 4, paper), 9, 9, fallback = other))
  }

  @Test
  fun theSavedBaconsBackgroundIsItsNavy() {
    // The drawing that was on the device: orange letters on the editor's dark navy, with the navy all
    // round the edge -- so the plain count and the weighted one agree here, which is the sanity check.
    assertEquals(
      Color(BaconPattern.BACKGROUND),
      backgroundColour(
        BaconPattern.pixels.map { Color(it) },
        BaconPattern.columns,
        BaconPattern.rows,
        fallback = Color(BaconPattern.INK),
      ),
    )
  }

  @Test
  fun addedCellsUseTheDrawingsPaperNotThePalettesDarkest() {
    // paper is the lighter of the two, so the palette's darkest swatch -- the fallback colour -- would
    // be ink. The new row has to come out paper.
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = 9, initialRows = 9)
    for (y in 0 until 9) {
      for (x in 0 until 9) grid.setPixel(x, y, paper)
    }
    for (y in 3..5) {
      for (x in 3..5) grid.setPixel(x, y, ink)
    }

    grid.addRowBottom()
    grid.addColumnRight()

    assertEquals(grid.defaultColor, ink)
    assertEquals(paper, grid.getPixel(0, 9))
    assertEquals(paper, grid.getPixel(8, 9))
    assertEquals(paper, grid.getPixel(9, 0))
  }

  @Test
  fun spaceAddedForAGroupingIsPaperToo() {
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = 10, initialRows = 10)
    // A 10x10 sheet with a small subject in it, under 3-pixel cells: the grid grows to the smallest legal
    // 9x9 grouping, which is 21x21, and the added space is the sheet's own colour.
    for (y in 0 until 10) {
      for (x in 0 until 10) grid.setPixel(x, y, paper)
    }
    for (y in 4..6) {
      for (x in 4..6) grid.setPixel(x, y, ink)
    }

    grid.fitCells(3)

    assertEquals(21, grid.columns)
    assertEquals(21, grid.rows)
    assertEquals(paper, grid.getPixel(20, 0))
    assertEquals(paper, grid.getPixel(0, 20))
  }

  @Test
  fun theOppositeOfADarkBackgroundIsWhite() {
    // The editor's dark navy, and pure black: the shape being dragged out is drawn white on both.
    assertEquals(Color.White, contrastColour(Color(0xFF181425)))
    assertEquals(Color.White, contrastColour(Color.Black))
  }

  @Test
  fun theOppositeOfALightBackgroundIsBlack() {
    assertEquals(Color.Black, contrastColour(paper))
    assertEquals(Color.Black, contrastColour(Color.White))
  }

  @Test
  fun clearingPaintsThePaperBackNotThePalettesDarkest() {
    // The clear tool has to leave the sheet, not the palette's darkest swatch: on this pair the darkest
    // is the ink, which is exactly the colour a clear must not leave behind.
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = 9, initialRows = 9)
    for (y in 0 until 9) {
      for (x in 0 until 9) grid.setPixel(x, y, paper)
    }
    for (y in 3..5) {
      for (x in 3..5) grid.setPixel(x, y, ink)
    }
    assertEquals("the palette's darkest is the ink", ink, grid.defaultColor)

    grid.clear()

    assertEquals(paper, grid.getPixel(0, 0))
    assertEquals(paper, grid.getPixel(4, 4))
    assertEquals(paper, grid.getPixel(8, 8))
  }

  @Test
  fun clearingADrawingThatIsAlreadyBackgroundIsNotAnEdit() {
    val grid = PixelGridState(palette = listOf(ink, paper), initialColumns = 9, initialRows = 9)
    for (y in 0 until 9) {
      for (x in 0 until 9) grid.setPixel(x, y, paper)
    }
    val steps = grid.undoDepth
    val revision = grid.revision

    grid.clear()

    assertEquals(paper, grid.getPixel(4, 4))
    assertEquals(steps, grid.undoDepth)
    assertEquals(revision, grid.revision)
  }
}
