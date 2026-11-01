package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.StrictSolverPortfolio
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which portfolio setting is fastest for a drawing this app actually gets.
 *
 * The app runs [StrictSolverPortfolio] with four workers and a fixed hedge: the first worker runs alone for
 * 60 ms and the rest are started if it is not done by then. Concurrent solvers saturate memory bandwidth, so
 * for a drawing whose solve is *ordinarily* slower than the hedge, all four run together for most of the
 * time -- which may well be slower than one worker doing the whole job.
 *
 * This measures the matrix: workers 1-4 against several hedges, same tile set, several seeds, reporting the
 * median wall clock. Numbers only; the assertions are in [NativeSolverTest].
 */
@RunWith(AndroidJUnit4::class)
class PortfolioBenchmarkTest {

  private val ink = 0xFF181425.toInt()
  private val rust = 0xFFBE4A2F.toInt()
  private val gold = 0xFFFEAE34.toInt()
  private val slate = 0xFF5A6988.toInt()

  /** The drawing the editor opens with, plus a motif: the case the preview shows on most edits. */
  private fun drawing(): IntArray {
    val pixels = IntArray(SOURCE_WIDTH * SOURCE_HEIGHT) { ink }
    for (y in 0 until SOURCE_HEIGHT) {
      for (x in 0 until SOURCE_WIDTH) {
        pixels[y * SOURCE_WIDTH + x] = when {
          x == 0 || y == 0 || x == SOURCE_WIDTH - 1 || y == SOURCE_HEIGHT - 1 -> gold
          (x - 7) * (x - 7) + (y - 5) * (y - 5) < 9 -> rust
          x % 4 == 0 -> slate
          else -> ink
        }
      }
    }
    return pixels
  }

  private fun timePortfolio(
    packed: IntArray,
    tileCount: Int,
    workers: Int,
    hedgeMs: Long,
    seeds: IntArray,
  ): LongArray {
    val times = LongArray(seeds.size)
    for ((index, seed) in seeds.withIndex()) {
      val model = WfcNative.modelCreate(packed, tileCount, COLUMNS, ROWS, relaxedEdges = false)
      check(model != 0L) { "model creation failed" }
      try {
        val portfolio = StrictSolverPortfolio(
          columns = COLUMNS,
          rows = ROWS,
          solverFactory = { workerSeed, columns, rows ->
            NativeTileSolver(model, columns, rows, workerSeed)
          },
        )
        val startedAt = System.nanoTime()
        val result = portfolio.solve(
          seed = seed,
          workers = workers,
          deadlineNanos = System.nanoTime() + 8_000_000_000L,
          maxDecisions = 1_000_000,
          hedgeMs = hedgeMs,
        )
        times[index] = (System.nanoTime() - startedAt) / 1_000_000
        check(result.assignment != null) { "seed $seed did not solve" }
      } finally {
        WfcNative.modelDestroy(model)
      }
    }
    return times
  }

  @Test
  fun workersAndHedgeAgainstTheWallClock() {
    val cells = BitmapWaveFunctionCell.createCells(
      IntArray2D(SOURCE_WIDTH, SOURCE_HEIGHT, drawing().copyOf()),
    )
    val packed = WfcNative.packTiles(cells)
    val seeds = IntArray(15) { it + 1 }

    // Warm up: the first model build and the first solve pay for page faults and JIT.
    timePortfolio(packed, cells.size, workers = 1, hedgeMs = 60, seeds = intArrayOf(9))

    println("PORTFOLIO tiles=${cells.size} grid=${COLUMNS}x$ROWS device=${android.os.Build.MODEL}")
    val configs = listOf(
      Triple(1, 60L, "1 worker"),
      Triple(2, 60L, "2 workers, hedge 60"),
      Triple(2, 400L, "2 workers, hedge 400"),
      Triple(4, 60L, "4 workers, hedge 60 (shipping)"),
      Triple(4, 400L, "4 workers, hedge 400 (adaptive, slow solve)"),
      Triple(4, 8_000L, "4 workers, no hedge"),
    )
    for ((workers, hedge, label) in configs) {
      val times = timePortfolio(packed, cells.size, workers, hedge, seeds).sorted()
      val median = times[times.size / 2]
      val p90 = times[(times.size * 9) / 10]
      println(
        "PORTFOLIO $label: median=${median}ms p90=${p90}ms mean=${times.sum() / times.size}ms  " +
          "min=${times.first()}ms max=${times.last()}ms",
      )
    }
  }

  private companion object {
    const val SOURCE_WIDTH = 16
    const val SOURCE_HEIGHT = 11
    const val COLUMNS = 152
    const val ROWS = 270
  }
}
