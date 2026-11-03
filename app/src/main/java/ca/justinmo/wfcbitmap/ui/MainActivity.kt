package ca.justinmo.wfcbitmap.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.justinmo.wfcbitmap.DebugTestPattern
import ca.justinmo.wfcbitmap.DrawingStore
import ca.justinmo.wfcbitmap.edgeRuleNotice
import ca.justinmo.wfcbitmap.EditorMessage
import ca.justinmo.wfcbitmap.EditorSnapshot
import ca.justinmo.wfcbitmap.EditorStateHolder
import ca.justinmo.wfcbitmap.ImageSaver
import ca.justinmo.wfcbitmap.LatencyMeter
import ca.justinmo.wfcbitmap.OutputAspect
import ca.justinmo.wfcbitmap.OutputScale
import ca.justinmo.wfcbitmap.GifEncoder
import ca.justinmo.wfcbitmap.GifSaver
import ca.justinmo.wfcbitmap.KernelSize
import ca.justinmo.wfcbitmap.Mp4Encoder
import ca.justinmo.wfcbitmap.Mp4Saver
import ca.justinmo.wfcbitmap.OutputSettings
import ca.justinmo.wfcbitmap.VideoFormat
import ca.justinmo.wfcbitmap.cropFrame
import ca.justinmo.wfcbitmap.generationPlan
import ca.justinmo.wfcbitmap.stitchScreen
import ca.justinmo.wfcbitmap.LOOP_SEAM_CELLS
import ca.justinmo.wfcbitmap.PANORAMA_TIMEOUT_MILLIS
import ca.justinmo.wfcbitmap.startupPreview
import ca.justinmo.wfcbitmap.videoFramePlan
import ca.justinmo.wfcbitmap.videoPlan
import ca.justinmo.wfcbitmap.Palette
import ca.justinmo.wfcbitmap.PatternStore
import ca.justinmo.wfcbitmap.PixelArtScaler
import ca.justinmo.wfcbitmap.PixelCollapseApplication
import ca.justinmo.wfcbitmap.PixelGridState
import ca.justinmo.wfcbitmap.PreviewScale
import ca.justinmo.wfcbitmap.SavedPattern
import ca.justinmo.wfcbitmap.savedPixelSize
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import ca.justinmo.wfcbitmap.WfcGenerator
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /*
     * The bars are drawn over black, always: `SystemBarStyle.dark` is the light-icon style, and naming it rather
     * than letting `enableEdgeToEdge` pick by the system-wide appearance keeps the icons visible in light mode
     * too.
     */
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
      navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
    )
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
   * The palettes were already being parsed before this composition existed.
   *
   * The Application starts on the one palette the editor opens with -- the last session's, or
   * `endesga-32.txt` -- and parses the rest immediately afterwards. Waiting only for that first palette
   * is what lets the editor come up as soon as it has colours, while the picker fills in behind it.
   */
  val application = context.applicationContext as PixelCollapseApplication
  val paletteRepository = application.palettes
  val preferences = application.preferences
  val initialPalette = paletteRepository.initialPalette
  if (initialPalette == null) {
    Box(Modifier.fillMaxSize().background(Color.Black))
    return
  }
  val palettes = paletteRepository.palettes

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

  /*
   * Whether this build is debuggable, read from the installed application rather than `BuildConfig.DEBUG`
   * because the module does not enable `buildConfig`. A debug build opens on AGENTS.md's canonical test
   * pattern instead of the restored session, so whatever is being looked at in the editor is reproducible
   * from the moment the app starts; a release build is unaffected.
   */
  val debugBuild = remember {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) {
      Log.i(
        TAG,
        "Debug build: opening on the canonical ${DebugTestPattern.columns}x${DebugTestPattern.rows} test pattern",
      )
    }
    debuggable
  }

  val gridState = remember(initialPalette) {
    PixelGridState(palette = initialPalette.colors).apply {
      if (debugBuild) {
        restoreDrawing(DebugTestPattern.columns, DebugTestPattern.rows, DebugTestPattern.pixels)
      } else {
        restored?.let { restoreDrawing(it.columns, it.rows, it.pixels) }
      }
    }
  }

  val patternStore = remember(context) { PatternStore.forDirectory(context.filesDir) }
  var savePatternDialogOpen by remember { mutableStateOf(false) }
  var loadPatternDialogOpen by remember { mutableStateOf(false) }
  var videoExportOpen by remember { mutableStateOf(false) }
  // The loop's two knobs: how many frame widths it covers, and how many frames a second it plays at.
  // One pixel per frame means the frame count follows from the loop's width, so the frame rate is what
  // sets how long the clip lasts.
  var videoScreens by remember { mutableIntStateOf(3) }
  var videoFps by remember { mutableIntStateOf(25) }
  // The container the export writes. A setting of the export rather than a second button: the dialog picks
  // one, and the one Export button writes it.
  var videoFormat by remember { mutableStateOf(VideoFormat.Gif) }
  var exportingVideo by remember { mutableStateOf(false) }
  var videoExportProgress by remember { mutableStateOf<Float?>(null) }
  var videoExportLabel by remember { mutableStateOf("") }
  var savedPatterns by remember { mutableStateOf<List<SavedPattern>>(emptyList()) }
  /*
   * A loaded pattern may name a palette that is no longer in the assets. The pattern file carries the
   * palette's colours too, so it can be put back into the picker as a one-off entry rather than losing
   * the colours the drawing was made with.
   */
  var patternPalette by remember { mutableStateOf<Palette?>(null) }
  val availablePalettes = remember(palettes, patternPalette) {
    val restored = patternPalette
    if (restored != null && palettes.none { it.id == restored.id }) palettes + restored else palettes
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
   * What the picture is: its shape, the size it is generated at, and the size it is written at.
   *
   * Two of those regenerate, because they change the picture itself: the shape, which decides the solver
   * grid, and the preview size, which decides how many pixels each cell of that grid is drawn as. The save
   * size does not regenerate anything -- scaling happens on the way out of the app, to a file -- which is
   * what keeps the picture on screen and the picture in the file independent of each other.
   */
  var outputSettings by remember {
    val restoredOutput = OutputSettings(
      aspect = restored?.aspectName?.let { name ->
        OutputAspect.entries.firstOrNull { it.name == name }
      } ?: OutputAspect.Portrait9x16,
      preview = restored?.previewName?.let { name ->
        PreviewScale.entries.firstOrNull { it.name == name }
      } ?: PreviewScale.Half,
      scale = restored?.scaleName?.let { name ->
        OutputScale.entries.firstOrNull { it.name == name }
      } ?: OutputScale.One,
      scaler = restored?.scalerName?.let { name ->
        PixelArtScaler.entries.firstOrNull { it.name == name }
      } ?: PixelArtScaler.Nearest,
      kernel = restored?.kernelName?.let { name ->
        KernelSize.entries.firstOrNull { it.name == name }
      } ?: KernelSize.Three,
    )
    // A debug build opens at the finest preview (see [startupPreview]); the rest of the session is the
    // user's, and the override happens before the save size is clamped so the two stay consistent.
    val stored = preferences.outputSettings(restoredOutput)
    mutableStateOf(
      stored.copy(preview = startupPreview(stored.preview, debugBuild)).withSupportedScale(),
    )
  }

  /*
   * Whether the drawing's edges are read as wrapping into each other.
   *
   * On by default: the picture is a tiling, and a tiling has no border of its own. Off is the choice for
   * a drawing whose drawn border has to stay the picture's border. It is a choice about what the picture
   * may be, so changing it regenerates the way a change of shape does.
   */
  var wrapEdges by remember {
    mutableStateOf(preferences.wrapEdges(fallback = restored?.wrapEdges ?: true))
  }

  /*
   * Whether every 3x3 pattern is also offered mirrored left to right.
   *
   * The tile set of a drawing is every window of it, so the picture can only ever be arranged from the
   * shapes as they were drawn. Mirroring each window adds the flipped versions as well: twice the patterns
   * to fit together, and therefore more arrangements of the same drawing -- at the cost of shapes facing a
   * way the drawing does not face. Off by default for that reason.
   */
  var mirrorHorizontal by remember {
    mutableStateOf(
      preferences.mirrorHorizontal(fallback = restored?.mirrorHorizontal ?: false)
    )
  }

  /*
   * Whether every 3x3 pattern is also offered flipped top to bottom.
   *
   * The same bargain as the other mirror, around the other axis, and independent of it: with both on the
   * tile set also carries the half turn the two make, which is four arrangements of every window instead of
   * one. Either is a choice about what the picture may be made of, so both regenerate like the shape does.
   */
  var mirrorVertical by remember {
    mutableStateOf(preferences.mirrorVertical(fallback = restored?.mirrorVertical ?: false))
  }

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
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val generation = remember { GenerationSlot() }
  // The WFC generator keeps a tally and a result cache, so only one generation may run at a time.
  // The mutex lives on the application so an export that outlives the editor still serialises with it.
  val generationMutex = application.generationMutex

  // The Application already picked the saved session's palette (or endesga-32.txt) as the one to parse
  // first, so the editor can open straight into it; the rest of the list arrives for the picker.
  var activePalette by remember { mutableStateOf(initialPalette) }

  /*
   * Whether choosing a palette also rewrites the drawing's colours to the nearest entries in it.
   *
   * On by default, because picking a palette is meant to change what the picture is made of; a drawing
   * left with colours the palette does not contain looks unchanged, which is not a palette swap. The
   * rewrite is a single undo step, so it is easy to take back.
   */
  var autoMigrateColors by remember {
    mutableStateOf(preferences.autoMigrateColors(fallback = true))
  }

  // The settings are small and independent, so each one is written when it changes. The first run also
  // seeds preferences that have never been written with the value restored from the session.
  LaunchedEffect(wrapEdges) { preferences.setWrapEdges(wrapEdges) }
  LaunchedEffect(mirrorHorizontal) { preferences.setMirrorHorizontal(mirrorHorizontal) }
  LaunchedEffect(mirrorVertical) { preferences.setMirrorVertical(mirrorVertical) }
  LaunchedEffect(outputSettings) { preferences.setOutputSettings(outputSettings) }
  LaunchedEffect(autoMigrateColors) { preferences.setAutoMigrateColors(autoMigrateColors) }

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
      colorArgb = (drawingColor ?: activePalette.colors.first()).toArgb(),
      aspectName = outputSettings.aspect.name,
      previewName = outputSettings.preview.name,
      scaleName = outputSettings.scale.name,
      scalerName = outputSettings.scaler.name,
      kernelName = outputSettings.kernel.name,
      wrapEdges = wrapEdges,
      mirrorHorizontal = mirrorHorizontal,
      mirrorVertical = mirrorVertical,
      paletteId = activePalette.id,
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
    // its proportions, and the preview size is the resolution it fills that grid at. Both keep the cost
    // bounded: the coarsest preview solves the fewest cells, and 4x is capped at [WfcGenerator.DEFAULT_CELL_COUNT].
    val plan = generationPlan(outputSettings.aspect, outputSettings.preview, outputSettings.kernel)
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
          generationMutex.withLock {
            WfcGenerator.generate(
              pixels = pixels,
              sourceWidth = columns,
              sourceHeight = rows,
              seed = requestedSeed,
              columns = plan.columns,
              rows = plan.rows,
              wrapEdges = wrapEdges,
              mirrorHorizontal = mirrorHorizontal,
              mirrorVertical = mirrorVertical,
              pixelsPerCell = plan.pixelsPerCell,
              cellSize = plan.cellSize,
            )
          }
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
            "Generated ${generated.bitmap.width}x${generated.bitmap.height} bitmap from " +
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

  // A different preview size is the same picture drawn with more or fewer pixels, so it is rebuilt too.
  LaunchedEffect(outputSettings.preview) { requestGeneration() }
  LaunchedEffect(outputSettings.kernel) { requestGeneration() }

  // Wrapping the drawing's edges or not changes what the picture may be made of, so it is rebuilt too.
  LaunchedEffect(wrapEdges) { requestGeneration() }

  // Either mirror changes the tile set, and so the picture, so the picture is rebuilt for both.
  LaunchedEffect(mirrorHorizontal) { requestGeneration() }
  LaunchedEffect(mirrorVertical) { requestGeneration() }

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
   * What to say when the size asked for could not be allocated, or null when that is not what happened.
   *
   * The top of the size list is a 42-megapixel picture -- 168 MB of ARGB before it is even encoded -- and a
   * device that cannot find that much memory fails inside the scaler with an OutOfMemoryError. Saying so is
   * more use than "could not save": the size is the reason, and a smaller option fixes it.
   */
  fun tooBigMessage(cause: Throwable): String? =
    if (cause is OutOfMemoryError) "Not enough memory for that size" else null

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

  /*
   * The save's outcome, as a line over the drawing rather than a toast.
   *
   * A toast is gone in two seconds and can be neither acted on nor read again. A save is worth more than
   * that: the picture has just been written somewhere the person cannot see, so the line carries a "View"
   * button that opens the very row that was written. It is cleared as soon as the snackbar has it, so the
   * same outcome showing twice is two saves, not one message refused the second time.
   */
  var message by remember { mutableStateOf<EditorMessage?>(null) }

  /** Opens a picture that was just saved, in whatever the device shows pictures with. */
  fun viewPicture(picture: Uri) {
    val opened = runCatching { context.startActivity(ImageSaver.viewIntent(picture)) }
    if (opened.isFailure) {
      Log.w(TAG, "No app would show $picture", opened.exceptionOrNull())
      Toast.makeText(context, "No app to show the picture", Toast.LENGTH_SHORT).show()
    }
  }

  /** Opens the exported GIF. */
  fun viewVideo(video: Uri) {
    val opened = runCatching { context.startActivity(GifSaver.viewIntent(video)) }
    if (opened.isFailure) {
      Log.w(TAG, "No app would show $video", opened.exceptionOrNull())
      Toast.makeText(context, "No app to show the GIF", Toast.LENGTH_SHORT).show()
    }
  }

  /** Opens the exported MP4. */
  fun viewMp4(video: Uri) {
    val opened = runCatching { context.startActivity(Mp4Saver.viewIntent(video)) }
    if (opened.isFailure) {
      Log.w(TAG, "No app would show $video", opened.exceptionOrNull())
      Toast.makeText(context, "No app to show the video", Toast.LENGTH_SHORT).show()
    }
  }

  /**
   * Makes the panning GIF: generate a wide panorama, cut frame-sized windows out of it left to right,
   * and encode them into the gallery.
   *
   * The panorama is a second generation, much wider than the preview, so it waits on [generationMutex]
   * and the preview job is cancelled first. The preview is rebuilt in the `finally` whatever happens.
   */
  fun exportVideo(format: VideoFormat) {
    if (exportingVideo) return
    videoExportOpen = false
    val frame = videoFramePlan(outputSettings.aspect, outputSettings.preview, outputSettings.kernel)
    val video = videoPlan(
      frameColumns = frame.columns,
      frameRows = frame.rows,
      pixelsPerCell = frame.pixelsPerCell,
      cellSize = frame.cellSize,
      screens = videoScreens,
      fps = videoFps,
    )
    val columns = gridState.columns
    val rows = gridState.rows
    val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
    val requestedSeed = seed
    val noun = format.label
    exportingVideo = true
    videoExportLabel = "Exporting $noun…"
    videoExportProgress = 0f
    message = null
    generation.job?.cancel()
    // The application scope, not the composition: backgrounding or turning the screen off must not
    // cancel a long export. The progress state is observed by the editor whenever it is visible.
    application.exportScope.launch {
      try {
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
         * The values to repeat are taken from the MIDDLE of a first solve, and that detail is the whole
         * trick. The picture's first two columns sit on the grid's left border, where a pattern is allowed
         * to have nothing on its left; that pair is therefore a configuration no interior strip can carry,
         * and pinning it at the far end asks for something that does not exist -- the search comes back
         * empty in a hundred milliseconds. A pair from the middle is an ordinary interior configuration and
         * pins anywhere.
         */
        val solveColumns = panoramaColumns + LOOP_SEAM_CELLS
        val solveOne: suspend (IntArray?) -> WfcGenerator.Output? = { pinned ->
          try {
            generationMutex.withLock {
              WfcGenerator.generate(
                pixels = pixels,
                sourceWidth = columns,
                sourceHeight = rows,
                seed = requestedSeed,
                columns = solveColumns,
                rows = panoramaRows,
                wrapEdges = wrapEdges,
                mirrorHorizontal = mirrorHorizontal,
                mirrorVertical = mirrorVertical,
                pixelsPerCell = video.pixelsPerCell,
                cellSize = video.cellSize,
                timeoutMillis = PANORAMA_TIMEOUT_MILLIS,
                fixedCenters = pinned,
                forceRelaxedEdges = true,
              )
            }
          } catch (e: WfcGenerator.GenerationException) {
            null
          }
        }

        videoExportLabel = "Solving the $noun panorama…"
        val open = solveOne(null)
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
        if (solved != null) {
          // Keep the picture's own columns: the extra ones were there to close the loop, not to be shown.
          val stride = solveColumns * video.pixelsPerCell
          val raw = IntArray(stride * frameHeight)
          solved.bitmap.getPixels(raw, 0, stride, 0, 0, stride, frameHeight)
          for (y in 0 until frameHeight) {
            raw.copyInto(panorama, y * video.panoramaWidth, y * stride, y * stride + video.panoramaWidth)
          }
          if (looped == null) {
            // Last resort: repeat the first cells at the end. The wrap is continuous to the pixel, but the
            // pattern just before it was not solved against them, so the join can still show.
            Log.w(TAG, "Video loop could not be closed; repeating the first cells at the end")
            val seam = minOf(video.panoramaWidth, LOOP_SEAM_CELLS * video.pixelsPerCell)
            for (y in 0 until frameHeight) {
              for (x in 0 until seam) {
                panorama[y * video.panoramaWidth + (video.panoramaWidth - seam + x)] =
                  panorama[y * video.panoramaWidth + x]
              }
            }
          } else {
            Log.i(TAG, "Video loop closed: the last $panoramaColumns cells run into the first")
          }
          videoExportProgress = 0.1f
          videoExportLabel = "Exporting $noun…"
        }
        // Only reached when the panorama could not be solved at all; the plan keeps every loop small
        // enough that this is a safety net rather than a path the app takes.
        val panoramaSolved = solved != null
        val screenColumns = frameWidth / video.pixelsPerCell
        var previousCenters: IntArray? = null
        var previousPatterns: IntArray? = null
        for (screen in 0 until (if (panoramaSolved) 0 else video.screenCount)) {
          // Copy the previous screen's edge onto this screen's edge: its last [pinnedCells] cells become
          // this screen's first [pinnedCells]. Both the colour of each cell and the pattern behind it are
          // copied -- the colour alone is not enough to continue the drawing's diagonals across the join.
          // `skipLast` leaves the previous screen's last column unpinned: it was that screen's right
          // border, so its pattern was chosen with nothing to its right, and pinning it exactly would pin
          // an outward pixel no solve ever constrained.
          val pinEdge = { source: IntArray?, pinnedCells: Int, skipLast: Boolean ->
            if (pinnedCells <= 0 || source == null) {
              null
            } else {
              IntArray(screenColumns * video.panoramaRows) { Int.MIN_VALUE }.also { pinned ->
                for (row in 0 until video.panoramaRows) {
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
          // A pin can make an otherwise fine screen unsolvable, and a stronger pin fails sooner, so it is
          // given up in stages: exact patterns at the full width, then colours at the full width, then half
          // the width, then nothing. Patterns come first because a matching colour is not enough to continue
          // the drawing's diagonals; the colour-only stage matters because it still pastes the whole band
          // over, and a screen that keeps its band has no break at the join at all.
          val attempts = listOf(
            video.overlapColumns to true,
            video.overlapColumns to false,
            (video.overlapColumns / 2) to false,
            0 to false,
          ).distinct()
          var generated: WfcGenerator.Output? = null
          var lastFailure: WfcGenerator.GenerationException? = null
          var pinnedColumns = 0
          var pinnedPatterns = false
          for ((pinWidth, wantPatterns) in attempts) {
            try {
              generated = generationMutex.withLock {
                WfcGenerator.generate(
                  pixels = pixels,
                  sourceWidth = columns,
                  sourceHeight = rows,
                  // A new seed per screen gives each solve a different arrangement; the pinned
                  // overlap below is what makes it continue the previous screen.
                  seed = requestedSeed + screen * 1013,
                  columns = screenColumns,
                  rows = video.panoramaRows,
                  wrapEdges = wrapEdges,
                  mirrorHorizontal = mirrorHorizontal,
                  mirrorVertical = mirrorVertical,
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
              pinnedPatterns = wantPatterns
              break
            } catch (e: WfcGenerator.GenerationException) {
              lastFailure = e
            }
          }
          val solved = generated ?: throw lastFailure!!
          previousCenters = solved.cellCenters
          previousPatterns = solved.cellPatterns
          Log.i(
            TAG,
            "Video screen $screen/${video.screenCount}: pinned $pinnedColumns of " +
              "${video.overlapColumns} edge cells " +
              (if (pinnedPatterns) "with their patterns" else "by colour") +
              " (${solved.millis} ms, ${solved.tileCount} patterns)",
          )
          val bitmap = solved.bitmap
          if (bitmap.width != frameWidth || bitmap.height != frameHeight) {
            throw IllegalStateException(
              "Screen was ${bitmap.width}x${bitmap.height}, expected ${frameWidth}x$frameHeight",
            )
          }
          val screenPixels = IntArray(frameWidth * frameHeight)
          bitmap.getPixels(screenPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
          // Copy exactly as much of the previous screen's edge as this screen was pinned to: pasting
          // more would overwrite cells this screen chose freely, which is the break at the seam.
          stitchScreen(panorama, video, screenPixels, screen, seamColumns = pinnedColumns)
          // Stitching is a small part of the wall clock; encoding the frames is the long tail. Keep the
          // bar's first tenth for the screens so it does not race to 60% in the first second.
          videoExportProgress = 0.02f + 0.08f * (screen + 1) / video.screenCount
        }
        if (!panoramaSolved) {
          // The stitched fallback is a left-to-right pan, not a loop: close it the cheap way so the wrap
          // is at least pixel-continuous. The plan keeps every loop small enough for one solve, so this
          // is a safety net rather than a path the app takes.
          val seam = minOf(video.panoramaWidth, LOOP_SEAM_CELLS * video.pixelsPerCell)
          for (y in 0 until frameHeight) {
            for (x in 0 until seam) {
              panorama[y * video.panoramaWidth + (video.panoramaWidth - seam + x)] =
                panorama[y * video.panoramaWidth + x]
            }
          }
        }
        val reportFrame: (Int) -> Unit = { index ->
          if (index % 4 == 0 || index == video.frameCount - 1) {
            val progress = 0.1f + 0.88f * index / video.frameCount
            mainHandler.post { videoExportProgress = progress }
          }
        }
        val bytes = if (format == VideoFormat.Mp4) {
          withContext(Dispatchers.Default) {
            Mp4Encoder.encode(
              context = context,
              width = video.frameWidth,
              height = video.frameHeight,
              frameCount = video.frameCount,
              // The GIF delay is whole centiseconds, so the rate that delay makes is the real one; both
              // formats use it so the MP4 and the GIF play at the same speed.
              fps = video.effectiveFps.roundToInt().coerceIn(1, 60),
            ) { index ->
              reportFrame(index)
              cropFrame(panorama, video.panoramaWidth, video, index)
            }
          }
        } else {
          val palette = GifEncoder.paletteFor(panorama)
          withContext(Dispatchers.Default) {
            GifEncoder.encode(
              width = video.frameWidth,
              height = video.frameHeight,
              palette = palette,
              frameCount = video.frameCount,
              delayForFrame = { video.frameDelayCentiseconds },
            ) { index ->
              reportFrame(index)
              cropFrame(panorama, video.panoramaWidth, video, index)
            }
          }
        }
        val uri = if (format == VideoFormat.Mp4) {
          Mp4Saver.save(context, bytes)
        } else {
          GifSaver.save(context, bytes)
        }
        videoExportProgress = 1f
        message = EditorMessage("$noun saved", "View") {
          if (format == VideoFormat.Mp4) viewMp4(uri) else viewVideo(uri)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Could not make the $noun", e)
        message = EditorMessage(
          if (e is WfcGenerator.GenerationException) {
            e.message ?: "Could not make the $noun"
          } else {
            "Could not make the $noun"
          },
        )
      } finally {
        exportingVideo = false
        videoExportProgress = null
        requestGeneration()
      }
    }
  }

  /**
   * The size the file is written at: the grid at the save size, which is the picture's own size and not a
   * multiple of whatever the preview happens to be showing.
   */
  fun savedSize(): Pair<Int, Int> =
    savedPixelSize(outputSettings.aspect, outputSettings.scale)

  fun writePicture(picture: Bitmap) {
    scope.launch {
      val (width, height) = savedSize()
      Log.i(
        TAG,
        "Saving ${picture.width}x${picture.height} as ${width}x${height} " +
          "(${outputSettings.aspect.label}, preview ${outputSettings.preview.label}, " +
          "size ${outputSettings.scale.label})",
      )
      val outcome = runCatching {
        ImageSaver.saveToGallery(
          context,
          picture,
          width = width,
          height = height,
          scaler = outputSettings.scaler,
          scaleFactor = outputSettings.scale.factor.toInt(),
        )
      }
      outcome.fold(
        onSuccess = { saved ->
          message = EditorMessage(
            text = "Saved to the gallery",
            actionLabel = "View",
            onAction = { viewPicture(saved) },
          )
        },
        onFailure = { error ->
          Log.w(TAG, "Could not save the picture", error)
          // No button: the reason is the whole of the news, and there is nothing to open.
          message = EditorMessage(tooBigMessage(error) ?: "Could not save the picture")
        },
      )
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
      val (width, height) = savedSize()
      val outcome = runCatching {
        val uri = ImageSaver.prepareShare(
          context,
          picture,
          width = width,
          height = height,
          scaler = outputSettings.scaler,
          scaleFactor = outputSettings.scale.factor.toInt(),
        )
        context.startActivity(Intent.createChooser(ImageSaver.shareIntent(uri), "Share the picture"))
      }
      if (outcome.isFailure) {
        val cause = outcome.exceptionOrNull()
        Log.w(TAG, "Could not share the picture", cause)
        val reason = cause?.let { tooBigMessage(it) } ?: "Could not share the picture"
        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
      }
    }
  }

  PixelEditorScreen(
    modifier = modifier,
    gridState = gridState,
    notice = notice,
    // The save's own line, with its way to go and look at what was written.
    message = message,
    onMessageShown = { message = null },
    videoProgress = videoExportProgress,
    videoLabel = videoExportLabel,
    output = outputSettings,
    onOutputChange = { outputSettings = it },
    // The wrap setting belongs to whoever builds the picture, so the screen reports the tap and this side
    // owns the answer: setting it is what rebuilds the picture (see the effect above).
    wrapEdges = wrapEdges,
    onWrapEdgesChange = { wrapEdges = it },
    // The mirrors are the same kind of setting: the caller builds the picture, so it owns the answer.
    mirrorHorizontal = mirrorHorizontal,
    onMirrorHorizontalChange = { mirrorHorizontal = it },
    mirrorVertical = mirrorVertical,
    onMirrorVerticalChange = { mirrorVertical = it },
    // The drawing marks its edge cells while the picture on screen really does keep the drawing's border.
    edgeRuleKept = output?.relaxedEdges != true,
    palettes = availablePalettes,
    activePalette = activePalette,
    // The drawing is rewritten here, where both the grid and the generator are: a palette swap that
    // changes any cell is a new picture, not just a new strip of swatches.
    autoMigrateColors = autoMigrateColors,
    onAutoMigrateColorsChange = { autoMigrateColors = it },
    onPaletteChange = { palette ->
      if (autoMigrateColors && gridState.migrateColors(palette.colors) > 0) {
        requestGeneration()
      }
      activePalette = palette
      if (palette.id != patternPalette?.id) patternPalette = null
    },
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
    onSavePattern = { savePatternDialogOpen = true },
    onLoadPattern = {
      scope.launch {
        savedPatterns = withContext(Dispatchers.IO) {
          patternStore.list().mapNotNull { patternStore.load(it.id) }
        }
        loadPatternDialogOpen = true
      }
    },
    onExportVideo = { videoExportOpen = true },
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
        // The preview uses the same smoothing the saved file will, so it is visible while drawing.
        scaler = outputSettings.scaler,
        // Next variation of the same drawing: advance the seed and regenerate from here.
        onTap = {
          latency.tap()
          seed++
          requestGeneration()
        },
      )
    },
  )

  val videoFrame = videoFramePlan(outputSettings.aspect, outputSettings.preview, outputSettings.kernel)
  VideoExportScreen(
    open = videoExportOpen,
    format = videoFormat,
    screens = videoScreens,
    fps = videoFps,
    frameColumns = videoFrame.columns,
    frameRows = videoFrame.rows,
    pixelsPerCell = videoFrame.pixelsPerCell,
    cellSize = videoFrame.cellSize,
    exporting = exportingVideo,
    onScreensChange = { videoScreens = it },
    onFpsChange = { videoFps = it },
    onFormatChange = { videoFormat = it },
    onDismiss = { videoExportOpen = false },
    onExport = { exportVideo(videoFormat) },
  )

  if (savePatternDialogOpen) {
    SavePatternDialog(
      onDismiss = { savePatternDialogOpen = false },
      onSave = { name ->
        val snapshot = snapshotProvider()
        savePatternDialogOpen = false
        scope.launch {
          val saved = withContext(Dispatchers.IO) {
            patternStore.save(name, activePalette, snapshot)
          }
          message = if (saved != null) {
            EditorMessage("Pattern saved")
          } else {
            EditorMessage("Could not save that pattern")
          }
        }
      },
    )
  }

  if (loadPatternDialogOpen) {
    LoadPatternDialog(
      patterns = savedPatterns,
      onDismiss = { loadPatternDialogOpen = false },
      onLoad = { metadata ->
        loadPatternDialogOpen = false
        scope.launch {
          val saved = withContext(Dispatchers.IO) { patternStore.load(metadata.id) }
          if (saved == null) {
            message = EditorMessage("Could not load that pattern")
            return@launch
          }

          val snapshot = saved.snapshot
          gridState.restoreDrawing(snapshot.columns, snapshot.rows, snapshot.pixels)
          seed = snapshot.seed
          outputSettings = OutputSettings(
            aspect = OutputAspect.entries.firstOrNull { it.name == snapshot.aspectName }
              ?: outputSettings.aspect,
            preview = PreviewScale.entries.firstOrNull { it.name == snapshot.previewName }
              ?: outputSettings.preview,
            scale = OutputScale.entries.firstOrNull { it.name == snapshot.scaleName }
              ?: outputSettings.scale,
            scaler = PixelArtScaler.entries.firstOrNull { it.name == snapshot.scalerName }
              ?: outputSettings.scaler,
            kernel = KernelSize.entries.firstOrNull { it.name == snapshot.kernelName }
              ?: outputSettings.kernel,
          ).withSupportedScale()
          wrapEdges = snapshot.wrapEdges
          mirrorHorizontal = snapshot.mirrorHorizontal
          mirrorVertical = snapshot.mirrorVertical
          drawingColor = Color(snapshot.colorArgb)

          // The palette is part of the pattern's metadata. Use the bundled file when it still exists;
          // otherwise put the saved colours back as a one-off picker entry.
          val bundled = availablePalettes.firstOrNull { it.id == metadata.paletteId }
          if (bundled != null) {
            patternPalette = null
            activePalette = bundled
          } else {
            val restored = Palette(
              id = metadata.paletteId,
              name = metadata.paletteName,
              colors = metadata.paletteColors,
            )
            if (restored.colors.isNotEmpty()) {
              patternPalette = restored
              activePalette = restored
            }
          }

          requestGeneration()
          message = EditorMessage("Pattern loaded")
        }
      },
      onDelete = { metadata ->
        scope.launch {
          val deleted = withContext(Dispatchers.IO) { patternStore.delete(metadata.id) }
          savedPatterns = withContext(Dispatchers.IO) {
            patternStore.list().mapNotNull { patternStore.load(it.id) }
          }
          message = if (deleted) {
            EditorMessage("Pattern deleted")
          } else {
            EditorMessage("Could not delete that pattern")
          }
        }
      },
    )
  }
}
