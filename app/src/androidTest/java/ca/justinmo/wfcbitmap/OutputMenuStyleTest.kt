package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The output menu is a dark panel with light labels.
 *
 * The menu names its own colours instead of leaning on the theme, so this renders it and measures the two
 * things that matter: the panel behind a label is dark, and the label itself is light.
 */
@RunWith(AndroidJUnit4::class)
class OutputMenuStyleTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "OutputMenuStyleTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theMenuIsADarkPanelWithLightLabels() {
    rule.setContent { PixelEditorScreen() }
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Output shape and size").performClick()
    rule.waitForIdle()

    val label = rule.onNodeWithText("9:16").captureToImage().toPixelMap()
    var light = 0
    var dark = 0
    for (y in 0 until label.height) {
      for (x in 0 until label.width) {
        val colour = label[x, y]
        if (colour.alpha < 0.5f) continue
        val luminance = (colour.red + colour.green + colour.blue) / 3f
        if (luminance > 0.55f) light++ else if (luminance < 0.2f) dark++
      }
    }
    println("MENUDIAG light=$light dark=$dark")
    assertTrue("the label has to be drawn light: light=$light", light > 20)
    assertTrue("on a panel that is dark: dark=$dark light=$light", dark > light)
  }

  /**
   * Every size row answers "how big" in pixels, and the preview and the save size are separate lists.
   *
   * The multiplier alone is not the answer -- the shape decides it as much as the scale does -- so the size
   * is written beside it. The bottom of the save list is the picture itself and the top is sixteen times it.
   */
  @Test
  fun theSizeListShowsThePixelsItWrites() {
    rule.setContent { PixelEditorScreen() }
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Output shape and size").performClick()
    rule.waitForIdle()

    // Two headings, because the picture on screen and the file written from it are sized separately.
    rule.onNodeWithText("Preview size").assertExists("the menu has to name the preview size")
    rule.onNodeWithText("Save size").assertExists("the menu has to name the save size")

    rule.onNodeWithText("16×").assertExists("the save size list has to show 16×")
    rule.onNodeWithText("→ 4864 x 8640").assertExists("and its pixels")
    // Nothing below 1x: a file smaller than the picture is not one of the sizes on offer.
    rule.onNodeWithText("0.25×").assertDoesNotExist()
    rule.onNodeWithText("→ 76 x 135").assertDoesNotExist()
    // The preview list is its own: the smallest preview is 1x, and its pixels say so.
    rule.onAllNodesWithText("1×").assertCountEquals(2)
    rule.onNodeWithText("→ 152 x 272").assertExists("the preview size list has to show its pixels")
    // The preview list ends at 4x, 608x1080. The save 4x is a different size, 1216x2160, and appears once.
    rule.onAllNodesWithText("→ 1216 x 2160").assertCountEquals(1)
  }

  private companion object {
    const val WAKELOCK_MILLIS = 120_000L
  }
}
