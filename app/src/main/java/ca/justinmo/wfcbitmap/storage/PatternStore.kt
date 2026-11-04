package ca.justinmo.wfcbitmap.storage
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import java.io.File
import java.io.IOException

/**
 * One saved pattern's metadata, read without decoding the drawing.
 *
 * The palette is stored here as well as in the snapshot: it is what a pattern needs to be loaded with,
 * and keeping its colours means a pattern can still restore its look if the palette file it was saved
 * with no longer exists.
 */
data class PatternMetadata(
  /** The file name, used to load or delete this pattern. */
  val id: String,
  val name: String,
  val paletteId: String,
  val paletteName: String,
  val paletteColors: List<Color>,
  val columns: Int,
  val rows: Int,
  val savedAtMillis: Long,
)

/** A saved pattern with its full drawing snapshot and the PNG preview shown in the picker. */
data class SavedPattern(
  val metadata: PatternMetadata,
  val snapshot: EditorSnapshot,
  val previewPixels: IntArray? = null,
  val previewWidth: Int = snapshot.columns,
  val previewHeight: Int = snapshot.rows,
)

/**
 * Saved drawings in the app's private files, each a lossless PNG with its palette in the metadata.
 *
 * A pattern is the drawing plus the settings that decide how it is generated. The image is the drawing
 * at one pixel per cell, so it survives without resampling; the PNG's `tEXt` chunk carries the seed, the
 * output settings, the edge and mirror settings, and the palette (id, name and colours). That means the
 * picker can list a pattern from its metadata alone, and a pattern can bring its palette back even when
 * that palette is no longer in the assets.
 *
 * Files are written to a temporary name and renamed over the real one, so a write interrupted by the
 * process dying cannot leave half a pattern where a good one was.
 */
class PatternStore(private val directory: File) {

  /** Saves [snapshot] as [name] with [palette] recorded in its metadata. */
  fun save(name: String, palette: Palette, snapshot: EditorSnapshot): PatternMetadata? {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || !snapshot.isValid) return null
    val id = fileNameFor(trimmedName)
    val metadata = PatternMetadata(
      id = id,
      name = trimmedName,
      paletteId = palette.id,
      paletteName = palette.name,
      paletteColors = palette.colors,
      columns = snapshot.columns,
      rows = snapshot.rows,
      savedAtMillis = System.currentTimeMillis(),
    )
    return try {
      directory.mkdirs()
      val bytes = PatternPng.encode(metadata, snapshot)
      val temporary = File(directory, "$id.tmp")
      temporary.writeBytes(bytes)
      val target = File(directory, id)
      if (!temporary.renameTo(target)) {
        target.delete()
        temporary.renameTo(target)
      }
      metadata
    } catch (_: IOException) {
      null
    }
  }

  /** Every saved pattern's metadata, newest first. */
  fun list(): List<PatternMetadata> = try {
    directory.listFiles()
      ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
      ?.mapNotNull { file ->
        try {
          PatternPng.readMetadata(file.readBytes(), file.name)
        } catch (_: IOException) {
          null
        }
      }
      ?.sortedByDescending { it.savedAtMillis }
      ?: emptyList()
  } catch (_: Exception) {
    emptyList()
  }

  /** The pattern [id] with its drawing, or null when it cannot be read. */
  fun load(id: String): SavedPattern? = try {
    val file = File(directory, id)
    if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) {
      null
    } else {
      PatternPng.decode(file.readBytes(), id)
    }
  } catch (_: Exception) {
    null
  }

  /** Deletes the pattern [id]; true when the file is gone. */
  fun delete(id: String): Boolean = try {
    val file = File(directory, id)
    file.delete() || !file.exists()
  } catch (_: Exception) {
    false
  }

  /** A name that can be a file name on any of the filesystems this app runs on. */
  private fun fileNameFor(name: String): String {
    val safe = name.trim()
      .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
      .take(80)
      .ifBlank { "pattern" }
    return safe + EXTENSION
  }

  companion object {
    private const val EXTENSION = ".png"

    /** The largest a pattern PNG can be: a full 500x500 drawing plus metadata. */
    private const val MAX_BYTES = 8_000_000

    /** The pattern store in an app's private files directory. */
    fun forDirectory(directory: File): PatternStore = PatternStore(File(directory, "patterns"))
  }
}