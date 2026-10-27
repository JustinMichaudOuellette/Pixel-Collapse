package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8

/**
 * One tile of the overlapping model: the 3x3 window of the drawing it was sampled from, where `null`
 * means the drawing ends before that pixel.
 *
 * This is only the *tile* side of the model -- sampling, weights, and the direction/colour lookup the
 * native solver's packing needs. The search, and the consistency pruning of the tile set itself, live
 * in C (`app/src/main/cpp/wfc.c`); there is no Kotlin solver any more.
 */
class BitmapWaveFunctionCell(
    val kernel: Array<Int?>,
    private val probability: Float,
) {

    /** The pixel this tile shows, i.e. the centre of its window. */
    val color: Int = checkNotNull(kernel[4])

    /** Static weight: how often this window occurs in the drawing. */
    val weight: Float get() = probability

    /** The colour this kernel shows towards [direction], or null where the drawing ends. */
    fun kernelColor(direction: Direction2d8): Int? {
        val i = when (direction) {
            Direction2d8.LEFT -> 3
            Direction2d8.UP_LEFT -> 0
            Direction2d8.UP -> 1
            Direction2d8.UP_RIGHT -> 2
            Direction2d8.RIGHT -> 5
            Direction2d8.DOWN_RIGHT -> 8
            Direction2d8.DOWN -> 7
            Direction2d8.DOWN_LEFT -> 6
        }
        return kernel[i]
    }

    /**
     * @return true when this kernel can sit at a cell whose [direction] neighbour is [other].
     *
     * Both directions of the relation are checked: this kernel must want the neighbour's centre
     * colour there, and the neighbour's kernel must want this cell's centre colour here.
     */
    fun isCompatibleWith(other: BitmapWaveFunctionCell, direction: Direction2d8): Boolean {
        return other.color == kernelColor(direction) && other.kernelColor(direction.opposite()) == color
    }

    override fun toString(): String {
        return "Color ${color.toHexString()}, Colors: [${kernel.joinToString { it?.toHexString() ?: "null" }}]"
    }

    companion object {

        /** Number of pixels in a window, i.e. the length of a kernel and of a counting key. */
        const val KERNEL_SIZE = 9

        /**
         * Canonical tile order: by the window itself, blanks first.
         *
         * The order matters because it is the tile index the solver stores in its result. Sorting makes
         * the tile set a function of the drawing alone rather than of the order windows were visited
         * in, which is what lets [ca.justinmo.wfcbitmap.WfcGenerator] recognise "this edit did not
         * change the tile set" and reuse the previous collapse instead of re-solving.
         */
        private val CANONICAL_ORDER = Comparator<List<Int?>> { a, b ->
            for (i in 0 until KERNEL_SIZE) {
                val left = a[i]
                val right = b[i]
                val order = when {
                    left == right -> 0
                    left == null -> -1
                    right == null -> 1
                    else -> left.compareTo(right)
                }
                if (order != 0) return@Comparator order
            }
            0
        }

        /**
         * Samples every 3x3 window of the drawing, as it lies: a window that hangs over an edge of the
         * drawing has no pixel there, and the kernel records that as `null`.
         *
         * Those blanks are what give a drawing an edge. A kernel is blank in a direction only where the
         * drawing ends, so the solver requires border cells to be blank outwards, which is the same as
         * requiring them to use a pattern sampled on the matching edge of the drawing -- and in turn
         * means a pixel drawn on the edge of the drawing can only be generated on the edge of the
         * bitmap.
         *
         * The drawing is **not** mirrored here, and that is deliberate. Mirroring the image would also
         * mirror the borders, so a pattern sampled on the drawing's right edge would carry a "blank to
         * the right" mask and land on the bitmap's *left* edge with the wrong colours: the edge would
         * still be an edge, but not the one that was drawn. Every tile is exactly a window of the
         * drawing, so a pixel stays on the edge it was put on.
         *
         * Patterns that can never be placed next to anything are dropped by the native model, not here.
         *
         * Note the trade the edge rule makes. Because the drawing's edge must then close around the
         * output, a border that cannot repeat enough has *no* tiling at all, and
         * [StrictSolverPortfolio] then returns nothing, which is why the caller falls back to the
         * stretched drawing.
         */
        fun createCells(
            pixels: IntArray2D,
        ): List<BitmapWaveFunctionCell> {
            val counts = mutableMapOf<List<Int?>, Int>()
            countWindows(pixels, counts)
            return createCells(counts)
        }

        /**
         * Builds the tile set from window counts: one tile per distinct window, weighted by how often
         * it occurs, in canonical order.
         *
         * Takes counts rather than pixels so an edit can update only the windows it touched
         * ([ca.justinmo.wfcbitmap.WindowTally]) and still produce exactly the tile set a full resample
         * would.
         */
        fun createCells(counts: Map<List<Int?>, Int>): List<BitmapWaveFunctionCell> {
            val kernels = counts.keys.sortedWith(CANONICAL_ORDER)
            return kernels.map { kernel ->
                BitmapWaveFunctionCell(kernel.toTypedArray(), (counts[kernel] ?: 0).toFloat())
            }
        }

        /** Counts every window of [pixels], adding into [counts]. */
        fun countWindows(pixels: IntArray2D, counts: MutableMap<List<Int?>, Int>) {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    add(counts, windowAt(pixels, x, y), 1)
                }
            }
        }

        /** The window centred on (x, y), or all-null where that is outside the drawing. */
        fun windowAt(pixels: IntArray2D, x: Int, y: Int): List<Int?> {
            val kernel = MutableList<Int?>(KERNEL_SIZE) { null }
            for (dx in -1..1) {
                for (dy in -1..1) {
                    kernel[(dy + 1) * 3 + dx + 1] = pixels.getOrNull(x + dx, y + dy)
                }
            }
            return kernel
        }

        /** Adds [delta] to the count of [kernel], dropping the entry when it reaches zero. */
        fun add(counts: MutableMap<List<Int?>, Int>, kernel: List<Int?>, delta: Int) {
            val updated = (counts[kernel] ?: 0) + delta
            if (updated <= 0) {
                counts.remove(kernel)
            } else {
                counts[kernel] = updated
            }
        }
    }
}
