package ca.justinmo.wfcbitmap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.ui.TutorialScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first-launch tour: it starts on the first page, Next pages forward, and Skip closes it.
 *
 * Run it on its own like the other compose-rule tests: `-e class ca.justinmo.wfcbitmap.TutorialScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class TutorialScreenTest {

  @get:Rule
  val rule = createComposeRule()

  @Test
  fun itPagesForwardAndSkips() {
    var dismissed = false
    rule.setContent {
      TutorialScreen(open = true, onDismiss = { dismissed = true })
    }

    rule.onNodeWithText("Draw a bitmap, collapse a bigger one").assertIsDisplayed()
    rule.onNodeWithText("Next").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Your drawing becomes tiles").assertIsDisplayed()

    rule.onNodeWithText("Skip").performClick()
    rule.waitForIdle()
    assertTrue("Skip has to close the tour", dismissed)
  }

  @Test
  fun itDrawsNothingWhileClosed() {
    rule.setContent {
      TutorialScreen(open = false, onDismiss = {})
    }

    rule.onNodeWithText("Draw a bitmap, collapse a bigger one").assertDoesNotExist()
  }
}
