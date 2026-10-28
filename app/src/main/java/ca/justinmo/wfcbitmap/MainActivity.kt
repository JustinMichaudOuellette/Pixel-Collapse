package ca.justinmo.wfcbitmap

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
   * What the picture is: its shape and the size it is written at. Changing the shape regenerates, because
   * the solver grid follows it; changing the size does not, because scaling happens on the way out.
   */
  var outputSettings by remember { mutableStateOf(BitmapEditor.OutputSettings()) }

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
    // The shape of the output is a solving decision: the grid the solver fills is what gives the picture
    // its proportions, and [WfcGenerator.gridForAspect] keeps the cell count (and so the cost) fixed
    // whatever shape is chosen.
    val (outputColumns, outputRows) = WfcGenerator.gridForAspect(outputSettings.aspect.ratio)
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
          columns = outputColumns,
          rows = outputRows,
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

  // A different output shape is a different grid to solve, so the picture has to be rebuilt for it.
  LaunchedEffect(outputSettings.aspect) { requestGeneration() }

  /*
   * Saving and sharing write the picture that is on screen, which is the generated bitmap.
   *
   * The bitmap is copied here, on the main thread, before the write: the generator reuses its render
   * targets, so the live bitmap can be repainted by the next generation while the encoder is reading
   * it. A 304x540 copy is cheap enough to belong on the main thread.
   */
  val context = LocalContext.current
  var pictureToSave by remember { mutableStateOf<Bitmap?>(null) }

  /** The picture at the output size that was chosen, copied off the generator's reusable bitmap. */
  fun currentPicture(): Bitmap? = output?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)

  fun writePicture(picture: Bitmap) {
    scope.launch {
      val outcome = runCatching {
        ImageSaver.saveToGallery(context, picture, scale = outputSettings.scale.factor)
      }
      val message = outcome.fold(
        onSuccess = { "Saved to the gallery" },
        onFailure = { error ->
          Log.w(TAG, "Could not save the picture", error)
          "Could not save the picture"
        },
      )
      Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
  }

  // Only reached on API 26-28; from 29 the write needs no permission and never gets here.
  val writePermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    val picture = pictureToSave
    pictureToSave = null
    if (granted && picture != null) {
      writePicture(picture)
    } else {
      Toast.makeText(context, "Saving needs permission to write pictures", Toast.LENGTH_SHORT).show()
    }
  }

  fun savePicture() {
    val picture = currentPicture()
    if (picture == null) {
      Toast.makeText(context, "Still drawing - nothing to save yet", Toast.LENGTH_SHORT).show()
      return
    }
    val needsPermission = ImageSaver.needsLegacyWritePermission() &&
      ContextCompat.checkSelfPermission(context, ImageSaver.LEGACY_WRITE_PERMISSION) !=
      PackageManager.PERMISSION_GRANTED
    if (needsPermission) {
      pictureToSave = picture
      writePermission.launch(ImageSaver.LEGACY_WRITE_PERMISSION)
    } else {
      writePicture(picture)
    }
  }

  /*
   * Sharing hands the same picture to whatever app will take it. It goes through the app's cache and the
   * FileProvider declared in the manifest, which is why this needs no permission and cannot expose
   * anything else.
   */
  fun sharePicture() {
    val picture = currentPicture()
    if (picture == null) {
      Toast.makeText(context, "Still drawing - nothing to share yet", Toast.LENGTH_SHORT).show()
      return
    }
    scope.launch {
      val outcome = runCatching {
        val uri = ImageSaver.prepareShare(context, picture, scale = outputSettings.scale.factor)
        context.startActivity(Intent.createChooser(ImageSaver.shareIntent(uri), "Share the picture"))
      }
      if (outcome.isFailure) {
        Log.w(TAG, "Could not share the picture", outcome.exceptionOrNull())
        Toast.makeText(context, "Could not share the picture", Toast.LENGTH_SHORT).show()
      }
    }
  }

  PixelEditorScreen(
    modifier = modifier,
    gridState = gridState,
    notice = notice,
    output = outputSettings,
    onOutputChange = { outputSettings = it },
    // The clock starts at the gesture; generation is requested by the same tap that edits the grid.
    onTap = {
      latency.tap()
      requestGeneration()
    },
    onResize = {
      requestGeneration()
    },
    onSave = { savePicture() },
    onShare = { sharePicture() },
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
