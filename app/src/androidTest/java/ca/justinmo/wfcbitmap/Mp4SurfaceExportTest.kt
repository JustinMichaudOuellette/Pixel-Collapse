package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import ca.justinmo.wfcbitmap.video.Mp4Encoder
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.stillMetalFrame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The direct-to-encoder path must produce a playable MP4, with the same orientation as the readback
 * path and with the requested duration.
 */
@RunWith(AndroidJUnit4::class)
class Mp4SurfaceExportTest {

  private val size = 240
  private val fps = 10
  private val frames = 12

  @Test
  fun aShaderFrameCanBeEncodedWithoutReadback() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val renderer = MetalOffscreenRenderer()
    assertTrue("renderer did not open: ${renderer.failure}", renderer.open())
    val file = File.createTempFile("wfc-surface-", ".mp4", context.cacheDir)
    try {
      assertTrue("EGL presentation time is unavailable", renderer.supportsWindowSurface())
      renderer.upload(topBottomTexture(), size, size)
      val settings = MetalSettings(
        emboss = 0f,
        metallic = 0f,
        roughness = 0.5f,
        textureTint = 1f,
        lightIntensity = 20f,
        environmentIntensity = 0f,
        animateLight = false,
      )
      val frame = stillMetalFrame(size, size, 1, 0, frames, settings)
      val reference = renderer.render(frame, IntArray(size * size))
        ?: error("No reference frame")

      val bytes = Mp4Encoder.encodeToSurface(
        context = context,
        width = size,
        height = size,
        frameCount = frames,
        fps = fps,
      ) { surface, index, pts ->
        val current = stillMetalFrame(size, size, 1, index, frames, settings)
        if (!renderer.renderToSurface(current, surface, pts)) {
          error("Could not render frame $index")
        }
      }
      assertTrue("encoder produced no bytes", bytes.size > 1_000)
      file.writeBytes(bytes)

      val extractor = android.media.MediaExtractor()
      try {
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val times = ArrayList<Long>()
        while (extractor.sampleTime >= 0 && times.size < 30) {
          times.add(extractor.sampleTime)
          if (!extractor.advance()) break
        }
        android.util.Log.i("Mp4Surface", "sample times=$times")
        assertTrue("expected $frames samples, got ${times.size}", times.size == frames)
        assertTrue(
          "last sample at ${times.last()}, expected ${(frames - 1) * 1000L / fps}",
          times.last() == (frames - 1) * 1000L / fps,
        )
      } finally {
        extractor.release()
      }

      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(file.absolutePath)
        val decoded = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        assertNotNull("decoder returned no frame", decoded)
        decoded!!
        try {
          val referenceTop = averageRow(reference, 0, 40)
          val referenceBottom = averageRow(reference, size - 40, size)
          val decodedTop = averageBitmapRow(decoded, 0, 40)
          val decodedBottom = averageBitmapRow(decoded, size - 40, size)
          assertTrue(
            "decoded frame was blank (top=$decodedTop bottom=$decodedBottom)",
            decodedTop > 5 || decodedBottom > 5,
          )
          // The source is white on top and black at the bottom; the encoded frame must keep that.
          assertTrue(
            "orientation mismatch: reference top=$referenceTop bottom=$referenceBottom, " +
              "decoded top=$decodedTop bottom=$decodedBottom",
            decodedTop > decodedBottom + 10,
          )
        } finally {
          decoded.recycle()
        }
      } finally {
        retriever.release()
      }
    } finally {
      renderer.close()
      file.delete()
    }
  }

  private fun topBottomTexture(): IntArray = IntArray(size * size) { index ->
    if (index / size < size / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
  }

  private fun averageRow(pixels: IntArray, start: Int, end: Int): Double {
    var sum = 0.0
    var count = 0
    for (y in start until end) {
      for (x in 0 until size) {
        val p = pixels[y * size + x]
        sum += (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3.0
        count++
      }
    }
    return sum / count
  }

  private fun averageBitmapRow(bitmap: Bitmap, start: Int, end: Int): Double {
    var sum = 0.0
    var count = 0
    for (y in start until end) {
      for (x in 0 until size) {
        val p = bitmap.getPixel(x, y)
        sum += (((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)) / 3.0
        count++
      }
    }
    return sum / count
  }
}
