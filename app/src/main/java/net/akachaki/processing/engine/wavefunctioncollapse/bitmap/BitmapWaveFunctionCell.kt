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
         * @param wrap read the drawing as a torus instead: a window that hangs over an edge takes the
         *   pixel on the opposite edge, so no kernel is ever blank and the drawing has no borders at all.
         *   The tile set then describes a pattern that can wrap around the picture's edges, which is the
         *   editor's "wrap edges" mode.
         *
         * Patterns that can never be placed next to anything are dropped by the native model, not here.
         *
         * Note the trade the edge rule makes. Because the drawing's edge must then close around the
         * output, a border that cannot repeat enough has *no* tiling at all, and
         * [StrictSolverPortfolio] then returns nothing -- which is why the caller may be asked to wrap
         * the edges instead, and why a drawing with no tiling in either mode is reported as a failure
         * rather than drawn.
         */
        fun createCells(
            pixels: IntArray2D,
            wrap: Boolean = false,
            mirrorHorizontal: Boolean = false,
            mirrorVertical: Boolean = false,
            quarterTurn: Boolean = false,
        ): List<BitmapWaveFunctionCell> {
            val counts = mutableMapOf<List<Int?>, Int>()
            countWindows(pixels, counts, wrap)
            return createCells(counts, mirrorHorizontal, mirrorVertical, quarterTurn)
        }

        /**
         * Builds the tile set from window counts: one tile per distinct window, weighted by how often
         * it occurs, in canonical order.
         *
         * Takes counts rather than pixels so an edit can update only the windows it touched
         * ([ca.justinmo.wfcbitmap.WindowTally]) and still produce exactly the tile set a full resample
         * would.
         *
         * @param mirrorHorizontal also offer every window mirrored left to right (see [horizontalMirror]).
         * @param mirrorVertical also offer every window flipped top to bottom (see [verticalMirror]).
         * @param quarterTurn also offer every window turned a quarter turn clockwise (see
         *   [quarterTurnClockwise]).
         *
         * Either mirror, or the turn, is how the picture gets *more* arrangements out of the same drawing: a
         * mirrored or turned window is a pattern the drawing does not contain, so the tile set grows and the
         * solver has more room. With both mirrors on, the half turn comes with them -- flipping left to right
         * and then top to bottom is the same as turning the window upside down -- so the two add up to four
         * orientations rather than three. The turn offers all four turnings, including the two sideways ones
         * neither mirror can make; with a mirror on as well the tile set is the whole symmetry group of a
         * square, all eight orientations of every window. The three are off by default because a mirrored or
         * turned pattern is not what was drawn: the picture may then show a motif facing a way it does not
         * face in the drawing, which is exactly the point when the drawing is symmetric and exactly wrong
         * when it is not.
         */
        fun createCells(
            counts: Map<List<Int?>, Int>,
            mirrorHorizontal: Boolean = false,
            mirrorVertical: Boolean = false,
            quarterTurn: Boolean = false,
        ): List<BitmapWaveFunctionCell> {
            val tally = if (!mirrorHorizontal && !mirrorVertical && !quarterTurn) {
                counts
            } else {
                // Every orientation the setting allows is counted with the window it came from, so they are
                // all equally likely to be picked. A window that is its own mirror contributes once rather
                // than twice, hence the dedup: a symmetric motif must not become twice as likely as the
                // motifs around it.
                val both = HashMap<List<Int?>, Int>(counts.size * 8)
                for ((kernel, count) in counts) {
                    for (variant in orientations(kernel, mirrorHorizontal, mirrorVertical, quarterTurn)) {
                        both[variant] = (both[variant] ?: 0) + count
                    }
                }
                both
            }
            val kernels = tally.keys.sortedWith(CANONICAL_ORDER)
            return kernels.map { kernel ->
                BitmapWaveFunctionCell(kernel.toTypedArray(), (tally[kernel] ?: 0).toFloat())
            }
        }

        /**
         * The distinct orientations of [kernel] the three settings allow: itself, and whatever the moves
         * that are on make of it.
         *
         * Deduplicated, because many patterns are their own mirror -- a vertical bar is, a checkerboard is,
         * so is anything drawn down the middle -- and counting one of those twice would make it twice as
         * likely to be picked as the patterns beside it. A quarter turn is not a mirror, so a turned window
         * is a new pattern unless it survives the turn as well -- which takes its four corners agreeing with
         * each other and its four edge middles agreeing with each other.
         *
         * The moves are *closed over* rather than listed out: a turn and a mirror compose -- turning a window
         * and then mirroring it is a flip about a diagonal -- so the set a setting allows is the group those
         * moves generate. The two mirrors generate four (the Klein group: both axes and the half turn); the
         * turn on its own generates four as well, the four turnings; and the turn with either mirror
         * generates the whole eight of a square. Listing that table by hand is where a missing diagonal flip
         * would hide.
         */
        fun orientations(
            kernel: List<Int?>,
            mirrorHorizontal: Boolean = false,
            mirrorVertical: Boolean = false,
            quarterTurn: Boolean = false,
        ): List<List<Int?>> {
            val variants = LinkedHashSet<List<Int?>>(8)
            variants.add(kernel)
            if (!mirrorHorizontal && !mirrorVertical && !quarterTurn) return variants.toList()
            var grew = true
            while (grew) {
                grew = false
                for (variant in variants.toList()) {
                    if (mirrorHorizontal && variants.add(horizontalMirror(variant))) grew = true
                    if (mirrorVertical && variants.add(verticalMirror(variant))) grew = true
                    if (quarterTurn && variants.add(quarterTurnClockwise(variant))) grew = true
                }
            }
            return variants.toList()
        }

        /**
         * The kernel flipped on the y axis: its rows keep their place and their columns reverse. This is the
         * left-to-right mirror.
         *
         * A slot is `(dy + 1) * 3 + (dx + 1)`, so mirroring is the same row read backwards. The centre is
         * its own mirror, which is why a mirrored tile shows the same pixel as the window it came from --
         * only the shapes around it are turned around.
         */
        fun horizontalMirror(kernel: List<Int?>): List<Int?> = List(KERNEL_SIZE) { slot ->
            kernel[(slot / 3) * 3 + (2 - slot % 3)]
        }

        /**
         * The kernel flipped on the x axis: its columns keep their place and its rows reverse. This is the
         * top-to-bottom mirror, and the same reasoning holds as for [horizontalMirror] -- the centre pixel is
         * its own, so the tile still shows what its window showed.
         */
        fun verticalMirror(kernel: List<Int?>): List<Int?> = List(KERNEL_SIZE) { slot ->
            kernel[(2 - slot / 3) * 3 + slot % 3]
        }

        /**
         * The kernel turned a quarter turn clockwise: its top row becomes its right column, and what was in
         * the bottom-left corner is now in the top-left.
         *
         * Read as "where did this slot come from", which is the same trick the two mirrors use: a slot is
         * `(dy + 1) * 3 + (dx + 1)`, and after the turn the cell at `(dx, dy)` shows what the window had at
         * `(dy, 2 - dx)`. The centre is still the centre, so a turned tile shows the same pixel as the window
         * it came from, exactly as a mirrored one does.
         *
         * Which way it turns does not matter to the tile set, and that is worth knowing before picking one:
         * [orientations] closes the set under this move, so the counter-clockwise turning is generated from
         * the clockwise one anyway. It matches the direction of the editor's turn-the-drawing tool, so the
         * two never disagree about what a quarter turn means.
         */
        fun quarterTurnClockwise(kernel: List<Int?>): List<Int?> = List(KERNEL_SIZE) { slot ->
            kernel[(2 - slot % 3) * 3 + slot / 3]
        }

        /**
         * Counts every window of [pixels], adding into [counts].
         *
         * @param wrap sample across the drawing's edges; see [createCells].
         */
        fun countWindows(
            pixels: IntArray2D,
            counts: MutableMap<List<Int?>, Int>,
            wrap: Boolean = false,
        ) {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    add(counts, windowAt(pixels, x, y, wrap), 1)
                }
            }
        }

        /**
         * The window centred on (x, y): all-null where that is outside the drawing, or the pixels from
         * the opposite edge when [wrap] is set.
         */
        fun windowAt(pixels: IntArray2D, x: Int, y: Int, wrap: Boolean = false): List<Int?> {
            val kernel = MutableList<Int?>(KERNEL_SIZE) { null }
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val slot = (dy + 1) * 3 + dx + 1
                    kernel[slot] = if (wrap) {
                        pixels[wrapInto(x + dx, pixels.width), wrapInto(y + dy, pixels.height)]
                    } else {
                        pixels.getOrNull(x + dx, y + dy)
                    }
                }
            }
            return kernel
        }

        /**
         * [value] folded into `0 until [size]`, so a window can read across the drawing's edge.
         *
         * Only [windowAt]'s wrapping branch reaches this, and only with coordinates one step outside the
         * drawing, so it is a fold and not a general index: one addition at most.
         */
        private fun wrapInto(value: Int, size: Int): Int {
            val remainder = value % size
            return if (remainder < 0) remainder + size else remainder
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
