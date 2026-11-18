/**
 * The measurements and colours the editor's panels share: how big a tool button is, what sits behind the preview,
 * the panel's own background, and the look of the two dropdown menus.
 *
 * Kept together so that the strip, the canvas, the preview and the menus are cut from the same cloth. The cloth
 * is black and white, like the theme: the drawing's colours are the only colour on screen.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Behind the preview, where the picture does not reach: the app's black, so the picture is all that shows. */
internal val PREVIEW_BACKGROUND = Color.Black

/** Behind a menu or a tool's explanation: the same black, so they read as part of the editor. */
internal val PANEL_BACKGROUND = Color.Black

const val BUTTON_SIZE_DP_INT = 48

internal val BUTTON_SIZE = BUTTON_SIZE_DP_INT.dp

/**
 * The editor grid's default cell size, in dp.
 *
 * Deliberately separate from [BUTTON_SIZE]: the drawing grid is a workspace, not a row of controls, and
 * changing the toolbar/colour-button size must not resize the cells. The +/- edge controls still use the
 * button size.
 */
const val EDITOR_GRID_CELL_SIZE_DP_INT = 38

internal val EDITOR_GRID_CELL_SIZE = EDITOR_GRID_CELL_SIZE_DP_INT.dp

/** The menu's labels, in the theme's ink. */
internal val MENU_TEXT_COLOR = Color.White

/** A hairline between the parts of a menu -- the shape from the sizes, the palettes from each other. */
internal val MENU_DIVIDER_COLOR = Color.White.copy(alpha = 0.15f)

/**
 * The grid's separators on the drawing: #767676.
 *
 * A grey rather than white, because the lines are there to mark the cells and not to compete with the
 * drawing: on a dark drawing white made the grid the brightest thing on screen.
 */
internal val GRID_LINE_COLOR = Color(0xFF767676)

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