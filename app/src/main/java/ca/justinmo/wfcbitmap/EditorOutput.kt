/**
 * What the picture is and what size it comes out at: its shape, the size it is generated at, the size it is
 * saved at, and the one-off [EditorMessage] a save is reported with.
 *
 * These are settings, not drawing. They change a cell's pixels or the picture's proportions, never the grid the
 * solver fills, so [previewPixelSize] and [savedPixelSize] are the two numbers the output menu shows.
 */

package ca.justinmo.wfcbitmap

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** An output shape the editor offers, as width / height. */
enum class OutputAspect(val label: String, val ratio: Float) {
  Portrait9x16("9:16", 9f / 16f),
  Portrait3x4("3:4", 3f / 4f),
  Square("1:1", 1f),
  Landscape4x3("4:3", 4f / 3f),
  Landscape16x9("16:9", 16f / 9f),
}

/**
 * How many pixels the picture is written at, as a multiple of the generated bitmap.
 *
 * This is a saving decision, not a solving one: the structure of the picture is decided by the grid, so
 * scaling on the way out only changes how big the file is.
 */
enum class OutputScale(val label: String, val numerator: Int, val denominator: Int = 1) {
  /*
   * The biggest end of the list is deliberately further out than the middle steps: the file is a copy of
   * the picture, so it is the one worth paying for once. The bottom of the list is 1x and no lower --
   * a file smaller than the picture on screen is a file the person cannot see the drawing in, and the
   * preview size is the setting that decides how many pixels the picture itself has.
   */
  One("1×", 1),
  Two("2×", 2),
  Three("3×", 3),
  Four("4×", 4),
  Sixteen("16×", 16),
  ;

  /** How many pixels of the written picture there are per pixel of the generated bitmap. */
  val factor: Double get() = numerator.toDouble() / denominator

  /**
   * [value] pixels of the generated bitmap, as pixels of the picture written at this scale.
   *
   * Rounded down rather than to nearest: the size has to stay a whole number of pixels, and rounding down
   * is the only choice that cannot claim a pixel the grid does not have.
   */
  fun pixels(value: Int): Int = (value.toLong() * numerator / denominator).toInt()
}

/** The number of pixels each solved cell is drawn with, whatever the preview resolution. */
private const val PREVIEW_PIXELS_PER_CELL = 4

/**
 * How much detail the preview is generated with.
 *
 * The preview always fills its column, so the setting changes what is in the picture: 1x solves a
 * sixteenth of the base cells (the blockiest picture), 2x a quarter, and 4x the base cells -- today's
 * detail. Every cell is drawn as four pixels in the generated bitmap, so the bitmap grows with the
 * number of cells while the preview stays the same size on screen.
 *
 * The constant names predate the labels; they are kept because sessions and preferences store the enum
 * by name.
 */
enum class PreviewScale(
  val label: String,
  val cellCount: Int,
  val pixelsPerCell: Int = PREVIEW_PIXELS_PER_CELL,
) {
  /** The default: a sixteenth of the base cells, and the coarsest picture. */
  Half("1×", WfcGenerator.DEFAULT_CELL_COUNT / 16),
  /** A quarter of the base cells. */
  One("2×", WfcGenerator.DEFAULT_CELL_COUNT / 4),
  /** The base cell count: the finest picture, and today's detail. */
  Two("4×", WfcGenerator.DEFAULT_CELL_COUNT),
}

/**
 * The pixel size the picture on screen is generated at: the solver grid for [aspect] at the preview's
 * cell count, one cell being [PreviewScale.pixelsPerCell] pixels of it.
 *
 * The shape decides it as much as the resolution does -- the grid keeps the requested cell count
 * whatever the aspect, so a square picture is wider than a 9:16 one at the same resolution -- which is
 * why the menu shows the size beside each option rather than a bare multiplier.
 */
internal fun previewPixelSize(aspect: OutputAspect, preview: PreviewScale): Pair<Int, Int> {
  val (columns, rows) = WfcGenerator.gridForAspect(aspect.ratio, preview.cellCount)
  return columns * preview.pixelsPerCell to rows * preview.pixelsPerCell
}

/**
 * The pixel size the picture is written at, in pixels of the solver grid.
 *
 * The save size is its own scale of the picture, not a multiple of whatever the preview happens to be: a
 * 1× save is the picture as the app has always drawn it, 16× is sixteen times that, and choosing a
 * preview size never moves a number in this list. That is what makes the two settings independent -- the
 * preview decides how the picture is drawn, the save size decides what is written out of it.
 */
internal fun savedPixelSize(aspect: OutputAspect, scale: OutputScale): Pair<Int, Int> {
  val (width, height) = previewPixelSize(aspect, PreviewScale.One)
  return scale.pixels(width) to scale.pixels(height)
}

/**
 * A line the editor shows over the drawing, with an optional button.
 *
 * Two kinds of thing are said over the drawing: what a tool does, and what just happened to the picture.
 * The second can be worth acting on -- after a save, the first thing anyone wants is to see the file that
 * was written -- so a message carries a label and what to run when it is tapped, and is shown for longer
 * than a plain one. A message with no action is just a sentence with nothing to do about it.
 */
data class EditorMessage(
  val text: String,
  /** The button's label, or null for a line that only has to be read. */
  val actionLabel: String? = null,
  /** What the button does; null when there is no button. */
  val onAction: (() -> Unit)? = null,
)

/**
 * What the generated picture is: its shape, the size it is generated at, and the size it is written at.
 */
data class OutputSettings(
  val aspect: OutputAspect = OutputAspect.Portrait9x16,
  val preview: PreviewScale = PreviewScale.Half,
  val scale: OutputScale = OutputScale.One,
)
