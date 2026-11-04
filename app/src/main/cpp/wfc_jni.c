/*
 * JNI entry points for the C solver. Kept deliberately thin: every function either converts an
 * argument, calls one wfc_* function, or copies a result, so the interesting code stays in wfc.c.
 *
 * The class and method names below must match ca.justinmo.wfcbitmap.WfcNative exactly -- the object is
 * annotated @Keep so R8 cannot rename it out of reach of the dynamic linker.
 */

#include <jni.h>
#include <stdlib.h>

#include "wfc.h"



static jlong to_handle(const void *pointer) { return (jlong)(intptr_t)pointer; }

static void *from_handle(jlong handle) { return (void *)(intptr_t)handle; }

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelCreate(JNIEnv *env, jobject thiz, jintArray tiles,
                                                 jint tile_count, jint columns, jint rows,
                                                 jboolean relaxed_edges) {
  (void)thiz;
  if (tiles == NULL || tile_count <= 0) return 0;
  jsize length = (*env)->GetArrayLength(env, tiles);
  if (length < (jsize)tile_count * WFC_PACKED_TILE_INTS) return 0;

  size_t ints = (size_t)tile_count * WFC_PACKED_TILE_INTS;
  int32_t *packed = malloc(ints * sizeof(int32_t));
  if (packed == NULL) return 0;
  (*env)->GetIntArrayRegion(env, tiles, 0, (jsize)ints, (jint *)packed);
  wfc_model *model =
    wfc_model_create(packed, tile_count, columns, rows, relaxed_edges ? 1 : 0);
  free(packed);
  return to_handle(model);
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelTileCount(JNIEnv *env, jobject thiz, jlong model) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_count((const wfc_model *)from_handle(model));
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelTileSource(JNIEnv *env, jobject thiz, jlong model,
                                                     jint tile) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_source((const wfc_model *)from_handle(model), (int)tile);
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelTileCenter(JNIEnv *env, jobject thiz, jlong model,
                                                     jint tile) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_center((const wfc_model *)from_handle(model), (int)tile);
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelCellAlternatives(JNIEnv *env, jobject thiz,
                                                           jlong model, jintArray assignment,
                                                           jint cell) {
  (void)thiz;
  const wfc_model *native_model = (const wfc_model *)from_handle(model);
  if (native_model == NULL || assignment == NULL) return 0;
  int variables = wfc_model_variable_count(native_model);
  if ((*env)->GetArrayLength(env, assignment) < variables) return 0;
  jint *values = (*env)->GetIntArrayElements(env, assignment, NULL);
  if (values == NULL) return 0;
  int count = wfc_model_cell_alternatives(native_model, (const int32_t *)values, (int)cell);
  (*env)->ReleaseIntArrayElements(env, assignment, values, JNI_ABORT);
  return count;
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelEvolve(JNIEnv *env, jobject thiz, jlong model,
                                                 jintArray assignment, jintArray cells,
                                                 jint seed) {
  (void)thiz;
  const wfc_model *native_model = (const wfc_model *)from_handle(model);
  if (native_model == NULL || assignment == NULL || cells == NULL) return 0;
  int variables = wfc_model_variable_count(native_model);
  int cell_count = (*env)->GetArrayLength(env, cells);
  if ((*env)->GetArrayLength(env, assignment) < variables) return 0;
  jint *assignment_values = (*env)->GetIntArrayElements(env, assignment, NULL);
  jint *cell_values = (*env)->GetIntArrayElements(env, cells, NULL);
  if (assignment_values == NULL || cell_values == NULL) {
    if (assignment_values != NULL) {
      (*env)->ReleaseIntArrayElements(env, assignment, assignment_values, JNI_ABORT);
    }
    if (cell_values != NULL) {
      (*env)->ReleaseIntArrayElements(env, cells, cell_values, JNI_ABORT);
    }
    return 0;
  }
  int changed = wfc_model_evolve(native_model, (int32_t *)assignment_values,
                                 (const int32_t *)cell_values, cell_count, (uint32_t)seed);
  (*env)->ReleaseIntArrayElements(env, assignment, assignment_values, 0);
  (*env)->ReleaseIntArrayElements(env, cells, cell_values, JNI_ABORT);
  return changed;
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_modelDestroy(JNIEnv *env, jobject thiz, jlong model) {
  (void)env;
  (void)thiz;
  wfc_model_destroy((wfc_model *)from_handle(model));
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverCreate(JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return to_handle(wfc_solver_create());
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverDestroy(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  wfc_solver_destroy((wfc_solver *)from_handle(solver));
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverAbort(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  wfc_solver_abort((wfc_solver *)from_handle(solver));
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSolve(JNIEnv *env, jobject thiz, jlong solver, jlong model,
                                                 jint seed, jlong deadline_nanos, jint max_decisions,
                                                 jintArray out) {
  (void)thiz;
  wfc_solver *native_solver = (wfc_solver *)from_handle(solver);
  const wfc_model *native_model = (const wfc_model *)from_handle(model);
  if (native_solver == NULL || native_model == NULL || out == NULL) return 0;
  jsize length = (*env)->GetArrayLength(env, out);
  int variable_count = wfc_model_variable_count(native_model);
  if (length < variable_count) return 0;

  int solved = wfc_solver_solve(native_solver, native_model, (uint32_t)seed, (int64_t)deadline_nanos,
                                (int)max_decisions, NULL, NULL);
  if (solved) {
    /* The solution already lives in the solver's buffer: copy it out, no allocation here either. */
    const int32_t *assignment = wfc_solver_assignment(native_solver);
    if (assignment == NULL) return 0;
    (*env)->SetIntArrayRegion(env, out, 0, variable_count, (const jint *)assignment);
  }
  /* The result is handed over: reset the solver so the next collapse starts clean. */
  wfc_solver_finish(native_solver);
  return solved ? 1 : 0;
}

/*
 * Copies one optional per-variable pin array out of Java, or returns NULL when the caller passed null
 * (or an array too short to be the grid). The caller frees the result with free().
 */
static int32_t *copy_pins(JNIEnv *env, jintArray pins, int variable_count) {
  if (pins == NULL) return NULL;
  if ((*env)->GetArrayLength(env, pins) < variable_count) return NULL;
  int32_t *copy = malloc((size_t)variable_count * sizeof(int32_t));
  if (copy == NULL) return NULL;
  (*env)->GetIntArrayRegion(env, pins, 0, variable_count, (jint *)copy);
  return copy;
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSetTrace(JNIEnv *env, jobject thiz, jlong solver,
                                                    jboolean enabled) {
  (void)env;
  (void)thiz;
  wfc_solver_set_trace((wfc_solver *)from_handle(solver), enabled ? 1 : 0);
}

/*
 * The traced variant of solverSolve. It deliberately does not call wfc_solver_finish: the trace and
 * initial sizes have to be copied out first, and the caller does that through solverTraceInitial /
 * solverTraceEvents before calling solverFinish. Every other caller keeps using solverSolve, which
 * finishes immediately as before.
 */
JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSolveTraced(JNIEnv *env, jobject thiz, jlong solver,
                                                       jlong model, jint seed, jlong deadline_nanos,
                                                       jint max_decisions, jintArray out) {
  (void)thiz;
  wfc_solver *native_solver = (wfc_solver *)from_handle(solver);
  const wfc_model *native_model = (const wfc_model *)from_handle(model);
  if (native_solver == NULL || native_model == NULL || out == NULL) return 0;
  int variable_count = wfc_model_variable_count(native_model);
  if ((*env)->GetArrayLength(env, out) < variable_count) return 0;

  int solved = wfc_solver_solve(native_solver, native_model, (uint32_t)seed, (int64_t)deadline_nanos,
                                (int)max_decisions, NULL, NULL);
  if (solved) {
    const int32_t *assignment = wfc_solver_assignment(native_solver);
    if (assignment == NULL) return 0;
    (*env)->SetIntArrayRegion(env, out, 0, variable_count, (const jint *)assignment);
  }
  return solved ? 1 : 0;
}

JNIEXPORT jintArray JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverTraceInitial(JNIEnv *env, jobject thiz,
                                                        jlong solver) {
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  int count = wfc_solver_trace_initial_count(native_solver);
  const int32_t *sizes = wfc_solver_trace_initial_sizes(native_solver);
  jintArray out = (*env)->NewIntArray(env, count);
  if (out == NULL) return NULL;
  if (count > 0 && sizes != NULL) {
    (*env)->SetIntArrayRegion(env, out, 0, count, (const jint *)sizes);
  }
  return out;
}

JNIEXPORT jintArray JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverTraceInitialColors(JNIEnv *env, jobject thiz,
                                                              jlong solver) {
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  int count = wfc_solver_trace_initial_count(native_solver);
  const uint32_t *colours = wfc_solver_trace_initial_colors(native_solver);
  jintArray out = (*env)->NewIntArray(env, count);
  if (out == NULL) return NULL;
  if (count > 0 && colours != NULL) {
    (*env)->SetIntArrayRegion(env, out, 0, count, (const jint *)colours);
  }
  return out;
}

JNIEXPORT jlongArray JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverTraceEvents(JNIEnv *env, jobject thiz,
                                                       jlong solver) {
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  int count = wfc_solver_trace_event_count(native_solver);
  const uint64_t *events = wfc_solver_trace_events(native_solver);
  jlongArray out = (*env)->NewLongArray(env, count);
  if (out == NULL) return NULL;
  if (count > 0 && events != NULL) {
    (*env)->SetLongArrayRegion(env, out, 0, count, (const jlong *)events);
  }
  return out;
}

JNIEXPORT jboolean JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverTraceOverflow(JNIEnv *env, jobject thiz,
                                                         jlong solver) {
  (void)env;
  (void)thiz;
  return wfc_solver_trace_overflow((const wfc_solver *)from_handle(solver)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverFinish(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  wfc_solver_finish((wfc_solver *)from_handle(solver));
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSolveFixed(JNIEnv *env, jobject thiz, jlong solver,
                                                      jlong model, jint seed, jlong deadline_nanos,
                                                      jint max_decisions, jintArray fixed_centers,
                                                      jintArray fixed_patterns, jintArray out) {
  (void)thiz;
  wfc_solver *native_solver = (wfc_solver *)from_handle(solver);
  const wfc_model *native_model = (const wfc_model *)from_handle(model);
  if (native_solver == NULL || native_model == NULL || out == NULL) return 0;
  if (fixed_centers == NULL && fixed_patterns == NULL) return 0;
  int variable_count = wfc_model_variable_count(native_model);
  if ((*env)->GetArrayLength(env, out) < variable_count) return 0;

  int32_t *centres = copy_pins(env, fixed_centers, variable_count);
  int32_t *patterns = copy_pins(env, fixed_patterns, variable_count);
  int solved = wfc_solver_solve(native_solver, native_model, (uint32_t)seed, (int64_t)deadline_nanos,
                                (int)max_decisions, centres, patterns);
  free(centres);
  free(patterns);
  if (solved) {
    const int32_t *assignment = wfc_solver_assignment(native_solver);
    if (assignment == NULL) return 0;
    (*env)->SetIntArrayRegion(env, out, 0, variable_count, (const jint *)assignment);
  }
  wfc_solver_finish(native_solver);
  return solved ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverDecisions(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : wfc_solver_decisions(native_solver);
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSetFineProfiling(JNIEnv *env, jobject thiz, jlong solver,
                                                          jboolean enabled) {
  (void)env;
  (void)thiz;
  wfc_solver_set_fine_profile((wfc_solver *)from_handle(solver), enabled ? 1 : 0);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverPhaseNanos(JNIEnv *env, jobject thiz, jlong solver,
                                                      jint phase) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return (jlong)wfc_solver_phase_nanos(native_solver, (int)phase);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverRevises(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : (jlong)wfc_solver_revises(native_solver);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverDeadEnds(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : (jlong)wfc_solver_dead_ends(native_solver);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverShrinks(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : (jlong)wfc_solver_shrinks(native_solver);
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSetOrder(JNIEnv *env, jobject thiz, jlong solver,
                                                    jint mode) {
  (void)env;
  (void)thiz;
  wfc_solver_set_order((wfc_solver *)from_handle(solver), (int)mode);
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_generation_WfcNative_solverSetWorklist(JNIEnv *env, jobject thiz, jlong solver,
                                                       jint mode) {
  (void)env;
  (void)thiz;
  wfc_solver_set_worklist((wfc_solver *)from_handle(solver), (int)mode);
}
