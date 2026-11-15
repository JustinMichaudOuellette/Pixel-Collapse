package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.video.MetalCamera
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import ca.justinmo.wfcbitmap.video.MetalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Evidence for the perspective slider: what the projection matrix actually does to the quad.
 *
 * The debugging checklist for this feature is: find the matrix, confirm the slider reaches it, confirm the
 * matrix divides by a depth in clip-space w, and confirm the projected vertex positions move. This renders a
 * checkerboard — which makes any geometric distortion obvious — at five slider positions, twice: once with
 * the quad at its natural size (the raw projection) and once with the quad grown to keep viewport coverage.
 *
 * The logs printed here are the numbers behind the design decision: for a quad perpendicular to the camera,
 * every one of its vertices has the same eye depth, so every vertex has the same clip-space w, so the
 * perspective divide scales the whole quad by one factor and no vertex can move relative to another.
 */
@RunWith(AndroidJUnit4::class)
class PerspectiveEvidenceTest {

  private val size = 256
  private val cell = 32

  @Test
  fun theProjectionMatrixMovesTheQuadAndDividesByDepth() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "metal-tests")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw AssertionError("Could not create ${directory.absolutePath}")
    }

    val renderer = MetalOffscreenRenderer()
    assertTrue("GLES renderer did not open: ${renderer.failure}", renderer.open())
    try {
      val checker = checkerboard()
      val out = IntArray(size * size)
      val settings = MetalSettings(animateLight = false)
      val camera = MetalCamera()

      for (percent in listOf(0, 25, 50, 75, 100)) {
        val n = percent / 100f
        camera.update(n, 1f)
        val m = camera.viewProjection

        // The four corners of the quad as the vertex shader sees them: (±1·scale, ±1·scale, 0, 1).
        // The four corners of the wall as the vertex shader sees them: the plate's parameter corner scaled,
        // with the dish's rim depth on z.
        val scale = camera.quadScale
        var w = Float.NaN
        for (sx in listOf(-1f, 1f)) {
          for (sy in listOf(-1f, 1f)) {
            val x = sx * scale
            val y = sy * scale
            val z = -camera.dishDepth
            val clipX = m[0] * x + m[4] * y + m[8] * z + m[12]
            val clipY = m[1] * x + m[5] * y + m[9] * z + m[13]
            val clipW = m[3] * x + m[7] * y + m[11] * z + m[15]
            if (w.isNaN()) w = clipW
            Log.i(
              TAG,
              "P ${percent}% corner ($sx,$sy) -> ndc (${clipX / clipW}, ${clipY / clipW}) " +
                "clip.w=$clipW",
            )
            assertEquals("all four corners share one eye depth", w, clipW, 1e-4f)
          }
        }
        Log.i(
          TAG,
          "P ${percent}%: fov=${camera.fieldOfViewDegrees} dish=${camera.dishDepth} " +
            "quadScale=${camera.quadScale} m0=${m[0]} m5=${m[5]} m11=${m[11]} m15=${m[15]}",
        )

        // 1. The raw projection: the quad at its natural size, nothing compensating. This is the projection's
        // own effect, and it must move the projected corners.
        val raw = render(renderer, checker, settings, s = 1f / camera.quadScale, camera, out)
        save(directory, "P-raw-$percent", raw)

        // 2. The same frame with the quad grown so it keeps covering the viewport.
        val covered = render(renderer, checker, settings, s = 1f, camera, out)
        save(directory, "P-cover-$percent", covered)

        // The quad's own scale must grow so that its rim stays on the frame's corners; the dish is not
        // compensated away, so the picture still foreshortens.
        assertEquals(
          "the rim must reach the frame",
          (MetalCamera.DISTANCE + camera.dishDepth) *
            kotlin.math.tan(Math.toRadians(camera.fieldOfViewDegrees / 2.0).toFloat()),
          camera.quadScale,
          0.01f,
        )
      }
    } finally {
      renderer.close()
    }
  }

  /**
   * Renders the checkerboard with the quad scaled by `previewScale`, which multiplies the model scale in the
   * renderer. `1 / quadScale` cancels the coverage growth and shows the projection alone.
   */
  private fun render(
    renderer: MetalOffscreenRenderer,
    image: IntArray,
    settings: MetalSettings,
    s: Float,
    camera: MetalCamera,
    out: IntArray,
  ): IntArray {
    renderer.upload(image, size, size)
    val frame = MetalFrame(
      width = size,
      height = size,
      viewportAspect = 1f,
      imageAspect = 1f,
      stepTexels = cell.toFloat(),
      previewScale = s,
      settings = settings,
    )
    return renderer.render(frame, out)?.copyOf() ?: throw AssertionError("no frame")
  }

  private fun checkerboard(): IntArray = IntArray(size * size) { index ->
    val x = index % size / cell
    val y = index / size / cell
    if ((x + y) % 2 == 0) 0xFFF2F4F8.toInt() else 0xFF101014.toInt()
  }

  private fun save(directory: File, name: String, pixels: IntArray) {
    val file = File(directory, "$name.png")
    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    Log.i(TAG, "saved ${file.absolutePath}")
  }

  private companion object {
    const val TAG = "PerspectiveEvidence"
  }
}
