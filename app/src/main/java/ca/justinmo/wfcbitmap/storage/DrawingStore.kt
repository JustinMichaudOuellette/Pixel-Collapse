package ca.justinmo.wfcbitmap.storage
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import java.io.File
import java.io.IOException

/**
 * The one file the editor's session is kept in between launches.
 *
 * A file rather than `onSaveInstanceState`, because a Bundle is for a screen's worth of ids and selections:
 * a 500x500 drawing is a megabyte of pixels, which a transaction will refuse outright. A file also survives
 * the cases a Bundle does not -- the process being killed while in the background, or the user swiping the
 * app away -- which is most of the ways a session is actually lost.
 *
 * Writes go to a temporary file and are renamed over the real one, so a save interrupted by the process
 * dying cannot leave a half-written snapshot where a good one was: the reader either sees the old file or
 * the new one.
 */
internal class DrawingStore(private val file: File) {

  /** Writes [snapshot] over whatever was saved before; false when the write itself failed. */
  fun save(snapshot: EditorSnapshot): Boolean {
    if (!snapshot.isValid) return false
    return try {
      file.parentFile?.mkdirs()
      val temporary = File(file.parentFile, "${file.name}.tmp")
      temporary.writeBytes(snapshot.encode())
      if (!temporary.renameTo(file)) {
        // Some filesystems refuse to rename over an existing file; dropping the old one first loses the
        // previous session only if the rename then fails too, which is the case where nothing would work.
        file.delete()
        temporary.renameTo(file)
      }
      true
    } catch (e: IOException) {
      false
    }
  }

  /** The saved session, or null when there is none or it cannot be read. */
  fun load(): EditorSnapshot? = try {
    if (!file.isFile || file.length() <= 0L || file.length() > EditorSnapshot.MAX_BYTES) {
      null
    } else {
      EditorSnapshot.decode(file.readBytes())
    }
  } catch (e: IOException) {
    null
  }

  /** Forgets the saved session, e.g. once it has been loaded and the file is no longer needed. */
  fun clear(): Boolean = file.delete()

  companion object {
    /** The file name inside the app's private files directory. */
    const val FILE_NAME = "editor-session.state"

    /** The store for an app's own private storage. */
    fun forDirectory(directory: File): DrawingStore = DrawingStore(File(directory, FILE_NAME))
  }
}

/**
 * Hands the activity a way to read the editor's state when it goes to the background.
 *
 * The state lives in a composition, and the lifecycle callback that has to read it lives in the activity,
 * so something has to bridge the two. This is that bridge: the composition installs a provider, the
 * activity calls it from `onStop`. A plain class rather than snapshot state, because nothing composes on
 * it -- the only reader is a lifecycle callback, on the main thread, after composition has settled.
 */
class EditorStateHolder {

  /** Installed by the editor; null before it has been composed, or after it has gone away. */
  var snapshot: (() -> EditorSnapshot?)? = null
}