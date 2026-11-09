package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.startupPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which preview size a build opens with.
 *
 * A debug build has to open at the finest preview so a generation problem is looked at with every cell on
 * screen; a release build has to keep whatever was last chosen. The choice is only about where the session
 * starts -- every size is still selectable in both builds.
 */
class StartupPreviewTest {

  @Test
  fun aDebugBuildOpensAtTheDefaultPreview() {
    for (stored in PreviewScale.entries) {
      assertEquals(
        "a debug build ignores the stored $stored and opens at 2x",
        PreviewScale.One,
        startupPreview(stored, debugBuild = true),
      )
    }
  }

  @Test
  fun aReleaseBuildOpensOnWhatWasChosen() {
    for (stored in PreviewScale.entries) {
      assertEquals(stored, startupPreview(stored, debugBuild = false))
    }
  }

  @Test
  fun theDefaultIsTheTwoTimesPreview() {
    assertEquals("2×", PreviewScale.One.label)
    assertEquals(2, PreviewScale.One.factor)
    assertTrue(PreviewScale.Two.factor > PreviewScale.One.factor)
  }
}