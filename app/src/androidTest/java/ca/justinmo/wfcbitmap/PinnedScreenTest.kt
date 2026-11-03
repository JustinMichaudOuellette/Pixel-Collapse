package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A pinned screen must actually keep the centres it was given. This is the contract the video stitching
 * relies on; it needs a device because the solver is native and the generation returns a Bitmap.
 */
@RunWith(AndroidJUnit4::class)
class PinnedScreenTest {

  @Test
  fun aPinnedEdgeComesBackWithExactlyThoseCentres() = runBlocking {
    for (cellSize in listOf(1, 2, 3)) {
      pinOneScreen(cellSize)
    }
  }

  @Test
  fun theAgreedTestPatternKeepsItsEdgeAtEveryKernelSize() = runBlocking {
    // AGENTS.md's canonical 9x9 drawing: '*' filled, '_' empty.
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val width = rows.first().length
    val height = rows.size
    val filled = 0xFFF2AA4C.toInt()
    val empty = 0xFF101820.toInt()
    val pixels = IntArray(width * height) { index ->
      if (rows[index / width][index % width] == '*') filled else empty
    }
    for (cellSize in listOf(1, 2, 3)) {
      val columns = 24
      val gridRows = 24
      val scale = 4
      val first = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 7001,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = scale,
        cellSize = cellSize,
      )
      // Copy before the next generation: the generator reuses its two bitmap buffers. The bitmap is the
      // grid expanded by `scale`, so a cell row is `scale` pixel rows tall and indexing by cell row
      // alone would compare one cell row's centres against a different cell row's pixels.
      val frameWidth = columns * scale
      val frameHeight = gridRows * scale
      val firstPixels = IntArray(frameWidth * frameHeight)
      first.bitmap.getPixels(firstPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
      val centers = first.cellCenters ?: error("first screen has no centres")
      val overlap = 1
      val fixed = IntArray(columns * gridRows) { Int.MIN_VALUE }
      for (row in 0 until gridRows) {
        for (cell in 0 until overlap) {
          fixed[row * columns + cell] = centers[row * columns + (columns - overlap + cell)]
        }
      }
      val second = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 7001 + 1013,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = scale,
        cellSize = cellSize,
        fixedCenters = fixed,
        forceRelaxedEdges = true,
      )
      val secondPixels = IntArray(frameWidth * frameHeight)
      second.bitmap.getPixels(secondPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
      val secondCenters = second.cellCenters ?: error("second screen has no centres")
      val overlapPixels = overlap * scale
      for (row in 0 until gridRows) {
        for (cell in 0 until overlap) {
          val firstCell = row * columns + (columns - overlap + cell)
          // A cell's centre is the ARGB pixel it shows only for an ungrouped drawing. With a 2x2 or 3x3
          // kernel the centre names the whole block, so it is a token and can never equal a pixel; the
          // rendered edge check below covers every kernel size for that case.
          if (cellSize == 1) {
            assertEquals(
              "cellSize $cellSize first centre $firstCell",
              centers[firstCell],
              firstPixels[row * scale * frameWidth + (frameWidth - overlapPixels + cell * scale)],
            )
            assertEquals(
              "cellSize $cellSize second render $firstCell",
              secondCenters[row * columns + cell],
              secondPixels[row * scale * frameWidth + cell * scale],
            )
          }
          assertEquals(
            "cellSize $cellSize pinned centre $firstCell",
            centers[firstCell],
            secondCenters[row * columns + cell],
          )
          // The pinned cell's whole rendered square must come from the previous screen, whatever the
          // kernel: that is what the seam in the panorama actually shows.
          for (dy in 0 until scale) {
            for (dx in 0 until scale) {
              assertEquals(
                "cellSize $cellSize pinned pixel $firstCell +$dx,$dy",
                firstPixels[(row * scale + dy) * frameWidth + (frameWidth - overlapPixels + cell * scale + dx)],
                secondPixels[(row * scale + dy) * frameWidth + cell * scale + dx],
              )
            }
          }
        }
      }
      for (y in 0 until frameHeight) {
        for (x in 0 until overlapPixels) {
          assertEquals(
            "cellSize $cellSize edge $y,$x",
            firstPixels[y * frameWidth + (frameWidth - overlapPixels + x)],
            secondPixels[y * frameWidth + x],
          )
        }
      }
    }
  }

  @Test
  fun aRealVideoScreenCanBePinned() = runBlocking {
    val width = 9
    val height = 9
    val colors = intArrayOf(
      0xFF101820.toInt(), 0xFFF2AA4C.toInt(), 0xFF2E8B57.toInt(), 0xFF6A5ACD.toInt(),
      0xFFB22222.toInt(), 0xFF4682B4.toInt(), 0xFFDAA520.toInt(), 0xFF8FBC8F.toInt(),
    )
    val pixels = IntArray(width * height) { index ->
      colors[(index * 7 + index / width * 3) % colors.size]
    }
    val columns = 76
    val rows = 135
    // cellSize 1 is the plain drawing, 3 is the shipped default; the export runs both, and a grouped
    // cell's centre is a token rather than a pixel, so the pin means something different in each.
    for (cellSize in listOf(1, 3)) {
      val patterns = patternsOf(pixels, width, height, cellSize)
      val first = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 1234,
        columns = columns,
        rows = rows,
        wrapEdges = true,
        pixelsPerCell = 4,
        cellSize = cellSize,
      )
      val centers = first.cellCenters ?: error("first screen has no centres")
      val overlap = 12
      val fixed = IntArray(columns * rows) { Int.MIN_VALUE }
      for (row in 0 until rows) {
        for (cell in 0 until overlap) {
          fixed[row * columns + cell] = centers[row * columns + (columns - overlap + cell)]
        }
      }
      val second = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 1234 + 1013,
        columns = columns,
        rows = rows,
        wrapEdges = true,
        pixelsPerCell = 4,
        cellSize = cellSize,
        fixedCenters = fixed,
        forceRelaxedEdges = true,
        timeoutMillis = 15_000L,
      )
      val secondCenters = second.cellCenters ?: error("second screen has no centres")
      for (index in fixed.indices) {
        if (fixed[index] == Int.MIN_VALUE) continue
        assertEquals("cellSize $cellSize real screen pinned centre $index", fixed[index], secondCenters[index])
      }
      assertIsATiling(secondCenters, columns, rows, patterns, "cellSize $cellSize real screen")
      val frameWidth = columns * 4
      val frameHeight = rows * 4
      val firstPixels = IntArray(frameWidth * frameHeight)
      first.bitmap.getPixels(firstPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
      val secondPixels = IntArray(frameWidth * frameHeight)
      second.bitmap.getPixels(secondPixels, 0, frameWidth, 0, 0, frameWidth, frameHeight)
      val overlapPixels = overlap * 4
      for (row in 0 until frameHeight) {
        for (x in 0 until overlapPixels) {
          assertEquals(
            "cellSize $cellSize real rendered edge $row,$x",
            firstPixels[row * frameWidth + (frameWidth - overlapPixels + x)],
            secondPixels[row * frameWidth + x],
          )
        }
      }
    }
  }

  /**
   * The band is forced, so it always comes back exactly -- that is what the tests above check. What can
   * still go wrong is the free cell just to its right: pinning narrows domains outside the solver's own
   * trail bookkeeping, so that narrowing has to be announced to the neighbours itself. If it is not, a
   * seam can end up with a pattern the drawing does not contain, which is what "the pixels between
   * screens are off" looks like.
   *
   * Every 3x3 window of a tiling has to be one of the drawing's own windows, so checking that across
   * the band boundary is the direct test of whether the seam is a tiling at all.
   */
  @Test
  fun aPinnedSeamIsStillATilingOfTheDrawing() = runBlocking {
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val width = rows.first().length
    val height = rows.size
    val filled = 0xFFF2AA4C.toInt()
    val empty = 0xFF101820.toInt()
    val pixels = IntArray(width * height) { index ->
      if (rows[index / width][index % width] == '*') filled else empty
    }
    for (cellSize in listOf(1, 2, 3)) {
      val columns = 24
      val gridRows = 24
      val scale = 2
      val first = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 4242,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = scale,
        cellSize = cellSize,
      )
      val centers = first.cellCenters ?: error("first screen has no centres")
      val overlap = 3
      val fixed = IntArray(columns * gridRows) { Int.MIN_VALUE }
      for (row in 0 until gridRows) {
        for (cell in 0 until overlap) {
          fixed[row * columns + cell] = centers[row * columns + (columns - overlap + cell)]
        }
      }
      val second = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 4242 + 1013,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = scale,
        cellSize = cellSize,
        fixedCenters = fixed,
        forceRelaxedEdges = true,
      )
      val secondCenters = second.cellCenters ?: error("second screen has no centres")
      assertIsATiling(secondCenters, columns, gridRows, patternsOf(pixels, width, height, cellSize), "cellSize $cellSize")
    }
  }

  /**
   * The whole export, as `PixelEditor.exportVideo` runs it: generate each frame-sized screen, pin its
   * left edge to the previous screen's right edge, and stitch them into a panorama.
   *
   * This is the check the bug was about. Every screen on its own can look right -- the pinned band is
   * forced, and `stitchScreen` copies that band over from the panorama anyway -- so a seam only shows up
   * here, as a 3x3 window of the stitched panorama that the drawing does not contain, or as a screen
   * that had to give up on its pins and be generated unconstrained.
   */
  @Test
  fun aStitchedPanoramaIsATilingAcrossEverySeam() = runBlocking {
    // Two drawings, because they fail differently. The 9x9 test pattern is the reported case and has two
    // colours, so only 512 windows are possible and a broken seam could hit one of the 81 sampled ones by
    // luck; the colour-rich one makes that essentially impossible, so a seam that does not fit cannot
    // hide. Every kernel matters because a grouped cell's centre is a token, not a pixel.
    val canonicalRows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val canonical = IntArray(81) { index ->
      if (canonicalRows[index / 9][index % 9] == '*') 0xFFF2AA4C.toInt() else 0xFF101820.toInt()
    }
    val colors = intArrayOf(
      0xFF101820.toInt(), 0xFFF2AA4C.toInt(), 0xFF2E8B57.toInt(), 0xFF6A5ACD.toInt(),
      0xFFB22222.toInt(), 0xFF4682B4.toInt(), 0xFFDAA520.toInt(), 0xFF8FBC8F.toInt(),
    )
    val rich = IntArray(81) { index -> colors[(index * 7 + index / 9 * 3) % colors.size] }
    val width = 9
    val height = 9
    val frameColumns = 24
    val frameRows = 24
    val scale = 4
    for ((name, pixels) in listOf("canonical" to canonical, "rich" to rich)) {
    for (cellSize in listOf(1, 3)) {
      val plan = videoPlan(
        frameColumns = frameColumns,
        frameRows = frameRows,
        pixelsPerCell = scale,
        cellSize = cellSize,
        screens = 3,
        fps = 25,
      )
      assertEquals("three screens", 3, plan.screenCount)
      val patterns = patternsOf(pixels, width, height, cellSize)
      val panorama = IntArray(plan.panoramaWidth * plan.frameHeight)
      var fallbacks = 0
      var previousCenters: IntArray? = null
      for (screen in 0 until plan.screenCount) {
        val fixed = previousCenters?.let { previous ->
          IntArray(frameColumns * frameRows) { Int.MIN_VALUE }.also { pinned ->
            for (row in 0 until frameRows) {
              val base = row * frameColumns
              for (cell in 0 until plan.overlapColumns) {
                pinned[base + cell] = previous[base + (frameColumns - plan.overlapColumns + cell)]
              }
            }
          }
        }
        val generated = try {
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = width,
            sourceHeight = height,
            seed = 9001 + screen * 1013,
            columns = frameColumns,
            rows = frameRows,
            wrapEdges = true,
            pixelsPerCell = scale,
            cellSize = cellSize,
            timeoutMillis = 15_000L,
            fixedCenters = fixed,
            forceRelaxedEdges = true,
          )
        } catch (e: WfcGenerator.GenerationException) {
          // The export's own fallback: an unsolvable pinned screen becomes an unconstrained one. It is
          // allowed, but every one of them is a seam that no longer continues the previous screen.
          if (fixed == null) throw e
          fallbacks++
          WfcGenerator.generate(
            pixels = pixels,
            sourceWidth = width,
            sourceHeight = height,
            seed = 9001 + screen * 1013,
            columns = frameColumns,
            rows = frameRows,
            wrapEdges = true,
            pixelsPerCell = scale,
            cellSize = cellSize,
            timeoutMillis = 15_000L,
            forceRelaxedEdges = true,
          )
        }
        // Every screen, seams included, has to be a tiling of the drawing at cell level. This is the
        // check that has teeth: the pinned band always comes back correct, but the cells just right of
        // it are free, and a pattern the drawing does not contain is exactly what "the pixels between
        // screens are off" is.
        val centers = generated.cellCenters ?: error("$name cellSize $cellSize screen $screen has no centres")
        assertIsATiling(centers, frameColumns, frameRows, patterns, "$name cellSize $cellSize screen $screen")
        previousCenters = centers
        val screenPixels = IntArray(plan.frameWidth * plan.frameHeight)
        generated.bitmap.getPixels(screenPixels, 0, plan.frameWidth, 0, 0, plan.frameWidth, plan.frameHeight)
        stitchScreen(panorama, plan, screenPixels, screen)
      }
      assertEquals("$name cellSize $cellSize screens that gave up their pins", 0, fallbacks)
      // The stitched panorama itself is only readable at cell level when the cells are pixels; with a
      // kernel the panorama carries block colours, which cannot be turned back into tokens. The tiling
      // check above covers that case, and this one covers the pixels actually written to the video.
      if (cellSize == 1) {
        for (row in 1 until plan.panoramaRows - 1) {
          for (col in 1 until plan.panoramaColumns - 1) {
            val seam = col / (frameColumns - plan.overlapColumns)
            val window = List(9) { slot ->
              val r = row - 1 + slot / 3
              val c = col - 1 + slot % 3
              panorama[(r * scale) * plan.panoramaWidth + c * scale]
            }
            assertTrue(
              "$name cellSize $cellSize: panorama window at $col,$row (seam $seam) is not in the drawing",
              window in patterns,
            )
          }
        }
      }
    }
    }
  }

  /**
   * The reported case, at its root: a pinned edge has to keep the previous screen's *patterns*, not only
   * its colours.
   *
   * The canonical 9x9 drawing at the default kernel is one pixel per cell, so the model works on 3x3
   * windows of a 9x9 motif: 81 patterns whose centres are only two colours. Pinning a centre therefore
   * leaves every pattern that shows that colour available, and the solver can reproduce the edge pixel
   * while laying a different diagonal behind it. That is locally a legal tiling and visually a cut, which
   * is why the tiling checks alone never saw it.
   */
  @Test
  fun aPinnedEdgeKeepsThePreviousScreensPatterns() = runBlocking {
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val width = rows.first().length
    val height = rows.size
    val filled = 0xFFF2AA4C.toInt()
    val empty = 0xFF101820.toInt()
    val pixels = IntArray(width * height) { index ->
      if (rows[index / width][index % width] == '*') filled else empty
    }
    val columns = 24
    val gridRows = 24
    val overlap = 4
    for (cellSize in listOf(1, 2, 3)) {
      val first = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 31,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = 2,
        cellSize = cellSize,
      )
      val centers = first.cellCenters ?: error("first screen has no centres")
      val firstPatterns = first.cellPatterns ?: error("first screen has no patterns")
      val fixedCenters = IntArray(columns * gridRows) { Int.MIN_VALUE }
      val fixedPatterns = IntArray(columns * gridRows) { Int.MIN_VALUE }
      for (row in 0 until gridRows) {
        for (cell in 0 until overlap) {
          val source = row * columns + (columns - overlap + cell)
          fixedCenters[row * columns + cell] = centers[source]
          // The band's last column was the previous screen's *right border*: its pattern was chosen with
          // no neighbour to its right, so pinning it exactly would also pin an outward pixel that nothing
          // ever constrained. Only its colour is pinned there; the pattern to its left still fixes the
          // diagonal it has to continue.
          if (cell < overlap - 1) fixedPatterns[row * columns + cell] = firstPatterns[source]
        }
      }
      val second = WfcGenerator.generate(
        pixels = pixels,
        sourceWidth = width,
        sourceHeight = height,
        seed = 31 + 1013,
        columns = columns,
        rows = gridRows,
        wrapEdges = true,
        pixelsPerCell = 2,
        cellSize = cellSize,
        fixedCenters = fixedCenters,
        fixedPatterns = fixedPatterns,
        forceRelaxedEdges = true,
      )
      val secondPatterns = second.cellPatterns ?: error("second screen has no patterns")
      for (index in fixedPatterns.indices) {
        if (fixedPatterns[index] == Int.MIN_VALUE) continue
        assertEquals(
          "cellSize $cellSize pinned pattern $index",
          fixedPatterns[index],
          secondPatterns[index],
        )
      }
    }
  }

  /**
   * The shipped export, frame for frame, at the real geometry, checked on the pixels it actually writes.
   *
   * The frame is the default 76x135 cells at 4 pixels per cell, and both kernels are covered because
   * the default one is `KernelSize.Three`, whose cell is a single drawing pixel: at that size the model
   * works on 3x3 windows of the 9x9 drawing and has to be *made* to continue the arrangement, which is
   * exactly where a seam shows.
   */
  @Test
  fun theShippedExportPanoramaHasNoCutAtItsSeams() = runBlocking {
    val canonicalRows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val width = 9
    val height = 9
    val pixels = IntArray(width * height) { index ->
      if (canonicalRows[index / width][index % width] == '*') 0xFFF2AA4C.toInt() else 0xFF101820.toInt()
    }
    val scale = 4
    val frameColumns = 76
    val frameRows = 135
    for (cellSize in listOf(1, 3)) {
    val plan = videoPlan(frameColumns, frameRows, scale, cellSize, screens = 3, fps = 25)
    assertEquals("three screens", 3, plan.screenCount)
    // How a solved cell renders, so the stitched panorama can be read back as the cells it was made of.
    // With a 3x3 block on a 4x4 square every block pixel is sampled at least once, so the map cannot
    // collide; with no grouping the cell is one colour and the square is uniform.
    val grouping = if (cellSize > 1) groupCells(pixels, width, height, cellSize) else null
    val tokenOfSquare = HashMap<List<Int>, Int>()
    if (grouping != null) {
      for (token in grouping.blocks.indices) {
        tokenOfSquare[renderedSquare(grouping.blocks[token], cellSize, scale)] = token
      }
    }
    val patterns = patternsOf(pixels, width, height, cellSize)
    // The shipped path for a panorama this size: one solve for the whole thing, so there is no join at
    // all. This is what replaced the stitched frames, which is where the cut used to be.
    val cellsAcross = plan.panoramaColumns * plan.panoramaRows
    assertTrue(
      "the shipped panorama ($cellsAcross cells) should fit a single solve, budget $MAX_PANORAMA_CELLS",
      cellsAcross <= MAX_PANORAMA_CELLS,
    )
    val solved = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 9001,
      columns = plan.panoramaColumns,
      rows = plan.panoramaRows,
      wrapEdges = true,
      pixelsPerCell = scale,
      cellSize = cellSize,
      timeoutMillis = PANORAMA_TIMEOUT_MILLIS,
      forceRelaxedEdges = true,
    )
    val panorama = IntArray(plan.panoramaWidth * plan.frameHeight)
    solved.bitmap.getPixels(panorama, 0, plan.panoramaWidth, 0, 0, plan.panoramaWidth, plan.frameHeight)

    val cells = IntArray(plan.panoramaColumns * plan.panoramaRows)
    for (row in 0 until plan.panoramaRows) {
      for (col in 0 until plan.panoramaColumns) {
        val where = "cell $col,$row"
        cells[row * plan.panoramaColumns + col] = if (grouping == null) {
          // No grouping: the cell is one colour, so its square is uniform and the panorama value is it.
          val colour = panorama[(row * scale) * plan.panoramaWidth + col * scale]
          for (slot in 1 until scale * scale) {
            val sibling = panorama[(row * scale + slot / scale) * plan.panoramaWidth + col * scale + slot % scale]
            assertEquals("$where is not a solid cell", colour, sibling)
          }
          colour
        } else {
          val square = List(scale * scale) { slot ->
            panorama[(row * scale + slot / scale) * plan.panoramaWidth + col * scale + slot % scale]
          }
          tokenOfSquare[square] ?: error("$where renders a square the drawing cannot produce")
        }
      }
    }
    assertIsATiling(cells, plan.panoramaColumns, plan.panoramaRows, patterns, "cellSize $cellSize panorama")
    }
  }

  /**
   * The default export, at the settings a debug build opens with, has to be one solve that finishes.
   *
   * This is the configuration the seam was reported in: a 4x preview makes the export frame 76x135 cells,
   * and 15 seconds at 0.5 frame widths per second makes the panorama 11 screens of it. Every screen after
   * the first used to be stitched on at `frameColumns - overlap` -- five sixths of the frame, which is
   * where the cut appeared -- so the whole point of the single-solve path is that this size fits it.
   */
  @Test
  fun theDefaultExportPanoramaSolvesAsOneGrid() = runBlocking {
    val rows = listOf(
      "_________",
      "__**_**__",
      "_*__*__*_",
      "_*_____*_",
      "__*___*__",
      "_*_____*_",
      "_*__*__*_",
      "__**_**__",
      "_________",
    )
    val width = rows.first().length
    val height = rows.size
    val pixels = IntArray(width * height) { index ->
      if (rows[index / width][index % width] == '*') 0xFFF2AA4C.toInt() else 0xFF101820.toInt()
    }
    val frame = videoFramePlan(OutputAspect.Portrait9x16, PreviewScale.Two, KernelSize.Three)
    val plan = videoPlan(
      frameColumns = frame.columns,
      frameRows = frame.rows,
      pixelsPerCell = frame.pixelsPerCell,
      cellSize = frame.cellSize,
      screens = 3,
      fps = 25,
    )
    val cellsAcross = plan.panoramaColumns * plan.panoramaRows
    assertTrue(
      "the default panorama is ${plan.panoramaColumns}x${plan.panoramaRows} = $cellsAcross cells, " +
        "over the $MAX_PANORAMA_CELLS single-solve budget",
      cellsAcross <= MAX_PANORAMA_CELLS,
    )
    val rowsCount = plan.panoramaRows
    val panoramaColumns = plan.panoramaColumns
    val patterns = patternsOf(pixels, width, height, plan.cellSize)
    // Mirrors the export: a plain solve two cells wider than the picture, then one that pins an interior
    // pair of its columns at both ends, which is what makes the wrap a solved adjacency.
    val solveColumns = panoramaColumns + LOOP_SEAM_CELLS
    val open = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 1234,
      columns = solveColumns,
      rows = rowsCount,
      wrapEdges = true,
      pixelsPerCell = plan.pixelsPerCell,
      cellSize = plan.cellSize,
      timeoutMillis = PANORAMA_TIMEOUT_MILLIS,
      forceRelaxedEdges = true,
    )
    val openCentres = open.cellCenters ?: error("the panorama has no centres")
    val anchor = solveColumns / 2
    val pinned = IntArray(solveColumns * rowsCount) { Int.MIN_VALUE }
    for (row in 0 until rowsCount) {
      for (i in 0 until LOOP_SEAM_CELLS) {
        val value = openCentres[row * solveColumns + anchor + i]
        pinned[row * solveColumns + i] = value
        pinned[row * solveColumns + solveColumns - LOOP_SEAM_CELLS + i] = value
      }
    }
    val looped = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 1234,
      columns = solveColumns,
      rows = rowsCount,
      wrapEdges = true,
      pixelsPerCell = plan.pixelsPerCell,
      cellSize = plan.cellSize,
      timeoutMillis = PANORAMA_TIMEOUT_MILLIS,
      fixedCenters = pinned,
      forceRelaxedEdges = true,
    )
    val loopCentres = looped.cellCenters ?: error("the looped panorama has no centres")
    println(
      "default loop ${panoramaColumns}x$rowsCount closed " +
        "(${plan.frameCount} frames at ${plan.frameDelayCentiseconds} cs)",
    )
    // The ends are the same cells, so the wrap is an adjacency the solver itself made.
    for (row in 0 until rowsCount) {
      for (i in 0 until LOOP_SEAM_CELLS) {
        assertEquals(
          "loop point at row $row, cell $i",
          loopCentres[row * solveColumns + i],
          loopCentres[row * solveColumns + solveColumns - LOOP_SEAM_CELLS + i],
        )
      }
    }
    // The picture is the columns that were kept, and it is a tiling all the way across -- including the
    // windows that straddle the loop point.
    val cells = IntArray(panoramaColumns * rowsCount) { index ->
      loopCentres[(index / panoramaColumns) * solveColumns + index % panoramaColumns]
    }
    assertIsATiling(cells, panoramaColumns, rowsCount, patterns, "default loop")
    for (row in 1 until rowsCount - 1) {
      for (col in listOf(0, panoramaColumns - 1)) {
        val window = List(9) { slot ->
          val r = row - 1 + slot / 3
          val c = (col - 1 + slot % 3 + panoramaColumns) % panoramaColumns
          cells[r * panoramaColumns + c]
        }
        assertTrue("window straddling the loop point at $col,$row", window in patterns)
      }
    }
  }

  /** A cell's block expanded over its `scale` square, exactly as `renderSolvedCells` writes it. */
  private fun renderedSquare(block: IntArray, cell: Int, scale: Int): List<Int> = List(scale * scale) { slot ->
    val dy = slot / scale
    val dx = slot % scale
    block[(dy * cell / scale) * cell + dx * cell / scale]
  }

  /**
   * Every 3x3 window the drawing contains, read the way the solver reads the drawing.
   *
   * `generate` groups only when the kernel is above one cell, and a grouped cell's centre is a token
   * naming its block rather than a colour, so both the grouping and the absence of it have to be
   * reproduced here or the comparison is between two different alphabets.
   */
  private fun patternsOf(pixels: IntArray, width: Int, height: Int, cellSize: Int): HashSet<List<Int>> {
    val grouping = if (cellSize > 1) groupCells(pixels, width, height, cellSize) else null
    val columns = grouping?.width ?: width
    val rows = grouping?.height ?: height
    val grid = grouping?.tokens ?: pixels
    val patterns = HashSet<List<Int>>()
    for (y in 0 until rows) {
      for (x in 0 until columns) {
        patterns += List(9) { slot ->
          grid[((y + slot / 3) % rows) * columns + (x + slot % 3) % columns]
        }
      }
    }
    return patterns
  }

  /**
   * Asserts that a solved grid is a tiling of the drawing: every interior 3x3 window of cell centres is
   * one of the drawing's windows. Border cells are skipped because they have fewer neighbours and the
   * solver's model accepts partial patterns there.
   */
  private fun assertIsATiling(
    centres: IntArray,
    columns: Int,
    rows: Int,
    patterns: Set<List<Int>>,
    label: String,
  ) {
    for (row in 1 until rows - 1) {
      for (col in 1 until columns - 1) {
        val window = List(9) { slot -> centres[(row - 1 + slot / 3) * columns + (col - 1 + slot % 3)] }
        assertTrue("$label: window at $col,$row is not in the drawing", window in patterns)
      }
    }
  }

  private suspend fun pinOneScreen(cellSize: Int) {
    val width = 12
    val height = 12
    val red = 0xFFFF0000.toInt()
    val blue = 0xFF0000FF.toInt()
    val pixels = IntArray(width * height) { index ->
      if ((index / width) % 2 == 0) red else blue
    }
    val columns = 24
    val rows = 24
    val first = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 11,
      columns = columns,
      rows = rows,
      wrapEdges = true,
      pixelsPerCell = 1,
      cellSize = cellSize,
    )
    val centers = first.cellCenters ?: error("first screen has no centres")
    val overlap = 4
    val fixed = IntArray(columns * rows) { Int.MIN_VALUE }
    for (row in 0 until rows) {
      for (cell in 0 until overlap) {
        fixed[row * columns + cell] = centers[row * columns + (columns - overlap + cell)]
      }
    }

    val second = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 12,
      columns = columns,
      rows = rows,
      wrapEdges = true,
      pixelsPerCell = 1,
      cellSize = cellSize,
      fixedCenters = fixed,
      forceRelaxedEdges = true,
    )
    val secondCenters = second.cellCenters ?: error("second screen has no centres")
    for (index in fixed.indices) {
      if (fixed[index] == Int.MIN_VALUE) continue
      assertEquals("cellSize $cellSize pinned centre $index", fixed[index], secondCenters[index])
    }

    // The rendered edge must match too: that is what the panorama actually shows at the seam.
    val firstPixels = IntArray(columns * rows)
    first.bitmap.getPixels(firstPixels, 0, columns, 0, 0, columns, rows)
    val secondPixels = IntArray(columns * rows)
    second.bitmap.getPixels(secondPixels, 0, columns, 0, 0, columns, rows)
    for (row in 0 until rows) {
      for (cell in 0 until overlap) {
        assertEquals(
          "cellSize $cellSize rendered edge $row,$cell",
          firstPixels[row * columns + (columns - overlap + cell)],
          secondPixels[row * columns + cell],
        )
      }
    }
  }
}
