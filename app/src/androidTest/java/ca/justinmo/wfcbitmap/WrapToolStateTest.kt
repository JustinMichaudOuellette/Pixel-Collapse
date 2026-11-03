package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.ui.PixelEditorScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.Test

/**
 * The wrap button tells the truth about the picture that is up, including when the picture relaxed its edges.
 *
 * There are two ways for the picture to end up with no border to keep: wrapping was asked for, or the edge
 * rule could not be held and the generator dropped it. The second is the one that used to go unmentioned --
 * the button stayed dark while the picture had given up on the drawing's border, and the only sign was a
 * snackbar that had already faded.
 */
@RunWith(AndroidJUnit4::class)
class WrapToolStateTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "WrapToolStateTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theButtonIsLitWhenThePictureCouldNotKeepTheBorder() {
    rule.setContent { PixelEditorScreen(edgeRuleKept = false) }
    rule.waitForIdle()

    // Wrapping is not on -- it is the picture that dropped the rule -- and the button still has to say that
    // the drawing's border is not the picture's border.
    rule.onNodeWithContentDescription(WRAP).assertIsSelected()
  }

  @Test
  fun theButtonIsDarkWhileTheBorderIsKept() {
    rule.setContent { PixelEditorScreen(edgeRuleKept = true) }
    rule.waitForIdle()

    rule.onNodeWithContentDescription(WRAP).assertIsNotSelected()
  }

  @Test
  fun tappingItWrapsAndItStaysLit() {
    rule.setContent {
      var wrap by remember { mutableStateOf(false) }
      PixelEditorScreen(
        wrapEdges = wrap,
        onWrapEdgesChange = { wrap = it },
        edgeRuleKept = true,
      )
    }
    rule.waitForIdle()

    rule.onNodeWithContentDescription(WRAP).assertIsNotSelected()
    rule.onNodeWithContentDescription(WRAP).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(WRAP).assertIsSelected()
    // And it stays on: unwrapping is a second tap, not a release, because the state is the setting.
    rule.onNodeWithContentDescription(WRAP).assertIsSelected()
  }

  private companion object {
    const val WRAP = "Wrap the pattern around the drawing's edges"
    const val WAKELOCK_MILLIS = 120_000L
  }
}