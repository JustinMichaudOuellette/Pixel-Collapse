package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.enum.Direction2d
import kotlin.random.Random

interface WaveFunctionCell<CellType : WaveFunctionCell<CellType>> {

  fun probability(state: WaveFunctionState<CellType>): Float

  /** @return true if the cell collapses into this type given the random probability. */
  fun collapses(state: WaveFunctionState<CellType>, random: Random): Boolean {
    val randomFloat = random.nextFloat()
    val probability = probability(state)
    return probability > 0f && randomFloat <= probability
  }

    fun matches(possibleCell: WaveFunctionState.PossibleCell<CellType>): Boolean

    fun banImpossibleNeighborCell(
        state: WaveFunctionState<CellType>,
        direction: Direction2d,
        neighbor: WaveFunctionState<CellType>
    ): List<WaveFunctionState.PossibleCell<CellType>>

    /**
     * Hard constraint checked when this cell is chosen for [state]: does it fit every neighbour
     * that is already collapsed, in **both** directions?
     *
     * Probe-based checks like [probability] are soft: they only look one way and are used for the
     * weights and domains. This is the final word on whether a placement is legal, so a cell is
     * never placed that a collapsed neighbour does not accept.
     */
    fun canBePlaced(state: WaveFunctionState<CellType>): Boolean = conflictsAt(state) == 0

    /**
     * Number of neighbour constraints this cell would violate at [state], checking every collapsed
     * neighbour in both directions. Zero means it fits everywhere and [canBePlaced] is true.
     *
     * Implementations that cannot measure the violations can keep the default, which only knows
     * whether the placement is legal at all. The repair pass uses the count to choose replacements.
     */
    fun conflictsAt(state: WaveFunctionState<CellType>): Int = if (probability(state) > 0f) 0 else 1

    /**
     * Bans the cells of [neighbor] that **no** cell still possible for [state] allows in [direction].
     *
     * Unlike [banImpossibleNeighborCell], which is only meaningful once [state] is collapsed, this
     * one can also use the whole remaining set of cells of [state]. Implementations that cannot
     * compare their remaining cells can keep the default, which is the single-cell contract.
     * Only the localized-erasure strategy calls this.
     */
    fun banImpossibleNeighborCells(
        state: WaveFunctionState<CellType>,
        direction: Direction2d,
        neighbor: WaveFunctionState<CellType>
    ): List<WaveFunctionState.PossibleCell<CellType>> =
        banImpossibleNeighborCell(state, direction, neighbor)

    /**
     * Same as [banImpossibleNeighborCells] but only reports **how many** cells the ban removed,
     * which is all the propagation needs. Implementations that can do that without building a list
     * should override this: it is the hottest path of the generator.
     */
    fun banImpossibleNeighborCellsCount(
        state: WaveFunctionState<CellType>,
        direction: Direction2d,
        neighbor: WaveFunctionState<CellType>
    ): Int = banImpossibleNeighborCells(state, direction, neighbor).size

    fun cellShortDescription(): String

    fun cellFullDescription(): String
}
