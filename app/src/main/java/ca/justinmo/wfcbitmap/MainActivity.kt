package ca.justinmo.wfcbitmap

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.justinmo.wfcbitmap.BitmapEditor.PixelEditorScreen
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    windowInsetsController.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    setContent {
      WFCBitmapTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
          PixelEditor(
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }
}

/**
 * How long the drawing has to sit still before a solve starts.
 *
 * Zero, because the solve is cancellable: a newer edit aborts the running one within a few tens of
 * milliseconds (see [WfcGenerator]), so waiting first only adds latency to every single tap. Measured
 * end to end with [LatencyMeter] on a Pixel 7a, tap to the frame that shows the new preview:
 *
 * | debounce | tap -> frame  | of which "wait" |
 * |----------|--------------|-----------------|
 * | 150 ms   | 203-253 ms   | 156-166 ms      |
 * | 0 ms     | 35-80 ms     | 3-14 ms         |
 *
 * The remainder is the collapse itself (11-44 ms) plus the frame pipeline (9-23 ms).
 */
private const val PREVIEW_DEBOUNCE_MS = 0L

/**
 * One fixed seed: the preview should follow the drawing, not the dice. A seed that was reseeded on
 * every edit would reshuffle the whole picture each time, so an edit would not visibly change what
 * it edited.
 */
private const val PREVIEW_SEED = 1

/** Log tag for a generation that failed outright (see the catch in [PixelEditor]). */
private const val TAG = "WfcPreview"

/**
 * The editor draws the *source image*; the column on its left shows, live, the bitmap that source
 * generates (see [WfcGenerator]). There is no button: the preview is derived state of the drawing
 * and is rebuilt whenever the drawing changes.
 */
@Composable
fun PixelEditor(modifier: Modifier = Modifier) {
  val gridState = remember { BitmapEditor.PixelGridState() }
  var output by remember { mutableStateOf<WfcGenerator.Output?>(null) }
  // End-to-end measurement: tap detected -> the frame that shows the new preview is committed.
  val view = LocalView.current
  val latency = remember { LatencyMeter() }
  /*
   * Which variation is on screen. Edits keep the seed, so an edit changes what it touched and leaves the
   * rest of the picture alone; tapping the preview advances it, which is how you ask for another
   * arrangement of the same drawing.
   */
  var seed by remember { mutableIntStateOf(PREVIEW_SEED) }

  // Rebuild the bitmap whenever the drawing changes. `collectLatest` is what makes a newer edit
  // *cancel* the solve that is still running for an older one: the generator hands the coroutine's
  // cancellation down to the search (see WfcGenerator), so a superseded solve stops within a few
  // tens of milliseconds instead of running to its deadline and briefly showing a stale bitmap.
  // The debounce below is inside the cancelled body, so a continuous stroke starts no solve at all
  // until the user pauses.
  LaunchedEffect(gridState) {
    snapshotFlow { gridState.revision to seed }
      .collectLatest { request ->
        val revision = request.first
        delay(PREVIEW_DEBOUNCE_MS)
        if (gridState.revision != revision) {
          return@collectLatest
        }
        // Snapshot the drawing on the main thread: the solver runs on a background dispatcher and
        // must not read Compose snapshot state.
        val columns = gridState.columns
        val rows = gridState.rows
        val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
        val generationStart = System.nanoTime()
        val generated = try {
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = columns,
            sourceHeight = rows,
            seed = request.second,
          )
        } catch (e: CancellationException) {
          // The composable went away; let the cancellation through instead of reporting it.
          throw e
        } catch (e: Exception) {
          // A drawing this app can make always yields a bitmap (wrap-around extraction guarantees a
          // tiling), so this is a bug rather than something the user can act on: log it and leave the
          // last picture up instead of covering the preview with an error.
          Log.w(TAG, "Could not generate a bitmap for the current drawing", e)
          null
        }
        val generationEnd = System.nanoTime()
        if (generated != null) {
          if (generated.usedFallback) {
            // The drawing's edge could not close around the grid, so there is no tiling that keeps it
            // and the preview shows the drawing stretched instead. Nothing on screen can say that, so
            // leave a trace for whoever wonders why the preview stopped rearranging patterns.
            Log.i(TAG, "No tiling under the edge rule; preview shows the drawing stretched")
          }
          Log.i(
            TAG,
            "Generated ${generated.columns * WfcGenerator.PIXELS_PER_CELL}x" +
              "${generated.rows * WfcGenerator.PIXELS_PER_CELL} bitmap from " +
              "${generated.columns}x${generated.rows} cells, ${generated.tileCount} patterns, " +
              "${generated.millis} ms (extract ${generated.extractMillis} ms, " +
              "solve ${generated.solveMillis} ms, render ${generated.renderMillis} ms)",
          )
          output = generated
          // The state write above schedules the recomposition; this reports when the frame carrying it
          // actually reaches the compositor.
          latency.registerCommit(view, generationStart, generationEnd, generated)
        }
      }
  }

  PixelEditorScreen(
    modifier = modifier,
    gridState = gridState,
    // Start the clock the moment the editor detects the tap; the whole pipeline is measured from here.
    onTap = { latency.tap() },
    preview = {
      WfcPreview(
        output = output,
        // Next variation of the same drawing: advancing the seed re-solves from scratch, and because
        // the seed is part of what the preview generation watches, this triggers a regeneration.
        onTap = {
          latency.tap()
          seed++
        },
      )
    },
  )
}
