package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.ui.PixelEditorScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test

/**
 * The generation notice belongs in a snackbar, not in a strip under the drawing.
 *
 * It used to be a line of its own below the canvas, which cost the drawing height for as long as the
 * drawing happened to bind its edges -- which is most of the time for a busy drawing. This checks it
 * reaches the screen through the snackbar the tool explanations already use.
 */
@RunWith(AndroidJUnit4::class)
class NoticeSnackbarTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "NoticeSnackbarTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theNoticeIsShownInASnackbar() {
    rule.setContent {
      PixelEditorScreen(notice = MESSAGE)
    }
    rule.waitForIdle()
    rule.onNodeWithText(MESSAGE, substring = true).assertExists("the notice has to reach the screen")
  }

  @Test
  fun noNoticeMeansNoSnackbar() {
    rule.setContent {
      PixelEditorScreen(notice = null)
    }
    rule.waitForIdle()
    assertTrue(
      "a normal tiling must say nothing at all",
      rule.onAllNodesWithText("Edges relaxed", substring = true).fetchSemanticsNodes().isEmpty(),
    )
  }

  private companion object {
    const val MESSAGE = "Edges relaxed"
    const val WAKELOCK_MILLIS = 120_000L
  }
}