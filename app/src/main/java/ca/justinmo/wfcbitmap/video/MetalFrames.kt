package ca.justinmo.wfcbitmap.video

/**
 * Maps a loop frame onto the panorama slice and animation phase the metal shader should draw.
 *
 * A panorama is generated either horizontally (the ordinary pan) or with its axes swapped (a vertical
 * pan). The vertical plan is built by calling [videoPlan] with the two frame sides exchanged; before it
 * reaches the GPU the panorama is transposed back, so the shader always sees an upright surface and only
 * the crop window and wrap axis differ. Doing the transpose once on the whole panorama, rather than once
 * per frame, is what keeps the per-frame export GPU-only.
 *
 * [turns] advances by one whole orbit per loop when the light is animated, so the last frame is exactly
 * one orbit after the first and a GIF runs into itself with no light jump.
 */
internal fun panoramaMetalFrame(
  plan: VideoPlan,
  index: Int,
  settings: MetalSettings,
): MetalFrame {
  val vertical = plan.scrollDirection.isVertical
  val frameWidth = if (vertical) plan.frameHeight else plan.frameWidth
  val frameHeight = if (vertical) plan.frameWidth else plan.frameHeight
  val panoramaWidth = if (vertical) plan.panoramaHeight else plan.panoramaWidth
  val panoramaHeight = if (vertical) plan.panoramaWidth else plan.panoramaHeight
  val span = if (vertical) panoramaHeight else panoramaWidth
  val shifted = if (plan.frameCount <= 1) 0 else (index * plan.pixelsPerFrame) % span
  val reverse = plan.scrollDirection == ScrollDirection.RightToLeft ||
    plan.scrollDirection == ScrollDirection.Up
  val start = if (reverse && shifted != 0) span - shifted else shifted
  val scaleX = if (vertical) 1f else frameWidth.toFloat() / panoramaWidth
  val scaleY = if (vertical) frameHeight.toFloat() / panoramaHeight else 1f
  val turns = if (plan.frameCount <= 1) {
    0f
  } else {
    index.toFloat() / plan.frameCount * settings.lightTurnsPerLoop
  }
  return MetalFrame(
    width = plan.outputWidth,
    height = plan.outputHeight,
    viewportAspect = plan.outputWidth.toFloat() / plan.outputHeight,
    imageAspect = frameWidth.toFloat() / frameHeight,
    uvOffsetX = if (vertical) 0f else start.toFloat() / panoramaWidth,
    uvOffsetY = if (vertical) start.toFloat() / panoramaHeight else 0f,
    uvScaleX = scaleX,
    uvScaleY = scaleY,
    wrapX = !vertical,
    wrapY = vertical,
    stepTexels = plan.pixelsPerCell.toFloat().coerceAtLeast(1f),
    turns = turns,
    settings = settings,
  )
}

/** The same mapping for a standalone frame (Solve/Evolve), where the whole image is the surface. */
internal fun stillMetalFrame(
  width: Int,
  height: Int,
  cellPixels: Int,
  index: Int,
  frameCount: Int,
  settings: MetalSettings,
): MetalFrame = MetalFrame(
  width = width,
  height = height,
  viewportAspect = width.toFloat() / height,
  imageAspect = width.toFloat() / height,
  stepTexels = cellPixels.coerceAtLeast(1).toFloat(),
  turns = if (frameCount <= 1) {
    0f
  } else {
    index.toFloat() / frameCount * settings.lightTurnsPerLoop
  },
  settings = settings,
)
