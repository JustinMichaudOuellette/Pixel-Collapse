package ca.justinmo.wfcbitmap.output

/**
 * EPX/Scale2x-family upscaling for a saved pixel-art picture.
 *
 * The rules are local: each pixel looks at its four edge neighbours and, where two of them agree and the
 * opposite pair does not, pushes the agreeing colour into the corner. A stair-step becomes a round step
 * without inventing a colour or averaging two pixels into a blur. 3x uses the Scale3x corner rules, 6x is
 * a 3x pass followed by a 2x one, 9x is a 3x pass twice, and 4x/16x are repeated 2x passes.
 *
 * Pure pixels rather than a `Bitmap`, so the same code drives the saved file and can be tested on the JVM.
 */
internal object EpxUpscaler {

  /** Scales [pixels] by an integer factor, at least 1. */
  fun scale(pixels: IntArray, width: Int, height: Int, factor: Int): ScaledImage {
    require(width > 0 && height > 0)
    require(pixels.size >= width * height)
    if (factor <= 1) return ScaledImage(pixels.copyOf(width * height), width, height)

    var image = ScaledImage(pixels.copyOf(width * height), width, height)
    var remaining = factor
    while (remaining > 1) {
      val step = when {
        remaining % 3 == 0 -> 3
        else -> 2
      }
      image = when (step) {
        3 -> scale3(image)
        else -> scale2(image)
      }
      remaining /= step
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
        val a = source.pixels[up * width + x]
        val b = source.pixels[y * width + right]
        val c = source.pixels[y * width + left]
        val d = source.pixels[down * width + x]

        val at = (y * 2) * outWidth + x * 2
        out[at] = if (c == a && c != d && a != b) a else p
        out[at + 1] = if (a == b && a != c && b != d) b else p
        out[at + outWidth] = if (d == c && d != b && c != a) c else p
        out[at + outWidth + 1] = if (b == d && b != a && d != c) d else p
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  private fun scale3(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 3
    val out = IntArray(outWidth * height * 3)
    for (y in 0 until height) {
      val up = (y - 1).coerceAtLeast(0)
      val down = (y + 1).coerceAtMost(height - 1)
      for (x in 0 until width) {
        val left = (x - 1).coerceAtLeast(0)
        val right = (x + 1).coerceAtMost(width - 1)

        val a = source.pixels[up * width + left]
        val b = source.pixels[up * width + x]
        val c = source.pixels[up * width + right]
        val d = source.pixels[y * width + left]
        val e = source.pixels[y * width + x]
        val f = source.pixels[y * width + right]
        val g = source.pixels[down * width + left]
        val h = source.pixels[down * width + x]
        val i = source.pixels[down * width + right]

        var e0 = e
        var e1 = e
        var e2 = e
        var e3 = e
        val e4 = e
        var e5 = e
        var e6 = e
        var e7 = e
        var e8 = e

        if (d == b && d != h && b != f) e0 = d
        if ((d == b && d != h && b != f && e != c) ||
          (b == f && b != d && f != h && e != a)
        ) {
          e1 = b
        }
        if (b == f && b != d && f != h) e2 = f
        if ((h == d && h != f && d != b && e != a) ||
          (d == b && d != h && b != f && e != g)
        ) {
          e3 = d
        }
        if ((b == f && b != d && f != h && e != i) ||
          (f == h && f != b && h != d && e != c)
        ) {
          e5 = f
        }
        if (h == d && h != f && d != b) e6 = d
        if ((f == h && f != b && h != d && e != g) ||
          (h == d && h != f && d != b && e != i)
        ) {
          e7 = h
        }
        if (f == h && f != b && h != d) e8 = f

        val at = (y * 3) * outWidth + x * 3
        out[at] = e0
        out[at + 1] = e1
        out[at + 2] = e2
        out[at + outWidth] = e3
        out[at + outWidth + 1] = e4
        out[at + outWidth + 2] = e5
        out[at + outWidth * 2] = e6
        out[at + outWidth * 2 + 1] = e7
        out[at + outWidth * 2 + 2] = e8
      }
    }
    return ScaledImage(out, outWidth, height * 3)
  }
}