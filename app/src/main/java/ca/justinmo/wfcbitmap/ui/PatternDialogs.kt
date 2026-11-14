package ca.justinmo.wfcbitmap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ca.justinmo.wfcbitmap.storage.EditorSnapshot
import ca.justinmo.wfcbitmap.storage.PatternMetadata
import ca.justinmo.wfcbitmap.storage.SavedPattern

/** Asks for a name and saves the current drawing as a pattern. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SavePatternDialog(
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  val focusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current

  // The dialog is for naming a pattern, so start with the field focused and the keyboard up.
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
    keyboard?.show()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Color.Black,
    titleContentColor = Color.White,
    textContentColor = Color.White,
    title = { Text("Save pattern") },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.focusRequester(focusRequester),
        colors = OutlinedTextFieldDefaults.colors(
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White,
          cursorColor = Color.White,
          focusedBorderColor = Color.White,
          unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
          focusedLabelColor = Color.White,
          unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
        ),
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onSave(name.trim()) },
        enabled = name.isNotBlank(),
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
      ) { Text("Save") }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
      ) { Text("Cancel") }
    },
  )
}

/** Lists the saved patterns as a grid of their own drawings, and confirms deletions. */
@Composable
internal fun LoadPatternDialog(
  patterns: List<SavedPattern>,
  onDismiss: () -> Unit,
  onLoad: (PatternMetadata) -> Unit,
  onDelete: (PatternMetadata) -> Unit,
) {
  var pendingDelete by remember { mutableStateOf<PatternMetadata?>(null) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          // Keep the title and cells off the camera cutout and out of the system bars, while the
          // black surface itself still reaches the screen edges.
          .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onDismiss) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = Color.White,
            )
          }
          Text(
            text = "Choose a pattern",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
          )
        }

        if (patterns.isEmpty()) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              "No patterns in the gallery yet.\nUse Save pattern to put one there.",
              color = Color.White,
            )
          }
        } else {
          LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(items = patterns, key = { it.metadata.id }) { pattern ->
              PatternCell(
                pattern = pattern,
                onClick = { onLoad(pattern.metadata) },
                onLongClick = { pendingDelete = pattern.metadata },
              )
            }
          }
        }
      }
    }
  }

  pendingDelete?.let { pattern ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      containerColor = Color.Black,
      titleContentColor = Color.White,
      textContentColor = Color.White,
      title = { Text("Delete pattern?") },
      text = { Text("This cannot be undone.") },
      confirmButton = {
        TextButton(
          onClick = {
            pendingDelete = null
            onDelete(pattern)
          },
          colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252)),
        ) { Text("Delete") }
      },
      dismissButton = {
        TextButton(
          onClick = { pendingDelete = null },
          colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) { Text("Cancel") }
      },
    )
  }
}

/** One pattern cell: the preview fills the cell; long-press is the delete action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatternCell(
  pattern: SavedPattern,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val image = remember(pattern.snapshot, pattern.previewPixels) {
    val preview = pattern.previewPixels
    if (preview != null && pattern.previewWidth > 0 && pattern.previewHeight > 0) {
      Bitmap.createBitmap(
        preview,
        pattern.previewWidth,
        pattern.previewHeight,
        Bitmap.Config.ARGB_8888,
      ).asImageBitmap()
    } else {
      pattern.snapshot.toImageBitmap()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(1f)
      .clip(RoundedCornerShape(6.dp))
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .background(Color.Black),
  ) {
    Image(
      bitmap = image,
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
      filterQuality = FilterQuality.None,
    )
  }
}

private fun EditorSnapshot.toImageBitmap(): ImageBitmap =
  Bitmap.createBitmap(pixels, columns, rows, Bitmap.Config.ARGB_8888).asImageBitmap()