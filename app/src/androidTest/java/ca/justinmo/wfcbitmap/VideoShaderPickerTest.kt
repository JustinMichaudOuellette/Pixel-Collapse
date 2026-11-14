package ca.justinmo.wfcbitmap

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.justinmo.wfcbitmap.ui.VideoExportScreen
import ca.justinmo.wfcbitmap.video.MetalSettings
import ca.justinmo.wfcbitmap.video.VideoFormat
import ca.justinmo.wfcbitmap.video.VideoLook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The shader is a menu, and Metal 3D's settings live behind the gear rather than inline. */
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
    onFormatChange: (VideoFormat) -> Unit = {},
    metal: MetalSettings = MetalSettings(),
    onMetalChange: (MetalSettings) -> Unit = {},
  ) {
    val lookState = mutableStateOf(look)
    val formatState = mutableStateOf(format)
    val metalState = mutableStateOf(metal)
    rule.setContent {
      VideoExportScreen(
        open = true,
        format = formatState.value,
        screens = 2,
        fps = 16,
        frameColumns = 20,
        frameRows = 20,
        pixelsPerCell = 2,
        cellSize = 1,
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        look = lookState.value,
        metal = metalState.value,
        previewPixels = if (withPreview) IntArray(1000 * 1000) { 0xFF181425.toInt() } else null,
        previewWidth = if (withPreview) 1000 else 0,
        previewHeight = if (withPreview) 1000 else 0,
        previewCellPixels = 2,
        exporting = false,
        onScreensChange = {},
        onLookChange = { lookState.value = it },
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
  fun theShaderIsAMenuAndMetalHasASettingsButton() {
    showScreen(VideoLook.Metal)
    rule.onNodeWithContentDescription("Shader picker").assertExists()
    rule.onNodeWithContentDescription("Metal 3D shader settings").assertExists()

    rule.onNodeWithContentDescription("Shader picker").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Flat").assertExists()
    // Once as the field's current value, once as the selected menu row.
    rule.onAllNodesWithText("Metal 3D").assertCountEquals(2)

    rule.onNodeWithText("Flat").performClick()
    rule.waitForIdle()
    rule.onNodeWithContentDescription("Metal 3D shader settings").assertDoesNotExist()
  }

  @Test
  fun theGearOpensTheShaderSettings() {
    showScreen(VideoLook.Metal)
    rule.onNodeWithContentDescription("Metal 3D shader settings").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Metal 3D shader").assertExists()
    rule.onNodeWithText("Metal finish").assertExists()
    rule.onNodeWithContentDescription("Close shader settings").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Metal 3D shader").assertDoesNotExist()
  }

  @Test
  fun thePreviewZoomsToTheExportPixelSizeAndBackToFit() {
    // The preview bitmap is 1000 px square and the export is 2000 px: one exported pixel is twice
    // the size of one preview pixel, so the ceiling must be 2x rather than 1x.
    showScreen(VideoLook.Metal, withPreview = true, outputWidth = 2000, outputHeight = 2000)
    rule.onNodeWithContentDescription("Metal 3D shader settings").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Export 2×").assertExists()
    rule.onNodeWithText("Export 2×").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Fit").assertExists()
    rule.onNodeWithText("Fit").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Export 2×").assertExists()
  }

  @Test
  fun eachLightSwitchTurnsItsSourceOff() {
    var latest: MetalSettings? = null
    showScreen(VideoLook.Metal, onMetalChange = { latest = it })
    rule.onNodeWithContentDescription("Metal 3D shader settings").performClick()
    rule.waitForIdle()

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
    showScreen(VideoLook.Metal, withPreview = true)
    rule.onNodeWithContentDescription("Metal 3D shader settings").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Paused").assertDoesNotExist()
    rule.onNodeWithContentDescription("Metal preview").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Paused").assertExists()
    rule.onNodeWithContentDescription("Metal preview").performClick()
    rule.waitForIdle()
    rule.onNodeWithText("Paused").assertDoesNotExist()
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
