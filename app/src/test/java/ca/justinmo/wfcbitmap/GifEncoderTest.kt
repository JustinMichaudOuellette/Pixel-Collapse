package ca.justinmo.wfcbitmap

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The GIF container and the panning geometry, checked by reading the GIF back and inspecting frames. */
class GifEncoderTest {

  private val red = 0xFFFF0000.toInt()
  private val green = 0xFF00FF00.toInt()
  private val blue = 0xFF0000FF.toInt()
  private val white = 0xFFFFFFFF.toInt()

  @Test
  fun aGifComesBackWithItsFramesAndPixels() {
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
  fun aVideoFrameIsCappedSoItsPanoramaCanBeSolved() {
    val plan = videoFramePlan(OutputAspect.Portrait9x16, PreviewScale.Two, KernelSize.Six)

    assertTrue("frame grid ${plan.columns}x${plan.rows}", plan.columns * plan.rows <= 10_260)
    assertEquals(4, plan.pixelsPerCell)
    assertEquals(2, plan.cellSize)
  }

  @Test
  fun aModestPreviewIsUsedAsTheVideoFrameUnchanged() {
    val preview = generationPlan(OutputAspect.Portrait9x16, PreviewScale.Half, KernelSize.Three)
    val frame = videoFramePlan(OutputAspect.Portrait9x16, PreviewScale.Half, KernelSize.Three)

    assertEquals(preview.columns, frame.columns)
    assertEquals(preview.rows, frame.rows)
  }

  @Test
  fun stitchedScreensShareTheOverlapAndFillThePanorama() {
    val plan = videoPlan(
      frameColumns = 10, frameRows = 2, pixelsPerCell = 1, cellSize = 1,
      durationSeconds = 2f, speed = 1f,
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
  fun aPlanStitchesEnoughScreensToCoverTheRequestedPan() {
    val plan = videoPlan(
      frameColumns = 38, frameRows = 67, pixelsPerCell = 4, cellSize = 2,
      durationSeconds = 4f, speed = 1f,
    )

    // Overlap is 38 / 6 = 6 cells plus the seam margin, so each extra screen advances 31 cells.
    assertEquals(7, plan.overlapColumns)
    assertEquals(6, plan.screenCount)
    assertEquals(38 + 5 * 31, plan.panoramaColumns)
    assertEquals(38 * 4, plan.frameWidth)
    assertEquals(67 * 4, plan.frameHeight)
    assertEquals(240, plan.frameCount)
    assertEquals(listOf(1, 2, 2), plan.delayPattern)
    assertEquals(1.02f, plan.effectiveSpeed, 0.05f)
  }

  @Test
  fun aVeryLongRequestIsCappedAtTheScreenBudget() {
    val plan = videoPlan(
      frameColumns = 38, frameRows = 67, pixelsPerCell = 4, cellSize = 2,
      durationSeconds = 20f, speed = 4f,
    )

    assertEquals(96, plan.screenCount)
    assertEquals(38 + 95 * 31, plan.panoramaColumns)
  }

  @Test
  fun theNewMaximumDurationAndSpeedDoNotHitTheCap() {
    val plan = videoPlan(
      frameColumns = 76, frameRows = 135, pixelsPerCell = 4, cellSize = 2,
      durationSeconds = 30f, speed = 2f,
    )

    assertEquals(74, plan.screenCount)
    assertTrue("effective ${plan.effectiveSpeed}", plan.effectiveSpeed >= 2f - 0.01f)
  }

  @Test
  fun theStitchedPanoramaIsMadeOfWholeScreens() {
    val plan = videoPlan(
      frameColumns = 76, frameRows = 135, pixelsPerCell = 4, cellSize = 2,
      durationSeconds = 10f, speed = 2f,
    )

    assertEquals(plan.panoramaColumns, plan.frameWidth / 4 + (plan.screenCount - 1) * (76 - plan.overlapColumns))
    assertEquals(13, plan.overlapColumns)
    assertTrue(plan.screenCount <= 96)
  }

  @Test
  fun aNarrowerSeamCopiesOnlyWhatWasPinned() {
    // A screen that had to give up part of its pin must not have the previous screen's edge pasted over
    // cells it was left free to choose. The paste would win, its own choice would follow immediately
    // after, and that disagreement is the hard jump at the seam.
    val plan = videoPlan(
      frameColumns = 10, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      durationSeconds = 2f, speed = 1f,
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
  fun theFirstFrameIsTheLeftEdgeAndTheLastIsTheRightEdge() {
    val width = 6
    val panorama = IntArray(width) { it } // values 0..5
    val plan = videoPlan(
      frameColumns = 2, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      durationSeconds = 1f, speed = 1f,
    ).copy(panoramaWidth = width, frameWidth = 2, frameCount = 3)

    val first = cropFrame(panorama, width, plan, 0)
    val last = cropFrame(panorama, width, plan, 2)

    assertTrue(first.contentEquals(intArrayOf(0, 1)))
    assertTrue(last.contentEquals(intArrayOf(4, 5)))
  }

  @Test
  fun framesMoveLeftToRight() {
    val width = 10
    val panorama = IntArray(width) { it }
    val plan = videoPlan(
      frameColumns = 2, frameRows = 1, pixelsPerCell = 1, cellSize = 1,
      durationSeconds = 1f, speed = 1f,
    ).copy(panoramaWidth = width, frameWidth = 2, frameCount = 5)

    val starts = (0 until 5).map { cropFrame(panorama, width, plan, it).first() }
    assertEquals(starts.sorted(), starts)
    assertEquals(0, starts.first())
    assertEquals(width - plan.frameWidth, starts.last())
  }
}
