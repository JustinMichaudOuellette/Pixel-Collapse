package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import ca.justinmo.wfcbitmap.generation.WfcNative
import kotlinx.coroutines.runBlocking
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The best-effort fill on the device: it must always produce every cell, and it must be a fallback the
 * exact path never reaches when a tiling exists.
 */
@RunWith(AndroidJUnit4::class)
class BestEffortFillTest {

  private val red = 0xFFFF0000.toInt()
  private val blue = 0xFF0000FF.toInt()

  private fun uniform(colour: Int, weight: Float = 1f): BitmapWaveFunctionCell =
    BitmapWaveFunctionCell(Array<Int?>(9) { colour }, weight)

  /** A centre of [centre] with every direction showing [edge], so two of these only fit as a pair. */
  private fun ringed(centre: Int, edge: Int): BitmapWaveFunctionCell =
    BitmapWaveFunctionCell(Array<Int?>(9) { if (it == 4) centre else edge }, 1f)

  /** Two tiles that are each compatible only with themselves, so red and blue cannot touch. */
  private fun twoSelfishTiles(): Long {
    val cells = listOf(uniform(red, weight = 2f), uniform(blue))
    return WfcNative.modelCreate(
      WfcNative.packTiles(cells),
      cells.size,
      columns = 2,
      rows = 2,
      relaxedEdges = true,
    )
  }

  @Test
  fun aFillThatFitsIsAWholeGrid() {
    val model = twoSelfishTiles()
    assertTrue("the model must accept the two-tile set", model != 0L)
    try {
      val assignment = IntArray(4)
      val conflicts = WfcNative.modelFillBestEffort(model, assignment, null, null, null, seed = 1)
      assertEquals("every cell fits when the fill may choose freely", 0, conflicts)
      val tileCount = WfcNative.modelTileCount(model)
      assertTrue("every cell must have a model tile", assignment.all { it in 0 until tileCount })
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun aFillIntersectsTheCompatibilityRowsInTheRightDirection() {
    // Red centres need blue edges; blue centres need red edges. The only valid arrangement is the
    // checkerboard, so a wrong direction in the intersection would force a conflict.
    val cells = listOf(ringed(red, blue), ringed(blue, red))
    val model = WfcNative.modelCreate(
      WfcNative.packTiles(cells),
      cells.size,
      // One row: the two patterns alternate and no diagonal neighbour asks for a same-colour pair,
      // which these tiles do not support.
      columns = 4,
      rows = 1,
      relaxedEdges = true,
    )
    assertTrue("the alternating model must be accepted", model != 0L)
    try {
      val assignment = IntArray(4)
      val conflicts = WfcNative.modelFillBestEffort(model, assignment, null, null, null, seed = 1)
      assertEquals(
        "an alternating row exists, so the fill must not force a cell " +
          "(assignment=${assignment.map { WfcNative.modelTileSource(model, it) }})",
        0,
        conflicts,
      )
      val sources = assignment.map { WfcNative.modelTileSource(model, it) }
      assertEquals("both patterns must be used", setOf(0, 1), sources.toSet())
      assertTrue(
        "the fill must alternate the two patterns, got $sources",
        (0 until 3).all { sources[it] != sources[it + 1] },
      )
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun contradictoryPinsAreReportedInsteadOfFailing() {
    val model = twoSelfishTiles()
    assertTrue("the model must accept the two-tile set", model != 0L)
    try {
      // Pin two adjacent cells to patterns that cannot touch: red at (0,0), blue at (1,0).
      val pins = intArrayOf(0, 1, Int.MIN_VALUE, Int.MIN_VALUE)
      val assignment = IntArray(4)
      val conflicts = WfcNative.modelFillBestEffort(model, assignment, null, pins, null, seed = 1)
      assertTrue("the pins themselves disagree, so a conflict must be reported", conflicts > 0)
      assertEquals("the first pinned pattern must be kept", 0, WfcNative.modelTileSource(model, assignment[0]))
      assertEquals("the second pinned pattern must be kept", 1, WfcNative.modelTileSource(model, assignment[1]))
      val tileCount = WfcNative.modelTileCount(model)
      assertTrue("every cell must still have a model tile", assignment.all { it in 0 until tileCount })
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun anImpossiblePinFailsNormallyOrFallsBackWhenAsked() = runBlocking {
    val ink = 0xFF181425.toInt()
    val pixels = IntArray(9 * 9) { ink }
    val pinned = IntArray(16 * 16) { Int.MIN_VALUE }
    // A colour the drawing does not contain: no tile can satisfy this pin.
    pinned[0] = 0xFF00FF00.toInt()

    try {
      WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = 9,
        sourceHeight = 9,
        seed = 1,
        columns = 16,
        rows = 16,
        fixedCenters = pinned,
        bestEffort = false,
        isSessionPicture = false,
      )
      fail("without best effort an impossible pin must still report no tiling")
    } catch (_: WfcGenerator.GenerationException) {
      // The normal path keeps its all-or-nothing promise.
    }

    val output = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = 9,
      sourceHeight = 9,
      seed = 1,
      columns = 16,
      rows = 16,
      fixedCenters = pinned,
      bestEffort = true,
      isSessionPicture = false,
    )
    assertTrue("the best-effort fill must answer where the exact search could not", output.bestEffort)
    assertEquals(
      "a pin no pattern can satisfy is ignored, and the fill itself fits",
      0,
      output.relaxedCells,
    )
    assertEquals("the cell still shows a colour from the drawing", ink, output.cellCenters?.get(0))
  }

  @Test
  fun aBackgroundHeavyDrawingDoesNotFillWithOneColour() = runBlocking {
    // A small motif on a large blank field: the background window is by far the commonest, which is
    // exactly the case that used to make the fill choose it everywhere.
    val background = 0xFF181425.toInt()
    val gold = 0xFFFEAE34.toInt()
    val rust = 0xFFBE4A2F.toInt()
    val side = 24
    val pixels = IntArray(side * side) { background }
    for (y in 10..14) {
      for (x in 10..14) {
        pixels[y * side + x] = if (x == 10 || y == 10 || x == 14 || y == 14) gold else rust
      }
    }
    // Force the exact attempts to fail without preventing the fill from working.
    val pinned = IntArray(side * side) { Int.MIN_VALUE }
    pinned[0] = 0xFF00FF00.toInt()

    val output = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = side,
      sourceHeight = side,
      seed = 3,
      columns = side,
      rows = side,
      fixedCenters = pinned,
      bestEffort = true,
      isSessionPicture = false,
    )
    assertTrue("the exact attempts cannot satisfy the pin", output.bestEffort)
    assertTrue(
      "the fill must not collapse to the most common background pattern",
      output.cellPatterns!!.distinct().size > 1,
    )
    val centres = output.cellCenters!!
    val nonBackground = centres.count { it != background }
    assertTrue(
      "the picture must contain more than the background colour",
      centres.distinct().size > 1,
    )
    assertEquals(
      "the source's motif centre must stay a motif in the fill",
      rust,
      centres[12 * side + 12],
    )
    assertTrue(
      "the motif must occupy a visible part of the picture, not a stray pixel " +
        "($nonBackground/${centres.size} non-background, ${centres.distinct().size} colours)",
      nonBackground >= centres.size / 20,
    )
  }

  @Test
  fun aBackgroundHeavyDrawingWithNoPinsIsNotAPlainFieldEither() = runBlocking {
    val background = 0xFF181425.toInt()
    val gold = 0xFFFEAE34.toInt()
    val side = 24
    val pixels = IntArray(side * side) { background }
    for (y in 10..14) {
      for (x in 10..14) pixels[y * side + x] = gold
    }
    // No pin: the exact solver may succeed with a uniform background, and the toggle must still
    // replace that plain field with the best-effort picture.
    val output = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = side,
      sourceHeight = side,
      seed = 3,
      columns = side,
      rows = side,
      bestEffort = true,
      isSessionPicture = false,
    )
    val centres = output.cellCenters!!
    assertTrue(
      "the picture must not be a single-colour field (bestEffort=${output.bestEffort}, " +
        "${centres.distinct().size} colours)",
      centres.distinct().size > 1,
    )
  }

  @Test
  fun theExactPathIsStillUsedWhenItCanTile() = runBlocking {
    val ink = 0xFF181425.toInt()
    val gold = 0xFFFEAE34.toInt()
    // A two-colour checkerboard gives the exact solver a real tiling to find, so the fallback must not
    // replace it just because best effort was made available.
    val pixels = IntArray(9 * 9) { index ->
      if ((index / 9 + index % 9) % 2 == 0) ink else gold
    }
    val exact = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = 9,
      sourceHeight = 9,
      seed = 1,
      columns = 24,
      rows = 24,
      wrapEdges = true,
      bestEffort = false,
      isSessionPicture = false,
    )
    val withFallbackAvailable = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = 9,
      sourceHeight = 9,
      seed = 1,
      columns = 24,
      rows = 24,
      wrapEdges = true,
      bestEffort = true,
      isSessionPicture = false,
    )
    assertFalse("a tiling exists, so best effort must not be used", withFallbackAvailable.bestEffort)
    assertArrayEquals(
      "making best effort available must not change an exact picture",
      exact.cellPatterns,
      withFallbackAvailable.cellPatterns,
    )
  }
}
