package ca.justinmo.wfcbitmap.storage
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.content.Context

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
     * The preview default used to be the largest size, and the app wrote it on first run. Moving the
     * default to the smallest one means that a preference written by the old app would otherwise keep
     * opening on the big preview: migrate that one old default once, and never touch the choice again.
     */
    migratePreviewDefault()
  }

  private fun migratePreviewDefault() {
    if (preferences.getInt(KEY_SCHEMA_VERSION, 1) >= SCHEMA_VERSION) return
    val editor = preferences.edit().putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
    if (preferences.getString(KEY_OUTPUT_PREVIEW, null) == PreviewScale.Two.name) {
      editor.putString(KEY_OUTPUT_PREVIEW, PreviewScale.Half.name)
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

  fun solveAnimation(fallback: SolveAnimation): SolveAnimation =
    enumByName(preferences.getString(KEY_SOLVE_ANIMATION, null), SolveAnimation.entries)
      ?: fallback

  fun setSolveAnimation(value: SolveAnimation) {
    preferences.edit().putString(KEY_SOLVE_ANIMATION, value.name).apply()
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
    const val SCHEMA_VERSION = 2

    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_WRAP_EDGES = "wrap_edges"
    const val KEY_MIRROR_HORIZONTAL = "mirror_horizontal"
    const val KEY_MIRROR_VERTICAL = "mirror_vertical"
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
    const val KEY_SOLVE_ANIMATION = "solve_animation"
  }
}