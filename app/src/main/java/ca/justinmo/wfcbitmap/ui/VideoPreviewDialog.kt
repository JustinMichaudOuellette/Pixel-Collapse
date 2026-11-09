package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.video.ScrollDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A motion preview of the pan using the current preview picture.
 *
 * It is deliberately cheap: the editor already has one finished frame, so this slides that frame
 * across the screen at the chosen frame rate and pixels-per-frame instead of solving a second panorama
 * just to look at it. The export itself still builds its own wide panorama.
 */
@Composable
fun VideoPreviewDialog(
  open: Boolean,
  image: ImageBitmap?,
  fps: Int,
  pixelsPerFrame: Int,
  scrollDirection: ScrollDirection = ScrollDirection.LeftToRight,
  onDismiss: () -> Unit,
) {
  if (!open || image == null) return
  var frame by remember(open) { mutableIntStateOf(0) }
  LaunchedEffect(open, fps) {
    while (isActive) {
      delay((1000L / max(1, fps)).coerceAtLeast(1L))
      frame++
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        // The export frame's own aspect, letterboxed into the screen: a 9:16 export must look 9:16 in
        // the preview even when the editor (and therefore this dialog) is in landscape.
        val aspect = image.width.toFloat() / image.height
        val viewWidth = min(size.width, size.height * aspect)
        val viewHeight = viewWidth / aspect
        val left = (size.width - viewWidth) / 2f
        val top = (size.height - viewHeight) / 2f
        val scale = viewWidth / image.width
        val span = if (scrollDirection.isVertical) image.height else image.width
        val rawShift = (frame.toLong() * pixelsPerFrame % span).toInt()
        val reverse = scrollDirection == ScrollDirection.RightToLeft ||
          scrollDirection == ScrollDirection.Up
        val shift = if (reverse && rawShift != 0) span - rawShift else rawShift
        val offset = shift * scale
        clipRect(left, top, left + viewWidth, top + viewHeight) {
          if (scrollDirection.isVertical) {
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
      IconButton(
        onClick = onDismiss,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(12.dp),
      ) {
        Icon(Icons.Default.Close, contentDescription = "Close preview", tint = Color.White)
      }
    }
  }
}
