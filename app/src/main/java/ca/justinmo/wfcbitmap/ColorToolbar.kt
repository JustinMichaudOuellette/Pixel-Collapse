/**
 * The palette: the colour strip under the drawing, the picker menu behind its last swatch, and the swatches
 * themselves.
 *
 * The strip owns only which colour is selected; the drawing colour belongs to the caller, so a session can be
 * saved without the strip being composed at all.
 */

package ca.justinmo.wfcbitmap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ColorToolbar(
  palette: Palette = PaletteStore.defaultPalette,
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
  onDismiss: () -> Unit,
  onPaletteSelected: (Palette) -> Unit,
) {
  val itemColors = MenuDefaults.itemColors(
    textColor = MENU_TEXT_COLOR,
    leadingIconColor = MENU_TEXT_COLOR,
    trailingIconColor = MENU_TEXT_COLOR,
  )

  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    containerColor = PANEL_BACKGROUND,
  ) {
    MenuHeading("Palette")
    palettes.forEach { item ->
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
            // Continuous band of all palette colors
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(3.dp))
            ) {
              item.colors.forEach { color ->
                Box(
                  modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(color)
                )
              }
            }
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

/**
 * The colour a fresh session starts with: the default palette's first entry.
 */
internal val defaultDrawingColor: Color get() = PaletteStore.defaultPalette.colors.first()

private val SWATCH_SELECTED_BORDER_COLOR = Color.White
private val SWATCH_MARGIN = 5.dp

private val SWATCH_CORNER_RADIUS = 3.dp
