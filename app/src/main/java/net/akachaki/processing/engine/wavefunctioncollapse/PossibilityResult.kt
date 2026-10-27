package net.akachaki.processing.engine.wavefunctioncollapse;

enum class PossibilityResult(val flag: Int) {
    NONE(0),
    CHANGED(1),
    NOT_CHANGED(1 shl 1),
    CONTRADICTION(1 shl 2),
    ;

    fun or(flags: Int): Int = flags or this.flag

    companion object {
        fun Int.has(possibilityResult: PossibilityResult): Boolean {
            return possibilityResult.flag and this != 0
        }
    }
}
