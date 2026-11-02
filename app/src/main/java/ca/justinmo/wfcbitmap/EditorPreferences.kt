package ca.justinmo.wfcbitmap

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
  )

  fun setOutputSettings(settings: OutputSettings) {
    preferences.edit()
      .putString(KEY_OUTPUT_ASPECT, settings.aspect.name)
      .putString(KEY_OUTPUT_PREVIEW, settings.preview.name)
      .putString(KEY_OUTPUT_SCALE, settings.scale.name)
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
  }
}
