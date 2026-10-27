package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio

/**
 * Bridges the pixel editor to the wave-function-collapse engine: turns the drawing into a *bitmap*.
 *
 * The drawing is the *source image*. Every distinct 3x3 window of it becomes a tile, and the windows
 * that hang over an edge carry no pixels there ([BitmapWaveFunctionCell.createCells]), so a tile can
 * be blank towards the outside. The native solver (app/src/main/cpp/wfc.c) then fills a much larger
 * grid with tiles that fit together in all eight directions, and because a
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

  /** Longest the hedge may grow to; beyond this a long solve should have hedged much earlier. */
  private const val MAX_HEDGE_MS = 400L

  /** Shortest the hedge may shrink to, so a run of fast collapses never disables the portfolio. */
  private const val MIN_HEDGE_MS = 40L

  /** The hedge used until a solve has been measured: see [lastSolveMillis]. */
  private const val FAST_SOLVER_HEDGE_MS = 60L

  /** Solvers to run in parallel; the portfolio only uses more than one when the first is slow. */
  private const val WORKERS = 4

  /**
   * How long the first worker runs alone before the portfolio spreads to the other cores, in ms.
   *
   * A fixed hedge cannot be right for both ends: the native solver collapses a simple drawing in under
   * a millisecond, where starting three more workers is pure cost, and it can spend seconds on an
   * unlucky seed, where not starting them is the cost. Worse, a hedge *shorter* than the solve time is
   * actively harmful -- measured on a 76x135 grid, a drawing one worker collapses in 81 ms took 108 ms
   * once three hedged workers were competing with it for the same cores. So the hedge follows the last
   * solve: 1.5x what a single worker needed last time, clamped so one fast collapse cannot disable the
   * portfolio and one slow collapse cannot stall it.
   */
  private var lastSolveMillis: Long = 0

  /*
   * Incremental extraction and result reuse.
   *
   * The tally keeps the window counts of the previous drawing so an edit only re-tallies the nine
   * windows around each changed pixel, and cachedPacked is the tile set the cached bitmap was made
   * from. Since a collapse is seeded, the same tile set on the same grid always gives the same picture,
   * so an edit that introduces no new pattern returns the previous output instead of re-solving -- which
   * is most edits, because drawing over an area whose patterns already exist changes counts only if it
   * changes a colour.
   */
  private val cacheLock = Any()
  private val tally = WindowTally()
  private var cachedPacked: IntArray = IntArray(0)
  private var cachedOutput: Output? = null
  private var cachedSeed: Int = Int.MIN_VALUE

  /** The reusable bitmaps the collapse is drawn into. Guarded by [cacheLock]. */
  private var targets: RenderTarget? = null

  private fun renderTarget(columns: Int, rows: Int): RenderTarget {
    val existing = targets
    if (existing != null && existing.columns == columns && existing.rows == rows) return existing
    return RenderTarget(columns, rows).also { targets = it }
  }
  private fun hedgeMillis(): Long =
    if (lastSolveMillis <= 0) FAST_SOLVER_HEDGE_MS
    else (lastSolveMillis * 3 / 2).coerceIn(MIN_HEDGE_MS, MAX_HEDGE_MS)

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
    /**
     * The picture at cell resolution ([columns] x [rows]), which is what the preview draws.
     *
     * The generated image is an integer upscale of exactly this, so uploading the small one and letting
     * the compositor scale it with nearest neighbour shows the same pixels for a quarter of the texture
     * upload (41 KB instead of 164 KB per update).
     */
    val display: Bitmap,
    private val publish: () -> Bitmap,
    val columns: Int,
    val rows: Int,
    val tileCount: Int,
    val millis: Long,
    val seed: Int,
    val wonBySeed: Int?,
    val workers: Int,
    /** True when no tiling exists (or none was found in time) and the stretched drawing was used. */
    val usedFallback: Boolean,
    /** Time spent turning the solved cells into pixels, in milliseconds. */
    val renderMillis: Long = 0,
    /** Time the solver portfolio reported for itself, in milliseconds. */
    val solveMillis: Long = 0,
    /** Time spent sampling the tile set and building the native model, in milliseconds. */
    val extractMillis: Long = 0,
  ) {
    /**
     * The generated bitmap at the published size ([BITMAP_WIDTH] x [BITMAP_HEIGHT]).
     *
     * Expanded on first use from [display] with nearest neighbour, which is exact: the published bitmap
     * is [PIXELS_PER_CELL] times the cell grid in both directions. Nothing in the live path reads this,
     * so a generation that is only displayed pays nothing for it; a caller that wants the full-size
     * image (saving, or a test) gets it and pays once.
     */
    val bitmap: Bitmap by lazy(LazyThreadSafetyMode.PUBLICATION) { publish() }

    /** What the preview draws: the cell-resolution bitmap. */
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) { display.asImageBitmap() }
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
    /*
     * Bordered extraction: the windows that hang over an edge are blank there, which is what the
     * solver's edge rule needs to keep the drawing's edges on the bitmap's edges.
     *
     * Sampling is incremental (WindowTally), and the packed tile set doubles as the cache key: because
     * the collapse is seeded, an edit that leaves the tile set untouched would produce exactly the
     * bitmap already on screen, so it is handed straight back. Both the tally and the cache are
     * guarded, because a cancelled generation can still be inside its extraction when the next starts.
     */
    val packed: IntArray
    val extractStartedAt = System.nanoTime()
    synchronized(cacheLock) {
      val sampled = tally.sample(source)
      if (sampled.isEmpty()) {
        throw GenerationException("The drawing is empty - draw a pattern first.")
      }
      if (sampled.size > MAX_TILES) {
        throw GenerationException(
          "The drawing has ${sampled.size} distinct 3x3 patterns; at most $MAX_TILES are supported. " +
            "Draw with fewer colours or simpler shapes.",
        )
      }
      packed = WfcNative.packTiles(sampled)
      val reusable = cachedOutput
      if (reusable != null && cachedSeed == seed && columns == reusable.columns &&
        rows == reusable.rows && cachedPacked.contentEquals(packed)
      ) {
        return@withContext reusable
      }
    }
    val extractNanos = System.nanoTime() - extractStartedAt
    val startedAt = System.currentTimeMillis()
    // The native solver is the only solver: the tile set is packed into a C model that every worker
    // shares. If the library is missing there is nothing to fall back to, so say so plainly instead of
    // failing somewhere deeper.
    if (!WfcNative.available) {
      throw GenerationException("The native WFC solver is not available on this device.")
    }
    /*
     * The model prunes the tile set itself (patterns that can never be placed next to anything), so the
     * indices a solve returns are model tiles, and `sources` maps each one back to its sampled pattern.
     */
    val nativeModel = WfcNative.modelCreate(packed, packed.size / WfcNative.PACKED_TILE_INTS, columns, rows)
    if (nativeModel == 0L) {
      throw GenerationException("No pattern in this drawing can touch another, so it cannot be tiled.")
    }
    val tileCount = WfcNative.modelTileCount(nativeModel)
    val sources = IntArray(tileCount) { tile -> WfcNative.modelTileSource(nativeModel, tile) }
    // Read the colours while the model is alive: it is destroyed as soon as the solve returns.
    val centers = IntArray(tileCount) { tile -> WfcNative.modelTileCenter(nativeModel, tile) }
    val result = try {
      val portfolio = StrictSolverPortfolio(
        columns = columns,
        rows = rows,
        solverFactory = { workerSeed, workerColumns, workerRows ->
          NativeTileSolver(nativeModel, workerColumns, workerRows, workerSeed)
        },
      )
      // Hand the caller's cancellation down to the search. Without this the solve would run to its
      // deadline even though the editor had already replaced the drawing it was working on: the
      // collapse is blocking, so cancelling the coroutine alone cannot stop it.
      val scope = this
      portfolio.solve(
        seed = seed,
        workers = WORKERS,
        deadlineNanos = System.nanoTime() + TIMEOUT_NANOS,
        maxDecisions = MAX_DECISIONS,
        shouldAbort = { !scope.isActive },
      )
    } finally {
      if (nativeModel != 0L) {
        WfcNative.modelDestroy(nativeModel)
      }
    }
    // Null now means either "no tiling found" or "we were cancelled"; only the first one should fall
    // back to the stretched drawing. Letting the cancellation through is what makes it the second.
    ensureActive()
    val millis = System.currentTimeMillis() - startedAt
    val assignment = result.assignment
    val usedFallback = assignment == null
    // A model tile's centre colour is exactly the pixel the cell represents (the model kept the packed
    // kernels), and the strict rule guarantees every neighbour relation in the result holds in the
    // drawing.
    val renderStartedAt = System.nanoTime()
    val target = synchronized(cacheLock) { renderTarget(columns, rows) }
    val display = if (assignment != null) {
      target.renderSolved(assignment, sources, centers, columns, rows)
    } else {
      target.renderColours(
        stretchedDrawing(pixels, sourceWidth, sourceHeight, columns, rows),
        columns,
        rows,
      )
    }
    val renderMillis = (System.nanoTime() - renderStartedAt) / 1_000_000
    val output = Output(
      display = display,
      publish = { Bitmap.createScaledBitmap(display, BITMAP_WIDTH, BITMAP_HEIGHT, false) },
      columns = columns,
      rows = rows,
      tileCount = tileCount,
      millis = millis,
      seed = seed,
      wonBySeed = result.seed,
      workers = result.entries.size,
      usedFallback = usedFallback,
      renderMillis = renderMillis,
      solveMillis = result.millis,
      extractMillis = extractNanos / 1_000_000,
    )
    synchronized(cacheLock) {
      cachedPacked = packed
      cachedOutput = output
      cachedSeed = seed
    }
    output
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
   * Two reusable bitmaps and one reusable pixel buffer, so a collapse stops allocating pixels.
   *
   * Each generation used to allocate a 164 KB `IntArray`, copy it into a fresh 164 KB `Bitmap` and drop
   * both -- 328 KB of garbage per keystroke, plus the extra copy. Both now live here. The two bitmaps
   * alternate because the preview may still be drawing the previous frame while the next one is being
   * written: the one on screen is never the one being filled in.
   *
   * The bitmaps are at *cell* resolution ([columns] x [rows]). The published image is an integer upscale
   * of exactly this, so the preview scales it up with nearest neighbour in the compositor instead: same
   * pixels on screen, a quarter of the texture upload, and a quarter of the writing here.
   */
  private class RenderTarget(val columns: Int, val rows: Int) {
    private val bitmaps = Array(2) { Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888) }
    private val pixels = IntArray(columns * rows)
    private var next = 0

    /**
     * Draws a solved grid: cell (x, y) shows `centres[sources[assignment[y * columns + x]]]`.
     *
     * The lookup happens here rather than into an intermediate colour array, so a collapse keeps no
     * pixel-side allocation at all.
     */
    fun renderSolved(
      assignment: IntArray,
      sources: IntArray,
      centres: IntArray,
      columns: Int,
      rows: Int,
    ): Bitmap {
      for (y in 0 until rows) {
        val rowBase = y * columns
        for (x in 0 until columns) {
          pixels[rowBase + x] = centres[sources[assignment[rowBase + x]]]
        }
      }
      return publish()
    }

    /** Draws a ready-made grid of cell colours, e.g. the stretched fallback. */
    fun renderColours(cellColours: IntArray, columns: Int, rows: Int): Bitmap {
      cellColours.copyInto(pixels, 0, 0, columns * rows)
      return publish()
    }

    /** Copies the filled buffer into the next bitmap: the one on screen is never written. */
    private fun publish(): Bitmap {
      val bitmap = bitmaps[next]
      next = (next + 1) % bitmaps.size
      bitmap.setPixels(pixels, 0, columns, 0, 0, columns, rows)
      return bitmap
    }
  }
}