package ca.justinmo.wfcbitmap.palette

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ca.justinmo.wfcbitmap.storage.DrawingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's palettes, loaded in two stages before the editor needs them.
 *
 * The first stage resolves the one palette the editor opens on: the palette the last session was using
 * when that file still exists, otherwise the default (`nintendo-entertainment-system.txt`). The UI waits
 * only for this one, so it has
 * colours as soon as it can be composed. The second stage parses every other palette file straight after,
 * so opening the palette picker finds the whole list there.
 *
 * The state lives here rather than in an Activity because the Application starts the work and the editor
 * may be recreated while it is still running; [initialPalette] and [palettes] are snapshot state, so
 * Compose updates the moment each stage publishes.
 */
class PaletteRepository(context: Context) {

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var preloadJob: Job? = null

  /** The palette the editor can open on, or null while it is still being parsed. */
  var initialPalette by mutableStateOf<Palette?>(null)
    private set

  /**
   * Every palette parsed so far, with the default palette first once the full list is in.
   *
   * It starts as the single [initialPalette] and grows to the complete list; the picker observes it and
   * simply shows what is ready.
   */
  var palettes by mutableStateOf<List<Palette>>(emptyList())
    private set

  /** Starts the two-stage load. Calling it again does nothing: one Application, one load. */
  fun preload() {
    if (preloadJob != null) return
    preloadJob = scope.launch {
      // The last session remembers which palette was selected. Reading the snapshot is a file read, so
      // it happens off the main thread like the palette files themselves.
      val preferredId = withContext(Dispatchers.IO) {
        runCatching { DrawingStore.forDirectory(appContext.filesDir).load()?.paletteId }.getOrNull()
      }

      val fileNames = PaletteStore.paletteFiles(appContext)
      val (initialName, initial) = findInitialPalette(fileNames, preferredId) ?: return@launch

      // Stage one: the editor has everything it needs to compose and draw.
      initialPalette = initial
      palettes = listOf(initial)

      // Stage two: the rest of the picker, parsed straight after the one the UI is waiting on. The list
      // is rebuilt in asset order (with the default first) so the picker does not reorder itself just
      // because a session happened to open on a palette from the middle of the alphabet.
      val all = fileNames.mapNotNull { fileName ->
        if (fileName.equals(initialName, ignoreCase = true)) {
          initial
        } else {
          PaletteStore.loadPalette(appContext, fileName)
        }
      }
      palettes = PaletteStore.orderWithDefaultFirst(all)
    }
  }

  /**
   * The first palette to parse: the previously used one when it is still in the assets, otherwise the
   * default, otherwise whatever file can be read at all.
   */
  private suspend fun findInitialPalette(
    fileNames: List<String>,
    preferredId: String?,
  ): Pair<String, Palette>? {
    val preferredFile = preferredId?.let { id ->
      fileNames.firstOrNull { it.equals(id, ignoreCase = true) }
    }
    val defaultFile = fileNames.firstOrNull {
      it.equals(PaletteStore.DEFAULT_PALETTE_ID, ignoreCase = true)
    }
    val candidates = buildList {
      preferredFile?.let { add(it) }
      defaultFile?.let { if (it !in this) add(it) }
      addAll(fileNames)
    }

    for (fileName in candidates.distinct()) {
      val palette = PaletteStore.loadPalette(appContext, fileName) ?: continue
      return fileName to palette
    }
    return null
  }
}