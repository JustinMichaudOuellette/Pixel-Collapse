package ca.justinmo.wfcbitmap.palette
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * A color palette loaded from a paint.net palette file (.txt asset).
 */
data class Palette(
  val id: String,
  val name: String,
  val colors: List<Color>,
  /** The file's `;Description:` line, kept as written; it may contain HTML. */
  val description: String = "",
) {
  /**
   * The colour in this palette closest to [color], or [color] itself when the palette is empty.
   *
   * Closeness is plain RGB distance: palette files hold opaque screen colours, and the nearest entry
   * in that space is what a palette swap has always meant in pixel art.
   */
  fun closestColor(color: Color): Color = PaletteStore.closestColor(color, colors)
}

object PaletteStore {

  const val DEFAULT_PALETTE_ID = "endesga-32.txt"

  /**
   * Parses a paint.net format palette stream (.txt file).
   * Lines starting with ';' are comments/metadata (e.g. ';Palette Name: Name').
   * Hex color lines are 8 (AARRGGBB) or 6 (RRGGBB) hex chars.
   *
   * A file with no colour lines comes back with no colours: the default palette is read from
   * `endesga-32.txt` like any other, so there is no built-in list to fall back to.
   */
  fun parsePalette(id: String, inputStream: InputStream): Palette {
    var name = id.removeSuffix(".txt").replace('-', ' ').replace('_', ' ')
      .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    var description = ""
    val colors = mutableListOf<Color>()

    inputStream.bufferedReader().useLines { lines ->
      for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.startsWith(";")) {
          if (line.startsWith(";Palette Name:", ignoreCase = true)) {
            val parsedName = line.substringAfter(":").trim()
            if (parsedName.isNotEmpty()) {
              name = parsedName
            }
          } else if (line.startsWith(";Description:", ignoreCase = true)) {
            description = line.substringAfter(":").trim()
          }
          continue
        }
        if (line.isEmpty()) continue
        parseColor(line)?.let { colors.add(it) }
      }
    }

    return Palette(
      id = id,
      name = name,
      colors = colors,
      description = description,
    )
  }

  fun parseColor(hexStr: String): Color? {
    val cleanHex = hexStr.removePrefix("#").trim()
    return try {
      val argb = when (cleanHex.length) {
        6 -> (0xFF000000 or cleanHex.toLong(16)).toInt()
        8 -> cleanHex.toLong(16).toInt()
        else -> return null
      }
      Color(argb)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * The palette asset file names, sorted, without reading any of them.
   *
   * Palette files are the `.txt` files in assets; `dummy.txt` is skipped, and a file that cannot be
   * listed is simply not offered.
   */
  suspend fun paletteFiles(context: Context): List<String> = withContext(Dispatchers.IO) {
    try {
      context.assets.list("")
        ?.filter { it.endsWith(".txt", ignoreCase = true) && !it.equals("dummy.txt", ignoreCase = true) }
        ?.sortedBy { it.lowercase() }
        ?: emptyList()
    } catch (_: Exception) {
      emptyList()
    }
  }

  /**
   * Parses one palette asset, or null when it cannot be read or has no colours.
   */
  suspend fun loadPalette(context: Context, id: String): Palette? = withContext(Dispatchers.IO) {
    val palette = try {
      context.assets.open(id).use { stream ->
        parsePalette(id, stream)
      }
    } catch (_: Exception) {
      null
    }
    palette?.takeIf { it.colors.isNotEmpty() }
  }

  /**
   * Loads every palette file in the app's assets folder, with `endesga-32.txt` first.
   *
   * The files are read and parsed on [Dispatchers.IO]. The Application uses [paletteFiles] and
   * [loadPalette] to load the first palette before this full list, but this remains the one call that
   * reads everything.
   */
  suspend fun loadAllPalettes(context: Context): List<Palette> {
    val palettes = paletteFiles(context).mapNotNull { loadPalette(context, it) }
    return orderWithDefaultFirst(palettes)
  }

  /** The same list with `endesga-32.txt` moved to the front, when it is present. */
  fun orderWithDefaultFirst(palettes: List<Palette>): List<Palette> {
    val defaultIndex = palettes.indexOfFirst {
      it.id.equals(DEFAULT_PALETTE_ID, ignoreCase = true)
    }
    if (defaultIndex <= 0) return palettes
    val ordered = palettes.toMutableList()
    val defaultPalette = ordered.removeAt(defaultIndex)
    ordered.add(0, defaultPalette)
    return ordered
  }

  /**
   * The palette the editor starts on: `endesga-32.txt`, or the first loaded palette when the asset is
   * missing.
   */
  fun defaultPalette(palettes: List<Palette>): Palette {
    require(palettes.isNotEmpty()) { "No palettes were loaded" }
    return palettes.firstOrNull { it.id.equals(DEFAULT_PALETTE_ID, ignoreCase = true) }
      ?: palettes.first()
  }

  /**
   * The colour in [palette] closest to [color], measured as squared RGB distance, or [color] itself
   * when there is no palette to choose from.
   */
  fun closestColor(color: Color, palette: List<Color>): Color {
    if (palette.isEmpty()) return color
    var closest = palette.first()
    var closestDistance = colorDistanceSquared(color, closest)
    for (index in 1 until palette.size) {
      val candidate = palette[index]
      val distance = colorDistanceSquared(color, candidate)
      if (distance < closestDistance) {
        closest = candidate
        closestDistance = distance
      }
    }
    return closest
  }

  private fun colorDistanceSquared(first: Color, second: Color): Float {
    val red = first.red - second.red
    val green = first.green - second.green
    val blue = first.blue - second.blue
    return red * red + green * green + blue * blue
  }
}