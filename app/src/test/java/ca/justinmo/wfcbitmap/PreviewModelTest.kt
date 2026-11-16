package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.video.PreviewCamera
import ca.justinmo.wfcbitmap.video.PreviewSource
import ca.justinmo.wfcbitmap.video.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preview camera and window mapping, independent of GL and Compose. */
class PreviewModelTest {

  private fun panorama(width: Int = 200, height: Int = 100, frameWidth: Int = 100): PreviewSource =
    PreviewSource(
      pixels = IntArray(width * height) { 0xFF102030.toInt() },
      width = width,
      height = height,
      frameWidth = frameWidth,
      frameHeight = height,
      loopLength = width,
      wrapX = true,
      wrapY = false,
      vertical = false,
      cellPixels = 2,
    )

  @Test
  fun aPinchZoomsAndClampsTheOffsets() {
    val source = panorama()
    val camera = PreviewCamera().transformed(
      zoomChange = 2f,
      panPixelsX = 10_000f,
      panPixelsY = 10_000f,
      boxWidth = 500f,
      boxHeight = 500f,
      viewportAspect = 1f,
      source = source,
    )

    assertFalse(camera.isFitted)
    assertEquals(2f, camera.zoom, 0.0001f)
    // At 2x only half the frame is visible in each axis, so the offset cannot exceed a quarter of
    // the source in either direction.
    assertTrue(camera.offsetX in -0.25f..0.25f)
    assertTrue(camera.offsetY in -0.25f..0.25f)
  }

  @Test
  fun zoomingBackToFitZeroesTheOffsets() {
    val source = panorama()
    val zoomed = PreviewCamera(zoom = 3f, offsetX = 0.2f, offsetY = -0.2f)
    val fitted = zoomed.transformed(
      zoomChange = 0.1f,
      panPixelsX = 0f,
      panPixelsY = 0f,
      boxWidth = 500f,
      boxHeight = 500f,
      viewportAspect = 1f,
      source = source,
    )

    assertTrue(fitted.isFitted)
    assertEquals(1f, fitted.zoom, 0.0001f)
    assertEquals(0f, fitted.offsetX, 0.0001f)
    assertEquals(0f, fitted.offsetY, 0.0001f)
  }

  @Test
  fun aHorizontalPanoramaWindowWrapsInX() {
    val source = panorama()
    val atStart = source.window(PreviewCamera(), loopPixels = 0f)
    val atWrap = source.window(PreviewCamera(), loopPixels = 190f)

    assertEquals(0f, atStart.uvOffsetX, 0.0001f)
    assertEquals(0.5f, atStart.uvScaleX, 0.0001f)
    assertTrue(atStart.wrapX)
    assertFalse(atStart.wrapY)
    assertEquals(0.95f, atWrap.uvOffsetX, 0.0001f)
  }

  @Test
  fun aVerticalSourceUsesTheHeightWindow() {
    val source = PreviewSource(
      pixels = IntArray(100 * 200),
      width = 100,
      height = 200,
      frameWidth = 100,
      frameHeight = 100,
      loopLength = 200,
      wrapX = false,
      wrapY = true,
      vertical = true,
      cellPixels = 2,
    )
    val window = source.window(PreviewCamera(zoom = 2f, offsetY = 0.1f), loopPixels = 120f)

    assertFalse(window.wrapX)
    assertTrue(window.wrapY)
    assertEquals(0.6f, window.uvOffsetY, 0.0001f)
    assertEquals(0.5f, window.uvScaleY, 0.0001f)
    assertEquals(2f, window.previewScale, 0.0001f)
  }
}
