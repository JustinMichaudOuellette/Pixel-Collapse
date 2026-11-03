package ca.justinmo.wfcbitmap

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
  /** Frame (viewport) size in pixels. */
  val frameWidth: Int,
  val frameHeight: Int,
  /** Panorama width in solver cells. */
  val panoramaColumns: Int,
  val panoramaRows: Int,
  val pixelsPerCell: Int,
  val cellSize: Int,
  /**
   * Frames in one loop: one per pixel of panorama width.
   *
   * One pixel per frame is what makes the pan smooth: the window never skips a column, so the picture
   * moves by exactly one pixel each time it is shown. A whole turn of the panorama is therefore
   * [panoramaWidth] frames, and the frame after the last one is the first one again.
   */
  val frameCount: Int,
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

/** Smallest and largest loop the export offers, in frame widths. */
internal const val MIN_LOOP_SCREENS = 2
internal const val MAX_LOOP_SCREENS = 6

/** The frame rates the slider offers, in frames per second. */
internal const val MIN_VIDEO_FPS = 10
internal const val MAX_VIDEO_FPS = 50

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
 * The frame grid the video uses.
 *
 * A panorama is several frames wide, so it must not inherit the finest preview: a 4x preview at six
 * screens would ask the solver for a grid six times the size it already takes to solve. The stream uses
 * the preview when it is modest, and caps the cell count when it is not; the frame is then drawn at the
 * same pixels per cell.
 */
internal fun videoFramePlan(
  aspect: OutputAspect,
  preview: PreviewScale,
  kernel: KernelSize,
): GenerationPlan {
  val plan = generationPlan(aspect, preview, kernel)
  val cells = plan.columns * plan.rows
  if (cells <= VIDEO_MAX_FRAME_CELLS) return plan
  val (columns, rows) = WfcGenerator.gridForAspect(aspect.ratio, VIDEO_MAX_FRAME_CELLS)
  return plan.copy(columns = columns, rows = rows)
}

/** Largest cell grid one video frame may have; a panorama multiplies this by up to six. */
private const val VIDEO_MAX_FRAME_CELLS = 10_260

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
internal const val PANORAMA_TIMEOUT_MILLIS = 60_000L


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
): VideoPlan {
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
  val delay = (100f / fps.coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS)).roundToInt().coerceIn(1, 100)
  val effectiveFps = 100f / delay
  return VideoPlan(
    panoramaWidth = panoramaWidth,
    panoramaHeight = frameRows * pixelsPerCell,
    frameWidth = frameColumns * pixelsPerCell,
    frameHeight = frameRows * pixelsPerCell,
    panoramaColumns = panoramaColumns,
    panoramaRows = frameRows,
    pixelsPerCell = pixelsPerCell,
    cellSize = cellSize,
    frameCount = panoramaWidth,
    frameDelayCentiseconds = delay,
    effectiveFps = effectiveFps,
    loopSeconds = panoramaWidth / effectiveFps,
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
  val x = if (plan.frameCount <= 1) 0 else index % plan.frameCount
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
