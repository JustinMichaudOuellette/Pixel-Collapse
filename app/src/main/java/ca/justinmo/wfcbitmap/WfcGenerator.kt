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
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * [generate] then reports that as a [GenerationException] rather than drawing something that is not a
 * tiling -- the editor shows the message in the preview instead of a picture. A picture that is not a
 * tiling of the drawing and is not the drawing either has nothing to say for itself, so it is not drawn.
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
   * Output bitmap geometry: 9:16, 540 pixels tall.
   *
   * A 9:16 image 540 pixels tall is 303.75 wide, so 304 is the closest integer width -- off by one
   * eighth of a pixel, i.e. 0.08%, exactly as it was at 152x270. Doubling the height keeps the same
   * aspect and the same fractional error; nothing an image format can express closer than that.
   */
  const val BITMAP_WIDTH = 304
  const val BITMAP_HEIGHT = 540

  /**
   * Pixels per synthesized cell.
   *
   * This is what sets the *size of the pattern's pixels on screen*: the image is [BITMAP_WIDTH] x
   * [BITMAP_HEIGHT] and the grid is that divided by this, so halving it doubles the number of cells and
   * therefore draws each synthesized pixel at half the size. The image size itself does not change.
   *
   * It is 2, i.e. 152x270 = 41,040 cells. That is four times the cell count of the previous 76x135
   * grid, so the search has four times the work and four times the memory; see the note on
   * [TIMEOUT_NANOS] for what that does to the worst case. One pixel per cell (304x540 cells) was not
   * viable in earlier probes.
   */
  const val PIXELS_PER_CELL = 2

  /** The grid the solver fills: one cell per [PIXELS_PER_CELL] square of the bitmap. */
  const val DEFAULT_COLUMNS = BITMAP_WIDTH / PIXELS_PER_CELL
  const val DEFAULT_ROWS = BITMAP_HEIGHT / PIXELS_PER_CELL

  /** Cells the default output has; keeping this fixed is what keeps a reshape as cheap as the default. */
  const val DEFAULT_CELL_COUNT = DEFAULT_COLUMNS * DEFAULT_ROWS

  /** Narrowest and widest output shape the editor offers, as width / height. */
  private const val MIN_OUTPUT_ASPECT = 0.4f
  private const val MAX_OUTPUT_ASPECT = 2.5f

  /** Guards against a grid too small for the solver to say anything, or too large to keep in memory. */
  private const val MIN_OUTPUT_SIDE = 32
  private const val MAX_OUTPUT_SIDE = 1024

  /**
   * The solver grid to fill for an output of [aspect] (width / height) with about [cells] cells.
   *
   * Time and memory follow the *number* of cells, not the shape, so reshaping an output keeps the cost of
   * the default 9:16 one: a square is 202x202, a 16:9 one is 270x152, all within a cell or two of 41,040.
   * The result is in cells, so the bitmap is this multiplied by [PIXELS_PER_CELL].
   */
  fun gridForAspect(aspect: Float, cells: Int = DEFAULT_CELL_COUNT): Pair<Int, Int> {
    // coerceIn passes a NaN straight through, and rounding one throws, so a nonsense aspect falls back
    // to a square rather than to an exception.
    val safeAspect = if (aspect.isFinite()) aspect.coerceIn(MIN_OUTPUT_ASPECT, MAX_OUTPUT_ASPECT) else 1f
    val safeCells = cells.coerceAtLeast(MIN_OUTPUT_SIDE * MIN_OUTPUT_SIDE)
    val columns = sqrt(safeCells.toDouble() * safeAspect)
      .roundToInt()
      .coerceIn(MIN_OUTPUT_SIDE, MAX_OUTPUT_SIDE)
    val rows = (safeCells.toDouble() / columns)
      .roundToInt()
      .coerceIn(MIN_OUTPUT_SIDE, MAX_OUTPUT_SIDE)
    return columns to rows
  }

  private const val MAX_DECISIONS = 1_000_000

  /**
   * The longest the relaxed attempt may spend searching, in nanoseconds.
   *
   * By the time the edge rule has been dropped this is the last chance at a picture, so it is not measured
   * against anything: a slow collapse is still better than none. It is bounded all the same, so a
   * pathological tile set cannot block a device for minutes.
   */
  private const val TIMEOUT_NANOS = 8_000_000_000L

  /**
   * How many times the previous solve's cost a solve gets before the drawing is reported as untileable.
   *
   * A search that has already run four times as long as the previous picture took is not going to produce
   * one while the drawing is being looked at, and an answer that arrives after the person has moved on is
   * worth nothing. Waiting for a fixed timeout instead is what used to leave a stale picture on screen for
   * seconds after an edit.
   */
  private const val SOLVE_BUDGET_FACTOR = 4L

  /** A solve's budget before any generation has been measured: a slow but possible solve is about a second. */
  private const val FIRST_SOLVE_BUDGET_MS = 1_000L

  /** Floor: below this, a GC pause or a cold cache could report a drawing as untileable that tiles fine. */
  private const val MIN_SOLVE_BUDGET_MS = 150L

  /** Ceiling: a solve may never run long enough to be a wait, whatever the last generation cost. */
  private const val MAX_SOLVE_BUDGET_MS = 4_000L

  /** Longest the hedge may grow to; beyond this a long solve should have hedged much earlier. */
  private const val MAX_HEDGE_MS = 400L

  /** Shortest the hedge may shrink to, so a run of fast collapses never disables the portfolio. */
  private const val MIN_HEDGE_MS = 40L

  /** The hedge used until a solve has been measured: see [lastSolveMillis]. */
  private const val FAST_SOLVER_HEDGE_MS = 60L

  /**
   * Solvers to run in parallel; the portfolio only uses more than one when the first is slow.
   *
   * Two, not four. Measured on a Pixel 7a with a 73-pattern drawing on the shipping 152x270 grid, fifteen
   * seeds each: one worker 277 ms median, two workers 256 ms, four workers 313 ms. A collapse is memory
   * bound -- concurrent solvers fight over the same bandwidth -- so past two the extra cores make each
   * other slower instead of faster, and the phone pays for four threads of CPU on every edit.
   */
  private const val WORKERS = 2

  /**
   * How long the last solve that produced a picture took, in milliseconds; 0 until one has been measured.
   *
   * A fixed hedge cannot be right for both ends: the native solver collapses a simple drawing in under
   * a millisecond, where starting three more workers is pure cost, and it can spend seconds on an
   * unlucky seed, where not starting them is the cost. Worse, a hedge *shorter* than the solve time is
   * actively harmful -- measured on a 76x135 grid, a drawing one worker collapses in 81 ms took 108 ms
   * once three hedged workers were competing with it for the same cores. So the hedge follows the last
   * solve: 1.5x what a single worker needed last time, clamped so one fast collapse cannot disable the
   * portfolio and one slow collapse cannot stall it.
   *
   * A solve's budget is measured against the same number ([solveBudgetMillis]): it gets four times what
   * the last picture cost before the drawing is reported as one that cannot be tiled. What it times is
   * the *winning* solve, not the wall clock of a failed generation, so a drawing that keeps failing does
   * not ratchet its own budget up towards the ceiling -- that wait is what the budget exists to cut short.
   *
   * Guarded by [cacheLock] like the rest of the generation state: a cancelled generation can still be
   * inside one when the next starts.
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

  /** Whether the cached picture was made with the edges wrapped; part of the cache key like the seed. */
  private var cachedWrapEdges: Boolean = false

  /** Whether the cached tile set carried mirrored patterns; part of the cache key like the seed. */
  private var cachedMirrorSymmetry: Boolean = false

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

  /**
   * How long a solve may run before the drawing is reported as one that cannot be tiled, in milliseconds.
   *
   * Tied to what the last picture cost rather than to a constant (see [SOLVE_BUDGET_FACTOR]), and clamped
   * at both ends: the floor stops a hiccup from declaring a drawing untileable when it tiles perfectly
   * well, and the ceiling stops one pathological generation from making the next one wait ages for it.
   *
   * @param previousSolveMillis how long the last picture's own solve took; 0 or less when none has been
   *   measured yet, which is the first generation after launch.
   */
  internal fun solveBudgetMillis(previousSolveMillis: Long): Long =
    (if (previousSolveMillis <= 0) FIRST_SOLVE_BUDGET_MS
    else previousSolveMillis * SOLVE_BUDGET_FACTOR)
      .coerceIn(MIN_SOLVE_BUDGET_MS, MAX_SOLVE_BUDGET_MS)

  private fun previousSolveMillis(): Long = synchronized(cacheLock) { lastSolveMillis }

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
     * The generated image: [columns] x [rows] cells expanded to [BITMAP_WIDTH] x [BITMAP_HEIGHT] pixels,
     * i.e. a [PIXELS_PER_CELL] square per cell.
     *
     * This is the image the app hands to the preview and the one a caller would save; there is no
     * separately-scaled copy.
     */
    val bitmap: Bitmap,
    val columns: Int,
    val rows: Int,
    val tileCount: Int,
    val millis: Long,
    val seed: Int,
    val wonBySeed: Int?,
    val workers: Int,
    /**
     * True when the edge rule had to be dropped to get a tiling at all, i.e. the picture is an ordinary
     * tiling of the drawing's patterns and its border does not show the drawing's border. See
     * [edgeRuleNotice] for the line that says so.
     */
    val relaxedEdges: Boolean = false,
    /** Time spent turning the solved cells into pixels, in milliseconds. */
    val renderMillis: Long = 0,
    /** Time the solver portfolio reported for itself, in milliseconds. */
    val solveMillis: Long = 0,
    /** Time spent sampling the tile set and building the native model, in milliseconds. */
    val extractMillis: Long = 0,
  ) {
    /** What the preview draws: the generated image itself. */
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) { bitmap.asImageBitmap() }
  }

  /**
   * @param pixels ARGB of the source drawing, row-major, `sourceWidth * sourceHeight` entries.
   * @param wrapEdges sample the drawing as a torus, so its borders are not borders and the pattern may
   *   run off one edge and back on the other (see [BitmapWaveFunctionCell.createCells]). False -- the
   *   default -- keeps the edge rule, where the drawing's border has to be the picture's border.
   * @param mirrorSymmetry also offer every window's mirror image, which means more patterns to arrange and
   *   therefore more possible pictures from the same drawing (see [BitmapWaveFunctionCell.createCells]).
   *   Off by default: a mirrored pattern is one the drawing does not contain.
   */
  suspend fun generate(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    seed: Int,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    wrapEdges: Boolean = false,
    mirrorSymmetry: Boolean = false,
  ): Output = withContext(Dispatchers.Default) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || pixels.size < sourceWidth * sourceHeight) {
      throw GenerationException("The drawing is empty - draw a pattern first.")
    }
    val source = IntArray2D(sourceWidth, sourceHeight, pixels.copyOf())
    /*
     * Extraction, bordered or wrapped. Bordered, the windows that hang over an edge are blank there, which
     * is what the solver's edge rule needs to keep the drawing's edges on the bitmap's edges; wrapped, they
     * read the opposite edge instead, which is a drawing with no edges to keep.
     *
     * Sampling is incremental (WindowTally), and the packed tile set doubles as the cache key: because
     * the collapse is seeded, an edit that leaves the tile set untouched would produce exactly the
     * bitmap already on screen, so it is handed straight back. Both the tally and the cache are
     * guarded, because a cancelled generation can still be inside its extraction when the next starts.
     */
    val packed: IntArray
    val extractStartedAt = System.nanoTime()
    synchronized(cacheLock) {
      val sampled = tally.sample(source, wrap = wrapEdges, mirrorSymmetry = mirrorSymmetry)
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
        rows == reusable.rows && cachedPacked.contentEquals(packed) &&
        cachedWrapEdges == wrapEdges && cachedMirrorSymmetry == mirrorSymmetry
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
     * The edge rule first, and the rule dropped only if it cannot be kept.
     *
     * With the edges wrapped the drawing has no border for the rule to respect -- every tile is a complete
     * window -- so there is nothing to try: the rule is dropped from the start and the model is the
     * ordinary overlapping one.
     *
     * Without wrapping the rule is the whole point: a border cell accepts only patterns sampled on the
     * matching edge of the drawing, so the drawing's border is the picture's border. But that rule can be
     * *impossible* rather than merely slow: the bitmap's border may only use patterns sampled on the
     * drawing's matching edge, so it is a walk over the drawing's border patterns, and a border that does
     * not repeat cannot walk 152 or 270 cells. Measured on a 16x11 drawing with a per-row border: no
     * tiling, proven in 5 ms with 0 decisions -- so this is the common case for a busy drawing, not a rare
     * one. A relaxed tiling is still a picture of the drawing's patterns, only without the drawing's border
     * around it, so it is shown and the notice line says what happened and what to change.
     *
     * The two attempts have different budgets on purpose. The edge rule gets four times what the last
     * solve cost (see [solveBudgetMillis]) because it is worth waiting for a little; the relaxed attempt
     * gets the full [TIMEOUT_NANOS], because by then the alternative is no picture at all.
     */
    val scope = this
    val shouldAbort = { !scope.isActive }
    val budgetMillis = solveBudgetMillis(previousSolveMillis())
    var relaxedEdges = false
    var tier = solveTier(
      packed = packed,
      columns = columns,
      rows = rows,
      seed = seed,
      dropEdgeRule = wrapEdges,
      deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000,
      shouldAbort = shouldAbort,
    )
    ensureActive()
    if (tier?.result?.assignment == null && !wrapEdges) {
      // The edge rule found no tiling (or none in time). Dropping it is the difference between a picture
      // whose border is not the drawing's and no picture at all, and the notice line says which.
      tier = solveTier(
        packed = packed,
        columns = columns,
        rows = rows,
        seed = seed,
        dropEdgeRule = true,
        deadlineNanos = System.nanoTime() + TIMEOUT_NANOS,
        shouldAbort = shouldAbort,
      )
      ensureActive()
      relaxedEdges = tier?.result?.assignment != null
    }
    if (tier == null) {
      throw GenerationException("No pattern in this drawing can touch another, so it cannot be tiled.")
    }
    val assignment = tier.result.assignment
      ?: throw GenerationException(noTilingMessage(columns, rows, wrapEdges))
    val millis = System.currentTimeMillis() - startedAt
    // A model tile's centre colour is exactly the pixel the cell represents (the model kept the packed
    // kernels), and the compatibility table guarantees every neighbour relation in the result holds in
    // the drawing, under whichever sampling produced the domains.
    val renderStartedAt = System.nanoTime()
    val target = synchronized(cacheLock) { renderTarget(columns, rows) }
    val bitmap = target.renderSolved(assignment, tier.sources, tier.centers, columns, rows)
    val renderMillis = (System.nanoTime() - renderStartedAt) / 1_000_000
    // What the next solve's budget is measured against: see [lastSolveMillis].
    val solveMillis = tier.result.millis
    val output = Output(
      bitmap = bitmap,
      columns = columns,
      rows = rows,
      tileCount = tier.tileCount,
      millis = millis,
      seed = seed,
      wonBySeed = tier.result.seed,
      workers = tier.result.entries.size,
      relaxedEdges = relaxedEdges,
      renderMillis = renderMillis,
      solveMillis = solveMillis,
      extractMillis = extractNanos / 1_000_000,
    )
    synchronized(cacheLock) {
      cachedPacked = packed
      cachedOutput = output
      cachedSeed = seed
      cachedWrapEdges = wrapEdges
      cachedMirrorSymmetry = mirrorSymmetry
      lastSolveMillis = solveMillis
    }
    output
  }

  /** One model plus the solve on it, kept together because the mapping is needed to render. */
  private class Tier(
    val result: net.akachaki.processing.engine.wavefunctioncollapse.bitmap.PortfolioResult,
    val sources: IntArray,
    val centers: IntArray,
    val tileCount: Int,
  )

  /**
   * Builds a model for the tile set and solves it, or returns null when nothing in it can be placed.
   *
   * The model is destroyed before returning; `sources` and `centers` are read out first because the
   * solved cells index the model's tiles and the colours live in the model.
   *
   * @param dropEdgeRule true for a wrapped tile set: the edge rule asks a border cell to sit on a pattern
   *   sampled on that edge of the drawing, and a wrapped sampling has no such patterns, so the rule is
   *   dropped and any complete pattern may sit anywhere.
   * @param deadlineNanos the absolute [System.nanoTime] the search must stop at.
   */
  private suspend fun solveTier(
    packed: IntArray,
    columns: Int,
    rows: Int,
    seed: Int,
    dropEdgeRule: Boolean,
    deadlineNanos: Long,
    shouldAbort: () -> Boolean,
  ): Tier? {
    val nativeModel = WfcNative.modelCreate(
      packed,
      packed.size / WfcNative.PACKED_TILE_INTS,
      columns,
      rows,
      relaxedEdges = dropEdgeRule,
    )
    if (nativeModel == 0L) return null
    return try {
      val tileCount = WfcNative.modelTileCount(nativeModel)
      val sources = IntArray(tileCount) { tile -> WfcNative.modelTileSource(nativeModel, tile) }
      val centers = IntArray(tileCount) { tile -> WfcNative.modelTileCenter(nativeModel, tile) }
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
      val result = portfolio.solve(
        seed = seed,
        workers = WORKERS,
        deadlineNanos = deadlineNanos,
        maxDecisions = MAX_DECISIONS,
        shouldAbort = shouldAbort,
      )
      Tier(result, sources, centers, tileCount)
    } finally {
      WfcNative.modelDestroy(nativeModel)
    }
  }

  /**
   * Two reusable bitmaps and one reusable pixel buffer, so a collapse stops allocating pixels.
   *
   * Each generation used to allocate a 656 KB `IntArray`, copy it into a fresh `Bitmap` and drop both;
   * both now live here. The two bitmaps alternate because the preview may still be drawing the previous
   * frame while the next one is being written: the one on screen is never the one being filled in.
   *
   * Each cell is drawn as a [PIXELS_PER_CELL] square, so the bitmaps are exactly
   * [BITMAP_WIDTH] x [BITMAP_HEIGHT] and are the generated image the app hands out.
   */
  private class RenderTarget(val columns: Int, val rows: Int) {
    private val scale = PIXELS_PER_CELL
    private val width = columns * scale
    private val height = rows * scale
    private val bitmaps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val pixels = IntArray(width * height)
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
          val colour = centres[sources[assignment[rowBase + x]]]
          fill(x, y, colour)
        }
      }
      return publish()
    }

    /** Expands one cell to a [scale] square of one colour, keeping every cell edge pixel-aligned. */
    private fun fill(x: Int, y: Int, colour: Int) {
      val pixelX = x * scale
      val rowBase = y * scale * width
      for (dy in 0 until scale) {
        val base = rowBase + dy * width + pixelX
        for (dx in 0 until scale) pixels[base + dx] = colour
      }
    }

    /** Copies the filled buffer into the next bitmap: the one on screen is never written. */
    private fun publish(): Bitmap {
      val bitmap = bitmaps[next]
      next = (next + 1) % bitmaps.size
      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
      return bitmap
    }
  }
}

/**
 * The line under the drawing when the picture is a tiling of the drawing's patterns but not of its border.
 *
 * A relaxed tiling is still a picture of the drawing -- the alternative was no picture at all -- so it is
 * shown rather than refused, and the line says what happened and what to change. It is null for the normal
 * case, so nothing is drawn under the drawing unless there is something to say.
 *
 * @param relaxedEdges the edge rule was dropped to get a tiling at all (see [WfcGenerator.Output]).
 */
fun edgeRuleNotice(
  relaxedEdges: Boolean,
  columns: Int,
  rows: Int,
): String? = if (relaxedEdges) {
  "Edges relaxed: the drawing's border can't repeat around ${columns}x$rows, so this is a plain " +
    "tiling of your patterns. Keep the drawing's outer row and column one colour to keep real edges."
} else {
  null
}

/**
 * What to say instead of a picture when the solver found no tiling for the drawing, with the edge rule or
 * without it.
 *
 * It is the message of the [WfcGenerator.GenerationException] that [WfcGenerator.generate] throws, so it is
 * what the preview shows where the picture would have been. The two modes fail for two different reasons:
 *
 *  - with the edges wrapped there are no border tiles to get wrong, so the patterns themselves do not fit
 *    together and the drawing has to change;
 *  - with real edges even the relaxed attempt found nothing, so the drawing is complex in a way the solver
 *    cannot put together, and wrapping the edges is the one setting left to try.
 *
 * @param wrapEdges whether the edges were being wrapped, which is what decides which advice applies.
 */
internal fun noTilingMessage(
  columns: Int,
  rows: Int,
  wrapEdges: Boolean,
): String = if (wrapEdges) {
  "The patterns in this drawing do not fit together. Simplify the shapes, or clear a few pixels."
} else {
  "No tiling fits this drawing, with its border or without. Turn on Wrap edges, or simplify the shapes."
}