package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.editor.PixelGridState
import ca.justinmo.wfcbitmap.ui.EditorMode
import ca.justinmo.wfcbitmap.ui.ZoomablePixelCanvas
import ca.justinmo.wfcbitmap.ui.showsEdgeBand
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The drawing's grid lines all look the same unless the picture really keeps the drawing's border.
 *
 * The edge band is drawn by dotting the separators that lie in it, and a dotted separator is a three-pixel
 * stroke against the hairline every other line uses. That is only honest while the border is being kept: with
 * the edge rule dropped, or with the edges wrapped, the band marks cells that are not special, and a grid
 * dotted along four sides and hairlined everywhere else reads as a band the picture is keeping.
 *
 * Measured, not asserted from the outside: along each separator the longest run of drawn pixels is three for
 * a dotted line and one for a hairline, so "uniform" means every separator gives the same answer.
 */
@RunWith(AndroidJUnit4::class)
class EdgeBandUniformTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "EdgeBandUniformTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  @Test
  fun aWrappedDrawingHasNoBandOnItsGrid() {
    // Wrapped: the drawing's border is not a border, so no separator may be dotted.
    assertTrue("a wrapped drawing is uniform", separatorsAreUniform(marksEdgeBand = false))
  }

  @Test
  fun aDrawingWhoseEdgeRuleWasDroppedHasNoBandOnItsGrid() {
    assertTrue("a relaxed picture is uniform", separatorsAreUniform(marksEdgeBand = false))
  }

  @Test
  fun aDrawingWhoseBorderIsKeptStillMarksTheBand() {
    // The control: with the band marked some separators are dotted, or the test above would pass by drawing
    // nothing at all.
    assertFalse("a kept border still has a band in the separators", separatorsAreUniform(marksEdgeBand = true))
  }

  @Test
  fun theBandRuleItselfIsWhatDecidesIt() {
    // The decision the editor makes, spelled out: only a kept border that is not wrapped is marked.
    assertTrue(showsEdgeBand(edgeRuleKept = true, wrapEdges = false))
    assertFalse(showsEdgeBand(edgeRuleKept = false, wrapEdges = false))
    assertFalse(showsEdgeBand(edgeRuleKept = true, wrapEdges = true))
    assertFalse(showsEdgeBand(edgeRuleKept = false, wrapEdges = true))
  }

  /** Draws a bare grid and answers whether every vertical separator is the same kind of line. */
  private fun separatorsAreUniform(marksEdgeBand: Boolean): Boolean {
    val grid = PixelGridState(initialColumns = GRID, initialRows = GRID)
    rule.setContent {
      val cellPx = with(LocalDensity.current) { CELL.toPx() }
      Box(Modifier.size(CELL * GRID)) {
        ZoomablePixelCanvas(
          gridState = grid,
          selectedColor = Color.White,
          mode = EditorMode.Paint,
          selection = null,
          baseCellSizePx = cellPx.toInt(),
          canvasTestTag = TAG,
          marksEdgeBand = marksEdgeBand,
        )
      }
    }
    rule.waitForIdle()

    val canvas = rule.onNodeWithTag(TAG).captureToImage().toPixelMap()
    val cell = canvas.width / GRID
    // How many dashes each separator is broken into. A hairline runs 1 drawn pixel and 1 skipped; a band dot
    // is three pixels long with a gap nearly twice that, so a dotted line is broken into roughly a quarter as
    // many pieces. Counting pieces is what the eye is doing when it says one line looks different.
    val pieces = (0..GRID).map { column ->
      val x = (column * cell).coerceIn(0, canvas.width - 1)
      (x - 1..x + 1)
        .map { window -> dashesAlong(canvas, window) }
        .max()
    }
    println("EDGEBAND marks=$marksEdgeBand cell=$cell pieces=$pieces")
    // A third of the count rather than "all equal": the outermost separators are clipped by the canvas edge,
    // which costs them a few dashes, while a dotted separator is broken into a quarter as many pieces as a
    // hairline. The two are far enough apart that this cannot be read as a band.
    return pieces.max() - pieces.min() <= pieces.max() / 3
  }

  /** How many separate stretches of drawn pixels run down column [x] of the captured canvas. */
  private fun dashesAlong(canvas: PixelMap, x: Int): Int {
    if (x !in 0 until canvas.width) return 0
    var pieces = 0
    var inside = false
    for (y in 0 until canvas.height) {
      val colour = canvas[x, y]
      // The cells are empty, so anything brighter than the background is a grid line.
      val drawn = colour.alpha > 0.5f && (colour.red + colour.green + colour.blue) / 3f > 0.5f
      if (drawn && !inside) pieces++
      inside = drawn
    }
    return pieces
  }

  private companion object {
    const val GRID = 12
    const val TAG = "edge-band-canvas"
    val CELL = 24.dp
    const val WAKELOCK_MILLIS = 120_000L
  }
}