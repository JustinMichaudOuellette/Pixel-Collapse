/**
 * The palette: the colour strip under the drawing, the picker menu behind its last swatch, and the swatches
 * themselves.
 *
 * The strip owns only which colour is selected; the drawing colour belongs to the caller, so a session can be
 * saved without the strip being composed at all.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import ca.justinmo.wfcbitmap.Palette

@Composable
fun ColorToolbar(
  palette: Palette,
  selectedColor: Color,
  onColorSelected: (Color) -> Unit,
) {
  Column(
    modifier = Modifier
      .width(BUTTON_SIZE)
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

  if (!expanded) return

  /*
   * A popup rather than a DropdownMenu.
   *
   * DropdownMenu puts its content in a scrollable Column that also measures intrinsic width, and a
   * LazyColumn cannot live under either. The palette list has to be lazy -- dozens of palettes, one with
   * hundreds of colours -- so the menu provides its own panel and scrolls only the list.
   */
  Popup(
    onDismissRequest = onDismiss,
    properties = PopupProperties(focusable = true),
  ) {
    Surface(
      color = PANEL_BACKGROUND,
      shape = RoundedCornerShape(4.dp),
      shadowElevation = 8.dp,
    ) {
      Column(modifier = Modifier.width(280.dp)) {
        MenuHeading("Palette")

        /*
         * The switch says what choosing a palette does to the drawing that is already there.
         *
         * On, the drawing keeps its shapes but every colour is replaced by the closest one in the palette
         * that was picked. Off, switching only changes the strip the tools paint from, and the drawing
         * keeps colours the new palette may not have.
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
            // The row above owns the click: a switch with its own callback inside a clickable menu item
            // would receive the tap as well and could toggle twice.
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

        HorizontalDivider(color = MENU_DIVIDER_COLOR)

        /*
         * The list, not a column: there are dozens of palettes and one of them has hundreds of colours,
         * so composing every row and every swatch before the menu could open was what made it slow. A
         * lazy list builds the handful of rows on screen, and the band in each row is one draw call
         * instead of one composable per colour.
         */
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp),
        ) {
          items(items = palettes, key = { it.id }) { item ->
            DropdownMenuItem(
              text = {
                Column(
                  modifier = Modifier
                    .width(200.dp)
                    .padding(vertical = 4.dp)
                ) {
                  Text(
                    text = item.name,
                    color = MENU_TEXT_COLOR,
                    fontSize = 14.sp,
                  )
                  PaletteBand(
                    colors = item.colors,
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(14.dp)
                      .padding(top = 4.dp)
                      .clip(RoundedCornerShape(3.dp)),
                  )
                }
              },
              onClick = { onPaletteSelected(item) },
              colors = itemColors,
              trailingIcon = {
                if (item.id == currentPalette.id) {
                  Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
                }
              },
            )
          }
        }
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
