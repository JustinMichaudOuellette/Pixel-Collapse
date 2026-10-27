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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
 * How long the drawing has to sit still before a solve starts. Editing is a stream of taps, and
 * every solve is a full wave-function collapse, so regenerating on each tap would only ever finish
 * work for drawings that no longer exist.
 */
private const val PREVIEW_DEBOUNCE_MS = 150L

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

  // Rebuild the bitmap whenever the drawing changes. `collectLatest` is what makes a newer edit
  // *cancel* the solve that is still running for an older one: the generator hands the coroutine's
  // cancellation down to the search (see WfcGenerator), so a superseded solve stops within a few
  // tens of milliseconds instead of running to its deadline and briefly showing a stale bitmap.
  // The debounce below is inside the cancelled body, so a continuous stroke starts no solve at all
  // until the user pauses.
  LaunchedEffect(gridState) {
    snapshotFlow { gridState.revision }
      .collectLatest { revision ->
        delay(PREVIEW_DEBOUNCE_MS)
        if (gridState.revision != revision) {
          return@collectLatest
        }
        // Snapshot the drawing on the main thread: the solver runs on a background dispatcher and
        // must not read Compose snapshot state.
        val columns = gridState.columns
        val rows = gridState.rows
        val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
        val generated = try {
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = columns,
            sourceHeight = rows,
            seed = PREVIEW_SEED,
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
        if (generated != null) {
          if (generated.usedFallback) {
            // Still a bitmap, but the drawing's own tiling rather than a shuffled one. Nothing on
            // screen can say so, so leave a trace for whoever wonders why the preview stopped
            // varying.
            Log.i(TAG, "Search timed out; preview shows the drawing's periodic tiling")
          }
          Log.i(
            TAG,
            "Generated ${generated.bitmap.width}x${generated.bitmap.height} bitmap from " +
              "${generated.columns}x${generated.rows} cells, ${generated.tileCount} patterns, " +
              "${generated.millis} ms",
          )
          output = generated
        }
      }
  }

  PixelEditorScreen(
    modifier = modifier,
    gridState = gridState,
    preview = { WfcPreview(output = output) },
  )
}
