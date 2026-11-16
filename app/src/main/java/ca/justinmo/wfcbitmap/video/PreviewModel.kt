package ca.justinmo.wfcbitmap.video

import kotlin.math.floor

/**
 * What the preview is looking at.
 *
 * [pixels] is always upright: for a vertical loop the caller supplies the transposed panorama. [width] and
 * [height] are the full source surface; [frameWidth] and [frameHeight] are the viewport the export cuts.
 * [loopLength] is the distance the window walks before the loop repeats, measured along the wrap axis.
 */
internal data class PreviewSource(
  val pixels: IntArray,
  val width: Int,
  val height: Int,
  val frameWidth: Int,
  val frameHeight: Int,
  val loopLength: Int,
  val wrapX: Boolean,
  val wrapY: Boolean,
  val vertical: Boolean,
  val cellPixels: Int,
) {
  val frameAspect: Float get() = frameWidth.toFloat() / frameHeight.coerceAtLeast(1)

  companion object {
    fun fromPanorama(panorama: VideoPanorama, cellPixels: Int): PreviewSource =
      PreviewSource(
        pixels = panorama.previewPixels,
        width = panorama.previewWidth,
        height = panorama.previewHeight,
        frameWidth = panorama.previewFrameWidth,
        frameHeight = panorama.previewFrameHeight,
        loopLength = if (panorama.vertical) panorama.previewHeight else panorama.previewWidth,
        wrapX = !panorama.vertical,
        wrapY = panorama.vertical,
        vertical = panorama.vertical,
        cellPixels = cellPixels.coerceAtLeast(1),
      )

    fun fromFrame(pixels: IntArray, width: Int, height: Int, cellPixels: Int): PreviewSource =
      PreviewSource(
        pixels = pixels,
        width = width,
        height = height,
        frameWidth = width,
        frameHeight = height,
        loopLength = width,
        wrapX = false,
        wrapY = false,
        vertical = false,
        cellPixels = cellPixels.coerceAtLeast(1),
      )
  }
}

/**
 * The camera over a preview source. It is deliberately independent of GL and Compose so the gesture and
 * clamp rules can be unit-tested.
 */
internal data class PreviewCamera(
  val zoom: Float = 1f,
  val offsetX: Float = 0f,
  val offsetY: Float = 0f,
  val paused: Boolean = false,
) {
  val isFitted: Boolean get() = zoom <= 1.001f

  /**
   * Applies one pinch/drag step.
   *
   * [boxWidth] and [boxHeight] are the on-screen preview size in pixels. The offsets are in source-UV
   * units, which is also what the shader's preview offset uses.
   */
  fun transformed(
    zoomChange: Float,
    panPixelsX: Float,
    panPixelsY: Float,
    boxWidth: Float,
    boxHeight: Float,
    viewportAspect: Float,
    source: PreviewSource,
    maxZoom: Float = MAX_PREVIEW_ZOOM,
  ): PreviewCamera {
    val nextZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
    val (baseWidth, baseHeight) = baseFit(viewportAspect, source)
    val rectWidth = (baseWidth * nextZoom).coerceAtLeast(0.0001f)
    val rectHeight = (baseHeight * nextZoom).coerceAtLeast(0.0001f)
    val nextX = offsetX - panPixelsX / boxWidth.coerceAtLeast(1f) / rectWidth
    val nextY = offsetY - panPixelsY / boxHeight.coerceAtLeast(1f) / rectHeight
    return copy(zoom = nextZoom, offsetX = nextX, offsetY = nextY).clamped(
      viewportAspect = viewportAspect,
      source = source,
      maxZoom = maxZoom,
    )
  }

  /** Brings zoom and offsets back inside the source, e.g. after a rotation or a new source. */
  fun clamped(
    viewportAspect: Float,
    source: PreviewSource,
    maxZoom: Float = MAX_PREVIEW_ZOOM,
  ): PreviewCamera {
    val safeZoom = zoom.coerceIn(1f, maxZoom)
    val (baseWidth, baseHeight) = baseFit(viewportAspect, source)
    val rectWidth = (baseWidth * safeZoom).coerceAtLeast(0.0001f)
    val rectHeight = (baseHeight * safeZoom).coerceAtLeast(0.0001f)
    val maxOffsetX = ((1f - 1f / rectWidth) * 0.5f).coerceAtLeast(0f)
    val maxOffsetY = ((1f - 1f / rectHeight) * 0.5f).coerceAtLeast(0f)
    return copy(
      zoom = safeZoom,
      offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX),
      offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY),
    )
  }

  private fun baseFit(viewportAspect: Float, source: PreviewSource): Pair<Float, Float> {
    val frameAspect = source.frameAspect
    return if (viewportAspect > frameAspect) {
      // Pillarboxed: full height, narrower width.
      frameAspect / viewportAspect to 1f
    } else {
      // Letterboxed: full width, shorter height.
      1f to viewportAspect / frameAspect
    }
  }

  companion object {
    const val MAX_PREVIEW_ZOOM = 8f
  }
}

/** The material on the preview surface. Flat is a direct texture pass; Metal is the PBR shader. */
internal sealed interface PreviewMaterial {
  data object Flat : PreviewMaterial
  data class Metal(val settings: MetalSettings, val embossScale: Float = 1f) : PreviewMaterial
}

/**
 * The uniforms a preview draw needs, independent of which material is selected.
 *
 * [loopPixels] is the position of the window along the panorama in source pixels; for a single frame it
 * is ignored. The mapping is shared by the flat and metal programs, which is what makes their framing
 * and zoom identical.
 */
internal data class PreviewWindow(
  val uvOffsetX: Float,
  val uvOffsetY: Float,
  val uvScaleX: Float,
  val uvScaleY: Float,
  val wrapX: Boolean,
  val wrapY: Boolean,
  val imageAspect: Float,
  val previewScale: Float,
  val previewOffsetX: Float,
  val previewOffsetY: Float,
)

internal fun PreviewSource.window(camera: PreviewCamera, loopPixels: Float): PreviewWindow {
  val startX = if (wrapX) positiveMod(floor(loopPixels).toInt(), width) else 0
  val startY = if (wrapY) positiveMod(floor(loopPixels).toInt(), height) else 0
  return PreviewWindow(
    uvOffsetX = if (wrapX) startX.toFloat() / width.coerceAtLeast(1) else 0f,
    uvOffsetY = if (wrapY) startY.toFloat() / height.coerceAtLeast(1) else 0f,
    uvScaleX = frameWidth.toFloat() / width.coerceAtLeast(1),
    uvScaleY = frameHeight.toFloat() / height.coerceAtLeast(1),
    wrapX = wrapX,
    wrapY = wrapY,
    imageAspect = frameAspect,
    previewScale = camera.zoom,
    previewOffsetX = camera.offsetX,
    previewOffsetY = camera.offsetY,
  )
}

internal fun positiveMod(value: Int, limit: Int): Int {
  if (limit <= 0) return 0
  val remainder = value % limit
  return if (remainder < 0) remainder + limit else remainder
}
