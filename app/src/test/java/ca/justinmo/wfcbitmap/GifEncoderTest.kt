package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.editor.*
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.video.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The GIF container and the panning geometry, checked by reading the GIF back and inspecting frames. */
class GifEncoderTest {

  private val red = 0xFFFF0000.toInt()
  private val green = 0xFF00FF00.toInt()
  private val blue = 0xFF0000FF.toInt()
  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun aGifComesBackWithItsFramesAndPixels() {
    assumeNativeGif()
    val frames = listOf(
      intArrayOf(red, green, blue, white),
      intArrayOf(white, blue, green, red),
    )

    val bytes = GifEncoder.encode(
      2, 2, intArrayOf(red, green, blue, white), frameCount = frames.size, delayForFrame = { 5 },
    ) { frames[it] }

    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    try {
      assertEquals("two frames", 2, reader.getNumImages(true))
      val first = reader.read(0)
      assertEquals(red, first.getRGB(0, 0))
      assertEquals(green, first.getRGB(1, 0))
      assertEquals(blue, first.getRGB(0, 1))
      assertEquals(white, first.getRGB(1, 1))
      val second = reader.read(1)
      assertEquals(white, second.getRGB(0, 0))
      assertEquals(red, second.getRGB(1, 1))
    } finally {
      reader.dispose()
    }
  }

  @Test
  fun theFrameDelayIsWrittenIntoTheGraphicControlExtension() {
    assumeNativeGif()
    val bytes = GifEncoder.encode(
      1, 1, intArrayOf(red), frameCount = 1, delayForFrame = { 7 },
    ) { intArrayOf(red) }

    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    try {
      val tree = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0") as Element
      val gce = tree.getElementsByTagName("GraphicControlExtension").item(0) as Element
      assertEquals("7", gce.getAttribute("delayTime"))
    } finally {
      reader.dispose()
    }
  }

  @Test
  fun anOffPaletteColourTakesTheNearestPaletteEntry() {
    assumeNativeGif()
    // Green is not in the table; the nearest entry is still one of the two supplied colours.
    val bytes = GifEncoder.encode(
      1, 1, intArrayOf(red, blue), frameCount = 1, delayForFrame = { 5 },
    ) { intArrayOf(green) }

    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    try {
      val colour = reader.read(0).getRGB(0, 0)
      assertTrue("got ${Integer.toHexString(colour)}", colour == red || colour == blue)
    } finally {
      reader.dispose()
    }
  }

  @Test
  fun aBusyFrameSurvivesTheLzwCodeSizeChanges() {
    assumeNativeGif()
    // Enough random pixels to grow the LZW dictionary through several code sizes (and near a reset),
    // which is where an off-by-one in the code-size bump would show up as a decode failure.
    val palette = IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }
    val width = 128
    val height = 128
    val random = java.util.Random(1)
    val frame = IntArray(width * height) { palette[random.nextInt(palette.size)] }

    val bytes = GifEncoder.encode(width, height, palette, frameCount = 1, delayForFrame = { 5 }) { frame }

    val reader = ImageIO.getImageReadersByFormatName("gif").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
    try {
      val decoded = reader.read(0)
      for (y in 0 until height) {
        for (x in 0 until width) {
          assertEquals("pixel $x,$y", frame[y * width + x], decoded.getRGB(x, y))
        }
      }
    } finally {
      reader.dispose()
    }
  }

  @Test
  fun aVideoFrameUsesTheSelectedPreviewGrid() {
    for (preview in PreviewScale.entries) {
      val expected = generationPlan(OutputAspect.Portrait9x16, preview, KernelSize.Six)
      val frame = videoFramePlan(OutputAspect.Portrait9x16, preview, KernelSize.Six)
      assertEquals("${preview.label} columns", expected.columns, frame.columns)
      assertEquals("${preview.label} rows", expected.rows, frame.rows)
      assertEquals("${preview.label} pixels per cell", expected.pixelsPerCell, frame.pixelsPerCell)
      assertEquals("${preview.label} kernel", 2, frame.cellSize)
    }
  }

  @Test
  fun theOutputIsTheSelectedPreviewSize() {
    val frame = videoFramePlan(OutputAspect.Portrait9x16, PreviewScale.Two, KernelSize.Three)
    val plan = videoPlan(
      frameColumns = frame.columns, frameRows = frame.rows, pixelsPerCell = frame.pixelsPerCell,
      cellSize = frame.cellSize, screens = 2, fps = 25,
      outputWidth = 1080, outputHeight = 1920,
    )
    assertEquals(1080, plan.outputWidth)
    assertEquals(1920, plan.outputHeight)
    assertEquals("no upscaling at full preview", plan.frameWidth, plan.outputWidth)
  }

  @Test
  fun stitchedScreensShareTheOverlapAndFillThePanorama() {
    val plan = videoPlan(
      frameColumns = 10, frameRows = 2, pixelsPerCell = 1, cellSize = 1,
      screens = 4, fps = 25,
    )
    assertEquals(4, plan.screenCount)
    // 10 / 6 = 1, floored to 2, plus the one-cell seam margin.
    assertEquals(3, plan.overlapColumns)
    val panorama = IntArray(plan.panoramaWidth * plan.frameHeight)
    for (screen in 0 until plan.screenCount) {
      val value = screen + 1
      val pixels = IntArray(plan.frameWidth * plan.frameHeight) { value }
      stitchScreen(panorama, plan, pixels, screen)
    }

    val firstRow = (0 until plan.panoramaWidth).map { panorama[it] }
    assertEquals(
      listOf(
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        2, 2, 2, 2, 2, 2, 2,
        3, 3, 3, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4,
      ),
      firstRow,
    )
    assertTrue(panorama.all { it != 0 })
  }

  @Test
  fun aPlanCoversTheLoopItWasAskedFor() {
    val plan = videoPlan(
      frameColumns = 38, frameRows = 67, pixelsPerCell = 4, cellSize = 2,
      screens = 6, fps = 25,
    )

    // Overlap is 38 / 6 = 6 cells plus the seam margin, so each extra screen advances 31 cells.
    assertEquals(7, plan.overlapColumns)
    assertEquals(6, plan.screenCount)
    assertEquals(38 + 5 * 31, plan.panoramaColumns)
    assertEquals(38 * 4, plan.frameWidth)
    assertEquals(67 * 4, plan.frameHeight)
    // One frame per pixel of panorama width: the window moves exactly one pixel each time.
    assertEquals(plan.panoramaWidth, plan.frameCount)
    assertEquals(193 * 4, plan.panoramaWidth)
    assertEquals(4, plan.frameDelayCentiseconds)
    assertEquals(25f, plan.effectiveFps, 0.01f)
  }

  @Test
  fun theSlowestGifFrameRateIsOneFramePerSecond() {
    val plan = videoPlan(
      frameColumns = 10, frameRows = 2, pixelsPerCell = 1, cellSize = 1,
      screens = 2, fps = MIN_GIF_FPS,
    )

    // A GIF delay is whole centiseconds, so one second is 100 cs.
    assertEquals(MAX_GIF_DELAY_CENTISECONDS, plan.frameDelayCentiseconds)
    assertEquals(1f, plan.effectiveFps, 0.01f)
  }

  @Test
  fun aGifAskedForFewerThanOneFramePerSecondStillHoldsForOneSecond() {
    val plan = videoPlan(
      frameColumns = 10, frameRows = 2, pixelsPerCell = 1, cellSize = 1,
      screens = 2, fps = 0,
    )

    assertEquals(MAX_GIF_DELAY_CENTISECONDS, plan.frameDelayCentiseconds)
    assertEquals(1f, plan.effectiveFps, 0.01f)
  }

  @Test
  fun aLoopLongerThanTheSliderIsCapped() {
    val plan = videoPlan(
      frameColumns = 38, frameRows = 67, pixelsPerCell = 4, cellSize = 2,
      screens = 99, fps = 25,
    )

    assertEquals(MAX_LOOP_SCREENS, plan.screenCount)
    assertEquals(38 + (MAX_LOOP_SCREENS - 1) * 31, plan.panoramaColumns)
  }

  @Test
  fun aFullPreviewFrameUsesTheShortestLoop() {
    val frame = videoFramePlan(OutputAspect.Portrait9x16, PreviewScale.Two, KernelSize.Three)
    val widest = widestLoop(frame.columns, frame.rows, frame.columns - (frame.columns / 6 + 1))
    assertEquals(MIN_LOOP_SCREENS, widest)
    val plan = videoPlan(
      frameColumns = frame.columns, frameRows = frame.rows, pixelsPerCell = frame.pixelsPerCell,
      cellSize = frame.cellSize, screens = MAX_LOOP_SCREENS, fps = 25,
    )
    assertEquals(MIN_LOOP_SCREENS, plan.screenCount)
  }

  @Test
  fun theLoopIsMadeOfWholeScreens() {
    val plan = videoPlan(
      frameColumns = 76, frameRows = 135, pixelsPerCell = 4, cellSize = 2,
      screens = 4, fps = 25,
    )

    assertEquals(plan.panoramaColumns, plan.frameWidth / 4 + (plan.screenCount - 1) * (76 - plan.overlapColumns))
    assertEquals(13, plan.overlapColumns)
  }

  @Test
  fun aNarrowerSeamCopiesOnlyWhatWasPinned() {
    // A screen that had to give up part of its pin must not have the previous screen's edge pasted over
    // cells it was left free to choose. The paste would win, its own choice would follow immediately
    // after, and that disagreement is the hard jump at the seam.
    val plan = videoPlan(
      frameColumns = 10, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      screens = 2, fps = 25,
    )
    val advance = plan.frameWidth - plan.overlapColumns
    assertEquals(3, plan.overlapColumns)

    val wide = IntArray(plan.panoramaWidth)
    stitchScreen(wide, plan, IntArray(plan.frameWidth) { 1 }, 0)
    stitchScreen(wide, plan, IntArray(plan.frameWidth) { 2 }, 1)
    for (x in 0 until plan.overlapColumns) {
      assertEquals("full pin keeps $x", 1, wide[advance + x])
    }

    val narrow = IntArray(plan.panoramaWidth)
    stitchScreen(narrow, plan, IntArray(plan.frameWidth) { 1 }, 0)
    stitchScreen(narrow, plan, IntArray(plan.frameWidth) { 2 }, 1, seamColumns = 1)
    assertEquals("the one pinned cell is the previous screen's", 1, narrow[advance])
    for (x in 1 until plan.frameWidth) {
      assertEquals("the rest is this screen's own", 2, narrow[advance + x])
    }
    assertEquals("a narrower seam does not move the screen", plan.frameWidth, advance + plan.overlapColumns)
  }

  @Test
  fun eachFrameMovesTheWindowExactlyOnePixel() {
    val width = 10
    val panorama = IntArray(width) { it } // values 0..9
    val plan = videoPlan(
      frameColumns = 2, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      screens = 2, fps = 25,
    ).copy(panoramaWidth = width, frameWidth = 2, frameCount = width)

    val starts = (0 until plan.frameCount).map { cropFrame(panorama, width, plan, it).first() }
    assertEquals((0 until width).toList(), starts)
  }

  @Test
  fun theFrameAfterTheLastOneIsTheFirstOneAgain() {
    val width = 6
    val panorama = IntArray(width) { it } // values 0..5
    val plan = videoPlan(
      frameColumns = 2, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      screens = 2, fps = 25,
    ).copy(panoramaWidth = width, frameWidth = 2, frameCount = width)

    val first = cropFrame(panorama, width, plan, 0)
    assertTrue(first.contentEquals(intArrayOf(0, 1)))
    // The last frame straddles the loop point: its second half is the panorama's own beginning.
    val last = cropFrame(panorama, width, plan, width - 1)
    assertTrue(last.contentEquals(intArrayOf(width - 1, 0)))
    // And the frame after it is the first one again, which is what makes the clip loop.
    assertTrue(cropFrame(panorama, width, plan, width).contentEquals(first))
  }

  /**
   * The GIF encoder is native now, so the encode-and-decode cases belong on a device. On the JVM there
   * is no libwfc; skip rather than report a missing library as a broken encoder.
   */
  private fun assumeNativeGif() {
    Assume.assumeFalse(
      "native GIF encoding needs Android",
      System.getProperty("java.vm.name")?.contains("OpenJDK") == true,
    )
  }
}