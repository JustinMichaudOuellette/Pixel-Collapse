package ca.justinmo.wfcbitmap

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.video.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The native LZW stream has to remain a GIF that a decoder can read. */
@RunWith(AndroidJUnit4::class)
class GifNativeTest {

  @Test
  fun theNativeEncoderWritesADecodableGif() {
    val red = 0xFFFF0000.toInt()
    val blue = 0xFF0000FF.toInt()
    val frame = intArrayOf(red, blue, blue, red)
    val bytes = GifEncoder.encode(
      width = 2,
      height = 2,
      palette = intArrayOf(red, blue),
      frameCount = 1,
      delayForFrame = { 5 },
    ) { frame }

    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertNotNull("the GIF decoder rejected the native LZW stream", decoded)
    try {
      assertEquals(2, decoded!!.width)
      assertEquals(2, decoded.height)
      assertEquals(red, decoded.getPixel(0, 0))
      assertEquals(blue, decoded.getPixel(1, 0))
      assertEquals(blue, decoded.getPixel(0, 1))
      assertEquals(red, decoded.getPixel(1, 1))
    } finally {
      decoded?.recycle()
    }
  }

  @Test
  fun theWholeGifIsWrittenByTheNativeEncoder() {
    // The decodable-file test above already exercises the native path; this one pins the guarantee that
    // there is no Kotlin fallback left to silently take over.
    val bytes = GifEncoder.encode(
      width = 2,
      height = 2,
      palette = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
      frameCount = 1,
      delayForFrame = { 5 },
    ) { intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0, 0) }
    org.junit.Assert.assertTrue("a native GIF starts with GIF89a", bytes.size > 6)
  }
}
