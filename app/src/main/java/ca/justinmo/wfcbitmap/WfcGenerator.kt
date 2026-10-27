package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio

/**
 * Bridges the pixel editor to the wave-function-collapse engine: turns the drawing into a *bitmap*.
 *
 * The drawing is the *source image*. Every distinct 3x3 window of it becomes a tile, and the windows
 * that hang over an edge carry no pixels there ([BitmapWaveFunctionCell.createCells]), so a tile can
 * be blank towards the outside. The
 * [StrictBitmapSolver][net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver]
 * then fills a much larger grid with tiles that fit together in all eight directions, and because a
 * border cell only accepts a tile blank in the direction that leaves the grid, **pixels drawn on the
 * edge of the drawing can only be generated on the edge of the bitmap**.
 *
 * That edge rule is also the one limitation worth knowing: the drawing's border has to be repetitive
 * enough to run around the whole 76x135 grid. A border that cannot be closed yields no tiling, and
 * [generate] then falls back to the drawing stretched across the grid -- the one construction that
 * keeps the drawing's edges on the bitmap's edges without solving anything. [Output.usedFallback] says
 * which happened, which is what lets the editor log it instead of silently showing a different picture.
 *
 * The bitmap is [BITMAP_WIDTH] x [BITMAP_HEIGHT] (9:16, 270 tall). The grid the solver fills is
 * [DEFAULT_COLUMNS] x [DEFAULT_ROWS] cells, each drawn as a [PIXELS_PER_CELL] square of that bitmap:
 * one cell per pixel would be a 152x270 grid, which the strict search does not finish.
 *
 * All the heavy work runs on [Dispatchers.Default]; callers pass a snapshot of the drawing (plain
 * `IntArray` of ARGB) rather than the Compose state, so no snapshot state is touched off the main
 * thread.
 */
object WfcGenerator {

  /**
   * Output bitmap geometry: 9:16, 270 pixels tall.
   *
   * A 9:16 image 270 pixels tall is 151.875 wide, so 152 is the closest integer width -- off by one
   * eighth of a pixel, i.e. 0.08%. Nothing an image format can express closer than that.
   */
  const val BITMAP_WIDTH = 152
  const val BITMAP_HEIGHT = 270

  /**
   * Pixels per generated cell: the bitmap is an integer nearest-neighbour upscale of the pattern, so
   * cells stay square and their edges land on pixel boundaries.
   *
   * This is 2 rather than 1 because the cell grid is what the solver actually has to fill. At one
   * pixel per cell that is 152x270 = 41,040 cells, and the strict search does not finish that (it ran
   * the whole 20 s budget on two of four probe drawings); at 76x135 it solves in 0.1-0.6 s. The
   * bitmap keeps the requested 152x270 either way.
   */
  const val PIXELS_PER_CELL = 2

  /** The grid the solver fills: one cell per [PIXELS_PER_CELL] square of the bitmap. */
  const val DEFAULT_COLUMNS = BITMAP_WIDTH / PIXELS_PER_CELL
  const val DEFAULT_ROWS = BITMAP_HEIGHT / PIXELS_PER_CELL

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

  /**
   * A finished generation: the bitmap plus what it took.
   *
   * Only [bitmap] (through [image]) is drawn today -- the preview shows the picture and nothing else
   * -- but the rest is what a status line, a save filename or a bug report needs, and it costs
   * nothing to carry once the solve has produced it.
   */
  data class Output(
    val bitmap: Bitmap,
    val columns: Int,
    val rows: Int,
    val tileCount: Int,
    val millis: Long,
    val seed: Int,
    val wonBySeed: Int?,
    val workers: Int,
    /** True when no tiling exists (or none was found in time) and the stretched drawing was used. */
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
    // Bordered extraction: the windows that hang over an edge are blank there, which is what the
    // solver's edge rule needs to keep the drawing's edges on the bitmap's edges.
    val cells = BitmapWaveFunctionCell.createCells(source)
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
    // Hand the caller's cancellation down to the search. Without this the solve would run to its
    // deadline even though the editor had already replaced the drawing it was working on: the
    // collapse is blocking, so cancelling the coroutine alone cannot stop it.
    val scope = this
    val result = portfolio.solve(
      seed = seed,
      workers = WORKERS,
      deadlineNanos = System.nanoTime() + TIMEOUT_NANOS,
      maxDecisions = MAX_DECISIONS,
      shouldAbort = { !scope.isActive },
    )
    // Null now means either "no tiling found" or "we were cancelled"; only the first one should fall
    // back to the stretched drawing. Letting the cancellation through is what makes it the second.
    ensureActive()
    val millis = System.currentTimeMillis() - startedAt
    val assignment = result.assignment
    val usedFallback = assignment == null
    // Tile -> its centre colour is exactly the pixel the cell represents, and the strict rule
    // guarantees every neighbour relation in the result is one that holds in the drawing.
    val cellColors = if (assignment != null) {
      IntArray(columns * rows) { cells[assignment[it]].color }
    } else {
      stretchedDrawing(pixels, sourceWidth, sourceHeight, columns, rows)
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
   * The drawing, stretched by nearest neighbour across the whole [columns] x [rows] grid.
   *
   * This is the fallback when the edge rule finds no tiling, and it is chosen because of the edge
   * rule itself: the outermost cells map to the drawing's outermost pixels, so the drawing's edges
   * really are the bitmap's edges -- which is the one thing the tiling is required to preserve.
   *
   * It is a stretch, not a tiling: the picture is the drawing magnified, with its pixel boundaries
   * landing on the grid instead of a new arrangement of its 3x3 patterns.
   */
  private fun stretchedDrawing(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    columns: Int,
    rows: Int,
  ): IntArray = IntArray(columns * rows) { index ->
    val x = index % columns
    val y = index / columns
    // x * width / columns rather than a modulo: the last column maps to the drawing's last column,
    // so the right edge stays the right edge.
    val sx = (x.toLong() * sourceWidth / columns).toInt().coerceIn(0, sourceWidth - 1)
    val sy = (y.toLong() * sourceHeight / rows).toInt().coerceIn(0, sourceHeight - 1)
    pixels[sy * sourceWidth + sx]
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
