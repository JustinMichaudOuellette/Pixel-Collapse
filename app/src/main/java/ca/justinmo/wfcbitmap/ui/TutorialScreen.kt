package ca.justinmo.wfcbitmap.ui

import android.content.res.Configuration
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

/**
 * The first-launch tour: six screens that explain what the app does before the editor asks to be
 * used.
 *
 * The first launch shows it as the screen's own content, so the editor is not painted underneath
 * it; opened from the About screen it is a dialog over the editor instead, which leaves the drawing
 * and the solver exactly where they were. The first page keeps the drawing the tour has always
 * shown and generates the result beside it itself: a square, deliberately coarse collapse of that
 * drawing, made with the widest tile set the generator offers (wrapped, borderless edges, both
 * mirrors and the quarter turn) and advanced to the next seed once a second. It starts from the
 * editor's current seed so the variations continue where the picture on screen left off.
 *
 * The pages are paged rather than tabbed because this is read once; dots and a Next button are
 * enough to say where you are.
 */
@Composable
internal fun TutorialScreen(
    open: Boolean,
    onDismiss: () -> Unit,
    seed: Int = SAMPLE_FIRST_SEED,
) {
  if (!open) return
  Dialog(
      onDismissRequest = onDismiss,
      properties =
          DialogProperties(
              usePlatformDefaultWidth = false,
              decorFitsSystemWindows = false,
          ),
  ) {
    TutorialContent(
        onDismiss = onDismiss,
        seed = seed,
        // The dialog is its own window, outside the activity's Scaffold, so it applies the
        // safe-drawing
        // insets itself rather than inheriting the editor's content padding.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )
  }
}

/**
 * The tour as the screen's own content, for the first launch.
 *
 * Composed into the activity's window instead of a dialog window, so the window's first frame is
 * the tour: the editor is never painted for the frame or two a dialog takes to appear, and it is
 * not composed at all until the tour closes. [onDismiss] covers Skip, the last page's button, and
 * the back gesture.
 */
@Composable
internal fun FirstLaunchTutorial(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    seed: Int = SAMPLE_FIRST_SEED,
) {
  // A dialog closes on back by itself; an inline screen has to say so, or back would close the app.
  BackHandler(onBack = onDismiss)
  TutorialContent(onDismiss = onDismiss, seed = seed, modifier = modifier)
}

/**
 * Shows the navigation bar for as long as the tour is on screen.
 *
 * The editor owns the app's chrome and keeps the navigation bar out of the way, but the tour is
 * read rather than drawn: its pages are swiped through and its footer buttons sit at the bottom
 * edge, so the bar has to be there while it is up, and put away again when the editor comes back. A
 * dialog has a window of its own; the first-launch tour shares the activity's, so both windows are
 * handled here.
 */
@Composable
private fun ShowNavigationBarWhileVisible() {
  val view = LocalView.current
  DisposableEffect(view) {
    val window =
        (view.parent as? DialogWindowProvider)?.window ?: view.context.hostingActivity()?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    if (controller == null) {
      onDispose {}
    } else {
      controller.show(WindowInsetsCompat.Type.navigationBars())
      onDispose {
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    }
  }
}

/**
 * The tour's pages, page dots and footer: the one implementation shared by [TutorialScreen]'s
 * dialog and by [FirstLaunchTutorial]'s full screen. The caller supplies the safe-drawing padding
 * its host needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TutorialContent(
    onDismiss: () -> Unit,
    seed: Int,
    modifier: Modifier = Modifier,
) {
  ShowNavigationBarWhileVisible()
  val pager = rememberPagerState(pageCount = { TUTORIAL_PAGES.size })
  val scope = rememberCoroutineScope()
  val page = pager.currentPage
  val lastPage = TUTORIAL_PAGES.lastIndex
  // Landscape keeps the same footer but gives up some of its padding: the page content needs the height.
  val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
    Column(modifier = modifier.fillMaxSize()) {
      TopAppBar(
          title = { Text("Introduction") },
          actions = {
            TextButton(onClick = onDismiss) {
              Text("Skip", color = MENU_TEXT_COLOR.copy(alpha = 0.75f))
            }
          },
          colors =
              TopAppBarDefaults.topAppBarColors(
                  containerColor = PANEL_BACKGROUND,
                  titleContentColor = MENU_TEXT_COLOR,
                  navigationIconContentColor = MENU_TEXT_COLOR,
                  actionIconContentColor = MENU_TEXT_COLOR,
              ),
          // The screen already applies the safe-drawing insets; the app bar must not apply them
          // again.
          windowInsets = WindowInsets(0, 0, 0, 0),
      )

      HorizontalPager(
          state = pager,
          modifier = Modifier.fillMaxWidth().weight(1f),
      ) { index ->
        TutorialPageView(TUTORIAL_PAGES[index], seed, active = index == pager.settledPage)
      }

      // One row for the whole footer: Back on the left, the page dots centred between the two
      // buttons, and
      // Next on the right. A Box rather than weighted spacers because the dots belong on the
      // screen's
      // centre line, not on the centre of whatever the two buttons leave over.
      Box(
          modifier =
              Modifier.fillMaxWidth()
                  .padding(horizontal = 20.dp, vertical = if (landscape) 6.dp else 16.dp),
      ) {
        if (page > 0) {
          TextButton(
              onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
              modifier = Modifier.align(Alignment.CenterStart),
          ) {
            Text("Back", color = MENU_TEXT_COLOR.copy(alpha = 0.72f))
          }
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          TUTORIAL_PAGES.indices.forEach { index ->
            Box(
                modifier =
                    Modifier.padding(horizontal = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == page) MENU_TEXT_COLOR
                            else MENU_TEXT_COLOR.copy(alpha = 0.22f),
                        ),
            )
          }
        }
        Button(
            onClick = {
              if (page == lastPage) {
                onDismiss()
              } else {
                scope.launch { pager.animateScrollToPage(page + 1) }
              }
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MENU_TEXT_COLOR,
                    contentColor = Color.Black,
                ),
            modifier = Modifier.align(Alignment.CenterEnd).height(if (landscape) 40.dp else 48.dp),
        ) {
          Text(if (page == lastPage) "Start drawing" else "Next", fontSize = 14.sp)
        }
      }
    }
  }
}

@Composable
private fun TutorialPageView(page: TutorialPage, seed: Int, active: Boolean) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    if (maxWidth > maxHeight) {
      LandscapeTutorialPage(page, seed, active)
    } else {
      // The picture keeps a little under half the page at most, so even a short landscape screen
      // still leaves
      // the title and the body a scrollable area of their own below it.
      val pictureSize = maxHeight * 0.45f
      // The toolbar page has no drawing beside it and a short body, so it can give its tools more height
      // and draw them at a comfortable size.
      val toolsHeight = maxHeight * 0.62f
      Column(
          modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Spacer(Modifier.height(20.dp))
        when (page.art) {
          // The first page animates drawing the sample one touch at a time; the next page shows that
          // drawing
          // finished, beside the picture collapsed from it.
          TutorialArtKind.Draw -> DrawingTutorialArt(Modifier.size(minOf(300.dp, pictureSize)))
          TutorialArtKind.Extract -> ExtractionTutorialArt(Modifier.size(minOf(300.dp, pictureSize)))
          TutorialArtKind.PatternResult -> PatternResultArt(seed)
          TutorialArtKind.Tools ->
              ToolsTutorialArt(Modifier.fillMaxWidth().height(toolsHeight), active = active)
        }
        /*
         * The title and the body share whatever height the picture leaves, and are centred in it rather than
         * pinned under the picture. The scroll belongs to the block, not to the page: a body too tall for a
         * short screen scrolls on its own, while the title and the picture stay put.
         */
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
          Column(
              modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
                text = page.title,
                color = MENU_TEXT_COLOR,
                fontSize = 25.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = page.body,
                color = MENU_TEXT_COLOR.copy(alpha = 0.72f),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 380.dp),
            )
          }
        }
      }
    }
  }
}

/**
 * One page in landscape: the illustration on the left and the text on the right.
 *
 * The row gives the picture the full height of the pager instead of a slice of it, which is what a
 * short landscape screen needs; the text gets the wider, calmer column on the right and scrolls on
 * its own.
 */
@Composable
private fun LandscapeTutorialPage(page: TutorialPage, seed: Int, active: Boolean) {
  Row(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.weight(0.56f).fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
      when (page.art) {
        TutorialArtKind.Draw -> DrawingTutorialArt(Modifier.fillMaxSize())
        TutorialArtKind.Extract -> ExtractionTutorialArt(Modifier.fillMaxSize())
        TutorialArtKind.PatternResult -> PatternResultArt(seed)
        TutorialArtKind.Tools -> ToolsTutorialArt(Modifier.fillMaxSize(), active = active)
      }
    }
    Box(
        modifier = Modifier.weight(0.44f).fillMaxHeight(),
        contentAlignment = Alignment.CenterStart,
    ) {
      Column(
          modifier =
              Modifier.fillMaxWidth()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 8.dp, vertical = 8.dp),
          horizontalAlignment = Alignment.Start,
      ) {
        Text(
            text = page.title,
            color = MENU_TEXT_COLOR,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = page.body,
            color = MENU_TEXT_COLOR.copy(alpha = 0.72f),
            fontSize = 12.5f.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.widthIn(max = 460.dp),
        )
      }
    }
  }
}

/** How often the sample advances: one fresh collapse per second, each for the next seed. */
private const val SAMPLE_INTERVAL_MILLIS = 1_000L

/**
 * The drawing animation's gesture pace. A dragged stroke starts with a short press, then the
 * blurred grey touch circle expands as the long press is recognised; only then does the finger
 * start dragging.
 */
private const val DRAWING_PRESS_MILLIS = 120L
private const val DRAWING_LONG_PRESS_MILLIS = 400L
private const val DRAWING_LONG_PRESS_STEPS = 10

/**
 * The pace of a tapped stroke: the circle grows from small to a little under the drag size while
 * the cell is held, rests a beat, then lifts. The pause after each lift is what makes one tap per
 * cell legible.
 */
private const val DRAWING_TAP_MILLIS = 150L
private const val DRAWING_TAP_STEPS = 12
private const val DRAWING_TAP_HOLD_MILLIS = 50L
private const val DRAWING_TAP_GAP_MILLIS = 160L

/** How long the highlight rests on the last cell while the hand lifts. */
private const val DRAWING_TOUCH_UP_MILLIS = 90L

/** Pause between strokes, while the hand moves to the next one; the highlight is off for this. */
private const val DRAWING_STROKE_GAP_MILLIS = 220L

/** Blank-paper pause before the drawing starts again, and the hold on the finished drawing. */
private const val DRAWING_RESTART_MILLIS = 600L
private const val DRAWING_HOLD_MILLIS = 1_800L

/** The seed a sample starts on when it is composed without editor state (previews and UI tests). */
private const val SAMPLE_FIRST_SEED = 1

/**
 * How long the sample's solve may take.
 *
 * The editor owns its solve budgets; a demonstration must not inherit them (see
 * [WfcGenerator.generate]'s `isSessionPicture`), so it carries one of its own. It is only a bound
 * on a pathological collapse: the sample's grid is small, so it normally fits in the editor's own
 * budget.
 */
private const val SAMPLE_SOLVE_BUDGET_MILLIS = 4_000L

/**
 * The sample's own result grid: square and coarser than the editor's preview, so the page reads as
 * pixel art rather than as a second preview. One pixel per cell; [PixelBitmap] scales it up by
 * whole pixels.
 */
private const val SAMPLE_RESULT_COLUMNS = 32
private const val SAMPLE_RESULT_ROWS = 32
private const val SAMPLE_RESULT_RATIO = SAMPLE_RESULT_COLUMNS.toFloat() / SAMPLE_RESULT_ROWS

/** Log tag for a sample that could not be generated; the page keeps its last frame. */
private const val TAG = "TutorialScreen"

/**
 * The first page's animation: the tour's 8x9 drawing being painted the way a hand would draw it.
 *
 * The frame and the left inner bar are long presses followed by drags: the finger presses the first
 * cell, the blurred grey touch circle expands while the hold is being recognised, and only then
 * does the segment start to paint. The last group, the short bar on the right, is drawn with taps
 * instead: each cell gets its own touch, the circle grows to a slightly smaller size on it, and
 * both lift before the next tap. The circle rests on the last cell while the hand lifts, then the
 * next gesture begins.
 */
@Composable
private fun DrawingTutorialArt(modifier: Modifier) {
  var painted by remember { mutableIntStateOf(0) }
  var touched by remember { mutableIntStateOf(-1) }
  var pressProgress by remember { mutableFloatStateOf(1f) }
  var touchRadius by remember { mutableFloatStateOf(TOUCH_PRESSED_RADIUS_CELLS) }
  LaunchedEffect(Unit) {
    val strokes = SAMPLE_DRAWING_STROKES
    while (isActive) {
      painted = 0
      touched = -1
      pressProgress = 1f
      delay(DRAWING_RESTART_MILLIS.milliseconds)
      for (stroke in strokes) {
        touchRadius = if (stroke.taps) TOUCH_TAPPED_RADIUS_CELLS else TOUCH_PRESSED_RADIUS_CELLS
        if (stroke.taps) {
          // One tap per cell: the circle grows on the cell while it is pressed, holds that size a
          // beat, then
          // lifts and the next tap begins after a pause. The growth and the gaps are what make each
          // cell read
          // as its own tap rather than as a drag.
          for (cell in stroke.cells) {
            touched = cell
            pressProgress = 0f
            painted++
            for (step in 1..DRAWING_TAP_STEPS) {
              pressProgress = step / DRAWING_TAP_STEPS.toFloat()
              delay((DRAWING_TAP_MILLIS / DRAWING_TAP_STEPS).milliseconds)
            }
            delay(DRAWING_TAP_HOLD_MILLIS.milliseconds)
            touched = -1
            delay(DRAWING_TAP_GAP_MILLIS.milliseconds)
          }
        } else {
          touched = stroke.cells.first()
          pressProgress = 0f
          delay(DRAWING_PRESS_MILLIS.milliseconds)
          for (step in 1..DRAWING_LONG_PRESS_STEPS) {
            pressProgress = step / DRAWING_LONG_PRESS_STEPS.toFloat()
            delay((DRAWING_LONG_PRESS_MILLIS / DRAWING_LONG_PRESS_STEPS).milliseconds)
          }
          for (index in stroke.cells.indices) {
            touched = stroke.cells[index]
            painted++
            delay(
              (if (index == stroke.cells.lastIndex) {
                DRAWING_TOUCH_UP_MILLIS
              } else {
                stroke.stepMillis
              }).milliseconds,
            )
          }
          touched = -1
        }
        delay(DRAWING_STROKE_GAP_MILLIS.milliseconds)
      }
      delay(DRAWING_HOLD_MILLIS.milliseconds)
    }
  }
  val pixels =
      remember(painted) {
        IntArray(SAMPLE_DRAWING_COLUMNS * SAMPLE_DRAWING_ROWS) { SAMPLE_PAPER }
            .also { cells ->
              for (step in 0 until painted) {
                val cell = SAMPLE_DRAWING_TOUCHES[step]
                cells[cell] = SAMPLE_DRAWING[cell]
              }
            }
      }
  PixelCells(
      pixels = pixels,
      columns = SAMPLE_DRAWING_COLUMNS,
      rows = SAMPLE_DRAWING_ROWS,
      modifier = modifier,
      touchedCell = touched,
      pressProgress = pressProgress,
      maxPressRadius = touchRadius,
  )
}

/**
 * The extraction page's animation: a 3x3 window centred on one pixel at a time slides across the
 * tour's drawing, and the window under it is shown enlarged beside the drawing as the tile it
 * becomes.
 *
 * Every pixel is visited, edges included, because that is how the solver reads the drawing: a
 * window is centred on a pixel, and on a border pixel it reads the drawing's opposite edge -- the
 * relaxed, borderless edges the sample is generated with. The scan runs at one steady pace, rows
 * included, so it reads as a window being moved over the drawing rather than as a blur of tiles.
 */
@Composable
private fun ExtractionTutorialArt(modifier: Modifier) {
  var window by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    val count = EXTRACT_COLUMNS * EXTRACT_ROWS
    while (isActive) {
      for (index in 0 until count) {
        window = index
        delay(EXTRACT_STEP_MILLIS.milliseconds)
      }
      delay(EXTRACT_HOLD_MILLIS.milliseconds)
    }
  }
  val column = window % EXTRACT_COLUMNS
  val row = window / EXTRACT_COLUMNS
  val tile =
      remember(window) {
        IntArray(3 * 3) { cell ->
          val x = Math.floorMod(column + cell % 3 - 1, SAMPLE_DRAWING_COLUMNS)
          val y = Math.floorMod(row + cell / 3 - 1, SAMPLE_DRAWING_ROWS)
          SAMPLE_DRAWING[y * SAMPLE_DRAWING_COLUMNS + x]
        }
      }
  Row(
      modifier = modifier,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    // The drawing, and the one window being read out of it.
    PixelCells(
        pixels = SAMPLE_DRAWING,
        columns = SAMPLE_DRAWING_COLUMNS,
        rows = SAMPLE_DRAWING_ROWS,
        modifier = Modifier.weight(2.2f).aspectRatio(SAMPLE_DRAWING_RATIO),
        highlightColumn = column,
        highlightRow = row,
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = MENU_TEXT_COLOR.copy(alpha = 0.65f),
        modifier = Modifier.size(16.dp),
    )
    // The window as a tile: the same nine cells, enlarged, which is what the solver can then place.
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      PixelCells(
          pixels = tile,
          columns = 3,
          rows = 3,
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      )
      Spacer(Modifier.height(4.dp))
      Text(
          text = "one tile",
          color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
          fontSize = 10.sp,
      )
    }
  }
}

/**
 * The result page's before/after: the tour's 8x9 drawing and a square, deliberately coarse collapse
 * of it.
 *
 * The drawing keeps the editor's grey grid; the result is a bitmap, so it is drawn without
 * separators. The result is live: its first frame is generated with the seed the editor's picture
 * is on, and every second the sample takes the next seed and collapses the drawing again, which is
 * the same "another arrangement of the same drawing" the preview offers on a tap.
 */
@Composable
private fun PatternResultArt(seed: Int) {
  var result by remember(seed) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(seed) {
    var nextSeed = seed
    while (isActive) {
      generateResult(nextSeed)?.let { result = it }
      nextSeed++
      delay(SAMPLE_INTERVAL_MILLIS.milliseconds)
    }
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      PixelCells(
          pixels = SAMPLE_DRAWING,
          columns = SAMPLE_DRAWING_COLUMNS,
          rows = SAMPLE_DRAWING_ROWS,
          modifier = Modifier.width(88.dp).aspectRatio(SAMPLE_DRAWING_RATIO),
      )
      Spacer(Modifier.height(6.dp))
      Text(
          text = "Drawn bitmap",
          color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
          fontSize = 10.sp,
      )
    }
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      PixelBitmap(
          image = result,
          modifier = Modifier.width(188.dp).aspectRatio(SAMPLE_RESULT_RATIO),
      )
      Spacer(Modifier.height(6.dp))
      Text(
          text = "Generated bitmaps",
          color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
          fontSize = 10.sp,
      )
    }
  }
}

/**
 * One frame of the sample: the page's drawing collapsed on its own square, low-resolution grid.
 *
 * The grid is deliberately not the editor's: [SAMPLE_RESULT_COLUMNS] x [SAMPLE_RESULT_ROWS] cells
 * at one pixel each make a small picture that reads as pixel art when the page scales it up. The
 * page always shows the widest tile set the generator offers -- the borderless (wrapped) edge rule,
 * both mirrors and the quarter turn -- and the demonstration flag keeps the solves out of the
 * editor's budgets and caches and away from the bitmap the editor is showing.
 *
 * Null when the solve found no picture; the page then keeps the last frame for another second.
 */
private suspend fun generateResult(seed: Int): ImageBitmap? {
  return try {
    WfcGenerator.generate(
            pixels = SAMPLE_DRAWING,
            sourceWidth = SAMPLE_DRAWING_COLUMNS,
            sourceHeight = SAMPLE_DRAWING_ROWS,
            seed = seed,
            columns = SAMPLE_RESULT_COLUMNS,
            rows = SAMPLE_RESULT_ROWS,
            // The drawing's edges are read as wrapping into each other: every window is a complete
            // pattern, the edge
            // rule has no border left to keep, and the tile set is the dense one the page has
            // always shown. Sampling
            // with a border and only relaxing the edge rule was what turned the result into coarse,
            // wavy bands.
            wrapEdges = true,
            mirrorHorizontal = true,
            mirrorVertical = true,
            quarterTurn = true,
            pixelsPerCell = 1,
            cellSize = 1,
            timeoutMillis = SAMPLE_SOLVE_BUDGET_MILLIS,
            // The sample is a demonstration, not the picture the editor shows: its solves must not
            // become the
            // cost the next full-size preview is measured against, nor overwrite the bitmap already
            // on screen.
            isSessionPicture = false,
        )
        .image
  } catch (e: CancellationException) {
    // The tour closed or paged away: not a failed picture, just one nobody is waiting for any more.
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Could not generate the tutorial's sample for seed $seed", e)
    null
  }
}

/**
 * One pixel-art grid: [pixels] drawn as cells, with the editor's grey separators between them.
 *
 * The cell is a whole number of device pixels and the grid is centred, so no cell edge lands on a
 * fraction of a pixel and the drawing is never resampled.
 *
 * @param touchedCell the cell under the animation's touch, or -1 for none.
 * @param pressProgress how much of the long press has been recognised, 0..1: the blurred grey touch
 *   circle expands as the finger holds, and stays at its full size while it follows the drag from
 *   cell to cell.
 * @param maxPressRadius the circle's radius, in cells, once [pressProgress] reaches 1. A drag's
 *   circle grows to the full touch size; a tapped pixel's grows to a slightly smaller one.
 * @param highlightColumn the column of the pixel a 3x3 window is centred on, or -1 for none.
 * @param highlightRow the row of that pixel, or -1 for none. The window stays bright while the rest
 *   of the grid is dimmed, which is what the extraction page's animation moves across the drawing.
 */
@Composable
private fun PixelCells(
    pixels: IntArray,
    columns: Int,
    rows: Int,
    modifier: Modifier,
    touchedCell: Int = -1,
    pressProgress: Float = 1f,
    maxPressRadius: Float = TOUCH_PRESSED_RADIUS_CELLS,
    highlightColumn: Int = -1,
    highlightRow: Int = -1,
) {
  Canvas(modifier) {
    // A centred window needs a cell of margin around the grid for the part that reads across an
    // edge; with
    // no window the grid fills the canvas exactly as it always has.
    val hasWindow = highlightColumn in 0 until columns && highlightRow in 0 until rows
    val margin = if (hasWindow) 1 else 0
    val cell =
        floor(
                minOf(
                    size.width / (columns + 2f * margin),
                    size.height / (rows + 2f * margin),
                ),
            )
            .coerceAtLeast(1f)
    val gridWidth = cell * columns
    val gridHeight = cell * rows
    val originX = floor((size.width - gridWidth) / 2f)
    val originY = floor((size.height - gridHeight) / 2f)
    for (row in 0 until rows) {
      for (column in 0 until columns) {
        drawRect(
            color = Color(pixels[row * columns + column]),
            topLeft = Offset(originX + column * cell, originY + row * cell),
            size = Size(cell, cell),
        )
      }
    }
    for (column in 0..columns) {
      val x = originX + column * cell
      drawLine(
          color = GRID_LINE_COLOR,
          start = Offset(x, originY),
          end = Offset(x, originY + gridHeight),
          strokeWidth = 1f,
      )
    }
    for (row in 0..rows) {
      val y = originY + row * cell
      drawLine(
          color = GRID_LINE_COLOR,
          start = Offset(originX, y),
          end = Offset(originX + gridWidth, y),
          strokeWidth = 1f,
      )
    }
    if (hasWindow) {
      // The window is centred on the highlighted pixel, so its top-left is one cell up and left of
      // that
      // pixel. On a border pixel it therefore hangs off the drawing and reads the opposite edge;
      // those nine
      // cells are exactly what the tile beside the drawing shows.
      val left = originX + (highlightColumn - 1) * cell
      val top = originY + (highlightRow - 1) * cell
      val windowSize = cell * 3
      // Dim the padded grid, then redraw the window's cells over it, so the window stays bright
      // whether it
      // sits inside the drawing or astride an edge.
      val dim = Color.Black.copy(alpha = 0.45f)
      drawRect(
          dim,
          topLeft = Offset(originX - cell, originY - cell),
          size = Size((columns + 2) * cell, (rows + 2) * cell),
      )
      for (dy in -1..1) {
        val wrappedY = Math.floorMod(highlightRow + dy, rows)
        for (dx in -1..1) {
          val wrappedX = Math.floorMod(highlightColumn + dx, columns)
          drawRect(
              color = Color(pixels[wrappedY * columns + wrappedX]),
              topLeft =
                  Offset(
                      originX + (highlightColumn + dx) * cell,
                      originY + (highlightRow + dy) * cell,
                  ),
              size = Size(cell, cell),
          )
        }
      }
      // The window's own separators, back on top of the redrawn cells.
      for (i in 0..3) {
        val x = left + i * cell
        val y = top + i * cell
        drawLine(GRID_LINE_COLOR, Offset(x, top), Offset(x, top + windowSize), strokeWidth = 1f)
        drawLine(GRID_LINE_COLOR, Offset(left, y), Offset(left + windowSize, y), strokeWidth = 1f)
      }
      // A black stroke under a white one: whichever cell the frame crosses, one of the two
      // contrasts with it.
      drawRect(
          color = Color.Black,
          topLeft = Offset(left, top),
          size = Size(windowSize, windowSize),
          style = Stroke(width = 3.dp.toPx()),
      )
      drawRect(
          color = Color.White,
          topLeft = Offset(left, top),
          size = Size(windowSize, windowSize),
          style = Stroke(width = 1.5.dp.toPx()),
      )
    }
    if (touchedCell in 0 until columns * rows) {
      drawTouchCircle(
          center =
              Offset(
                  originX + (touchedCell % columns) * cell + cell / 2f,
                  originY + (touchedCell / columns) * cell + cell / 2f,
              ),
          cell = cell,
          pressProgress = pressProgress,
          maxRadiusCells = maxPressRadius,
      )
    }
  }
}

/**
 * Draws the touch indicator centred on [center]: the drop shadow, the soft translucent fill with its
 * opaque border, and the press-expanded radius. Both the drawing page and the tools page use this, so
 * the gesture looks the same wherever it is illustrated.
 */
private fun DrawScope.drawTouchCircle(
    center: Offset,
    cell: Float,
    pressProgress: Float,
    maxRadiusCells: Float = TOUCH_PRESSED_RADIUS_CELLS,
    alpha: Float = 1f,
) {
  val a = alpha.coerceIn(0f, 1f)
  if (a <= 0f) return
  val progress = pressProgress.coerceIn(0f, 1f)
  // The long press expands the circle; easing the last of it makes the growth settle instead of stopping
  // dead. How big it ends up is the caller's: a drag grows to the full touch size, a tap to a bit less.
  val expanded = progress * (2f - progress)
  val grown = maxRadiusCells.coerceAtLeast(TOUCH_START_RADIUS_CELLS)
  val radius = cell * (TOUCH_START_RADIUS_CELLS + (grown - TOUCH_START_RADIUS_CELLS) * expanded)

  // Outer shading: a wide ambient shadow behind a tighter key shadow, both cast below the circle. Two
  // gradients give the soft, even falloff a design slide uses; one hard disc would read as another outline.
  val ambientCenter = Offset(center.x, center.y + radius * 0.40f)
  drawCircle(
      brush =
          Brush.radialGradient(
              colors =
                  listOf(
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0.20f * a),
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0.08f * a),
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0f),
                  ),
              center = ambientCenter,
              radius = radius * 1.65f,
          ),
      radius = radius * 1.65f,
      center = ambientCenter,
  )
  val keyCenter = Offset(center.x, center.y + radius * 0.24f)
  drawCircle(
      brush =
          Brush.radialGradient(
              colors =
                  listOf(
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0.38f * a),
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0.14f * a),
                      SAMPLE_TOUCH_SHADOW.copy(alpha = 0f),
                  ),
              center = keyCenter,
              radius = radius * 1.25f,
          ),
      radius = radius * 1.25f,
      center = keyCenter,
  )

  // Inner fill: one flat translucent grey with a feathered edge, so the circle looks out of focus rather
  // than lit from a point. There is no centre highlight; the only gradient is the blur at the rim.
  val blurRadius = radius * 1.20f
  val fill = SAMPLE_TOUCH_FILL.copy(alpha = SAMPLE_TOUCH_FILL.alpha * a)
  drawCircle(
      brush =
          Brush.radialGradient(
              0f to fill,
              0.62f to fill,
              1f to fill.copy(alpha = 0.5f * a),
              center = center,
              radius = blurRadius,
          ),
      radius = blurRadius,
      center = center,
  )

  // The actual circle: a crisp border around the fill and its feathered rim, so the blur stays readable
  // as a disc instead of a smudge.
  drawCircle(
      color = SAMPLE_TOUCH_OUTLINE.copy(alpha = a),
      radius = blurRadius,
      center = center,
      style = Stroke(width = 1.25.dp.toPx()),
  )
}

/**
 * Previews of the long-press touch circle, one pane per stage of the press.
 *
 * These are the parts of [PixelCells] that are hard to judge while the first page's animation is
 * running: the two shadow gradients, the feathered fill and the expanded radius. [PixelCells]'s
 * `pressProgress` is pinned instead of animated, so the start, the middle and the end of the press
 * are all in one pane.
 */
@Preview(
    name = "Touch circle",
    widthDp = 544,
    heightDp = 236,
    backgroundColor = 0xFF000000,
    showBackground = true,
)
@Composable
fun TouchCirclePreview() {
  WFCBitmapTheme {
    Row(
        modifier = Modifier.padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
      TouchCircleStage(label = "Press starts", pressProgress = 0f)
      TouchCircleStage(label = "Half held", pressProgress = 0.5f)
      TouchCircleStage(label = "Held", pressProgress = 1f)
    }
  }
}

/**
 * One pane of [TouchCirclePreview]: the tour's drawing with the circle at [pressProgress] on one
 * cell.
 */
@Composable
private fun TouchCircleStage(label: String, pressProgress: Float) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    PixelCells(
        pixels = SAMPLE_DRAWING,
        columns = SAMPLE_DRAWING_COLUMNS,
        rows = SAMPLE_DRAWING_ROWS,
        modifier = Modifier.width(160.dp).aspectRatio(SAMPLE_DRAWING_RATIO),
        touchedCell = TOUCH_PREVIEW_CELL,
        pressProgress = pressProgress,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = label,
        color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
        fontSize = 10.sp,
    )
  }
}

/**
 * The generated picture, drawn as the bitmap it is: no separators, and whole-pixel scaling when it
 * grows, so a cell is not smeared into its neighbour.
 */
@Composable
private fun PixelBitmap(image: ImageBitmap?, modifier: Modifier) {
  Canvas(modifier) {
    val picture = image ?: return@Canvas
    val fit = minOf(size.width / picture.width, size.height / picture.height)
    // A picture larger than its box has to be resampled down; one that fits is grown by whole
    // pixels.
    val scale = if (fit >= 1f) floor(fit) else fit
    val width = (picture.width * scale).toInt().coerceAtLeast(1)
    val height = (picture.height * scale).toInt().coerceAtLeast(1)
    drawImage(
        image = picture,
        dstOffset =
            IntOffset(
                floor((size.width - width) / 2f).toInt(),
                floor((size.height - height) / 2f).toInt(),
            ),
        dstSize = IntSize(width, height),
        filterQuality = FilterQuality.None,
    )
  }
}

/** One tool in the tour's toolbar: the app's own icon and the sentence a long press puts up. */
private class TutorialTool(val icon: ImageVector, val explanation: String)

/**
 * Every tool in the editor, in the order the toolbar lists them, with the sentence the app shows when
 * it is held. The tools page walks this list from the first tool to the last, then starts again.
 */
private val TUTORIAL_TOOLS = listOf(
  TutorialTool(
    Icons.Default.Colorize,
    "Takes the colour under your finger and makes it the one you draw with.",
  ),
  TutorialTool(
    Icons.Default.FormatColorFill,
    "Paints the whole joined area of the tapped colour, up to anything drawn in another colour.",
  ),
  TutorialTool(
    Icons.Default.FindReplace,
    "Swaps the tapped colour for the selected one everywhere on the drawing.",
  ),
  TutorialTool(
    Icons.Default.Timeline,
    "Drag from one cell to another; the line between the two points is painted.",
  ),
  TutorialTool(
    Icons.Default.CropSquare,
    "Drag out a box; its outline is painted and the cells inside it are left alone.",
  ),
  TutorialTool(
    Icons.Default.RadioButtonUnchecked,
    "Drag out a box; an ellipse filling it is painted as an outline, not a fill.",
  ),
  TutorialTool(
    Icons.Default.ChangeHistory,
    "Drag out a box; a triangle standing on its bottom edge is painted as an outline.",
  ),
  TutorialTool(
    Icons.Default.CropFree,
    "Drag out a rectangle to select it, then drag inside it to move what it holds.",
  ),
  TutorialTool(
    Icons.Default.ContentCopy,
    "Drag out an area to copy it, then drag the copy where it belongs. The original stays where it is.",
  ),
  TutorialTool(Icons.AutoMirrored.Filled.Undo, "Takes back the last edit, however many cells it touched."),
  TutorialTool(Icons.AutoMirrored.Filled.Redo, "Puts back the edit you just took back."),
  TutorialTool(
    Icons.Default.Flip,
    "Flips the drawing horizontally, like a mirror held to one side.",
  ),
  TutorialTool(
    Icons.Default.Flip,
    "Flips the drawing vertically, like a mirror laid underneath.",
  ),
  TutorialTool(
    Icons.AutoMirrored.Filled.RotateRight,
    "Turns the whole drawing a quarter turn clockwise, which swaps its width and height.",
  ),
  TutorialTool(
    Icons.Default.Crop,
    "Trims the empty background around the drawing, leaving one cell of it, which is what the " +
      "pattern needs to wrap around the edges.",
  ),
  TutorialTool(Icons.Default.Palette, "Chooses the color palette to draw with."),
  TutorialTool(
    Icons.Default.AspectRatio,
    "Chooses the shape of the picture and the resolution it is generated and previewed at.",
  ),
  TutorialTool(
    Icons.Default.GridOn,
    "Groups the drawing into cells for the 3x3 kernel: 3x3 means one pixel per cell, 6x6 means " +
      "2x2 cells, up to 24x24 means 8x8 cells.",
  ),
  TutorialTool(
    Icons.Default.AutoFixHigh,
    "Chooses how a saved file is enlarged -- square blocks, EPX or Scale3x -- and the multiplier " +
      "it is saved at.",
  ),
  TutorialTool(
    Icons.Default.Repeat,
    "On: the drawing's edges are not edges, so the pattern wraps around them. Off: the drawing's " +
      "border is the picture's border. It lights up on its own when the picture could not keep that border.",
  ),
  TutorialTool(
    Icons.Default.SwapHoriz,
    "On: every 3x3 pattern is also available flipped left to right, so there are more patterns to " +
      "arrange. Off: only the patterns exactly as drawn.",
  ),
  TutorialTool(
    Icons.Default.SwapVert,
    "On: every 3x3 pattern is also available flipped top to bottom. Off: only the patterns exactly " +
      "as drawn. With the left-to-right mirror on as well, the half turn comes too.",
  ),
  TutorialTool(
    Icons.Default.Rotate90DegreesCw,
    "On: every 3x3 pattern is also available turned a quarter turn clockwise, so the picture can " +
      "use shapes lying on their side -- something neither mirror can offer. With the mirrors on as " +
      "well, every window has all eight orientations.",
  ),
  TutorialTool(
    Icons.Default.PhotoLibrary,
    "Opens the app's pattern picker over the gallery and loads the drawing, palette and settings " +
      "from the selected pattern.",
  ),
  TutorialTool(
    Icons.Default.PhotoCamera,
    "Takes a photo, reduces it to a small grid, posterises it to the selected palette, dithers it, " +
      "and uses that as the drawing to generate from. You can adjust each step before applying it.",
  ),
  TutorialTool(
    Icons.Default.Movie,
    "Generates a wide picture and pans across it left to right, looping back to the start. Saved as " +
      "an animated GIF or an MP4; the loop length and frame rate are adjustable.",
  ),
  TutorialTool(
    Icons.Default.Save,
    "Writes the drawing, its palette and its generation settings into the gallery as a PNG with an " +
      "extensible JSON description. This is the single save for the app.",
  ),
  TutorialTool(
    Icons.Default.Share,
    "Hands the picture to another app, like a message or a photo editor.",
  ),
  TutorialTool(
    Icons.Default.Delete,
    "Empties the drawing back to one colour. The size of the grid is kept.",
  ),
  TutorialTool(
    Icons.Default.Settings,
    "Adjusts the size of the tool and colour buttons.",
  ),
  TutorialTool(
    Icons.Default.Info,
    "Explains what the picture is, what the sizes and the edge settings change, and where saved " +
      "files go.",
  ),
  TutorialTool(
    Icons.Default.PowerSettingsNew,
    "Closes the app. The drawing is kept and comes back next time; Save writes it out as a picture.",
  ),
)

/** Pause on the empty toolbar when the page is entered, before the first touch appears. */
private const val TOOLS_INITIAL_DELAY_MILLIS = 800L

/** How long the snackbar's exit animation has to finish before the next tool is pressed. */
private const val TOOL_HIDE_MILLIS = 300L

/** How long the touch circle takes to fade out once the snackbar is up. */
private const val TOUCH_FADE_MILLIS = 220L
private const val TOUCH_FADE_STEPS = 8

/** Reading time for an explanation: a base plus a share per character, clamped to a useful range. */
private fun toolReadingMillis(explanation: String): Long =
  (1_200L + explanation.length * 40L).coerceIn(2_000L, 7_000L)

/**
 * The tools page's animation: the full toolbar, walking from the first tool to the last. The touch
 * circle expands on the current button, the snackbar slides in with that tool's own explanation, and the
 * circle fades out as soon as the snackbar is up. The snackbar stays for a time proportional to the
 * length of the sentence before the circle moves on.
 */
@Composable
private fun ToolsTutorialArt(modifier: Modifier, active: Boolean) {
  var current by remember { mutableIntStateOf(0) }
  var snackbarVisible by remember { mutableStateOf(false) }
  var pressProgress by remember { mutableFloatStateOf(0f) }
  var touchAlpha by remember { mutableFloatStateOf(1f) }
  LaunchedEffect(active) {
    if (!active) return@LaunchedEffect
    // Start on the first tool every time the page is entered, not from wherever the animation was.
    current = 0
    snackbarVisible = false
    pressProgress = 0f
    // Hold the bare toolbar for a moment before the first touch, so the page has settled first.
    touchAlpha = 0f
    delay(TOOLS_INITIAL_DELAY_MILLIS.milliseconds)
    while (isActive) {
      for (index in TUTORIAL_TOOLS.indices) {
        current = index
        snackbarVisible = false
        pressProgress = 0f
        touchAlpha = 1f
        // The same press-then-hold the first page uses: the circle grows while the tool is held.
        delay(DRAWING_PRESS_MILLIS.milliseconds)
        for (step in 1..DRAWING_LONG_PRESS_STEPS) {
          pressProgress = step / DRAWING_LONG_PRESS_STEPS.toFloat()
          delay((DRAWING_LONG_PRESS_MILLIS / DRAWING_LONG_PRESS_STEPS).milliseconds)
        }
        snackbarVisible = true
        // The snackbar is the feedback now: let the touch circle fade out under it.
        for (step in 1..TOUCH_FADE_STEPS) {
          touchAlpha = 1f - step / TOUCH_FADE_STEPS.toFloat()
          delay((TOUCH_FADE_MILLIS / TOUCH_FADE_STEPS).milliseconds)
        }
        touchAlpha = 0f
        delay((toolReadingMillis(TUTORIAL_TOOLS[index].explanation) - TOUCH_FADE_MILLIS).coerceAtLeast(0L).milliseconds)
        snackbarVisible = false
        delay(TOOL_HIDE_MILLIS.milliseconds)
      }
    }
  }
  Column(
    modifier = modifier.padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
      // Landscape has more width than height, so the toolbar uses twice the columns and half the rows;
      // the buttons stay legible instead of shrinking to fit eight rows.
      val columns = if (maxWidth > maxHeight) 8 else 4
      val rows = (TUTORIAL_TOOLS.size + columns - 1) / columns
      // The circle grows to the full touch size, so the toolbar keeps a margin for its blur all round.
      val button =
          minOf(
              maxWidth / (columns + 2.2f),
              maxHeight / (rows + 2.2f),
              44.dp,
          )
      val margin = button * 1.1f
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ToolsGrid(
          button = button,
          margin = margin,
          current = current,
          pressProgress = pressProgress,
          touchAlpha = touchAlpha,
          columns = columns,
        )
      }
    }
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(96.dp),
      contentAlignment = Alignment.BottomCenter,
    ) {
      ToolSnackbar(
        visible = snackbarVisible,
        message = TUTORIAL_TOOLS[current].explanation,
      )
    }
  }
}

/** The snackbar that slides up over the toolbar while a tool is held. */
@Composable
private fun ToolSnackbar(visible: Boolean, message: String) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
  ) {
    Snackbar(
      containerColor = PANEL_BACKGROUND,
      contentColor = Color.White,
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(4.dp)),
    ) {
      Text(
        text = message,
        color = Color.White,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The full toolbar, with the touch circle drawn over the current tool. */
@Composable
private fun ToolsGrid(
    button: Dp,
    margin: Dp,
    current: Int,
    pressProgress: Float,
    touchAlpha: Float,
    columns: Int,
) {
  val rows = (TUTORIAL_TOOLS.size + columns - 1) / columns
  Box(
    modifier = Modifier
      .width(button * columns.toFloat() + margin * 2f)
      .height(button * rows.toFloat() + margin * 2f),
  ) {
    Column(modifier = Modifier.offset(x = margin, y = margin)) {
      TUTORIAL_TOOLS.chunked(columns).forEach { rowTools ->
        Row {
          rowTools.forEach { tool ->
            ToolButton(icon = tool.icon, size = button)
          }
        }
      }
    }
    Canvas(modifier = Modifier.matchParentSize()) {
      if (current in TUTORIAL_TOOLS.indices) {
        val buttonPx = button.toPx()
        val marginPx = margin.toPx()
        drawTouchCircle(
          center = Offset(
            marginPx + (current % columns) * buttonPx + buttonPx / 2f,
            marginPx + (current / columns) * buttonPx + buttonPx / 2f,
          ),
          cell = buttonPx,
          pressProgress = pressProgress,
          alpha = touchAlpha,
        )
      }
    }
  }
}

/** One tool button, styled like the editor's: square, thin border, white glyph, black translucent fill. */
@Composable
private fun ToolButton(icon: ImageVector, size: Dp) {
  val shape = RoundedCornerShape(2.dp)
  Box(
    modifier = Modifier
      .size(size)
      .clip(shape)
      .background(Color.Black.copy(alpha = 0.4f))
      .border(0.5.dp, Color.White.copy(alpha = 0.2f), shape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.9f),
      modifier = Modifier.size(size * (20f / 38f)),
    )
  }
}
private enum class TutorialArtKind {
  PatternResult,
  Draw,
  Extract,
  Tools,
}

private class TutorialPage(
    val title: String,
    val body: String,
    val art: TutorialArtKind,
)

private val TUTORIAL_PAGES =
    listOf(
        TutorialPage(
            title = "Start by drawing some pixels",
            body = "The patterns will be used to generate images.",
            art = TutorialArtKind.Draw,
        ),
        TutorialPage(
            title = "Patterns are extracted",
            body =
                "The drawing is read by centring a 3×3 window on each pixel in turn. " +
                    "This creates a dictionary of which patterns can be used during generation." +
                    "\n\nA pattern that occurs often in the drawing is more likely to appear in the result, so the shapes " +
                    "you repeat are the ones the picture leans towards.",
            art = TutorialArtKind.Extract,
        ),
        TutorialPage(
            title = "Countless variations can be generated",
            body =
                "The pattern dictionary, statistics and a random number generator are used to generate images.\n\n" +
                    "The wave function collapsed algorithm does the magic by assembling an image that is stochastically based on your drawing.\n\n" +
                    "The algorithm is written in C and is highly optimized to provide the best user experience.",
            art = TutorialArtKind.PatternResult,
        ),
        TutorialPage(
            title = "Long press any tool to learn about it",
            body = "Every tool explains itself so you can learn as you go.",
            art = TutorialArtKind.Tools,
        ),
    )

/**
 * The 8x9 drawing the tour uses, the one it has always shown.
 *
 * It is the tour's own illustration: the app does not copy the editor's grid into the tour. The
 * first page animates painting it, and the next page shows it finished beside the picture generated
 * from it, so the tour always describes the same drawing.
 */
private const val SAMPLE_DRAWING_COLUMNS = 8
private const val SAMPLE_DRAWING_ROWS = 9
private const val SAMPLE_DRAWING_RATIO = SAMPLE_DRAWING_COLUMNS.toFloat() / SAMPLE_DRAWING_ROWS

/**
 * The window centres an extraction pass visits: every pixel of the drawing, edges included. A
 * window centred on a border pixel reads across the relaxed edge, so it is a complete window too.
 * The scan keeps one pace through the rows, and rests a beat after the last window before starting
 * again.
 */
private const val EXTRACT_COLUMNS = SAMPLE_DRAWING_COLUMNS
private const val EXTRACT_ROWS = SAMPLE_DRAWING_ROWS
private const val EXTRACT_STEP_MILLIS = 200L
private const val EXTRACT_HOLD_MILLIS = 1_200L

/**
 * A paper cell inside the drawing's frame: the shadows have paper to fall on and the grid lines
 * still show.
 */
private const val TOUCH_PREVIEW_CELL = 4 * SAMPLE_DRAWING_COLUMNS + 4

private val SAMPLE_DRAWING_TEXT =
    listOf(
        ".KKKKKKK",
        ".K.K.K.K",
        ".K.K.K.K",
        ".K.K.KKK",
        ".K.K...K",
        ".K.KKKKK",
        ".K.....K",
        ".KKKKKKK",
        "........",
    )

/** The colour a cell starts as, before the first page's animation paints it. */
private val SAMPLE_PAPER = 0xFFFCFCFC.toInt()

/** [SAMPLE_DRAWING_TEXT] as ARGB pixels, in the page's black-and-white sample colours. */
private val SAMPLE_DRAWING =
    IntArray(SAMPLE_DRAWING_COLUMNS * SAMPLE_DRAWING_ROWS) { index ->
      val filled =
          SAMPLE_DRAWING_TEXT[index / SAMPLE_DRAWING_COLUMNS].get(index % SAMPLE_DRAWING_COLUMNS) ==
              'K'
      if (filled) 0xFF000000.toInt() else SAMPLE_PAPER
    }

/**
 * The touch indicator's fill, border and shadow. The fill is one flat translucent grey whose only
 * gradient is the feathered rim: no centre highlight, just a blurry disc. The border is opaque so
 * the blurred fill still reads as a circle, and the shadow fades to transparent so nothing outside
 * the border is opaque.
 */
private val SAMPLE_TOUCH_FILL = Color(0xFFC4C4C4).copy(alpha = 0.65f)
private val SAMPLE_TOUCH_OUTLINE = Color(0xFF8A8A8A)
private val SAMPLE_TOUCH_SHADOW = Color.Black

/**
 * The touch circle's radius, in cells: the small circle a press starts at, the size a drag's long
 * press grows to, and the slightly smaller size a tapped pixel's circle grows to, so the two
 * gestures stay distinct.
 */
private const val TOUCH_START_RADIUS_CELLS = 0.55f
private const val TOUCH_PRESSED_RADIUS_CELLS = 0.90f
private const val TOUCH_TAPPED_RADIUS_CELLS = 0.80f

/**
 * One stroke of the drawing animation: the cells in the order a hand would move through them, how
 * long the drag rests between one cell and the next, and whether the hand draws the cells as a drag
 * or as separate taps.
 */
private class DrawingStroke(
    val cells: IntArray,
    val stepMillis: Long,
    val taps: Boolean = false,
)

/**
 * The sample drawn the way a person would draw it: the frame in one clockwise drag, the left inner
 * bar as an L-shaped drag, and the last group as one tap per cell. [SAMPLE_DRAWING_TOUCHES] is
 * these strokes flattened, so the animation's painted-cell count can index it directly.
 */
private val SAMPLE_DRAWING_STROKES: List<DrawingStroke> by lazy {
  fun cell(column: Int, row: Int): Int = row * SAMPLE_DRAWING_COLUMNS + column

  // The 7x8 frame, clockwise from the cell after the top-left corner: top, right, bottom, left.
  val frame =
      ((1..7).map { cell(it, 0) } +
              (1..7).map { cell(7, it) } +
              (6 downTo 1).map { cell(it, 7) } +
              (6 downTo 1).map { cell(1, it) })
          .toIntArray()

  // The left inner divider, then the bar at the bottom of it, which runs into the frame's right
  // side.
  val leftDetail = ((1..5).map { cell(3, it) } + (4..6).map { cell(it, 5) }).toIntArray()

  // The right inner divider, then its short bar, which also meets the frame's right side.
  val rightDetail = ((1..3).map { cell(5, it) } + listOf(cell(6, 3))).toIntArray()

  listOf(
      // The outline is a quick, confident drag, and the left detail is dragged more slowly. The
      // last group is
      // tapped cell by cell rather than dragged, which is how the page shows both ways of painting.
      DrawingStroke(frame, stepMillis = 45L),
      DrawingStroke(leftDetail, stepMillis = 70L),
      DrawingStroke(rightDetail, stepMillis = 70L, taps = true),
  )
}

/** Every painted cell in stroke order; the animation's painted count indexes this. */
private val SAMPLE_DRAWING_TOUCHES: IntArray by lazy {
  SAMPLE_DRAWING_STROKES.flatMap { it.cells.toList() }.toIntArray()
}
