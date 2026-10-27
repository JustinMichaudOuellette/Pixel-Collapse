package net.akachaki.processing.engine.wavefunctioncollapse

enum class HardContradictionStrategy {
    PATCH,
    BACKTRACK,

    /**
     * Localized erasure with a dynamic radius: the failing area is reset to its maximum-entropy
     * state and re-propagated, and the radius grows while the same area keeps contradicting.
     * See [WaveFunctionCollapser.solve].
     */
    LOCAL_ERASURE,
}
