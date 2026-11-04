/**
 * The drawing surface: zoom, pan, taps, drags, and the grid drawn over the cells -- including the two-cell band
 * around the edge that the edge rule cares about.
 *
 * The canvas turns a gesture into cells and reports them; it never changes the drawing itself.
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

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

/** The tag UI tests use to find the drawing: it has no other handle, being one pointer-driven Canvas. */
const val CANVAS_TEST_TAG = "pixel-canvas"

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
 * - [EditorMode.Line], [EditorMode.Rectangle], [EditorMode.Ellipse], [EditorMode.Triangle]: press, drag to size, release to
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
  /**
   * How many cells apart the grid separators are drawn.
   *
   * 1 is the ordinary per-pixel grid. When the recipe groups the drawing into cells this is the cell
   * side, so the separators show the kernel's cell boundaries instead of cutting every pixel into a cell.
   */
  gridStep: Int = 1,
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

              // A hold starts by toggling its first cell exactly like a tap. The colour that tap
              // revealed becomes the brush for the rest of the stroke, so holding a selected-colour
              // cell flips it back and then keeps painting with the restored colour.
              var strokeColor = selectedColor
              tapToPixel(held.position)?.let { (col, row) ->
                strokeColor = gridState.toggleColor(col, row, selectedColor)
                gridState.paint(col, row, selectedColor)
                stroke.add(row * gridState.columns + col)
                onTap()
              }

              fun paintAt(position: Offset) {
                val (col, row) = tapToPixel(position) ?: return
                // A cell is painted once per stroke, and painting it does not toggle: a stroke that
                // crosses a cell already showing the colour must leave it as it is.
                if (!stroke.add(row * gridState.columns + col)) return
                gridState.paintOver(col, row, strokeColor)
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

      // The shape being dragged out: the cells it would paint, drawn over the grid in the background's
      // opposite colour, so the outline of a shape can be seen whatever colour it is being drawn on.
      val previewCells = shapePreview
      if (previewCells.isNotEmpty()) {
        // The preview is a preview of the shape being painted, so it uses the paint colour itself.
        for (index in previewCells) {
          val col = index % gridState.columns
          val row = index / gridState.columns
          drawRect(selectedColor, Offset(col * cs, row * cs), cellSize)
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
      val gridLineColor = GRID_LINE_COLOR
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

      if (gridStep <= 1) {
        for (col in 0..gridState.columns) drawSeparator(vertical = true, lineIndex = col)
        for (row in 0..gridState.rows) drawSeparator(vertical = false, lineIndex = row)
      } else {
        /*
         * Grouped mode: one separator per cell, plus the outer edge. The pixels inside a cell are still
         * separate drawing pixels -- only the lines tell the eye where a kernel cell begins and ends.
         */
        fun groupSeparator(vertical: Boolean, lineIndex: Int) {
          val fixed = lineIndex * cs
          if (vertical) {
            drawLine(gridLineColor, Offset(fixed, 0f), Offset(fixed, h), lineWidth)
          } else {
            drawLine(gridLineColor, Offset(0f, fixed), Offset(w, fixed), lineWidth)
          }
        }
        var col = 0
        while (col < gridState.columns) {
          groupSeparator(vertical = true, lineIndex = col)
          col += gridStep
        }
        groupSeparator(vertical = true, lineIndex = gridState.columns)
        var row = 0
        while (row < gridState.rows) {
          groupSeparator(vertical = false, lineIndex = row)
          row += gridStep
        }
        groupSeparator(vertical = false, lineIndex = gridState.rows)
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
      color = if (enabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.15f),
      fontSize = 14.sp
    )
  }
}