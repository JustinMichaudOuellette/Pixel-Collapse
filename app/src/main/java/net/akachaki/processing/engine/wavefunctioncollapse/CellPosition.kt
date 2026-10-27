package net.akachaki.processing.engine.wavefunctioncollapse

/**
 * A cell coordinate.
 *
 * Android port note: this stands in for `org.joml.Vector2i`, which the original engine file
 * (`WaveFunctionCollapser`) used only to remember which cells were seeded. Keeping the port free of
 * JOML means the Android module needs no third-party math dependency; the two fields have the same
 * names, so the calling code is unchanged apart from the type name.
 */
data class CellPosition(val x: Int, val y: Int)
