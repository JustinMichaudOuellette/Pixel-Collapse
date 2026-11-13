package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcNative
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tile counts above the old 1,024 limit.
 *
 * 1,024 tiles is 16 domain words, which used to be the largest word index a four-bit trail key could
 * address. These tests build sets on both sides of that boundary: the model and solver must accept the
 * new 4,096 ceiling, and the model must still reject one tile more.
 */
@RunWith(AndroidJUnit4::class)
class LargeTileSetTest {

  /**
   * One tile per colour, every kernel slot the same colour.
   *
   * Such a tile is compatible only with itself, so a solved grid must be uniform. That keeps the test
   * about the *count* -- model creation, bitset width and trail packing -- rather than about finding a
   * tiling in a rich random set.
   */
  private fun uniformTiles(count: Int): List<BitmapWaveFunctionCell> = List(count) { index ->
    val colour = 0xFF000000.toInt() or index
    BitmapWaveFunctionCell(Array<Int?>(9) { colour }, 1f)
  }

  @Test
  fun aModelSolvesAtTheRaisedTileLimit() {
    val count = 4096
    val packed = WfcNative.packTiles(uniformTiles(count))
    val model = WfcNative.modelCreate(packed, count, 8, 8, relaxedEdges = true)
    assertTrue("the native model rejected the $count-tile set", model != 0L)
    try {
      assertEquals(count, WfcNative.modelTileCount(model))
      val solver = WfcNative.solverCreate()
      assertTrue("could not create a solver", solver != 0L)
      try {
        val out = IntArray(8 * 8)
        val solved = WfcNative.solverSolve(
          solver,
          model,
          seed = 1,
          deadlineNanos = System.nanoTime() + 10_000_000_000L,
          maxDecisions = 1_000_000,
          out = out,
        )
        assertEquals("the $count-tile model did not solve", 1, solved)
        assertTrue(
          "a tile set where every tile supports only itself must solve to one tile",
          out.all { it == out[0] },
        )
      } finally {
        WfcNative.solverDestroy(solver)
      }
    } finally {
      WfcNative.modelDestroy(model)
    }
  }

  @Test
  fun oneTileOverTheLimitIsRejected() {
    val count = 4097
    val packed = WfcNative.packTiles(uniformTiles(count))
    assertEquals(0L, WfcNative.modelCreate(packed, count, 4, 4, relaxedEdges = true))
  }
}
