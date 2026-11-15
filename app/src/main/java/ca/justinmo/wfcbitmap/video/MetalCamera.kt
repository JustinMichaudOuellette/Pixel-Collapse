package ca.justinmo.wfcbitmap.video

import kotlin.math.atan
import kotlin.math.tan

/**
 * The camera that looks at the wall, and the geometry it projects.
 *
 * The scene is four independent things: this camera, the plate, the texture on it, and the light rig. The
 * camera never moves; the plate is a rectangle that curves gently away from the camera towards its edges.
 *
 * Why the plate is not flat. A rectangle perpendicular to a centred camera has one eye depth for all four of
 * its corners, so a perspective projection can only scale it — the perspective divide has the same `w`
 * everywhere on it, and no vertex can move relative to another. If the picture is to foreshorten, the plate
 * has to have depth: it is built as a mesh whose rim sits [dishDepth] behind its centre, so the surface
 * recedes towards the edges and the outer part of the picture is seen at an angle. Straight lines stay
 * straight across each facet, and the whole thing is symmetric about both centre lines because the depth only
 * depends on the distance from the centre.
 *
 * Framing. The plate's half-height is 1 and its half-width is the picture's aspect ratio, in plate units. The
 * camera sits at [DISTANCE] with the slider picking the vertical field of view, from orthographic at 0 to
 * [MAX_FIELD_OF_VIEW_DEGREES] at 1. The dish's rim — its four corners — sits at eye depth
 * `DISTANCE + dishDepth`, where the frame's half-height is `(DISTANCE + dishDepth) * tan(fov / 2)`. Growing
 * the plate by exactly that factor puts the rim on the frame's corners, which keeps the viewport covered at
 * every slider value. Because the rim is behind the centre, its picture is drawn smaller than the middle's:
 * that difference *is* the foreshortening, and it is deliberately not compensated away.
 *
 * The dish deepens with the slider (`dishDepth ∝ n²`), so at 0 the plate is flat and the orthographic camera
 * shows it undistorted, and the two ends join continuously: at n -> 0 the dish vanishes, the field of view
 * tends to [FIELD_OF_VIEW_AT_FRAME] and the scale tends to 1.
 *
 * The matrices, the scale and the depth are rebuilt only when the slider value or the viewport aspect changes.
 */
internal class MetalCamera {

  /** Column-major 4x4 `projection * view`, ready for `glUniformMatrix4fv(..., false, ...)`. */
  val viewProjection = FloatArray(16)

  /** How far the plate's rim sits behind its centre, in world units. 0 in orthographic mode. */
  var dishDepth = 0f
    private set

  /**
   * The uniform scale applied to the plate so that its rim still lands on the frame's corners under the
   * current projection. This is what keeps the viewport covered; it does not flatten the dish, so the
   * foreshortening survives it.
   */
  var quadScale = 1f
    private set

  /** The vertical field of view of the projection in degrees; 0 in orthographic mode. */
  var fieldOfViewDegrees = 0f
    private set

  private var builtPerspective = Float.NaN
  private var builtAspect = Float.NaN
  private val projection = FloatArray(16)
  private val view = FloatArray(16)

  /**
   * Rebuilds the matrices and the plate's framing for [perspective] (0..1, already normalised by the caller)
   * and [viewportAspect]. Returns immediately when neither changed since the last call.
   */
  fun update(perspective: Float, viewportAspect: Float) {
    val n = if (perspective.isFinite()) perspective.coerceIn(0f, 1f) else 0f
    val aspect = if (viewportAspect.isFinite() && viewportAspect > 0.001f) viewportAspect else 1f
    if (n == builtPerspective && aspect == builtAspect) return
    builtPerspective = n
    builtAspect = aspect

    if (n <= 0f) {
      // Orthographic: half-height 1 and half-width = viewport aspect, which maps the plate's own
      // (aspect, 1) half-extents onto the whole frame. Depth does not matter to a parallel projection, so
      // the dish is flat here and the picture is undistorted.
      orthographic(projection, aspect)
      fieldOfViewDegrees = 0f
      dishDepth = 0f
      quadScale = 1f
    } else {
      // Perspective: the lens widens with the slider while the camera and the plate stay where they are.
      // Squared so the low end of the slider is gentle and the top end is unmistakable.
      val fovY = FIELD_OF_VIEW_AT_FRAME +
        n * n * ((MAX_FIELD_OF_VIEW_DEGREES * DEGREES_TO_RADIANS) - FIELD_OF_VIEW_AT_FRAME)
      perspective(projection, fovY, aspect)
      fieldOfViewDegrees = Math.toDegrees(fovY.toDouble()).toFloat()

      // The wall curves away from the camera towards its edges: the rim ends up this far behind the centre.
      dishDepth = MAX_DISH_DEPTH * n * n

      // The rim must land on the frame's corners. At the rim's eye depth the frame's half-height is
      // (DISTANCE + dishDepth) * tan(fov / 2), so that is the plate's half-height after scaling.
      quadScale = (DISTANCE + dishDepth) * tan(fovY * 0.5f)
    }

    // The eye never moves: it is at (0, 0, DISTANCE) looking down -z at every slider value, so the view is
    // the same pure translation whatever the projection is.
    view.fill(0f)
    view[0] = 1f
    view[5] = 1f
    view[10] = 1f
    view[14] = -DISTANCE
    view[15] = 1f

    multiply(viewProjection, projection, view)
  }

  /** Standard pinhole frustum, symmetric about both axes, column-major. */
  private fun perspective(out: FloatArray, fovY: Float, aspect: Float) {
    out.fill(0f)
    val f = 1f / tan(fovY * 0.5f)
    out[0] = f / aspect
    out[5] = f
    out[10] = (FAR + NEAR) / (NEAR - FAR)
    // Column-major indices: element (row, column) lives at column * 4 + row.
    out[14] = 2f * FAR * NEAR / (NEAR - FAR) // (2, 3), the depth offset
    out[11] = -1f // (3, 2): clip-space w becomes the eye-space depth, so far things get small.
    // out[15] stays 0.
  }

  /** Parallel projection over the frame the viewport covers, column-major. */
  private fun orthographic(out: FloatArray, aspect: Float) {
    out.fill(0f)
    out[0] = 1f / aspect
    out[5] = 1f
    out[10] = -2f / (FAR - NEAR)
    out[14] = -(FAR + NEAR) / (FAR - NEAR)
    out[15] = 1f
  }

  /** `out = a * b`, column-major. */
  private fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
    for (column in 0 until 4) {
      for (row in 0 until 4) {
        var sum = 0f
        for (k in 0 until 4) sum += a[k * 4 + row] * b[column * 4 + k]
        out[column * 4 + row] = sum
      }
    }
  }

  internal companion object {
    /** Camera distance from the wall, in plate units. Constant: the eye never moves. */
    const val DISTANCE = 2.6f

    /** The vertical field of view at which a half-height-1 plate exactly fills the frame. */
    val FIELD_OF_VIEW_AT_FRAME: Float = 2f * atan(1f / DISTANCE)

    /** The widest lens, at the top of the slider. 100 degrees is a strong, unmistakable perspective. */
    const val MAX_FIELD_OF_VIEW_DEGREES = 100f

    /**
     * How far the rim sits behind the centre at the top of the slider, in world units. Around a third of the
     * camera distance: the outer part of the picture is then clearly smaller than the middle, and the rim
     * leans away by about 39 degrees — far enough to read as a wall curving away, not so far that the last
     * few pixels of the picture are squeezed out of existence.
     */
    const val MAX_DISH_DEPTH = 0.8f

    private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

    const val NEAR = 0.05f
    const val FAR = 100f
  }
}
