package ca.justinmo.wfcbitmap.editor
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * The canonical 9x9 test pattern from AGENTS.md, which a **debug** build opens with.
 *
 * The pattern under discussion is then always on screen when the app starts, instead of being redrawn
 * before every look at a generation, kernel or export problem. Release builds never reach this: the
 * caller checks the installed build's debuggable flag first, so a released app still opens on the
 * restored session or on an empty drawing.
 *
 * Keep the rows identical to AGENTS.md -- `*` filled, `_` empty -- because they are the shared reference
 * for the generation, kernel-grouping and video-seam work.
 */
internal object DebugTestPattern {

  /** The amber the filled cells are drawn in; the same value the instrumented tests use. */
  private val FILLED = 0xFFF2AA4C.toInt()

  /** The near-black background the empty cells are drawn in. */
  private val EMPTY = 0xFF101820.toInt()

  private val ROWS = listOf(
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

  /** The drawing's width in cells. */
  val columns: Int = ROWS.first().length

  /** The drawing's height in cells. */
  val rows: Int = ROWS.size

  /** One ARGB pixel per cell, row-major; what [PixelGridState.restoreDrawing] takes. */
  val pixels: IntArray = IntArray(columns * rows) { index ->
    if (ROWS[index / columns][index % columns] == '*') FILLED else EMPTY
  }

  /**
   * The same pattern using the active palette: filled cells take its **first** colour and the
   * background takes its **darkest** one, matching the editor's own default background.
   */
  fun pixelsFor(palette: List<Color>): IntArray {
    val filled = palette.firstOrNull()?.toArgb() ?: FILLED
    val background = palette.minByOrNull { it.luminance() }?.toArgb() ?: EMPTY
    return IntArray(columns * rows) { index ->
      if (ROWS[index / columns][index % columns] == '*') filled else background
    }
  }
}