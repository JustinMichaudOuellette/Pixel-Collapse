package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The flat preview is now a GL material too. This renders it offscreen so the direct texture pass,
 * source-upload colour space and the zoom/window uniforms are verified without relying on a screenshot
 * of a SurfaceView.
 */
@RunWith(AndroidJUnit4::class)
class FlatPreviewShaderTest {

  @Test
  fun theFlatMaterialDrawsTheSourceAndZooms() {
    val size = 128
    val source = checkerTexture(size, cell = 8)
    val renderer = MetalOffscreenRenderer()
    assertTrue("renderer did not open: ${renderer.failure}", renderer.open())
    try {
      renderer.upload(source, size, size, srgb = false)
      val out = IntArray(size * size)
      val fitted = renderer.renderFlat(
        MetalFrame(
          width = size,
          height = size,
          viewportAspect = 1f,
          imageAspect = 1f,
          previewScale = 1f,
        ),
        out,
      )?.copyOf() ?: throw AssertionError("no flat frame")
      val zoomed = renderer.renderFlat(
        MetalFrame(
          width = size,
          height = size,
          viewportAspect = 1f,
          imageAspect = 1f,
          previewScale = 2f,
        ),
        out,
      )?.copyOf() ?: throw AssertionError("no zoomed flat frame")

      // The source is RGBA8 and the flat shader writes it through unchanged, so a 1:1 draw samples
      // the source colours exactly at these two cell centres.
      assertNear(source[32 * size + 32], fitted[32 * size + 32])
      assertNear(source[32 * size + 96], fitted[32 * size + 96])

      var difference = 0L
      for (i in fitted.indices) {
        val a = fitted[i]
        val b = zoomed[i]
        difference += abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)).toLong()
        difference += abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
        difference += abs((a and 0xFF) - (b and 0xFF))
      }
      Log.i(TAG, "flat zoom difference=$difference")

      val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "metal-tests",
      )
      directory.mkdirs()
      save(directory, "G-flat-fitted", fitted, size)
      save(directory, "G-flat-zoomed", zoomed, size)
      assertTrue("flat preview did not zoom", difference > size * size * 3L)
    } finally {
      renderer.close()
    }
  }

  /**
   * A checker with detail at the cell scale. A plain four-quadrant image would look identical at a
   * centred 2x zoom, because each quadrant stays uniform; this pattern actually changes when magnified.
   */
  private fun checkerTexture(width: Int, cell: Int): IntArray {
    val colours = intArrayOf(
      0xFFFF3040.toInt(),
      0xFF30A0FF.toInt(),
      0xFF30D060.toInt(),
      0xFFF0C040.toInt(),
    )
    return IntArray(width * width) { index ->
      val x = index % width
      val y = index / width
      colours[((x / cell) + (y / cell)) % colours.size]
    }
  }

  private fun assertNear(expected: Int, actual: Int) {
    assertTrue("red", abs(((expected ushr 16) and 0xFF) - ((actual ushr 16) and 0xFF)) <= 2)
    assertTrue("green", abs(((expected ushr 8) and 0xFF) - ((actual ushr 8) and 0xFF)) <= 2)
    assertTrue("blue", abs((expected and 0xFF) - (actual and 0xFF)) <= 2)
  }

  private fun save(directory: File, name: String, pixels: IntArray, size: Int) {
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    FileOutputStream(File(directory, "$name.png")).use {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
  }

  private companion object {
    const val TAG = "FlatPreviewTest"
  }
}
