package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The trace has to describe the search that actually ran, and its final state has to be the solved
 * grid. This is the device-side check for the pixels the solve animation shows: a trace that misses
 * events, starts from the wrong domains, or leaves cells undecided would animate something that never
 * happened.
 */
@RunWith(AndroidJUnit4::class)
class SolveTraceTest {

  private val width = 16
  private val height = 11

  private fun ringsPixels(): IntArray {
    val ink = 0xFF181425.toInt()
    val rust = 0xFFBE4A2F.toInt()
    val gold = 0xFFFEAE34.toInt()
    val slate = 0xFF5A6988.toInt()
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val d = (x - 7) * (x - 7) + (y - 5) * (y - 5)
        pixels[y * width + x] = when {
          d < 6 -> gold
          d < 16 -> rust
          d < 30 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  @Test
  fun traceCapturesEveryDecisionAndEndsSolved() {
    val pixels = ringsPixels()
    val cells = BitmapWaveFunctionCell.createCells(IntArray2D(width, height, pixels))
    val packed = WfcNative.packTiles(cells)
    val columns = 48
    val rows = 64
    val model = WfcNative.modelCreate(packed, cells.size, columns, rows, relaxedEdges = true)
    assertTrue("the model must build", model != 0L)
    val solver = NativeTileSolver(model, columns, rows, seed = 1, traceEnabled = true)
    try {
      val assignment = solver.solve(
        deadlineNanos = System.nanoTime() + 30_000_000_000L,
        maxDecisions = 1_000_000,
      )
      assertNotNull("the trace solve must find a tiling", assignment)
      assertFalse("the trace buffer must not overflow at this size", solver.traceOverflow)
      val initial = solver.traceInitial
      val events = solver.traceEvents
      assertNotNull("no initial domain sizes were captured", initial)
      assertNotNull("no domain events were captured", events)
      assertEquals("one initial size per cell", columns * rows, initial!!.size)
      assertTrue("the trace has no transitions at all", events!!.isNotEmpty())

      val state = initial.copyOf()
      for (packedEvent in events) {
        val cell = (packedEvent and ((1L shl 20) - 1)).toInt()
        assertTrue("event names cell $cell outside the grid", cell in state.indices)
        state[cell] = ((packedEvent ushr 20) and ((1L shl 10) - 1)).toInt()
      }
      assertTrue(
        "the trace must end with every cell decided, but ${state.count { it != 1 }} are not",
        state.all { it == 1 },
      )
    } finally {
      solver.close()
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun waveFrameCoversEveryPixelAndEndsAsThePicture() = runBlocking {
    val output = WfcGenerator.generate(
      pixels = DebugTestPattern.pixels,
      sourceWidth = DebugTestPattern.columns,
      sourceHeight = DebugTestPattern.rows,
      seed = 1,
      columns = 135,
      rows = 240,
      wrapEdges = true,
      pixelsPerCell = 4,
    )
    val trace = WfcGenerator.traceSolve(output)
    assertNotNull("the displayed solve could not be replayed", trace)
    val width = output.bitmap.width
    val height = output.bitmap.height
    val solvedPixels = IntArray(width * height)
    output.bitmap.getPixels(solvedPixels, 0, width, 0, 0, width, height)
    val frames = SolveAnimationFrames(
      trace = trace!!,
      solvedPixels = solvedPixels,
      width = width,
      height = height,
      background = 0xFF101820.toInt(),
      style = SolveAnimation.Wave,
      requestedSteps = 24,
    )
    val first = frames.frameAt(0)
    assertEquals("the frame has uncovered pixels", 0, first.count { (it ushr 24) == 0 })
    assertTrue(
      "the first frame must show candidate-colour averages, not the finished picture",
      !first.contentEquals(solvedPixels),
    )
    val last = frames.frameAt(frames.steps - 1)
    assertTrue("the final wave frame is not the solved picture", last.contentEquals(solvedPixels))

    // A one-cell border of the solve grid, as pixels. If these differ from the interior at frame 0,
    // the edge domains are still being rendered as more decided than the rest of the wave.
    val edge = LinkedHashMap<Int, Int>()
    val inside = LinkedHashMap<Int, Int>()
    for (y in 0 until height step 4) {
      for (x in 0 until width step 4) {
        val colour = first[y * width + x]
        val target = if (x == 0 || y == 0 || x >= width - 4 || y >= height - 4) edge else inside
        target[colour] = (target[colour] ?: 0) + 1
      }
    }
    println("wave frame 0 edge=$edge inside=$inside")
  }

  @Test
  fun localResampleChangesOnlyThePickedCells() = runBlocking {
    val base = WfcGenerator.generate(
      pixels = ringsPixels(),
      sourceWidth = width,
      sourceHeight = height,
      seed = 1,
      columns = 48,
      rows = 64,
      wrapEdges = true,
      pixelsPerCell = 1,
    )
    val before = base.cellCenters!!.copyOf()
    val diagnosticModel = WfcNative.modelCreate(
      base.packedTiles!!,
      base.packedTiles!!.size / WfcNative.PACKED_TILE_INTS,
      base.columns,
      base.rows,
      relaxedEdges = base.modelRelaxedEdges,
    )
    if (diagnosticModel != 0L) {
      val assignment = base.assignment!!
      val alternatives = assignment.indices.map {
        WfcNative.modelCellAlternatives(diagnosticModel, assignment, it)
      }
      println(
        "local resample diagnostic: ${alternatives.count { it > 0 }} cells with alternatives, " +
          "max ${alternatives.maxOrNull() ?: 0}",
      )
      WfcNative.modelDestroy(diagnosticModel)
    }
    val evolver = TileEvolver(output = base, cellsPerFrame = 20)
    try {
      val after = evolver.nextFrame(seed = 11)
      println("local resample: ${evolver.lastChanged} changed of 20 picked cells")
      assertTrue("twenty resamples changed too many cells", evolver.lastChanged <= 20)
      val visibleChanged = before.indices.count { before[it] != after[it] }
      println("local resample: $visibleChanged visible cells changed")
      assertTrue(
        "the visible frame changed more cells than were resampled",
        visibleChanged <= 20,
      )
      // The undo pass has to arrive at the starting picture exactly, or the evolve clip's loop is a
      // jump of however many cells the bookkeeping missed.
      assertArrayEquals(
        "undoing every change must return the starting picture",
        before,
        evolver.pictureAfterUndoing(evolver.changeCount),
      )
    } finally {
      evolver.close()
    }
  }

  @Test
  fun generatorReplayMatchesTheDisplayedSolve() = runBlocking {
    val pixels = ringsPixels()
    val output = WfcGenerator.generate(
      pixels = pixels,
      sourceWidth = width,
      sourceHeight = height,
      seed = 3,
      columns = 48,
      rows = 64,
      wrapEdges = true,
      pixelsPerCell = 1,
    )
    val trace = WfcGenerator.traceSolve(output)
    assertNotNull("the displayed solve could not be replayed", trace)
    assertEquals("the trace has the wrong number of cells", 48 * 64, trace!!.initialSizes.size)
    val state = trace.initialSizes.copyOf()
    for (packedEvent in trace.events) {
      val cell = (packedEvent and ((1L shl 20) - 1)).toInt()
      state[cell] = ((packedEvent ushr 20) and ((1L shl 10) - 1)).toInt()
    }
    assertTrue("the replayed trace did not end solved", state.all { it == 1 })
  }
}
