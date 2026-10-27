package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.color.Color
import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.enum.Direction2d8
import net.akachaki.processing.engine.wavefunctioncollapse.bitmap.BitmapWaveFunctionCell
import kotlin.random.Random
import kotlin.reflect.KClass

class WaveFunctionState<CellType>(
    val x: Int,
    val y: Int,
    val states: WaveFunctionGrid<CellType>,
    configuration: WaveFunctionConfiguration<CellType>,
) where CellType : WaveFunctionCell<CellType> {

    var cell: CellType? = null

    val possibleCells: Array<PossibleCell<CellType>> = Array(configuration.possibleCells.size) {
        PossibleCell(x, y, configuration.possibleCells[it])
    }

    var possibleCellCount: Int = possibleCells.size
        private set

    val neighbors: List<WaveFunctionNeighbor<CellType>> by lazy {
        val neighbors = mutableListOf<WaveFunctionNeighbor<CellType>>()
        leftState()?.let { neighbors.add(WaveFunctionNeighbor(Direction2d8.LEFT, it)) }
        upState()?.let { neighbors.add(WaveFunctionNeighbor(Direction2d8.UP, it)) }
        rightState()?.let { neighbors.add(WaveFunctionNeighbor(Direction2d8.RIGHT, it)) }
        bottomState()?.let { neighbors.add(WaveFunctionNeighbor(Direction2d8.DOWN, it)) }
        neighbors.toList()
    }

    /**
     * Recalculate the probability of every remaining possible cell and removes the ones with zero probability.
     * @return true if the possibleCellCount changed
     */
    fun recalculatePossibilities(): PossibilityResult {
        for (i in 0 until possibleCells.size) {
            val possibleCell = possibleCells[i]
            if (!possibleCell.isBanned) {
                possibleCell.updateProbability(this)
            }
        }
        val previousPossibleCellCount = possibleCellCount
        var newPossibleCellCount = 0
        for (i in 0 until possibleCells.size) {
            val possibleCell = possibleCells[i]
            val probability = possibleCell.probability
            if (probability > 0f) {
                newPossibleCellCount++
            }
        }
        if (newPossibleCellCount == 0) {
            return PossibilityResult.CONTRADICTION
        }
        possibleCellCount = newPossibleCellCount
        return if (previousPossibleCellCount != possibleCellCount) {
            PossibilityResult.CHANGED
        } else {
            PossibilityResult.NOT_CHANGED
        }
    }

    fun anyNeighborIs(kClass: KClass<out WaveFunctionCell<CellType>>): Boolean =
        kClass.isInstance(leftCell()) || kClass.isInstance(upCell()) || kClass.isInstance(rightCell()) || kClass.isInstance(
            bottomCell()
        )

    fun anyNeighborIsAnyOf(kClasses: Set<KClass<out WaveFunctionCell<CellType>>>): Boolean =
        cellIsAnyOf(leftCell(), kClasses) ||
                cellIsAnyOf(upCell(), kClasses) ||
                cellIsAnyOf(rightCell(), kClasses) ||
                cellIsAnyOf(bottomCell(), kClasses)

    private fun cellIsAnyOf(cell: CellType?, kClasses: Set<KClass<out WaveFunctionCell<CellType>>>): Boolean =
        cell != null && kClasses.any { it.isInstance(this) }

    inline fun leftCell(): CellType? = leftState()?.cell
    inline fun upCell(): CellType? = upState()?.cell
    inline fun rightCell(): CellType? = rightState()?.cell
    inline fun bottomCell(): CellType? = bottomState()?.cell

    inline fun leftState(): WaveFunctionState<CellType>? = states.stateAtOrNull(x - 1, y)
    inline fun upState(): WaveFunctionState<CellType>? = states.stateAtOrNull(x, y - 1)
    inline fun rightState(): WaveFunctionState<CellType>? = states.stateAtOrNull(x + 1, y)
    inline fun bottomState(): WaveFunctionState<CellType>? = states.stateAtOrNull(x, y + 1)

    inline fun neighborCell(dx: Int, dy: Int): WaveFunctionState<CellType>? {
        return states.stateAtOrNull(x + dx, y + dy)
    }

    fun neighborCell(d: Direction2d): CellType? {
        return states.stateAtOrNull(x + d.dx, y + d.dy)?.cell
    }

    /**
     * Bans the cells of [neighbor] that none of the cells still possible here allows in [direction].
     *
     * While this state is collapsed the collapsed cell carries the constraint; while it is not, any
     * cell that is still possible can do it, because the implementation must compare this state's
     * whole remaining set (see [WaveFunctionCell.banImpossibleNeighborCells]).
     */
    fun banImpossibleNeighborCells(
        direction: Direction2d,
        neighbor: WaveFunctionState<CellType>,
    ): List<PossibleCell<CellType>> {
        val collapsedCell = cell
        if (collapsedCell != null) {
            return collapsedCell.banImpossibleNeighborCells(this, direction, neighbor)
        }
        val representative = possibleCells.firstOrNull { !it.isBanned && it.probability > 0f }
            ?: return emptyList()
        return representative.cell.banImpossibleNeighborCells(this, direction, neighbor)
    }

    /**
     * Bans what this state disallows in [neighbor] and returns how many cells that removed.
     *
     * The count is what the propagation needs; it never needs the banned cells themselves, so an
     * implementation can ban without building a list.
     */
    fun banImpossibleNeighborCellsCount(
        direction: Direction2d,
        neighbor: WaveFunctionState<CellType>,
    ): Int {
        val collapsedCell = cell
        if (collapsedCell != null) {
            return collapsedCell.banImpossibleNeighborCellsCount(this, direction, neighbor)
        }
        val representative = possibleCells.firstOrNull { !it.isBanned && it.probability > 0f }
            ?: return 0
        return representative.cell.banImpossibleNeighborCellsCount(this, direction, neighbor)
    }

    /**
     * Picks a cell for this state, weighted by the probabilities, but only among the cells that fit
     * every collapsed neighbour in both directions ([WaveFunctionCell.canBePlaced]).
     *
     * A cell that fails that check is never placed, which is what keeps stray single pixels that do
     * not belong to the source out of the result.
     *
     * @return true when a cell was picked; false when nothing fits and the state is contradictory.
     */
    fun collapse(random: Random): Boolean {
        val usable = BooleanArray(possibleCells.size)
        var totalProbability = 0f
        for (i in possibleCells.indices) {
            val possibleCell = possibleCells[i]
            if (possibleCell.probability > 0f) {
                usable[i] = true
                totalProbability += possibleCell.probability
            }
        }
        if (totalProbability <= 0f) {
            cell = null
            recalculatePossibilities()
            return false
        }
        var remainingTarget = random.nextFloat() * totalProbability
        for (i in possibleCells.indices) {
            if (!usable[i]) {
                continue
            }
            remainingTarget -= possibleCells[i].probability
            if (remainingTarget <= 0f) {
                cell = possibleCells[i].cell
                return true
            }
        }
        // Floating point rounding can leave the target just above 0: take the first usable cell.
        for (i in possibleCells.indices) {
            if (usable[i]) {
                cell = possibleCells[i].cell
                return true
            }
        }
        cell = null
        return false
    }

    fun banContradictionCell() {
        val cell = this.cell ?: return
        possibleCells.forEach { possibleCell ->
            if (cell.matches(possibleCell)) {
                possibleCell.ban()
            }
        }
        this.cell = null
    }

    fun unban() {
        possibleCells.forEach { possibleCell ->
            possibleCell.isBanned = false
        }
    }

    /**
     * @return true when the collapsed cell fits its neighbours in both directions, or, while this
     * state is not collapsed yet, when at least one cell could be placed.
     */
    fun verifyIntegrity(): Boolean {
        val collapsedCell = cell
        if (collapsedCell != null) {
            return collapsedCell.canBePlaced(this)
        }
        return possibleCells.any {
            it.updateProbability(this)
            it.probability > 0f && it.cell.canBePlaced(this)
        }
    }

    /**
     * Number of neighbour constraints the current cell violates. A state that is not collapsed yet
     * counts as one violation, so the repair pass knows it still has to pick a cell.
     */
    fun conflictsNow(): Int {
        val collapsedCell = cell ?: return 1
        return collapsedCell.conflictsAt(this)
    }

    /**
     * @return true when at least one still-possible cell also survives every collapsed neighbour, in
     * this engine's pairwise rule. The AC-3 look-ahead uses it to spot a domain that the tentative
     * collapse would wipe out.
     */
    fun hasViableCell(): Boolean {
        for (i in possibleCells.indices) {
            val possibleCell = possibleCells[i]
            if (possibleCell.isBanned) {
                continue
            }
            if (possibleCell.cell.probability(this) > 0f) {
                return true
            }
        }
        return false
    }

    fun neighborCellsString(): String {
        return "%-15s %-15s %-15s\n%-15s %-15s %-15s\n%-15s %-15s %-15s".format(
            neighborCell(Direction2d8.UP_LEFT)?.cellShortDescription(),
            neighborCell(Direction2d8.UP)?.cellShortDescription(),
            neighborCell(Direction2d8.UP_RIGHT)?.cellShortDescription(),
            neighborCell(Direction2d8.LEFT)?.cellShortDescription(),
            ".",
            neighborCell(Direction2d8.RIGHT)?.cellShortDescription(),
            neighborCell(Direction2d8.DOWN_LEFT)?.cellShortDescription(),
            neighborCell(Direction2d8.DOWN)?.cellShortDescription(),
            neighborCell(Direction2d8.DOWN_RIGHT)?.cellShortDescription(),
        )
    }

    fun possibleCellsString(): String {
        val sb = StringBuilder()
        possibleCells.forEach { pc ->
            sb.append('\n')
            sb.append(pc.cell.cellFullDescription())
        }
        return sb.toString()
    }

    class PossibleCell<CellType>(val x: Int, val y: Int, val cell: CellType) where CellType : WaveFunctionCell<CellType> {

        var isBanned: Boolean = false

        var probability: Float = 1f
            private set
            get() = if (isBanned) 0f else field

        fun ban() {
            isBanned = true
        }

        fun updateProbability(state: WaveFunctionState<CellType>) {
            probability = cell.probability(state)
        }

        override fun toString(): String {
            return "Possible Cell: isBanned = $isBanned, probability = $probability, cell = $cell"
        }

        fun unban() {
            isBanned = false
        }
    }
}