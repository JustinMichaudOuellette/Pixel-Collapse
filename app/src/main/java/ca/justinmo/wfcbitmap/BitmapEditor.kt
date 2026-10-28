package ca.justinmo.wfcbitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BitmapEditor {

  /** Smallest grid allowed in either axis, in cells. */
  const val MIN_GRID_SIZE = 7

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

  /**
   * A rectangle of cells, inclusive at both corners.
   *
   * Selections are kept as cell rectangles rather than pixel rectangles: everything the editor does is
   * cell-shaped, and a rectangle in cells survives zooming.
   */
  data class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left + 1
    val height: Int get() = bottom - top + 1

    /** True when [col], [row] is inside the rectangle. */
    operator fun contains(cell: Pair<Int, Int>): Boolean =
      cell.first in left..right && cell.second in top..bottom

    /** The same rectangle grown to cover both corners, whatever order they came in. */
    companion object {
      /** Null when either corner is outside a [columns] x [rows] grid. */
      fun between(
        first: Pair<Int, Int>,
        second: Pair<Int, Int>,
        columns: Int,
        rows: Int,
      ): CellRect? {
        val (c0, r0) = first
        val (c1, r1) = second
        if (c0 !in 0 until columns || c1 !in 0 until columns) return null
        if (r0 !in 0 until rows || r1 !in 0 until rows) return null
        return CellRect(minOf(c0, c1), minOf(r0, r1), maxOf(c0, c1), maxOf(r0, r1))
      }
    }
  }

  /**
   * Holds the mutable pixel grid state, and the undo history every edit goes through.
   *
   * Two things are kept apart deliberately. [revision] is bumped on every change and is what the live
   * preview watches; [undoDepth] and [redoDepth] only move when a whole action has finished, so the
   * buttons that show them do not light up in the middle of a drag.
   */
  class PixelGridState(
    palette: List<Color> = PALETTE,
    initialColumns: Int = 16,
    initialRows: Int = 11,
  ) {
    var columns by mutableIntStateOf(initialColumns)
      private set
    var rows by mutableIntStateOf(initialRows)
      private set

    val defaultColor: Color = palette.minBy { it.luminance() }

    /** One colour per cell, row-major. */
    val pixels = mutableStateListOf<Color>().apply {
      repeat(columns * rows) { add(defaultColor) }
    }

    /**
     * Bumped on every edit. The live preview watches this one value instead of the pixel list: it is
     * a single snapshot state read, and it cannot miss two edits that cancel out.
     */
    var revision by mutableIntStateOf(0)
      private set

    /** How many steps back the history can go; drives the undo button. */
    var undoDepth by mutableIntStateOf(0)
      private set

    /** How many undone steps can be replayed; drives the redo button. */
    var redoDepth by mutableIntStateOf(0)
      private set

    /**
     * The colour each cell had before its last paint, so a second tap can put it back.
     *
     * Not part of the history: undoing clears it, because the colours it remembers belong to a drawing
     * that no longer exists.
     */
    private var previousColors = arrayOfNulls<Color>(columns * rows)

    /** The editor state at one moment. Colours are packed ARGB; the app only ever uses opaque palette colours. */
    private class Snapshot(val columns: Int, val rows: Int, val argb: IntArray)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()

    /** True between [beginEdit] and [endEdit]: the outermost pair owns the one history step. */
    private var editOpen = false

    /** Whether the open edit has changed anything yet, so a no-op edit can give its snapshot back. */
    private var editChanged = false

    fun canAddColumn(): Boolean = columns < 500
    fun canRemoveColumn(): Boolean = columns > MIN_GRID_SIZE
    fun canAddRow(): Boolean = rows < 500
    fun canRemoveRow(): Boolean = rows > MIN_GRID_SIZE

// ─────────────────────────────────────────────────────────────────────────────
// History
// ─────────────────────────────────────────────────────────────────────────────

    fun canUndo(): Boolean = undoDepth > 0
    fun canRedo(): Boolean = redoDepth > 0

    /**
     * Starts an edit that the history treats as one step, ended by [endEdit].
     *
     * A drag stroke, a fill, a shape or a selection move is one action to the person doing it even though
     * it touches many cells, so it has to be one step to undo. Nested pairs do nothing: the outermost one
     * owns the step, which lets a caller group operations without knowing what is inside them.
     */
    fun beginEdit() {
      if (editOpen) return
      editOpen = true
      editChanged = false
      undoStack.addLast(snapshot())
      trimHistory()
      // The redo trail is not dropped here but when the session is shown to change something: a gesture
      // that turns out to do nothing must leave the history exactly as it found it.
      refreshDepths()
    }

    /**
     * Ends the edit started by [beginEdit], dropping its history step when nothing actually changed, so
     * undo never steps over a gesture that did nothing.
     */
    fun endEdit() {
      if (!editOpen) return
      editOpen = false
      if (!editChanged) {
        undoStack.removeLastOrNull()
        refreshDepths()
      }
      editChanged = false
    }

    fun undo() {
      val step = undoStack.removeLastOrNull() ?: return
      redoStack.addLast(snapshot())
      restore(step)
      refreshDepths()
    }

    fun redo() {
      val step = redoStack.removeLastOrNull() ?: return
      undoStack.addLast(snapshot())
      restore(step)
      refreshDepths()
    }

    /**
     * Forgets the history.
     *
     * Called when the drawing is replaced wholesale by something the history cannot describe: a step
     * back into a different drawing would be a lie, not an undo.
     */
    fun clearHistory() {
      undoStack.clear()
      redoStack.clear()
      editOpen = false
      editChanged = false
      refreshDepths()
    }

    /** Records the state before a change; a no-op inside an open edit, which already recorded it. */
    private fun pushUndo() {
      if (editOpen) return
      undoStack.addLast(snapshot())
      trimHistory()
      redoStack.clear()
      refreshDepths()
    }

    /**
     * Notes that a change is about to be made.
     *
     * Outside an edit session that is the step itself. Inside one, it is only the first change that
     * counts: it marks the session as real, which is what keeps the session's single snapshot as an undo
     * step and what drops the redo trail. A session that never calls this leaves the history untouched.
     */
    private fun noteEdit() {
      if (!editOpen) {
        pushUndo()
        return
      }
      if (editChanged) return
      editChanged = true
      redoStack.clear()
      refreshDepths()
    }

    private fun snapshot(): Snapshot =
      Snapshot(columns, rows, IntArray(pixels.size) { pixels[it].toArgb() })

    private fun restore(step: Snapshot) {
      columns = step.columns
      rows = step.rows
      val restored = step.argb.map { Color(it) }
      pixels.clear()
      pixels.addAll(restored)
      previousColors = arrayOfNulls(columns * rows)
      editOpen = false
      editChanged = false
      revision++
    }

    private fun refreshDepths() {
      undoDepth = undoStack.size
      redoDepth = redoStack.size
    }

    /**
     * Drops the oldest steps once the history holds more cells than [MAX_HISTORY_CELLS], so a drawing at
     * the 500-cell limit cannot keep hundreds of megabytes of snapshots alive.
     */
    private fun trimHistory() {
      var cells = undoStack.sumOf { it.argb.size }
      while (undoStack.size > 1 && cells > MAX_HISTORY_CELLS) {
        cells -= undoStack.removeFirst().argb.size
      }
    }

// ─────────────────────────────────────────────────────────────────────────────
// Grid size
// ─────────────────────────────────────────────────────────────────────────────

    fun addColumnLeft() {
      if (!canAddColumn()) return
      noteEdit()
      val newCols = columns + 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(newCols * rows)
      for (r in 0 until rows) {
        newPixels.add(defaultColor)
        newPrevious[r * newCols] = null
        for (c in 0 until columns) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * newCols + (c + 1)] = previousColors[oldIdx]
        }
      }
      columns = newCols
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun addColumnRight() {
      if (!canAddColumn()) return
      noteEdit()
      val newCols = columns + 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(newCols * rows)
      for (r in 0 until rows) {
        for (c in 0 until columns) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * newCols + c] = previousColors[oldIdx]
        }
        newPixels.add(defaultColor)
        newPrevious[r * newCols + columns] = null
      }
      columns = newCols
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun removeColumnLeft() {
      if (!canRemoveColumn()) return
      noteEdit()
      val newCols = columns - 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(newCols * rows)
      for (r in 0 until rows) {
        for (c in 0 until newCols) {
          val oldIdx = r * columns + (c + 1)
          newPixels.add(pixels[oldIdx])
          newPrevious[r * newCols + c] = previousColors[oldIdx]
        }
      }
      columns = newCols
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun removeColumnRight() {
      if (!canRemoveColumn()) return
      noteEdit()
      val newCols = columns - 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(newCols * rows)
      for (r in 0 until rows) {
        for (c in 0 until newCols) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * newCols + c] = previousColors[oldIdx]
        }
      }
      columns = newCols
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun addRowTop() {
      if (!canAddRow()) return
      noteEdit()
      val newRows = rows + 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(columns * newRows)
      for (c in 0 until columns) {
        newPixels.add(defaultColor)
        newPrevious[c] = null
      }
      for (r in 0 until rows) {
        for (c in 0 until columns) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[(r + 1) * columns + c] = previousColors[oldIdx]
        }
      }
      rows = newRows
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun addRowBottom() {
      if (!canAddRow()) return
      noteEdit()
      val newRows = rows + 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(columns * newRows)
      for (r in 0 until rows) {
        for (c in 0 until columns) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * columns + c] = previousColors[oldIdx]
        }
      }
      for (c in 0 until columns) {
        newPixels.add(defaultColor)
        newPrevious[rows * columns + c] = null
      }
      rows = newRows
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun removeRowTop() {
      if (!canRemoveRow()) return
      noteEdit()
      val newRows = rows - 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(columns * newRows)
      for (r in 0 until newRows) {
        for (c in 0 until columns) {
          val oldIdx = (r + 1) * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * columns + c] = previousColors[oldIdx]
        }
      }
      rows = newRows
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

    fun removeRowBottom() {
      if (!canRemoveRow()) return
      noteEdit()
      val newRows = rows - 1
      val newPixels = mutableListOf<Color>()
      val newPrevious = arrayOfNulls<Color>(columns * newRows)
      for (r in 0 until newRows) {
        for (c in 0 until columns) {
          val oldIdx = r * columns + c
          newPixels.add(pixels[oldIdx])
          newPrevious[r * columns + c] = previousColors[oldIdx]
        }
      }
      rows = newRows
      pixels.clear()
      pixels.addAll(newPixels)
      previousColors = newPrevious
      revision++
    }

// ─────────────────────────────────────────────────────────────────────────────
// Painting
// ─────────────────────────────────────────────────────────────────────────────

    fun getPixel(col: Int, row: Int): Color = pixels[row * columns + col]

    fun setPixel(col: Int, row: Int, color: Color) {
      val index = row * columns + col
      if (pixels[index] == color) {
        // Painting the same colour again is not an edit; regenerating for it would just repeat work.
        return
      }
      noteEdit()
      pixels[index] = color
      revision++
    }

    /**
     * Handles a tap on a cell: paints it with [color], or puts back the colour it had before, when the
     * tap lands on a cell that already shows [color].
     *
     * A cell therefore toggles: one tap paints, the next restores what was there, the next paints again.
     * The revert is keyed on the cell's own state, not on which cell was tapped last, so tapping B in
     * between does not cost A a second tap -- tapping A once puts A back, which is what a tap on a cell
     * that already has the selected colour has to mean. (It did require A to be the previous tap, so
     * editing B in between swallowed the first tap on A and it took two.)
     */
    fun paint(col: Int, row: Int, color: Color) {
      if (col !in 0 until columns || row !in 0 until rows) return
      val index = row * columns + col
      val current = pixels[index]
      val previous = previousColors[index]
      if (previous != null && current == color) {
        noteEdit()
        pixels[index] = previous
        previousColors[index] = null
        revision++
        return
      }
      if (current != color) {
        noteEdit()
        previousColors[index] = current
        pixels[index] = color
        revision++
      }
    }

    /**
     * Sets a cell to [color] without the toggle in [paint].
     *
     * A stroke paints what it crosses, so dragging back over a cell that already shows the colour has to
     * leave it alone. It also leaves [previousColors] alone, like [fill]: the revert-on-second-tap rule
     * belongs to a deliberate tap on one cell, not to a gesture that swept across the drawing.
     */
    fun paintOver(col: Int, row: Int, color: Color) {
      if (col !in 0 until columns || row !in 0 until rows) return
      val index = row * columns + col
      if (pixels[index] == color) return
      noteEdit()
      pixels[index] = color
      revision++
    }

    /**
     * Paints every cell in [indices] that is not already [color], as one edit, and reports how many
     * changed. This is what a shape commits: a line, a rectangle or an ellipse arrives all at once.
     */
    fun paintCells(indices: Collection<Int>, color: Color): Int {
      val changing = indices.filter { it in pixels.indices && pixels[it] != color }
      if (changing.isEmpty()) return 0
      noteEdit()
      for (index in changing) pixels[index] = color
      revision++
      return changing.size
    }

    fun clear() {
      previousColors = arrayOfNulls(columns * rows)
      if (pixels.all { it == defaultColor }) {
        return
      }
      noteEdit()
      for (i in pixels.indices) pixels[i] = defaultColor
      revision++
    }

    /**
     * Fills the region of cells around ([col], [row]) that all show the same colour as that cell, and
     * reports whether anything changed.
     *
     * A region is cells reachable by steps up, down, left and right -- not diagonally -- so a diagonal
     * line is a wall. The whole flood is one edit: [revision] is bumped once, however many cells change.
     * [previousColors] is left alone because the per-cell revert in [paint] is a single-cell gesture and
     * has no meaning for a flood.
     */
    fun fill(col: Int, row: Int, color: Color): Boolean {
      if (col !in 0 until columns || row !in 0 until rows) return false
      val target = pixels[row * columns + col]
      // Nothing to do when the region already has the requested colour; callers reguard against this
      // because a no-op fill must not ask for a regeneration.
      if (target == color) return false

      // Work out the region first and paint it afterwards: the history step has to be taken while the
      // drawing still looks like it did, not once the flood has already changed it.
      val region = ArrayList<Int>()
      val seen = BooleanArray(columns * rows)
      val pending = ArrayDeque<Int>()
      pending.addLast(row * columns + col)
      while (pending.isNotEmpty()) {
        val index = pending.removeLast()
        if (seen[index] || pixels[index] != target) continue
        seen[index] = true
        region.add(index)
        val c = index % columns
        val r = index / columns
        if (c > 0) pending.addLast(index - 1)
        if (c < columns - 1) pending.addLast(index + 1)
        if (r > 0) pending.addLast(index - columns)
        if (r < rows - 1) pending.addLast(index + columns)
      }

      noteEdit()
      for (index in region) pixels[index] = color
      revision++
      return true
    }

// ─────────────────────────────────────────────────────────────────────────────
// Whole-drawing edits
// ─────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces every cell showing [from] with [to] and reports how many changed.
     *
     * The per-cell revert colours are dropped: after a replace the drawing has no single-cell history that
     * a tap could meaningfully put back.
     */
    fun replaceColor(from: Color, to: Color): Int {
      if (from == to) return 0
      val targets = pixels.indices.filter { pixels[it] == from }
      if (targets.isEmpty()) return 0
      noteEdit()
      for (index in targets) pixels[index] = to
      previousColors = arrayOfNulls(columns * rows)
      revision++
      return targets.size
    }

    fun flipHorizontal() {
      if (columns < 2) return
      noteEdit()
      for (row in 0 until rows) {
        val start = row * columns
        for (col in 0 until columns / 2) {
          val left = start + col
          val right = start + columns - 1 - col
          val swapped = pixels[left]
          pixels[left] = pixels[right]
          pixels[right] = swapped
        }
      }
      previousColors = arrayOfNulls(columns * rows)
      revision++
    }

    fun flipVertical() {
      if (rows < 2) return
      noteEdit()
      for (row in 0 until rows / 2) {
        val top = row * columns
        val bottom = (rows - 1 - row) * columns
        for (col in 0 until columns) {
          val swapped = pixels[top + col]
          pixels[top + col] = pixels[bottom + col]
          pixels[bottom + col] = swapped
        }
      }
      previousColors = arrayOfNulls(columns * rows)
      revision++
    }

    /**
     * Turns the drawing a quarter turn clockwise, which swaps [columns] and [rows].
     *
     * A tap's remembered colours are dropped: the cells they described have moved, and a tap should not
     * put a colour back into a cell that is now somewhere else.
     */
    fun rotateClockwise() {
      noteEdit()
      val old = pixels.toList()
      val newColumns = rows
      val newRows = columns
      val turned = MutableList(newColumns * newRows) { defaultColor }
      for (row in 0 until rows) {
        for (col in 0 until columns) {
          val newCol = rows - 1 - row
          val newRow = col
          turned[newRow * newColumns + newCol] = old[row * columns + col]
        }
      }
      columns = newColumns
      rows = newRows
      pixels.clear()
      pixels.addAll(turned)
      previousColors = arrayOfNulls(columns * rows)
      revision++
    }

    /**
     * Moves what is inside [rect] by [dCol], [dRow] cells and reports whether anything moved.
     *
     * The cells the selection leaves behind go back to [defaultColor], like [clear]: the drawing has no
     * transparency, so a hole has to be filled with something. The destination is clamped so the whole
     * selection stays inside the grid, and the source is read into a buffer first so a move that overlaps
     * its own starting place does not smear.
     */
    fun moveCells(rect: CellRect, dCol: Int, dRow: Int): Boolean {
      if (rect.width <= 0 || rect.height <= 0) return false
      if (rect.right >= columns || rect.bottom >= rows || rect.left < 0 || rect.top < 0) return false
      val newLeft = (rect.left + dCol).coerceIn(0, columns - rect.width)
      val newTop = (rect.top + dRow).coerceIn(0, rows - rect.height)
      if (newLeft == rect.left && newTop == rect.top) return false

      val lifted = Array(rect.width * rect.height) { pixels[rect.top * columns + rect.left + it % rect.width + (it / rect.width) * columns] }
      noteEdit()
      for (row in rect.top..rect.bottom) {
        for (col in rect.left..rect.right) pixels[row * columns + col] = defaultColor
      }
      for (row in 0 until rect.height) {
        for (col in 0 until rect.width) {
          pixels[(newTop + row) * columns + newLeft + col] = lifted[row * rect.width + col]
        }
      }
      previousColors = arrayOfNulls(columns * rows)
      revision++
      return true
    }

    /** The rectangle [rect] would occupy after a move by [dCol], [dRow], clamped the same way [moveCells] clamps. */
    fun movedRect(rect: CellRect, dCol: Int, dRow: Int): CellRect {
      val newLeft = (rect.left + dCol).coerceIn(0, (columns - rect.width).coerceAtLeast(0))
      val newTop = (rect.top + dRow).coerceIn(0, (rows - rect.height).coerceAtLeast(0))
      return CellRect(newLeft, newTop, newLeft + rect.width - 1, newTop + rect.height - 1)
    }

    /** The cell indices inside [rect], row-major. Used to snapshot a selection for a move preview. */
    fun indicesOf(rect: CellRect): List<Int> {
      val indices = ArrayList<Int>(rect.width * rect.height)
      for (row in rect.top..rect.bottom) {
        for (col in rect.left..rect.right) {
          if (col in 0 until columns && row in 0 until rows) indices.add(row * columns + col)
        }
      }
      return indices
    }

    /**
     * Writes a lifted block of colours back into [rect], row-major, and reports whether anything changed.
     *
     * This is how a selection move is applied while it is being dragged: the caller keeps the block, clears
     * where it was, and stamps it where it is now. Stamping from the held block rather than from the grid
     * is what stops a move that passes over its own starting place from smearing the pixels.
     */
    fun stamp(rect: CellRect, block: List<Color>): Boolean {
      if (block.size < rect.width * rect.height) return false
      val changes = ArrayList<Pair<Int, Color>>(rect.width * rect.height)
      for (row in 0 until rect.height) {
        for (col in 0 until rect.width) {
          val targetCol = rect.left + col
          val targetRow = rect.top + row
          if (targetCol !in 0 until columns || targetRow !in 0 until rows) continue
          val index = targetRow * columns + targetCol
          val color = block[row * rect.width + col]
          if (pixels[index] != color) changes.add(index to color)
        }
      }
      if (changes.isEmpty()) return false
      noteEdit()
      for ((index, color) in changes) pixels[index] = color
      revision++
      return true
    }
  }

  /**
   * How many cells of history are kept before the oldest steps are dropped: a few of the largest possible
   * drawings, and thousands of ordinary ones.
   */
  private const val MAX_HISTORY_CELLS = 8_000_000

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

  /**
   * What the next gesture on the drawing does.
   *
   * [Paint] is the unarmed state: a tap paints a cell and a hold paints a stroke. Exactly one mode is armed
   * at a time. A [oneShot] mode disarms itself as soon as it has been used, because leaving it armed makes
   * the next tap do something the person did not ask for; the shape tools and the selection stay armed,
   * since drawing another shape or moving the selection again is the obvious next thing to do.
   */
  enum class EditorMode(val oneShot: Boolean) {
    Paint(false),
    Fill(true),
    ReplaceColor(true),
    Eyedropper(true),
    Line(false),
    Rectangle(false),
    Ellipse(false),
    Select(false),
    ;

    /** True for the modes that draw a shape between the press and the release. */
    val isShape: Boolean get() = this == Line || this == Rectangle || this == Ellipse
  }

  /** An output shape the editor offers, as width / height. */
  enum class OutputAspect(val label: String, val ratio: Float) {
    Portrait9x16("9:16", 9f / 16f),
    Portrait3x4("3:4", 3f / 4f),
    Square("1:1", 1f),
    Landscape4x3("4:3", 4f / 3f),
    Landscape16x9("16:9", 16f / 9f),
  }

  /**
   * How many pixels the picture is written at, as a multiple of the generated bitmap.
   *
   * This is a saving decision, not a solving one: the structure of the picture is decided by the grid, so
   * scaling on the way out only changes how big the file is.
   */
  enum class OutputScale(val factor: Int) {
    One(1),
    Two(2),
    Three(3),
    Four(4),
    ;

    val label: String get() = "${factor}×"
  }

  /** What the generated picture is: its shape and the size it is written at. */
  data class OutputSettings(
    val aspect: OutputAspect = OutputAspect.Portrait9x16,
    val scale: OutputScale = OutputScale.One,
  )

  /**
   * @param preview fills the column left of the drawing grid. It is kept as a slot so the editor does
   *   not need to know anything about the wave-function collapse that produces the bitmap shown there.
   * @param output the shape and save size of the generated picture; the preview column takes its shape.
   * @param onOutputChange called when the output menu picks a different shape or size.
   */
  @Composable
  fun PixelEditorScreen(
    modifier: Modifier = Modifier,
    gridState: PixelGridState = remember { PixelGridState() },
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
    output: OutputSettings = OutputSettings(),
    onOutputChange: (OutputSettings) -> Unit = {},
    /**
     * One line under the drawing when the generated bitmap is not a plain edge-respecting tiling of it,
     * and what to do about it. Null (the usual case) draws nothing, so the column is the drawing alone.
     *
     * It lives here and not in the preview because the preview is the picture and nothing else.
     */
    notice: String? = null,
    preview: @Composable () -> Unit = {},
  ) {
    // The first palette colour is selected on open, so the swatch strip starts with a highlighted
    // colour and a tap on the drawing already has something to paint with.
    var selectedColor by remember { mutableStateOf(PALETTE.first()) }

    // The armed tool, and the area the selection tool has marked out. Both are the editor's own state:
    // they change what a gesture means, not what the generated picture is.
    var mode by remember { mutableStateOf(EditorMode.Paint) }
    var selection by remember { mutableStateOf<CellRect?>(null) }
    var outputMenuOpen by remember { mutableStateOf(false) }

    // Tapping the armed mode puts the drawing back to painting; tapping another one arms that. A
    // selection belongs to the select tool, so arming any other tool forgets it rather than leaving a
    // rectangle on the drawing that no tool is going to act on.
    fun arm(next: EditorMode) {
      mode = if (mode == next) EditorMode.Paint else next
      if (mode != EditorMode.Select) selection = null
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
        selected = mode == EditorMode.Eyedropper,
      ) { arm(EditorMode.Eyedropper) },
      EditorTool(
        icon = Icons.Default.FormatColorFill,
        contentDescription = "Fill a connected area with the selected colour",
        selected = mode == EditorMode.Fill,
      ) { arm(EditorMode.Fill) },
      EditorTool(
        icon = Icons.Default.FindReplace,
        contentDescription = "Replace the tapped colour everywhere with the selected colour",
        selected = mode == EditorMode.ReplaceColor,
      ) { arm(EditorMode.ReplaceColor) },
      EditorTool(
        icon = Icons.Default.Timeline,
        contentDescription = "Draw a line",
        selected = mode == EditorMode.Line,
        startsGroup = true,
      ) { arm(EditorMode.Line) },
      EditorTool(
        icon = Icons.Default.CropSquare,
        contentDescription = "Draw a rectangle",
        selected = mode == EditorMode.Rectangle,
      ) { arm(EditorMode.Rectangle) },
      EditorTool(
        icon = Icons.Default.RadioButtonUnchecked,
        contentDescription = "Draw an ellipse",
        selected = mode == EditorMode.Ellipse,
      ) { arm(EditorMode.Ellipse) },
      EditorTool(
        icon = Icons.Default.CropFree,
        contentDescription = "Select an area to move",
        selected = mode == EditorMode.Select,
      ) { arm(EditorMode.Select) },

      EditorTool(
        icon = Icons.Default.Undo,
        contentDescription = "Undo",
        enabled = gridState.canUndo(),
        startsGroup = true,
      ) {
        gridState.undo()
        onResize()
      },
      EditorTool(
        icon = Icons.Default.Redo,
        contentDescription = "Redo",
        enabled = gridState.canRedo(),
      ) {
        gridState.redo()
        onResize()
      },

      EditorTool(
        icon = Icons.Default.Flip,
        contentDescription = "Mirror the drawing left to right",
        startsGroup = true,
      ) {
        gridState.flipHorizontal()
        onResize()
      },
      EditorTool(
        icon = Icons.Default.Flip,
        contentDescription = "Mirror the drawing top to bottom",
        iconRotationDegrees = 90f,
      ) {
        gridState.flipVertical()
        onResize()
      },
      EditorTool(
        icon = Icons.Default.RotateRight,
        contentDescription = "Turn the drawing a quarter turn",
      ) {
        gridState.rotateClockwise()
        onResize()
      },

      EditorTool(
        icon = Icons.Default.AspectRatio,
        contentDescription = "Output shape and size",
        startsGroup = true,
      ) { outputMenuOpen = true },

      EditorTool(
        icon = Icons.Default.Save,
        contentDescription = "Save the picture to the gallery",
        startsGroup = true,
      ) { onSave() },
      EditorTool(
        icon = Icons.Default.Share,
        contentDescription = "Share the picture",
      ) { onShare() },
      // Clearing changes the drawing but not its size; regeneration is requested all the same so the
      // preview does not keep showing a picture of pixels that are gone.
      EditorTool(
        icon = Icons.Default.Delete,
        contentDescription = "Clear the drawing",
      ) {
        gridState.clear()
        onResize()
      },
    )

    Row(modifier = modifier) {
        // The live bitmap preview: the same shape as the generated output, so what is shown here is the
        // picture at the aspect ratio it will be saved in.
        Box(
          modifier = Modifier
            .aspectRatio(output.aspect.ratio)
            .fillMaxHeight()
            .background(PREVIEW_BACKGROUND),
        ) {
          preview()
        }

      VerticalDividerLine()

      // The menu hangs off the toolbar it is opened from, so it reads as part of it.
      Box {
        EditorToolbar(tools = editorTools)
        OutputMenu(
          expanded = outputMenuOpen,
          output = output,
          onDismiss = { outputMenuOpen = false },
          onOutputChange = {
            onOutputChange(it)
            outputMenuOpen = false
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
              onSelectionChange = { selection = it },
              onColorPicked = { selectedColor = it },
              onModeConsumed = { if (mode.oneShot) mode = EditorMode.Paint },
              onTap = onTap,
              onResize = onResize,
            )
          }

          if (notice != null) {
            Text(
              text = notice,
              color = NOTICE_COLOR,
              fontSize = 10.sp,
              lineHeight = 13.sp,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
          }
        }

      VerticalDividerLine()

        ColorToolbar(
          selectedColor = selectedColor,
          onColorSelected = { selectedColor = it },
        )
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
      MenuHeading("Shape")
      OutputAspect.entries.forEach { aspect ->
        DropdownMenuItem(
          text = { Text(aspect.label) },
          onClick = { onOutputChange(output.copy(aspect = aspect)) },
          trailingIcon = {
            if (output.aspect == aspect) Icon(Icons.Default.Check, contentDescription = null)
          },
        )
      }
      HorizontalDivider()
      MenuHeading("Save size")
      OutputScale.entries.forEach { scale ->
        DropdownMenuItem(
          text = { Text(scale.label) },
          onClick = { onOutputChange(output.copy(scale = scale)) },
          trailingIcon = {
            if (output.scale == scale) Icon(Icons.Default.Check, contentDescription = null)
          },
        )
      }
    }
  }

  @Composable
  private fun MenuHeading(text: String) {
    Text(
      text = text,
      color = Color.White.copy(alpha = 0.6f),
      fontSize = 11.sp,
      modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
  }

  /** Slightly darker than the app background, so the preview reads as its own surface. */
  private val PREVIEW_BACKGROUND = Color(0xFF0B0910)

  /** The notice line under the drawing: warm enough to be noticed, dim enough not to be an alarm. */
  private val NOTICE_COLOR = Color(0xFFFEAE34)

  @Composable
  fun VerticalDividerLine() {
    VerticalDivider(thickness = 1.dp, color = Color.White)
  }

// ─────────────────────────────────────────────────────────────────────────────
// The drawing
// ─────────────────────────────────────────────────────────────────────────────

  /**
   * The drawing: a pan-and-zoom grid of cells whose colours generate the picture.
   *
   * What a gesture means comes from the armed [mode]:
   * - [EditorMode.Paint]: a tap paints one cell; a hold buzzes once and then paints every cell the finger
   *   crosses. Moving before the hold is recognised still pans and zooms.
   * - [EditorMode.Fill], [EditorMode.ReplaceColor], [EditorMode.Eyedropper]: a tap acts once, and
   *   [onModeConsumed] lets the caller disarm the mode.
   * - [EditorMode.Line], [EditorMode.Rectangle], [EditorMode.Ellipse]: press, drag to size, release to
   *   commit, with the cells the shape would cover shown while dragging.
   * - [EditorMode.Select]: dragging outside the selection draws a new one, a tap clears it, and dragging
   *   inside it moves the selected cells.
   *
   * @param onSelectionChange the selection after the tool changed it, or null when it was cleared.
   * @param onColorPicked the colour the eyedropper found.
   * @param onModeConsumed a one-shot mode has been used and should be disarmed.
   * @param onTap an edit that came from a tap, for the latency measurement and the regeneration.
   * @param onResize a batch edit finished (undo, a shape, a move) and the picture should be rebuilt.
   */
  @Composable
  fun ZoomablePixelCanvas(
    gridState: PixelGridState,
    selectedColor: Color,
    mode: EditorMode,
    selection: CellRect?,
    baseCellSizePx: Int,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10f,
    onSelectionChange: (CellRect?) -> Unit = {},
    onColorPicked: (Color) -> Unit = {},
    onModeConsumed: () -> Unit = {},
    onTap: () -> Unit = {},
    onResize: () -> Unit = {},
  ) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current

    /*
     * The gesture handler below is restarted whenever its keys change, so the selection is read through
     * this holder instead of being a key: moving a selection updates it on every step, and a handler keyed
     * on it would cancel its own drag the moment it moved.
     */
    val currentSelection by rememberUpdatedState(selection)

    // The cells a shape would cover if it were released now, and the rectangle the selection tool is
    // dragging out. Both are drawn over the grid; neither is part of it.
    var shapePreview by remember { mutableStateOf<List<Int>>(emptyList()) }
    var marquee by remember { mutableStateOf<CellRect?>(null) }

    fun tapToPixel(screenPos: Offset): Pair<Int, Int>? {
      val localX = (screenPos.x - offset.x) / scale
      val localY = (screenPos.y - offset.y) / scale
      val col = floor(localX / baseCellSizePx).toInt()
      val row = floor(localY / baseCellSizePx).toInt()
      return if (col in 0 until gridState.columns && row in 0 until gridState.rows)
        col to row else null
    }

    Box(
      modifier = Modifier
        .clipToBounds()
        .fillMaxSize()
        .pointerInput(gridState, selectedColor) {
          detectDragGestures { change, dragAmount ->
            change.consume()
            offset += dragAmount
          }
        }
        .pointerInput(Unit) {
          detectTransformGestures { centroid, pan, zoom, _ ->
            val newScale = (scale * zoom).coerceIn(minZoom, maxZoom)
            val scaleDelta = newScale / scale
            offset = (offset - centroid) * scaleDelta + centroid + pan
            scale = newScale
          }
        }
        // Everything that edits the grid goes through one gesture handler.
        //
        // A tap and a hold cannot be detected by detectTapGestures next to detectDragGesturesAfterLongPress:
        // a hold released without moving would fire both, and the tap would put back the cell the hold had
        // just painted. Mode and colour are keys rather than captured values, so arming a tool reinstalls
        // the handler instead of leaving a stale one behind; the selection is not a key, see above.
        .pointerInput(gridState, selectedColor, mode) {
          /** The point where the pointer lifts, or null when the gesture was taken over or cancelled. */
          suspend fun AwaitPointerEventScope.awaitUpOrCancel(id: PointerId): Offset? {
            while (true) {
              val event = awaitPointerEvent()
              if (event.changes.any { it.isConsumed }) return null
              val change = event.changes.firstOrNull { it.id == id } ?: return null
              if (change.changedToUpIgnoreConsumed()) return change.position
            }
          }

          /** Press, drag and release a shape, showing the cells it would cover while it moves. */
          suspend fun AwaitPointerEventScope.dragShape(start: Pair<Int, Int>, id: PointerId) {
            var end = start
            shapePreview = shapeCells(mode, start, end, gridState.columns, gridState.rows)
            val released = drag(id) { change ->
              change.consume()
              val cell = tapToPixel(change.position)
              if (cell != null && cell != end) {
                end = cell
                shapePreview = shapeCells(mode, start, end, gridState.columns, gridState.rows)
              }
            }
            shapePreview = emptyList()
            if (!released) return
            gridState.paintCells(shapeCells(mode, start, end, gridState.columns, gridState.rows), selectedColor)
            onResize()
          }

          /** Select by dragging out a rectangle, or move an existing selection by dragging it. */
          suspend fun AwaitPointerEventScope.dragSelection(down: PointerInputChange, downCell: Pair<Int, Int>?) {
            val moving = currentSelection
            val grabbed = downCell

            // A press inside the selection moves it. The block is lifted once and stamped at each new
            // rectangle, so dragging back and forth never smears the pixels, and the whole drag is one
            // undo step.
            if (moving != null && grabbed != null && grabbed in moving) {
              val grabCol = grabbed.first
              val grabRow = grabbed.second
              val lifted = gridState.indicesOf(moving).map { gridState.pixels[it] }
              var placed: CellRect = moving
              gridState.beginEdit()
              val released = drag(down.id) { change ->
                change.consume()
                val cell = tapToPixel(change.position) ?: return@drag
                val target = gridState.movedRect(moving, cell.first - grabCol, cell.second - grabRow)
                if (target == placed) return@drag
                gridState.paintCells(gridState.indicesOf(placed), gridState.defaultColor)
                gridState.stamp(target, lifted)
                placed = target
                onSelectionChange(target)
              }
              gridState.endEdit()
              if (released) onResize()
              return
            }

            // Anywhere else, drag out a new rectangle; a tap with no drag clears the selection.
            var end = grabbed
            marquee = grabbed?.let { CellRect.between(it, it, gridState.columns, gridState.rows) }
            val released = drag(down.id) { change ->
              change.consume()
              val cell = tapToPixel(change.position) ?: return@drag
              if (cell != end) {
                end = cell
                marquee = grabbed?.let { CellRect.between(it, cell, gridState.columns, gridState.rows) }
              }
            }
            marquee = null
            // A cancelled drag (a pinch that took the gesture, say) is not a selection: leave the one
            // that was there alone rather than replacing it with whatever the finger happened to touch.
            if (!released) return
            onSelectionChange(
              if (grabbed != null && end != null) {
                CellRect.between(grabbed, end, gridState.columns, gridState.rows)
              } else {
                null
              }
            )
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val downCell = tapToPixel(down.position)

            when {
              mode == EditorMode.Select -> dragSelection(down, downCell)

              mode.isShape -> {
                if (downCell == null) {
                  // Started outside the grid: nothing to anchor the shape to, so let the gesture go by.
                  awaitUpOrCancel(down.id)
                } else {
                  dragShape(downCell, down.id)
                }
              }

              mode != EditorMode.Paint -> {
                // One-shot modes act on a tap. A gesture the pan handler took over is not a tap at all,
                // so the mode stays armed.
                val up = awaitUpOrCancel(down.id) ?: return@awaitEachGesture
                val cell = tapToPixel(up) ?: return@awaitEachGesture
                when (mode) {
                  EditorMode.Fill -> {
                    gridState.fill(cell.first, cell.second, selectedColor)
                    onTap()
                  }
                  EditorMode.ReplaceColor -> {
                    val target = gridState.getPixel(cell.first, cell.second)
                    gridState.replaceColor(target, selectedColor)
                    onTap()
                  }
                  EditorMode.Eyedropper -> onColorPicked(gridState.getPixel(cell.first, cell.second))
                  else -> return@awaitEachGesture
                }
                onModeConsumed()
              }

              else -> {
                val held = awaitLongPressOrCancellation(down.id)
                if (held == null) {
                  // No hold. Either the finger lifted, which is a tap, or the pan handler consumed the
                  // gesture, which means it was never a tap.
                  val up = currentEvent.changes.firstOrNull {
                    it.id == down.id && it.changedToUpIgnoreConsumed() && !it.isConsumed
                  } ?: return@awaitEachGesture
                  tapToPixel(up.position)?.let { (col, row) ->
                    gridState.paint(col, row, selectedColor)
                    onTap()
                  }
                  return@awaitEachGesture
                }

                // Held down: buzz, then paint every cell the finger crosses until it lifts, as one undo
                // step however many cells that turns out to be.
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val stroke = HashSet<Int>()
                gridState.beginEdit()

                fun paintAt(position: Offset) {
                  val (col, row) = tapToPixel(position) ?: return
                  // A cell is painted once per stroke, and painting it does not toggle: a stroke that
                  // crosses a cell already showing the colour must leave it as it is.
                  if (!stroke.add(row * gridState.columns + col)) return
                  gridState.paintOver(col, row, selectedColor)
                  onTap()
                }

                paintAt(held.position)
                var previous = held.position
                drag(held.id) { change ->
                  // Consumed so the pan and zoom handlers leave a painting drag alone.
                  change.consume()
                  // Walk the segment between this event and the last one: a fast drag reports positions
                  // far apart, and painting only where it reported would leave gaps.
                  sampleSegment(previous, change.position, baseCellSizePx / 2f) { paintAt(it) }
                  previous = change.position
                }
                gridState.endEdit()
              }
            }
          }
        }
    ) {
      Canvas(
        modifier = Modifier.fillMaxSize()
      ) {
        // Apply transform manually inside Canvas draw scope
        val sx = scale
        val tx = offset.x
        val ty = offset.y

        drawContext.canvas.save()
        drawContext.canvas.translate(tx, ty)
        drawContext.canvas.scale(sx, sx)

        val cs = baseCellSizePx.toFloat()
        val cellSize = Size(cs, cs)

        // Pixels
        for (row in 0 until gridState.rows) {
          for (col in 0 until gridState.columns) {
            val color = gridState.getPixel(col, row)
            drawRect(color, Offset(col * cs, row * cs), cellSize)
          }
        }

        // The shape being dragged out: the cells it would paint, drawn over the grid.
        val previewCells = shapePreview
        if (previewCells.isNotEmpty()) {
          val overlay = Color.White.copy(alpha = 0.35f)
          for (index in previewCells) {
            val col = index % gridState.columns
            val row = index / gridState.columns
            drawRect(overlay, Offset(col * cs, row * cs), cellSize)
          }
        }

        // The selection, or the rectangle being dragged out. One hairline whatever the zoom.
        (marquee ?: selection)?.let { rect ->
          drawRect(
            color = Color.White,
            topLeft = Offset(rect.left * cs, rect.top * cs),
            size = Size(rect.width * cs, rect.height * cs),
            style = Stroke(width = 2f / sx),
          )
        }

        // Grid lines
        val gridLineColor = Color.White
        val lineWidth = 1f / sx
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineWidth, lineWidth))

        val w = gridState.columns * cs
        val h = gridState.rows * cs
        for (col in 0..gridState.columns) {
          drawLine(gridLineColor, Offset(col * cs, 0f), Offset(col * cs, h), lineWidth, pathEffect = pathEffect)
        }
        for (row in 0..gridState.rows) {
          drawLine(gridLineColor, Offset(0f, row * cs), Offset(w, row * cs), lineWidth, pathEffect = pathEffect)
        }

        drawContext.canvas.restore()
      }

      // Scrollable, zoomable buttons placed next to grid edges, sized as a grid cell
      Box(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y,
            transformOrigin = TransformOrigin(0f, 0f)
          )
      ) {
        val cs = baseCellSizePx.toFloat()
        val cellDp = BUTTON_SIZE

        val midCol = gridState.columns / 2
        val midRow = gridState.rows / 2

        val leftMinusRow = (midRow - 1).coerceIn(0, gridState.rows - 1)
        val leftPlusRow = midRow.coerceIn(0, gridState.rows - 1)

        val topMinusCol = (midCol - 1).coerceIn(0, gridState.columns - 1)
        val topPlusCol = midCol.coerceIn(0, gridState.columns - 1)

        // Left side (- and + next to left edge at x = -cs)
        SubtleCellButton(
          text = "-",
          onClick = { gridState.removeColumnLeft(); onResize() },
          enabled = gridState.canRemoveColumn(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (-cs).toDp() }, y = with(LocalDensity.current) { (leftMinusRow * cs).toDp() })
        )
        SubtleCellButton(
          text = "+",
          onClick = { gridState.addColumnLeft(); onResize() },
          enabled = gridState.canAddColumn(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (-cs).toDp() }, y = with(LocalDensity.current) { (leftPlusRow * cs).toDp() })
        )

        // Right side (- and + next to right edge at x = columns * cs)
        SubtleCellButton(
          text = "-",
          onClick = { gridState.removeColumnRight(); onResize() },
          enabled = gridState.canRemoveColumn(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (gridState.columns * cs).toDp() }, y = with(LocalDensity.current) { (leftMinusRow * cs).toDp() })
        )
        SubtleCellButton(
          text = "+",
          onClick = { gridState.addColumnRight(); onResize() },
          enabled = gridState.canAddColumn(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (gridState.columns * cs).toDp() }, y = with(LocalDensity.current) { (leftPlusRow * cs).toDp() })
        )

        // Top side (- and + next to top edge at y = -cs)
        SubtleCellButton(
          text = "-",
          onClick = { gridState.removeRowTop(); onResize() },
          enabled = gridState.canRemoveRow(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (topMinusCol * cs).toDp() }, y = with(LocalDensity.current) { (-cs).toDp() })
        )
        SubtleCellButton(
          text = "+",
          onClick = { gridState.addRowTop(); onResize() },
          enabled = gridState.canAddRow(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (topPlusCol * cs).toDp() }, y = with(LocalDensity.current) { (-cs).toDp() })
        )

        // Bottom side (- and + next to bottom edge at y = rows * cs)
        SubtleCellButton(
          text = "-",
          onClick = { gridState.removeRowBottom(); onResize() },
          enabled = gridState.canRemoveRow(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (topMinusCol * cs).toDp() }, y = with(LocalDensity.current) { (gridState.rows * cs).toDp() })
        )
        SubtleCellButton(
          text = "+",
          onClick = { gridState.addRowBottom(); onResize() },
          enabled = gridState.canAddRow(),
          modifier = Modifier
            .size(cellDp)
            .offset(x = with(LocalDensity.current) { (topPlusCol * cs).toDp() }, y = with(LocalDensity.current) { (gridState.rows * cs).toDp() })
        )
      }
    }
  }

  @Composable
  private fun SubtleCellButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
  ) {
    Box(
      modifier = modifier
        .clip(RoundedCornerShape(2.dp))
        .background(Color.Black.copy(alpha = 0.4f))
        .border(width = 0.5f.dp, color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(2.dp))
        .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        color = if (enabled) Color.White.copy(alpha = 0.9f) else Color.Gray.copy(alpha = 0.3f),
        fontSize = 14.sp
      )
    }
  }

  /**
   * The cells a shape from [from] to [to] covers, as flat indices into a [columns] x [rows] grid.
   *
   * All three shapes are outlines, not fills: a solid area is what the fill tool is for, and an outline
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

    val left = minOf(from.first, to.first)
    val right = maxOf(from.first, to.first)
    val top = minOf(from.second, to.second)
    val bottom = maxOf(from.second, to.second)

    when (mode) {
      EditorMode.Line -> {
        val startCol = from.first
        val startRow = from.second
        val dCol = to.first - startCol
        val dRow = to.second - startRow
        val steps = maxOf(abs(dCol), abs(dRow))
        if (steps == 0) {
          add(startCol, startRow)
        } else {
          for (step in 0..steps) {
            val fraction = step.toFloat() / steps
            add(
              (startCol + dCol * fraction).roundToInt(),
              (startRow + dRow * fraction).roundToInt(),
            )
          }
        }
      }

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

      else -> Unit
    }
    return cells.toList()
  }

// ─────────────────────────────────────────────────────────────────────────────
// Editor toolbar
// ─────────────────────────────────────────────────────────────────────────────

  /**
   * One entry in the vertical toolbar between the preview and the drawing.
   *
   * @param icon glyph drawn in the square button, from the Material icon set.
   * @param contentDescription what the glyph means. The button has no visible label, so this is the
   *   only thing a screen reader (or a UI test) can read.
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
   */
  @Composable
  fun EditorToolbar(tools: List<EditorTool>) {
    if (tools.isEmpty()) return

    LazyColumn(
      modifier = Modifier
        .width(BUTTON_SIZE)
        .fillMaxHeight(),
    ) {
      items(tools) { tool ->
        EditorToolButton(tool)
      }
    }
  }

  /** One icon button, wearing the same dark treatment as the canvas's resize buttons. */
  @Composable
  private fun EditorToolButton(tool: EditorTool) {
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
          .size(BUTTON_SIZE)
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
          // A mode is a toggle, so it is a selectable button: the armed state reaches a screen reader as
          // checked/not checked, instead of being only a lighter square for the eye.
          .then(
            if (tool.selected == null) {
              Modifier.clickable(enabled = tool.enabled, onClick = tool.onClick)
            } else {
              Modifier.selectable(
                selected = armed,
                enabled = tool.enabled,
                role = Role.Button,
                onClick = tool.onClick,
              )
            }
          )
          // The description belongs to the button, not to the glyph inside it, so the whole square is one
          // accessibility node instead of a described icon sitting next to an undescribed button.
          .semantics { contentDescription = tool.contentDescription },
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = tool.icon,
          contentDescription = null,
          tint = if (tool.enabled) Color.White.copy(alpha = 0.9f) else Color.Gray.copy(alpha = 0.3f),
          modifier = Modifier
            .size(TOOL_ICON_SIZE)
            .rotate(tool.iconRotationDegrees),
        )
      }
    }
  }

  /** Glyph size inside a [BUTTON_SIZE] cell, small enough to leave the button a visible margin. */
  private val TOOL_ICON_SIZE = 20.dp

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
// ─────────────────────────────────────────────────────────────────────────────
// Color toolbar
// ─────────────────────────────────────────────────────────────────────────────

  private val PALETTE = listOf(
       Color(0xffbe4a2f),
       Color(0xffd77643),
       Color(0xffead4aa),
       Color(0xffe4a672),
       Color(0xffb86f50),
       Color(0xff733e39),
       Color(0xff3e2731),
       Color(0xffa22633),
       Color(0xffe43b44),
       Color(0xfff77622),
       Color(0xfffeae34),
       Color(0xfffee761),
       Color(0xff63c74d),
       Color(0xff3e8948),
       Color(0xff265c42),
       Color(0xff193c3e),
       Color(0xff124e89),
       Color(0xff0099db),
       Color(0xff2ce8f5),
       Color(0xffffffff),
       Color(0xffc0cbdc),
       Color(0xff8b9bb4),
       Color(0xff5a6988),
       Color(0xff3a4466),
       Color(0xff262b44),
       Color(0xff181425),
       Color(0xffff0044),
       Color(0xff68386c),
       Color(0xffb55088),
       Color(0xfff6757a),
       Color(0xffe8b796),
       Color(0xffc28569),
  )

  @Composable
  fun ColorToolbar(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
  ) {
      Column(
        modifier = Modifier
          .width(BUTTON_SIZE)
          .fillMaxHeight(),
      ) {
        // Color swatches
        LazyColumn(
          modifier = Modifier.weight(1f),
        ) {
          items(PALETTE) { color ->
            ColorSwatch(
              color = color,
              isSelected = color == selectedColor,
              onClick = { onColorSelected(color) },
            )
          }
        }
      }
  }

  @Composable
  fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
  ) {
    Box(
      modifier = Modifier
        .aspectRatio(1f)
        .clickable(onClick = onClick)
        .then(
          if (isSelected) {
            Modifier.background(Color.DarkGray)
          } else {
            Modifier
          }
        )
        .padding(SWATCH_MARGIN)
        .border(
              width = 1.dp,
              color = SWATCH_SELECTED_BORDER_COLOR,
              shape = RoundedCornerShape(SWATCH_CORNER_RADIUS)
        ),
      contentAlignment = Alignment.Center
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(color = color, shape = RoundedCornerShape(SWATCH_CORNER_RADIUS))
      )
    }
  }

  private val SWATCH_SELECTED_BORDER_COLOR = Color.White
  private val SWATCH_MARGIN = 5.dp

  private val SWATCH_CORNER_RADIUS = 3.dp

  const val BUTTON_SIZE_DP_INT = 38

  private val BUTTON_SIZE = BUTTON_SIZE_DP_INT.dp
}

@Preview(heightDp = BitmapEditor.BUTTON_SIZE_DP_INT, widthDp = BitmapEditor.BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchPreviewPreview() {
  WFCBitmapTheme {
    BitmapEditor.ColorSwatch(
      color = Color.Red,
      isSelected = false,
      onClick = {},
    )
  }
}

@Preview(heightDp = BitmapEditor.BUTTON_SIZE_DP_INT, widthDp = BitmapEditor.BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchSelectedPreviewPreview() {
  WFCBitmapTheme {
    BitmapEditor.ColorSwatch(
      color = Color.Red,
      isSelected = true,
      onClick = {},
    )
  }
}

@Preview(heightDp = 418, widthDp = 880, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PixelEditorPreview() {
  WFCBitmapTheme {
    BitmapEditor.PixelEditorScreen()
  }
}
