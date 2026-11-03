package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The automatic fallback, on the device: a drawing whose border cannot repeat still produces a picture.
 *
 * This is the regression guard for the setting nobody chooses. A drawing drawn to the edge of an 11-row grid
 * has a border that cannot walk 270 rows of the bitmap, so the edge rule has no tiling for it -- proven in a
 * few milliseconds -- and the only thing standing between the user and an error message is the second
 * attempt with the rule dropped. It has to keep happening on its own, and it has to say so.
 */
@RunWith(AndroidJUnit4::class)
class RelaxedEdgesFallbackTest {

  private val ink = 0xFF181425.toInt()
  private val gold = 0xFFFEAE34.toInt()

  /** Every row of the border is a different colour, so no border pattern can repeat around the grid. */
  private fun stripedBorder(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      val colour = 0xFF000000.toInt() or (y * 0x0A0B0C).coerceAtLeast(1)
      pixels[y * SOURCE_WIDTH] = colour
      pixels[y * SOURCE_WIDTH + SOURCE_WIDTH - 1] = colour
      pixels[y] = gold
      pixels[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH + y] = gold
    }
    return pixels
  }

  @Test
  fun aBorderThatCannotRepeatStillGetsAPicture() {
    val output = runBlocking {
      WfcGenerator.generate(
        pixels = stripedBorder(),
        sourceWidth = SOURCE_WIDTH,
        sourceHeight = SOURCE_HEIGHT,
        seed = 7,
        wrapEdges = false,
      )
    }
    assertNotNull("expected a picture.used", output)
    assertTrue(
      "the edge rule cannot keep this drawing's border, so the relaxed attempt must have produced it",
      output.relaxedEdges,
    )
    assertEquals("and the snackbar must say so", "Edges relaxed", edgeRuleNotice(output.relaxedEdges))
    println(
      "relaxed fallback: ${output.tileCount} tiles, ${output.millis} ms " +
        "(solve ${output.solveMillis} ms), relaxed=${output.relaxedEdges}",
    )
  }

  @Test
  fun aDrawingThatKeepsItsBorderIsNotRelaxed() {
    // A one-colour frame can repeat around the grid, so the edge rule applies and nothing is reported.
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        if (x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1) {
          pixels[y * SOURCE_WIDTH + x] = gold
        }
      }
    }
    val output = runBlocking {
      WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = SOURCE_WIDTH,
        sourceHeight = SOURCE_HEIGHT,
        seed = 7,
        wrapEdges = false,
      )
    }
    assertTrue("the edge rule should have found this tiling", !output.relaxedEdges)
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
  }
}