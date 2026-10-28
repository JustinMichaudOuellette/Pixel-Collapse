package ca.justinmo.wfcbitmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.justinmo.wfcbitmap.BitmapEditor.BUTTON_SIZE_DP_INT
import ca.justinmo.wfcbitmap.BitmapEditor.ColorSwatch
import ca.justinmo.wfcbitmap.BitmapEditor.PixelEditorScreen
import ca.justinmo.wfcbitmap.ui.theme.WFCBitmapTheme
import kotlin.math.floor
import kotlin.math.roundToInt

object BitmapEditor {

// ─────────────────────────────────────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────────────────────────────────────

  /** Holds the mutable pixel grid state. */
  class PixelGridState(
    palette: List<Color> = PALETTE,
    val columns: Int = 16,
    val rows: Int = 11,
  ) {
    val defaultColor: Color = palette.minBy { it.luminance() }

    /** Flat array storing ARGB int for every pixel; 0 = transparent. */
    val pixels = mutableStateListOf<Color>().apply {
      repeat(columns * rows) { add(defaultColor) }
    }

    /**
     * Bumped on every edit. The live preview watches this one value instead of the pixel list: it is
     * a single snapshot state read, and it cannot miss two edits that cancel out.
     */
    var revision by mutableIntStateOf(0)
      private set

    fun getPixel(col: Int, row: Int): Color = pixels[row * columns + col]

    /** The colour each cell had before its last paint, so a second tap can put it back. */
    private val previousColors = arrayOfNulls<Color>(columns * rows)

    fun setPixel(col: Int, row: Int, color: Color) {
      val index = row * columns + col
      if (pixels[index] == color) {
        // Painting the same colour again is not an edit; regenerating for it would just repeat work.
        return
      }
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
      val index = row * columns + col
      val current = pixels[index]
      val previous = previousColors[index]
      if (previous != null && current == color) {
        pixels[index] = previous
        previousColors[index] = null
        revision++
        return
      }
      if (current != color) {
        previousColors[index] = current
        pixels[index] = color
        revision++
      }
    }

    fun clear() {
      previousColors.fill(null)
      if (pixels.all { it == defaultColor }) {
        return
      }
      for (i in pixels.indices) pixels[i] = defaultColor
      revision++
    }
  }

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

  /**
   * @param preview fills the column left of the drawing grid. It is kept as a slot so the editor does
   *   not need to know anything about the wave-function collapse that produces the bitmap shown there.
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
    /**
     * One line under the drawing when the generated bitmap is not a plain edge-respecting tiling of it,
     * and what to do about it. Null (the usual case) draws nothing, so the column is the drawing alone.
     *
     * It lives here and not in the preview because the preview is the picture and nothing else.
     */
    notice: String? = null,
    preview: @Composable () -> Unit = {},
  ) {
    var selectedColor by remember { mutableStateOf(Color.Black) }

    Row(modifier = modifier) {
        // The live bitmap preview: the same 9:16 shape as the generated output, so what is shown
        // here is the picture at the aspect ratio it will be saved in.
        Box(
          modifier = Modifier
            .aspectRatio(9f / 16f)
            .fillMaxHeight()
            .background(PREVIEW_BACKGROUND),
        ) {
          preview()
        }

      VerticalDividerLine()

        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
        ) {
          val baseCellSizePx = LocalDensity.current.run { BUTTON_SIZE.toPx().roundToInt() }

          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ZoomablePixelCanvas(
              gridState = gridState,
              selectedColor = selectedColor,
              baseCellSizePx = baseCellSizePx,
              onTap = onTap,
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
          onClear = { gridState.clear() },
        )
    }
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
// Improved ZoomablePixelCanvas with size tracking
// ─────────────────────────────────────────────────────────────────────────────

  @Composable
  fun ZoomablePixelCanvas(
    gridState: PixelGridState,
    selectedColor: Color,
    minZoom: Float = 0.5f,
    maxZoom: Float = 10f,
    baseCellSizePx: Int,
    onTap: () -> Unit = {},
  ) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun tapToPixel(screenPos: Offset): Pair<Int, Int>? {
      val localX = (screenPos.x - offset.x) / scale
      val localY = (screenPos.y - offset.y) / scale
      val col = floor(localX / baseCellSizePx).toInt()
      val row = floor(localY / baseCellSizePx).toInt()
      return if (col in 0 until gridState.columns && row in 0 until gridState.rows)
        col to row else null
    }

    Canvas(
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
        // Single-finger draw
        .pointerInput(gridState, selectedColor) {
          detectTapGestures { tapOffset ->
            tapToPixel(tapOffset)?.let { (col, row) ->
              gridState.paint(col, row, selectedColor)
              // After the paint, so a generation requested from here sees this edit. It is also where
              // the end-to-end measurement starts: the tap has become a change to the drawing.
              onTap()
            }
          }
        }
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
    onClear: () -> Unit,
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

//        Spacer(Modifier.width(8.dp))

        // Clear button
//        IconButton(
//          onClick = onClear,
//          modifier = Modifier
//            .size(44.dp)
//            .clip(RoundedCornerShape(8.dp))
//            .background(Color(0xFF0F3460))
//        ) {
//          Icon(
//            imageVector = Icons.Default.Delete,
//            contentDescription = "Clear canvas",
//            tint = Color(0xFFE94560),
//          )
//        }
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

@Preview(heightDp = BUTTON_SIZE_DP_INT, widthDp = BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchPreviewPreview() {
  WFCBitmapTheme {
    ColorSwatch(
      color = Color.Red,
      isSelected = false,
      onClick = {},
    )
  }
}

@Preview(heightDp = BUTTON_SIZE_DP_INT, widthDp = BUTTON_SIZE_DP_INT, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun ColorSwatchSelectedPreviewPreview() {
  WFCBitmapTheme {
    ColorSwatch(
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
    PixelEditorScreen()
  }
}
