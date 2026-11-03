package ca.justinmo.wfcbitmap.video
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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

/** Writes an exported MP4 into the gallery. */
object Mp4Saver {

  const val MIME_TYPE = "video/mp4"

  fun fileName(timestampMillis: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis))
    val millis = String.format(Locale.US, "%03d", timestampMillis % 1000)
    return "wfc-$stamp-$millis.mp4"
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

  fun viewIntent(video: Uri): Intent =
    Intent(Intent.ACTION_VIEW)
      .setDataAndType(video, MIME_TYPE)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun writeThroughMediaStore(context: Context, bytes: ByteArray, name: String): Uri {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, name)
      put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/${ImageSaver.ALBUM}")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending)
      ?: throw IOException("The gallery would not create an entry for $name")
    try {
      val out = resolver.openOutputStream(uri)
        ?: throw IOException("The gallery entry for $uri cannot be written")
      out.use { it.write(bytes) }
      resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
        null,
        null,
      )
      resolver.notifyChange(uri, null)
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    }
    return uri
  }

  @Suppress("DEPRECATION")
  private fun writeToSharedStorage(context: Context, bytes: ByteArray, name: String): Uri {
    val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
    val album = File(movies, ImageSaver.ALBUM)
    if (!album.isDirectory && !album.mkdirs()) {
      throw IOException("Could not create $album")
    }
    val file = File(album, name)
    file.outputStream().use { it.write(bytes) }
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, name)
      put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Video.Media.DATA, file.absolutePath)
    }
    return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
      ?: run {
        file.delete()
        throw IOException("The gallery would not accept $file")
      }
  }
}