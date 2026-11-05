package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.editor.DitherMode
import ca.justinmo.wfcbitmap.editor.PhotoPatternPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a synthetic photograph through the camera pipeline on the device and writes each step to
 * PNG, so the downscale, palette mapping and dither can be looked at rather than only asserted.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPatternVisualTest {

  @Test
  fun aPhotoWalksThroughEveryStep() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "photo-tests")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw AssertionError("Could not create ${directory.absolutePath}")
    }

    val source = syntheticPhoto()
    val cropped = PhotoPatternPipeline.cropToAspect(source, 9f / 16f)
    val (columns, rows) = PhotoPatternPipeline.targetSize(48, 9f / 16f, cellSize = 1)
    val downscaled = PhotoPatternPipeline.downscale(cropped, columns, rows)
    // A palette with both the photo's warm browns and a few accents: a posterise step should replace
    // gradients with broad colour fields, not with stripes from a palette that has no mid-tones.
    val palette = intArrayOf(
      Color.rgb(20, 20, 26),
      Color.rgb(232, 74, 95),
      Color.rgb(99, 199, 77),
      Color.rgb(62, 155, 255),
      Color.rgb(255, 200, 87),
      Color.WHITE,
      Color.rgb(120, 90, 60),
      Color.rgb(180, 140, 100),
      Color.rgb(80, 60, 50),
      Color.rgb(230, 190, 140),
    )
    val subset = PhotoPatternPipeline.paletteSubset(downscaled, palette, count = 7)
    val posterized = PhotoPatternPipeline.posterize(downscaled, subset)
    val floyd = PhotoPatternPipeline.dither(
      downscaled, columns, rows, subset, DitherMode.FloydSteinberg, 1f,
    )
    val bayer = PhotoPatternPipeline.dither(
      downscaled, columns, rows, subset, DitherMode.Bayer8, 1f,
    )

    save(directory, "1-source", cropped)
    save(directory, "2-downscale", downscaled, columns, rows)
    save(directory, "3-posterize", posterized, columns, rows)
    save(directory, "4-floyd", floyd, columns, rows)
    save(directory, "5-bayer8", bayer, columns, rows)

    assertEquals(columns * rows, downscaled.size)
    assertTrue("Floyd and posterize are identical", !floyd.contentEquals(posterized))
    assertTrue("Bayer and Floyd are identical", !bayer.contentEquals(floyd))
    val allowed = subset.map { it and 0xFFFFFF }.toSet()
    assertTrue(posterized.all { (it and 0xFFFFFF) in allowed })
    assertTrue("posterize used only one colour", posterized.distinct().size > 2)

    val step = File(directory, "4-floyd.png")
    Log.i(TAG, "camera pipeline images written to ${directory.absolutePath}, final ${step.length()} bytes")
  }

  /**
   * The camera tool's first step, on a real encoded photo.
   *
   * A photo whose file is perfectly readable used to come back as null from [PhotoPatternPipeline
   * .decode], because the size pass and the decode pass were read as one: BitmapFactory returns null
   * from an `inJustDecodeBounds` pass by design.
   */
  @Test
  fun aReadablePhotoDecodesOffItsUri() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.cacheDir, "photo-tests").apply { mkdirs() }
    val photo = syntheticPhoto()
    val file = File(directory, "decode-source.jpg")
    FileOutputStream(file).use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    Log.i(TAG, "decode source ${file.absolutePath}, ${file.length()} bytes")

    val decoded = PhotoPatternPipeline.decode(context, Uri.fromFile(file))
    assertNotNull("a readable JPEG decoded to nothing", decoded)
    assertEquals(photo.width, decoded!!.width)
    assertEquals(photo.height, decoded.height)
  }

  /** A soft gradient with a few coloured discs: enough structure to show every step. */
  private fun syntheticPhoto(): Bitmap {
    val width = 480
    val height = 640
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
      0f, 0f, width.toFloat(), height.toFloat(),
      intArrayOf(Color.rgb(20, 24, 40), Color.rgb(240, 180, 100), Color.rgb(20, 40, 60)),
      null,
      Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    paint.shader = null
    val discs = listOf(
      Triple(120f, 180f, 90f to Color.rgb(232, 74, 95)),
      Triple(350f, 260f, 110f to Color.rgb(62, 155, 255)),
      Triple(220f, 470f, 130f to Color.rgb(99, 199, 77)),
      Triple(390f, 560f, 70f to Color.rgb(255, 220, 90)),
    )
    for ((x, y, disc) in discs) {
      paint.color = disc.second
      canvas.drawCircle(x, y, disc.first, paint)
    }
    return bitmap
  }

  private fun save(directory: File, name: String, bitmap: Bitmap) {
    val file = File(directory, "$name.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    Log.i(TAG, "saved ${file.absolutePath}")
  }

  private fun save(directory: File, name: String, pixels: IntArray, width: Int, height: Int) {
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    save(directory, name, bitmap)
    bitmap.recycle()
  }

  private companion object {
    const val TAG = "PhotoPatternTest"
  }
}
