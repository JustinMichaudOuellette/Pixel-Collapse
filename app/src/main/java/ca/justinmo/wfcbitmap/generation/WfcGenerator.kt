package ca.justinmo.wfcbitmap.generation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.generation.WfcGenerator.MAX_SOLVE_BUDGET_MS
import ca.justinmo.wfcbitmap.generation.WfcGenerator.RETRY_BUDGET_MS
import ca.justinmo.wfcbitmap.generation.WfcGenerator.SOLVE_BUDGET_FACTOR
import ca.justinmo.wfcbitmap.generation.WfcGenerator.TIMEOUT_NANOS
import ca.justinmo.wfcbitmap.generation.WfcGenerator.cacheLock
import ca.justinmo.wfcbitmap.generation.WfcGenerator.lastSolveMillis
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.previewPixelSize
import ca.justinmo.wfcbitmap.output.previewStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.akachaki.processing.engine.array.IntArray2D
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
 * The bitmap is the solver grid times the pixels per cell the caller asks for. The editor's preview
 * resolutions trade detail for speed: the default 1x fills roughly 38x68 cells with four pixels each,
 * the base 41,040-cell 9:16 grid (152x270) is the finest preview, and one cell per pixel would be a
 * 304x540 grid, which the strict search does not finish.
 *
 * All the heavy work runs on [Dispatchers.Default]; callers pass a snapshot of the drawing (plain
 * `IntArray` of ARGB) rather than the Compose state, so no snapshot state is touched off the main
 * thread.
 */
object WfcGenerator {

  private const val TAG = "WfcGenerator"

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
   * the 9:16 one: a square is 202x202, a 16:9 one is 270x152, all within a cell or two of 41,040 at the
   * default count. The preview passes its own count to trade detail for solve time. The result is in cells,
   * so the bitmap is this multiplied by the caller's pixels per cell.
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

  /**
   * What a generation gets when the one before it found no picture, in milliseconds.
   *
   * Deliberately above [MAX_SOLVE_BUDGET_MS]. A failed generation measures nothing, so [lastSolveMillis]
   * still describes the last picture that *worked* -- usually a fast one -- and four times that can be
   * shorter than the search that just ran out of time. Retrying then hands the search the very budget
   * that failed, so a drawing that timed out once times out every time. A retry is the person asking
   * again: it gets a fixed five seconds, long enough for a collapse that is slow rather than impossible.
   */
  private const val RETRY_BUDGET_MS = 5_000L

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

  /**
   * Whether the last generation ended without a picture, which is what a retry is answering.
   *
   * Only a generation that got as far as the search counts: an empty drawing or one with too many
   * patterns fails before any time is spent, and handing the next drawing five seconds because of that
   * would only make a genuinely untileable one slower to report. A cancelled generation never counts --
   * it was replaced, not attempted. Guarded by [cacheLock] like the rest of the generation state.
   */
  private var lastGenerationFailed: Boolean = false

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

  /** Whether the cached tile set carried patterns mirrored left to right; part of the cache key like the seed. */
  private var cachedMirrorHorizontal: Boolean = false

  /** Whether the cached tile set carried patterns flipped top to bottom; part of the cache key as well. */
  private var cachedMirrorVertical: Boolean = false

  /** Whether the cached tile set carried patterns turned a quarter turn; part of the cache key as well. */
  private var cachedQuarterTurn: Boolean = false

  /** How many pixels per cell the cached picture was drawn with; part of the cache key as well. */
  private var cachedPixelsPerCell: Int = PIXELS_PER_CELL

  /** The reusable bitmaps the session's picture is drawn into. Guarded by [cacheLock]. */
  private var targets: RenderTarget? = null

  /**
   * The reusable bitmaps a demonstration is drawn into; see [generate]'s `isSessionPicture`.
   *
   * Kept apart from [targets] rather than sharing it: a target alternates between its two bitmaps, so a
   * demonstration drawing into the session's target would eventually overwrite the bitmap the editor is
   * still showing. Guarded by [cacheLock] like [targets].
   */
  private var sampleTargets: RenderTarget? = null

  private fun renderTarget(
    columns: Int,
    rows: Int,
    pixelsPerCell: Int,
    isSessionPicture: Boolean,
  ): RenderTarget {
    val existing = if (isSessionPicture) targets else sampleTargets
    if (existing != null && existing.columns == columns && existing.rows == rows &&
      existing.pixelsPerCell == pixelsPerCell
    ) {
      return existing
    }
    return RenderTarget(columns, rows, pixelsPerCell).also {
      if (isSessionPicture) targets = it else sampleTargets = it
    }
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
   * A generation that follows a failed one is the exception: there is nothing to measure against, so it
   * gets [RETRY_BUDGET_MS] outright -- see that constant for why a retry must not inherit the budget that
   * just ran out.
   *
   * @param previousSolveMillis how long the last picture's own solve took; 0 or less when none has been
   *   measured yet, which is the first generation after launch.
   * @param lastGenerationFailed whether the last generation ended without a picture.
   */
  internal fun solveBudgetMillis(
    previousSolveMillis: Long,
    lastGenerationFailed: Boolean = false,
  ): Long = when {
    lastGenerationFailed -> RETRY_BUDGET_MS
    previousSolveMillis <= 0 -> FIRST_SOLVE_BUDGET_MS
    else -> (previousSolveMillis * SOLVE_BUDGET_FACTOR)
      .coerceIn(MIN_SOLVE_BUDGET_MS, MAX_SOLVE_BUDGET_MS)
  }

  private fun previousSolveMillis(): Long = synchronized(cacheLock) { lastSolveMillis }

  private fun previousGenerationFailed(): Boolean = synchronized(cacheLock) { lastGenerationFailed }

  private fun noteGenerationFailed() {
    synchronized(cacheLock) { lastGenerationFailed = true }
  }

  /**
   * The solver packs tile domains into 64-bit words and a 6-bit word index; this is its ceiling.
   *
   * Must match `WFC_MAX_TILES` in wfc.h. The native check is a second guard: raising this without the
   * native constant only produces the generic "no tiling" error further down.
   */
  private const val MAX_TILES = 4096

  /**
   * Largest tile set that keeps the two-worker, pooled fast path.
   *
   * Above this a solver's domains are 32+ words wide, so one worker alone needs hundreds of megabytes
   * at the largest grids and a second worker would double that for a bandwidth-bound search. Turning
   * the portfolio down to one worker here is what keeps the 4,096-pattern tier from regressing the
   * memory behaviour of the ≤1,024 path.
   */
  internal const val FAST_TILE_LIMIT = 1024

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
     * The generated image: [columns] x [rows] cells expanded to one square of pixels per cell.
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
    /**
     * True when the exact search found no tiling and the picture was filled best-effort instead. The
     * picture is made of the drawing's patterns but is not guaranteed to be a tiling; [relaxedCells]
     * says how much of it could not be made to fit.
     */
    val bestEffort: Boolean = false,
    /**
     * In a best-effort picture, how many cells have at least one incompatible neighbour. Zero is
     * possible: the fill may happen to produce a valid tiling even when the search could not prove one.
     */
    val relaxedCells: Int = 0,
    /** Time spent turning the solved cells into pixels, in milliseconds. */
    val renderMillis: Long = 0,
    /** Time the solver portfolio reported for itself, in milliseconds. */
    val solveMillis: Long = 0,
    /** Time spent sampling the tile set and building the native model, in milliseconds. */
    val extractMillis: Long = 0,
    /** The centre value of every solved cell, row-major; what a following screen pins its edge to. */
    val cellCenters: IntArray? = null,
    /**
     * The packed pattern index of every solved cell, row-major: the *shape* that produced the cell and not
     * just its colour. A following screen pins its edge to this as well, so the seam continues the
     * previous screen's diagonals instead of only its edge pixels.
     */
    val cellPatterns: IntArray? = null,
    /** Model-tile index of every solved cell, row-major; local Evolve resamples from this. */
    val assignment: IntArray? = null,
    /**
     * The native model's edge rule for this picture. [relaxedEdges] is the user-facing "the drawing's
     * border could not be kept" flag, which is false for a wrapped drawing even though its model also
     * drops the rule; a replay needs the rule the solver actually ran with.
     */
    val modelRelaxedEdges: Boolean = false,
    /**
     * The packed tile set the solve used. A solve animation replays this model with [wonBySeed] to
     * capture the real search instead of revealing the finished bitmap in a shuffled order.
     */
    val packedTiles: IntArray? = null,
    /** The grouped cell blocks, when the kernel is larger than 3x3, for replaying a solve. */
    val blocks: List<IntArray>? = null,
    /** One cell's side in drawing pixels, i.e. the kernel size. */
    val cellSize: Int = 1,
  ) {
    /** What the preview draws: the generated image itself. */
    val image: ImageBitmap by lazy(LazyThreadSafetyMode.PUBLICATION) { bitmap.asImageBitmap() }
  }

  /**
   * @param pixels ARGB of the source drawing, row-major, `sourceWidth * sourceHeight` entries.
   * @param wrapEdges sample the drawing as a torus, so its borders are not borders and the pattern may
   *   run off one edge and back on the other (see [BitmapWaveFunctionCell.createCells]). False -- the
   *   default -- keeps the edge rule, where the drawing's border has to be the picture's border.
   * @param mirrorHorizontal also offer every window mirrored left to right, which means more patterns to
   *   arrange and therefore more possible pictures from the same drawing (see
   *   [BitmapWaveFunctionCell.createCells]). Off by default: a mirrored pattern is one the drawing does not
   *   contain.
   * @param mirrorVertical also offer every window flipped top to bottom, the other mirror; with both on the
   *   half turn comes with them.
   * @param quarterTurn also offer every window turned a quarter turn clockwise, the third symmetry setting.
   *   Independent of the mirrors, and composing with them: with all three on the tile set carries all eight
   *   orientations of every window (see [BitmapWaveFunctionCell.createCells]).
   * @param bestEffort when the exact search finds no tiling, fill every cell with the most likely pattern
   *   instead of reporting failure. The picture is an approximation with seams, not a tiling; see
   *   [Output.bestEffort]. Exact output is still used whenever the search finds it.
   * @param pixelsPerCell how many pixels of the picture each solved cell becomes. It changes only the size
   *   of the picture, not what it is -- the solver fills the same grid either way -- so it is a rendering
   *   choice rather than a solving one, and the save size is applied on top of it.
   * @param isSessionPicture whether this generation is the picture the session shows. True for every
   *   preview and every saved picture; false only for a demonstration such as the tutorial's animated
   *   sample. A demonstration's cost, outcome and result stay out of the measurements and cache below,
   *   because a 32x36 solve every second would otherwise tell the editor that a full-size picture costs a
   *   few milliseconds and drop the next edit's budget to its floor. Such a caller should pass
   *   [timeoutMillis] as well, so the sample does not inherit a retry budget meant for the editor.
   */
  suspend fun generate(
    pixels: IntArray,
    sourceWidth: Int,
    sourceHeight: Int,
    seed: Int,
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
    wrapEdges: Boolean = false,
    mirrorHorizontal: Boolean = false,
    mirrorVertical: Boolean = false,
    quarterTurn: Boolean = false,
    bestEffort: Boolean = false,
    pixelsPerCell: Int = PIXELS_PER_CELL,
    cellSize: Int = 1,
    timeoutMillis: Long? = null,
    fixedCenters: IntArray? = null,
    fixedPatterns: IntArray? = null,
    forceRelaxedEdges: Boolean = false,
    isSessionPicture: Boolean = true,
  ): Output = withContext(Dispatchers.Default) {
    if (sourceWidth <= 0 || sourceHeight <= 0 || pixels.size < sourceWidth * sourceHeight) {
      throw GenerationException("The drawing is empty - draw a pattern first.")
    }
    /*
     * Grouping: a 6x6 pattern is a 3x3 kernel of 2x2 cells, a 9x9 one a 3x3 kernel of 3x3 cells, and so
     * on. The drawing is reduced to one pixel per cell -- the colour most of the cell's pixels share --
     * and the ordinary 3x3 model then runs on those grouped pixels. Each solved cell is drawn back at
     * [cellSize] pixels so the picture keeps the drawing's size.
     */
    val cell = cellSize.coerceAtLeast(1)
    val grouped = if (cell > 1) groupCells(pixels, sourceWidth, sourceHeight, cell) else null
    val sourceColumns = grouped?.width ?: sourceWidth
    val sourceRows = grouped?.height ?: sourceHeight
    val sourcePixels = grouped?.tokens ?: pixels
    val renderScale = pixelsPerCell
    val source = IntArray2D(sourceColumns, sourceRows, sourcePixels.copyOf())
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
      val sampled = tally.sample(
        source,
        wrap = wrapEdges,
        mirrorHorizontal = mirrorHorizontal,
        mirrorVertical = mirrorVertical,
        quarterTurn = quarterTurn,
      )
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
      if (isSessionPicture && grouped == null && fixedCenters == null && fixedPatterns == null && reusable != null && cachedSeed == seed && columns == reusable.columns &&
        rows == reusable.rows && cachedPacked.contentEquals(packed) &&
        cachedWrapEdges == wrapEdges && cachedMirrorHorizontal == mirrorHorizontal &&
        cachedMirrorVertical == mirrorVertical && cachedQuarterTurn == quarterTurn &&
        cachedPixelsPerCell == renderScale &&
        // An exact picture is valid for either setting. A best-effort one must not be handed back
        // after the toggle is turned off, or normal mode would silently show an approximation.
        (!reusable.bestEffort || bestEffort)
      ) {
        // The picture from the cache is a picture: whatever failed before is no longer the last answer.
        lastGenerationFailed = false
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
     * solve cost (see [solveBudgetMillis]) because it is worth waiting for a little -- and a fixed five
     * seconds when the previous generation found no picture at all, because then there is no cost to
     * measure and the retry must not inherit the budget that just ran out; the relaxed attempt gets the
     * full [TIMEOUT_NANOS], because by then the alternative is no picture at all.
     */
    val scope = this
    val shouldAbort = { !scope.isActive }
    val isRetry = timeoutMillis == null && isSessionPicture && previousGenerationFailed()
    val budgetMillis = timeoutMillis?.coerceAtLeast(1)
      ?: solveBudgetMillis(previousSolveMillis(), isRetry)
    if (isRetry) {
      Log.i(TAG, "The last generation found no picture: retrying with a $budgetMillis ms budget")
    }
    val dropEdgeRule = wrapEdges || forceRelaxedEdges
    var relaxedEdges = forceRelaxedEdges
    var tier = solveTier(
      packed = packed,
      columns = columns,
      rows = rows,
      seed = seed,
      dropEdgeRule = dropEdgeRule,
      deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000,
      shouldAbort = shouldAbort,
      fixedCenters = fixedCenters,
      fixedPatterns = fixedPatterns,
    )
    ensureActive()
    if (tier?.result?.assignment == null && !dropEdgeRule) {
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
        fixedCenters = fixedCenters,
        fixedPatterns = fixedPatterns,
      )
      ensureActive()
      relaxedEdges = tier?.result?.assignment != null
    }
    /*
     * The last resort. The exact attempts above still run first, so a drawing that can be tiled keeps
     * today's picture exactly. Two cases reach the fill: no exact picture at all, and an exact picture
     * that is a single colour -- usually a large blank background with every cell taking the same
     * pattern. The second case is why the tool has to look at the result rather than only at failure:
     * the solver can succeed and still show nothing.
     */
    val exactAssignment = tier?.result?.assignment
    // Only inspect the colours when the tool is on; normal mode must not pay for a scan it will not use.
    val exactIsPlain = bestEffort && exactAssignment != null && isPlainField(tier!!, exactAssignment)
    if (bestEffort && (exactAssignment == null || exactIsPlain)) {
      val filled = bestEffortTier(
        packed = packed,
        columns = columns,
        rows = rows,
        seed = seed,
        sourcePixels = sourcePixels,
        sourceColumns = sourceColumns,
        sourceRows = sourceRows,
        fixedCenters = fixedCenters,
        fixedPatterns = fixedPatterns,
      )
      ensureActive()
      if (filled != null) {
        tier = filled
        // A wrapped drawing already dropped the edge rule by choice; only a genuinely relaxed border
        // should add "Edges relaxed" to the best-effort notice.
        relaxedEdges = !wrapEdges
      }
    }
    if (tier == null) {
      if (isSessionPicture) noteGenerationFailed()
      throw GenerationException("No pattern in this drawing can touch another, so it cannot be tiled.")
    }
    val assignment = tier.result.assignment
    if (assignment == null) {
      // The search ran out of budget or proved that nothing fits: either way this drawing did not come
      // back with a picture, so the next attempt is a retry and gets the retry budget.
      if (isSessionPicture) noteGenerationFailed()
      throw GenerationException(noTilingMessage(columns, rows, wrapEdges))
    }
    val millis = System.currentTimeMillis() - startedAt
    // A model tile's centre colour is exactly the pixel the cell represents (the model kept the packed
    // kernels), and the compatibility table guarantees every neighbour relation in the result holds in
    // the drawing, under whichever sampling produced the domains.
    val renderStartedAt = System.nanoTime()
    val target = synchronized(cacheLock) { renderTarget(columns, rows, renderScale, isSessionPicture) }
    val bitmap = target.renderSolved(
      assignment, tier.sources, tier.centers, columns, rows,
      blocks = grouped?.blocks,
      cell = cell,
    )
    val renderMillis = (System.nanoTime() - renderStartedAt) / 1_000_000
    // What the next solve's budget is measured against: see [lastSolveMillis].
    val solveMillis = tier.result.millis
    val cellCenters = IntArray(columns * rows) {
      // assignment indexes model tiles, and centres is indexed by model tile. Do not route this
      // through sources: that array maps a model tile to its packed source, which is a different
      // index as soon as the model prunes a pattern.
      tier.centers[assignment[it]]
    }
    val cellPatterns = IntArray(columns * rows) {
      // The packed source index of the pattern each cell was solved with: a label for the shape, stable
      // across generations because the packed order is fixed for a given tile set. This is what lets the
      // next screen reproduce an edge *pattern* rather than only its colours.
      tier.sources[assignment[it]]
    }
    val output = Output(
      bitmap = bitmap,
      columns = columns,
      rows = rows,
      tileCount = tier.tileCount,
      millis = millis,
      seed = seed,
      wonBySeed = if (tier.bestEffort) null else tier.result.seed,
      workers = if (tier.bestEffort) 0 else tier.result.entries.size,
      relaxedEdges = relaxedEdges,
      bestEffort = tier.bestEffort,
      relaxedCells = tier.relaxedCells,
      renderMillis = renderMillis,
      solveMillis = solveMillis,
      extractMillis = extractNanos / 1_000_000,
      cellCenters = cellCenters,
      cellPatterns = cellPatterns,
      assignment = assignment,
      modelRelaxedEdges = wrapEdges || relaxedEdges,
      packedTiles = packed,
      blocks = grouped?.blocks,
      cellSize = cell,
    )
    if (isSessionPicture && grouped == null && fixedCenters == null && fixedPatterns == null) {
      synchronized(cacheLock) {
        cachedPacked = packed
        cachedOutput = output
        cachedSeed = seed
        cachedWrapEdges = wrapEdges
        cachedMirrorHorizontal = mirrorHorizontal
        cachedMirrorVertical = mirrorVertical
        cachedQuarterTurn = quarterTurn
        cachedPixelsPerCell = renderScale
        lastSolveMillis = solveMillis
      }
    }
    // A picture came back: the next generation is measured against this solve again, not against the
    // retry budget a failure would have bought. Only the session's pictures count; see [isSessionPicture].
    if (isSessionPicture) synchronized(cacheLock) { lastGenerationFailed = false }
    output
  }

  /**
   * Replays the solve that produced [output] with tracing on, and returns the real search trace.
   *
   * The replay uses the exact model, edge rule, grid and winning seed of the picture on screen, so the
   * search is deterministic and produces the same tiling again. That is deliberate: recording during
   * every preview would either cost memory in the editor or record every portfolio worker, and a
   * one-worker replay at export time costs one solve and keeps the normal path unchanged.
   *
   * @return null when the model is no longer available, the replay fails, the trace buffer overflowed,
   *   or the replayed tiling differs from the displayed one. The caller should report that rather than
   *   animate a picture that is not the one on screen.
   */
  internal suspend fun traceSolve(output: Output): SolveTrace? = withContext(Dispatchers.Default) {
    val packed = output.packedTiles ?: return@withContext null
    if (!WfcNative.available) return@withContext null
    val model = WfcNative.modelCreate(
      packed,
      packed.size / WfcNative.PACKED_TILE_INTS,
      output.columns,
      output.rows,
      relaxedEdges = output.modelRelaxedEdges,
    )
    if (model == 0L) return@withContext null
    val solver = NativeTileSolver(
      model = model,
      columns = output.columns,
      rows = output.rows,
      seed = output.wonBySeed ?: output.seed,
      traceEnabled = true,
    )
    try {
      // The picture's own solve time is the best estimate of the replay cost; the floor keeps a very
      // fast first solve from timing out on a cold device, and the ceiling keeps a pathological one
      // from holding the export forever.
      val budgetMillis = (output.solveMillis.coerceAtLeast(250L) * 4 + 2_000L)
        .coerceAtMost(20_000L)
      val assignment = solver.solve(
        deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000,
        maxDecisions = MAX_DECISIONS,
      )
      if (assignment == null) {
        Log.w(TAG, "Could not replay the solve for the animation")
        return@withContext null
      }
      if (solver.traceOverflow) {
        Log.w(TAG, "The solve trace filled its event buffer and was discarded")
        return@withContext null
      }
      val initial = solver.traceInitial ?: return@withContext null
      val initialColors = solver.traceInitialColors ?: return@withContext null
      val events = solver.traceEvents ?: return@withContext null
      if (initial.size != output.columns * output.rows ||
        initialColors.size != output.columns * output.rows
      ) {
        Log.w(TAG, "Solve trace has ${initial.size} cells, expected ${output.columns * output.rows}")
        return@withContext null
      }
      val expected = output.cellPatterns
      if (expected != null) {
        val tileCount = WfcNative.modelTileCount(model)
        val sources = IntArray(tileCount) { WfcNative.modelTileSource(model, it) }
        val replayed = IntArray(output.columns * output.rows) { sources[assignment[it]] }
        if (!replayed.contentEquals(expected)) {
          Log.w(TAG, "The replayed solve differs from the picture on screen; not animating it")
          return@withContext null
        }
      }
      SolveTrace(
        columns = output.columns,
        rows = output.rows,
        tileCount = WfcNative.modelTileCount(model),
        initialSizes = initial,
        initialColors = initialColors,
        events = events,
      )
    } finally {
      solver.close()
      WfcNative.modelDestroy(model)
    }
  }

  /**
   * True when one colour fills at least 90% of the picture, i.e. the exact solve produced something
   * that reads as a plain field. Nine tenths is deliberately conservative: a picture with a real motif
   * spread through it is left alone, while a solver that took the background pattern everywhere (the
   * usual way an exact solve "succeeds" and still shows nothing) is replaced by the guided fill.
   */
  private fun isPlainField(tier: Tier, assignment: IntArray): Boolean {
    if (assignment.isEmpty()) return false
    val counts = HashMap<Int, Int>()
    var largest = 0
    for (cell in assignment) {
      val colour = tier.centers[cell]
      val count = (counts[colour] ?: 0) + 1
      counts[colour] = count
      if (count > largest) largest = count
    }
    return largest * 10 >= assignment.size * 9
  }

  /** One model plus the solve on it, kept together because the mapping is needed to render. */
  private class Tier(
    val result: net.akachaki.processing.engine.wavefunctioncollapse.bitmap.PortfolioResult,
    val sources: IntArray,
    val centers: IntArray,
    val tileCount: Int,
    val bestEffort: Boolean = false,
    val relaxedCells: Int = 0,
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
    fixedCenters: IntArray? = null,
    fixedPatterns: IntArray? = null,
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
          NativeTileSolver(nativeModel, workerColumns, workerRows, workerSeed, fixedCenters, fixedPatterns)
        },
      )
      // Large tile sets are memory-bound and their domains are 32+ words wide, so a second worker
      // duplicates hundreds of megabytes of bitsets for very little wall-clock gain. The fast path
      // (≤1,024 patterns, 16 words) keeps both workers exactly as before.
      val workers = if (tileCount > FAST_TILE_LIMIT) 1 else WORKERS
      // Hand the caller's cancellation down to the search. Without this the solve would run to its
      // deadline even though the editor had already replaced the drawing it was working on: the
      // collapse is blocking, so cancelling the coroutine alone cannot stop it.
      val result = portfolio.solve(
        seed = seed,
        workers = workers,
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
   * Fills the grid without backtracking, when the exact attempts failed or produced a one-colour field.
   *
   * The fill always produces every cell, so there is no "no tiling" answer here: the only failures are
   * a model that contains no placeable pattern and an allocation error. Relaxed edges are used because
   * the strict border rule is the usual reason the exact attempts had nowhere to go, and the source
   * colours guide the fill so a background-heavy drawing does not collapse to its background.
   * [Tier.relaxedCells] reports how many cells could not be made compatible with all their neighbours.
   */
  private fun bestEffortTier(
    packed: IntArray,
    columns: Int,
    rows: Int,
    seed: Int,
    sourcePixels: IntArray,
    sourceColumns: Int,
    sourceRows: Int,
    fixedCenters: IntArray? = null,
    fixedPatterns: IntArray? = null,
  ): Tier? {
    val nativeModel = WfcNative.modelCreate(
      packed,
      packed.size / WfcNative.PACKED_TILE_INTS,
      columns,
      rows,
      relaxedEdges = true,
    )
    if (nativeModel == 0L) return null
    return try {
      val tileCount = WfcNative.modelTileCount(nativeModel)
      val sources = IntArray(tileCount) { tile -> WfcNative.modelTileSource(nativeModel, tile) }
      val centers = IntArray(tileCount) { tile -> WfcNative.modelTileCenter(nativeModel, tile) }
      val assignment = IntArray(columns * rows)
      val preferredCenters = sourceCentersForOutput(
        sourcePixels = sourcePixels,
        sourceColumns = sourceColumns,
        sourceRows = sourceRows,
        columns = columns,
        rows = rows,
      )
      val startedAt = System.currentTimeMillis()
      val relaxedCells = WfcNative.modelFillBestEffort(
        nativeModel,
        assignment,
        fixedCenters,
        fixedPatterns,
        preferredCenters,
        seed,
      )
      if (relaxedCells < 0) return null
      val result = net.akachaki.processing.engine.wavefunctioncollapse.bitmap.PortfolioResult(
        assignment = assignment,
        seed = null,
        millis = System.currentTimeMillis() - startedAt,
        entries = emptyList(),
      )
      Tier(
        result = result,
        sources = sources,
        centers = centers,
        tileCount = tileCount,
        bestEffort = true,
        relaxedCells = relaxedCells,
      )
    } finally {
      WfcNative.modelDestroy(nativeModel)
    }
  }

  /**
   * The source colour each output cell should prefer in a best-effort fill.
   *
   * The exact search has no notion of where in the drawing a pattern came from, but the best-effort
   * fallback needs one: without it, a drawing that is mostly blank gives the fill no reason to put the
   * motif anywhere, and the picture becomes a plain background. This repeats the source over the
   * output grid at its own pixel scale -- or shrinks it when the drawing is larger than the picture --
   * and lets the native fill prefer a tile showing that colour whenever one is compatible. It is a
   * guide, not a constraint: when no tile with that centre fits, the fill falls back to its usual
   * choice.
   */
  private fun sourceCentersForOutput(
    sourcePixels: IntArray,
    sourceColumns: Int,
    sourceRows: Int,
    columns: Int,
    rows: Int,
  ): IntArray = IntArray(columns * rows) { index ->
    val x = index % columns
    val y = index / columns
    val sourceX = if (sourceColumns <= columns) {
      x % sourceColumns
    } else {
      (x.toLong() * sourceColumns / columns).toInt().coerceIn(0, sourceColumns - 1)
    }
    val sourceY = if (sourceRows <= rows) {
      y % sourceRows
    } else {
      (y.toLong() * sourceRows / rows).toInt().coerceIn(0, sourceRows - 1)
    }
    sourcePixels[sourceY * sourceColumns + sourceX]
  }

  /**
   * Two reusable bitmaps and one reusable pixel buffer, so a collapse stops allocating pixels.
   *
   * Each generation used to allocate a 656 KB `IntArray`, copy it into a fresh `Bitmap` and drop both;
   * both now live here. The two bitmaps alternate because the preview may still be drawing the previous
   * frame while the next one is being written: the one on screen is never the one being filled in.
   *
   * Each cell is drawn as a `pixelsPerCell` square, so the bitmaps are exactly the size of the picture the
   * editor is set to show, and are the generated image the app hands out.
   */
  private class RenderTarget(val columns: Int, val rows: Int, val pixelsPerCell: Int) {
    private val scale = pixelsPerCell
    private val width = columns * scale
    private val height = rows * scale
    private val bitmaps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val pixels = IntArray(width * height)
    private var next = 0

    /**
     * Draws a solved grid: cell (x, y) shows `centres[assignment[y * columns + x]]`.
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
      blocks: List<IntArray>? = null,
      cell: Int = 1,
    ): Bitmap {
      renderSolvedCells(
        pixels = pixels,
        width = width,
        columns = columns,
        rows = rows,
        scale = scale,
        assignment = assignment,
        sources = sources,
        centres = centres,
        blocks = blocks,
        cell = cell,
      )
      return publish()
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

/** The solver grid, the render scale, and the drawing cell size a generation should use. */
internal data class GenerationPlan(
  val columns: Int,
  val rows: Int,
  val pixelsPerCell: Int,
  val cellSize: Int,
)

/**
 * Works out one generation's shape.
 *
 * The picture is exactly the preview's pixel size whatever the grouping, and the square a cell is drawn
 * in is chosen to make that true ([previewStep]): a whole number of the cell's own pixels where the
 * preview allows it, which is what stops the drawing being resampled, and otherwise the largest step
 * that divides the preview rather than one that leaves the bitmap a few pixels short.
 */
internal fun generationPlan(
  aspect: OutputAspect,
  preview: PreviewScale,
  kernel: KernelSize,
): GenerationPlan {
  val (width, height) = previewPixelSize(aspect, preview)
  val pixelsPerCell = previewStep(width, height, kernel.cellSize)
  return GenerationPlan(
    columns = (width / pixelsPerCell).coerceAtLeast(1),
    rows = (height / pixelsPerCell).coerceAtLeast(1),
    pixelsPerCell = pixelsPerCell,
    cellSize = kernel.cellSize,
  )
}

/**
 * One pixel per [cell]-sized group of the drawing, where the pixel is a token naming the whole block.
 *
 * A 6x6 pattern is a 3x3 kernel of 2x2 cells: the drawing's 2x2 blocks become the cells, each block
 * keeps its four colours under a token, and the ordinary 3x3 model runs on those tokens. Rendering
 * expands the token back to the block, so the picture keeps the cell's internal detail instead of
 * averaging it away.
 *
 * Only whole cells are used; a leftover strip narrower than [cell] is ignored.
 *
 * @throws WfcGenerator.GenerationException when the drawing is smaller than one cell.
 */
internal fun groupCells(pixels: IntArray, width: Int, height: Int, cell: Int): GroupedCells {
  val columns = width / cell
  val rows = height / cell
  if (columns < 1 || rows < 1) {
    throw WfcGenerator.GenerationException(
      "The drawing is smaller than one ${cell}x$cell cell. Draw a bigger pattern or pick a smaller grouping.",
    )
  }
  val tokens = IntArray(columns * rows)
  val blocks = ArrayList<IntArray>()
  val tokenByBlock = HashMap<BlockKey, Int>()
  for (cy in 0 until rows) {
    for (cx in 0 until columns) {
      val block = IntArray(cell * cell) { slot ->
        val dy = slot / cell
        val dx = slot % cell
        pixels[(cy * cell + dy) * width + cx * cell + dx]
      }
      val key = BlockKey(block)
      val token = tokenByBlock.getOrPut(key) {
        blocks.add(block)
        blocks.size - 1
      }
      tokens[cy * columns + cx] = token
    }
  }
  return GroupedCells(tokens, columns, rows, blocks)
}

/** The drawing grouped into cells: a token per cell, and the block each token stands for. */
internal class GroupedCells(
  val tokens: IntArray,
  val width: Int,
  val height: Int,
  val blocks: List<IntArray>,
)

/** Content equality for a cell's block of pixels, so equal blocks share one token. */
private class BlockKey(val pixels: IntArray) {
  override fun equals(other: Any?): Boolean = other is BlockKey && pixels.contentEquals(other.pixels)
  override fun hashCode(): Int = pixels.contentHashCode()
}

/**
 * Which pixel of a [cell]-sized block output pixel [pixel] of a [scale]-sized square shows.
 *
 * Sampled at the output pixel's **centre**, i.e. `(pixel + 0.5) * cell / scale`. That is what makes a
 * block resample evenly: 3 pixels into 4 output pixels doubles the middle one, 8 into 4 keeps the
 * pixels nearest the centre. Truncating `pixel * cell / scale` instead doubles the *first* pixel of
 * every block, which drags each tile half a pixel up and to the left and, at the 9x9 kernel (3-pixel
 * cells, the first size that does not divide the 4-pixel square), makes the tiles look torn.
 *
 * Exact when [scale] is a multiple of [cell] (6x6, 12x12), where both formulas agree.
 */
internal fun blockSourcePixel(pixel: Int, cell: Int, scale: Int): Int =
  ((2 * pixel + 1) * cell) / (2 * scale)

/**
 * Draws a solved grid into [pixels], row-major [width].
 *
 * Without [blocks] each cell is one colour. With them, a cell names a whole [cell]-sized block and the
 * block is expanded to the cell's [scale] square: every source pixel becomes `scale / cell` output
 * pixels, so a 2x2 cell still shows four colours rather than one average.
 */
internal fun renderSolvedCells(
  pixels: IntArray,
  width: Int,
  columns: Int,
  rows: Int,
  scale: Int,
  assignment: IntArray,
  sources: IntArray,
  centres: IntArray,
  blocks: List<IntArray>?,
  cell: Int,
) {
  for (y in 0 until rows) {
    val rowBase = y * columns
    val pixelY = y * scale
    for (x in 0 until columns) {
      // assignment is a model-tile index; centres is indexed the same way. Using sources here reads a
      // packed-source index into a model-tile array, which picks the wrong colour whenever the model
      // pruned a tile.
      val solved = assignment[rowBase + x]
      val pixelX = x * scale
      if (blocks == null) {
        val colour = centres[solved]
        for (dy in 0 until scale) {
          val base = (pixelY + dy) * width + pixelX
          for (dx in 0 until scale) pixels[base + dx] = colour
        }
      } else {
        val block = blocks[centres[solved]]
        for (dy in 0 until scale) {
          val sourceRow = blockSourcePixel(dy, cell, scale) * cell
          val base = (pixelY + dy) * width + pixelX
          for (dx in 0 until scale) {
            pixels[base + dx] = block[sourceRow + blockSourcePixel(dx, cell, scale)]
          }
        }
      }
    }
  }
}

/**
 * Draws a grid that is already reduced to one centre value per cell, row-major.
 *
 * Evolve holds these per-cell centres for every frame instead of a full bitmap; this is the render step
 * that turns one back into pixels, preserving grouped-cell blocks when [blocks] is present.
 */
internal fun renderCellCenters(
  pixels: IntArray,
  width: Int,
  columns: Int,
  rows: Int,
  scale: Int,
  centres: IntArray,
  blocks: List<IntArray>?,
  cell: Int,
) {
  for (y in 0 until rows) {
    val rowBase = y * columns
    val pixelY = y * scale
    for (x in 0 until columns) {
      val centre = centres[rowBase + x]
      val pixelX = x * scale
      if (blocks == null) {
        for (dy in 0 until scale) {
          val base = (pixelY + dy) * width + pixelX
          for (dx in 0 until scale) pixels[base + dx] = centre
        }
      } else {
        val block = blocks[centre]
        for (dy in 0 until scale) {
          val sourceRow = blockSourcePixel(dy, cell, scale) * cell
          val base = (pixelY + dy) * width + pixelX
          for (dx in 0 until scale) {
            pixels[base + dx] = block[sourceRow + blockSourcePixel(dx, cell, scale)]
          }
        }
      }
    }
  }
}

/**
 * What to say when the picture is a tiling of the drawing's patterns but not of its border, or null for the
 * normal case.
 *
 * Two words and nothing else. A relaxed tiling is still a picture of the drawing -- the alternative is no
 * picture at all -- so the news is worth a moment's mention and not worth two sentences over the drawing it
 * describes: this is shown as a snackbar, which sits on top of the picture, and the picture is the thing
 * being looked at. The advice about keeping the outer row and column one colour belongs in the log with the
 * rest of the generation's detail.
 *
 * @param relaxedEdges the edge rule was dropped to get a tiling at all (see [WfcGenerator.Output]).
 */
fun edgeRuleNotice(relaxedEdges: Boolean): String? = if (relaxedEdges) "Edges relaxed" else null

/**
 * What to say when the picture is a best-effort fill rather than a tiling, or null for an exact one.
 *
 * The cell count is the point: it says the picture is an approximation and how large the approximation
 * is. Zero is still a best-effort result -- the fill happened to fit -- so it is named without a count.
 */
fun bestEffortNotice(bestEffort: Boolean, relaxedCells: Int): String? = when {
  !bestEffort -> null
  relaxedCells <= 0 -> "Best effort"
  else -> "Best effort: $relaxedCells cells"
}

/** The one notice line for a picture: the edge rule and the best-effort fill are separate facts. */
internal fun generationNotice(
  relaxedEdges: Boolean,
  bestEffort: Boolean,
  relaxedCells: Int,
): String? {
  val edge = edgeRuleNotice(relaxedEdges)
  val best = bestEffortNotice(bestEffort, relaxedCells)
  return when {
    edge == null -> best
    best == null -> edge
    else -> "$edge · $best"
  }
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