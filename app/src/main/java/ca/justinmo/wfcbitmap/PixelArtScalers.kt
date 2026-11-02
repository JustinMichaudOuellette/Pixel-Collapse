package ca.justinmo.wfcbitmap

/** An image as row-major ARGB pixels, after scaling. */
internal data class ScaledImage(val pixels: IntArray, val width: Int, val height: Int)

/**
 * Dispatches a scaling pass to the algorithm [PixelArtScaler] names.
 *
 * Every algorithm works on plain pixels, so the same code drives the saved file and the on-screen
 * preview and can be tested on the JVM. [PixelArtScaler.Nearest] is not scaled here: the caller's bitmap
 * fit is already nearest-neighbour, so [scale] returns a copy for it.
 */
internal object PixelScaler {
  fun scale(
    pixels: IntArray,
    width: Int,
    height: Int,
    scaler: PixelArtScaler,
    factor: Int,
  ): ScaledImage = when (scaler) {
    PixelArtScaler.Nearest -> {
      require(factor == 1) { "Blocks is resampled by the bitmap fit, not by this scaler" }
      ScaledImage(pixels.copyOf(width * height), width, height)
    }
    PixelArtScaler.Epx, PixelArtScaler.Scale3x -> EpxUpscaler.scale(pixels, width, height, factor)
    PixelArtScaler.Eagle -> EagleUpscaler.scale(pixels, width, height, factor)
    PixelArtScaler.Smooth -> SmoothUpscaler.scale(pixels, width, height, factor)
    PixelArtScaler.Hq2x -> Hq2xUpscaler.scale(pixels, width, height, factor)
    PixelArtScaler.Sai -> SaiUpscaler.scale(pixels, width, height, factor, SaiUpscaler.Variant.SaI)
    PixelArtScaler.SuperSai ->
      SaiUpscaler.scale(pixels, width, height, factor, SaiUpscaler.Variant.SuperSaI)
    PixelArtScaler.SuperEagle ->
      SaiUpscaler.scale(pixels, width, height, factor, SaiUpscaler.Variant.SuperEagle)
  }
}

/**
 * Eagle's 2x rules: a corner takes the diagonal neighbour when that neighbour also matches both edges of
 * the corner. Where the pattern is not an L the pixel stays as it is, so the result is the same input
 * colours with the staircase knocked off the inside corners.
 */
internal object EagleUpscaler {
  fun scale(pixels: IntArray, width: Int, height: Int, factor: Int): ScaledImage {
    requirePowerOfTwo(pixels, width, height, factor)
    var image = ScaledImage(pixels.copyOf(width * height), width, height)
    var remaining = factor
    while (remaining > 1) {
      image = scale2(image)
      remaining /= 2
    }
    return image
  }

  private fun scale2(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 2
    val out = IntArray(outWidth * height * 2)
    for (y in 0 until height) {
      val up = (y - 1).coerceAtLeast(0)
      val down = (y + 1).coerceAtMost(height - 1)
      for (x in 0 until width) {
        val left = (x - 1).coerceAtLeast(0)
        val right = (x + 1).coerceAtMost(width - 1)
        val p = source.pixels[y * width + x]
        val n = source.pixels[up * width + x]
        val s = source.pixels[down * width + x]
        val w = source.pixels[y * width + left]
        val e = source.pixels[y * width + right]
        val nw = source.pixels[up * width + left]
        val ne = source.pixels[up * width + right]
        val sw = source.pixels[down * width + left]
        val se = source.pixels[down * width + right]

        val at = (y * 2) * outWidth + x * 2
        out[at] = if (nw == n && nw == w) nw else p
        out[at + 1] = if (ne == n && ne == e) ne else p
        out[at + outWidth] = if (sw == s && sw == w) sw else p
        out[at + outWidth + 1] = if (se == s && se == e) se else p
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }
}

/**
 * A soft 2x pass in the spirit of hq2x's corner blending: when the two neighbours on a corner agree and
 * differ from the centre, the corner is blended halfway towards them. Straight edges and flat areas are
 * left untouched, so the picture stays pixel art rather than becoming a blurred photograph.
 */
internal object SmoothUpscaler {
  fun scale(pixels: IntArray, width: Int, height: Int, factor: Int): ScaledImage {
    requirePowerOfTwo(pixels, width, height, factor)
    var image = ScaledImage(pixels.copyOf(width * height), width, height)
    var remaining = factor
    while (remaining > 1) {
      image = scale2(image)
      remaining /= 2
    }
    return image
  }

  private fun scale2(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 2
    val out = IntArray(outWidth * height * 2)
    for (y in 0 until height) {
      val up = (y - 1).coerceAtLeast(0)
      val down = (y + 1).coerceAtMost(height - 1)
      for (x in 0 until width) {
        val left = (x - 1).coerceAtLeast(0)
        val right = (x + 1).coerceAtMost(width - 1)
        val p = source.pixels[y * width + x]
        val n = source.pixels[up * width + x]
        val s = source.pixels[down * width + x]
        val w = source.pixels[y * width + left]
        val e = source.pixels[y * width + right]

        val at = (y * 2) * outWidth + x * 2
        out[at] = if (n == w && n != p) blendHalf(p, n) else p
        out[at + 1] = if (n == e && n != p) blendHalf(p, n) else p
        out[at + outWidth] = if (s == w && s != p) blendHalf(p, s) else p
        out[at + outWidth + 1] = if (s == e && s != p) blendHalf(p, s) else p
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  /** The average of two ARGB pixels, channel by channel, alpha included. */
  private fun blendHalf(a: Int, b: Int): Int {
    val alpha = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF)) / 2
    val red = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF)) / 2
    val green = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF)) / 2
    val blue = ((a and 0xFF) + (b and 0xFF)) / 2
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }
}

/** Eagle and Smooth are repeated 2x passes, so the factor has to be one or a power of two. */
internal fun requirePowerOfTwo(pixels: IntArray, width: Int, height: Int, factor: Int) {
  require(width > 0 && height > 0) { "The picture has to have pixels" }
  require(pixels.size >= width * height) { "The pixel array is smaller than the picture" }
  require(factor >= 1 && (factor and (factor - 1)) == 0) {
    "This scaler doubles, so the factor has to be a power of two"
  }
}
