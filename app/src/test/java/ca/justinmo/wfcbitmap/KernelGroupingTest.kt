package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.groupCells
import ca.justinmo.wfcbitmap.generation.renderCellCenters
import ca.justinmo.wfcbitmap.generation.renderSolvedCells
import ca.justinmo.wfcbitmap.output.KernelSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping drawing pixels into cells, and putting a 2x2 cell back together when rendering. */
class KernelGroupingTest {

  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()
  private val green = 0xFF00FF00.toInt()
  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun aSixBySixPatternIsTwoByTwoCells() {
    assertEquals(1, KernelSize.Three.cellSize)
    assertEquals(2, KernelSize.Six.cellSize)
    assertEquals(3, KernelSize.Nine.cellSize)
    assertEquals(4, KernelSize.Twelve.cellSize)
    assertEquals(5, KernelSize.Fifteen.cellSize)
    assertEquals(6, KernelSize.Eighteen.cellSize)
    assertEquals(7, KernelSize.TwentyOne.cellSize)
    assertEquals(8, KernelSize.TwentyFour.cellSize)
  }

  @Test
  fun groupingKeepsEachCellsFourPixels() {
    // Two 2x2 cells: a four-colour one and a green one.
    val pixels = intArrayOf(
      red, green, green, green,
      blue, white, green, green,
      green, green, green, green,
      green, green, green, green,
    )

    val grouped = groupCells(pixels, 4, 4, cell = 2)

    assertEquals(2, grouped.width)
    assertEquals(2, grouped.height)
    assertEquals(2, grouped.blocks.size)
    val first = grouped.blocks[grouped.tokens[0]]
    assertEquals(red, first[0])
    assertEquals(green, first[1])
    assertEquals(blue, first[2])
    assertEquals(white, first[3])
    assertTrue(grouped.blocks[grouped.tokens[1]].all { it == green })
    assertNotEquals(grouped.tokens[0], grouped.tokens[1])
  }

  @Test
  fun identicalCellsShareOneToken() {
    val pixels = IntArray(4 * 4) { red }

    val grouped = groupCells(pixels, 4, 4, cell = 2)

    assertEquals(1, grouped.blocks.size)
    assertTrue(grouped.tokens.all { it == 0 })
  }

  @Test
  fun groupingIgnoresLeftoverPixelsOutsideWholeCells() {
    // 5x5 with 2x2 cells: the last column and row are not part of any full cell.
    val pixels = IntArray(25) { red }
    pixels[4] = blue
    pixels[24] = blue

    val grouped = groupCells(pixels, 5, 5, cell = 2)

    assertEquals(2, grouped.width)
    assertEquals(2, grouped.height)
    assertEquals(1, grouped.blocks.size)
    assertTrue(grouped.blocks[0].all { it == red })
  }

  @Test
  fun aDrawingSmallerThanOneCellIsRejected() {
    val thrown = runCatching {
      groupCells(IntArray(3 * 3), 3, 3, cell = 4)
    }.exceptionOrNull()

    assertTrue(thrown is WfcGenerator.GenerationException)
  }

  @Test
  fun aGroupedCellRendersItsWholeBlockNotAnAverage() {
    // One 2x2 cell, drawn at 4 pixels per cell: every source pixel must become a 2x2 output square.
    val block = intArrayOf(red, green, blue, white)
    val pixels = IntArray(16)
    val assignment = intArrayOf(0)
    val sources = intArrayOf(0)
    val centres = intArrayOf(0)

    renderSolvedCells(
      pixels = pixels,
      width = 4,
      columns = 1,
      rows = 1,
      scale = 4,
      assignment = assignment,
      sources = sources,
      centres = centres,
      blocks = listOf(block),
      cell = 2,
    )

    val expected = intArrayOf(
      red, red, green, green,
      red, red, green, green,
      blue, blue, white, white,
      blue, blue, white, white,
    )
    assertTrue("rendered block was ${pixels.toList()}", pixels.contentEquals(expected))
  }

  @Test
  fun anUngroupedCellStillRendersOneSquareColour() {
    val pixels = IntArray(4)
    renderSolvedCells(
      pixels = pixels,
      width = 2,
      columns = 1,
      rows = 1,
      scale = 2,
      assignment = intArrayOf(0),
      sources = intArrayOf(0),
      centres = intArrayOf(red),
      blocks = null,
      cell = 1,
    )

    assertTrue(pixels.all { it == red })
  }

  /*
   * The 9x9 kernel is the first one whose cell (3 pixels) does not divide the 4 pixels a cell is drawn
   * at, so it is the first one where the block has to be resampled at all. Resampling has to sample the
   * output pixel's centre: 3 pixels into 4 doubles the middle pixel. Truncating the scale instead
   * doubles the *first* pixel of every cell, which draws each tile half a pixel up and to the left and
   * makes the picture look torn wherever two cells meet.
   */

  @Test
  fun aThreeByThreeCellIsSampledAtTheOutputPixelsCentre() {
    // Nine distinct colours, one per source pixel, so the mapping is visible in the output.
    val block = IntArray(9) { 0xFF000001.toInt() or (it shl 8) }
    val pixels = IntArray(16)

    renderCellCenters(
      pixels = pixels,
      width = 4,
      columns = 1,
      rows = 1,
      scale = 4,
      centres = intArrayOf(0),
      blocks = listOf(block),
      cell = 3,
    )

    // 3 source pixels across 4 output pixels: the middle source pixel is the one that is doubled.
    val sampled = intArrayOf(0, 1, 1, 2)
    for (dy in 0 until 4) {
      for (dx in 0 until 4) {
        assertEquals(
          "output pixel ($dx,$dy) shows the wrong source pixel; rendered " +
            pixels.toList(),
          block[sampled[dy] * 3 + sampled[dx]],
          pixels[dy * 4 + dx],
        )
      }
    }
  }

  @Test
  fun aNineByNineCellIsDrawnTheSameWayByBothRenderers() {
    val block = IntArray(9) { 0xFF000001.toInt() or (it shl 8) }
    val blocks = listOf(block)
    val fromGrid = IntArray(12 * 8)
    val fromCentres = IntArray(12 * 8)

    renderSolvedCells(
      pixels = fromGrid,
      width = 12,
      columns = 3,
      rows = 2,
      scale = 4,
      assignment = intArrayOf(0, 0, 0, 0, 0, 0),
      sources = intArrayOf(0, 0, 0, 0, 0, 0),
      centres = intArrayOf(0),
      blocks = blocks,
      cell = 3,
    )
    renderCellCenters(
      pixels = fromCentres,
      width = 12,
      columns = 3,
      rows = 2,
      scale = 4,
      centres = intArrayOf(0, 0, 0, 0, 0, 0),
      blocks = blocks,
      cell = 3,
    )

    assertArrayEquals(
      "the preview and the video must draw a 9x9 cell the same way",
      fromGrid,
      fromCentres,
    )
  }

  @Test
  fun anEightByEightCellKeepsThePixelsAtItsCentreWhenShrunk() {
    // 8 source pixels into 4 output pixels: sample the centres (1, 3, 5, 7), not every other pixel
    // from the top-left corner (0, 2, 4, 6), which shifts the picture half a cell up and left.
    val block = IntArray(64) { 0xFF000001.toInt() or (it shl 8) }
    val pixels = IntArray(16)

    renderCellCenters(
      pixels = pixels,
      width = 4,
      columns = 1,
      rows = 1,
      scale = 4,
      centres = intArrayOf(0),
      blocks = listOf(block),
      cell = 8,
    )

    val sampled = intArrayOf(1, 3, 5, 7)
    for (dy in 0 until 4) {
      for (dx in 0 until 4) {
        assertEquals(
          "output pixel ($dx,$dy) shows the wrong source pixel; rendered " + pixels.toList(),
          block[sampled[dy] * 8 + sampled[dx]],
          pixels[dy * 4 + dx],
        )
      }
    }
  }
}