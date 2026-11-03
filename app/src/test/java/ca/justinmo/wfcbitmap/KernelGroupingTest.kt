package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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
}