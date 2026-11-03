package ca.justinmo.wfcbitmap.video
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/**
 * H.264 MP4 writer for the panning export.
 *
 * Frames are converted to YUV420 and fed to [MediaCodec] with explicit presentation times, so the clip
 * length comes from the requested duration and frame rate rather than from how long encoding happens
 * to take. [MediaMuxer] puts the encoded stream into an MP4 in the app's cache; the caller saves or
 * deletes it.
 */
internal object Mp4Encoder {

  /** Encodes [frameCount] frames at [fps] and returns the MP4 bytes. */
  fun encode(
    context: Context,
    width: Int,
    height: Int,
    frameCount: Int,
    fps: Int,
    frameAt: (Int) -> IntArray,
  ): ByteArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    require(frameCount > 0)
    require(fps in 1..60)

    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
      setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
      )
      setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceAtLeast(1_000_000))
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
    }
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    codec.start()

    val file = File.createTempFile("wfc-mp4-", ".mp4", context.cacheDir)
    val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val info = MediaCodec.BufferInfo()
    var track = -1

    fun drain(end: Boolean): Boolean {
      while (true) {
        val out = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
        when {
          out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return false
          out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
            track = muxer.addTrack(codec.outputFormat)
            muxer.start()
          }
          out >= 0 -> {
            val buffer = codec.getOutputBuffer(out)
            if (buffer != null && info.size > 0 && track >= 0) {
              buffer.position(info.offset)
              buffer.limit(info.offset + info.size)
              muxer.writeSampleData(track, buffer, info)
            }
            codec.releaseOutputBuffer(out, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
          }
        }
      }
    }

    try {
      for (frameIndex in 0 until frameCount) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) throw IOException("The video encoder did not accept frame $frameIndex")
        val image = codec.getInputImage(index)
          ?: throw IOException("The video encoder has no input image")
        fillYuv(image, frameAt(frameIndex), width, height)
        val pts = frameIndex * 1_000_000L / fps
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
        drain(end = false)
      }
      val index = codec.dequeueInputBuffer(10_000)
      if (index >= 0) {
        codec.queueInputBuffer(
          index, 0, 0, frameCount * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
      }
      drain(end = true)
    } finally {
      runCatching { codec.stop() }
      codec.release()
      runCatching { if (track >= 0) muxer.stop() }
      muxer.release()
    }

    val bytes = file.readBytes()
    file.delete()
    return bytes
  }

  /** ARGB -> YUV420, written into the codec's input [android.media.Image] planes. */
  private fun fillYuv(image: android.media.Image, frame: IntArray, width: Int, height: Int) {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    for (y in 0 until height) {
      val row = y * width
      for (x in 0 until width) {
        val colour = frame[row + x]
        val r = (colour ushr 16) and 0xFF
        val g = (colour ushr 8) and 0xFF
        val b = colour and 0xFF
        val luma = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
        yBuffer.put(y * yPlane.rowStride + x * yPlane.pixelStride, luma.toByte())
        if (y % 2 == 0 && x % 2 == 0) {
          val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
          val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
          val uIndex = (y / 2) * uPlane.rowStride + (x / 2) * uPlane.pixelStride
          val vIndex = (y / 2) * vPlane.rowStride + (x / 2) * vPlane.pixelStride
          uBuffer.put(uIndex, u.toByte())
          vBuffer.put(vIndex, v.toByte())
        }
      }
    }
  }
}