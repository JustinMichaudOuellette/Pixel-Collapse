package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictBitmapSolver
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Checks the C solver against the Kotlin one on device, where the native library is available.
 *
 * The two are supposed to implement the same rule, so the test asserts the properties rather than an
 * identical layout (the searches are stochastic): both must find a tiling for the same tile set, the
 * tiling must satisfy every neighbour relation, and the bitmap's border must be made of patterns that
 * were sampled on the drawing's border -- which is what "edge pixels stay on the edge" means.
 */
@RunWith(AndroidJUnit4::class)
class NativeSolverTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()

  private fun flatDrawing(): IntArray = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }

  /** A drawn border with a motif inside: the case the edge rule exists for. */
  private fun borderedDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val onEdge = x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1
        pixels[y * SOURCE_WIDTH + x] = when {
          onEdge -> gold
          (x - 7) * (x - 7) + (y - 5) * (y - 5) < 9 -> rust
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun ringsDrawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT)
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        val d = (x - 7) * (x - 7) + (y - 5) * (y - 5)
        pixels[y * SOURCE_WIDTH + x] = when {
          d < 6 -> gold
          d < 16 -> rust
          d < 30 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun tiles(pixels: IntArray): List<BitmapWaveFunctionCell> =
    BitmapWaveFunctionCell.createCells(IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, pixels.copyOf()))

  /**
   * The drawing that showed the mismatch on device: rust along the top row and bottom row for the
   * first ten columns, rust down the whole left column, ink everywhere else (including the right
   * column). Every tile sampled at x = 0 has a rust centre, so the bitmap's left column has to be
   * rust, and every tile sampled at x = 15 is ink, so its right column has to be ink.
   */
  @Test
  fun asymmetricBorderKeepsItsColoursOnTheEdges() {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (x in 0..9) {
      pixels[x] = rust
      pixels[(SOURCE_HEIGHT - 1) * SOURCE_WIDTH + x] = rust
    }
    for (y in 0 until SOURCE_HEIGHT) pixels[y * SOURCE_WIDTH] = rust
    val cells = tiles(pixels)

    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    assertTrue("native model could not be created", model != 0L)
    val solver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
    val assignment = try {
      solver.solve(System.nanoTime() + 30_000_000_000L, 1_000_000)
    } finally {
      solver.close()
      WfcNative.modelDestroy(model)
    }
    assertNotNull("native solver found no tiling for the asymmetric border", assignment)
    assertEquals(0, violations(assignment!!, cells))

    val leftColumn = (0 until OUTPUT_ROWS).map { cells[assignment[it * OUTPUT_COLUMNS]].color }.toSet()
    val rightColumn = (0 until OUTPUT_ROWS)
      .map { cells[assignment[it * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]].color }
      .toSet()
    assertEquals("the drawing's left column is rust, so the bitmap's left column must be too", setOf(rust), leftColumn)
    assertEquals("the drawing's right column is ink, so the bitmap's right column must be too", setOf(ink), rightColumn)
    assertBorderPatternsAreBlankOutwards("asymmetric", assignment, cells)
  }

  @Test
  fun nativeLibraryIsAvailable() {
    assertTrue("libwfc.so did not load on this device", WfcNative.available)
  }

  /**
   * The same tile set must solve in both implementations, and the native solution must satisfy the
   * rule. A failure here means the C port and the Kotlin original disagree.
   */
  @Test
  fun nativeSolverAgreesWithTheKotlinSolver() {
    for ((name, drawing) in listOf(
      "flat" to flatDrawing(),
      "bordered" to borderedDrawing(),
      "rings" to ringsDrawing(),
    )) {
      val cells = tiles(drawing)

      // Kotlin: the reference.
      val kotlinStartedAt = System.nanoTime()
      val kotlin = StrictBitmapSolver(
        cells,
        OUTPUT_COLUMNS,
        OUTPUT_ROWS,
        Direction2d8.entries,
        Random(1),
      ).solve(System.nanoTime() + 30_000_000_000L, 1_000_000)
      val kotlinMillis = (System.nanoTime() - kotlinStartedAt) / 1_000_000

      // Native: the one the app runs.
      val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
      assertTrue("$name: the native model could not be created", model != 0L)
      val nativeSolver = NativeTileSolver(model, OUTPUT_COLUMNS, OUTPUT_ROWS, seed = 1)
      val nativeStartedAt = System.nanoTime()
      val native = try {
        nativeSolver.solve(System.nanoTime() + 30_000_000_000L, 1_000_000)
      } finally {
        nativeSolver.close()
        WfcNative.modelDestroy(model)
      }
      val nativeMillis = (System.nanoTime() - nativeStartedAt) / 1_000_000

      println(
        "$name: ${cells.size} tiles, kotlin=${if (kotlin == null) "none" else "${kotlinMillis}ms"} " +
          "native=${if (native == null) "none" else "${nativeMillis}ms"}",
      )
      assertNotNull("$name: the Kotlin solver found no tiling, so the tile set is unusable", kotlin)
      assertNotNull("$name: the native solver found no tiling but the Kotlin one did", native)
      assertEquals("$name: native solution has neighbour mismatches", 0, violations(native!!, cells))
      assertEquals("$name: kotlin solution has neighbour mismatches", 0, violations(kotlin!!, cells))

      // The edge rule, on both.
      assertBorderPatternsAreBlankOutwards("$name native", native, cells)
      assertBorderPatternsAreBlankOutwards("$name kotlin", kotlin, cells)
    }
  }

  /** The portfolio must drive the native solver through the factory, not the Kotlin one. */
  @Test
  fun portfolioRunsTheNativeSolver() {
    val cells = tiles(ringsDrawing())
    val model = WfcNative.modelCreate(WfcNative.packTiles(cells), cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
    assertTrue(model != 0L)
    try {
      val result = StrictSolverPortfolio(
        cells,
        OUTPUT_COLUMNS,
        OUTPUT_ROWS,
        Direction2d8.entries,
        solverFactory = { seed, columns, rows -> NativeTileSolver(model, columns, rows, seed) },
      ).solve(
        seed = 1,
        workers = 4,
        deadlineNanos = System.nanoTime() + 30_000_000_000L,
        maxDecisions = 1_000_000,
      )
      val assignment = result.assignment
      assertTrue("the portfolio found no tiling through the native solver", assignment != null)
      assertEquals(0, violations(assignment!!, cells))
      println("native portfolio: ${result.millis}ms, seeds=${result.entries.map { it.decisions }}")
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  private fun assertBorderPatternsAreBlankOutwards(
    label: String,
    assignment: IntArray,
    cells: List<BitmapWaveFunctionCell>,
  ) {
    for (x in 0 until OUTPUT_COLUMNS) {
      assertEquals(
        "$label: top row $x is not a drawing-edge pattern",
        null,
        cells[assignment[x]].kernelColor(Direction2d8.UP),
      )
      assertEquals(
        "$label: bottom row $x is not a drawing-edge pattern",
        null,
        cells[assignment[(OUTPUT_ROWS - 1) * OUTPUT_COLUMNS + x]].kernelColor(Direction2d8.DOWN),
      )
    }
    for (y in 0 until OUTPUT_ROWS) {
      assertEquals(
        "$label: left column $y is not a drawing-edge pattern",
        null,
        cells[assignment[y * OUTPUT_COLUMNS]].kernelColor(Direction2d8.LEFT),
      )
      assertEquals(
        "$label: right column $y is not a drawing-edge pattern",
        null,
        cells[assignment[y * OUTPUT_COLUMNS + OUTPUT_COLUMNS - 1]].kernelColor(Direction2d8.RIGHT),
      )
    }
  }

  private fun violations(assignment: IntArray, cells: List<BitmapWaveFunctionCell>): Int {
    var bad = 0
    for (y in 0 until OUTPUT_ROWS) {
      for (x in 0 until OUTPUT_COLUMNS) {
        val here = cells[assignment[y * OUTPUT_COLUMNS + x]]
        for (direction in Direction2d8.entries) {
          val nx = x + direction.dx
          val ny = y + direction.dy
          if (nx < 0 || ny < 0 || nx >= OUTPUT_COLUMNS || ny >= OUTPUT_ROWS) continue
          val there = cells[assignment[ny * OUTPUT_COLUMNS + nx]]
          if (here.kernelColor(direction) != there.color ||
            there.kernelColor(direction.opposite()) != here.color
          ) {
            bad++
          }
        }
      }
    }
    return bad
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
    const val OUTPUT_COLUMNS = 76
    const val OUTPUT_ROWS = 135
  }
}
