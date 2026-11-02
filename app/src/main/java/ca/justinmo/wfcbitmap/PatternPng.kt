package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The PNG half of a saved pattern: an 8-bit RGBA image, one pixel per drawing cell, with the pattern's
 * settings and palette in a `tEXt` chunk.
 *
 * A PNG because it is lossless and every ordinary image tool can open it. The image is the drawing itself
 * (one pixel per cell), not the generated picture, so it survives a save and load without resampling.
 * The `tEXt` chunk carries everything the image cannot: the palette, the seed, the output shape and sizes,
 * the edge setting, and the two mirrors.
 *
 * The PNG is written by hand rather than through `Bitmap.compress`, because that encoder cannot attach
 * metadata and this codec has to be testable on the JVM. It supports the one format it writes: 8-bit
 * RGBA, non-interlaced, with all five PNG row filters accepted on the way in.
 */
internal object PatternPng {

  private const val MAGIC = 0x57464350 // "WFCP"
  private const val VERSION = 2
  private const val KEYWORD = "PixelCollapsePattern"
  private const val COLOR_TYPE_RGBA = 6
  private const val BYTES_PER_PIXEL = 4

  private val SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
  )

  /** Encodes [snapshot] as a PNG, with [metadata] in its `tEXt` chunk. */
  fun encode(metadata: PatternMetadata, snapshot: EditorSnapshot): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(SIGNATURE)
    out.write(chunk("IHDR", headerData(snapshot.columns, snapshot.rows)))
    out.write(chunk("tEXt", textData(metadata, snapshot)))
    out.write(chunk("IDAT", compressedPixels(snapshot)))
    out.write(chunk("IEND", ByteArray(0)))
    return out.toByteArray()
  }

  /** Reads just the metadata and dimensions, without inflating the image. */
  fun readMetadata(bytes: ByteArray, id: String): PatternMetadata? =
    parse(bytes, id)?.payload?.metadata

  /** Reads the image and the metadata into a pattern. */
  fun decode(bytes: ByteArray, id: String): SavedPattern? {
    val parsed = parse(bytes, id) ?: return null
    val pixels = inflatePixels(parsed.compressed, parsed.width, parsed.height) ?: return null
    val payload = parsed.payload
    val snapshot = EditorSnapshot(
      columns = parsed.width,
      rows = parsed.height,
      pixels = pixels,
      seed = payload.seed,
      colorArgb = payload.colorArgb,
      aspectName = payload.aspectName,
      previewName = payload.previewName,
      scaleName = payload.scaleName,
      scalerName = payload.scalerName,
      wrapEdges = payload.wrapEdges,
      mirrorHorizontal = payload.mirrorHorizontal,
      mirrorVertical = payload.mirrorVertical,
      paletteId = payload.metadata.paletteId,
    )
    if (!snapshot.isValid) return null
    return SavedPattern(payload.metadata, snapshot)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Reading
  // ─────────────────────────────────────────────────────────────────────────────

  private class Payload(
    val metadata: PatternMetadata,
    val seed: Int,
    val colorArgb: Int,
    val aspectName: String,
    val previewName: String,
    val scaleName: String,
    val scalerName: String,
    val wrapEdges: Boolean,
    val mirrorHorizontal: Boolean,
    val mirrorVertical: Boolean,
  )

  private class Parsed(
    val width: Int,
    val height: Int,
    val payload: Payload,
    val compressed: ByteArray,
  )

  private fun parse(bytes: ByteArray, id: String): Parsed? {
    if (bytes.size < 8) return null
    for (index in SIGNATURE.indices) {
      if (bytes[index] != SIGNATURE[index]) return null
    }

    var width = 0
    var height = 0
    var sawHeader = false
    var payload: Payload? = null
    val compressed = ByteArrayOutputStream()
    var offset = 8

    while (offset + 12 <= bytes.size) {
      val length = readInt(bytes, offset)
      offset += 4
      if (length < 0 || offset + 4 + length + 4 > bytes.size) return null
      val type = String(bytes, offset, 4, Charsets.US_ASCII)
      offset += 4
      val dataStart = offset
      offset += length
      val expectedCrc = readInt(bytes, offset)
      offset += 4

      val crc = CRC32()
      crc.update(bytes, dataStart - 4, length + 4)
      if (crc.value.toInt() != expectedCrc) return null

      when (type) {
        "IHDR" -> {
          if (length != 13) return null
          width = readInt(bytes, dataStart)
          height = readInt(bytes, dataStart + 4)
          val bitDepth = bytes[dataStart + 8].toInt() and 0xFF
          val colourType = bytes[dataStart + 9].toInt() and 0xFF
          val compression = bytes[dataStart + 10].toInt() and 0xFF
          val filter = bytes[dataStart + 11].toInt() and 0xFF
          val interlace = bytes[dataStart + 12].toInt() and 0xFF
          if (width !in EditorSnapshot.MIN_SIDE..EditorSnapshot.MAX_SIDE) return null
          if (height !in EditorSnapshot.MIN_SIDE..EditorSnapshot.MAX_SIDE) return null
          if (bitDepth != 8 || colourType != COLOR_TYPE_RGBA) return null
          if (compression != 0 || filter != 0 || interlace != 0) return null
          sawHeader = true
        }

        "tEXt" -> {
          if (!sawHeader) return null
          payload = parseText(bytes, dataStart, length, id, width, height)
        }

        "IDAT" -> {
          if (!sawHeader) return null
          compressed.write(bytes, dataStart, length)
        }

        "IEND" -> break
      }
    }

    if (!sawHeader) return null
    val parsedPayload = payload ?: return null
    if (compressed.size() == 0) return null
    return Parsed(width, height, parsedPayload, compressed.toByteArray())
  }

  private fun parseText(
    bytes: ByteArray,
    start: Int,
    length: Int,
    id: String,
    width: Int,
    height: Int,
  ): Payload? {
    val separator = (start until start + length).firstOrNull { bytes[it] == 0.toByte() }
      ?: return null
    val keyword = String(bytes, start, separator - start, Charsets.US_ASCII)
    if (keyword != KEYWORD) return null
    val encoded = String(
      bytes,
      separator + 1,
      start + length - (separator + 1),
      Charsets.US_ASCII,
    )
    val payload = try {
      Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
      return null
    }
    return decodePayload(payload, id, width, height)
  }

  private fun decodePayload(
    bytes: ByteArray,
    id: String,
    width: Int,
    height: Int,
  ): Payload? {
    return try {
      DataInputStream(bytes.inputStream()).use { input ->
        if (input.readInt() != MAGIC) return null
        val version = input.readInt()
        if (version !in 1..VERSION) return null
        val name = input.readUTF()
        val paletteId = input.readUTF()
        val paletteName = input.readUTF()
        val colourCount = input.readInt()
        if (colourCount !in 0..MAX_COLORS) return null
        val colours = ArrayList<Color>(colourCount)
        repeat(colourCount) { colours.add(Color(input.readInt())) }
        val savedAt = input.readLong()
        val seed = input.readInt()
        val colorArgb = input.readInt()
        val aspectName = input.readUTF()
        val previewName = input.readUTF()
        val scaleName = input.readUTF()
        val scalerName = if (version >= 2) {
          input.readUTF()
        } else {
          PixelArtScaler.Nearest.name
        }
        val wrapEdges = input.readBoolean()
        val mirrorHorizontal = input.readBoolean()
        val mirrorVertical = input.readBoolean()
        Payload(
          metadata = PatternMetadata(
            id = id,
            name = name,
            paletteId = paletteId,
            paletteName = paletteName,
            paletteColors = colours,
            columns = width,
            rows = height,
            savedAtMillis = savedAt,
          ),
          seed = seed,
          colorArgb = colorArgb,
          aspectName = aspectName,
          previewName = previewName,
          scaleName = scaleName,
          scalerName = scalerName,
          wrapEdges = wrapEdges,
          mirrorHorizontal = mirrorHorizontal,
          mirrorVertical = mirrorVertical,
        )
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun inflatePixels(compressed: ByteArray, width: Int, height: Int): IntArray? {
    val stride = width * BYTES_PER_PIXEL + 1
    val raw = ByteArray(stride * height)
    val inflater = Inflater()
    try {
      inflater.setInput(compressed)
      val buffer = ByteArray(8192)
      var total = 0
      while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        if (count == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) return null
        } else {
          if (total + count > raw.size) return null
          System.arraycopy(buffer, 0, raw, total, count)
          total += count
        }
      }
      if (total != raw.size) return null
    } catch (_: Exception) {
      return null
    } finally {
      inflater.end()
    }
    return unfilter(raw, width, height, stride)
  }

  private fun unfilter(raw: ByteArray, width: Int, height: Int, stride: Int): IntArray? {
    val unfiltered = ByteArray(stride * height)
    for (row in 0 until height) {
      val filter = raw[row * stride].toInt() and 0xFF
      val source = row * stride + 1
      val previous = (row - 1) * stride + 1
      for (index in 0 until width * BYTES_PER_PIXEL) {
        val byte = raw[source + index].toInt() and 0xFF
        val left = if (index >= BYTES_PER_PIXEL) {
          unfiltered[source + index - BYTES_PER_PIXEL].toInt() and 0xFF
        } else {
          0
        }
        val above = if (row > 0) unfiltered[previous + index].toInt() and 0xFF else 0
        val upperLeft = if (row > 0 && index >= BYTES_PER_PIXEL) {
          unfiltered[previous + index - BYTES_PER_PIXEL].toInt() and 0xFF
        } else {
          0
        }
        val value = when (filter) {
          0 -> byte
          1 -> byte + left
          2 -> byte + above
          3 -> byte + (left + above) / 2
          4 -> byte + paeth(left, above, upperLeft)
          else -> return null
        }
        unfiltered[source + index] = value.toByte()
      }
    }

    val pixels = IntArray(width * height)
    for (row in 0 until height) {
      for (column in 0 until width) {
        val at = row * stride + 1 + column * BYTES_PER_PIXEL
        val red = unfiltered[at].toInt() and 0xFF
        val green = unfiltered[at + 1].toInt() and 0xFF
        val blue = unfiltered[at + 2].toInt() and 0xFF
        val alpha = unfiltered[at + 3].toInt() and 0xFF
        pixels[row * width + column] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
      }
    }
    return pixels
  }

  private fun paeth(left: Int, above: Int, upperLeft: Int): Int {
    val estimate = left + above - upperLeft
    val leftDistance = kotlin.math.abs(estimate - left)
    val aboveDistance = kotlin.math.abs(estimate - above)
    val upperLeftDistance = kotlin.math.abs(estimate - upperLeft)
    return when {
      leftDistance <= aboveDistance && leftDistance <= upperLeftDistance -> left
      aboveDistance <= upperLeftDistance -> above
      else -> upperLeft
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Writing
  // ─────────────────────────────────────────────────────────────────────────────

  private fun headerData(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
      data.writeInt(width)
      data.writeInt(height)
      data.writeByte(8) // bit depth
      data.writeByte(COLOR_TYPE_RGBA)
      data.writeByte(0) // compression
      data.writeByte(0) // filter
      data.writeByte(0) // interlace
    }
    return out.toByteArray()
  }

  private fun textData(metadata: PatternMetadata, snapshot: EditorSnapshot): ByteArray {
    val payload = ByteArrayOutputStream()
    DataOutputStream(payload).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeUTF(metadata.name)
      out.writeUTF(metadata.paletteId)
      out.writeUTF(metadata.paletteName)
      out.writeInt(metadata.paletteColors.size)
      for (colour in metadata.paletteColors) out.writeInt(colour.toArgb())
      out.writeLong(metadata.savedAtMillis)
      out.writeInt(snapshot.seed)
      out.writeInt(snapshot.colorArgb)
      out.writeUTF(snapshot.aspectName)
      out.writeUTF(snapshot.previewName)
      out.writeUTF(snapshot.scaleName)
      out.writeUTF(snapshot.scalerName)
      out.writeBoolean(snapshot.wrapEdges)
      out.writeBoolean(snapshot.mirrorHorizontal)
      out.writeBoolean(snapshot.mirrorVertical)
    }
    val encoded = Base64.getEncoder().encode(payload.toByteArray())
    val keyword = KEYWORD.toByteArray(Charsets.US_ASCII)
    return keyword + byteArrayOf(0) + encoded
  }

  private fun compressedPixels(snapshot: EditorSnapshot): ByteArray {
    val width = snapshot.columns
    val height = snapshot.rows
    val stride = width * BYTES_PER_PIXEL + 1
    val raw = ByteArray(stride * height)
    for (row in 0 until height) {
      raw[row * stride] = 0 // filter: None
      for (column in 0 until width) {
        val argb = snapshot.pixels[row * width + column]
        val at = row * stride + 1 + column * BYTES_PER_PIXEL
        raw[at] = (argb ushr 16).toByte()
        raw[at + 1] = (argb ushr 8).toByte()
        raw[at + 2] = argb.toByte()
        raw[at + 3] = (argb ushr 24).toByte()
      }
    }

    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    val out = ByteArrayOutputStream()
    try {
      deflater.setInput(raw)
      deflater.finish()
      val buffer = ByteArray(8192)
      while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        if (count > 0) out.write(buffer, 0, count)
      }
    } finally {
      deflater.end()
    }
    return out.toByteArray()
  }

  private fun chunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { chunk ->
      chunk.writeInt(data.size)
      chunk.write(typeBytes)
      chunk.write(data)
      chunk.writeInt(crc.value.toInt())
    }
    return out.toByteArray()
  }

  private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
      ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
      ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
      (bytes[offset + 3].toInt() and 0xFF)

  private const val MAX_COLORS = 4096
}
