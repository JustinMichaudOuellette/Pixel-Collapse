package ca.justinmo.wfcbitmap.ui

import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import ca.justinmo.wfcbitmap.editor.DitherMode
import ca.justinmo.wfcbitmap.editor.PhotoPattern
import ca.justinmo.wfcbitmap.editor.PhotoPatternPipeline
import ca.justinmo.wfcbitmap.palette.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "CameraPattern"
private const val MAX_PALETTE_CHOICES = 64

/**
 * How many times a capture is read back before the tool gives up.
 *
 * Some camera apps report a result before the file has been flushed, so the first read can see an
 * empty or truncated file even though the photo itself is fine.
 */
private const val READ_ATTEMPTS = 4
private const val READ_RETRY_MILLIS = 150L

/**
 * The camera tool's screens, in the order a photo walks through them.
 *
 * Each screen owns one step of the pipeline and its own controls. Back -- the header's arrow, the
 * system gesture, or the tool's own button -- returns to the screen before it, which is why there is
 * no Retake on the later screens: going back to [Photo] *is* the retake.
 */
private enum class CameraStep(val title: String, val caption: String) {
  Photo("Photo", "Take a photo, or choose one from the gallery"),
  Downscale("Downscale", "Reduce the photo to the drawing's grid"),
  Posterize("Posterize", "Map it to the colours of a palette"),
  Dither("Dither", "Spread the error the palette could not take"),
}

/** The screen before this one, or null on the first screen. */
private fun CameraStep.previous(): CameraStep? = CameraStep.entries.getOrNull(ordinal - 1)

/** The screen after this one, or null on the last. */
private fun CameraStep.next(): CameraStep? = CameraStep.entries.getOrNull(ordinal + 1)

/**
 * The camera tool: take or choose a photo, then walk it through downscale, palette posterisation and
 * dithering -- one screen per step -- before it replaces the drawing.
 *
 * The tool keeps every step's result in this composition, so going back one screen to change the grid
 * size or the palette does not re-take the photo or re-do the decode.
 *
 * The camera capture and the system photo picker both live here. Neither needs a runtime permission:
 * the camera intent writes through a cache FileProvider URI, and the photo picker returns a read grant
 * for the chosen image.
 */
@Composable
internal fun CameraPatternScreen(
  open: Boolean,
  palettes: List<Palette>,
  aspect: Float,
  cellSize: Int,
  palette: Palette,
  onDismiss: () -> Unit,
  onApply: (PhotoPattern, Palette) -> Unit,
) {
  if (!open) return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var step by remember { mutableStateOf(CameraStep.Photo) }
  var photo by remember { mutableStateOf<Bitmap?>(null) }
  var cameraUri by remember { mutableStateOf<Uri?>(null) }
  var cameraFile by remember { mutableStateOf<File?>(null) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // The palette the posterize screen maps the photo to. It starts on the editor's palette, and the
  // app switches to whatever was used here when the pattern is applied.
  var chosenPalette by remember(palette) { mutableStateOf(palette) }

  var columns by remember(cellSize) {
    mutableIntStateOf(PhotoPatternPipeline.nearestColumns(PhotoPatternPipeline.DEFAULT_COLUMNS.toFloat(), cellSize))
  }
  val paletteArgb = remember(chosenPalette) {
    IntArray(chosenPalette.colors.size) { chosenPalette.colors[it].toArgb() }
  }
  val maxPaletteColors = paletteArgb.size.coerceIn(1, MAX_PALETTE_CHOICES)
  var colorCount by remember(chosenPalette) { mutableIntStateOf(maxPaletteColors) }
  var dither by remember { mutableStateOf(DitherMode.FloydSteinberg) }
  var strength by remember { mutableFloatStateOf(1f) }

  fun load(uri: Uri, file: File?) {
    busy = true
    error = null
    Log.i(TAG, "load $uri (${file?.let { "${it.length()} bytes at ${it.absolutePath}" } ?: "not a capture"})")
    scope.launch {
      var decoded: Bitmap? = null
      var attempt = 0
      while (decoded == null && attempt < READ_ATTEMPTS) {
        if (attempt > 0) delay(READ_RETRY_MILLIS)
        decoded = withContext(Dispatchers.IO) {
          runCatching { PhotoPatternPipeline.decode(context, uri) }
            .onFailure { Log.w(TAG, "read ${attempt + 1} threw for $uri", it) }
            .getOrNull()
        }
        Log.i(
          TAG,
          "read ${attempt + 1}/$READ_ATTEMPTS: " +
            (decoded?.let { "${it.width}x${it.height}" } ?: "no image") +
            ", ${file?.length() ?: -1L} bytes on disk",
        )
        attempt++
      }
      if (decoded == null) {
        val bytes = file?.length() ?: -1L
        error = if (bytes == 0L) {
          "The camera returned an empty file"
        } else {
          "That image could not be read"
        }
        Log.w(TAG, "gave up on $uri after $attempt reads, $bytes bytes on disk")
      } else {
        photo = decoded
        Log.i(TAG, "photo ready: ${decoded.width}x${decoded.height}")
        // A readable photo is what the first screen was waiting for: show its downscale next.
        step = CameraStep.Downscale
      }
      busy = false
    }
  }

  val cameraLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture(),
  ) { success ->
    val uri = cameraUri
    val file = cameraFile
    Log.i(
      TAG,
      "capture result: success=$success, uri=$uri, ${file?.length() ?: -1L} bytes at ${file?.absolutePath}",
    )
    if (success && uri != null) {
      load(uri, file)
    } else {
      error = "No photo was taken"
      Log.i(TAG, "capture was cancelled or reported no photo")
    }
  }

  val pickerLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    Log.i(TAG, "picked $uri")
    if (uri != null) load(uri, file = null)
  }

  fun takePhoto() {
    try {
      val directory = File(context.cacheDir, "camera").apply { mkdirs() }
      val file = File(directory, "photo-${System.currentTimeMillis()}.jpg").apply { createNewFile() }
      val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
      )
      cameraUri = uri
      cameraFile = file
      Log.i(TAG, "capture into $uri (${file.absolutePath})")
      cameraLauncher.launch(uri)
    } catch (cause: ActivityNotFoundException) {
      error = "No camera app is available"
      Log.w(TAG, "no camera app to take a picture", cause)
    } catch (cause: Throwable) {
      error = "The camera could not be opened"
      Log.w(TAG, "could not start the camera", cause)
    }
  }

  fun choosePhoto() {
    runCatching {
      pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }.onFailure {
      error = "No photo picker is available"
      Log.w(TAG, "no photo picker to choose a picture", it)
    }
  }

  val target = remember(columns, aspect, cellSize) {
    PhotoPatternPipeline.targetSize(columns, aspect, cellSize)
  }

  // Crop once to the output shape, not once per slider tick: the target is at most one cell away from
  // this aspect, and the difference is not visible at the sizes the tool offers.
  val cropped = remember(photo, aspect) {
    photo?.let { PhotoPatternPipeline.cropToAspect(it, aspect) }
  }
  val downscaled = remember(cropped, target) {
    cropped?.let { PhotoPatternPipeline.downscale(it, target.first, target.second) }
  }
  val subset = remember(downscaled, paletteArgb, colorCount) {
    downscaled?.let { PhotoPatternPipeline.paletteSubset(it, paletteArgb, colorCount) } ?: IntArray(0)
  }
  val posterized = remember(downscaled, subset) {
    downscaled?.let { PhotoPatternPipeline.posterize(it, subset) }
  }
  val dithered = remember(downscaled, target, subset, dither, strength) {
    downscaled?.let {
      PhotoPatternPipeline.dither(
        pixels = it,
        width = target.first,
        height = target.second,
        subset = subset,
        mode = dither,
        strength = strength,
      )
    }
  }

  val sourceImage = remember(cropped) { cropped?.asImageBitmap() }
  val downImage = remember(downscaled, target) { downscaled?.asImage(target) }
  val posterImage = remember(posterized, target) { posterized?.asImage(target) }
  val finalImage = remember(dithered, target) { dithered?.asImage(target) }

  fun goBack() {
    val previous = step.previous()
    if (previous == null) onDismiss() else step = previous
  }

  Dialog(
    // Back -- the system gesture or the header arrow -- stays inside the tool until it reaches the
    // first screen, where it closes it.
    onDismissRequest = { goBack() },
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false,
    ),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = PANEL_BACKGROUND) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing),
      ) {
        CameraHeader(step = step, onBack = { goBack() }, onClose = onDismiss)
        if (busy) {
          LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (error != null) {
          Text(
            error.orEmpty(),
            color = Color(0xFFFF8A80),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
          when (step) {
            CameraStep.Photo -> SourceStep(
              photo = sourceImage,
              enabled = !busy,
              onTakePhoto = { takePhoto() },
              onChoosePhoto = { choosePhoto() },
            )

            CameraStep.Downscale -> DownscaleStep(
              image = downImage,
              width = target.first,
              height = target.second,
              columns = columns,
              range = remember(cellSize) { PhotoPatternPipeline.columnRange(cellSize) },
              onColumns = { columns = PhotoPatternPipeline.nearestColumns(it, cellSize) },
            )

            CameraStep.Posterize -> PosterizeStep(
              image = posterImage,
              palettes = palettes,
              chosen = chosenPalette,
              subset = subset,
              colorCount = colorCount,
              maxColors = maxPaletteColors,
              onPalette = { chosenPalette = it },
              onColorCount = { colorCount = it.roundToInt().coerceIn(1, maxPaletteColors) },
            )

            CameraStep.Dither -> DitherStep(
              image = finalImage,
              dither = dither,
              strength = strength,
              onDither = { dither = it },
              onStrength = { strength = it },
            )
          }
        }

        Surface(color = PANEL_BACKGROUND) {
          val next = step.next()
          val pattern = dithered
          val readyWhen = when (step) {
            CameraStep.Photo -> photo != null
            CameraStep.Dither -> pattern != null
            else -> true
          }
          Button(
            onClick = {
              when (next) {
                null -> if (pattern != null) {
                  onApply(PhotoPattern(target.first, target.second, pattern.copyOf()), chosenPalette)
                }
                else -> step = next
              }
            },
            enabled = readyWhen && !busy,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 10.dp)
              .height(52.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MENU_TEXT_COLOR,
              contentColor = Color.Black,
            ),
          ) {
            Text(
              text = next?.let { "Next: ${it.title}" } ?: "Use as pattern",
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

/** The header: back to the screen before this one, the step's name, and the way out of the tool. */
@Composable
private fun CameraHeader(step: CameraStep, onBack: () -> Unit, onClose: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 4.dp, end = 8.dp, top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack) {
      Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = step.previous()?.let { "Back to ${it.title}" } ?: "Close",
        tint = MENU_TEXT_COLOR,
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = step.title, color = MENU_TEXT_COLOR, fontSize = 20.sp)
      Text(
        text = step.caption,
        color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconButton(onClick = onClose) {
      Icon(Icons.Default.Close, contentDescription = "Close", tint = MENU_TEXT_COLOR)
    }
  }
  // The four steps as one bar: how far along the photo is, without a line of numbers.
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    CameraStep.entries.forEach { entry ->
      Box(
        modifier = Modifier
          .weight(1f)
          .height(3.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(
            if (entry.ordinal <= step.ordinal) {
              MENU_TEXT_COLOR
            } else {
              MENU_TEXT_COLOR.copy(alpha = 0.18f)
            },
          ),
      )
    }
  }
}

/** The first screen: take a photo or pick one, with the last photo still on screen. */
@Composable
private fun SourceStep(
  photo: ImageBitmap?,
  enabled: Boolean,
  onTakePhoto: () -> Unit,
  onChoosePhoto: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF101014))
        .border(1.dp, MENU_DIVIDER_COLOR, RoundedCornerShape(12.dp)),
      contentAlignment = Alignment.Center,
    ) {
      if (photo != null) {
        Image(
          bitmap = photo,
          contentDescription = "The photo to use",
          contentScale = ContentScale.Fit,
          filterQuality = FilterQuality.None,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = MENU_TEXT_COLOR.copy(alpha = 0.65f),
            modifier = Modifier.size(56.dp),
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "Take a photo, or choose one from the gallery",
            color = MENU_TEXT_COLOR.copy(alpha = 0.65f),
            fontSize = 13.sp,
          )
        }
      }
    }

    Spacer(Modifier.height(14.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Button(
        onClick = onTakePhoto,
        enabled = enabled,
        modifier = Modifier
          .weight(1f)
          .height(48.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MENU_TEXT_COLOR,
          contentColor = Color.Black,
        ),
      ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Take photo", fontSize = 13.sp)
      }
      Button(
        onClick = onChoosePhoto,
        enabled = enabled,
        modifier = Modifier
          .weight(1f)
          .height(48.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = MENU_TEXT_COLOR.copy(alpha = 0.14f),
          contentColor = MENU_TEXT_COLOR,
        ),
      ) {
        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Choose photo", fontSize = 13.sp)
      }
    }
    Text(
      "The photo is cropped to the output shape, reduced to a small grid, posterised to a palette and " +
        "dithered. The result becomes the drawing the pattern is generated from; applying it is undoable.",
      color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
      fontSize = 11.sp,
      lineHeight = 15.sp,
      modifier = Modifier.padding(top = 10.dp),
    )
  }
}

/** Screen one of the pipeline: how many cells the photo is reduced to. */
@Composable
private fun DownscaleStep(
  image: ImageBitmap?,
  width: Int,
  height: Int,
  columns: Int,
  range: IntRange,
  onColumns: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
    StepPreview(
      image = image,
      description = "The photo at the drawing's grid",
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.height(12.dp))
    PhotoHeading("Drawing size", "$width × $height cells")
    PhotoSlider(
      value = columns.toFloat(),
      range = range.first.toFloat()..range.last.toFloat(),
      valueText = "$columns columns",
      onChange = onColumns,
    )
    Text(
      "The photo is reduced to this many cells. Larger keeps more of the photograph's detail; " +
        "smaller makes bolder shapes for the pattern.",
      color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
      fontSize = 11.sp,
      lineHeight = 15.sp,
    )
  }
}

/** Screen two: which palette the photo's colours are reduced to, and how many of them. */
@Composable
private fun PosterizeStep(
  image: ImageBitmap?,
  palettes: List<Palette>,
  chosen: Palette,
  subset: IntArray,
  colorCount: Int,
  maxColors: Int,
  onPalette: (Palette) -> Unit,
  onColorCount: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
    StepPreview(
      image = image,
      description = "The photo posterised to the palette",
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.height(10.dp))
    PalettePicker(palettes = palettes, selected = chosen, onSelect = onPalette)
    Spacer(Modifier.height(8.dp))
    PhotoSlider(
      value = colorCount.toFloat(),
      // A one-colour palette has no range to slide over; keep the thumb well defined rather than
      // handing the slider a start equal to its end.
      range = 1f..maxColors.coerceAtLeast(2).toFloat(),
      valueText = "$colorCount of ${chosen.colors.size} colours used",
      enabled = maxColors > 1,
      onChange = onColorCount,
    )
    // The colours the photo actually kept: the palette's entries it leans on, in palette order.
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      subset.forEach { argb ->
        Box(
          modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(argb)),
        )
      }
    }
    Text(
      "The photo is mapped to the selected palette, keeping the colours it uses most. Fewer colours " +
        "give a flatter, more graphic pattern.",
      color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
      fontSize = 11.sp,
      lineHeight = 15.sp,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/** Screen three: how the error from that palette mapping is spread between neighbouring cells. */
@Composable
private fun DitherStep(
  image: ImageBitmap?,
  dither: DitherMode,
  strength: Float,
  onDither: (DitherMode) -> Unit,
  onStrength: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
    StepPreview(
      image = image,
      description = "The dithered camera pattern",
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.height(12.dp))
    PhotoHeading("Dither", dither.label)
    DitherMode.entries.chunked(2).forEach { row ->
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        row.forEach { mode ->
          val chosen = dither == mode
          Button(
            onClick = { onDither(mode) },
            modifier = Modifier
              .weight(1f)
              .height(40.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = if (chosen) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.12f),
              contentColor = if (chosen) Color.Black else MENU_TEXT_COLOR,
            ),
          ) {
            Text(mode.label, fontSize = 11.sp, maxLines = 1)
          }
        }
        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
      }
      Spacer(Modifier.height(6.dp))
    }
    PhotoSlider(
      value = strength,
      range = 0f..1f,
      valueText = if (dither == DitherMode.None) "off" else "${(strength * 100).roundToInt()}%",
      enabled = dither != DitherMode.None,
      onChange = onStrength,
    )
    Text(
      "Error diffusion follows the picture; an ordered matrix lays a regular texture over it. The " +
        "strength controls how far the error or threshold pushes each cell.",
      color = MENU_TEXT_COLOR.copy(alpha = 0.55f),
      fontSize = 11.sp,
      lineHeight = 15.sp,
    )
  }
}

/** A step's picture, letterboxed in whatever room the screen has left. */
@Composable
private fun StepPreview(
  image: ImageBitmap?,
  description: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Text("No readable photo", color = MENU_TEXT_COLOR.copy(alpha = 0.6f), fontSize = 12.sp)
    }
  }
}

/**
 * The palettes the photo can be posterised to.
 *
 * A row of chips rather than a menu: the posterize screen is a screen of its own, so the whole list can
 * sit under the picture and be scrolled through with the result in sight. The chip's band is drawn, so a
 * palette of hundreds of colours costs one draw call and not one composable per entry.
 */
@Composable
private fun PalettePicker(
  palettes: List<Palette>,
  selected: Palette,
  onSelect: (Palette) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    PhotoHeading("Palette", selected.name)
    Spacer(Modifier.height(6.dp))
    val state = rememberLazyListState(
      initialFirstVisibleItemIndex = palettes.indexOfFirst { it.id == selected.id }.coerceAtLeast(0),
    )
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(palettes, key = { it.id }) { item ->
        PaletteChip(
          palette = item,
          selected = item.id == selected.id,
          onClick = { onSelect(item) },
        )
      }
    }
  }
}

@Composable
private fun PaletteChip(palette: Palette, selected: Boolean, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .width(112.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) MENU_TEXT_COLOR else MENU_TEXT_COLOR.copy(alpha = 0.10f))
      .clickable(onClick = onClick)
      .padding(6.dp),
  ) {
    PaletteBand(
      colors = palette.colors,
      modifier = Modifier
        .fillMaxWidth()
        .height(12.dp)
        .clip(RoundedCornerShape(3.dp)),
    )
    Text(
      text = palette.name,
      color = if (selected) Color.Black else MENU_TEXT_COLOR,
      fontSize = 10.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 4.dp),
    )
  }
}

@Composable
private fun PhotoHeading(title: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(title, color = MENU_TEXT_COLOR, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.weight(1f))
    Text(
      value,
      color = MENU_TEXT_COLOR.copy(alpha = 0.6f),
      fontSize = 11.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun PhotoSlider(
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  valueText: String,
  enabled: Boolean = true,
  onChange: (Float) -> Unit,
) {
  Text(valueText, color = MENU_TEXT_COLOR.copy(alpha = 0.55f), fontSize = 10.sp)
  Slider(
    value = value,
    onValueChange = onChange,
    valueRange = range,
    enabled = enabled,
    colors = SliderDefaults.colors(
      thumbColor = MENU_TEXT_COLOR,
      activeTrackColor = MENU_TEXT_COLOR,
      inactiveTrackColor = MENU_TEXT_COLOR.copy(alpha = 0.25f),
    ),
  )
}

private fun IntArray.asImage(size: Pair<Int, Int>): ImageBitmap =
  Bitmap.createBitmap(this, size.first, size.second, Bitmap.Config.ARGB_8888).asImageBitmap()
