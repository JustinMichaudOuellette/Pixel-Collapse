package ca.justinmo.wfcbitmap

/**
 * The drawing that was open in the editor when the 9x9 grouping was reported as rendering its tiles
 * wrongly: a 27x15 pixel-font "BACON" in the editor's dark navy and orange.
 *
 * It is here as a fixture rather than the AGENTS.md test pattern because it is nothing like it: 27x15
 * is not square, the pattern is a row of letterforms with empty rows above and below, and 27 and 15
 * are both a whole number of 3-pixel cells, so with the 9x9 kernel it reduces to a 9x5 grid of cells
 * whose blocks are all different fragments of text. A rendering bug that only shows up when a cell is
 * smaller than the square it is drawn into is easiest to see on exactly this drawing.
 */
internal object BaconPattern {

  const val columns = 27
  const val rows = 15

  const val BACKGROUND = 0xFF181425.toInt()
  const val INK = 0xFFBE4A2F.toInt()

  /** One character per pixel, row-major: `*` is ink, `_` is the background. */
  private val glyphs = arrayOf(
    "___________________________",
    "___________________________",
    "___________________________",
    "___**__***_***_***_*___*___",
    "___*_*_*_*_*___*_*_**__*___",
    "___**__***_*___*_*_*_*_*___",
    "___*_*_*_*_*___*_*_*__**___",
    "___**__*_*_***_***_*___*___",
    "___________________________",
    "___________________________",
    "___________________________",
    "___________________________",
    "___________________________",
    "___________________________",
    "___________________________",
  )

  /** One ARGB pixel per cell, row-major; what the editor's grid holds. */
  val pixels: IntArray = IntArray(columns * rows) { index ->
    val glyph = glyphs[index / columns][index % columns]
    if (glyph == '*') INK else BACKGROUND
  }
}
