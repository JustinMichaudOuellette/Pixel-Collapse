package ca.justinmo.wfcbitmap.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import ca.justinmo.wfcbitmap.video.PreviewCamera
import ca.justinmo.wfcbitmap.video.PreviewMaterial
import ca.justinmo.wfcbitmap.video.PreviewSource
import ca.justinmo.wfcbitmap.video.window
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The metal preview drawn straight into an OpenGL surface by a [TextureView].
 *
 * The exporter already renders with [MetalOffscreenRenderer]; this gives it a window surface that the
 * view hierarchy composites, so every preview frame stays on the GPU. Nothing is read back into a
 * `Bitmap` and there is no `SurfaceView` window layer: a `TextureView` is an ordinary View, which is what
 * makes it safe inside the export dialog where the old live `GLSurfaceView` was not.
 *
 * Rendering happens on its own thread because an EGL context is bound to the thread that created it. The
 * thread owns the [SurfaceTexture] and releases it after tearing down its EGL surface, so the UI thread
 * never joins a render thread that might be inside `eglSwapBuffers`.
 */
internal class MetalPreviewTexture(context: Context) :
  TextureView(context), TextureView.SurfaceTextureListener {

  private val renderer = MetalPreviewRenderer()

  @Volatile private var scene: Scene? = null
  @Volatile private var textureOwner: Thread? = null

  init {
    surfaceTextureListener = this
    isOpaque = true
  }

  fun setScene(
    source: PreviewSource,
    material: PreviewMaterial.Metal,
    camera: PreviewCamera,
    loopPixels: Float,
  ) {
    scene = Scene(source, material, camera, loopPixels)
  }

  override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
    textureOwner = renderer.start(texture, width, height) { scene }
  }

  override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
    renderer.resize(width, height)
  }

  override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
    val owner = textureOwner
    textureOwner = null
    renderer.stop()
    // A started render thread releases the texture itself once its EGL surface is gone; if none ever
    // started, the framework must do it.
    return owner == null
  }

  override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}

/** The frame the render thread should draw. */
private data class Scene(
  val source: PreviewSource,
  val material: PreviewMaterial.Metal,
  val camera: PreviewCamera,
  val loopPixels: Float,
)

private class MetalPreviewRenderer {

  @Volatile private var stopFlag = AtomicBoolean(true)
  @Volatile private var surfaceWidth = 1
  @Volatile private var surfaceHeight = 1

  /** Starts a render thread for [texture]; returns it so the view can hand the texture over on destroy. */
  fun start(texture: SurfaceTexture, width: Int, height: Int, scene: () -> Scene?): Thread {
    // A previous texture may still have a thread finishing; stop it before starting the next.
    stopFlag.set(true)
    surfaceWidth = width
    surfaceHeight = height
    val stop = AtomicBoolean(false)
    stopFlag = stop
    val thread = Thread({ run(texture, scene, stop) }, "metal-preview")
    thread.start()
    return thread
  }

  fun resize(width: Int, height: Int) {
    surfaceWidth = width
    surfaceHeight = height
  }

  fun stop() {
    stopFlag.set(true)
  }

  private fun run(texture: SurfaceTexture, scene: () -> Scene?, stop: AtomicBoolean) {
    val renderer = MetalOffscreenRenderer()
    val target = try {
      Surface(texture)
    } catch (e: Exception) {
      texture.release()
      return
    }
    try {
      if (!renderer.open()) return
      var uploadedPixels: IntArray? = null
      var uploadedVersion = Int.MIN_VALUE
      var uploadedWidth = 0
      var uploadedHeight = 0
      var lastScene: Scene? = null
      var lastWidth = 0
      var lastHeight = 0
      while (!stop.get()) {
        val current = scene()
        if (current == null) {
          Thread.sleep(8)
          continue
        }
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) {
          Thread.sleep(8)
          continue
        }
        // Nothing changed: sleep instead of re-rendering the same frame.
        if (current == lastScene && width == lastWidth && height == lastHeight) {
          Thread.sleep(4)
          continue
        }
        if (current.source.pixels !== uploadedPixels ||
          current.source.version != uploadedVersion ||
          current.source.width != uploadedWidth ||
          current.source.height != uploadedHeight
        ) {
          renderer.upload(current.source.pixels, current.source.width, current.source.height, srgb = true)
          uploadedPixels = current.source.pixels
          uploadedVersion = current.source.version
          uploadedWidth = current.source.width
          uploadedHeight = current.source.height
        }
        val window = current.source.window(current.camera, current.loopPixels)
        val settings = current.material.settings
        val frame = MetalFrame(
          width = width,
          height = height,
          viewportAspect = width.toFloat() / height,
          imageAspect = window.imageAspect,
          uvOffsetX = window.uvOffsetX,
          uvOffsetY = window.uvOffsetY,
          uvScaleX = window.uvScaleX,
          uvScaleY = window.uvScaleY,
          embossScale = current.material.embossScale,
          previewScale = window.previewScale,
          previewOffsetX = window.previewOffsetX,
          previewOffsetY = window.previewOffsetY,
          wrapX = window.wrapX,
          wrapY = window.wrapY,
          stepTexels = current.source.cellPixels.toFloat(),
          turns = (current.source.phase ?: 0f) * settings.lightTurnsPerLoop,
          settings = settings,
        )
        // Draws and presents to the TextureView's surface; eglSwapBuffers paces to the display.
        renderer.renderToSurface(frame, target, System.nanoTime())
        lastScene = current
        lastWidth = width
        lastHeight = height
      }
    } catch (_: Exception) {
      // A preview that cannot render must not take the screen down; the view simply stays blank.
    } finally {
      renderer.close()
      target.release()
      texture.release()
    }
  }
}
