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
import ca.justinmo.wfcbitmap.video.MAX_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MAX_GIF_DELAY_CENTISECONDS
import ca.justinmo.wfcbitmap.video.MAX_LOOP_SCREENS
import ca.justinmo.wfcbitmap.video.MAX_MP4_FPS
import ca.justinmo.wfcbitmap.video.MAX_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.MIN_EVOLVE_CELLS
import ca.justinmo.wfcbitmap.video.MIN_GIF_DELAY_CENTISECONDS
import ca.justinmo.wfcbitmap.video.MIN_LOOP_SCREENS
import ca.justinmo.wfcbitmap.video.MIN_VIDEO_FPS
import ca.justinmo.wfcbitmap.video.SolveAnimation
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoMode
import ca.justinmo.wfcbitmap.video.estimatedVideoBytes
import ca.justinmo.wfcbitmap.video.formatByteSize
import ca.justinmo.wfcbitmap.video.videoPlan
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
  mode: VideoMode = VideoMode.Panorama,
  solveAnimation: SolveAnimation = SolveAnimation.Collapse,
  evolveCells: Int = 1,
  screens: Int,
  fps: Int,
  pixelsPerFrame: Int = 2,
  frameColumns: Int,
  frameRows: Int,
  pixelsPerCell: Int,
  cellSize: Int,
  outputWidth: Int,
  outputHeight: Int,
  exporting: Boolean,
  onScreensChange: (Int) -> Unit,
  onModeChange: (VideoMode) -> Unit = {},
  onSolveAnimationChange: (SolveAnimation) -> Unit = {},
  onEvolveCellsChange: (Int) -> Unit = {},
  onFpsChange: (Int) -> Unit,
  onPixelsPerFrameChange: (Int) -> Unit = {},
  onFormatChange: (VideoFormat) -> Unit,
  onPreview: () -> Unit = {},
  onDismiss: () -> Unit,
  onExport: () -> Unit,
) {
  if (!open) return
  val plan = videoPlan(
    frameColumns, frameRows, pixelsPerCell, cellSize, screens, fps,
    pixelsPerFrame = pixelsPerFrame,
    outputWidth = outputWidth, outputHeight = outputHeight,
  )
  val solveSteps = (fps * 2).coerceIn(24, 90)
  val solveTotal = if (format == VideoFormat.Gif) solveSteps * 2 - 1 else solveSteps

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

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          VideoMode.entries.forEach { option ->
            val chosen = option == mode
            Button(
              onClick = { onModeChange(option) },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(
                containerColor = if (chosen) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.12f),
                contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
              ),
            ) {
              Text(option.label, fontSize = 13.sp)
            }
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
            when (mode) {
              VideoMode.Panorama ->
                "A wide picture pans left to right and loops back to its start. Every frame moves the " +
                  "window one pixel, so the loop is smooth by construction."
              VideoMode.Solve ->
                "A replay of the real solver, not a reveal of the finished bitmap. A GIF runs the replay " +
                  "backwards so it loops; an MP4 stops on the finished picture."
              VideoMode.Evolve ->
                "Evolve nudges the picture a few cells at a time: a small patch is re-solved on each " +
                  "frame with the cells around it held in place. Then those changes are taken back one " +
                  "at a time, so the clip ends where it started."
            },
            color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
          )

          if (mode == VideoMode.Solve) {
            Spacer(Modifier.height(18.dp))
            SettingLabel("Animation", solveAnimation.label)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              SolveAnimation.entries.forEach { option ->
                val chosen = option == solveAnimation
                Button(
                  onClick = { onSolveAnimationChange(option) },
                  enabled = !exporting,
                  modifier = Modifier.weight(1f),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = if (chosen) {
                      MENU_TEXT_COLOR
                    } else {
                      MENU_TEXT_COLOR.copy(alpha = 0.12f)
                    },
                    contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
                    disabledContainerColor = if (chosen) {
                      MENU_TEXT_COLOR.copy(alpha = 0.5f)
                    } else {
                      MENU_TEXT_COLOR.copy(alpha = 0.08f)
                    },
                    disabledContentColor = MENU_TEXT_COLOR.copy(alpha = 0.6f),
                  ),
                ) {
                  Text(option.label, fontSize = 12.sp)
                }
              }
            }
            Spacer(Modifier.height(6.dp))
            Text(
              solveAnimation.description,
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
          }

          if (mode == VideoMode.Evolve) {
            Spacer(Modifier.height(18.dp))
            SettingLabel("Cells per frame", "$evolveCells")
            Slider(
              value = evolveCells.toFloat(),
              onValueChange = {
                onEvolveCellsChange(
                  it.roundToInt().coerceIn(MIN_EVOLVE_CELLS, MAX_EVOLVE_CELLS),
                )
              },
              valueRange = MIN_EVOLVE_CELLS.toFloat()..MAX_EVOLVE_CELLS.toFloat(),
              steps = (MAX_EVOLVE_CELLS - MIN_EVOLVE_CELLS - 1) / 8,
              colors = SliderDefaults.colors(
                thumbColor = MENU_TEXT_COLOR,
                activeTrackColor = MENU_TEXT_COLOR,
                inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
              ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
              "A small local patch is re-solved each frame with its boundary pinned. If the drawing " +
                "has no local alternative, the patch keeps its current tiling. The second half of the " +
                "clip undoes the changes one at a time, back to the starting picture.",
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
          }

          Spacer(Modifier.height(22.dp))
          if (mode == VideoMode.Panorama) {
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
          }
          SettingLabel(
            "Frame rate",
            if (format == VideoFormat.Gif) {
              "${formatOneDecimal(plan.effectiveFps)} fps · ${plan.frameDelayCentiseconds} cs"
            } else {
              "${fps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS)} fps"
            },
          )
          if (format == VideoFormat.Gif) {
            // A GIF only stores whole centiseconds, so the thumb is the delay itself. There are exactly
            // nine ticks: 2 cs (50 fps) through 10 cs (10 fps), one per possible GIF value.
            Slider(
              value = plan.frameDelayCentiseconds.toFloat(),
              onValueChange = { cs ->
                val whole = cs.roundToInt().coerceIn(
                  MIN_GIF_DELAY_CENTISECONDS,
                  MAX_GIF_DELAY_CENTISECONDS,
                )
                onFpsChange((100f / whole).roundToInt().coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS))
              },
              valueRange = MIN_GIF_DELAY_CENTISECONDS.toFloat()..MAX_GIF_DELAY_CENTISECONDS.toFloat(),
              steps = MAX_GIF_DELAY_CENTISECONDS - MIN_GIF_DELAY_CENTISECONDS - 1,
              colors = SliderDefaults.colors(
                thumbColor = MENU_TEXT_COLOR,
                activeTrackColor = MENU_TEXT_COLOR,
                inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
              ),
            )
          } else {
            Slider(
              value = fps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS).toFloat(),
              onValueChange = { onFpsChange(it.roundToInt().coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS)) },
              valueRange = MIN_VIDEO_FPS.toFloat()..MAX_MP4_FPS.toFloat(),
              steps = MAX_MP4_FPS - MIN_VIDEO_FPS - 1,
              colors = SliderDefaults.colors(
                thumbColor = MENU_TEXT_COLOR,
                activeTrackColor = MENU_TEXT_COLOR,
                inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
              ),
            )
          }
          Spacer(Modifier.height(10.dp))
          if (mode == VideoMode.Panorama) {
            SettingLabel("Pixels per frame", "$pixelsPerFrame")
            Slider(
              value = pixelsPerFrame.toFloat(),
              onValueChange = { onPixelsPerFrameChange(it.roundToInt().coerceIn(1, 16)) },
              valueRange = 1f..16f,
              steps = 14,
              colors = SliderDefaults.colors(
                thumbColor = MENU_TEXT_COLOR,
                activeTrackColor = MENU_TEXT_COLOR,
                inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
              ),
            )
          }

          Spacer(Modifier.height(12.dp))
          if (mode == VideoMode.Panorama) {
            Text(
              "${plan.outputWidth}×${plan.outputHeight} · ${plan.frameCount} frames · " +
                "${formatOneDecimal(plan.loopSeconds)} s per loop · ${plan.pixelsPerFrame} px per frame",
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
            Text(
              "Estimated size ≈ ${formatByteSize(estimatedVideoBytes(plan, format))}",
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
          } else {
            Text(
              // Evolve's undo pass is as long as the number of cells that changed, which only the solve
              // knows, so its frame count is not quoted here.
              if (mode == VideoMode.Evolve) {
                "${frameColumns * pixelsPerCell}×${frameRows * pixelsPerCell} · " +
                  "$solveSteps evolving frames, then one undo frame per changed cell"
              } else {
                "${frameColumns * pixelsPerCell}×${frameRows * pixelsPerCell} · $solveTotal frames"
              },
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              lineHeight = 15.sp,
            )
          }

          if (mode == VideoMode.Panorama) {
            Spacer(Modifier.height(16.dp))
            Button(
              onClick = onPreview,
              enabled = !exporting,
              modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = MENU_TEXT_COLOR.copy(alpha = 0.16f),
                contentColor = MENU_TEXT_COLOR,
              ),
            ) {
              Text("Preview motion", fontSize = 13.sp)
            }
          }

          Spacer(Modifier.height(16.dp))
          FormatToggle(
            format = format,
            enabled = !exporting,
            exporting = exporting,
            onFormatChange = onFormatChange,
            onExport = onExport,
          )
          Spacer(Modifier.height(16.dp))
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
  exporting: Boolean,
  onFormatChange: (VideoFormat) -> Unit,
  onExport: () -> Unit,
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
            text = when (option) {
              VideoFormat.Gif -> "Loops anywhere"
              VideoFormat.Mp4 -> "Smaller, plays in a video app"
            },
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
          )
        }
      }
    }
    Button(
      onClick = onExport,
      enabled = enabled,
      modifier = Modifier
        .weight(1f)
        .height(56.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = MENU_TEXT_COLOR,
        contentColor = Color.Black,
      ),
    ) {
      Text(if (exporting) "…" else "Export", fontSize = 14.sp)
    }
  }
}

/** One decimal at most, and no trailing ".0": 25, 33.3, 16.7. */
private fun formatOneDecimal(value: Float): String {
  val tenths = (value * 10).roundToInt()
  return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}