package ca.justinmo.wfcbitmap.video
import ca.justinmo.wfcbitmap.generation.SolveTrace

/**
 * Turns a real [SolveTrace] into video frames.
 *
 * The trace is a sequence of domain transitions, not a sequence of finished bitmaps. This class
 * pre-samples the trace at [steps] points, stores one packed size/colour value per cell for each sample,
 * and paints a frame on demand. The trace itself is not retained after the samples are built, so its
 * event array can be collected while the animation plays.
 *
 * A cell is drawn from the finished picture only when its domain size is 1. Both variants therefore
 * show the solver's real decision order; they differ only in what an undecided cell shows:
 *
 *  - [SolveAnimation.Collapse] leaves it as the editor's blank paper colour.
 *  - [SolveAnimation.Wave] fills it with the real average of the colours still possible there, captured
 *    from the solver's own domain at that moment. The generated part always uses the editor's pixels.
 */
internal class SolveAnimationFrames(
  trace: SolveTrace,
  private val solvedPixels: IntArray,
  val width: Int,
  val height: Int,
  background: Int,
  private val style: SolveAnimation,
  requestedSteps: Int,
) {

  private val columns = trace.columns
  private val rows = trace.rows
  private val cellWidth = (width / columns).coerceAtLeast(1)
  private val cellHeight = (height / rows).coerceAtLeast(1)
  private val backgroundColor = background

  /**
   * How many samples the animation can afford.
   *
   * A sample is one int per cell, and the whole point of this class is to hold a sample per step. At a
   * fine preview (540x960 at one pixel per cell) a single sample is about 2 MB, so the requested 90 steps
   * would need roughly 190 MB before any frame is drawn -- an OutOfMemoryError on a normal phone. The cap
   * trades some smoothness for a clip that can actually be built; the exporter uses this same [steps]
   * value, so the preview and the file still agree.
   */
  val steps: Int = solveAnimationStepCount(columns * rows, requestedSteps)

  /**
   * Each sample is one int per cell: low 8 bits are the domain size, the next 24 are the average RGB of
   * the remaining candidates. Sample 0 is the state after pins and before propagation; the last sample
   * includes every trace event.
   */
  private val snapshots: Array<IntArray> = buildSnapshots(trace)

  /**
   * Colours the animation needs beyond the finished picture's own palette. For Wave, the most common
   * average candidate colours are reserved so the GIF does not collapse them all onto nearby final
   * colours. Pixel art normally uses far fewer than 256 colours, so this leaves room for the picture.
   */
  val paletteExtras: IntArray = buildList {
    add(backgroundColor)
    if (style == SolveAnimation.Wave) {
      val counts = HashMap<Int, Int>()
      for (sample in snapshots) {
        for (packed in sample) {
          if ((packed and 0xFF) == 1) continue
          val rgb = (packed ushr 8) and 0xFFFFFF
          counts[rgb] = (counts[rgb] ?: 0) + 1
        }
      }
      counts.entries
        .sortedByDescending { it.value }
        .take(MAX_AVERAGE_COLOURS)
        .forEach { add(0xFF000000.toInt() or it.key) }
    }
  }.distinct().take(256).toIntArray()

  /** The frame at [forwardIndex] in the forward run, 0 = untouched, [steps] - 1 = solved. */
  fun frameAt(forwardIndex: Int): IntArray =
    IntArray(width * height).also { fillFrame(forwardIndex, it) }

  /**
   * Paints the frame at [forwardIndex] into [frame], whose size must be [width] x [height].
   *
   * The preview and the exporter both reuse a buffer here rather than allocating a multi-megabyte array
   * for every frame.
   */
  internal fun fillFrame(forwardIndex: Int, frame: IntArray) {
    require(frame.size == width * height) {
      "Frame buffer is ${frame.size}, expected ${width * height}"
    }
    val sample = snapshots[forwardIndex.coerceIn(0, snapshots.lastIndex)]
    java.util.Arrays.fill(frame, backgroundColor)
    for (y in 0 until rows) {
      val cellRow = y * columns
      val pixelY = y * cellHeight
      for (x in 0 until columns) {
        val packed = sample[cellRow + x]
        val size = packed and 0xFF
        val pixelX = x * cellWidth
        if (size == 1) {
          for (dy in 0 until cellHeight) {
            val row = (pixelY + dy) * width + pixelX
            solvedPixels.copyInto(frame, row, row, row + cellWidth)
          }
        } else if (style == SolveAnimation.Wave) {
          val average = 0xFF000000.toInt() or ((packed ushr 8) and 0xFFFFFF)
          for (dy in 0 until cellHeight) {
            val row = (pixelY + dy) * width + pixelX
            java.util.Arrays.fill(frame, row, row + cellWidth, average)
          }
        }
      }
    }
  }

  private fun buildSnapshots(trace: SolveTrace): Array<IntArray> {
    val cellCount = columns * rows
    val state = IntArray(cellCount)
    for (i in 0 until minOf(cellCount, trace.initialSizes.size)) {
      val size = trace.initialSizes[i].coerceIn(0, 255)
      val colour = trace.initialColors.getOrNull(i) ?: backgroundColor
      state[i] = pack(size, colour)
    }
    val samples = Array(steps) { IntArray(cellCount) }
    samples[0] = state.copyOf()
    var event = 0
    for (frame in 1 until steps) {
      // Sample the trace by event count. The last frame gets every event; the first gets none.
      val target = (frame.toLong() * trace.events.size / (steps - 1)).toInt()
      while (event < target && event < trace.events.size) {
        applyEvent(state, trace.events[event])
        event++
      }
      samples[frame] = state.copyOf()
    }
    // The integer division above can leave a remainder; make the final sampled state fully solved.
    while (event < trace.events.size) {
      applyEvent(state, trace.events[event])
      event++
    }
    samples[steps - 1] = state.copyOf()
    return samples
  }

  private fun applyEvent(state: IntArray, event: Long) {
    val cell = (event and TRACE_VAR_MASK).toInt()
    if (cell !in state.indices) return
    val size = ((event ushr TRACE_VAR_BITS) and TRACE_SIZE_MASK).toInt()
    val colour = ((event ushr TRACE_COLOR_SHIFT) and TRACE_COLOR_MASK).toInt()
    state[cell] = pack(size, colour)
  }

  private fun pack(size: Int, colour: Int): Int =
    (((colour and 0xFFFFFF) shl 8) or (size and 0xFF))

  private companion object {
    const val TRACE_VAR_BITS = 20
    const val TRACE_VAR_MASK = (1L shl TRACE_VAR_BITS) - 1
    const val TRACE_SIZE_MASK = (1L shl 10) - 1
    const val TRACE_COLOR_SHIFT = 30
    const val TRACE_COLOR_MASK = 0xFFFFFFL

    /** Leave at least half the 256-entry GIF palette for the finished picture itself. */
    const val MAX_AVERAGE_COLOURS = 128
  }
}

/**
 * The number of samples a solve animation can afford for [cellCount] cells.
 *
 * Each sample is one int per cell. At a fine preview (540x960 at one pixel per cell) one sample is about
 * 2 MB, so the requested 90 steps would need roughly 190 MB before a frame is drawn -- an
 * OutOfMemoryError on a normal phone. The cap trades smoothness for a clip that can actually be built; the
 * exporter and the preview both use this value, so they still agree on the frame count.
 */
internal fun solveAnimationStepCount(cellCount: Int, requestedSteps: Int): Int {
  val max = if (cellCount <= 0) {
    Int.MAX_VALUE
  } else {
    (MAX_SOLVE_SNAPSHOT_BYTES / (cellCount.toLong() * 4L))
      .toInt()
      .coerceAtLeast(2)
  }
  return requestedSteps.coerceAtLeast(2).coerceAtMost(max)
}

/** How much the per-step snapshots may occupy; see [solveAnimationStepCount]. */
private const val MAX_SOLVE_SNAPSHOT_BYTES = 48L * 1024 * 1024
