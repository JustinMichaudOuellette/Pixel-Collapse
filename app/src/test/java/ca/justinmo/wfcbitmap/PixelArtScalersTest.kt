package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eagle and Smooth: the two extra 2x pixel-art algorithms, as pixels rather than as a bitmap. */
class PixelArtScalersTest {

  private val black = 0xFF000000.toInt()
  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun bothAlgorithmsGiveTheExpectedDimensionsForTheirFactors() {
    val source = IntArray(3 * 2)
    for (factor in listOf(2, 4, 16)) {
      val eagle = EagleUpscaler.scale(source, 3, 2, factor)
      assertEquals("Eagle $factor x width", 3 * factor, eagle.width)
      assertEquals("Eagle $factor x height", 2 * factor, eagle.height)
      assertEquals("Eagle $factor x pixels", 3 * 2 * factor * factor, eagle.pixels.size)

      val smooth = SmoothUpscaler.scale(source, 3, 2, factor)
      assertEquals("Smooth $factor x width", 3 * factor, smooth.width)
      assertEquals("Smooth $factor x height", 2 * factor, smooth.height)
      assertEquals("Smooth $factor x pixels", 3 * 2 * factor * factor, smooth.pixels.size)
    }
  }

  @Test
  fun oneTimesIsACopy() {
    val source = intArrayOf(black, white, white, black)
    for (image in listOf(
      EagleUpscaler.scale(source, 2, 2, 1),
      SmoothUpscaler.scale(source, 2, 2, 1),
    )) {
      assertEquals(2, image.width)
      assertEquals(2, image.height)
      assertArrayEquals(source, image.pixels)
    }
  }

  @Test
  fun aFlatPictureStaysFlat() {
    val source = IntArray(4 * 3) { black }
    for (factor in listOf(2, 4, 16)) {
      assertTrue(
        "Eagle ${factor}x keeps one colour",
        EagleUpscaler.scale(source, 4, 3, factor).pixels.all { it == black },
      )
      assertTrue(
        "Smooth ${factor}x keeps one colour",
        SmoothUpscaler.scale(source, 4, 3, factor).pixels.all { it == black },
      )
    }
  }

  @Test
  fun eagleTakesTheCornerFromTheDiagonalWhenBothEdgesMatch() {
    // The centre's up, left and up-left neighbours are white; that makes the centre's top-left output
    // pixel white instead of the centre's own black.
    val source = intArrayOf(
      white, white, black,
      white, black, black,
      black, black, black,
    )

    val scaled = EagleUpscaler.scale(source, 3, 3, 2)

    assertEquals(white, scaled.pixels[2 * scaled.width + 2])
  }

  @Test
  fun smoothBlendsTheCornerHalfwayTowardsTheMatchingNeighbours() {
    val source = intArrayOf(
      white, white, black,
      white, black, black,
      black, black, black,
    )

    val scaled = SmoothUpscaler.scale(source, 3, 3, 2)

    // Halfway between the black centre and the white corner: 0x7F on every colour channel.
    assertEquals(0xFF7F7F7F.toInt(), scaled.pixels[2 * scaled.width + 2])
  }

  @Test
  fun eagleOnlyUsesColoursThatWereDrawn() {
    val source = intArrayOf(
      white, black, white,
      black, white, black,
      white, black, white,
    )

    val eagle = EagleUpscaler.scale(source, 3, 3, 2)
    assertTrue("Eagle invents no colour", eagle.pixels.all { it == black || it == white })
  }

  @Test
  fun theDispatcherUsesTheAlgorithmItIsGiven() {
    val source = intArrayOf(black, white, white, black)
    for (scaler in PixelArtScaler.entries) {
      when (scaler) {
        PixelArtScaler.Nearest -> {
          val image = PixelScaler.scale(source, 2, 2, scaler, 1)
          assertEquals(2, image.width)
          assertEquals(2, image.height)
        }
        else -> {
          val factor = when (scaler) {
            PixelArtScaler.Scale3x -> 3
            else -> 2
          }
          val image = PixelScaler.scale(source, 2, 2, scaler, factor)
          assertEquals(2 * factor, image.width)
          assertEquals(2 * factor, image.height)
        }
      }
    }
  }

  @Test
  fun hq2xAndSaiGiveTheExpectedDimensionsForTheirFactors() {
    val source = IntArray(3 * 2)
    for (factor in listOf(2, 4, 16)) {
      val hq = Hq2xUpscaler.scale(source, 3, 2, factor)
      assertEquals("HQ2x $factor x width", 3 * factor, hq.width)
      assertEquals("HQ2x $factor x height", 2 * factor, hq.height)
      assertEquals("HQ2x $factor x pixels", 3 * 2 * factor * factor, hq.pixels.size)

      for (variant in SaiUpscaler.Variant.entries) {
        val sai = SaiUpscaler.scale(source, 3, 2, factor, variant)
        assertEquals("$variant $factor x width", 3 * factor, sai.width)
        assertEquals("$variant $factor x height", 2 * factor, sai.height)
        assertEquals("$variant $factor x pixels", 3 * 2 * factor * factor, sai.pixels.size)
      }
    }
  }

  @Test
  fun hq2xAndSaiKeepAFlatPictureFlat() {
    val source = IntArray(4 * 3) { black }
    for (factor in listOf(2, 4, 16)) {
      assertTrue(
        "HQ2x ${factor}x keeps one colour",
        Hq2xUpscaler.scale(source, 4, 3, factor).pixels.all { it == black },
      )
      for (variant in SaiUpscaler.Variant.entries) {
        assertTrue(
          "$variant ${factor}x keeps one colour",
          SaiUpscaler.scale(source, 4, 3, factor, variant).pixels.all { it == black },
        )
      }
    }
  }

  @Test
  fun hq2xAndSaiBlendDiagonalEdges() {
    // A black field with a white step: every filter has to mix colours somewhere along that corner.
    val source = intArrayOf(
      black, black, black,
      black, black, white,
      black, black, white,
    )

    val hq = Hq2xUpscaler.scale(source, 3, 3, 2)
    assertTrue("HQ2x mixes a corner", hq.pixels.any { it != black && it != white })

    for (variant in SaiUpscaler.Variant.entries) {
      val sai = SaiUpscaler.scale(source, 3, 3, 2, variant)
      assertTrue("$variant mixes a corner", sai.pixels.any { it != black && it != white })
    }
  }

  @Test
  fun blendingKeepsOpaqueAlphaOpaque() {
    val source = IntArray(3 * 3) { 0x80000000.toInt() }
    for (variant in SaiUpscaler.Variant.entries) {
      val scaled = SaiUpscaler.scale(source, 3, 3, 2, variant)
      assertTrue("$variant keeps alpha", scaled.pixels.all { (it ushr 24) and 0xFF == 0x80 })
    }
  }
}