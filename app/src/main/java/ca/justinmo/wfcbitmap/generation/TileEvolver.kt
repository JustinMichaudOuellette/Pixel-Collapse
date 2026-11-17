package ca.justinmo.wfcbitmap.generation

/**
 * Local Evolve: re-solves a small patch of cells while every cell outside the patch is pinned to its
 * current pattern.
 *
 * The patch is a connected blob of [cellsPerFrame] cells grown from a random start cell. Only its cells
 * are free, so the rest of the picture is untouched and the changed pixels stay local. This is the
 * visible counterpart of a single-cell resample: one cell usually has no centre-changing alternative,
 * but a small free patch does.
 *
 * The native model is built once; [close] must be called.
 */
internal class TileEvolver(
  output: WfcGenerator.Output,
  private val cellsPerFrame: Int,
) : AutoCloseable {

  private val columns = output.columns
  private val rows = output.rows
  private val cellCount = columns * rows
  private val model: Long
  private val assignment: IntArray
  private val patterns: IntArray
  private val sources: IntArray
  private val centres: IntArray
  private val current = output.cellCenters?.copyOf()
    ?: IntArray(columns * rows)
  private var closed = false

  /** How many of the free cells the last [nextFrame] actually changed to a different tile. */
  internal var lastChanged: Int = 0
    private set

  /**
   * Every cell the forward pass changed, oldest first.
   *
   * One entry per changed cell: the cell index in the high 32 bits and the centre it replaced in the low
   * 32. Reverting these newest-first walks the picture back to where it started, which is how an evolve
   * clip loops -- its second pass takes the changes back instead of replaying frames, and arrives at the
   * starting picture exactly rather than approximately.
   */
  private val changes = ArrayList<Long>()

  /** How many cell changes the forward pass has made, i.e. how many a second pass can take back. */
  internal val changeCount: Int
    get() = changes.size

  /**
   * A copy of the recorded changes, oldest first: the cell index in the high 32 bits and the centre it
   * replaced in the low 32.
   *
   * The preview keeps one clip's worth of undo frames without holding a picture per frame by recording
   * this log and rebuilding each undo picture from it, exactly as [pictureAfterUndoing] does.
   */
  internal fun changesSnapshot(): LongArray = LongArray(changes.size) { changes[it] }

  init {
    val packed = requireNotNull(output.packedTiles) { "the solve has no tile set to evolve" }
    val initialPatterns = requireNotNull(output.cellPatterns) { "the solve has no patterns to evolve" }
    val initialAssignment = requireNotNull(output.assignment) { "the solve has no assignment to evolve" }
    requireNotNull(output.cellCenters) { "the solve has no cell colours to evolve" }
    require(initialPatterns.size == cellCount) { "the patterns do not match the grid" }
    require(initialAssignment.size == cellCount) { "the assignment does not match the grid" }
    require(WfcNative.available) { "the native solver is not available" }
    model = WfcNative.modelCreate(
      packed,
      packed.size / WfcNative.PACKED_TILE_INTS,
      columns,
      rows,
      relaxedEdges = output.modelRelaxedEdges,
    )
    check(model != 0L) { "the tile set could not be rebuilt" }
    patterns = initialPatterns.copyOf()
    assignment = initialAssignment.copyOf()
    val tileCount = WfcNative.modelTileCount(model)
    sources = IntArray(tileCount) { WfcNative.modelTileSource(model, it) }
    centres = IntArray(tileCount) { WfcNative.modelTileCenter(model, it) }
  }

  /**
   * Frees a connected blob of [cellsPerFrame] cells around a random start cell, pins everything else,
   * and re-solves. The assignment and patterns are updated cumulatively.
   */
  fun nextFrame(seed: Int): IntArray {
    check(!closed) { "the evolver is closed" }
    val random = java.util.Random(seed.toLong())
    val wanted = cellsPerFrame.coerceIn(1, cellCount)
    /*
     * A patch can re-solve to exactly the picture that is already there. Try a few different patches
     * before accepting a duplicate frame; the export promises that each frame changes at least one
     * pixel.
     */
    repeat(FRAME_ATTEMPTS) { attempt ->
      val free = connectedBlob(random.nextInt(cellCount), wanted, random)
      val fixed = patterns.copyOf()
      for (cell in free) fixed[cell] = Int.MIN_VALUE
      val solver = NativeTileSolver(
        model = model,
        columns = columns,
        rows = rows,
        seed = seed + attempt * 7919,
        fixedPatterns = fixed,
      )
      val solved = try {
        solver.solve(
          deadlineNanos = System.nanoTime() + 20_000_000_000L,
          maxDecisions = 1_000_000,
        )
      } finally {
        solver.close()
      } ?: return@repeat

      var visibleChanged = 0
      val nextAssignment = IntArray(free.size)
      val nextPatterns = IntArray(free.size)
      val nextColours = IntArray(free.size)
      for ((index, cell) in free.withIndex()) {
        val tile = solved[cell]
        nextAssignment[index] = tile
        nextPatterns[index] = sources[tile]
        nextColours[index] = centres[tile]
        if (nextColours[index] != current[cell]) {
          // Remember what this cell looked like before it changes, so the undo pass can put it back.
          changes.add((cell.toLong() shl 32) or (current[cell].toLong() and 0xFFFF_FFFFL))
          visibleChanged++
        }
      }
      if (visibleChanged == 0) return@repeat
      for ((index, cell) in free.withIndex()) {
        assignment[cell] = nextAssignment[index]
        patterns[cell] = nextPatterns[index]
        current[cell] = nextColours[index]
      }
      lastChanged = visibleChanged
      return current.copyOf()
    }
    lastChanged = 0
    return current.copyOf()
  }

  /**
   * The picture with the newest [count] recorded changes reverted, newest first.
   *
   * With [changeCount] reverted this is the picture the evolver started from, so an export's second pass
   * ends exactly where its first pass began. This reads the recorded data only -- no solve and no native
   * model -- so it is valid after [close] too, which is when the export actually asks for these frames.
   */
  fun pictureAfterUndoing(count: Int): IntArray {
    val reverted = count.coerceIn(0, changes.size)
    val picture = current.copyOf()
    for (index in 0 until reverted) {
      val change = changes[changes.size - 1 - index]
      picture[(change ushr 32).toInt()] = change.toInt()
    }
    return picture
  }

  /** Grows a connected set of [wanted] cells from [start], four-connected, in random order. */
  private fun connectedBlob(start: Int, wanted: Int, random: java.util.Random): IntArray {
    if (wanted >= cellCount) return IntArray(cellCount) { it }
    val seen = BooleanArray(cellCount)
    val order = IntArray(wanted)
    var size = 0
    val queue = ArrayDeque<Int>()
    queue.add(start)
    seen[start] = true
    while (queue.isNotEmpty() && size < wanted) {
      val cell = queue.removeFirst()
      order[size++] = cell
      val x = cell % columns
      val y = cell / columns
      // Shuffle the four directions so the blob does not always grow the same way.
      val dirs = intArrayOf(0, 1, 2, 3)
      for (i in 3 downTo 1) {
        val j = random.nextInt(i + 1)
        val tmp = dirs[i]
        dirs[i] = dirs[j]
        dirs[j] = tmp
      }
      for (dir in dirs) {
        val nx = when (dir) {
          0 -> x - 1
          1 -> x + 1
          else -> x
        }
        val ny = when (dir) {
          2 -> y - 1
          3 -> y + 1
          else -> y
        }
        if (nx !in 0 until columns || ny !in 0 until rows) continue
        val neighbour = ny * columns + nx
        if (seen[neighbour]) continue
        seen[neighbour] = true
        queue.add(neighbour)
      }
    }
    return order.copyOf(size)
  }

  override fun close() {
    if (closed) return
    closed = true
    WfcNative.modelDestroy(model)
  }

  private companion object {
    /** Different patches tried before a frame is allowed to repeat. */
    const val FRAME_ATTEMPTS = 16
  }
}
