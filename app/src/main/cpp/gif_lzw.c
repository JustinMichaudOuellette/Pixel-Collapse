/*
 * The whole GIF encoder, in C.
 *
 * Kotlin supplies the output stream, the palette, the per-frame delays and a callback that returns one
 * ARGB frame. Everything after that -- palette-index mapping, GIF89a header and colour table, graphic
 * control extensions, image descriptors, LZW compression, sub-blocks and the trailer -- is built here and
 * written a frame at a time, so a long full-resolution clip never has to exist in memory as a whole.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define GIF_MAX_CODES 4096
#define GIF_HASH_SIZE 8192
#define GIF_HASH_MASK (GIF_HASH_SIZE - 1)
#define PALETTE_HASH_SIZE 512
#define PALETTE_HASH_MASK (PALETTE_HASH_SIZE - 1)

typedef struct {
  uint8_t *data;
  size_t size;
  size_t capacity;
} byte_buffer;

static int buffer_reserve(byte_buffer *buffer, size_t extra) {
  if (buffer->size + extra <= buffer->capacity) return 1;
  size_t wanted = buffer->capacity == 0 ? 4096 : buffer->capacity;
  while (wanted < buffer->size + extra) wanted *= 2;
  uint8_t *grown = realloc(buffer->data, wanted);
  if (grown == NULL) return 0;
  buffer->data = grown;
  buffer->capacity = wanted;
  return 1;
}

static int buffer_write(byte_buffer *buffer, const uint8_t *data, size_t length) {
  if (!buffer_reserve(buffer, length)) return 0;
  memcpy(buffer->data + buffer->size, data, length);
  buffer->size += length;
  return 1;
}

static int buffer_byte(byte_buffer *buffer, uint8_t value) {
  if (!buffer_reserve(buffer, 1)) return 0;
  buffer->data[buffer->size++] = value;
  return 1;
}

typedef struct {
  byte_buffer *out;
  uint32_t bits;
  int bit_count;
  uint8_t block[255];
  int block_used;
} gif_writer;

static int writer_flush_block(gif_writer *writer) {
  if (writer->block_used == 0) return 1;
  if (!buffer_byte(writer->out, (uint8_t)writer->block_used)) return 0;
  if (!buffer_write(writer->out, writer->block, (size_t)writer->block_used)) return 0;
  writer->block_used = 0;
  return 1;
}

static int writer_byte(gif_writer *writer, uint8_t value) {
  writer->block[writer->block_used++] = value;
  return writer->block_used == 255 ? writer_flush_block(writer) : 1;
}

static int writer_code(gif_writer *writer, int code, int code_size) {
  writer->bits |= (uint32_t)code << writer->bit_count;
  writer->bit_count += code_size;
  while (writer->bit_count >= 8) {
    if (!writer_byte(writer, (uint8_t)(writer->bits & 0xFFu))) return 0;
    writer->bits >>= 8;
    writer->bit_count -= 8;
  }
  return 1;
}

static int writer_finish(gif_writer *writer) {
  if (writer->bit_count > 0) {
    if (!writer_byte(writer, (uint8_t)(writer->bits & 0xFFu))) return 0;
    writer->bits = 0;
    writer->bit_count = 0;
  }
  if (!writer_flush_block(writer)) return 0;
  return buffer_byte(writer->out, 0);
}

static void dictionary_clear(int32_t *keys) {
  for (int i = 0; i < GIF_HASH_SIZE; i++) keys[i] = -1;
}

static int dictionary_find(const int32_t *keys, const int16_t *values, int key) {
  uint32_t slot = ((uint32_t)key * 2654435761u) & GIF_HASH_MASK;
  for (;;) {
    if (keys[slot] == key) return values[slot];
    if (keys[slot] == -1) return -1;
    slot = (slot + 1) & GIF_HASH_MASK;
  }
}

static void dictionary_put(int32_t *keys, int16_t *values, int key, int value) {
  uint32_t slot = ((uint32_t)key * 2654435761u) & GIF_HASH_MASK;
  while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) & GIF_HASH_MASK;
  keys[slot] = key;
  values[slot] = (int16_t)value;
}

static int encode_lzw(const uint8_t *pixels, int count, int min_code_size, byte_buffer *out) {
  if (count <= 0 || min_code_size < 2 || min_code_size > 8) return 0;

  gif_writer writer;
  memset(&writer, 0, sizeof(writer));
  writer.out = out;

  int32_t *keys = malloc(sizeof(int32_t) * GIF_HASH_SIZE);
  int16_t *values = malloc(sizeof(int16_t) * GIF_HASH_SIZE);
  if (keys == NULL || values == NULL) {
    free(keys);
    free(values);
    return 0;
  }
  dictionary_clear(keys);

  int clear_code = 1 << min_code_size;
  int end_code = clear_code + 1;
  int next_code = end_code + 1;
  int code_size = min_code_size + 1;
  int ok = writer_code(&writer, clear_code, code_size);

  int prefix = pixels[0];
  for (int i = 1; ok && i < count; i++) {
    int value = pixels[i];
    int key = (prefix << 8) | value;
    int existing = dictionary_find(keys, values, key);
    if (existing >= 0) {
      prefix = existing;
      continue;
    }

    ok = writer_code(&writer, prefix, code_size);
    if (!ok) break;
    if (next_code < GIF_MAX_CODES) {
      dictionary_put(keys, values, key, next_code++);
      if (next_code > (1 << code_size) && code_size < 12) code_size++;
    } else {
      ok = writer_code(&writer, clear_code, code_size);
      dictionary_clear(keys);
      next_code = end_code + 1;
      code_size = min_code_size + 1;
    }
    prefix = value;
  }

  if (ok) ok = writer_code(&writer, prefix, code_size);
  if (ok) ok = writer_code(&writer, end_code, code_size);
  if (ok) ok = writer_finish(&writer);

  free(keys);
  free(values);
  return ok;
}

static int table_size_for(int colours) {
  int size = 2;
  while (size < colours) size <<= 1;
  return size;
}

static int log2_int(int value) {
  int bits = 0;
  while (value > 1) {
    value >>= 1;
    bits++;
  }
  return bits;
}

static void write_short(byte_buffer *buffer, int value) {
  buffer_byte(buffer, (uint8_t)(value & 0xFF));
  buffer_byte(buffer, (uint8_t)((value >> 8) & 0xFF));
}

/* --- palette lookup ------------------------------------------------------------------------- */

typedef struct {
  int32_t keys[PALETTE_HASH_SIZE];
  int16_t values[PALETTE_HASH_SIZE];
  const int32_t *palette;
  int palette_size;
} palette_map;

static void palette_map_init(palette_map *map, const int32_t *palette, int palette_size) {
  map->palette = palette;
  map->palette_size = palette_size;
  for (int i = 0; i < PALETTE_HASH_SIZE; i++) map->keys[i] = -1;
  for (int i = 0; i < palette_size; i++) {
    int key = palette[i] & 0xFFFFFF;
    uint32_t slot = ((uint32_t)key * 2654435761u) & PALETTE_HASH_MASK;
    while (map->keys[slot] != -1 && map->keys[slot] != key) {
      slot = (slot + 1) & PALETTE_HASH_MASK;
    }
    map->keys[slot] = key;
    map->values[slot] = (int16_t)i;
  }
}

static int palette_index(const palette_map *map, int argb) {
  int rgb = argb & 0xFFFFFF;
  uint32_t slot = ((uint32_t)rgb * 2654435761u) & PALETTE_HASH_MASK;
  for (;;) {
    if (map->keys[slot] == rgb) return map->values[slot];
    if (map->keys[slot] == -1) break;
    slot = (slot + 1) & PALETTE_HASH_MASK;
  }

  int r = (rgb >> 16) & 0xFF;
  int g = (rgb >> 8) & 0xFF;
  int b = rgb & 0xFF;
  int best = 0;
  int best_distance = 1 << 30;
  for (int i = 0; i < map->palette_size; i++) {
    int colour = map->palette[i];
    int dr = r - ((colour >> 16) & 0xFF);
    int dg = g - ((colour >> 8) & 0xFF);
    int db = b - (colour & 0xFF);
    int distance = dr * dr + dg * dg + db * db;
    if (distance < best_distance) {
      best_distance = distance;
      best = i;
    }
  }
  return best;
}

/* --- JNI plumbing --------------------------------------------------------------------------- */

typedef struct {
  JNIEnv *env;
  jobject stream;
  jmethodID write;
  jobject provider;
  jmethodID invoke;
  jclass integer_class;
  jmethodID integer_value_of;
} gif_context;

static int java_write(gif_context *ctx, const uint8_t *data, size_t length) {
  if (length == 0) return 1;
  jbyteArray array = (*ctx->env)->NewByteArray(ctx->env, (jsize)length);
  if (array == NULL) return 0;
  (*ctx->env)->SetByteArrayRegion(ctx->env, array, 0, (jsize)length, (const jbyte *)data);
  (*ctx->env)->CallVoidMethod(ctx->env, ctx->stream, ctx->write, array);
  (*ctx->env)->DeleteLocalRef(ctx->env, array);
  return !(*ctx->env)->ExceptionCheck(ctx->env);
}

static int java_write_buffer(gif_context *ctx, const byte_buffer *buffer) {
  return java_write(ctx, buffer->data, buffer->size);
}

static jintArray next_frame(gif_context *ctx, int index) {
  jobject boxed = (*ctx->env)->CallStaticObjectMethod(
    ctx->env, ctx->integer_class, ctx->integer_value_of, (jint)index);
  if (boxed == NULL) return NULL;
  jobject result = (*ctx->env)->CallObjectMethod(ctx->env, ctx->provider, ctx->invoke, boxed);
  (*ctx->env)->DeleteLocalRef(ctx->env, boxed);
  if (result == NULL) return NULL;
  return (jintArray)result;
}

JNIEXPORT jboolean JNICALL
Java_ca_justinmo_wfcbitmap_video_GifNative_encode(
  JNIEnv *env, jobject thiz, jobject stream, jint width, jint height,
  jintArray palette_array, jintArray delays_array, jobject frame_provider) {
  (void)thiz;
  if (stream == NULL || palette_array == NULL || delays_array == NULL || frame_provider == NULL) {
    return JNI_FALSE;
  }
  if (width <= 0 || height <= 0) return JNI_FALSE;

  jsize palette_size = (*env)->GetArrayLength(env, palette_array);
  jsize frame_count = (*env)->GetArrayLength(env, delays_array);
  if (palette_size < 1 || palette_size > 256 || frame_count <= 0) return JNI_FALSE;

  jint *palette = (*env)->GetIntArrayElements(env, palette_array, NULL);
  jint *delays = (*env)->GetIntArrayElements(env, delays_array, NULL);
  if (palette == NULL || delays == NULL) {
    if (palette != NULL) (*env)->ReleaseIntArrayElements(env, palette_array, palette, JNI_ABORT);
    if (delays != NULL) (*env)->ReleaseIntArrayElements(env, delays_array, delays, JNI_ABORT);
    return JNI_FALSE;
  }

  int table_size = table_size_for((int)palette_size);
  int min_code_size = log2_int(table_size);
  if (min_code_size < 2) min_code_size = 2;
  int ok = 1;
  byte_buffer header = {0};
  byte_buffer frame_block = {0};
  byte_buffer pixels = {0};

  /* Header, logical screen descriptor and global colour table in one write. */
  static const char signature[] = "GIF89a";
  ok = buffer_write(&header, (const uint8_t *)signature, 6);
  write_short(&header, width);
  write_short(&header, height);
  buffer_byte(&header, (uint8_t)(0x80 | 0x70 | (log2_int(table_size) - 1)));
  buffer_byte(&header, 0);
  buffer_byte(&header, 0);
  for (int i = 0; i < table_size && ok; i++) {
    int colour = i < (int)palette_size ? palette[i] : 0;
    buffer_byte(&header, (uint8_t)((colour >> 16) & 0xFF));
    buffer_byte(&header, (uint8_t)((colour >> 8) & 0xFF));
    buffer_byte(&header, (uint8_t)(colour & 0xFF));
  }
  /* Netscape looping extension. */
  static const uint8_t loop[] = {
    0x21, 0xFF, 0x0B, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0',
    0x03, 0x01, 0x00, 0x00, 0x00,
  };
  ok = ok && buffer_write(&header, loop, sizeof(loop));

  jclass stream_class = (*env)->GetObjectClass(env, stream);
  jmethodID write_method = (*env)->GetMethodID(env, stream_class, "write", "([B)V");
  jclass function_class = (*env)->FindClass(env, "kotlin/jvm/functions/Function1");
  jmethodID invoke_method = (*env)->GetMethodID(
    env, function_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
  jclass integer_class = (*env)->FindClass(env, "java/lang/Integer");
  jmethodID value_of = (*env)->GetStaticMethodID(
    env, integer_class, "valueOf", "(I)Ljava/lang/Integer;");
  if (write_method == NULL || invoke_method == NULL || value_of == NULL) ok = 0;

  gif_context ctx = {
    .env = env,
    .stream = stream,
    .write = write_method,
    .provider = frame_provider,
    .invoke = invoke_method,
    .integer_class = integer_class,
    .integer_value_of = value_of,
  };

  ok = ok && java_write_buffer(&ctx, &header);
  palette_map map;
  palette_map_init(&map, (const int32_t *)palette, (int)palette_size);

  for (int frame = 0; ok && frame < frame_count; frame++) {
    jintArray frame_array = next_frame(&ctx, frame);
    if (frame_array == NULL || (*env)->ExceptionCheck(env)) {
      ok = 0;
      break;
    }
    jsize frame_length = (*env)->GetArrayLength(env, frame_array);
    if (frame_length != width * height) {
      (*env)->DeleteLocalRef(env, frame_array);
      ok = 0;
      break;
    }
    jint *argb = (*env)->GetIntArrayElements(env, frame_array, NULL);
    if (argb == NULL) {
      (*env)->DeleteLocalRef(env, frame_array);
      ok = 0;
      break;
    }

    pixels.size = 0;
    if (!buffer_reserve(&pixels, (size_t)frame_length)) {
      ok = 0;
    } else {
      for (int i = 0; i < (int)frame_length; i++) {
        pixels.data[i] = (uint8_t)palette_index(&map, argb[i]);
      }
      pixels.size = (size_t)frame_length;
    }
    (*env)->ReleaseIntArrayElements(env, frame_array, argb, JNI_ABORT);
    (*env)->DeleteLocalRef(env, frame_array);
    if (!ok) break;

    frame_block.size = 0;
    /* Graphic control extension. */
    buffer_byte(&frame_block, 0x21);
    buffer_byte(&frame_block, 0xF9);
    buffer_byte(&frame_block, 0x04);
    buffer_byte(&frame_block, 0x04);
    write_short(&frame_block, delays[frame] > 0 ? delays[frame] : 1);
    buffer_byte(&frame_block, 0);
    buffer_byte(&frame_block, 0);
    /* Image descriptor. */
    buffer_byte(&frame_block, 0x2C);
    write_short(&frame_block, 0);
    write_short(&frame_block, 0);
    write_short(&frame_block, width);
    write_short(&frame_block, height);
    buffer_byte(&frame_block, 0);
    buffer_byte(&frame_block, (uint8_t)min_code_size);
    ok = encode_lzw(pixels.data, (int)pixels.size, min_code_size, &frame_block);
    ok = ok && java_write_buffer(&ctx, &frame_block);
  }

  if (ok) {
    uint8_t trailer = 0x3B;
    ok = java_write(&ctx, &trailer, 1);
  }

  free(header.data);
  free(frame_block.data);
  free(pixels.data);
  (*env)->ReleaseIntArrayElements(env, palette_array, palette, JNI_ABORT);
  (*env)->ReleaseIntArrayElements(env, delays_array, delays, JNI_ABORT);
  return ok ? JNI_TRUE : JNI_FALSE;
}
