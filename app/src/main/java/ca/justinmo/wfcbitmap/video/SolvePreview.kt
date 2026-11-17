package ca.justinmo.wfcbitmap.video

/**
 * Plays the same frame sequence the Solve exporter writes.
 *
 * The exporter paints its frames from [SolveAnimationFrames]. This class plays those exact frames for the
 * preview, with the same forward/reverse ordering a GIF uses, so what the preview shows is the picture
 * sequence the file will contain -- not a second, approximate animation.
 *
 * It keeps two reusable frame buffers because a full frame is a couple of megabytes; alternating them
 * keeps the frame the GL thread or Compose is still reading from being overwritten by the next.
 */
class SolvePreview internal constructor(
  internal val frames: SolveAnimationFrames,
) {

  val width: Int get() = frames.width
  val height: Int get() = frames.height

  /** Frames in one forward pass; the preview and the exported file use the same count. */
  internal val steps: Int get() = frames.steps

  private val buffers = arrayOfNulls<IntArray>(2)
  private var nextBuffer = 0

  /** Bumped for every painted frame so a reused buffer still uploads. */
  private var sourceVersion = 0

  /**
   * The preview source for [sequenceIndex] of the clip's playback order.
   *
   * A GIF alternates a forward and a reverse pass, so its sequence is `2 * steps - 1` frames long: the
   * forward pass, then the reverse pass back to the opening frame. An MP4 keeps the forward pass only.
   * The index wraps, which is what lets the preview loop forever without the caller tracking the end.
   *
   * [gifLoop] must match the format selected on the export screen.
   */
  internal fun previewSource(
    sequenceIndex: Int,
    gifLoop: Boolean,
    cellPixels: Int,
  ): PreviewSource {
    val buffer = buffers[nextBuffer] ?: IntArray(width * height).also { buffers[nextBuffer] = it }
    nextBuffer = (nextBuffer + 1) % buffers.size
    val total = if (gifLoop) steps * 2 - 1 else steps
    val sequence = positiveMod(sequenceIndex, total)
    val forward = if (gifLoop && sequence >= steps) total - 1 - sequence else sequence
    frames.fillFrame(forward, buffer)
    return PreviewSource.fromFrame(
      pixels = buffer,
      width = width,
      height = height,
      cellPixels = cellPixels,
      version = ++sourceVersion,
      // The exporter lights a solve frame from its index in the clip; use the same phase here.
      phase = if (total <= 1) 0f else forward.toFloat() / total,
    )
  }
}
