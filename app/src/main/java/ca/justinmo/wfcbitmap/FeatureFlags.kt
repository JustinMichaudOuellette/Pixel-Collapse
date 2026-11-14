package ca.justinmo.wfcbitmap

/**
 * Build-time switches for features that are not ready to ship.
 *
 * Keeping every disabled feature here makes the list of what is withheld one file: turn a flag back on
 * to restore the tool everywhere it is offered, without hunting through the toolbar and the tour.
 * The flags are checked by the editor tool catalog; a hidden tool has no button to invoke it.
 */
internal object FeatureFlags {

  /** The triangle shape tool. */
  const val TRIANGLE_TOOL_ENABLED = false

  /** The camera-photo drawing tool. */
  const val CAMERA_TOOL_ENABLED = false

  /** The best-effort fill mode. */
  const val BEST_EFFORT_TOOL_ENABLED = false
}
