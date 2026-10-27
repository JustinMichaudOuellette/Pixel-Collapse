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
 * @param tiles   tile_count * 10 ints: [present direction mask, weight, 9 kernel colours]. The mask
 *                says which of the nine kernel slots hold a colour; the others are ignored, so a
 *                blank ("the drawing ends here") needs no sentinel.
 * @param columns output width in cells.
 * @param rows    output height in cells.
 * @return a model, or NULL when the arguments are unusable (tile_count outside 1..1024, a grid that
 *         is not positive, or nothing that fits the border rule).
 */
wfc_model *wfc_model_create(const int32_t *tiles, int tile_count, int columns, int rows);
void wfc_model_destroy(wfc_model *model);

wfc_solver *wfc_solver_create(const wfc_model *model);
void wfc_solver_destroy(wfc_solver *solver);

/* Asks the running search to stop; safe to call from any thread. */
void wfc_solver_abort(wfc_solver *solver);

/*
 * Runs one search.
 *
 * @param deadline_nanos absolute CLOCK_MONOTONIC deadline, i.e. System.nanoTime() on Android.
 * @param assignment     operator-provided buffer of columns * rows ints, filled on success.
 * @return 1 when a tiling was found, 0 when there is none (root propagation emptied a domain), the
 *         deadline passed, the decision cap was hit, or the solver was aborted.
 */
int wfc_solver_solve(wfc_solver *solver, uint32_t seed, int64_t deadline_nanos, int max_decisions,
                     int32_t *assignment);

int wfc_solver_decisions(const wfc_solver *solver);
int64_t wfc_solver_revises(const wfc_solver *solver);

/* How many cells the solver fills, i.e. the size of the assignment buffer it needs. */
int wfc_solver_variable_count(const wfc_solver *solver);

#endif /* WFC_H */
