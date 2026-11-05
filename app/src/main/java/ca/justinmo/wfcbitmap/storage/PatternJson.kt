package ca.justinmo.wfcbitmap.storage
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The JSON document carried inside a pattern PNG's `tEXt` chunk.
 *
 * The image carries the generated preview; this carries the pattern itself: drawing pixels, palette,
 * seed and the settings that decide how the picture is generated. It is a versioned serializable
 * object, so a later build can add fields and older files stay readable (`ignoreUnknownKeys`).
 */
internal object PatternJson {

  const val FORMAT = "pixel-collapse-pattern"
  const val VERSION = 1

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }

  fun encode(metadata: PatternMetadata, snapshot: EditorSnapshot): String =
    json.encodeToString(
      Document(
        format = FORMAT,
        version = VERSION,
        name = metadata.name,
        paletteId = metadata.paletteId,
        paletteName = metadata.paletteName,
        savedAt = metadata.savedAtMillis,
        columns = snapshot.columns,
        rows = snapshot.rows,
        seed = snapshot.seed,
        colorArgb = snapshot.colorArgb,
        aspect = snapshot.aspectName,
        preview = snapshot.previewName,
        scale = snapshot.scaleName,
        scaler = snapshot.scalerName,
        kernel = snapshot.kernelName,
        wrapEdges = snapshot.wrapEdges,
        mirrorHorizontal = snapshot.mirrorHorizontal,
        mirrorVertical = snapshot.mirrorVertical,
        quarterTurn = snapshot.quarterTurn,
        paletteColors = metadata.paletteColors.map { it.toArgb() },
        pixelsBase64 = encodePixels(snapshot.pixels),
      )
    )

  fun decode(text: String, id: String, width: Int, height: Int): PatternPayload? = try {
    val document = json.decodeFromString<Document>(text)
    if (document.format != FORMAT || document.version > VERSION) {
      null
    } else {
      val columns = document.columns.takeIf { it > 0 } ?: width
      val rows = document.rows.takeIf { it > 0 } ?: height
      val pixels = if (document.pixelsBase64.isEmpty()) {
        null
      } else {
        decodePixels(document.pixelsBase64) ?: return null
      }
      if (pixels != null && pixels.size != columns * rows) return null
      PatternPayload(
        metadata = PatternMetadata(
          id = id,
          name = document.name.ifBlank { "Pattern" },
          paletteId = document.paletteId,
          paletteName = document.paletteName,
          paletteColors = document.paletteColors.map { Color(it) },
          columns = columns,
          rows = rows,
          savedAtMillis = document.savedAt,
        ),
        pixels = pixels,
        seed = document.seed,
        colorArgb = document.colorArgb,
        aspectName = document.aspect,
        previewName = document.preview,
        scaleName = document.scale,
        scalerName = document.scaler,
        kernelName = document.kernel,
        wrapEdges = document.wrapEdges,
        mirrorHorizontal = document.mirrorHorizontal,
        mirrorVertical = document.mirrorVertical,
        quarterTurn = document.quarterTurn,
      )
    }
  } catch (_: Exception) {
    null
  }

  private fun encodePixels(pixels: IntArray): String {
    val buffer = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
    buffer.asIntBuffer().put(pixels)
    return Base64.getEncoder().encodeToString(buffer.array())
  }

  private fun decodePixels(encoded: String): IntArray? = try {
    val bytes = Base64.getDecoder().decode(encoded)
    if (bytes.size % Int.SIZE_BYTES != 0) return null
    val ints = IntArray(bytes.size / Int.SIZE_BYTES)
    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().get(ints)
    ints
  } catch (_: Exception) {
    null
  }

  @Serializable
  private data class Document(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val name: String = "Pattern",
    val paletteId: String = "",
    val paletteName: String = "",
    val savedAt: Long = 0L,
    val columns: Int = 0,
    val rows: Int = 0,
    val seed: Int = 0,
    @SerialName("colorArgb") val colorArgb: Int = 0,
    val aspect: String = OutputAspect.Portrait9x16.name,
    val preview: String = PreviewScale.Half.name,
    val scale: String = OutputScale.One.name,
    val scaler: String = PixelArtScaler.Nearest.name,
    val kernel: String = KernelSize.Three.name,
    val wrapEdges: Boolean = false,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
    val quarterTurn: Boolean = false,
    val paletteColors: List<Int> = emptyList(),
    val pixelsBase64: String = "",
  )
}

/** The metadata and settings read from one pattern JSON document. */
internal class PatternPayload(
  val metadata: PatternMetadata,
  /** The drawing pixels carried by the JSON; null for a pattern written before they were added. */
  val pixels: IntArray?,
  val seed: Int,
  val colorArgb: Int,
  val aspectName: String,
  val previewName: String,
  val scaleName: String,
  val scalerName: String,
  val kernelName: String,
  val wrapEdges: Boolean,
  val mirrorHorizontal: Boolean,
  val mirrorVertical: Boolean,
  /** Whether every window was also offered turned a quarter turn; absent from older files, so false. */
  val quarterTurn: Boolean = false,
)
