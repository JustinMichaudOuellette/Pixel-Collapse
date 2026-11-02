package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two symmetry settings: every window offered mirrored, left to right and/or top to bottom.
 *
 * A drawing's tile set is its window counts, so without them the picture can only ever be arranged from the
 * shapes as they were drawn. Mirroring each window adds the flipped versions as other tiles, which is more
 * patterns to fit together and therefore more arrangements of the same drawing -- at the cost of a motif
 * appearing turned the way the drawing does not turn it.
 *
 * The two are independent, and together they are three flips: about the y axis, about the x axis, and the half
 * turn the two make. A window's centre pixel is its own under all of them, so a mirrored tile still shows the
 * pixel its window was centred on.
 */
class SymmetryTest {

  private val ink = 0xFF181425.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val rust = 0xFFBE4A2F.toInt()

  /** The drawing's kernel slots, row-major, which is the order the mirrors work in. */
  private fun kernel(vararg colours: Int?): List<Int?> = colours.toList()

  @Test
  fun theLeftToRightMirrorReversesEachRowAndLeavesTheCentre() {
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
      BitmapWaveFunctionCell.horizontalMirror(window),
    )
    assertEquals("the centre pixel is its own mirror", 5, BitmapWaveFunctionCell.horizontalMirror(window)[4])
  }

  @Test
  fun theTopToBottomMirrorReversesTheRowsAndLeavesTheCentre() {
    val window = kernel(
      1, 2, 3,
      4, 5, 6,
      7, 8, 9,
    )
    assertEquals(
      kernel(
        7, 8, 9,
        4, 5, 6,
        1, 2, 3,
      ),
      BitmapWaveFunctionCell.verticalMirror(window),
    )
    assertEquals("the centre pixel is its own flip", 5, BitmapWaveFunctionCell.verticalMirror(window)[4])
  }

  @Test
  fun eitherMirrorTwiceGivesTheWindowBack() {
    val window = kernel(1, 2, 3, 4, 5, 6, 7, 8, 9)
    assertEquals(window, BitmapWaveFunctionCell.horizontalMirror(BitmapWaveFunctionCell.horizontalMirror(window)))
    assertEquals(window, BitmapWaveFunctionCell.verticalMirror(BitmapWaveFunctionCell.verticalMirror(window)))
  }

  @Test
  fun theTwoMirrorsTogetherAreTheHalfTurn() {
    // Flipping one way and then the other is the same as turning the window upside down, whichever order the
    // two are applied in -- which is why both settings give four orientations rather than three.
    val window = kernel(1, 2, 3, 4, 5, 6, 7, 8, 9)
    val byBoth = BitmapWaveFunctionCell.verticalMirror(BitmapWaveFunctionCell.horizontalMirror(window))
    assertEquals(
      "the half turn is the same either way round",
      byBoth,
      BitmapWaveFunctionCell.horizontalMirror(BitmapWaveFunctionCell.verticalMirror(window)),
    )
    assertEquals(
      kernel(
        9, 8, 7,
        6, 5, 4,
        3, 2, 1,
      ),
      byBoth,
    )
  }

  @Test
  fun theOrientationsGrowWithTheSettings() {
    val window = kernel(1, 2, 3, 4, 5, 6, 7, 8, 9)
    assertEquals(1, BitmapWaveFunctionCell.orientations(window).size)
    assertEquals(2, BitmapWaveFunctionCell.orientations(window, mirrorHorizontal = true).size)
    assertEquals(2, BitmapWaveFunctionCell.orientations(window, mirrorVertical = true).size)
    assertEquals(4, BitmapWaveFunctionCell.orientations(window, mirrorHorizontal = true, mirrorVertical = true).size)
  }

  @Test
  fun aWindowThatIsItsOwnMirrorIsNotOfferedTwice() {
    // A vertical bar is its own left-to-right mirror, and a horizontal bar its own top-to-bottom one: neither
    // may be counted twice, which would make it twice as likely to be picked as the patterns beside it.
    val verticalBar = kernel(0, 1, 0, 0, 1, 0, 0, 1, 0)
    assertEquals(listOf(verticalBar), BitmapWaveFunctionCell.orientations(verticalBar, mirrorHorizontal = true))
    val horizontalBar = kernel(0, 0, 0, 1, 1, 1, 0, 0, 0)
    assertEquals(listOf(horizontalBar), BitmapWaveFunctionCell.orientations(horizontalBar, mirrorVertical = true))
  }

  /**
   * A drawing that is its own mirror gains no new patterns, and their weights add up instead.
   *
   * Every window the drawing has, its mirror is one it already has, so the tile set is the same size -- but a
   * pattern is now offered from both sides of the drawing it appears on, so its weight is its own count plus
   * its mirror's. That is what "the drawing plus its mirror image" means when the drawing is already its own
   * mirror image.
   */
  @Test
  fun aMirrorSymmetricDrawingKeepsItsTileSetAndMergesTheWeights() {
    val pixels = IntArray2D.create(5, 5) { x, _ -> if (x == 0 || x == 4) gold else ink }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true)

    assertEquals("a symmetric drawing gains no new patterns", plain.size, mirrored.size)
    for (cell in mirrored) {
      assertEquals(
        "a pattern's weight is its own count plus its mirror's: ${cell.kernel}",
        weightOf(plain, cell.kernel.toList(), BitmapWaveFunctionCell.horizontalMirror(cell.kernel.toList())),
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
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true)

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
        BitmapWaveFunctionCell.horizontalMirror(kernel) in original,
      )
    }
  }

  @Test
  fun theVerticalMirrorAddsItsOwnPatternsToo() {
    // Gold only along the top: the windows with gold along the bottom are shapes the drawing lacks.
    val pixels = IntArray2D.create(6, 8) { _, y -> if (y <= 1) gold else ink }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val vertical = BitmapWaveFunctionCell.createCells(pixels, mirrorVertical = true)

    assertTrue("the flip must add patterns: ${vertical.size} vs ${plain.size}", vertical.size > plain.size)
    val original = plain.map { it.kernel.toList() }.toSet()
    for (kernel in vertical.map { it.kernel.toList() }.filter { it !in original }) {
      assertTrue(
        "a flipped tile must be the flip of a drawn window: $kernel",
        BitmapWaveFunctionCell.verticalMirror(kernel) in original,
      )
    }
  }

  @Test
  fun bothMirrorsGiveMorePatternsThanEitherAlone() {
    // Gold in an L along the top and left edges, and one speck away from both. A drawing whose motif sits in
    // the middle gains nothing from a mirror -- every shift of it is a window the scan already found -- so what
    // a mirror *adds* is the shapes it can make out of the edges: the flip puts the edge's blank side on the
    // other side, and the picture may then use a border pattern at the opposite border.
    val pixels = IntArray2D.create(8, 6) { x, y ->
      when {
        x == 0 || y == 0 -> gold
        x == 5 && y == 3 -> rust
        else -> ink
      }
    }
    val plain = BitmapWaveFunctionCell.createCells(pixels)
    val one = BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true)
    val other = BitmapWaveFunctionCell.createCells(pixels, mirrorVertical = true)
    val both = BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true, mirrorVertical = true)

    assertTrue("one mirror adds patterns: ${one.size} vs ${plain.size}", one.size > plain.size)
    assertTrue("the other adds patterns too: ${other.size} vs ${plain.size}", other.size > plain.size)
    assertTrue("the two together add the half turn as well: ${both.size} vs ${one.size}", both.size > one.size)
    // And the two settings are not the same setting: this drawing's flips are not each other's flips.
    assertNotEquals(
      "the axes are different axes",
      one.map { it.kernel.toList() }.toSet(),
      other.map { it.kernel.toList() }.toSet(),
    )
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

    for (mirrored in listOf(
      BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true),
      BitmapWaveFunctionCell.createCells(pixels, mirrorVertical = true),
      BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true, mirrorVertical = true),
    )) {
      for (cell in mirrored) {
        assertTrue(
          "a mirrored pattern still shows a pixel the drawing has: $cell",
          cell.color in centresDrawn,
        )
      }
    }
  }

  /** The weight a kernel should have with one mirror on: its own count plus its mirror's. */
  private fun weightOf(
    cells: List<BitmapWaveFunctionCell>,
    kernel: List<Int?>,
    mirror: List<Int?>,
  ): Float {
    val own = cells.firstOrNull { it.kernel.toList() == kernel }?.weight ?: 0f
    if (mirror == kernel) return own
    return own + (cells.firstOrNull { it.kernel.toList() == mirror }?.weight ?: 0f)
  }

  @Test
  fun symmetryAndWrappingCompose() {
    // Both settings on: wrapped windows are complete, and their mirrors are complete too.
    val pixels = IntArray2D.create(6, 5) { x, y -> if (x == y) gold else ink }
    val cells = BitmapWaveFunctionCell.createCells(
      pixels,
      wrap = true,
      mirrorHorizontal = true,
      mirrorVertical = true,
    )

    assertTrue("expected patterns", cells.isNotEmpty())
    for (cell in cells) {
      for (direction in Direction2d8.entries) {
        assertTrue("wrapped and mirrored $cell is blank towards $direction", cell.kernelColor(direction) != null)
      }
    }
  }

  @Test
  fun theTallyCarriesTheSettingWithoutResampling() {
    // The mirrors are applied when the tile set is built, not to the tally, so switching them on for the same
    // drawing must give the mirrored set immediately, and switching them off must give the drawing's own set
    // back -- no mirrored window may have leaked into the tally.
    val tally = WindowTally()
    // Gold along the top and the left edge: an L is asymmetric about both axes, and its windows include the
    // blanks that make a drawing have edges, so either flip has something new to add.
    val pixels = IntArray2D.create(8, 5) { x, y -> if (x == 0 || y == 0) gold else ink }
    val plain = tally.sample(pixels)
    val horizontally = tally.sample(pixels, mirrorHorizontal = true)
    val vertically = tally.sample(pixels, mirrorVertical = true)
    val both = tally.sample(pixels, mirrorHorizontal = true, mirrorVertical = true)

    assertTrue("one mirror adds patterns: ${horizontally.size} vs ${plain.size}", horizontally.size > plain.size)
    assertTrue("the other does too: ${vertically.size} vs ${plain.size}", vertically.size > plain.size)
    assertTrue("and both add more: ${both.size} vs ${horizontally.size}", both.size > horizontally.size)
    assertEquals(
      "the tally itself stays the drawing's own windows",
      plain.map { it.kernel.toList() to it.weight },
      tally.sample(pixels).map { it.kernel.toList() to it.weight },
    )
  }

  @Test
  fun thePaintedColoursSurviveMirroring() {
    // Nothing a person drew is invented: every colour in the mirrored tile set came from the drawing.
    val pixels = IntArray2D.create(8, 6) { x, y -> if ((x * y) % 5 == 0) gold else ink }
    val drawnColours = BitmapWaveFunctionCell.createCells(pixels).flatMap { it.kernel.filterNotNull() }.toSet()
    val mirrored = BitmapWaveFunctionCell.createCells(pixels, mirrorHorizontal = true, mirrorVertical = true)

    assertEquals(
      "mirroring must not introduce a colour the drawing does not have",
      drawnColours.size,
      mirrored.flatMap { it.kernel.filterNotNull() }.toSet().size,
    )
  }
}
