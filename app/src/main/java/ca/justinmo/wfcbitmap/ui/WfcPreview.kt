package ca.justinmo.wfcbitmap.ui
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The live bitmap generated from the drawing, drawn in the column left of the grid edge.
 *
 * Nothing else is drawn here on purpose: no caption, no controls, no padding. Two things earn an
 * exception. One is the bar across the top while [isGenerating], which says the picture showing is a
 * moment out of date. The other is [error], which is the picture's replacement when there is no picture:
 * a sentence about this drawing is still about the drawing, and the column is the only place the editor
 * has to say it at the size the picture would be.
 *
 * What is drawn here is the generated bitmap, at a whole-number magnification and with nearest-neighbour
 * resampling: `FilterQuality.None` keeps the pattern's pixels as blocks, and the whole number is what
 * stops them being blocks of different sizes. The leftover column is padding around the picture, which
 * is centred in it. The size is worked out by [previewDrawSize].
 *
 * @param output the last generated bitmap, or null before the first one is ready, or after a generation
 *   that failed. Holding on to the previous output is what keeps the preview steady while a newer edit is
 *   being solved.
 * @param error what went wrong instead of a picture, or null when the last generation worked. It is drawn
 *   *in place of* the picture, not over it: a message about why there is no picture cannot be read under
 *   the picture it replaced, and a stretched or half-made one would be a quieter lie than the sentence.
 * @param isGenerating whether a newer bitmap is being solved right now. The picture under it stays up,
 *   because a preview that blanked on every tap would show nothing for most of the time it is used.
 * @param onTap called when the preview is tapped: the caller advances the seed and regenerates.
 */
@Composable
fun WfcPreview(
  output: WfcGenerator.Output?,
  modifier: Modifier = Modifier,
  error: String? = null,
  isGenerating: Boolean = false,
  scaler: PixelArtScaler = PixelArtScaler.Nearest,
  onTap: () -> Unit = {},
) {
  var zoom by remember { mutableStateOf(1f) }
  var pan by remember { mutableStateOf(Offset.Zero) }

  // The chosen upscaler is shown on screen too, not only in the saved file: a 2x pass is enough to make
  // the difference visible, and it is small enough to compute off the main thread.
  val displayImage by produceState<ImageBitmap?>(
    initialValue = null,
    output?.bitmap,
    scaler,
  ) {
    val bitmap = output?.bitmap
    value = if (scaler != PixelArtScaler.Nearest && bitmap != null) {
      withContext(Dispatchers.Default) { scaledPreview(bitmap, scaler) }
    } else {
      null
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .clipToBounds()
      // A tap asks for the next variation: same drawing, next seed. The ripple is the only feedback
      // the preview has, which is why it is left on.
      .clickable(onClickLabel = "Generate a new variation", onClick = onTap)
      // Pinching takes over only with two fingers, or with one while already zoomed: a single finger on
      // a 1x preview still belongs to the panel that slides it over the editor.
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          var pinched = false
          do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
              pinched = true
              val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 8f)
              zoom = nextZoom
              pan = if (nextZoom > 1f) pan + event.calculatePan() else Offset.Zero
              event.changes.forEach { it.consume() }
            } else if (pinched || (zoom > 1f && pressed == 1)) {
              if (zoom > 1f) pan += event.calculatePan()
              event.changes.forEach { it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      },
  ) {
    if (error != null) {
      Text(
        text = error,
        color = PREVIEW_ERROR_COLOR,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .fillMaxSize()
          // Centred in the column the picture would have filled, with a margin so the sentence is a
          // block of text rather than a line running to both edges.
          .padding(horizontal = 20.dp, vertical = 24.dp)
          .wrapContentHeight(Alignment.CenterVertically),
      )
    } else if (output != null) {
      val picture = displayImage ?: output.image
      Canvas(
        modifier = Modifier
          .fillMaxSize()
          // Says to a screen reader what the picture is; the drawing itself has no text to give it.
          .semantics { contentDescription = PREVIEW_DESCRIPTION },
      ) {
        val (width, height) = previewDrawSize(
          bitmapWidth = picture.width,
          bitmapHeight = picture.height,
          columnWidth = size.width.toInt(),
          columnHeight = size.height.toInt(),
          zoom = zoom,
        )
        if (width <= 0 || height <= 0) return@Canvas
        val maxPanX = ((width - size.width) / 2f).coerceAtLeast(0f)
        val maxPanY = ((height - size.height) / 2f).coerceAtLeast(0f)
        // Centred at 1x; while zoomed, the pinch's pan moves the picture but never past its edges.
        val left = (size.width - width) / 2f + pan.x.coerceIn(-maxPanX, maxPanX)
        val top = (size.height - height) / 2f + pan.y.coerceIn(-maxPanY, maxPanY)
        clipRect(0f, 0f, size.width, size.height) {
          drawImage(
            image = picture,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.None,
          )
        }
      }
    }
    // Drawn after the picture, so it is drawn *over* it: the bar is about the picture it covers, and it
    // may neither be hidden behind it nor push it down. It takes no room it would have to take back.
    GeneratingBar(
      visible = isGenerating,
      modifier = Modifier.align(Alignment.TopCenter),
    )
  }
}

/**
 * The hairline that runs along the top edge of the preview while the picture is being rebuilt.
 *
 * It is indeterminate, because nothing in the app can say how far along a solve is: [WfcGenerator]
 * reports its timings only once it has finished. A bar across the top and not a spinner over the middle
 * because the picture underneath is the thing being looked at, and it has to stay readable while the bar
 * says it is out of date.
 *
 * @param visible whether a solve is in flight. It is not drawn at all until the solve has run long
 *   enough to be worth watching: see [SHOW_DELAY_MILLIS].
 */
@Composable
private fun GeneratingBar(visible: Boolean, modifier: Modifier = Modifier) {
  // A generation that is over in a couple of frames would flash rather than inform, and edits are made
  // faster than that, so the bar is only armed once there is something to wait for. The effect is
  // restarted whenever `visible` changes, so a solve that ends inside the delay simply never arms it.
  var armed by remember { mutableStateOf(false) }
  LaunchedEffect(visible) {
    if (visible) {
      delay(SHOW_DELAY_MILLIS)
      armed = true
    } else {
      armed = false
    }
  }
  // Faded in and out rather than switched: a 2 dp line appearing on a picture reads as a drawing glitch,
  // and fading out is what lets the new picture be looked at without the bar still sitting over it.
  val alpha by animateFloatAsState(
    targetValue = if (armed) 1f else 0f,
    animationSpec = tween(durationMillis = if (armed) FADE_IN_MILLIS else FADE_OUT_MILLIS),
    label = "generating bar",
  )
  if (alpha <= 0f) return

  // Where the highlight is in its sweep. Read inside the draw block below, so the sweep invalidates the
  // bar's drawing on every frame without recomposing anything for it.
  val phase by rememberInfiniteTransition(label = "generating").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "sweep",
  )

  Canvas(
    modifier = modifier
      .fillMaxWidth()
      .height(BAR_HEIGHT)
      .alpha(alpha)
      // Says to a screen reader what the bar says to the eye, and says it as a progress bar rather than
      // as decorative pixels.
      .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
  ) {
    // The track dims the picture's top row, so the bar reads the same over a white row of pixels as over
    // a black one instead of vanishing into the bright parts of the pattern.
    drawRect(TRACK_COLOR)
    // The highlight runs from just off the left edge to just off the right one. Travelling its own width
    // outside the bar at both ends is what stops it from popping into and out of existence mid-sweep.
    val highlightWidth = size.width * HIGHLIGHT_FRACTION
    val left = (size.width + highlightWidth) * phase - highlightWidth
    drawRect(
      brush = Brush.horizontalGradient(
        colors = listOf(Color.Transparent, HIGHLIGHT_COLOR, Color.Transparent),
        startX = left,
        endX = left + highlightWidth,
      ),
      topLeft = Offset(left, 0f),
      size = Size(highlightWidth, size.height),
    )
  }
}

/** The preview's accessibility name: it is the picture, and saying so is the only thing it can be called. */
private const val PREVIEW_DESCRIPTION = "Live bitmap generated from the drawing"

/** A 2x copy of the generated picture through [scaler], for the smoothing to be visible while drawing. */
private fun scaledPreview(source: Bitmap, scaler: PixelArtScaler): ImageBitmap {
  val pixels = IntArray(source.width * source.height)
  source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
  val scaled = PixelScaler.scale(pixels, source.width, source.height, scaler, 2)
  return Bitmap.createBitmap(
    scaled.pixels,
    scaled.width,
    scaled.height,
    Bitmap.Config.ARGB_8888,
  ).asImageBitmap()
}

/**
 * The size the generated picture is drawn at inside a preview column of [columnWidth] x [columnHeight].
 *
 * Drawn at a **whole-number** magnification, never a fraction. A 540-pixel picture fitted to a 598-pixel
 * column would be scaled by 1.107, which draws some of its pixels one screen pixel wide and some two --
 * the stretch-and-duplicate that a pixel picture must not have. The largest whole magnification that fits
 * is used instead, and the column's leftover is padding around the picture, which is centred in it.
 *
 * A picture larger than the column is sampled down by a whole divisor for the same reason: every picture
 * pixel then stands for one screen pixel of a k-by-k block rather than a fraction of one.
 *
 * [zoom] is the pinch magnification. It multiplies the whole-number step and is rounded to one, so the
 * picture stays exact at every zoom instead of smearing between two of them.
 */
internal fun previewDrawSize(
  bitmapWidth: Int,
  bitmapHeight: Int,
  columnWidth: Int,
  columnHeight: Int,
  zoom: Float = 1f,
): Pair<Int, Int> {
  if (bitmapWidth <= 0 || bitmapHeight <= 0 || columnWidth <= 0 || columnHeight <= 0) return 0 to 0
  val fit = minOf(
    columnWidth.toDouble() / bitmapWidth,
    columnHeight.toDouble() / bitmapHeight,
  )
  if (fit >= 1.0) {
    val base = floor(fit).toInt().coerceAtLeast(1)
    val scale = (base * zoom).roundToInt().coerceAtLeast(1)
    return bitmapWidth * scale to bitmapHeight * scale
  }
  // Smaller than the column: whole-number division, so each k-by-k block of picture pixels is one pixel.
  val divisor = ceil(1.0 / fit).toInt().coerceAtLeast(1)
  return (bitmapWidth / divisor).coerceAtLeast(1) to (bitmapHeight / divisor).coerceAtLeast(1)
}

/** A hairline over the picture rather than a band across it: the preview is the point, not the bar. */
private val BAR_HEIGHT = 2.dp

/**
 * The colour of the message shown when there is no picture.
 *
 * The theme's ink, because the app has only the two colours: a message is written like everything else, and the
 * words themselves -- the preview's own place, where a picture was expected -- are what mark it as a failure.
 */
private val PREVIEW_ERROR_COLOR = Color.White

/**
 * How long a solve has to run before the bar is shown at all.
 *
 * Shorter than the fastest solve worth waiting for and longer than a frame, so an edit that lands on a
 * picture almost immediately costs nothing but never shows a bar that is gone before it is read.
 */
private const val SHOW_DELAY_MILLIS = 100L

private const val FADE_IN_MILLIS = 90
private const val FADE_OUT_MILLIS = 160

/** One pass of the highlight: slow enough to read as work happening, fast enough not to look stuck. */
private const val SWEEP_MILLIS = 900

/** The highlight's width as a fraction of the bar's: it lights part of the bar, never all of it. */
private const val HIGHLIGHT_FRACTION = 0.3f

/** Dims the picture's top row under the bar, so the bar reads over light and dark pixels alike. */
private val TRACK_COLOR = Color.Black.copy(alpha = 0.4f)

/** The moving highlight, bright enough to be seen over dark pixels but not to outshine the picture. */
private val HIGHLIGHT_COLOR = Color.White.copy(alpha = 0.9f)