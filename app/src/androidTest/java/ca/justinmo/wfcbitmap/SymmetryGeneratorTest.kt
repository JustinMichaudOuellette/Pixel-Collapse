package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The three symmetry settings reach the generator, and the generator knows which one it was asked for.
 *
 * The tile set is what the solver is given, so the flips and the turn have to show up there -- and the cached
 * picture is keyed by the settings that made it, so turning one off has to be able to bring the plain picture
 * back rather than the turned or mirrored one that was cached a moment ago.
 */
@RunWith(AndroidJUnit4::class)
class SymmetryGeneratorTest {

  @Test
  fun theVerticalMirrorAddsPatternsToTheTileSet() = runBlocking {
    val plain = generate(mirrorVertical = false)
    val flipped = generate(mirrorVertical = true)

    assertTrue(
      "the flip has to reach the solver: ${flipped.tileCount} vs ${plain.tileCount} patterns",
      flipped.tileCount > plain.tileCount,
    )
  }

  @Test
  fun theQuarterTurnAddsPatternsToTheTileSet() = runBlocking {
    val plain = generate()
    val turned = generate(quarterTurn = true)

    assertTrue(
      "the turn has to reach the solver: ${turned.tileCount} vs ${plain.tileCount} patterns",
      turned.tileCount > plain.tileCount,
    )
  }

  @Test
  fun theTurnAndTheMirrorAreDifferentPatterns() = runBlocking {
    val turned = generate(quarterTurn = true)
    val mirrored = generate(mirrorHorizontal = true)
    val both = generate(mirrorHorizontal = true, quarterTurn = true)

    // Stripes lying one way: the turn offers the ones lying across, the mirror the ones running the other way,
    // and together more than either -- the two settings are not two names for one thing.
    assertTrue(
      "the turn and the mirror are different tile sets",
      turned.packedTiles?.contentEquals(mirrored.packedTiles) == false,
    )
    assertTrue("and together they add more", both.tileCount > turned.tileCount)
    assertTrue("and together they add more", both.tileCount > mirrored.tileCount)
  }

  @Test
  fun turningAMirrorOffBringsTheUnmirroredPictureBack() = runBlocking {
    val before = generate(mirrorVertical = false)
    val flipped = generate(mirrorVertical = true)
    val after = generate(mirrorVertical = false)

    assertTrue("the two pictures differ", !before.bitmap.sameAs(flipped.bitmap))
    // The same drawing, seed and settings are the same picture: if the cache key ignored the mirror, this
    // would hand back the flipped one that was generated in between.
    assertTrue("the unmirrored picture comes back", before.bitmap.sameAs(after.bitmap))
  }

  @Test
  fun turningTheQuarterTurnOffBringsTheUnturnedPictureBack() = runBlocking {
    val before = generate()
    val turned = generate(quarterTurn = true)
    val after = generate()

    assertTrue("the two pictures differ", !before.bitmap.sameAs(turned.bitmap))
    // The same reasoning as the mirror above, for the third setting: the turn is part of what the cached
    // picture is a picture of, so switching it off has to bring the plain one back rather than the turned one.
    assertTrue("the unturned picture comes back", before.bitmap.sameAs(after.bitmap))
  }

  private suspend fun generate(
    mirrorHorizontal: Boolean = false,
    mirrorVertical: Boolean = false,
    quarterTurn: Boolean = false,
  ) = WfcGenerator.generate(
    pixels = DRAWING,
    sourceWidth = SIDE,
    sourceHeight = SIDE,
    seed = 11,
    columns = GRID,
    rows = GRID,
    // Wrapped, so the edge rule cannot be what decides whether there is a picture at all.
    wrapEdges = true,
    mirrorHorizontal = mirrorHorizontal,
    mirrorVertical = mirrorVertical,
    quarterTurn = quarterTurn,
  )

  private companion object {
    const val SIDE = 7
    const val GRID = 24

    val INK: Int = 0xFF181425.toInt()
    val GOLD: Int = 0xFFFEAE34.toInt()
    val RUST: Int = 0xFFBE4A2F.toInt()

    /**
     * A drawing of diagonal stripes running one way, with a speck off the diagonal. Stripes are asymmetric
     * about both axes in a way even a wrapped scan cannot already contain: a window with the stripe running
     * down to the right is a shape the drawing has, and the stripe running down to the left is one it does not.
     * Gold along the top and left edges only would *not* do -- on a torus those edge windows already include
     * their own flips, so the test would be measuring nothing.
     */
    val DRAWING: IntArray = IntArray(SIDE * SIDE) { index ->
      val x = index % SIDE
      val y = index / SIDE
      when {
        x == 3 && y == 4 -> RUST
        (x + y) % 3 == 0 -> GOLD
        else -> INK
      }
    }
  }
}