package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.enum.Direction2d
import kotlin.random.Random

open class WaveFunctionConfiguration<CellType>(
    val columnCount: Int,
    val rowCount: Int,
    val possibleCells: Array<CellType>,
    val random: Random,
    val directions: List<Direction2d>,
    val supportsZeroCell: Boolean,
    val hardContradictionStrategy: HardContradictionStrategy = HardContradictionStrategy.PATCH,
    /**
     * Safety valve for [HardContradictionStrategy.BACKTRACK]: [WaveFunctionCollapser.solve] gives up
     * (and throws) once this many collapses were rolled back. Unused by the default strategy.
     */
    val maxBacktracks: Int = 20_000,
    /**
     * Edge length of the area erased around a contradiction by
     * [HardContradictionStrategy.LOCAL_ERASURE]: the zone is `2 * radius + 1` cells wide, so the
     * default of 2 erases a 5x5 block. Unused by the other strategies.
     */
    val baseEraseRadius: Int = 2,
    /** Upper bound the erasure radius may grow to while the same area keeps contradicting. */
    val maxEraseRadius: Int = 12,
    /**
     * Layer 1 safety valve: how many candidate tiles the AC-3 look-ahead tries before it gives the
     * cell up as a contradiction. Each try is cheap, so a few dozen is plenty.
     */
    val lookAheadAttempts: Int = 32,
    /**
     * Layer 3: weight multiplier applied to the tile choices inside a zone that contradicted. The
     * penalty stacks on repeated failures and decays back to 1 as the zone stabilises.
     */
    val penaltyFactor: Float = 0.5f,
    /** Layer 3: collapses between two decay sweeps of the learned weight penalties. */
    val penaltyDecaySteps: Int = 128,
    /** Layer 3: multiplier applied to every penalty at a decay sweep (values above 1 decay). */
    val penaltyDecayRate: Float = 1.1f,
    /**
     * Layer 4: localized erasures allowed for one map generation. When the budget runs out (or the
     * erasure radius would cover more than half the grid) the generator gives up on local healing
     * and restarts from a clean grid instead of pushing a doomed search further.
     *
     * The default scales with the map: a flat 50 is a good ceiling on a small grid, but a large map
     * legitimately needs more healing events, and giving up that early would restart forever.
     */
    val maxErasuresPerGeneration: Int = maxOf(50, columnCount * rowCount / 2),
    /** Layer 4: clean restarts allowed before [WaveFunctionCollapser.solve] throws. */
    val maxHardResets: Int = 8,
)
