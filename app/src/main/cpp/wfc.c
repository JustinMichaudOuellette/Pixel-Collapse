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
  int *present;                /* per variable: direction bits with a neighbour inside the grid */
  int *step_offset;            /* per direction: dy * columns + dx */
  uint64_t *compat;            /* [(direction * tile_count + tile) * word_count + word] */
  uint64_t *domain_by_present; /* [present * word_count + word] */
  int *size_by_present;
};

typedef struct {
  int var;
  int *values;
  int count;
  int next;
  int trail_mark;
} wfc_frame;

struct wfc_solver {
  const wfc_model *model;

  uint64_t *domains; /* variable_count * word_count */
  int *size;         /* per variable: values left */
  int *hist;         /* per size: how many variables, the index min-domain selection searches */
  int smallest;

  int *trail_key; /* (variable << 4) | word */
  uint64_t *trail_value;
  int trail_size;
  int trail_cap;

  int *queue; /* (variable << 4) | direction */
  int queue_head;
  int queue_size;
  int queue_cap;
  uint64_t *in_queue; /* bitset over variable_count * WFC_DIRS */

  wfc_frame *stack;
  int stack_size;
  int stack_cap;

  uint32_t rng;
  int decisions;
  int64_t revises;
  volatile int abort_flag;
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

wfc_model *wfc_model_create(const int32_t *raw, int tile_count, int columns, int rows) {
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
  m->present = calloc((size_t)m->variable_count, sizeof(int));
  m->step_offset = calloc(WFC_DIRS, sizeof(int));
  m->compat = calloc((size_t)WFC_DIRS * tile_count * m->word_count, sizeof(uint64_t));
  m->domain_by_present = calloc(256 * (size_t)m->word_count, sizeof(uint64_t));
  m->size_by_present = calloc(256, sizeof(int));
  if (m->tiles == NULL || m->present == NULL || m->step_offset == NULL || m->compat == NULL ||
      m->domain_by_present == NULL || m->size_by_present == NULL) {
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
  }

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
   * The edge rule: a border cell's domain is the tiles whose mask is *exactly* the in-grid directions,
   * i.e. the ones sampled on that edge of the drawing. Interior cells therefore accept only complete
   * patterns, and an edge pattern can only ever sit on its own edge.
   */
  for (int p = 0; p < 256; p++) {
    uint64_t *row = m->domain_by_present + (size_t)p * m->word_count;
    int count = 0;
    for (int t = 0; t < tile_count; t++) {
      if (m->tiles[t].mask != p) continue;
      row[t >> 6] |= 1ull << (t & 63);
      count++;
    }
    m->size_by_present[p] = count;
  }
  return m;
}

void wfc_model_destroy(wfc_model *model) {
  if (model == NULL) return;
  free(model->tiles);
  free(model->present);
  free(model->step_offset);
  free(model->compat);
  free(model->domain_by_present);
  free(model->size_by_present);
  free(model);
}

/* --- solver lifecycle ---------------------------------------------------------------------- */

wfc_solver *wfc_solver_create(const wfc_model *model) {
  if (model == NULL) return NULL;
  wfc_solver *s = calloc(1, sizeof(*s));
  if (s == NULL) return NULL;
  s->model = model;
  size_t vars = (size_t)model->variable_count;
  s->domains = calloc(vars * (size_t)model->word_count, sizeof(uint64_t));
  s->size = calloc(vars, sizeof(int));
  s->hist = calloc((size_t)model->tile_count + 1, sizeof(int));
  s->in_queue = calloc((vars * WFC_DIRS + 63) / 64, sizeof(uint64_t));
  s->trail_cap = 1024;
  s->trail_key = calloc((size_t)s->trail_cap, sizeof(int));
  s->trail_value = calloc((size_t)s->trail_cap, sizeof(uint64_t));
  s->queue_cap = 4096;
  s->queue = calloc((size_t)s->queue_cap, sizeof(int));
  s->stack_cap = 256;
  s->stack = calloc((size_t)s->stack_cap, sizeof(wfc_frame));
  if (s->domains == NULL || s->size == NULL || s->hist == NULL || s->in_queue == NULL ||
      s->trail_key == NULL || s->trail_value == NULL || s->queue == NULL || s->stack == NULL) {
    wfc_solver_destroy(s);
    return NULL;
  }
  return s;
}

void wfc_solver_destroy(wfc_solver *solver) {
  if (solver == NULL) return;
  if (solver->stack != NULL) {
    for (int i = 0; i < solver->stack_size; i++) free(solver->stack[i].values);
  }
  free(solver->domains);
  free(solver->size);
  free(solver->hist);
  free(solver->trail_key);
  free(solver->trail_value);
  free(solver->queue);
  free(solver->in_queue);
  free(solver->stack);
  free(solver);
}

void wfc_solver_abort(wfc_solver *solver) {
  if (solver != NULL) solver->abort_flag = 1;
}

int wfc_solver_decisions(const wfc_solver *solver) { return solver->decisions; }

int64_t wfc_solver_revises(const wfc_solver *solver) { return solver->revises; }

int wfc_solver_variable_count(const wfc_solver *solver) {
  return solver == NULL ? 0 : solver->model->variable_count;
}

/* --- domains and the trail ------------------------------------------------------------------ */

static void adjust_size(wfc_solver *s, int v, int new_size) {
  int old = s->size[v];
  if (old == new_size) return;
  s->hist[old]--;
  s->size[v] = new_size;
  s->hist[new_size]++;
  if (new_size >= 2 && new_size < s->smallest) s->smallest = new_size;
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
  int value = s->queue[s->queue_head];
  s->queue_head = (s->queue_head + 1) % s->queue_cap;
  s->queue_size--;
  return value;
}

static void mark_out(wfc_solver *s, int v, int d) {
  int index = v * WFC_DIRS + d;
  s->in_queue[index >> 6] &= ~(1ull << (index & 63));
}

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

  s->revises++;
  if (s->size[v] == 0) return 0;
  if (removed != 0) {
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

static int select_variable(wfc_solver *s) {
  const int tiles = s->model->tile_count;
  if (s->smallest < 2) s->smallest = 2;
  for (int size = s->smallest; size <= tiles; size++) {
    if (s->hist[size] == 0) continue;
    s->smallest = size;
    for (int v = 0; v < s->model->variable_count; v++) {
      if (s->size[v] == size) return v;
    }
  }
  return -1;
}

static void collect_values(wfc_solver *s, int v, int *values, double *keys, int count) {
  const int words = s->model->word_count;
  int base = v * words;
  int filled = 0;
  for (int word = 0; word < words; word++) {
    uint64_t bits = s->domains[base + word];
    while (bits != 0) {
      int tile = (word << 6) + ctz(bits);
      bits &= bits - 1;
      values[filled] = tile;
      double weight = (double)s->model->tiles[tile].weight;
      if (weight < 1e-6) weight = 1e-6;
      keys[filled] = pow(rng_double(&s->rng), 1.0 / weight);
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

static int push_frame(wfc_solver *s, int v, int *values, int count, int trail_mark) {
  if (s->stack_size == s->stack_cap) {
    int grown = s->stack_cap * 2;
    wfc_frame *resized = realloc(s->stack, (size_t)grown * sizeof(wfc_frame));
    if (resized == NULL) return 0;
    s->stack = resized;
    s->stack_cap = grown;
  }
  wfc_frame *frame = &s->stack[s->stack_size++];
  frame->var = v;
  frame->values = values;
  frame->count = count;
  frame->next = 0;
  frame->trail_mark = trail_mark;
  return 1;
}

static void free_frames(wfc_solver *s) {
  for (int i = 0; i < s->stack_size; i++) free(s->stack[i].values);
  s->stack_size = 0;
}

static int search(wfc_solver *s, int64_t deadline_nanos, int max_decisions) {
  while (1) {
    if (s->abort_flag != 0 || now_nanos() > deadline_nanos || s->decisions > max_decisions) {
      free_frames(s);
      return 0;
    }
    int v = select_variable(s);
    if (v < 0) {
      free_frames(s);
      return 1;
    }

    int count = s->size[v];
    int *values = malloc((size_t)count * sizeof(int));
    double *keys = malloc((size_t)count * sizeof(double));
    if (values == NULL || keys == NULL) {
      free(values);
      free(keys);
      free_frames(s);
      return 0;
    }
    collect_values(s, v, values, keys, count);
    free(keys);
    if (!push_frame(s, v, values, count, s->trail_size)) {
      free(values);
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
        free(frame->values);
        s->stack_size--;
        continue;
      }
      int tile = frame->values[frame->next++];
      s->decisions++;
      /* Rewind to the state this level started from: a previous value may have been descended into. */
      int mark = frame->trail_mark;
      undo(s, mark);
      assign(s, frame->var, tile);
      if (propagate(s, deadline_nanos)) {
        descended = 1;
        break;
      }
      undo(s, mark);
    }
    if (!descended && s->stack_size == 0) return 0;
  }
}

static int initialise_domains(wfc_solver *s) {
  const wfc_model *m = s->model;
  memset(s->hist, 0, sizeof(int) * ((size_t)m->tile_count + 1));
  memset(s->in_queue, 0, sizeof(uint64_t) * (((size_t)m->variable_count * WFC_DIRS + 63) / 64));
  s->smallest = m->tile_count + 1;
  for (int v = 0; v < m->variable_count; v++) {
    int present = m->present[v];
    int size = m->size_by_present[present];
    const uint64_t *row = m->domain_by_present + (size_t)present * m->word_count;
    int base = v * m->word_count;
    for (int word = 0; word < m->word_count; word++) s->domains[base + word] = row[word];
    s->size[v] = size;
    s->hist[size]++;
    if (size == 0) return 0; /* nothing fits this cell: no tiling exists */
    if (size >= 2 && size < s->smallest) s->smallest = size;
  }
  if (s->smallest > m->tile_count) s->smallest = 2;
  return 1;
}

static int enqueue_all(wfc_solver *s) {
  const wfc_model *m = s->model;
  for (int v = 0; v < m->variable_count; v++) {
    for (int d = 0; d < WFC_DIRS; d++) {
      if (((m->present[v] >> d) & 1) != 0) enqueue(s, v, d);
    }
  }
  return 1;
}

static int extract_solution(wfc_solver *s, int32_t *assignment) {
  const wfc_model *m = s->model;
  for (int v = 0; v < m->variable_count; v++) {
    if (s->size[v] != 1) return 0;
    assignment[v] = single_value(s, v);
  }
  return 1;
}

int wfc_solver_solve(wfc_solver *s, uint32_t seed, int64_t deadline_nanos, int max_decisions,
                     int32_t *assignment) {
  if (s == NULL || assignment == NULL) return 0;
  /* A previous solve that ended with frames still open (every solution does) must not leak them. */
  free_frames(s);
  s->rng = seed == 0 ? 0x9E3779B9u : seed;
  s->decisions = 0;
  s->revises = 0;
  s->trail_size = 0;
  s->queue_head = 0;
  s->queue_size = 0;
  s->abort_flag = 0;

  if (!initialise_domains(s)) return 0;
  enqueue_all(s);
  if (s->abort_flag != 0) return 0;
  if (!propagate(s, deadline_nanos)) return 0;
  if (!search(s, deadline_nanos, max_decisions)) return 0;
  return extract_solution(s, assignment);
}
