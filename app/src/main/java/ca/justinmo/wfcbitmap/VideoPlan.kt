package ca.justinmo.wfcbitmap

import kotlin.math.roundToInt

/**
 * The geometry of a panning video: a wide panorama, a frame-sized window that walks across it left to
 * right, and the frame timing.
 *
 * The panorama is made of whole frames generated one at a time and stitched with an overlap, so the
 * solver never has to swallow the whole wide grid at once. [speed] is measured in frame widths per
 * second and [duration] in seconds.
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
  val frameCount: Int,
  /** GIF delay in centiseconds for frame i; a 1,2,2 pattern averages 60 fps. */
  val delayPattern: List<Int>,
  /** Frame widths actually travelled per second. */
  val effectiveSpeed: Float,
  /** How many frame-sized solves the panorama is stitched from. */
  val screenCount: Int,
  /** How many cells at the seam are taken from the previous screen. */
  val overlapColumns: Int,
)

/** The container the video export writes. */
enum class VideoFormat(val label: String) {
  Gif("GIF"),
  Mp4("MP4"),
}

/** Frames per second of the exported animation. */
const val VIDEO_FPS = 60

/** How many frame-sized solves one video may stitch. */
private const val MAX_SCREENS = 96

/**
 * The frame grid the video uses.
 *
 * A panorama is several frames wide, so it must not inherit the finest preview: a 4x preview at five
 * screens would ask the solver for a grid five times the size it already takes to solve, and the
 * export times out before it finds a tiling. The stream uses the preview when it is modest, and caps
 * the cell count when it is not; the frame is then drawn at the same pixels per cell.
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

/** Largest cell grid one video frame may have; a panorama multiplies this by up to five. */
private const val VIDEO_MAX_FRAME_CELLS = 10_260

/**
 * Largest panorama the export solves as one grid, in cells.
 *
 * Up to this size the whole panorama is collapsed in a single solve, which is what removes the seam
 * between screens entirely: there is no join for the pattern to break at. Above it the export falls back
 * to stitched frames, because the solver's memory and time grow with the cell count and a very long clip
 * would ask for a grid several times the size of the finest preview.
 *
 * 120,000 cells is a bit under three times the finest preview grid (41,040), which the app solves in about
 * 160 ms, so the common export -- a 76x135 frame over a minute or so of pan -- stays inside it.
 */
internal const val MAX_PANORAMA_CELLS = 120_000

/**
 * How long the single panorama solve may run before the export gives up and stitches frames instead.
 *
 * Generous, because there is no smaller answer to fall back on within the same attempt: the whole picture
 * is one solve, and a slow one is still far quicker than the export that follows it.
 */
internal const val PANORAMA_TIMEOUT_MILLIS = 60_000L

/**
 * Extra cells of the previous screen kept at the seam, on top of the plain one-in-six overlap.
 *
 * The seam is a strip of cells that two screens share: the previous screen's right edge is copied onto
 * the next screen's left edge and pinned there, so both screens show the same pixels across it. But a
 * cell's pattern is its 3x3 neighbourhood, so with only the bare overlap the first cell the next screen
 * is free to choose sits one cell past the pinned strip and has just a single column of copied context
 * to its left. Any disagreement about how the pattern continues then lands immediately after the strip,
 * which the eye reads as the picture breaking at the join.
 *
 * One more cell than the join needs costs one cell of pan per screen and puts that transition inside the
 * copied strip instead of one cell past it.
 */
private const val SEAM_MARGIN_CELLS = 1

/**
 * Works out the panorama and frame grid for a panning video.
 *
 * @param frameColumns the requested preview grid width in cells.
 * @param frameRows the requested preview grid height in cells.
 * @param speed frame widths per second.
 */
internal fun videoPlan(
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  durationSeconds: Float,
  speed: Float,
): VideoPlan {
  val duration = durationSeconds.coerceIn(0.5f, 30f)
  val requested = (speed * duration).coerceAtLeast(0.1f)
  val share = (frameColumns / 6).coerceIn(2, 16)
  // The seam is wider than the plain share by [SEAM_MARGIN_CELLS], but it can never take the whole frame:
  // a screen that is all overlap generates nothing new and the pan stops advancing. The floor keeps tiny
  // request sizes working rather than shrinking their seam below what they already had.
  val overlap = minOf(share + SEAM_MARGIN_CELLS, maxOf(share, frameColumns / 2))
  val advance = (frameColumns - overlap).coerceAtLeast(1)
  val screensWanted = 1 + kotlin.math.ceil(requested * frameColumns / advance).toInt()
  val screenCount = screensWanted.coerceIn(2, MAX_SCREENS)
  val panoramaColumns = frameColumns + (screenCount - 1) * advance
  val traveled = (panoramaColumns - frameColumns).toFloat() / frameColumns
  val frameCount = (duration * VIDEO_FPS).roundToInt().coerceAtLeast(2)
  return VideoPlan(
    panoramaWidth = panoramaColumns * pixelsPerCell,
    panoramaHeight = frameRows * pixelsPerCell,
    frameWidth = frameColumns * pixelsPerCell,
    frameHeight = frameRows * pixelsPerCell,
    panoramaColumns = panoramaColumns,
    panoramaRows = frameRows,
    pixelsPerCell = pixelsPerCell,
    cellSize = cellSize,
    frameCount = frameCount,
    delayPattern = listOf(1, 2, 2),
    effectiveSpeed = traveled / duration,
    screenCount = screenCount,
    overlapColumns = overlap,
  )
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
 * Cuts the frame [index] out of a panorama, moving left to right.
 *
 * Frame 0 is the left edge and the last frame is the right edge, so the clip starts and ends on the
 * panorama rather than sliding in from off-screen.
 */
internal fun cropFrame(
  panorama: IntArray,
  panoramaWidth: Int,
  plan: VideoPlan,
  index: Int,
): IntArray {
  val frame = IntArray(plan.frameWidth * plan.frameHeight)
  val travel = (panoramaWidth - plan.frameWidth).coerceAtLeast(0)
  val x = if (plan.frameCount <= 1) 0 else (travel.toLong() * index / (plan.frameCount - 1)).toInt()
  for (y in 0 until plan.frameHeight) {
    val source = y * panoramaWidth + x
    val target = y * plan.frameWidth
    panorama.copyInto(frame, target, source, source + plan.frameWidth)
  }
  return frame
}

/** Every frame of the pan, left to right. */
internal fun panFrames(panorama: IntArray, panoramaWidth: Int, plan: VideoPlan): List<IntArray> =
  List(plan.frameCount) { cropFrame(panorama, panoramaWidth, plan, it) }
