/**
 * What the picture is and what size it comes out at: its shape, the size it is generated at, the size it is
 * saved at, and the one-off [EditorMessage] a save is reported with.
 *
 * These are settings, not drawing. They change a cell's pixels or the picture's proportions, never the grid the
 * solver fills, so [previewPixelSize] and [savedPixelSize] are the two numbers the output menu shows.
 */

package ca.justinmo.wfcbitmap.output

import ca.justinmo.wfcbitmap.output.*

/** An output shape the editor offers, as width / height. */
enum class OutputAspect(val label: String, val ratio: Float) {
  Portrait9x16("9:16", 9f / 16f),
  Portrait3x4("3:4", 3f / 4f),
  Square("1:1", 1f),
  Landscape4x3("4:3", 4f / 3f),
  Landscape16x9("16:9", 16f / 9f),
}

/**
 * How a saved picture's pixels are enlarged.
 *
 * [Nearest] copies each block as it is. [Epx] is the EPX/Scale2x family: it looks at the four neighbours
 * of every pixel and rounds the stair-steps where two edges meet. [Scale3x] does the same on a 3x grid.
 * [Eagle] takes a corner from the diagonal neighbour when that neighbour matches both edges of the
 * corner. [Smooth] blends a corner halfway towards the two matching neighbours. [Hq2x] compares each
 * pixel with its neighbours in YUV and mixes according to a 256-entry rule table. [Sai], [SuperSai] and
 * [SuperEagle] are the 2xSaI family, which blends a corner from its surroundings instead of picking one
 * existing colour.
 */
enum class PixelArtScaler(val label: String) {
  Nearest("Blocks"),
  Epx("EPX"),
  Scale3x("Scale3x"),
  Eagle("Eagle"),
  Smooth("Smooth"),
  Hq2x("HQ2x"),
  Sai("2xSaI"),
  SuperSai("Super 2xSaI"),
  SuperEagle("Super Eagle"),
  ;

  /**
   * Which save sizes this algorithm can make. Blocks can make any size. EPX doubles, so it has 2x, 4x
   * and 16x, while Scale3x starts at 3x and can be repeated (6x is 3x then 2x, 9x is 3x twice). The
   * others are 2x passes, so they make 2x, 4x and 16x by repeating.
   */
  val supportedScales: Set<OutputScale>
    get() = when (this) {
      Nearest -> OutputScale.entries.toSet()
      Epx -> setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen)
      Scale3x -> setOf(OutputScale.Three, OutputScale.Six, OutputScale.Nine)
      Eagle, Smooth, Hq2x, Sai, SuperSai, SuperEagle ->
        setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen)
    }
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
  Six("6×", 6),
  Nine("9×", 9),
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

/**
 * How much detail the preview is generated with.
 *
 * Each step is a quarter of the 4× size: 1× is 270×480 for 9:16, 2× is 540×960, 3× is 810×1440 and
 * 4× is 1080×1920. The other shapes follow the same quarter steps from their own 4× size (see
 * [maxPreviewSize]). The generated bitmap is exactly this size; the solver grid is that size divided
 * by a whole number of pixels per cell (see [previewGrid]).
 *
 * The constant names predate the labels; they are kept because sessions and preferences store the enum
 * by name. `Two` is still the 4× option it has always been, so stored sessions keep their detail.
 */
enum class PreviewScale(val label: String, val factor: Int) {
  /** 1×: a quarter of the 4× width and height. */
  Half("1×", 1),
  /** 2×: half of the 4× size. */
  One("2×", 2),
  /** 3×: three quarters of the 4× size. */
  Three("3×", 3),
  /** 4×: the maximum preview size. */
  Two("4×", 4),
}

/**
 * The 4× preview size for each shape: 1080 pixels on the short side, with the given long side.
 *
 * These are the numbers the output menu shows; every smaller preview is this size times 1/4, 2/4 or
 * 3/4, rounded to a whole pixel.
 */
private fun maxPreviewSize(aspect: OutputAspect): Pair<Int, Int> = when (aspect) {
  OutputAspect.Portrait9x16 -> 1080 to 1920
  OutputAspect.Portrait3x4 -> 1080 to 1440
  OutputAspect.Square -> 1080 to 1080
  OutputAspect.Landscape4x3 -> 1080 to 810
  OutputAspect.Landscape16x9 -> 1080 to 608
}

/**
 * The solver grid and pixels-per-cell that draw [aspect] at [preview]'s exact pixel size.
 *
 * The step is chosen so the bitmap is exactly the preview's size: four pixels a cell when four divides
 * both sides, otherwise the largest divisor of both that does. 4× 9:16 is 270×480 cells at 4 pixels;
 * 1× 9:16 is 90×160 cells at 3 because 270 is not divisible by 4.
 */
internal data class PreviewGrid(
  val columns: Int,
  val rows: Int,
  val pixelsPerCell: Int,
)

/** The classic step: four output pixels per cell, i.e. one drawing pixel per kernel cell at four. */
internal const val CLASSIC_PIXELS_PER_CELL = 4

/**
 * The square a cell is drawn in, so that the bitmap measures exactly the preview's pixel size.
 *
 * Preference order:
 *
 *  1. A step of at most four pixels that divides **both** sides and is a whole number of cells. Four
 *     pixels is the classic look and it is used wherever it fits: 3x3 and 6x6 draw one and two pixel
 *     cells at four, 12x12 draws four-pixel cells one to one.
 *  2. The smallest step above four that is a whole number of cells and divides both sides: 9x9 is three
 *     pixels, 15x15 five, 18x18 six.
 *  3. When no whole-cell step divides the preview, a step smaller than the cell -- the picture is the
 *     right size and a block gives up some of its pixels. Downsampling is uniform, so the tiling stays
 *     intact; stretching a block up to a step it does not divide tears it away from its neighbours.
 *  4. Only when even that is impossible is the cell itself kept, leaving the bitmap up to `cell - 1`
 *     pixels short: a whole-cell picture that is a pixel narrow beats one that is the right size and
 *     smeared.
 */
internal fun previewStep(width: Int, height: Int, cellSize: Int): Int {
  val cell = cellSize.coerceAtLeast(1)
  fun divides(step: Int) = step > 0 && width % step == 0 && height % step == 0

  for (step in CLASSIC_PIXELS_PER_CELL downTo 1) {
    if (divides(step) && step % cell == 0) return step
  }
  var multiple = ((CLASSIC_PIXELS_PER_CELL / cell) + 1) * cell
  val limit = minOf(width, height)
  while (multiple <= limit) {
    if (divides(multiple)) return multiple
    multiple += cell
  }
  for (step in CLASSIC_PIXELS_PER_CELL downTo 1) {
    if (divides(step) && step <= cell) return step
  }
  return cell
}

internal fun previewGrid(aspect: OutputAspect, preview: PreviewScale): PreviewGrid {
  val (width, height) = previewPixelSize(aspect, preview)
  // Each step solves a quarter of the width/height of the one above it: 4× is the full preview grid,
  // 3× three quarters, 2× half and 1× a quarter. The step keeps the bitmap exactly the preview's size
  // while making the lower steps visibly blockier (and much cheaper).
  val step = previewStep(width, height, cellSize = 1)
  return PreviewGrid(
    columns = (width / step).coerceAtLeast(1),
    rows = (height / step).coerceAtLeast(1),
    pixelsPerCell = step,
  )
}

/**
 * The pixel size the picture on screen is generated at.
 *
 * Starting from the 4× size, 3× is three quarters, 2× a half and 1× a quarter, each side rounded to
 * the nearest whole pixel (so 4:3's 810-pixel height gives 608 at 3× and 203 at 1×).
 */
internal fun previewPixelSize(aspect: OutputAspect, preview: PreviewScale): Pair<Int, Int> {
  val (maxWidth, maxHeight) = maxPreviewSize(aspect)
  fun quarter(value: Int): Int = ((value.toLong() * preview.factor + 2) / 4).toInt()
  return quarter(maxWidth) to quarter(maxHeight)
}

/**
 * The pixel size the picture is written at.
 *
 * This is a multiple of the **selected preview**, so the numbers in the upscaler menu line up with what
 * is on screen: at 2× preview, 2× save is twice 540×960 rather than a separately based size.
 */
internal fun savedPixelSize(
  aspect: OutputAspect,
  scale: OutputScale,
  preview: PreviewScale,
): Pair<Int, Int> {
  val (width, height) = previewPixelSize(aspect, preview)
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
 * How big one pattern is, counted in drawing pixels.
 *
 * The engine's kernel is always 3x3 cells. [Three] makes each cell one pixel; [Six] makes each cell
 * 2x2 pixels (a 6x6 pattern), [Nine] makes them 3x3, and so on up to [TwentyFour], whose cells are
 * 8x8 pixels. Larger cells mean the drawing is grouped into blocks before tiling, and the grid shows
 * one separator per cell.
 */
enum class KernelSize(val pixels: Int, val label: String) {
  Three(3, "3×3"),
  Six(6, "6×6"),
  Nine(9, "9×9"),
  Twelve(12, "12×12"),
  Fifteen(15, "15×15"),
  Eighteen(18, "18×18"),
  TwentyOne(21, "21×21"),
  TwentyFour(24, "24×24"),
  ;

  /** Side of one cell of the 3x3 kernel: a 6x6 pattern is three 2x2 cells across. */
  val cellSize: Int get() = pixels / Three.pixels

  /** Whether this size groups pixels into cells instead of using one pixel per kernel cell. */
  val grouped: Boolean get() = cellSize > 1
}

/**
 * What the generated picture is: its shape, the size it is generated at, and the size it is written at.
 */
data class OutputSettings(
  val aspect: OutputAspect = OutputAspect.Portrait9x16,
  val preview: PreviewScale = PreviewScale.One,
  val scale: OutputScale = OutputScale.Two,
  val scaler: PixelArtScaler = PixelArtScaler.Eagle,
  val kernel: KernelSize = KernelSize.Three,
) {
  /** The same settings with a save size the chosen scaler can actually make. */
  fun withSupportedScale(): OutputSettings {
    if (scale in scaler.supportedScales) return this
    val supported = OutputScale.entries.firstOrNull { it in scaler.supportedScales } ?: scale
    return copy(scale = supported)
  }
}

/**
 * The preview size a build opens with.
 *
 * The default is [PreviewScale.One], the 2× size; a debug build opens there too, so a generation,
 * kernel or seam problem is looked at with a normal-sized preview. The size can be changed while the
 * app runs; only a restored session's stored choice overrides the default.
 */
internal fun startupPreview(stored: PreviewScale, debugBuild: Boolean): PreviewScale =
  if (debugBuild) PreviewScale.One else stored