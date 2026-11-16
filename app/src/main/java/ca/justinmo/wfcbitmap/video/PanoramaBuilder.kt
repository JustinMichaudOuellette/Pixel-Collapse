package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.generation.WfcGenerator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything needed to build the wide panorama a panning video is cut from.
 *
 * The caller snapshots the drawing and the editor's generation settings on the main thread; the builder
 * itself only talks to [WfcGenerator], so it is safe to run off the composition.
 */
internal data class PanoramaParams(
  val sourcePixels: IntArray,
  val sourceWidth: Int,
  val sourceHeight: Int,
  val seed: Int,
  val plan: VideoPlan,
  val wrapEdges: Boolean,
  val mirrorHorizontal: Boolean,
  val mirrorVertical: Boolean,
  val quarterTurn: Boolean,
  val bestEffort: Boolean,
  val timeoutMillis: Long = PANORAMA_TIMEOUT_MILLIS,
)

/**
 * A built panorama in the orientation the export cuts frames from.
 *
 * [previewPixels] is the same surface turned upright for a vertical loop, so the preview can draw a
 * vertical window without transposing a million pixels inside composition. For a horizontal loop it is
 * simply [pixels].
 */
data class VideoPanorama(
  val pixels: IntArray,
  val width: Int,
  val height: Int,
  val frameWidth: Int,
  val frameHeight: Int,
  val vertical: Boolean,
  val loopClosed: Boolean,
) {
  val previewPixels: IntArray =
    if (vertical) transposePixels(pixels, width, height) else pixels
  val previewWidth: Int = if (vertical) height else width
  val previewHeight: Int = if (vertical) width else height
  val previewFrameWidth: Int = if (vertical) frameHeight else frameWidth
  val previewFrameHeight: Int = if (vertical) frameWidth else frameHeight
}

/**
 * Builds the same looped panorama the exporter uses, at whatever frame resolution the plan names.
 *
 * The one-solve path is tried first: the panorama is solved two cells wider than it is shown, the middle
 * two columns are copied onto both ends, and a second solve then closes the loop. When the loop is too
 * large for one solve, whole screens are solved in turn with their left edge pinned to the previous
 * screen's right edge, exactly as the exporter does.
 *
 * The preview screen calls this so it can show the real screen-to-screen edge pattern instead of repeating
 * one independent solved frame. It is deliberately the same algorithm: a preview that is cheaper than the
 * export is a preview that lies about the seam.
 */
internal suspend fun buildPanorama(
  params: PanoramaParams,
  mutex: Mutex,
  onStage: (String) -> Unit = {},
  onScreen: (Int, Int) -> Unit = { _, _ -> },
): VideoPanorama {
  val video = params.plan
  val frameWidth = video.frameWidth
  val frameHeight = video.frameHeight
  val panorama = IntArray(video.panoramaWidth * frameHeight)
  val panoramaColumns = video.panoramaColumns
  val panoramaRows = video.panoramaRows

  /*
   * The panorama is solved two cells wider than the picture and closed on itself: those two cells are
   * pinned to the same values at both ends, so the column past the last one shown is the first one,
   * and the window that straddles the wrap is a window the solver itself made consistent.
   *
   * The values to repeat are taken from the MIDDLE of a first solve: the picture's first two columns sit
   * on the grid's left border, where a pattern is allowed to have nothing on its left, so that pair is a
   * configuration no interior strip can carry. A pair from the middle is an ordinary interior
   * configuration and pins anywhere.
   */
  val solveColumns = panoramaColumns + LOOP_SEAM_CELLS
  val solveOne: suspend (IntArray?) -> WfcGenerator.Output? = { pinned ->
    try {
      mutex.withLock {
        WfcGenerator.generate(
          pixels = params.sourcePixels,
          sourceWidth = params.sourceWidth,
          sourceHeight = params.sourceHeight,
          seed = params.seed,
          columns = solveColumns,
          rows = panoramaRows,
          wrapEdges = params.wrapEdges,
          mirrorHorizontal = params.mirrorHorizontal,
          mirrorVertical = params.mirrorVertical,
          quarterTurn = params.quarterTurn,
          bestEffort = params.bestEffort,
          pixelsPerCell = video.pixelsPerCell,
          cellSize = video.cellSize,
          timeoutMillis = params.timeoutMillis,
          fixedCenters = pinned,
          forceRelaxedEdges = true,
        )
      }
    } catch (e: WfcGenerator.GenerationException) {
      null
    }
  }

  val canSolvePanorama = panoramaColumns * panoramaRows <= MAX_PANORAMA_CELLS
  onStage(if (canSolvePanorama) "Solving the panorama…" else "Building the panorama…")
  val open = if (canSolvePanorama) solveOne(null) else null
  val looped = open?.let { first ->
    val centres = first.cellCenters ?: return@let null
    val anchor = solveColumns / 2
    val pinned = IntArray(solveColumns * panoramaRows) { Int.MIN_VALUE }
    for (row in 0 until panoramaRows) {
      for (i in 0 until LOOP_SEAM_CELLS) {
        val value = centres[row * solveColumns + anchor + i]
        pinned[row * solveColumns + i] = value
        pinned[row * solveColumns + solveColumns - LOOP_SEAM_CELLS + i] = value
      }
    }
    solveOne(pinned)
  }
  val solved = looped ?: open
  var loopClosed = looped != null
  if (solved != null) {
    // Keep the picture's own columns: the extra ones were there to close the loop, not to be shown.
    val stride = solveColumns * video.pixelsPerCell
    val raw = IntArray(stride * frameHeight)
    solved.bitmap.getPixels(raw, 0, stride, 0, 0, stride, frameHeight)
    for (y in 0 until frameHeight) {
      raw.copyInto(
        panorama,
        y * video.panoramaWidth,
        y * stride,
        y * stride + video.panoramaWidth,
      )
    }
    if (looped == null) {
      // Last resort: repeat the first cells at the end. The wrap is continuous to the pixel, but the
      // pattern just before it was not solved against them, so the join can still show.
      val seam = minOf(video.panoramaWidth, LOOP_SEAM_CELLS * video.pixelsPerCell)
      for (y in 0 until frameHeight) {
        for (x in 0 until seam) {
          panorama[y * video.panoramaWidth + (video.panoramaWidth - seam + x)] =
            panorama[y * video.panoramaWidth + x]
        }
      }
    }
    onStage("Panorama solved")
  }

  // Only reached when the panorama could not be solved as one grid; solve screens and paste them.
  val panoramaSolved = solved != null
  val screenColumns = frameWidth / video.pixelsPerCell
  var previousCenters: IntArray? = null
  var previousPatterns: IntArray? = null
  for (screen in 0 until (if (panoramaSolved) 0 else video.screenCount)) {
    val pinEdge = { source: IntArray?, pinnedCells: Int, skipLast: Boolean ->
      if (pinnedCells <= 0 || source == null) {
        null
      } else {
        IntArray(screenColumns * panoramaRows) { Int.MIN_VALUE }.also { pinned ->
          for (row in 0 until panoramaRows) {
            val base = row * screenColumns
            for (cell in 0 until pinnedCells) {
              if (skipLast && cell == pinnedCells - 1) continue
              pinned[base + cell] =
                source[base + (screenColumns - pinnedCells + cell)]
            }
          }
        }
      }
    }
    val attempts = if (previousCenters == null) {
      listOf(0 to false)
    } else {
      listOf(
        video.overlapColumns to true,
        video.overlapColumns to false,
        0 to false,
      )
    }.distinct()
    var generated: WfcGenerator.Output? = null
    var lastFailure: WfcGenerator.GenerationException? = null
    var pinnedColumns = 0
    for ((pinWidth, wantPatterns) in attempts) {
      try {
        generated = mutex.withLock {
          WfcGenerator.generate(
            pixels = params.sourcePixels,
            sourceWidth = params.sourceWidth,
            sourceHeight = params.sourceHeight,
            // A new seed per screen gives each solve a different arrangement; the pinned overlap is
            // what makes it continue the previous screen.
            seed = params.seed + screen * 1013,
            columns = screenColumns,
            rows = panoramaRows,
            wrapEdges = params.wrapEdges,
            mirrorHorizontal = params.mirrorHorizontal,
            mirrorVertical = params.mirrorVertical,
            quarterTurn = params.quarterTurn,
            bestEffort = params.bestEffort,
            pixelsPerCell = video.pixelsPerCell,
            cellSize = video.cellSize,
            timeoutMillis = 15_000L,
            fixedCenters = pinEdge(previousCenters, pinWidth, false),
            fixedPatterns = if (wantPatterns) {
              pinEdge(previousPatterns, pinWidth, true)
            } else {
              null
            },
            forceRelaxedEdges = true,
          )
        }
        pinnedColumns = pinWidth
        break
      } catch (e: WfcGenerator.GenerationException) {
        lastFailure = e
      }
    }
    val screenSolved = generated ?: throw (lastFailure
      ?: WfcGenerator.GenerationException("The drawing could not be tiled into a panorama"))
    if (screen > 0 && pinnedColumns == 0) pinnedColumns = video.overlapColumns
    previousCenters = screenSolved.cellCenters
    previousPatterns = screenSolved.cellPatterns
    onScreen(screen, video.screenCount)
    val bitmap = screenSolved.bitmap
    if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
      throw IllegalStateException(
        "Screen was ${bitmap.width}x${bitmap.height}, expected ${frameWidth}x$frameHeight",
      )
    }
    val screenPixels = IntArray(frameWidth * frameHeight)
    bitmap.getPixels(screenPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
    stitchScreen(panorama, video, screenPixels, screen, seamColumns = pinnedColumns)
    loopClosed = false
  }
  if (!panoramaSolved) {
    // The stitched fallback is a pan, not a loop: close it cheaply so the wrap is pixel-continuous.
    val seam = minOf(video.panoramaWidth, LOOP_SEAM_CELLS * video.pixelsPerCell)
    for (y in 0 until frameHeight) {
      for (x in 0 until seam) {
        panorama[y * video.panoramaWidth + (video.panoramaWidth - seam + x)] =
          panorama[y * video.panoramaWidth + x]
      }
    }
  }

  return VideoPanorama(
    pixels = panorama,
    width = video.panoramaWidth,
    height = frameHeight,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
    vertical = video.scrollDirection.isVertical,
    loopClosed = loopClosed,
  )
}
