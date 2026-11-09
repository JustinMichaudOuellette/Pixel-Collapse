package ca.justinmo.wfcbitmap.output

/**
 * HQ2x: the classic high-quality 2x filter, from the public-domain clean reimplementation by byuu and
 * blargg (a very close, independent version of Maxim Stepin's HQ2x).
 *
 * Each pixel is compared with its eight neighbours in a rough YUV space; the eight close/distant answers
 * form a byte that indexes a 256-entry rule table. The rule says how much of the centre and which two
 * neighbours to mix, so diagonals come out smooth without the blocky stair-steps of the simple filters.
 *
 * The source is one pixel per eight-bit channel (ARGB); the blends run on those channels directly.
 */
internal object Hq2xUpscaler {

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
    val pixels = source.pixels
    // One YUV value per source pixel, instead of recomputing the neighbours' YUV for every pixel.
    val yuvs = IntArray(width * height) { yuv(pixels[it]) }
    for (y in 0 until height) {
      val up = (y - 1).coerceAtLeast(0)
      val down = (y + 1).coerceAtMost(height - 1)
      for (x in 0 until width) {
        val left = (x - 1).coerceAtLeast(0)
        val right = (x + 1).coerceAtMost(width - 1)
        val a = pixels[up * width + left]
        val b = pixels[up * width + x]
        val c = pixels[up * width + right]
        val d = pixels[y * width + left]
        val e = pixels[y * width + x]
        val f = pixels[y * width + right]
        val g = pixels[down * width + left]
        val h = pixels[down * width + x]
        val i = pixels[down * width + right]

        val eYuv = yuvs[y * width + x]
        var pattern = 0
        if (differs(eYuv, yuvs[up * width + left])) pattern = pattern or 0x01
        if (differs(eYuv, yuvs[up * width + x])) pattern = pattern or 0x02
        if (differs(eYuv, yuvs[up * width + right])) pattern = pattern or 0x04
        if (differs(eYuv, yuvs[y * width + left])) pattern = pattern or 0x08
        if (differs(eYuv, yuvs[y * width + right])) pattern = pattern or 0x10
        if (differs(eYuv, yuvs[down * width + left])) pattern = pattern or 0x20
        if (differs(eYuv, yuvs[down * width + x])) pattern = pattern or 0x40
        if (differs(eYuv, yuvs[down * width + right])) pattern = pattern or 0x80

        val at = (y * 2) * outWidth + x * 2
        out[at] = blend(HQ_TABLE[pattern], e, a, b, d, f, h)
        pattern = rotate(pattern)
        out[at + 1] = blend(HQ_TABLE[pattern], e, c, f, b, h, d)
        pattern = rotate(pattern)
        out[at + outWidth + 1] = blend(HQ_TABLE[pattern], e, i, h, f, d, b)
        pattern = rotate(pattern)
        out[at + outWidth] = blend(HQ_TABLE[pattern], e, g, d, h, b, f)
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  /** The rule table: how each of the four output pixels mixes the centre and its neighbours. */
  private val HQ_TABLE = intArrayOf(
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 15, 12, 5, 3, 17, 13,
    4, 4, 6, 18, 4, 4, 6, 18, 5, 3, 12, 12, 5, 3, 1, 12,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 17, 13, 5, 3, 16, 14,
    4, 4, 6, 18, 4, 4, 6, 18, 5, 3, 16, 12, 5, 3, 1, 14,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 19, 12, 12, 5, 19, 16, 12,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 12, 5, 3, 16, 12,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 19, 1, 12, 5, 19, 1, 14,
    4, 4, 6, 2, 4, 4, 6, 18, 5, 3, 16, 12, 5, 19, 1, 14,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 15, 12, 5, 3, 17, 13,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 12, 5, 3, 16, 12,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 17, 13, 5, 3, 16, 14,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 13, 5, 3, 1, 14,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 12, 5, 3, 16, 13,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 12, 5, 3, 1, 12,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 16, 12, 5, 3, 1, 14,
    4, 4, 6, 2, 4, 4, 6, 2, 5, 3, 1, 12, 5, 3, 1, 14,
  )

  private val DIFF_OFFSET = (0x440 shl 21) + (0x207 shl 11) + 0x407
  private val DIFF_MASK = (0x380 shl 21) + (0x1f0 shl 11) + 0x3f0

  /** Rough YUV of the colour, packed so one subtraction compares all three channels at once. */
  private fun yuv(color: Int): Int {
    val r = (color ushr 16) and 0xFF
    val g = (color ushr 8) and 0xFF
    val b = color and 0xFF
    val y = ((r + g + b) * (0.25 * (63.5 / 48.0))).toInt()
    val u = (((r - b) * 0.25 + 128.0) * (7.5 / 7.0)).toInt()
    val v = (((g * 2.0 - r - b) * 0.125 + 128.0) * (7.5 / 6.0)).toInt()
    return (y shl 21) + (u shl 11) + v
  }

  private fun differs(eYuv: Int, neighborYuv: Int): Boolean =
    ((eYuv - neighborYuv + DIFF_OFFSET) and DIFF_MASK) != 0

  private fun same(a: Int, b: Int): Boolean =
    (((yuv(a) - yuv(b) + DIFF_OFFSET) and DIFF_MASK) == 0)

  private fun rotate(n: Int): Int =
    ((n ushr 2) and 0x11) or ((n shl 2) and 0x88) or
      ((n and 0x01) shl 5) or ((n and 0x08) shl 3) or
      ((n and 0x10) ushr 3) or ((n and 0x80) ushr 5)

  /**
   * The rule for one output pixel. [a], [b], [d], [f], [h] are the neighbours in the order the rule
   * expects; the caller rotates both the pattern and the neighbours for the other three pixels.
   */
  private fun blend(rule: Int, e: Int, a: Int, b: Int, d: Int, f: Int, h: Int): Int = when (rule) {
    1 -> blend1(e, a)
    2 -> blend1(e, d)
    3 -> blend1(e, b)
    4 -> blend2(e, d, b)
    5 -> blend2(e, a, b)
    6 -> blend2(e, a, d)
    7 -> blend3(e, b, d)
    8 -> blend3(e, d, b)
    9 -> blend4(e, d, b)
    10 -> blend5(e, d, b)
    11 -> blend6(e, d, b)
    12 -> if (same(b, d)) blend2(e, d, b) else e
    13 -> if (same(b, d)) blend5(e, d, b) else e
    14 -> if (same(b, d)) blend6(e, d, b) else e
    15 -> if (same(b, d)) blend2(e, d, b) else blend1(e, a)
    16 -> if (same(b, d)) blend4(e, d, b) else blend1(e, a)
    17 -> if (same(b, d)) blend5(e, d, b) else blend1(e, a)
    18 -> if (same(b, f)) blend3(e, b, d) else blend1(e, d)
    19 -> if (same(d, h)) blend3(e, d, b) else blend1(e, b)
    else -> e
  }

  private fun blend1(e: Int, a: Int): Int = mix(e, 3, a, 1, 0, 0, 2)

  private fun blend2(e: Int, b: Int, c: Int): Int = mix(e, 2, b, 1, c, 1, 2)

  private fun blend3(e: Int, b: Int, c: Int): Int = mix(e, 5, b, 2, c, 1, 3)

  private fun blend4(e: Int, b: Int, c: Int): Int = mix(e, 6, b, 1, c, 1, 3)

  private fun blend5(e: Int, b: Int, c: Int): Int = mix(e, 2, b, 3, c, 3, 3)

  private fun blend6(e: Int, b: Int, c: Int): Int = mix(e, 14, b, 1, c, 1, 4)

  /** One channel-by-channel weighted average: (a*wa + b*wb + c*wc) / 2^shift. */
  private fun mix(a: Int, wa: Int, b: Int, wb: Int, c: Int, wc: Int, shift: Int): Int {
    val alpha = (((a ushr 24) and 0xFF) * wa + ((b ushr 24) and 0xFF) * wb +
      ((c ushr 24) and 0xFF) * wc) ushr shift
    val red = (((a ushr 16) and 0xFF) * wa + ((b ushr 16) and 0xFF) * wb +
      ((c ushr 16) and 0xFF) * wc) ushr shift
    val green = (((a ushr 8) and 0xFF) * wa + ((b ushr 8) and 0xFF) * wb +
      ((c ushr 8) and 0xFF) * wc) ushr shift
    val blue = ((a and 0xFF) * wa + (b and 0xFF) * wb + (c and 0xFF) * wc) ushr shift
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }
}