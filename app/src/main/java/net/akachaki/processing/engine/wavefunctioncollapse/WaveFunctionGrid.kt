package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.array.Array2D
import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.enum.Direction2d8

class WaveFunctionGrid<CellType : WaveFunctionCell<CellType>>(
    configuration: WaveFunctionConfiguration<CellType>,
) {

    private val states: Array2D<WaveFunctionState<CellType>> =
        Array2D.create(configuration.columnCount, configuration.rowCount) { x, y ->
            WaveFunctionState(x, y, this, configuration)
        }

    fun stateAtOrNull(x: Int, y: Int): WaveFunctionState<CellType>? = states.getOrNull(x, y)

    fun stateAt(x: Int, y: Int): WaveFunctionState<CellType> = states[x, y]

    fun toMutableSet(): MutableSet<WaveFunctionState<CellType>> = states.data.toMutableSet()

    val height: Int get() = states.height
}