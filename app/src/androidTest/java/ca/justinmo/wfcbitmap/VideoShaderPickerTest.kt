package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.ui.VideoExportScreen
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoLook
import ca.justinmo.wfcbitmap.video.VideoMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The video type, shader, inline material controls and fixed preview. */
@RunWith(AndroidJUnit4::class)
class VideoShaderPickerTest {

  @get:Rule
  val rule = createComposeRule()

  private var wakeLock: PowerManager.WakeLock? = null

  @Before
  fun keepScreenOn() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "VideoShaderPickerTest",
    ).apply { acquire(WAKELOCK_MILLIS) }
  }

  @After
  fun releaseScreen() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  private fun showScreen(
    look: VideoLook,
    withPreview: Boolean = false,
    outputWidth: Int = 80,
    outputHeight: Int = 80,
    format: VideoFormat = VideoFormat.Gif,
    mode: VideoMode = VideoMode.Panorama,
    fps: Int = 16,
    onFormatChange: (VideoFormat) -> Unit = {},
    onModeChange: (VideoMode) -> Unit = {},
    onRiffPatternsChange: (Int) -> Unit = {},
    metal: MetalSettings = MetalSettings(),
    onMetalChange: (MetalSettings) -> Unit = {},
  ) {
    val lookState = mutableStateOf(look)
    val formatState = mutableStateOf(format)
    val modeState = mutableStateOf(mode)
    val metalState = mutableStateOf(metal)
    // A stable array: a new one on every recomposition would look like a new video frame and reset the
    // preview camera.
    val previewPixels = if (withPreview) IntArray(1000 * 1000) { 0xFF181425.toInt() } else null
    rule.setContent {
      VideoExportScreen(
        open = true,
        format = formatState.value,
        mode = modeState.value,
        screens = 2,
        fps = fps,
        frameColumns = 20,
        frameRows = 20,
        pixelsPerCell = 2,
        cellSize = 1,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        look = lookState.value,
        metal = metalState.value,
        previewPixels = previewPixels,
        previewWidth = if (withPreview) 1000 else 0,
        previewHeight = if (withPreview) 1000 else 0,
        previewCellPixels = 2,
        exporting = false,
        onScreensChange = {},
        onLookChange = { lookState.value = it },
        onModeChange = {
          modeState.value = it
          onModeChange(it)
        },
        onRiffPatternsChange = onRiffPatternsChange,
        onFpsChange = {},
        onFormatChange = {
          formatState.value = it
          onFormatChange(it)
        },
        onMetalChange = {
          metalState.value = it
          onMetalChange(it)
        },
        onDismiss = {},
        onExport = {},
      )
    }
    rule.waitForIdle()
  }

  @Test
  fun theVideoTypeIsADropdownInTheTitlebar() {
    var selected: VideoMode? = null
    showScreen(VideoLook.Flat, format = VideoFormat.Gif, onModeChange = { selected = it })
    rule.onNodeWithText("Video export").assertDoesNotExist()
    rule.onNodeWithText("Preview motion").assertDoesNotExist()
    rule.onNodeWithContentDescription("Export video").assertExists()
    rule.onNodeWithContentDescription("Video type picker").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Solve").performClick()
    rule.waitForIdle()
    assertEquals(VideoMode.Solve, selected)
    rule.onNodeWithText("A replay of the real solver", substring = true).assertExists()
  }

  @Test
  fun riffOffersAPatternCountAndSaysWhatItDoes() {
    var selected: VideoMode? = null
    showScreen(VideoLook.Flat, format = VideoFormat.Gif, onModeChange = { selected = it })
    rule.onNodeWithContentDescription("Video type picker").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Riff").performClick()
    rule.waitForIdle()
    assertEquals(VideoMode.Riff, selected)
    rule.onNodeWithText("Riff re-solves", substring = true).assertExists()
    rule.onNodeWithText("Patterns").performScrollTo().assertExists()
    rule.onNodeWithText("24").performScrollTo().assertExists()
  }

  @Test
  fun aGifCanBeAsSlowAsOneFramePerSecond() {
    showScreen(VideoLook.Flat, format = VideoFormat.Gif, fps = 1)
    rule.onNodeWithText("1 fps · 100 cs").performScrollTo().assertExists()
  }

  @Test
  fun theFlatPreviewCanBePinchedToZoom() {
    showScreen(VideoLook.Flat, withPreview = true, format = VideoFormat.Gif)
    val preview = rule.onNodeWithContentDescription("Video preview")
    preview.performTouchInput {
      val middle = center
      down(0, middle + Offset(-30f, 0f))
      down(1, middle + Offset(30f, 0f))
      moveTo(0, middle + Offset(-150f, 0f), delayMillis = 300)
      moveTo(1, middle + Offset(150f, 0f), delayMillis = 300)
      up(0)
      up(1)
    }
    rule.waitForIdle()
    val state = preview.fetchSemanticsNode()
      .config[SemanticsProperties.StateDescription]
    assertTrue("expected a zoomed flat preview, state was '$state'", state.contains("Zoom") &&
      !state.contains("Zoom 1.0x"))
  }

  @Test
  fun theMetalControlsAreInlineAndThePreviewIsAlwaysVisible() {
    showScreen(VideoLook.Metal, withPreview = true, format = VideoFormat.Mp4)
    rule.onNodeWithContentDescription("Metal preview").assertExists()
    rule.onNodeWithContentDescription("Metal 3D shader settings").assertDoesNotExist()
    rule.onNodeWithText("Metal finish").performScrollTo().assertExists()

    rule.onNodeWithContentDescription("Shader picker").performClick()
    rule.waitForIdle()
    // Once as the field's current value, once as the selected menu row.
    rule.onAllNodesWithText("Metal 3D").assertCountEquals(2)
    rule.onNodeWithText("Flat").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Metal finish").assertDoesNotExist()
    rule.onNodeWithContentDescription("Video preview").assertExists()
  }

  @Test
  fun thePreviewHasNoZoomLabels() {
    showScreen(
      VideoLook.Metal,
      withPreview = true,
      outputWidth = 2000,
      outputHeight = 2000,
      format = VideoFormat.Mp4,
    )
    rule.onNodeWithContentDescription("Metal preview").assertExists()
    rule.onNodeWithText("Export 2×").assertDoesNotExist()
    rule.onNodeWithText("Fit").assertDoesNotExist()
    rule.onNodeWithText("Paused").assertDoesNotExist()
  }

  @Test
  fun eachLightSwitchTurnsItsSourceOff() {
    var latest: MetalSettings? = null
    showScreen(VideoLook.Metal, format = VideoFormat.Mp4, onMetalChange = { latest = it })
    rule.onNodeWithContentDescription("Turn off Key light").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(false, latest?.keyLightEnabled)

    rule.onNodeWithContentDescription("Turn off Sky").performScrollTo().performClick()
    rule.waitForIdle()
    assertEquals(false, latest?.skyEnabled)
    rule.onNodeWithContentDescription("Turn on Sky").assertExists()
  }

  @Test
  fun tappingThePreviewPausesAndResumesIt() {
    showScreen(VideoLook.Metal, withPreview = true, format = VideoFormat.Mp4)
    rule.onNodeWithContentDescription("Metal preview")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
    rule.onNodeWithContentDescription("Metal preview").performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Metal preview")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
    rule.onNodeWithContentDescription("Metal preview").performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Metal preview")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
  }

  @Test
  fun aShaderDisablesGifAndSwitchesToMp4() {
    var changed: VideoFormat? = null
    showScreen(
      look = VideoLook.Metal,
      format = VideoFormat.Gif,
      onFormatChange = { changed = it },
    )
    rule.waitForIdle()
    assertEquals(VideoFormat.Mp4, changed)
    rule.onNodeWithText("Not with a shader").assertExists()
  }

  private companion object {
    const val WAKELOCK_MILLIS = 120_000L
  }
}
