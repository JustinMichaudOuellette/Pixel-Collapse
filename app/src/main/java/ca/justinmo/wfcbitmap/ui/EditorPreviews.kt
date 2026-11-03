/**
 * Compose previews of the editor's parts, for the IDE's layout editor.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme

@Preview(heightDp = BUTTON_SIZE_DP_INT, widthDp = BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchPreviewPreview() {
  WFCBitmapTheme {
    ColorSwatch(
      color = Color.Red,
      isSelected = false,
      onClick = {},
    )
  }
}

@Preview(heightDp = BUTTON_SIZE_DP_INT, widthDp = BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchSelectedPreviewPreview() {
  WFCBitmapTheme {
    ColorSwatch(
      color = Color.Red,
      isSelected = true,
      onClick = {},
    )
  }
}

@Preview(name = "Info screen", heightDp = 900, widthDp = 411, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun EditorInfoPreview() {
  WFCBitmapTheme {
    EditorInfoScreen(open = true, onClose = {})
  }
}

@Preview(heightDp = 418, widthDp = 880, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PixelEditorPreview() {
  WFCBitmapTheme {
    PixelEditorScreen()
  }
}
