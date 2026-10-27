package ca.justinmo.wfcbitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell

/**
 * Keeps the window tally of the drawing between edits, so a one-pixel edit only re-tallies the windows
 * that pixel actually touches.
 *
 * Every pixel belongs to nine windows, so a full resample costs `width * height * 9` colour lookups on
 * every keystroke, while a delta costs at most `81` for a single pixel. More importantly the tally means
 * the tile set can be compared with the previous one cheaply, which is what lets [WfcGenerator] skip the
 * collapse entirely when the edit did not change any pattern.
 *
 * Not thread-safe: [WfcGenerator] calls it inside its own lock.
 */
internal class WindowTally {

  private var previous: IntArray? = null
  private var previousWidth = 0
  private var previousHeight = 0
  private val counts = HashMap<List<Int?>, Int>()

  /**
   * Returns the tile set for [pixels], updated incrementally when only part of the drawing changed.
   *
   * Falls back to a full resample when there is nothing to compare against, when the dimensions moved,
   * or when the edit touched more pixels than a resample would cost.
   */
  fun sample(pixels: IntArray2D): List<BitmapWaveFunctionCell> {
    val last = previous
    val canDiff = last != null &&
      last.size == pixels.data.size &&
      previousWidth == pixels.width &&
      previousHeight == pixels.height
    if (!canDiff) {
      counts.clear()
      BitmapWaveFunctionCell.countWindows(pixels, counts)
    } else {
      val changed = changedPixels(last, pixels.data)
      if (changed.size * 9 >= pixels.width * pixels.height) {
        // A redraw of most of the canvas: the delta would touch nearly every window anyway.
        counts.clear()
        BitmapWaveFunctionCell.countWindows(pixels, counts)
      } else {
        // Windows are centred on a pixel, so an edit at p only changes the windows centred within one
        // pixel of p. A pixel shared by several edited neighbours must only be tallied once, hence the
        // dedup set.
        val touched = HashSet<Int>()
        val old = IntArray2D(previousWidth, previousHeight, last.copyOf())
        for (index in changed) {
          val x = index % pixels.width
          val y = index / pixels.width
          for (dy in -1..1) {
            for (dx in -1..1) {
              val cx = x + dx
              val cy = y + dy
              if (cx < 0 || cy < 0 || cx >= pixels.width || cy >= pixels.height) continue
              if (!touched.add(cy * pixels.width + cx)) continue
              BitmapWaveFunctionCell.add(counts, BitmapWaveFunctionCell.windowAt(old, cx, cy), -1)
              BitmapWaveFunctionCell.add(counts, BitmapWaveFunctionCell.windowAt(pixels, cx, cy), 1)
            }
          }
        }
      }
    }
    previous = pixels.data.copyOf()
    previousWidth = pixels.width
    previousHeight = pixels.height
    return BitmapWaveFunctionCell.createCells(counts)
  }

  /** Indices of the pixels that differ between [before] and [after]. */
  private fun changedPixels(before: IntArray, after: IntArray): IntArray {
    if (before.size != after.size) return IntArray(0)
    var count = 0
    for (i in before.indices) {
      if (before[i] != after[i]) count++
    }
    val changed = IntArray(count)
    var next = 0
    for (i in before.indices) {
      if (before[i] != after[i]) changed[next++] = i
    }
    return changed
  }

  /** Drops the cached drawing, e.g. when the editor is reset or the grid changes size. */
  fun clear() {
    previous = null
    counts.clear()
  }
}
