package net.akachaki.processing.engine.wavefunctioncollapse

class ContradictionException(val state: WaveFunctionState<out WaveFunctionCell<*>>)
    : RuntimeException("Contradiction exception: (${state.x}, ${state.y})\n${state.neighborCellsString()}\n${state.possibleCellsString()}")