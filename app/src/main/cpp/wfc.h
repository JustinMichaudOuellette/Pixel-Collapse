#ifndef WFC_H
#define WFC_H

#include <stdint.h>

/*
 * Overlapping-model wave function collapse, the same algorithm and the same rule as the Kotlin
 * StrictBitmapSolver, written in C because it is the only place in the app that is actually hot.
 *
 * The model is the part that depends only on the tile set (compatibility tables, the per-cell
 * starting domains); one model is shared by every worker. A solver owns the mutable state of one
 * search (domains, trail, arc queue) and can be aborted from another thread.
 */

#define WFC_DIRS 8

/* Kernel slot for each direction, in Direction2d8 declaration order (LEFT, UP_LEFT, UP, ...). */
extern const int WFC_DIR_SLOT[WFC_DIRS];

/*
 * Ints per tile in the packed array: direction mask, weight, then the nine kernel colours. Must match
 * WfcNative.PACKED_TILE_INTS on the Kotlin side -- getting it wrong shifts every kernel by a word and
 * silently breaks every compatibility test.
 */
#define WFC_PACKED_TILE_INTS 11

typedef struct wfc_model wfc_model;
typedef struct wfc_solver wfc_solver;

/*
 * @param tiles   tile_count * 11 ints: [present direction mask, weight, 9 kernel colours]. The mask
 *                says which of the nine kernel slots hold a colour; the others are ignored, so a
 *                blank ("the drawing ends here") needs no sentinel.
 * @param columns output width in cells.
 * @param rows    output height in cells.
 * @param relaxed_edges 0 keeps the edge rule (a cell accepts only patterns whose mask is exactly the
 *                directions it has a neighbour in, so patterns sampled on the drawing's edge only ever
 *                sit on the bitmap's edge). Non-zero drops the "exactly": a cell accepts any pattern that
 *                has a colour towards every neighbour it has, so a border cell may use a complete
 *                pattern and its outward pixels are simply not drawn. The strict rule can be impossible
 *                for a drawing whose border cannot repeat around the bitmap's, which is the only reason
 *                the relaxed one exists: it makes the instance the ordinary overlapping model.
 * @return a model, or NULL when the arguments are unusable (tile_count outside 1..1024, a grid that
 *         is not positive).
 */
wfc_model *wfc_model_create(const int32_t *tiles, int tile_count, int columns, int rows,
                            int relaxed_edges);
void wfc_model_destroy(wfc_model *model);

/* A solver owns only scratch space; it is bound to a model by each wfc_solver_solve call, so one\n * solver can serve collapse after collapse (see wfc_solver_finish). */
wfc_solver *wfc_solver_create(void);
void wfc_solver_destroy(wfc_solver *solver);

/* Asks the running search to stop; safe to call from any thread. */
void wfc_solver_abort(wfc_solver *solver);

/*
 * Runs one search. The solution lands in the solver's own buffer, read back with
 * [wfc_solver_assignment], so no allocation happens per solve.
 *
 * @param deadline_nanos absolute CLOCK_MONOTONIC deadline, i.e. System.nanoTime() on Android.
 * @param fixed_centers optional per-variable pinned centre; INT32_MIN leaves the cell free.
 * @param fixed_patterns optional per-variable pinned *packed* pattern index (the caller's tile index);
 *        INT32_MIN leaves the cell free. Pinning the pattern rather than only its centre is what makes a
 *        stitched screen continue the previous one's shape and not merely its edge colours: with only a
 *        centre pinned, every pattern that shares that centre stays available, so the diagonals behind it
 *        can be laid out differently.
 * @return 1 when a tiling was found, 0 when there is none (root propagation emptied a domain), the
 *         deadline passed, the decision cap was hit, or the solver was aborted.
 */
int wfc_solver_solve(wfc_solver *solver, const wfc_model *model, uint32_t seed, int64_t deadline_nanos,
                     int max_decisions, const int32_t *fixed_centers, const int32_t *fixed_patterns);

/* The tiles of the last successful solve, one per variable, valid until [wfc_solver_finish]. */
const int32_t *wfc_solver_assignment(const wfc_solver *solver);

/*
 * Call once the result has been taken away. Clears the result buffer and the search state and drops the
 * solver's model pointer, so the solver is clean and reusable for the next collapse (and cannot be read
 * for a stale tiling in between).
 */
void wfc_solver_finish(wfc_solver *solver);

int wfc_solver_decisions(const wfc_solver *solver);
int64_t wfc_solver_revises(const wfc_solver *solver);

/*
 * Optional solve trace: every domain-size transition, plus the domain sizes and average candidate
 * colour the search started from.
 *
 * The trace is what lets the app animate the real search instead of revealing the finished bitmap. Each
 * event packs the variable in the low 20 bits, the new domain size in the next 10 bits, and the average
 * RGB of the remaining candidate tile centres in the next 24 bits. Sizes above 1023 are stored as 1023,
 * because no visualisation needs more resolution than that. A size of 1 means the cell was decided at
 * that point, and a size of 0 is a contradiction that propagation will undo.
 *
 * Recording is off by default: it is only enabled for the replay a solve-video export asks for.
 * wfc_solver_trace_overflow is true when the event buffer filled up, in which case the trace is
 * incomplete and should not be used.
 */
void wfc_solver_set_trace(wfc_solver *solver, int enabled);
const int32_t *wfc_solver_trace_initial_sizes(const wfc_solver *solver);
const uint32_t *wfc_solver_trace_initial_colors(const wfc_solver *solver);
int wfc_solver_trace_initial_count(const wfc_solver *solver);
int wfc_solver_trace_event_count(const wfc_solver *solver);
const uint64_t *wfc_solver_trace_events(const wfc_solver *solver);
int wfc_solver_trace_overflow(const wfc_solver *solver);

/*
 * Instrumentation for the last solve: how many decisions led to a contradiction (dead ends, i.e. wasted
 * branches) and how many revisions actually removed a tile. Together with wfc_solver_revises they say
 * whether the search is backtracking too much or propagating too widely -- see wfc_solver_set_order.
 */
int64_t wfc_solver_dead_ends(const wfc_solver *solver);
int64_t wfc_solver_shrinks(const wfc_solver *solver);

/*
 * Variable selection order, for measuring rather than for the app:
 *   0  minimum domain first, ties to the lowest variable index (the default; what the app runs)
 *   1  a fixed outward-in peel: the border ring, then each ring inwards
 *   2  minimum domain first, ties to the cell peeled earliest
 * Modes 1 and 2 cost a grid-sized order table per solve.
 */
void wfc_solver_set_order(wfc_solver *solver, int mode);

/*
 * Arc worklist discipline, for measuring: 0 pops the oldest arc (FIFO, breadth-first, the default),
 * 1 pops the newest (LIFO, depth-first, follows one wavefront to its fixpoint first).
 */
void wfc_solver_set_worklist(wfc_solver *solver, int mode);

/* How many cells the model fills, i.e. the size of the assignment buffer a solve needs. */
int wfc_model_variable_count(const wfc_model *model);

/*
 * How many tiles survived the model's own arc-consistency pass. wfc_model_create prunes patterns that
 * can never be placed, so this can be smaller than the tile count passed in.
 */
int wfc_model_tile_count(const wfc_model *model);

/*
 * The index, in the caller's packed array, of the tile at [tile] in the model. Solve results are model
 * tile indices; this is how a caller turns one back into the pattern it sampled.
 */
int wfc_model_tile_source(const wfc_model *model, int tile);

/* The ARGB pixel a model tile shows (its window centre). */
int wfc_model_tile_center(const wfc_model *model, int tile);

/* Phase timers of the last solve, in nanoseconds: 0 setup, 1 initial propagation, 2 search,
 * 3 variable selection (inside search), 4 tile ordering (inside search). */
enum {
  WFC_PHASE_SETUP = 0,
  WFC_PHASE_INITIAL_PROPAGATION = 1,
  WFC_PHASE_SEARCH = 2,
  WFC_PHASE_SELECTION = 3,
  WFC_PHASE_ORDERING = 4
};
int64_t wfc_solver_phase_nanos(const wfc_solver *solver, int phase);

/*
 * Turns the per-decision phase probes on. They cost a clock read per decision, which is negligible
 * where clock_gettime is a vDSO call and large where it is a syscall, so they are off by default.
 */
void wfc_solver_set_fine_profile(wfc_solver *solver, int enabled);

#endif /* WFC_H */
