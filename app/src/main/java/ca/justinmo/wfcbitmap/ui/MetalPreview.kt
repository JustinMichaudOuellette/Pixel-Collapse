package ca.justinmo.wfcbitmap.ui

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.MetalGpu
import ca.justinmo.wfcbitmap.video.MetalSettings
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A live metal preview on the export screen.
 *
 * This is deliberately a real GL surface rather than a baked thumbnail: the point of the look is that the
 * highlight travels, and a still image cannot show that. It runs the same [MetalGpu] as the exporter, so
 * what the preview shows is the material that will be written, not an approximation of it.
 */
@Composable
internal fun MetalPreview(
  pixels: IntArray?,
  width: Int,
  height: Int,
  cellPixels: Int,
  settings: MetalSettings,
  modifier: Modifier = Modifier,
) {
  AndroidView(
    modifier = modifier,
    factory = { context -> MetalPreviewView(context) },
    update = { view ->
      view.setImage(pixels, width, height, cellPixels)
      view.setSettings(settings)
    },
  )
}

private class MetalPreviewView(context: Context) : GLSurfaceView(context) {

  private val previewRenderer = MetalPreviewRenderer()
  private var lastSource: IntArray? = null
  private var lastWidth = -1
  private var lastHeight = -1
  private var lastCell = -1
  private var lastSettings: MetalSettings? = null

  init {
    setEGLContextClientVersion(3)
    setEGLConfigChooser(8, 8, 8, 8, 0, 0)
    preserveEGLContextOnPause = true
    setRenderer(previewRenderer)
    renderMode = RENDERMODE_CONTINUOUSLY
    // Compose draws its own background over a plain SurfaceView. A media overlay keeps the GL surface
    // above the dialog's canvas but below the window decorations, so the preview is visible without
    // floating over the rest of the screen the way setZOrderOnTop would.
    setZOrderOnTop(true)
    setBackgroundColor(android.graphics.Color.BLACK)
  }

  fun setImage(pixels: IntArray?, width: Int, height: Int, cellPixels: Int) {
    if (pixels == null || width <= 0 || height <= 0) return
    if (pixels === lastSource && width == lastWidth && height == lastHeight && cellPixels == lastCell) {
      return
    }
    lastSource = pixels
    lastWidth = width
    lastHeight = height
    lastCell = cellPixels
    val copy = pixels.copyOf()
    queueEvent { previewRenderer.setImage(copy, width, height, cellPixels) }
  }

  fun setSettings(settings: MetalSettings) {
    if (settings == lastSettings) return
    lastSettings = settings
    queueEvent { previewRenderer.setSettings(settings) }
  }
}

private class MetalPreviewRenderer : GLSurfaceView.Renderer {

  private var gpu: MetalGpu? = null
  private var pixels: IntArray? = null
  private var imageWidth = 0
  private var imageHeight = 0
  private var stepTexels = 1f
  private var settings = MetalSettings()
  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private var startedNanos = 0L

  fun setImage(pixels: IntArray, width: Int, height: Int, cellPixels: Int) {
    this.pixels = pixels
    imageWidth = width
    imageHeight = height
    stepTexels = cellPixels.coerceAtLeast(1).toFloat()
    gpu?.uploadAlbedo(pixels, width, height)
  }

  fun setSettings(settings: MetalSettings) {
    this.settings = settings.sanitised()
  }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    val created = MetalGpu()
    if (created.init()) {
      gpu = created
      pixels?.let { created.uploadAlbedo(it, imageWidth, imageHeight) }
      Log.i("MetalPreview", "GLES preview ready")
    } else {
      gpu = null
      Log.w("MetalPreview", "GLES preview unavailable: ${created.failure}")
    }
    startedNanos = System.nanoTime()
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)
    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
  }

  override fun onDrawFrame(gl: GL10?) {
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glClearColor(0f, 0f, 0f, 1f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    val g = gpu ?: return
    val image = pixels ?: return
    if (imageWidth <= 0 || imageHeight <= 0) return
    val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000f
    // One full orbit every four seconds at the default speed: fast enough to read as motion on a still
    // drawing, slow enough that the eye can follow a highlight across a single bevel.
    val turns = elapsed * 0.25f * settings.lightTurnsPerLoop
    g.draw(
      MetalFrame(
        width = surfaceWidth,
        height = surfaceHeight,
        viewportAspect = surfaceWidth.toFloat() / surfaceHeight,
        imageAspect = imageWidth.toFloat() / imageHeight,
        stepTexels = stepTexels,
        turns = turns,
        settings = settings,
      ),
    )
  }
}
