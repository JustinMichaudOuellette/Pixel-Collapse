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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
 * One fixed seed: the preview should follow the drawing, not the dice. A seed that was reseeded on
 * every edit would reshuffle the whole picture each time, so an edit would not visibly change what
 * it edited.
 */
private const val PREVIEW_SEED = 1

/** Log tag for a generation that failed outright (see the catch in [PixelEditor]). */
private const val TAG = "WfcPreview"

/**
 * Holds the in-flight generation so the next edit can cancel it.
 *
 * Deliberately a plain class rather than snapshot state: replacing the job must not recompose anything,
 * and the whole point of triggering generation from the gesture is to stay off the snapshot path.
 */
private class GenerationSlot {
  var job: Job? = null
}

/**
 * The editor draws the *source image*; the column on its left shows, live, the bitmap that source
 * generates (see [WfcGenerator]). There is no button: the preview is derived state of the drawing
 * and is rebuilt whenever the drawing changes.
 */
@Composable
fun PixelEditor(modifier: Modifier = Modifier) {
  val gridState = remember { BitmapEditor.PixelGridState() }
  // End-to-end measurement: tap detected -> the frame that shows the new preview is committed.
  val view = LocalView.current
  val latency = remember { LatencyMeter() }
  /*
   * Which variation is on screen. Edits keep the seed, so an edit changes what it touched and leaves the
   * rest of the picture alone; tapping the preview advances it, which is how you ask for another
   * arrangement of the same drawing.
   */
  var seed by remember { mutableIntStateOf(PREVIEW_SEED) }
  var output by remember { mutableStateOf<WfcGenerator.Output?>(null) }
  /*
   * What the preview cannot say about itself: that it is not a plain edge-respecting tiling of the
   * drawing, and what to do about it. Null when there is nothing to report, which is the normal case.
   */
  var notice by remember { mutableStateOf<String?>(null) }

  /*
   * Generation is started straight from the gesture, not from observing the drawing.
   *
   * Observing costs a frame: `revision` is snapshot state, Compose delivers apply-notifications on a
   * frame callback, and only then does a `snapshotFlow` collector resume. Measured with the latency
   * meter below, that "poke the generator" detour alone was 2.4-12.9 ms (median 8.8) on a 90 Hz phone --
   * about one refresh period, quantised by when the tap landed within the frame. Calling the generator
   * from the tap handler removes the frame wait entirely; a new tap cancels the previous job, and the
   * generator hands that cancellation down to the search, so superseded work still stops promptly.
   */
  val scope = rememberCoroutineScope()
  val generation = remember { GenerationSlot() }

  fun requestGeneration() {
    // Snapshot the drawing here, on the main thread: the solver runs on a background dispatcher and
    // must not read Compose snapshot state.
    val columns = gridState.columns
    val rows = gridState.rows
    val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
    val requestedSeed = seed
    generation.job?.cancel()
    generation.job = scope.launch {
      latency.bodyStart()
      val generationStart = System.nanoTime()
      val generated = try {
        WfcGenerator.generate(
          pixels = pixels,
          sourceWidth = columns,
          sourceHeight = rows,
          seed = requestedSeed,
        )
      } catch (e: CancellationException) {
        // A newer edit replaced this one; let the cancellation through instead of reporting it.
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
          // The drawing's edge could not close around the grid even with the edge rule dropped, so the
          // preview shows the drawing stretched. The notice line says so.
          Log.i(TAG, "No tiling for this drawing; preview shows the drawing stretched")
        } else if (generated.relaxedEdges) {
          // The edge rule had no tiling (the drawing's border cannot repeat around the bitmap's), so the
          // picture was generated with it dropped: still a tiling of the drawing's patterns, but the
          // bitmap's border is not the drawing's border.
          Log.i(TAG, "Edge rule relaxed: the drawing's border cannot repeat around the grid")
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
        notice = edgeRuleNotice(
          relaxedEdges = generated.relaxedEdges,
          usedFallback = generated.usedFallback,
          columns = generated.columns,
          rows = generated.rows,
        )
        latency.registerCommit(view, generationStart, generationEnd, generated)
      }
    }
  }

  // First picture for the drawing as it starts out.
  LaunchedEffect(Unit) { requestGeneration() }

  PixelEditorScreen(
    modifier = modifier,
    gridState = gridState,
    notice = notice,
    // The clock starts at the gesture; generation is requested by the same tap that edits the grid.
    onTap = {
      latency.tap()
      requestGeneration()
    },
    preview = {
      WfcPreview(
        output = output,
        // Next variation of the same drawing: advance the seed and regenerate from here.
        onTap = {
          latency.tap()
          seed++
          requestGeneration()
        },
      )
    },
  )
}
