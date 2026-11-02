package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.ui.EditorMode
import ca.justinmo.wfcbitmap.ui.ZoomablePixelCanvas
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test

/**
 * The select tool driven as gestures rather than as state.
 *
 * Two things have broken here before and only a real gesture on a real canvas notices them: the tool
 * disarming after the marquee (so the next drag painted instead of moving), and the move erasing whatever
 * the block passed over (because it punched a hole where the block *had been* rather than putting back
 * what was there before the drag). Cell (c, r) is centred at ((c + 0.5) * 40dp, (r + 0.5) * 40dp).
 *
 * Run it on its own -- `-e class ca.justinmo.wfcbitmap.SelectionToolTest` -- rather than as part of the
 * whole device suite: the compose rule launches its own activity, and sharing the instrumentation run with
 * the solver tests makes that launch unreliable on a busy device, which then fails as "no compose
 * hierarchies found" rather than as anything about gestures.
 */
@RunWith(AndroidJUnit4::class)
class SelectionToolTest {

  @get:Rule
  val rule = createComposeRule()

  /**
   * Holds the screen on for the test.
   *
   * A gesture test drives a window, and a device that has gone to sleep has no visible window for the
   * compose rule to find -- which shows up as "no compose hierarchies found" rather than as anything
   * about gestures. The full suite runs long enough to hit the screen timeout, so this holds it awake.
   */
  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "SelectionToolTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  private val blockMark = Color(0xFFFF0000)
  private val underMark = Color(0xFF0000FF)

  private class Harness(
    val grid: PixelGridState,
    val mode: androidx.compose.runtime.MutableState<EditorMode>,
    val selection: androidx.compose.runtime.MutableState<CellRect?>,
  )

  private fun harness(marks: List<Triple<Int, Int, Color>>): Harness {
    val grid = PixelGridState(initialColumns = GRID, initialRows = GRID)
    for ((col, row, colour) in marks) grid.paintOver(col, row, colour)
    val mode = mutableStateOf(EditorMode.Paint)
    val selection = mutableStateOf<CellRect?>(null)
    rule.setContent {
      val cellPx = with(LocalDensity.current) { CELL.toPx() }
      Box(Modifier.size(CELL * GRID)) {
        ZoomablePixelCanvas(
          gridState = grid,
          selectedColor = Color.White,
          mode = mode.value,
          selection = selection.value,
          baseCellSizePx = cellPx.toInt(),
          canvasTestTag = TAG,
          onSelectionChange = { selection.value = it },
          onModeConsumed = { mode.value = EditorMode.Paint },
          onTap = {},
          onResize = {},
        )
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread { mode.value = EditorMode.Select }
    rule.waitForIdle()
    return Harness(grid, mode, selection)
  }

  @Test
  fun draggingASelectionMovesItAndLeavesNothingSelected() {
    val h = harness(listOf(Triple(4, 4, blockMark)))

    // Marquee over (3,3)-(5,5).
    rule.onNodeWithTag(TAG).performTouchInput {
      swipe(start = cellCentre(3, 3), end = cellCentre(5, 5), durationMillis = 400)
    }
    rule.waitForIdle()
    val drawn = h.selection.value
    assertNotNull("drawing a marquee must produce a selection", drawn)
    assertEquals(
      "drawing the marquee must not spend the tool, or the next drag paints",
      EditorMode.Select,
      h.mode.value,
    )

    // Grab the selection by its top-left cell and drag it two cells along.
    val step = cellSizePx()
    val from = cellCentre(drawn!!.left, drawn.top)
    rule.onNodeWithTag(TAG).performTouchInput {
      swipe(start = from, end = Offset(from.x + 2 * step, from.y + 2 * step), durationMillis = 400)
    }
    rule.waitForIdle()

    assertEquals("the block left its old cell", h.grid.defaultColor, h.grid.getPixel(4, 4))
    val found = cells(h.grid).filter { (col, row) -> h.grid.getPixel(col, row) == blockMark }
    assertEquals("the block must exist exactly once", 1, found.size)
    assertTrue("it must have moved: ${found.first()}", found.first() != (4 to 4))
    assertNull("the move is finished, so nothing is left selected", h.selection.value)
  }

  @Test
  fun draggingOverOtherCellsAndAwayLeavesThemIntact() {
    val h = harness(listOf(Triple(0, 0, blockMark), Triple(5, 5, underMark)))

    // Marquee over (0,0)-(2,2): the block holds the cell marked red.
    rule.onNodeWithTag(TAG).performTouchInput {
      swipe(start = cellCentre(0, 0), end = cellCentre(2, 2), durationMillis = 400)
    }
    rule.waitForIdle()
    assertNotNull("expected a selection", h.selection.value)

    // Drag the block over the blue cell and on, ending somewhere else: the cell it merely passed over has
    // to come back, not be left as a hole.
    val grab = cellCentre(0, 0)
    rule.onNodeWithTag(TAG).performTouchInput {
      down(grab)
      advanceEventTime(32)
      moveTo(cellCentre(5, 5))
      advanceEventTime(32)
      moveTo(cellCentre(0, 6))
      advanceEventTime(32)
      up()
    }
    rule.waitForIdle()

    assertEquals("the cell the block passed over must be exactly as it was", underMark, h.grid.getPixel(5, 5))
    assertEquals("and the block itself moved on", blockMark, h.grid.getPixel(0, 5))
    assertEquals("leaving its old place empty", h.grid.defaultColor, h.grid.getPixel(0, 0))
  }

  @Test
  fun draggingAwayAndBackStillReleasesTheSelection() {
    val h = harness(listOf(Triple(4, 4, blockMark)))
    rule.onNodeWithTag(TAG).performTouchInput {
      swipe(start = cellCentre(3, 3), end = cellCentre(5, 5), durationMillis = 400)
    }
    rule.waitForIdle()
    assertNotNull("expected a selection", h.selection.value)
    val depthBefore = h.grid.undoDepth

    // Wander out to the far corner and come back to where it started, then let go.
    val grab = cellCentre(3, 3)
    rule.onNodeWithTag(TAG).performTouchInput {
      down(grab)
      advanceEventTime(32)
      moveTo(cellCentre(6, 6))
      advanceEventTime(32)
      moveTo(grab)
      advanceEventTime(32)
      up()
    }
    rule.waitForIdle()

    assertEquals("the block is back where it started", blockMark, h.grid.getPixel(4, 4))
    assertNull("a finished move releases the selection even when it ends where it began", h.selection.value)
    assertEquals("and a move that changed nothing leaves no undo step", depthBefore, h.grid.undoDepth)
  }

  private fun cells(grid: PixelGridState) =
    (0 until GRID).flatMap { row -> (0 until GRID).map { col -> col to row } }

  /** Centre of a cell, in the pixels touch injection works in. */
  private fun cellCentre(col: Int, row: Int): Offset = with(rule.density) {
    Offset((CELL * (col + 0.5f)).toPx(), (CELL * (row + 0.5f)).toPx())
  }

  private fun cellSizePx(): Float = with(rule.density as Density) { CELL.toPx() }

  private companion object {
    const val GRID = 8
    val CELL = 40.dp
    const val TAG = "selection-test-canvas"
    const val WAKELOCK_MILLIS = 120_000L
  }
}
