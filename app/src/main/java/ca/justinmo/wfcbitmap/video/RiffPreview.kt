package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.renderCellCenters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Every frame a Riff clip is made of, kept as one cell-centre value per cell.
 *
 * Riff re-solves the same drawing for every frame with a seed no earlier frame used. A bitmap per frame
 * would be tens of megabytes; the cell centres are what the exporter already works in, so the whole clip
 * is a few megabytes at most and [fillFrame] is the exporter's own render step.
 */
internal class RiffFrames(
  val width: Int,
  val height: Int,
  private val columns: Int,
  private val rows: Int,
  private val scale: Int,
  private val cellSize: Int,
  private val blocks: List<IntArray>?,
  private val frames: Array<IntArray>,
) {

  val frameCount: Int get() = frames.size

  fun fillFrame(index: Int, out: IntArray) {
    require(out.size == width * height) {
      "Frame buffer is ${out.size}, expected ${width * height}"
    }
    renderCellCenters(
      pixels = out,
      width = width,
      columns = columns,
      rows = rows,
      scale = scale,
      centres = frames[index.coerceIn(0, frames.lastIndex)],
      blocks = blocks,
      cell = cellSize,
    )
  }
}

/**
 * Builds the patterns a Riff clip is made of: one full solve per seed, all demonstrations so the
 * editor's picture and its solve-time measurement are untouched.
 *
 * Runs on the default dispatcher and takes the generator's mutex per frame, so it serialises with the
 * editor's own generation instead of competing with it for the native solver.
 */
internal suspend fun buildRiffFrames(
  pixels: IntArray,
  sourceWidth: Int,
  sourceHeight: Int,
  seed: Int,
  patterns: Int,
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  wrapEdges: Boolean,
  mirrorHorizontal: Boolean,
  mirrorVertical: Boolean,
  quarterTurn: Boolean,
  bestEffort: Boolean,
  generationMutex: Mutex,
  onFrame: ((Int) -> Unit)? = null,
): RiffFrames = withContext(Dispatchers.Default) {
  val seeds = riffSeeds(seed, patterns)
  val centres = ArrayList<IntArray>(seeds.size)
  var blocks: List<IntArray>? = null
  for ((index, frameSeed) in seeds.withIndex()) {
    // A replaced build stops between solves; a single native solve itself is not interruptible.
    ensureActive()
    val solved = generationMutex.withLock {
      WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        seed = frameSeed,
        columns = frameColumns,
        rows = frameRows,
        wrapEdges = wrapEdges,
        mirrorHorizontal = mirrorHorizontal,
        mirrorVertical = mirrorVertical,
        quarterTurn = quarterTurn,
        bestEffort = bestEffort,
        pixelsPerCell = pixelsPerCell,
        cellSize = cellSize,
        timeoutMillis = RIFF_FRAME_TIMEOUT_MILLIS,
        // A frame is a demonstration, not the picture the editor shows: it must neither seed that
        // cache nor teach the editor's budget a cost it never actually paid for a preview.
        isSessionPicture = false,
      )
    }
    val cellCentres = solved.cellCenters
      ?: throw WfcGenerator.GenerationException("A riff frame had no solved cells")
    centres.add(cellCentres)
    if (blocks == null) blocks = solved.blocks
    onFrame?.invoke(index + 1)
  }
  RiffFrames(
    width = frameColumns * pixelsPerCell,
    height = frameRows * pixelsPerCell,
    columns = frameColumns,
    rows = frameRows,
    scale = pixelsPerCell,
    cellSize = cellSize,
    blocks = blocks,
    frames = centres.toTypedArray(),
  )
}

/**
 * Plays [RiffFrames] for the export screen, in the same order the exporter writes them.
 *
 * The clip is a run of distinct arrangements, so there is no reverse pass; the sequence simply wraps.
 * Two reusable buffers keep a full frame from being allocated per tick.
 */
class RiffPreview internal constructor(
  internal val frames: RiffFrames,
) {

  val width: Int get() = frames.width
  val height: Int get() = frames.height

  /** Patterns in the clip; the same count the file contains. */
  internal val frameCount: Int get() = frames.frameCount

  private val buffers = arrayOfNulls<IntArray>(2)
  private var nextBuffer = 0

  /** Bumped for every painted frame so a reused buffer still uploads. */
  private var sourceVersion = 0

  internal fun previewSource(sequenceIndex: Int, cellPixels: Int): PreviewSource {
    val buffer = buffers[nextBuffer] ?: IntArray(width * height).also { buffers[nextBuffer] = it }
    nextBuffer = (nextBuffer + 1) % buffers.size
    val total = frames.frameCount
    val index = positiveMod(sequenceIndex, total)
    frames.fillFrame(index, buffer)
    return PreviewSource.fromFrame(
      pixels = buffer,
      width = width,
      height = height,
      cellPixels = cellPixels,
      version = ++sourceVersion,
      // The exporter lights a riff frame from its index in the clip; use the same phase here.
      phase = if (total <= 1) 0f else index.toFloat() / total,
    )
  }
}
