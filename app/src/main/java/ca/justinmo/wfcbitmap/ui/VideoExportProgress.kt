package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The export progress card: label plus a bar, shown for as long as the export runs.
 *
 * It is deliberately not a snackbar: a snackbar times out, and an export can take minutes. This card
 * stays until the work finishes or fails.
 */
@Composable
fun VideoExportProgress(
  label: String,
  progress: Float,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = PANEL_BACKGROUND.copy(alpha = 0.94f),
    modifier = modifier.width(300.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
      Text(label, color = Color.White, fontSize = 12.sp)
      Spacer(Modifier.height(8.dp))
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.2f),
      )
    }
  }
}
