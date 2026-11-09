package ca.justinmo.wfcbitmap.generation

/**
 * A replayable record of one real collapse.
 *
 * [initialSizes] is the domain size of every cell after the optional pins and before propagation, and
 * [initialColors] is the average ARGB of that cell's candidate tile centres at the same point.
 * [events] is every domain-size/colour transition the search made, in order, packed as variable in the
 * low 20 bits, new size in the next 10, and the new average RGB in the next 24. A singleton event is a
 * cell the solver actually decided at that point; a later event for the same cell is either propagation
 * narrowing it again or backtracking undoing the decision.
 */
internal class SolveTrace(
  val columns: Int,
  val rows: Int,
  val tileCount: Int,
  val initialSizes: IntArray,
  val initialColors: IntArray,
  val events: LongArray,
)
