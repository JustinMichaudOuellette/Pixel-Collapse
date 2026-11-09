package ca.justinmo.wfcbitmap.editor

import android.util.Log
import android.view.View
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import java.util.Locale

/**
 * Measures one interaction end to end: from the moment a tap is *detected* to the moment the frame
 * carrying the new preview is committed to the compositor.
 *
 * That is the number a person feels, and it is not the sum of the pieces the solver reports: the
 * debounce, the pixel snapshot, the tile extraction, the collapse, the render, recomposition, the
 * texture upload and the RenderThread all sit between the touch and the pixels changing.
 *
 * [registerCommit] needs API 29 (`ViewTreeObserver.registerFrameCommitCallback`). Below that it falls
 * back to the next animation frame, which under-reports by one frame's draw work; the component
 * breakdown in the log line makes which one was used obvious.
 */
internal class LatencyMeter(private val tag: String = "WfcLatency") {

  /** When the tap that started the current interaction was detected, in [System.nanoTime] nanos. */
  var tapNanos: Long = 0L
    private set

  /** When the generator coroutine actually started working on the tap, in nanos. */
  var bodyStartNanos: Long = 0L
    private set

  /** When the drawing had been copied out of Compose state, in nanos. */
  var snapshotNanos: Long = 0L
    private set

  /** Records that a tap was detected. */
  fun tap() {
    tapNanos = System.nanoTime()
    bodyStartNanos = 0L
    snapshotNanos = 0L
  }

  /** Records that copying the drawing out of Compose state has begun (it is on the tap's own thread). */
  fun snapshotStart() {
    // Only the end is needed: the snapshot is one contiguous piece of work on the tap's thread.
  }

  /** Records that the drawing has been copied out of Compose state; only the dispatch is left. */
  fun snapshotDone() {
    snapshotNanos = System.nanoTime()
  }

  /**
   * Records that the generation coroutine began handling the tap.
   *
   * The gap between [snapshotDone] and this is the dispatch onto whatever thread runs the solver; the gap
   * between [tap] and [snapshotDone] is the app's own work plus whatever Compose did first.
   */
  fun bodyStart() {
    bodyStartNanos = System.nanoTime()
  }

  /**
   * Schedules the end of the measurement: the commit of the frame that draws the new preview.
   *
   * @param generationStart when the generation began, i.e. when the solver was handed the drawing.
   * @param generationEnd when the generator handed back the output.
   * @param output what was generated, for the phase breakdown.
   */
  fun registerCommit(
    view: View,
    generationStart: Long,
    generationEnd: Long,
    output: WfcGenerator.Output,
  ) {
    val tappedAt = tapNanos
    if (tappedAt == 0L) {
      return
    }
    /*
     * Only the commit is measured, not the vsync it waited for. Pairing a Choreographer frame callback
     * with the commit callback was tried and is a race: the two arrive in either order and can belong to
     * different frames, which produced negative "draw" times. "ui+gpu" is therefore everything after the
     * bitmap exists -- and on a 90 Hz phone it comes out in multiples of ~11.1 ms, because most of it is
     * waiting for the next frame rather than drawing.
     *
     * That is what an experiment with this meter showed: replacing the Compose Image with a plain View
     * drawing the same bitmap gave a median of 16.6 ms against 14.8 ms for Compose (n=10 and n=14), i.e.
     * no improvement -- the cost is frame pacing and the texture upload, not the composition.
     */
    val report = { commitNanos: Long ->
      // Read what the breakdown needs *before* clearing the marks.
      val bodyAt = bodyStartNanos
      val snapAt = snapshotNanos
      tapNanos = 0L
      bodyStartNanos = 0L
      snapshotNanos = 0L
      report(tappedAt, snapAt, bodyAt, generationStart, generationEnd, commitNanos, output)
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      view.viewTreeObserver.registerFrameCommitCallback(Runnable { report(System.nanoTime()) })
    } else {
      // No commit callback below Q: the animation frame is the closest this API level offers, so this
      // under-reports by the frame's own draw work.
      view.postOnAnimation { report(System.nanoTime()) }
    }
  }
  private class FrameTiming {
    var frameNanos: Long = 0
    var commitNanos: Long = 0
    var committed: Boolean = false
  }


  /**
   * Logs one interaction:
   *
   * ```
   * WfcLatency: tap->frame 41.9 ms (snapshot 0.0, dispatch 6.2, prep 0.0, extract 6.0,
   *             solve 13.0, render 0.0, ui+gpu 13.6)
   * ```
   *
   * - `snapshot` � copying the drawing out of Compose state on the tap's own thread (sub-0.1 ms)
   * - `dispatch` � handing the work to a solver thread
   * - `prep` � anything between resuming the coroutine and calling the generator
   * - `extract` � sampling the tile set and building the native model
   * - `solve`, `render` � from the generator
   * - `ui+gpu` � everything after the bitmap exists: the state write, recomposition, the upload, the
   *   RenderThread and the compositor. On a 90 Hz phone this comes out in multiples of ~11.1 ms, so it
   *   is mostly frame pacing rather than drawing.
   */
  private fun report(
    tappedAt: Long,
    snapshotNanos: Long,
    bodyStartNanos: Long,
    generationStart: Long,
    generationEnd: Long,
    commitNanos: Long,
    output: WfcGenerator.Output,
  ) {
    val total = millis(commitNanos - tappedAt)
    val snapshot = if (snapshotNanos == 0L) 0.0 else millis(snapshotNanos - tappedAt)
    val dispatch = when {
      bodyStartNanos == 0L -> millis(generationStart - tappedAt)
      snapshotNanos == 0L -> millis(bodyStartNanos - tappedAt)
      else -> millis(bodyStartNanos - snapshotNanos)
    }
    val prep = if (bodyStartNanos == 0L) 0.0 else millis(generationStart - bodyStartNanos)
    val extract = output.extractMillis.toDouble()
    val solve = output.solveMillis.toDouble()
    val render = output.renderMillis.toDouble()
    val after = millis(commitNanos - generationEnd)
    Log.i(
      tag,
      String.format(
        Locale.US,
        "tap->frame %.1f ms (snapshot %.1f, dispatch %.1f, prep %.1f, extract %.1f, solve %.1f, " +
          "render %.1f, ui+gpu %.1f)",
        total,
        snapshot,
        dispatch,
        prep,
        extract,
        solve,
        render,
        after,
      ),
    )
  }

  private fun millis(nanos: Long): Double = nanos / 1_000_000.0

  /** Drops the current measurement, e.g. when the editor is recreated. */
  fun reset() {
    tapNanos = 0L
  }
}