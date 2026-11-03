package ca.justinmo.wfcbitmap.video

import java.io.OutputStream

/**
 * The native GIF encoder. It owns the whole file: header, palette table, per-frame extensions, LZW and
 * streaming writes. There is no Kotlin fallback.
 */
internal object GifNative {
  init {
    System.loadLibrary("wfc")
  }

  /**
   * Writes a complete looping GIF to [out].
   *
   * @return true when the whole file was written.
   */
  external fun encode(
    out: OutputStream,
    width: Int,
    height: Int,
    palette: IntArray,
    delays: IntArray,
    frameAt: (Int) -> IntArray,
  ): Boolean
}
