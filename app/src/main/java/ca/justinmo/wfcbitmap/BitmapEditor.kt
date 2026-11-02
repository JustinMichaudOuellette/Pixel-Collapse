package ca.justinmo.wfcbitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    /**
     * Ends the open edit and takes back everything it wrote, for a gesture that was cancelled.
     *
     * A drag that never landed must leave the drawing exactly as it found it *and* leave no step in the
     * history: an undo that undoes nothing is a trap for the next press of the undo button.
     */
    fun cancelEdit() {
      if (!editOpen) return
      val step = undoStack.removeLastOrNull()
      editOpen = false
      editChanged = false
      if (step != null) {
        restore(step)
      }
      // restore() puts the grid back but not the counters, and the undo button reads the counters.
      refreshDepths()
    }

    /**
     * Puts [indices] back to the colours they had in [source], the grid as it was before the edit began.
     *
     * This is what makes a drag idempotent: each step of a move puts back the cells the previous step
     * touched and then re-applies the move from scratch, so dragging a block over other cells and back
     * leaves those cells exactly as they were. Part of an open edit, so it records no history of its own.
     */
    fun revertCells(indices: IntArray, source: List<Color>) {
      if (source.size != pixels.size) return
      var changed = false
      for (index in indices) {
        if (index !in pixels.indices) continue
        if (pixels[index] != source[index]) {
          pixels[index] = source[index]
          changed = true
        }
      }
      if (changed) revision++
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

    /**
     * Replaces the drawing with a saved one, with no history behind it.
     *
     * The history is cleared rather than continued: the saved drawing was saved when the app was closed,
     * and there is nothing behind it to undo *to*. A size that disagrees with the pixel count is ignored,
     * because a half-restored grid is worse than a fresh one.
     */
    fun restoreDrawing(columns: Int, rows: Int, pixels: IntArray) {
      if (columns < MIN_GRID_SIZE || rows < MIN_GRID_SIZE) return
      if (pixels.size < columns * rows) return
      clearHistory()
      this.columns = columns
      this.rows = rows
      this.pixels.clear()
      this.pixels.addAll(List(columns * rows) { Color(pixels[it]) })
      previousColors = arrayOfNulls(columns * rows)
      revision++
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
   * How many cells deep the edge band is: the separator indicator marks two cells in from every side.
   *
   * Two and not one: the outer ring is what the edge rule literally constrains, but a band one cell deep
   * leaves nothing to see between one grid line and the next, so the indication starts two cells in and the
   * ring itself sits inside it.
   */
  internal const val EDGE_BAND_CELLS = 2

  /**
   * True for a grid line inside the edge band, counted from either end.
   *
   * Lines are numbered `0..count`, so the band's lines are the first and last `EDGE_BAND_CELLS + 1` of them:
   * the lines that bound every cell in a band that deep, including the one where the band ends.
   */
  internal fun isEdgeBandLine(index: Int, count: Int): Boolean =
    index <= EDGE_BAND_CELLS || index >= count - EDGE_BAND_CELLS

  /**
   * The parts of one grid line that lie inside the edge band, in cells along that line.
   *
   * This is the whole of the indicator, and it is *segments* rather than whole lines. A line already inside
   * the band -- one of the first or last `EDGE_BAND_CELLS + 1` of them -- is dotted end to end. A line that
   * only crosses the band, which is every separator running out of the top or bottom strip, is dotted along
   * the part inside that strip and plain everywhere else: dotting whole lines instead leaves the inside of
   * the top and bottom strips with plain vertical separators, and of the left and right strips with plain
   * horizontal ones.
   *
   * @param lineIndex which line, `0..lineCount`.
   * @param lineCount lines across the grid, so `lineCount + 1` of them.
   * @param spanCount cells along the line: how far the band reaches is measured in this direction.
   */
  internal fun edgeBandSpans(lineIndex: Int, lineCount: Int, spanCount: Int): List<IntRange> =
    when {
      // A band that is deeper than half the grid has no middle left to leave plain.
      spanCount <= 2 * EDGE_BAND_CELLS -> listOf(0..spanCount)
      isEdgeBandLine(lineIndex, lineCount) -> listOf(0..spanCount)
      else -> listOf(0..EDGE_BAND_CELLS, (spanCount - EDGE_BAND_CELLS)..spanCount)
    }

  /**
   * Whether the drawing marks its edge band at all: only while the picture really keeps the drawing's border.
   *
   * Two things can take that border away, and the band has to go with either of them. One is the edge rule
   * being dropped for a picture that could not hold it; the other is wrapping, where the drawing's edge is
   * not an edge in the first place and every pattern was sampled across it. The separators are dotted only
   * where the band is marked, so this is what decides between a grid with a band in it and a grid whose lines
   * all look the same -- and a grid whose lines *almost* all look the same, with a dotted band left over from
   * a rule the picture is not keeping, is the one thing it must never be.
   */
  internal fun showsEdgeBand(edgeRuleKept: Boolean, wrapEdges: Boolean): Boolean =
    edgeRuleKept && !wrapEdges

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
    Select,
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
  enum class OutputScale(val label: String, val numerator: Int, val denominator: Int = 1) {
    /*
     * The biggest end of the list is deliberately further out than the middle steps: the file is a copy of
     * the picture, so it is the one worth paying for once. The bottom of the list is 1x and no lower --
     * a file smaller than the picture on screen is a file the person cannot see the drawing in, and the
     * preview size is the setting that decides how many pixels the picture itself has.
     */
    One("1×", 1),
    Two("2×", 2),
    Three("3×", 3),
    Four("4×", 4),
    Sixteen("16×", 16),
    ;

    /** How many pixels of the written picture there are per pixel of the generated bitmap. */
    val factor: Double get() = numerator.toDouble() / denominator

    /**
     * [value] pixels of the generated bitmap, as pixels of the picture written at this scale.
     *
     * Rounded down rather than to nearest: the size has to stay a whole number of pixels, and rounding down
     * is the only choice that cannot claim a pixel the grid does not have.
     */
    fun pixels(value: Int): Int = (value.toLong() * numerator / denominator).toInt()
  }

  /**
   * How many pixels the picture on screen is generated at, per cell of the solver grid.
   *
   * The drawing is cells and the picture is pixels, and this is the one place the two are tied together. It
   * is a rendering choice and nothing more: the solver fills the same grid whatever it is set to, so the
   * picture has the same patterns in the same places and only its pixels get bigger or smaller.
   *
   * The preview column is always filled, whichever of these is chosen -- a smaller picture is scaled up to fit
   * it, one pixel to one block, which is why a smaller size looks blockier rather than smaller. [Two] is the
   * top of the list because it is as many pixels as a phone's column can show: a bigger preview would be
   * generated, scaled back down to fit, and look exactly like this one.
   */
  enum class PreviewScale(val label: String, val pixelsPerCell: Int) {
    /**
     * One pixel per cell, the smallest a picture can be made of: the blockiest the preview gets and the
     * cheapest to generate.
     */
    Half("0.5×", 1),
    One("1×", 2),
    /**
     * The default: four pixels per cell, which on a phone is a picture that fills the column at 1:1, so the
     * preview shows exactly the pixels that were generated and none of them twice.
     */
    Two("2×", 4),
    ;

    /** How many times the default [WfcGenerator.PIXELS_PER_CELL] square this draws a cell with. */
    val factor: Double get() = pixelsPerCell.toDouble() / WfcGenerator.PIXELS_PER_CELL
  }

  /**
   * The pixel size the picture on screen is generated at: the solver grid for [aspect], one cell being
   * [PreviewScale.pixelsPerCell] pixels of it.
   *
   * The shape decides it as much as the scale does -- the grid keeps a fixed number of cells whatever the
   * aspect, so a square picture is wider than a 9:16 one at the same scale -- which is why the menu shows the
   * size beside each option rather than a bare multiplier.
   */
  internal fun previewPixelSize(aspect: OutputAspect, preview: PreviewScale): Pair<Int, Int> {
    val (columns, rows) = WfcGenerator.gridForAspect(aspect.ratio)
    return columns * preview.pixelsPerCell to rows * preview.pixelsPerCell
  }

  /**
   * The pixel size the picture is written at, in pixels of the solver grid.
   *
   * The save size is its own scale of the picture, not a multiple of whatever the preview happens to be: a
   * 1× save is the picture as the app has always drawn it, 16× is sixteen times that, and choosing a
   * preview size never moves a number in this list. That is what makes the two settings independent -- the
   * preview decides how the picture is drawn, the save size decides what is written out of it.
   */
  internal fun savedPixelSize(aspect: OutputAspect, scale: OutputScale): Pair<Int, Int> {
    val (width, height) = previewPixelSize(aspect, PreviewScale.One)
    return scale.pixels(width) to scale.pixels(height)
  }

  /**
   * A line the editor shows over the drawing, with an optional button.
   *
   * Two kinds of thing are said over the drawing: what a tool does, and what just happened to the picture.
   * The second can be worth acting on -- after a save, the first thing anyone wants is to see the file that
   * was written -- so a message carries a label and what to run when it is tapped, and is shown for longer
   * than a plain one. A message with no action is just a sentence with nothing to do about it.
   */
  data class EditorMessage(
    val text: String,
    /** The button's label, or null for a line that only has to be read. */
    val actionLabel: String? = null,
    /** What the button does; null when there is no button. */
    val onAction: (() -> Unit)? = null,
  )

  /**
   * What the generated picture is: its shape, the size it is generated at, and the size it is written at.
   */
  data class OutputSettings(
    val aspect: OutputAspect = OutputAspect.Portrait9x16,
    val preview: PreviewScale = PreviewScale.Two,
    val scale: OutputScale = OutputScale.One,
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
    preview: @Composable () -> Unit = {},
  ) {
    // The first palette colour is selected on open, so the swatch strip starts with a highlighted
    // colour and a tap on the drawing already has something to paint with.
    var selectedColor by remember { mutableStateOf(initialColor ?: PALETTE.first()) }

    // Reported on every change, including the one at first composition, so the caller can save it.
    LaunchedEffect(selectedColor) { onColorChange(selectedColor) }

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
        icon = Icons.Default.AspectRatio,
        contentDescription = "Output shape and size",
        explanation = "Chooses the shape of the picture and the size it is saved at.",
        startsGroup = true,
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

  /** The menu's labels: light enough to read on the panel, dim enough not to compete with the drawing. */
  private val MENU_TEXT_COLOR = Color.White.copy(alpha = 0.92f)

  /** A hairline between the shape half of the menu and the size half. */
  private val MENU_DIVIDER_COLOR = Color.White.copy(alpha = 0.15f)

  @Composable
  private fun MenuHeading(text: String) {
    Text(
      text = text,
      color = Color.White.copy(alpha = 0.7f),
      fontSize = 11.sp,
      modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
  }

  /** The tag UI tests use to find the drawing: it has no other handle, being one pointer-driven Canvas. */
  const val CANVAS_TEST_TAG = "pixel-canvas"

  /**
   * What the preview may not take from the editor beside it: the two toolbars and the two dividers between
   * the columns. Everything left over is the preview's, up to the width its aspect ratio asks for.
   */
  private val PREVIEW_CHROME_WIDTH = (BUTTON_SIZE_DP_INT * 2).dp + 2.dp

  /** Below this the preview would be a sliver rather than a picture, so the cap stops giving way here. */
  private fun minPreviewWidth(): Dp = (BUTTON_SIZE_DP_INT * 4).dp

  /** Slightly darker than the app background, so the preview reads as its own surface. */
  private val PREVIEW_BACKGROUND = Color(0xFF0B0910)

  /** Behind a menu or a tool's explanation: the toolbar's own near-black, so they read as part of the editor. */
  private val PANEL_BACKGROUND = Color(0xFF1A1720)

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
   * - [EditorMode.Duplicate]: drag out the area to copy, exactly as the select tool does, then drag the
   *   selection: a copy of it follows the finger while the original stays where it is.
   * - [EditorMode.Line], [EditorMode.Rectangle], [EditorMode.Ellipse]: press, drag to size, release to
   *   commit, with the cells the shape would cover shown while dragging. The committed shape spends the
   *   tool, so a second shape means arming it again.
   * - [EditorMode.Select]: dragging outside the selection draws a new one, a tap clears it, and dragging
   *   inside it moves the selected cells. Drawing the marquee does not spend the tool -- the move is the
   *   use -- and after a move the selection stays where it was put.
   *
   * @param onSelectionChange the selection after the tool changed it, or null when it was cleared.
   * @param onColorPicked the colour the eyedropper found.
   * @param onModeConsumed the armed mode has been used and should be disarmed. It is only called for a
   *   gesture that actually happened: a press the pan handler stole, or one that started off the grid, is
   *   not a use and leaves the tool armed.
   * @param onTap an edit that came from a tap, for the latency measurement and the regeneration.
   * @param onResize a batch edit finished (undo, a shape, a move, a paste) and the picture should be
   *   rebuilt.
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
    /** The tag a UI test finds the drawing by; each test passes its own so two canvases never collide. */
    canvasTestTag: String = CANVAS_TEST_TAG,
    /**
     * Whether the drawing's edge band is marked on the grid; see [showsEdgeBand].
     *
     * Only while the generated picture keeps the drawing's border is the outer ring special: those are the
     * cells whose pixels land on the generated bitmap's border, so keeping them one colour is what keeps the
     * picture's edges real. The canvas marks them so the ring is visible while drawing instead of having to
     * be remembered -- and leaves every separator the same when that border is not being kept at all.
     */
    marksEdgeBand: Boolean = true,
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
    //
    // A floating paste spans two gestures, so the drawing it was dropped onto is kept here: moving the
    // copy puts this back between steps, which is what leaves the pixels underneath untouched.
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
        // A handle for UI tests: the drawing is one Canvas and gestures are the only way to drive it, so a
        // test needs a stable way to say "this is the thing to swipe on".
        .testTag(canvasTestTag)
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

          /**
           * Press, drag and release a shape, showing the cells it would cover while it moves.
           *
           * @return true when the shape was committed, which is what tells the caller the tool has been
           *   spent. A gesture the pan handler took over never became a shape, so it returns false and
           *   leaves the tool armed.
           */
          suspend fun AwaitPointerEventScope.dragShape(start: Pair<Int, Int>, id: PointerId): Boolean {
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
            if (!released) return false
            gridState.paintCells(shapeCells(mode, start, end, gridState.columns, gridState.rows), selectedColor)
            onResize()
            return true
          }

          /**
           * Drags the block of cells in [from] with the finger, grabbed at [grabCol], [grabRow].
           *
           * Every step is re-derived from the grid as it was when the drag began, so passing over other
           * cells and moving on leaves them exactly as they were: only the cells the previous step wrote
           * are put back in between. Erasing the previous *rectangle* instead destroys whatever the block
           * merely passed over, because nothing else remembers those cells.
           *
           * The caller owns the edit session: this opens one at its first write and leaves it open, so a
           * paste and the drag that places it are one undo step.
           *
           * @param onMoved called with each new rectangle while the block is being dragged.
           * @return where the block ended when the gesture landed and moved it; null when it never moved or
           *   the gesture was taken over, in which case every cell it wrote has already been put back.
           */
          suspend fun AwaitPointerEventScope.dragBlock(
            down: PointerInputChange,
            from: CellRect,
            grabCol: Int,
            grabRow: Int,
            /**
             * Whether the cells the block is lifted out of become a hole. True for moving pixels that are
             * part of the drawing; false for a copy floating over it, which leaves the drawing underneath
             * exactly as it was when the copy was dropped.
             */
            leaveHole: Boolean = true,
            /**
             * The grid to return to between steps, when it is older than this drag: a floating copy is
             * dropped on one gesture and moved on the next, and what has to come back is what was under it
             * before it was ever dropped.
             */
            base: List<Color>? = null,
            onMoved: (CellRect) -> Unit,
          ): CellRect? {
            val lifted = gridState.indicesOf(from).map { gridState.pixels[it] }
            val before = base ?: gridState.pixels.toList()
            val hole = if (leaveHole) gridState.indicesOf(from) else emptyList()
            var placed = from
            // A floating copy is already written where it was dropped, and [before] predates that write, so
            // its cells are the first thing the next step has to put back.
            var touched = if (leaveHole) IntArray(0) else gridState.indicesOf(from).toIntArray()
            var editing = false

            fun placeAt(target: CellRect) {
              if (!editing) {
                gridState.beginEdit()
                editing = true
              }
              gridState.revertCells(touched, before)
              if (hole.isNotEmpty()) gridState.paintCells(hole, gridState.defaultColor)
              gridState.stamp(target, lifted)
              touched = (hole + gridState.indicesOf(target)).toIntArray()
              placed = target
            }

            val released = drag(down.id) { change ->
              change.consume()
              val cell = tapToPixel(change.position) ?: return@drag
              val target = gridState.movedRect(from, cell.first - grabCol, cell.second - grabRow)
              if (target == placed) return@drag
              placeAt(target)
              onMoved(target)
            }
            if (!released) {
              // The gesture was taken over (a pinch, say): put back every cell this drag wrote.
              gridState.revertCells(touched, before)
              return null
            }
            return if (placed != from) placed else null
          }

          /**
           * Select by dragging out a rectangle, or move an existing selection by dragging it.
           *
           * Drawing the marquee does *not* spend the tool: it is how you say what to move, and the obvious
           * next gesture is the move itself, with the tool still lit. A move that actually lands spends it,
           * like every other tool -- the drawing is back to painting afterwards, and the selection stays
           * where it was put.
           *
           * @return true when the selection was moved, i.e. when the tool has been used. A cancelled drag
           *   or a marquee returns false and leaves it armed.
           */
          suspend fun AwaitPointerEventScope.dragSelection(
            down: PointerInputChange,
            downCell: Pair<Int, Int>?,
          ): Boolean {
            val moving = currentSelection
            val grabbed = downCell

            // A press inside the selection moves it. The block is lifted once and stamped at each new
            // rectangle, so the whole drag is one undo step and the pixels inside it are never smeared.
            if (moving != null && grabbed != null && grabbed in moving) {
              // Whether the block ever left its place, which is what separates a move from a tap: a drag
              // that wanders and comes back is still a move, and the selection is let go of at the end.
              var dragged = false
              val landed = dragBlock(down, moving, grabbed.first, grabbed.second) { target ->
                dragged = true
                onSelectionChange(target)
              }
              if (landed != null) {
                gridState.endEdit()
                onResize()
              } else {
                // Nothing net moved (or the gesture was taken over), so there is no step to keep.
                gridState.cancelEdit()
              }
              if (dragged) {
                // The gesture finished anywhere -- including back where it started. Either way it was a
                // move, so the selection is let go of and the tool is spent.
                onSelectionChange(null)
                return true
              }
              // Pressed and released without dragging: a tap, not a move. The selection stays.
              return false
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
            if (!released) return false
            onSelectionChange(
              if (grabbed != null && end != null) {
                CellRect.between(grabbed, end, gridState.columns, gridState.rows)
              } else {
                null
              }
            )
            // Drawing the marquee does not spend the tool: it says *what* to move, and the drag that
            // moves it is the next gesture. Returning true here disarms the tool, so the next drag paints
            // instead of moving -- the bug this line exists to keep from coming back.
            return false
          }

          /**
           * Copy an area, then drag the copy: the select tool's two gestures, with a duplicate in the middle.
           *
           * The first gesture is the select tool's marquee -- nothing is written, the area is only chosen --
           * and the copy is not taken until it is dragged, so the original is always in place to be seen
           * against. The second gesture drags a *copy* of that area: it follows the finger and the cells
           * underneath it come back when it moves on, which is what makes it a duplicate rather than a move
           * (a move lifts the pixels away and leaves a hole behind).
           *
           * @return true when the copy has been put down, i.e. when the tool has been used.
           */
          suspend fun AwaitPointerEventScope.dragDuplicate(
            down: PointerInputChange,
            downCell: Pair<Int, Int>?,
          ): Boolean {
            val selected = currentSelection
            val grabbed = downCell

            // Second gesture: drag the copy. Nothing is lifted away, so the original stays where it is.
            if (selected != null && grabbed != null && grabbed in selected) {
              var dragged = false
              val landed = dragBlock(
                down = down,
                from = selected,
                grabCol = grabbed.first,
                grabRow = grabbed.second,
                leaveHole = false,
                onMoved = { target ->
                  dragged = true
                  onSelectionChange(target)
                },
              )
              if (landed != null) {
                gridState.endEdit()
                onResize()
              } else {
                // Nothing net moved (or the gesture was taken over): no step to keep.
                gridState.cancelEdit()
              }
              if (dragged) {
                onSelectionChange(null)
                return true
              }
              return false
            }

            // First gesture: the area to copy, drawn out exactly as the select tool draws one.
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
            // A cancelled drag is not a selection: leave the one that was there alone.
            if (!released) return false
            onSelectionChange(
              if (grabbed != null && end != null) {
                CellRect.between(grabbed, end, gridState.columns, gridState.rows)
              } else {
                null
              }
            )
            // Not spent: the area is chosen, and the next drag moves a copy of it.
            return false
          }
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val downCell = tapToPixel(down.position)

            when {
              // A tool is spent by the gesture that uses it -- one shape, one move, one paste -- so the
              // armed mode goes dark and the drawing is back to painting. Re-arming is the one tap it costs
              // to draw a second shape. The selection is the exception in one direction only: drawing the
              // marquee is preparation for the move, so it leaves the tool armed and the move spends it.
              mode == EditorMode.Select -> {
                if (dragSelection(down, downCell)) onModeConsumed()
              }

              mode == EditorMode.Duplicate -> {
                if (dragDuplicate(down, downCell)) onModeConsumed()
              }

              mode.isShape -> {
                if (downCell == null) {
                  // Started outside the grid: nothing to anchor the shape to, so let the gesture go by.
                  awaitUpOrCancel(down.id)
                } else if (dragShape(downCell, down.id)) {
                  onModeConsumed()
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


        /*
         * The grid lines, which is the whole of the edge indicator.
         *
         * Inside the edge band the separators are dotted and everywhere else they are finely dashed, so the
         * band reads as a region of the drawing without a single pixel of the drawing itself being changed:
         * no tint, no frame, nothing drawn over the cells.
         *
         * The dotting is *per segment*. A separator running out of the top strip is dotted for the strip's
         * depth and plain for the rest of its length, which is what the middle of the top strip needs in
         * order to look like the rest of the band -- dotting only the lines that lie wholly inside the band
         * leaves the top and bottom strips with plain vertical separators and the side strips with plain
         * horizontal ones.
         *
         * It is only drawn while the picture keeps the drawing's border, which is not the same as the edge
         * rule holding: wrapping takes the border away too, because every pattern was then sampled across the
         * drawing's edge. Either way the band would be pointing at cells that are not special, so the grid is
         * left uniformly dashed instead -- see [showsEdgeBand].
         */
        val gridLineColor = Color.White
        val lineWidth = 1f / sx
        val pathEffect = PathEffect.dashPathEffect(floatArrayOf(lineWidth, lineWidth))
        /*
         * Dot, gap, dot -- three pixels either way, against the hairline and 1px gap of the rest.
         *
         * A dotted line drawn with the same 1px stroke as the others is not a dotted line: a single pixel of
         * dash followed by a single pixel of gap closes back up into a solid-looking line over pixel art, so
         * the band was invisible. The stroke is what makes the dots real; the dash lengths only space them.
         */
        val dotSize = 3f / sx
        val dottedWidth = 3f / sx
        val dotted = PathEffect.dashPathEffect(floatArrayOf(dotSize, dotSize * 1.6f))

        val w = gridState.columns * cs
        val h = gridState.rows * cs

        /** One stretch of a separator, dotted or plain. */
        fun separator(vertical: Boolean, fixed: Float, fromCell: Int, toCell: Int, isDotted: Boolean) {
          val from = fromCell * cs
          val to = toCell * cs
          if (to - from <= 0f) return
          val width = if (isDotted) dottedWidth else lineWidth
          val effect = if (isDotted) dotted else pathEffect
          if (vertical) {
            drawLine(gridLineColor, Offset(fixed, from), Offset(fixed, to), width, pathEffect = effect)
          } else {
            drawLine(gridLineColor, Offset(from, fixed), Offset(to, fixed), width, pathEffect = effect)
          }
        }

        /** Draws [lineIndex] with its band stretches dotted and the rest plain. */
        fun drawSeparator(vertical: Boolean, lineIndex: Int) {
          val fixed = lineIndex * cs
          val spans = if (marksEdgeBand) {
            edgeBandSpans(
              lineIndex = lineIndex,
              lineCount = if (vertical) gridState.columns else gridState.rows,
              spanCount = if (vertical) gridState.rows else gridState.columns,
            )
          } else {
            emptyList()
          }
          val total = if (vertical) gridState.rows else gridState.columns
          var next = 0
          for (span in spans) {
            separator(vertical, fixed, next, span.first, isDotted = false)
            separator(vertical, fixed, span.first, span.last, isDotted = true)
            next = span.last
          }
          separator(vertical, fixed, next, total, isDotted = false)
        }

        for (col in 0..gridState.columns) drawSeparator(vertical = true, lineIndex = col)
        for (row in 0..gridState.rows) drawSeparator(vertical = false, lineIndex = row)

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
  fun EditorToolbar(tools: List<EditorTool>, onExplain: (EditorTool) -> Unit = {}) {
    if (tools.isEmpty()) return

    LazyColumn(
      modifier = Modifier
        .width(BUTTON_SIZE)
        .fillMaxHeight(),
    ) {
      items(tools) { tool ->
        EditorToolButton(tool, onExplain)
      }
    }
  }

  /** One icon button, wearing the same dark treatment as the canvas's resize buttons. */
  @Composable
  private fun EditorToolButton(tool: EditorTool, onExplain: (EditorTool) -> Unit) {
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

  // https://lospec.com/palette-list/endesga-32
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

  /**
   * The colour a fresh session starts with: the palette's first entry, which the colour strip shows as
   * selected on open. Exposed because the session snapshot needs it when nothing has been reported yet.
   */
  internal val defaultDrawingColor: Color get() = PALETTE.first()

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
