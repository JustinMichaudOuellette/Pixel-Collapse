package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The edge band's separators are actually drawn differently from the rest, measured in pixels.
 *
 * "Make the separators dotted" is easy to implement and easy to get wrong: a dotted line drawn with the same
 * hairline stroke as its neighbours closes back up into a solid-looking line over pixel art, which is exactly
 * what happened and what this checks against. It renders the canvas, counts the light pixels along a
 * separator inside the band and one outside it, and requires the band's to be visibly heavier.
 */
@RunWith(AndroidJUnit4::class)
class EdgeBandRenderingTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "EdgeBandRenderingTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun theBandSeparatorsAreHeavierThanThePlainOnes() {
    rule.setContent {
      val grid = BitmapEditor.PixelGridState(initialColumns = GRID, initialRows = GRID)
      val cellPx = with(LocalDensity.current) { CELL.toPx() }
      Box(Modifier.size(CELL * GRID)) {
        BitmapEditor.ZoomablePixelCanvas(
          gridState = grid,
          selectedColor = Color.White,
          mode = BitmapEditor.EditorMode.Paint,
          selection = null,
          baseCellSizePx = cellPx.toInt(),
          canvasTestTag = TAG,
          edgeRuleKept = true,
          onSelectionChange = {},
          onTap = {},
          onResize = {},
        )
      }
    }
    rule.waitForIdle()

    val image = rule.onNodeWithTag(TAG).captureToImage().toPixelMap()
    val cell = with(rule.density) { CELL.toPx() }

    val inBand = lightPixelsAlong(image, (1 * cell).toInt())
    val plain = lightPixelsAlong(image, ((GRID / 2) * cell).toInt())

    println("EDGEDIAG band=$inBand plain=$plain")
    assertTrue("the band's separator must be drawn at all: band=$inBand", inBand > 0)
    assertTrue(
      "and it must be visibly heavier than the plain separator: band=$inBand plain=$plain",
      inBand > plain,
    )
  }

  /** Light pixels within a couple of pixels of the vertical line at [x], over the whole height. */
  private fun lightPixelsAlong(image: androidx.compose.ui.graphics.ImageBitmap, x: Int): Int {
    val pixels = image.toPixelMap()
    var count = 0
    for (y in 0 until pixels.height) {
      for (dx in -2..2) {
        val px = x + dx
        if (px < 0 || px >= pixels.width) continue
        val colour = pixels[px, y]
        if (abs(colour.red - 1f) < 0.15f && abs(colour.green - 1f) < 0.15f && abs(colour.blue - 1f) < 0.15f) {
          count++
        }
      }
    }
    return count
  }

  private companion object {
    const val GRID = 8
    val CELL = 40.dp
    const val TAG = "edge-band-test-canvas"
    const val WAKELOCK_MILLIS = 120_000L
  }
}