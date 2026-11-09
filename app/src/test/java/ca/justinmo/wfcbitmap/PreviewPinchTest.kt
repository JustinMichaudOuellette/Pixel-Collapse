package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.ui.MAX_PREVIEW_ZOOM
import ca.justinmo.wfcbitmap.ui.MIN_PREVIEW_ZOOM
import ca.justinmo.wfcbitmap.ui.PINCH_GAIN
import ca.justinmo.wfcbitmap.ui.pinchGain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far a pinch takes the preview.
 *
 * The gesture is applied more strongly than the fingers move, so the full range is one comfortable pinch
 * instead of a pinch, a let-go and another pinch. These tests are written against [PINCH_GAIN] rather
 * than a number, so the gain can be tuned without them turning into arithmetic about the old one.
 */
class PreviewPinchTest {

  @Test
  fun noMovementIsNoChange() {
    // At rest the gesture reports 1, and 1 has to stay 1: amplifying the factor rather than its change
    // would jump the picture bigger the instant a second finger landed.
    assertEquals(1f, pinchGain(1f), 1e-6f)
  }

  @Test
  fun aPinchIsAppliedMoreStronglyThanTheFingersMoved() {
    // The *change* is what is amplified: doubling the fingers gives one-plus-a-gain, not two.
    assertEquals(1f + PINCH_GAIN, pinchGain(2f), 1e-6f)
    assertEquals(1f + 2f * PINCH_GAIN, pinchGain(3f), 1e-6f)
    assertTrue("a spread of two is bigger than two", pinchGain(2f) > 2f)
    // Pinching in is stronger in the same way: the change is amplified in both directions.
    assertEquals(1f - 0.3f * PINCH_GAIN, pinchGain(0.7f), 1e-6f)
  }

  @Test
  fun onePinchReachesTheFullZoom() {
    // The gesture factor that gets to the ceiling: whatever the gain, it has to be less than the range
    // itself, or a single pinch could not reach the top.
    val needed = 1f + (MAX_PREVIEW_ZOOM - 1f) / PINCH_GAIN
    assertTrue("a pinch of $needed should be inside what two thumbs can do", needed < MAX_PREVIEW_ZOOM)
    val zoomed = (MIN_PREVIEW_ZOOM * pinchGain(needed)).coerceIn(MIN_PREVIEW_ZOOM, MAX_PREVIEW_ZOOM)
    assertEquals(MAX_PREVIEW_ZOOM, zoomed, 1e-4f)
  }

  @Test
  fun theZoomStillStopsAtTheTopAndTheBottom() {
    assertEquals(
      MAX_PREVIEW_ZOOM,
      (MIN_PREVIEW_ZOOM * pinchGain(40f)).coerceIn(MIN_PREVIEW_ZOOM, MAX_PREVIEW_ZOOM),
      0f,
    )
    assertEquals(
      MIN_PREVIEW_ZOOM,
      (MIN_PREVIEW_ZOOM * pinchGain(0.1f)).coerceIn(MIN_PREVIEW_ZOOM, MAX_PREVIEW_ZOOM),
      0f,
    )
  }
}
