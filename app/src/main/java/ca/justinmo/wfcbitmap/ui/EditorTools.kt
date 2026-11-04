/**
 * The tool strip: what a tool is ([EditorTool]), how its button reads, and the cell shapes the drawing tools lay
 * down.
 *
 * A tool explains itself by long press, and [EditorMode] is the state a tap on the drawing acts on -- which is
 * exactly what the strip, the canvas and the screen have to agree about.
 */

package ca.justinmo.wfcbitmap.ui
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

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


/**
 * One entry in the vertical toolbar between the preview and the drawing.
 *
 * @param icon glyph drawn in the square button, from the Material icon set.
 * @param contentDescription what the glyph means. The button has no visible label, so this is the
 *   only thing a screen reader (or a UI test) can read.
 * @param explanation one sentence saying what the tool does, shown in a snackbar when the button is
 *   held down. A glyph cannot teach a tool it does not resemble, and this toolbar has no room for a
 *   caption, so holding is how you ask what a button is before using it on a drawing you care about.
 * @param selected null for a plain action; true or false for a *mode* tool, which changes what the next
 *   gesture on the drawing does. A mode shows whether it is armed, to the eye and to a screen reader.
 * @param enabled false draws the button greyed out and ignores clicks.
 * @param iconRotationDegrees turns the glyph, which is how one icon serves both mirror directions.
 * @param startsGroup draws a divider above the button, to group tools that read together.
 * @param onClick run when the button is clicked.
 */
class EditorTool(
  val icon: ImageVector,
  val contentDescription: String,
  val explanation: String,
  val selected: Boolean? = null,
  val enabled: Boolean = true,
  val iconRotationDegrees: Float = 0f,
  val startsGroup: Boolean = false,
  val onClick: () -> Unit,
)

/**
 * Vertical strip of editor tools, scrolled: there are more tools than fit on a phone in landscape.
 *
 * An empty list draws nothing rather than an empty strip, so a screen with no tools keeps the drawing
 * as wide as it would be without the toolbar.
 *
 * @param tools drawn top to bottom in list order.
 * @param onExplain called with a tool when its button is held down, for the caller to explain it.
 */
@Composable
fun EditorToolbar(
  tools: List<EditorTool>,
  onExplain: (EditorTool) -> Unit = {},
  buttonSize: androidx.compose.ui.unit.Dp = BUTTON_SIZE,
) {
  if (tools.isEmpty()) return

  LazyColumn(
    modifier = Modifier
      .width(buttonSize)
      .fillMaxHeight(),
  ) {
    items(tools) { tool ->
      EditorToolButton(tool, onExplain, buttonSize)
    }
  }
}

/** One icon button, wearing the same dark treatment as the canvas's resize buttons. */
@Composable
private fun EditorToolButton(
  tool: EditorTool,
  onExplain: (EditorTool) -> Unit,
  buttonSize: androidx.compose.ui.unit.Dp,
) {
  val shape = RoundedCornerShape(2.dp)
  val armed = tool.selected == true
  Column {
    if (tool.startsGroup) {
      HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 3.dp),
        thickness = 1.dp,
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