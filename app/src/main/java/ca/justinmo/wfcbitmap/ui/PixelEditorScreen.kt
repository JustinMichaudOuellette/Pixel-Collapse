/**
 * The editor screen: the tool strip beside the drawing, the live preview, the output menu, and the snackbar host
 * that says what a long press or a save did.
 *
 * The screen owns no state it can help owning -- the drawing lives in [PixelGridState], the settings are handed
 * in, and every action is a callback -- which is what lets it be previewed and tested without a generator
 * behind it.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Palette as PaletteIcon
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.Icons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.justinmo.wfcbitmap.CellRect
import ca.justinmo.wfcbitmap.EditorMessage
import ca.justinmo.wfcbitmap.OutputAspect
import ca.justinmo.wfcbitmap.OutputScale
import ca.justinmo.wfcbitmap.OutputSettings
import ca.justinmo.wfcbitmap.Palette
import ca.justinmo.wfcbitmap.PixelGridState
import ca.justinmo.wfcbitmap.previewPixelSize
import ca.justinmo.wfcbitmap.PreviewScale
import ca.justinmo.wfcbitmap.savedPixelSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The palette a preview or UI test sees when the caller supplies none.
 *
 * The app always passes the palettes it parsed from assets; this one exists so the screen can be
 * composed on its own, and it is deliberately not the app's default -- that is `endesga-32.txt`.
 */
private val PREVIEW_PALETTE = Palette(
  id = "preview.txt",
  name = "Preview",
  colors = listOf(Color.Black, Color.White),
)

/**
 * @param preview fills the column left of the drawing grid. It is kept as a slot so the editor does
 *   not need to know anything about the wave-function collapse that produces the bitmap shown there.
 * @param output the shape, the size the picture is generated at and the size it is written at; the
 *   preview column takes its shape.
 * @param onOutputChange called when the output menu picks a different shape, preview size or save size.
 */
@Composable
fun PixelEditorScreen(
  modifier: Modifier = Modifier,
  gridState: PixelGridState = remember { PixelGridState(palette = PREVIEW_PALETTE.colors) },
  /**
   * Called when a tap that may edit the grid is detected, before the cell changes.
   *
   * It exists for measurement: this is the earliest point at which the app knows a person touched the
   * drawing, so an end-to-end latency can be timed from here to the frame that shows the result.
   */
  onTap: () -> Unit = {},
  onResize: () -> Unit = {},
  /**
   * Called when the Save tool is tapped: the caller owns the generated bitmap, so writing it out is
   * the caller's job. A drawing that has not produced a picture yet is still a valid save request;
   * what to say about that is also the caller's call.
   */
  onSave: () -> Unit = {},
  /** Called when the Share tool is tapped; the caller owns the bitmap and the chooser. */
  onShare: () -> Unit = {},
  /**
   * Called when the Close tool is tapped: leaving an app is the activity's business, not the editor's, so
   * the caller closes itself rather than this screen reaching for a context to finish.
   */
  onExit: () -> Unit = {},
  output: OutputSettings = OutputSettings(),
  onOutputChange: (OutputSettings) -> Unit = {},
  /**
   * Shown in a snackbar when the picture is not a plain edge-respecting tiling of the drawing, and what to
   * do about it. Null (the usual case) shows nothing.
   *
   * A snackbar and not a line under the drawing: the sentence is about one picture, and a strip reserved
   * under the drawing for as long as the message applies costs the drawing height it needs.
   */
  notice: String? = null,
  /**
   * A line to show over the drawing on the caller's behalf, with an optional action button.
   *
   * This is for outcomes the caller owns -- a save that worked is worth a way to go and look at the
   * picture, and that is a button rather than a sentence -- so it is a different thing from [notice]: the
   * notice is about the picture and has nothing to do, and this one can be acted on.
   */
  message: EditorMessage? = null,
  /** Called once [message] has been handed to the snackbar, so the caller can forget it. */
  onMessageShown: () -> Unit = {},
  /**
   * Whether the drawing's edges are read as wrapping into each other; see [EditorTool].
   *
   * The editor only reports it: the picture is the caller's to build, so acting on the setting is the
   * caller's job, and this screen shows the toggle as a mode rather than keeping the answer.
   */
  wrapEdges: Boolean = false,
  /** Called when the wrap-edges toggle is tapped, with the state it should have afterwards. */
  onWrapEdgesChange: (Boolean) -> Unit = {},
  /**
   * The colour the paint tools start with, or null for the palette's first entry. Only a saved session
   * passes one: on a fresh start the first swatch is selected, so a tap on the drawing has something to
   * paint with.
   */
  initialColor: Color? = null,
  /** Called with the drawing colour whenever it changes, so the caller can save a session. */
  onColorChange: (Color) -> Unit = {},
  /**
   * Whether every pattern is also offered mirrored left to right; see [EditorTool].
   *
   * Like [wrapEdges] the editor only reports it, because acting on it is what the caller does when it
   * builds the picture.
   */
  mirrorHorizontal: Boolean = false,
  /** Called when the left-to-right mirror is tapped, with the state it should have afterwards. */
  onMirrorHorizontalChange: (Boolean) -> Unit = {},
  /**
   * Whether every pattern is also offered flipped top to bottom; see [EditorTool].
   *
   * The second of the two mirrors, and independent of the first: the drawing is the same either way, so
   * the picture can be given the shapes turned about one axis, the other, both, or neither.
   */
  mirrorVertical: Boolean = false,
  /** Called when the top-to-bottom mirror is tapped, with the state it should have afterwards. */
  onMirrorVerticalChange: (Boolean) -> Unit = {},
  /**
   * Whether the edge rule held for the picture on screen. False once the generator had to drop it, which
   * means the drawing's border is not the picture's border any more.
   *
   * Between this and [wrapEdges] the screen knows whether the picture keeps the drawing's border at all, and
   * that is what decides both the marked edge band on the grid and whether the wrap button reads as on: a
   * borderless picture makes the band a lie and the button a truth.
   */
  edgeRuleKept: Boolean = true,
  palettes: List<Palette> = listOf(PREVIEW_PALETTE),
  activePalette: Palette = PREVIEW_PALETTE,
  /**
   * Whether picking a palette also re-colours the drawing to the closest colours in it.
   *
   * The drawing itself is the caller's to rewrite, so the editor only carries the switch and reports
   * changes; the caller performs the migration when it changes the active palette.
   */
  autoMigrateColors: Boolean = true,
  /** Called when the palette picker's auto-migrate switch is tapped, with its new state. */
  onAutoMigrateColorsChange: (Boolean) -> Unit = {},
  onPaletteChange: (Palette) -> Unit = {},
  preview: @Composable () -> Unit = {},
) {
  // The first palette colour is selected on open, so the swatch strip starts with a highlighted
  // colour and a tap on the drawing already has something to paint with.
  var selectedColor by remember { mutableStateOf(initialColor ?: activePalette.colors.first()) }

  LaunchedEffect(activePalette) {
    if (selectedColor !in activePalette.colors) {
      selectedColor = activePalette.colors.first()
    }
  }

  // Reported on every change, including the one at first composition, so the caller can save it.
  LaunchedEffect(selectedColor) { onColorChange(selectedColor) }

  // The armed tool, and the area the selection tool has marked out. Both are the editor's own state:
  // they change what a gesture means, not what the generated picture is.
  var mode by remember { mutableStateOf(EditorMode.Paint) }
  var selection by remember { mutableStateOf<CellRect?>(null) }
  var outputMenuOpen by remember { mutableStateOf(false) }
  var paletteMenuOpen by remember { mutableStateOf(false) }


  // Tapping the armed mode puts the drawing back to painting; tapping another one arms that. A
  // selection belongs to the select tool, so arming any other tool forgets it rather than leaving a
  // rectangle on the drawing that no tool is going to act on.
  fun arm(next: EditorMode) {
    mode = if (mode == next) EditorMode.Paint else next
    if (mode != EditorMode.Select) selection = null
  }

  /*
   * The line that explains a tool when its button is held down.
   *
   * A snackbar and not a tooltip: the toolbar is a scrolled strip of small squares at the very edge of
   * the screen, and there is no room to hang anything beside one. A snackbar can be any width, sits
   * where the eye already is, and takes itself away again.
   */
  val snackbar = remember { SnackbarHostState() }
  val snackbarScope = rememberCoroutineScope()
  var snackbarJob by remember { mutableStateOf<Job?>(null) }

  /**
   * Shows [message] over the drawing, replacing whatever line was there.
   *
   * One line at a time, on purpose: a second message means the first is out of date -- the next tool's
   * explanation, or a new generation's notice -- so it is replaced rather than queued behind it.
   *
   * An [actionLabel] makes it a message with a way to act on it, and [onAction] runs if that is tapped
   * before the line goes away on its own.
   */
  fun showSnackbar(
    message: String,
    duration: SnackbarDuration,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
  ) {
    snackbarJob?.cancel()
    snackbarJob = snackbarScope.launch {
      val outcome = snackbar.showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = duration,
      )
      // Dismissed means the line timed out or was swept away by a newer one: only a tap runs the action.
      if (outcome == SnackbarResult.ActionPerformed) onAction?.invoke()
    }
  }

  fun explain(tool: EditorTool) = showSnackbar(tool.explanation, SnackbarDuration.Short)

  /*
   * The generation notice gets the snackbar rather than a line of its own under the drawing.
   *
   * It is a sentence about one picture, not a permanent part of the editor: while it sat under the
   * drawing it took a strip of the drawing's height for as long as the drawing kept binding its edges,
   * which is most of the time. It appears when the message changes, and the same message appearing twice
   * in a row is not news.
   */
  LaunchedEffect(notice) {
    // Short: it is a word about the picture, and it sits over the picture while it is up.
    notice?.let { showSnackbar(it, SnackbarDuration.Short) }
  }

  /*
   * The caller's own line, which can carry an action: after a save, "Saved to the gallery" with a way to
   * see the file. Long rather than Short, because a line that can be acted on has to stay long enough to
   * be acted on, and the button is the reason it is there at all.
   *
   * The caller is told as soon as the line is up, not when it goes away: the message is a one-shot event,
   * and the same event happening twice in a row has to show twice.
   */
  LaunchedEffect(message) {
    val line = message ?: return@LaunchedEffect
    val duration = if (line.actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long
    showSnackbar(line.text, duration, line.actionLabel, line.onAction)
    onMessageShown()
  }

  // A selection cannot outlive the grid it was measured on: undo, rotate and the +/- buttons all change
  // the grid under it.
  LaunchedEffect(gridState.columns, gridState.rows) { selection = null }

  // One entry per tool, top to bottom: modes first, then history, then whole-drawing edits, then output
  // and the actions that leave the app. Rebuilt on every recomposition so the lambdas see current state.
  val editorTools = listOf(
    EditorTool(
      icon = Icons.Default.Colorize,
      contentDescription = "Pick a colour from the drawing",
      explanation = "Takes the colour under your finger and makes it the one you draw with.",
      selected = mode == EditorMode.Eyedropper,
    ) { arm(EditorMode.Eyedropper) },
    EditorTool(
      icon = Icons.Default.FormatColorFill,
      contentDescription = "Fill a connected area with the selected colour",
      explanation = "Paints the whole joined area of the tapped colour, up to anything drawn in another colour.",
      selected = mode == EditorMode.Fill,
    ) { arm(EditorMode.Fill) },
    EditorTool(
      icon = Icons.Default.FindReplace,
      contentDescription = "Replace the tapped colour everywhere with the selected colour",
      explanation = "Swaps the tapped colour for the selected one everywhere on the drawing.",
      selected = mode == EditorMode.ReplaceColor,
    ) { arm(EditorMode.ReplaceColor) },
    EditorTool(
      icon = Icons.Default.Timeline,
      contentDescription = "Draw a line",
      explanation = "Drag from one cell to another; the line between the two points is painted.",
      selected = mode == EditorMode.Line,
      startsGroup = true,
    ) { arm(EditorMode.Line) },
    EditorTool(
      icon = Icons.Default.CropSquare,
      contentDescription = "Draw a rectangle",
      explanation = "Drag out a box; its outline is painted and the cells inside it are left alone.",
      selected = mode == EditorMode.Rectangle,
    ) { arm(EditorMode.Rectangle) },
    EditorTool(
      icon = Icons.Default.RadioButtonUnchecked,
      contentDescription = "Draw an ellipse",
      explanation = "Drag out a box; an ellipse filling it is painted as an outline, not a fill.",
      selected = mode == EditorMode.Ellipse,
    ) { arm(EditorMode.Ellipse) },
    EditorTool(
      icon = Icons.Default.CropFree,
      contentDescription = "Select an area to move",
      explanation = "Drag out a rectangle to select it, then drag inside it to move what it holds.",
      selected = mode == EditorMode.Select,
    ) { arm(EditorMode.Select) },
    // One tool, used in two steps: drag out an area to copy, then drag the copy. It sits with the select
    // tool because it is that gesture plus a duplicate -- the original stays where it is.
    EditorTool(
      icon = Icons.Default.ContentCopy,
      contentDescription = "Copy an area: drag out the area, then drag the copy",
      explanation = "Drag out an area to copy it, then drag the copy where it belongs. The original " +
        "stays where it is.",
      selected = mode == EditorMode.Duplicate,
    ) { arm(EditorMode.Duplicate) },

    EditorTool(
      icon = Icons.Default.Undo,
      contentDescription = "Undo",
      explanation = "Takes back the last edit, however many cells it touched.",
      enabled = gridState.canUndo(),
      startsGroup = true,
    ) {
      gridState.undo()
      onResize()
    },
    EditorTool(
      icon = Icons.Default.Redo,
      contentDescription = "Redo",
      explanation = "Puts back the edit you just took back.",
      enabled = gridState.canRedo(),
    ) {
      gridState.redo()
      onResize()
    },

    EditorTool(
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing left to right",
      explanation = "Flips the drawing horizontally, like a mirror held to one side.",
      startsGroup = true,
    ) {
      gridState.flipHorizontal()
      onResize()
    },
    EditorTool(
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing top to bottom",
      explanation = "Flips the drawing vertically, like a mirror laid underneath.",
      iconRotationDegrees = 90f,
    ) {
      gridState.flipVertical()
      onResize()
    },
    EditorTool(
      icon = Icons.Default.RotateRight,
      contentDescription = "Turn the drawing a quarter turn",
      explanation = "Turns the whole drawing a quarter turn clockwise, which swaps its width and height.",
    ) {
      gridState.rotateClockwise()
      onResize()
    },

    EditorTool(
      icon = Icons.Default.PaletteIcon,
      contentDescription = "Select color palette",
      explanation = "Chooses the color palette to draw with.",
      startsGroup = true,
    ) { paletteMenuOpen = true },
    EditorTool(
      icon = Icons.Default.AspectRatio,
      contentDescription = "Output shape and size",
      explanation = "Chooses the shape of the picture and the size it is saved at.",
    ) { outputMenuOpen = true },
    // A generation setting rather than an edit, which is why it sits with the output menu and not with
    // the drawing tools: it changes what the picture is allowed to be made of, not the drawing.
    EditorTool(
      icon = Icons.Default.Repeat,
      contentDescription = "Wrap the pattern around the drawing's edges",
      explanation = "On: the drawing's edges are not edges, so the pattern wraps around them. " +
        "Off: the drawing's border is the picture's border. It lights up on its own when the picture " +
        "could not keep that border.",
      /*
       * Armed means the picture has no border to keep, which is true in two cases: wrapping was asked for,
       * or the edge rule could not be held and the solver dropped it (see WfcGenerator.Output.relaxedEdges).
       * Both leave the drawing's outer row and column out of the picture's border, so the button says the
       * same thing either way -- a button that stayed dark while the picture was busy relaxing its edges
       * would be telling the person drawing that their border was kept, which is exactly what is not true.
       */
      selected = wrapEdges || !edgeRuleKept,
      onClick = { onWrapEdgesChange(!wrapEdges) },
    ),
    /*
     * The two mirrors, side by side and independent of each other. Together they are the three flips a
     * 3x3 window has -- one axis, the other, and the half turn the two make -- so a drawing with both on
     * is arranged from four times the shapes it was drawn with.
     */
    EditorTool(
      icon = Icons.Default.SwapHoriz,
      contentDescription = "Mirror every pattern left to right, giving the picture more arrangements",
      explanation = "On: every 3x3 pattern is also available flipped left to right, so there are more " +
        "patterns to arrange. Off: only the patterns exactly as drawn.",
      // Armed means mirrored: the picture may then use shapes facing the other way.
      selected = mirrorHorizontal,
      onClick = { onMirrorHorizontalChange(!mirrorHorizontal) },
    ),
    EditorTool(
      icon = Icons.Default.SwapVert,
      contentDescription = "Mirror every pattern top to bottom, giving the picture more arrangements",
      explanation = "On: every 3x3 pattern is also available flipped top to bottom. Off: only the " +
        "patterns exactly as drawn. With the left-to-right mirror on as well, the half turn comes too.",
      // The other axis; the two do not depend on each other.
      selected = mirrorVertical,
      onClick = { onMirrorVerticalChange(!mirrorVertical) },
    ),

    EditorTool(
      icon = Icons.Default.Save,
      contentDescription = "Save the picture to the gallery",
      explanation = "Writes the picture as it is on the left to your gallery.",
      startsGroup = true,
    ) { onSave() },
    EditorTool(
      icon = Icons.Default.Share,
      contentDescription = "Share the picture",
      explanation = "Hands the picture to another app, like a message or a photo editor.",
    ) { onShare() },
    // Clearing changes the drawing but not its size; regeneration is requested all the same so the
    // preview does not keep showing a picture of pixels that are gone.
    EditorTool(
      icon = Icons.Default.Delete,
      contentDescription = "Clear the drawing",
      explanation = "Empties the drawing back to one colour. The size of the grid is kept.",
    ) {
      gridState.clear()
      onResize()
    },

    // Last, in its own group, and as far from the tools that edit the drawing as the strip goes: closing
    // the app throws away a drawing that has never been saved anywhere, so it must not be a button the
    // thumb can reach while it is painting. A held press says exactly that before it is tapped.
    EditorTool(
      icon = Icons.Default.PowerSettingsNew,
      contentDescription = "Close the app",
      explanation = "Closes the app. The drawing is kept and comes back next time; Save writes it out " +
        "as a picture.",
      startsGroup = true,
    ) { onExit() },
  )

  Box(modifier = modifier) {
    Row(modifier = Modifier.fillMaxSize()) {
      // The live bitmap preview: the same shape as the generated output, so what is shown here is the
      // picture at the aspect ratio it will be saved in.
      //
      // Capped at what is left after the columns beside it, and clipped. Without the cap a tall phone's
      // preview takes the width its own aspect ratio asks for -- the whole screen in portrait -- and the
      // toolbars are pushed off the row behind it; everything drawn inside the preview, the loading bar
      // included, then spans the full width and reads as if it were over the toolbar. The cap keeps the
      // preview inside its own column at every aspect ratio, and the clip means nothing it draws can
      // reach a neighbour even if a size ever disagrees.
      BoxWithConstraints {
        val previewWidth = (maxWidth - PREVIEW_CHROME_WIDTH).coerceAtLeast(minPreviewWidth())
        Box(
          modifier = Modifier
            .widthIn(max = previewWidth)
            .aspectRatio(output.aspect.ratio)
            .fillMaxHeight()
            .clipToBounds()
            .background(PREVIEW_BACKGROUND),
        ) {
          preview()
        }
      }

    VerticalDividerLine()

    // The menu hangs off the toolbar it is opened from, so it reads as part of it.
    Box {
      EditorToolbar(tools = editorTools, onExplain = { explain(it) })
      OutputMenu(
        expanded = outputMenuOpen,
        output = output,
        onDismiss = { outputMenuOpen = false },
        onOutputChange = {
          onOutputChange(it)
          outputMenuOpen = false
        },
      )
      PaletteMenu(
        expanded = paletteMenuOpen,
        palettes = palettes,
        currentPalette = activePalette,
        autoMigrateColors = autoMigrateColors,
        onAutoMigrateColorsChange = onAutoMigrateColorsChange,
        onDismiss = { paletteMenuOpen = false },
        onPaletteSelected = { palette ->
          // Keep the paint tools on a colour the new palette actually has. The caller migrates the
          // drawing itself; this is the same rule for the one colour that lives in the editor.
          if (autoMigrateColors) {
            selectedColor = palette.closestColor(selectedColor)
          }
          onPaletteChange(palette)
          paletteMenuOpen = false
        },
      )
    }

    VerticalDividerLine()

      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
      ) {
        val baseCellSizePx = LocalDensity.current.run { BUTTON_SIZE.toPx().roundToInt() }

        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
        ) {
          ZoomablePixelCanvas(
            gridState = gridState,
            selectedColor = selectedColor,
            mode = mode,
            selection = selection,
            baseCellSizePx = baseCellSizePx,
            marksEdgeBand = showsEdgeBand(edgeRuleKept, wrapEdges),
            onSelectionChange = { selection = it },
            onColorPicked = { selectedColor = it },
            onModeConsumed = { mode = EditorMode.Paint },
            onTap = onTap,
            onResize = onResize,
          )
        }

      }

    VerticalDividerLine()

      ColorToolbar(
        palette = activePalette,
        selectedColor = selectedColor,
        onColorSelected = { selectedColor = it },
      )
    }

    // Over the drawing rather than beside it: the explanation is about a button still under the finger,
    // and moving the drawing aside to make room for a sentence would be worse than covering a corner.
    SnackbarHost(
      hostState = snackbar,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp),
    ) { data ->
      Snackbar(
        snackbarData = data,
        containerColor = PANEL_BACKGROUND,
        contentColor = Color.White,
        dismissActionContentColor = Color.White,
      )
    }
  }
}

/**
 * The output shape and save size, as a menu off the toolbar.
 *
 * A menu rather than two cycling buttons: with no text on the toolbar, a button that cycled through
 * "9:16 / 1:1 / 16:9" could not show which one is current, and a menu can mark it.
 */
@Composable
private fun OutputMenu(
  expanded: Boolean,
  output: OutputSettings,
  onDismiss: () -> Unit,
  onOutputChange: (OutputSettings) -> Unit,
) {
  /*
   * Dark panel, light labels -- set explicitly rather than left to the theme.
   *
   * A dropdown from Material takes its container and its text colours from the colour scheme, and this
   * app's scheme is the stock one with only the background painted black: the menu came out as a light
   * panel with dark labels floating over a black editor. The panel is the same near-black as the toolbar
   * and the snackbar, and every label on it -- the two headings, the options, and the tick -- is light.
   */
  // The labels, the tick and its ink: one set of colours for every row in the menu.
  val itemColors = MenuDefaults.itemColors(
    textColor = MENU_TEXT_COLOR,
    leadingIconColor = MENU_TEXT_COLOR,
    trailingIconColor = MENU_TEXT_COLOR,
  )

  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    containerColor = PANEL_BACKGROUND,
  ) {
    MenuHeading("Shape")
    OutputAspect.entries.forEach { aspect ->
      DropdownMenuItem(
        text = { Text(aspect.label) },
        onClick = { onOutputChange(output.copy(aspect = aspect)) },
        colors = itemColors,
        trailingIcon = {
          if (output.aspect == aspect) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
          }
        },
      )
    }
    HorizontalDivider(color = MENU_DIVIDER_COLOR)
    /*
     * Two sizes, and they are separate: the one the picture is generated and shown at, and the one it is
     * written to a file at. They used to be one list, which read as though picking a bigger file size
     * would give a bigger picture on screen -- it cannot, because what is on screen is the bitmap the
     * solver's grid was drawn into.
     */
    MenuHeading("Preview size")
    PreviewScale.entries.forEach { preview ->
      val (width, height) = previewPixelSize(output.aspect, preview)
      DropdownMenuItem(
        text = { SizeRow(preview.label, width, height) },
        onClick = { onOutputChange(output.copy(preview = preview)) },
        colors = itemColors,
        trailingIcon = {
          if (output.preview == preview) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
          }
        },
      )
    }
    HorizontalDivider(color = MENU_DIVIDER_COLOR)
    MenuHeading("Save size")
    OutputScale.entries.forEach { scale ->
      val (width, height) = savedPixelSize(output.aspect, scale)
      DropdownMenuItem(
        text = { SizeRow(scale.label, width, height) },
        onClick = { onOutputChange(output.copy(scale = scale)) },
        colors = itemColors,
        trailingIcon = {
          if (output.scale == scale) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
          }
        },
      )
    }
  }
}

/**
 * One size row: the multiplier, then the pixels it means, dimmer than the multiplier.
 *
 * The multiplier alone is not the answer to "how big" -- the shape decides it as much as the multiplier
 * does -- so the size it actually means is written beside it.
 */
@Composable
private fun SizeRow(label: String, width: Int, height: Int) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label)
    Text(
      text = "→ ${width} x ${height}",
      color = Color.White.copy(alpha = 0.55f),
      fontSize = 12.sp,
      modifier = Modifier.padding(start = 10.dp),
    )
  }
}

/**
 * What the preview may not take from the editor beside it: the two toolbars and the two dividers between
 * the columns. Everything left over is the preview's, up to the width its aspect ratio asks for.
 */
private val PREVIEW_CHROME_WIDTH = (BUTTON_SIZE_DP_INT * 2).dp + 2.dp

/** Below this the preview would be a sliver rather than a picture, so the cap stops giving way here. */
private fun minPreviewWidth(): Dp = (BUTTON_SIZE_DP_INT * 4).dp
