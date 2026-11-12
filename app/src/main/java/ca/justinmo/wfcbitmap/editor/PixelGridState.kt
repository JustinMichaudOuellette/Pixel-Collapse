/**
 * The drawing itself: the grid of cells, the history that makes an edit undoable, and the operations that
 * reshape the grid -- adding and removing rows and columns, moving a rectangle of cells, stamping a block.
 *
 * The pixels are the whole document. Everything else about a session is a setting the caller owns; the drawing
 * is the part the editor must not lose, so it is kept here and nowhere else.
 */

package ca.justinmo.wfcbitmap.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import ca.justinmo.wfcbitmap.editor.*
import ca.justinmo.wfcbitmap.palette.PaletteStore

/** Smallest grid allowed in either axis, in cells. */
const val MIN_GRID_SIZE = 7

/** Largest grid allowed in either axis, in cells. */
const val MAX_GRID_SIZE = 500

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
  /**
   * The colours the drawing may start from. The app hands over the palette it loaded from
   * `nintendo-entertainment-system.txt`; the single black fallback only keeps the state constructible in
   * previews and tests that do not care which palette they are on.
   */
  palette: List<Color> = listOf(Color.Black),
  initialColumns: Int = 16,
  initialRows: Int = 11,
) {
  var columns by mutableIntStateOf(initialColumns)
    private set
  var rows by mutableIntStateOf(initialRows)
    private set

  /**
   * How many drawing pixels one grouped cell is, i.e. the kernel's cell size; 1 when the drawing is not
   * grouped.
   *
   * Every resize moves the grid by whole cells, so the drawing always stays a whole number of cells wide
   * and tall. Anything else leaves a strip at the edge that the solver never groups: with 3-pixel cells a
   * 28-wide drawing has a ninth cell that is one pixel wide, and every pattern in it is a fragment.
   */
  var cellSize: Int = 1
    private set

  /**
   * The colour new cells start as, and the one [clear] paints the whole drawing with. It follows the
   * active palette whenever the drawing is migrated to that palette.
   */
  var defaultColor: Color = palette.minByOrNull { it.luminance() } ?: Color.Black
    private set

  /** A blank sheet is paper: the lightest palette colour, not the palette's darkest ink. */
  private val blankColor: Color = palette.maxByOrNull { it.luminance() } ?: Color.White

  /** One colour per cell, row-major. */
  val pixels = mutableStateListOf<Color>().apply {
    repeat(columns * rows) { add(blankColor) }
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
  private class Snapshot(val columns: Int, val rows: Int, val cellSize: Int, val argb: IntArray)

  private val undoStack = ArrayDeque<Snapshot>()
  private val redoStack = ArrayDeque<Snapshot>()

  /** True between [beginEdit] and [endEdit]: the outermost pair owns the one history step. */
  private var editOpen = false

  /** Whether the open edit has changed anything yet, so a no-op edit can give its snapshot back. */
  private var editChanged = false

  /** One grouped cell, in drawing pixels; never zero. */
  private val cellStep: Int get() = cellSize.coerceAtLeast(1)

  fun canAddColumn(): Boolean = columns + cellStep <= MAX_GRID_SIZE
  fun canRemoveColumn(): Boolean = columns - cellStep >= smallestSide()
  fun canAddRow(): Boolean = rows + cellStep <= MAX_GRID_SIZE
  fun canRemoveRow(): Boolean = rows - cellStep >= smallestSide()

  /**
   * The smallest side this grouping allows: [MIN_GRID_SIZE] whole cells in each direction.
   *
   * [MIN_GRID_SIZE] is a count of cells, not of pixels. Without grouping the two are the same number,
   * which is why it reads as one; under a 9x9 kernel a cell is 3 pixels, so the smallest legal drawing is
   * 7 cells = 21 pixels, and a 9-pixel one would be only three cells across.
   */
  private fun smallestSide(): Int = MIN_GRID_SIZE * cellStep

  /**
   * [value] rounded up to a whole number of [step]-sized cells, kept between the editor's smallest grid
   * and its largest.
   *
   * The floor is [MIN_GRID_SIZE] *cells*, i.e. `MIN_GRID_SIZE * step` pixels: a drawing has to hold at
   * least seven cells each way whatever the grouping, because that is the count the ungrouped minimum has
   * always meant. Rounding only upwards would leave a drawing below it wherever the starting grid was
   * already small, and a 9x9 grouping on a 9-pixel drawing would leave three cells rather than seven.
   */
  private fun alignUp(value: Int, step: Int): Int {
    val rounded = ((value + step - 1) / step) * step
    val floor = MIN_GRID_SIZE * step
    // A grid already at the limit gives up its last partial cell rather than growing past the limit.
    return minOf(maxOf(rounded, floor), (MAX_GRID_SIZE / step) * step)
  }

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
  /**
   * Replaces the drawing with a saved one, with no history behind it.
   *
   * The history is cleared rather than continued: the saved drawing was saved when the app was closed,
   * and there is nothing behind it to undo *to*. A size that disagrees with the pixel count is ignored,
   * because a half-restored grid is worse than a fresh one.
   *
   * [cellSize] is the grouping the drawing is being restored under: a saved drawing whose saved grouping
   * had been changed since is grown to a whole number of the new cells, exactly as picking that grouping
   * in the editor does. Nothing that was drawn is lost; the extra cells are blank.
   */
  fun restoreDrawing(columns: Int, rows: Int, pixels: IntArray, cellSize: Int = 1) {
    if (columns < MIN_GRID_SIZE || rows < MIN_GRID_SIZE) return
    if (pixels.size < columns * rows) return
    clearHistory()
    this.cellSize = cellSize.coerceAtLeast(1)
    previousColors = arrayOfNulls(columns * rows)
    val source = List(columns * rows) { Color(pixels[it]) }
    replaceGrid(
      newColumns = alignUp(columns, this.cellSize),
      newRows = alignUp(rows, this.cellSize),
      sourceColumns = columns,
      sourceRows = rows,
      source = source,
    )
  }

  /**
   * Replaces every cell with [pixels] as one undoable edit.
   *
   * This is the camera tool's commit: the photo has already been reduced to the grid, so the drawing
   * becomes the photo's pixel pattern. Unlike [restoreDrawing], the previous drawing stays in the undo
   * history -- a photo that looked better before the downscale can be taken back without losing it.
   *
   * [columns] and [rows] are grown to whole [cellSize] cells exactly as any other resize is, and the
   * extra cells are background. A size that cannot hold [pixels] is ignored, because half a photo is
   * worse than the drawing that was already there.
   */
  fun replaceDrawing(columns: Int, rows: Int, pixels: IntArray, cellSize: Int = this.cellSize) {
    if (columns < MIN_GRID_SIZE || rows < MIN_GRID_SIZE) return
    if (pixels.size < columns * rows) return
    val step = cellSize.coerceAtLeast(1)
    val newColumns = alignUp(columns, step)
    val newRows = alignUp(rows, step)
    noteEdit()
    this.cellSize = step
    previousColors = arrayOfNulls<Color>(newColumns * newRows)
    val source = List(columns * rows) { Color(pixels[it]) }
    replaceGrid(newColumns, newRows, columns, rows, source)
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
    Snapshot(columns, rows, cellSize, IntArray(pixels.size) { pixels[it].toArgb() })

  private fun restore(step: Snapshot) {
    columns = step.columns
    rows = step.rows
    // The grouping is part of the shape: undoing a resize that a bigger grouping caused has to put the
    // cells back too, or the grid would be left not a whole number of them wide.
    cellSize = step.cellSize
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
    val count = cellStep
    val filler = addedColour()
    noteEdit()
    val newCols = columns + count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(newCols * rows)
    for (r in 0 until rows) {
      repeat(count) { newPixels.add(filler) }
      for (c in 0 until columns) {
        val oldIdx = r * columns + c
        newPixels.add(pixels[oldIdx])
        newPrevious[r * newCols + (c + count)] = previousColors[oldIdx]
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
    val count = cellStep
    val filler = addedColour()
    noteEdit()
    val newCols = columns + count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(newCols * rows)
    for (r in 0 until rows) {
      for (c in 0 until columns) {
        val oldIdx = r * columns + c
        newPixels.add(pixels[oldIdx])
        newPrevious[r * newCols + c] = previousColors[oldIdx]
      }
      repeat(count) { newPixels.add(filler) }
    }
    columns = newCols
    pixels.clear()
    pixels.addAll(newPixels)
    previousColors = newPrevious
    revision++
  }

  fun removeColumnLeft() {
    if (!canRemoveColumn()) return
    val count = cellStep
    noteEdit()
    val newCols = columns - count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(newCols * rows)
    for (r in 0 until rows) {
      for (c in 0 until newCols) {
        val oldIdx = r * columns + (c + count)
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
    val count = cellStep
    noteEdit()
    val newCols = columns - count
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
    val count = cellStep
    val filler = addedColour()
    noteEdit()
    val newRows = rows + count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(columns * newRows)
    repeat(count) { newPixels.addAll(List(columns) { filler }) }
    for (r in 0 until rows) {
      for (c in 0 until columns) {
        val oldIdx = r * columns + c
        newPixels.add(pixels[oldIdx])
        newPrevious[(r + count) * columns + c] = previousColors[oldIdx]
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
    val count = cellStep
    val filler = addedColour()
    noteEdit()
    val newRows = rows + count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(columns * newRows)
    for (r in 0 until rows) {
      for (c in 0 until columns) {
        val oldIdx = r * columns + c
        newPixels.add(pixels[oldIdx])
        newPrevious[r * columns + c] = previousColors[oldIdx]
      }
    }
    repeat(count) { newPixels.addAll(List(columns) { filler }) }
    rows = newRows
    pixels.clear()
    pixels.addAll(newPixels)
    previousColors = newPrevious
    revision++
  }

  fun removeRowTop() {
    if (!canRemoveRow()) return
    val count = cellStep
    noteEdit()
    val newRows = rows - count
    val newPixels = mutableListOf<Color>()
    val newPrevious = arrayOfNulls<Color>(columns * newRows)
    for (r in 0 until newRows) {
      for (c in 0 until columns) {
        val oldIdx = (r + count) * columns + c
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
    val count = cellStep
    noteEdit()
    val newRows = rows - count
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

  /**
   * Takes on a grouping of [size] pixels per cell, growing the drawing to a whole number of those cells
   * when it is not one already.
   *
   * This is the "add space if needed" half of grouping: a 27x20 drawing under 5-pixel cells is 5.4 x 4
   * cells, and the strip past the last whole cell is part of the drawing the solver would never see.
   * Rather than drop it, the drawing grows to 30x20 -- on the right and the bottom, where nothing that
   * was drawn moves -- so every pixel drawn stays in a cell that gets grouped. A side that already is a
   * whole number of cells is left alone, which is why switching between 6x6 and 12x12 never resizes
   * anything.
   */
  fun fitCells(size: Int) {
    val step = size.coerceAtLeast(1)
    val newColumns = alignUp(columns, step)
    val newRows = alignUp(rows, step)
    cellSize = step
    if (newColumns == columns && newRows == rows) return
    noteEdit()
    replaceGrid(newColumns, newRows, columns, rows, pixels.toList())
  }

  /**
   * On a fresh start, makes the blank sheet the largest one that fits the drawing area.
   *
   * The editor draws one drawing pixel per cell at its fixed cell size and puts its + and - buttons in
   * the cell just outside each edge, so the sheet plus that one-cell ring has to fit: [maxColumns] and
   * [maxRows] are the drawing-pixel counts already reduced by that ring. The sides are rounded down to
   * whole [cellSize] cells and kept at the editor's minimum; a drawing area too small for the minimum
   * clips rather than inventing a smaller legal grid.
   */
  fun fitBlankToCanvas(maxColumns: Int, maxRows: Int, cellSize: Int) {
    val step = cellSize.coerceAtLeast(1)
    val minSide = MIN_GRID_SIZE * step
    val columns = ((maxColumns.coerceAtMost(MAX_GRID_SIZE) / step) * step).coerceAtLeast(minSide)
    val rows = ((maxRows.coerceAtMost(MAX_GRID_SIZE) / step) * step).coerceAtLeast(minSide)
    restoreDrawing(columns, rows, IntArray(columns * rows) { blankColor.toArgb() }, step)
  }

  /**
   * Crops the drawing to what is actually drawn on it, keeping [borderCells] cells of background round it.
   *
   * "Drawn on it" means every cell that is not the background colour ([background]), so the plain margin
   * a drawing usually carries goes away and the picture stops paying for it: a 40x40 drawing of a 12x12
   * subject is solved from 12 cells, not 40.
   *
   * The border is not decoration. With the edges wrapping, one cell is enough for the pattern to run off
   * the drawing and back on; with the edges kept, the drawing's border is the picture's border and a
   * window centred on a border cell has to be complete, which takes two. The border is *added* when the
   * drawing sits against its own edge, so the result always has it, and the result is then rounded up to
   * whole grouping cells and to the editor's smallest grid -- the rules about cells do not stop applying
   * because a tool made the grid smaller.
   *
   * @return true when the drawing changed; a drawing that is all background has nothing to crop to.
   */
  fun cropToContent(borderCells: Int): Boolean {
    val background = background()
    var left = columns
    var right = -1
    var top = rows
    var bottom = -1
    for (row in 0 until rows) {
      for (column in 0 until columns) {
        if (pixels[row * columns + column] == background) continue
        if (column < left) left = column
        if (column > right) right = column
        if (row < top) top = row
        if (row > bottom) bottom = row
      }
    }
    if (right < 0) return false
    val border = borderCells.coerceAtLeast(0)
    val newColumns = alignUp(right - left + 1 + 2 * border, cellStep)
    val newRows = alignUp(bottom - top + 1 + 2 * border, cellStep)
    // The content is placed exactly one border in from the new top-left; any rounding-up slack and any
    // background that was outside the old grid lands on the right and the bottom.
    val offsetColumn = left - border
    val offsetRow = top - border
    if (newColumns == columns && newRows == rows && offsetColumn == 0 && offsetRow == 0) {
      return false
    }
    noteEdit()
    val newPixels = ArrayList<Color>(newColumns * newRows)
    val newPrevious = arrayOfNulls<Color>(newColumns * newRows)
    for (r in 0 until newRows) {
      val sourceRow = offsetRow + r
      for (c in 0 until newColumns) {
        val sourceColumn = offsetColumn + c
        if (sourceRow in 0 until rows && sourceColumn in 0 until columns) {
          val oldIdx = sourceRow * columns + sourceColumn
          newPixels.add(pixels[oldIdx])
          newPrevious[r * newColumns + c] = previousColors[oldIdx]
        } else {
          newPixels.add(background)
        }
      }
    }
    columns = newColumns
    rows = newRows
    pixels.clear()
    pixels.addAll(newPixels)
    previousColors = newPrevious
    revision++
    return true
  }

  /**
   * Replaces the grid with [newColumns] x [newRows], copying [source] into its top-left corner and
   * filling the rest with the source's own background colour -- see [backgroundColour] -- so the added
   * space extends the paper the drawing is on rather than the subject drawn on it. Cells the source does
   * not cover are new, so they have no previous colour to toggle back to.
   */
  private fun replaceGrid(
    newColumns: Int,
    newRows: Int,
    sourceColumns: Int,
    sourceRows: Int,
    source: List<Color>,
  ) {
    val filler = backgroundColour(source, sourceColumns, sourceRows, defaultColor)
    val newPixels = ArrayList<Color>(newColumns * newRows)
    val newPrevious = arrayOfNulls<Color>(newColumns * newRows)
    for (r in 0 until newRows) {
      for (c in 0 until newColumns) {
        if (r < sourceRows && c < sourceColumns) {
          val oldIdx = r * sourceColumns + c
          newPixels.add(source[oldIdx])
          newPrevious[r * newColumns + c] = previousColors.getOrNull(oldIdx)
        } else {
          newPixels.add(filler)
        }
      }
    }
    columns = newColumns
    rows = newRows
    pixels.clear()
    pixels.addAll(newPixels)
    previousColors = newPrevious
    revision++
  }

  /**
   * The drawing's own background colour, detected from its cells: the paper the clear and crop tools use
   * and the colour the drag-out preview is drawn against.
   */
  val backgroundColor: Color get() = background()

  /**
   * The colour a cell added at the edge starts as: the drawing's own background.
   *
   * Not [defaultColor], which is the palette's darkest swatch: that is the right answer for an empty
   * drawing, but on one that has a background of its own -- or was drawn on a palette whose darkest
   * colour is not the paper -- it puts a stripe of the wrong colour along the new edge.
   */
  private fun addedColour(): Color = background()

  /**
   * The drawing's background, detected from its cells and kept until the drawing changes.
   *
   * Detection walks every cell, and a tap asks for it whenever the tapped cell already shows the tapped
   * colour, so the answer is cached per [revision] instead of being recomputed on every tap.
   */
  private fun background(): Color {
    val cached = backgroundCache
    if (cached != null && backgroundCacheRevision == revision) return cached
    val detected = backgroundColour(pixels, columns, rows, defaultColor)
    backgroundCache = detected
    backgroundCacheRevision = revision
    return detected
  }

  private var backgroundCache: Color? = null
  private var backgroundCacheRevision: Int = -1

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
   *
   * When the cell already shows [color] and nothing was remembered -- it was painted that colour before
   * this session, or drawn into a restored pattern -- it reverts to the drawing's own background
   * ([background]), which is the colour a tap on a finished picture is asking to take away. Tapping a
   * cell that is already the background therefore does nothing.
   */
  fun paint(col: Int, row: Int, color: Color) {
    if (col !in 0 until columns || row !in 0 until rows) return
    val index = row * columns + col
    val current = pixels[index]
    if (current == color) {
      val revert = previousColors[index] ?: background()
      if (revert == current) return
      noteEdit()
      pixels[index] = revert
      previousColors[index] = null
      revision++
      return
    }
    noteEdit()
    previousColors[index] = current
    pixels[index] = color
    revision++
  }

  /**
   * The colour a tap at ([col], [row]) would leave in the cell, without changing the grid.
   *
   * [paint] toggles: a cell already showing [color] goes back to the colour remembered before it, or
   * the background when nothing was remembered. This answers that same question in advance, so a
   * long-press stroke can start by flipping the first cell and then paint the rest with the colour the
   * flip revealed.
   */
  fun toggleColor(col: Int, row: Int, color: Color): Color {
    if (col !in 0 until columns || row !in 0 until rows) return color
    val index = row * columns + col
    val current = pixels[index]
    return if (current == color) previousColors[index] ?: background() else color
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

  /**
   * Paints the whole drawing back to its background, as the clear tool does.
   *
   * The background, not [defaultColor]: clearing a sheet that was drawn on a warm paper has to leave the
   * paper, and a palette whose darkest swatch is not the paper would otherwise turn the drawing black --
   * or, worse, into the subject's own colour. Detected before anything changes, and the clear is a no-op
   * when the drawing is already that colour.
   */
  fun clear() {
    previousColors = arrayOfNulls(columns * rows)
    val background = background()
    if (pixels.all { it == background }) {
      return
    }
    noteEdit()
    for (i in pixels.indices) pixels[i] = background
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

  /**
   * Re-colours every cell to the closest colour in [palette] and reports how many cells changed.
   *
   * This is how a drawing moves to a new palette without losing its shapes: each existing colour is
   * replaced by its nearest entry in the palette, so the picture keeps its structure but is drawn only
   * with colours the new palette has. The whole swap is one undo step.
   *
   * [defaultColor] follows the new palette too, so a cell revealed by erasing does not bring a colour
   * from the old palette back -- and with it the background the clear tool paints, which is detected from
   * the drawing and therefore moves with it. The per-cell revert colours are dropped: after the migration
   * they refer to colours the drawing no longer uses.
   */
  fun migrateColors(palette: List<Color>): Int {
    if (palette.isEmpty()) return 0
    defaultColor = palette.minByOrNull { it.luminance() } ?: defaultColor

    // Resolve each distinct colour once rather than once per cell: a drawing is mostly a handful of
    // palette colours repeated over a large grid.
    val replacements = HashMap<Color, Color>()
    for (current in pixels) {
      if (current !in replacements) {
        replacements[current] = PaletteStore.closestColor(current, palette)
      }
    }
    val changed = pixels.count { replacements.getValue(it) != it }
    if (changed == 0) return 0

    noteEdit()
    for (index in pixels.indices) {
      val replacement = replacements.getValue(pixels[index])
      if (pixels[index] != replacement) pixels[index] = replacement
    }
    previousColors = arrayOfNulls(columns * rows)
    revision++
    return changed
  }

  /**
   * Takes on [palette]'s colour conventions without touching the drawing.
   *
   * This is the camera tool's commit: the photo was already mapped to the palette chosen in the tool, so
   * every cell already holds one of its colours and there is nothing to migrate -- but the colour a
   * revealed cell falls back to has to come from that palette, not from the one the drawing was made on.
   * No undo step, because nothing was drawn.
   */
  fun adoptPalette(palette: List<Color>) {
    if (palette.isEmpty()) return
    defaultColor = palette.minByOrNull { it.luminance() } ?: defaultColor
    revision++
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
    val background = background()
    noteEdit()
    val old = pixels.toList()
    val newColumns = rows
    val newRows = columns
    val turned = MutableList(newColumns * newRows) { background }
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
   * The cells the selection leaves behind go back to the drawing's background, like [clear]: the drawing
   * has no transparency, so a hole has to be filled with something, and the paper is what was there
   * before the move. The destination is clamped so the whole selection stays inside the grid, and the
   * source is read into a buffer first so a move that overlaps its own starting place does not smear.
   */
  fun moveCells(rect: CellRect, dCol: Int, dRow: Int): Boolean {
    if (rect.width <= 0 || rect.height <= 0) return false
    if (rect.right >= columns || rect.bottom >= rows || rect.left < 0 || rect.top < 0) return false
    val newLeft = (rect.left + dCol).coerceIn(0, columns - rect.width)
    val newTop = (rect.top + dRow).coerceIn(0, rows - rect.height)
    if (newLeft == rect.left && newTop == rect.top) return false

    val lifted = Array(rect.width * rect.height) { pixels[rect.top * columns + rect.left + it % rect.width + (it / rect.width) * columns] }
    val background = background()
    noteEdit()
    for (row in rect.top..rect.bottom) {
      for (col in rect.left..rect.right) pixels[row * columns + col] = background
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