package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ways the drawing is sampled: as it lies, and wrapped.
 *
 * As it lies, a window that hangs over an edge is blank there, which is what gives the drawing an edge at
 * all. Wrapped, that window reads the pixel on the opposite side instead, so the drawing has no borders and
 * its patterns run around the picture -- the editor's wrap setting. These tests pin both, because the whole
 * difference between "the drawing's border is the picture's border" and "the pattern wraps" is which of the
 * two samplings ran.
 */
class WrappedSamplingTest {

  private val ink = 0xFF181425.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val rust = 0xFFBE4A2F.toInt()

  private fun drawing(fill: (x: Int, y: Int) -> Int) = IntArray2D.create(WIDTH, HEIGHT, fill)

  // ───────────────────────────────────────────────────────────────────────────
  // The two samplings
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun sampledAsItLiesTheWindowsOnTheEdgeCarryBlanks() {
    val cells = BitmapWaveFunctionCell.createCells(drawing { _, _ -> ink })
    assertTrue(
      "a bordered sampling must produce patterns with holes where the drawing ends",
      cells.any { cell -> cell.kernel.any { it == null } },
    )
  }

  @Test
  fun wrappedSamplingHasNoBlanksAnywhere() {
    val cells = BitmapWaveFunctionCell.createCells(drawing { x, y -> if (x == y) gold else ink }, wrap = true)
    for (cell in cells) {
      assertTrue("a wrapped window is complete: $cell", cell.kernel.all { it != null })
    }
  }

  @Test
  fun wrappedSamplingReadsTheOppositeEdge() {
    val pixels = drawing { x, _ -> if (x == 0) rust else ink }

    // The window centred on the drawing's right column looks right, past the drawing, at its left column.
    val onRight = BitmapWaveFunctionCell.windowAt(pixels, WIDTH - 1, HEIGHT / 2, wrap = true)
    assertEquals("the pixel past the right edge is the one on the left edge", rust, onRight[5])

    // And the one on the left column looks left, past the drawing, at its right column.
    val onLeft = BitmapWaveFunctionCell.windowAt(pixels, 0, HEIGHT / 2, wrap = true)
    assertEquals("the pixel past the left edge is the one on the right", ink, onLeft[3])
  }

  @Test
  fun wrappedSamplingFoldsBothAxes() {
    // The lone pixel in the top-left corner is the bottom-right neighbour of the corner window.
    val pixels = drawing { x, y -> if (x == 0 && y == 0) gold else ink }
    val corner = BitmapWaveFunctionCell.windowAt(pixels, WIDTH - 1, HEIGHT - 1, wrap = true)
    assertEquals("the pixel past the bottom-right corner is the top-left one", gold, corner[8])
  }

  /**
   * Why wrap mode drops the edge rule instead of relaxing it: the rule asks a border cell to sit on a
   * pattern sampled on that edge of the drawing, and a wrapped tile set has no such patterns at all.
   */
  @Test
  fun aWrappedTileSetHasNoEdgePatternsToSatisfyTheRuleWith() {
    val cells = BitmapWaveFunctionCell.createCells(
      drawing { x, y -> if ((x + y) % 3 == 0) gold else ink },
      wrap = true,
    )
    for (cell in cells) {
      for (direction in Direction2d8.entries) {
        assertTrue("wrapped $cell is blank towards $direction", cell.kernelColor(direction) != null)
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // The incremental tally
  // ───────────────────────────────────────────────────────────────────────────

  @Test
  fun theIncrementalTallyMatchesAFullWrappedResample() {
    // The tally updates only the windows an edit touched. On a torus an edit is felt across the edge as
    // well, so this is the case where a naive delta would quietly disagree with a full resample.
    val tally = WindowTally()
    val original = drawing { x, y -> if ((x + y) % 4 == 0) gold else ink }
    tally.sample(original, wrap = true)

    // The pixel in the top-left corner is also a neighbour of the windows in the bottom-right one.
    val edited = original.data.copyOf()
    edited[0] = rust
    val incremental = tally.sample(IntArray2D(WIDTH, HEIGHT, edited), wrap = true)
    val full = BitmapWaveFunctionCell.createCells(IntArray2D(WIDTH, HEIGHT, edited.copyOf()), wrap = true)

    assertEquals(
      "incremental wrapped tally differs from a full wrapped resample",
      full.map { it.kernel.toList() to it.weight },
      incremental.map { it.kernel.toList() to it.weight },
    )
  }

  @Test
  fun switchingToWrappedSamplingDoesNotReuseTheBorderedCounts() {
    // The same drawing tallies differently under the two samplings, so the cached tally must not be carried
    // across the setting: that would show the bordered tile set in wrap mode for one edit.
    val tally = WindowTally()
    val pixels = drawing { x, y -> if (x == 0 || y == 0) gold else ink }

    val bordered = tally.sample(pixels, wrap = false)
    val wrapped = tally.sample(pixels, wrap = true)

    assertTrue("the bordered set has edge patterns", bordered.any { cell -> cell.kernel.any { it == null } })
    assertFalse("the wrapped set has none", wrapped.any { cell -> cell.kernel.any { it == null } })
  }

  private companion object {
    const val WIDTH = 8
    const val HEIGHT = 6
  }
}
