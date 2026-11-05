package ca.justinmo.wfcbitmap.storage
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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
 * (this file and its test) and one consumer, the format is a short fixed list of fields, and a versioned
 * header costs less than a dependency.
 */
data class EditorSnapshot(
  val columns: Int,
  val rows: Int,
  /** ARGB of the drawing, row-major, `columns * rows` entries. */
  val pixels: IntArray,
  val seed: Int,
  /** The colour the paint tools are drawing with, ARGB. */
  val colorArgb: Int,
  /** [OutputAspect] by name, so a reordered enum cannot silently change a saved session. */
  val aspectName: String,
  /** [PreviewScale] by name, for the same reason as [aspectName]. */
  val previewName: String,
  /** [OutputScale] by name, for the same reason as [aspectName]. */
  val scaleName: String,
  /** [PixelArtScaler] by name, for the same reason as [aspectName]. */
  val scalerName: String = PixelArtScaler.Nearest.name,
  /** [KernelSize] by name: how the drawing is grouped into tiles. */
  val kernelName: String = KernelSize.Three.name,
  val wrapEdges: Boolean,
  /** Whether every pattern was also offered flipped left to right. */
  val mirrorHorizontal: Boolean,
  /** Whether every pattern was also offered flipped top to bottom. */
  val mirrorVertical: Boolean,
  /** Whether every pattern was also offered turned a quarter turn; the third symmetry setting. */
  val quarterTurn: Boolean = false,
  /** The selected palette ID. */
  val paletteId: String = PaletteStore.DEFAULT_PALETTE_ID,
  /**
   * True when the drawing has been written to the gallery as a pattern and has not been edited since.
   * On the next launch such a session opens blank instead of restoring the saved drawing.
   */
  val savedAsPattern: Boolean = false,
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
      out.writeUTF(scalerName)
      out.writeBoolean(wrapEdges)
      out.writeBoolean(mirrorHorizontal)
      out.writeBoolean(mirrorVertical)
      out.writeBoolean(quarterTurn)
      out.writeUTF(paletteId)
      out.writeUTF(kernelName)
      out.writeBoolean(savedAsPattern)
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
     * and so a setting the picture cannot be reproduced without. 4 split the one mirror setting into the two
     * the editor has: either of them changes the tile set, so a session cannot come back without both.
     * 5 added paletteId for palette selection. 6 added the pixel-art scaler used when saving. 7 added
     * the tile-grouping kernel, which changes the tile set as much as the mirrors do. 8 added
     * savedAsPattern, so a pattern that was not edited afterwards does not reopen. 9 added the
     * quarter-turn symmetry setting, the third way a window can be offered turned.
     */
    private const val VERSION = 9

    private const val HEADER_BYTES = 128

    /** The editor's own limits on a grid side, so a corrupt file cannot ask for a gigabyte of pixels. */
    const val MIN_SIDE = MIN_GRID_SIZE
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
          if (magic != MAGIC || version < 4 || version > VERSION) return null

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
          val scalerName = if (version >= 6) {
            input.readUTF()
          } else {
            PixelArtScaler.Nearest.name
          }
          val wrapEdges = input.readBoolean()
          val mirrorHorizontal = input.readBoolean()
          val mirrorVertical = input.readBoolean()
          val quarterTurn = version >= 9 && input.readBoolean()
          val paletteId = if (version >= 5) input.readUTF() else PaletteStore.DEFAULT_PALETTE_ID
          val kernelName = if (version >= 7) input.readUTF() else KernelSize.Three.name
          val savedAsPattern = version >= 8 && input.readBoolean()

          EditorSnapshot(
            columns = columns,
            rows = rows,
            pixels = pixels,
            seed = seed,
            colorArgb = colorArgb,
            aspectName = aspectName,
            previewName = previewName,
            scaleName = scaleName,
            scalerName = scalerName,
            kernelName = kernelName,
            wrapEdges = wrapEdges,
            mirrorHorizontal = mirrorHorizontal,
            mirrorVertical = mirrorVertical,
            quarterTurn = quarterTurn,
            paletteId = paletteId,
            savedAsPattern = savedAsPattern,
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
      previewName == other.previewName && scaleName == other.scaleName &&
      scalerName == other.scalerName && kernelName == other.kernelName &&
      wrapEdges == other.wrapEdges &&
      mirrorHorizontal == other.mirrorHorizontal &&
      mirrorVertical == other.mirrorVertical &&
      quarterTurn == other.quarterTurn &&
      paletteId == other.paletteId &&
      savedAsPattern == other.savedAsPattern
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
    result = 31 * result + scalerName.hashCode()
    result = 31 * result + kernelName.hashCode()
    result = 31 * result + wrapEdges.hashCode()
    result = 31 * result + mirrorHorizontal.hashCode()
    result = 31 * result + mirrorVertical.hashCode()
    result = 31 * result + quarterTurn.hashCode()
    result = 31 * result + paletteId.hashCode()
    result = 31 * result + savedAsPattern.hashCode()
    return result
  }
}