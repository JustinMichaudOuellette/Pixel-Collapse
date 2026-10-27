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
Java_ca_justinmo_wfcbitmap_WfcNative_modelCreate(JNIEnv *env, jobject thiz, jintArray tiles,
                                                 jint tile_count, jint columns, jint rows) {
  (void)thiz;
  if (tiles == NULL || tile_count <= 0) return 0;
  jsize length = (*env)->GetArrayLength(env, tiles);
  if (length < (jsize)tile_count * WFC_PACKED_TILE_INTS) return 0;

  size_t ints = (size_t)tile_count * WFC_PACKED_TILE_INTS;
  int32_t *packed = malloc(ints * sizeof(int32_t));
  if (packed == NULL) return 0;
  (*env)->GetIntArrayRegion(env, tiles, 0, (jsize)ints, (jint *)packed);
  wfc_model *model = wfc_model_create(packed, tile_count, columns, rows);
  free(packed);
  return to_handle(model);
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_modelTileCount(JNIEnv *env, jobject thiz, jlong model) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_count((const wfc_model *)from_handle(model));
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_modelTileSource(JNIEnv *env, jobject thiz, jlong model,
                                                     jint tile) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_source((const wfc_model *)from_handle(model), (int)tile);
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_modelTileCenter(JNIEnv *env, jobject thiz, jlong model,
                                                     jint tile) {
  (void)env;
  (void)thiz;
  return (jint)wfc_model_tile_center((const wfc_model *)from_handle(model), (int)tile);
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_modelDestroy(JNIEnv *env, jobject thiz, jlong model) {
  (void)env;
  (void)thiz;
  wfc_model_destroy((wfc_model *)from_handle(model));
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverCreate(JNIEnv *env, jobject thiz) {
  (void)env;
  (void)thiz;
  return to_handle(wfc_solver_create());
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverDestroy(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  wfc_solver_destroy((wfc_solver *)from_handle(solver));
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverAbort(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  wfc_solver_abort((wfc_solver *)from_handle(solver));
}

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverSolve(JNIEnv *env, jobject thiz, jlong solver, jlong model,
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
                                (int)max_decisions);
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

JNIEXPORT jint JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverDecisions(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : wfc_solver_decisions(native_solver);
}

JNIEXPORT void JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverSetFineProfiling(JNIEnv *env, jobject thiz, jlong solver,
                                                          jboolean enabled) {
  (void)env;
  (void)thiz;
  wfc_solver_set_fine_profile((wfc_solver *)from_handle(solver), enabled ? 1 : 0);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverPhaseNanos(JNIEnv *env, jobject thiz, jlong solver,
                                                      jint phase) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return (jlong)wfc_solver_phase_nanos(native_solver, (int)phase);
}

JNIEXPORT jlong JNICALL
Java_ca_justinmo_wfcbitmap_WfcNative_solverRevises(JNIEnv *env, jobject thiz, jlong solver) {
  (void)env;
  (void)thiz;
  const wfc_solver *native_solver = (const wfc_solver *)from_handle(solver);
  return native_solver == NULL ? 0 : (jlong)wfc_solver_revises(native_solver);
}
