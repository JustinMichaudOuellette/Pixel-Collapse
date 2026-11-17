package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.generation.TileEvolver
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.renderCellCenters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Every frame an Evolve clip is made of.
 *
 * The forward pass is kept as one cell-centre value per cell -- the compact form the exporter already
 * works in. The undo pass is reconstructed from the recorded changes on demand instead of storing a
 * picture per frame, so a clip's worth of undo frames costs a change log and a copy per frame rather than
 * one bitmap each.
 *
 * [fillFrame] is the exporter's own render step, so the preview and the file paint identical pixels from
 * identical frames.
 */
internal class EvolveFrames(
  val width: Int,
  val height: Int,
  private val columns: Int,
  private val rows: Int,
  private val scale: Int,
  private val cellSize: Int,
  private val blocks: List<IntArray>?,
  private val forward: Array<IntArray>,
  private val finalCentres: IntArray,
  private val changes: LongArray,
  private val undoPlan: IntArray,
) {

  /** Forward frames, then one undo frame per entry in the plan. */
  val frameCount: Int get() = forward.size + undoPlan.size

  fun fillFrame(index: Int, out: IntArray) {
    require(out.size == width * height) {
      "Frame buffer is ${out.size}, expected ${width * height}"
    }
    val centres = when {
      index < forward.size -> forward[index.coerceIn(0, forward.lastIndex)]
      undoPlan.isEmpty() -> forward[forward.lastIndex]
      else -> undoPicture(undoPlan[(index - forward.size).coerceIn(0, undoPlan.lastIndex)])
    }
    renderCellCenters(
      pixels = out,
      width = width,
      columns = columns,
      rows = rows,
      scale = scale,
      centres = centres,
      blocks = blocks,
      cell = cellSize,
    )
  }

  /** The picture with the newest [reverted] recorded changes taken back, newest first. */
  private fun undoPicture(reverted: Int): IntArray {
    val picture = finalCentres.copyOf()
    val count = reverted.coerceIn(0, changes.size)
    for (index in 0 until count) {
      val change = changes[changes.size - 1 - index]
      picture[(change ushr 32).toInt()] = change.toInt()
    }
    return picture
  }
}

/**
 * Builds the frame source an Evolve clip is made of: [steps] forward evolutions, then the undo pass that
 * takes the recorded cell changes back newest-first.
 *
 * Runs on the default dispatcher because each forward frame is a small solve. Returns null when the
 * picture cannot be evolved; [CancellationException] is rethrown so a replaced build stops.
 */
internal suspend fun buildEvolveFrames(
  base: WfcGenerator.Output,
  steps: Int,
  cells: Int,
  seed: Int,
  onForwardFrame: ((Int) -> Unit)? = null,
): EvolveFrames? = withContext(Dispatchers.Default) {
  val baseCentres = base.cellCenters ?: return@withContext null
  val width = base.bitmap.width
  val height = base.bitmap.height
  val scale = (width / base.columns).coerceAtLeast(1)
  try {
    val forward = ArrayList<IntArray>(steps.coerceAtLeast(1))
    forward.add(baseCentres)
    val evolver = TileEvolver(base, cells)
    val (undoPlan, finalCentres, changes) = try {
      for (frame in 1 until steps) {
        // A newer setting cancels this build; stop between frames rather than finishing the whole clip.
        ensureActive()
        forward.add(evolver.nextFrame(seed * 31 + frame * 101))
        onForwardFrame?.invoke(frame)
      }
      Triple(
        evolveUndoPlan(evolver.changeCount),
        evolver.pictureAfterUndoing(0),
        evolver.changesSnapshot(),
      )
    } finally {
      evolver.close()
    }
    EvolveFrames(
      width = width,
      height = height,
      columns = base.columns,
      rows = base.rows,
      scale = scale,
      cellSize = base.cellSize,
      blocks = base.blocks,
      forward = forward.toTypedArray(),
      finalCentres = finalCentres,
      changes = changes,
      undoPlan = undoPlan.toIntArray(),
    )
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }
}

/**
 * Plays [EvolveFrames] for the export screen, in the same order the exporter writes them.
 *
 * An Evolve clip loops by construction -- its undo pass ends one change short of the opening picture --
 * so the sequence simply wraps. Two reusable buffers keep a full frame from being allocated per tick.
 */
class EvolvePreview internal constructor(
  internal val frames: EvolveFrames,
) {

  val width: Int get() = frames.width
  val height: Int get() = frames.height

  /** Forward frames plus the undo pass; the same count the file contains. */
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
      // The exporter lights an evolve frame from its index in the clip; use the same phase here.
      phase = if (total <= 1) 0f else index.toFloat() / total,
    )
  }
}
