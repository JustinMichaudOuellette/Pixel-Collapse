package ca.justinmo.wfcbitmap.ui
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The app explaining itself: what the picture is, what each control changes about it, and where the files go.
 *
 * A full-screen dialog rather than a tooltip or a second destination. It is long enough to need scrolling,
 * and it has to cover the editor while it is up: the drawing underneath is still there, and a tap meant for a
 * sentence must not paint a pixel.
 *
 * The prose says what the code does, because that is what is being asked when the button is pressed -- not
 * what the buttons are called. Every claim here is a sentence about behaviour the rest of the app has to keep.
 */
@Composable
internal fun EditorInfoScreen(open: Boolean, onClose: () -> Unit) {
  if (!open) return
  InfoScreen(title = "About Pixel Collapse", onClose = onClose) {
    InfoSection(
      title = "The picture is not your drawing",
      body = "Every 3×3 window of the drawing becomes a tile -- 81 of them for a 9×9 drawing -- and the app " +
        "fills a much larger grid with those tiles, choosing each one so that it fits the tiles already " +
        "around it. The result is made only of shapes you drew, arranged into a picture you did not.",
    )
    InfoSection(
      title = "Tile grouping",
      body = "The kernel is always 3×3 cells, so the grouping decides how big one cell is: 3×3 is one " +
        "drawing pixel per cell, 6×6 is 2×2, 9×9 is 3×3. It changes what a pattern is, not how many cells " +
        "the picture has. At 9×9 a 9×9 drawing is seen whole, and the picture becomes its lattice.",
    ) { KernelCards() }
    InfoSection(
      title = "Preview size",
      body = "1× solves a sixteenth of the cells, 2× a quarter, 4× the full count. It decides how much " +
        "detail is in the picture, not how big the picture is: the preview always fills its column, so a " +
        "coarser setting is a blockier picture of the same size.",
    )
    InfoSection(
      title = "Edges and mirrors",
      body = "Wrapped, the drawing is read as a torus: its borders are not borders, and a pattern may run " +
        "off one edge and come back on the other. Unwrapped, the drawing's border has to be the picture's " +
        "border, which a busy border cannot be around a long edge -- the app then tiles without that rule " +
        "and says \"Edges relaxed\". Mirrors offer the flipped copies of every window as well, which is " +
        "more arrangements of the same drawing, and shapes facing a way the drawing does not face.",
    )
    InfoSection(
      title = "Saving",
      body = "Save writes the picture to your gallery at the save size, which multiplies the generated " +
        "picture. The preview decides how much is generated; the save size decides how big the file is. " +
        "Saved patterns are the drawing itself, kept inside the app so they can be loaded again.",
    )
    InfoSection(
      title = "The video",
      body = "The export solves one wide panorama, pins the cells past its end to its own first cells, and " +
        "then pans a frame-sized window across it one pixel at a time. Because the panorama's end is its " +
        "beginning, the last frame runs into the first and the clip loops without a jump; because the " +
        "window moves a pixel at a time, nothing is skipped. A GIF stores whole centiseconds, so the frame " +
        "rate you pick is played at the nearest delay it can express, and the dialog says which.",
    )
    Spacer(Modifier.height(28.dp))
  }
}

/**
 * One titled block of the explanation: a heading, a paragraph, and an optional illustration underneath.
 *
 * Sections and not one long paragraph: each one answers a question the editor raises on its own -- why the
 * picture is not a copy, what the sizes mean, where the files went -- so that a reader who only wants one
 * of those answers can find it without reading the rest.
 */
@Composable
internal fun InfoSection(
  title: String,
  body: String,
  content: @Composable () -> Unit = {},
) {
  Spacer(Modifier.height(22.dp))
  Text(
    text = title,
    color = MENU_TEXT_COLOR,
    fontSize = 15.sp,
    fontWeight = FontWeight.Bold,
  )
  Spacer(Modifier.height(5.dp))
  Text(
    text = body,
    color = MENU_TEXT_COLOR.copy(alpha = 0.72f),
    fontSize = 13.sp,
    lineHeight = 19.sp,
  )
  content()
}

/**
 * The explanation as a full screen: a title bar with a close button, and a scrolling body under it.
 *
 * A plain composable in the same window as the editor, so opening it never costs the drawing its state --
 * no collapse is repeated, and nothing on the editor is rebuilt when it closes.
 */
@Composable
internal fun InfoScreen(
  title: String,
  onClose: () -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
      Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
          Text(
            text = title,
            color = MENU_TEXT_COLOR,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center),
          )
          IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = MENU_TEXT_COLOR,
            )
          }
        }
        HorizontalDivider(color = MENU_DIVIDER_COLOR)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
          content = content,
        )
      }
    }
  }
}

/**
 * The first three groupings as cards, so the difference between them is a picture and a line of text rather
 * than a paragraph: what changes is how many of your pixels sit inside one cell, and that is exactly what
 * each card says.
 */
@Composable
private fun KernelCards() {
  Spacer(Modifier.height(12.dp))
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    listOf(KernelSize.Three, KernelSize.Six, KernelSize.Nine).forEach { kernel ->
      Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.07f),
      ) {
        Column(modifier = Modifier.padding(10.dp)) {
          Text(
            text = kernel.label,
            color = MENU_TEXT_COLOR,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = if (kernel.grouped) {
              "${kernel.cellSize}×${kernel.cellSize} pixels per cell"
            } else {
              "One pixel per cell"
            },
            color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
          )
        }
      }
    }
  }
}