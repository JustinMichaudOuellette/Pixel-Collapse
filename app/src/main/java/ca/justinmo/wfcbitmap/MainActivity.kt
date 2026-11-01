package ca.justinmo.wfcbitmap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

  /** The bridge the editor installs its state provider on; see [EditorStateHolder]. */
  private val editorState = EditorStateHolder()

  /**
   * The session is written when the app leaves the foreground.
   *
   * `onStop` and not `onPause`: pausing happens for a dialog or a permission prompt, where the session is
   * still the one about to come back, while stopping is the point at which the process may be killed
   * without another word from us. The write is one small file on the main thread, and it is a drawing
   * being kept -- a few hundred kilobytes at worst, in exchange for not losing the work.
   */
  override fun onStop() {
    super.onStop()
    val snapshot = editorState.snapshot?.invoke() ?: return
    DrawingStore.forDirectory(filesDir).save(snapshot)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    /*
     * Keep the screen on while the editor is in front.
     *
     * Drawing is done in bursts with pauses to look at the preview, and a screen that sleeps in the middle
     * of a drawing is worse than the battery it costs to hold it awake -- more so here than in most apps,
     * because the drawing is never persisted: a lock screen in the middle of a session is a real risk of
     * losing the picture. The flag is per window, so it stops applying when the app leaves the foreground;
     * no wakelock and no permission.
     */
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
            modifier = Modifier.padding(innerPadding),
            editorState = editorState,
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
 * What the preview says when a generation failed for a reason no message was written for.
 *
 * It is deliberately less specific than the messages [WfcGenerator] throws: those say what about the
 * drawing to change, and this one is for the case where the app cannot know. It names the only thing the
 * person can do about it, which is to retry or edit the drawing.
 */
private const val GENERATION_FAILED =
  "Could not make a picture from this drawing. Try again, or change the drawing a little."

/**
 * The activity a composition is running in, however many context wrappers it is behind.
 *
 * `LocalContext` is the activity here, but it is documented as "a context", not "the activity", and a
 * wrapper is enough to make a cast fail: walking the wrappers is what makes closing the app work whatever
 * the host does with the context.
 */
private tailrec fun Context.hostingActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.hostingActivity()
  else -> null
}

/**
 * Holds the in-flight generation so the next edit can cancel it.
 *
 * Deliberately a plain class rather than snapshot state: replacing the job must not recompose anything,
 * and the whole point of triggering generation from the gesture is to stay off the snapshot path. The
 * request counter is the exception -- it is read on the main thread only, and it exists so that a
 * cancelled job cannot report its replacement as finished (see [PixelEditor]).
 */
private class GenerationSlot {
  var job: Job? = null

  /** The newest request, counting from 1. Only the job holding this number may clear the busy flag. */
  var newestRequest: Int = 0
}

/**
 * The editor draws the *source image*; the column on its left shows, live, the bitmap that source
 * generates (see [WfcGenerator]). There is no button: the preview is derived state of the drawing
 * and is rebuilt whenever the drawing changes.
 */
@Composable
fun PixelEditor(modifier: Modifier = Modifier, editorState: EditorStateHolder? = null) {
  val context = LocalContext.current

  /*
   * The session from the last time the app was open, if there was one.
   *
   * Read once, before any state is created, so every piece of the editor can start from it: the drawing,
   * the seed that decides which arrangement of it is on screen, and the settings that decide how the
   * picture is made. A snapshot that cannot be read comes back as null and the app starts on an empty
   * drawing, which is the only safe answer to a corrupt file.
   */
  val restored = remember {
    DrawingStore.forDirectory(context.filesDir).load().also { snapshot ->
      if (snapshot != null) {
        Log.i(TAG, "Restored the last session: ${snapshot.columns}x${snapshot.rows} drawing")
      }
    }
  }

  val gridState = remember {
    BitmapEditor.PixelGridState().apply {
      restored?.let { restoreDrawing(it.columns, it.rows, it.pixels) }
    }
  }
  // End-to-end measurement: tap detected -> the frame that shows the new preview is committed.
  val view = LocalView.current
  val latency = remember { LatencyMeter() }
  /*
   * Which variation is on screen. Edits keep the seed, so an edit changes what it touched and leaves the
   * rest of the picture alone; tapping the preview advances it, which is how you ask for another
   * arrangement of the same drawing.
   */
  var seed by remember { mutableIntStateOf(restored?.seed ?: PREVIEW_SEED) }
  var output by remember { mutableStateOf<WfcGenerator.Output?>(null) }
  /*
   * Whether a solve is in flight, which is what the bar across the top of the preview reports.
   *
   * It is a flag beside the job rather than a property of it because nothing can say how far along a
   * solve is -- [WfcGenerator] reports its timings only once it is done -- so the most the preview can
   * be told is "one is running". The picture already on screen stays up while it is true: a preview
   * that blanked on every tap would be empty for most of the time it is used.
   */
  var generating by remember { mutableStateOf(false) }
  /*
   * Why there is no picture, when there is none: the message the preview shows in place of one. It is
   * cleared by every generation that succeeds, so it can never outlive the failure that set it.
   */
  var error by remember { mutableStateOf<String?>(null) }
  /*
   * What the picture cannot say about itself: that it is a tiling of the drawing's patterns but not of its
   * border, and what to change. Null when there is nothing to report, which is the normal case.
   */
  var notice by remember { mutableStateOf<String?>(null) }

  /*
   * What the picture is: its shape and the size it is written at. Changing the shape regenerates, because
   * the solver grid follows it; changing the size does not, because scaling happens on the way out.
   */
  var outputSettings by remember {
    mutableStateOf(
      BitmapEditor.OutputSettings(
        aspect = restored?.aspectName?.let { name ->
          BitmapEditor.OutputAspect.entries.firstOrNull { it.name == name }
        } ?: BitmapEditor.OutputAspect.Portrait9x16,
        scale = restored?.scaleFactor?.let { factor ->
          BitmapEditor.OutputScale.entries.firstOrNull { it.factor == factor }
        } ?: BitmapEditor.OutputScale.One,
      ),
    )
  }

  /*
   * Whether the drawing's edges are read as wrapping into each other.
   *
   * Off by default, because real edges are the point of the app: what you draw on the border of the
   * drawing appears on the border of the picture. On is the answer for a drawing whose border cannot
   * repeat around the grid -- its patterns are then sampled across its edges, so there is no border to
   * keep and the pattern simply runs around the picture. It is a choice about what the picture may be,
   * so changing it regenerates the way a change of shape does.
   */
  var wrapEdges by remember { mutableStateOf(restored?.wrapEdges ?: false) }

  /*
   * Whether every 3x3 pattern is also offered mirrored.
   *
   * The tile set of a drawing is every window of it, so the picture can only ever be arranged from the
   * shapes as they were drawn. Mirroring each window adds the flipped versions as well: twice the patterns
   * to fit together, and therefore more arrangements of the same drawing -- at the cost of shapes facing a
   * way the drawing does not face. Off by default for that reason.
   */
  var mirrorSymmetry by remember { mutableStateOf(restored?.mirrorSymmetry ?: false) }

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

  /*
   * The colour the paint tools are using. It lives in the screen, because the toolbar that picks it does;
   * it is mirrored here so the session snapshot can save it.
   */
  var drawingColor by remember {
    mutableStateOf(restored?.let { Color(it.colorArgb) })
  }

  /*
   * What the activity saves when the app goes to the background.
   *
   * Read on demand rather than kept up to date on every edit: the state it describes is all snapshot state,
   * so a reader that is handed the grid and the seed gets whatever is true at the moment it is called,
   * which is exactly what "save the session" means. Nothing here allocates until it is asked.
   */
  val snapshotProvider: () -> EditorSnapshot = {
    EditorSnapshot(
      columns = gridState.columns,
      rows = gridState.rows,
      pixels = IntArray(gridState.columns * gridState.rows) { gridState.pixels[it].toArgb() },
      seed = seed,
      colorArgb = (drawingColor ?: BitmapEditor.defaultDrawingColor).toArgb(),
      aspectName = outputSettings.aspect.name,
      scaleFactor = outputSettings.scale.factor,
      wrapEdges = wrapEdges,
      mirrorSymmetry = mirrorSymmetry,
    )
  }

  // Installed after every composition, so the holder always reads the state of the newest one.
  SideEffect { editorState?.snapshot = snapshotProvider }

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
    /*
     * The newest request owns the busy flag.
     *
     * A cancelled job finishes *after* its replacement has been started -- cancellation is delivered, not
     * instantaneous -- so a job that cleared the flag on its way out would report the new solve as done
     * the instant it began, and the bar would never be seen for anything but the last edit of a burst.
     * Each job therefore clears the flag only if it is still the newest request when it ends.
     */
    val request = generation.newestRequest + 1
    generation.newestRequest = request
    generating = true
    generation.job = scope.launch {
      try {
        latency.bodyStart()
        val generationStart = System.nanoTime()
        // Why there is no picture, when there is none. Set by the catches below, which is what lets a
        // failure come back as a message the preview can show where the picture would have been.
        var generationError: String? = null
        val generated = try {
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = columns,
            sourceHeight = rows,
            seed = requestedSeed,
            columns = outputColumns,
            rows = outputRows,
            wrapEdges = wrapEdges,
            mirrorSymmetry = mirrorSymmetry,
          )
        } catch (e: CancellationException) {
          // A newer edit replaced this one; let the cancellation through instead of reporting it.
          throw e
        } catch (e: WfcGenerator.GenerationException) {
          // A drawing the solver cannot tile, or one too complex to try. The message is written for the
          // person drawing it, so it goes to the preview as it is.
          Log.i(TAG, "No picture for this drawing: ${e.message}")
          generationError = e.message ?: GENERATION_FAILED
          null
        } catch (e: Exception) {
          // Not something the drawing can be blamed for. It is still reported in the preview rather than
          // hidden behind the last picture, which would go on claiming to be a picture of this drawing.
          Log.w(TAG, "Could not generate a bitmap for the current drawing", e)
          generationError = GENERATION_FAILED
          null
        }
        val generationEnd = System.nanoTime()
        if (generated != null) {
          if (wrapEdges) {
            // The drawing's edges were read as wrapping into each other, so the picture's patterns run
            // around its border instead of the drawing's border landing on it.
            Log.i(TAG, "Edges wrapped: the drawing's borders are not borders in this picture")
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
          error = null
          notice = edgeRuleNotice(generated.relaxedEdges)
          latency.registerCommit(view, generationStart, generationEnd, generated)
        } else {
          /*
           * No picture this time: say why where the picture would have been, and forget the one that was
           * there. Leaving the previous picture up is what an error must not do here -- it would be a
           * picture of a drawing that no longer exists, and for a drawing that has never produced one it
           * would be nothing at all.
           */
          error = generationError
          output = null
          notice = null
        }
      } finally {
        // Also reached when a newer request cancelled this one, which is exactly when it must not clear
        // the flag: that newer request set it again before this job could run its last line.
        if (request == generation.newestRequest) generating = false
      }
    }
  }

  // First picture for the drawing as it starts out.
  LaunchedEffect(Unit) { requestGeneration() }

  // A different output shape is a different grid to solve, so the picture has to be rebuilt for it.
  LaunchedEffect(outputSettings.aspect) { requestGeneration() }

  // Wrapping the drawing's edges or not changes what the picture may be made of, so it is rebuilt too.
  LaunchedEffect(wrapEdges) { requestGeneration() }

  // Turning symmetry on or off changes the tile set, and so the picture, so it is rebuilt as well.
  LaunchedEffect(mirrorSymmetry) { requestGeneration() }

  /*
   * Saving and sharing write the picture that is on screen, which is the generated bitmap.
   *
   * The bitmap is copied here, on the main thread, before the write: the generator reuses its render
   * targets, so the live bitmap can be repainted by the next generation while the encoder is reading
   * it. A 304x540 copy is cheap enough to belong on the main thread.
   */
  var pictureToSave by remember { mutableStateOf<Bitmap?>(null) }

  /** The picture at the output size that was chosen, copied off the generator's reusable bitmap. */
  fun currentPicture(): Bitmap? = output?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)

  /**
   * What to say when there is nothing to save or share.
   *
   * With a failed generation the reason is on screen in the preview, and repeating it is better than
   * claiming the app is still drawing: it is not, and it will not finish on its own.
   */
  fun nothingToUse(): String = error ?: "Still drawing - nothing to save yet"

  /**
   * Closes the app.
   *
   * Finishing the activity is the whole of it: there is nothing to flush, because the drawing lives only in
   * memory and the picture is written out only when the Save tool is used, so nothing here can be lost that
   * the person had not already chosen to keep. The process follows the last activity out.
   */
  fun exitApp() {
    context.hostingActivity()?.finish()
  }

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
      Toast.makeText(context, nothingToUse(), Toast.LENGTH_SHORT).show()
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
      Toast.makeText(context, nothingToUse(), Toast.LENGTH_SHORT).show()
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
    // The wrap setting belongs to whoever builds the picture, so the screen reports the tap and this side
    // owns the answer: setting it is what rebuilds the picture (see the effect above).
    wrapEdges = wrapEdges,
    onWrapEdgesChange = { wrapEdges = it },
    // Symmetry is the same kind of setting: the caller builds the picture, so it owns the answer.
    mirrorSymmetry = mirrorSymmetry,
    onMirrorSymmetryChange = { mirrorSymmetry = it },
    // The drawing marks its edge cells while the picture on screen really does keep the drawing's border.
    edgeRuleKept = output?.relaxedEdges != true,
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
    onExit = { exitApp() },
    // Only a restored session has a colour to start with; otherwise the palette's first swatch is it.
    initialColor = restored?.let { Color(it.colorArgb) },
    onColorChange = { drawingColor = it },
    preview = {
      WfcPreview(
        output = output,
        // A drawing with no picture says why, where the picture would have been.
        error = error,
        // The last picture stays up while the next one is solved; the bar across its top is what says
        // the solve is running, so the preview never has to blank to say it.
        isGenerating = generating,
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
