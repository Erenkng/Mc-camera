package com.erenkng.mccamera.video

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encodes the rendered mosaic into an H.264 MP4.
 *
 * The renderer draws each frame a second time into [inputSurface]; nothing is
 * read back to the CPU, so recording costs one extra draw call rather than a
 * pixel copy. Audio is not captured, which keeps the app free of the microphone
 * permission.
 */
class VideoRecorder(private val context: Context) {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var legacyFile: File? = null

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false

    var inputSurface: Surface? = null
        private set

    var width = 0
        private set
    var height = 0
        private set

    val isRecording: Boolean get() = encoder != null

    /** Nanosecond timestamp of the first frame, used to zero the timeline. */
    private var startNanos = 0L

    fun start(requestedWidth: Int, requestedHeight: Int): Boolean {
        check(encoder == null) { "Kayıt zaten sürüyor" }

        width = align16(requestedWidth)
        height = align16(requestedHeight)

        return try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 6)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            encoder = codec

            openMuxer()
            startNanos = 0L
            true
        } catch (e: Exception) {
            Log.e(TAG, "Kayıt başlatılamadı", e)
            releaseQuietly()
            false
        }
    }

    /** Converts a wall-clock timestamp into the encoder's zero-based timeline. */
    fun timestampFor(nanos: Long): Long {
        if (startNanos == 0L) startNanos = nanos
        return nanos - startNanos
    }

    fun drain(endOfStream: Boolean) {
        val codec = encoder ?: return
        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "signalEndOfInputStream başarısız", e)
            }
        }

        // Bounded so a codec that never reports end-of-stream cannot wedge the
        // render thread.
        var attempts = 0
        while (attempts++ < MAX_DRAIN_ATTEMPTS) {
            val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return

                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer?.addTrack(codec.outputFormat) ?: -1
                        muxer?.start()
                        muxerStarted = true
                    }
                }

                status >= 0 -> {
                    val buffer = codec.getOutputBuffer(status)
                    if (buffer != null && muxerStarted &&
                        bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(status, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /** Finishes the file and returns the gallery entry it landed in. */
    fun stop(): Uri? {
        if (encoder == null) return null
        try {
            drain(true)
        } catch (e: Exception) {
            Log.w(TAG, "Son kareler yazılamadı", e)
        }

        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Muxer durdurulamadı", e)
        }

        releaseQuietly()
        return publish()
    }

    private fun openMuxer() {
        val name = "MCCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Galeri kaydı oluşturulamadı")
            pendingUri = uri
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Dosya açılamadı")
            descriptor = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER,
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            legacyFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun publish(): Uri? {
        pendingUri?.let { uri ->
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
            pendingUri = null
            return uri
        }

        legacyFile?.let { file ->
            legacyFile = null
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, file.absolutePath)
            }
            return context.contentResolver
                .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }
        return null
    }

    private fun releaseQuietly() {
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null

        inputSurface?.release()
        inputSurface = null

        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        trackIndex = -1

        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
        descriptor = null
    }

    private fun align16(value: Int): Int = ((value + 8) / 16).coerceAtLeast(1) * 16

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FOLDER = "MC Camera"
        private const val FRAME_RATE = 30
        private const val TIMEOUT_US = 10_000L
        private const val MAX_DRAIN_ATTEMPTS = 500

        /** Short-side targets offered in settings. */
        val QUALITIES = intArrayOf(720, 1080)

        /** Fits a short-side target to an aspect ratio, capping the long side. */
        fun sizeFor(shortSide: Int, viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
            if (viewWidth <= 0 || viewHeight <= 0) return shortSide to shortSide
            val portrait = viewHeight >= viewWidth
            var w = if (portrait) shortSide else shortSide * viewWidth / viewHeight
            var h = if (portrait) shortSide * viewHeight / viewWidth else shortSide
            val longest = maxOf(w, h)
            if (longest > MAX_LONG_SIDE) {
                val scale = MAX_LONG_SIDE.toFloat() / longest
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
            return w to h
        }

        private const val MAX_LONG_SIDE = 1920
    }
}
