package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The symmetry setting: every window offered mirrored as well.
 *
 * A drawing's tile set is its window counts, so without this the picture can only ever be arranged from the
 * shapes as they were drawn. Mirroring each window adds the flipped version as another tile, which is more
 * patterns to fit together and therefore more arrangements of the same drawing -- at the cost of a motif
 * appearing turned the way the drawing does not turn it.
 *
 * The mirroring is a left-right flip (the y axis), so it is the *rows* of the kernel that reverse and the
 * centre pixel is untouched: a mirrored tile still shows the pixel its window was centred on.
 */
class SymmetryTest {

  private val ink = 0xFF181425.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val rust = 0xFFBE4A2F.toInt()

  /** The drawing's kernel slots, row-major, which is the order `mirroredKernel` works in. */
  private fun kernel(vararg colours: Int?): List<Int?> = colours.toList()

  @Test
  fun mirroringReversesEachRowOfTheWindowAndLeavesTheCentre() {
    val window = kernel(
      1, 2, 3,
      4, 5, 6,
      7, 8, 9,
    )
    assertEquals(
      kernel(
        3, 2, 1,
        6, 5, 4,
        9, 8, 7,
      ),
      BitmapWaveFunctionCell.mirroredKernel(window),
    )
    assertEquals("the centre pixel is its own mirror", 5, BitmapWaveFunctionCell.mirroredKernel(window)[4])
  }

  @Test
  fun mirroringTwiceGivesTheWindowBack() {
    val window = kernel(1, 2, 3, 4, 5, 6, 7, 8, 9)
    val twice = BitmapWaveFunctionCell.mirroredKernel(BitmapWaveFunctionCell.mirroredKernel(window))
    assertEquals(window, twice)
  }

  /**
   * A left-right symmetric drawing gains no new patterns, and their weights add up instead.
   *
   * Every window the drawing has, its mirror is one it already has, so the tile set is the same size --
   * but a pattern is now offered from both sides of the drawing it appears on, so its weight is its own
   * count plus its mirror's. That is what "the drawing plus its mirror image" means when the drawing is
   * already its own mirror image.
   */
  @Test
  fun aMirrorSymmetricDrawingKeepsItsTileSetAndMergesTheWeights() {
    val pixels = IntArray2D.create(5, 5) { x, _ -> if (x == 0 || x == 4) gold else ink }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorSymmetry = true)

    assertEquals("a symmetric drawing gains no new patterns", plain.size, mirrored.size)
    for (cell in mirrored) {
      assertEquals(
        "a pattern's weight is its own count plus its mirror's: ${cell.kernel}",
        weightOf(plain, cell.kernel.toList()),
        cell.weight,
        0f,
      )
    }
  }

  @Test
  fun anAsymmetricWindowGainsItsMirrorAsAnotherTile() {
    // Gold only down the left side: the windows with gold on their right are shapes the drawing lacks.
    val pixels = IntArray2D.create(8, 5) { x, _ -> if (x <= 1) gold else ink }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorSymmetry = true)

    assertTrue(
      "symmetry must add patterns to arrange: ${mirrored.size} vs ${plain.size}",
      mirrored.size > plain.size,
    )
    // Every extra tile is the mirror of a tile the drawing really contains.
    val original = plain.map { it.kernel.toList() }.toSet()
    val extra = mirrored.map { it.kernel.toList() }.filter { it !in original }
    assertTrue("expected the mirrored windows to be new tiles", extra.isNotEmpty())
    for (kernel in extra) {
      assertTrue(
        "a mirrored tile must be the mirror of a drawn window: $kernel",
        BitmapWaveFunctionCell.mirroredKernel(kernel) in original,
      )
    }
  }

  @Test
  fun aMirroredTileShowsTheSamePixelAsTheWindowItCameFrom() {
    val pixels = IntArray2D.create(9, 7) { x, y ->
      when {
        (x + y) % 3 == 0 -> gold
        x == 1 -> rust
        else -> ink
      }
    }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val centresDrawn = plain.map { it.color }.toSet()
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorSymmetry = true)

    for (cell in mirrored) {
      assertTrue(
        "a mirrored pattern still shows a pixel the drawing has: $cell",
        cell.color in centresDrawn,
      )
    }
  }

  /** The weight a kernel should have with symmetry on: its own count plus its mirror's. */
  private fun weightOf(cells: List<BitmapWaveFunctionCell>, kernel: List<Int?>): Float {
    val own = cells.firstOrNull { it.kernel.toList() == kernel }?.weight ?: 0f
    val mirrored = BitmapWaveFunctionCell.mirroredKernel(kernel)
    if (mirrored == kernel) return own
    return own + (cells.firstOrNull { it.kernel.toList() == mirrored }?.weight ?: 0f)
  }

  @Test
  fun symmetryAndWrappingCompose() {
    // Both settings on: wrapped windows are complete, and their mirrors are complete too.
    val pixels = IntArray2D.create(6, 5) { x, y -> if (x == y) gold else ink }
    val cells = BitmapWaveFunctionCell.createCells(pixels, wrap = true, mirrorSymmetry = true)

    assertTrue("expected patterns", cells.isNotEmpty())
    for (cell in cells) {
      for (direction in Direction2d8.entries) {
        assertTrue("wrapped and mirrored $cell is blank towards $direction", cell.kernelColor(direction) != null)
      }
    }
  }

  @Test
  fun theTallyCarriesTheSettingWithoutResampling() {
    // The mirror is applied when the tile set is built, not to the tally, so switching it on for the same
    // drawing must give the mirrored set immediately, and switching it off must give the drawing's own set
    // back -- no mirrored window may have leaked into the tally.
    val tally = WindowTally()
    val pixels = IntArray2D.create(8, 5) { x, _ -> if (x <= 1) gold else ink }
    val plain = tally.sample(pixels, mirrorSymmetry = false)
    val mirrored = tally.sample(pixels, mirrorSymmetry = true)

    assertTrue("symmetry must add patterns: ${mirrored.size} vs ${plain.size}", mirrored.size > plain.size)
    assertEquals(
      "the tally itself stays the drawing's own windows",
      plain.map { it.kernel.toList() to it.weight },
      tally.sample(pixels, mirrorSymmetry = false).map { it.kernel.toList() to it.weight },
    )
  }

  @Test
  fun thePaintedColoursSurviveMirroring() {
    // Nothing a person drew is invented: every colour in the mirrored tile set came from the drawing.
    val pixels = IntArray2D.create(8, 6) { x, y -> if ((x * y) % 5 == 0) gold else ink }
    val drawnColours = BitmapWaveFunctionCell.createCells(pixels).flatMap { it.kernel.filterNotNull() }.toSet()
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorSymmetry = true)

    assertEquals(
      "mirroring must not introduce a colour the drawing does not have",
      drawnColours.size,
      mirrored.flatMap { it.kernel.filterNotNull() }.toSet().size,
    )
  }
}
