package ca.justinmo.wfcbitmap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import ca.justinmo.wfcbitmap.video.MetalFinish
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.ScrollDirection
import ca.justinmo.wfcbitmap.video.SolveAnimation
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoLook
import ca.justinmo.wfcbitmap.video.VideoMode
import ca.justinmo.wfcbitmap.video.estimatedVideoBytes
import ca.justinmo.wfcbitmap.video.formatByteSize
import ca.justinmo.wfcbitmap.video.videoPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.min
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
  scrollDirection: ScrollDirection = ScrollDirection.LeftToRight,
  look: VideoLook = VideoLook.Flat,
  metal: MetalSettings = MetalSettings(),
  previewPixels: IntArray? = null,
  previewWidth: Int = 0,
  previewHeight: Int = 0,
  previewCellPixels: Int = 1,
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
  onScrollDirectionChange: (ScrollDirection) -> Unit = {},
  onLookChange: (VideoLook) -> Unit = {},
  onMetalChange: (MetalSettings) -> Unit = {},
  onFormatChange: (VideoFormat) -> Unit,
  onDismiss: () -> Unit,
  onExport: () -> Unit,
) {
  if (!open) return
  var shaderMenuOpen by remember { mutableStateOf(false) }
  var modeMenuOpen by remember { mutableStateOf(false) }
  val gifAllowed = look == VideoLook.Flat
  val exportPixelScale = if (previewWidth > 0) {
    maxOf(
      outputWidth.toFloat() / previewWidth,
      if (previewHeight > 0) outputHeight.toFloat() / previewHeight else 1f,
    ).coerceAtLeast(1f)
  } else {
    1f
  }
  // GIF cannot carry a continuous-tone shader: its 256-colour table bands the material and the file
  // gets large. Moving to a shader selects MP4, including for a session restored with GIF + Metal.
  LaunchedEffect(look, format) {
    if (!gifAllowed && format == VideoFormat.Gif) onFormatChange(VideoFormat.Mp4)
  }
  val plan = videoPlan(
    frameColumns, frameRows, pixelsPerCell, cellSize, screens, fps,
    pixelsPerFrame = pixelsPerFrame,
    scrollDirection = scrollDirection,
    outputWidth = outputWidth, outputHeight = outputHeight,
  )
  val solveSteps = (fps * 2).coerceIn(24, 90)
  val solveTotal = if (format == VideoFormat.Gif) solveSteps * 2 - 1 else solveSteps
  val previewFps = if (format == VideoFormat.Gif) {
    (100f / maxOf(plan.frameDelayCentiseconds, 10)).roundToInt()
  } else {
    fps.coerceIn(MIN_VIDEO_FPS, MAX_MP4_FPS)
  }
  var previewFrame by remember(mode) { mutableIntStateOf(0) }
  LaunchedEffect(mode, previewFps, pixelsPerFrame, previewPixels) {
    if (mode != VideoMode.Panorama || previewPixels == null) return@LaunchedEffect
    while (isActive) {
      delay((1000L / previewFps.coerceAtLeast(1)).coerceAtLeast(1L))
      previewFrame++
    }
  }
  val verticalPreview = scrollDirection.isVertical
  val previewSpan = if (verticalPreview) previewHeight else previewWidth
  val previewShift = if (previewSpan <= 0) {
    0
  } else {
    val raw = ((previewFrame.toLong() * pixelsPerFrame) % previewSpan).toInt()
    val reverse = scrollDirection == ScrollDirection.RightToLeft || scrollDirection == ScrollDirection.Up
    if (reverse && raw != 0) previewSpan - raw else raw
  }
  val previewFraction = if (previewSpan <= 0) 0f else previewShift.toFloat() / previewSpan

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
          IconButton(onClick = onDismiss) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = MENU_TEXT_COLOR,
            )
          }
          VideoModePicker(
            mode = mode,
            enabled = !exporting,
            expanded = modeMenuOpen,
            onExpandedChange = { modeMenuOpen = it },
            onModeChange = onModeChange,
            modifier = Modifier.weight(1f),
          )
          Button(
            onClick = onExport,
            enabled = !exporting && (format != VideoFormat.Gif || gifAllowed),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier
              .height(36.dp)
              .semantics { contentDescription = "Export video" },
            colors = ButtonDefaults.buttonColors(
              containerColor = MENU_TEXT_COLOR,
              contentColor = Color.Black,
            ),
          ) {
            Text(if (exporting) "…" else "Export", fontSize = 13.sp)
          }
        }

        /*
         * The preview is fixed above the controls: it is the thing every setting below changes, so it
         * must not scroll away with them. It stays visible for both looks and whichever format is set;
         * GIF cannot carry the shader, so a shader preview is only shown for MP4.
         */
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(0.34f)
            .background(Color.Black),
          contentAlignment = Alignment.Center,
        ) {
          when {
            previewPixels == null || previewWidth <= 0 || previewHeight <= 0 -> {
              Text(
                "Solve a picture first to preview the video.",
                color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
                fontSize = 11.sp,
              )
            }
            look == VideoLook.Metal && format == VideoFormat.Mp4 -> {
              ZoomableMetalPreview(
                pixels = previewPixels,
                width = previewWidth,
                height = previewHeight,
                cellPixels = previewCellPixels,
                settings = metal,
                maxZoom = exportPixelScale,
                panX = if (mode == VideoMode.Panorama && !verticalPreview) previewFraction else 0f,
                panY = if (mode == VideoMode.Panorama && verticalPreview) previewFraction else 0f,
                wrapX = mode == VideoMode.Panorama && !verticalPreview,
                wrapY = mode == VideoMode.Panorama && verticalPreview,
                modifier = Modifier.fillMaxSize(),
              )
            }
            mode == VideoMode.Panorama -> {
              PanoramaFlatPreview(
                pixels = previewPixels,
                width = previewWidth,
                height = previewHeight,
                shiftPixels = previewShift,
                vertical = verticalPreview,
                modifier = Modifier.fillMaxSize(),
              )
            }
            else -> {
              FlatPreview(
                pixels = previewPixels,
                width = previewWidth,
                height = previewHeight,
                modifier = Modifier.fillMaxSize(),
              )
            }
          }
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(0.66f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        ) {
          Text(
            when (mode) {
              VideoMode.Panorama ->
                "A wide picture pans around a loop and returns to its start. Pick the direction below; " +
                  "every frame moves the window by the chosen step, so the loop is smooth by construction."
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

          Spacer(Modifier.height(18.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            ShaderPicker(
              look = look,
              enabled = !exporting,
              expanded = shaderMenuOpen,
              onExpandedChange = { shaderMenuOpen = it },
              onLookChange = { selected ->
                onLookChange(selected)
                if (selected != VideoLook.Flat && format == VideoFormat.Gif) {
                  onFormatChange(VideoFormat.Mp4)
                }
              },
              modifier = Modifier.weight(1f),
            )
          }

          // The material controls belong to this screen now; the live preview above stays put while
          // these scroll.
          if (look == VideoLook.Metal) {
            MetalShaderControls(
              metal = metal,
              exporting = exporting,
              onMetalChange = onMetalChange,
            )
          }

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

            Spacer(Modifier.height(14.dp))
            SettingLabel("Scroll direction", scrollDirection.label)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              ScrollDirection.entries.forEach { option ->
                val chosen = option == scrollDirection
                Button(
                  onClick = { onScrollDirectionChange(option) },
                  enabled = !exporting,
                  modifier = Modifier.weight(1f),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = if (chosen) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.12f),
                    contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
                  ),
                ) {
                  Icon(
                    imageVector = when (option) {
                      ScrollDirection.LeftToRight -> Icons.AutoMirrored.Filled.ArrowForward
                      ScrollDirection.RightToLeft -> Icons.AutoMirrored.Filled.ArrowBack
                      ScrollDirection.Up -> Icons.Default.ArrowUpward
                      ScrollDirection.Down -> Icons.Default.ArrowDownward
                    },
                    contentDescription = option.label,
                  )
                }
              }
            }
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
            // A GIF only stores whole centiseconds. The slider runs opposite the delay: the left end is
            // 10 cs (10 fps), the right end is the fastest value, 2 cs (50 fps), which is also the default.
            val delay = plan.frameDelayCentiseconds.coerceIn(
              MIN_GIF_DELAY_CENTISECONDS,
              MAX_GIF_DELAY_CENTISECONDS,
            )
            Slider(
              value = (MIN_GIF_DELAY_CENTISECONDS + MAX_GIF_DELAY_CENTISECONDS - delay).toFloat(),
              onValueChange = { reversed ->
                val whole = (MIN_GIF_DELAY_CENTISECONDS + MAX_GIF_DELAY_CENTISECONDS -
                  reversed.roundToInt()).coerceIn(
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

          Spacer(Modifier.height(16.dp))
          FormatToggle(
            format = format,
            enabled = !exporting,
            gifEnabled = gifAllowed,
            onFormatChange = onFormatChange,
          )
          Spacer(Modifier.height(16.dp))
        }
      }
    }
  }
}

/** The video type as a compact field in the titlebar, with the modes in a menu. */
@Composable
private fun VideoModePicker(
  mode: VideoMode,
  enabled: Boolean,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onModeChange: (VideoMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val itemColors = MenuDefaults.itemColors(
    textColor = MENU_TEXT_COLOR,
    leadingIconColor = MENU_TEXT_COLOR,
    trailingIconColor = MENU_TEXT_COLOR,
  )
  Box(modifier = modifier) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, MENU_DIVIDER_COLOR, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled) { onExpandedChange(true) }
        .semantics { contentDescription = "Video type picker" },
      color = MENU_TEXT_COLOR.copy(alpha = 0.08f),
      shape = RoundedCornerShape(8.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(mode.label, color = MENU_TEXT_COLOR, fontSize = 13.sp)
        Icon(
          Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = MENU_TEXT_COLOR.copy(alpha = 0.7f),
          modifier = Modifier.padding(start = 4.dp),
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { onExpandedChange(false) },
      containerColor = PANEL_BACKGROUND,
    ) {
      VideoMode.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          onClick = {
            onExpandedChange(false)
            onModeChange(option)
          },
          colors = itemColors,
          trailingIcon = {
            if (option == mode) {
              Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
            }
          },
        )
      }
    }
  }
}

/** The picture as it will be exported flat: the fallback preview and the GIF preview. */
@Composable
private fun FlatPreview(
  pixels: IntArray,
  width: Int,
  height: Int,
  modifier: Modifier = Modifier,
) {
  val image = remember(pixels, width, height) {
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
  }
  Image(
    bitmap = image,
    contentDescription = "Video preview",
    contentScale = ContentScale.Fit,
    modifier = modifier,
  )
}

/**
 * The flat panorama preview: the solved frame repeated and slid past the window at the export's own
 * pace. This is deliberately the same picture repeated, not a second panorama solve; the export still
 * builds its own wide panorama.
 */
@Composable
private fun PanoramaFlatPreview(
  pixels: IntArray,
  width: Int,
  height: Int,
  shiftPixels: Int,
  vertical: Boolean,
  modifier: Modifier = Modifier,
) {
  val image = remember(pixels, width, height) {
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
  }
  Canvas(modifier.semantics { contentDescription = "Video preview" }) {
    val aspect = image.width.toFloat() / image.height
    val viewWidth = min(size.width, size.height * aspect)
    val viewHeight = viewWidth / aspect
    val left = (size.width - viewWidth) / 2f
    val top = (size.height - viewHeight) / 2f
    val scale = viewWidth / image.width
    val offset = shiftPixels * scale
    clipRect(left, top, left + viewWidth, top + viewHeight) {
      if (vertical) {
        var y = top - offset
        while (y < top + viewHeight) {
          drawImage(
            image = image,
            dstOffset = IntOffset(left.roundToInt(), y.roundToInt()),
            dstSize = IntSize(viewWidth.roundToInt(), viewHeight.roundToInt()),
          )
          y += viewHeight
        }
      } else {
        var x = left - offset
        while (x < left + viewWidth) {
          drawImage(
            image = image,
            dstOffset = IntOffset(x.roundToInt(), top.roundToInt()),
            dstSize = IntSize(viewWidth.roundToInt(), viewHeight.roundToInt()),
          )
          x += viewWidth
        }
      }
    }
  }
}

/**
 * The shader picker: the current shader as a field, with the available shaders in a menu.
 *
 * A menu rather than the old pair of buttons because the list is expected to grow, and a field can say
 * which shader is selected without giving every one of them a permanent button on the screen.
 */
@Composable
private fun ShaderPicker(
  look: VideoLook,
  enabled: Boolean,
  expanded: Boolean,
  onExpandedChange: (Boolean) -> Unit,
  onLookChange: (VideoLook) -> Unit,
  modifier: Modifier = Modifier,
) {
  val itemColors = MenuDefaults.itemColors(
    textColor = MENU_TEXT_COLOR,
    leadingIconColor = MENU_TEXT_COLOR,
    trailingIconColor = MENU_TEXT_COLOR,
  )
  Box(modifier = modifier) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .border(1.dp, MENU_DIVIDER_COLOR, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled) { onExpandedChange(true) }
        .semantics { contentDescription = "Shader picker" },
      color = MENU_TEXT_COLOR.copy(alpha = 0.08f),
      shape = RoundedCornerShape(8.dp),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Shader",
          color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
          fontSize = 12.sp,
          modifier = Modifier.weight(1f),
        )
        Text(look.label, color = MENU_TEXT_COLOR, fontSize = 14.sp)
        Icon(
          Icons.Default.KeyboardArrowDown,
          contentDescription = null,
          tint = MENU_TEXT_COLOR.copy(alpha = 0.7f),
          modifier = Modifier.padding(start = 6.dp),
        )
      }
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { onExpandedChange(false) },
      containerColor = PANEL_BACKGROUND,
    ) {
      VideoLook.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          onClick = {
            onExpandedChange(false)
            onLookChange(option)
          },
          colors = itemColors,
          trailingIcon = {
            if (option == look) {
              Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
            }
          },
        )
      }
    }
  }
}

/**
 * The live metal preview with pinch-to-zoom and drag-to-pan, capped at 1:1.
 *
 * The picture is laid out at one bitmap pixel per screen pixel at 1x. At rest it is scaled down to fit
 * the preview box; pinching multiplies that layout scale up to 1x, so the ceiling is the source's own
 * resolution rather than an arbitrary magnification. The parent clips the zoomed picture, so panning
 * cannot draw over the titlebar or the controls.
 */
@Composable
private fun ZoomableMetalPreview(
  pixels: IntArray,
  width: Int,
  height: Int,
  cellPixels: Int,
  settings: MetalSettings,
  maxZoom: Float,
  panX: Float = 0f,
  panY: Float = 0f,
  wrapX: Boolean = false,
  wrapY: Boolean = false,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(
    modifier = modifier.clipToBounds().background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    val density = LocalDensity.current
    val imageWidth = with(density) { width.toDp() }
    val imageHeight = with(density) { height.toDp() }
    val fitScale = minOf(
      maxWidth.value / imageWidth.value,
      maxHeight.value / imageHeight.value,
    ).coerceIn(0.01f, 1f)
    val zoomCeiling = maxZoom.coerceAtLeast(1f)
    var scale by remember(width, height, fitScale, zoomCeiling) {
      mutableFloatStateOf(fitScale)
    }
    var pan by remember(width, height, fitScale, zoomCeiling) { mutableStateOf(Offset.Zero) }
    var paused by remember(width, height) { mutableStateOf(false) }

    val boxWidth = with(density) { maxWidth.toPx() }
    val boxHeight = with(density) { maxHeight.toPx() }
    val imageWidthPx = with(density) { imageWidth.toPx() }
    val imageHeightPx = with(density) { imageHeight.toPx() }

    fun clampPan(candidate: Offset, atScale: Float): Offset {
      val maxX = ((imageWidthPx * atScale - boxWidth) / 2f).coerceAtLeast(0f)
      val maxY = ((imageHeightPx * atScale - boxHeight) / 2f).coerceAtLeast(0f)
      return Offset(
        candidate.x.coerceIn(-maxX, maxX),
        candidate.y.coerceIn(-maxY, maxY),
      )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
      val next = (scale * zoomChange).coerceIn(fitScale, zoomCeiling)
      scale = next
      pan = clampPan(pan + panChange, next)
    }

    MetalPreview(
      pixels = pixels,
      width = width,
      height = height,
      cellPixels = cellPixels,
      settings = settings,
      paused = paused,
      panX = panX,
      panY = panY,
      wrapX = wrapX,
      wrapY = wrapY,
      modifier = Modifier
        .size(imageWidth * scale, imageHeight * scale)
        .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
        .clip(RoundedCornerShape(10.dp))
        .background(Color.Black),
    )

    // The gesture layer sits over the picture. A tap pauses the light where it is; the zoom button is
    // added after it so it still wins its own tap.
    Box(
      modifier = Modifier
        .matchParentSize()
        .transformable(transformState)
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ) { paused = !paused }
        .semantics { contentDescription = "Metal preview" },
    )

    if (paused) {
      Text(
        text = "Paused",
        color = MENU_TEXT_COLOR,
        fontSize = 11.sp,
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(8.dp)
          .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 3.dp),
      )
    }

    Text(
      text = "${(scale * 100).roundToInt()}%",
      color = MENU_TEXT_COLOR,
      fontSize = 10.sp,
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(8.dp)
        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp),
    )
    TextButton(
      onClick = {
        if (scale < zoomCeiling - 0.001f) {
          scale = zoomCeiling
          pan = Offset.Zero
        } else {
          scale = fitScale
          pan = Offset.Zero
        }
      },
      contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(8.dp)
        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
    ) {
      Text(
        if (scale < zoomCeiling - 0.001f) {
          "Export ${formatOneDecimal(zoomCeiling)}×"
        } else {
          "Fit"
        },
        color = MENU_TEXT_COLOR,
        fontSize = 11.sp,
      )
    }
  }
}

/**
 * The Metal 3D material controls, inline under the shader picker.
 *
 * The preview above the scroll is the same GPU shader the exporter runs, so a slider and the material
 * it changes are visible together. Every change is applied to the caller's state as it is made.
 */
@Composable
private fun MetalShaderControls(
  metal: MetalSettings,
  exporting: Boolean,
  onMetalChange: (MetalSettings) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
                Spacer(Modifier.height(16.dp))
                SettingLabel("Metal finish", metal.finish.label)
                MetalFinish.entries.chunked(3).forEach { row ->
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    row.forEach { finish ->
                      val chosen = finish == metal.finish
                      Button(
                        onClick = { onMetalChange(metal.withFinish(finish)) },
                        enabled = !exporting,
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                        modifier = Modifier
                          .weight(1f)
                          .height(34.dp),
                        colors = ButtonDefaults.buttonColors(
                          containerColor = if (chosen) {
                            MENU_TEXT_COLOR
                          } else {
                            MENU_TEXT_COLOR.copy(alpha = 0.12f)
                          },
                          contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
                        ),
                      ) {
                        Text(finish.label, fontSize = 10.sp, maxLines = 1)
                      }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                  }
                  Spacer(Modifier.height(6.dp))
                }

                MetalSlider(
                  label = "Emboss strength",
                  value = metal.emboss,
                  range = 0f..2.5f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(emboss = it)) }
                MetalSlider(
                  label = "Metallic",
                  value = metal.metallic,
                  range = 0f..1f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(metallic = it)) }
                MetalSlider(
                  label = "Roughness",
                  value = metal.roughness,
                  range = 0.03f..0.65f,
                  decimals = 3,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(roughness = it)) }
                MetalSlider(
                  label = "Texture tint",
                  value = metal.textureTint,
                  range = 0f..1f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(textureTint = it)) }
                MetalSlider(
                  label = "Anisotropy",
                  value = metal.anisotropy,
                  range = 0f..1f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(anisotropy = it)) }
                MetalSlider(
                  label = "Brush direction",
                  value = metal.brushAngleDegrees,
                  range = 0f..180f,
                  decimals = 0,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(brushAngleDegrees = it)) }
                MetalSlider(
                  label = "Parallax",
                  value = metal.parallax,
                  range = 0f..0.35f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(parallax = it)) }

                Spacer(Modifier.height(6.dp))
                MetalColorSliders(
                  label = "Metal base colour",
                  color = metal.baseColor,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(baseColor = it)) }

                Spacer(Modifier.height(14.dp))
                Text("MOVING LIGHT", color = MENU_TEXT_COLOR.copy(alpha = 0.5f), fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  listOf(true, false).forEach { animate ->
                    val chosen = metal.animateLight == animate
                    Button(
                      onClick = { onMetalChange(metal.copy(animateLight = animate)) },
                      enabled = !exporting,
                      modifier = Modifier.weight(1f),
                      colors = ButtonDefaults.buttonColors(
                        containerColor = if (chosen) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.12f),
                        contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
                      ),
                    ) {
                      Text(if (animate) "Animated" else "Manual", fontSize = 12.sp)
                    }
                  }
                }
                LightSwitch(
                  label = "Key light",
                  on = metal.keyLightEnabled,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(keyLightEnabled = it)) }
                MetalSlider(
                  label = "Light intensity",
                  value = metal.lightIntensity,
                  range = 0f..12f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(lightIntensity = it)) }
                MetalSlider(
                  label = "Animation speed",
                  value = metal.lightTurnsPerLoop,
                  range = 0f..3f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(lightTurnsPerLoop = it)) }
                MetalSlider(
                  label = "Light position",
                  value = metal.lightAzimuthDegrees,
                  range = 0f..360f,
                  decimals = 0,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(lightAzimuthDegrees = it)) }
                MetalSlider(
                  label = "Light height",
                  value = metal.lightElevationDegrees,
                  range = -20f..90f,
                  decimals = 0,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(lightElevationDegrees = it)) }
                MetalColorSliders(
                  label = "Light colour",
                  color = metal.lightColor,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(lightColor = it)) }

                Spacer(Modifier.height(14.dp))
                Text("REFLECTIONS", color = MENU_TEXT_COLOR.copy(alpha = 0.5f), fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                  "ENVIRONMENT LIGHTS",
                  color = MENU_TEXT_COLOR.copy(alpha = 0.4f),
                  fontSize = 9.sp,
                )
                Spacer(Modifier.height(4.dp))
                environmentLightSwitches.chunked(2).forEach { row ->
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    row.forEach { light ->
                      LightSwitch(
                        label = light.label,
                        on = light.isOn(metal),
                        enabled = !exporting,
                        modifier = Modifier.weight(1f),
                      ) { onMetalChange(light.toggle(metal)) }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                  }
                  Spacer(Modifier.height(6.dp))
                }
                MetalSlider(
                  label = "Environment intensity",
                  value = metal.environmentIntensity,
                  range = 0f..2f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(environmentIntensity = it)) }
                MetalSlider(
                  label = "Environment rotation",
                  value = metal.environmentRotationDegrees,
                  range = 0f..360f,
                  decimals = 0,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(environmentRotationDegrees = it)) }
                MetalSlider(
                  label = "Exposure",
                  value = metal.exposure,
                  range = 0.4f..2.5f,
                  decimals = 2,
                  enabled = !exporting,
                ) { onMetalChange(metal.copy(exposure = it)) }

                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(20.dp))
  }
}

@Composable
private fun LightSwitch(
  label: String,
  on: Boolean,
  enabled: Boolean,
  modifier: Modifier = Modifier.fillMaxWidth(),
  onChange: (Boolean) -> Unit,
) {
  Button(
    onClick = { onChange(!on) },
    enabled = enabled,
    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    modifier = modifier
      .height(34.dp)
      .semantics {
        contentDescription = "${if (on) "Turn off" else "Turn on"} $label"
      },
    colors = ButtonDefaults.buttonColors(
      containerColor = if (on) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.12f),
      contentColor = if (on) Color.Black else MENU_TEXT_COLOR.copy(alpha = 0.65f),
    ),
  ) {
    Text(label, fontSize = 10.sp, maxLines = 1)
  }
}

@Composable
private fun MetalSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  decimals: Int,
  enabled: Boolean,
  onChange: (Float) -> Unit,
) {
  SettingLabel(label, String.format(Locale.US, "%.${decimals}f", value))
  Slider(
    value = value,
    onValueChange = onChange,
    valueRange = range,
    enabled = enabled,
    colors = SliderDefaults.colors(
      thumbColor = MENU_TEXT_COLOR,
      activeTrackColor = MENU_TEXT_COLOR,
      inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
      disabledThumbColor = MENU_TEXT_COLOR.copy(alpha = 0.4f),
      disabledActiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.4f),
      disabledInactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.15f),
    ),
  )
}

/**
 * Three compact 0..255 channel sliders, so the metal and the key can be any colour rather than only one
 * of the presets. The hex value is shown next to the label as the source of truth.
 */
@Composable
private fun MetalColorSliders(
  label: String,
  color: Int,
  enabled: Boolean,
  onChange: (Int) -> Unit,
) {
  SettingLabel(label, String.format(Locale.US, "#%06X", color and 0xFFFFFF))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MetalColorChannel(
      name = "R",
      value = (color ushr 16) and 0xFF,
      enabled = enabled,
      modifier = Modifier.weight(1f),
    ) { onChange((color and 0xFF00FFFF.toInt()) or (it shl 16)) }
    MetalColorChannel(
      name = "G",
      value = (color ushr 8) and 0xFF,
      enabled = enabled,
      modifier = Modifier.weight(1f),
    ) { onChange((color and 0xFFFF00FF.toInt()) or (it shl 8)) }
    MetalColorChannel(
      name = "B",
      value = color and 0xFF,
      enabled = enabled,
      modifier = Modifier.weight(1f),
    ) { onChange((color and 0xFFFFFF00.toInt()) or it) }
  }
}

@Composable
private fun MetalColorChannel(
  name: String,
  value: Int,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  onChange: (Int) -> Unit,
) {
  Column(modifier = modifier) {
    Text(
      "$name $value",
      color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
      fontSize = 10.sp,
    )
    Slider(
      value = value.toFloat(),
      onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
      valueRange = 0f..255f,
      enabled = enabled,
      colors = SliderDefaults.colors(
        thumbColor = MENU_TEXT_COLOR,
        activeTrackColor = MENU_TEXT_COLOR,
        inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
      ),
    )
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
  gifEnabled: Boolean,
  onFormatChange: (VideoFormat) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    VideoFormat.entries.forEach { option ->
      val chosen = option == format
      val optionEnabled = enabled && (option != VideoFormat.Gif || gifEnabled)
      val ink = if (optionEnabled) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.35f)
      Surface(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(10.dp))
          .clickable(enabled = optionEnabled) { onFormatChange(option) }
          .border(
            width = 1.dp,
            color = if (chosen && optionEnabled) MENU_TEXT_COLOR else MENU_DIVIDER_COLOR,
            shape = RoundedCornerShape(10.dp),
          ),
        shape = RoundedCornerShape(10.dp),
        color = if (chosen && optionEnabled) {
          MENU_TEXT_COLOR.copy(alpha = 0.14f)
        } else {
          Color.Transparent
        },
      ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
          Text(
            text = option.label,
            color = ink,
            fontSize = 14.sp,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
          )
          Spacer(Modifier.height(3.dp))
          Text(
            text = when {
              option == VideoFormat.Gif && !gifEnabled -> "Not with a shader"
              option == VideoFormat.Gif -> "Loops anywhere"
              else -> "Smaller, plays in a video app"
            },
            color = ink.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
          )
        }
      }
    }
  }
}

/** One environment light that can be switched off independently. */
private data class LightSwitchSpec(
  val label: String,
  val isOn: (MetalSettings) -> Boolean,
  val toggle: (MetalSettings) -> MetalSettings,
)

private val environmentLightSwitches = listOf(
  LightSwitchSpec("Sky", { it.skyEnabled }, { it.copy(skyEnabled = !it.skyEnabled) }),
  LightSwitchSpec("Floor", { it.floorEnabled }, { it.copy(floorEnabled = !it.floorEnabled) }),
  LightSwitchSpec("Horizon", { it.horizonEnabled }, { it.copy(horizonEnabled = !it.horizonEnabled) }),
  LightSwitchSpec(
    "Floor bounce",
    { it.floorBounceEnabled },
    { it.copy(floorBounceEnabled = !it.floorBounceEnabled) },
  ),
  LightSwitchSpec(
    "Front panel",
    { it.frontPanelEnabled },
    { it.copy(frontPanelEnabled = !it.frontPanelEnabled) },
  ),
  LightSwitchSpec("Blue panel", { it.bluePanelEnabled }, { it.copy(bluePanelEnabled = !it.bluePanelEnabled) }),
  LightSwitchSpec("Amber panel", { it.amberPanelEnabled }, { it.copy(amberPanelEnabled = !it.amberPanelEnabled) }),
  LightSwitchSpec(
    "Key softbox",
    { it.keySoftboxEnabled },
    { it.copy(keySoftboxEnabled = !it.keySoftboxEnabled) },
  ),
  LightSwitchSpec(
    "Overhead",
    { it.overheadPanelEnabled },
    { it.copy(overheadPanelEnabled = !it.overheadPanelEnabled) },
  ),
  LightSwitchSpec("Spot 1", { it.smallSpot1Enabled }, { it.copy(smallSpot1Enabled = !it.smallSpot1Enabled) }),
  LightSwitchSpec("Spot 2", { it.smallSpot2Enabled }, { it.copy(smallSpot2Enabled = !it.smallSpot2Enabled) }),
  LightSwitchSpec("Spot 3", { it.smallSpot3Enabled }, { it.copy(smallSpot3Enabled = !it.smallSpot3Enabled) }),
)

/** One decimal at most, and no trailing ".0": 25, 33.3, 16.7. */
private fun formatOneDecimal(value: Float): String {
  val tenths = (value * 10).roundToInt()
  return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}