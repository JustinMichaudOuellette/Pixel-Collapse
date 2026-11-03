package ca.justinmo.wfcbitmap

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A small animated-GIF writer: one global colour table, full frames, a loop count, and LZW data.
 *
 * Pixel art is the easy case for GIF: the picture uses a small palette, so no dithering or colour
 * quantisation is needed. The caller supplies the palette and the frames already reduced to it; this
 * class only writes the 89a container and the LZW stream.
 */
internal object GifEncoder {

  /**
   * Encodes [frameCount] frames as an endlessly looping GIF.
   *
   * Frames are pulled one at a time through [frameAt], so a long clip never holds every frame in
   * memory at once.
   *
   * @param palette ARGB colours, at most 256; the global colour table is padded to a power of two.
   * @param delayForFrame how long frame [i] stays on screen, in hundredths of a second. A function
   *   rather than one value because 60 fps is 1.666 cs, which GIF cannot express in one number; a
   *   repeating 1,2,2 sequence averages exactly 60 frames per second.
   */
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
   * Streams the same GIF to [out].
   *
   * A full-resolution GIF is far too large for a ByteArray; writing frames as they are encoded keeps
   * only one frame and its LZW block in memory.
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

    val tableSize = tableSizeFor(palette.size)
    val paletteIndices = HashMap<Int, Int>(palette.size * 2)
    palette.forEachIndexed { index, colour -> paletteIndices[colour and 0xFFFFFF] = index }

    out.write("GIF89a".toByteArray(Charsets.US_ASCII))

    // Logical screen descriptor: global table, 8-bit colour resolution, table size.
    out.writeShort(width)
    out.writeShort(height)
    out.write(0x80 or 0x70 or (log2(tableSize) - 1))
    out.write(0) // background colour index
    out.write(0) // pixel aspect ratio

    // Global colour table, padded with black.
    for (i in 0 until tableSize) {
      val colour = if (i < palette.size) palette[i] else 0
      out.write((colour ushr 16) and 0xFF)
      out.write((colour ushr 8) and 0xFF)
      out.write(colour and 0xFF)
    }

    // Netscape looping extension: loop forever.
    out.write(0x21)
    out.write(0xFF)
    out.write(0x0B)
    out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
    out.write(0x03)
    out.write(0x01)
    out.writeShort(0)
    out.write(0)

    val minCodeSize = maxOf(2, log2(tableSize))
    val indices = IntArray(width * height)
    for (frameIndex in 0 until frameCount) {
      val frame = frameAt(frameIndex)
      require(frame.size == width * height) { "A frame has to be width * height" }
      // Graphic control extension: disposal 1, no transparency.
      out.write(0x21)
      out.write(0xF9)
      out.write(0x04)
      out.write(0x04)
      out.writeShort(delayForFrame(frameIndex).coerceAtLeast(1))
      out.write(0)
      out.write(0)

      // Image descriptor: full frame, no local colour table.
      out.write(0x2C)
      out.writeShort(0)
      out.writeShort(0)
      out.writeShort(width)
      out.writeShort(height)
      out.write(0)

      for (i in frame.indices) {
        val rgb = frame[i] and 0xFFFFFF
        indices[i] = paletteIndices[rgb] ?: nearestIndex(rgb, palette)
      }
      out.write(minCodeSize)
      out.write(lzw(indices, minCodeSize))
    }

    out.write(0x3B)
  }

  /**
   * The most common colours in [pixels], as an ARGB palette of at most [maxColors] entries.
   *
   * Pixel-art output normally has fewer than 256 colours, so this is the whole palette; a busier
   * picture keeps its most frequent colours and the encoder maps the rest to the nearest one.
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

  /** Nearest palette index by squared RGB distance; only reached when a frame uses an off-palette colour. */
  private fun nearestIndex(rgb: Int, palette: IntArray): Int {
    val r = (rgb ushr 16) and 0xFF
    val g = (rgb ushr 8) and 0xFF
    val b = rgb and 0xFF
    var best = 0
    var bestDistance = Int.MAX_VALUE
    for (i in palette.indices) {
      val pr = (palette[i] ushr 16) and 0xFF
      val pg = (palette[i] ushr 8) and 0xFF
      val pb = palette[i] and 0xFF
      val dr = r - pr
      val dg = g - pg
      val db = b - pb
      val distance = dr * dr + dg * dg + db * db
      if (distance < bestDistance) {
        bestDistance = distance
        best = i
      }
    }
    return best
  }

  /**
   * GIF LZW. Codes are packed least-significant-bit first into sub-blocks of at most 255 bytes.
   */
  private fun lzw(indices: IntArray, minCodeSize: Int): ByteArray {
    val clearCode = 1 shl minCodeSize
    val endCode = clearCode + 1
    var nextCode = endCode + 1
    var codeSize = minCodeSize + 1
    val dictionary = HashMap<Int, Int>()
    val bits = ByteArrayOutputStream()
    var bitBuffer = 0
    var bitCount = 0

    fun emit(code: Int) {
      bitBuffer = bitBuffer or (code shl bitCount)
      bitCount += codeSize
      while (bitCount >= 8) {
        bits.write(bitBuffer and 0xFF)
        bitBuffer = bitBuffer ushr 8
        bitCount -= 8
      }
    }

    emit(clearCode)
    var prefix = indices[0]
    for (i in 1 until indices.size) {
      val value = indices[i]
      val key = (prefix shl 8) or value
      val existing = dictionary[key]
      if (existing != null) {
        prefix = existing
      } else {
        emit(prefix)
        if (nextCode < 4096) {
          dictionary[key] = nextCode
          nextCode++
          if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
        } else {
          emit(clearCode)
          dictionary.clear()
          nextCode = endCode + 1
          codeSize = minCodeSize + 1
        }
        prefix = value
      }
    }
    emit(prefix)
    emit(endCode)
    if (bitCount > 0) {
      bits.write(bitBuffer and 0xFF)
    }

    val data = bits.toByteArray()
    val out = ByteArrayOutputStream(data.size + data.size / 255 + 2)
    var offset = 0
    while (offset < data.size) {
      val length = minOf(255, data.size - offset)
      out.write(length)
      out.write(data, offset, length)
      offset += length
    }
    out.write(0)
    return out.toByteArray()
  }

  private fun tableSizeFor(colours: Int): Int {
    var size = 2
    while (size < colours) size = size shl 1
    return size
  }

  private fun log2(value: Int): Int {
    var bits = 0
    var v = value
    while (v > 1) {
      v = v shr 1
      bits++
    }
    return bits
  }

  private fun ByteArrayOutputStream.writeShort(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
  }

  private fun OutputStream.writeShort(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
  }
}
