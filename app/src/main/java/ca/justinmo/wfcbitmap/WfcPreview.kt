package ca.justinmo.wfcbitmap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale

/**
 * The live bitmap generated from the drawing, filling the column left of the grid edge to edge.
 *
 * Nothing else is drawn here on purpose: no caption, no controls, no padding.
 *
 * The bitmap is only 152x270, so the preview is a large nearest-neighbour blow-up of it
 * (`FilterQuality.None`), which is what keeps the pattern's pixels crisp instead of smeared. The
 * column is the same 9:16 shape as the bitmap, so `FillBounds` covers it exactly; the only stretch is
 * the one-eighth of a pixel that 152 versus 151.875 already accounts for.
 *
 * @param output the last generated bitmap, or null before the first one is ready. Holding on to the
 *   previous output is what keeps the preview steady while a newer edit is being solved.
 * @param onTap called when the preview is tapped: the caller advances the seed and regenerates.
 */
@Composable
fun WfcPreview(
  output: WfcGenerator.Output?,
  modifier: Modifier = Modifier,
  onTap: () -> Unit = {},
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      // A tap asks for the next variation: same drawing, next seed. The ripple is the only feedback
      // the preview has, which is why it is left on.
      .clickable(onClickLabel = "Generate a new variation", onClick = onTap),
  ) {
    if (output != null) {
      Image(
        bitmap = output.image,
        contentDescription = "Live bitmap generated from the drawing",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
      )
    }
  }
}