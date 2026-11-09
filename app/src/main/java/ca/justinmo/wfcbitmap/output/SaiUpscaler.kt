package ca.justinmo.wfcbitmap.output

/**
 * The 2xSaI family: 2xSaI, Super 2xSaI and Super Eagle.
 *
 * Where EPX picks a neighbour's colour at a corner, these algorithms also blend: a diagonal edge that
 * does not resolve into one clear colour is mixed from the four pixels around the corner. That makes
 * curves and anti-aliased-looking edges smoother, at the cost of introducing a few blended colours.
 *
 * [Variant.SaI] looks at a 4x4 window around each pixel and chooses between the centre, an edge
 * neighbour and two interpolations. [Variant.SuperSaI] adds the two rows and columns beyond that window
 * to decide which diagonal owns the corner. [Variant.SuperEagle] is the Eagle branch structure with the
 * same four-way blending, which softens corners rather than replacing them outright.
 */
internal object SaiUpscaler {

  enum class Variant { SaI, SuperSaI, SuperEagle }

  fun scale(
    pixels: IntArray,
    width: Int,
    height: Int,
    factor: Int,
    variant: Variant,
  ): ScaledImage {
    requirePowerOfTwo(pixels, width, height, factor)
    var image = ScaledImage(pixels.copyOf(width * height), width, height)
    var remaining = factor
    while (remaining > 1) {
      image = when (variant) {
        Variant.SaI -> scale2Sai(image)
        Variant.SuperSaI -> scale2SuperSai(image)
        Variant.SuperEagle -> scale2SuperEagle(image)
      }
      remaining /= 2
    }
    return image
  }

  /** A 4x4 neighbourhood read with edge replication, so borders scale like the picture's edge. */
  private class Reader(val pixels: IntArray, val width: Int, val height: Int) {
    fun at(x: Int, y: Int): Int =
      pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
  }

  private fun scale2Sai(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 2
    val out = IntArray(outWidth * height * 2)
    val r = Reader(source.pixels, width, height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val a = r.at(x, y)
        val b = r.at(x + 1, y)
        val c = r.at(x, y + 1)
        val d = r.at(x + 1, y + 1)
        val e = r.at(x, y - 1)
        val f = r.at(x + 1, y - 1)
        val g = r.at(x - 1, y)
        val h = r.at(x - 1, y + 1)
        val i = r.at(x - 1, y - 1)
        val j = r.at(x + 2, y - 1)
        val k = r.at(x + 2, y)
        val l = r.at(x + 2, y + 1)
        val m = r.at(x - 1, y + 2)
        val n = r.at(x, y + 2)
        val o = r.at(x + 1, y + 2)
        val p = r.at(x + 2, y + 2)

        val product: Int
        val product1: Int
        val product2: Int
        if (a == d && b != c) {
          product = if ((a == e && b == l) || (a == c && a == f && b != e && b == j)) {
            a
          } else {
            interpolate(a, b)
          }
          product1 = if ((a == g && c == o) || (a == b && a == h && g != c && c == m)) {
            a
          } else {
            interpolate(a, c)
          }
          product2 = a
        } else if (b == c && a != d) {
          product = if ((b == f && a == h) || (b == e && b == d && a != f && a == i)) {
            b
          } else {
            interpolate(a, b)
          }
          product1 = if ((c == h && a == f) || (c == g && c == d && a != h && a == i)) {
            c
          } else {
            interpolate(a, c)
          }
          product2 = b
        } else if (a == d && b == c) {
          if (a == b) {
            product = a
            product1 = a
            product2 = a
          } else {
            product1 = interpolate(a, c)
            product = interpolate(a, b)
            var score = 0
            score += getResult1(a, b, g, e)
            score += getResult2(b, a, k, f)
            score += getResult2(b, a, h, n)
            score += getResult1(a, b, l, o)
            product2 = when {
              score > 0 -> a
              score < 0 -> b
              else -> qInterpolate(a, b, c, d)
            }
          }
        } else {
          product2 = qInterpolate(a, b, c, d)
          product = when {
            a == c && a == f && b != e && b == j -> a
            b == e && b == d && a != f && a == i -> b
            else -> interpolate(a, b)
          }
          product1 = when {
            a == b && a == h && g != c && c == m -> a
            c == g && c == d && a != h && a == i -> c
            else -> interpolate(a, c)
          }
        }

        val at = (y * 2) * outWidth + x * 2
        out[at] = a
        out[at + 1] = product
        out[at + outWidth] = product1
        out[at + outWidth + 1] = product2
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  private fun scale2SuperSai(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 2
    val out = IntArray(outWidth * height * 2)
    val r = Reader(source.pixels, width, height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val colorB0 = r.at(x - 1, y - 1)
        val colorB1 = r.at(x, y - 1)
        val colorB2 = r.at(x + 1, y - 1)
        val colorB3 = r.at(x + 2, y - 1)
        val color4 = r.at(x - 1, y)
        val color5 = r.at(x, y)
        val color6 = r.at(x + 1, y)
        val colorS2 = r.at(x + 2, y)
        val color1 = r.at(x - 1, y + 1)
        val color2 = r.at(x, y + 1)
        val color3 = r.at(x + 1, y + 1)
        val colorS1 = r.at(x + 2, y + 1)
        val colorA0 = r.at(x - 1, y + 2)
        val colorA1 = r.at(x, y + 2)
        val colorA2 = r.at(x + 1, y + 2)
        val colorA3 = r.at(x + 2, y + 2)

        val product1b: Int
        val product2b: Int
        val product1a: Int
        val product2a: Int
        if (color2 == color6 && color5 != color3) {
          product1b = color2
          product2b = color2
        } else if (color5 == color3 && color2 != color6) {
          product1b = color5
          product2b = color5
        } else if (color5 == color3 && color2 == color6) {
          var score = 0
          score += getResult(color6, color5, color1, colorA1)
          score += getResult(color6, color5, color4, colorB1)
          score += getResult(color6, color5, colorA2, colorS1)
          score += getResult(color6, color5, colorB2, colorS2)
          product1b = when {
            score > 0 -> color6
            score < 0 -> color5
            else -> interpolate(color5, color6)
          }
          product2b = product1b
        } else {
          product2b = when {
            color6 == color3 && color3 == colorA1 && color2 != colorA2 && color3 != colorA0 ->
              qInterpolate(color3, color3, color3, color2)
            color5 == color2 && color2 == colorA2 && colorA1 != color3 && color2 != colorA3 ->
              qInterpolate(color2, color2, color2, color3)
            else -> interpolate(color2, color3)
          }
          product1b = when {
            color6 == color3 && color6 == colorB1 && color5 != colorB2 && color6 != colorB0 ->
              qInterpolate(color6, color6, color6, color5)
            color5 == color2 && color5 == colorB2 && colorB1 != color6 && color5 != colorB3 ->
              qInterpolate(color6, color5, color5, color5)
            else -> interpolate(color5, color6)
          }
        }

        product2a = when {
          color5 == color3 && color2 != color6 && color4 == color5 && color5 != colorA2 ->
            interpolate(color2, color5)
          color5 == color1 && color6 == color5 && color4 != color2 && color5 != colorA0 ->
            interpolate(color2, color5)
          else -> color2
        }
        product1a = when {
          color2 == color6 && color5 != color3 && color1 == color2 && color2 != colorB2 ->
            interpolate(color2, color5)
          color4 == color2 && color3 == color2 && color1 != color5 && color2 != colorB0 ->
            interpolate(color2, color5)
          else -> color5
        }

        val at = (y * 2) * outWidth + x * 2
        out[at] = product1a
        out[at + 1] = product1b
        out[at + outWidth] = product2a
        out[at + outWidth + 1] = product2b
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  private fun scale2SuperEagle(source: ScaledImage): ScaledImage {
    val width = source.width
    val height = source.height
    val outWidth = width * 2
    val out = IntArray(outWidth * height * 2)
    val r = Reader(source.pixels, width, height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val colorB1 = r.at(x, y - 1)
        val colorB2 = r.at(x + 1, y - 1)
        val color4 = r.at(x - 1, y)
        val color5 = r.at(x, y)
        val color6 = r.at(x + 1, y)
        val colorS2 = r.at(x + 2, y)
        val color1 = r.at(x - 1, y + 1)
        val color2 = r.at(x, y + 1)
        val color3 = r.at(x + 1, y + 1)
        val colorS1 = r.at(x + 2, y + 1)
        val colorA1 = r.at(x, y + 2)
        val colorA2 = r.at(x + 1, y + 2)

        val product1a: Int
        val product1b: Int
        val product2a: Int
        val product2b: Int
        if (color2 == color6 && color5 != color3) {
          product1b = color2
          product2a = color2
          product1a = if (color1 == color2 || color6 == colorB2) {
            interpolate(color2, interpolate(color2, color5))
          } else {
            interpolate(color5, color6)
          }
          product2b = if (color6 == colorS2 || color2 == colorA1) {
            interpolate(color2, interpolate(color2, color3))
          } else {
            interpolate(color2, color3)
          }
        } else if (color5 == color3 && color2 != color6) {
          product2b = color5
          product1a = color5
          product1b = if (colorB1 == color5 || color3 == colorS1) {
            interpolate(color5, interpolate(color5, color6))
          } else {
            interpolate(color5, color6)
          }
          product2a = if (color3 == colorA2 || color4 == color5) {
            interpolate(color5, interpolate(color5, color2))
          } else {
            interpolate(color2, color3)
          }
        } else if (color5 == color3 && color2 == color6) {
          var score = 0
          score += getResult(color6, color5, color1, colorA1)
          score += getResult(color6, color5, color4, colorB1)
          score += getResult(color6, color5, colorA2, colorS1)
          score += getResult(color6, color5, colorB2, colorS2)
          if (score > 0) {
            product1b = color2
            product2a = color2
            product1a = interpolate(color5, color6)
            product2b = product1a
          } else if (score < 0) {
            product2b = color5
            product1a = color5
            product1b = interpolate(color5, color6)
            product2a = product1b
          } else {
            product2b = color5
            product1a = color5
            product1b = color2
            product2a = color2
          }
        } else {
          val mixed = interpolate(color2, color6)
          product2b = qInterpolate(color3, color3, color3, mixed)
          product1a = qInterpolate(color5, color5, color5, mixed)
          val mixed2 = interpolate(color5, color3)
          product2a = qInterpolate(color2, color2, color2, mixed2)
          product1b = qInterpolate(color6, color6, color6, mixed2)
        }

        val at = (y * 2) * outWidth + x * 2
        out[at] = product1a
        out[at + 1] = product1b
        out[at + outWidth] = product2a
        out[at + outWidth + 1] = product2b
      }
    }
    return ScaledImage(out, outWidth, height * 2)
  }

  /** The midpoint of two colours, channel by channel. */
  private fun interpolate(a: Int, b: Int): Int {
    if (a == b) return a
    val alpha = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF)) ushr 1
    val red = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF)) ushr 1
    val green = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF)) ushr 1
    val blue = ((a and 0xFF) + (b and 0xFF)) ushr 1
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  /** The average of four colours, channel by channel. */
  private fun qInterpolate(a: Int, b: Int, c: Int, d: Int): Int {
    val alpha = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF) +
      ((c ushr 24) and 0xFF) + ((d ushr 24) and 0xFF)) ushr 2
    val red = (((a ushr 16) and 0xFF) + ((b ushr 16) and 0xFF) +
      ((c ushr 16) and 0xFF) + ((d ushr 16) and 0xFF)) ushr 2
    val green = (((a ushr 8) and 0xFF) + ((b ushr 8) and 0xFF) +
      ((c ushr 8) and 0xFF) + ((d ushr 8) and 0xFF)) ushr 2
    val blue = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF)) ushr 2
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
  }

  private fun getResult1(a: Int, b: Int, c: Int, d: Int): Int =
    signedResult(a, b, c, d, firstPairWins = true)

  private fun getResult2(a: Int, b: Int, c: Int, d: Int): Int =
    signedResult(a, b, c, d, firstPairWins = false)

  private fun getResult(a: Int, b: Int, c: Int, d: Int): Int = getResult1(a, b, c, d)

  /**
   * The little vote the 2xSaI family uses to decide which diagonal a corner belongs to: how many of
   * [c] and [d] are matched by [a], and how many by [b].
   */
  private fun signedResult(a: Int, b: Int, c: Int, d: Int, firstPairWins: Boolean): Int {
    var x = 0
    var y = 0
    var r = 0
    if (a == c) x += 1 else if (b == c) y += 1
    if (a == d) x += 1 else if (b == d) y += 1
    if (x <= 1) r += if (firstPairWins) 1 else -1
    if (y <= 1) r += if (firstPairWins) -1 else 1
    return r
  }
}