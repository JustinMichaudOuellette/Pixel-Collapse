/*
 * Overlapping-model wave function collapse: AC-3 propagation with backtracking over bitset domains.
 *
 * This is the same algorithm and the same constraint as the Kotlin StrictBitmapSolver -- it is a
 * performance port, not a different model, so the two must agree on: the edge rule (a cell's domain is
 * the tiles whose direction mask is exactly the directions that have a neighbour inside the grid), the
 * arc worklist order (FIFO; depth-first was measured much worse), min-domain selection, and weighted
 * sampling without replacement (Efraimidis-Spirakis: key = random^(1/weight), largest first).
 *
 * The shape of the code follows the Kotlin original closely so that the two can be read side by side.
 *
 * --- where the time actually goes (152x270 = 41,040 cells, Pixel 7a) ------------------------------
 *
 *   drawing    tiles  decisions  dead ends  revisions   shrinks   wall   initial  search
 *   flat          9          0         0           0         0   1.0 ms   0.0      0.0
 *   bordered     50     18,212         0   3,064,778   510,588  120 ms  30 ms   88 ms
 *   rings       104         66         0   4,394,225   813,598  380 ms   6 ms  373 ms
 *
 * Three consequences, each measured rather than reasoned:
 *
 *  1. `dead ends` is zero on every drawing tried, so this search never backtracks: every decision is
 *     taken once and kept. Nothing is wasted on bad branches, and heuristics whose whole purpose is to
 *     avoid them (better value ordering, backjumping, restarts) have nothing to win.
 *  2. Revisions / shrinks is 5.4-6.0, i.e. about six arc checks per domain narrowing against the
 *     eight a cell can have: the worklist is not repeating itself. The cost is the narrowing itself.
 *  3. A cell's domain narrows 12 times (bordered) to 20 times (rings) before it is a singleton, and each
 *     narrowing re-checking its neighbours scans the candidates still left. Time is therefore
 *     `shrinks x 6 x |domain|`, which is why breadth-first beats depth-first by the shrink ratio, and
 *     why -O3/SIMD/allocation work is already spent: the inner loop runs at ~1.7 ns per candidate, which
 *     is a dependent load plus a `and`.
 *
 * The remaining levers are not in this file: collapse fewer grids (warm-start from the previous tiling
 * on an edit, which the app does not do), or collapse them on more cores.
 */

#include "wfc.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Direction order is Direction2d8's: LEFT, UP_LEFT, UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT. */
const int WFC_DIR_SLOT[WFC_DIRS] = {3, 0, 1, 2, 5, 8, 7, 6};

static const int OPPOSITE[WFC_DIRS] = {4, 5, 6, 7, 0, 1, 2, 3};
static const int DX[WFC_DIRS] = {-1, -1, 0, 1, 1, 1, 0, -1};
static const int DY[WFC_DIRS] = {0, -1, -1, -1, 0, 1, 1, 1};

/* Direction bits are packed with the word index in the low four bits of trail and queue entries. */
#define PACK_BITS 4
#define PACK_MASK ((1 << PACK_BITS) - 1)

/* Check the clock every this many revisions: a syscall per arc would dominate short revisions. */
#define CLOCK_EVERY 512



typedef struct {
  int mask;        /* direction bits where the kernel has a pixel */
  int weight;      /* how often the pattern occurs in the drawing */
  int centre;      /* kernel slot 4, the pixel the cell shows */
  int kernel[9];
} wfc_tile;

struct wfc_model {
  int tile_count;
  int columns;
  int rows;
  int variable_count;
  int word_count;
  wfc_tile *tiles;
  /*
   * Index into the caller's packed array for each surviving tile. wfc_model_create prunes tiles that
   * can never be placed next to anything, so tile indices here are compacted; the caller needs this
   * map to read a solved cell back as the pattern it sampled.
   */
  int *source;
  int *present;                /* per variable: direction bits with a neighbour inside the grid */
  int *step_offset;            /* per direction: dy * columns + dx */
  uint64_t *compat;            /* [(direction * tile_count + tile) * word_count + word] */

  uint64_t *domain_by_present; /* [present * word_count + word] */
  int *size_by_present;

  /*
   * Arc seeding support. A cell's starting domain depends only on its own present-mask, so cells fall
   * into at most a handful of classes (nine for a bordered tile set). This table says, for a pair of
   * those classes and a direction, whether the two full domains already support each other: when they
   * do, the arc cannot prune anything at initialisation and need not be queued.
   */
  int class_of_mask[256];
  int class_count;
  unsigned char *class_masks;      /* [class] present-mask of that class */
  unsigned char *class_consistent; /* [(class * class_count + class) * WFC_DIRS + direction] */
};

typedef struct {
  int var;
  /** Start of this frame's candidate tiles inside the solver's value arena. */
  int values_offset;
  int count;
  int next;
  int trail_mark;
  /** This variable's position in the peel order, so backtracking can rewind the sweep cursor. */
  int order_pos;
} wfc_frame;

struct wfc_solver {
  const wfc_model *model;

  uint64_t *domains; /* variable_count * word_count */
  int *size;         /* per variable: values left */
  /*
   * Min-domain selection: a bit per (domain size, variable), so a decision is "find the first set bit of
   * the smallest non-empty size" -- O(1) instead of scanning all variables (which was 31% of the search
   * on a decision-heavy drawing), and it returns the *lowest variable index* of that size, which is the
   * tie-break the search has always used. A plain linked list per size is cheaper to store but breaks
   * ties in insertion order, and that alone tripled the decisions on one drawing.
   */
  uint64_t *size_bits;
  int bitset_words;
  int smallest;

  /*
   * What the buffers are actually sized for. Compared on every bind: comparing the model *pointer* is
   * not enough, because a freed model can be reallocated at the same address and a stale binding then
   * looks current -- which is how a pooled solver ended up clearing a result buffer sized for a
   * 76x135 grid with the variable count of a 152x270 one.
   */
  int bound_variables;
  int bound_words;
  int bound_tiles;

  /* Capacities of the buffers above, so a pooled solver only grows them when a model needs more room. */
  int capacity_variables;
  int capacity_words;
  int capacity_tiles;

  int *trail_key; /* (variable << 4) | word */
  uint64_t *trail_value;
  int trail_size;
  int trail_cap;

  int *queue; /* (variable << 4) | direction */
  int queue_head;
  int queue_size;
  int queue_cap;
  /*
   * Arc worklist: one bit per (variable, direction), so an arc queued twice is only queued once.
   *
   * Coalescing this per variable instead -- one entry per variable plus a direction mask -- was tried at
   * 152x270 and measured worse: bordered 122.6 -> 127.3 ms, rings 395.5 -> 421.5 ms, with slightly more
   * revisions (3.06M -> 3.07M and 4.39M -> 4.51M). Grouping a variable's arcs makes each pass prune
   * against more neighbours at once, and at this size that costs more than the queue traffic it saves.
   */
  uint64_t *in_queue;

  wfc_frame *stack;
  int stack_size;
  int stack_cap;

  /*
   * Candidate tiles for the frames of the search, in one bump-allocated arena. Frames are pushed and
   * popped LIFO, so a frame's slice is always at the top and popping is just moving the bump pointer
   * back -- no malloc/free per decision, which used to be two allocations and a free for every branch.
   */
  int *value_arena;
  int arena_size;
  int arena_cap;

  /** Scratch for the weighted keys of one decision; reused, never allocated per decision. */
  double *keys_scratch;

  /** Solution buffer, so the JNI layer does not allocate one per solve. */
  int32_t *assignment;

  uint32_t rng;
  int decisions;
  int64_t revises;
  /*
   * Instrumentation for "is the search wasting work on bad branches?". A dead end is a decision whose
   * propagation failed and had to be undone; a shrink is a revision that actually removed a tile. The
   * ratio (revises per decision, shrinks per revision) is what separates "too much backtracking" from
   * "the propagation cascade is simply wide", and those two have completely different fixes.
   */
  int64_t dead_ends;
  int64_t shrinks;
  volatile int abort_flag;

  /*
   * Variable-selection order, so competing orders can be measured against each other instead of argued
   * about. 0 is what the app runs: minimum domain, ties to the lowest variable index. 1 and 2 are the
   * outward-in peel orders described on build_sweep().
   *
   * Measured at 152x270 on a Pixel 7a, 3 solves per point, seed 1:
   *
   *   bordered (50 tiles)          wall      decisions   revisions
   *     mode 0 (min domain)       119.8 ms     18,212     3,064,778
   *     mode 1 (pure peel)        120.8 ms     18,827     3,088,642
   *     mode 2 (peel tie-break)   139.4 ms     18,827     3,088,642
   *   rings (104 tiles)
   *     mode 0 / 1 / 2            381 / 383 / 380 ms, 66 decisions, 4,394,225 revisions -- identical,
   *                               because propagation decides the whole grid no matter where it starts.
   *
   * So the order is worth nothing here, and the tie-break costs 16% (it scans a whole row of the size
   * bitset per decision). The reason: this search never backtracks (dead_ends is 0 on every drawing
   * measured), so there are no "bad cells" for a better order to avoid, and min-domain already picks a
   * cell the cascade has narrowed. Peel is not wrong, it is just not better -- which is why the shipping
   * path stays mode 0.
   */
  int order_mode;
  int *sweep_order; /* variable index at each position of the peel */
  int *sweep_rank;  /* the inverse: how early a variable is peeled */
  int sweep_cursor;
  int sweep_cap;

  /*
   * Arc worklist discipline: 0 is FIFO (breadth-first over the grid, what the app runs), 1 is LIFO
   * (depth-first, follows one wavefront at a time).
   *
   * Measured at 152x270, Pixel 7a, 3 solves per point, seed 1:
   *
   *   bordered   FIFO 122.7 ms  3,064,778 revisions  510,588 shrinks
   *              LIFO 175.9 ms  4,116,389 revisions  519,297 shrinks   (+43%)
   *   rings      FIFO 408.8 ms  4,394,225 revisions  813,598 shrinks
   *              LIFO 815.1 ms  8,361,565 revisions 1,077,475 shrinks  (2.0x)
   *
   * Depth-first is the intuitive choice -- finish one wave before starting the next -- and it doubles the
   * work. The reason is that a cell can only narrow as far as its neighbours have: breadth-first gives
   * every cell the most information available before it is narrowed, so it narrows fewer times, while
   * depth-first narrows on partial information and then has to narrow again. The shrink count is what
   * the whole solve costs, so FIFO wins by the ratio of the shrink counts.
   */
  int worklist_mode;

  /* Phase timers, so a slow solve can be attributed to a phase instead of guessed at. */
  int fine_profile;
  int64_t setup_nanos;
  int64_t initial_propagation_nanos;
  int64_t search_nanos;
  int64_t selection_nanos;
  int64_t ordering_nanos;
};

/* --- small helpers ------------------------------------------------------------------------- */

static int64_t now_nanos(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

static inline uint32_t rng_next(uint32_t *state) {
  uint32_t x = *state;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  *state = x == 0 ? 0x9E3779B9u : x;
  return *state;
}

static inline double rng_double(uint32_t *state) {
  /* 24 bits is plenty for a tie-break, and it keeps the value strictly below 1. */
  return (double)(rng_next(state) >> 8) * (1.0 / 16777216.0);
}

static inline int popcount(uint64_t value) { return __builtin_popcountll(value); }

static inline int ctz(uint64_t value) { return __builtin_ctzll(value); }

/* --- model --------------------------------------------------------------------------------- */

wfc_model *wfc_model_create(const int32_t *raw, int tile_count, int columns, int rows,
                            int relaxed_edges) {
  if (raw == NULL || tile_count <= 0 || tile_count > 1024) return NULL;
  if (columns <= 0 || rows <= 0) return NULL;
  /* Keeping the variable count in an int keeps every index computation exact. */
  if ((int64_t)columns * rows > (int64_t)1 << 24) return NULL;

  wfc_model *m = calloc(1, sizeof(*m));
  if (m == NULL) return NULL;
  m->tile_count = tile_count;
  m->columns = columns;
  m->rows = rows;
  m->variable_count = columns * rows;
  m->word_count = (tile_count + 63) >> 6;

  m->tiles = calloc((size_t)tile_count, sizeof(wfc_tile));
  m->source = calloc((size_t)tile_count, sizeof(int));
  m->present = calloc((size_t)m->variable_count, sizeof(int));
  m->step_offset = calloc(WFC_DIRS, sizeof(int));
  m->compat = calloc((size_t)WFC_DIRS * tile_count * m->word_count, sizeof(uint64_t));

  m->domain_by_present = calloc(256 * (size_t)m->word_count, sizeof(uint64_t));
  m->size_by_present = calloc(256, sizeof(int));
  m->class_masks = calloc(256, sizeof(unsigned char));
  if (m->tiles == NULL || m->source == NULL || m->present == NULL || m->step_offset == NULL ||
      m->compat == NULL ||
      m->domain_by_present == NULL || m->size_by_present == NULL || m->class_masks == NULL) {
    wfc_model_destroy(m);
    return NULL;
  }

  for (int t = 0; t < tile_count; t++) {
    const int32_t *packed = raw + (size_t)t * WFC_PACKED_TILE_INTS;
    wfc_tile *tile = &m->tiles[t];
    tile->mask = packed[0];
    tile->weight = packed[1] > 0 ? packed[1] : 1;
    for (int slot = 0; slot < 9; slot++) tile->kernel[slot] = packed[2 + slot];
    tile->centre = tile->kernel[4];
    m->source[t] = t;
  }

  /*
   * Arc consistency on the tile set itself: drop every pattern that can never be placed next to any
   * other pattern in some direction where it has a pixel.
   *
   * This used to run in Kotlin on every edit, as a fixpoint over a LinkedHashSet inside a lambda: it
   * cost 7 ms for a 9-pattern drawing and 23 ms for a 104-pattern one, i.e. more than the collapse
   * itself. Here it is the same algorithm on plain arrays, running where the compatibility table is
   * needed anyway.
   *
   * A pattern with a pixel towards `d` needs a partner `u` whose centre is that pixel and whose own
   * pixel towards `-d` is this pattern's centre. A pattern with no pixel towards `d` needs no support:
   * that is where the drawing ends.
   */
  {
    unsigned char *dead = calloc((size_t)tile_count, sizeof(unsigned char));
    if (dead == NULL) {
      wfc_model_destroy(m);
      return NULL;
    }
    int changed = 1;
    while (changed) {
      changed = 0;
      for (int t = 0; t < tile_count; t++) {
        if (dead[t]) continue;
        const wfc_tile *ct = &m->tiles[t];
        int supported = 1;
        for (int d = 0; d < WFC_DIRS && supported; d++) {
          if ((ct->mask & (1 << d)) == 0) continue;
          int required = ct->kernel[WFC_DIR_SLOT[d]];
          int opposite = OPPOSITE[d];
          int slot_back = WFC_DIR_SLOT[opposite];
          int found = 0;
          for (int u = 0; u < tile_count && !found; u++) {
            if (dead[u]) continue;
            const wfc_tile *cu = &m->tiles[u];
            if (cu->centre != required) continue;
            if ((cu->mask & (1 << opposite)) == 0) continue;
            if (cu->kernel[slot_back] != ct->centre) continue;
            found = 1;
          }
          if (!found) supported = 0;
        }
        if (!supported) {
          dead[t] = 1;
          changed = 1;
        }
      }
    }
    /* Compact the survivors, remembering which packed tile each one came from. */
    int kept = 0;
    for (int t = 0; t < tile_count; t++) {
      if (dead[t]) continue;
      m->tiles[kept] = m->tiles[t];
      m->source[kept] = m->source[t];
      kept++;
    }
    free(dead);
    if (kept == 0) {
      /* Nothing survived, so no drawing can be tiled: the caller falls back to the stretch. */
      wfc_model_destroy(m);
      return NULL;
    }
    tile_count = kept;
    m->tile_count = kept;
    m->word_count = (kept + 63) >> 6;
  }

  /*
   * The compatibility and domain tables were sized for the unpruned tile set, so they have to be
   * cleared before the survivors are written in with the (possibly narrower) new word stride.
   */
  memset(m->compat, 0, sizeof(uint64_t) * (size_t)WFC_DIRS * (size_t)tile_count * m->word_count);
  memset(m->domain_by_present, 0, sizeof(uint64_t) * 256 * (size_t)m->word_count);

  for (int d = 0; d < WFC_DIRS; d++) m->step_offset[d] = DY[d] * columns + DX[d];

  /* Which directions have a neighbour inside the grid, per variable. */
  for (int y = 0; y < rows; y++) {
    for (int x = 0; x < columns; x++) {
      int present = 0;
      for (int d = 0; d < WFC_DIRS; d++) {
        int nx = x + DX[d];
        int ny = y + DY[d];
        if (nx >= 0 && nx < columns && ny >= 0 && ny < rows) present |= 1 << d;
      }
      m->present[y * columns + x] = present;
    }
  }

  /*
   * Compatibility: tile `t` may sit next to tile `u` in direction `d` when both agree in both
   * directions -- `t` has a pixel towards `u` and it is `u`'s centre, and `u` has a pixel back towards
   * `t` and it is `t`'s centre. A blank towards `d` (the drawing ended there) supports nobody.
   */
  for (int d = 0; d < WFC_DIRS; d++) {
    int opposite = OPPOSITE[d];
    for (int t = 0; t < tile_count; t++) {
      const wfc_tile *ct = &m->tiles[t];
      uint64_t *row = m->compat + ((size_t)d * tile_count + t) * m->word_count;
      if ((ct->mask & (1 << d)) == 0) continue;
      int towards = ct->kernel[WFC_DIR_SLOT[d]];
      for (int u = 0; u < tile_count; u++) {
        const wfc_tile *cu = &m->tiles[u];
        if (cu->centre != towards) continue;
        if ((cu->mask & (1 << opposite)) == 0) continue;
        if (cu->kernel[WFC_DIR_SLOT[opposite]] != ct->centre) continue;
        row[u >> 6] |= 1ull << (u & 63);
      }
    }
  }

  /*
   * Nothing else can be pruned from a drawing-derived tile set, and that is worth stating because it
   * closes off a whole family of optimisations.
   *
   * Every tile here is a 3x3 window that occurs somewhere in the drawing, with exactly the neighbours it
   * has there. So arc consistency removes nothing (`sampled=104 model=104` on the probe drawings), and a
   * 2x2 path-consistency pass -- drop tiles whose up-neighbour and right-neighbour cannot coexist --
   * removes nothing either: it was implemented and measured, with byte-identical revision counts
   * (3,064,778 and 4,394,225 before and after) and no change in solve time. Domain sizes are therefore a
   * property of the drawing, not of the pruning, and the collapse's cost is inherent to
   * cells x domain size x cascades.
   */
  /*
   * Domains by cell class. Two rules, and which one is used is the difference between "the drawing's
   * edge is the bitmap's edge" and "the drawing is a texture":
   *
   *  - strict (relaxed_edges == 0): a cell accepts the tiles whose mask is *exactly* the in-grid
   *    directions. Border cells therefore accept only patterns sampled on that edge of the drawing, and
   *    an edge pattern can only sit on its own edge. This is the rule the app wants, and it is also the
   *    rule that can be impossible: a border cell can only follow another border cell whose right
   *    colours match, which makes the bitmap's border a walk over the drawing's border patterns. A
   *    drawing whose border does not repeat cannot walk 152/270 cells, and then there is no tiling at
   *    all -- measured: a 16x11 drawing with a per-row border fails in 5 ms with 0 decisions, i.e. the
   *    root arc-consistency pass proves it immediately.
   *
   *  - relaxed (relaxed_edges != 0): a cell accepts every tile that has a colour towards all the
   *    neighbours it actually has -- `mask` is allowed to contain directions that are off-grid, which
   *    simply means the pattern's outward pixels are not drawn. Border cells can then use complete
   *    patterns, so the drawing's border no longer has to repeat and the instance becomes the ordinary
   *    overlapping model, which tiles for any drawing with a self-consistent pattern in it.
   *
   * The caller uses strict first and relaxed only when strict has no tiling, so the edge rule is kept
   * wherever it can be.
   */
  for (int p = 0; p < 256; p++) {
    uint64_t *row = m->domain_by_present + (size_t)p * m->word_count;
    int count = 0;
    for (int t = 0; t < tile_count; t++) {
      int mask = m->tiles[t].mask;
      int keep = relaxed_edges ? ((mask & p) == p) : (mask == p);
      if (!keep) continue;
      row[t >> 6] |= 1ull << (t & 63);
      count++;
    }
    m->size_by_present[p] = count;
  }

  /*
   * Build the arc-seeding table. A cell's starting domain depends only on its present-mask, so cells
   * fall into a handful of classes; two full domains that already support each other in a direction
   * cannot prune anything at initialisation, and queueing those arcs was most of the setup cost of a
   * small collapse (~82,000 arcs on a 76x135 grid, almost all of them no-ops).
   *
   * Only the *initial* arcs are skipped: once a domain shrinks, the neighbour-enqueue in revise() puts
   * the affected arcs back on the worklist, so nothing that can prune is ever missed.
   */
  m->class_count = 0;
  for (int p = 0; p < 256; p++) m->class_of_mask[p] = -1;
  for (int v = 0; v < m->variable_count; v++) {
    int p = m->present[v];
    if (m->class_of_mask[p] >= 0) continue;
    m->class_of_mask[p] = m->class_count;
    m->class_masks[m->class_count] = (unsigned char)p;
    m->class_count++;
  }
  size_t classes = (size_t)m->class_count;
  m->class_consistent = calloc(classes * classes * WFC_DIRS, sizeof(unsigned char));
  if (m->class_consistent == NULL) return NULL;
  for (int cv = 0; cv < m->class_count; cv++) {
    for (int cn = 0; cn < m->class_count; cn++) {
      int mask_v = m->class_masks[cv];
      int mask_n = m->class_masks[cn];
      const uint64_t *domain_v = m->domain_by_present + (size_t)mask_v * m->word_count;
      const uint64_t *domain_n = m->domain_by_present + (size_t)mask_n * m->word_count;
      for (int d = 0; d < WFC_DIRS; d++) {
        int consistent = 1;
        for (int tile = 0; tile < tile_count && consistent; tile++) {
          if ((domain_v[tile >> 6] & (1ull << (tile & 63))) == 0) continue;
          const uint64_t *row = m->compat + (size_t)(d * tile_count + tile) * m->word_count;
          int supported = 0;
          for (int k = 0; k < m->word_count; k++) {
            if ((row[k] & domain_n[k]) != 0) {
              supported = 1;
              break;
            }
          }
          if (!supported) consistent = 0;
        }
        m->class_consistent[((size_t)cv * m->class_count + cn) * WFC_DIRS + d] =
          (unsigned char)consistent;
      }
    }
  }
  return m;
}

void wfc_model_destroy(wfc_model *model) {
  if (model == NULL) return;
  free(model->tiles);
  free(model->source);
  free(model->present);
  free(model->step_offset);
  free(model->compat);

  free(model->domain_by_present);
  free(model->size_by_present);
  free(model->class_masks);
  free(model->class_consistent);
  free(model);
}

/* --- solver lifecycle ---------------------------------------------------------------------- */

wfc_solver *wfc_solver_create(void) {
  wfc_solver *s = calloc(1, sizeof(*s));
  if (s == NULL) return NULL;
  s->trail_cap = 1024;
  s->trail_key = calloc((size_t)s->trail_cap, sizeof(int));
  s->trail_value = calloc((size_t)s->trail_cap, sizeof(uint64_t));
  s->queue_cap = 4096;
  s->queue = calloc((size_t)s->queue_cap, sizeof(int));
  s->stack_cap = 256;
  s->stack = calloc((size_t)s->stack_cap, sizeof(wfc_frame));
  s->arena_cap = 4096;
  s->value_arena = malloc((size_t)s->arena_cap * sizeof(int));
  if (s->trail_key == NULL || s->trail_value == NULL || s->queue == NULL || s->stack == NULL ||
      s->value_arena == NULL) {
    wfc_solver_destroy(s);
    return NULL;
  }
  return s;
}

/*
 * Points the solver at [model], growing its buffers only when the model needs more room than they have.
 *
 * Capacities are monotonic, so a pooled solver settles after a few collapses and then runs with no
 * allocation at all -- which is the point of pooling: allocating and first-touching ~250 KB per worker
 * per keystroke was a measurable slice of a small solve.
 */
static int bind_model(wfc_solver *s, const wfc_model *m) {
  if (s->model == m && s->bound_variables == m->variable_count &&
      s->bound_words == m->word_count && s->bound_tiles == m->tile_count) {
    return 1;
  }
  /*
   * The size bitsets are one row per domain size and one *bit per variable*, so their row width follows
   * the grid. Growing the grid therefore invalidates them even when the tile count is unchanged -- a
   * pooled solver that ran a 76x135 grid and is then handed a 152x270 one would otherwise write past a
   * buffer sized for a quarter of the bits, which is a heap corruption that only shows up as a crash in
   * a worker thread. Both shapes are tracked: rows (tile count) and width (variable count).
   */
  int bitset_grew = s->bitset_words != ((m->variable_count + 63) >> 6);
  if (s->capacity_variables < m->variable_count || s->capacity_words < m->word_count) {
    size_t vars = (size_t)m->variable_count;
    uint64_t *domains = realloc(s->domains, vars * m->word_count * sizeof(uint64_t));
    if (domains == NULL) return 0;
    s->domains = domains;
    int *sizes = realloc(s->size, vars * sizeof(int));
    if (sizes == NULL) return 0;
    s->size = sizes;
    uint64_t *queue_flags = realloc(s->in_queue, ((vars * WFC_DIRS + 63) / 64) * sizeof(uint64_t));
    if (queue_flags == NULL) return 0;
    s->in_queue = queue_flags;
    int32_t *assignment = realloc(s->assignment, vars * sizeof(int32_t));
    if (assignment == NULL) return 0;
    s->assignment = assignment;
    s->bitset_words = (int)((m->variable_count + 63) >> 6);
    bitset_grew = 1;
    s->capacity_variables = m->variable_count;
    s->capacity_words = m->word_count;
  }
  if (s->capacity_tiles < m->tile_count || s->size_bits == NULL || bitset_grew) {
    /*
     * Sized for the larger of the two: a buffer that shrank on a model with fewer tiles would then be
     * written past by the next solve, which clears rows up to the largest domain size it has seen. Rows
     * are one per domain size, so capacity_tiles is the number of rows minus one.
     */
    size_t rows = (size_t)(m->tile_count > s->capacity_tiles ? m->tile_count : s->capacity_tiles) + 1;
    size_t words = rows * s->bitset_words;
    uint64_t *bits = realloc(s->size_bits, words * sizeof(uint64_t));
    if (bits == NULL) return 0;
    s->size_bits = bits;
    /*
     * realloc does not zero, and rows the *first* solve never writes would otherwise hold whatever the
     * allocator left there -- which select_variable happily reads as candidate bits. Clearing here (on
     * allocation only, which is rare) keeps the per-solve clear from seeing allocator leftovers.
     */
    memset(s->size_bits, 0, words * sizeof(uint64_t));
    double *keys = realloc(s->keys_scratch, (size_t)m->tile_count * sizeof(double));
    if (keys == NULL) return 0;
    s->keys_scratch = keys;
    s->capacity_tiles = (int)rows - 1;
  }
  s->model = m;
  s->bound_variables = m->variable_count;
  s->bound_words = m->word_count;
  s->bound_tiles = m->tile_count;
  return 1;
}

void wfc_solver_destroy(wfc_solver *solver) {
  if (solver == NULL) return;
  free(solver->domains);
  free(solver->size);
  free(solver->size_bits);
  free(solver->trail_key);
  free(solver->trail_value);
  free(solver->queue);
  free(solver->in_queue);
  free(solver->stack);
  free(solver->value_arena);
  free(solver->keys_scratch);
  free(solver->assignment);
  free(solver->sweep_order);
  free(solver->sweep_rank);
  free(solver);
}

const int32_t *wfc_solver_assignment(const wfc_solver *solver) {
  return solver == NULL ? NULL : solver->assignment;
}

void wfc_solver_abort(wfc_solver *solver) {
  if (solver != NULL) solver->abort_flag = 1;
}

int wfc_solver_decisions(const wfc_solver *solver) { return solver->decisions; }

int64_t wfc_solver_revises(const wfc_solver *solver) { return solver->revises; }

int64_t wfc_solver_dead_ends(const wfc_solver *solver) {
  return solver == NULL ? 0 : solver->dead_ends;
}

int64_t wfc_solver_shrinks(const wfc_solver *solver) {
  return solver == NULL ? 0 : solver->shrinks;
}

void wfc_solver_set_order(wfc_solver *solver, int mode) {
  if (solver == NULL) return;
  solver->order_mode = mode < 0 ? 0 : mode;
}

void wfc_solver_set_worklist(wfc_solver *solver, int mode) {
  if (solver == NULL) return;
  solver->worklist_mode = mode != 0 ? 1 : 0;
}

int wfc_model_tile_count(const wfc_model *model) { return model == NULL ? 0 : model->tile_count; }

int wfc_model_tile_center(const wfc_model *model, int tile) {
  if (model == NULL || tile < 0 || tile >= model->tile_count) return 0;
  return model->tiles[tile].centre;
}

int wfc_model_tile_source(const wfc_model *model, int tile) {
  if (model == NULL || tile < 0 || tile >= model->tile_count) return -1;
  return model->source[tile];
}

int wfc_model_variable_count(const wfc_model *model) {
  return model == NULL ? 0 : model->variable_count;
}

void wfc_solver_set_fine_profile(wfc_solver *solver, int enabled) {
  if (solver != NULL) solver->fine_profile = enabled;
}

int64_t wfc_solver_phase_nanos(const wfc_solver *solver, int phase) {
  if (solver == NULL) return 0;
  switch (phase) {
    case 0: return solver->setup_nanos;
    case 1: return solver->initial_propagation_nanos;
    case 2: return solver->search_nanos;
    case 3: return solver->selection_nanos;
    case 4: return solver->ordering_nanos;
    default: return 0;
  }
}

/* --- domains and the trail ------------------------------------------------------------------ */

/*
 * Sets or clears the bit for (size, variable). Sizes below 2 are not tracked: 1 means assigned and is
 * never selected again, 0 means a contradiction and aborts propagation anyway.
 */
static void set_size_bit(wfc_solver *s, int v, int size, int present) {
  if (size < 2 || size > s->model->tile_count) return;
  uint64_t *row = s->size_bits + (size_t)size * s->bitset_words;
  uint64_t bit = 1ull << (v & 63);
  if (present) {
    row[v >> 6] |= bit;
    if (size < s->smallest) s->smallest = size;
  } else {
    row[v >> 6] &= ~bit;
  }
}

/* Moves [v] to the new domain size's bitset. */
static void adjust_size(wfc_solver *s, int v, int new_size) {
  int old = s->size[v];
  if (old == new_size) return;
  set_size_bit(s, v, old, 0);
  s->size[v] = new_size;
  set_size_bit(s, v, new_size, 1);
}

/*
 * Allocation failures here would leave the search with a trail it cannot undo. Rather than crashing
 * the app, the solve is abandoned: the flag makes every loop unwind, and the next solve rebuilds the
 * domains from scratch, so a half-applied trail is never read.
 */
static int push_trail(wfc_solver *s, int v, int word, uint64_t old) {
  if (s->trail_size == s->trail_cap) {
    int grown = s->trail_cap * 2;
    int *keys = realloc(s->trail_key, (size_t)grown * sizeof(int));
    uint64_t *values = realloc(s->trail_value, (size_t)grown * sizeof(uint64_t));
    if (keys == NULL || values == NULL) {
      free(keys);
      free(values);
      s->abort_flag = 1;
      return 0;
    }
    s->trail_key = keys;
    s->trail_value = values;
    s->trail_cap = grown;
  }
  s->trail_key[s->trail_size] = (v << PACK_BITS) | word;
  s->trail_value[s->trail_size] = old;
  s->trail_size++;
  return 1;
}

/* Writes one domain word, recording the change so the search can undo it. */
static void set_word(wfc_solver *s, int v, int word, uint64_t value) {
  int index = v * s->model->word_count + word;
  uint64_t old = s->domains[index];
  if (old == value) return;
  if (!push_trail(s, v, word, old)) return;
  s->domains[index] = value;
  adjust_size(s, v, s->size[v] + popcount(value) - popcount(old));
}

static void undo(wfc_solver *s, int mark) {
  const int words = s->model->word_count;
  while (s->trail_size > mark) {
    s->trail_size--;
    int key = s->trail_key[s->trail_size];
    int v = key >> PACK_BITS;
    int word = key & PACK_MASK;
    int index = v * words + word;
    uint64_t old = s->trail_value[s->trail_size];
    adjust_size(s, v, s->size[v] + popcount(old) - popcount(s->domains[index]));
    s->domains[index] = old;
  }
}

static int single_value(const wfc_solver *s, int v) {
  int base = v * s->model->word_count;
  for (int word = 0; word < s->model->word_count; word++) {
    uint64_t bits = s->domains[base + word];
    if (bits != 0) return (word << 6) + ctz(bits);
  }
  return -1;
}

/* --- arc queue ------------------------------------------------------------------------------ */

static int queue_push(wfc_solver *s, int value) {
  if (s->queue_size == s->queue_cap) {
    int grown = s->queue_cap * 2;
    int *resized = calloc((size_t)grown, sizeof(int));
    if (resized == NULL) {
      /* Dropping an arc would leave propagation incomplete, so the solve is worthless: abandon it. */
      s->abort_flag = 1;
      return 0;
    }
    for (int i = 0; i < s->queue_size; i++) {
      resized[i] = s->queue[(s->queue_head + i) % s->queue_cap];
    }
    free(s->queue);
    s->queue = resized;
    s->queue_cap = grown;
    s->queue_head = 0;
  }
  s->queue[(s->queue_head + s->queue_size) % s->queue_cap] = value;
  s->queue_size++;
  return 1;
}

static int queue_pop(wfc_solver *s) {
  int slot = s->queue_head;
  if (s->worklist_mode != 0) {
    /* LIFO: take the most recently queued arc, i.e. the newest end of the ring. */
    slot = (s->queue_head + s->queue_size - 1) % s->queue_cap;
    s->queue_size--;
    if (s->queue_size == 0) s->queue_head = 0;
    return s->queue[slot];
  }
  int value = s->queue[s->queue_head];
  s->queue_head = (s->queue_head + 1) % s->queue_cap;
  s->queue_size--;
  /* Keep "empty" canonical: pushing only replays the head-to-tail walk when the head is zero. */
  if (s->queue_size == 0) s->queue_head = 0;
  return value;
}

static void mark_out(wfc_solver *s, int v, int d) {
  int index = v * WFC_DIRS + d;
  s->in_queue[index >> 6] &= ~(1ull << (index & 63));
}

/** Queues the arc (v, d): "revise v against its neighbour in direction d". */
static void enqueue(wfc_solver *s, int v, int d) {
  int index = v * WFC_DIRS + d;
  uint64_t mask = 1ull << (index & 63);
  int word = index >> 6;
  if ((s->in_queue[word] & mask) != 0) return;
  s->in_queue[word] |= mask;
  if (!queue_push(s, (v << PACK_BITS) | d)) {
    /* Undo the "already queued" mark so a retry is not silently skipped. */
    s->in_queue[word] &= ~mask;
  }
}

/* --- propagation ---------------------------------------------------------------------------- */

static int revise(wfc_solver *s, int v, int d) {
  const wfc_model *m = s->model;
  const int tiles = m->tile_count;
  const int words = m->word_count;
  if (((m->present[v] >> d) & 1) == 0) return 1;

  const int neighbour = v + m->step_offset[d];
  const int v_base = v * words;
  const int n_base = neighbour * words;
  int removed = 0;

  if (s->size[neighbour] == 1) {
    /*
     * The neighbour is assigned, so exactly one tile supports us and the revision is a single mask
     * intersection with its partner row: compatibility is symmetric, so the tiles that the assigned
     * tile supports in the opposite direction are the tiles it accepts in this one.
     */
    const int unit = single_value(s, neighbour);
    const uint64_t *row = m->compat + (size_t)(OPPOSITE[d] * tiles + unit) * words;
    for (int word = 0; word < words; word++) {
      uint64_t current = s->domains[v_base + word];
      if (current == 0) continue;
      uint64_t kept = current & row[word];
      if (kept != current) {
        removed += popcount(current) - popcount(kept);
        set_word(s, v, word, kept);
      }
    }
  } else if (words == 2) {
    /*
     * The dominant tile-set size (65..128 patterns) handled as one 128-bit AND per candidate.
     *
     * The two words of a domain and of a compatibility row are adjacent, so they load as one 16-byte
     * value and the support test becomes a single AND plus a zero test instead of two loads, two ANDs
     * and an OR. The rows live right next to each other (16 bytes apart), which keeps this loop in L1.
     *
     * Loaded through memcpy rather than a cast because the compatibility table is a uint64_t array;
     * clang turns that into the same 128-bit load without the aliasing trouble.
     */
    const uint64_t *neighbour_pair = s->domains + n_base;
    uint64_t neighbour0 = neighbour_pair[0];
    uint64_t neighbour1 = neighbour_pair[1];
    __uint128_t neighbour_bits = ((__uint128_t)neighbour1 << 64) | neighbour0;
    for (int word = 0; word < 2; word++) {
      uint64_t current = s->domains[v_base + word];
      if (current == 0) continue;
      uint64_t remaining = current;
      uint64_t bits = current;
      while (bits != 0) {
        int bit = ctz(bits);
        bits &= bits - 1;
        int tile = (word << 6) + bit;
        const uint64_t *row = m->compat + (size_t)(d * tiles + tile) * 2;
        __uint128_t partners;
        memcpy(&partners, row, sizeof(partners));
        if ((partners & neighbour_bits) == 0) remaining &= ~(1ull << bit);
      }
      if (remaining != current) {
        removed += popcount(current) - popcount(remaining);
        set_word(s, v, word, remaining);
      }
    }
  } else {
    /*
     * Exact revision, candidate by candidate: ask the neighbour's domain whether any of its tiles
     * supports each candidate.
     *
     * Two faster-looking alternatives were tried and measured on a 76x135 grid, and both lost:
     *
     *  - A word-parallel support table (union of the partners of each *word* of tiles) can only
     *    over-approximate, because a word holds 64 tiles. Weaker pruning keeps domains wide, and this
     *    search is fast precisely because propagation collapses the grid with almost no decisions:
     *    `rings` (104 tiles) solves with 23 decisions here, while the table version burned the full
     *    1,000,000-decision cap and never found a tiling.
     *  - Walking the neighbour's domain instead of the candidates (exact, and cheaper whenever the
     *    neighbour is smaller) computes the same sets and leaves the trajectory untouched, but measured
     *    47% slower on `bordered`: the union of rows costs a read-modify-write per tile, while the
     *    scan below reads one row per candidate and early-exits... it does not early-exit, but its
     *    hoisted words keep it ahead.
     *
     * Precision and a tight inner loop beat fewer operations here.
     *
     * There is a way to make a dense formulation exact: compatibility is equality of the *overlap*, so
     * the table could be keyed on overlap signatures and grouped by them ("union the groups of the
     * overlaps still present on the other side"), which is one 128-bit OR per group instead of one test
     * per candidate. It is exact, unlike the word-level table, and it dies on the alphabet size: measured
     * with the drawing's own tiles, `bordered` has 33 distinct orthogonal overlaps for 50 tiles and
     * `rings` 78 for 104 (diagonals 21 and 43). About 1.3 tiles per group, so the "few" groups are as
     * many as the candidates they would replace, and the grouping buys nothing.
     *
     * That is the same structural fact that makes the model un-prunable (see the note in
     * wfc_model_create): every tile is a window that really occurs in the drawing, so its overlap with
     * its neighbours is its own, and a tile set sampled from a hand-drawn picture has no structure to
     * quotient, no domain to shrink and no neighbour to skip. Account-level optimisations are closed;
     * what is left is doing fewer collapses (a warm start) or doing them on more cores.
     *
     * On SIMD: this loop is bit-serial by nature -- `bit = ctz(bits); bits &= bits - 1` walks the set
     * bits one at a time and the extracted bit indexes the next load -- so there is no vector register
     * to fill. The compiler agrees: with -Rpass-analysis=loop-vectorize it reports "cannot identify
     * array bounds" / "could not determine number of loop iterations" for every loop on the search path
     * and vectorises only the tile-set build. Measured on a Pixel 7a with -fvectorize -fslp-vectorize
     * versus -fno-vectorize -fno-slp-vectorize: 27.12 vs 27.08 ms (bordered) and 87.11 vs 87.08 ms
     * (rings), i.e. nothing.
     */
    if (words == 1) {
      const uint64_t neighbour_bits = s->domains[n_base];
      uint64_t current = s->domains[v_base];
      if (current != 0) {
        uint64_t remaining = current;
        uint64_t bits = current;
        while (bits != 0) {
          int bit = ctz(bits);
          bits &= bits - 1;
          const uint64_t *row = m->compat + (size_t)(d * tiles + bit);
          if ((row[0] & neighbour_bits) == 0) remaining &= ~(1ull << bit);
        }
        if (remaining != current) {
          removed += popcount(current) - popcount(remaining);
          set_word(s, v, 0, remaining);
        }
      }
    } else {
      for (int word = 0; word < words; word++) {
        uint64_t current = s->domains[v_base + word];
        if (current == 0) continue;
        uint64_t remaining = current;
        uint64_t bits = current;
        while (bits != 0) {
          int tile = (word << 6) + ctz(bits);
          bits &= bits - 1;
          const uint64_t *row = m->compat + (size_t)(d * tiles + tile) * words;
          int supported = 0;
          for (int k = 0; k < words; k++) {
            if ((row[k] & s->domains[n_base + k]) != 0) {
              supported = 1;
              break;
            }
          }
          if (!supported) remaining &= ~(1ull << (tile & 63));
        }
        if (remaining != current) {
          removed += popcount(current) - popcount(remaining);
          set_word(s, v, word, remaining);
        }
      }
    }
  }

  s->revises++;
  if (s->size[v] == 0) return 0;
  if (removed != 0) {
    s->shrinks++;
    /* This domain shrank, so every neighbour that constrains it has to be revised again. */
    for (int d2 = 0; d2 < WFC_DIRS; d2++) {
      if (((m->present[v] >> d2) & 1) != 0) {
        enqueue(s, v + m->step_offset[d2], OPPOSITE[d2]);
      }
    }
  }
  return 1;
}

static int propagate(wfc_solver *s, int64_t deadline_nanos) {
  if (s->abort_flag != 0) return 0;
  int since_clock = 0;
  while (s->queue_size > 0) {
    if (++since_clock >= CLOCK_EVERY) {
      since_clock = 0;
      if (s->abort_flag != 0 || now_nanos() > deadline_nanos) return 0;
    }
    int packed = queue_pop(s);
    int v = packed >> PACK_BITS;
    int d = packed & PACK_MASK;
    mark_out(s, v, d);
    if (!revise(s, v, d)) return 0;
  }
  return 1;
}

/* --- search --------------------------------------------------------------------------------- */

static void assign(wfc_solver *s, int v, int tile) {
  const wfc_model *m = s->model;
  int base = v * m->word_count;
  for (int word = 0; word < m->word_count; word++) {
    if (s->domains[base + word] != 0) set_word(s, v, word, 0);
  }
  set_word(s, v, tile >> 6, 1ull << (tile & 63));
  for (int d = 0; d < WFC_DIRS; d++) {
    if (((m->present[v] >> d) & 1) != 0) enqueue(s, v + m->step_offset[d], OPPOSITE[d]);
  }
}

/*
 * Builds the "peel" order: the border ring first, then each ring inwards, each ring walked clockwise
 * from its bottom-left corner. That is the order the app's author proposed -- fill the border, then the
 * bottom-left corner's surroundings, then the next pixels around them -- and it is the natural way to
 * keep the decided region compact while it grows.
 *
 * Why the order could matter at all: propagation is cheap against a decided cell (one 128-bit AND) and
 * only expensive against a wide-open one (a scan of every remaining candidate). Choosing where to
 * branch decides how much of each cascade runs through wide-open cells. It is not obvious which way
 * that cuts -- peeling from the border downwards could equally make the last decision responsible for
 * a long cascade -- so the order is selectable and measured rather than assumed.
 */
static int build_sweep(wfc_solver *s) {
  const wfc_model *m = s->model;
  const int vars = m->variable_count;
  const int columns = m->columns;
  const int rows = m->rows;
  if (s->sweep_cap < vars) {
    int grown = s->sweep_cap < 256 ? 256 : s->sweep_cap;
    while (grown < vars) grown *= 2;
    int *order = realloc(s->sweep_order, (size_t)grown * sizeof(int));
    if (order == NULL) return 0;
    s->sweep_order = order;
    int *rank = realloc(s->sweep_rank, (size_t)grown * sizeof(int));
    if (rank == NULL) return 0;
    s->sweep_rank = rank;
    s->sweep_cap = grown;
  }
  for (int v = 0; v < vars; v++) s->sweep_rank[v] = -1;

  int filled = 0;
  int rings = (columns < rows ? columns : rows);
  rings = (rings + 1) / 2;
#define ADD_CELL(x, y)                                    \
  do {                                                    \
    int v_ = (y) * columns + (x);                         \
    if (s->sweep_rank[v_] < 0) {                          \
      s->sweep_rank[v_] = filled;                         \
      s->sweep_order[filled++] = v_;                      \
    }                                                     \
  } while (0)
  for (int r = 0; r < rings; r++) {
    const int left = r;
    const int right = columns - 1 - r;
    const int top = r;
    const int bottom = rows - 1 - r;
    if (left > right || top > bottom) break;
    /* Clockwise from the ring's bottom-left corner: up the left side, along the top, down the right,
     * then back along the bottom. Cells shared with a smaller ring are skipped, so a degenerate ring
     * (a single row or a single cell) still adds each of its cells exactly once. */
    for (int y = bottom; y >= top; y--) ADD_CELL(left, y);
    for (int x = left + 1; x <= right; x++) ADD_CELL(x, top);
    for (int y = top + 1; y <= bottom; y++) ADD_CELL(right, y);
    for (int x = right - 1; x >= left + 1; x--) ADD_CELL(x, bottom);
  }
#undef ADD_CELL
  s->sweep_cursor = 0;
  return filled == vars;
}

static int select_variable(wfc_solver *s) {
  const int tiles = s->model->tile_count;
  const int words = s->bitset_words;

  if (s->order_mode == 1) {
    /*
     * Pure peel: the next undecided cell in the order above, ignoring domain sizes. Propagation usually
     * has already assigned most of the grid, so the cursor mostly skips over cells rather than choosing
     * them -- which is what makes a fixed order affordable at all.
     */
    const int vars = s->model->variable_count;
    int i = s->sweep_cursor;
    while (i < vars && s->size[s->sweep_order[i]] <= 1) i++;
    if (i >= vars) {
      s->sweep_cursor = vars;
      return -1;
    }
    s->sweep_cursor = i + 1;
    return s->sweep_order[i];
  }

  if (s->smallest < 2) s->smallest = 2;
  while (s->smallest <= tiles) {
    const uint64_t *row = s->size_bits + (size_t)s->smallest * words;
    if (s->order_mode != 2) {
      for (int word = 0; word < words; word++) {
        if (row[word] != 0) return (word << 6) + ctz(row[word]);
      }
    } else {
      /*
       * Min domain as usual, but ties go to the cell that is peeled earliest. Keeps the strong
       * heuristic (never branch on a cell that propagation could still narrow) while making the
       * decided region grow outward-in instead of jumping around the grid.
       *
       * This costs a full scan of the winning row instead of stopping at the first set bit. At 152x270
       * that row is up to 641 words, so the extra cost is only worth paying if it buys fewer decisions.
       */
      int best = -1;
      int best_rank = s->model->variable_count + 1;
      for (int word = 0; word < words; word++) {
        uint64_t bits = row[word];
        while (bits != 0) {
          int v = (word << 6) + ctz(bits);
          bits &= bits - 1;
          int rank = s->sweep_rank[v];
          if (rank < best_rank) {
            best_rank = rank;
            best = v;
          }
        }
      }
      if (best >= 0) return best;
    }
    s->smallest++;
  }
  return -1;
}

static void collect_values(wfc_solver *s, int v, int *values, int count) {
  const int words = s->model->word_count;
  int base = v * words;
  int filled = 0;
  /*
   * The keys go into a per-solver scratch buffer rather than an allocation per decision. Ordering by
   * log(random) / weight is the same order as the textbook random^(1/weight) race -- it is a monotone
   * transform of it, sorted the other way round -- for one log instead of a pow per candidate, and
   * log() is markedly cheaper than pow().
   */
  double *keys = s->keys_scratch;
  for (int word = 0; word < words; word++) {
    uint64_t bits = s->domains[base + word];
    while (bits != 0) {
      int tile = (word << 6) + ctz(bits);
      bits &= bits - 1;
      values[filled] = tile;
      double weight = (double)s->model->tiles[tile].weight;
      if (weight < 1e-6) weight = 1e-6;
      keys[filled] = log(rng_double(&s->rng)) / weight;
      filled++;
    }
  }
  /* Insertion sort, largest key first: weighted sampling without replacement. */
  for (int i = 1; i < count; i++) {
    double key = keys[i];
    int value = values[i];
    int j = i - 1;
    while (j >= 0 && keys[j] < key) {
      keys[j + 1] = keys[j];
      values[j + 1] = values[j];
      j--;
    }
    keys[j + 1] = key;
    values[j + 1] = value;
  }
}

/**
 * Reserves [count] ints at the top of the value arena for a new frame.
 *
 * @return the offset to write the candidate tiles at, or -1 when the arena cannot grow.
 */
static int reserve_values(wfc_solver *s, int count) {
  if (s->arena_size + count > s->arena_cap) {
    int needed = s->arena_size + count;
    int grown = s->arena_cap * 2;
    while (grown < needed) grown *= 2;
    int *resized = realloc(s->value_arena, (size_t)grown * sizeof(int));
    if (resized == NULL) return -1;
    s->value_arena = resized;
    s->arena_cap = grown;
  }
  return s->arena_size;
}

static int push_frame(wfc_solver *s, int v, int values_offset, int count, int trail_mark) {
  if (s->stack_size == s->stack_cap) {
    int grown = s->stack_cap * 2;
    wfc_frame *resized = realloc(s->stack, (size_t)grown * sizeof(wfc_frame));
    if (resized == NULL) return 0;
    s->stack = resized;
    s->stack_cap = grown;
  }
  wfc_frame *frame = &s->stack[s->stack_size++];
  frame->var = v;
  frame->values_offset = values_offset;
  frame->count = count;
  frame->next = 0;
  frame->trail_mark = trail_mark;
  frame->order_pos = s->order_mode == 1 ? s->sweep_rank[v] : 0;
  s->arena_size = values_offset + count;
  return 1;
}

static void free_frames(wfc_solver *s) {
  s->stack_size = 0;
  s->arena_size = 0;
}

static int search(wfc_solver *s, int64_t deadline_nanos, int max_decisions) {
  while (1) {
    if (s->abort_flag != 0 || now_nanos() > deadline_nanos || s->decisions > max_decisions) {
      free_frames(s);
      return 0;
    }
    /*
     * The two per-decision probes are off unless asked for. Each costs a clock read, and on a device
     * where clock_gettime is a syscall rather than a vDSO call that is microseconds per decision --
     * enough to show up as most of the "selection" time it is supposed to be measuring.
     */
    int64_t phase_started = s->fine_profile ? now_nanos() : 0;
    int v = select_variable(s);
    if (s->fine_profile) s->selection_nanos += now_nanos() - phase_started;
    if (v < 0) {
      free_frames(s);
      return 1;
    }

    phase_started = s->fine_profile ? now_nanos() : 0;
    int count = s->size[v];
    int values_offset = reserve_values(s, count);
    if (values_offset < 0) {
      free_frames(s);
      return 0;
    }
    int *values = s->value_arena + values_offset;
    collect_values(s, v, values, count);
    if (s->fine_profile) s->ordering_nanos += now_nanos() - phase_started;
    if (!push_frame(s, v, values_offset, count, s->trail_size)) {
      free_frames(s);
      return 0;
    }

    int descended = 0;
    while (s->stack_size > 0) {
      if (s->abort_flag != 0) {
        free_frames(s);
        return 0;
      }
      wfc_frame *frame = &s->stack[s->stack_size - 1];
      if (frame->next >= frame->count) {
        undo(s, frame->trail_mark);
        /* The frame is gone, so the peel cursor has to go back to its cell: that cell is undecided
         * again and the next decision may well land on it. */
        if (s->order_mode == 1) s->sweep_cursor = frame->order_pos;
        /* Popping is just dropping the arena back to this frame's slice: nothing to free. */
        s->arena_size = frame->values_offset;
        s->stack_size--;
        continue;
      }
      int tile = s->value_arena[frame->values_offset + frame->next++];
      s->decisions++;
      /* Rewind to the state this level started from: a previous value may have been descended into. */
      int mark = frame->trail_mark;
      undo(s, mark);
      assign(s, frame->var, tile);
      if (s->order_mode == 1) s->sweep_cursor = frame->order_pos + 1;
      if (propagate(s, deadline_nanos)) {
        descended = 1;
        break;
      }
      /* This value led to a contradiction somewhere: that is one dead end, and one branch wasted. */
      s->dead_ends++;
      undo(s, mark);
      if (s->order_mode == 1) s->sweep_cursor = frame->order_pos;
    }
    if (!descended && s->stack_size == 0) return 0;
  }
}

static int initialise_domains(wfc_solver *s) {
  const wfc_model *m = s->model;
  /*
   * Clear every bitset row the buffer has, not just the rows up to the previous solve's largest domain
   * size: a model with fewer tiles than the last one would otherwise leave rows that this solve never
   * writes and select_variable still reads. The whole buffer is a few hundred KB at 152x270, so clearing
   * it is tens of microseconds.
   */
  memset(s->size_bits, 0, sizeof(uint64_t) * (size_t)(s->capacity_tiles + 1) * s->bitset_words);
  memset(s->in_queue, 0, sizeof(uint64_t) * (((size_t)m->variable_count * WFC_DIRS + 63) / 64));
  s->smallest = m->tile_count + 1;
  for (int v = 0; v < m->variable_count; v++) {
    int present = m->present[v];
    int size = m->size_by_present[present];
    const uint64_t *row = m->domain_by_present + (size_t)present * m->word_count;
    int base = v * m->word_count;
    /*
     * Copied as one memcpy rather than a word loop so the two-word case (the usual one above 64 tiles)
     * is a single load/store pair instead of a runtime-length loop. This is deliberately not claimed as
     * a win: measured against the loop it was inside the noise. It is simply the shape clang lowers
     * best, and the rest of the search path has no such shape -- see the note on revise().
     */
    memcpy(&s->domains[base], row, sizeof(uint64_t) * (size_t)m->word_count);
    s->size[v] = size;
    if (size == 0) return 0; /* nothing fits this cell: no tiling exists */
    set_size_bit(s, v, size, 1);
  }
  if (s->smallest > m->tile_count) s->smallest = 2;
  return 1;
}

/*
 * Seeds the worklist with the arcs that can actually prune.
 *
 * The edge rule makes the starting domains differ per cell type, so "the full domain" means full for
 * that cell's present-mask, not the whole tile set -- which is why an earlier attempt at skipping
 * "full to full" arcs was wrong. The model precomputes the real question instead: for this pair of
 * domain classes and this direction, does every tile of the first domain already have a supporter in
 * the second? If it does, revise() cannot remove anything, now or until some neighbour shrinks -- and
 * when that happens, revise() enqueues the arc itself.
 */
static void enqueue_seed_arcs(wfc_solver *s) {
  const wfc_model *m = s->model;
  for (int v = 0; v < m->variable_count; v++) {
    int class_v = m->class_of_mask[m->present[v]];
    for (int d = 0; d < WFC_DIRS; d++) {
      if (((m->present[v] >> d) & 1) == 0) continue;
      int neighbour = v + m->step_offset[d];
      int class_n = m->class_of_mask[m->present[neighbour]];
      if (m->class_consistent[((size_t)class_v * m->class_count + class_n) * WFC_DIRS + d] == 0) {
        enqueue(s, v, d);
      }
    }
  }
}
static int extract_solution(wfc_solver *s, int32_t *assignment) {
  const wfc_model *m = s->model;
  for (int v = 0; v < m->variable_count; v++) {
    if (s->size[v] != 1) return 0;
    assignment[v] = single_value(s, v);
  }
  return 1;
}

void wfc_solver_finish(wfc_solver *s) {
  if (s == NULL) return;
  /*
   * Called once the caller has taken the result away (the JNI layer copies it into a Java array first).
   * Everything the next collapse would otherwise have to distrust is cleared here rather than at the
   * start of that collapse:
   *
   *  - the solution buffer, so a failed or aborted solve cannot leave a previous tiling lying around
   *    for anything that reads it out of turn;
   *  - the search state -- frames, value arena, trail and arc queue -- so the solver sits in a
   *    known-empty state between runs and can be pooled and reused safely;
   *  - the model pointer, which must not outlive wfc_model_destroy.
   *
   * The domains, sizes and size bitsets are *not* cleared here: initialise_domains() rewrites every one
   * of those before anything reads them, so clearing them here would be a 164 KB memset per collapse
   * no gain. The invariant is "every buffer is written before it is read inside a solve"; the result is
   * the only thing that outlives a solve, so that is what the handoff resets.
   */
  if (s->assignment != NULL && s->bound_variables > 0) {
    memset(s->assignment, 0, sizeof(int32_t) * (size_t)s->bound_variables);
  }
  free_frames(s);
  s->trail_size = 0;
  s->queue_head = 0;
  s->queue_size = 0;
  s->model = NULL;
}

int wfc_solver_solve(wfc_solver *s, const wfc_model *model, uint32_t seed, int64_t deadline_nanos,int max_decisions) {
  if (s == NULL || model == NULL) return 0;
  if (!bind_model(s, model)) return 0;
  /* A previous solve that ended with frames still open (every solution does) must not leak them. */
  free_frames(s);
  s->rng = seed == 0 ? 0x9E3779B9u : seed;
  s->decisions = 0;
  s->revises = 0;
  s->dead_ends = 0;
  s->shrinks = 0;
  s->trail_size = 0;
  s->queue_head = 0;
  s->queue_size = 0;
  s->abort_flag = 0;
  /* The peel depends only on the grid, so it is rebuilt per solve rather than per decision. */
  if (s->order_mode != 0 && !build_sweep(s)) return 0;

  s->setup_nanos = 0;
  s->initial_propagation_nanos = 0;
  s->search_nanos = 0;
  s->selection_nanos = 0;
  s->ordering_nanos = 0;

  int64_t phase_started = now_nanos();
  if (!initialise_domains(s)) return 0;
  enqueue_seed_arcs(s);
  s->setup_nanos = now_nanos() - phase_started;
  if (s->abort_flag != 0) return 0;

  phase_started = now_nanos();
  int consistent = propagate(s, deadline_nanos);
  s->initial_propagation_nanos = now_nanos() - phase_started;
  if (!consistent) return 0;

  phase_started = now_nanos();
  int solved = search(s, deadline_nanos, max_decisions);
  s->search_nanos = now_nanos() - phase_started;
  if (!solved) return 0;
  return extract_solution(s, s->assignment);
}