/**
 * The measurements and colours the editor's panels share: how big a tool button is, what sits behind the preview,
 * the panel's own background, and the look of the two dropdown menus.
 *
 * Kept together so that the strip, the canvas, the preview and the menus are cut from the same cloth.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Slightly darker than the app background, so the preview reads as its own surface. */
internal val PREVIEW_BACKGROUND = Color(0xFF0B0910)

/** Behind a menu or a tool's explanation: the toolbar's own near-black, so they read as part of the editor. */
internal val PANEL_BACKGROUND = Color(0xFF1A1720)

const val BUTTON_SIZE_DP_INT = 38

internal val BUTTON_SIZE = BUTTON_SIZE_DP_INT.dp

/** The menu's labels: light enough to read on the panel, dim enough not to compete with the drawing. */
internal val MENU_TEXT_COLOR = Color.White.copy(alpha = 0.92f)

/** A hairline between the parts of a menu -- the shape from the sizes, the palettes from each other. */
internal val MENU_DIVIDER_COLOR = Color.White.copy(alpha = 0.15f)

/** A heading over a group of menu rows, the same in both menus. */
@Composable
internal fun MenuHeading(text: String) {
  Text(
    text = text,
    color = Color.White.copy(alpha = 0.7f),
    fontSize = 11.sp,
    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
  )
}
