package ca.justinmo.wfcbitmap.storage

import android.content.Context
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.OutputSettings
import ca.justinmo.wfcbitmap.output.PixelArtScaler
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.video.MAX_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MAX_LOOP_SCREENS
import ca.justinmo.wfcbitmap.video.MAX_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.MIN_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MIN_LOOP_SCREENS
import ca.justinmo.wfcbitmap.video.MIN_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.MetalFinish
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.ScrollDirection
import ca.justinmo.wfcbitmap.video.SolveAnimation
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoLook
import ca.justinmo.wfcbitmap.video.VideoMode

/** The range offered for the tool and colour button size, in dp. */
const val MIN_TOOL_BUTTON_SIZE = 38
const val MAX_TOOL_BUTTON_SIZE = 44

/**
 * The editor settings that outlive a drawing, kept in one small SharedPreferences file.
 *
 * These are choices about how the editor behaves -- which symmetries and edge rule the picture is
 * generated with, the shape and sizes the picture is made at, and whether a palette swap recolours the
 * drawing -- not parts of the drawing itself. Remembering them here means they come back even when the
 * saved session cannot be read, and that changing one is durable as soon as it is tapped.
 *
 * Every getter takes the value to use when the preference has never been written, so the caller can
 * seed a first launch from the restored session and still give the app its documented defaults.
 */
class EditorPreferences(context: Context) {

  private val preferences = context.applicationContext
    .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

  init {
    /*
     * Migrations, run once per installed app:
     *
     * - The preview default used to be the largest size, and the app wrote it on first run. Moving the
     *   default to the smallest one means that a preference written by the old app would otherwise keep
     *   opening on the big preview: migrate that one old default once, and never touch the choice again.
     * - The three symmetries became the default in schema 3: an install coming from an older version takes
     *   them once, so the new default also reaches a device that already had the old false values written.
     */
    migrateDefaults()
  }

  private fun migrateDefaults() {
    val previousVersion = preferences.getInt(KEY_SCHEMA_VERSION, 1)
    if (previousVersion >= SCHEMA_VERSION) return
    val editor = preferences.edit().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
    if (preferences.getString(KEY_OUTPUT_PREVIEW, null) == PreviewScale.Two.name) {
      editor.putString(KEY_OUTPUT_PREVIEW, PreviewScale.Half.name)
    }
    if (previousVersion < 3) {
      editor.putBoolean(KEY_MIRROR_HORIZONTAL, true)
      editor.putBoolean(KEY_MIRROR_VERTICAL, true)
      editor.putBoolean(KEY_QUARTER_TURN, true)
    }
    editor.apply()
  }

  fun wrapEdges(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_WRAP_EDGES, fallback)

  fun setWrapEdges(value: Boolean) {
    preferences.edit().putBoolean(KEY_WRAP_EDGES, value).apply()
  }

  fun mirrorHorizontal(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_MIRROR_HORIZONTAL, fallback)

  fun setMirrorHorizontal(value: Boolean) {
    preferences.edit().putBoolean(KEY_MIRROR_HORIZONTAL, value).apply()
  }

  fun mirrorVertical(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_MIRROR_VERTICAL, fallback)

  fun setMirrorVertical(value: Boolean) {
    preferences.edit().putBoolean(KEY_MIRROR_VERTICAL, value).apply()
  }

  fun quarterTurn(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_QUARTER_TURN, fallback)

  fun setQuarterTurn(value: Boolean) {
    preferences.edit().putBoolean(KEY_QUARTER_TURN, value).apply()
  }

  fun autoMigrateColors(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_AUTO_MIGRATE_COLORS, fallback)

  fun setAutoMigrateColors(value: Boolean) {
    preferences.edit().putBoolean(KEY_AUTO_MIGRATE_COLORS, value).apply()
  }

  fun videoScreens(fallback: Int): Int =
    preferences.getInt(KEY_VIDEO_SCREENS, fallback).coerceIn(MIN_LOOP_SCREENS, MAX_LOOP_SCREENS)

  fun setVideoScreens(value: Int) {
    preferences.edit().putInt(KEY_VIDEO_SCREENS, value).apply()
  }

  fun videoFps(fallback: Int): Int =
    preferences.getInt(KEY_VIDEO_FPS, fallback).coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS)

  fun setVideoFps(value: Int) {
    preferences.edit().putInt(KEY_VIDEO_FPS, value).apply()
  }

  fun videoPixelsPerFrame(fallback: Int): Int =
    preferences.getInt(KEY_VIDEO_PIXELS_PER_FRAME, fallback).coerceIn(1, 16)

  fun setVideoPixelsPerFrame(value: Int) {
    preferences.edit().putInt(KEY_VIDEO_PIXELS_PER_FRAME, value).apply()
  }

  fun videoFormat(fallback: VideoFormat): VideoFormat =
    enumByName(preferences.getString(KEY_VIDEO_FORMAT, null), VideoFormat.entries)
      ?: fallback

  fun setVideoFormat(value: VideoFormat) {
    preferences.edit().putString(KEY_VIDEO_FORMAT, value.name).apply()
  }

  fun videoMode(fallback: VideoMode): VideoMode =
    enumByName(preferences.getString(KEY_VIDEO_MODE, null), VideoMode.entries)
      ?: fallback

  fun setVideoMode(value: VideoMode) {
    preferences.edit().putString(KEY_VIDEO_MODE, value.name).apply()
  }

  fun scrollDirection(fallback: ScrollDirection): ScrollDirection =
    enumByName(preferences.getString(KEY_SCROLL_DIRECTION, null), ScrollDirection.entries)
      ?: fallback

  fun setScrollDirection(value: ScrollDirection) {
    preferences.edit().putString(KEY_SCROLL_DIRECTION, value.name).apply()
  }

  fun videoLook(fallback: VideoLook): VideoLook =
    enumByName(preferences.getString(KEY_VIDEO_LOOK, null), VideoLook.entries)
      ?: fallback

  fun setVideoLook(value: VideoLook) {
    preferences.edit().putString(KEY_VIDEO_LOOK, value.name).apply()
  }

  /**
   * The metal controls, field by field.
   *
   * Each value falls back independently, so a preference file written by an older build (which knew
   * fewer controls) still opens with the documented defaults for the rest.
   */
  fun metalSettings(fallback: MetalSettings = MetalSettings()): MetalSettings {
    val d = fallback
    return MetalSettings(
      finish = enumByName(preferences.getString(KEY_METAL_FINISH, null), MetalFinish.entries)
        ?: d.finish,
      emboss = preferences.getFloat(KEY_METAL_EMBOSS, d.emboss),
      metallic = preferences.getFloat(KEY_METAL_METALLIC, d.metallic),
      roughness = preferences.getFloat(KEY_METAL_ROUGHNESS, d.roughness),
      baseColor = preferences.getInt(KEY_METAL_BASE_COLOR, d.baseColor),
      textureTint = preferences.getFloat(KEY_METAL_TEXTURE_TINT, d.textureTint),
      lightIntensity = preferences.getFloat(KEY_METAL_LIGHT_INTENSITY, d.lightIntensity),
      lightColor = preferences.getInt(KEY_METAL_LIGHT_COLOR, d.lightColor),
      lightAzimuthDegrees = preferences.getFloat(KEY_METAL_LIGHT_AZIMUTH, d.lightAzimuthDegrees),
      lightElevationDegrees = preferences.getFloat(KEY_METAL_LIGHT_ELEVATION, d.lightElevationDegrees),
      animateLight = preferences.getBoolean(KEY_METAL_ANIMATE_LIGHT, d.animateLight),
      lightTurnsPerLoop = preferences.getFloat(KEY_METAL_LIGHT_SPEED, d.lightTurnsPerLoop),
      environmentRotationDegrees = preferences.getFloat(KEY_METAL_ENV_ROTATION,
        d.environmentRotationDegrees),
      environmentIntensity = preferences.getFloat(KEY_METAL_ENV_INTENSITY, d.environmentIntensity),
      parallax = preferences.getFloat(KEY_METAL_PARALLAX, d.parallax),
      anisotropy = preferences.getFloat(KEY_METAL_ANISOTROPY, d.anisotropy),
      brushAngleDegrees = preferences.getFloat(KEY_METAL_BRUSH_ANGLE, d.brushAngleDegrees),
      exposure = preferences.getFloat(KEY_METAL_EXPOSURE, d.exposure),
    ).sanitised()
  }

  fun setMetalSettings(settings: MetalSettings) {
    preferences.edit()
      .putString(KEY_METAL_FINISH, settings.finish.name)
      .putFloat(KEY_METAL_EMBOSS, settings.emboss)
      .putFloat(KEY_METAL_METALLIC, settings.metallic)
      .putFloat(KEY_METAL_ROUGHNESS, settings.roughness)
      .putInt(KEY_METAL_BASE_COLOR, settings.baseColor)
      .putFloat(KEY_METAL_TEXTURE_TINT, settings.textureTint)
      .putFloat(KEY_METAL_LIGHT_INTENSITY, settings.lightIntensity)
      .putInt(KEY_METAL_LIGHT_COLOR, settings.lightColor)
      .putFloat(KEY_METAL_LIGHT_AZIMUTH, settings.lightAzimuthDegrees)
      .putFloat(KEY_METAL_LIGHT_ELEVATION, settings.lightElevationDegrees)
      .putBoolean(KEY_METAL_ANIMATE_LIGHT, settings.animateLight)
      .putFloat(KEY_METAL_LIGHT_SPEED, settings.lightTurnsPerLoop)
      .putFloat(KEY_METAL_ENV_ROTATION, settings.environmentRotationDegrees)
      .putFloat(KEY_METAL_ENV_INTENSITY, settings.environmentIntensity)
      .putFloat(KEY_METAL_PARALLAX, settings.parallax)
      .putFloat(KEY_METAL_ANISOTROPY, settings.anisotropy)
      .putFloat(KEY_METAL_BRUSH_ANGLE, settings.brushAngleDegrees)
      .putFloat(KEY_METAL_EXPOSURE, settings.exposure)
      .apply()
  }

  fun solveAnimation(fallback: SolveAnimation): SolveAnimation =
    enumByName(preferences.getString(KEY_SOLVE_ANIMATION, null), SolveAnimation.entries)
      ?: fallback

  fun setSolveAnimation(value: SolveAnimation) {
    preferences.edit().putString(KEY_SOLVE_ANIMATION, value.name).apply()
  }

  fun evolveCells(fallback: Int): Int =
    preferences.getInt(KEY_EVOLVE_CELLS, fallback).coerceIn(MIN_EVOLVE_CELLS, MAX_EVOLVE_CELLS)

  fun setEvolveCells(value: Int) {
    preferences.edit().putInt(KEY_EVOLVE_CELLS, value).apply()
  }

  fun toolButtonSize(fallback: Int): Int =
    preferences.getInt(KEY_TOOL_BUTTON_SIZE, fallback)
      .coerceIn(MIN_TOOL_BUTTON_SIZE, MAX_TOOL_BUTTON_SIZE)

  fun setToolButtonSize(value: Int) {
    preferences.edit().putInt(KEY_TOOL_BUTTON_SIZE, value).apply()
  }

  /** Whether the first-launch tutorial has already been shown. */
  fun tutorialSeen(fallback: Boolean): Boolean =
    preferences.getBoolean(KEY_TUTORIAL_SEEN, fallback)

  fun setTutorialSeen(value: Boolean) {
    preferences.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()
  }

  /**
   * The output settings, falling back per field.
   *
   * Stored by enum name rather than ordinal so reordering an enum cannot silently change the shape a
   * remembered session comes back with. A name this build does not know falls back too.
   */
  fun outputSettings(fallback: OutputSettings): OutputSettings = OutputSettings(
    aspect = enumByName(preferences.getString(KEY_OUTPUT_ASPECT, null), OutputAspect.entries)
      ?: fallback.aspect,
    preview = enumByName(preferences.getString(KEY_OUTPUT_PREVIEW, null), PreviewScale.entries)
      ?: fallback.preview,
    scale = enumByName(preferences.getString(KEY_OUTPUT_SCALE, null), OutputScale.entries)
      ?: fallback.scale,
    scaler = enumByName(preferences.getString(KEY_OUTPUT_SCALER, null), PixelArtScaler.entries)
      ?: fallback.scaler,
    kernel = enumByName(preferences.getString(KEY_OUTPUT_KERNEL, null), KernelSize.entries)
      ?: fallback.kernel,
  )

  fun setOutputSettings(settings: OutputSettings) {
    preferences.edit()
      .putString(KEY_OUTPUT_ASPECT, settings.aspect.name)
      .putString(KEY_OUTPUT_PREVIEW, settings.preview.name)
      .putString(KEY_OUTPUT_SCALE, settings.scale.name)
      .putString(KEY_OUTPUT_SCALER, settings.scaler.name)
      .putString(KEY_OUTPUT_KERNEL, settings.kernel.name)
      .apply()
  }

  private fun <T : Enum<T>> enumByName(name: String?, entries: List<T>): T? =
    name?.let { wanted -> entries.firstOrNull { it.name == wanted } }

  private companion object {
    const val FILE_NAME = "editor_settings"

    /** 2 moved the preview default from the largest size to the smallest. */
    const val SCHEMA_VERSION = 3

    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_WRAP_EDGES = "wrap_edges"
    const val KEY_MIRROR_HORIZONTAL = "mirror_horizontal"
    const val KEY_MIRROR_VERTICAL = "mirror_vertical"
    const val KEY_QUARTER_TURN = "quarter_turn"
    const val KEY_AUTO_MIGRATE_COLORS = "auto_migrate_colors"
    const val KEY_OUTPUT_ASPECT = "output_aspect"
    const val KEY_OUTPUT_PREVIEW = "output_preview"
    const val KEY_OUTPUT_SCALE = "output_scale"
    const val KEY_OUTPUT_SCALER = "output_scaler"
    const val KEY_OUTPUT_KERNEL = "output_kernel"
    const val KEY_VIDEO_SCREENS = "video_screens"
    const val KEY_VIDEO_FPS = "video_fps"
    const val KEY_VIDEO_PIXELS_PER_FRAME = "video_pixels_per_frame"
    const val KEY_VIDEO_FORMAT = "video_format"
    const val KEY_VIDEO_MODE = "video_mode"
    const val KEY_SCROLL_DIRECTION = "scroll_direction"
    const val KEY_VIDEO_LOOK = "video_look"
    const val KEY_METAL_FINISH = "metal_finish"
    const val KEY_METAL_EMBOSS = "metal_emboss"
    const val KEY_METAL_METALLIC = "metal_metallic"
    const val KEY_METAL_ROUGHNESS = "metal_roughness"
    const val KEY_METAL_BASE_COLOR = "metal_base_color"
    const val KEY_METAL_TEXTURE_TINT = "metal_texture_tint"
    const val KEY_METAL_LIGHT_INTENSITY = "metal_light_intensity"
    const val KEY_METAL_LIGHT_COLOR = "metal_light_color"
    const val KEY_METAL_LIGHT_AZIMUTH = "metal_light_azimuth"
    const val KEY_METAL_LIGHT_ELEVATION = "metal_light_elevation"
    const val KEY_METAL_ANIMATE_LIGHT = "metal_animate_light"
    const val KEY_METAL_LIGHT_SPEED = "metal_light_speed"
    const val KEY_METAL_ENV_ROTATION = "metal_env_rotation"
    const val KEY_METAL_ENV_INTENSITY = "metal_env_intensity"
    const val KEY_METAL_PARALLAX = "metal_parallax"
    const val KEY_METAL_ANISOTROPY = "metal_anisotropy"
    const val KEY_METAL_BRUSH_ANGLE = "metal_brush_angle"
    const val KEY_METAL_EXPOSURE = "metal_exposure"
    const val KEY_SOLVE_ANIMATION = "solve_animation"
    const val KEY_EVOLVE_CELLS = "evolve_cells"
    const val KEY_TOOL_BUTTON_SIZE = "tool_button_size"
    const val KEY_TUTORIAL_SEEN = "tutorial_seen"
  }
}