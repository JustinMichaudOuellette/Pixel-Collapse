package ca.justinmo.wfcbitmap

import android.app.Application
import ca.justinmo.wfcbitmap.palette.PaletteRepository
import ca.justinmo.wfcbitmap.storage.EditorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

/**
 * Starts the palette load before there is an editor to show it.
 *
 * The UI opens on one palette -- the last session's, or the default Nintendo Entertainment System
 * palette -- and waits only for that one.
 * The rest of the palette files are parsed immediately afterwards, so the picker already has its list by
 * the time it can be opened.
 */
class PixelCollapseApplication : Application() {

  lateinit var palettes: PaletteRepository
    private set

  /** The editor settings remembered across sessions; see [EditorPreferences]. */
  lateinit var preferences: EditorPreferences
    private set

  /**
   * A scope that outlives any one screen.
   *
   * A video export can take a minute or more, and locking the screen or switching away must not cancel
   * it. Work started here keeps running while the activity is stopped, and its observers pick the
   * progress or result back up when the editor is visible again.
   */
  val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  /**
   * Serialises every WFC generation, including one started by an export that outlives the activity.
   * The generator keeps a tally and a result cache, so two solves must never overlap.
   */
  val generationMutex = Mutex()

  override fun onCreate() {
    super.onCreate()
    preferences = EditorPreferences(this)
    palettes = PaletteRepository(this).also { it.preload() }
  }
}