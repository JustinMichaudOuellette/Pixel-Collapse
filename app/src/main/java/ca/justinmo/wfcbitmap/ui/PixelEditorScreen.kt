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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.justinmo.wfcbitmap.editor.CellRect
import ca.justinmo.wfcbitmap.editor.PixelGridState
import ca.justinmo.wfcbitmap.output.EditorMessage
import ca.justinmo.wfcbitmap.output.KernelSize
import ca.justinmo.wfcbitmap.output.OutputAspect
import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.OutputSettings
import ca.justinmo.wfcbitmap.output.PixelArtScaler
import ca.justinmo.wfcbitmap.output.PreviewScale
import ca.justinmo.wfcbitmap.output.previewPixelSize
import ca.justinmo.wfcbitmap.output.savedPixelSize
import ca.justinmo.wfcbitmap.palette.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.Palette as PaletteIcon

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
   * Called when the Save tool is tapped: it writes the pattern PNG with its JSON metadata into the
   * gallery. The caller owns the snapshot and the naming dialog.
   */
  onSave: () -> Unit = {},
  /** Called when the Share tool is tapped; the caller owns the bitmap and the chooser. */
  onShare: () -> Unit = {},
  /** Called when the pattern picker tool is tapped. */
  onLoadPattern: () -> Unit = {},
  /** Called when the camera-photo tool is tapped. */
  onCameraPhoto: () -> Unit = {},
  /** Called when the Video export tool is tapped. */
  onExportVideo: () -> Unit = {},
  /**
   * Called when the Close tool is tapped: leaving an app is the activity's business, not the editor's, so
   * the caller closes itself rather than this screen reaching for a context to finish.
   */
  onExit: () -> Unit = {},
  /** Size of the square tool and colour buttons, in dp; adjustable from the Settings tool. */
  toolButtonSize: Int = BUTTON_SIZE_DP_INT,
  onToolButtonSizeChange: (Int) -> Unit = {},
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
  /** Progress of a long video export, 0..1; null when nothing is being exported. */
  videoProgress: Float? = null,
  /** Label beside the export progress bar. */
  videoLabel: String? = null,
  /** Cancels the running export; the progress card is visible only while it runs. */
  onCancelVideoExport: () -> Unit = {},
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
   * Whether every pattern is also offered turned a quarter turn; see [EditorTool].
   *
   * The third symmetry setting, and the one neither mirror reaches: a mirror never turns a pattern on its
   * side. It composes with them rather than replacing them -- with a mirror on as well the picture may use
   * all eight orientations of a window.
   */
  quarterTurn: Boolean = false,
  /** Called when the quarter-turn toggle is tapped, with the state it should have afterwards. */
  onQuarterTurnChange: (Boolean) -> Unit = {},
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
  var selectedColor by remember {
    mutableStateOf(
      initialColor ?: activePalette.colors.minByOrNull { it.luminance() } ?: Color.Black,
    )
  }

  LaunchedEffect(activePalette) {
    if (selectedColor !in activePalette.colors) {
      selectedColor = activePalette.colors.minByOrNull { it.luminance() } ?: Color.Black
    }
  }

  // Reported on every change, including the one at first composition, so the caller can save it.
  LaunchedEffect(selectedColor) { onColorChange(selectedColor) }

  // The armed tool, and the area the selection tool has marked out. Both are the editor's own state:
  // they change what a gesture means, not what the generated picture is.
  var mode by remember { mutableStateOf(EditorMode.Paint) }
  var selection by remember { mutableStateOf<CellRect?>(null) }
  var outputMenuOpen by remember { mutableStateOf(false) }
  var kernelMenuOpen by remember { mutableStateOf(false) }
  var scalerMenuOpen by remember { mutableStateOf(false) }
  var paletteMenuOpen by remember { mutableStateOf(false) }
  // The explanation of the app, over everything. It is the editor's own state like the menus are: reading
  // it changes nothing about the drawing or the picture, so it belongs here rather than to the caller.
  var infoOpen by remember { mutableStateOf(false) }
  var settingsOpen by remember { mutableStateOf(false) }


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
  /*
   * Whether the line currently on (or about to be on) screen has an action button.
   *
   * This is set synchronously when a message with an action is handed over, before the snackbar
   * coroutine has actually run. Checking SnackbarHostState.currentSnackbarData instead is too late:
   * a notice arriving in the same frame sees no current line, cancels the pending action job, and
   * shows itself -- which is how "Saved ... View" ended up as an actionless notice.
   */
  var snackbarHasAction by remember { mutableStateOf(false) }

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
    snackbarHasAction = actionLabel != null
    snackbarJob = snackbarScope.launch {
      val outcome = snackbar.showSnackbar(
        message = message,
        actionLabel = actionLabel,
        duration = duration,
      )
      snackbarHasAction = false
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
    // Never replace a line that has a button: after a save or an export, "View" is the whole point
    // of the snackbar, and a notice arriving from the rebuilt preview must not take it away.
    if (notice != null && !snackbarHasAction) showSnackbar(notice, SnackbarDuration.Short)
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
      icon = Icons.Default.ChangeHistory,
      contentDescription = "Draw a triangle",
      explanation = "Drag out a box; a triangle standing on its bottom edge is painted as an outline.",
      selected = mode == EditorMode.Triangle,
    ) { arm(EditorMode.Triangle) },
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

    /*
     * The flips and the turn are off while the matching symmetry is being applied to every pattern.
     * Mirroring or turning the drawing as well would say the same thing twice -- the picture already has
     * those orientations of everything -- and a button that looks live but cannot change the picture is
     * worse than one that says so.
     */
    EditorTool(
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing left to right",
      explanation = if (mirrorHorizontal) {
        "Off while the patterns are mirrored left to right: the picture already has both orientations."
      } else {
        "Flips the drawing horizontally, like a mirror held to one side."
      },
      enabled = !mirrorHorizontal,
      startsGroup = true,
    ) {
      gridState.flipHorizontal()
      onResize()
    },
    EditorTool(
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing top to bottom",
      explanation = if (mirrorVertical) {
        "Off while the patterns are mirrored top to bottom: the picture already has both orientations."
      } else {
        "Flips the drawing vertically, like a mirror laid underneath."
      },
      enabled = !mirrorVertical,
      iconRotationDegrees = 90f,
    ) {
      gridState.flipVertical()
      onResize()
    },
    EditorTool(
      icon = Icons.Default.RotateRight,
      contentDescription = "Turn the drawing a quarter turn",
      explanation = if (quarterTurn) {
        "Off while the patterns are turned a quarter turn: the picture already has every turning."
      } else {
        "Turns the whole drawing a quarter turn clockwise, which swaps its width and height."
      },
      enabled = !quarterTurn,
    ) {
      gridState.rotateClockwise()
      onResize()
    },
    /*
     * Crops to what is drawn, keeping the margin the solver needs: one cell when the edges wrap (the
     * pattern can run off the drawing and back on), two when the drawing's border is the picture's
     * border, because then a window centred on a border cell has to be complete. The border colour is
     * the drawing's own background, detected from its cells.
     */
    EditorTool(
      icon = Icons.Default.Crop,
      contentDescription = "Crop the drawing to what is drawn on it",
      explanation = if (wrapEdges || !edgeRuleKept) {
        "Trims the empty background around the drawing, leaving one cell of it, which is what the " +
          "pattern needs to wrap around the edges."
      } else {
        "Trims the empty background around the drawing, leaving two cells of it, which is what the " +
          "drawing's border needs to be the picture's border."
      },
    ) {
      if (gridState.cropToContent(borderCells = if (wrapEdges || !edgeRuleKept) 1 else 2)) onResize()
    },

    EditorTool(
      icon = Icons.Default.PaletteIcon,
      contentDescription = "Select color palette",
      explanation = "Chooses the color palette to draw with.",
      startsGroup = true,
    ) { paletteMenuOpen = true },
    EditorTool(
      icon = Icons.Default.AspectRatio,
      contentDescription = "Output shape and preview size",
      explanation = "Chooses the shape of the picture and the resolution it is generated and previewed at.",
    ) { outputMenuOpen = true },
    // How big one cell of the 3x3 kernel is: one drawing pixel, or a group of 2x2 up to 8x8 pixels.
    EditorTool(
      icon = Icons.Default.GridOn,
      contentDescription = "Choose how many pixels make one tile",
      explanation = "Groups the drawing into cells for the 3x3 kernel: 3x3 means one pixel per cell, " +
        "6x6 means 2x2 cells, up to 24x24 means 8x8 cells.",
      selected = output.kernel.grouped,
    ) { kernelMenuOpen = true },
    // A saving decision, not a drawing one: EPX only changes the file, never the grid being painted.
    // The save-size multiplier lives here too, because the algorithm is what decides which sizes exist.
    EditorTool(
      icon = Icons.Default.AutoFixHigh,
      contentDescription = "Choose the pixel-art upscaling and save size",
      explanation = "Chooses how a saved file is enlarged -- square blocks, EPX or Scale3x -- and the " +
        "multiplier it is saved at.",
      selected = output.scaler != PixelArtScaler.Nearest,
    ) { scalerMenuOpen = true },
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
     * The symmetry row: the two mirrors and the quarter turn, one button each and none of them switching
     * another on.
     *
     * The mirrors between them are the three flips a 3x3 window has -- one axis, the other, and the half
     * turn the two make -- so a drawing with both on is arranged from four times the shapes it was drawn
     * with. The turn is the move the first two cannot give, which is why it is its own setting rather than a
     * third mirror: with all three on the picture may use all eight orientations of every window, the
     * whole symmetry group of a square.
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
      icon = Icons.Default.Rotate90DegreesCw,
      contentDescription = "Turn every pattern a quarter turn, giving the picture more arrangements",
      explanation = "On: every 3x3 pattern is also available turned a quarter turn clockwise, so the " +
        "picture can use shapes lying on their side -- something neither mirror can offer. With the " +
        "mirrors on as well, every window has all eight orientations.",
      // A third axis of its own: a mirror never turns a pattern on its side, so this is not the two
      // mirrors by another name.
      selected = quarterTurn,
      onClick = { onQuarterTurnChange(!quarterTurn) },
    ),

    // The single save writes the pattern, not a flattened picture: the PNG carries the drawing and its
    // JSON description, and the picker reads it back from the gallery.
    EditorTool(
      icon = Icons.Default.PhotoLibrary,
      contentDescription = "Choose a pattern from the gallery",
      explanation = "Opens the app's pattern picker over the gallery and loads the drawing, palette and " +
        "settings from the selected pattern.",
      startsGroup = true,
    ) { onLoadPattern() },

    // A photo as a new drawing: downscale, posterise to the selected palette, dither, then generate.
    EditorTool(
      icon = Icons.Default.PhotoCamera,
      contentDescription = "Use a camera photo as the drawing",
      explanation = "Takes a photo, reduces it to a small grid, posterises it to the selected palette, " +
        "dithers it, and uses that as the drawing to generate from. You can adjust each step before " +
        "applying it.",
      startsGroup = true,
    ) { onCameraPhoto() },

    // A movie of the drawing: a wide panorama is generated and a frame-sized window pans across it, looping.
    EditorTool(
      icon = Icons.Default.Movie,
      contentDescription = "Make a panning video",
      explanation = "Generates a wide picture and pans across it left to right, looping back to the start. " +
        "Saved as an animated GIF or an MP4; the loop length and frame rate are adjustable.",
      startsGroup = true,
    ) { onExportVideo() },

    EditorTool(
      icon = Icons.Default.Save,
      contentDescription = "Save the pattern to the gallery",
      explanation = "Writes the drawing, its palette and its generation settings into the gallery as a " +
        "PNG with an extensible JSON description. This is the single save for the app.",
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

    EditorTool(
      icon = Icons.Default.Settings,
      contentDescription = "Settings",
      explanation = "Adjusts the size of the tool and colour buttons.",
      startsGroup = true,
    ) { settingsOpen = true },

    // What the app does, in its own words. Its own group, next to the close button: reading is not editing,
    // and the tools that change the drawing should not have to be passed on the way to it.
    EditorTool(
      icon = Icons.Default.Info,
      contentDescription = "About this app",
      explanation = "Explains what the picture is, what the sizes and the edge settings change, and where " +
        "saved files go.",
      startsGroup = true,
    ) { infoOpen = true },

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

  val density = LocalDensity.current
  // The panel's horizontal position. Until the slot is measured there is nothing to clamp to, so the
  // first frame falls back to the leftmost position and the effect below stores it as the real value.
  var previewOffset by remember { mutableStateOf<Float?>(null) }
  // Whether the person has dragged the preview. Until they have, every layout change keeps it at the
  // leftmost position: the window animates up to full height on launch, and the panel has to follow that
  // instead of keeping the leftmost offset of the smaller, first frame.
  var previewDragged by remember { mutableStateOf(false) }
  BoxWithConstraints(modifier = modifier) {
    // The column is exactly as wide as the picture is drawn at this height, so nothing is left to pad
    // on either side of it. The panel is the same width, whatever the shape: a picture wider than the
    // screen's 9:16 slot still draws over the editor, which is what the drag is for.
    val toolButtonDp = toolButtonSize.dp
    val maxPreviewSlot = (maxWidth - (toolButtonDp * 2 + 2.dp))
      .coerceAtLeast(minPreviewWidth(toolButtonDp))
    val availableHeightPx = with(density) { maxHeight.toPx() }.toInt()
    val drawnPreviewWidthPx = previewColumnWidthPx(output.aspect, output.preview, availableHeightPx)
    val drawnPreviewWidth = with(density) { drawnPreviewWidthPx.toDp() }
    val previewSlot = drawnPreviewWidth.coerceIn(minPreviewWidth(toolButtonDp), maxPreviewSlot)
    val previewPanelWidth = maxOf(previewSlot, drawnPreviewWidth)
    val previewPanelWidthPx = with(density) { previewPanelWidth.toPx() }
    // Stop where the panel's right edge meets the editor's left edge: it may leave the editor, but it
    // never retracts past that edge and leaves the preview slot empty.
    val previewSlotPx = with(density) { previewSlot.toPx() }
    val previewMinOffset = (previewSlotPx - previewPanelWidthPx).coerceAtMost(0f)
    // Opening the app leaves the preview pushed left: a picture wider than its slot starts beside the
    // editor instead of over it, and the person drags it back when they want to see it whole. It keeps
    // tracking that edge until the first drag, so the launch animation cannot leave it short.

    LaunchedEffect(previewMinOffset, previewPanelWidthPx) {
      previewOffset = if (previewDragged) {
        (previewOffset ?: previewMinOffset).coerceIn(previewMinOffset, 0f)
      } else {
        previewMinOffset
      }
    }

    @Composable
    fun ToolArea(columns: Int = 1) {
      // The menu hangs off the toolbar it is opened from, so it reads as part of it.
      Box {
        EditorToolbar(
          tools = editorTools,
          onExplain = { explain(it) },
          buttonSize = toolButtonDp,
          columns = columns,
        )
        OutputMenu(
          expanded = outputMenuOpen,
          output = output,
          onDismiss = { outputMenuOpen = false },
          onOutputChange = {
            onOutputChange(it.withSupportedScale())
            outputMenuOpen = false
          },
        )
        KernelMenu(
          expanded = kernelMenuOpen,
          current = output.kernel,
          onDismiss = { kernelMenuOpen = false },
          onKernelChange = { kernel ->
            onOutputChange(output.copy(kernel = kernel))
            kernelMenuOpen = false
          },
        )
        ScalerMenu(
          expanded = scalerMenuOpen,
          aspect = output.aspect,
          preview = output.preview,
          current = output.scaler,
          currentScale = output.scale,
          onDismiss = { scalerMenuOpen = false },
          onSelect = { scaler, scale ->
            onOutputChange(output.copy(scaler = scaler, scale = scale))
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
    }

    if (maxWidth < maxHeight) {
      // The portrait preview is 960 physical pixels tall, capped only when the screen is genuinely too
      // short to leave the colour strip and drawing any room.
      val portraitPreviewHeight = (960f / density.density).dp
        .coerceAtMost(maxHeight * 0.65f)
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier
            .height(portraitPreviewHeight)
            .fillMaxWidth(),
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .background(PREVIEW_BACKGROUND),
          ) {
            preview()
          }
          VerticalDividerLine()
          ToolArea(columns = 4)
        }
        HorizontalDivider(color = MENU_DIVIDER_COLOR)
        ColorStrip(
          palette = activePalette,
          selectedColor = selectedColor,
          onColorSelected = { selectedColor = it },
          buttonSize = toolButtonDp,
        )
        HorizontalDivider(color = MENU_DIVIDER_COLOR)
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        ) {
          ZoomablePixelCanvas(
            gridState = gridState,
            selectedColor = selectedColor,
            mode = mode,
            selection = selection,
            baseCellSizePx = LocalDensity.current.run { BUTTON_SIZE.toPx().roundToInt() },
            gridStep = if (output.kernel.grouped) output.kernel.cellSize else 1,
            marksEdgeBand = showsEdgeBand(edgeRuleKept, wrapEdges),
            onSelectionChange = { selection = it },
            onColorPicked = { selectedColor = it },
            onModeConsumed = { mode = EditorMode.Paint },
            onTap = onTap,
            onResize = onResize,
          )
        }
      }
    } else {
    Row(modifier = Modifier.fillMaxSize()) {
      // The slot the 9:16 preview occupies. A wider aspect draws over the part of the editor to the
      // right of it instead of taking width away from the drawing.
      Spacer(modifier = Modifier.width(previewSlot))

    VerticalDividerLine()

    ToolArea()

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
            gridStep = if (output.kernel.grouped) output.kernel.cellSize else 1,
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
        buttonSize = toolButtonDp,
      )
    }

    // The live preview, over the editor rather than beside it. Its slot above stays 9:16, but a wider
    // aspect draws across the toolbar and drawing; dragging it left moves it off the editor, stopping at
    // the editor's edge rather than past it.
    Box(
      modifier = Modifier
        .align(Alignment.TopStart)
        .offset { IntOffset((previewOffset ?: previewMinOffset).roundToInt(), 0) }
        .requiredWidth(previewPanelWidth)
        .fillMaxHeight()
        .background(PREVIEW_BACKGROUND)
        .pointerInput(previewMinOffset, previewPanelWidthPx) {
          detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            previewDragged = true
            previewOffset = ((previewOffset ?: previewMinOffset) + dragAmount)
              .coerceIn(previewMinOffset, 0f)
          }
        },
    ) {
      preview()
    }
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
        containerColor = PANEL_BACKGROUND,
        contentColor = Color.White,
        actionContentColor = Color.White,
        dismissActionContentColor = Color.White,
        action = data.visuals.actionLabel?.let { label ->
          {
            TextButton(onClick = { data.performAction() }) {
              Text(label, color = Color.White)
            }
          }
        },
      ) {
        Text(data.visuals.message, color = Color.White)
      }
    }

    if (videoProgress != null) {
      VideoExportProgress(
        label = videoLabel ?: "Exporting…",
        progress = videoProgress,
        onCancel = onCancelVideoExport,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = 76.dp),
      )
    }

    EditorSettingsDialog(
      open = settingsOpen,
      buttonSize = toolButtonSize,
      onButtonSizeChange = onToolButtonSizeChange,
      onDismiss = { settingsOpen = false },
    )

    // Last, so it covers the editor and the snackbar with it: it is a reading screen, and nothing under it
    // is meant to be touched while it is up.
    EditorInfoScreen(open = infoOpen, onClose = { infoOpen = false })
  }
}

/**
 * The output shape and preview size, as a menu off the toolbar.
 *
 * The save size is not here: it belongs to the upscaling tool, where the algorithm decides which
 * multipliers can be made.
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
     * The resolution the picture itself is generated at -- the number of cells the solver fills. The size
     * it is written to a file at is the upscaling tool's decision, because the algorithm is what decides
     * which multipliers are possible; keeping it here as well would show a list the algorithm might not
     * be able to make.
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
  }
}

/**
 * The pattern-size picker: how big one cell of the 3x3 kernel is.
 *
 * 3x3 is one drawing pixel per kernel cell. 6x6 is a 3x3 kernel of 2x2 cells, 9x9 is 3x3 cells, and so on
 * up to 24x24 with 8x8 cells; the drawing is grouped into cells of that side before the ordinary 3x3
 * model runs.
 */
@Composable
private fun KernelMenu(
  expanded: Boolean,
  current: KernelSize,
  onDismiss: () -> Unit,
  onKernelChange: (KernelSize) -> Unit,
) {
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
    MenuHeading("Tile grouping")
    KernelSize.entries.forEach { kernel ->
      DropdownMenuItem(
        text = {
          Column(modifier = Modifier.width(220.dp)) {
            Text(
              text = kernel.label,
              color = MENU_TEXT_COLOR,
              fontSize = 14.sp,
            )
            Text(
              text = if (kernel.grouped) {
                "${kernel.cellSize}×${kernel.cellSize} cells in a 3×3 kernel"
              } else {
                "One pixel per cell (the classic pattern)"
              },
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
        },
        onClick = { onKernelChange(kernel) },
        colors = itemColors,
        trailingIcon = {
          if (kernel == current) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
          }
        },
      )
    }
  }
}

/**
 * The upscaling picker: the algorithm and the save-size multiplier in one panel.
 *
 * The two are one decision -- an algorithm only has the multipliers it can actually produce (EPX has no
 * 1x) -- so they are chosen together here. Picking an algorithm keeps the panel open and moves the size
 * list to the sizes that algorithm can make, letting the current size stand when it is still possible.
 */
@Composable
private fun ScalerMenu(
  expanded: Boolean,
  aspect: OutputAspect,
  preview: PreviewScale,
  current: PixelArtScaler,
  currentScale: OutputScale,
  onDismiss: () -> Unit,
  onSelect: (PixelArtScaler, OutputScale) -> Unit,
) {
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
    MenuHeading("Upscaling")
    PixelArtScaler.entries.forEach { scaler ->
      DropdownMenuItem(
        text = {
          Column(modifier = Modifier.width(220.dp)) {
            Text(
              text = scaler.label,
              color = MENU_TEXT_COLOR,
              fontSize = 14.sp,
            )
            Text(
              text = when (scaler) {
                PixelArtScaler.Nearest -> "Square blocks, any size"
                PixelArtScaler.Epx -> "Rounded corners; 2x, 4x or 16x"
                PixelArtScaler.Scale3x -> "Rounded corners; 3x, 6x or 9x"
                PixelArtScaler.Eagle -> "Corner from the diagonal; 2x, 4x or 16x"
                PixelArtScaler.Smooth -> "Blended corners; 2x, 4x or 16x"
                PixelArtScaler.Hq2x -> "Smoothest diagonals; 2x, 4x or 16x"
                PixelArtScaler.Sai -> "Blended edges; 2x, 4x or 16x"
                PixelArtScaler.SuperSai -> "Edge-aware blending; 2x, 4x or 16x"
                PixelArtScaler.SuperEagle -> "Eagle with blending; 2x, 4x or 16x"
              },
              color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
              fontSize = 11.sp,
              modifier = Modifier.padding(top = 2.dp),
            )
          }
        },
        onClick = {
          /*
           * The panel stays open: the size list under the algorithms is part of the same choice, and
           * closing here would make everyone reopen the tool just to pick how big the file is.
           */
          val scale = if (currentScale in scaler.supportedScales) {
            currentScale
          } else {
            OutputScale.entries.first { it in scaler.supportedScales }
          }
          onSelect(scaler, scale)
        },
        colors = itemColors,
        trailingIcon = {
          if (scaler == current) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MENU_TEXT_COLOR)
          }
        },
      )
    }
    HorizontalDivider(color = MENU_DIVIDER_COLOR)
    MenuHeading("Save size")
    OutputScale.entries
      .filter { it in current.supportedScales }
      .forEach { scale ->
        val (width, height) = savedPixelSize(aspect, scale, preview)
        DropdownMenuItem(
          text = { SizeRow(scale.label, width, height) },
          onClick = {
            onSelect(current, scale)
            onDismiss()
          },
          colors = itemColors,
          trailingIcon = {
            if (currentScale == scale) {
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

/** Below this the preview would be a sliver rather than a picture, so the cap stops giving way here. */
private fun minPreviewWidth(toolButtonSize: Dp): Dp = toolButtonSize * 4