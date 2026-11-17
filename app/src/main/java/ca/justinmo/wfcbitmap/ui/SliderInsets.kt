package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The standard modifier for every slider in the app.
 *
 * The 16 dp side inset keeps the track and thumb off the screen edge, and [systemGestureExclusion] keeps
 * a drag that starts near that edge from being taken by the system back gesture.
 */
internal fun Modifier.sliderInsets(): Modifier =
  padding(horizontal = 16.dp).systemGestureExclusion()
