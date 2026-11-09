package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a generation's milliseconds actually go, and what the render costs on its own.
 *
 * The app's own log says the search is a small part of a generation (tens of milliseconds against a few
 * hundred for turning cells into pixels), so this measures the render directly: the per-cell fill, the row
 * fill, and the upload into the [Bitmap], each on its own. Not an assertion-heavy test -- it exists to
 * choose between implementations with numbers, and to be re-run after a change to see whether it helped.
 *
 * Runs on the device (`connectedDebugAndroidTest`), in the same debug build the phone runs.
 */
@RunWith(AndroidJUnit4::class)
class RenderBenchmarkTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()

  /** The real output size: 152x270 cells drawn as 2x2 squares. */
  private val columns = WfcGenerator.DEFAULT_COLUMNS
  private val rows = WfcGenerator.DEFAULT_ROWS
  private val width = columns * WfcGenerator.PIXELS_PER_CELL
  private val height = rows * WfcGenerator.PIXELS_PER_CELL

  /** A solved grid is just tile indices; the render only needs a spread of them. */
  private val palette = intArrayOf(ink, rust, gold, slate)

  private fun assignment() = IntArray(columns * rows) { it % palette.size }

  private fun centres(tile: Int) = palette[tile % palette.size]

  // ───────────────────────────────────────────────────────────────────────────
  // The fill strategies
  // ───────────────────────────────────────────────────────────────────────────

  /** What the app does now: per cell, write a 2x2 block into the pixel buffer. */
  private fun fillPerCell(assignment: IntArray, pixels: IntArray) {
    val scale = WfcGenerator.PIXELS_PER_CELL
    for (y in 0 until rows) {
      val rowBase = y * scale * width
      for (x in 0 until columns) {
        val colour = centres(assignment[y * columns + x])
        val pixelX = x * scale
        for (dy in 0 until scale) {
          val base = rowBase + dy * width + pixelX
          for (dx in 0 until scale) pixels[base + dx] = colour
        }
      }
    }
  }

  /**
   * Row-major: build one output row from the cells it covers, then copy it to the rows below it.
   *
   * The pixels of one cell row are the same in every row the cell is tall, so half the writes are copies
   * of a block that was just built -- and a two-row block is two `arraycopy`s of 304 ints.
   */
  private fun fillByRows(assignment: IntArray, pixels: IntArray) {
    val scale = WfcGenerator.PIXELS_PER_CELL
    val row = IntArray(width)
    for (y in 0 until rows) {
      val cellRowBase = y * columns
      for (x in 0 until columns) {
        val colour = centres(assignment[cellRowBase + x])
        val pixelX = x * scale
        for (dx in 0 until scale) row[pixelX + dx] = colour
      }
      for (dy in 0 until scale) {
        System.arraycopy(row, 0, pixels, (y * scale + dy) * width, width)
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Measurements
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun theFillAndTheUploadCostWhat() {
    val assignment = assignment()
    val pixels = IntArray(width * height)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val runs = 40

    // Warm up so the first-run cost (allocation, JIT) does not decide which is faster.
    repeat(5) {
      fillPerCell(assignment, pixels)
      fillByRows(assignment, pixels)
      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    var perCell = 0L
    var byRows = 0L
    var upload = 0L
    var createFromArray = 0L
    var createBlank = 0L

    for (i in 0 until runs) {
      // Alternate the order so neither strategy always runs into the other's cache state.
      if (i % 2 == 0) {
        perCell += time { fillPerCell(assignment, pixels) }
        byRows += time { fillByRows(assignment, pixels) }
      } else {
        byRows += time { fillByRows(assignment, pixels) }
        perCell += time { fillPerCell(assignment, pixels) }
      }
      upload += time { bitmap.setPixels(pixels, 0, width, 0, 0, width, height) }
      createFromArray += time {
        // No `setPixels` at all: hand the whole array over in one call.
        val created = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        check(created.width == width)
      }
      createBlank += time {
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        check(created.width == width)
      }
    }

    println("--- render benchmark, ${width}x$height, $runs runs (avg ms) ---")
    println("fill per cell            : %.2f".format(perCell / runs.toDouble() / 1_000_000))
    println("fill by rows             : %.2f".format(byRows / runs.toDouble() / 1_000_000))
    println("setPixels (upload)       : %.2f".format(upload / runs.toDouble() / 1_000_000))
    println("createBitmap(intArray)   : %.2f".format(createFromArray / runs.toDouble() / 1_000_000))
    println("createBitmap(blank)      : %.2f".format(createBlank / runs.toDouble() / 1_000_000))
  }

  @Test
  fun aGenerationWarmAndByPhase() {
    val pixels = drawing()
    val runs = 8
    var extract = 0L
    var solve = 0L
    var render = 0L
    var total = 0L

    for (i in 0 until runs) {
      // A new seed each time: the generator hands back its cached picture when the tile set and the seed
      // are both unchanged, which would measure nothing at all.
      val output = runBlocking {
        WfcGenerator.generate(
          pixels = pixels,
          sourceWidth = SOURCE_WIDTH,
          sourceHeight = SOURCE_HEIGHT,
          seed = 1000 + i,
        )
      }
      extract += output.extractMillis
      solve += output.solveMillis
      render += output.renderMillis
      total += output.millis
      println(
        "run $i: ${output.tileCount} tiles, total ${output.millis} ms " +
          "(extract ${output.extractMillis}, solve ${output.solveMillis}, render ${output.renderMillis})",
      )
    }
    println("--- averages over $runs runs (ms) ---")
    println("extract: %.1f".format(extract / runs.toDouble()))
    println("solve  : %.1f".format(solve / runs.toDouble()))
    println("render : %.1f".format(render / runs.toDouble()))
    println("total  : %.1f".format(total / runs.toDouble()))
  }

  private fun drawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        pixels[y * SOURCE_WIDTH + x] = when {
          x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1 -> gold
          (x - 7) * (x - 7) + (y - 5) * (y - 5) < 9 -> rust
          x % 4 == 0 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  private inline fun time(block: () -> Unit): Long {
    val startedAt = System.nanoTime()
    block()
    return System.nanoTime() - startedAt
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
  }
}