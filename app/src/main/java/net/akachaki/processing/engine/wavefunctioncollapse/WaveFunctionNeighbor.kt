package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.enum.Direction2d8

class WaveFunctionNeighbor<CellType : WaveFunctionCell<CellType>>(
    val direction: Direction2d8,
    val state: WaveFunctionState<CellType>,
)