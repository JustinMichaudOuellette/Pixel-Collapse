package ca.justinmo.wfcbitmap.video

import ca.justinmo.wfcbitmap.FeatureFlags
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The slider's upper end; values above the old 1.0 exaggerate the camera projection. */
internal const val MAX_CAMERA_PERSPECTIVE = 3f

/**
 * The look of the embossed metal, independent of any particular frame.
 *
 * These are artist controls, not pixels: the renderer turns them into a physically based material
 * (GGX direct light + prefiltered studio environment) every frame. The defaults are a polished,
 * slightly warm silver because that is the surface that shows an embossed height field most clearly:
 * a mirror has no diffuse wash to hide the bevels, and the moving studio light draws a hard, travelling
 * highlight along every raised edge.
 *
 * The input drawing supplies three things to the material: the height field (its luminance), the fine
 * colour that tints the reflection (scaled by [textureTint]), and the silhouette of every raised cell.
 * The metal itself is described by [baseColor], [metallic] and [roughness].
 */
data class MetalSettings(
  /** Which preset the controls were last set from; only used to light the chosen chip. */
  val finish: MetalFinish = MetalFinish.Chrome,
  /** How far the height field is pushed into the normal, in "plate bevel" units. 0 is flat. */
  val emboss: Float = 1.3f,
  /** 1 is a conductor (no diffuse, tinted reflections); 0 is a dielectric coat over the drawing. */
  val metallic: Float = 1f,
  /** GGX roughness. 0.06 is a mirror, 0.5 is a satin/brushed surface. */
  val roughness: Float = 0.06f,
  /** Normal-incidence reflectance of the metal, as an sRGB colour. */
  val baseColor: Int = 0xFFF1F4F8.toInt(),
  /** How much of the drawing's own colour replaces the metal's base colour. 0 keeps pure metal. */
  val textureTint: Float = 0.78f,
  /** Strength of the moving studio key light. */
  val lightIntensity: Float = 8.0f,
  /** Colour of the moving key light, as an sRGB colour. */
  val lightColor: Int = 0xFFFFF1D8.toInt(),
  /** Where the key sits when it is not animated, in degrees around the surface. */
  val lightAzimuthDegrees: Float = 28f,
  /** Height of the manual key above the surface, in degrees. */
  val lightElevationDegrees: Float = 38f,
  /** Whether the key orbits; when false it stays at [lightAzimuthDegrees]. */
  val animateLight: Boolean = true,
  /** Orbits per loop. One makes the moving highlight return exactly where it started. */
  val lightTurnsPerLoop: Float = 1f,
  /** Base orientation of the studio environment, in degrees around the surface normal. */
  val environmentRotationDegrees: Float = 0f,
  /** How bright the reflected studio is. */
  val environmentIntensity: Float = 1f,
  /** Optional parallax offset of the height field, in surface units. Kept low to avoid swimming. */
  val parallax: Float = 0f,
  /** Directional roughness for the brushed finish: 0 is isotropic, 1 is strongly streaked. */
  val anisotropy: Float = 0f,
  /** Direction of the brush/streak pattern, in degrees in the texture plane. */
  val brushAngleDegrees: Float = 0f,
  /** Exposure applied before the ACES curve. */
  val exposure: Float = 1.08f,
  /**
   * The camera's projection, from orthographic to a wide lens. 0 is an orthographic camera and the plate
   * fills the frame; higher values widen the perspective, so the plate recedes symmetrically towards the
   * centre. Only the projection changes: the camera, the plate, its texture coordinates and the light rig
   * all stay exactly where they are.
   */
  val cameraPerspective: Float = 0f,
  /** The moving key light. Off leaves only the reflected studio. */
  val keyLightEnabled: Boolean = true,
  /** The dark-to-bright studio backdrop. */
  val skyEnabled: Boolean = true,
  /** The floor half of the studio backdrop. */
  val floorEnabled: Boolean = true,
  /** The bright haze where the floor meets the sky. */
  val horizonEnabled: Boolean = true,
  /** The dim bounce that stops downward bevels going to pure black. */
  val floorBounceEnabled: Boolean = true,
  /** The broad soft-box straight ahead. */
  val frontPanelEnabled: Boolean = true,
  /** The cool panel on one side. */
  val bluePanelEnabled: Boolean = true,
  /** The warm panel on the other side. */
  val amberPanelEnabled: Boolean = true,
  /** The high key soft-box used by the moving-light look. */
  val keySoftboxEnabled: Boolean = true,
  /** The broad overhead panel. */
  val overheadPanelEnabled: Boolean = true,
  /** Small highlight 1, the white spot on one side. */
  val smallSpot1Enabled: Boolean = FeatureFlags.SPOT_LIGHTS_ENABLED,
  /** Small highlight 2, the cool spot beside it. */
  val smallSpot2Enabled: Boolean = FeatureFlags.SPOT_LIGHTS_ENABLED,
  /** Small highlight 3, the warm spot further around. */
  val smallSpot3Enabled: Boolean = FeatureFlags.SPOT_LIGHTS_ENABLED,
) {
  /** The same settings with the numeric controls brought into their documented ranges. */
  fun sanitised(): MetalSettings = copy(
    emboss = emboss.coerceIn(0f, 2.5f),
    metallic = metallic.coerceIn(0f, 1f),
    roughness = roughness.coerceIn(0.03f, 1f),
    textureTint = textureTint.coerceIn(0f, 1f),
    lightIntensity = lightIntensity.coerceIn(0f, 20f),
    lightAzimuthDegrees = lightAzimuthDegrees.mod(360f),
    lightElevationDegrees = lightElevationDegrees.coerceIn(-30f, 90f),
    lightTurnsPerLoop = lightTurnsPerLoop.coerceIn(0f, 4f),
    environmentRotationDegrees = environmentRotationDegrees.mod(360f),
    environmentIntensity = environmentIntensity.coerceIn(0f, 3f),
    parallax = parallax.coerceIn(0f, 0.35f),
    anisotropy = anisotropy.coerceIn(0f, 1f),
    brushAngleDegrees = brushAngleDegrees.mod(360f),
    exposure = exposure.coerceIn(0.2f, 4f),
    cameraPerspective = cameraPerspective.coerceIn(0f, MAX_CAMERA_PERSPECTIVE),
    smallSpot1Enabled = smallSpot1Enabled && FeatureFlags.SPOT_LIGHTS_ENABLED,
    smallSpot2Enabled = smallSpot2Enabled && FeatureFlags.SPOT_LIGHTS_ENABLED,
    smallSpot3Enabled = smallSpot3Enabled && FeatureFlags.SPOT_LIGHTS_ENABLED,
  )

  /** The same settings, with a preset's metal look applied. Other controls are left alone. */
  fun withFinish(preset: MetalFinish): MetalSettings = copy(
    finish = preset,
    baseColor = preset.baseColor,
    roughness = preset.roughness,
    metallic = preset.metallic,
    anisotropy = preset.anisotropy,
    textureTint = preset.textureTint,
  )

  /**
   * The key light's position on the studio orbit.
   *
   * [turns] is measured in whole orbits: 0 is [lightAzimuthDegrees], 0.25 is a quarter turn later. The
   * orbit is an ellipse in front of the plate rather than a full sphere — a key that went behind the
   * plate would leave the relief unlit for half of every loop — but it still crosses from one side to
   * the other and rises and falls over the surface.
   */
  fun lightPosition(turns: Float): FloatArray {
    val azimuth = Math.toRadians(lightAzimuthDegrees.toDouble()) + 2.0 * PI * turns
    val elevation = lightElevationDegrees / 90f
    val x = (cos(azimuth) * 2.0).toFloat()
    val y = (sin(azimuth) * 1.15).toFloat() + elevation * 1.35f
    return floatArrayOf(x, y, 1.75f)
  }

  /** Orientation of the reflected studio, in radians, for a loop that has advanced [turns]. */
  fun environmentRotation(turns: Float): Float =
    Math.toRadians(environmentRotationDegrees.toDouble()).toFloat() +
      turns * 2f * PI.toFloat()

  /** The linear RGB reflectance of [baseColor], used as F0 by the shader. */
  fun baseColorLinear(): FloatArray = srgbColorToLinear(baseColor)

  /** The linear RGB of [lightColor], used as the key's radiance. */
  fun lightColorLinear(): FloatArray = srgbColorToLinear(lightColor)
}

/** A metal preset: a base reflectance, a roughness and, for brushed steel, a streak direction. */
enum class MetalFinish(
  val label: String,
  val baseColor: Int,
  val roughness: Float,
  val metallic: Float,
  val anisotropy: Float,
  val textureTint: Float,
) {
  Chrome(
    label = "Chrome",
    baseColor = 0xFFF1F4F8.toInt(),
    roughness = 0.06f,
    metallic = 1f,
    anisotropy = 0f,
    textureTint = 0.78f,
  ),
  Silver(
    label = "Silver",
    baseColor = 0xFFD7DCE3.toInt(),
    roughness = 0.14f,
    metallic = 1f,
    anisotropy = 0f,
    textureTint = 0.75f,
  ),
  Gold(
    label = "Gold",
    baseColor = 0xFFFFC65C.toInt(),
    roughness = 0.18f,
    metallic = 1f,
    anisotropy = 0f,
    textureTint = 0.30f,
  ),
  Copper(
    label = "Copper",
    baseColor = 0xFFE08654.toInt(),
    roughness = 0.24f,
    metallic = 1f,
    anisotropy = 0f,
    textureTint = 0.32f,
  ),
  Brushed(
    label = "Brushed",
    baseColor = 0xFFC9CED6.toInt(),
    roughness = 0.34f,
    metallic = 1f,
    anisotropy = 0.85f,
    textureTint = 0.40f,
  ),
}

/** sRGB 0..255 component to linear light; the shader samples albedo already decoded by the hardware. */
internal fun srgbToLinear(channel: Int): Float {
  val c = (channel and 0xFF) / 255f
  return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).let { it * it }
}

/** An ARGB colour as linear RGB. */
internal fun srgbColorToLinear(argb: Int): FloatArray = floatArrayOf(
  srgbToLinear((argb ushr 16) and 0xFF),
  srgbToLinear((argb ushr 8) and 0xFF),
  srgbToLinear(argb and 0xFF),
)

/** `Float.mod` is not on every Kotlin target this module uses; a positive remainder is all we need. */
private fun Float.mod(divisor: Float): Float {
  val remainder = this % divisor
  return if (remainder < 0f) remainder + divisor else remainder
}
