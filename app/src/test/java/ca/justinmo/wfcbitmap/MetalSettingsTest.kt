package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.video.MetalFinish
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.ScrollDirection
import ca.justinmo.wfcbitmap.video.panoramaMetalFrame
import ca.justinmo.wfcbitmap.video.videoPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The material controls and the panorama mapping, without needing a GPU. */
class MetalSettingsTest {

  @Test
  fun aPresetChangesTheMetalAndLeavesTheRestAlone() {
    val base = MetalSettings(emboss = 1.9f, lightIntensity = 7f)

    val gold = base.withFinish(MetalFinish.Gold)

    assertEquals(MetalFinish.Gold, gold.finish)
    assertEquals(MetalFinish.Gold.baseColor, gold.baseColor)
    assertEquals(MetalFinish.Gold.roughness, gold.roughness, 0.0001f)
    assertEquals(1.9f, gold.emboss, 0.0001f)
    assertEquals(7f, gold.lightIntensity, 0.0001f)
  }

  @Test
  fun theControlsAreClampedToTheirDocumentedRanges() {
    val wild = MetalSettings(
      emboss = 99f,
      metallic = -2f,
      roughness = -1f,
      textureTint = 4f,
      lightIntensity = 100f,
      environmentIntensity = 9f,
      parallax = 4f,
      anisotropy = 3f,
      exposure = 0f,
    ).sanitised()

    assertEquals(2.5f, wild.emboss, 0.0001f)
    assertEquals(0f, wild.metallic, 0.0001f)
    assertEquals(0.03f, wild.roughness, 0.0001f)
    assertEquals(1f, wild.textureTint, 0.0001f)
    assertEquals(20f, wild.lightIntensity, 0.0001f)
    assertEquals(3f, wild.environmentIntensity, 0.0001f)
    assertEquals(0.35f, wild.parallax, 0.0001f)
    assertEquals(1f, wild.anisotropy, 0.0001f)
    assertEquals(0.2f, wild.exposure, 0.0001f)
  }

  @Test
  fun theKeyLightOrbitsAndTheEnvironmentTurns() {
    val settings = MetalSettings(
      animateLight = true,
      lightAzimuthDegrees = 0f,
      lightElevationDegrees = 0f,
      lightTurnsPerLoop = 1f,
      environmentRotationDegrees = 0f,
    )

    val start = settings.lightPosition(0f)
    val quarter = settings.lightPosition(0.25f)
    val half = settings.lightPosition(0.5f)

    assertNotEquals(start[0], quarter[0], 0.01f)
    assertNotEquals(start[0], half[0], 0.01f)
    assertEquals(0.0f, settings.environmentRotation(0f), 0.0001f)
    assertEquals(Math.PI.toFloat() / 2f, settings.environmentRotation(0.25f), 0.0001f)
  }

  @Test
  fun aHorizontalLoopUsesTheWidthCropAndWrapsX() {
    val plan = videoPlan(
      frameColumns = 8,
      frameRows = 4,
      pixelsPerCell = 4,
      cellSize = 1,
      screens = 2,
      fps = 10,
      pixelsPerFrame = 2,
      outputWidth = 64,
      outputHeight = 32,
    )

    val first = panoramaMetalFrame(plan, 0, MetalSettings())
    val last = panoramaMetalFrame(plan, plan.frameCount - 1, MetalSettings())

    assertTrue(first.wrapX)
    assertFalse(first.wrapY)
    assertEquals(0f, first.uvOffsetX, 0.0001f)
    assertEquals(plan.frameWidth.toFloat() / plan.panoramaWidth, first.uvScaleX, 0.0001f)
    assertEquals(1f, first.uvScaleY, 0.0001f)
    assertTrue(first.uvOffsetX in 0f..1f)
    assertTrue(last.uvOffsetX in 0f..1f)
  }

  @Test
  fun aVerticalLoopUsesTheHeightCropAndWrapsY() {
    val plan = videoPlan(
      frameColumns = 8,
      frameRows = 4,
      pixelsPerCell = 4,
      cellSize = 1,
      screens = 2,
      fps = 10,
      pixelsPerFrame = 2,
      scrollDirection = ScrollDirection.Down,
      outputWidth = 32,
      outputHeight = 64,
    )

    val frame = panoramaMetalFrame(plan, plan.frameCount / 2, MetalSettings())

    assertFalse(frame.wrapX)
    assertTrue(frame.wrapY)
    assertEquals(1f, frame.uvScaleX, 0.0001f)
    assertTrue(frame.uvOffsetY in 0f..1f)
    assertTrue(frame.uvScaleY > 0f)
  }
}
