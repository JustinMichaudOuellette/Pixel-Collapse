package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.video.MetalShade.MIN_HEIGHT
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The CPU fallback for the metal look, used only when the device cannot create a GLES 3.0 context.
 *
 * The real material is [PreviewGpu]/[MetalShader]: Cook-Torrance GGX, a moving key, a prefiltered studio
 * environment and a BRDF LUT. This routine keeps the old, approximate shading so an export on a device
 * without GLES 3.0 still produces a metal-ish file instead of failing; it does not read [MetalSettings],
 * and its key and fill are fixed. It is deliberately not the path the app uses on modern hardware.
 *
 * The drawing's own pixels still become a height field, its gradient still becomes the surface normal,
 * and the metal is still tinted by the drawing's colour -- the parts of the look that do not need a GPU.
 * The routine works at whatever resolution the caller hands it, never touches the input array, and
 * allocates only its output.
 */
internal object MetalShade {

  /** Height of the darkest colour, so a black cell is still a surface rather than a hole. */
  private const val MIN_HEIGHT = 0.06f

  /** Height range above [MIN_HEIGHT]; the lightest colour sits at the top of it. */
  private const val HEIGHT_SPAN = 0.94f

  /**
   * How steep a bevel a step in the height field makes.
   *
   * The sampling radius follows the cell size, but the height difference across that span is what the
   * gradient sees either way, so the slope is scaled the same at every cell size: a bigger cell gets a
   * wider bevel at the same angle instead of a flatter one.
   */
  private const val RELIEF = 1.15f

  /** Exposure of the environment; over one keeps the metal bright enough to read as polished. */
  private const val ENV_GAIN = 1.55f

  /*
   * The reflected environment: a bright overhead panel, a medium horizon and a dark floor. The horizon
   * is what a flat plate reflects -- a mirror held upright shows the horizon, not the sky -- so plates
   * read as a mid-tone metal and their bevels, which turn towards the sky or the floor, are what carry
   * the bright and dark edges.
   */
  private const val ZENITH_R = 0.98f
  private const val ZENITH_G = 1.00f
  private const val ZENITH_B = 1.08f
  private const val HORIZON_R = 0.68f
  private const val HORIZON_G = 0.70f
  private const val HORIZON_B = 0.76f
  private const val GROUND_R = 0.05f
  private const val GROUND_G = 0.05f
  private const val GROUND_B = 0.07f

  private const val INV_255 = 1f / 255f

  /**
   * The key area light, low and to the left.
   *
   * Low matters: a flat plate reflects the horizon, so a light high overhead is only seen by plates that
   * are almost flat too, which leaves the bevels dark. Placed near the horizon it is exactly the bevels
   * -- whose reflections turn sideways -- that catch it, which is the bright edge the eye reads as metal.
   */
  private val KEY = unit(-0.82f, 0.35f, 0.45f)
  private val KX = KEY[0]
  private val KY = KEY[1]
  private val KZ = KEY[2]

  /** The warm fill light, low and to the right, so the opposite bevel has a second colour to catch. */
  private val FILL = unit(0.80f, 0.05f, 0.60f)
  private val FX = FILL[0]
  private val FY = FILL[1]
  private val FZ = FILL[2]

  /**
   * The shaded copy of [pixels].
   *
   * @param width and [height] the frame's size; [pixels] holds exactly that many ARGB entries.
   * @param cellPixels how many pixels one solved cell is drawn with, which sets how far apart the
   *   height samples are: one cell is the smallest feature the relief can resolve, so sampling across
   *   it is what keeps the shading stable from frame to frame.
   */
  fun paint(pixels: IntArray, width: Int, height: Int, cellPixels: Int): IntArray {
    val count = width * height
    if (width <= 0 || height <= 0 || pixels.size < count) return pixels

    val heights = FloatArray(count)
    for (i in 0 until count) {
      val color = pixels[i]
      val red = ((color ushr 16) and 0xFF) * INV_255
      val green = ((color ushr 8) and 0xFF) * INV_255
      val blue = (color and 0xFF) * INV_255
      // Squaring the channels first makes the drawing's dark colours clearly sunk and its light ones
      // clearly raised, instead of shading every colour into the same mid-grey band.
      val luminance = 0.2126f * red * red + 0.7152f * green * green + 0.0722f * blue * blue
      heights[i] = MIN_HEIGHT + HEIGHT_SPAN * luminance
    }

    val radius = (cellPixels - 1).coerceIn(1, 4)
    val slopeScale = RELIEF
    val shaded = IntArray(count)
    for (y in 0 until height) {
      val row = y * width
      val upRow = wrap(y - radius, height) * width
      val downRow = wrap(y + radius, height) * width
      for (x in 0 until width) {
        val left = heights[row + wrap(x - radius, width)]
        val right = heights[row + wrap(x + radius, width)]
        val above = heights[upRow + x]
        val below = heights[downRow + x]
        val here = heights[row + x]

        val color = pixels[row + x]
        val red = ((color ushr 16) and 0xFF) * INV_255
        val green = ((color ushr 8) and 0xFF) * INV_255
        val blue = (color and 0xFF) * INV_255
        val albedoR = red * red
        val albedoG = green * green
        val albedoB = blue * blue

        // The normal is the upside-down gradient of the relief. The small colour term tilts it a
        // little as well, so two flat plates of different colours do not reflect exactly the same
        // patch of sky and the metal keeps the drawing's palette legible.
        val nx = -(right - left) * slopeScale + (albedoR - albedoB) * 0.10f
        val ny = -(below - above) * slopeScale + (albedoG - albedoR) * 0.06f
        val slope = sqrt(nx * nx + ny * ny)
        val invLength = 1f / sqrt(nx * nx + ny * ny + 1f)
        val normalX = nx * invLength
        val normalY = ny * invLength
        val normalZ = invLength

        // What a viewer straight on sees reflected: V is +Z, so R = 2(N·V)N - V.
        val reflectX = 2f * normalZ * normalX
        val reflectY = 2f * normalZ * normalY
        val reflectZ = 2f * normalZ * normalZ - 1f

        val up = reflectY.coerceIn(-1f, 1f)
        val envR: Float
        val envG: Float
        val envB: Float
        if (up >= 0f) {
          envR = HORIZON_R + (ZENITH_R - HORIZON_R) * up
          envG = HORIZON_G + (ZENITH_G - HORIZON_G) * up
          envB = HORIZON_B + (ZENITH_B - HORIZON_B) * up
        } else {
          val down = -up
          envR = HORIZON_R + (GROUND_R - HORIZON_R) * down
          envG = HORIZON_G + (GROUND_G - HORIZON_G) * down
          envB = HORIZON_B + (GROUND_B - HORIZON_B) * down
        }

        val key = (reflectX * KX + reflectY * KY + reflectZ * KZ).coerceAtLeast(0f)
        val key2 = key * key
        val key4 = key2 * key2
        val key8 = key4 * key4
        val keyLight = key2 * 0.22f + key8 * 2.40f

        val fill = (reflectX * FX + reflectY * FY + reflectZ * FZ).coerceAtLeast(0f)
        val fill2 = fill * fill
        val fill4 = fill2 * fill2
        val fill8 = fill4 * fill4
        val fillLight = fill8 * 1.40f

        // A ring of light around the horizon. A bevel steep enough to turn its reflection sideways is
        // inside that ring whichever way it faces, so every drawn edge catches a rim instead of only
        // the edges that happen to line up with the two fixed area lights.
        val grazingRing = (1f - reflectZ).coerceIn(0f, 1f)
        val ring2 = grazingRing * grazingRing
        val ring4 = ring2 * ring2
        val horizonBand = 1f - abs(reflectY).coerceIn(0f, 1f)
        val ringLight = ring4 * horizonBand * 0.85f

        // Dark cells sit in the relief's valleys, so they also gather a little less of the sky.
        val heightFraction = (here - MIN_HEIGHT) / HEIGHT_SPAN
        val occlusion = 0.55f + 0.45f * heightFraction

        // Metallic Fresnel: the tint is the albedo at normal incidence and washes out to white at a
        // grazing angle. The floor means even a black cell still catches some light.
        val grazing = 1f - normalZ
        val grazing2 = grazing * grazing
        val grazing5 = grazing2 * grazing2 * grazing
        val baseR = albedoR * 0.85f + 0.06f
        val baseG = albedoG * 0.85f + 0.06f
        val baseB = albedoB * 0.85f + 0.06f
        val fresnelR = baseR + (1f - baseR) * grazing5
        val fresnelG = baseG + (1f - baseG) * grazing5
        val fresnelB = baseB + (1f - baseB) * grazing5

        // A metal has no diffuse colour, but a little direct light keeps the drawing readable when
        // the reflected sky happens to be dark at that angle.
        val nDotL = normalX * KX + normalY * KY + normalZ * KZ
        val diffuse = 0.20f * nDotL.coerceAtLeast(0f)

        val envScale = ENV_GAIN * occlusion
        // Every bevel catches some light, whatever direction it faces: without this the rim would come
        // and go with the shape's edges, and the metal would only sparkle where an edge happened to line
        // up with one of the lights.
        val bevel = (slope * 0.85f).coerceIn(0f, 1f)
        val sheen = bevel * bevel * 0.55f
        var outR = (envR * envScale + keyLight + fillLight + ringLight * 0.95f) * fresnelR +
          diffuse * albedoR + sheen * (0.35f + 0.65f * fresnelR) * 0.90f
        var outG = (envG * envScale + keyLight + fillLight * 0.78f + ringLight * 0.98f) * fresnelG +
          diffuse * albedoG + sheen * (0.35f + 0.65f * fresnelG) * 0.95f
        var outB = (envB * envScale + keyLight + fillLight * 0.52f + ringLight) * fresnelB +
          diffuse * albedoB + sheen * (0.35f + 0.65f * fresnelB)

        // Reinhard-ish knee then a square-root gamma, so the bright reflections roll off instead of
        // clipping into flat white patches.
        outR /= 1f + outR * 0.7f
        outG /= 1f + outG * 0.7f
        outB /= 1f + outB * 0.7f
        val redOut = (sqrt(outR) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val greenOut = (sqrt(outG) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val blueOut = (sqrt(outB) * 255f + 0.5f).toInt().coerceIn(0, 255)
        shaded[row + x] = (0xFF shl 24) or (redOut shl 16) or (greenOut shl 8) or blueOut
      }
    }
    return shaded
  }
}

/** A unit-length direction, for the two area lights. */
private fun unit(x: Float, y: Float, z: Float): FloatArray {
  val length = sqrt(x * x + y * y + z * z)
  return floatArrayOf(x / length, y / length, z / length)
}

/** [value] wrapped into `0 until limit`, useful because the panorama is a loop. */
private fun wrap(value: Int, limit: Int): Int {
  val remainder = value % limit
  return if (remainder < 0) remainder + limit else remainder
}
