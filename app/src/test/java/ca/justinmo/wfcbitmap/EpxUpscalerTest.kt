package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPX/Scale2x upscaling: the saved file stays pixel art, but the stair-steps get rounded.
 *
 * The rules only ever choose one of the input colours, so a test can state the two things that matter
 * without pinning down every corner: the picture gets the right size, and no new colour appears.
 */
class EpxUpscalerTest {

  private val black = 0xFF000000.toInt()
  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun oneTimesIsACopy() {
    val source = intArrayOf(black, white, white, black)
    val scaled = EpxUpscaler.scale(source, 2, 2, 1)

    assertEquals(2, scaled.width)
    assertEquals(2, scaled.height)
    assertTrue(source.contentEquals(scaled.pixels))
    assertTrue("a copy, not the same array", source !== scaled.pixels)
  }

  @Test
  fun theSupportedFactorsGiveTheExpectedDimensions() {
    val source = IntArray(3 * 2)
    for (factor in listOf(2, 3, 4, 6, 9, 16)) {
      val scaled = EpxUpscaler.scale(source, 3, 2, factor)
      assertEquals("$factor x width", 3 * factor, scaled.width)
      assertEquals("$factor x height", 2 * factor, scaled.height)
      assertEquals("$factor x pixels", 3 * 2 * factor * factor, scaled.pixels.size)
    }
  }

  @Test
  fun sixTimesIsAThreeTimesPassThenATwoTimesOne() {
    val source = intArrayOf(
      white, white, black,
      white, black, black,
      black, black, white,
    )

    val once = EpxUpscaler.scale(source, 3, 3, 6)
    val three = EpxUpscaler.scale(source, 3, 3, 3)
    val staged = EpxUpscaler.scale(three.pixels, three.width, three.height, 2)

    assertEquals(staged.width, once.width)
    assertEquals(staged.height, once.height)
    assertTrue("6x is 3x then 2x", once.pixels.contentEquals(staged.pixels))
  }

  @Test
  fun nineTimesIsTheThreeTimesPassTwice() {
    val source = intArrayOf(
      white, white, black,
      white, black, black,
      black, black, white,
    )

    val once = EpxUpscaler.scale(source, 3, 3, 9)
    val three = EpxUpscaler.scale(source, 3, 3, 3)
    val staged = EpxUpscaler.scale(three.pixels, three.width, three.height, 3)

    assertEquals(staged.width, once.width)
    assertEquals(staged.height, once.height)
    assertTrue("9x is 3x twice", once.pixels.contentEquals(staged.pixels))
  }

  @Test
  fun aFlatPictureStaysFlat() {
    val source = IntArray(5 * 4) { black }
    for (factor in listOf(2, 3, 4, 6, 9)) {
      val scaled = EpxUpscaler.scale(source, 5, 4, factor)
      assertTrue("${factor}x keeps one colour", scaled.pixels.all { it == black })
    }
  }

  @Test
  fun upscalingOnlyEverChoosesAnInputColour() {
    val source = IntArray(6 * 5) { index ->
      when {
        index % 3 == 0 -> black
        index % 3 == 1 -> white
        else -> 0xFFBE4A2F.toInt()
      }
    }
    for (factor in listOf(2, 3, 4, 6, 9)) {
      val scaled = EpxUpscaler.scale(source, 6, 5, factor)
      assertTrue(
        "${factor}x invents no colour",
        scaled.pixels.all { it in setOf(black, white, 0xFFBE4A2F.toInt()) },
      )
    }
  }

  @Test
  fun epxRoundsACorner() {
    // A white step around a black pixel: its up and left neighbours agree, and the other pair does not,
    // so EPX turns its top-left corner white. Nearest-neighbour scaling would not.
    val source = intArrayOf(
      white, white, black,
      white, black, black,
      black, black, black,
    )

    val scaled = EpxUpscaler.scale(source, 3, 3, 2)

    assertTrue("the corner was rounded", scaled.pixels[2 * scaled.width + 2] == white)
  }
}
