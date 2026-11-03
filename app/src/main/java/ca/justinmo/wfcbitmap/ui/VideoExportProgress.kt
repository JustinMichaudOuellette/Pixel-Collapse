package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The export progress card: label, bar and a cancel action, shown for as long as the export runs.
 *
 * It is deliberately not a snackbar: a snackbar times out, and an export can take minutes. This card
 * stays until the work finishes, fails, or the user cancels it.
 */
@Composable
fun VideoExportProgress(
  label: String,
  progress: Float,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = PANEL_BACKGROUND.copy(alpha = 0.94f),
    modifier = modifier.width(320.dp),
  ) {
    Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          label,
          color = Color.White,
          fontSize = 12.sp,
          modifier = Modifier.weight(1f),
        )
        Text(
          String.format(Locale.US, "%.2f%%", progress.coerceIn(0f, 1f) * 100f),
          color = Color.White.copy(alpha = 0.85f),
          fontSize = 11.sp,
          modifier = Modifier.padding(start = 8.dp),
        )
        TextButton(onClick = onCancel) {
          Text("Cancel", color = Color.White, fontSize = 12.sp)
        }
      }
      Spacer(Modifier.height(6.dp))
      LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier
          .fillMaxWidth()
          .padding(end = 8.dp),
        color = Color.White,
        trackColor = Color.White.copy(alpha = 0.2f),
      )
    }
  }
}
