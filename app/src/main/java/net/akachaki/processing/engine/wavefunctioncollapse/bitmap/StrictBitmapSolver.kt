package net.akachaki.processing.engine.wavefunctioncollapse.bitmap

import net.akachaki.processing.engine.enum.Direction2d
import net.akachaki.processing.engine.enum.Direction2d8
import kotlin.random.Random

/**
 * Strict solver for the overlapping-tile model: AC-3 propagation with backtracking.
 *
 * The generator in [net.akachaki.processing.engine.wavefunctioncollapse.WaveFunctionCollapser] runs on
 * a weaker, one-directional rule because the strict rule makes the greedy search stall (see
 * `docs/wavefunction-collapse.md`). This solver implements the strict rule exactly ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â a kernel may only
 * sit next to a neighbour when both kernels agree in both directions ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â and is meant as the quality pass
 * that ends with **zero** mismatching cells.
 *
 * Domains are bitsets over the tile set, arc consistency is word operations (a tile is supported when
 * `compatible[t][d] & domain[neighbour]` is non-zero), and the search assigns one tile at a time in
 * min-domain order. Every domain change goes through a trail, so backtracking costs O(changes) instead
 * of copying the grid.
 *
 * Status: verified to solve 23x19 up to 46x38 with zero violations. Larger grids (69x120 and up) are
 * known satisfiable but the search currently exhausts ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Â ÃƒÂ¢Ã¢â€šÂ¬Ã¢â€žÂ¢ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã¢â‚¬Â¦Ãƒâ€šÃ‚Â¡ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™Ãƒâ€ Ã¢â‚¬â„¢ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã‚Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã†â€™ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡ÃƒÆ’Ã¢â‚¬Å¡Ãƒâ€šÃ‚Â the backtracking still has a completeness bug,
 * see `docs/wavefunction-collapse.md`.
 *
 * @param cells the tile set (kernels with weights).
 * @param columns grid width.
 * @param rows grid height.
 * @param directions the directions the constraint is checked in (all 8 for the strict rule).
 */
class StrictBitmapSolver(
    private val cells: List<BitmapWaveFunctionCell>,
    private val columns: Int,
    private val rows: Int,
    private val directions: List<Direction2d>,
    private val random: Random,
    /**
     * Cooperative cancellation, checked at the same points as [abortRequested]: when it returns true
     * the search gives up and [solve] returns null. [abortRequested] is a flag the *portfolio* sets
     * when another worker wins; this is the caller's own reason to stop, typically "the user edited
     * the drawing again, so nobody wants the answer to this question any more".
     *
     * Called from the solving thread on a hot-ish path, so it has to be cheap and thread safe (a
     * `CoroutineScope.isActive` read is exactly that).
     */
    private val abortCheck: () -> Boolean = { false },
) {
    private val tileCount = cells.size
    private val wordCount = (tileCount + 63) ushr 6
    private val directionCount = directions.size
    private val variableCount = columns * rows

    /**
     * `compatible[(direction * tileCount + tile) * wordCount + w]`: tiles that fit [tile] in
     * [direction]. Direction-major on purpose: a revision walks the tiles of one direction, so the
     * rows it needs are contiguous (one 6.7 KB block per direction, resident in L1) instead of being
     * 256 bytes apart, which touched a new cache line per tile.
     */
    private val compatible: LongArray = LongArray(tileCount * directionCount * wordCount)

    private fun compatBase(direction: Int, tile: Int): Int =
        (direction * tileCount + tile) * wordCount

    /** Which directions have a neighbour inside the grid, per variable. */
    private val presentMask = IntArray(variableCount)

    /**
     * `presentMask & propagationMask`: the directions [revise] has to enforce for each variable, kept
     * as one mask so the hot loops test a single int.
     *
     * [propagationMask] stays at all eight directions on purpose. With a 3x3 kernel a diagonal pair
     * of cells overlaps in a 2x2 block of pixels, not in the single corner pixel that makes the
     * diagonal constraint redundant, so an orthogonal-only propagation is unsound: measured with
     * `solver=strict strict=true seeds=3 sizes=69x120`, it produced 7,718-15,004 violations.
     */
    private val activeMask = IntArray(variableCount)

    /** Bit [d] set when direction [d] is propagated; all of them (see [activeMask]). */
    private val propagationMask: Int = (1 shl directionCount) - 1

    /** Which directions a kernel has a pixel in, per tile. */
    private val tilePresentMask = IntArray(tileCount)

    /** Opposite direction index, per direction index. */
    private val oppositeIndex = IntArray(directionCount)

    /**
     * Index step that reaches the neighbour in each direction (`dy * columns + dx`). Every neighbour
     * lookup is then `variable + stepOffset[d]` after a single test of [presentMask]: no integer
     * division and no range object in the hottest loop of the solver.
     */
    private val stepOffset = IntArray(directionCount)

    /**
     * The starting domain, indexed by [presentMask] signature (`present * wordCount + word`). Cells
     * with the same set of in-grid neighbours start from the same tiles, so this table replaces
     * `variableCount * tileCount` comparisons with `variableCount` word copies during setup.
     *
     * The signature holds the directions that have a neighbour **inside** the grid, and a tile is in
     * the domain only when it has a pixel in *exactly* those directions: present inside, blank
     * outside. That is what keeps the drawing's edges on the bitmap's edges. A kernel is blank in a
     * direction only where the drawing ends, so a border cell can only use a pattern sampled on the
     * matching edge of the drawing, and that pattern can only sit on that edge: its blank pixel
     * points into the grid, which no cell with a neighbour there accepts.
     *
     * The price is that the drawing's edge must be able to run all the way around the output -- a
     * border sequence that never repeats cannot be stretched across 76 columns -- and a drawing that
     * cannot do it yields no tiling at all. [WfcGenerator] then falls back to the drawing stretched,
     * which keeps the edges on the edges. Accepting blank-or-complete kernels instead (the "crop"
     * rule) makes every border cell satisfiable, but then a drawn edge pixel can land anywhere in the
     * picture and nothing is guaranteed to be on the edge, which is the opposite of what an edge is
     * for.
     */
    private val domainByPresent = LongArray(256 * wordCount)
    private val sizeByPresent = IntArray(256)

    /**
     * Domains in one flat array: word `w` of variable `v` lives at `domains[v * wordCount + w]`.
     *
     * An `Array<LongArray>` costs an object header plus a pointer dereference on every access, and at
     * 1080x1920 it would be 2M separate objects for the collector to scan. Flat storage is 66 MB
     * instead of ~96 MB, has one indirection fewer on the hottest path, and gives the hardware
     * prefetcher a chance when the search walks neighbouring cells.
     */
    private val domains = LongArray(variableCount * wordCount)

    /** First word of [variable] in [domains]. */
    private fun domainBase(variable: Int): Int = variable * wordCount

    /**
     * Undo log. One int per entry packs the variable and the word (`variable shl 4 or word`), so a
     * push is two array writes instead of three; a solve performs millions of them.
     */
    private var trailEntries = IntArray(1024)
    private var trailValues = LongArray(1024)
    private var trailSize = 0

    /**
     * Arc worklist as a power-of-two ring buffer. `ArrayDeque<Int>` would box every arc, and the
     * propagation performs millions of enqueues per solve.
     *
     * Entries are packed as `variable shl 4 or direction`, so decoding an arc is a shift instead of
     * the integer division by [directionCount] this loop used to pay on every one of the 16.8M arcs
     * a 270x480 grid needs. [inQueue] is a bitset rather than a `BooleanArray`: one byte per arc was
     * 16.6 MB at 1080x1920, this is 2 MB.
     */
    private var queueBuffer = IntArray(4096)
    private var queueHead = 0
    private var queueSize = 0
    private val inQueue = LongArray((variableCount * directionCount + 63) ushr 6)

    /**
     * Support table for bit-parallel revision: `supportMask[(d * wordCount + w) * wordCount + k]` is
     * the set of tiles (word [k]) that at least one tile of word [w] supports in direction [d].
     * [verifySupportMasks] checks it against the direct compatibility test.
     */
    private val supportMask = LongArray(directionCount * wordCount * wordCount)

    /** Scratch for the revision query; the solver is single threaded. */
    private val supportScratch = LongArray(wordCount)

    /**
     * Min-domain selection as intrusive doubly-linked lists per domain size (no heap, no boxing).
     * `bucketOf[variable]` is the variable's current domain size; only sizes >= 2 are in a list,
     * because a domain of 1 is an assigned variable and is never selected again.
     */
    private val bucketHead = IntArray(tileCount + 1) { -1 }
    private val bucketNext = IntArray(variableCount) { -1 }
    private val bucketPrev = IntArray(variableCount) { -1 }
    private val bucketOf = IntArray(variableCount)
    private var smallestBucket = 2

    // Phase timers, for deciding what to optimize next. A handful of nanoTime calls per decision.
    var compatNanos: Long = 0
        private set
    var initialPropagationNanos: Long = 0
        private set
    var searchPropagationNanos: Long = 0
        private set
    var selectionNanos: Long = 0
        private set
    var valueOrderingNanos: Long = 0
        private set
    var reviseCount: Long = 0
        private set
    var arcPushes: Long = 0
        private set

    /**
     * Diagnostic: when set, every [revise] recomputes its result with the plain per-tile scan and
     * records the first disagreement in [firstReviseDivergence]. It proves that rewriting the hot
     * loop (precomputed neighbours, singleton fast path, word-wise trail) did not change *which*
     * tiles get removed -- the search trajectory has to stay identical.
     */
    var checkRevise: Boolean = false

    /** Diagnostic: first disagreement found by [checkRevise] (null when every revision agreed). */
    var firstReviseDivergence: String? = null
        private set

    private val beforeWords = LongArray(wordCount)

    private var decisions = 0
    private var deadlineNanos = Long.MAX_VALUE
    private var maxDecisions = Int.MAX_VALUE

    /**
     * Set by another solver that already found a solution (see [StrictSolverPortfolio]); the running
     * search returns null at its next deadline check. Volatile because the reader is a worker thread
     * and the writer is whichever worker won.
     */
    @Volatile
    var abortRequested: Boolean = false

    /**
     * Both reasons to stop early: another worker won, or the caller no longer wants the answer. Every
     * place that used to test [abortRequested] tests this instead.
     */
    private fun aborting(): Boolean = abortRequested || abortCheck()

    /** Where the last failed solve wiped a domain out, for diagnostics (-1 when none). */
    var failureVariable: Int = -1
        private set
    var failureDirection: Int = -1
        private set
    var failedDuringInitialPropagation: Boolean = false
        private set

    /** Diagnostics: branching decisions made by the last solve. */
    var decisionsMade: Int = 0
        private set

    /** Diagnostics: number of domains with a single value at the end of the last solve. */
    var singletonDomains: Int = 0
        private set

    /** Diagnostics: deepest decision stack reached by the last solve. */
    var maxDepthReached: Int = 0
        private set

    init {
        require(directionCount <= ARC_DIRECTION_MASK + 1) {
            "packed arcs hold at most ${ARC_DIRECTION_MASK + 1} directions, got $directionCount"
        }
        require(wordCount <= TRAIL_WORD_MASK + 1) {
            "packed trail entries hold at most ${TRAIL_WORD_MASK + 1} words, got $wordCount"
        }
        for (d in 0 until directionCount) {
            val direction = directions[d]
            val opposite = directions.indexOfFirst { it.dx == -direction.dx && it.dy == -direction.dy }
            oppositeIndex[d] = if (opposite < 0) d else opposite
        }
        for (t in 0 until tileCount) {
            var mask = 0
            for (d in 0 until directionCount) {
                if (cells[t].kernelColor(directions[d] as Direction2d8) != null) {
                    mask = mask or (1 shl d)
                }
            }
            tilePresentMask[t] = mask
        }
        for (d in 0 until directionCount) {
            stepOffset[d] = directions[d].dy * columns + directions[d].dx
        }
        for (p in 0 until 256) {
            val base = p * wordCount
            var count = 0
            for (t in 0 until tileCount) {
                // Exactly the in-grid directions: present inside, blank outside. See [domainByPresent].
                if (tilePresentMask[t] == p) {
                    domainByPresent[base + (t ushr 6)] =
                        domainByPresent[base + (t ushr 6)] or (1L shl (t and 63))
                    count++
                }
            }
            sizeByPresent[p] = count
        }
        val compatStartedAt = System.nanoTime()
        buildCompatibility()
        buildSupportMasks()
        compatNanos = System.nanoTime() - compatStartedAt
    }

    /**
     * Builds [supportMask]. Compatibility is symmetric: the tiles that support `t` in direction `d`
     * are exactly the partners of `t` in the opposite direction.
     */
    private fun buildSupportMasks() {
        for (d in 0 until directionCount) {
            val opposite = oppositeIndex[d]
            for (w in 0 until wordCount) {
                val base = (d * wordCount + w) * wordCount
                for (u in 0 until tileCount) {
                    if ((u ushr 6) != w) {
                        continue
                    }
                    val partners = compatBase(opposite, u)
                    for (k in 0 until wordCount) {
                        supportMask[base + k] = supportMask[base + k] or compatible[partners + k]
                    }
                }
            }
        }
    }

    /**
     * Diagnostic: checks [supportMask] against the direct compatibility test, tile pair by tile pair.
     * @return the number of mismatching (direction, tile, word) triples, 0 when the table is correct.
     */
    fun verifySupportMasks(): Int {
        var mismatches = 0
        for (d in 0 until directionCount) {
            val direction = directions[d] as Direction2d8
            for (w in 0 until wordCount) {
                for (t in 0 until tileCount) {
                    val inMask = (supportMask[(d * wordCount + w) * wordCount + (t ushr 6)] and
                        (1L shl (t and 63))) != 0L
                    var expected = false
                    for (u in 0 until tileCount) {
                        if ((u ushr 6) != w) {
                            continue
                        }
                        val fits = cells[t].kernelColor(direction) != null &&
                            cells[t].kernelColor(direction) == cells[u].color &&
                            cells[u].kernelColor(direction.opposite()) == cells[t].color
                        if (fits) {
                            expected = true
                            break
                        }
                    }
                    if (inMask != expected) {
                        mismatches++
                    }
                }
            }
        }
        return mismatches
    }


    /**
     * Solves the grid.
     *
     * @param deadlineNanos absolute [System.nanoTime] deadline; the search returns null when it passes.
     * @param maxDecisions cap on branching decisions, so a pathological instance cannot run forever.
     * @return a tile index per variable (`y * columns + x`), or null when no solution was found in time.
     */
    fun solve(deadlineNanos: Long = Long.MAX_VALUE, maxDecisions: Int = 2_000_000): IntArray? {
        this.deadlineNanos = deadlineNanos
        this.maxDecisions = maxDecisions
        abortRequested = false
        decisions = 0
        trailSize = 0
        maxDepthReached = 0
        failureVariable = -1
        failureDirection = -1
        failedDuringInitialPropagation = false
        queueClear()
        java.util.Arrays.fill(inQueue, 0L)
        initialiseDomains()
        enqueueAll()
        val propagationStartedAt = System.nanoTime()
        val consistent = ac3()
        initialPropagationNanos = System.nanoTime() - propagationStartedAt
        if (!consistent) {
            failedDuringInitialPropagation = failureVariable >= 0
            return null
        }
        val result = search()
        decisionsMade = decisions
        singletonDomains = (0 until variableCount).count { popCount(it) == 1 }
        return result
    }

    // --- setup ------------------------------------------------------------------------------------

    /**
     * Diagnostic entry point: runs only the root arc consistency and returns whether it succeeded.
     * Used to check that a known solution is not removed by propagation.
     */
    fun propagateRoot(): Boolean {
        deadlineNanos = Long.MAX_VALUE
        trailSize = 0
        queueClear()
        java.util.Arrays.fill(inQueue, 0L)
        initialiseDomains()
        enqueueAll()
        return ac3()
    }

    /**
     * Diagnostic: compares the bit-parallel revision against the per-tile reference for every
     * (variable, direction, word) of the current domains and returns the first disagreement.
     * Run it after [propagateRoot] so the domains are restricted and non-trivial.
     */
    fun findRevisionDivergence(): String? {
        for (variable in 0 until variableCount) {
            val x = variable % columns
            val y = variable / columns
            for (d in 0 until directionCount) {
                val direction = directions[d]
                val nx = x + direction.dx
                val ny = y + direction.dy
                if (nx !in 0 until columns || ny !in 0 until rows) {
                    continue
                }
                val neighborBase = (ny * columns + nx) * wordCount
                java.util.Arrays.fill(supportScratch, 0L)
                for (w in 0 until wordCount) {
                    if (domains[neighborBase + w] == 0L) {
                        continue
                    }
                    val base = (d * wordCount + w) * wordCount
                    for (k in 0 until wordCount) {
                        supportScratch[k] = supportScratch[k] or supportMask[base + k]
                    }
                }
                val vBase = domainBase(variable)
                for (k in 0 until wordCount) {
                    val maskUnsupported = domains[vBase + k] and supportScratch[k].inv()
                    var referenceUnsupported = 0L
                    var bits = domains[vBase + k]
                    while (bits != 0L) {
                        val t = (k shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                        bits = bits and (bits - 1)
                        val base = compatBase(d, t)
                        var supported = false
                        for (w2 in 0 until wordCount) {
                            if ((compatible[base + w2] and domains[neighborBase + w2]) != 0L) {
                                supported = true
                                break
                            }
                        }
                        if (!supported) {
                            referenceUnsupported = referenceUnsupported or (1L shl (t and 63))
                        }
                    }
                    if (maskUnsupported != referenceUnsupported) {
                        return "variable=$variable direction=$d word=$k " +
                            "mask=$maskUnsupported reference=$referenceUnsupported"
                    }
                }
            }
        }
        return null
    }
    /**
     * Lock-step check: runs the initial propagation and, for every arc, compares the mask-based
     * unsupported set against the per-tile reference *before* the revision is applied. Returns the
     * first disagreement with its inputs, or null when the two agree at every step.
     */
    fun compareRevisionsDuringPropagation(): String? {
        deadlineNanos = Long.MAX_VALUE
        trailSize = 0
        queueClear()
        java.util.Arrays.fill(inQueue, 0L)
        initialiseDomains()
        enqueueAll()
        while (queueSize > 0) {
            val packed = queuePop()
            val variable = packed ushr ARC_DIRECTION_BITS
            val direction = packed and ARC_DIRECTION_MASK
            markQueueOut(variable, direction)
            val x = variable % columns
            val y = variable / columns
            val dx = directions[direction].dx
            val dy = directions[direction].dy
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until columns && ny in 0 until rows) {
                val neighborBase = (ny * columns + nx) * wordCount
                java.util.Arrays.fill(supportScratch, 0L)
                for (w in 0 until wordCount) {
                    if (domains[neighborBase + w] == 0L) {
                        continue
                    }
                    val base = (direction * wordCount + w) * wordCount
                    for (k in 0 until wordCount) {
                        supportScratch[k] = supportScratch[k] or supportMask[base + k]
                    }
                }
                val vBase = domainBase(variable)
                for (k in 0 until wordCount) {
                    val maskUnsupported = domains[vBase + k] and supportScratch[k].inv()
                    var referenceUnsupported = 0L
                    var bits = domains[vBase + k]
                    while (bits != 0L) {
                        val t = (k shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                        bits = bits and (bits - 1)
                        val base = compatBase(direction, t)
                        var supported = false
                        for (w2 in 0 until wordCount) {
                            if ((compatible[base + w2] and domains[neighborBase + w2]) != 0L) {
                                supported = true
                                break
                            }
                        }
                        if (!supported) {
                            referenceUnsupported = referenceUnsupported or (1L shl (t and 63))
                        }
                    }
                    if (maskUnsupported != referenceUnsupported) {
                        return "variable=$variable direction=$direction word=$k " +
                            "mask=$maskUnsupported reference=$referenceUnsupported " +
                            "neighborDomain=${
                                java.util.Arrays.copyOfRange(
                                    domains, neighborBase, neighborBase + wordCount,
                                ).contentToString()
                            } " +
                            "domain=${
                                java.util.Arrays.copyOfRange(
                                    domains, vBase, vBase + wordCount,
                                ).contentToString()
                            }"
                    }
                }
            }
            if (!revise(variable, direction)) {
                return "propagation contradiction at variable=$variable direction=$direction"
            }
        }
        return null
    }
    /** Diagnostic: is [tile] still in the domain of [variable]? */
    fun isValueAlive(variable: Int, tile: Int): Boolean {
        val base = domainBase(variable)
        return (domains[base + (tile ushr 6)] and (1L shl (tile and 63))) != 0L
    }

    /** Diagnostic: domain size of [variable]. */
    fun domainSize(variable: Int): Int = popCount(variable)

    /**
     * Diagnostic entry point: fixes the first [hintPrefix] variables to the values of [hint] (a known
     * solution) and searches for the rest. Separates "the search is incomplete" from "propagation
     * rejects a valid partial assignment".
     */
    fun solveWithHint(hint: IntArray, hintPrefix: Int): IntArray? {
        deadlineNanos = Long.MAX_VALUE
        maxDecisions = Int.MAX_VALUE
        decisions = 0
        trailSize = 0
        maxDepthReached = 0
        hintFailedAt = -1
        hintRootFailed = false
        queueClear()
        java.util.Arrays.fill(inQueue, 0L)
        initialiseDomains()
        enqueueAll()
        if (!ac3()) {
            hintRootFailed = true
            return null
        }
        for (variable in 0 until hintPrefix) {
            assign(variable, hint[variable])
            if (!ac3()) {
                hintFailedAt = variable
                return null
            }
        }
        debugHint = hint
        firstSolutionValueMissingAt = -1
        return search()
    }

    /** Diagnostics for [solveWithHint]: the prefix index whose propagation failed (-1 when none). */
    var hintFailedAt: Int = -1
        private set
    var hintRootFailed: Boolean = false
        private set

    /** Diagnostic: first variable whose domain no longer contains the hinted solution value. */
    var firstSolutionValueMissingAt: Int = -1
        private set

    /** Debug hook: when set, the search verifies the hinted solution value is still available. */
    private var debugHint: IntArray? = null

    private fun buildCompatibility() {
        for (t in 0 until tileCount) {
            for (d in 0 until directionCount) {
                val base = compatBase(d, t)
                val direction = directions[d] as Direction2d8
                for (u in 0 until tileCount) {
                    val fits = cells[t].kernelColor(direction) != null &&
                        cells[t].kernelColor(direction) == cells[u].color &&
                        cells[u].kernelColor(direction.opposite()) == cells[t].color
                    if (fits) {
                        compatible[base + (u ushr 6)] = compatible[base + (u ushr 6)] or (1L shl (u and 63))
                    }
                }
            }
        }
    }

    private fun initialiseDomains() {
        java.util.Arrays.fill(bucketHead, -1)
        java.util.Arrays.fill(bucketNext, -1)
        java.util.Arrays.fill(bucketPrev, -1)
        java.util.Arrays.fill(bucketOf, 0)
        smallestBucket = tileCount + 1
        var variable = 0
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                var present = 0
                for (d in 0 until directionCount) {
                    val direction = directions[d]
                    val nx = x + direction.dx
                    val ny = y + direction.dy
                    if (nx >= 0 && nx < columns && ny >= 0 && ny < rows) {
                        present = present or (1 shl d)
                    }
                }
                presentMask[variable] = present
                activeMask[variable] = present and propagationMask
                val vBase = domainBase(variable)
                val base = present * wordCount
                for (w in 0 until wordCount) {
                    domains[vBase + w] = domainByPresent[base + w]
                }
                val size = sizeByPresent[present]
                bucketOf[variable] = size
                if (size >= 2) {
                    bucketInsert(variable, size)
                }
                variable++
            }
        }
        if (smallestBucket > tileCount) {
            smallestBucket = 2
        }
    }

    // --- min-domain buckets -----------------------------------------------------------------------

    private fun bucketInsert(variable: Int, size: Int) {
        bucketPrev[variable] = -1
        bucketNext[variable] = bucketHead[size]
        if (bucketHead[size] >= 0) {
            bucketPrev[bucketHead[size]] = variable
        }
        bucketHead[size] = variable
        if (size < smallestBucket) {
            smallestBucket = size
        }
    }

    private fun bucketRemove(variable: Int, size: Int) {
        val previous = bucketPrev[variable]
        val next = bucketNext[variable]
        if (previous >= 0) {
            bucketNext[previous] = next
        } else {
            bucketHead[size] = next
        }
        if (next >= 0) {
            bucketPrev[next] = previous
        }
        bucketPrev[variable] = -1
        bucketNext[variable] = -1
    }

    /** Moves [variable] to the list for [newSize]; sizes below 2 are not listed (assigned). */
    private fun bucketMove(variable: Int, newSize: Int) {
        val old = bucketOf[variable]
        if (old == newSize) {
            return
        }
        if (old >= 2) {
            bucketRemove(variable, old)
        }
        bucketOf[variable] = newSize
        if (newSize >= 2) {
            bucketInsert(variable, newSize)
        }
    }

    // --- propagation ------------------------------------------------------------------------------

    private fun enqueueAll() {
        for (variable in 0 until variableCount) {
            val active = activeMask[variable]
            var d = 0
            while (d < directionCount) {
                if (((active ushr d) and 1) != 0) {
                    enqueue(variable, d)
                }
                d++
            }
        }
    }

    /**
     * Seeds the AC-3 worklist with only the arcs that can do anything: those touching a restricted
     * domain. Every interior cell starts with the full tile set, and the tile set is arc-consistent
     * (see `BitmapWaveFunctionCell.pruneUnsupported`), so interior-to-interior arcs are no-ops --
     * enqueueing all of them just to scan 200 tiles each was pure waste.
     */
    private fun enqueueRestrictedArcs() {
        for (variable in 0 until variableCount) {
            if (bucketOf[variable] == tileCount) {
                continue
            }
            val active = activeMask[variable]
            var d = 0
            while (d < directionCount) {
                if (((active ushr d) and 1) != 0) {
                    enqueue(variable, d)
                    enqueue(variable + stepOffset[d], oppositeIndex[d])
                }
                d++
            }
        }
    }

    private fun enqueue(variable: Int, direction: Int) {
        val index = variable * directionCount + direction
        val word = index ushr 6
        val mask = 1L shl (index and 63)
        if ((inQueue[word] and mask) == 0L) {
            inQueue[word] = inQueue[word] or mask
            arcPushes++
            queuePush((variable shl ARC_DIRECTION_BITS) or direction)
        }
    }

    private fun markQueueOut(variable: Int, direction: Int) {
        val index = variable * directionCount + direction
        inQueue[index ushr 6] = inQueue[index ushr 6] and (1L shl (index and 63)).inv()
    }

    private fun queuePush(value: Int) {
        if (queueSize == queueBuffer.size) {
            val grown = IntArray(queueBuffer.size * 2)
            for (i in 0 until queueSize) {
                grown[i] = queueBuffer[(queueHead + i) and (queueBuffer.size - 1)]
            }
            queueBuffer = grown
            queueHead = 0
        }
        queueBuffer[(queueHead + queueSize) and (queueBuffer.size - 1)] = value
        queueSize++
    }

    /**
     * Pops the oldest arc: the worklist stays breadth-first. A depth-first pop was measured and is
     * much worse (`seeds=3 sizes=69x120`: 217 ms -> 666 ms, 308k -> 1.97M revisions, and 135x240
     * stopped completing), because spreading the reductions evenly prunes whole neighbourhoods at
     * once while a depth-first cascade keeps re-revising the same fringe.
     */
    private fun queuePop(): Int {
        val value = queueBuffer[queueHead]
        queueHead = (queueHead + 1) and (queueBuffer.size - 1)
        queueSize--
        return value
    }

    private fun queueClear() {
        queueHead = 0
        queueSize = 0
    }

    private fun ac3(): Boolean {
        var sinceClockCheck = 0
        while (queueSize > 0) {
            // The clock is a syscall-ish cost; checking it per arc used to dominate short revises.
            if (++sinceClockCheck >= 512) {
                sinceClockCheck = 0
                if (aborting() || System.nanoTime() > deadlineNanos) {
                    return false
                }
            }
            val packed = queuePop()
            val variable = packed ushr ARC_DIRECTION_BITS
            val direction = packed and ARC_DIRECTION_MASK
            markQueueOut(variable, direction)
            reviseCount++
            if (!revise(variable, direction)) {
                return false
            }
        }
        return true
    }

    /** Diagnostic: the compatibility predicate straight from the kernels, independent of any table. */
    private fun fitsDirect(t: Int, u: Int, directionIndex: Int): Boolean {
        val direction = directions[directionIndex] as Direction2d8
        return cells[t].kernelColor(direction) != null &&
            cells[t].kernelColor(direction) == cells[u].color &&
            cells[u].kernelColor(direction.opposite()) == cells[t].color
    }

    /** Reference support test, one tile at a time: the definition [revise] has to reproduce. */
    private fun referenceSupported(t: Int, direction: Int, neighborBase: Int): Boolean {
        val base = compatBase(direction, t)
        var k = 0
        while (k < wordCount) {
            if ((compatible[base + k] and domains[neighborBase + k]) != 0L) {
                return true
            }
            k++
        }
        return false
    }

    /** @return the only tile in the domain at [base], which must hold exactly one bit. */
    private fun singleValue(base: Int): Int {
        var w = 0
        while (w < wordCount) {
            val bits = domains[base + w]
            if (bits != 0L) {
                return (w shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
            }
            w++
        }
        return -1
    }

    /** Drops the tiles of [variable] that have no support in its neighbour in [direction]. */
    private fun revise(variable: Int, direction: Int): Boolean {
        if (((activeMask[variable] ushr direction) and 1) == 0) {
            return true
        }
        val neighbor = variable + stepOffset[direction]
        val neighborBase = domainBase(neighbor)
        val vBase = domainBase(variable)
        if (checkRevise) {
            for (w in 0 until wordCount) {
                beforeWords[w] = domains[vBase + w]
            }
        }
        var removed = 0
        var unitTile = -1
        if (bucketOf[neighbor] == 1) {
            // The neighbour is assigned, so exactly one tile supports us and the revision is a
            // single mask intersection with its partner row: compatibility is symmetric, so the
            // tiles that [unit] supports in the opposite direction support it in this one.
            val unit = singleValue(neighborBase)
            unitTile = unit
            val base = compatBase(oppositeIndex[direction], unit)
            for (w in 0 until wordCount) {
                val current = domains[vBase + w]
                if (current == 0L) {
                    continue
                }
                val kept = current and compatible[base + w]
                if (kept != current) {
                    pushTrail(variable, w)
                    domains[vBase + w] = kept
                    removed += java.lang.Long.bitCount(current) - java.lang.Long.bitCount(kept)
                }
            }
        } else {
            // Hoist the neighbour's words into locals: the support test reads them once per tile
            // otherwise, and the neighbour domain does not change during one revision.
            val n0 = domains[neighborBase]
            val n1 = if (wordCount > 1) domains[neighborBase + 1] else 0L
            val n2 = if (wordCount > 2) domains[neighborBase + 2] else 0L
            val n3 = if (wordCount > 3) domains[neighborBase + 3] else 0L
            val fourWords = wordCount == 4
            for (w in 0 until wordCount) {
                var bits = domains[vBase + w]
                if (bits == 0L) {
                    continue
                }
                var remaining = bits
                val wordBits = w shl 6
                while (bits != 0L) {
                    val t = wordBits + java.lang.Long.numberOfTrailingZeros(bits)
                    bits = bits and (bits - 1)
                    val base = compatBase(direction, t)
                    val supported = if (fourWords) {
                        ((compatible[base] and n0) or (compatible[base + 1] and n1) or
                            (compatible[base + 2] and n2) or (compatible[base + 3] and n3)) != 0L
                    } else {
                        var k = 0
                        var hit = false
                        while (k < wordCount) {
                            if ((compatible[base + k] and domains[neighborBase + k]) != 0L) {
                                hit = true
                                break
                            }
                            k++
                        }
                        hit
                    }
                    if (!supported) {
                        remaining = remaining and (1L shl (t and 63)).inv()
                        removed++
                    }
                }
                if (remaining != domains[vBase + w]) {
                    pushTrail(variable, w)
                    domains[vBase + w] = remaining
                }
            }
        }
        if (checkRevise && firstReviseDivergence == null) {
            for (w in 0 until wordCount) {
                var expected = beforeWords[w]
                var bits = expected
                while (bits != 0L) {
                    val t = (w shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                    bits = bits and (bits - 1)
                    if (!referenceSupported(t, direction, neighborBase)) {
                        expected = expected and (1L shl (t and 63)).inv()
                    }
                }
                if (expected != domains[vBase + w]) {
                    val tile = (w shl 6) + java.lang.Long.numberOfTrailingZeros(beforeWords[w] and expected.inv())
                    firstReviseDivergence = "variable=$variable direction=$direction word=$w " +
                        "fast=${domains[vBase + w]} reference=$expected before=${beforeWords[w]} " +
                        "neighborSize=${bucketOf[neighbor]} unit=$unitTile tile=$tile " +
                        "neighborDomain=${
                            java.util.Arrays.copyOfRange(
                                domains, neighborBase, neighborBase + wordCount,
                            ).contentToString()
                        } " +
                        "fitsTileUnit=${
                            if (unitTile >= 0) fitsDirect(tile, unitTile, direction) else "n/a"
                        } " +
                        "fitsUnitTile=${
                            if (unitTile >= 0) {
                                fitsDirect(unitTile, tile, oppositeIndex[direction])
                            } else {
                                "n/a"
                            }
                        }"
                    break
                }
            }
        }
        if (removed == 0) {
            return true
        }
        bucketMove(variable, bucketOf[variable] - removed)
        // This domain shrank, so every neighbour that constrains it has to be revised again. Note
        // that the neighbour we just revised against is included: its own domain may now lose the
        // tiles that only this cell supported.
        var d2 = 0
        val active = activeMask[variable]
        while (d2 < directionCount) {
            if (((active ushr d2) and 1) != 0) {
                enqueue(variable + stepOffset[d2], oppositeIndex[d2])
            }
            d2++
        }
        if (bucketOf[variable] == 0) {
            failureVariable = variable
            failureDirection = direction
            return false
        }
        return true
    }

    private fun pushTrail(variable: Int, word: Int) {
        if (trailSize == trailEntries.size) {
            val newSize = trailSize * 2
            trailEntries = trailEntries.copyOf(newSize)
            trailValues = trailValues.copyOf(newSize)
        }
        trailEntries[trailSize] = (variable shl TRAIL_WORD_BITS) or word
        trailValues[trailSize] = domains[domainBase(variable) + word]
        trailSize++
    }

    private fun undo(mark: Int) {
        while (trailSize > mark) {
            trailSize--
            val entry = trailEntries[trailSize]
            val variable = entry ushr TRAIL_WORD_BITS
            val word = entry and TRAIL_WORD_MASK
            val old = trailValues[trailSize]
            val index = domainBase(variable) + word
            val current = domains[index]
            if (old != current) {
                val delta = java.lang.Long.bitCount(old) - java.lang.Long.bitCount(current)
                if (delta != 0) {
                    bucketMove(variable, bucketOf[variable] + delta)
                }
                domains[index] = old
            }
        }
    }

    // --- search -----------------------------------------------------------------------------------

    private class Frame(val variable: Int, val values: IntArray, var next: Int, val trailMark: Int)

    private fun search(): IntArray? {
        val stack = ArrayList<Frame>()
        while (true) {
            if (aborting() || System.nanoTime() > deadlineNanos || decisions > maxDecisions) {
                return null
            }
            val selectionStartedAt = System.nanoTime()
            val variable = selectVariable()
            selectionNanos += System.nanoTime() - selectionStartedAt
            if (variable < 0) {
                debugHint = null
                return extractSolution()
            }
            debugHint?.let { hint ->
                if (firstSolutionValueMissingAt < 0 && !isValueAlive(variable, hint[variable])) {
                    // Recorded once: either the search diverged from the hint (expected) or
                    // propagation dropped a value of a verified solution (a real bug).
                    firstSolutionValueMissingAt = variable
                }
            }
            val orderingStartedAt = System.nanoTime()
            val values = orderedValues(variable)
            valueOrderingNanos += System.nanoTime() - orderingStartedAt
            val frame = Frame(variable, values, 0, trailSize)
            stack.add(frame)
            if (stack.size > maxDepthReached) {
                maxDepthReached = stack.size
            }
            var descended = false
            while (stack.isNotEmpty()) {
                val current = stack.last()
                if (current.next >= current.values.size) {
                    undo(current.trailMark)
                    stack.removeAt(stack.lastIndex)
                    continue
                }
                val tile = current.values[current.next++]
                decisions++
                // Rewind to the state this level started from before trying the value. A previous
                // value at this level may have succeeded and been descended into; its assignment and
                // everything propagation derived from it must be gone, otherwise every retry is
                // judged against a stale, over-constrained grid.
                val mark = current.trailMark
                undo(mark)
                assign(current.variable, tile)
                val propagationStartedAt = System.nanoTime()
                val consistent = ac3()
                searchPropagationNanos += System.nanoTime() - propagationStartedAt
                if (consistent) {
                    descended = true
                    break
                }
                undo(mark)
            }
            if (!descended && stack.isEmpty()) {
                return null
            }
        }
    }

    /** Restricts [variable] to [tile], recording every word on the trail, and queues its neighbours. */
    private fun assign(variable: Int, tile: Int) {
        val vBase = domainBase(variable)
        for (w in 0 until wordCount) {
            if (domains[vBase + w] != 0L) {
                pushTrail(variable, w)
            }
            domains[vBase + w] = 0L
        }
        domains[vBase + (tile ushr 6)] = 1L shl (tile and 63)
        bucketMove(variable, 1)
        // Propagation has to start from the assignment: revise every neighbour against it.
        val active = activeMask[variable]
        var d = 0
        while (d < directionCount) {
            if (((active ushr d) and 1) != 0) {
                enqueue(variable + stepOffset[d], oppositeIndex[d])
            }
            d++
        }
    }

    /**
     * @return the unassigned variable with the smallest domain, or -1 when everything is assigned.
     *
     * Domain sizes are kept exact on every change ([bucketMove]), and a lazy variant that only
     * repaired stale list nodes at selection time was measured and is *not* an option: it degraded
     * branching badly (135x240 went from 0.7 s to >120 s and the 2M decision cap on all three seeds,
     * one 69x120 seed needed 577M revisions). Min-domain accuracy is load-bearing here.
     */
    private fun selectVariable(): Int {
        if (smallestBucket < 2) {
            smallestBucket = 2
        }
        while (smallestBucket <= tileCount) {
            val head = bucketHead[smallestBucket]
            if (head >= 0) {
                return head
            }
            smallestBucket++
        }
        return -1
    }

    /**
     * When set, candidate tiles are ordered by how many options they leave their neighbours
     * ("least constraining value"), with the weighted order only breaking ties.
     *
     * **Off by default: measured worse.** At 135x240 it raised decisions from 11,518 to 30,555 and
     * the solve from 0.69 s to 1.20 s, and it made every seed take the same path (identical decision
     * counts). The weighted sampling in [orderedValues] is not just an aesthetic choice either: it
     * decides whether the output keeps the drawing's rare motifs or floods with its dominant colour.
     */
    var supportOrdering: Boolean = false

    /**
     * The tiles of [variable] in the order the search tries them.
     *
     * The default is weighted sampling *without* replacement: each tile gets `random^(1/weight)` and
     * the largest key is tried first (Efraimidis-Spirakis), so a tile is picked first with
     * probability `weight / totalWeight` and the remaining order is the same rule over what is left.
     * That is what makes the output look like the drawing: a tile that occurs ten times as often is
     * ten times as likely to be chosen *at each cell*, and the picture still gets its rare motifs.
     *
     * This is deliberate and load-bearing. The obvious variant -- the key `weight * random`, also
     * sorted descending -- is not weighted sampling: it is a race between the largest weights, so the
     * drawing's dominant (usually background) tile wins at nearly every cell and the result floods
     * with one colour. With a 100:5 weight ratio the rare tile came first 0.1% of the time instead of
     * the 5% it is entitled to, and a 16x12 doodle generated a bitmap that was one flat background.
     */
    private fun orderedValues(variable: Int): IntArray {
        val vBase = domainBase(variable)
        val expected = popCount(variable)
        val values = IntArray(expected)
        var count = 0
        for (w in 0 until wordCount) {
            var bits = domains[vBase + w]
            val wordBits = w shl 6
            while (bits != 0L) {
                values[count++] = wordBits + java.lang.Long.numberOfTrailingZeros(bits)
                bits = bits and (bits - 1)
            }
        }
        check(count == expected) { "bit iteration lost values: $count != $expected" }
        val keys = DoubleArray(count)
        if (supportOrdering) {
            // Least constraining value first, with the weighted order only breaking ties.
            for (i in 0 until count) {
                val tile = values[i]
                keys[i] = supportScore(tile, variable) * SCORE_SCALE +
                    Math.pow(random.nextDouble(), 1.0 / cells[tile].weight)
            }
        } else {
            for (i in 0 until count) {
                // A weight of 0 would make the exponent infinite; counts below one cannot occur, but
                // clamp anyway so a hand-built tile set cannot produce NaN keys and a broken order.
                val weight = cells[values[i]].weight.coerceAtLeast(1e-6f)
                keys[i] = Math.pow(random.nextDouble(), 1.0 / weight)
            }
        }
        for (i in 1 until count) {
            val key = keys[i]
            val value = values[i]
            var j = i - 1
            while (j >= 0 && keys[j] < key) {
                keys[j + 1] = keys[j]
                values[j + 1] = values[j]
                j--
            }
            keys[j + 1] = key
            values[j + 1] = value
        }
        return values
    }

    /**
     * How many values the neighbours of [variable] would keep if [tile] were chosen: for each active
     * direction, the number of compatible tiles still present next door. Higher is less constraining.
     */
    private fun supportScore(tile: Int, variable: Int): Int {
        val active = activeMask[variable]
        var score = 0
        var d = 0
        while (d < directionCount) {
            if (((active ushr d) and 1) != 0) {
                val neighborBase = domainBase(variable + stepOffset[d])
                val base = compatBase(d, tile)
                var k = 0
                while (k < wordCount) {
                    score += java.lang.Long.bitCount(compatible[base + k] and domains[neighborBase + k])
                    k++
                }
            }
            d++
        }
        return score
    }

    private fun extractSolution(): IntArray? {
        val result = IntArray(variableCount)
        for (variable in 0 until variableCount) {
            if (popCount(variable) != 1) {
                return null
            }
            val vBase = domainBase(variable)
            var tile = -1
            for (w in 0 until wordCount) {
                val bits = domains[vBase + w]
                if (bits != 0L) {
                    tile = (w shl 6) + java.lang.Long.numberOfTrailingZeros(bits)
                    break
                }
            }
            if (tile < 0) {
                return null
            }
            result[variable] = tile
        }
        return result
    }

    // --- bit helpers ------------------------------------------------------------------------------

    private companion object {
        /** Direction bits in a packed arc; see [queueBuffer]. */
        const val ARC_DIRECTION_BITS = 4
        const val ARC_DIRECTION_MASK = (1 shl ARC_DIRECTION_BITS) - 1

        /** Word bits in a packed trail entry; see [trailEntries]. */
        const val TRAIL_WORD_BITS = 4
        const val TRAIL_WORD_MASK = (1 shl TRAIL_WORD_BITS) - 1

        /** Keeps a support score strictly above the [0, 1) weighted tie-break that follows it. */
        const val SCORE_SCALE = 1024.0
    }

    private fun popCount(variable: Int): Int {
        val vBase = domainBase(variable)
        var count = 0
        for (w in 0 until wordCount) {
            count += java.lang.Long.bitCount(domains[vBase + w])
        }
        return count
    }
}
