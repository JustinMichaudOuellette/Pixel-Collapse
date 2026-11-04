/**
 * The palette: the colour strip under the drawing, the picker menu behind its last swatch, and the swatches
 * themselves.
 *
 * The strip owns only which colour is selected; the drawing colour belongs to the caller, so a session can be
 * saved without the strip being composed at all.
 */

package ca.justinmo.wfcbitmap.ui
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.text.Html
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

@Composable
fun ColorToolbar(
  palette: Palette,
  selectedColor: Color,
  onColorSelected: (Color) -> Unit,
  buttonSize: androidx.compose.ui.unit.Dp = BUTTON_SIZE,
) {
  Column(
    modifier = Modifier
      .width(buttonSize)
      .fillMaxHeight(),
  ) {
    // Color swatches
    LazyColumn(
      modifier = Modifier.weight(1f),
    ) {
      items(palette.colors) { color ->
        ColorSwatch(
          color = color,
          isSelected = color == selectedColor,
          onClick = { onColorSelected(color) },
        )
      }
    }
  }
}

/** The same palette laid out along a row, for the portrait editor. */
@Composable
fun ColorStrip(
  palette: Palette,
  selectedColor: Color,
  onColorSelected: (Color) -> Unit,
  buttonSize: androidx.compose.ui.unit.Dp = BUTTON_SIZE,
) {
  androidx.compose.foundation.lazy.LazyRow(
    modifier = Modifier
      .fillMaxWidth()
      .height(buttonSize),
  ) {
    items(palette.colors) { color ->
      Box(modifier = Modifier.width(buttonSize).fillMaxHeight()) {
        ColorSwatch(
          color = color,
          isSelected = color == selectedColor,
          onClick = { onColorSelected(color) },
        )
      }
    }
  }
}

@Composable
internal fun PaletteMenu(
  expanded: Boolean,
  palettes: List<Palette>,
  currentPalette: Palette,
  autoMigrateColors: Boolean,
  onAutoMigrateColorsChange: (Boolean) -> Unit,
  onDismiss: () -> Unit,
  onPaletteSelected: (Palette) -> Unit,
) {
  val itemColors = MenuDefaults.itemColors(
    textColor = MENU_TEXT_COLOR,
    leadingIconColor = MENU_TEXT_COLOR,
    trailingIconColor = MENU_TEXT_COLOR,
  )

  // Hoisted above the early return so the list keeps its place when the picker is closed and reopened.
  val listState = rememberLazyListState()

  if (!expanded) return

  // Created with the picker and forgotten when it closes, so a description shown on one visit is not
  // still there when the picker is opened again.
  val snackbar = remember { SnackbarHostState() }
  val snackbarScope = rememberCoroutineScope()
  val uriHandler = LocalUriHandler.current

  /*
   * A popup rather than a DropdownMenu.
   *
   * DropdownMenu puts its content in a scrollable Column that also measures intrinsic width, and a
   * LazyColumn cannot live under either. The palette list has to be lazy -- dozens of palettes, one with
   * hundreds of colours -- so the menu provides its own full-height panel and scrolls only the list.
   */
  Popup(
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Surface(
      modifier = Modifier
        .width(400.dp)
        .fillMaxHeight(),
      color = PANEL_BACKGROUND,
      shape = RoundedCornerShape(4.dp),
      shadowElevation = 8.dp,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
          MenuHeading("Palette")

        /*
         * The list, not a column: there are dozens of palettes and one of them has hundreds of colours,
         * so composing every row and every swatch before the menu could open was what made it slow. A
         * lazy list builds the handful of rows on screen, and the band in each row is one draw call
         * instead of one composable per colour.
         *
         * The auto-migrate setting and the Lospec link are list items too, so the link sits right after
         * the switch and both scroll away with the rest of the list.
         */
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        ) {
          item(key = "auto-migrate") {
            /*
             * The switch says what choosing a palette does to the drawing that is already there.
             *
             * On, the drawing keeps its shapes but every colour is replaced by the closest one in the
             * palette that was picked. Off, switching only changes the strip the tools paint from, and
             * the drawing keeps colours the new palette may not have.
             */
            DropdownMenuItem(
              text = {
                Column(
                  modifier = Modifier
                    .width(200.dp)
                    .padding(vertical = 4.dp)
                ) {
                  Text(
                    text = "Auto migrate colors",
                    color = MENU_TEXT_COLOR,
                    fontSize = 14.sp,
                  )
                  Text(
                    text = "Recolor the drawing to the closest colors in the palette you pick",
                    color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                  )
                }
              },
              onClick = { onAutoMigrateColorsChange(!autoMigrateColors) },
              colors = itemColors,
              trailingIcon = {
                // The row above owns the click: a switch with its own callback inside a clickable menu
                // item would receive the tap as well and could toggle twice.
                Switch(
                  checked = autoMigrateColors,
                  onCheckedChange = null,
                  colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = MENU_TEXT_COLOR,
                    uncheckedThumbColor = MENU_TEXT_COLOR,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = MENU_TEXT_COLOR.copy(alpha = 0.5f),
                  ),
                )
              },
            )
          }

          // Every palette file came from Lospec; this opens the list they were downloaded from.
          item(key = "lospec-link") {
            Text(
              text = "Palettes from lospec.com/palette-list",
              color = MENU_TEXT_COLOR,
              fontSize = 12.sp,
              textDecoration = TextDecoration.Underline,
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  uriHandler.openUri(LOSPEC_PALETTE_LIST_URL)
                  onDismiss()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            )
          }

          item(key = "palette-divider") {
            HorizontalDivider(color = MENU_DIVIDER_COLOR)
          }

          items(items = palettes, key = { it.id }) { item ->
            PaletteRow(
              palette = item,
              selected = item.id == currentPalette.id,
              onClick = { onPaletteSelected(item) },
              onLongClick = {
                // Keep the picker open: the description is shown in its own snackbar, over the list.
                snackbarScope.launch {
                  snackbar.currentSnackbarData?.dismiss()
                  snackbar.showSnackbar(
                    message = paletteDescriptionText(item),
                    duration = SnackbarDuration.Long,
                  )
                }
              },
            )
          }
        }
        }

        SnackbarHost(
          hostState = snackbar,
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp),
        ) { data ->
          Snackbar(
            snackbarData = data,
            containerColor = PANEL_BACKGROUND,
            contentColor = Color.White,
            dismissActionContentColor = Color.White,
          )
        }
      }
    }
  }
}

/**
 * One palette in the picker: name, colour band, and the tick for the active one.
 *
 * A long press is its own gesture so the description can be read without choosing the palette; the tap
 * still selects it.
 */
@Composable
private fun PaletteRow(
  palette: Palette,
  selected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = palette.name,
        color = MENU_TEXT_COLOR,
        fontSize = 14.sp,
      )
      PaletteBand(
        colors = palette.colors,
        modifier = Modifier
          .fillMaxWidth()
          .height(16.dp)
          .padding(top = 4.dp)
          .clip(RoundedCornerShape(3.dp)),
      )
    }
    // Reserve the tick's space on every row, selected or not, so all the colour bands end at the same
    // place instead of the active one being narrower than the rest.
    Box(
      modifier = Modifier
        .padding(start = 12.dp)
        .width(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (selected) {
        Icon(
          Icons.Default.Check,
          contentDescription = "Selected",
          tint = MENU_TEXT_COLOR,
        )
      }
    }
  }
}

/**
 * A palette's colours as one continuous band.
 *
 * Drawn rather than composed: a palette can have hundreds of entries, and hundreds of `Box`es in a
 * `Row` made opening the picker expensive for a strip that is 200dp wide.
 */
@Composable
private fun PaletteBand(colors: List<Color>, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    if (colors.isEmpty()) return@Canvas
    var left = 0f
    for (index in colors.indices) {
      val right = size.width * (index + 1) / colors.size
      drawRect(
        color = colors[index],
        topLeft = Offset(left, 0f),
        size = Size(right - left, size.height),
      )
      left = right
    }
  }
}

@Composable
fun ColorSwatch(
  color: Color,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier = Modifier
      .aspectRatio(1f)
      .clickable(onClick = onClick)
      .then(
        if (isSelected) {
          Modifier.background(Color.DarkGray)
        } else {
          Modifier
        }
      )
      .padding(SWATCH_MARGIN)
      .border(
        width = 1.dp,
        color = SWATCH_SELECTED_BORDER_COLOR,
        shape = RoundedCornerShape(SWATCH_CORNER_RADIUS)
      ),
    contentAlignment = Alignment.Center
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(color = color, shape = RoundedCornerShape(SWATCH_CORNER_RADIUS))
    )
  }
}

private val SWATCH_SELECTED_BORDER_COLOR = Color.White
private val SWATCH_MARGIN = 5.dp

private val SWATCH_CORNER_RADIUS = 3.dp

/**
 * A palette description as the picker's snackbar can show it.
 *
 * Palette files come from Lospec and their descriptions may contain HTML. A snackbar draws plain text,
 * so markup is parsed to its visible text; links keep their label but not their target.
 */
private fun paletteDescriptionText(palette: Palette): String {
  val description = palette.description.trim()
  if (description.isEmpty()) return "No description for ${palette.name}."
  return runCatching {
    Html.fromHtml(description, Html.FROM_HTML_MODE_LEGACY).toString()
  }.getOrDefault(description).trim().ifBlank { description }
}

/** Where every palette file came from. */
private const val LOSPEC_PALETTE_LIST_URL = "https://lospec.com/palette-list"