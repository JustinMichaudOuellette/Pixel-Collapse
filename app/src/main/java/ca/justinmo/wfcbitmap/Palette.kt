package ca.justinmo.wfcbitmap

import android.content.Context
import androidx.compose.ui.graphics.Color
import java.io.InputStream

/**
 * A color palette loaded from a paint.net palette file (.txt asset).
 */
data class Palette(
  val id: String,
  val name: String,
  val colors: List<Color>,
)

object PaletteStore {

  /** Default palette (Endesga 32) fallback colors when assets cannot be loaded. */
  val DEFAULT_PALETTE_COLORS = listOf(
    Color(0xffbe4a2f),
    Color(0xffd77643),
    Color(0xffead4aa),
    Color(0xffe4a672),
    Color(0xffb86f50),
    Color(0xff733e39),
    Color(0xff3e2731),
    Color(0xffa22633),
    Color(0xffe43b44),
    Color(0xfff77622),
    Color(0xfffeae34),
    Color(0xfffee761),
    Color(0xff63c74d),
    Color(0xff3e8948),
    Color(0xff265c42),
    Color(0xff193c3e),
    Color(0xff124e89),
    Color(0xff0099db),
    Color(0xff2ce8f5),
    Color(0xffffffff),
    Color(0xffc0cbdc),
    Color(0xff8b9bb4),
    Color(0xff5a6988),
    Color(0xff3a4466),
    Color(0xff262b44),
    Color(0xff181425),
    Color(0xffff0044),
    Color(0xff68386c),
    Color(0xffb55088),
    Color(0xfff6757a),
    Color(0xffe8b796),
    Color(0xffc28569),
  )

  const val DEFAULT_PALETTE_ID = "endesga-32.txt"

  val defaultPalette = Palette(
    id = DEFAULT_PALETTE_ID,
    name = "Endesga 32",
    colors = DEFAULT_PALETTE_COLORS,
  )

  /**
   * Parses a paint.net format palette stream (.txt file).
   * Lines starting with ';' are comments/metadata (e.g. ';Palette Name: Name').
   * Hex color lines are 8 (AARRGGBB) or 6 (RRGGBB) hex chars.
   */
  fun parsePalette(id: String, inputStream: InputStream): Palette {
    var name = id.removeSuffix(".txt").replace('-', ' ').replace('_', ' ')
      .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
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
      colors = colors.ifEmpty { DEFAULT_PALETTE_COLORS },
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
   * Loads all palette files from the app's assets folder (`.txt` palette files).
   * Ensures `endesga-32.txt` is listed first as the default palette.
   */
  fun loadAllPalettes(context: Context): List<Palette> {
    val assetManager = context.assets
    val paletteFiles = try {
      assetManager.list("")?.filter { it.endsWith(".txt") && (it != "dummy.txt") } ?: emptyList()
    } catch (_: Exception) {
      emptyList()
    }

    if (paletteFiles.isEmpty()) {
      return listOf(defaultPalette)
    }

    val palettes = paletteFiles.mapNotNull { filename ->
      try {
        assetManager.open(filename).use { stream ->
          parsePalette(filename, stream)
        }
      } catch (_: Exception) {
        null
      }
    }.toMutableList()

    if (palettes.isEmpty()) {
      return listOf(defaultPalette)
    }

    // Ensure default palette (endesga-32.txt) is at index 0
    val defaultIndex = palettes.indexOfFirst { it.id.equals(DEFAULT_PALETTE_ID, ignoreCase = true) }
    if (defaultIndex > 0) {
      val defaultP = palettes.removeAt(defaultIndex)
      palettes.add(0, defaultP)
    }

    return palettes
  }
}
