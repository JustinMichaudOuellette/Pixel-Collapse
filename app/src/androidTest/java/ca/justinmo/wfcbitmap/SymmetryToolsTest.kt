package ca.justinmo.wfcbitmap

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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Three symmetry settings, three buttons, and none of them turns another on.
 *
 * They are separate settings because they are separate choices about what the picture may be made of: the
 * left-to-right flip, the top-to-bottom flip, the half turn the two make together, the quarter turns, or
 * nothing at all. A set of buttons that behaved like one switch would be telling the person drawing that they
 * had chosen something they had not.
 */
@RunWith(AndroidJUnit4::class)
class SymmetryToolsTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "SymmetryToolsTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theTwoMirrorsAreArmedIndependently() {
    rule.setContent {
      var horizontal by remember { mutableStateOf(false) }
      var vertical by remember { mutableStateOf(false) }
      PixelEditorScreen(
        mirrorHorizontal = horizontal,
        onMirrorHorizontalChange = { horizontal = it },
        mirrorVertical = vertical,
        onMirrorVerticalChange = { vertical = it },
      )
    }
    rule.waitForIdle()

    // Neither, to begin with.
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsNotSelected()
    rule.onNodeWithContentDescription(VERTICAL).assertIsNotSelected()

    // The left-to-right flip only arms its own button.
    rule.onNodeWithContentDescription(HORIZONTAL).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsSelected()
    rule.onNodeWithContentDescription(VERTICAL).assertIsNotSelected()

    // The top-to-bottom flip arms the other one, and leaves the first where it was.
    rule.onNodeWithContentDescription(VERTICAL).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsSelected()
    rule.onNodeWithContentDescription(VERTICAL).assertIsSelected()

    // And turning one off does not turn the other off.
    rule.onNodeWithContentDescription(HORIZONTAL).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsNotSelected()
    rule.onNodeWithContentDescription(VERTICAL).assertIsSelected()
  }

  @Test
  fun theQuarterTurnIsArmedIndependentlyOfTheMirrors() {
    rule.setContent {
      var horizontal by remember { mutableStateOf(false) }
      var quarterTurn by remember { mutableStateOf(false) }
      PixelEditorScreen(
        mirrorHorizontal = horizontal,
        onMirrorHorizontalChange = { horizontal = it },
        quarterTurn = quarterTurn,
        onQuarterTurnChange = { quarterTurn = it },
      )
    }
    rule.waitForIdle()

    rule.onNodeWithContentDescription(QUARTER_TURN).assertIsNotSelected()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsNotSelected()

    // The turn is a move of its own: arming it must not claim a mirror was chosen.
    rule.onNodeWithContentDescription(QUARTER_TURN).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(QUARTER_TURN).assertIsSelected()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsNotSelected()

    // And a mirror does not arm the turn either, or switch it off.
    rule.onNodeWithContentDescription(HORIZONTAL).performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription(QUARTER_TURN).assertIsSelected()
    rule.onNodeWithContentDescription(HORIZONTAL).assertIsSelected()
  }

  private companion object {
    const val HORIZONTAL = "Mirror every pattern left to right, giving the picture more arrangements"
    const val VERTICAL = "Mirror every pattern top to bottom, giving the picture more arrangements"
    const val QUARTER_TURN = "Turn every pattern a quarter turn, giving the picture more arrangements"
    const val WAKELOCK_MILLIS = 120_000L
  }
}