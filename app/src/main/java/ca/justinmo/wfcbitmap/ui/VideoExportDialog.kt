package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ca.justinmo.wfcbitmap.VIDEO_FPS
import ca.justinmo.wfcbitmap.VideoFormat
import ca.justinmo.wfcbitmap.videoPlan
import kotlin.math.roundToInt

/**
 * The video-export settings: how long the clip runs and how fast the panorama moves.
 *
 * A single format for now: an animated GIF. The summary below the sliders is the actual geometry the
 * export will use, including the speed after the panorama cap, so a very long, fast request is visible
 * before it is started.
 */
@Composable
fun VideoExportDialog(
  open: Boolean,
  durationSeconds: Float,
  speed: Float,
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  exporting: Boolean,
  onDurationChange: (Float) -> Unit,
  onSpeedChange: (Float) -> Unit,
  onDismiss: () -> Unit,
  onExport: (VideoFormat) -> Unit,
) {
  if (!open) return
  val plan = videoPlan(frameColumns, frameRows, pixelsPerCell, cellSize, durationSeconds, speed)

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = PANEL_BACKGROUND,
      modifier = Modifier.width(380.dp),
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        Text("Video export", color = MENU_TEXT_COLOR, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
          "A wide picture pans left to right. Export it as an MP4 or an animated GIF.",
          color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
          fontSize = 12.sp,
        )
        Spacer(Modifier.height(18.dp))

        SettingLabel("Duration", "${durationSeconds.roundToInt()} s")
        Slider(
          value = durationSeconds,
          onValueChange = { onDurationChange(it.roundToInt().toFloat()) },
          valueRange = 1f..30f,
          steps = 28,
          colors = SliderDefaults.colors(
            thumbColor = MENU_TEXT_COLOR,
            activeTrackColor = MENU_TEXT_COLOR,
            inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
          ),
        )

        SettingLabel("Speed", "${formatSpeed(speed)} screens/s")
        Slider(
          value = speed,
          onValueChange = onSpeedChange,
          valueRange = 0.25f..1f,
          steps = 14,
          colors = SliderDefaults.colors(
            thumbColor = MENU_TEXT_COLOR,
            activeTrackColor = MENU_TEXT_COLOR,
            inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
          ),
        )

        Text(
          "GIF / MP4 · ${plan.frameWidth}×${plan.frameHeight} · ${plan.frameCount} frames · " +
            "${VIDEO_FPS} fps · ${formatSpeed(plan.effectiveSpeed)} screens/s",
          color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
          fontSize = 11.sp,
        )
        if (plan.effectiveSpeed < speed - 0.01f) {
          Text(
            "The panorama is capped so it stays solvable, so the clip pans slower than requested.",
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
          )
        }
        Spacer(Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = { onExport(VideoFormat.Gif) },
            enabled = !exporting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
              containerColor = MENU_TEXT_COLOR,
              contentColor = Color.Black,
            ),
          ) {
            Text("Export GIF")
          }
          Spacer(Modifier.width(8.dp))
          Button(
            onClick = { onExport(VideoFormat.Mp4) },
            enabled = !exporting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
              containerColor = MENU_TEXT_COLOR,
              contentColor = Color.Black,
            ),
          ) {
            Text("Export MP4")
          }
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

/** 0.25, 0.5, 1, 1.5, 2 -- the slider steps without trailing zeros. */
private fun formatSpeed(speed: Float): String {
  val hundredths = (speed * 100).roundToInt()
  return when {
    hundredths % 100 == 0 -> "${hundredths / 100}"
    hundredths % 10 == 0 -> "${hundredths / 100}.${hundredths % 100 / 10}"
    else -> "${hundredths / 100}.${hundredths % 100}"
  }
}
