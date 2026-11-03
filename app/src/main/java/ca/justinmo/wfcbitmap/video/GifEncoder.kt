package ca.justinmo.wfcbitmap.video

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Animated-GIF writer.
 *
 * The container, colour mapping and LZW stream are all produced by [GifNative]; Kotlin only chooses the
 * palette and hands the frames over one at a time. That keeps the encoder off the JVM for the expensive
 * per-pixel loops while still streaming the file to disk.
 */
internal object GifEncoder {

  fun encode(
    width: Int,
    height: Int,
    palette: IntArray,
    frameCount: Int,
    delayForFrame: (Int) -> Int,
    frameAt: (Int) -> IntArray,
  ): ByteArray {
    val bytes = ByteArrayOutputStream()
    encodeTo(bytes, width, height, palette, frameCount, delayForFrame, frameAt)
    return bytes.toByteArray()
  }

  /**
   * Streams the GIF to [out].
   *
   * @param palette ARGB colours, at most 256; the native encoder pads the global table.
   * @param delayForFrame how long frame [i] stays on screen, in hundredths of a second.
   */
  fun encodeTo(
    out: OutputStream,
    width: Int,
    height: Int,
    palette: IntArray,
    frameCount: Int,
    delayForFrame: (Int) -> Int,
    frameAt: (Int) -> IntArray,
  ) {
    require(width > 0 && height > 0)
    require(palette.size in 1..256) { "A GIF palette has at most 256 colours" }
    require(frameCount > 0)
    val delays = IntArray(frameCount) { delayForFrame(it).coerceAtLeast(1) }
    val encoded = GifNative.encode(out, width, height, palette, delays) { index ->
      frameAt(index)
    }
    if (!encoded) throw IOException("The native GIF encoder could not write the file")
  }

  /**
   * The most common colours in [pixels], as an ARGB palette of at most [maxColors] entries.
   *
   * Pixel-art output normally has fewer than 256 colours, so this is the whole palette; a busier
   * picture keeps its most frequent colours and the native encoder maps the rest to the nearest one.
   */
  fun paletteFor(pixels: IntArray, maxColors: Int = 256): IntArray {
    val counts = HashMap<Int, Int>()
    for (pixel in pixels) {
      val rgb = pixel and 0xFFFFFF
      counts[rgb] = (counts[rgb] ?: 0) + 1
    }
    val ordered = counts.entries.sortedByDescending { it.value }
    if (ordered.isEmpty()) return intArrayOf(0xFF000000.toInt())
    return ordered.take(maxColors).map { 0xFF000000.toInt() or it.key }.toIntArray()
  }
}
