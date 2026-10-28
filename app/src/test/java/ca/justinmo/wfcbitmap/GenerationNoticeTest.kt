package ca.justinmo.wfcbitmap

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line the editor shows when the preview is not a plain edge-respecting tiling.
 *
 * The point of these is that the user is told *what to do*, and only told when there is something to
 * say: a normal tiling must produce nothing at all, because the notice sits under the drawing and a
 * permanent line there would be noise.
 */
class GenerationNoticeTest {

  @Test
  fun aNormalTilingHasNothingToSay() {
    assertNull(edgeRuleNotice(relaxedEdges = false, usedFallback = false, columns = 152, rows = 270))
  }

  @Test
  fun relaxedEdgesSaysWhyAndWhatToChange() {
    val notice = edgeRuleNotice(relaxedEdges = true, usedFallback = false, columns = 152, rows = 270)!!
    assertTrue("says the edge rule was dropped: $notice", notice.contains("Edges relaxed", ignoreCase = true))
    assertTrue("names the grid it could not repeat around: $notice", notice.contains("152x270"))
    assertTrue(
      "tells the user what to change: $notice",
      notice.contains("outer row and column", ignoreCase = true),
    )
  }

  @Test
  fun theStretchedFallbackOutranksTheRelaxedOne() {
    // Both can be true when neither rule found a tiling; the more severe one is the one to show.
    val notice = edgeRuleNotice(relaxedEdges = true, usedFallback = true, columns = 152, rows = 270)!!
    assertTrue("says it is stretched: $notice", notice.contains("stretched", ignoreCase = true))
    assertTrue("still suggests an action: $notice", notice.contains("Simplify", ignoreCase = true))
  }
}
