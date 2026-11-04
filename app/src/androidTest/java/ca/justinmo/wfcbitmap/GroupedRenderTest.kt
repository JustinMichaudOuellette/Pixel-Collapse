package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Grouped kernels end to end: the drawing is reduced to cells, solved in token space, and drawn back
 * as one block per cell.
 *
 * Needs a device because it runs the native solver, and uses the 9x9 kernel because that is the first
 * size whose cell (3 pixels) does not divide the four pixels a cell is drawn at -- so it is the first
 * one whose blocks have to be resampled at all.
 */
@RunWith(AndroidJUnit4::class)
class GroupedRenderTest {

  /** The plan a 9x9 kernel gets at the 2x preview: 3-pixel cells, drawn one to one. */
  private val plan = generationPlan(OutputAspect.Portrait9x16, PreviewScale.One, KernelSize.Nine)
  private val columns = plan.columns
  private val rows = plan.rows
  private val scale = plan.pixelsPerCell

  private suspend fun solveGrouped(): WfcGenerator.Output = WfcGenerator.generate(
    pixels = DebugTestPattern.pixels,
    sourceWidth = DebugTestPattern.columns,
    sourceHeight = DebugTestPattern.rows,
    seed = 7,
    columns = columns,
    rows = rows,
    wrapEdges = true,
    pixelsPerCell = scale,
    cellSize = plan.cellSize,
  )

  @Test
  fun theGroupedPictureIsTheBlockTheSolveChoseForEachCell() = runBlocking {
    val output = solveGrouped()
    val cell = requireNotNull(output.cellSize.takeIf { it > 1 }) { "the solve was not grouped" }
    val centres = requireNotNull(output.cellCenters)
    val blocks = requireNotNull(output.blocks)
    val pixels = IntArray(output.bitmap.width * output.bitmap.height)
    output.bitmap.getPixels(pixels, 0, output.bitmap.width, 0, 0, output.bitmap.width, output.bitmap.height)

    var wrong = 0
    for (y in 0 until output.rows) {
      for (x in 0 until output.columns) {
        val block = blocks[centres[y * output.columns + x]]
        for (dy in 0 until scale) {
          for (dx in 0 until scale) {
            val actual = pixels[(y * scale + dy) * output.bitmap.width + x * scale + dx]
            val expected = block[blockSourcePixel(dy, cell, scale) * cell + blockSourcePixel(dx, cell, scale)]
            if (actual != expected) wrong++
          }
        }
      }
    }
    assertEquals("pixels that disagree with the block the solve chose", 0, wrong)
  }

  /**
   * Neighbouring cells must be two windows of the drawing that really do overlap: a cell and the one
   * to its right are windows centred one cell apart, so the left window's middle and right columns are
   * the right window's left and middle columns.
   *
   * That is what makes the picture a fit of the drawing rather than blocks merely placed next to each
   * other: if it fails, the tiles are arranged into shapes the drawing does not contain, which is what
   * "the tiles are rendered incorrectly" would look like.
   */
  @Test
  fun theGroupedTilingIsAConsistentFitOfTheDrawingsWindows() = runBlocking {
    val output = solveGrouped()
    val grouped = groupCells(
      DebugTestPattern.pixels,
      DebugTestPattern.columns,
      DebugTestPattern.rows,
      KernelSize.Nine.cellSize,
    )
    val windows = BitmapWaveFunctionCell.createCells(
      IntArray2D(grouped.width, grouped.height, grouped.tokens),
      wrap = true,
    )
    val patterns = requireNotNull(output.cellPatterns)

    fun window(x: Int, y: Int): Array<Int?> = windows[patterns[y * columns + x]].kernel

    var broken = 0
    for (y in 1 until rows - 1) {
      for (x in 0 until columns) {
        if (x + 1 >= columns) continue
        val here = window(x, y)
        val right = window(x + 1, y)
        for (row in 0 until 3) {
          if (here[row * 3 + 1] != right[row * 3]) broken++
          if (here[row * 3 + 2] != right[row * 3 + 1]) broken++
        }
      }
    }
    for (y in 0 until rows - 1) {
      for (x in 1 until columns - 1) {
        val here = window(x, y)
        val below = window(x, y + 1)
        for (column in 0 until 3) {
          if (here[3 + column] != below[column]) broken++
          if (here[6 + column] != below[3 + column]) broken++
        }
      }
    }
    assertEquals("cells whose windows disagree with their neighbours", 0, broken)
  }
}
