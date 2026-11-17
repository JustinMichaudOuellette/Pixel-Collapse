package ca.justinmo.wfcbitmap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import ca.justinmo.wfcbitmap.video.PreviewCamera
import ca.justinmo.wfcbitmap.video.PreviewMaterial
import ca.justinmo.wfcbitmap.video.PreviewSource
import ca.justinmo.wfcbitmap.video.PreviewWindow
import ca.justinmo.wfcbitmap.video.window
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The one preview surface for the export screen.
 *
 * The flat look is drawn by Compose from the frame's own pixels. The metal look draws straight into a
 * [MetalPreviewTexture]'s OpenGL surface, so no frame is read back to the CPU. There is no live
 * `GLSurfaceView` here on purpose: a `SurfaceView`'s pixels live in a separate window layer, and its render
 * thread is joined on the UI thread when the dialog closes, which made the metal preview unreliable inside
 * the dialog. A `TextureView` is an ordinary composited View, so it has neither problem.
 */
@Composable
internal fun PreviewSurface(
  source: PreviewSource?,
  material: PreviewMaterial,
  loopPixels: Float,
  modifier: Modifier = Modifier,
  maxZoom: Float = PreviewCamera.MAX_PREVIEW_ZOOM,
) {
  if (source == null) return
  BoxWithConstraints(
    modifier = modifier.clipToBounds().background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    val density = LocalDensity.current
    val boxWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val boxHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
    val viewportAspect = boxWidth / boxHeight
    // Keep zoom/pan across a panorama rebuild, but not across the switch between a single frame and a
    // panorama: they have different coordinate systems, and carrying a paused or zoomed camera from the
    // Solve preview into Panorama made the next preview look frozen. The wrap flags are what distinguish
    // those two sources.
    val sourceKey = Triple(source.frameAspect, source.wrapX, source.wrapY)

    var camera by remember(sourceKey) { mutableStateOf(PreviewCamera()) }
    // Keep the current camera in the gesture lambda without rebuilding the transform state on every
    // pointer move.
    val currentCamera by rememberUpdatedState(camera)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
      camera = currentCamera.transformed(
        zoomChange = zoomChange,
        panPixelsX = panChange.x,
        panPixelsY = panChange.y,
        boxWidth = boxWidth,
        boxHeight = boxHeight,
        viewportAspect = viewportAspect,
        source = source,
        maxZoom = maxZoom,
      )
    }

    // The loop keeps playing at every zoom level. Zooming is a camera over the same moving window, not
    // a pause: freezing the loop here would show a static frame in the only place where the animation
    // is the thing being previewed.
    SideEffect {
      val clamped = camera.clamped(viewportAspect, source, maxZoom)
      if (clamped != camera) camera = clamped
    }

    if (material is PreviewMaterial.Metal) {
      MetalSurface(
        source = source,
        material = material,
        camera = camera,
        loopPixels = loopPixels,
      )
    } else {
      FlatSurface(source = source, camera = camera, loopPixels = loopPixels)
    }

    val label = when (material) {
      is PreviewMaterial.Metal -> "Metal preview"
      PreviewMaterial.Flat -> "Video preview"
    }
    Box(
      modifier = Modifier
        .fillMaxSize()
        .transformable(transformState)
        .clickable(
          interactionSource = interactionSource,
          indication = null,
        ) {
          if (material is PreviewMaterial.Metal) {
            camera = currentCamera.copy(paused = !currentCamera.paused)
          }
        }
        .semantics {
          contentDescription = label
          stateDescription = when {
            camera.paused -> "Paused"
            material is PreviewMaterial.Metal -> "Playing"
            else -> String.format(Locale.US, "Playing · Zoom %.1fx", camera.zoom)
          }
        },
    )
  }
}

/**
 * The flat look, drawn by Compose.
 *
 * The pixels are copied into one reusable bitmap on composition. [PreviewSource.version] is what says the
 * contents changed: the solve sweep alternates two source buffers, so the array identity alone would miss
 * every other frame. [FilterQuality.None] when magnified keeps the pixel art crisp, matching the GL
 * material's nearest-neighbour magnification.
 */
@Composable
private fun FlatSurface(
  source: PreviewSource,
  camera: PreviewCamera,
  loopPixels: Float,
) {
  val bitmap = remember(source.width, source.height) {
    Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
  }
  // Fills the reusable bitmap with the frame that is being shown right now.
  remember(bitmap, source.version, source.pixels) {
    bitmap.setPixels(source.pixels, 0, source.width, 0, 0, source.width, source.height)
  }
  val image = remember(bitmap, source.version, source.pixels) { bitmap.asImageBitmap() }
  Canvas(Modifier.fillMaxSize()) {
    val window = source.window(camera, loopPixels)
    drawFlatWindow(image, source, window, camera)
  }
}

/**
 * The metal look, drawn straight into a [MetalPreviewTexture]'s OpenGL surface.
 *
 * Every frame stays on the GPU: the exporter's renderer presents to the TextureView's surface, and the
 * view hierarchy composites it. A TextureView is an ordinary View, so this also avoids the `SurfaceView`
 * window layer and render-thread join that made the old live GL preview unreliable in the dialog.
 */
@Composable
private fun MetalSurface(
  source: PreviewSource,
  material: PreviewMaterial.Metal,
  camera: PreviewCamera,
  loopPixels: Float,
) {
  val context = LocalContext.current
  val view = remember { MetalPreviewTexture(context) }
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { view },
    update = { it.setScene(source, material, camera, loopPixels) },
  )
}

/**
 * Draws one window of [source] the way the flat material would: the frame fitted in the viewport, then
 * the camera's zoom and pan applied, with the frame wrapping at the source's ends.
 */
private fun DrawScope.drawFlatWindow(
  image: ImageBitmap,
  source: PreviewSource,
  window: PreviewWindow,
  camera: PreviewCamera,
) {
  if (source.width <= 0 || source.height <= 0) return
  val srcX = (window.uvOffsetX * source.width).roundToInt()
  val srcY = (window.uvOffsetY * source.height).roundToInt()
  val srcWidth = (window.uvScaleX * source.width).roundToInt().coerceIn(1, source.width)
  val srcHeight = (window.uvScaleY * source.height).roundToInt().coerceIn(1, source.height)
  if (srcWidth <= 0 || srcHeight <= 0) return

  val fit = min(size.width / srcWidth, size.height / srcHeight)
  val scale = fit * camera.zoom
  val destinationWidth = srcWidth * scale
  val destinationHeight = srcHeight * scale
  // Same mapping as the shader: screen = (uv - 0.5 - previewOffset) * rect + 0.5.
  val left = (size.width - destinationWidth) * 0.5f - camera.offsetX * destinationWidth
  val top = (size.height - destinationHeight) * 0.5f - camera.offsetY * destinationHeight

  val columns = windowSegments(srcX, srcWidth, source.width, window.wrapX)
  val rows = windowSegments(srcY, srcHeight, source.height, window.wrapY)
  val quality = if (scale >= 1f) FilterQuality.None else FilterQuality.Low
  for (row in rows) {
    for (column in columns) {
      val destinationX = left + destinationWidth * column.fraction
      val destinationY = top + destinationHeight * row.fraction
      val destinationW = destinationWidth * column.length / srcWidth
      val destinationH = destinationHeight * row.length / srcHeight
      drawImage(
        image = image,
        srcOffset = IntOffset(column.start, row.start),
        srcSize = IntSize(column.length, row.length),
        dstOffset = IntOffset(destinationX.roundToInt(), destinationY.roundToInt()),
        dstSize = IntSize(
          destinationW.roundToInt().coerceAtLeast(1),
          destinationH.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = quality,
      )
    }
  }
}

/**
 * Splits one window axis into the source slices that fill it.
 *
 * [fraction] is where the slice starts inside the window (0..1), so a window that runs off the end of the
 * source continues from the opposite edge instead of being clamped.
 */
private fun windowSegments(
  start: Int,
  length: Int,
  limit: Int,
  wrap: Boolean,
): List<WindowSegment> {
  if (length <= 0 || limit <= 0) return emptyList()
  if (!wrap) {
    val begin = start.coerceIn(0, limit - 1)
    val count = min(length, limit - begin)
    return listOf(WindowSegment(begin, count, 0f))
  }
  val first = min(length, limit - start)
  val segments = ArrayList<WindowSegment>(2)
  if (first > 0) segments.add(WindowSegment(start, first, 0f))
  if (first < length) {
    segments.add(WindowSegment(0, length - first, first.toFloat() / length))
  }
  return segments
}

private data class WindowSegment(val start: Int, val length: Int, val fraction: Float)
