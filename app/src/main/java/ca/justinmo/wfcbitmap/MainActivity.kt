package ca.justinmo.wfcbitmap

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ca.justinmo.wfcbitmap.BitmapEditor.PixelEditorScreen
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)

    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
    windowInsetsController.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    setContent {
      WFCBitmapTheme {
        Scaffold(
          modifier = Modifier.fillMaxSize(),
          contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
          PixelEditor(
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }
}

/**
 * The editor draws the *source image*; "Generate" grows it into a bitmap built only from 3x3
 * patterns that occur in the drawing (see [WfcGenerator]), and the result can be saved as a PNG.
 */
@Composable
fun PixelEditor(modifier: Modifier = Modifier) {
  val gridState = remember { BitmapEditor.PixelGridState() }
  var state by remember { mutableStateOf<WfcUiState>(WfcUiState.Idle) }
  var seed by remember { mutableIntStateOf(1) }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current

  // Creating the document through the system picker needs no storage permission on any supported
  // API level, and it puts the file wherever the user wants it.
  var pendingSave by remember { mutableStateOf<WfcGenerator.Output?>(null) }
  val saveLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("image/png"),
  ) { uri ->
    val output = pendingSave
    pendingSave = null
    if (uri == null || output == null) {
      return@rememberLauncherForActivityResult
    }
    val saved = runCatching {
      context.contentResolver.openOutputStream(uri)?.use { stream ->
        output.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
      } ?: false
    }.getOrDefault(false)
    Toast.makeText(
      context,
      if (saved) "Saved ${output.bitmap.width}x${output.bitmap.height} bitmap" else "Could not save the bitmap",
      Toast.LENGTH_SHORT,
    ).show()
  }

  fun generate(requestSeed: Int) {
    if (state is WfcUiState.Generating) {
      return
    }
    // Snapshot the drawing on the main thread: the solver runs on a background dispatcher and must
    // not read Compose snapshot state.
    val columns = gridState.columns
    val rows = gridState.rows
    val pixels = IntArray(columns * rows) { gridState.pixels[it].toArgb() }
    scope.launch {
      state = WfcUiState.Generating
      state = try {
        WfcUiState.Ready(
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = columns,
            sourceHeight = rows,
            seed = requestSeed,
          )
        )
      } catch (e: WfcGenerator.GenerationException) {
        WfcUiState.Failed(e.message ?: "Generation failed")
      } catch (e: Exception) {
        WfcUiState.Failed("Generation failed: ${e.message ?: e::class.simpleName}")
      }
    }
  }

  Box(modifier.fillMaxSize()) {
    PixelEditorScreen(gridState = gridState)

    Button(
      onClick = { generate(seed) },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(24.dp),
    ) {
      Text(if (state is WfcUiState.Generating) "Generating..." else "Generate bitmap")
    }

    WfcOverlay(
      state = state,
      onClose = { state = WfcUiState.Idle },
      onRegenerate = {
        seed = Random.nextInt(1, 1_000_000)
        generate(seed)
      },
      onSave = { output ->
        pendingSave = output
        val name = "wfc-${output.columns}x${output.rows}-seed${output.wonBySeed ?: output.seed}.png"
        saveLauncher.launch(name)
      },
    )
  }
}
