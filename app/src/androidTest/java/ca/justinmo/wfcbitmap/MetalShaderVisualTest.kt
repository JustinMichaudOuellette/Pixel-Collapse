package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.video.MetalFinish
import ca.justinmo.wfcbitmap.video.MetalFrame
import ca.justinmo.wfcbitmap.video.MetalOffscreenRenderer
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.MAX_CAMERA_PERSPECTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The real GPU test: renders the shader on the device and writes PNGs that a person can look at.
 *
 * It also asserts the differences the task is about, so a shader that compiles but paints a flat tinted
 * rectangle fails rather than merely looking wrong. The images land in the app's external files:
 * `/sdcard/Android/data/ca.justinmo.wfcbitmap/files/metal-tests/`.
 */
@RunWith(AndroidJUnit4::class)
class MetalShaderVisualTest {

  private val width = 288
  private val height = 288
  private val cell = 32

  @Test
  fun theMetalShaderIsEmbossedLitAndResponsive() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "metal-tests")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw AssertionError("Could not create ${directory.absolutePath}")
    }

    val renderer = MetalOffscreenRenderer()
    val opened = renderer.open()
    assertTrue("GLES renderer did not open: ${renderer.failure}", opened)
    try {
      val pattern = patternTexture()
      val gradient = gradientTexture()
      val stripes = stripesTexture()
      val out = IntArray(width * height)
      val base = MetalSettings(
        animateLight = false,
        lightAzimuthDegrees = 28f,
        lightElevationDegrees = 38f,
        emboss = 1.3f,
      )

      // Test A — static lighting: the embossed relief must have contrast across the picture.
      val staticChrome = render(renderer, pattern, base, turns = 0f, out)
      save(directory, "A-static-chrome", staticChrome)
      val staticSpread = deviation(staticChrome)
      Log.i(TAG, "A static chrome: mean=${mean(staticChrome)} spread=$staticSpread")
      assertTrue("A static chrome is flat (spread $staticSpread)", staticSpread > 9.0)

      // Every light is switchable. Turning them all off must leave a visibly different, darker frame.
      val noLights = base.copy(
        keyLightEnabled = false,
        skyEnabled = false,
        floorEnabled = false,
        horizonEnabled = false,
        floorBounceEnabled = false,
        frontPanelEnabled = false,
        bluePanelEnabled = false,
        amberPanelEnabled = false,
        keySoftboxEnabled = false,
        overheadPanelEnabled = false,
        smallSpot1Enabled = false,
        smallSpot2Enabled = false,
        smallSpot3Enabled = false,
      )
      val dark = render(renderer, pattern, noLights, turns = 0f, out)
      save(directory, "G-lights-off", dark)
      val allLightsDelta = difference(staticChrome, dark)
      Log.i(TAG, "G all lights off: mean-abs-difference=$allLightsDelta")
      assertTrue(
        "Switching every light off did not change the frame ($allLightsDelta)",
        allLightsDelta > 5.0,
      )



      // Camera projection: orthographic at 0 and the perspective camera at 1 must differ at the edges.
      val orthographic = render(renderer, pattern, base.copy(cameraPerspective = 0f), 0f, out)
      val perspective = render(renderer, pattern, base.copy(cameraPerspective = 1f), 0f, out)
      save(directory, "A-orthographic", orthographic)
      save(directory, "A-perspective", perspective)
      val projectionDelta = difference(orthographic, perspective)
      Log.i(TAG, "A camera projection: mean-abs-difference=$projectionDelta")
      assertTrue(
        "Orthographic and perspective cameras look the same ($projectionDelta)",
        projectionDelta > 0.5,
      )

      // Test B — moving light: the same surface at two points in the orbit must not be identical.
      val animated = base.copy(animateLight = true, lightTurnsPerLoop = 1f)
      val atZero = render(renderer, pattern, animated, turns = 0.0f, out)
      val atHalf = render(renderer, pattern, animated, turns = 0.5f, out)
      save(directory, "B-light-000", atZero)
      save(directory, "B-light-180", atHalf)
      val lightDelta = difference(atZero, atHalf)
      Log.i(TAG, "B light travel: mean-abs-difference=$lightDelta")
      assertTrue("The moving light did not change the frame ($lightDelta)", lightDelta > 2.0)

      // Test C — roughness: 0.05 / 0.2 / 0.5 must look visibly different.
      val mirror = render(renderer, pattern, base.copy(roughness = 0.05f), 0f, out)
      val satin = render(renderer, pattern, base.copy(roughness = 0.2f), 0f, out)
      val rough = render(renderer, pattern, base.copy(roughness = 0.5f), 0f, out)
      save(directory, "C-roughness-005", mirror)
      save(directory, "C-roughness-020", satin)
      save(directory, "C-roughness-050", rough)
      val mirrorToRough = difference(mirror, rough)
      val mirrorToSatin = difference(mirror, satin)
      Log.i(TAG, "C roughness deltas: 0.05->0.20=$mirrorToSatin 0.05->0.50=$mirrorToRough")
      assertTrue("Roughness 0.05 and 0.5 look the same ($mirrorToRough)", mirrorToRough > 4.0)
      assertTrue("Roughness 0.05 and 0.2 look the same ($mirrorToSatin)", mirrorToSatin > 0.5)

      // Test D — chrome, gold, copper: different F0 must give different reflected colour.
      val chrome = render(renderer, pattern, base.withFinish(MetalFinish.Chrome), 0f, out)
      val gold = render(renderer, pattern, base.withFinish(MetalFinish.Gold), 0f, out)
      val copper = render(renderer, pattern, base.withFinish(MetalFinish.Copper), 0f, out)
      save(directory, "D-chrome", chrome)
      save(directory, "D-gold", gold)
      save(directory, "D-copper", copper)
      val chromeGold = difference(chrome, gold)
      val goldCopper = difference(gold, copper)
      val brushed = render(renderer, pattern, base.withFinish(MetalFinish.Brushed), 0f, out)
      save(directory, "D-brushed", brushed)
      val chromeBrushed = difference(chrome, brushed)
      Log.i(
        TAG,
        "D material deltas: chrome-gold=$chromeGold gold-copper=$goldCopper " +
          "chrome-brushed=$chromeBrushed",
      )
      assertTrue("Gold is not distinguishable from chrome ($chromeGold)", chromeGold > 8.0)
      assertTrue("Copper is not distinguishable from gold ($goldCopper)", goldCopper > 4.0)
      assertTrue("Brushed steel is not distinguishable from chrome ($chromeBrushed)", chromeBrushed > 4.0)

      // Environment rotation must change what the mirror shows, not only the light.
      val turned = render(renderer, pattern, base.copy(environmentRotationDegrees = 180f), 0f, out)
      save(directory, "F-environment-rotated", turned)
      val rotationDelta = difference(chrome, turned)
      Log.i(TAG, "F environment rotation delta=$rotationDelta")
      assertTrue("Environment rotation did nothing ($rotationDelta)", rotationDelta > 2.0)

      // Test E — different inputs: an arbitrary picture must emboss too, not only the test motif.
      renderer.upload(gradient, width, height)
      val gradientMetal = render(renderer, gradient, base, 0f, out)
      save(directory, "E-gradient", gradientMetal)
      renderer.upload(stripes, width, height)
      val stripesMetal = render(renderer, stripes, base, 0f, out)
      save(directory, "E-stripes", stripesMetal)
      Log.i(TAG, "E texture spreads: gradient=${deviation(gradientMetal)} stripes=${deviation(stripesMetal)}")
      assertTrue("The gradient input is flat (${deviation(gradientMetal)})", deviation(gradientMetal) > 4.0)
      assertTrue("The stripe input is flat (${deviation(stripesMetal)})", deviation(stripesMetal) > 9.0)
      assertTrue(
        "Different inputs produced identical metal",
        difference(gradientMetal, stripesMetal) > 5.0,
      )
    } finally {
      renderer.close()
    }
  }

  /**
   * Coverage, on the real GPU: at every point of the perspective slider the quad must cover the whole
   * viewport — no background along any edge or in any corner — while staying centred and keeping the picture
   * undistorted.
   */
  @Test
  fun theQuadCoversTheWholeViewportAtEveryPerspective() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "metal-tests")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw AssertionError("Could not create ${directory.absolutePath}")
    }

    val renderer = MetalOffscreenRenderer()
    assertTrue("GLES renderer did not open: ${renderer.failure}", renderer.open())
    try {
      val out = IntArray(width * height)
      val base = MetalSettings(animateLight = false, emboss = 1.3f)
      val pattern = patternTexture()

      // The validation ladder: orthographic, then 25/50/75/100 percent of the slider.
      val frames = LinkedHashMap<Float, IntArray>()
      for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
        val frame = render(
          renderer,
          pattern,
          base.copy(cameraPerspective = MAX_CAMERA_PERSPECTIVE * fraction),
          0f,
          out,
        )
        frames[fraction] = frame
        save(directory, "H-coverage-${(fraction * 100).toInt()}", frame)

        val bounds = litBounds(frame)
        Log.i(TAG, "H coverage at ${(fraction * 100).toInt()}%: bounds=${bounds.toList()}")
        assertTrue(
          "background visible at ${(fraction * 100).toInt()}% (${bounds.toList()})",
          coversFrame(bounds),
        )
        // Centred: the lit box's centre is the frame's centre.
        assertEquals((width - 1) / 2f, (bounds[0] + bounds[2]) / 2f, 1.5f)
        assertEquals((height - 1) / 2f, (bounds[1] + bounds[3]) / 2f, 1.5f)
      }

      // The picture is not shifted about: the quad is only ever scaled uniformly and its UVs are fixed
      // (asserted in MetalCameraTest), so the centre of the drawing must land on the centre pixel.
      val flatRow = frames.getValue(0f)
      val deepRow = frames.getValue(1f)
      val centreColumn = (height / 2) * width + width / 2
      val drift = difference(intArrayOf(flatRow[centreColumn]), intArrayOf(deepRow[centreColumn]))
      Log.i(TAG, "H centre drift across the whole slider: $drift")
      assertTrue("the centre of the picture moved ($drift)", drift < 6.0)
    } finally {
      renderer.close()
    }
  }

  /**
   * The perspective must be visible as geometry, not as shading. A checkerboard makes it unmistakable: equal
   * cells of the picture cover less screen towards the wall's rim, so the outermost cells are narrower than
   * the middle ones and the boundaries pack towards the edges.
   */
  @Test
  fun thePerspectiveForeshortensTheTexture() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "metal-tests")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw AssertionError("Could not create ${directory.absolutePath}")
    }

    val renderer = MetalOffscreenRenderer()
    assertTrue("GLES renderer did not open: ${renderer.failure}", renderer.open())
    try {
      val out = IntArray(width * height)
      val base = MetalSettings(animateLight = false, emboss = 0f)
      val board = checkerboard()
      val flat = render(renderer, board, base.copy(cameraPerspective = 0f), 0f, out).copyOf()
      val deep = render(
        renderer,
        board,
        base.copy(cameraPerspective = MAX_CAMERA_PERSPECTIVE),
        0f,
        out,
      ).copyOf()
      save(directory, "H-checker-flat", flat)
      save(directory, "H-checker-perspective", deep)

      // The frame is covered at both ends.
      assertTrue("flat leaves the frame", coversFrame(litBounds(flat)))
      assertTrue("perspective leaves the frame", coversFrame(litBounds(deep)))

      val row = height / 2
      val flatEdges = checkerEdges(flat, row)
      val deepEdges = checkerEdges(deep, row)
      // The cell that straddles the middle of the frame, and the width of the first cell at the frame's left
      // edge (the wall's own border is outside the frame, so that cell is cut by the frame edge).
      val flatCentre = widthAt(flatEdges, width / 2)
      val deepCentre = widthAt(deepEdges, width / 2)
      val flatRim = flatEdges.firstOrNull() ?: -1
      val deepRim = deepEdges.firstOrNull() ?: -1
      Log.i(TAG, "H checker cell widths flat centre=$flatCentre rim=$flatRim")
      Log.i(TAG, "H checker cell widths perspective centre=$deepCentre rim=$deepRim")
      assertTrue("no checkerboard resolved", flatCentre > 0 && deepCentre > 0)
      assertTrue("no checkerboard resolved", flatRim > 0 && deepRim > 0)

      // A flat wall draws the edge cell and the middle cell the same width ...
      assertTrue(
        "the flat wall is not uniform (rim $flatRim, centre $flatCentre)",
        abs(flatRim - flatCentre) <= flatCentre * 0.25f,
      )
      // ... and the curved one magnifies the middle while the rim's cell is squeezed to a fraction of it:
      // that difference is the foreshortening.
      assertTrue("the middle did not grow ($flatCentre -> $deepCentre)", deepCentre > flatCentre * 1.2f)
      assertTrue(
        "no foreshortening at the rim (rim $deepRim, centre $deepCentre)",
        deepRim < flatRim * 0.8f,
      )
    } finally {
      renderer.close()
    }
  }

  /** The x positions along [row] where the checkerboard changes colour. */
  private fun checkerEdges(pixels: IntArray, row: Int): IntArray {
    val edges = ArrayList<Int>()
    for (x in 1 until width) {
      val a = luminance(pixels[row * width + x - 1])
      val b = luminance(pixels[row * width + x])
      if (abs(b - a) >= 40) edges.add(x)
    }
    return edges.toIntArray()
  }

  /** The width of the checker cell that straddles [x], or -1 when [x] is outside the board. */
  private fun widthAt(edges: IntArray, x: Int): Int {
    val left = edges.lastOrNull { it <= x } ?: return -1
    val right = edges.firstOrNull { it > x } ?: return -1
    return right - left
  }

  private fun luminance(pixel: Int): Int {
    val r = (pixel ushr 16) and 0xFF
    val g = (pixel ushr 8) and 0xFF
    val b = pixel and 0xFF
    return (r * 30 + g * 59 + b * 11) / 100
  }

  /** A white/near-black checkerboard, so any geometric distortion is obvious. */
  private fun checkerboard(): IntArray = IntArray(width * height) { index ->
    val x = index % width / cell
    val y = index / width / cell
    if ((x + y) % 2 == 0) 0xFFF2F4F8.toInt() else 0xFF101014.toInt()
  }

  /** True when the lit box reaches all four frame edges, allowing a pixel of rasterisation slop. */
  private fun coversFrame(bounds: IntArray): Boolean =
    bounds[0] <= 1 && bounds[1] <= 1 && bounds[2] >= width - 2 && bounds[3] >= height - 2

  /** The bounding box of the pixels that are not background, as left, top, right, bottom. */
  private fun litBounds(pixels: IntArray): IntArray {
    var left = width
    var right = -1
    var top = height
    var bottom = -1
    for (y in 0 until height) {
      for (x in 0 until width) {
        val p = pixels[y * width + x]
        val sum = ((p ushr 16) and 0xFF) + ((p ushr 8) and 0xFF) + (p and 0xFF)
        if (sum <= 12) continue
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
      }
    }
    return intArrayOf(left, top, right, bottom)
  }

  private fun render(
    renderer: MetalOffscreenRenderer,
    image: IntArray,
    settings: MetalSettings,
    turns: Float,
    out: IntArray,
  ): IntArray {
    renderer.upload(image, width, height)
    val frame = MetalFrame(
      width = width,
      height = height,
      viewportAspect = width.toFloat() / height,
      imageAspect = width.toFloat() / height,
      stepTexels = cell.toFloat(),
      turns = turns,
      settings = settings,
    )
    // The renderer reuses [out] between calls, so copy before the next frame overwrites it.
    return renderer.render(frame, out)?.copyOf() ?: throw AssertionError("The GPU returned no frame")
  }

  /** The canonical 9x9 motif, one 32-pixel cell, in four different colours. */
  private fun patternTexture(): IntArray {
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val background = 0xFF15151E.toInt()
    val colours = intArrayOf(
      0xFFE84A5F.toInt(),
      0xFF63C74D.toInt(),
      0xFF3E9BFF.toInt(),
      0xFFFFC857.toInt(),
    )
    val pixels = IntArray(width * height) { background }
    for (row in 0 until 9) {
      for (column in 0 until 9) {
        if (rows[row][column] != '*') continue
        val colour = colours[(row * 9 + column) % colours.size]
        for (y in row * cell until (row + 1) * cell) {
          for (x in column * cell until (column + 1) * cell) {
            pixels[y * width + x] = colour
          }
        }
      }
    }
    return pixels
  }

  private fun gradientTexture(): IntArray = IntArray(width * height) { index ->
    val x = index % width
    val y = index / width
    val r = (x * 255 / (width - 1)).coerceIn(0, 255)
    val g = (y * 255 / (height - 1)).coerceIn(0, 255)
    (0xFF shl 24) or (r shl 16) or (g shl 8) or 140
  }

  private fun stripesTexture(): IntArray = IntArray(width * height) { index ->
    val x = index % width
    val y = index / width
    val diagonal = (x + y) % 48 < 14
    if (diagonal) 0xFFF2F4F8.toInt() else 0xFF1C2030.toInt()
  }

  private fun save(directory: File, name: String, pixels: IntArray) {
    val file = File(directory, "$name.png")
    val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    Log.i(TAG, "saved ${file.absolutePath}")
  }

  private fun mean(pixels: IntArray): Double {
    var sum = 0.0
    for (pixel in pixels) {
      sum += ((pixel ushr 16) and 0xFF) + ((pixel ushr 8) and 0xFF) + (pixel and 0xFF)
    }
    return sum / (pixels.size * 3.0)
  }

  private fun deviation(pixels: IntArray): Double {
    val average = mean(pixels)
    var sum = 0.0
    for (pixel in pixels) {
      val luminance = (((pixel ushr 16) and 0xFF) + ((pixel ushr 8) and 0xFF) + (pixel and 0xFF)) / 3.0
      val d = luminance - average
      sum += d * d
    }
    return sqrt(sum / pixels.size)
  }

  private fun difference(a: IntArray, b: IntArray): Double {
    var sum = 0.0
    val count = minOf(a.size, b.size)
    for (i in 0 until count) {
      val p = a[i]
      val q = b[i]
      sum += abs(((p ushr 16) and 0xFF) - ((q ushr 16) and 0xFF))
      sum += abs(((p ushr 8) and 0xFF) - ((q ushr 8) and 0xFF))
      sum += abs((p and 0xFF) - (q and 0xFF))
    }
    return sum / (count * 3.0)
  }

  private companion object {
    const val TAG = "MetalShaderTest"
  }
}
