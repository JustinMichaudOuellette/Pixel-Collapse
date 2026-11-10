package ca.justinmo.wfcbitmap.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * The first-launch tour: five screens that explain what the app does before the editor asks to be used.
 *
 * A dialog over the editor rather than a separate activity: the editor underneath is already composed, so
 * closing the tour leaves the drawing and the solver exactly where they were. The first page keeps the
 * drawing the tour has always shown and generates the result beside it itself: a square, deliberately
 * coarse collapse of that drawing, made with the widest tile set the generator offers (wrapped, borderless
 * edges, both mirrors and the quarter turn) and advanced to the next seed once a second. It starts from the
 * editor's current seed so the variations continue where the picture on screen left off.
 *
 * The pages are paged rather than tabbed because this is read once; dots and a Next button are enough to
 * say where you are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TutorialScreen(
  open: Boolean,
  onDismiss: () -> Unit,
  seed: Int = SAMPLE_FIRST_SEED,
) {
  if (!open) return
  val pager = rememberPagerState(pageCount = { TUTORIAL_PAGES.size })
  val scope = rememberCoroutineScope()
  val page = pager.currentPage
  val lastPage = TUTORIAL_PAGES.lastIndex

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        TopAppBar(
          title = { Text("Introduction") },
          actions = {
            TextButton(onClick = onDismiss) {
              Text("Skip", color = MENU_TEXT_COLOR.copy(alpha = 0.75f))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = PANEL_BACKGROUND,
            titleContentColor = MENU_TEXT_COLOR,
            navigationIconContentColor = MENU_TEXT_COLOR,
            actionIconContentColor = MENU_TEXT_COLOR,
          ),
          // The surface already applies the safe-drawing insets; the app bar must not apply them again.
          windowInsets = WindowInsets(0, 0, 0, 0),
        )

        HorizontalPager(
          state = pager,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        ) { index ->
          TutorialPageView(TUTORIAL_PAGES[index], seed)
        }

        // One row for the whole footer: Back on the left, the page dots centred between the two buttons, and
        // Next on the right. A Box rather than weighted spacers because the dots belong on the screen's
        // centre line, not on the centre of whatever the two buttons leave over.
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
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
                modifier = Modifier
                  .padding(horizontal = 4.dp)
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(
                    if (index == page) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.22f),
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
            colors = ButtonDefaults.buttonColors(
              containerColor = MENU_TEXT_COLOR,
              contentColor = Color.Black,
            ),
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .height(48.dp),
          ) {
            Text(if (page == lastPage) "Start drawing" else "Next", fontSize = 14.sp)
          }
        }
      }
    }
  }
}

@Composable
private fun TutorialPageView(page: TutorialPage, seed: Int) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(20.dp))
    when (page.art) {
      // The first page animates drawing the sample one touch at a time; the next page shows that drawing
      // finished, beside the picture collapsed from it.
      TutorialArtKind.Draw -> DrawingTutorialArt(Modifier.size(200.dp))
      TutorialArtKind.PatternResult -> PatternResultArt(seed)
      else -> TutorialArt(page.art, Modifier.size(200.dp))
    }
    Spacer(Modifier.height(30.dp))
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
    Spacer(Modifier.height(24.dp))
  }
}

/**
 * How often the sample advances: one fresh collapse per second, each for the next seed.
 */
private const val SAMPLE_INTERVAL_MILLIS = 1_000L

/**
 * The drawing animation's pace. A stroke starts with a touch-down pause, then the cells follow at the stroke's
 * own drag speed, and the highlight rests on the last cell while the hand lifts.
 */
private const val DRAWING_TOUCH_DOWN_MILLIS = 140L
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
 * [WfcGenerator.generate]'s `isSessionPicture`), so it carries one of its own. It is only a bound on a
 * pathological collapse: the sample's grid is small, so it normally fits in the editor's own budget.
 */
private const val SAMPLE_SOLVE_BUDGET_MILLIS = 4_000L

/**
 * The sample's own result grid: square and coarser than the editor's preview, so the page reads as pixel art
 * rather than as a second preview. One pixel per cell; [PixelBitmap] scales it up by whole pixels.
 */
private const val SAMPLE_RESULT_COLUMNS = 32
private const val SAMPLE_RESULT_ROWS = 32
private const val SAMPLE_RESULT_RATIO = SAMPLE_RESULT_COLUMNS.toFloat() / SAMPLE_RESULT_ROWS

/** Log tag for a sample that could not be generated; the page keeps its last frame. */
private const val TAG = "TutorialScreen"

/**
 * The first page's animation: the tour's 8x9 drawing being painted the way a hand would draw it.
 *
 * The pattern is three strokes, not a cell-by-cell sweep: the frame is traced clockwise in one drag, then the
 * two inner bars are drawn as L-shaped strokes. Each stroke starts with the touch highlight resting on its
 * first cell, moves at its own pace (the outline faster than the details), and lifts before the next one, so
 * the page reads as someone drawing the test pattern rather than as a scan filling cells in.
 */
@Composable
private fun DrawingTutorialArt(modifier: Modifier) {
  var painted by remember { mutableIntStateOf(0) }
  var touched by remember { mutableIntStateOf(-1) }
  LaunchedEffect(Unit) {
    val strokes = SAMPLE_DRAWING_STROKES
    while (isActive) {
      painted = 0
      touched = -1
      delay(DRAWING_RESTART_MILLIS)
      for (stroke in strokes) {
        touched = stroke.cells.first()
        delay(DRAWING_TOUCH_DOWN_MILLIS)
        for (index in stroke.cells.indices) {
          touched = stroke.cells[index]
          painted++
          delay(
            if (index == stroke.cells.lastIndex) {
              DRAWING_TOUCH_UP_MILLIS
            } else {
              stroke.stepMillis
            },
          )
        }
        touched = -1
        delay(DRAWING_STROKE_GAP_MILLIS)
      }
      delay(DRAWING_HOLD_MILLIS)
    }
  }
  val pixels = remember(painted) {
    IntArray(SAMPLE_DRAWING_COLUMNS * SAMPLE_DRAWING_ROWS) { SAMPLE_PAPER }.also { cells ->
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
  )
}

/**
 * The result page's before/after: the tour's 8x9 drawing and a square, deliberately coarse collapse of it.
 *
 * The drawing keeps the editor's grey grid; the result is a bitmap, so it is drawn without separators. The
 * result is live: its first frame is generated with the seed the editor's picture is on, and every second the
 * sample takes the next seed and collapses the drawing again, which is the same "another arrangement of the
 * same drawing" the preview offers on a tap.
 */
@Composable
private fun PatternResultArt(seed: Int) {
  var result by remember(seed) { mutableStateOf<ImageBitmap?>(null) }
  LaunchedEffect(seed) {
    var nextSeed = seed
    while (isActive) {
      generateResult(nextSeed)?.let { result = it }
      nextSeed++
      delay(SAMPLE_INTERVAL_MILLIS)
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
    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.padding(horizontal = 4.dp))
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
 * The grid is deliberately not the editor's: [SAMPLE_RESULT_COLUMNS] x [SAMPLE_RESULT_ROWS] cells at one pixel
 * each make a small picture that reads as pixel art when the page scales it up. The page always shows the
 * widest tile set the generator offers -- the borderless (wrapped) edge rule, both mirrors and the quarter
 * turn -- and the demonstration flag keeps the solves out of the editor's budgets and caches and away from the
 * bitmap the editor is showing.
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
      // The drawing's edges are read as wrapping into each other: every window is a complete pattern, the edge
      // rule has no border left to keep, and the tile set is the dense one the page has always shown. Sampling
      // with a border and only relaxing the edge rule was what turned the result into coarse, wavy bands.
      wrapEdges = true,
      mirrorHorizontal = true,
      mirrorVertical = true,
      quarterTurn = true,
      pixelsPerCell = 1,
      cellSize = 1,
      timeoutMillis = SAMPLE_SOLVE_BUDGET_MILLIS,
      // The sample is a demonstration, not the picture the editor shows: its solves must not become the
      // cost the next full-size preview is measured against, nor overwrite the bitmap already on screen.
      isSessionPicture = false,
    ).image
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
 * The cell is a whole number of device pixels and the grid is centred, so no cell edge lands on a fraction
 * of a pixel and the drawing is never resampled.
 *
 * @param touchedCell the cell under the animation's touch, or -1 for none. It is drawn as an amber ring and a
 *   soft dot after the grid, so the touch is visible whether the cell is paper or freshly painted.
 */
@Composable
private fun PixelCells(
  pixels: IntArray,
  columns: Int,
  rows: Int,
  modifier: Modifier,
  touchedCell: Int = -1,
) {
  Canvas(modifier) {
    val cell = floor(minOf(size.width / columns, size.height / rows)).coerceAtLeast(1f)
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
    if (touchedCell in 0 until columns * rows) {
      val left = originX + (touchedCell % columns) * cell
      val top = originY + (touchedCell / columns) * cell
      drawRoundRect(
        color = SAMPLE_TOUCH_COLOR,
        topLeft = Offset(left, top),
        size = Size(cell, cell),
        cornerRadius = CornerRadius(cell * 0.18f),
        style = Stroke(width = 2.dp.toPx()),
      )
      drawCircle(
        color = SAMPLE_TOUCH_COLOR.copy(alpha = 0.4f),
        radius = cell * 0.24f,
        center = Offset(left + cell / 2f, top + cell / 2f),
      )
    }
  }
}

/**
 * The generated picture, drawn as the bitmap it is: no separators, and whole-pixel scaling when it grows, so
 * a cell is not smeared into its neighbour.
 */
@Composable
private fun PixelBitmap(image: ImageBitmap?, modifier: Modifier) {
  Canvas(modifier) {
    val picture = image ?: return@Canvas
    val fit = minOf(size.width / picture.width, size.height / picture.height)
    // A picture larger than its box has to be resampled down; one that fits is grown by whole pixels.
    val scale = if (fit >= 1f) floor(fit) else fit
    val width = (picture.width * scale).toInt().coerceAtLeast(1)
    val height = (picture.height * scale).toInt().coerceAtLeast(1)
    drawImage(
      image = picture,
      dstOffset = IntOffset(
        floor((size.width - width) / 2f).toInt(),
        floor((size.height - height) / 2f).toInt(),
      ),
      dstSize = IntSize(width, height),
      filterQuality = FilterQuality.None,
    )
  }
}

/**
 * A 9x9 pixel drawing with one page-specific accent, so the tour's later screens look like the editor rather
 * than like four slides of body copy.
 */
@Composable
private fun TutorialArt(art: TutorialArtKind, modifier: Modifier) {
  Canvas(modifier) {
    val cells = HEART.size
    val cell = size.minDimension / (cells + 4f)
    val origin = Offset(
      (size.width - cell * cells) / 2f,
      (size.height - cell * cells) / 2f,
    )
    for (row in HEART.indices) {
      for (column in HEART[row].indices) {
        val filled = HEART[row][column] == '#'
        drawRect(
          color = if (filled) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.06f),
          topLeft = origin + Offset(column * cell, row * cell) + Offset(cell * 0.08f, cell * 0.08f),
          size = Size(cell * 0.84f, cell * 0.84f),
        )
      }
    }

    when (art) {
      TutorialArtKind.Draw, TutorialArtKind.PatternResult -> Unit
      TutorialArtKind.Collapse -> {
        drawRect(
          color = MENU_TEXT_COLOR,
          topLeft = origin + Offset(cell * 3f, cell * 3f),
          size = Size(cell * 3f, cell * 3f),
          style = Stroke(width = 2.dp.toPx()),
        )
      }
      TutorialArtKind.Steer -> {
        // The mirror axis, drawn as a dashed line: the symmetry settings are the page's subject.
        var y = origin.y - cell * 0.6f
        val x = origin.x + cell * cells / 2f
        while (y < origin.y + cell * cells + cell * 0.6f) {
          drawLine(
            color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
            start = Offset(x, y),
            end = Offset(x, y + cell * 0.5f),
            strokeWidth = 1.5.dp.toPx(),
          )
          y += cell
        }
      }
      TutorialArtKind.Export -> {
        val centre = Offset(origin.x + cell * 8.2f, origin.y + cell * 8.2f)
        val radius = cell * 1.5f
        drawCircle(
          color = MENU_TEXT_COLOR,
          radius = radius,
          center = centre,
          style = Stroke(width = 2.dp.toPx()),
        )
        val play = Path().apply {
          moveTo(centre.x - radius * 0.28f, centre.y - radius * 0.5f)
          lineTo(centre.x + radius * 0.55f, centre.y)
          lineTo(centre.x - radius * 0.28f, centre.y + radius * 0.5f)
          close()
        }
        drawPath(play, MENU_TEXT_COLOR)
      }
    }
  }
}

private enum class TutorialArtKind { PatternResult, Draw, Collapse, Steer, Export }

private class TutorialPage(
  val title: String,
  val body: String,
  val art: TutorialArtKind,
)

private val TUTORIAL_PAGES = listOf(
  TutorialPage(
    title = "Draw the test pattern",
    body = "Every picture starts as a small drawing. This is the tour's test pattern: an 8×9 phone, drawn " +
      "the way it would be by hand — the frame first, then the inner bars, one touch at a time. In the " +
      "editor you paint by touching the grid; the highlight shows the cell under the touch, and each touch " +
      "paints exactly one pixel.\n\n" +
      "Only the filled cells need painting — the empty cells are the paper the drawing sits on. The " +
      "finished drawing is the 3×3 window set that the next pages collapse into a larger bitmap.",
    art = TutorialArtKind.Draw,
  ),
  TutorialPage(
    title = "Draw a bitmap, collapse a bigger one",
    body = "The test pattern above is the bitmap loaded in the editor, and the result beside it is a " +
      "much larger bitmap collapsed from its 3×3 windows. Pixel Collapse lets you draw a small bitmap, " +
      "then uses wave function collapse to generate a larger one from its patterns; every shape in the " +
      "result comes from something you drew.\n\n" +
      "Wave function collapse is a constraint solver. Each cell starts with every 3×3 window of the " +
      "drawing as a possibility. The solver picks the least-undecided cell, chooses one of its windows, " +
      "and removes from its neighbours the windows that no longer fit; when one window is left in every " +
      "cell, the bitmap is finished.\n\n" +
      "It is closely related to model synthesis: both build a new model from an example under adjacency " +
      "constraints. Model synthesis assigns labels from the example and searches with backtracking and " +
      "repair; WFC's overlapping form uses per-cell domains and entropy-based collapse. That overlapping " +
      "form is what this app runs.",
    art = TutorialArtKind.PatternResult,
  ),
  TutorialPage(
    title = "Your drawing becomes tiles",
    body = "Every 3×3 window of the drawing becomes a tile. The solver fills a larger grid with those " +
      "tiles, choosing each one so it fits the tiles already around it. The tiles are weighted by how " +
      "often they occur, so the result leans towards the patterns you use most.",
    art = TutorialArtKind.Collapse,
  ),
  TutorialPage(
    title = "Change the rules",
    body = "Tile grouping, preview size, edges and symmetry change which patterns the solver may use. " +
      "Tap the preview for another arrangement of the same drawing.",
    art = TutorialArtKind.Steer,
  ),
  TutorialPage(
    title = "Keep it or move it",
    body = "Save the picture to your gallery, load a saved pattern, or export a looping video. The " +
      "About screen explains every control in detail.",
    art = TutorialArtKind.Export,
  ),
)

private val HEART = listOf(
  ".........",
  ".##...##.",
  "####.####",
  "#########",
  "#########",
  ".#######.",
  "..#####..",
  "...###...",
  "....#....",
)

/**
 * The 8x9 drawing the tour uses, the one it has always shown.
 *
 * It is the tour's own illustration: the app does not copy the editor's grid into the tour. The first page
 * animates painting it, and the next page shows it finished beside the picture generated from it, so the tour
 * always describes the same drawing.
 */
private const val SAMPLE_DRAWING_COLUMNS = 8
private const val SAMPLE_DRAWING_ROWS = 9
private const val SAMPLE_DRAWING_RATIO = SAMPLE_DRAWING_COLUMNS.toFloat() / SAMPLE_DRAWING_ROWS

private val SAMPLE_DRAWING_TEXT = listOf(
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
private val SAMPLE_DRAWING = IntArray(SAMPLE_DRAWING_COLUMNS * SAMPLE_DRAWING_ROWS) { index ->
  val filled = SAMPLE_DRAWING_TEXT[index / SAMPLE_DRAWING_COLUMNS]
    .get(index % SAMPLE_DRAWING_COLUMNS) == 'K'
  if (filled) 0xFF000000.toInt() else SAMPLE_PAPER
}

/** The ring and dot that mark the cell under the animation's touch, on paper or on a painted cell. */
private val SAMPLE_TOUCH_COLOR = Color(0xFFF2AA4C)

/**
 * One pen stroke of the drawing animation: the cells in the order a hand would move through them, and how long
 * the drag rests between one cell and the next.
 */
private class DrawingStroke(val cells: IntArray, val stepMillis: Long)

/**
 * The sample drawn the way a person would draw it: the frame in one clockwise drag, then the two inner bars as
 * L-shaped strokes. [SAMPLE_DRAWING_TOUCHES] is these strokes flattened, so the animation's painted-cell count
 * can index it directly.
 */
private val SAMPLE_DRAWING_STROKES: List<DrawingStroke> by lazy {
  fun cell(column: Int, row: Int): Int = row * SAMPLE_DRAWING_COLUMNS + column

  // The 7x8 frame, clockwise from the cell after the top-left corner: top, right, bottom, left.
  val frame = (
    (1..7).map { cell(it, 0) } +
      (1..7).map { cell(7, it) } +
      (6 downTo 1).map { cell(it, 7) } +
      (6 downTo 1).map { cell(1, it) }
    ).toIntArray()

  // The left inner divider, then the bar at the bottom of it, which runs into the frame's right side.
  val leftDetail = (
    (1..5).map { cell(3, it) } +
      (4..6).map { cell(it, 5) }
    ).toIntArray()

  // The right inner divider, then its short bar, which also meets the frame's right side.
  val rightDetail = (
    (1..3).map { cell(5, it) } +
      listOf(cell(6, 3))
    ).toIntArray()

  listOf(
    // The outline is a quick, confident drag; the details are drawn more slowly.
    DrawingStroke(frame, stepMillis = 45L),
    DrawingStroke(leftDetail, stepMillis = 70L),
    DrawingStroke(rightDetail, stepMillis = 70L),
  )
}

/** Every painted cell in stroke order; the animation's painted count indexes this. */
private val SAMPLE_DRAWING_TOUCHES: IntArray by lazy {
  SAMPLE_DRAWING_STROKES.flatMap { it.cells.toList() }.toIntArray()
}
