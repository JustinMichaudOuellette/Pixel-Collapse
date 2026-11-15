package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.video.MetalCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * The camera and wall geometry, without a GPU.
 *
 * The wall is a shallow dish: flat in the middle, curving away towards its edges. These tests pin down the
 * framing (the rim lands on the frame's corners at every slider value), the eye (it never moves), and the
 * foreshortening (equal steps along the plate get *smaller* on screen towards the rim).
 */
class MetalCameraTest {

  @Test
  fun theOrthographicCameraFillsTheFrame() {
    val camera = MetalCamera()
    camera.update(0f, 4f / 3f)

    assertEquals(1f, camera.quadScale, 0.0001f)
    assertEquals(0f, camera.dishDepth, 0.0001f)
    assertEquals(0f, camera.fieldOfViewDegrees, 0.0001f)
    assertPoint(camera, 4f / 3f, 1f, 0f, 1f, 1f)
    assertPoint(camera, -4f / 3f, -1f, 0f, -1f, -1f)
    assertPoint(camera, 0f, 0f, 0f, 0f, 0f)
  }

  @Test
  fun theWallsRimStaysOnTheFrameCorners() {
    val camera = MetalCamera()
    for (aspect in listOf(9f / 16f, 1f, 4f / 3f, 16f / 9f)) {
      for (step in 0..10) {
        camera.update(step / 10f, aspect)

        // The rim, in world units: the plate's own (aspect, 1) half-extents scaled, sitting dishDepth back.
        val scale = camera.quadScale
        val depth = -camera.dishDepth
        assertPoint(camera, aspect * scale, scale, depth, 1f, 1f)
        assertPoint(camera, -aspect * scale, -scale, depth, -1f, -1f)
        assertPoint(camera, aspect * scale, -scale, depth, 1f, -1f)
        assertPoint(camera, 0f, 0f, 0f, 0f, 0f)
      }
    }
  }

  @Test
  fun theEyeNeverMoves() {
    val camera = MetalCamera()
    for (step in 1..10) {
      camera.update(step / 10f, 16f / 9f)
      // A perspective matrix's clip-space w for a point on the wall is the eye depth, never the eye's position.
      assertEquals("the eye moved at step $step", MetalCamera.DISTANCE, camera.viewProjection[15], 1e-5f)
    }
  }

  @Test
  fun equalStepsAlongThePlateShrinkTowardsTheRim() {
    val camera = MetalCamera()
    camera.update(1f, 1f)
    val scale = camera.quadScale
    val half = camera.dishDepth / 2f

    // Screen radius of a point `r` of the way out along the middle row, at that point's own depth.
    fun screen(r: Float): Float {
      val x = r * scale
      val depth = -half * r * r
      return project(camera, x, 0f, depth)[0]
    }

    val inner = screen(0.1f) - screen(0f)
    val middle = screen(0.55f) - screen(0.45f)
    val outer = screen(1f) - screen(0.9f)
    println("perspective steps: inner=$inner middle=$middle outer=$outer")

    // Foreshortening: the same slice of the picture covers less of the screen as it moves towards the rim,
    // monotonically, so equal cells appear smaller the further out they are.
    assertTrue("no foreshortening at the rim ($outer vs $inner)", outer < inner * 0.85f)
    assertTrue("the middle is not between ($inner, $middle, $outer)", middle < inner && middle > outer)
  }

  @Test
  fun theDishAndTheScaleGrowWithTheSlider() {
    val camera = MetalCamera()
    var previousScale = camera.quadScale
    var previousDepth = camera.dishDepth
    for (step in 1..10) {
      camera.update(step / 10f, 1f)
      assertTrue("the scale shrank", camera.quadScale >= previousScale - 0.0001f)
      assertTrue("the dish got shallower", camera.dishDepth >= previousDepth - 0.0001f)
      previousScale = camera.quadScale
      previousDepth = camera.dishDepth
    }
    assertEquals(perspectiveScale(camera), camera.quadScale, 0.001f)
    assertEquals(MetalCamera.MAX_DISH_DEPTH, camera.dishDepth, 0.0001f)
  }

  @Test
  fun halfWayIsAModeratePerspective() {
    val camera = MetalCamera()
    camera.update(0.5f, 1f)

    assertTrue("the lens did not widen", camera.fieldOfViewDegrees > 42f)
    assertTrue("the dish did not form", camera.dishDepth > 0.05f)
    assertTrue("the dish is already a bowl", camera.dishDepth < MetalCamera.MAX_DISH_DEPTH)
    assertTrue("the plate did not grow", camera.quadScale > 1.05f)
  }

  @Test
  fun leavingTheOrthographicCameraIsContinuous() {
    val camera = MetalCamera()
    camera.update(0f, 1.5f)
    val orthographic = project(camera, 1.5f, 1f, 0f)

    camera.update(0.00001f, 1.5f)
    val almost = project(camera, 1.5f * camera.quadScale, camera.quadScale, -camera.dishDepth)

    assertEquals(orthographic[0], almost[0], 0.002f)
    assertEquals(orthographic[1], almost[1], 0.002f)
    assertEquals(1f, camera.quadScale, 0.01f)
    assertEquals(0f, camera.dishDepth, 0.01f)
  }

  @Test
  fun theProjectionIsMirrorSymmetric() {
    val camera = MetalCamera()
    camera.update(0.6f, 1f)

    val left = project(camera, -0.4f * camera.quadScale, 0.7f * camera.quadScale, -camera.dishDepth * 0.4f)
    val right = project(camera, 0.4f * camera.quadScale, -0.7f * camera.quadScale, -camera.dishDepth * 0.4f)

    assertEquals(left[0], -right[0], 0.0001f)
    assertEquals(left[1], -right[1], 0.0001f)
  }

  /** The scale that puts the rim on the frame's corners for the camera's current projection. */
  private fun perspectiveScale(camera: MetalCamera): Float =
    (MetalCamera.DISTANCE + camera.dishDepth) *
      tan(Math.toRadians(camera.fieldOfViewDegrees / 2.0).toFloat())

  private fun assertPoint(
    camera: MetalCamera,
    x: Float,
    y: Float,
    z: Float,
    expectedX: Float,
    expectedY: Float,
  ) {
    val ndc = project(camera, x, y, z)
    assertEquals("x for ($x, $y, $z)", expectedX, ndc[0], 0.0005f)
    assertEquals("y for ($x, $y, $z)", expectedY, ndc[1], 0.0005f)
  }

  /** A point in the wall's space transformed by the camera, in normalised device coordinates. */
  private fun project(camera: MetalCamera, x: Float, y: Float, z: Float): FloatArray {
    val m = camera.viewProjection
    val clipX = m[0] * x + m[4] * y + m[8] * z + m[12]
    val clipY = m[1] * x + m[5] * y + m[9] * z + m[13]
    val clipW = m[3] * x + m[7] * y + m[11] * z + m[15]
    return floatArrayOf(clipX / clipW, clipY / clipW)
  }
}
