package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things the editor says when a generation is not exactly what was asked for.
 *
 * Two, because they go to two different places and mean two different things: a *picture* that lost the
 * drawing's border is a notice line under the drawing, and *no picture at all* is a message shown in the
 * preview where the picture would have been. Both have to name what to do about it, and the normal case has
 * to say nothing at all -- a permanent line under the drawing would be noise.
 */
class GenerationNoticeTest {

  // ---------------------------------------------------------------------------
  // A picture with relaxed edges
  // ---------------------------------------------------------------------------

  @Test
  fun aNormalTilingHasNothingToSay() {
    assertNull(edgeRuleNotice(relaxedEdges = false))
  }

  @Test
  fun relaxedEdgesSaysTwoWordsAndNoMore() {
    // It is a snackbar over the picture now, and the picture is what is being looked at: the news is that
    // the edges were relaxed, not an essay about why.
    assertEquals("Edges relaxed", edgeRuleNotice(relaxedEdges = true))
  }

  // ---------------------------------------------------------------------------
  // No picture at all
  // ---------------------------------------------------------------------------

  @Test
  fun aDrawingThatWillNotTileWithRealEdgesIsToldToWrapOrSimplify() {
    // Both the edge rule and the relaxed attempt failed, so the advice cannot be "keep your border": the
    // remaining things to try are wrapping the edges and making the drawing simpler.
    val message = noTilingMessage(columns = 152, rows = 270, wrapEdges = false)
    assertTrue("offers the wrap setting: $message", message.contains("Wrap edges", ignoreCase = true))
    assertTrue("offers simplifying: $message", message.contains("simplify", ignoreCase = true))
  }

  @Test
  fun aWrappedDrawingThatWillNotTileIsToldToSimplify() {
    // With wrapping on there is no border to fix, so the patterns themselves are the problem.
    val message = noTilingMessage(columns = 152, rows = 270, wrapEdges = true)
    assertTrue("blames the patterns: $message", message.contains("patterns", ignoreCase = true))
    assertTrue("suggests what to change: $message", message.contains("Simplify", ignoreCase = true))
    assertFalse(
      "must not offer a setting that is already on: $message",
      message.contains("Turn on Wrap edges", ignoreCase = true),
    )
  }
}