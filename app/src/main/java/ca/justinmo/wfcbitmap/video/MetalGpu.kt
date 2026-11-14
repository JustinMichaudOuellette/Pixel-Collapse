package ca.justinmo.wfcbitmap.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame's worth of renderer state: which slice of the panorama to look at, how the viewport is
 * shaped, and where the animation is.
 *
 * [turns] is measured in light orbits (one is a full trip around the plate); it is not wall-clock time,
 * so a looping export can put exactly one orbit in one loop and close seamlessly.
 */
internal data class MetalFrame(
  val width: Int,
  val height: Int,
  val viewportAspect: Float = width.toFloat() / height.toFloat(),
  val imageAspect: Float = viewportAspect,
  val uvOffsetX: Float = 0f,
  val uvOffsetY: Float = 0f,
  val uvScaleX: Float = 1f,
  val uvScaleY: Float = 1f,
  /** How much the target is enlarged beyond the source; used to keep the emboss from stiffening. */
  val embossScale: Float = 1f,
  val previewScale: Float = 1f,
  val previewOffsetX: Float = 0f,
  val previewOffsetY: Float = 0f,
  val wrapX: Boolean = false,
  val wrapY: Boolean = false,
  /** Height-gradient sample distance in source texels: one drawn cell for the pixel-art sources. */
  val stepTexels: Float = 1f,
  val turns: Float = 0f,
  val settings: MetalSettings = MetalSettings(),
)

/**
 * The GLES 3.0 half of the metal renderer: programs, the procedural environment, the BRDF LUT, the
 * source texture and a full-screen triangle.
 *
 * This class owns GL objects and therefore must be created, used and destroyed on the thread whose EGL
 * context is current. [MetalOffscreenRenderer] provides that context for an export; a `GLSurfaceView`
 * provides it for the live preview. There is deliberately no per-frame `glTexImage2D` of the environment
 * or the LUT: both are baked once in [init], and only the source texture and the uniforms change per
 * frame.
 */
/** The ten environment-light switches, packed so a rebake only happens when one of them changes. */
private fun MetalSettings.lightMask(): Int {
  var mask = 0
  if (skyEnabled) mask = mask or (1 shl 0)
  if (floorEnabled) mask = mask or (1 shl 1)
  if (horizonEnabled) mask = mask or (1 shl 2)
  if (floorBounceEnabled) mask = mask or (1 shl 3)
  if (frontPanelEnabled) mask = mask or (1 shl 4)
  if (bluePanelEnabled) mask = mask or (1 shl 5)
  if (amberPanelEnabled) mask = mask or (1 shl 6)
  if (keySoftboxEnabled) mask = mask or (1 shl 7)
  if (overheadPanelEnabled) mask = mask or (1 shl 8)
  if (smallSpot1Enabled) mask = mask or (1 shl 9)
  if (smallSpot2Enabled) mask = mask or (1 shl 10)
  if (smallSpot3Enabled) mask = mask or (1 shl 11)
  return mask
}

internal class MetalGpu {

  private var program = 0
  private var environmentProgram = 0
  private var brdfProgram = 0
  private var vao = 0

  private var albedoTex = 0
  private var albedoWidth = 0
  private var albedoHeight = 0
  private var environmentTex = 0
  private var brdfTex = 0
  private var bakedLightMask = -1

  private var targetTex = 0
  private var targetFbo = 0
  private var targetWidth = 0
  private var targetHeight = 0

  private var uploadBuffer: ByteBuffer? = null
  private var readBuffer: ByteBuffer? = null
  private val uniformCache = HashMap<String, Int>()

  /** Why [init] failed, or null when it succeeded. */
  var failure: String? = null
    private set

  /**
   * Builds every GL object. Returns false (with [failure] set) rather than throwing, so the preview can
   * quietly fall back and an export can report one readable line.
   */
  fun init(): Boolean {
    if (program != 0) return true
    return try {
      environmentProgram = link(MetalShader.VERTEX, MetalShader.ENVIRONMENT, "environment")
      brdfProgram = link(MetalShader.VERTEX, MetalShader.BRDF, "BRDF LUT")
      program = link(MetalShader.VERTEX, MetalShader.FRAGMENT, "metal")
      val ids = IntArray(1)
      GLES30.glGenVertexArrays(1, ids, 0)
      vao = ids[0]
      GLES30.glBindVertexArray(vao)
      bakeEnvironment()
      bakeBrdf()
      GLES30.glDisable(GLES30.GL_DEPTH_TEST)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glDisable(GLES30.GL_CULL_FACE)
      true
    } catch (e: Throwable) {
      failure = e.message ?: e::class.java.simpleName
      android.util.Log.w("MetalGpu", "Metal GLES init failed: $failure", e)
      release()
      false
    }
  }

  /** Uploads the source image. The size may change; the texture is reallocated only when it does. */
  fun uploadAlbedo(pixels: IntArray, width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    if (width * height > pixels.size) return
    if (albedoTex == 0) {
      val ids = IntArray(1)
      GLES30.glGenTextures(1, ids, 0)
      albedoTex = ids[0]
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, albedoTex)
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
    if (width != albedoWidth || height != albedoHeight) {
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, width, height, 0,
        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
      )
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
      albedoWidth = width
      albedoHeight = height
    }

    val bytes = width * height * 4
    var buffer = uploadBuffer
    if (buffer == null || buffer.capacity() < bytes) {
      buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
      uploadBuffer = buffer
    }
    buffer.clear()
    for (i in 0 until width * height) {
      val colour = pixels[i]
      buffer.put(((colour ushr 16) and 0xFF).toByte())
      buffer.put(((colour ushr 8) and 0xFF).toByte())
      buffer.put((colour and 0xFF).toByte())
      buffer.put(0xFF.toByte())
    }
    buffer.position(0)
    GLES30.glTexSubImage2D(
      GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
    )
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
  }

  /** Draws [frame] into the currently bound framebuffer, which must already have the right viewport. */
  fun draw(frame: MetalFrame) {
    if (program == 0) return
    val s = frame.settings
    if (s.lightMask() != bakedLightMask) bakeEnvironment(s)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glClearColor(0f, 0f, 0f, 1f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

    GLES30.glUseProgram(program)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, albedoTex)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, environmentTex)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, brdfTex)
    GLES30.glUniform1i(location("uAlbedo"), 0)
    GLES30.glUniform1i(location("uEnvironment"), 1)
    GLES30.glUniform1i(location("uBrdf"), 2)

    GLES30.glUniform2f(location("uTexSize"), albedoWidth.toFloat(), albedoHeight.toFloat())
    GLES30.glUniform2f(location("uUvOffset"), frame.uvOffsetX, frame.uvOffsetY)
    GLES30.glUniform2f(location("uUvScale"), frame.uvScaleX, frame.uvScaleY)
    GLES30.glUniform1f(location("uViewportAspect"), frame.viewportAspect)
    GLES30.glUniform1f(location("uImageAspect"), frame.imageAspect)
    GLES30.glUniform1f(location("uWrapX"), if (frame.wrapX) 1f else 0f)
    GLES30.glUniform1f(location("uWrapY"), if (frame.wrapY) 1f else 0f)
    GLES30.glUniform1f(location("uStepTexels"), frame.stepTexels)

    val base = s.baseColorLinear()
    val light = s.lightColorLinear()
    val lightPos = s.lightPosition(if (s.animateLight) frame.turns else 0f)
    GLES30.glUniform1f(location("uEmboss"), s.emboss * frame.embossScale)
    GLES30.glUniform1f(location("uMetallic"), s.metallic)
    GLES30.glUniform1f(location("uRoughness"), s.roughness)
    GLES30.glUniform1f(location("uTextureTint"), s.textureTint)
    GLES30.glUniform3f(location("uBaseColor"), base[0], base[1], base[2])
    GLES30.glUniform3f(location("uLightColor"), light[0], light[1], light[2])
    GLES30.glUniform1f(location("uLightIntensity"), s.lightIntensity)
    GLES30.glUniform3f(location("uLightPosition"), lightPos[0], lightPos[1], lightPos[2])
    GLES30.glUniform1f(location("uEnvironmentIntensity"), s.environmentIntensity)
    GLES30.glUniform1f(
      location("uEnvironmentRotation"),
      s.environmentRotation(if (s.animateLight) frame.turns else 0f),
    )
    GLES30.glUniform1f(location("uParallax"), s.parallax)
    GLES30.glUniform1f(location("uAnisotropy"), s.anisotropy)
    GLES30.glUniform1f(location("uBrushAngle"), s.brushAngleDegrees)
    GLES30.glUniform1f(location("uExposure"), s.exposure)
    GLES30.glUniform1f(location("uCameraDistance"), CAMERA_DISTANCE)
    GLES30.glUniform1f(location("uPreviewScale"), frame.previewScale)
    GLES30.glUniform2f(
      location("uPreviewOffset"),
      frame.previewOffsetX,
      frame.previewOffsetY,
    )
    GLES30.glUniform1f(location("uKeyLightOn"), if (s.keyLightEnabled) 1f else 0f)

    GLES30.glBindVertexArray(vao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindVertexArray(0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
  }

  /**
   * Renders [frame] offscreen and returns ARGB pixels. [out] is reused when it is large enough; encoders
   * consume it before the next call, so no per-frame pixel array has to be allocated.
   */
  fun render(frame: MetalFrame, out: IntArray): IntArray {
    ensureTarget(frame.width, frame.height)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
    GLES30.glViewport(0, 0, frame.width, frame.height)
    draw(frame)
    GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
    val bytes = frame.width * frame.height * 4
    var buffer = readBuffer
    if (buffer == null || buffer.capacity() < bytes) {
      buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
      readBuffer = buffer
    }
    buffer.position(0)
    GLES30.glReadPixels(
      0, 0, frame.width, frame.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer,
    )
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    buffer.position(0)

    val count = frame.width * frame.height
    val output = if (out.size == count) out else IntArray(count)
    // GL's origin is bottom-left; Android bitmaps are top-left. Flip once, here, not in the shader:
    // the shader's vScreen is already in bitmap orientation, so a second flip would put the drawing
    // upside down.
    for (row in 0 until frame.height) {
      val targetRow = (frame.height - 1 - row) * frame.width
      for (x in 0 until frame.width) {
        val r = buffer.get().toInt() and 0xFF
        val g = buffer.get().toInt() and 0xFF
        val b = buffer.get().toInt() and 0xFF
        buffer.get()
        output[targetRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    return output
  }

  /** Allocates or resizes the offscreen colour target. Never called for the on-screen preview. */
  private fun ensureTarget(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    if (targetFbo != 0 && width == targetWidth && height == targetHeight) return
    if (targetFbo == 0) {
      val ids = IntArray(1)
      GLES30.glGenFramebuffers(1, ids, 0)
      targetFbo = ids[0]
      GLES30.glGenTextures(1, ids, 0)
      targetTex = ids[0]
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, targetTex)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
      GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
    )
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, targetTex, 0,
    )
    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      throw IllegalStateException("Metal target framebuffer is incomplete (0x${status.toString(16)})")
    }
    targetWidth = width
    targetHeight = height
  }

  /**
   * Bakes the studio into an equirectangular texture, then builds its mip chain. The mip chain is the
   * prefiltered radiance: `textureLod(..., roughness * 8)` blurs it exactly as a rough surface should.
   */
  private fun bakeEnvironment(settings: MetalSettings = MetalSettings()) {
    val width = ENVIRONMENT_WIDTH
    val height = ENVIRONMENT_HEIGHT
    // A rebake happens inside draw(), so the caller's target framebuffer and viewport must survive it.
    val previousFbo = IntArray(1)
    val previousViewport = IntArray(4)
    GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previousFbo, 0)
    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, previousViewport, 0)
    if (environmentTex != 0) GLES30.glDeleteTextures(1, intArrayOf(environmentTex), 0)
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    environmentTex = ids[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, environmentTex)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
      GLES30.GL_LINEAR_MIPMAP_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

    // RGBA8 rather than RGBA16F: the mip chain is the roughness prefilter, and linear filtering plus
    // glGenerateMipmap on a half-float target is not guaranteed on every GLES 3.0 driver. The panels are
    // already near white, so the lost headroom is invisible; the mip chain being correct is not.
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
      GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
    )

    GLES30.glGenFramebuffers(1, ids, 0)
    val fbo = ids[0]
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, environmentTex, 0,
    )
    if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previousFbo[0])
      GLES30.glViewport(
        previousViewport[0], previousViewport[1],
        previousViewport[2], previousViewport[3],
      )
      GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
      throw IllegalStateException("The environment framebuffer is incomplete")
    }
    GLES30.glViewport(0, 0, width, height)
    GLES30.glUseProgram(environmentProgram)
    GLES30.glUniform2f(
      GLES30.glGetUniformLocation(environmentProgram, "uEnvSize"),
      width.toFloat(),
      height.toFloat(),
    )
    fun flag(name: String, on: Boolean) {
      GLES30.glUniform1f(GLES30.glGetUniformLocation(environmentProgram, name), if (on) 1f else 0f)
    }
    flag("uSkyOn", settings.skyEnabled)
    flag("uFloorOn", settings.floorEnabled)
    flag("uHorizonOn", settings.horizonEnabled)
    flag("uFloorBounceOn", settings.floorBounceEnabled)
    flag("uFrontPanelOn", settings.frontPanelEnabled)
    flag("uBluePanelOn", settings.bluePanelEnabled)
    flag("uAmberPanelOn", settings.amberPanelEnabled)
    flag("uKeySoftboxOn", settings.keySoftboxEnabled)
    flag("uOverheadOn", settings.overheadPanelEnabled)
    flag("uSmallSpot1On", settings.smallSpot1Enabled)
    flag("uSmallSpot2On", settings.smallSpot2Enabled)
    flag("uSmallSpot3On", settings.smallSpot3Enabled)
    GLES30.glBindVertexArray(vao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindVertexArray(0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, environmentTex)
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previousFbo[0])
    GLES30.glViewport(
      previousViewport[0], previousViewport[1],
      previousViewport[2], previousViewport[3],
    )
    bakedLightMask = settings.lightMask()
  }

  /** Builds the split-sum BRDF integral once. */
  private fun bakeBrdf() {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    brdfTex = ids[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, brdfTex)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, BRDF_SIZE, BRDF_SIZE, 0,
      GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
    )
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glGenFramebuffers(1, ids, 0)
    val fbo = ids[0]
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, brdfTex, 0,
    )
    if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
      throw IllegalStateException("The BRDF framebuffer is incomplete")
    }
    GLES30.glViewport(0, 0, BRDF_SIZE, BRDF_SIZE)
    GLES30.glUseProgram(brdfProgram)
    GLES30.glUniform1f(
      GLES30.glGetUniformLocation(brdfProgram, "uLutSize"),
      BRDF_SIZE.toFloat(),
    )
    GLES30.glBindVertexArray(vao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindVertexArray(0)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
  }

  /** Compiles and links one program, throwing the driver's own log if anything is wrong. */
  private fun link(vertexSource: String, fragmentSource: String, label: String): Int {
    val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$label vertex")
    val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$label fragment")
    val p = GLES30.glCreateProgram()
    GLES30.glAttachShader(p, vs)
    GLES30.glAttachShader(p, fs)
    GLES30.glLinkProgram(p)
    val status = IntArray(1)
    GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    if (status[0] == 0) {
      val log = GLES30.glGetProgramInfoLog(p)
      GLES30.glDeleteProgram(p)
      throw IllegalStateException("$label failed to link: $log")
    }
    return p
  }

  private fun compile(type: Int, source: String, label: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val status = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
      val log = GLES30.glGetShaderInfoLog(shader)
      GLES30.glDeleteShader(shader)
      throw IllegalStateException("$label failed to compile: $log")
    }
    return shader
  }

  /** Cached so a per-frame draw does not ask the driver for twenty-odd locations every frame. */
  private fun location(name: String): Int =
    uniformCache.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

  /** Releases every GL object. Must be called on the context's thread. */
  fun release() {
    if (program != 0) GLES30.glDeleteProgram(program)
    if (environmentProgram != 0) GLES30.glDeleteProgram(environmentProgram)
    if (brdfProgram != 0) GLES30.glDeleteProgram(brdfProgram)
    program = 0
    environmentProgram = 0
    brdfProgram = 0
    uniformCache.clear()
    if (albedoTex != 0) GLES30.glDeleteTextures(1, intArrayOf(albedoTex), 0)
    if (environmentTex != 0) GLES30.glDeleteTextures(1, intArrayOf(environmentTex), 0)
    if (brdfTex != 0) GLES30.glDeleteTextures(1, intArrayOf(brdfTex), 0)
    if (targetTex != 0) GLES30.glDeleteTextures(1, intArrayOf(targetTex), 0)
    if (targetFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(targetFbo), 0)
    if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    albedoTex = 0
    environmentTex = 0
    brdfTex = 0
    targetTex = 0
    targetFbo = 0
    vao = 0
    targetWidth = 0
    targetHeight = 0
    albedoWidth = 0
    albedoHeight = 0
  }

  private companion object {
    const val ENVIRONMENT_WIDTH = 1024
    const val ENVIRONMENT_HEIGHT = 512
    const val BRDF_SIZE = 128
    const val CAMERA_DISTANCE = 2.6f
  }
}

/**
 * An EGL pbuffer + GLES 3.0 context for rendering frames without a window.
 *
 * Everything is created on the calling thread and must be used and closed on it. A 1x1 pbuffer is enough
 * because the renderer draws into its own framebuffer texture; the pbuffer only exists to satisfy EGL's
 * requirement for a current surface. If the device has no usable GLES 3.0 configuration, [open] returns
 * false and the exporter falls back to the CPU shader instead of crashing.
 */
internal class MetalOffscreenRenderer : AutoCloseable {

  private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var context: EGLContext = EGL14.EGL_NO_CONTEXT
  private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var config: EGLConfig? = null
  private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var windowTarget: Surface? = null
  private var gpu: MetalGpu? = null

  var failure: String? = null
    private set

  fun open(): Boolean {
    if (gpu != null) return true
    return try {
      display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
      if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display")
      val version = IntArray(2)
      if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
        throw IllegalStateException("eglInitialize failed")
      }
      val configs = arrayOfNulls<EGLConfig>(1)
      val configCount = IntArray(1)
      val configAttribs = intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        // The pbuffer is enough for offscreen rendering; WINDOW_BIT is needed for the direct-to-encoder
        // path, where the GPU draws into MediaCodec's input Surface instead.
        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_NONE,
      )
      if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, configCount, 0) ||
        configCount[0] == 0
      ) {
        throw IllegalStateException("No GLES 3.0 EGL config")
      }
      val config = configs[0] ?: throw IllegalStateException("No EGL config")
      this.config = config
      context = EGL14.eglCreateContext(
        display, config, EGL14.EGL_NO_CONTEXT,
        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
      )
      if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
      surface = EGL14.eglCreatePbufferSurface(
        display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
      )
      if (surface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreatePbufferSurface failed")
      if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
        throw IllegalStateException("eglMakeCurrent failed")
      }
      val g = MetalGpu()
      if (!g.init()) throw IllegalStateException(g.failure ?: "GLES initialisation failed")
      gpu = g
      true
    } catch (e: Throwable) {
      failure = e.message ?: e::class.java.simpleName
      android.util.Log.w("MetalGpu", "EGL/GLES open failed: $failure", e)
      close()
      false
    }
  }

  /**
   * True when the EGL implementation can put a presentation time on a window-surface frame. Without it
   * MediaCodec would timestamp frames by wall clock, so the direct-to-encoder path is only used when
   * this is available; the caller then falls back to the readback encoder.
   */
  fun supportsWindowSurface(): Boolean {
    if (display == EGL14.EGL_NO_DISPLAY) return false
    val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS) ?: return false
    return extensions.split(' ').contains("EGL_ANDROID_presentation_time")
  }

  /**
   * Renders [frame] into MediaCodec's input [target] and swaps it.
   *
   * No pixel ever leaves the GPU: this is the fast export path. The EGL window surface is cached per
   * target Surface; the codec keeps the same input Surface for the whole clip.
   */
  fun renderToSurface(frame: MetalFrame, target: Surface, presentationTimeNanos: Long): Boolean {
    if (!attachWindowSurface(target)) return false
    if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) return false
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, frame.width, frame.height)
    gpu?.draw(frame) ?: return false
    EGLExt.eglPresentationTimeANDROID(display, windowSurface, presentationTimeNanos)
    return EGL14.eglSwapBuffers(display, windowSurface)
  }

  private fun attachWindowSurface(target: Surface): Boolean {
    if (windowSurface != EGL14.EGL_NO_SURFACE && windowTarget === target) return true
    detachWindowSurface()
    val cfg = config ?: return false
    windowSurface = EGL14.eglCreateWindowSurface(
      display, cfg, target, intArrayOf(EGL14.EGL_NONE), 0,
    )
    if (windowSurface == EGL14.EGL_NO_SURFACE) return false
    windowTarget = target
    if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
      detachWindowSurface()
      return false
    }
    return true
  }

  private fun detachWindowSurface() {
    if (windowSurface != EGL14.EGL_NO_SURFACE) {
      // A surface cannot be destroyed while it is current; the pbuffer is the safe replacement.
      if (surface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglMakeCurrent(display, surface, surface, context)
      }
      EGL14.eglDestroySurface(display, windowSurface)
    }
    windowSurface = EGL14.EGL_NO_SURFACE
    windowTarget = null
  }

  fun upload(pixels: IntArray, width: Int, height: Int) {
    gpu?.uploadAlbedo(pixels, width, height)
  }

  fun render(frame: MetalFrame, out: IntArray): IntArray? = gpu?.render(frame, out)

  override fun close() {
    gpu?.release()
    gpu = null
    if (display != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglMakeCurrent(
        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
      )
      if (windowSurface != EGL14.EGL_NO_SURFACE) {
        EGL14.eglDestroySurface(display, windowSurface)
      }
      if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
      EGL14.eglTerminate(display)
    }
    surface = EGL14.EGL_NO_SURFACE
    windowSurface = EGL14.EGL_NO_SURFACE
    windowTarget = null
    config = null
    context = EGL14.EGL_NO_CONTEXT
    display = EGL14.EGL_NO_DISPLAY
  }

  private companion object {
    /** `EGL_OPENGL_ES3_BIT_KHR`; EGL14 does not expose the token on every SDK. */
    const val EGL_OPENGL_ES3_BIT = 0x40
  }
}
