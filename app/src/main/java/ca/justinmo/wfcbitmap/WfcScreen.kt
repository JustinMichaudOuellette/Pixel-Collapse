package ca.justinmo.wfcbitmap

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What the WFC screen is doing right now. */
sealed interface WfcUiState {
  data object Idle : WfcUiState
  data object Generating : WfcUiState
  data class Ready(val output: WfcGenerator.Output) : WfcUiState
  data class Failed(val message: String) : WfcUiState
}

/**
 * Full-screen overlay: a spinner while solving, the generated bitmap when it is done, or the reason
 * it failed. It sits above the editor so the drawing is still underneath.
 */
@Composable
fun WfcOverlay(
  state: WfcUiState,
  onClose: () -> Unit,
  onRegenerate: () -> Unit,
  onSave: (WfcGenerator.Output) -> Unit,
) {
  if (state is WfcUiState.Idle) {
    return
  }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color(0xF0000000)),
    contentAlignment = Alignment.Center,
  ) {
    when (state) {
      // Unreachable (handled above) but keeps the `when` exhaustive.
      WfcUiState.Idle -> Unit

      WfcUiState.Generating -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        CircularProgressIndicator()
        Text("Solving...", color = Color.White, style = MaterialTheme.typography.bodyLarge)
      }

      is WfcUiState.Failed -> Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          text = state.message,
          color = Color.White,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onClose) { Text("Close") }
      }

      is WfcUiState.Ready -> {
        val output = state.output
        Column(
          // Fill the overlay so the bitmap can be told to give way to the caption and buttons: an
          // aspect-ratio-sized image is taller than the screen in landscape, which used to push the
          // whole action row off the bottom edge where it could not be tapped.
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // Nearest-neighbour scaling: these are pixels, and smoothing them would hide exactly the
          // neighbour mismatches the strict solver exists to prevent.
          Image(
            bitmap = output.image,
            contentDescription = "Generated wave function collapse bitmap",
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
          )
          Text(
            text = buildString {
              append("${output.bitmap.width}x${output.bitmap.height} bitmap\n")
              append("${output.columns}x${output.rows} cells from ${output.tileCount} ")
              append(if (output.tileCount == 1) "tile" else "tiles")
              append('\n')
              append("${output.millis}ms, seed ${output.wonBySeed ?: output.seed}")
              if (output.workers > 1) append(" (won by one of ${output.workers} solvers)")
              if (output.usedFallback) {
                append("\nSearch ran out of time, so the drawing's own tiling is shown.")
              }
            },
            color = Color(0xFFC0CBDC),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRegenerate) { Text("New seed") }
            Button(onClick = { onSave(output) }) { Text("Save PNG") }
            Button(onClick = onClose) { Text("Edit") }
          }
        }
      }
    }
  }
}
