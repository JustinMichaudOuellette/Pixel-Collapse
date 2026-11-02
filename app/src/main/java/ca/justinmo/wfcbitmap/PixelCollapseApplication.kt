package ca.justinmo.wfcbitmap

import android.app.Application

/**
 * Starts the palette load before there is an editor to show it.
 *
 * The UI opens on one palette -- the last session's, or `endesga-32.txt` -- and waits only for that one.
 * The rest of the palette files are parsed immediately afterwards, so the picker already has its list by
 * the time it can be opened.
 */
class PixelCollapseApplication : Application() {

  lateinit var palettes: PaletteRepository
    private set

  /** The editor settings remembered across sessions; see [EditorPreferences]. */
  lateinit var preferences: EditorPreferences
    private set

  override fun onCreate() {
    super.onCreate()
    preferences = EditorPreferences(this)
    palettes = PaletteRepository(this).also { it.preload() }
  }
}
