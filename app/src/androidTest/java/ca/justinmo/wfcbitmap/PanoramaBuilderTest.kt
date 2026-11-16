package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.video.PanoramaParams
import ca.justinmo.wfcbitmap.video.buildPanorama
import ca.justinmo.wfcbitmap.video.videoPlan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The preview's panorama builder has to produce a surface wider than one frame and, for the normal
 * one-solve loop, a closed wrap. If it ever falls back to repeating one solved frame the preview is back
 * to inventing screen-to-screen seams, so that is what these assertions protect.
 */
@RunWith(AndroidJUnit4::class)
class PanoramaBuilderTest {

  @Test
  fun buildsARealLoopWiderThanOneFrame() = runBlocking {
    val source = canonicalPattern()
    val plan = videoPlan(
      frameColumns = 9,
      frameRows = 9,
      pixelsPerCell = 4,
      cellSize = 1,
      screens = 2,
      fps = 10,
      pixelsPerFrame = 2,
      outputWidth = 36,
      outputHeight = 36,
    )
    val built = buildPanorama(
      params = PanoramaParams(
        sourcePixels = source,
        sourceWidth = 9,
        sourceHeight = 9,
        seed = 424242,
        plan = plan,
        wrapEdges = true,
        mirrorHorizontal = true,
        mirrorVertical = true,
        quarterTurn = true,
        bestEffort = false,
      ),
      mutex = Mutex(),
    )

    assertEquals(plan.panoramaWidth, built.width)
    assertEquals(plan.frameWidth, built.frameWidth)
    assertEquals(plan.frameHeight, built.frameHeight)
    assertEquals(built.width * built.height, built.pixels.size)
    assertTrue("the preview must show more than one screen", built.width > built.frameWidth)
    assertTrue("the one-solve loop must close", built.loopClosed)
    assertTrue("a horizontal panorama needs no transpose", built.previewPixels === built.pixels)
    assertEquals(built.previewWidth * built.previewHeight, built.previewPixels.size)
  }

  /** The canonical 9x9 motif from AGENTS.md, in two colours. */
  private fun canonicalPattern(): IntArray {
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val ink = 0xFF101018.toInt()
    val paper = 0xFFF0F0F0.toInt()
    return IntArray(81) { index ->
      if (rows[index / 9][index % 9] == '*') paper else ink
    }
  }
}
