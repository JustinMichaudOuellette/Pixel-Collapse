package ca.justinmo.wfcbitmap

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
  fun aDebugBuildOpensAtTheFinestPreview() {
    for (stored in PreviewScale.entries) {
      assertEquals(
        "a debug build ignores the stored $stored and opens at 4x",
        PreviewScale.Two,
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
  fun theDebugDefaultIsTheLabelTheMenuShows() {
    // "4x" in the output menu, and the base cell count rather than a fraction of it.
    assertEquals("4×", PreviewScale.Two.label)
    assertTrue(PreviewScale.Two.cellCount > PreviewScale.One.cellCount)
  }
}
