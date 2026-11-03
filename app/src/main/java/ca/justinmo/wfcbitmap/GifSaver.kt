package ca.justinmo.wfcbitmap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes an animated GIF into the gallery, the way [ImageSaver] writes a PNG. */
object GifSaver {

  const val MIME_TYPE = "image/gif"

  /** A gallery-unique name for an export made at [timestampMillis]. */
  fun fileName(timestampMillis: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis))
    val millis = String.format(Locale.US, "%03d", timestampMillis % 1000)
    return "wfc-$stamp-$millis.gif"
  }

  suspend fun save(
    context: Context,
    bytes: ByteArray,
    name: String = fileName(System.currentTimeMillis()),
  ): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writeThroughMediaStore(context, bytes, name)
    } else {
      writeToSharedStorage(context, bytes, name)
    }
  }

  /** Saves an already-encoded GIF file without loading the whole thing into a byte array. */
  suspend fun save(
    context: Context,
    file: File,
    name: String = fileName(System.currentTimeMillis()),
  ): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writeFileThroughMediaStore(context, file, name)
    } else {
      writeFileToSharedStorage(context, file, name)
    }
  }

  fun viewIntent(picture: Uri): Intent =
    Intent(Intent.ACTION_VIEW)
      .setDataAndType(picture, MIME_TYPE)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun writeFileThroughMediaStore(context: Context, file: File, name: String): Uri {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${ImageSaver.ALBUM}")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
      ?: throw IOException("The gallery would not create an entry for $name")
    try {
      val out = resolver.openOutputStream(uri)
        ?: throw IOException("The gallery entry for $uri cannot be written")
      out.use { sink -> file.inputStream().use { source -> source.copyTo(sink) } }
      resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
        null,
        null,
      )
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    }
    return uri
  }

  @Suppress("DEPRECATION")
  private fun writeFileToSharedStorage(context: Context, file: File, name: String): Uri {
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val album = File(pictures, ImageSaver.ALBUM)
    if (!album.isDirectory && !album.mkdirs()) {
      throw IOException("Could not create $album")
    }
    val target = File(album, name)
    file.inputStream().use { source -> target.outputStream().use { source.copyTo(it) } }
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.DATA, target.absolutePath)
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: run {
        target.delete()
        throw IOException("The gallery would not accept $target")
      }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun writeThroughMediaStore(context: Context, bytes: ByteArray, name: String): Uri {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${ImageSaver.ALBUM}")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
      ?: throw IOException("The gallery would not create an entry for $name")
    try {
      val out = resolver.openOutputStream(uri)
        ?: throw IOException("The gallery entry for $uri cannot be written")
      out.use { it.write(bytes) }
      resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
        null,
        null,
      )
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    }
    return uri
  }

  @Suppress("DEPRECATION")
  private fun writeToSharedStorage(context: Context, bytes: ByteArray, name: String): Uri {
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val album = File(pictures, ImageSaver.ALBUM)
    if (!album.isDirectory && !album.mkdirs()) {
      throw IOException("Could not create $album")
    }
    val file = File(album, name)
    file.outputStream().use { it.write(bytes) }
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.DATA, file.absolutePath)
    }
    return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: run {
        file.delete()
        throw IOException("The gallery would not accept $file")
      }
  }
}
