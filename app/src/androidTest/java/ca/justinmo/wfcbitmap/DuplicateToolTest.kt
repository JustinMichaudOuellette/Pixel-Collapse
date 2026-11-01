package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy tool as one tool with two gestures: drag out an area, then drag a copy of it.
 *
 * What makes it a copy is what is left behind. A move lifts its pixels away; this does not, so after the
 * copy is dragged away the original has to still be exactly where it was -- and the cells the copy passed
 * over have to come back, rather than being eaten on the way.
 */
@RunWith(AndroidJUnit4::class)
class DuplicateToolTest {

  @get:Rule
  val rule = createComposeRule()

  private val ink = Color(0xFF0000FF)

  /** A gesture test drives a window, and a sleeping screen has no window to find; see SelectionToolTest. */
  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "DuplicateToolTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun draggingASelectionCopiesItAndLeavesTheOriginal() {
    val grid = BitmapEditor.PixelGridState(initialColumns = GRID, initialRows = GRID)
    // A 2x2 block to copy.
    for (row in 2..3) for (col in 2..3) grid.paintOver(col, row, ink)

    val mode = mutableStateOf(BitmapEditor.EditorMode.Paint)
    val selection = mutableStateOf<BitmapEditor.CellRect?>(null)
    rule.setContent {
      val cellPx = with(LocalDensity.current) { CELL.toPx() }
      Box(Modifier.size(CELL * GRID)) {
        BitmapEditor.ZoomablePixelCanvas(
          gridState = grid,
          selectedColor = Color.White,
          mode = mode.value,
          selection = selection.value,
          baseCellSizePx = cellPx.toInt(),
          canvasTestTag = TAG,
          onSelectionChange = { selection.value = it },
          onModeConsumed = { mode.value = BitmapEditor.EditorMode.Paint },
          onTap = {},
          onResize = {},
        )
      }
    }
    rule.waitForIdle()
    rule.runOnUiThread { mode.value = BitmapEditor.EditorMode.Duplicate }
    rule.waitForIdle()

    // First gesture: drag out the area to copy.
    rule.onNodeWithTag(TAG).performTouchInput {
      swipe(start = cellCentre(2, 2), end = cellCentre(3, 3), durationMillis = 300)
    }
    rule.waitForIdle()
    assertNotNull("the drag chose an area to copy", selection.value)
    assertEquals(
      "choosing the area does not spend the tool: the next drag copies it",
      BitmapEditor.EditorMode.Duplicate,
      mode.value,
    )

    // Second gesture: drag a copy of it away.
    val from = cellCentre(2, 2)
    rule.onNodeWithTag(TAG).performTouchInput {
      down(from)
      advanceEventTime(32)
      moveTo(cellCentre(5, 5))
      advanceEventTime(32)
      up()
    }
    rule.waitForIdle()

    assertEquals("a copy landed where it was dragged", ink, grid.getPixel(5, 5))
    assertEquals("and the rest of the copy too", ink, grid.getPixel(6, 6))
    assertEquals("the original is still exactly where it was", ink, grid.getPixel(2, 2))
    assertEquals("all of it", ink, grid.getPixel(3, 3))
    assertEquals("nothing was eaten on the way", grid.defaultColor, grid.getPixel(5, 7))
    assertNull("putting the copy down releases the selection", selection.value)
    assertEquals("and spends the tool", BitmapEditor.EditorMode.Paint, mode.value)
  }

  private fun cellCentre(col: Int, row: Int): Offset = with(rule.density) {
    Offset((CELL * (col + 0.5f)).toPx(), (CELL * (row + 0.5f)).toPx())
  }

  private companion object {
    const val GRID = 8
    val CELL = 40.dp
    const val TAG = "duplicate-test-canvas"
    const val WAKELOCK_MILLIS = 120_000L
  }
}