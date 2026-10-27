package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio

/**
 * Bridges the pixel editor to the wave-function-collapse engine: turns the drawing into a *bitmap*.
 *
 * The drawing is the *source image*. Every distinct 3x3 window of it becomes a tile, sampled with
 * wrap-around ([BitmapWaveFunctionCell.createWrappedCells]) so that the drawing's pattern can
 * continue past its own edges, and
 * [StrictBitmapSolver][net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver]
 * then fills a much larger grid with tiles that fit together in all eight directions. The result is
 * a picture built only from patterns that actually occur in the drawing.
 *
 * Because the tile set is closed under wrap-around, the drawing's periodic extension is always a
 * valid tiling, so *any* drawing produces a bitmap; [generate] only falls back to that periodic
 * extension (same picture, no shuffled variety) if the search cannot finish within its budget. The
 * fallback is reported through [Output.usedFallback] rather than hidden, so a slow search is still
 * visible to the user.
 *
 * All the heavy work runs on [Dispatchers.Default]; callers pass a snapshot of the drawing (plain
 * `IntArray` of ARGB) rather than the Compose state, so no snapshot state is touched off the main
 * thread.
 */
object WfcGenerator {

  /** Output grid: 9:16 at 6 pixels per editor cell is a good phone-sized canvas. */
  const val DEFAULT_COLUMNS = 54
  const val DEFAULT_ROWS = 96

  /**
   * Pixels per generated cell in the output bitmap. A cell *is* one pixel of the pattern, so the
   * bitmap is an integer nearest-neighbour upscale of it: blocky on purpose, and big enough
   * (54x96 -> 432x768) to be saved, shared or zoomed without turning to mush.
   */
  const val PIXELS_PER_CELL = 8

  /**
   * Give up rather than block a device for minutes. A drawing that tiles solves in a second or two
   * on a phone (measured 0.75 s for a 48-tile drawing on an emulator), so this is only reached by a
   * pathological tile set -- and even then the result is still a bitmap (the periodic fallback),
   * just not a shuffled one.
   */
  private const val TIMEOUT_NANOS = 8_000_000_000L
  private const val MAX_DECISIONS = 1_000_000

  /** Solvers to run in parallel; the portfolio only uses more than one when the first is slow. */
  private const val WORKERS = 4

  /** The solver packs tile domains into 64-bit words and a 4-bit word index; this is its ceiling. */
  private const val MAX_TILES = 1024

  class GenerationException(message: String) : Exception(message)

  /** A finished generation: the bitmap plus the numbers worth showing the user. */
  data class Output(
    val bitmap: Bitmap,
    val columns: Int,
    val rows: Int,
    val tileCount: Int,
    val millis: Long,
    val seed: Int,
    val wonBySeed: Int?,
    val workers: Int,
    /** True when the solver ran out of budget and the drawing's periodic extension was used. */
    val usedFallback: Boolean,
  ) {
    val image: ImageBitmap = bitmap.asImageBitmap()
  }

  /**
   * @param pixels ARGB of the source drawing, row-major, `sourceWidth * sourceHeight` entries.
   */
  suspend fun generate(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    seed: Int,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
  ): Output = withContext(Dispatchers.Default) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || pixels.size < sourceWidth * sourceHeight) {
      throw GenerationException("The drawing is empty - draw a pattern first.")
    }
    val source = IntArray2D(sourceWidth, sourceHeight, pixels.copyOf())
    val cells = BitmapWaveFunctionCell.createWrappedCells(source)
    if (cells.isEmpty()) {
      throw GenerationException("The drawing is empty - draw a pattern first.")
    }
    if (cells.size > MAX_TILES) {
      throw GenerationException(
        "The drawing has ${cells.size} distinct 3x3 patterns; at most $MAX_TILES are supported. " +
          "Draw with fewer colours or simpler shapes.",
      )
    }
    val startedAt = System.currentTimeMillis()
    val portfolio = StrictSolverPortfolio(cells, columns, rows, Direction2d8.entries)
    val result = portfolio.solve(
      seed = seed,
      workers = WORKERS,
      deadlineNanos = System.nanoTime() + TIMEOUT_NANOS,
      maxDecisions = MAX_DECISIONS,
    )
    val millis = System.currentTimeMillis() - startedAt
    val assignment = result.assignment
    val usedFallback = assignment == null
    // Tile -> its centre colour is exactly the pixel the cell represents, and the strict rule
    // guarantees every neighbour relation in the result is one that holds in the drawing.
    val cellColors = if (assignment != null) {
      IntArray(columns * rows) { cells[assignment[it]].color }
    } else {
      periodicCrop(pixels, sourceWidth, sourceHeight, columns, rows)
    }
    Output(
      bitmap = render(cellColors, columns, rows),
      columns = columns,
      rows = rows,
      tileCount = cells.size,
      millis = millis,
      seed = seed,
      wonBySeed = result.seed,
      workers = result.entries.size,
      usedFallback = usedFallback,
    )
  }

  /**
   * The drawing repeated periodically and cropped to [columns] x [rows].
   *
   * This is not a guess: every 3x3 window of it is a window of the drawing (wrap-around sampling),
   * so it is a tiling of the drawing's own tile set -- the certificate that the instance the solver
   * was given is satisfiable. It is what the search would be guaranteed to find if it had unlimited
   * time, and it is what keeps "draw something, get a bitmap" unconditional.
   */
  private fun periodicCrop(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    columns: Int,
    rows: Int,
  ): IntArray = IntArray(columns * rows) { index ->
    val x = index % columns
    val y = index / columns
    pixels[(y % sourceHeight) * sourceWidth + (x % sourceWidth)]
  }

  /**
   * Expands one cell per pixel to [PIXELS_PER_CELL]^2 pixels each.
   *
   * An integer scale keeps every cell a flat block of one colour, which is what makes the generated
   * image look like the drawing's pixels rather than a blur of them.
   */
  private fun render(cellColors: IntArray, columns: Int, rows: Int): Bitmap {
    val scale = PIXELS_PER_CELL
    val width = columns * scale
    val height = rows * scale
    val argb = IntArray(width * height)
    for (y in 0 until rows) {
      val rowBase = y * columns
      val pixelRowBase = y * scale * width
      for (x in 0 until columns) {
        val color = cellColors[rowBase + x]
        val pixelX = x * scale
        for (dy in 0 until scale) {
          val base = pixelRowBase + dy * width + pixelX
          for (dx in 0 until scale) {
            argb[base + dx] = color
          }
        }
      }
    }
    return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
  }
}
