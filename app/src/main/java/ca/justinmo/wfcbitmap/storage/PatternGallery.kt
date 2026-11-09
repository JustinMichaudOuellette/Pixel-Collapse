package ca.justinmo.wfcbitmap.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import ca.justinmo.wfcbitmap.output.ImageSaver
import ca.justinmo.wfcbitmap.palette.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Patterns live in the gallery, not in an app-private bookmark list.
 *
 * Each pattern is a lossless PNG whose pixels are the drawing at one pixel per cell and whose `tEXt`
 * chunk carries the extensible JSON document from [PatternJson]. The PNG is a normal gallery image, so
 * the file can be seen and copied outside the app, while the JSON keeps the settings that an ordinary
 * image cannot hold.
 *
 * The picker is the app's own grid, not the system photo picker: it lists the `wfc-pattern-*` rows in
 * the gallery and turns each back into a [SavedPattern] with its preview.
 */
class PatternGallery(private val context: Context) {

  /**
   * Writes [snapshot] into the gallery's WFCBitmap album. The file name is generated, and the image is
   * [previewPixels] (the editor's generated preview); the pattern itself is the JSON metadata.
   */
  suspend fun save(
    palette: Palette,
    snapshot: EditorSnapshot,
    previewPixels: IntArray,
    previewWidth: Int,
    previewHeight: Int,
  ): Uri? = withContext(Dispatchers.IO) {
    if (!snapshot.isValid || previewPixels.size != previewWidth * previewHeight) {
      return@withContext null
    }
    val fileName = fileNameFor(System.currentTimeMillis())
    val metadata = PatternMetadata(
      id = fileName,
      name = fileName.removeSuffix(".png"),
      paletteId = palette.id,
      paletteName = palette.name,
      paletteColors = palette.colors,
      columns = snapshot.columns,
      rows = snapshot.rows,
      savedAtMillis = System.currentTimeMillis(),
    )
    try {
      val bytes = PatternPng.encode(
        metadata = metadata,
        snapshot = snapshot,
        previewPixels = previewPixels,
        previewWidth = previewWidth,
        previewHeight = previewHeight,
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        writeThroughMediaStore(fileName, bytes)
      } else {
        writeToSharedStorage(fileName, bytes)
      }
    } catch (_: Exception) {
      null
    }
  }

  /** Every pattern in the gallery album, newest first, with its drawing decoded. */
  suspend fun list(): List<SavedPattern> = withContext(Dispatchers.IO) {
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND " +
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
      "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
    }
    val arguments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      arrayOf("$PREFIX%", "${Environment.DIRECTORY_PICTURES}/${ImageSaver.ALBUM}%")
    } else {
      arrayOf("$PREFIX%")
    }
    val result = ArrayList<SavedPattern>()
    try {
      context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        arguments,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
      )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
          val id = cursor.getLong(idColumn)
          val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id,
          )
          readPattern(uri)?.let { result.add(it) }
        }
      }
    } catch (_: Exception) {
      // A provider that refuses the query just means an empty picker, not a crash.
    }
    result
  }

  /** Deletes the gallery row [id] (the URI's string form). */
  suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
    try {
      context.contentResolver.delete(Uri.parse(id), null, null) > 0
    } catch (_: Exception) {
      false
    }
  }

  private fun readPattern(uri: Uri): SavedPattern? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
      val bytes = readLimited(input)
      if (bytes == null) null else PatternPng.decode(bytes, uri.toString())
    }
  } catch (_: Exception) {
    null
  }

  private fun readLimited(input: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      if (total > MAX_BYTES) return null
      out.write(buffer, 0, count)
    }
    return out.toByteArray()
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun writeThroughMediaStore(name: String, bytes: ByteArray): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${ImageSaver.ALBUM}")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return null
    try {
      resolver.openOutputStream(uri)?.use { it.write(bytes) }
        ?: throw IOException("the gallery row cannot be written")
      resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
        null,
        null,
      )
      resolver.notifyChange(uri, null)
      return uri
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    }
  }

  @Suppress("DEPRECATION")
  private fun writeToSharedStorage(name: String, bytes: ByteArray): Uri? {
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val album = File(pictures, ImageSaver.ALBUM)
    if (!album.isDirectory && !album.mkdirs()) return null
    val file = File(album, name)
    file.writeBytes(bytes)
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.DATA, file.absolutePath)
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: run {
        file.delete()
        null
      }
  }

  private fun fileNameFor(timestamp: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
    val millis = String.format(Locale.US, "%03d", timestamp % 1000)
    return "$PREFIX$stamp-$millis.png"
  }

  private companion object {
    const val PREFIX = "wfc-pattern-"
    const val MIME_TYPE = "image/png"
    const val MAX_BYTES = 16_000_000
  }
}
