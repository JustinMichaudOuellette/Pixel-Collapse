package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.MAX_LOOP_SCREENS
import ca.justinmo.wfcbitmap.MAX_VIDEO_FPS
import ca.justinmo.wfcbitmap.MIN_LOOP_SCREENS
import ca.justinmo.wfcbitmap.MIN_VIDEO_FPS
import ca.justinmo.wfcbitmap.VideoFormat
import ca.justinmo.wfcbitmap.videoPlan
import kotlin.math.roundToInt

/**
 * The video export as a screen of its own: the loop's two measurements, the container to write, and one
 * button to start it.
 *
 * Laid out the way the saved-pattern screen is, because they are the same kind of thing -- a full screen
 * over the editor with a title and a way out in the corner. Black reaches the screen edges, the content
 * stays inside the safe area, the title is the screen's own line in the top-left and closing is the button
 * opposite it. What this screen adds is a footer: the button that starts the work sits in its own bar
 * rather than at the end of the scroll, because it is the one thing that must never be off the bottom.
 *
 * The frame rate is asked for in frames per second and reported in the centiseconds a GIF actually stores,
 * because that is the number the file will contain: a GIF delay is a whole number of hundredths of a second,
 * so asking for 24 fps gets 4 cs, which plays at 25.
 */
@Composable
fun VideoExportScreen(
  open: Boolean,
  format: VideoFormat,
  screens: Int,
  fps: Int,
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  exporting: Boolean,
  onScreensChange: (Int) -> Unit,
  onFpsChange: (Int) -> Unit,
  onFormatChange: (VideoFormat) -> Unit,
  onDismiss: () -> Unit,
  onExport: () -> Unit,
) {
  if (!open) return
  val plan = videoPlan(frameColumns, frameRows, pixelsPerCell, cellSize, screens, fps)

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
          // Keep the title and the settings off the camera cutout and out of the system bars, while the
          // black surface itself still reaches the screen edges.
          .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Video export",
            color = MENU_TEXT_COLOR,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismiss) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = MENU_TEXT_COLOR,
            )
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        ) {
          Text(
            "A wide picture pans left to right and loops back to its start. Every frame moves the window " +
              "one pixel, so the loop is smooth by construction.",
            color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
          )

          Spacer(Modifier.height(22.dp))
          SettingLabel("Loop", "$screens screens")
          Slider(
            value = screens.toFloat(),
            onValueChange = { onScreensChange(it.roundToInt().coerceIn(MIN_LOOP_SCREENS, MAX_LOOP_SCREENS)) },
            valueRange = MIN_LOOP_SCREENS.toFloat()..MAX_LOOP_SCREENS.toFloat(),
            steps = MAX_LOOP_SCREENS - MIN_LOOP_SCREENS - 1,
            colors = SliderDefaults.colors(
              thumbColor = MENU_TEXT_COLOR,
              activeTrackColor = MENU_TEXT_COLOR,
              inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
            ),
          )

          Spacer(Modifier.height(10.dp))
          SettingLabel("Frame rate", "${formatOneDecimal(plan.effectiveFps)} fps · ${plan.frameDelayCentiseconds} cs")
          Slider(
            value = fps.toFloat(),
            onValueChange = { onFpsChange(it.roundToInt().coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS)) },
            valueRange = MIN_VIDEO_FPS.toFloat()..MAX_VIDEO_FPS.toFloat(),
            steps = MAX_VIDEO_FPS - MIN_VIDEO_FPS - 1,
            colors = SliderDefaults.colors(
              thumbColor = MENU_TEXT_COLOR,
              activeTrackColor = MENU_TEXT_COLOR,
              inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
            ),
          )
          if (plan.effectiveFps.roundToInt() != fps) {
            Text(
              "A GIF stores whole centiseconds, so $fps fps is played as ${plan.frameDelayCentiseconds} cs.",
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
          }

          Spacer(Modifier.height(12.dp))
          Text(
            "${plan.frameWidth}×${plan.frameHeight} · ${plan.frameCount} frames · " +
              "${formatOneDecimal(plan.loopSeconds)} s per loop · 1 px per frame",
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
          )

          Spacer(Modifier.height(24.dp))
          FormatToggle(format = format, enabled = !exporting, onFormatChange = onFormatChange)
          Spacer(Modifier.height(16.dp))
        }

        // The action never scrolls: it is the reason this screen exists, and a settings screen whose last
        // line is the button is a settings screen that hides its button when the phone is small.
        Button(
          onClick = onExport,
          enabled = !exporting,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MENU_TEXT_COLOR,
            contentColor = Color.Black,
          ),
        ) {
          Text(if (exporting) "Exporting…" else "Export ${format.label}")
        }
      }
    }
  }
}

@Composable
private fun SettingLabel(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = MENU_TEXT_COLOR, fontSize = 13.sp)
    Spacer(Modifier.weight(1f))
    Text(value, color = MENU_TEXT_COLOR.copy(alpha = 0.6f), fontSize = 12.sp)
  }
}

/**
 * The two containers as a pair of cards, the chosen one lit.
 *
 * A toggle rather than two export buttons: the format is a *setting* of the export, not a second thing to
 * press, and two buttons made the screen read as if it could write both at once. The card under the finger
 * says what the choice means -- a GIF loops anywhere, an MP4 is smaller and plays in a video app -- so the
 * labels do not have to.
 */
@Composable
private fun FormatToggle(
  format: VideoFormat,
  enabled: Boolean,
  onFormatChange: (VideoFormat) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    VideoFormat.entries.forEach { option ->
      val chosen = option == format
      Surface(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .clickable(enabled = enabled) { onFormatChange(option) }
          .border(
            width = 1.dp,
            color = if (chosen) MENU_TEXT_COLOR else MENU_DIVIDER_COLOR,
            shape = RoundedCornerShape(10.dp),
          ),
        shape = RoundedCornerShape(10.dp),
        color = if (chosen) MENU_TEXT_COLOR.copy(alpha = 0.14f) else Color.Transparent,
      ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
          Text(
            text = option.label,
            color = MENU_TEXT_COLOR,
            fontSize = 14.sp,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
          )
          Spacer(Modifier.height(3.dp))
          Text(
            text = if (option == VideoFormat.Gif) {
              "Loops anywhere"
            } else {
              "Smaller, plays in a video app"
            },
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
          )
        }
      }
    }
  }
}

/** One decimal at most, and no trailing ".0": 25, 33.3, 16.7. */
private fun formatOneDecimal(value: Float): String {
  val tenths = (value * 10).roundToInt()
  return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}
