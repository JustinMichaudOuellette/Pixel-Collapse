package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.video.MetalShade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The metal look: a lit relief of the drawing rather than a flat copy of it. */
class MetalShadeTest {

  private val white = 0xFFFFFFFF.toInt()
  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()

  @Test
  fun aFlatSurfaceComesBackAsOneColour() {
    // With no height difference anywhere the normals all point straight up, so the reflection -- and
    // therefore the shade -- is the same at every pixel.
    val flat = IntArray(12 * 10) { 0xFF808080.toInt() }

    val shaded = MetalShade.paint(flat, 12, 10, cellPixels = 2)

    assertTrue("every pixel is opaque", shaded.all { it ushr 24 == 0xFF })
    assertTrue("one shade across a flat plate", shaded.all { it == shaded[0] })
  }

  @Test
  fun theEdgeOfADrawnShapeIsLitDifferentlyFromItsMiddle() {
    val width = 16
    val height = 16
    // A red plate in the middle of a white one: the boundary is a step in the height field.
    val pixels = IntArray(width * height) { index ->
      val x = index % width
      val y = index / width
      if (x in 4..11 && y in 4..11) red else white
    }

    val shaded = MetalShade.paint(pixels, width, height, cellPixels = 2)

    assertNotEquals("the edge catches a different reflection", shaded[8 * width + 2], shaded[8 * width + 3])
    assertEquals("the middle of a plate is unbroken", shaded[8 * width + 9], shaded[9 * width + 9])
  }

  @Test
  fun theReliefKeepsTheDrawingsOwnColours() {
    val width = 16
    val height = 16
    val pixels = IntArray(width * height) { index ->
      if (index % width < width / 2) red else blue
    }

    val shaded = MetalShade.paint(pixels, width, height, cellPixels = 2)

    val redPlate = shaded[8 * width + 2]
    val bluePlate = shaded[8 * width + 13]
    assertTrue("the red cell stays red", channel(redPlate, 16) > channel(redPlate, 0))
    assertTrue("the blue cell stays blue", channel(bluePlate, 0) > channel(bluePlate, 16))
  }

  @Test
  fun theInputFrameIsNotChangedByPainting() {
    val pixels = IntArray(9 * 9) { if (it % 3 == 0) red else white }
    val original = pixels.copyOf()

    MetalShade.paint(pixels, 9, 9, cellPixels = 1)

    assertTrue("the caller's frame is left alone", pixels.contentEquals(original))
  }

  @Test
  fun theSameFrameShadesToTheSameFrame() {
    val pixels = IntArray(11 * 7) { 0xFF000000.toInt() or (it * 0x000A0B0C) }

    val first = MetalShade.paint(pixels, 11, 7, cellPixels = 3)
    val second = MetalShade.paint(pixels, 11, 7, cellPixels = 3)

    assertTrue(first.contentEquals(second))
  }

  @Test
  fun aBadSizeIsReturnedUntouched() {
    val pixels = intArrayOf(red, blue)

    assertTrue(MetalShade.paint(pixels, 0, 2, cellPixels = 1) === pixels)
    assertTrue(MetalShade.paint(pixels, 48, 48, cellPixels = 1) === pixels)
  }

  /** One 8-bit channel of an ARGB pixel: 16 red, 8 green, 0 blue. */
  private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF
}
