package net.akachaki.processing.engine.wavefunctioncollapse

import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.wavefunctioncollapse.PossibilityResult.Companion.has
import java.util.*
import kotlin.math.abs
import kotlin.random.Random

class WaveFunctionCollapser<CellType>(
    val configuration: WaveFunctionConfiguration<CellType>,
) where CellType : WaveFunctionCell<CellType> {

    private companion object {
        /** How much the erasure radius grows when the same area contradicts again. */
        const val ERASE_RADIUS_STEP = 2

        /** A contradiction this soon after an erasure means the area is still deadlocked. */
        const val RECENT_CONTRADICTION_STEPS = 16

        /** Collapses without a contradiction after which the erasure radius drops back to its base. */
        const val RADIUS_RESET_STEPS = 256

        /** Cap on erasures performed for a single contradiction, so recovery cannot loop forever. */
        const val MAX_CONSECUTIVE_ERASES = 4

        /** Probability of a random move during the min-conflicts repair, to escape local minima. */
        const val LOCAL_SEARCH_NOISE = 0.02f

        /** Probability of re-seating a still-wrong cell at random when the repair stalls. */
        const val LOCAL_SEARCH_KICK = 0.1f

        /** How many times the repair pass may restart from the cells that are still wrong. */
        const val MAX_REPAIR_SWEEPS = 20

        /** Node budget of the exact re-tiling search, so a hard component cannot explode. */
        const val MAX_COMPONENT_NODES = 20_000

        /** Node budget for the small 3x3 blocks of the cluster pass; they are cheap by nature. */
        const val CLUSTER_NODE_BUDGET = 600

        /** Node budget for a component: bigger pockets are allowed to search harder. */
        fun componentNodeBudget(cellCount: Int): Int =
            minOf(MAX_COMPONENT_NODES, 400 + cellCount * 300)

        /** A penalty this close to 1 is forgotten, so the learning table stays small. */
        const val PENALTY_RESET_THRESHOLD = 0.99f

        /** Floor of a single penalty, so a tile choice never becomes completely impossible. */
        const val MIN_PENALTY = 0.02f
    }

    private val grid =  WaveFunctionGrid(configuration)

    /** Counters and phase timers; cheap enough to keep on outside benchmarks. */
    val diagnostics: WaveFunctionDiagnostics = WaveFunctionDiagnostics()

    var isFirstFrame: Boolean = true

    private var allRemainingStates: MutableSet<WaveFunctionState<CellType>> = grid.toMutableSet()

    private val collapsibleStates: PriorityQueue<WaveFunctionState<CellType>> = PriorityQueue(
        compareBy<WaveFunctionState<CellType>> {
            it.possibleCellCount
        }
    )

    /**
     * Collapses performed while [WaveFunctionConfiguration.hardContradictionStrategy] is
     * [HardContradictionStrategy.BACKTRACK], most recent last. Each one remembers the cells it
     * banned in its neighbors so it can be rolled back exactly.
     */
    private val history: MutableList<Frame<CellType>> = mutableListOf()

    /** Number of collapses rolled back by the backtracking strategy. */
    var backtrackCount: Int = 0
        private set

    /** Number of contradictions hit by the backtracking strategy. */
    var contradictionCount: Int = 0
        private set

    // --- Localized erasure (HardContradictionStrategy.LOCAL_ERASURE) -----------------------------

    /**
     * Cells whose narrowed set of remaining cells still has to be pushed to their neighbours.
     * Collapsed cells push their fixed constraint, uncollapsed ones the colours of everything they
     * can still become. Only the localized-erasure strategy uses it.
     */
    private val propagationQueue: java.util.ArrayDeque<WaveFunctionState<CellType>> = java.util.ArrayDeque()

    /** Guards [propagationQueue] against holding the same cell twice. */
    private val propagationPending: MutableSet<WaveFunctionState<CellType>> = mutableSetOf()

    /** Cells currently in [collapsibleStates], so the erasure strategy never queues one twice. */
    private val queuedStates: MutableSet<WaveFunctionState<CellType>> = mutableSetOf()

    /** Queued cells whose number of remaining cells changed, so their priority must be refreshed. */
    private val staleQueuedStates: MutableSet<WaveFunctionState<CellType>> = mutableSetOf()

    /** Radius used by the next erasure; grows while the same area keeps contradicting. */
    private var eraseRadius: Int = configuration.baseEraseRadius

    /** Centre of the last erased area, or -1 when no erasure happened in the current sequence. */
    private var lastEraseX: Int = -1
    private var lastEraseY: Int = -1

    /** Collapses made since the last erasure; used to decide when the radius drops back. */
    private var stepsSinceErase: Int = 0

    /** Number of areas erased so far; useful for diagnostics. */
    var erasureCount: Int = 0
        private set

    /** Largest erasure radius reached so far; useful for diagnostics. */
    var maxEraseRadiusUsed: Int = 0
        private set

    // --- Layer 3: dynamic weight penalties (learning) ---------------------------------------------

    /** Reusable look-ahead buffers, so the collapse hot path allocates nothing. */
    private val candidateIndices = IntArray(configuration.possibleCells.size)
    private val candidateWeights = FloatArray(configuration.possibleCells.size)
    private val candidateAlive = BooleanArray(configuration.possibleCells.size)

    /** Index of every possible cell, so the learned penalties can be stored per (cell, tile). */
    private val cellIndex: Map<CellType, Int> =
        configuration.possibleCells.withIndex().associate { (index, cell) -> cell to index }

    /**
     * Learned weight multipliers, keyed by (grid position, possible-cell index). Only the choices
     * that actually led to a contradiction are present, so the table stays tiny; 1 means "unpenalised".
     */
    private val tilePenalties: HashMap<Long, Float> = HashMap()

    /** Collapses since the last decay sweep of [tilePenalties]. */
    private var stepsSincePenaltyDecay: Int = 0

    // --- Layer 4: macro fail-safe (escape hatch) --------------------------------------------------

    /** Localized erasures spent on the current generation (reset by [globalHardReset]). */
    var erasuresThisGeneration: Int = 0
        private set

    /** Clean restarts performed so far; the app uses it to clear its rendered bitmap. */
    var hardResetCount: Int = 0
        private set

    /** Cells seeded before generation started, so a hard reset can recreate the exact start. */
    private val initialSeeds: MutableList<CellPosition> = mutableListOf()

    fun stateAt(x: Int, y: Int): WaveFunctionState<CellType> {
        return grid.stateAt(x, y)
    }

    fun seedCell(x: Int, y: Int, cell: CellType) {
        grid.stateAt(x, y).cell = cell
    }

    fun forEachCell(block: (column: Int, row: Int, cell: CellType?) -> Unit) {
        for (column in 0 until configuration.columnCount) {
            for (row in 0 until configuration.rowCount) {
                block(column, row, grid.stateAt(column, row).cell)
            }
        }
    }

    fun computeInitialProbabilities() {
        purgeCollapsedStates()
        allRemainingStates.forEach { it.recalculatePossibilities() }
    }

    private fun purgeCollapsedStates() {
        allRemainingStates.removeIf { it.cell != null }
    }

    fun solve(): WaveFunctionState<CellType>? {
        when (configuration.hardContradictionStrategy) {
            HardContradictionStrategy.LOCAL_ERASURE -> return solveLocalizedErasure()
            HardContradictionStrategy.BACKTRACK -> return solveBacktracking()
            HardContradictionStrategy.PATCH -> Unit
        }
        if (collapsibleStates.isEmpty()) {
            return null
        }
        val stateToCollapse = topCollapseCandidate() ?: return null
        stateToCollapse.collapse(configuration.random)

        var result = recomputeNeighborsProbabilities(stateToCollapse)

        if (configuration.supportsZeroCell) {
            return stateToCollapse
        }

        while (result.has(PossibilityResult.CONTRADICTION)) {
            stateToCollapse.banContradictionCell()
            stateToCollapse.collapse(configuration.random)
            if (stateToCollapse.cell == null) {
                if (stateToCollapse.x == 16 && stateToCollapse.y == 228) {
                    //stateToCollapse.unban()
                    stateToCollapse.recalculatePossibilities()
                    stateToCollapse.collapse(configuration.random)
                    if (stateToCollapse.cell == null) {
                        //stateToCollapse.unban()
                        val newResult = stateToCollapse.recalculatePossibilities()
                        stateToCollapse.collapse(configuration.random)
                        if (stateToCollapse.cell == null) {
                            throw ContradictionException(stateToCollapse)
                            handleHardContradiction(stateToCollapse)
                            break
                        }
                    }
                } else {
                    throw ContradictionException(stateToCollapse)
                    handleHardContradiction(stateToCollapse)
                    break
                }
            }
            result = recomputeNeighborsProbabilities(stateToCollapse)
        }

        return stateToCollapse
    }

    /**
     * Collapses one state, and when that leaves a state without any cell left, rolls the latest
     * collapses back and bans the cells that led to the dead end before trying again.
     *
     * This is a separate, opt-in path: it only runs when the configuration asks for
     * [HardContradictionStrategy.BACKTRACK]. The default strategy keeps the original behaviour.
     *
     * @throws ContradictionException when there is nothing left to roll back.
     */
    private fun solveBacktracking(): WaveFunctionState<CellType>? {
        while (true) {
            if (collapsibleStates.isEmpty()) {
                return null
            }
            val state = topCollapseCandidate() ?: return null

            val frame = collapseRecording(state)
            if (frame == null) {
                // Nothing left to collapse here: this level is a dead end, so unwind past it
                // instead of letting it be retried with the same (empty) set of cells.
                contradictionCount++
                if (!rollback(forceProgress = true)) throw ContradictionException(state)
                continue
            }

            val result = recomputeNeighborsRecording(state, frame)
            if (!result.has(PossibilityResult.CONTRADICTION)) {
                return state
            }

            contradictionCount++
            if (!rollback()) throw ContradictionException(state)
        }
    }

    /** Collapses [state] and records it so it can be rolled back. */
    private fun collapseRecording(state: WaveFunctionState<CellType>): CollapseFrame<CellType>? {
        state.collapse(configuration.random)
        val cell = state.cell ?: return null
        val possibleCell = state.possibleCells.firstOrNull { it.cell === cell }
        if (possibleCell == null) {
            state.cell = null
            return null
        }
        return CollapseFrame(state, possibleCell).also { history.add(it) }
    }

    /**
     * Same as [recomputeNeighborsProbabilities], but keeps the bans it applies in [frame] instead of
     * lifting them again, so a rollback can lift exactly those.
     */
    private fun recomputeNeighborsRecording(
        state: WaveFunctionState<CellType>,
        frame: CollapseFrame<CellType>,
    ): Int {
        var results = 0

        configuration.directions.forEach { direction ->
            val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)
            if (neighbor == null || neighbor.cell != null) {
                return@forEach
            }
            val bannedCells = state.cell?.banImpossibleNeighborCell(state, direction, neighbor)
            if (bannedCells != null && bannedCells.isNotEmpty()) {
                frame.bannedCells.addAll(bannedCells)
            }
            val result = neighbor.recalculatePossibilities()
            if (result != PossibilityResult.CONTRADICTION) {
                if (allRemainingStates.remove(neighbor)) {
                    collapsibleStates.add(neighbor)
                } else if (result == PossibilityResult.CHANGED) {
                    collapsibleStates.remove(neighbor)
                    collapsibleStates.add(neighbor)
                }
            }
            results = result.or(results)
        }

        return results
    }

    /**
     * Rolls the search back to the latest state that still has an untried cell.
     * @param forceProgress when true, a level at the top of the search that reports remaining cells
     * but cannot collapse is given up instead of being retried, so the rollback cannot loop.
     * @return true when the search can continue, false when there is nothing left to roll back.
     */
    private fun rollback(forceProgress: Boolean = false): Boolean {
        var force = forceProgress
        // Levels that ran out of cells stay out of the queue until a level above them changed,
        // otherwise they are retried with the very same surroundings and the search loops.
        val exhausted = mutableListOf<WaveFunctionState<CellType>>()
        while (history.isNotEmpty()) {
            if (backtrackCount >= configuration.maxBacktracks) {
                return false
            }
            when (val frame = history.removeAt(history.lastIndex)) {
                is CollapseFrame<CellType> -> {
                    val state = undoCollapse(frame)
                    val retry = retryFrameFor(state)
                    // The cell it picked is a dead end for this state, but only while the collapses
                    // below this level stay the same.
                    retry.triedCells.add(frame.chosenCell)
                    frame.chosenCell.ban()
                    state.recalculatePossibilities()
                    backtrackCount++
                    if (state.possibleCellCount > 0) {
                        queueForCollapse(state)
                        requeueExhausted(exhausted)
                        return true
                    }
                    // No alternative left at this level: give the state its cells back, keep
                    // unwinding, and only queue it again once a level above it changes.
                    history.removeAt(history.lastIndex)
                    retry.triedCells.forEach { it.unban() }
                    state.recalculatePossibilities()
                    exhausted.add(state)
                }

                is RetryFrame<CellType> -> {
                    if (frame.state.possibleCellCount > 0 && !force) {
                        history.add(frame)
                        queueForCollapse(frame.state)
                        requeueExhausted(exhausted)
                        return true
                    }
                    frame.triedCells.forEach { it.unban() }
                    frame.state.recalculatePossibilities()
                    exhausted.add(frame.state)
                    force = false
                }
            }
        }
        return false
    }

    private fun requeueExhausted(exhausted: List<WaveFunctionState<CellType>>) {
        exhausted.forEach { queueForCollapse(it) }
    }

    /** Rolls back one collapse: restores its cell, lifts its bans and requeues what it touched. */
    private fun undoCollapse(frame: CollapseFrame<CellType>): WaveFunctionState<CellType> {
        val state = frame.state
        state.cell = null

        val affected = LinkedHashSet<WaveFunctionState<CellType>>()
        for (bannedCell in frame.bannedCells) {
            bannedCell.unban()
            affected.add(grid.stateAt(bannedCell.x, bannedCell.y))
        }
        affected.add(state)

        for (affectedState in affected) {
            if (affectedState.cell != null) {
                continue
            }
            affectedState.recalculatePossibilities()
            queueForCollapse(affectedState)
        }
        return state
    }

    /** Puts an uncollapsed state back in the collapse queue, without queuing it twice. */
    private fun queueForCollapse(state: WaveFunctionState<CellType>) {
        if (state.cell != null) {
            return
        }
        allRemainingStates.remove(state)
        collapsibleStates.remove(state)
        collapsibleStates.add(state)
    }

    /** The retry frame of [state] at the current level, created if there is none yet. */
    private fun retryFrameFor(state: WaveFunctionState<CellType>): RetryFrame<CellType> {
        val top = history.lastOrNull()
        if (top is RetryFrame<CellType> && top.state === state) {
            return top
        }
        return RetryFrame(state).also { history.add(it) }
    }

    // --- Localized erasure with dynamic radius ---------------------------------------------------

    /**
     * Collapses one state, and when that leaves a cell without any possibility, erases the area
     * around the contradiction and lets the constraints of the untouched map flow back into it.
     *
     * The strategy keeps no history: the grid *is* the state of the search, so the memory overhead
     * is a couple of coordinate counters. A contradiction is a local deadlock, so giving the cells
     * around it a fresh start (and their neighbours the chance to re-apply the constraints) is
     * enough to move on, and the erasure radius grows while the same area keeps failing.
     */
    private fun solveLocalizedErasure(): WaveFunctionState<CellType>? {
        while (true) {
            val pollStartedAt = System.nanoTime()
            val state = pollCandidate() ?: return null
            diagnostics.queueNanos += System.nanoTime() - pollStartedAt

            // Layer 1: commit only a cell that survives the look-ahead. If none does, this cell is
            // the dead end and recovery has to heal the area before anything is committed.
            if (!collapseWithLookAhead(state)) {
                contradictionCount++
                diagnostics.lookAheadFailures++
                recover(state)
                continue
            }

            // A state that cannot pick any cell is already contradictory; otherwise propagating the
            // new collapse tells us which cell (if any) ran out of possibilities.
            val contradiction = propagate(state) ?: if (state.cell == null) state else null
            if (contradiction == null) {
                diagnostics.collapses++
                onCollapsedWithoutContradiction()
                return state
            }
            recover(contradiction)
        }
    }

    /**
     * Layer 1, AC-3 look-ahead: picks and commits a cell for [state] without creating a dead end.
     *
     * Candidates are tried in weighted order (a random permutation biased towards heavy tiles, via
     * the `U^(1/weight)` trick). Before a candidate is committed it is validated: every neighbouring
     * cell must keep at least one possible cell afterwards. A candidate that would wipe out a
     * neighbour is discarded and the next one is tried, so the collapse cannot manufacture a
     * contradiction one step ahead.
     *
     * Nothing is banned or copied for the test: the grid itself is the only state.
     *
     * @return false when no candidate survived, i.e. every tile here leads to a wipe-out.
     */
    private fun collapseWithLookAhead(state: WaveFunctionState<CellType>): Boolean {
        val startedAt = System.nanoTime()
        try {
            return pickCellWithLookAhead(state)
        } finally {
            diagnostics.lookAheadNanos += System.nanoTime() - startedAt
        }
    }

    private fun pickCellWithLookAhead(state: WaveFunctionState<CellType>): Boolean {
        val possibleCells = state.possibleCells
        if (possibleCells.isEmpty()) {
            return false
        }
        val random = configuration.random

        // Reusable buffers: the look-ahead runs once per collapse, so it must not allocate.
        var available = 0
        for (index in possibleCells.indices) {
            val possibleCell = possibleCells[index]
            if (possibleCell.isBanned) {
                continue
            }
            val weight = possibleCell.probability * penaltyOf(state, index)
            if (weight <= 0f) {
                continue
            }
            candidateIndices[available] = index
            candidateWeights[available] = weight
            candidateAlive[available] = true
            available++
        }
        if (available == 0) {
            return false
        }

        val attempts = minOf(configuration.lookAheadAttempts, available)
        var attempt = 0
        while (attempt < attempts) {
            attempt++
            val slot = drawWeightedCandidate(available, random)
            if (slot < 0) {
                break
            }
            val candidate = possibleCells[candidateIndices[slot]]
            diagnostics.lookAheadTries++
            state.cell = candidate.cell
            // AC-3 step: keep the tile only when no neighbouring domain is wiped out by it.
            if (neighboursStayViable(state)) {
                return true
            }
            diagnostics.lookAheadRejections++
        }
        // Every tried tile kills a neighbour: leave the cell open for recovery.
        state.cell = null
        return false
    }

    /**
     * Draws one still-live candidate with probability proportional to its weight and marks it used,
     * which is sampling-without-replacement: high-weight tiles are tried first on average, but the
     * artwork still varies. @return the slot, or -1 when everything was drawn.
     */
    private fun drawWeightedCandidate(count: Int, random: Random): Int {
        var total = 0f
        for (i in 0 until count) {
            if (candidateAlive[i]) {
                total += candidateWeights[i]
            }
        }
        if (total <= 0f) {
            return -1
        }
        var target = random.nextFloat() * total
        for (i in 0 until count) {
            if (!candidateAlive[i]) {
                continue
            }
            target -= candidateWeights[i]
            if (target <= 0f) {
                candidateAlive[i] = false
                return i
            }
        }
        for (i in count - 1 downTo 0) {
            if (candidateAlive[i]) {
                candidateAlive[i] = false
                return i
            }
        }
        return -1
    }

    /**
     * @return true when no neighbour that is still open loses its whole domain because of the
     * tentative collapse sitting in [state].
     */
    private fun neighboursStayViable(state: WaveFunctionState<CellType>): Boolean {
        for (direction in configuration.directions) {
            val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)
                ?: continue
            if (neighbor.cell != null) {
                continue
            }
            if (!neighbor.hasViableCell()) {
                return false
            }
        }
        return true
    }

    /**
     * Pushes [initial]'s constraint into its neighbours and keeps going while their sets of
     * remaining cells change, stopping at the first cell left without possibilities.
     * @return that cell, or null when propagation settled.
     */
    private fun propagate(initial: WaveFunctionState<CellType>): WaveFunctionState<CellType>? {
        val startedAt = System.nanoTime()
        enqueuePropagation(initial)
        val contradiction = drainPropagation()
        diagnostics.propagationNanos += System.nanoTime() - startedAt
        if (contradiction != null) {
            diagnostics.propagationContradictions++
        }
        return contradiction
    }

    /** Processes the propagation queue until it is empty or a cell runs out of possibilities. */
    private fun drainPropagation(): WaveFunctionState<CellType>? {
        while (propagationQueue.isNotEmpty()) {
            val state = propagationQueue.removeFirst()
            propagationPending.remove(state)

            for (direction in configuration.directions) {
                val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)
                    ?: continue
                if (neighbor.cell != null) {
                    continue
                }
                // The collapsed cell fixes one colour, a partially restricted one allows every
                // colour its remaining cells could still produce.
                val reachableForTheFirstTime = allRemainingStates.remove(neighbor)
                val bannedCells = state.banImpossibleNeighborCellsCount(direction, neighbor)
                if (bannedCells == 0) {
                    // Nothing changed for this neighbour, but the first time it is reached it still
                    // has to join the search: its own collapse is what propagates further inward.
                    if (reachableForTheFirstTime) {
                        neighbor.recalculatePossibilities()
                        scheduleCollapse(neighbor)
                    }
                    continue
                }
                if (neighbor.recalculatePossibilities() == PossibilityResult.CONTRADICTION) {
                    // Halt propagation immediately: recovery takes over from this cell.
                    propagationQueue.clear()
                    propagationPending.clear()
                    return neighbor
                }
                scheduleCollapse(neighbor)
                enqueuePropagation(neighbor)
            }
        }
        return null
    }

    private fun enqueuePropagation(state: WaveFunctionState<CellType>) {
        if (propagationPending.add(state)) {
            propagationQueue.addLast(state)
        }
    }

    /**
     * Layers 2, 3 and 4: heals the area around [initial].
     *
     * The zone is erased, its perimeter is pushed back into propagation and, if that exposes another
     * dead cell, that one is erased too at the same radius (a deadlock that survives one erasure is
     * usually the same structural problem one step further on). Layer 3 penalises the tile choices
     * that were sitting in the failing zone, and layer 4 abandons the local approach for a clean
     * restart when the erasure budget or the covered area grows too large.
     */
    private fun recover(initial: WaveFunctionState<CellType>) {
        contradictionCount++
        escalateEraseRadius(initial)
        penalizeZone(initial.x, initial.y, eraseRadius)

        var center = initial
        var erases = 0
        while (true) {
            if (failSafeTriggered(eraseRadius)) {
                globalHardReset()
                return
            }
            val eraseStartedAt = System.nanoTime()
            eraseRegion(center.x, center.y, eraseRadius)
            diagnostics.erasureNanos += System.nanoTime() - eraseStartedAt
            diagnostics.erasures++
            erasuresThisGeneration++
            erasureCount++
            erases++
            val next = drainPropagation() ?: return
            if (erases >= MAX_CONSECUTIVE_ERASES) {
                // The zone keeps dying right after it is healed: local repair is not working, so hand
                // over to the layer-4 escape hatch instead of giving up on the whole generation.
                globalHardReset()
                return
            }
            center = next
        }
    }

    /**
     * Resets every cell in the square of [radius] around ([centerX], [centerY]) to its uncollapsed,
     * maximum-entropy state and clears its pending propagation work. The ring of cells just outside
     * the square is then pushed back into the propagation queue, so the constraints of the rest of
     * the map flow into the cleared area again.
     */
    private fun eraseRegion(centerX: Int, centerY: Int, radius: Int) {
        val minX = maxOf(0, centerX - radius)
        val maxX = minOf(configuration.columnCount - 1, centerX + radius)
        val minY = maxOf(0, centerY - radius)
        val maxY = minOf(configuration.rowCount - 1, centerY + radius)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val state = grid.stateAt(x, y)
                state.cell = null
                state.unban()                       // every cell is available again
                state.recalculatePossibilities()    // ...except where a collapsed neighbour forbids it
                propagationPending.remove(state)    // drop pending propagation work for the zone
                scheduleCollapse(state)
            }
        }

        // Re-propagation boundary: one ring around the erased square. Collapsed cells re-apply their
        // fixed constraint, partially restricted ones push the colours they still allow. Their
        // probabilities are refreshed first: the collapsed cells that were just removed may have
        // zeroed candidates out here, and a stale zero would silently shrink the domain for good.
        for (x in (minX - 1)..(maxX + 1)) {
            for (y in (minY - 1)..(maxY + 1)) {
                if (x in minX..maxX && y in minY..maxY) {
                    continue
                }
                val ring = grid.stateAtOrNull(x, y) ?: continue
                if (ring.cell == null) {
                    ring.recalculatePossibilities()
                }
                enqueuePropagation(ring)
            }
        }
    }

    // --- Layer 3: dynamic weight penalties -------------------------------------------------------

    /**
     * Penalises the choices that were collapsed inside the failing zone: they are the ones that led
     * to the contradiction, so the next attempts in the same area prefer other tiles instead of
     * reproducing the same failing layout (the "ping-pong" case).
     */
    private fun penalizeZone(centerX: Int, centerY: Int, radius: Int) {
        if (configuration.penaltyFactor >= 1f || configuration.possibleCells.isEmpty()) {
            return
        }
        val minX = maxOf(0, centerX - radius)
        val maxX = minOf(configuration.columnCount - 1, centerX + radius)
        val minY = maxOf(0, centerY - radius)
        val maxY = minOf(configuration.rowCount - 1, centerY + radius)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val state = grid.stateAt(x, y)
                val collapsedCell = state.cell ?: continue
                val index = cellIndex[collapsedCell] ?: continue
                val key = penaltyKey(state, index)
                val current = tilePenalties[key] ?: 1f
                tilePenalties[key] = maxOf(MIN_PENALTY, current * configuration.penaltyFactor)
            }
        }
        diagnostics.penaltyEntries = tilePenalties.size
    }

    /** Learned weight multiplier for [possibleCellIndex] at [state]; 1 when unpenalised. */
    private fun penaltyOf(state: WaveFunctionState<CellType>, possibleCellIndex: Int): Float {
        if (tilePenalties.isEmpty()) {
            return 1f
        }
        return tilePenalties[penaltyKey(state, possibleCellIndex)] ?: 1f
    }

    private fun penaltyKey(state: WaveFunctionState<CellType>, possibleCellIndex: Int): Long {
        val position = state.y.toLong() * configuration.columnCount + state.x
        return position * configuration.possibleCells.size + possibleCellIndex
    }

    /**
     * Slowly forgets the learned penalties: every entry moves back towards 1 and is dropped once it
     * is close enough, so a zone that has been generated successfully returns to normal weights.
     */
    private fun decayPenalties() {
        if (tilePenalties.isEmpty()) {
            return
        }
        val iterator = tilePenalties.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = entry.value * configuration.penaltyDecayRate
            if (decayed >= PENALTY_RESET_THRESHOLD) {
                iterator.remove()
            } else {
                entry.setValue(decayed)
            }
        }
        diagnostics.penaltyEntries = tilePenalties.size
    }

    // --- Layer 4: macro fail-safe ----------------------------------------------------------------

    /**
     * @return true when the local approach has to be abandoned: the per-generation erasure budget is
     * spent, or the zone that would be erased covers more than half of the grid.
     */
    private fun failSafeTriggered(radius: Int): Boolean {
        if (erasuresThisGeneration >= configuration.maxErasuresPerGeneration) {
            return true
        }
        val diameter = 2L * radius + 1
        val gridCells = configuration.columnCount.toLong() * configuration.rowCount
        return diameter * diameter * 2 > gridCells
    }

    /**
     * Layer 4 escape hatch: wipes the grid and starts the generation over from the seeded cells.
     *
     * The learned penalties are intentionally kept, so the next attempt avoids the choices that kept
     * failing. After [WaveFunctionConfiguration.maxHardResets] restarts the search gives up by
     * throwing, which lets the caller or the sketch decide what to do next.
     */
    fun globalHardReset() {
        hardResetCount++
        if (hardResetCount > configuration.maxHardResets) {
            throw ContradictionException(grid.stateAt(0, 0))
        }
        diagnostics.hardResets++
        resetGrid()
    }

    /** Restores the initial, uncollapsed state of the grid and re-seeds the start cells. */
    private fun resetGrid() {
        propagationQueue.clear()
        propagationPending.clear()
        queuedStates.clear()
        staleQueuedStates.clear()
        collapsibleStates.clear()
        allRemainingStates = grid.toMutableSet()
        erasuresThisGeneration = 0
        eraseRadius = configuration.baseEraseRadius
        lastEraseX = -1
        lastEraseY = -1
        stepsSinceErase = 0
        forEachState { state ->
            state.cell = null
            state.unban()
            state.recalculatePossibilities()
        }
        initialSeeds.forEach { seed -> seedState(seed.x, seed.y) }
    }

    /** Puts an uncollapsed state in the collapse queue at most once. */
    private fun scheduleCollapse(state: WaveFunctionState<CellType>) {
        if (state.cell != null) {
            return
        }
        allRemainingStates.remove(state)
        if (!queuedStates.add(state)) {
            // Already queued: its priority is outdated, refresh it when it reaches the front.
            staleQueuedStates.add(state)
            return
        }
        collapsibleStates.add(state)
    }

    /** Requeues states whose number of remaining cells changed while they were queued. */
    private fun pollCandidate(): WaveFunctionState<CellType>? {
        while (collapsibleStates.isNotEmpty()) {
            val state = topCollapseCandidate() ?: return null
            if (staleQueuedStates.remove(state)) {
                collapsibleStates.add(state)
                continue
            }
            queuedStates.remove(state)
            return state
        }
        return null
    }

    /**
     * Grows the erasure radius when the new contradiction is inside (or next to) the last erased
     * area and happened almost immediately after that erasure; otherwise starts over from the base
     * radius. The radius is also capped relative to the grid, so a small grid is never erased whole.
     */
    private fun escalateEraseRadius(center: WaveFunctionState<CellType>) {
        val sameArea = lastEraseX >= 0 &&
            abs(center.x - lastEraseX) <= eraseRadius + 1 &&
            abs(center.y - lastEraseY) <= eraseRadius + 1 &&
            stepsSinceErase <= RECENT_CONTRADICTION_STEPS
        eraseRadius = if (sameArea) {
            minOf(eraseRadius + ERASE_RADIUS_STEP, effectiveMaxEraseRadius)
        } else {
            configuration.baseEraseRadius
        }
        lastEraseX = center.x
        lastEraseY = center.y
        stepsSinceErase = 0
        maxEraseRadiusUsed = maxOf(maxEraseRadiusUsed, eraseRadius)
    }

    /** The configured radius cap, reduced for grids small enough that it would erase everything. */
    private val effectiveMaxEraseRadius: Int
        get() = minOf(
            configuration.maxEraseRadius,
            maxOf(configuration.baseEraseRadius, maxOf(configuration.columnCount, configuration.rowCount) / 4),
        )

    /** A collapse went through: the radius and the learned penalties relax a little. */
    private fun onCollapsedWithoutContradiction() {
        stepsSinceErase++
        if (stepsSinceErase >= RADIUS_RESET_STEPS) {
            eraseRadius = configuration.baseEraseRadius
            lastEraseX = -1
            lastEraseY = -1
            stepsSinceErase = 0
        }
        stepsSincePenaltyDecay++
        if (stepsSincePenaltyDecay >= configuration.penaltyDecaySteps) {
            stepsSincePenaltyDecay = 0
            decayPenalties()
        }
    }

    private fun handleHardContradiction(stateToCollapse: WaveFunctionState<CellType>) {
        when (configuration.hardContradictionStrategy) {
            HardContradictionStrategy.PATCH -> {
                stateToCollapse.unban()
                // clear immediate neighbors
                // recalculate probabilities within distance 2
                // remove cells from queue
            }
            HardContradictionStrategy.BACKTRACK -> {

            }
            HardContradictionStrategy.LOCAL_ERASURE -> {
                // Handled by solveLocalizedErasure; this path is not reached with that strategy.
                stateToCollapse.unban()
            }
        }
    }

    fun recomputeNeighborsProbabilities(state: WaveFunctionState<CellType>): Int {
        var results = 0

        configuration.directions.forEach { direction2d ->
            results = recomputeNeighborProbabilities(state, direction2d).or(results)
        }

        return results
    }

    private fun topCollapseCandidate(): WaveFunctionState<CellType>? {
        val state = collapsibleStates.poll()
        check(state.cell == null)
        return state
    }

    private fun recomputeNeighborProbabilities(state: WaveFunctionState<CellType>, direction: Direction2d): PossibilityResult {
        val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy) ?: return PossibilityResult.NOT_CHANGED
        if (neighbor.cell != null) {
            return PossibilityResult.NOT_CHANGED
        }
        val bannedCells = state.cell?.banImpossibleNeighborCell(state, direction, neighbor)
        val result = neighbor.recalculatePossibilities()
        if (result != PossibilityResult.CONTRADICTION) {
            if (allRemainingStates.remove(neighbor)) {
                collapsibleStates.add(neighbor)
            } else if (result == PossibilityResult.CHANGED) {
                collapsibleStates.remove(neighbor)
                collapsibleStates.add(neighbor)
            }
        } else {
            bannedCells?.forEach { cell ->
                cell.unban()
            }
        }
        return result
    }

    fun debugCellAt(x: Int, y: Int) {
        val state = stateAt(x, y)
        state.recalculatePossibilities()
    }

    /**
     * Seeds ([x], [y]) as a start cell. The position is remembered, so [globalHardReset] can rebuild
     * exactly the same starting point.
     */
    fun initializeEdgeState(x: Int, y: Int) {
        if (initialSeeds.none { it.x == x && it.y == y }) {
            initialSeeds.add(CellPosition(x, y))
        }
        seedState(x, y)
    }

    private fun seedState(x: Int, y: Int) {
        val state = stateAt(x, y)
        state.recalculatePossibilities()
        collapsibleStates.add(state)
        queuedStates.add(state)
    }

    fun verifyIntegrity(): List<CellPosition> {
        val result = mutableListOf<CellPosition>()
        forEachCell { column: Int, row: Int, cell: CellType? ->
            if (!grid.stateAt(column, row).verifyIntegrity()) {
                result.add(CellPosition(column, row))
            }
        }
        return result
    }

    /** Number of cells that still violate a neighbour constraint. */
    fun invalidCellCount(): Int {
        var invalid = 0
        forEachState { state ->
            if (state.conflictsNow() > 0) {
                invalid++
            }
        }
        return invalid
    }

    /**
     * Minimises the number of cells that violate a neighbour constraint, using min-conflicts local
     * search: a violating cell is replaced with the cell that leaves the fewest violations against
     * its current neighbours, and that cell and its neighbours are revisited.
     *
     * This is meant to run after the grid is complete, for the cells the collapse pass left
     * inconsistent. It never erases work, only replaces single cells, so it can be called repeatedly
     * (for example once per frame) without keeping any history.
     *
     * @param maxMoves budget for this call; the search continues from where it stopped next time.
     * @return the number of cells that still violate a constraint.
     */
    fun repairInvalidCells(maxMoves: Int = 200_000): Int {
        val startedAt = System.nanoTime()
        val result = repairInvalidCellsImpl(maxMoves)
        diagnostics.repairNanos += System.nanoTime() - startedAt
        return result
    }

    private fun repairInvalidCellsImpl(maxMoves: Int): Int {
        val candidates = configuration.possibleCells
        if (candidates.isEmpty()) {
            return 0
        }
        val random = configuration.random
        val pending = LinkedHashSet<WaveFunctionState<CellType>>()
        collectInvalid(pending)

        var moves = 0
        var sweeps = 0
        while (moves < maxMoves) {
            if (pending.isEmpty()) {
                // Every violating cell reached its best local choice and the search stalled. Kick a
                // fraction of the wrong cells to a random cell and try again: same plateau otherwise.
                if (sweeps >= MAX_REPAIR_SWEEPS) {
                    break
                }
                var kicked = 0
                forEachState { state ->
                    if (state.conflictsNow() > 0) {
                        if (random.nextFloat() < LOCAL_SEARCH_KICK) {
                            state.cell = candidates[random.nextInt(candidates.size)]
                            kicked++
                        }
                        pending.add(state)
                    }
                }
                if (kicked == 0) {
                    break
                }
                sweeps++
                continue
            }
            val state = pending.first()
            pending.remove(state)
            moves++

            val best = bestReplacement(state, candidates, random)
            if (state.cell === best) {
                continue
            }
            state.cell = best
            // Only this cell and its neighbours can have changed their violation count.
            if (state.conflictsNow() > 0) {
                pending.add(state)
            }
            for (direction in configuration.directions) {
                val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)
                    ?: continue
                if (neighbor.conflictsNow() > 0) {
                    pending.add(neighbor)
                }
            }
        }

        return invalidCellCount()
    }

    /**
     * Second repair phase for the cells min-conflicts cannot fix one at a time: when a cell's 8
     * neighbours demand contradictory colours, no single replacement helps, but re-tiling a small
     * block around it can.
     *
     * The block is cleared and an assignment with **zero** violations is searched for, so the block
     * is only touched when it can be made fully consistent with the (untouched) boundary. If no such
     * assignment exists the block is left exactly as it was.
     *
     * @return the number of cells that still violate a neighbour constraint.
     */
    fun repairInvalidClusters(maxIterations: Int = 4_000, blockRadius: Int = 1): Int {
        val startedAt = System.nanoTime()
        try {
            return repairInvalidClustersImpl(maxIterations, blockRadius)
        } finally {
            diagnostics.repairNanos += System.nanoTime() - startedAt
        }
    }

    private fun repairInvalidClustersImpl(maxIterations: Int, blockRadius: Int): Int {
        val candidates = configuration.possibleCells
        if (candidates.isEmpty()) {
            return 0
        }
        val random = configuration.random
        val invalid = mutableListOf<WaveFunctionState<CellType>>()

        fun collectInvalid() {
            invalid.clear()
            forEachState { state ->
                if (state.conflictsNow() > 0) {
                    invalid.add(state)
                }
            }
        }

        collectInvalid()
        var iterations = 0
        while (invalid.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val seed = invalid[random.nextInt(invalid.size)]
            val minX = maxOf(0, seed.x - blockRadius)
            val maxX = minOf(configuration.columnCount - 1, seed.x + blockRadius)
            val minY = maxOf(0, seed.y - blockRadius)
            val maxY = minOf(configuration.rowCount - 1, seed.y + blockRadius)

            val block = ArrayList<WaveFunctionState<CellType>>(9)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    block.add(grid.stateAt(x, y))
                }
            }

            val snapshot: MutableList<CellType?> = block.map { it.cell }.toMutableList()
            // Clearing the block first makes the cells that are not assigned yet impose no
            // constraint, so the search only has to satisfy the boundary and what it assigns.
            for (state in block) {
                state.cell = null
            }
            if (!assignExact(block, candidates, random, CLUSTER_NODE_BUDGET)) {
                // No zero-violation tiling for this block: put it back untouched.
                for (index in block.indices) {
                    block[index].cell = snapshot[index]
                }
            }
            if (iterations % 64 == 0) {
                collectInvalid()
            }
        }

        return invalidCellCount()
    }

    /**
     * Phase 1b: exact repair of the cells that still violate a constraint, grouped into connected
     * components.
     *
     * Each component is cleared and solved exactly: a depth-first search looks for an assignment with
     * **zero** violations against the untouched boundary. The violations left after min-conflicts are
     * isolated pockets, so components are small and the exact search terminates quickly. A component
     * without a solution is retried with one extra ring of cells around it, then left untouched.
     *
     * @param maxComponentCells components bigger than this are left to min-conflicts.
     * @param growAttempts how many times a failing component may grow by one ring before giving up.
     * @return the number of cells that still violate a constraint.
     */
    fun repairInvalidComponents(maxComponentCells: Int = 64, growAttempts: Int = 2): Int {
        val startedAt = System.nanoTime()
        try {
            val candidates = configuration.possibleCells
            if (candidates.isEmpty()) {
                return 0
            }
            val random = configuration.random
            val invalid = LinkedHashSet<WaveFunctionState<CellType>>()
            collectInvalid(invalid)
            if (invalid.isEmpty()) {
                return 0
            }
            val visited: MutableSet<WaveFunctionState<CellType>> = HashSet()
            for (start in invalid) {
                if (!visited.add(start)) {
                    continue
                }
                var component = collectComponent(start, visited)
                var solved = false
                for (attempt in 0..growAttempts) {
                    if (component.size > maxComponentCells) {
                        break
                    }
                    val snapshot: MutableList<CellType?> = component.map { it.cell }.toMutableList()
                    for (state in component) {
                        state.cell = null
                    }
                    if (assignExact(component, candidates, random, componentNodeBudget(component.size))) {
                        solved = true
                        break
                    }
                    for (index in component.indices) {
                        component[index].cell = snapshot[index]
                    }
                    component = growComponent(component)
                }
                if (!solved) {
                    // Leave it as it was; a later pass (or min-conflicts) may still fix this pocket.
                    continue
                }
            }
            return invalidCellCount()
        } finally {
            diagnostics.repairNanos += System.nanoTime() - startedAt
        }
    }

    /** Breadth-first collection of the 8-connected block of violating cells around [start]. */
    private fun collectComponent(
        start: WaveFunctionState<CellType>,
        visited: MutableSet<WaveFunctionState<CellType>>,
    ): MutableList<WaveFunctionState<CellType>> {
        val component = mutableListOf<WaveFunctionState<CellType>>()
        val queue = java.util.ArrayDeque<WaveFunctionState<CellType>>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            component.add(state)
            for (direction in configuration.directions) {
                val neighbor = grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)
                    ?: continue
                if (neighbor.cell == null) {
                    continue
                }
                if (!visited.add(neighbor)) {
                    continue
                }
                if (neighbor.conflictsNow() > 0) {
                    queue.add(neighbor)
                }
            }
        }
        return component
    }

    /** Adds the ring of cells around [component], so a dead component gets more room to breathe. */
    private fun growComponent(
        component: MutableList<WaveFunctionState<CellType>>,
    ): MutableList<WaveFunctionState<CellType>> {
        val grown = LinkedHashSet(component)
        for (state in component) {
            for (direction in configuration.directions) {
                grid.stateAtOrNull(state.x + direction.dx, state.y + direction.dy)?.let { grown.add(it) }
            }
        }
        return grown.toMutableList()
    }

    /**
     * Depth-first search for a violation-free assignment of [cells], which must already be cleared.
     *
     * Cells are visited most-constrained-first and every cell starts from the candidates that fit the
     * untouched boundary, so the search prunes hard even for components bigger than a 3x3 block.
     *
     * @return true when the whole block could be assigned.
     */
    private fun assignExact(
        cells: List<WaveFunctionState<CellType>>,
        candidates: Array<CellType>,
        random: Random,
        nodeBudget: Int,
    ): Boolean {
        // Options that only have to satisfy the fixed boundary (the block cells are cleared).
        val baseOptions = Array(cells.size) { index ->
            candidates.filter { it.conflictsAt(cells[index]) == 0 }
        }
        // Most constrained first: the cells with the fewest options decide the search early.
        val order = cells.indices.sortedBy { baseOptions[it].size }
        var nodes = 0

        fun search(position: Int): Boolean {
            if (position == order.size) {
                return true
            }
            if (++nodes > nodeBudget) {
                return false
            }
            val cellIndex = order[position]
            val state = cells[cellIndex]
            // Re-check against the cells assigned before this one.
            val options = baseOptions[cellIndex].filter { it.conflictsAt(state) == 0 }
            if (options.isEmpty()) {
                return false
            }
            // Random start: different blocks get different (equally valid) tilings.
            val start = if (options.size == 1) 0 else random.nextInt(options.size)
            for (offset in options.indices) {
                state.cell = options[(start + offset) % options.size]
                if (search(position + 1)) {
                    return true
                }
            }
            state.cell = null
            return false
        }

        return search(0)
    }

    /**
     * The cell that leaves the fewest violations for [state] against its current neighbours.
     * Ties are broken at random and a random cell is taken once in a while, because min-conflicts
     * alone can settle in a local minimum.
     */
    private fun bestReplacement(
        state: WaveFunctionState<CellType>,
        candidates: Array<CellType>,
        random: Random,
    ): CellType {
        var best = state.cell ?: candidates[0]
        var bestConflicts = Int.MAX_VALUE
        var ties = 0
        for (candidate in candidates) {
            val conflicts = candidate.conflictsAt(state)
            if (conflicts < bestConflicts) {
                bestConflicts = conflicts
                best = candidate
                ties = 1
            } else if (conflicts == bestConflicts) {
                ties++
                if (random.nextInt(ties) == 0) {
                    best = candidate
                }
            }
        }
        if (random.nextFloat() < LOCAL_SEARCH_NOISE) {
            return candidates[random.nextInt(candidates.size)]
        }
        return best
    }

    /** Adds every cell that violates a neighbour constraint to [sink] and returns how many. */
    private fun collectInvalid(sink: MutableSet<WaveFunctionState<CellType>>): Int {
        var invalid = 0
        forEachState { state ->
            if (state.conflictsNow() > 0) {
                sink.add(state)
                invalid++
            }
        }
        return invalid
    }

    private inline fun forEachState(block: (WaveFunctionState<CellType>) -> Unit) {
        for (column in 0 until configuration.columnCount) {
            for (row in 0 until configuration.rowCount) {
                block(grid.stateAt(column, row))
            }
        }
    }

    private sealed class Frame<Cell : WaveFunctionCell<Cell>>

    /** One collapse and every cell it banned in its neighbors, so it can be undone exactly. */
    private class CollapseFrame<Cell : WaveFunctionCell<Cell>>(
        val state: WaveFunctionState<Cell>,
        val chosenCell: WaveFunctionState.PossibleCell<Cell>,
    ) : Frame<Cell>() {
        val bannedCells: MutableList<WaveFunctionState.PossibleCell<Cell>> = mutableListOf()
    }

    /** The cells already tried at one state while the search sits at that level. */
    private class RetryFrame<Cell : WaveFunctionCell<Cell>>(
        val state: WaveFunctionState<Cell>,
    ) : Frame<Cell>() {
        val triedCells: MutableList<WaveFunctionState.PossibleCell<Cell>> = mutableListOf()
    }
}
