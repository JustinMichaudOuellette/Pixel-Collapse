package ca.justinmo.wfcbitmap.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.PreviewGpu
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.PreviewCamera
import ca.justinmo.wfcbitmap.video.PreviewMaterial
import ca.justinmo.wfcbitmap.video.PreviewSource
import ca.justinmo.wfcbitmap.video.window
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The one preview surface for every shader.
 *
 * Flat and Metal share the same source texture, window/pan mapping, zoom/pan camera and GLSurfaceView.
 * The material only chooses which fragment program the GPU runs, so a gesture cannot behave differently
 * depending on the look and the two previews cannot drift apart.
 */
@Composable
internal fun PreviewSurface(
  source: PreviewSource?,
  material: PreviewMaterial,
  loopPixels: Float,
  modifier: Modifier = Modifier,
  maxZoom: Float = PreviewCamera.MAX_PREVIEW_ZOOM,
) {
  BoxWithConstraints(
    modifier = modifier.clipToBounds().background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    if (source == null) return@BoxWithConstraints
    val density = LocalDensity.current
    val boxWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val boxHeight = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
    val viewportAspect = boxWidth / boxHeight
    // Key the camera on the framing, not on the pixel array identity: a recomposition can hand in an
    // equal-sized source again, and that must not throw away the user's zoom. A change of shape or wrap
    // axis does reset it because the old offsets no longer mean the same thing.
    val sourceKey = listOf(
      source.width,
      source.height,
      source.frameWidth,
      source.frameHeight,
      source.loopLength,
      source.wrapX,
      source.wrapY,
      source.vertical,
    )

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

    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context -> PreviewGlView(context) },
      update = { view ->
        view.setScene(source, material, camera, loopPixels)
      },
    )

    val label = when (material) {
      is PreviewMaterial.Metal -> "Metal preview"
      PreviewMaterial.Flat -> "Video preview"
    }
    androidx.compose.foundation.layout.Box(
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

private class PreviewGlView(context: Context) : GLSurfaceView(context) {

  private val previewRenderer = PreviewRenderer()
  private var lastSource: PreviewSource? = null
  private var lastMaterial: PreviewMaterial? = null
  private var lastCamera: PreviewCamera? = null
  private var lastLoopPixels = Float.NaN

  init {
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
    preserveEGLContextOnPause = true
    setRenderer(previewRenderer)
    renderMode = RENDERMODE_CONTINUOUSLY
    // Compose draws its own background over a plain SurfaceView. This keeps the live GL surface above
    // the dialog's canvas, as the previous Metal-only preview did.
    setZOrderOnTop(true)
    setBackgroundColor(android.graphics.Color.BLACK)
  }

  fun setScene(
    source: PreviewSource,
    material: PreviewMaterial,
    camera: PreviewCamera,
    loopPixels: Float,
  ) {
    val sameSource = source.pixels === lastSource?.pixels &&
      source.width == lastSource?.width &&
      source.height == lastSource?.height &&
      source.frameWidth == lastSource?.frameWidth &&
      source.frameHeight == lastSource?.frameHeight &&
      source.wrapX == lastSource?.wrapX &&
      source.wrapY == lastSource?.wrapY
    if (sameSource && material == lastMaterial && camera == lastCamera &&
      loopPixels == lastLoopPixels
    ) {
      return
    }
    // The source arrays are immutable snapshots: the panorama builder returns a new array and the
    // single-frame preview is copied once off the generator's reusable bitmap. Copying them here would
    // allocate a megapixel buffer on every camera tick, so the renderer holds the caller's array.
    lastSource = source
    lastMaterial = material
    lastCamera = camera
    lastLoopPixels = loopPixels
    queueEvent { previewRenderer.setScene(source, material, camera, loopPixels) }
  }
}

private class PreviewRenderer : GLSurfaceView.Renderer {

  private var gpu: PreviewGpu? = null
  private var source: PreviewSource? = null
  private var material: PreviewMaterial = PreviewMaterial.Flat
  private var camera = PreviewCamera()
  private var loopPixels = 0f
  private var uploadedSrgb = false
  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private var lastFrameNanos = 0L
  private var elapsedNanos = 0L

  fun setScene(
    source: PreviewSource,
    material: PreviewMaterial,
    camera: PreviewCamera,
    loopPixels: Float,
  ) {
    val sourceChanged = this.source == null ||
      this.source!!.pixels !== source.pixels ||
      this.source!!.width != source.width ||
      this.source!!.height != source.height ||
      this.source!!.frameWidth != source.frameWidth ||
      this.source!!.frameHeight != source.frameHeight ||
      this.source!!.wrapX != source.wrapX ||
      this.source!!.wrapY != source.wrapY
    val materialChanged = this.material != material
    this.source = source
    this.material = material
    this.camera = camera
    this.loopPixels = loopPixels
    if (materialChanged && material is PreviewMaterial.Metal) {
      gpu?.ensureMetal()
    }
    if (sourceChanged || materialChanged) uploadSource()
  }

  private fun uploadSource() {
    val current = source ?: return
    val metal = material is PreviewMaterial.Metal
    gpu?.uploadAlbedo(current.pixels, current.width, current.height, srgb = metal)
    uploadedSrgb = metal
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    val created = PreviewGpu()
    val metal = material is PreviewMaterial.Metal
    if (created.init(includeMetal = metal)) {
      gpu = created
      uploadSource()
      Log.i("PreviewGl", "GLES preview ready (flat=${!metal})")
    } else {
      gpu = null
      Log.w("PreviewGl", "GLES preview unavailable: ${created.failure}")
    }
    elapsedNanos = 0L
    lastFrameNanos = System.nanoTime()
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)
    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glClearColor(0f, 0f, 0f, 1f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    val g = gpu ?: return
    val current = source ?: return
    if (current.width <= 0 || current.height <= 0) return

    val now = System.nanoTime()
    if (!camera.paused && lastFrameNanos != 0L) {
      elapsedNanos += (now - lastFrameNanos).coerceAtLeast(0L)
    }
    lastFrameNanos = now
    val elapsed = elapsedNanos / 1_000_000_000f
    val metal = material as? PreviewMaterial.Metal
    val settings = metal?.settings ?: MetalSettings()
    val turns = elapsed * 0.25f * settings.lightTurnsPerLoop
    val window = current.window(camera, loopPixels)
    val frame = MetalFrame(
      width = surfaceWidth,
      height = surfaceHeight,
      viewportAspect = surfaceWidth.toFloat() / surfaceHeight,
      imageAspect = window.imageAspect,
      uvOffsetX = window.uvOffsetX,
      uvOffsetY = window.uvOffsetY,
      uvScaleX = window.uvScaleX,
      uvScaleY = window.uvScaleY,
      embossScale = metal?.embossScale ?: 1f,
      previewScale = window.previewScale,
      previewOffsetX = window.previewOffsetX,
      previewOffsetY = window.previewOffsetY,
      wrapX = window.wrapX,
      wrapY = window.wrapY,
      stepTexels = current.cellPixels.toFloat(),
      turns = turns,
      settings = settings,
    )

    if (metal != null) {
      if (g.ensureMetal()) {
        g.draw(frame)
        return
      }
      // A device that can run the flat preview but not the metal program should still show something.
      // The texture was uploaded as sRGB for metal, so re-upload the bytes linearly for the fallback.
      if (uploadedSrgb) {
        g.uploadAlbedo(current.pixels, current.width, current.height, srgb = false)
        uploadedSrgb = false
      }
    }
    g.drawFlat(frame)
  }
}

