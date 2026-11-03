package ca.justinmo.wfcbitmap

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.ui.EditorInfoScreen
import ca.justinmo.wfcbitmap.ui.PixelEditorScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The explanation screen: it opens with its title and sections, its close button really closes it, and the
 * tool strip has a button that opens it.
 *
 * Run it on its own -- `-e class ca.justinmo.wfcbitmap.InfoScreenTest` -- like the other compose-rule tests:
 * the rule launches its own activity, and sharing the instrumentation run with the solver tests makes that
 * launch unreliable.
 */
@RunWith(AndroidJUnit4::class)
class InfoScreenTest {

  @get:Rule
  val rule = createComposeRule()

  @Test
  fun itExplainsThePictureAndCloses() {
    var closed = false
    rule.setContent {
      EditorInfoScreen(open = true, onClose = { closed = true })
    }

    rule.onNodeWithText("About Pixel Collapse").assertIsDisplayed()
    rule.onNodeWithText("The picture is not your drawing").assertIsDisplayed()
    // Section headings further down are composed but may be scrolled out of the window.
    rule.onNodeWithText("Tile grouping").assertExists()
    rule.onNodeWithText("Saving").assertExists()
    rule.onNodeWithText("The video").assertExists()

    rule.onNodeWithContentDescription("Close").performClick()
    rule.waitForIdle()
    assertTrue("the close button has to close the screen", closed)
  }

  @Test
  fun itDrawsNothingWhileClosed() {
    rule.setContent {
      EditorInfoScreen(open = false, onClose = {})
    }

    rule.onNodeWithText("About Pixel Collapse").assertDoesNotExist()
  }

  /**
   * The button sits at the bottom of a long, scrolled strip, so this is the only way to check that it is
   * reachable and wired: find it inside the strip, scroll to it, tap it, and read the title it puts up.
   */
  @Test
  fun theToolStripHasAnInfoButtonThatOpensIt() {
    rule.setContent {
      PixelEditorScreen()
    }

    rule.onAllNodes(hasScrollToNodeAction()).onFirst()
      .performScrollToNode(hasContentDescription("About this app"))
    rule.onNodeWithContentDescription("About this app").performClick()
    rule.onNodeWithText("About Pixel Collapse").assertIsDisplayed()
  }
}
