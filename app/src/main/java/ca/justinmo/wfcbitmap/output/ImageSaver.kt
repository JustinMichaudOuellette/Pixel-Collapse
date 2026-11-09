package ca.justinmo.wfcbitmap.output

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a picture into the device's gallery.
 *
 * There are two code paths because the storage model changed in API 29: from Q on, a row is inserted
 * into [MediaStore] and the PNG is streamed into that row, which needs no permission at all; before Q
 * the file has to be written into shared storage directly, which needs WRITE_EXTERNAL_STORAGE.
 *
 * The bitmap is not copied here. Callers hand over a bitmap they own -- the generator reuses its render
 * targets, so handing over a live one would let the next generation repaint the pixels mid-encode.
 */
object ImageSaver {

  /** Gallery folder the saved pictures appear in, under the device's Pictures directory. */
  const val ALBUM = "WFCBitmap"

  /** Cache folder shared pictures are written to; matches the path in `res/xml/file_paths.xml`. */
  const val SHARE_FOLDER = "shared"

  /** Suffix of the [FileProvider] authority, which is `${applicationId}.fileprovider`. */
  const val FILE_PROVIDER_SUFFIX = ".fileprovider"

  /** MIME type of what is written; also what the gallery row is filed under. */
  private const val MIME_TYPE = "image/png"

  /**
   * True on the API levels where writing into the gallery still needs a runtime grant of
   * WRITE_EXTERNAL_STORAGE (26-28). From API 29 the [MediaStore] insert needs none.
   *
   * @param sdk the API level to answer for; defaults to the running device.
   */
  fun needsLegacyWritePermission(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk < API_LEVEL_Q

  /** The permission [needsLegacyWritePermission] talks about. */
  const val LEGACY_WRITE_PERMISSION = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

  /**
   * `Build.VERSION_CODES.Q` as a plain number: the check above stays a comparison of two ints, which
   * keeps it a unit test rather than something that needs a device.
   */
  private const val API_LEVEL_Q = 29

  /**
   * A gallery-unique-looking file name for a picture saved at [timestampMillis].
   *
   * Seconds are not enough on their own -- two saves in the same second would collide -- so the
   * millisecond remainder is appended. The extension is part of the name because MediaStore uses it to
   * decide how the file is treated.
   */
  fun fileName(timestampMillis: Long): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMillis))
    // Zero-padded so names sort in the order they were saved.
    val millis = String.format(Locale.US, "%03d", timestampMillis % 1000)
    return "wfc-$stamp-$millis.png"
  }

  /**
   * [bitmap], or a copy of it resampled to exactly [width] x [height].
   *
   * A size rather than a multiplier, because the save size is not a multiple of anything on screen: it is
   * its own size, worked out from the grid by [savedPixelSize]. The factors between any preview
   * size and any save size are whole numbers (one, two, four, eight times a cell).
   *
   * [scaler] decides how a bigger size is made. [PixelArtScaler.Nearest] copies blocks, which is exact but
   * keeps every stair-step. The others first put the picture back to its base resolution, then run their
   * own pass for [scaleFactor] so the corners are shaped at the size the file is written at. The caller
   * owns the result and must recycle it when it is not [bitmap].
   */
  fun atSize(
    bitmap: Bitmap,
    width: Int,
    height: Int,
    scaler: PixelArtScaler = PixelArtScaler.Nearest,
    scaleFactor: Int = 1,
  ): Bitmap {
    if (scaler != PixelArtScaler.Nearest && scaleFactor > 1) {
      val baseWidth = width / scaleFactor
      val baseHeight = height / scaleFactor
      if (baseWidth > 0 && baseHeight > 0) {
        val base = if (bitmap.width == baseWidth && bitmap.height == baseHeight) {
          bitmap
        } else {
          Bitmap.createScaledBitmap(bitmap, baseWidth, baseHeight, false)
        }
        val upscaled = try {
          upscale(base, scaleFactor, scaler)
        } finally {
          if (base !== bitmap) base.recycle()
        }
        if (upscaled.width == width && upscaled.height == height) return upscaled
        val fitted = Bitmap.createScaledBitmap(upscaled, width, height, false)
        if (fitted !== upscaled) upscaled.recycle()
        return fitted
      }
    }
    if (width == bitmap.width && height == bitmap.height) return bitmap
    return Bitmap.createScaledBitmap(bitmap, width, height, false)
  }

  private fun upscale(bitmap: Bitmap, factor: Int, scaler: PixelArtScaler): Bitmap {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val scaled = PixelScaler.scale(pixels, bitmap.width, bitmap.height, scaler, factor)
    return Bitmap.createBitmap(scaled.pixels, scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
  }

  /**
   * Writes [bitmap] into the gallery as a PNG and returns the URI it was written to.
   *
   * @param name file name including the extension; see [fileName].
   * @param width the width of the file, in pixels; see [atSize].
   * @param height the height of the file, in pixels.
   * @throws IOException when the gallery refuses the row or the encode fails, after cleaning up.
   */
  suspend fun saveToGallery(
    context: Context,
    bitmap: Bitmap,
    name: String = fileName(System.currentTimeMillis()),
    width: Int = bitmap.width,
    height: Int = bitmap.height,
    scaler: PixelArtScaler = PixelArtScaler.Nearest,
    scaleFactor: Int = 1,
  ): Uri = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writeThroughMediaStore(context, bitmap, name, width, height, scaler, scaleFactor)
    } else {
      writeToSharedStorage(context, bitmap, name, width, height, scaler, scaleFactor)
    }
  }

  /**
   * Writes [bitmap] to the app's cache for sharing and returns the URI a share sheet can hand out.
   *
   * The cache is the right home for a shared picture: it is temporary by definition, the app does not
   * need permission to write it, and the [FileProvider] declared in the manifest is the only way out.
   *
   * @param width the width of the file, in pixels; see [atSize].
   * @param height the height of the file, in pixels.
   */
  suspend fun prepareShare(
    context: Context,
    bitmap: Bitmap,
    width: Int = bitmap.width,
    height: Int = bitmap.height,
    scaler: PixelArtScaler = PixelArtScaler.Nearest,
    scaleFactor: Int = 1,
  ): Uri = withContext(Dispatchers.IO) {
    val name = fileName(System.currentTimeMillis())
    val dir = File(context.cacheDir, SHARE_FOLDER)
    if (!dir.isDirectory && !dir.mkdirs()) {
      throw IOException("Could not create $dir")
    }
    // Drop yesterday's shares so the cache cannot grow forever, but leave recent ones alone: the app a
    // picture was handed to may still be reading it.
    val cutoff = System.currentTimeMillis() - SHARE_LIFETIME_MS
    dir.listFiles()?.forEach { old -> if (old.lastModified() < cutoff) old.delete() }
    val file = File(dir, name)

    val scaled = atSize(bitmap, width, height, scaler, scaleFactor)
    try {
      file.outputStream().use { out ->
        if (!scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
          throw IOException("The picture could not be encoded as PNG")
        }
      }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }

    FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
  }

  /** The share intent for a picture written by [prepareShare]. */
  fun shareIntent(picture: Uri): Intent =
    Intent(Intent.ACTION_SEND)
      .setType(MIME_TYPE)
      .putExtra(Intent.EXTRA_STREAM, picture)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  /**
   * The intent that opens a picture written by [saveToGallery], for the "View" action of a save's snackbar.
   *
   * It points at the very row that was just inserted, so whatever the device opens pictures with -- the
   * gallery, or Photos -- shows it, and the grant lets it read a row it did not insert itself.
   */
  fun viewIntent(picture: Uri): Intent =
    Intent(Intent.ACTION_VIEW)
      .setDataAndType(picture, MIME_TYPE)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

  /**
   * API 29+: insert the row first, stream the PNG into it, then publish it.
   *
   * The row is inserted with `IS_PENDING` set so no other app can see a half-written picture, and
   * deleted again if the encode fails, so a failure leaves no empty entry in the gallery.
   */
  @RequiresApi(Build.VERSION_CODES.Q)
  private fun writeThroughMediaStore(
    context: Context,
    bitmap: Bitmap,
    name: String,
    width: Int,
    height: Int,
    scaler: PixelArtScaler,
    scaleFactor: Int,
  ): Uri {
    val resolver = context.contentResolver
    val pending = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
      ?: throw IOException("The gallery would not create an entry for $name")

    val scaled = atSize(bitmap, width, height, scaler, scaleFactor)
    try {
      encodeInto(resolver, uri, scaled)
      resolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
        null,
        null,
      )
      resolver.notifyChange(uri, null)
    } catch (e: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw e
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
    return uri
  }

  /**
   * API 26-28: write the file into shared storage, then add the gallery row that points at it.
   *
   * @throws IOException when the album cannot be created or the write fails.
   */
  @Suppress("DEPRECATION")
  private fun writeToSharedStorage(
    context: Context,
    bitmap: Bitmap,
    name: String,
    width: Int,
    height: Int,
    scaler: PixelArtScaler,
    scaleFactor: Int,
  ): Uri {
    val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val album = File(pictures, ALBUM)
    if (!album.isDirectory && !album.mkdirs()) {
      throw IOException("Could not create $album")
    }
    val file = File(album, name)
    val scaled = atSize(bitmap, width, height, scaler, scaleFactor)
    try {
      file.outputStream().use { out ->
        if (!scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) {
          throw IOException("The picture could not be encoded as PNG")
        }
      }
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }

    // Registering the file is what makes it appear in the gallery; a media scan would do the same but
    // only whenever the scanner next runs. A failed insert takes the file with it, so a failure does
    // not leave a picture in Pictures/ that nothing knows about.
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

  /** Streams the PNG into an already-inserted gallery row. */
  private fun encodeInto(
    resolver: ContentResolver,
    uri: Uri,
    bitmap: Bitmap,
  ) {
    val out = resolver.openOutputStream(uri)
      ?: throw IOException("The gallery entry for $uri cannot be written")
    out.use {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)) {
        throw IOException("The picture could not be encoded as PNG")
      }
    }
  }

  /** Ignored by the PNG encoder -- PNG is lossless -- but the API still wants a number. */
  private const val PNG_QUALITY = 100

  /** How long a shared picture is left in the cache before the next share clears it away. */
  private const val SHARE_LIFETIME_MS = 60L * 60 * 1000
}