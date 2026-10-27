package net.akachaki.processing.engine.wavefunctioncollapse

/**
 * Counters and phase timers for one [WaveFunctionCollapser].
 *
 * Updating them costs a couple of `System.nanoTime()` calls per collapse, so they stay enabled
 * outside benchmarks. They are the source of truth for the performance work: profile, then change.
 */
class WaveFunctionDiagnostics {

    /** Collapses committed by [WaveFunctionCollapser.solve]. */
    var collapses: Long = 0

    /** Wall time spent in the AC-3 look-ahead. */
    var lookAheadNanos: Long = 0

    /** Wall time spent pushing constraints to neighbours. */
    var propagationNanos: Long = 0

    /** Wall time spent erasing and re-seeding a zone (layer 2). */
    var erasureNanos: Long = 0

    /** Wall time spent in the min-conflicts / exact repair passes. */
    var repairNanos: Long = 0

    /** Wall time spent picking the next cell to collapse (priority queue maintenance). */
    var queueNanos: Long = 0

    /** Candidate tiles tested by the look-ahead. */
    var lookAheadTries: Long = 0

    /** Candidate tiles the look-ahead rejected because they wiped a neighbour out. */
    var lookAheadRejections: Long = 0

    /** Cells the look-ahead gave up on (no candidate survived). */
    var lookAheadFailures: Long = 0

    /** Contradictions found while propagating. */
    var propagationContradictions: Long = 0

    /** Localized erasures performed. */
    var erasures: Long = 0

    /** Clean restarts triggered by the macro fail-safe. */
    var hardResets: Long = 0

    /** Entries in the learned penalty table at the last update (diagnostic). */
    var penaltyEntries: Int = 0

    fun reset() {
        collapses = 0
        lookAheadNanos = 0
        propagationNanos = 0
        erasureNanos = 0
        repairNanos = 0
        queueNanos = 0
        lookAheadTries = 0
        lookAheadRejections = 0
        lookAheadFailures = 0
        propagationContradictions = 0
        erasures = 0
        hardResets = 0
    }

    /** Fraction of the look-ahead attempts that were rejected, in `0..1`. */
    val lookAheadRejectionRate: Float
        get() = if (lookAheadTries == 0L) 0f else lookAheadRejections.toFloat() / lookAheadTries

    override fun toString(): String {
        return "collapses=$collapses " +
            "lookAhead=${lookAheadNanos / 1_000_000}ms (tries=$lookAheadTries, " +
            "rejected=$lookAheadRejections, gave up=$lookAheadFailures) " +
            "propagation=${propagationNanos / 1_000_000}ms " +
            "erasure=${erasureNanos / 1_000_000}ms " +
            "queue=${queueNanos / 1_000_000}ms " +
            "repair=${repairNanos / 1_000_000}ms " +
            "erasures=$erasures resets=$hardResets penalties=$penaltyEntries"
    }
}
