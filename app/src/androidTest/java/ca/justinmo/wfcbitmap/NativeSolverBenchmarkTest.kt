package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the native solver's time goes, per drawing, over repeated solves.
 *
 * Not an assertion-heavy test: it exists to answer "what should be optimised next?" with numbers
 * instead of guesses, and to show the cost of the work *around* the search (model and solver creation,
 * which happen on every edit in the app).
 */
@RunWith(AndroidJUnit4::class)
class NativeSolverBenchmarkTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()

  private fun flat(): IntArray = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }

  private fun bordered(): IntArray {
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

  private fun rings(): IntArray {
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

  private class Totals {
    var wall = 0L
    var create = 0L
    var setup = 0L
    var initial = 0L
    var search = 0L
    var selection = 0L
    var ordering = 0L
    var decisions = 0L
    var revises = 0L
  }

  private fun run(label: String, pixels: IntArray, repeats: Int) {
    val cells = tiles(pixels)
    val packed = WfcNative.packTiles(cells)
    // Warm up once so the first-run JIT/page-fault cost does not land on the averages.
    solveOnce(packed, cells.size)?.let { }

    val totals = Totals()
    var solved = 0
    /*
     * One solver for every repeat, exactly like the pool: the app keeps its worker solvers alive across
     * collapses, so the model is the only thing built per edit.
     */
    val solver = WfcNative.solverCreate()
    check(solver != 0L)
    repeat(repeats) {
      val createStartedAt = System.nanoTime()
      val model = WfcNative.modelCreate(packed, cells.size, OUTPUT_COLUMNS, OUTPUT_ROWS)
      check(model != 0L)
      totals.create += System.nanoTime() - createStartedAt

      val startedAt = System.nanoTime()
      val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
      if (WfcNative.solverSolve(solver, model, 1, Long.MAX_VALUE, 1_000_000, out) == 1) solved++
      totals.wall += System.nanoTime() - startedAt

      totals.setup += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SETUP)
      totals.initial += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.INITIAL_PROPAGATION)
      totals.search += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SEARCH)
      totals.selection += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.SELECTION)
      totals.ordering += WfcNative.solverPhaseNanos(solver, WfcNative.Phase.ORDERING)
      totals.decisions += WfcNative.solverDecisions(solver)
      totals.revises += WfcNative.solverRevises(solver)

      WfcNative.modelDestroy(model)
    }
    WfcNative.solverDestroy(solver)

    fun ms(value: Long) = "%.2f".format(value / 1_000_000.0 / repeats)
    println(
      "PROFILE $label tiles=${cells.size} solved=$solved/$repeats  " +
        "wall=${ms(totals.wall)}ms  create=${ms(totals.create)}ms  " +
        "setup=${ms(totals.setup)}  initial=${ms(totals.initial)}  search=${ms(totals.search)}  " +
        "of which select=${ms(totals.selection)} order=${ms(totals.ordering)}  " +
        "decisions/run=${totals.decisions / repeats} revises/run=${totals.revises / repeats}",
    )
  }

  private fun solveOnce(packed: IntArray, tileCount: Int): IntArray? {
    val model = WfcNative.modelCreate(packed, tileCount, OUTPUT_COLUMNS, OUTPUT_ROWS)
    if (model == 0L) return null
    val solver = WfcNative.solverCreate()
    if (solver == 0L) {
      WfcNative.modelDestroy(model)
      return null
    }
    val out = IntArray(OUTPUT_COLUMNS * OUTPUT_ROWS)
    val result = if (WfcNative.solverSolve(solver, model, 1, Long.MAX_VALUE, 1_000_000, out) == 1) out else null
    WfcNative.solverDestroy(solver)
    WfcNative.modelDestroy(model)
    return result
  }

  @Test
  fun profilePhases() {
    println("PROFILE device=${android.os.Build.MODEL} abi=${android.os.Build.SUPPORTED_ABIS.first()}")
    run("flat", flat(), repeats = 20)
    run("bordered", bordered(), repeats = 10)
    run("rings", rings(), repeats = 10)
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
    const val OUTPUT_COLUMNS = 76
    const val OUTPUT_ROWS = 135
  }
}
