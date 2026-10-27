package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.enum.Direction2d8

/**
 * One tile of the overlapping model: the 3x3 window of the drawing it was sampled from, where `null`
 * means the drawing ends before that pixel.
 *
 * This is now only the *tile* side of the model -- sampling, weights, and the direction/colour lookup
 * the native solver's packing needs. The search itself lives in C (`app/src/main/cpp/wfc.c`); there is
 * no Kotlin solver any more.
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

        /**
         * Builds the tile set from every 3x3 window of the drawing, sampled as it lies: a window that
         * hangs over an edge of the drawing has no pixel there, and the kernel records that as `null`.
         *
         * Those blanks are what give a drawing an edge. A kernel is blank in a direction only where
         * the drawing ends, so the solver requires border cells to be blank outwards, which is the same
         * as requiring them to use a pattern sampled on the matching edge of the drawing -- and in turn
         * means a pixel drawn on the edge of the drawing can only be generated on the edge of the
         * bitmap.
         *
         * The drawing is **not** mirrored here, and that is deliberate. Mirroring the image would also
         * mirror the borders, so a pattern sampled on the drawing's right edge would carry a "blank to
         * the right" mask and land on the bitmap's *left* edge with the wrong colours: the edge would
         * still be an edge, but not the one that was drawn. Every tile is exactly a window of the
         * drawing, so a pixel stays on the edge it was put on.
         *
         * Note the trade this makes. Because the drawing's edge must then close around the output, a
         * border that cannot repeat enough has *no* tiling at all, which is a real possibility and the
         * reason [StrictSolverPortfolio] returns early when every worker gives up.
         */
        fun createCells(
            pixels: IntArray2D,
        ): List<BitmapWaveFunctionCell> {
            val possibilities = mutableMapOf<List<Int?>, Int>()
            computePossibilities(pixels, possibilities)
            return pruneUnsupported(possibilities.map { (kernel, count) ->
                BitmapWaveFunctionCell(kernel.toTypedArray(), count.toFloat())
            })
        }

        /**
         * Arc consistency on the tile set itself: drops every kernel that can never be placed next to
         * any other kernel in some direction.
         *
         * This is a one-off preprocessing step (a few hundred thousand comparisons), and it pays off
         * twice: the domains the search carries are smaller, and the tiles that could only ever cause
         * a contradiction are gone before the first collapse. A direction where the kernel has no
         * pixel (grid border) needs no support.
         */
        fun pruneUnsupported(cells: List<BitmapWaveFunctionCell>): List<BitmapWaveFunctionCell> {
            val alive = LinkedHashSet(cells)
            var changed = true
            while (changed) {
                changed = false
                val iterator = alive.iterator()
                while (iterator.hasNext()) {
                    val cell = iterator.next()
                    val supported = Direction2d8.entries.all { direction ->
                        val requiredColor = cell.kernelColor(direction)
                        requiredColor == null || alive.any { other ->
                            other.color == requiredColor &&
                                other.kernelColor(direction.opposite()) == cell.color
                        }
                    }
                    if (!supported) {
                        iterator.remove()
                        changed = true
                    }
                }
            }
            return alive.toList()
        }

        private fun computePossibilities(
            pixels: IntArray2D,
            possibilities: MutableMap<List<Int?>, Int>
        ) {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    val kernel = MutableList<Int?>(9) { null }
                    for (dx in -1..1) {
                        for (dy in -1..1) {
                            kernel[(dy + 1) * 3 + dx + 1] = pixels.getOrNull(x + dx, y + dy)
                        }
                    }
                    val count = possibilities[kernel]
                    possibilities[kernel] = (count ?: 0) + 1
                }
            }
        }
    }
}
