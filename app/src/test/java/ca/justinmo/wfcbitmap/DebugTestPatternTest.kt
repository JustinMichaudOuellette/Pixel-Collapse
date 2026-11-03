package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug build's starting drawing has to be the drawing AGENTS.md specifies, cell for cell.
 *
 * It is the shared reference for the generation, kernel-grouping and video-seam work, so a typo here would
 * quietly move every debug run off the pattern being discussed. This test is what makes the duplicated
 * rows worth having: it reads them back as the same `*`/`_` drawing the notes use.
 */
class DebugTestPatternTest {

  private val expected = listOf(
    "_________",
    "__**_**__",
    "_*__*__*_",
    "_*_____*_",
    "__*___*__",
    "_*_____*_",
    "_*__*__*_",
    "__**_**__",
    "_________",
  )

  @Test
  fun theStartingDrawingIsTheAgentsMdPattern() {
    assertEquals("width", expected.first().length, DebugTestPattern.columns)
    assertEquals("height", expected.size, DebugTestPattern.rows)
    assertEquals("pixel count", expected.size * expected.first().length, DebugTestPattern.pixels.size)

    // The top row is all background, so its first cell names the empty colour; anything else in the
    // drawing has to be the one filled colour.
    val empty = DebugTestPattern.pixels[0]
    val filled = DebugTestPattern.pixels.firstOrNull { it != empty }
      ?: error("the pattern has no filled cell")
    for (row in expected.indices) {
      for (col in expected[row].indices) {
        val pixel = DebugTestPattern.pixels[row * DebugTestPattern.columns + col]
        if (expected[row][col] == '*') {
          assertEquals("cell $col,$row should be filled", filled, pixel)
        } else {
          assertEquals("cell $col,$row should be empty", empty, pixel)
        }
      }
    }
  }
}
