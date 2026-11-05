package ca.justinmo.wfcbitmap.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How the final palette matching is spread over neighbouring pixels. */
internal enum class DitherMode(val label: String) {
  None("None"),
  FloydSteinberg("Floyd-Steinberg"),
  Bayer4("Bayer 4×4"),
  Bayer8("Bayer 8×8"),
}

/** The finished camera drawing: one ARGB colour per cell, ready for [PixelGridState]. */
internal data class PhotoPattern(
  val columns: Int,
  val rows: Int,
  val pixels: IntArray,
)

/**
 * The camera-photo to pixel-pattern pipeline.
 *
 * The steps are deliberately separate functions so the tool can show each one, and so each can be
 * tested without a composable:
 *
 *  1. [decode] reads a bounded-size bitmap and applies the camera's EXIF orientation.
 *  2. [cropToAspect] centre-crops it to the output shape.
 *  3. [downscale] reduces it to the drawing's cell grid.
 *  4. [paletteSubset] picks the most-used colours of the selected palette.
 *  5. [posterize] maps every cell to the nearest colour in that subset.
 *  6. [dither] adds error diffusion or an ordered matrix before that nearest-colour choice.
 *
 * Everything after [decode] works on the small grid, so a slider drag does not touch a megapixel
 * bitmap except for the one crop/downscale pass.
 */
internal object PhotoPatternPipeline {

  private const val TAG = "PhotoPattern"

  /** Largest source edge decoded for the preview; the grid is much smaller than this. */
  const val MAX_SOURCE_EDGE = 1800

  /**
   * The narrowest and widest drawing the camera tool offers, in cells.
   *
   * The narrow end is near [MIN_GRID_SIZE]: a photo reduced to eight cells across is the coarsest
   * pattern the editor can still hold, which is the point at which a photograph turns into shapes
   * rather than a picture of itself. The wide end is what a photograph's detail is worth as a
   * *drawing* -- the picture is generated from 3x3 windows of it, so past roughly this size the grid
   * spends its cells on detail the tiles cannot use, and it costs solve time on every edit. At nine by
   * sixteen, ninety-six columns is about sixteen thousand cells.
   */
  const val MIN_COLUMNS = 8
  const val MAX_COLUMNS = 96

  /**
   * Where the downscale slider starts, in cells.
   *
   * The tool replaces the drawing it was opened over, so where the drawing currently ends is not the
   * starting point: a photo reduced to a drawing's usual sixteen cells across reads as a handful of
   * blocks. Sixty-four is the smallest grid where a photograph still reads as a photograph, and it is
   * the size the tool has always started at.
   */
  const val DEFAULT_COLUMNS = 64

  /**
   * The column counts the tool offers for a drawing grouped into [cellSize] cells.
   *
   * Both ends are whole cells: the grid is counted in cells, so a column count the kernel cannot divide
   * would be rounded away the moment it is used, and a slider whose first steps all land on the same
   * drawing is a slider that lies. The low end also never falls below what the editor can hold.
   */
  fun columnRange(cellSize: Int): IntRange {
    val step = cellSize.coerceAtLeast(1)
    val lowest = ((MIN_COLUMNS + step - 1) / step) * step
    val highest = (MAX_COLUMNS / step) * step
    return lowest.coerceAtLeast(MIN_GRID_SIZE * step)..highest.coerceAtLeast(lowest)
  }

  /** The nearest whole-kernel column count to [value], inside [columnRange]. */
  fun nearestColumns(value: Float, cellSize: Int): Int {
    val step = cellSize.coerceAtLeast(1)
    val range = columnRange(step)
    return ((value / step).roundToInt() * step).coerceIn(range.first, range.last)
  }

  /**
   * The drawing size for a chosen number of columns.
   *
   * Rows follow the output shape. Both sides are rounded to whole kernel cells, so a grouped drawing
   * needs no silent padding when it is applied, and kept inside the editor's grid limits.
   */
  fun targetSize(columns: Int, aspect: Float, cellSize: Int): Pair<Int, Int> {
    val safeAspect = if (aspect.isFinite() && aspect > 0.01f) aspect else 1f
    val step = cellSize.coerceAtLeast(1)
    val minimum = MIN_GRID_SIZE * step
    val maximum = (MAX_GRID_SIZE / step) * step
    fun rounded(value: Int): Int =
      (((value + step / 2) / step) * step).coerceIn(minimum, maximum)

    val width = rounded(columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS))
    val height = rounded((width / safeAspect).roundToInt())
    return width to height
  }

  /**
   * Reads [uri] at no more than [maxEdge] pixels on its longest side, and turns the pixels upright.
   *
   * The EXIF orientation is applied by hand: BitmapFactory ignores it, and a photo taken in portrait is
   * frequently stored sideways.
   */
  fun decode(context: Context, uri: Uri, maxEdge: Int = MAX_SOURCE_EDGE): Bitmap? {
    val resolver = context.contentResolver
    val byteCount = runCatching {
      resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull()
    Log.i(TAG, "decode $uri (${byteCount?.let { "$it bytes" } ?: "size unknown"}, maxEdge $maxEdge)")

    // The bounds pass only fills [bounds]. BitmapFactory returns null whenever inJustDecodeBounds is
    // set, so that pass's result must be ignored rather than read as the decoded bitmap.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val source = runCatching { resolver.openInputStream(uri) }.getOrElse {
      Log.w(TAG, "could not open $uri", it)
      return null
    }
    if (source == null) {
      Log.w(TAG, "no stream for $uri")
      return null
    }
    val boundsRead = runCatching { source.use { BitmapFactory.decodeStream(it, null, bounds) } }
    if (boundsRead.isFailure) {
      Log.w(TAG, "could not read the size of $uri", boundsRead.exceptionOrNull())
      return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      Log.w(TAG, "no image in $uri: ${bounds.outWidth}x${bounds.outHeight}")
      return null
    }

    val largest = max(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (largest / (sample * 2) >= maxEdge) sample *= 2

    val options = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = runCatching {
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrElse {
      Log.w(TAG, "could not decode $uri", it)
      return null
    }
    if (decoded == null) {
      Log.w(TAG, "the decoder returned no bitmap for ${bounds.outWidth}x${bounds.outHeight} at sample $sample")
      return null
    }

    val orientation = runCatching {
      resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      }
    }.getOrElse {
      Log.w(TAG, "no EXIF orientation for $uri", it)
      ExifInterface.ORIENTATION_NORMAL
    } ?: ExifInterface.ORIENTATION_NORMAL

    val upright = upright(decoded, orientation)
    Log.i(
      TAG,
      "decoded ${bounds.outWidth}x${bounds.outHeight} at sample $sample, orientation $orientation, " +
        "upright ${upright.width}x${upright.height}",
    )
    return upright
  }

  private fun upright(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
      else -> return source
    }
    val turned = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (turned !== source) source.recycle()
    return turned
  }

  /** Centre-crops [source] to [aspect] (width / height), or returns it when it already matches. */
  fun cropToAspect(source: Bitmap, aspect: Float): Bitmap {
    if (!aspect.isFinite() || aspect <= 0.01f) return source
    val sourceAspect = source.width.toFloat() / source.height
    if (abs(sourceAspect - aspect) < 0.001f) return source
    val width: Int
    val height: Int
    if (sourceAspect > aspect) {
      height = source.height
      width = (height * aspect).roundToInt().coerceIn(1, source.width)
    } else {
      width = source.width
      height = (width / aspect).roundToInt().coerceIn(1, source.height)
    }
    val left = (source.width - width) / 2
    val top = (source.height - height) / 2
    return Bitmap.createBitmap(source, left, top, width, height)
  }

  /** Box/bilinear-filtered reduction to exactly [columns] x [rows], as ARGB pixels. */
  fun downscale(source: Bitmap, columns: Int, rows: Int): IntArray {
    require(columns > 0 && rows > 0)
    val scaled = if (source.width == columns && source.height == rows) {
      source
    } else {
      Bitmap.createScaledBitmap(source, columns, rows, true)
    }
    val pixels = IntArray(columns * rows)
    scaled.getPixels(pixels, 0, columns, 0, 0, columns, rows)
    if (scaled !== source) scaled.recycle()
    return pixels
  }

  /**
   * The [count] colours of [palette] that the photo uses most, kept in palette order.
   *
   * Counting is done against the full palette first. Taking the most-used entries rather than the
   * first `count` entries means a palette with a useful red near the end still gets that red when the
   * photo is mostly red.
   */
  fun paletteSubset(pixels: IntArray, palette: IntArray, count: Int): IntArray {
    if (palette.isEmpty()) return palette
    val wanted = count.coerceIn(1, palette.size)
    if (wanted == palette.size) return palette.copyOf()
    val usage = IntArray(palette.size)
    for (pixel in pixels) usage[closestIndex(pixel, palette)]++
    val chosen = (0 until palette.size)
      .sortedWith(compareByDescending<Int> { usage[it] }.thenBy { it })
      .take(wanted)
      .sorted()
    return IntArray(chosen.size) { palette[chosen[it]] }
  }

  /** Maps every pixel to the nearest colour in [subset], with no dither. */
  fun posterize(pixels: IntArray, subset: IntArray): IntArray {
    if (subset.isEmpty()) return pixels.copyOf()
    return IntArray(pixels.size) {
      val index = closestIndex(pixels[it], subset)
      (0xFF shl 24) or (subset[index] and 0xFFFFFF)
    }
  }

  /**
   * Quantises to [subset], spreading the quantisation error with [mode] and [strength].
   *
   * At strength 0, and for [DitherMode.None], the answer is exactly [posterize].
   */
  fun dither(
    pixels: IntArray,
    width: Int,
    height: Int,
    subset: IntArray,
    mode: DitherMode,
    strength: Float,
  ): IntArray {
    require(width > 0 && height > 0 && pixels.size >= width * height)
    val amount = strength.coerceIn(0f, 1f)
    if (subset.isEmpty() || mode == DitherMode.None || amount <= 0.0001f) {
      return posterize(pixels, subset)
    }
    return when (mode) {
      DitherMode.None -> posterize(pixels, subset)
      DitherMode.FloydSteinberg -> floydSteinberg(pixels, width, height, subset, amount)
      DitherMode.Bayer4 -> ordered(pixels, width, subset, BAYER_4, amount)
      DitherMode.Bayer8 -> ordered(pixels, width, subset, BAYER_8, amount)
    }
  }

  /**
   * Serpentine Floyd-Steinberg.
   *
   * Serpentine because a constant left-to-right scan puts every error streak in the same direction,
   * which is very visible on a smooth gradient.
   */
  private fun floydSteinberg(
    pixels: IntArray,
    width: Int,
    height: Int,
    subset: IntArray,
    strength: Float,
  ): IntArray {
    val error = FloatArray(pixels.size * 3)
    val output = IntArray(pixels.size)
    for (row in 0 until height) {
      val leftToRight = row % 2 == 0
      val start = if (leftToRight) 0 else width - 1
      val end = if (leftToRight) width else -1
      val step = if (leftToRight) 1 else -1
      var column = start
      while (column != end) {
        val index = row * width + column
        val pixel = pixels[index]
        val red = (((pixel ushr 16) and 0xFF) + error[index * 3]).coerceIn(0f, 255f)
        val green = (((pixel ushr 8) and 0xFF) + error[index * 3 + 1]).coerceIn(0f, 255f)
        val blue = ((pixel and 0xFF) + error[index * 3 + 2]).coerceIn(0f, 255f)
        val chosen = nearest(red, green, blue, subset)
        output[index] = (0xFF shl 24) or chosen
        val errorRed = (red - ((chosen ushr 16) and 0xFF)) * strength
        val errorGreen = (green - ((chosen ushr 8) and 0xFF)) * strength
        val errorBlue = (blue - (chosen and 0xFF)) * strength

        fun spread(targetColumn: Int, weight: Float) {
          if (targetColumn !in 0 until width) return
          val target = (row * width + targetColumn) * 3
          error[target] += errorRed * weight
          error[target + 1] += errorGreen * weight
          error[target + 2] += errorBlue * weight
        }
        spread(column + step, 7f / 16f)
        if (row + 1 < height) {
          spread(column - step, 3f / 16f)
          spread(column, 5f / 16f)
          spread(column + step, 1f / 16f)
        }
        column += step
      }
    }
    return output
  }

  private fun ordered(
    pixels: IntArray,
    width: Int,
    subset: IntArray,
    matrix: Array<IntArray>,
    strength: Float,
  ): IntArray {
    val size = matrix.size
    val levels = (size * size).toFloat()
    // A moderate amplitude: strong enough to break banding, small enough that the palette still reads.
    val amplitude = 56f * strength
    return IntArray(pixels.size) { index ->
      val x = index % width
      val y = index / width
      val offset = ((matrix[y % size][x % size] + 0.5f) / levels - 0.5f) * amplitude
      val pixel = pixels[index]
      val red = (((pixel ushr 16) and 0xFF) + offset).coerceIn(0f, 255f)
      val green = (((pixel ushr 8) and 0xFF) + offset).coerceIn(0f, 255f)
      val blue = ((pixel and 0xFF) + offset).coerceIn(0f, 255f)
      (0xFF shl 24) or nearest(red, green, blue, subset)
    }
  }

  private fun nearest(red: Float, green: Float, blue: Float, subset: IntArray): Int {
    var best = subset[0]
    var bestDistance = Float.MAX_VALUE
    for (i in subset.indices) {
      val candidate = subset[i]
      val dr = red - ((candidate ushr 16) and 0xFF)
      val dg = green - ((candidate ushr 8) and 0xFF)
      val db = blue - (candidate and 0xFF)
      val distance = dr * dr + dg * dg + db * db
      if (distance < bestDistance) {
        bestDistance = distance
        best = candidate
      }
    }
    return best and 0xFFFFFF
  }

  private fun closestIndex(pixel: Int, palette: IntArray): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    val red = (pixel ushr 16) and 0xFF
    val green = (pixel ushr 8) and 0xFF
    val blue = pixel and 0xFF
    for (i in palette.indices) {
      val candidate = palette[i]
      val dr = red - ((candidate ushr 16) and 0xFF)
      val dg = green - ((candidate ushr 8) and 0xFF)
      val db = blue - (candidate and 0xFF)
      val distance = dr * dr + dg * dg + db * db
      if (distance < bestDistance) {
        bestDistance = distance
        bestIndex = i
      }
    }
    return bestIndex
  }

  private val BAYER_4 = arrayOf(
    intArrayOf(0, 8, 2, 10),
    intArrayOf(12, 4, 14, 6),
    intArrayOf(3, 11, 1, 9),
    intArrayOf(15, 7, 13, 5),
  )

  private val BAYER_8 = arrayOf(
    intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
    intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
    intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
    intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
    intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
    intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
    intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
    intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
  )
}
