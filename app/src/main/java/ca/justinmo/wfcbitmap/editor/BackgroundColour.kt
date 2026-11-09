package ca.justinmo.wfcbitmap.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import ca.justinmo.wfcbitmap.editor.*

/**
 * How many times a cell on the drawing's edge counts when looking for its background.
 *
 * Four, because a background is *at* the edge: a margin two or three cells deep is still the paper,
 * while a subject that fills the middle is not. An interior four times the size of the ring is where
 * the two counts cross, which is about the point where a plain majority stops being a fair description
 * of "the drawing is on this colour".
 */
internal const val EDGE_WEIGHT = 4

/**
 * The colour that stands out against [colour]: white on a dark one, black on a light one.
 *
 * Used for the shape being dragged out, which is drawn over the picture rather than into it: the
 * person's own colour would vanish into a drawing that is mostly that colour, and a translucent white
 * disappears over anything pale. This one is the background's opposite, whichever way round it is.
 */
internal fun contrastColour(colour: Color): Color =
  if (colour.luminance() < CONTRAST_SPLIT) Color.White else Color.Black

/** Where a colour stops being dark: the middle of the luminance range. */
private const val CONTRAST_SPLIT = 0.5f

/**
 * The colour the drawing is on: its most common cell colour, with the edge counted [EDGE_WEIGHT] times.
 *
 * A plain count would call a large shape the background -- a pattern whose subject covers most of the
 * canvas has more of that colour than of the margin around it -- and then new rows and columns would be
 * filled with the subject's colour, which is the one colour they must not be. The margin is where a
 * background is, so cells on the border count for more, and a drawing that really is mostly one colour
 * still comes out as that colour because every one of its cells is counted at least once.
 *
 * Ties are broken by the plain count and then by colour value, so the answer does not depend on which
 * colour happened to be seen first.
 *
 * @param fallback what to answer for a drawing with no cells, or smaller than [columns] x [rows].
 */
internal fun backgroundColour(
  pixels: List<Color>,
  columns: Int,
  rows: Int,
  fallback: Color,
): Color {
  if (columns <= 0 || rows <= 0) return fallback
  // A list that does not describe the grid says nothing trustworthy about its background.
  if (pixels.size < columns * rows) return fallback
  val weighted = HashMap<Color, Int>()
  val plain = HashMap<Color, Int>()
  for (row in 0 until rows) {
    val edgeRow = row == 0 || row == rows - 1
    for (column in 0 until columns) {
      val colour = pixels.getOrNull(row * columns + column) ?: continue
      val edge = edgeRow || column == 0 || column == columns - 1
      weighted[colour] = (weighted[colour] ?: 0) + if (edge) EDGE_WEIGHT else 1
      plain[colour] = (plain[colour] ?: 0) + 1
    }
  }
  if (plain.isEmpty()) return fallback
  return weighted.entries.maxWithOrNull(
    compareBy<Map.Entry<Color, Int>> { it.value }
      .thenBy { plain[it.key] ?: 0 }
      .thenBy { it.key.toArgb() },
  )?.key ?: fallback
}
