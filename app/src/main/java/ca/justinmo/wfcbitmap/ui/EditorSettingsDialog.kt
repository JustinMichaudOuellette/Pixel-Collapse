package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.storage.MAX_TOOL_BUTTON_SIZE
import ca.justinmo.wfcbitmap.storage.MIN_TOOL_BUTTON_SIZE
import kotlin.math.roundToInt

/**
 * The single editor setting: how large the square tool and colour buttons are.
 *
 * The value is applied live, so the strip changes while the slider moves and the choice can be judged
 * against the drawing rather than from a number.
 */
@Composable
internal fun EditorSettingsDialog(
  open: Boolean,
  buttonSize: Int,
  onButtonSizeChange: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  if (!open) return
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Settings",
            color = MENU_TEXT_COLOR,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = MENU_TEXT_COLOR)
          }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Tool and colour button size", color = MENU_TEXT_COLOR, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("$buttonSize dp", color = MENU_TEXT_COLOR.copy(alpha = 0.6f), fontSize = 12.sp)
          }
          Slider(
            modifier = Modifier.sliderInsets(),
            value = buttonSize.toFloat(),
            onValueChange = {
              onButtonSizeChange(
                it.roundToInt().coerceIn(MIN_TOOL_BUTTON_SIZE, MAX_TOOL_BUTTON_SIZE),
              )
            },
            valueRange = MIN_TOOL_BUTTON_SIZE.toFloat()..MAX_TOOL_BUTTON_SIZE.toFloat(),
            steps = MAX_TOOL_BUTTON_SIZE - MIN_TOOL_BUTTON_SIZE - 1,
            colors = SliderDefaults.colors(
              thumbColor = MENU_TEXT_COLOR,
              activeTrackColor = MENU_TEXT_COLOR,
              inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
            ),
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "Smaller buttons leave more room for the drawing; larger buttons are easier to hit.",
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
          )
        }
      }
    }
  }
}
