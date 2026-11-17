package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.generation.GenerationPlan
import ca.justinmo.wfcbitmap.generation.generationPlan
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.video.*
import kotlin.math.roundToInt

/**
 * The geometry of a panning video: a wide panorama that joins end to start, a frame-sized window that walks
 * across it one pixel at a time, and the frame timing.
 *
 * The panorama is one solve -- or, when it would be too large for that, whole frames stitched with an
 * overlap -- and its right edge is pinned to its own left edge, so the last frame runs into the first and
 * the clip loops without a jump.
 */
internal data class VideoPlan(
  /** Panorama width and height in pixels. */
  val panoramaWidth: Int,
  val panoramaHeight: Int,
  /** Frame (viewport) size in solver pixels. */
  val frameWidth: Int,
  val frameHeight: Int,
  /** Pixel size of each written frame: the selected preview's size. */
  val outputWidth: Int,
  val outputHeight: Int,
  /** Panorama width in solver cells. */
  val panoramaColumns: Int,
  val panoramaRows: Int,
  val pixelsPerCell: Int,
  val cellSize: Int,
  /**
   * Frames in one loop: the panorama width divided by [pixelsPerFrame].
   *
   * The window advances [pixelsPerFrame] panorama pixels between frames, so the picture moves that many
   * pixels each time it is shown.
   */
  val frameCount: Int,
  /** How many panorama pixels the window advances between frames (1–16). */
  val pixelsPerFrame: Int,
  /** Which way the window walks around the loop. */
  val scrollDirection: ScrollDirection,
  /** GIF frame delay in whole centiseconds; a GIF cannot express anything finer than that. */
  val frameDelayCentiseconds: Int,
  /** The frame rate that delay actually produces, which is what the clip plays at. */
  val effectiveFps: Float,
  /** How long one loop runs, in seconds. */
  val loopSeconds: Float,
  /** How many frame-sized pieces the panorama is made of. */
  val screenCount: Int,
  /** How many cells at the seam are taken from the previous screen. */
  val overlapColumns: Int,
)

/** The container the video export writes. */
enum class VideoFormat(val label: String) {
  Gif("GIF"),
  Mp4("MP4"),
}

/**
 * How the export paints each solved frame.
 *
 * [Flat] is the picture exactly as the editor shows it. [Metal] lifts the drawing into a relief and
 * lights it with a studio environment, so the picture reads as a sheet of machined metal: every cell is
 * a plate, every plate edge a bevel, and the palette is kept because the metal's tint is the cell's own
 * colour. The pixels it produces are many more colours than pixel art has, so a metal GIF is palette
 * limited by the container while a metal MP4 is not.
 */
enum class VideoLook(val label: String, val description: String) {
  Flat("Flat", "The picture exactly as it is drawn."),
  Metal("Metal 3D", "The pattern raised, lit and given a metallic sheen."),
}

/**
 * What the video shows: a pan, the solve revealing the picture, a picture nudged patch by patch, or a run
 * of fresh patterns.
 */
enum class VideoMode(val label: String) {
  Panorama("Panorama"),
  Solve("Solve"),
  Evolve("Evolve"),

  /**
   * One freshly solved pattern per frame, each from a seed no earlier frame used. The clip is a riff on
   * the drawing rather than one arrangement of it: every frame is the same drawing, re-collapsed into a
   * different tiling.
   */
  Riff("Riff"),
}

/** Which way the panorama window travels around the loop. */
enum class ScrollDirection(val label: String) {
  LeftToRight("Left to right"),
  RightToLeft("Right to left"),
  Down("Down"),
  Up("Up"),
  ;

  /** True for directions that travel along the picture's height rather than its width. */
  val isVertical: Boolean
    get() = this == Down || this == Up

  /** The horizontal direction whose window path, transposed, is this vertical one. */
  internal val horizontalEquivalent: ScrollDirection
    get() = if (this == Up) RightToLeft else LeftToRight
}

/**
 * How the solve video depicts the search.
 *
 * Both variants replay the real solve captured by [ca.justinmo.wfcbitmap.generation.SolveTrace]; they
 * differ only in what an undecided cell shows.
 */
enum class SolveAnimation(val label: String, val description: String) {
  /**
   * Reveal cells only when the solver actually decided them. Undecided cells stay blank, so the order
   * on screen is the order of the search, including the first propagation wave and any backtracking.
   */
  Collapse("Collapse order", "Each cell appears when the solver really decided it."),
  /**
   * The same real decision order, with every cell that is not generated yet filled with the average of
   * the colours still possible there. Generated cells use the editor's own pixels.
   */
  Wave("Wave state", "Ungenerated cells show the average of their remaining possible colours."),
}

/** Smallest and largest loop the export offers, in frame widths. */
internal const val MIN_LOOP_SCREENS = 2
internal const val MAX_LOOP_SCREENS = 6

/** How many cells Evolve resamples per frame. */
internal const val MIN_EVOLVE_CELLS = 1
internal const val MAX_EVOLVE_CELLS = 256

/**
 * The longest an evolve clip's undo pass may get, in frames.
 *
 * The pass takes the changes back one at a time, so its length is the number of cells that changed. A
 * high cells-per-frame setting makes that number large; the cap spreads the same changes over at most
 * this many frames instead of turning a few seconds of evolution into thousands of frames.
 */
internal const val MAX_EVOLVE_UNDO_FRAMES = 600

/**
 * The changes an evolve clip's second pass has taken back by the end of each of its frames.
 *
 * Entry `i` is a cumulative count: undo frame `i` reverts that many of the recorded cell changes, newest
 * first, so the picture visibly unwinds instead of snapping back a frame at a time. When the log is
 * longer than [MAX_EVOLVE_UNDO_FRAMES] the changes are spread over that many frames, which is why an
 * entry can step by more than one.
 *
 * The final full reversal is left out on purpose: the last undo frame then holds a picture exactly one
 * change away from the clip's first frame, so looping back to the start is one more step of the same
 * undo rather than the starting picture held for two frames.
 */
internal fun evolveUndoPlan(changeCount: Int): List<Int> {
  if (changeCount <= 0) return emptyList()
  val frames = changeCount.coerceAtMost(MAX_EVOLVE_UNDO_FRAMES)
  val perFrame = (changeCount + frames - 1) / frames
  val plan = ArrayList<Int>(frames)
  var reverted = 0
  while (reverted < changeCount) {
    reverted = minOf(reverted + perFrame, changeCount)
    plan.add(reverted)
  }
  if (plan.isNotEmpty() && plan[plan.lastIndex] >= changeCount) plan.removeAt(plan.lastIndex)
  return plan
}

/** How many distinct patterns a Riff clip offers to show: one per frame. */
internal const val MIN_RIFF_PATTERNS = 4
internal const val MAX_RIFF_PATTERNS = 90
internal const val DEFAULT_RIFF_PATTERNS = 24

/**
 * How long one Riff frame's solve may take before the export gives up on it, in milliseconds.
 *
 * A Riff frame is an ordinary preview-sized solve and the clip cannot be encoded without it, so this is
 * the same generous budget the stitched panorama gives one of its screens rather than the editor's
 * adaptive budget, which is tuned for a picture that is being looked at.
 */
internal const val RIFF_FRAME_TIMEOUT_MILLIS = 15_000L

/**
 * The seeds a Riff clip solves, one per frame.
 *
 * The first seed is [baseSeed], so the clip opens on the picture already on screen. Every later entry is
 * a seed no earlier entry has used: [riffSeed] mixes the drawing's seed with the frame index through a
 * 64-bit finalizer, which puts consecutive frames far apart in seed space rather than stepping the seed
 * by one, and the list is de-duplicated so that even an unlucky collision cannot repeat a seed.
 *
 * This promises what it says -- no frame shares a seed with an earlier one. It does not promise that a
 * different seed always draws a different picture: a drawing that can only tile one way will show that
 * tiling on every frame, and no seed can change that.
 */
internal fun riffSeeds(baseSeed: Int, count: Int): List<Int> {
  if (count <= 0) return emptyList()
  val seeds = ArrayList<Int>(count)
  val used = HashSet<Int>(count * 2)
  seeds.add(baseSeed)
  used.add(baseSeed)
  var index = 0
  while (seeds.size < count) {
    val seed = riffSeed(baseSeed, index++)
    if (used.add(seed)) seeds.add(seed)
  }
  return seeds
}

/**
 * Frame [index] of a Riff clip's seed sequence: [baseSeed] mixed through SplitMix64's finalizer.
 *
 * The mixer is what makes consecutive frames land far apart in seed space. A plain `seed + index` would
 * hand the solver nearly the same random stream each time, which tends to produce arrangements that read
 * as one small change rather than a new pattern; the mixer decorrelates them.
 */
internal fun riffSeed(baseSeed: Int, index: Int): Int {
  var z = baseSeed.toLong() + (index.toLong() + 1L) * -0x61C8864680B583EBL // 0x9E3779B97F4A7C15
  z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L // 0xBF58476D1CE4E5B9
  z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L // 0x94D049BB133111EB
  return (z xor (z ushr 31)).toInt()
}

/**
 * The fastest and slowest frame rates the export offers, in frames per second.
 *
 * Both containers can go down to one frame a second. A GIF stores a whole number of centiseconds per
 * frame, so 100 cs is exactly one second; MP4 is capped separately at [MAX_MP4_FPS].
 */
internal const val MIN_VIDEO_FPS = 1
internal const val MAX_MP4_FPS = 60
internal const val MAX_VIDEO_FPS = 100

/** The slowest GIF the export writes: one frame a second, i.e. the longest delay GIF can hold cheaply. */
internal const val MIN_GIF_FPS = 1

/** GIF's whole-centisecond delays: 2 cs (50 fps) through 100 cs (1 fps). */
internal const val MIN_GIF_DELAY_CENTISECONDS = 2
internal const val MAX_GIF_DELAY_CENTISECONDS = 100

/**
 * How many cells at the loop point are pinned so that the panorama closes on itself.
 *
 * A cell's pattern is its 3x3 neighbourhood, so closing the loop needs one solved cell past the wrap on
 * each side: the last two cells of the solve repeat the first two, which makes the window that straddles
 * the wrap identical to a window the solver itself made consistent. One cell would leave the window on the
 * wrap half-solved; more than two only costs pinning.
 */
internal const val LOOP_SEAM_CELLS = 2

/**
 * The frame grid the video uses: exactly the selected preview grid.
 *
 * The export solves at the preview's own cells, so the written video has the same detail as the picture
 * on screen. A panorama too large for one solve is built as stitched screens of this same size rather
 * than being shrunk.
 */
internal fun videoFramePlan(
  aspect: OutputAspect,
  preview: PreviewScale,
  kernel: KernelSize,
): GenerationPlan = generationPlan(aspect, preview, kernel)

/**
 * Largest panorama the export solves as one grid, in cells.
 *
 * Up to this size the whole panorama is collapsed in a single solve, which is what removes the seam
 * between screens entirely: there is no join for the pattern to break at. It is also what makes the loop
 * possible, because closing the loop means pinning the last cells to the first ones and that only works if
 * the two ends are in the same solve.
 *
 * 120,000 cells is a bit under three times the finest preview grid (41,040), which the app solves in about
 * 160 ms, so every loop the slider offers stays inside it.
 */
internal const val MAX_PANORAMA_CELLS = 120_000

/**
 * How long the single panorama solve may run before the export gives up on it.
 *
 * Generous, because there is no smaller answer to fall back on: the whole picture is one solve, and a slow
 * one is still far quicker than the export that follows it.
 */
internal const val PANORAMA_TIMEOUT_MILLIS = 20_000L


/**
 * Extra cells of the previous screen kept at the seam, on top of the plain one-in-six overlap.
 *
 * The seam is a strip of cells that two screens share: the previous screen's right edge is copied onto the
 * next screen's left edge and pinned there, so both screens show the same pixels across it. But a cell's
 * pattern is its 3x3 neighbourhood, so with only the bare overlap the first cell the next screen is free to
 * choose sits one cell past the pinned strip and has just a single column of copied context to its left.
 * Any disagreement about how the pattern continues then lands immediately after the strip, which the eye
 * reads as the picture breaking at the join.
 *
 * One more cell than the join needs costs one cell of pan per screen and puts that transition inside the
 * copied strip instead of one cell past it.
 */
private const val SEAM_MARGIN_CELLS = 1

/**
 * Works out the panorama and frame grid for a looping video.
 *
 * @param frameColumns the requested preview grid width in cells.
 * @param frameRows the requested preview grid height in cells.
 * @param screens how many frame widths the loop covers.
 * @param fps the requested frame rate; the GIF delay that comes out of it is what actually plays.
 */
internal fun videoPlan(
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  screens: Int,
  fps: Int,
  pixelsPerFrame: Int = 1,
  scrollDirection: ScrollDirection = ScrollDirection.LeftToRight,
  outputWidth: Int = 0,
  outputHeight: Int = 0,
): VideoPlan {
  if (scrollDirection.isVertical) {
    // A vertical loop is the same wide panorama with its two axes swapped: generate it across the
    // picture's height, then transpose every frame back when it is written. The output keeps the
    // requested width and height.
    val generationPlan = videoPlan(
      frameColumns = frameRows,
      frameRows = frameColumns,
      pixelsPerCell = pixelsPerCell,
      cellSize = cellSize,
      screens = screens,
      fps = fps,
      pixelsPerFrame = pixelsPerFrame,
      scrollDirection = scrollDirection.horizontalEquivalent,
      outputWidth = outputWidth,
      outputHeight = outputHeight,
    )
    return generationPlan.copy(
      outputWidth = if (outputWidth > 0) outputWidth else frameColumns * pixelsPerCell,
      outputHeight = if (outputHeight > 0) outputHeight else frameRows * pixelsPerCell,
      scrollDirection = scrollDirection,
    )
  }
  val share = (frameColumns / 6).coerceIn(2, 16)
  // The seam is wider than the plain share by [SEAM_MARGIN_CELLS], but it can never take the whole frame:
  // a screen that is all overlap generates nothing new and the pan stops advancing. The floor keeps tiny
  // request sizes working rather than shrinking their seam below what they already had.
  val overlap = minOf(share + SEAM_MARGIN_CELLS, maxOf(share, frameColumns / 2))
  val advance = (frameColumns - overlap).coerceAtLeast(1)
  // A loop has to be one solve: shortening it is better than stitching it, because a stitched loop has a
  // join exactly at the point the loop closes on.
  val widest = widestLoop(frameColumns, frameRows, advance)
  val screenCount = screens.coerceIn(MIN_LOOP_SCREENS, widest)
  val panoramaColumns = frameColumns + (screenCount - 1) * advance
  val panoramaWidth = panoramaColumns * pixelsPerCell
  // A GIF's frame delay is stored in whole centiseconds, so 1 fps is the slowest it can go: 100 cs.
  // The fastest is 2 cs (50 fps), because browsers commonly clamp faster GIF delays.
  val delay = (100f / fps.coerceIn(MIN_GIF_FPS, MAX_VIDEO_FPS))
    .roundToInt()
    .coerceIn(MIN_GIF_DELAY_CENTISECONDS, MAX_GIF_DELAY_CENTISECONDS)
  val effectiveFps = 100f / delay
  val step = pixelsPerFrame.coerceIn(1, 16)
  val frameCount = ((panoramaWidth + step - 1) / step).coerceAtLeast(1)
  return VideoPlan(
    panoramaWidth = panoramaWidth,
    panoramaHeight = frameRows * pixelsPerCell,
    frameWidth = frameColumns * pixelsPerCell,
    frameHeight = frameRows * pixelsPerCell,
    outputWidth = if (outputWidth > 0) outputWidth else frameColumns * pixelsPerCell,
    outputHeight = if (outputHeight > 0) outputHeight else frameRows * pixelsPerCell,
    panoramaColumns = panoramaColumns,
    panoramaRows = frameRows,
    pixelsPerCell = pixelsPerCell,
    cellSize = cellSize,
    frameCount = frameCount,
    pixelsPerFrame = step,
    scrollDirection = scrollDirection,
    frameDelayCentiseconds = delay,
    effectiveFps = effectiveFps,
    loopSeconds = frameCount * step / effectiveFps,
    screenCount = screenCount,
    overlapColumns = overlap,
  )
}

/**
 * The longest loop that still fits one solve, in frame widths.
 *
 * The solve is a little wider than the panorama by [LOOP_SEAM_CELLS], because closing the loop needs cells
 * past the wrap; the budget is measured on that wider grid so that the loop the plan promises is the loop
 * the export can actually build.
 */
internal fun widestLoop(frameColumns: Int, frameRows: Int, advance: Int): Int {
  var widest = MIN_LOOP_SCREENS
  for (screens in MIN_LOOP_SCREENS..MAX_LOOP_SCREENS) {
    val columns = frameColumns + (screens - 1) * advance + LOOP_SEAM_CELLS
    if (columns * frameRows <= MAX_PANORAMA_CELLS) widest = screens
  }
  return widest
}

/**
 * Places one generated screen into the panorama.
 *
 * The first screen is copied whole. Every later screen is laid down at one advance to the right; its
 * left overlap is replaced with the previous screen's right edge, and only the newly generated part
 * beyond the overlap is copied. The previous edge is already in the panorama at `startX`, so copying
 * it from there also proves the seam is continuous.
 *
 * @param seamColumns how many cells at this screen's left edge were actually pinned to the previous
 *   screen, which is [VideoPlan.overlapColumns] unless a pinned solve had to be given up in stages.
 *   The copy has to match the pin exactly. Pasting the previous screen's edge over cells the solver was
 *   left free to choose puts its own choice just past the paste, and that disagreement is precisely the
 *   hard jump at the seam; copying exactly what was pinned keeps the boundary continuous instead,
 *   because a pinned cell renders the previous screen's own pixel.
 */
internal fun stitchScreen(
  panorama: IntArray,
  plan: VideoPlan,
  screenPixels: IntArray,
  screenIndex: Int,
  seamColumns: Int = plan.overlapColumns,
) {
  require(screenPixels.size == plan.frameWidth * plan.frameHeight)
  val overlapPixels = seamColumns.coerceIn(0, plan.overlapColumns) * plan.pixelsPerCell
  val advancePixels = plan.frameWidth - plan.overlapColumns * plan.pixelsPerCell
  val startX = screenIndex * advancePixels
  for (y in 0 until plan.frameHeight) {
    val row = y * plan.frameWidth
    val panoRow = y * plan.panoramaWidth
    if (screenIndex > 0) {
      // The next screen's own bitmap carries the previous screen's edge before it is written:
      // generation pins those cells, so this copy proves the seam is continuous rather than making it so.
      for (x in 0 until overlapPixels) {
        screenPixels[row + x] = panorama[panoRow + startX + x]
      }
    }
    for (x in 0 until plan.frameWidth) {
      panorama[panoRow + startX + x] = screenPixels[row + x]
    }
  }
}

/**
 * Cuts frame [index] out of the panorama, wrapping past its right edge back to its left one.
 *
 * The window moves one pixel per frame and the panorama is a loop, so the last frames show their right
 * part followed by the panorama's own beginning -- the same thing the first frame shows, which is what
 * makes the clip run into itself without a jump. Frame [VideoPlan.frameCount] would be frame 0 again, so
 * the loop stops one frame short of it.
 */
internal fun cropFrame(
  panorama: IntArray,
  panoramaWidth: Int,
  plan: VideoPlan,
  index: Int,
): IntArray {
  val frame = IntArray(plan.frameWidth * plan.frameHeight)
  val shifted = if (plan.frameCount <= 1) {
    0
  } else {
    (index * plan.pixelsPerFrame) % panoramaWidth
  }
  val reverse = plan.scrollDirection == ScrollDirection.RightToLeft ||
    plan.scrollDirection == ScrollDirection.Up
  val x = if (reverse && shifted != 0) panoramaWidth - shifted else shifted
  val head = minOf(plan.frameWidth, panoramaWidth - x).coerceAtLeast(0)
  for (y in 0 until plan.frameHeight) {
    val source = y * panoramaWidth + x
    val target = y * plan.frameWidth
    panorama.copyInto(frame, target, source, source + head)
    if (head < plan.frameWidth) {
      // Off the right edge and round to the left one: the rest of the window is the panorama's beginning.
      panorama.copyInto(frame, target + head, y * panoramaWidth, y * panoramaWidth + plan.frameWidth - head)
    }
  }
  return frame
}

/** Every frame of the loop, in order. */
internal fun panFrames(panorama: IntArray, panoramaWidth: Int, plan: VideoPlan): List<IntArray> =
  List(plan.frameCount) { cropFrame(panorama, panoramaWidth, plan, it) }

/**
 * A rough file-size estimate.
 *
 * It is measured on the *output* pixels, after the solver frame has been upscaled to the selected
 * preview size, so changing preview detail moves the estimate as well as the picture. The per-pixel
 * figures are deliberately rough: pixel-art GIF LZW and H.264 both depend heavily on the drawing.
 */
internal fun estimatedVideoBytes(plan: VideoPlan, format: VideoFormat): Long {
  val pixels = plan.outputWidth.toLong() * plan.outputHeight.toLong()
  val frameCount = plan.frameCount.toLong()
  val bytesPerPixel = when (format) {
    VideoFormat.Gif -> 0.12
    VideoFormat.Mp4 -> 0.04
  }
  return (pixels * frameCount * bytesPerPixel).toLong().coerceAtLeast(1024L)
}

/** 512 KB, 3.4 MB, 1.2 GB: enough precision for an estimate, not a byte count. */
internal fun formatByteSize(bytes: Long): String {
  val kb = bytes / 1024.0
  val mb = kb / 1024.0
  val gb = mb / 1024.0
  return when {
    gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
    mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
    else -> String.format(java.util.Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
  }
}

/**
 * Nearest-neighbour enlargement of a solved frame to the preview's output size.
 *
 * The solve is capped so a large preview stays solvable; this is what makes the written video still
 * 1080×1920 instead of the solver's smaller grid.
 */
internal fun transposePixels(pixels: IntArray, width: Int, height: Int): IntArray {
  val transposed = IntArray(width * height)
  for (y in 0 until height) {
    for (x in 0 until width) {
      transposed[x * height + y] = pixels[y * width + x]
    }
  }
  return transposed
}

internal fun scaleFrame(
  frame: IntArray,
  width: Int,
  height: Int,
  targetWidth: Int,
  targetHeight: Int,
): IntArray {
  if (width == targetWidth && height == targetHeight) return frame
  val scaled = IntArray(targetWidth * targetHeight)
  for (y in 0 until targetHeight) {
    val sourceRow = (y.toLong() * height / targetHeight).toInt().coerceIn(0, height - 1) * width
    val targetRow = y * targetWidth
    for (x in 0 until targetWidth) {
      scaled[targetRow + x] = frame[sourceRow + (x.toLong() * width / targetWidth).toInt()]
    }
  }
  return scaled
}