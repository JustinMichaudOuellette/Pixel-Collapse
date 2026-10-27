package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import net.akachaki.processing.engine.array.IntArray2D
import net.akachaki.processing.engine.color.ColorNamer
import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.WaveFunctionCell
import net.akachaki.processing.engine.wavefunctioncollapse.WaveFunctionState

class BitmapWaveFunctionCell(
    val kernel: Array<Int?>,
    private val probability: Float,
) : WaveFunctionCell<BitmapWaveFunctionCell> {

    val color: Int = checkNotNull(kernel[4])

    val id: Int = NEXT_ID++

    /** Static weight of this kernel (how often it appears in the source image). */
    val weight: Float get() = probability

    private val possibleColors: MutableSet<Int?> = mutableSetOf()

    /** Scratch buffer for [banImpossibleNeighborCellsCount]: the colours allowed in one direction. */
    private val allowedColors = IntArray(64)
    private var allowedColorCount = 0

    /**
     * Weighted soft check: can this kernel sit at [state] given the *collapsed* neighbours?
     * Only this direction is checked here because it drives the probabilities; the reverse direction
     * is a hard constraint and is enforced by [canBePlaced] when a cell is actually chosen.
     */
    override fun probability(state: WaveFunctionState<BitmapWaveFunctionCell>): Float {
        for (dy in -1 .. 1) {
            for (dx in -1 .. 1) {
                if (dx != 0 || dy != 0) {
                    val kernelColor = kernel[(dy + 1) * 3 + dx + 1]
                    val neighborState = state.neighborCell(dx, dy)
                    if (neighborState == null) {
                        if (kernelColor != null) {
                            return 0f
                        }
                    } else {
                        if (kernelColor == null) {
                            return 0f
                        }
                        val neighborCell = neighborState.cell
                        if (neighborCell != null) {
                            val expectedColor = neighborCell.color
                            if (expectedColor != kernelColor) {
                                return 0f
                            }
                        }
                    }
                }
            }
        }
        return probability
    }

    /**
     * Number of neighbour constraints this kernel would violate at [state], checking all 8
     * neighbours in both directions. Zero means the placement is legal.
     *
     * This kernel must want the neighbour's centre colour where the neighbour is, **and** the
     * neighbour's kernel must want this cell's centre colour where this cell is. Skipping the second
     * direction is what lets a lone pixel be placed that the already-collapsed neighbour does not
     * accept, i.e. single pixels that do not belong to the source image.
     */
    override fun conflictsAt(state: WaveFunctionState<BitmapWaveFunctionCell>): Int {
        var conflicts = 0
        for (dy in -1 .. 1) {
            for (dx in -1 .. 1) {
                if (dx != 0 || dy != 0) {
                    val kernelColor = kernel[(dy + 1) * 3 + dx + 1]
                    val neighborState = state.neighborCell(dx, dy)
                    if (neighborState == null) {
                        // Outside the grid: the kernel must leave that position empty.
                        if (kernelColor != null) {
                            conflicts++
                        }
                        continue
                    }
                    // Inside the grid: the kernel must have a pixel there.
                    if (kernelColor == null) {
                        conflicts++
                        continue
                    }
                    val neighborCell = neighborState.cell ?: continue
                    // What this cell requires of the neighbour's centre colour...
                    if (neighborCell.color != kernelColor) {
                        conflicts++
                    }
                    // ...and what the neighbour's kernel requires of this cell's centre colour.
                    if (neighborCell.kernel[(1 - dy) * 3 + (1 - dx)] != color) {
                        conflicts++
                    }
                }
            }
        }
        return conflicts
    }

    override fun matches(possibleCell: WaveFunctionState.PossibleCell<BitmapWaveFunctionCell>): Boolean {
        return this.color == possibleCell.cell.color
    }

    override fun banImpossibleNeighborCell(
        state: WaveFunctionState<BitmapWaveFunctionCell>,
        direction: Direction2d,
        neighbor: WaveFunctionState<BitmapWaveFunctionCell>
    ): List<WaveFunctionState.PossibleCell<BitmapWaveFunctionCell>> {
        possibleColors.clear()
        for (i in 0 until state.possibleCells.size) {
            val possibleCell = state.possibleCells[i]
            if (!possibleCell.isBanned && possibleCell.cell.color == color && possibleCell.probability > 0f) {
                possibleColors.add(possibleCell.cell.kernelColor(direction as Direction2d8))
            }
        }
        val bannedCells = mutableListOf<WaveFunctionState.PossibleCell<BitmapWaveFunctionCell>>()
        for (i in 0 until neighbor.possibleCells.size) {
            val possibleCell = neighbor.possibleCells[i]
            if (!possibleColors.contains(possibleCell.cell.color)) {
                possibleCell.ban()
                bannedCells.add(possibleCell)
            }
        }
        return bannedCells
    }

    /**
     * Domain-aware version of [banImpossibleNeighborCell], used by the localized-erasure strategy.
     *
     * A neighbouring cell can keep a cell if this state still allows its centre colour in the given
     * direction. When this state is collapsed the kernel is fixed, so exactly one colour is allowed;
     * while it is not collapsed every cell that could still be picked contributes its colour.
     */
    override fun banImpossibleNeighborCells(
        state: WaveFunctionState<BitmapWaveFunctionCell>,
        direction: Direction2d,
        neighbor: WaveFunctionState<BitmapWaveFunctionCell>
    ): List<WaveFunctionState.PossibleCell<BitmapWaveFunctionCell>> {
        // Collapsed: keep the original single-cell contract so the propagation this strategy drives
        // prunes exactly like the rest of the engine.
        if (state.cell != null) {
            return banImpossibleNeighborCell(state, direction, neighbor)
        }
        // Not collapsed: every cell that could still be picked contributes a colour.
        val direction8 = direction as Direction2d8
        val allowedColors = mutableSetOf<Int?>()
        for (i in 0 until state.possibleCells.size) {
            val possibleCell = state.possibleCells[i]
            if (!possibleCell.isBanned && possibleCell.probability > 0f) {
                allowedColors.add(possibleCell.cell.kernelColor(direction8))
            }
        }
        val bannedCells = mutableListOf<WaveFunctionState.PossibleCell<BitmapWaveFunctionCell>>()
        for (i in 0 until neighbor.possibleCells.size) {
            val possibleCell = neighbor.possibleCells[i]
            // Only report cells banned by this call, so callers can tell whether anything changed.
            if (possibleCell.isBanned) {
                continue
            }
            if (!allowedColors.contains(possibleCell.cell.color)) {
                possibleCell.ban()
                bannedCells.add(possibleCell)
            }
        }
        return bannedCells
    }

    /**
     * Allocation-free [banImpossibleNeighborCellsCount]: the propagation calls this for every
     * (cell, direction) pair, so it must not build a list or a set.
     *
     * While the source state is not collapsed, the colours its remaining cells allow are collected in
     * a small stack buffer (there are only a handful of colours per image); a cell whose centre colour
     * is not among them is banned. The return value is the number of cells banned.
     */
    override fun banImpossibleNeighborCellsCount(
        state: WaveFunctionState<BitmapWaveFunctionCell>,
        direction: Direction2d,
        neighbor: WaveFunctionState<BitmapWaveFunctionCell>,
    ): Int {
        val direction8 = direction as Direction2d8
        allowedColorCount = 0
        if (state.cell != null) {
            // Collapsed: the same union as [banImpossibleNeighborCell] — only the cells that this
            // kernel's colour covers contribute, and the kernel itself is fixed.
            for (i in 0 until state.possibleCells.size) {
                val possibleCell = state.possibleCells[i]
                if (possibleCell.isBanned || possibleCell.cell.color != color) {
                    continue
                }
                if (possibleCell.probability <= 0f) {
                    continue
                }
                val kernelColor = possibleCell.cell.kernelColor(direction8) ?: continue
                if (!isAllowedColor(kernelColor) && allowedColorCount < allowedColors.size) {
                    allowedColors[allowedColorCount++] = kernelColor
                }
            }
            var banned = 0
            for (i in 0 until neighbor.possibleCells.size) {
                val possibleCell = neighbor.possibleCells[i]
                if (isAllowedColor(possibleCell.cell.color)) {
                    continue
                }
                possibleCell.ban()
                banned++
            }
            return banned
        }
        // Not collapsed: every cell that could still be picked contributes the colour it needs in
        // this direction, and the neighbour keeps the cells whose centre colour is among them.
        for (i in 0 until state.possibleCells.size) {
            val possibleCell = state.possibleCells[i]
            if (possibleCell.isBanned || possibleCell.probability <= 0f) {
                continue
            }
            val kernelColor = possibleCell.cell.kernelColor(direction8) ?: continue
            if (!isAllowedColor(kernelColor) && allowedColorCount < allowedColors.size) {
                allowedColors[allowedColorCount++] = kernelColor
            }
        }
        var banned = 0
        for (i in 0 until neighbor.possibleCells.size) {
            val possibleCell = neighbor.possibleCells[i]
            if (possibleCell.isBanned || isAllowedColor(possibleCell.cell.color)) {
                continue
            }
            possibleCell.ban()
            banned++
        }
        return banned
    }

    private fun isAllowedColor(color: Int): Boolean {
        for (i in 0 until allowedColorCount) {
            if (allowedColors[i] == color) {
                return true
            }
        }
        return false
    }

    override fun cellShortDescription(): String {
        return ColorNamer.closestColorName(color)
    }

    override fun cellFullDescription(): String {
        return "id: %d \n%-15s %-15s %-15s\n%-15s %-15s %-15s\n%-15s %-15s %-15s\n".format(
        id,
            *kernel.map { color ->
                ColorNamer.closestColorName(color)
            }.toTypedArray())
    }

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

        private var NEXT_ID = 1

        fun createCells(
            pixels: IntArray2D,
        ): List<BitmapWaveFunctionCell> {
            val possibilities = mutableMapOf<List<Int?>, Int>()
            computePossibilities(pixels, possibilities)
            pixels.mirrorX()
            computePossibilities(pixels, possibilities)
            return pruneUnsupported(possibilities.map { (kernel, count) ->
                BitmapWaveFunctionCell(kernel.toTypedArray(), count.toFloat())
            })
        }

        /**
         * Builds the tile set by sampling every 3x3 window of the drawing **with wrap-around**: a
         * window that runs off the left edge continues from the right edge, and likewise top/bottom.
         *
         * This is what makes an arbitrary drawing actually produce a bitmap. Two properties follow
         * from it:
         *
         *  - every kernel is complete (no `null` holes), so there are no special edge or corner tiles
         *    and any tile may sit anywhere in the output;
         *  - the periodic extension of the drawing is itself a valid tiling, because every window it
         *    contains is one of the kernels extracted here, and neighbouring windows agree on their
         *    shared pixels by construction. A solution therefore *always* exists, for any grid size,
         *    which is exactly the guarantee [StrictBitmapSolver] is given when it is left to fill a
         *    finite rectangle.
         *
         * The result reads as the pattern continuing past the edges of the output rather than as a
         * framed picture, which is the usual look of the overlapping model.
         *
         * @param pixels the source drawing; unlike [createCells] it is not modified.
         */
        fun createWrappedCells(
            pixels: IntArray2D,
        ): List<BitmapWaveFunctionCell> {
            val possibilities = mutableMapOf<List<Int?>, Int>()
            val width = pixels.width
            val height = pixels.height
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val kernel = MutableList<Int?>(9) { null }
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            kernel[(dy + 1) * 3 + dx + 1] =
                                pixels[Math.floorMod(x + dx, width), Math.floorMod(y + dy, height)]
                        }
                    }
                    val count = possibilities[kernel]
                    possibilities[kernel] = (count ?: 0) + 1
                }
            }
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