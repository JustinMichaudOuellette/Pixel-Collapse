package ca.justinmo.wfcbitmap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Everything needed to put an editing session back exactly as it was left.
 *
 * The app is a drawing and a seed: the picture is derived, so restoring the drawing, the seed and the few
 * settings that change how it is generated is enough to get the same picture back without saving any
 * pixels of the output. The undo history and the clipboard are deliberately not here -- the first is not
 * worth hundreds of megabytes of snapshot file for a convenience, and the second is a scratch buffer.
 *
 * The encoding is written and read by hand rather than through a schema library: there are two producers
 * (this file and its test) and one consumer, the format is nine fields, and a versioned header costs less
 * than a dependency.
 */
data class EditorSnapshot(
  val columns: Int,
  val rows: Int,
  /** ARGB of the drawing, row-major, `columns * rows` entries. */
  val pixels: IntArray,
  val seed: Int,
  /** The colour the paint tools are drawing with, ARGB. */
  val colorArgb: Int,
  /** [BitmapEditor.OutputAspect] by name, so a reordered enum cannot silently change a saved session. */
  val aspectName: String,
  /** [BitmapEditor.PreviewScale] by name, for the same reason as [aspectName]. */
  val previewName: String,
  /** [BitmapEditor.OutputScale] by name, for the same reason as [aspectName]. */
  val scaleName: String,
  val wrapEdges: Boolean,
  val mirrorSymmetry: Boolean,
) {

  /** True when the snapshot describes a grid it could actually be loaded into. */
  val isValid: Boolean
    get() = columns >= MIN_SIDE && rows >= MIN_SIDE &&
      columns <= MAX_SIDE && rows <= MAX_SIDE &&
      pixels.size == columns * rows

  fun encode(): ByteArray {
    val buffer = ByteArrayOutputStream(HEADER_BYTES + pixels.size * 4)
    DataOutputStream(buffer).use { out ->
      out.writeInt(MAGIC)
      out.writeInt(VERSION)
      out.writeInt(columns)
      out.writeInt(rows)
      out.writeInt(pixels.size)
      for (pixel in pixels) out.writeInt(pixel)
      out.writeInt(seed)
      out.writeInt(colorArgb)
      out.writeUTF(aspectName)
      out.writeUTF(previewName)
      out.writeUTF(scaleName)
      out.writeBoolean(wrapEdges)
      out.writeBoolean(mirrorSymmetry)
    }
    return buffer.toByteArray()
  }

  companion object {
    /** "WFC1": four bytes that say what the file is, so a stray file is refused rather than misread. */
    private const val MAGIC = 0x57464331

    /**
     * Bumped only when the fields change; a file from another version is refused, not guessed at.
     *
     * 2 changed the save size from its multiplier to its name: the list now has a scale below 1, and a
     * whole number cannot describe it. 3 added the preview size, which is what the picture is generated at
     * and so a setting the picture cannot be reproduced without.
     */
    private const val VERSION = 3

    private const val HEADER_BYTES = 96

    /** The editor's own limits on a grid side, so a corrupt file cannot ask for a gigabyte of pixels. */
    const val MIN_SIDE = BitmapEditor.MIN_GRID_SIZE
    const val MAX_SIDE = 500

    /** The most a snapshot can be: a header plus a maximum-size grid of ARGB ints. */
    const val MAX_BYTES = HEADER_BYTES + MAX_SIDE * MAX_SIDE * 4 + 64

    /**
     * Reads a snapshot, or null when the bytes are not one.
     *
     * Every way of being wrong -- the wrong file, a truncated write, a version from another build, a grid
     * size that disagrees with the pixel count -- comes back as null, because the caller's answer to all of
     * them is the same: start with an empty drawing rather than crash on someone's saved work.
     */
    fun decode(bytes: ByteArray): EditorSnapshot? {
      if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
      return try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
          val magic = input.readInt()
          val version = input.readInt()
          if (magic != MAGIC || version != VERSION) return null

          val columns = input.readInt()
          val rows = input.readInt()
          val count = input.readInt()
          if (columns < MIN_SIDE || rows < MIN_SIDE || columns > MAX_SIDE || rows > MAX_SIDE) return null
          if (count != columns * rows) return null

          val pixels = IntArray(count) { input.readInt() }
          val seed = input.readInt()
          val colorArgb = input.readInt()
          val aspectName = input.readUTF()
          val previewName = input.readUTF()
          val scaleName = input.readUTF()
          val wrapEdges = input.readBoolean()
          val mirrorSymmetry = input.readBoolean()

          EditorSnapshot(
            columns = columns,
            rows = rows,
            pixels = pixels,
            seed = seed,
            colorArgb = colorArgb,
            aspectName = aspectName,
            previewName = previewName,
            scaleName = scaleName,
            wrapEdges = wrapEdges,
            mirrorSymmetry = mirrorSymmetry,
          )
        }
      } catch (e: IOException) {
        // Truncated, or not a snapshot at all.
        null
      }
    }
  }

  // Data classes with an array field need these spelled out; the array is the drawing, so identity would
  // be wrong and comparing contents is what a test (or a "did anything change" check) means.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is EditorSnapshot) return false
    return columns == other.columns && rows == other.rows && pixels.contentEquals(other.pixels) &&
      seed == other.seed && colorArgb == other.colorArgb && aspectName == other.aspectName &&
      previewName == other.previewName && scaleName == other.scaleName && wrapEdges == other.wrapEdges &&
      mirrorSymmetry == other.mirrorSymmetry
  }

  override fun hashCode(): Int {
    var result = columns
    result = 31 * result + rows
    result = 31 * result + pixels.contentHashCode()
    result = 31 * result + seed
    result = 31 * result + colorArgb
    result = 31 * result + aspectName.hashCode()
    result = 31 * result + previewName.hashCode()
    result = 31 * result + scaleName.hashCode()
    result = 31 * result + wrapEdges.hashCode()
    result = 31 * result + mirrorSymmetry.hashCode()
    return result
  }
}
