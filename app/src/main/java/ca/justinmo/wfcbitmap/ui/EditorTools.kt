/**
 * The tool strip: what a tool is ([EditorTool]), how its button reads, and the cell shapes the drawing tools lay
 * down.
 *
 * A tool explains itself by long press, and [EditorMode] is the state a tap on the drawing acts on -- which is
 * exactly what the strip, the canvas and the screen have to agree about.
 */

package ca.justinmo.wfcbitmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChangeHistory
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
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ca.justinmo.wfcbitmap.FeatureFlags
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.foundation.lazy.grid.items as gridItems

/**
 * What the next gesture on the drawing does.
 *
 * [Paint] is the unarmed state: a tap paints a cell and a hold paints a stroke. Exactly one mode is armed
 * at a time, and every other mode is spent by the gesture that uses it, because a tool that stayed armed
 * would make the next tap do something the person did not ask for. A shape is drawn once and a selection
 * is made once; arming the tool again is one tap, and it is a tap the person chose to make.
 */
enum class EditorMode {
  Paint,
  Fill,
  ReplaceColor,
  Eyedropper,
  Duplicate,
  Line,
  Rectangle,
  Ellipse,
  Triangle,
  Select,
  ;

  /** True for the modes that draw a shape between the press and the release. */
  val isShape: Boolean
    get() = this == Line || this == Rectangle || this == Ellipse || this == Triangle
}

/**
 * The cells a shape from [from] to [to] covers, as flat indices into a [columns] x [rows] grid.
 *
 * Every shape is an outline, not a fill: a solid area is what the fill tool is for, and an outline
 * leaves the cells inside it alone so the drawing keeps whatever was there.
 */
internal fun shapeCells(
  mode: EditorMode,
  from: Pair<Int, Int>,
  to: Pair<Int, Int>,
  columns: Int,
  rows: Int,
): List<Int> {
  val cells = LinkedHashSet<Int>()
  fun add(col: Int, row: Int) {
    if (col in 0 until columns && row in 0 until rows) cells.add(row * columns + col)
  }

  /**
   * The cells a line from one point to another passes through.
   *
   * Returned rather than added so the triangle can mirror a side about its apex: rounding a column
   * half-way between two cells rounds the same way for both sides, which drew a lopsided triangle.
   */
  fun lineCells(startCol: Int, startRow: Int, endCol: Int, endRow: Int): List<Pair<Int, Int>> {
    val dCol = endCol - startCol
    val dRow = endRow - startRow
    val steps = maxOf(abs(dCol), abs(dRow))
    if (steps == 0) return listOf(startCol to startRow)
    return (0..steps).map { step ->
      val fraction = step.toFloat() / steps
      (startCol + dCol * fraction).roundToInt() to (startRow + dRow * fraction).roundToInt()
    }
  }

  val left = minOf(from.first, to.first)
  val right = maxOf(from.first, to.first)
  val top = minOf(from.second, to.second)
  val bottom = maxOf(from.second, to.second)

  when (mode) {
    EditorMode.Line -> lineCells(from.first, from.second, to.first, to.second)
      .forEach { (col, row) -> add(col, row) }

    EditorMode.Rectangle -> {
      for (col in left..right) {
        add(col, top)
        add(col, bottom)
      }
      for (row in top..bottom) {
        add(left, row)
        add(right, row)
      }
    }

    EditorMode.Ellipse -> {
      val centreCol = (left + right) / 2f
      val centreRow = (top + bottom) / 2f
      val radiusCol = (right - left) / 2f
      val radiusRow = (bottom - top) / 2f
      if (radiusCol <= 0f || radiusRow <= 0f) {
        // A degenerate box is the line the ellipse would have collapsed to.
        for (row in top..bottom) {
          for (col in left..right) add(col, row)
        }
      } else {
        // Sweep rows and columns separately: each pass gives the two extremes of the ellipse on that
        // line, and taking both passes leaves no gap where the curve turns steeply.
        for (row in top..bottom) {
          val dy = (row - centreRow) / radiusRow
          if (abs(dy) > 1f) continue
          val half = radiusCol * sqrt(1f - dy * dy)
          add((centreCol - half).roundToInt(), row)
          add((centreCol + half).roundToInt(), row)
        }
        for (col in left..right) {
          val dx = (col - centreCol) / radiusCol
          if (abs(dx) > 1f) continue
          val half = radiusRow * sqrt(1f - dx * dx)
          add(col, (centreRow - half).roundToInt())
          add(col, (centreRow + half).roundToInt())
        }
      }
    }

    /*
     * Point up: the apex is the middle of the box's top edge and the base is its bottom edge. One side
     * is drawn with the line tool's own arithmetic and the other is its mirror image about the apex,
     * so the two sides are alike to the cell and both meet the base at its corners.
     */
    EditorMode.Triangle -> {
      val apexCol = (left + right) / 2
      for (col in left..right) add(col, bottom)
      val side = lineCells(apexCol, top, left, bottom)
      for ((col, row) in side) {
        add(col, row)
        add(apexCol + (apexCol - col), row)
      }
    }

    else -> Unit
  }
  return cells.toList()
}


/** A stable name for every tool, so the editor can attach behaviour without repeating the tool data. */
enum class EditorToolId {
  Eyedropper,
  Fill,
  ReplaceColor,
  Line,
  Rectangle,
  Ellipse,
  Triangle,
  Select,
  Duplicate,
  Undo,
  Redo,
  FlipHorizontal,
  FlipVertical,
  Rotate,
  Crop,
  Palette,
  Output,
  Kernel,
  Scaler,
  Wrap,
  MirrorHorizontal,
  MirrorVertical,
  QuarterTurn,
  BestEffort,
  PhotoLibrary,
  PhotoCamera,
  Movie,
  Save,
  Share,
  Clear,
  Settings,
  Info,
  Exit,
}

/**
 * What a tool is, independent of the editor's current state: its glyph, its name for accessibility, and
 * the sentence a hold explains it with.
 *
 * The editor attaches the action and the current selected/enabled state to one of these; the first-launch
 * tour reads the same entries, so its toolbar cannot drift from the real one.
 */
data class EditorToolInfo(
  val id: EditorToolId,
  val icon: ImageVector,
  val contentDescription: String,
  val explanation: String,
  val startsGroup: Boolean = false,
  val iconRotationDegrees: Float = 0f,
)

/**
 * Every tool, in toolbar order, including any that [FeatureFlags] currently disables.
 *
 * [editorToolInfos] is the list both the editor and the tour walk; the editor supplies the actions and
 * the current state, while the tour only needs the icon and the explanation.
 */
internal val allEditorToolInfos: List<EditorToolInfo> =
  listOf(
    EditorToolInfo(
      id = EditorToolId.Eyedropper,
      icon = Icons.Default.Colorize,
      contentDescription = "Pick a colour from the drawing",
      explanation = "Takes the colour under your finger and makes it the one you draw with.",
    ),
    EditorToolInfo(
      id = EditorToolId.Fill,
      icon = Icons.Default.FormatColorFill,
      contentDescription = "Fill a connected area with the selected colour",
      explanation =
        "Paints the whole joined area of the tapped colour, up to anything drawn in another colour.",
    ),
    EditorToolInfo(
      id = EditorToolId.ReplaceColor,
      icon = Icons.Default.FindReplace,
      contentDescription = "Replace the tapped colour everywhere with the selected colour",
      explanation = "Swaps the tapped colour for the selected one everywhere on the drawing.",
    ),
    EditorToolInfo(
      id = EditorToolId.Line,
      icon = Icons.Default.Timeline,
      contentDescription = "Draw a line",
      explanation = "Drag from one cell to another; the line between the two points is painted.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Rectangle,
      icon = Icons.Default.CropSquare,
      contentDescription = "Draw a rectangle",
      explanation = "Drag out a box; its outline is painted and the cells inside it are left alone.",
    ),
    EditorToolInfo(
      id = EditorToolId.Ellipse,
      icon = Icons.Default.RadioButtonUnchecked,
      contentDescription = "Draw an ellipse",
      explanation = "Drag out a box; an ellipse filling it is painted as an outline, not a fill.",
    ),
    EditorToolInfo(
      id = EditorToolId.Triangle,
      icon = Icons.Default.ChangeHistory,
      contentDescription = "Draw a triangle",
      explanation = "Drag out a box; a triangle standing on its bottom edge is painted as an outline.",
    ),
    EditorToolInfo(
      id = EditorToolId.Select,
      icon = Icons.Default.CropFree,
      contentDescription = "Select an area to move",
      explanation = "Drag out a rectangle to select it, then drag inside it to move what it holds.",
    ),
    EditorToolInfo(
      id = EditorToolId.Duplicate,
      icon = Icons.Default.ContentCopy,
      contentDescription = "Copy an area: drag out the area, then drag the copy",
      explanation =
        "Drag out an area to copy it, then drag the copy where it belongs. The original stays where it is.",
    ),
    EditorToolInfo(
      id = EditorToolId.Undo,
      icon = Icons.AutoMirrored.Filled.Undo,
      contentDescription = "Undo",
      explanation = "Takes back the last edit, however many cells it touched.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Redo,
      icon = Icons.AutoMirrored.Filled.Redo,
      contentDescription = "Redo",
      explanation = "Puts back the edit you just took back.",
    ),
    EditorToolInfo(
      id = EditorToolId.FlipHorizontal,
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing left to right",
      explanation = "Flips the drawing horizontally, like a mirror held to one side.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.FlipVertical,
      icon = Icons.Default.Flip,
      contentDescription = "Mirror the drawing top to bottom",
      explanation = "Flips the drawing vertically, like a mirror laid underneath.",
      iconRotationDegrees = 90f,
    ),
    EditorToolInfo(
      id = EditorToolId.Rotate,
      icon = Icons.AutoMirrored.Filled.RotateRight,
      contentDescription = "Turn the drawing a quarter turn",
      explanation = "Turns the whole drawing a quarter turn clockwise, which swaps its width and height.",
    ),
    EditorToolInfo(
      id = EditorToolId.Crop,
      icon = Icons.Default.Crop,
      contentDescription = "Crop the drawing to what is drawn on it",
      explanation =
        "Trims the empty background around the drawing, leaving one cell of it, which is what the pattern " +
          "needs to wrap around the edges.",
    ),
    EditorToolInfo(
      id = EditorToolId.Palette,
      icon = Icons.Default.Palette,
      contentDescription = "Select color palette",
      explanation = "Chooses the color palette to draw with.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Output,
      icon = Icons.Default.AspectRatio,
      contentDescription = "Output shape and preview size",
      explanation = "Chooses the shape of the picture and the resolution it is generated and previewed at.",
    ),
    EditorToolInfo(
      id = EditorToolId.Kernel,
      icon = Icons.Default.GridOn,
      contentDescription = "Choose how many pixels make one tile",
      explanation =
        "Groups the drawing into cells for the 3x3 kernel: 3x3 means one pixel per cell, 6x6 means 2x2 " +
          "cells, up to 24x24 means 8x8 cells.",
    ),
    EditorToolInfo(
      id = EditorToolId.Scaler,
      icon = Icons.Default.AutoFixHigh,
      contentDescription = "Choose the pixel-art upscaling and save size",
      explanation =
        "Chooses how a saved file is enlarged -- square blocks, EPX or Scale3x -- and the multiplier it is " +
          "saved at.",
    ),
    EditorToolInfo(
      id = EditorToolId.Wrap,
      icon = Icons.Default.Repeat,
      contentDescription = "Wrap the pattern around the drawing's edges",
      explanation =
        "On: the drawing's edges are not edges, so the pattern wraps around them. Off: the drawing's " +
          "border is the picture's border. It lights up on its own when the picture could not keep that border.",
    ),
    EditorToolInfo(
      id = EditorToolId.MirrorHorizontal,
      icon = Icons.Default.SwapHoriz,
      contentDescription = "Mirror every pattern left to right, giving the picture more arrangements",
      explanation =
        "On: every 3x3 pattern is also available flipped left to right, so there are more patterns to " +
          "arrange. Off: only the patterns exactly as drawn.",
    ),
    EditorToolInfo(
      id = EditorToolId.MirrorVertical,
      icon = Icons.Default.SwapVert,
      contentDescription = "Mirror every pattern top to bottom, giving the picture more arrangements",
      explanation =
        "On: every 3x3 pattern is also available flipped top to bottom. Off: only the patterns exactly as " +
          "drawn. With the left-to-right mirror on as well, the half turn comes too.",
    ),
    EditorToolInfo(
      id = EditorToolId.QuarterTurn,
      icon = Icons.Default.Rotate90DegreesCw,
      contentDescription = "Turn every pattern a quarter turn, giving the picture more arrangements",
      explanation =
        "On: every 3x3 pattern is also available turned a quarter turn clockwise, so the picture can use " +
          "shapes lying on their side -- something neither mirror can offer. With the mirrors on as well, " +
          "every window has all eight orientations.",
    ),
    EditorToolInfo(
      id = EditorToolId.BestEffort,
      icon = Icons.Default.Healing,
      contentDescription = "Best effort: fill the picture when the drawing cannot be tiled exactly",
      explanation =
        "Best effort: when no exact tiling fits, every cell that has none is filled with the most likely " +
          "pattern instead. The picture stays made of the drawing's shapes, but it is an approximation: " +
          "the preview says how many cells could not be made to fit.",
    ),
    EditorToolInfo(
      id = EditorToolId.PhotoLibrary,
      icon = Icons.Default.PhotoLibrary,
      contentDescription = "Choose a pattern from the gallery",
      explanation =
        "Opens the app's pattern picker over the gallery and loads the drawing, palette and settings from " +
          "the selected pattern.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.PhotoCamera,
      icon = Icons.Default.PhotoCamera,
      contentDescription = "Use a camera photo as the drawing",
      explanation =
        "Takes a photo, reduces it to a small grid, posterises it to the selected palette, dithers it, and " +
          "uses that as the drawing to generate from. You can adjust each step before applying it.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Movie,
      icon = Icons.Default.Movie,
      contentDescription = "Make a panning video",
      explanation =
        "Generates a wide picture and pans across it left to right, looping back to the start. Saved as an " +
          "animated GIF or an MP4; the loop length and frame rate are adjustable.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Save,
      icon = Icons.Default.Save,
      contentDescription = "Save the pattern to the gallery",
      explanation =
        "Writes the drawing, its palette and its generation settings into the gallery as a PNG with an " +
          "extensible JSON description. This is the single save for the app.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Share,
      icon = Icons.Default.Share,
      contentDescription = "Share the picture",
      explanation = "Hands the picture to another app, like a message or a photo editor.",
    ),
    EditorToolInfo(
      id = EditorToolId.Clear,
      icon = Icons.Default.Delete,
      contentDescription = "Clear the drawing",
      explanation = "Empties the drawing back to one colour. The size of the grid is kept.",
    ),
    EditorToolInfo(
      id = EditorToolId.Settings,
      icon = Icons.Default.Settings,
      contentDescription = "Settings",
      explanation = "Adjusts the size of the tool and colour buttons.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Info,
      icon = Icons.Default.Info,
      contentDescription = "About this app",
      explanation =
        "Explains what the picture is, what the sizes and the edge settings change, and where saved files go.",
      startsGroup = true,
    ),
    EditorToolInfo(
      id = EditorToolId.Exit,
      icon = Icons.Default.PowerSettingsNew,
      contentDescription = "Close the app",
      explanation =
        "Closes the app. The drawing is kept and comes back next time; Save writes it out as a picture.",
      startsGroup = true,
    ),
  )

/**
 * The tools this build actually offers.
 *
 * Filtering in one place means the editor and the first-launch tour cannot disagree about which tools
 * exist: both read [editorToolInfos], which is the same catalog with the disabled features removed.
 */
internal fun editorToolInfosFor(): List<EditorToolInfo> =
  allEditorToolInfos.filter { info ->
    when (info.id) {
      EditorToolId.Triangle -> FeatureFlags.TRIANGLE_TOOL_ENABLED
      EditorToolId.PhotoCamera -> FeatureFlags.CAMERA_TOOL_ENABLED
      EditorToolId.BestEffort -> FeatureFlags.BEST_EFFORT_TOOL_ENABLED
      EditorToolId.FlipHorizontal,
      EditorToolId.FlipVertical,
      EditorToolId.Rotate -> FeatureFlags.TRANSFORM_TOOLS_ENABLED
      EditorToolId.Settings -> FeatureFlags.SETTINGS_TOOL_ENABLED
      else -> true
    }
  }

/** The tools this build offers. */
internal val editorToolInfos: List<EditorToolInfo> = editorToolInfosFor()

/**
 * One entry in the vertical toolbar between the preview and the drawing: a catalog entry plus the
 * editor's current state and the action a tap runs.
 *
 * @param info the glyph, name and default explanation from [editorToolInfos].
 * @param explanation the sentence a hold shows; it differs from [EditorToolInfo.explanation] only where a
 *   setting changes what the tool can do (the mirrors, the turn and the crop).
 * @param selected null for a plain action; true or false for a *mode* tool, which changes what the next
 *   gesture on the drawing does. A mode shows whether it is armed, to the eye and to a screen reader.
 * @param enabled false draws the button greyed out and ignores clicks.
 * @param onClick run when the button is clicked.
 */
class EditorTool(
  val info: EditorToolInfo,
  val explanation: String = info.explanation,
  val selected: Boolean? = null,
  val enabled: Boolean = true,
  val onClick: () -> Unit,
) {
  val icon: ImageVector get() = info.icon
  val contentDescription: String get() = info.contentDescription
  val iconRotationDegrees: Float get() = info.iconRotationDegrees
  val startsGroup: Boolean get() = info.startsGroup
}

/**
 * Strip or grid of editor tools, scrolled: there are more tools than fit on a phone in one column.
 *
 * An empty list draws nothing rather than an empty strip, so a screen with no tools keeps the drawing
 * as wide as it would be without the toolbar.
 *
 * @param tools drawn in list order.
 * @param onExplain called with a tool when its button is held down, for the caller to explain it.
 * @param columns 1 is the landscape strip; 4 is the portrait grid.
 */
@Composable
fun EditorToolbar(
  tools: List<EditorTool>,
  onExplain: (EditorTool) -> Unit = {},
  buttonSize: androidx.compose.ui.unit.Dp = BUTTON_SIZE,
  columns: Int = 1,
) {
  if (tools.isEmpty()) return
  val columnCount = columns.coerceAtLeast(1)

  if (columnCount == 1) {
    LazyColumn(
      modifier = Modifier
        .width(buttonSize)
        .fillMaxHeight(),
    ) {
      items(tools) { tool ->
        EditorToolButton(tool, onExplain, buttonSize, showSeparators = true)
      }
    }
  } else {
    LazyVerticalGrid(
      columns = GridCells.Fixed(columnCount),
      modifier = Modifier
        .width(buttonSize * columnCount.toFloat())
        .fillMaxHeight(),
    ) {
      gridItems(tools) { tool ->
        EditorToolButton(tool, onExplain, buttonSize, showSeparators = false)
      }
    }
  }
}

/** One icon button, wearing the same dark treatment as the canvas's resize buttons. */
@Composable
private fun EditorToolButton(
  tool: EditorTool,
  onExplain: (EditorTool) -> Unit,
  buttonSize: androidx.compose.ui.unit.Dp,
  showSeparators: Boolean,
) {
  val shape = RoundedCornerShape(2.dp)
  val armed = tool.selected == true
  Column {
    if (showSeparators && tool.startsGroup) {
      HorizontalDivider(
        thickness = 3.dp,
        color = Color.White.copy(alpha = 0.15f),
      )
    }
    Box(
      modifier = Modifier
        .size(buttonSize)
        .clip(shape)
        // An armed mode is lit up: it changes what the next gesture does, so it must not look like the
        // buttons around it.
        .background(
          if (armed) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.4f)
        )
        .border(
          width = 0.5f.dp,
          color = Color.White.copy(alpha = if (armed) 0.55f else 0.2f),
          shape = shape,
        )
        /*
         * One click modifier for both gestures. `selectable` would carry the armed state to a screen
         * reader on its own, but it has no long press, so the mode tools get their toggle semantics from
         * the modifier below instead -- Role.Button plus the selected property, which is exactly what
         * `selectable` was setting. A held button explains its tool; it never acts on the drawing.
         */
        .combinedClickable(
          enabled = tool.enabled,
          role = Role.Button,
          onLongClickLabel = "Explain this tool",
          onLongClick = { onExplain(tool) },
          onClick = tool.onClick,
        )
        // The description belongs to the button, not to the glyph inside it, so the whole square is one
        // accessibility node instead of a described icon sitting next to an undescribed button.
        .semantics {
          contentDescription = tool.contentDescription
          tool.selected?.let { selected = it }
        },
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = tool.icon,
        contentDescription = null,
        tint = if (tool.enabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.15f),
        modifier = Modifier
          .size(buttonSize * TOOL_ICON_RATIO)
          .rotate(tool.iconRotationDegrees),
      )
    }
  }
}

/** Glyph size relative to the button, small enough to leave the button a visible margin. */
private const val TOOL_ICON_RATIO = 20f / 38f

/**
 * Calls [onPoint] for points spaced at most [step] apart along the straight line from [from] to [to],
 * ending exactly on [to].
 *
 * A touch stream reports one position per input event, and a fast drag can cross several cells between
 * two events. Painting only the reported positions would leave gaps in a stroke, so the line between
 * them is walked instead. A segment of zero length reports [to] once, so a stroke that stops still
 * paints where it stopped.
 */
internal fun sampleSegment(
  from: Offset,
  to: Offset,
  step: Float,
  onPoint: (Offset) -> Unit,
) {
  require(step > 0f) { "step must be positive, was $step" }
  val delta = to - from
  val distance = delta.getDistance()
  if (distance == 0f) {
    onPoint(to)
    return
  }
  val steps = ceil(distance / step).toInt().coerceAtLeast(1)
  for (i in 1..steps) {
    onPoint(from + delta * (i / steps.toFloat()))
  }
}