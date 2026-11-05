package ca.justinmo.wfcbitmap.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.justinmo.wfcbitmap.PixelCollapseApplication
import ca.justinmo.wfcbitmap.editor.DebugTestPattern
import ca.justinmo.wfcbitmap.editor.LatencyMeter
import ca.justinmo.wfcbitmap.editor.PixelGridState
import ca.justinmo.wfcbitmap.generation.TileEvolver
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.edgeRuleNotice
import ca.justinmo.wfcbitmap.generation.generationPlan
import ca.justinmo.wfcbitmap.generation.renderCellCenters
import ca.justinmo.wfcbitmap.output.EditorMessage
import ca.justinmo.wfcbitmap.output.ImageSaver
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.OutputSettings
import ca.justinmo.wfcbitmap.output.PixelArtScaler
import ca.justinmo.wfcbitmap.output.PixelScaler
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.savedPixelSize
import ca.justinmo.wfcbitmap.output.startupPreview
import ca.justinmo.wfcbitmap.palette.Palette
import ca.justinmo.wfcbitmap.storage.DrawingStore
import ca.justinmo.wfcbitmap.storage.EditorSnapshot
import ca.justinmo.wfcbitmap.storage.EditorStateHolder
import ca.justinmo.wfcbitmap.storage.PatternGallery
import ca.justinmo.wfcbitmap.storage.SavedPattern
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import ca.justinmo.wfcbitmap.video.GifEncoder
import ca.justinmo.wfcbitmap.video.GifSaver
import ca.justinmo.wfcbitmap.video.LOOP_SEAM_CELLS
import ca.justinmo.wfcbitmap.video.MAX_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MAX_MP4_FPS
import ca.justinmo.wfcbitmap.video.MAX_PANORAMA_CELLS
import ca.justinmo.wfcbitmap.video.MAX_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.MIN_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MIN_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.MetalShade
import ca.justinmo.wfcbitmap.video.Mp4Encoder
import ca.justinmo.wfcbitmap.video.Mp4Saver
import ca.justinmo.wfcbitmap.video.PANORAMA_TIMEOUT_MILLIS
import ca.justinmo.wfcbitmap.video.ScrollDirection
import ca.justinmo.wfcbitmap.video.panoramaMetalFrame
import ca.justinmo.wfcbitmap.video.stillMetalFrame
import ca.justinmo.wfcbitmap.video.SolveAnimation
import ca.justinmo.wfcbitmap.video.SolveAnimationFrames
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoLook
import ca.justinmo.wfcbitmap.video.VideoMode
import ca.justinmo.wfcbitmap.video.cropFrame
import ca.justinmo.wfcbitmap.video.evolveUndoPlan
import ca.justinmo.wfcbitmap.video.scaleFrame
import ca.justinmo.wfcbitmap.video.stitchScreen
import ca.justinmo.wfcbitmap.video.transposePixels
import ca.justinmo.wfcbitmap.video.videoFramePlan
import ca.justinmo.wfcbitmap.video.videoPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

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
    val saved = DrawingStore.forDirectory(filesDir).save(snapshot)
    if (saved) {
      Log.i(TAG, "Saved the session: ${snapshot.columns}x${snapshot.rows} drawing")
    } else {
      // Nothing else reports this: the write is best-effort, so an unwritable file would otherwise look
      // exactly like the drawing never having been saved at all.
      Log.w(TAG, "Could not save the session: ${snapshot.columns}x${snapshot.rows} drawing")
    }
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
    // Portrait keeps the status bar so the clock and battery stay readable; landscape is the drawing
    // surface and uses the whole screen. The navigation bar stays out of the way in both.
    if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
      windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
    } else {
      windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
    }
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
   * because the module does not enable `buildConfig`. A debug build with nothing saved opens on AGENTS.md's
   * canonical test pattern, so a fresh install is reproducible from the moment the app starts; a session
   * that was saved is always restored, in every build, because it is the user's drawing and not a default.
   */
  val debugBuild = remember {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) {
      Log.i(TAG, "Debug build: the canonical test pattern is the default drawing")
    }
    debuggable
  }

  /*
   * The grouping the session opens with, read before the grid is built: a grouped kernel only works on
   * a drawing that is a whole number of its cells wide and tall, so the restored drawing has to know
   * which cells it is being restored under.
   */
  val startupKernel = remember {
    val fallback = OutputSettings(
      kernel = restored?.kernelName?.let { name ->
        KernelSize.entries.firstOrNull { it.name == name }
      } ?: KernelSize.Three,
    )
    preferences.outputSettings(fallback).kernel
  }

  val gridState = remember(initialPalette) {
    PixelGridState(palette = initialPalette.colors).apply {
      if (restored != null && !restored.savedAsPattern) {
        restoreDrawing(
          restored.columns,
          restored.rows,
          restored.pixels,
          cellSize = startupKernel.cellSize,
        )
      } else if (debugBuild && restored == null) {
        restoreDrawing(
          DebugTestPattern.columns,
          DebugTestPattern.rows,
          DebugTestPattern.pixelsFor(initialPalette.colors),
          cellSize = startupKernel.cellSize,
        )
      }
      // The empty start is sized by the default, which a grouped kernel may not divide; doing it here
      // covers that case and is a no-op for the two above, which were restored already fitted.
      fitCells(startupKernel.cellSize)
    }
  }

  val patternGallery = remember(context) { PatternGallery(context) }
  var loadPatternDialogOpen by remember { mutableStateOf(false) }
  var videoExportOpen by remember { mutableStateOf(false) }
  var videoPreviewOpen by remember { mutableStateOf(false) }
  // The loop's two knobs: how many frame widths it covers, and how many frames a second it plays at.
  // One pixel per frame means the frame count follows from the loop's width, so the frame rate is what
  // sets how long the clip lasts.
  var videoScreens by remember { mutableIntStateOf(preferences.videoScreens(fallback = 2)) }
  var videoFps by remember { mutableIntStateOf(preferences.videoFps(fallback = 50)) }
  var videoPixelsPerFrame by remember {
    mutableIntStateOf(preferences.videoPixelsPerFrame(fallback = 2))
  }
  // The container the export writes. A setting of the export rather than a second button: the dialog picks
  // one, and the one Export button writes it.
  var videoFormat by remember { mutableStateOf(preferences.videoFormat(fallback = VideoFormat.Gif)) }
  var videoMode by remember { mutableStateOf(preferences.videoMode(fallback = VideoMode.Panorama)) }
  var scrollDirection by remember {
    mutableStateOf(preferences.scrollDirection(fallback = ScrollDirection.LeftToRight))
  }
  // How the exported frames are painted: the picture as drawn, or lifted into a lit metal relief.
  // A new install opens on Metal: the material is the point of this export, and Flat is one tap away.
  var videoLook by remember {
    mutableStateOf(preferences.videoLook(fallback = VideoLook.Metal))
  }
  // The metal material. Kept as one immutable value so a slider drag is one state write and one preview
  // update, and so the GPU renderer receives a coherent set of parameters on every frame.
  var metalSettings by remember { mutableStateOf(preferences.metalSettings()) }
  // How a solve video depicts the search: the real decision order, or the real domain/entropy state.
  var solveAnimation by remember {
    mutableStateOf(preferences.solveAnimation(fallback = SolveAnimation.Collapse))
  }
  // Evolve: how many cells each frame resamples.
  var evolveCells by remember {
    mutableIntStateOf(preferences.evolveCells(fallback = 1))
  }
  var exportingVideo by remember { mutableStateOf(false) }
  var videoExportProgress by remember { mutableStateOf<Float?>(null) }
  var videoExportLabel by remember { mutableStateOf("") }
  var videoExportJob by remember { mutableStateOf<Job?>(null) }
  val videoExportCancelled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
  var toolButtonSize by remember {
    mutableIntStateOf(preferences.toolButtonSize(fallback = BUTTON_SIZE_DP_INT))
  }
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
  var seed by remember {
    mutableIntStateOf(
      if (restored?.savedAsPattern == true) PREVIEW_SEED else restored?.seed ?: PREVIEW_SEED,
    )
  }
  /*
   * True while the drawing on screen is exactly the one last written to the gallery as a pattern.
   * The session snapshot carries it, so the next launch can start blank instead of reopening a drawing
   * the person has already put in the gallery.
   */
  var patternClean by remember { mutableStateOf(restored?.savedAsPattern ?: false) }
  var revisionsSeen by remember { mutableIntStateOf(0) }
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
      } ?: PreviewScale.One,
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
  LaunchedEffect(videoScreens) { preferences.setVideoScreens(videoScreens) }
  LaunchedEffect(videoFps) { preferences.setVideoFps(videoFps) }
  LaunchedEffect(videoPixelsPerFrame) { preferences.setVideoPixelsPerFrame(videoPixelsPerFrame) }
  LaunchedEffect(videoFormat) { preferences.setVideoFormat(videoFormat) }
  LaunchedEffect(videoMode) { preferences.setVideoMode(videoMode) }
  LaunchedEffect(scrollDirection) { preferences.setScrollDirection(scrollDirection) }
  LaunchedEffect(videoLook) { preferences.setVideoLook(videoLook) }
  LaunchedEffect(metalSettings) { preferences.setMetalSettings(metalSettings.sanitised()) }
  LaunchedEffect(solveAnimation) { preferences.setSolveAnimation(solveAnimation) }
  LaunchedEffect(evolveCells) { preferences.setEvolveCells(evolveCells) }
  LaunchedEffect(toolButtonSize) { preferences.setToolButtonSize(toolButtonSize) }
  LaunchedEffect(gridState.revision) {
    if (revisionsSeen > 0 && patternClean) patternClean = false
    revisionsSeen++
  }

  /*
   * The colour the paint tools are using. It lives in the screen, because the toolbar that picks it does;
   * it is mirrored here so the session snapshot can save it.
   */
  var drawingColor by remember {
    mutableStateOf(restored?.takeIf { !it.savedAsPattern }?.let { Color(it.colorArgb) })
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
      colorArgb = (
        drawingColor ?: activePalette.colors.minByOrNull { it.luminance() } ?: Color.Black
        ).toArgb(),
      aspectName = outputSettings.aspect.name,
      previewName = outputSettings.preview.name,
      scaleName = outputSettings.scale.name,
      scalerName = outputSettings.scaler.name,
      kernelName = outputSettings.kernel.name,
      wrapEdges = wrapEdges,
      mirrorHorizontal = mirrorHorizontal,
      mirrorVertical = mirrorVertical,
      paletteId = activePalette.id,
      savedAsPattern = patternClean,
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
   * Sharing writes the picture that is on screen, which is the generated bitmap.
   *
   * The bitmap is copied here, on the main thread, before the write: the generator reuses its render
   * targets, so the live bitmap can be repainted by the next generation while the encoder is reading
   * it. A 304x540 copy is cheap enough to belong on the main thread.
   */
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
   * A FileProvider copy for the View action.
   *
   * Google Photos sometimes opens a just-published MediaStore row and immediately returns to its own
   * last item. A cache copy behind our FileProvider is an ordinary readable image to any viewer, so the
   * View action opens what was just saved even while the gallery is still indexing the row.
   */
  fun viewCopy(source: File, suffix: String): Uri? = runCatching {
    val dir = File(context.cacheDir, ImageSaver.SHARE_FOLDER)
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
    val target = File(dir, "view-${System.currentTimeMillis()}$suffix")
    source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
  }.getOrNull()

  fun viewCopy(bytes: ByteArray, suffix: String): Uri? = runCatching {
    val dir = File(context.cacheDir, ImageSaver.SHARE_FOLDER)
    if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
    val target = File(dir, "view-${System.currentTimeMillis()}$suffix")
    target.outputStream().use { it.write(bytes) }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
  }.getOrNull()

  /**
   * Opens a private EGL/GLES 3.0 context for an export.
   *
   * The caller must create, use and close it on one thread. A null result means the device has no usable
   * GLES 3.0 context; the export then falls back to the CPU shader rather than failing.
   */
  fun openMetalRenderer(): MetalOffscreenRenderer? {
    val renderer = MetalOffscreenRenderer()
    if (renderer.open()) return renderer
    Log.w(TAG, "Metal GPU unavailable: ${renderer.failure}")
    return null
  }

  /**
   * One solved frame at exactly the selected upscaler's multiple of its size.
   *
   * The frame is never resampled to an arbitrary target: an integer factor keeps every pixel block
   * exact, and the chosen pixel-art scaler (Eagle, EPX, etc.) shapes the corners as it does for a saved
   * photo. Nearest simply copies blocks.
   */
  fun scaleVideoFrame(
    frame: IntArray,
    width: Int,
    height: Int,
    scaler: PixelArtScaler,
    factor: Int,
    vertical: Boolean,
  ): IntArray {
    // A vertical export is generated with its axes swapped, so its cropped frames arrive transposed.
    // Turn them back before the ordinary scaler sees them.
    val source = if (vertical) transposePixels(frame, width, height) else frame
    val sourceWidth = if (vertical) height else width
    val sourceHeight = if (vertical) width else height
    if (factor <= 1) return source
    if (scaler == PixelArtScaler.Nearest) {
      return scaleFrame(source, sourceWidth, sourceHeight, sourceWidth * factor, sourceHeight * factor)
    }
    return PixelScaler.scale(source, sourceWidth, sourceHeight, scaler, factor).pixels
  }

  /**
   * Makes the panning GIF: generate a wide panorama, cut frame-sized windows out of it left to right,
   * and encode them into the gallery.
   *
   * The panorama is a second generation, much wider than the preview, so it waits on [generationMutex]
   * and the preview job is cancelled first. The preview is rebuilt in the `finally` whatever happens.
   */
  /**
   * A solve animation built from a replay of the real search.
   *
   * The replay records every domain-size change, then [SolveAnimationFrames] samples it into video
   * frames. [SolveAnimation.Collapse] paints only cells the solver actually decided; [SolveAnimation.Wave]
   * paints the domain size of every cell. GIF adds the reverse run so the last frame is the first one
   * again; MP4 stops on the finished picture.
   */
  fun exportSolveAnimation(format: VideoFormat, animation: SolveAnimation) {
    if (exportingVideo) return
    val solved = output
    if (solved == null) {
      Toast.makeText(context, "There is no solved picture to animate yet", Toast.LENGTH_SHORT).show()
      return
    }
    val finished = solved.bitmap
    videoExportOpen = false
    message = null
    generation.job?.cancel()
    val width = finished.width
    val height = finished.height
    val solvedPixels = IntArray(width * height)
    finished.getPixels(solvedPixels, 0, width, 0, 0, width, height)
    val background = gridState.defaultColor.toArgb()
    val requestedSteps = (videoFps * 2).coerceIn(24, 90)
    val metal = videoLook == VideoLook.Metal
    val metalParams = metalSettings.sanitised()
    // The replay's frames are the solved bitmap itself, so its cells are this many pixels wide.
    val cellPixels = (width / solved.columns.coerceAtLeast(1)).coerceAtLeast(1)

    exportingVideo = true
    videoExportLabel = "Replaying the solve…"
    videoExportProgress = 0f
    videoExportCancelled.set(false)
    videoExportJob = application.exportScope.launch {
      try {
        val trace = WfcGenerator.traceSolve(solved)
        if (trace == null) {
          message = EditorMessage("Could not replay this solve for a video")
          return@launch
        }
        if (videoExportCancelled.get()) throw CancellationException("Export cancelled")
        val frames = SolveAnimationFrames(
          trace = trace,
          solvedPixels = solvedPixels,
          width = width,
          height = height,
          background = background,
          style = animation,
          requestedSteps = requestedSteps,
        )
        val steps = frames.steps
        val total = if (format == VideoFormat.Gif) steps * 2 - 1 else steps
        videoExportLabel = "Encoding ${format.label}…"
        videoExportProgress = 0.01f

        fun forwardIndex(index: Int): Int =
          if (format == VideoFormat.Gif && index >= steps) total - 1 - index else index

        fun rawFrame(forward: Int): IntArray {
          if (videoExportCancelled.get()) throw CancellationException("Export cancelled")
          return frames.frameAt(forward)
        }

        /** One output frame, GPU-shaded when possible and CPU-shaded (the old way) when not. */
        fun frameFor(index: Int, renderer: MetalOffscreenRenderer?, out: IntArray): IntArray {
          val forward = forwardIndex(index)
          val frame = rawFrame(forward)
          if (!metal) return frame
          if (renderer != null) {
            renderer.upload(frame, width, height)
            return renderer.render(
              stillMetalFrame(width, height, cellPixels, forward, total, metalParams),
              out,
            ) ?: MetalShade.paint(frame, width, height, cellPixels)
          }
          return MetalShade.paint(frame, width, height, cellPixels)
        }

        val noun = format.label
        val (uri, viewUri) = if (format == VideoFormat.Mp4) {
          val bytes = withContext(Dispatchers.Default) {
            val renderer = if (metal) openMetalRenderer() else null
            try {
              val out = IntArray(width * height)
              Mp4Encoder.encode(
                context = context,
                width = width,
                height = height,
                frameCount = total,
                fps = videoFps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS),
              ) { index ->
                mainHandler.post { videoExportProgress = 0.01f + 0.97f * index / total }
                frameFor(index, renderer, out)
              }
            } finally {
              renderer?.close()
            }
          }
          val saved = Mp4Saver.save(context, bytes)
          saved to viewCopy(bytes, ".mp4")
        } else {
          val gifDelay = (100f / videoFps.coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS))
            .roundToInt()
            .coerceIn(1, 100)
          val temp = File.createTempFile("wfc-solve-", ".gif", context.cacheDir)
          try {
            withContext(Dispatchers.Default) {
              val renderer = if (metal) openMetalRenderer() else null
              try {
                val out = IntArray(width * height)
                val palette = if (metal) {
                  GifEncoder.paletteFor(
                    frameFor(steps / 3, renderer, out),
                    extraColors = IntArray(0),
                  )
                } else {
                  GifEncoder.paletteFor(solvedPixels, extraColors = frames.paletteExtras)
                }
                temp.outputStream().buffered().use { stream ->
                  GifEncoder.encodeTo(
                    out = stream,
                    width = width,
                    height = height,
                    palette = palette,
                    frameCount = total,
                    delayForFrame = { gifDelay },
                  ) { index ->
                    mainHandler.post { videoExportProgress = 0.01f + 0.97f * index / total }
                    frameFor(index, renderer, out)
                  }
                }
              } finally {
                renderer?.close()
              }
            }
            val saved = GifSaver.save(context, temp)
            saved to viewCopy(temp, ".gif")
          } finally {
            temp.delete()
          }
        }
        videoExportProgress = 1f
        message = EditorMessage("$noun saved", "View") {
          val target = viewUri ?: uri
          if (format == VideoFormat.Mp4) viewMp4(target) else viewVideo(target)
        }
      } catch (e: CancellationException) {
        if (videoExportCancelled.get()) {
          message = EditorMessage("Export cancelled")
        }
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Could not make the solve animation", e)
        message = EditorMessage("Could not make the solve animation")
      } finally {
        exportingVideo = false
        videoExportProgress = null
        videoExportJob = null
        videoExportCancelled.set(false)
        requestGeneration()
      }
    }
  }

  /**
   * Evolve: each frame re-solves [cells] cells against their fixed neighbours, so the picture drifts
   * locally instead of becoming a completely different tiling. One model and one cumulative assignment
   * serve the whole video, so no frame re-runs the full search.
   *
   * The clip is in two passes. The first evolves the picture; the second takes those same changes back
   * one at a time, newest first, so the last frame is one change away from the first and the clip loops.
   */
  fun exportEvolveAnimation(format: VideoFormat, cells: Int) {
    if (exportingVideo) return
    val base = output
    if (base == null) {
      Toast.makeText(context, "There is no solved picture to evolve yet", Toast.LENGTH_SHORT).show()
      return
    }
    val width = base.bitmap.width
    val height = base.bitmap.height
    val baseCenters = base.cellCenters
    if (baseCenters == null) {
      Toast.makeText(context, "There is no solved picture to evolve yet", Toast.LENGTH_SHORT).show()
      return
    }
    val basePixels = IntArray(width * height)
    base.bitmap.getPixels(basePixels, 0, width, 0, 0, width, height)
    val steps = (videoFps * 2).coerceIn(24, 90)
    val baseSeed = seed
    val count = cells.coerceIn(MIN_EVOLVE_CELLS, MAX_EVOLVE_CELLS)
    val scale = (width / base.columns).coerceAtLeast(1)
    val metal = videoLook == VideoLook.Metal
    val metalParams = metalSettings.sanitised()

    videoExportOpen = false
    message = null
    generation.job?.cancel()
    exportingVideo = true
    videoExportLabel = "Evolving ${format.label}…"
    videoExportProgress = 0f
    videoExportCancelled.set(false)
    videoExportJob = application.exportScope.launch {
      try {
        // Evolve all unique frames once, as cell centres. That keeps the encoder from re-solving
        // everything, and is far smaller than holding bitmaps for every frame.
        val evolvedCenters = arrayOfNulls<IntArray>(steps)
        evolvedCenters[0] = baseCenters
        // One model and one cumulative assignment for the whole video: no per-frame re-solve.
        val evolver = TileEvolver(base, count)
        val undoReversions: List<Int>
        try {
          for (frame in 1 until steps) {
            if (videoExportCancelled.get()) throw CancellationException("Export cancelled")
            videoExportProgress = 0.01f + 0.49f * frame / steps
            evolvedCenters[frame] = evolver.nextFrame(baseSeed * 31 + frame * 101)
          }
          // What the second pass has to take back, and how many changes each of its frames reverts.
          undoReversions = evolveUndoPlan(evolver.changeCount)
        } finally {
          // Closing frees the native model; the recorded changes are plain data, so the undo pass can
          // still ask the evolver for its pictures afterwards.
          evolver.close()
        }
        val total = steps + undoReversions.size

        fun rawFrame(index: Int): IntArray {
          if (videoExportCancelled.get()) throw CancellationException("Export cancelled")
          val centres = if (index < steps) {
            evolvedCenters[index] ?: baseCenters
          } else {
            evolver.pictureAfterUndoing(undoReversions[index - steps])
          }
          val pixels = IntArray(width * height)
          renderCellCenters(
            pixels = pixels,
            width = width,
            columns = base.columns,
            rows = base.rows,
            scale = scale,
            centres = centres,
            blocks = base.blocks,
            cell = base.cellSize,
          )
          return pixels
        }

        fun frameFor(index: Int, renderer: MetalOffscreenRenderer?, out: IntArray): IntArray {
          val frame = rawFrame(index)
          if (!metal) return frame
          if (renderer != null) {
            renderer.upload(frame, width, height)
            return renderer.render(
              stillMetalFrame(width, height, scale, index, total, metalParams),
              out,
            ) ?: MetalShade.paint(frame, width, height, scale)
          }
          return MetalShade.paint(frame, width, height, scale)
        }

        videoExportLabel = "Encoding ${format.label}…"
        videoExportProgress = 0.5f
        val (uri, viewUri) = if (format == VideoFormat.Mp4) {
          val bytes = withContext(Dispatchers.Default) {
            val renderer = if (metal) openMetalRenderer() else null
            try {
              val out = IntArray(width * height)
              Mp4Encoder.encode(
                context = context,
                width = width,
                height = height,
                frameCount = total,
                fps = videoFps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS),
              ) { index ->
                mainHandler.post { videoExportProgress = 0.5f + 0.49f * index / total }
                frameFor(index, renderer, out)
              }
            } finally {
              renderer?.close()
            }
          }
          val saved = Mp4Saver.save(context, bytes)
          saved to viewCopy(bytes, ".mp4")
        } else {
          val gifDelay = (100f / videoFps.coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS))
            .roundToInt()
            .coerceIn(1, 100)
          val temp = File.createTempFile("wfc-evolve-", ".gif", context.cacheDir)
          try {
            withContext(Dispatchers.Default) {
              val renderer = if (metal) openMetalRenderer() else null
              try {
                val out = IntArray(width * height)
                val palette = if (metal) {
                  GifEncoder.paletteFor(
                    frameFor((total / 3).coerceIn(0, total - 1), renderer, out),
                    extraColors = IntArray(0),
                  )
                } else {
                  GifEncoder.paletteFor(
                    basePixels,
                    extraColors = activePalette.colors.map { it.toArgb() }.toIntArray(),
                  )
                }
                temp.outputStream().buffered().use { stream ->
                  GifEncoder.encodeTo(
                    out = stream,
                    width = width,
                    height = height,
                    palette = palette,
                    frameCount = total,
                    delayForFrame = { gifDelay },
                  ) { index ->
                    mainHandler.post { videoExportProgress = 0.5f + 0.49f * index / total }
                    frameFor(index, renderer, out)
                  }
                }
              } finally {
                renderer?.close()
              }
            }
            val saved = GifSaver.save(context, temp)
            saved to viewCopy(temp, ".gif")
          } finally {
            temp.delete()
          }
        }
        videoExportProgress = 1f
        message = EditorMessage("${format.label} saved", "View") {
          val target = viewUri ?: uri
          if (format == VideoFormat.Mp4) viewMp4(target) else viewVideo(target)
        }
      } catch (e: CancellationException) {
        if (videoExportCancelled.get()) {
          message = EditorMessage("Export cancelled")
        }
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Could not make the evolve video", e)
        message = EditorMessage("Could not make the evolve video")
      } finally {
        exportingVideo = false
        videoExportProgress = null
        videoExportJob = null
        videoExportCancelled.set(false)
        requestGeneration()
      }
    }
  }

  fun exportVideo(format: VideoFormat) {
    if (videoMode == VideoMode.Solve) {
      exportSolveAnimation(format, solveAnimation)
      return
    }
    if (videoMode == VideoMode.Evolve) {
      exportEvolveAnimation(format, evolveCells)
      return
    }
    if (exportingVideo) return
    videoExportOpen = false
    val frame = videoFramePlan(outputSettings.aspect, outputSettings.preview, outputSettings.kernel)
    val scaleFactor = outputSettings.scale.factor.toInt()
    val previewSize = (
      frame.columns * frame.pixelsPerCell * scaleFactor to
        frame.rows * frame.pixelsPerCell * scaleFactor
      )
    val video = videoPlan(
      frameColumns = frame.columns,
      frameRows = frame.rows,
      pixelsPerCell = frame.pixelsPerCell,
      cellSize = frame.cellSize,
      screens = videoScreens,
      fps = videoFps,
      pixelsPerFrame = videoPixelsPerFrame,
      scrollDirection = scrollDirection,
      outputWidth = previewSize.first,
      outputHeight = previewSize.second,
    )
    val columns = gridState.columns
    val rows = gridState.rows
    val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
    val vertical = scrollDirection.isVertical
    val sourcePixels = if (vertical) transposePixels(pixels, columns, rows) else pixels
    val sourceColumns = if (vertical) rows else columns
    val sourceRows = if (vertical) columns else rows
    val requestedSeed = seed
    val noun = format.label
    // Metal is rendered once per output frame, not baked into the panorama: the key light and the
    // environment turn across the loop, so every frame gets its own lighting. The panorama is uploaded
    // once and each frame is a crop of it, which keeps the per-frame work on the GPU.
    val metal = videoLook == VideoLook.Metal
    val metalParams = metalSettings.sanitised()
    val frameScaler = if (metal) PixelArtScaler.Nearest else outputSettings.scaler
    exportingVideo = true
    videoExportLabel = "Exporting $noun…"
    videoExportProgress = 0f
    message = null
    generation.job?.cancel()
    // The application scope, not the composition: backgrounding or turning the screen off must not
    // cancel a long export. The progress state is observed by the editor whenever it is visible.
    videoExportCancelled.set(false)
    videoExportJob = application.exportScope.launch {
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
                pixels = sourcePixels,
                sourceWidth = sourceColumns,
                sourceHeight = sourceRows,
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

        val canSolvePanorama = panoramaColumns * panoramaRows <= MAX_PANORAMA_CELLS
        videoExportLabel = if (canSolvePanorama) {
          "Solving the $noun panorama…"
        } else {
          "Building $noun…"
        }
        // The first solve can take seconds, so let the bar creep forward instead of sitting at zero.
        // It never passes the 10% mark reserved for the panorama; encoding takes the rest.
        val solveStartedAt = System.nanoTime()
        val solveTicker = launch {
          while (isActive) {
            delay(200)
            val seconds = (System.nanoTime() - solveStartedAt) / 1_000_000_000.0
            val fraction = (seconds / 20.0).toFloat().coerceIn(0f, 1f)
            videoExportProgress = maxOf(videoExportProgress ?: 0f, fraction * 0.01f)
          }
        }
        val open = if (canSolvePanorama) {
          solveOne(null)
        } else {
          // A preview-sized frame can put the loop over the one-solve budget. Skip the doomed solve and
          // go straight to stitching screens at the selected size.
          null
        }
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
        solveTicker.cancel()
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
          videoExportProgress = maxOf(videoExportProgress ?: 0f, 0.01f)
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
          val attempts = if (previousCenters == null) {
            // The first screen has no edge to pin; trying the same solve four times only multiplies
            // the worst-case wait.
            listOf(0 to false)
          } else {
            listOf(
              video.overlapColumns to true,
              video.overlapColumns to false,
              0 to false,
            )
          }.distinct()
          var generated: WfcGenerator.Output? = null
          var lastFailure: WfcGenerator.GenerationException? = null
          var pinnedColumns = 0
          var pinnedPatterns = false
          for ((pinWidth, wantPatterns) in attempts) {
            try {
              generated = generationMutex.withLock {
                WfcGenerator.generate(
                  pixels = sourcePixels,
                  sourceWidth = sourceColumns,
                  sourceHeight = sourceRows,
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
          // If even the unpinned solve was the one that worked, still paste the previous edge over this
          // screen: the join keeps its edge data even though the solve did not constrain it.
          if (screen > 0 && pinnedColumns == 0) pinnedColumns = video.overlapColumns
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
          // A stitched fallback is rarer and slower than one panorama solve; give it the span from
          // 10% to 35%, then leave everything from there on to the frame encoder.
          videoExportProgress = maxOf(
            videoExportProgress ?: 0f,
            0.01f * (screen + 1) / video.screenCount,
          )
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
        /**
         * The CPU fallback: crop, shade the old way, scale. Only used if the device cannot create a
         * GLES 3.0 context, so the export still produces a metal-ish file instead of failing.
         */
        fun cpuFrame(index: Int): IntArray {
          val cropped = cropFrame(panorama, video.panoramaWidth, video, index)
          val shaded = if (metal) {
            MetalShade.paint(cropped, video.frameWidth, video.frameHeight, video.pixelsPerCell)
          } else {
            cropped
          }
          return scaleVideoFrame(
            shaded,
            video.frameWidth,
            video.frameHeight,
            if (metal) PixelArtScaler.Nearest else frameScaler,
            scaleFactor,
            vertical = scrollDirection.isVertical,
          )
        }

        /** Uploads the loop once; vertical loops are transposed back to upright for the shader. */
        fun uploadPanorama(renderer: MetalOffscreenRenderer) {
          if (scrollDirection.isVertical) {
            val upright = transposePixels(panorama, video.panoramaWidth, frameHeight)
            renderer.upload(upright, frameHeight, video.panoramaWidth)
          } else {
            renderer.upload(panorama, video.panoramaWidth, frameHeight)
          }
        }
        val encodingStart = 0.01f
        videoExportProgress = maxOf(videoExportProgress ?: 0f, encodingStart)
        val reportFrame: (Int) -> Unit = { index ->
          if (videoExportCancelled.get()) {
            throw CancellationException("Export cancelled")
          }
          if (index % 4 == 0 || index == video.frameCount - 1) {
            val progress = encodingStart + (0.99f - encodingStart) * index / video.frameCount
            mainHandler.post {
              videoExportProgress = maxOf(videoExportProgress ?: 0f, progress)
            }
          }
        }
        val (uri, viewUri) = if (format == VideoFormat.Mp4) {
          val bytes = withContext(Dispatchers.Default) {
            val renderer = if (metal) openMetalRenderer() else null
            try {
              if (renderer != null) uploadPanorama(renderer)
              val out = IntArray(video.outputWidth * video.outputHeight)
              Mp4Encoder.encode(
                context = context,
                width = video.outputWidth,
                height = video.outputHeight,
                frameCount = video.frameCount,
                // The GIF delay is whole centiseconds, so the rate that delay makes is the real one; both
                // formats use it so the MP4 and the GIF play at the same speed.
                fps = videoFps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS),
              ) { index ->
                reportFrame(index)
                if (renderer != null) {
                  renderer.render(panoramaMetalFrame(video, index, metalParams), out)
                    ?: cpuFrame(index)
                } else {
                  cpuFrame(index)
                }
              }
            } finally {
              renderer?.close()
            }
          }
          videoExportProgress = maxOf(videoExportProgress ?: 0f, 0.99f)
          val saved = Mp4Saver.save(context, bytes)
          saved to viewCopy(bytes, ".mp4")
        } else {
          // Stream the GIF to a cache file: a full-resolution clip's bytes do not fit in a
          // ByteArrayOutputStream (that was the 200 MB allocation crash).
          val temp = File.createTempFile("wfc-export-", ".gif", context.cacheDir)
          try {
            withContext(Dispatchers.Default) {
              val renderer = if (metal) openMetalRenderer() else null
              try {
                if (renderer != null) uploadPanorama(renderer)
                val out = IntArray(video.outputWidth * video.outputHeight)
                val palette = if (metal) {
                  // Shade a representative frame for the 256-colour table: a flat palette would clip
                  // every sheen into bands, while one lit frame covers the actual range of the clip.
                  val sampleIndex = video.frameCount / 3
                  val sample = if (renderer != null) {
                    renderer.render(panoramaMetalFrame(video, sampleIndex, metalParams), out)
                      ?: cpuFrame(sampleIndex)
                  } else {
                    cpuFrame(sampleIndex)
                  }
                  GifEncoder.paletteFor(sample)
                } else {
                  GifEncoder.paletteFor(panorama)
                }
                temp.outputStream().buffered().use { stream ->
                  GifEncoder.encodeTo(
                    out = stream,
                    width = video.outputWidth,
                    height = video.outputHeight,
                    palette = palette,
                    frameCount = video.frameCount,
                    delayForFrame = { video.frameDelayCentiseconds },
                  ) { index ->
                    reportFrame(index)
                    if (renderer != null) {
                      renderer.render(panoramaMetalFrame(video, index, metalParams), out)
                        ?: cpuFrame(index)
                    } else {
                      cpuFrame(index)
                    }
                  }
                }
              } finally {
                renderer?.close()
              }
            }
            videoExportProgress = maxOf(videoExportProgress ?: 0f, 0.99f)
            val saved = GifSaver.save(context, temp)
            saved to viewCopy(temp, ".gif")
          } finally {
            temp.delete()
          }
        }
        videoExportProgress = 1f
        message = EditorMessage("$noun saved", "View") {
          val target = viewUri ?: uri
          if (format == VideoFormat.Mp4) viewMp4(target) else viewVideo(target)
        }
      } catch (e: CancellationException) {
        if (videoExportCancelled.get()) {
          message = EditorMessage("Export cancelled")
        }
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
        videoExportJob = null
        videoExportCancelled.set(false)
        requestGeneration()
      }
    }
  }

  /**
   * The size the file is written at: the grid at the save size, which is the picture's own size and not a
   * multiple of whatever the preview happens to be showing.
   */
  fun savedSize(): Pair<Int, Int> =
    savedPixelSize(outputSettings.aspect, outputSettings.scale, outputSettings.preview)

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

  /**
   * The single Save: the PNG is the current generated preview, the pattern (drawing, palette and
   * settings) travels in its JSON metadata, and the file name is generated for the gallery.
   */
  fun savePattern() {
    val preview = currentPicture()
    if (preview == null) {
      Toast.makeText(context, nothingToUse(), Toast.LENGTH_SHORT).show()
      return
    }
    val snapshot = snapshotProvider()
    val revisionAtSave = gridState.revision
    val width = preview.width
    val height = preview.height
    val pixels = IntArray(width * height)
    preview.getPixels(pixels, 0, width, 0, 0, width, height)
    preview.recycle()
    scope.launch {
      val saved = patternGallery.save(
        palette = activePalette,
        snapshot = snapshot,
        previewPixels = pixels,
        previewWidth = width,
        previewHeight = height,
      )
      message = if (saved != null) {
        // Only call the session clean if nothing was edited while the file was being written.
        if (gridState.revision == revisionAtSave) patternClean = true
        EditorMessage("Pattern saved to the gallery", "View") { viewPicture(saved) }
      } else {
        EditorMessage("Could not save that pattern")
      }
    }
  }

  fun loadPatterns() {
    scope.launch {
      savedPatterns = patternGallery.list()
      loadPatternDialogOpen = true
    }
  }

  // Reading the pattern images from the gallery album is a media read on every supported API level.
  val readMediaPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      loadPatterns()
    } else {
      Toast.makeText(context, "The pattern picker needs access to gallery images", Toast.LENGTH_SHORT).show()
    }
  }

  fun openPatternPicker() {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Manifest.permission.READ_MEDIA_IMAGES
    } else {
      Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
      loadPatterns()
    } else {
      readMediaPermission.launch(permission)
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
    onCancelVideoExport = {
      videoExportCancelled.set(true)
      videoExportLabel = "Cancelling…"
      videoExportJob?.cancel()
    },
    output = outputSettings,
    onOutputChange = { next ->
      // A bigger grouping needs the drawing to be whole cells wide and tall; picking one adds the space
      // it needs rather than leaving a strip at the edge that the solver never sees.
      if (next.kernel != outputSettings.kernel) gridState.fitCells(next.kernel.cellSize)
      outputSettings = next
    },
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
    onSave = { savePattern() },
    onShare = { sharePicture() },
    onLoadPattern = { openPatternPicker() },
    onExportVideo = { videoExportOpen = true },
    onExit = { exitApp() },
    toolButtonSize = toolButtonSize,
    onToolButtonSizeChange = { toolButtonSize = it },
    // Only a restored session has a colour to start with; otherwise the palette's first swatch is it.
    initialColor = restored?.takeIf { !it.savedAsPattern }?.let { Color(it.colorArgb) },
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
  val videoOutput = (
    videoFrame.columns * videoFrame.pixelsPerCell * outputSettings.scale.factor.toInt() to
      videoFrame.rows * videoFrame.pixelsPerCell * outputSettings.scale.factor.toInt()
    )
  // The live metal preview is the solved picture itself, uploaded once. `remember` is keyed on the
  // output and the look so a slider drag does not copy a megapixel array on every recomposition.
  val metalPreviewPixels = remember(output, videoLook) {
    if (videoLook != VideoLook.Metal) {
      null
    } else {
      output?.let { solved ->
        val w = solved.bitmap.width
        val h = solved.bitmap.height
        IntArray(w * h).also { solved.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
      }
    }
  }
  val metalPreviewWidth = output?.bitmap?.width ?: 0
  val metalPreviewHeight = output?.bitmap?.height ?: 0
  val metalPreviewCell = if (metalPreviewWidth > 0 && (output?.columns ?: 0) > 0) {
    (metalPreviewWidth / output!!.columns).coerceAtLeast(1)
  } else {
    1
  }
  VideoExportScreen(
    open = videoExportOpen,
    format = videoFormat,
    mode = videoMode,
    solveAnimation = solveAnimation,
    evolveCells = evolveCells,
    screens = videoScreens,
    fps = videoFps,
    pixelsPerFrame = videoPixelsPerFrame,
    scrollDirection = scrollDirection,
    look = videoLook,
    metal = metalSettings,
    previewPixels = metalPreviewPixels,
    previewWidth = metalPreviewWidth,
    previewHeight = metalPreviewHeight,
    previewCellPixels = metalPreviewCell,
    frameColumns = videoFrame.columns,
    frameRows = videoFrame.rows,
    pixelsPerCell = videoFrame.pixelsPerCell,
    cellSize = videoFrame.cellSize,
    outputWidth = videoOutput.first,
    outputHeight = videoOutput.second,
    exporting = exportingVideo,
    onScreensChange = { videoScreens = it },
    onModeChange = { videoMode = it },
    onSolveAnimationChange = { solveAnimation = it },
    onEvolveCellsChange = { evolveCells = it },
    onFpsChange = { videoFps = it },
    onPixelsPerFrameChange = { videoPixelsPerFrame = it },
    onScrollDirectionChange = { scrollDirection = it },
    onLookChange = { videoLook = it },
    onMetalChange = { metalSettings = it.sanitised() },
    onFormatChange = { videoFormat = it },
    onPreview = { videoPreviewOpen = true },
    onDismiss = { videoExportOpen = false },
    onExport = { exportVideo(videoFormat) },
  )
  val previewFps = if (videoFormat == VideoFormat.Gif) {
    val delay = (100f / videoFps.coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS))
      .roundToInt()
      .coerceIn(1, 100)
    // Google Photos -- the viewer this app hands GIFs to -- clamps short GIF delays (1 cs and often
    // anything under 10 cs) to roughly 10 frames per second. Preview at that floor so the motion
    // matches what the viewer will show, not the nominal delay written in the file.
    (100f / maxOf(delay, 10)).roundToInt()
  } else {
    videoFps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS)
  }
  VideoPreviewDialog(
    open = videoPreviewOpen,
    image = output?.image,
    fps = previewFps,
    pixelsPerFrame = videoPixelsPerFrame,
    scrollDirection = scrollDirection,
    onDismiss = { videoPreviewOpen = false },
  )

  if (loadPatternDialogOpen) {
    LoadPatternDialog(
      patterns = savedPatterns,
      onDismiss = { loadPatternDialogOpen = false },
      onLoad = { metadata ->
        loadPatternDialogOpen = false
        scope.launch {
          val saved = savedPatterns.firstOrNull { it.metadata.id == metadata.id }
          if (saved == null) {
            message = EditorMessage("Could not load that pattern")
            return@launch
          }

          val snapshot = saved.snapshot
          val patternKernel = KernelSize.entries.firstOrNull { it.name == snapshot.kernelName }
            ?: outputSettings.kernel
          // Restored under its own grouping: a pattern saved at 9x9 and loaded while 15x15 is chosen
          // grows to whole 5-pixel cells, which is what picking that grouping does to any drawing.
          gridState.restoreDrawing(
            snapshot.columns,
            snapshot.rows,
            snapshot.pixels,
            cellSize = patternKernel.cellSize,
          )
          patternClean = false
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
            kernel = patternKernel,
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
          val deleted = patternGallery.delete(metadata.id)
          savedPatterns = patternGallery.list()
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