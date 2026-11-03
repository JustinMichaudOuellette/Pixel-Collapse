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
import androidx.compose.ui.test.junit4.createComposeRule
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
 * A save says what it did over the drawing, with a way to go and look at the file.
 *
 * A toast cannot do this. It is gone in two seconds, it cannot be acted on, and a picture has just been
 * written into the gallery where the person who pressed Save cannot see it -- so the line stays longer, and
 * carries a View button that opens the very row that was written.
 */
@RunWith(AndroidJUnit4::class)
class SaveSnackbarTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "SaveSnackbarTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theSaveLineHasAViewButtonThatRunsItsAction() {
    var viewed = 0
    rule.setContent {
      var message by remember {
        mutableStateOf<EditorMessage?>(
          EditorMessage(
            text = "Saved to the gallery",
            actionLabel = "View",
            onAction = { viewed++ },
          ),
        )
      }
      PixelEditorScreen(
        message = message,
        onMessageShown = { message = null },
      )
    }
    rule.waitForIdle()

    rule.onNodeWithText("Saved to the gallery").assertExists("the save has to say what it did")
    rule.onNodeWithText("View").assertExists("and offer a way to see it")
    rule.onNodeWithText("View").performClick()
    rule.waitForIdle()
    assertTrue("tapping View has to run the action: viewed=$viewed", viewed == 1)
  }

  @Test
  fun aFailureIsShownTheSameWayWithoutAButton() {
    // The reason is the whole of the news and there is nothing to open, so it is a line with no action --
    // still a snackbar, so a save never answers in two different kinds of transient popup.
    rule.setContent {
      PixelEditorScreen(
        message = EditorMessage("Not enough memory for that size"),
      )
    }
    rule.waitForIdle()

    rule.onNodeWithText("Not enough memory for that size").assertExists("the failure has to be shown")
    rule.onNodeWithText("View").assertDoesNotExist()
  }

  private companion object {
    const val WAKELOCK_MILLIS = 120_000L
  }
}