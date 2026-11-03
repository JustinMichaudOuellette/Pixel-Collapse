package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two mirrors reach the generator, and the generator knows which one it was asked for.
 *
 * The tile set is what the solver is given, so the flips have to show up there -- and the cached picture is
 * keyed by the settings that made it, so turning one mirror off has to be able to bring the unmirrored picture
 * back rather than the flipped one that was cached a moment ago.
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
  fun theTwoMirrorsAreDifferentPatterns() = runBlocking {
    val horizontal = generate(mirrorHorizontal = true)
    val vertical = generate(mirrorVertical = true)
    val both = generate(mirrorHorizontal = true, mirrorVertical = true)

    // Each of the three settings offers a different set of shapes, so each was a real choice.
    assertTrue("both mirrors add the half turn too", both.tileCount > horizontal.tileCount)
    assertTrue("both mirrors add the half turn too", both.tileCount > vertical.tileCount)
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

  private suspend fun generate(
    mirrorHorizontal: Boolean = false,
    mirrorVertical: Boolean = false,
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