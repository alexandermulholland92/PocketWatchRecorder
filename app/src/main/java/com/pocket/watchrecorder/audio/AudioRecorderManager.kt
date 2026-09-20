package com.pocket.watchrecorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

/**
 * Thin, defensive wrapper around [MediaRecorder].
 *
 * Owns the recorder's whole lifecycle and the on-disk file, so callers never
 * have to reason about the state machine. Every terminal path releases the
 * native recorder and every failure path deletes the orphaned file.
 *
 * Not thread-safe: drive it from a single thread (the main thread is fine —
 * start/stop are fast).
 */
/** Thrown when there isn't enough room to record safely. */
class InsufficientStorageException(val freeBytes: Long) :
    IllegalStateException("Only ${freeBytes / (1024 * 1024)} MB free")

class AudioRecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorderManager"

        /** Must match the content_type sent to the provisioning endpoint. */
        const val CONTENT_TYPE = "audio/m4a"
        const val FILE_EXTENSION = "m4a"

        private const val SAMPLE_RATE_HZ = 44_100
        private const val BIT_RATE_BPS = 64_000       // ~480 KB/min mono
        private const val CHANNEL_COUNT = 1

        /** MediaRecorder.stop() throws if the muxer never got a valid frame. */
        private const val MIN_VALID_DURATION_MS = 1_000L

        /**
         * Recording length is uncapped. The only limit left is free space, so
         * we refuse to start below this rather than let MediaRecorder die
         * mid-take — an MPEG-4 file whose moov atom was never written is not
         * recoverable, and a long recording is a lot to lose that way.
         */
        private const val MIN_FREE_BYTES = 64L * 1024 * 1024         // 64 MB

        /** getMaxAmplitude() is 16-bit signed full scale. */
        private const val MAX_AMPLITUDE = 32_767f
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtElapsedMs: Long = 0L

    val isRecording: Boolean
        get() = recorder != null

    val elapsedMillis: Long
        get() = if (isRecording) SystemClock.elapsedRealtime() - startedAtElapsedMs else 0L

    private val outputDir: File
        get() = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * Begins capture into a fresh file in the app's private storage.
     *
     * The caller is responsible for holding RECORD_AUDIO before calling this.
     *
     * @return the file being written to.
     * @throws IllegalStateException if already recording.
     * @throws java.io.IOException if the encoder could not be prepared.
     */
    @SuppressLint("MissingPermission")
    fun start(): File {
        check(recorder == null) { "start() called while already recording" }

        val free = outputDir.usableSpace
        if (free in 1 until MIN_FREE_BYTES) {
            throw InsufficientStorageException(free)
        }

        val file = File(outputDir, "watch_${timestamp()}.$FILE_EXTENSION")
        val newRecorder = createRecorder()

        try {
            newRecorder.apply {
                // MIC keeps the platform's default tuning. VOICE_RECOGNITION is
                // sometimes cleaner for ASR (no AGC pumping) but is not honoured
                // uniformly across Wear OEMs — switch only after A/B testing.
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(CHANNEL_COUNT)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE_BPS)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start recording", t)
            runCatching { newRecorder.reset() }
            runCatching { newRecorder.release() }
            file.delete()
            throw t
        }

        recorder = newRecorder
        currentFile = file
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Recording -> ${file.absolutePath}")
        return file
    }

    /**
     * Stops capture and finalises the container.
     *
     * @return the finished file, or `null` if the take was too short / produced
     *         no valid audio. In the null case the partial file is deleted.
     */
    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        val file = currentFile
        val duration = elapsedMillis

        recorder = null
        currentFile = null
        startedAtElapsedMs = 0L

        return try {
            activeRecorder.stop()
            activeRecorder.release()

            val valid = file != null &&
                    file.exists() &&
                    file.length() > 0L &&
                    duration >= MIN_VALID_DURATION_MS

            if (valid) {
                Log.i(TAG, "Stopped: ${file!!.length()} bytes / ${duration}ms")
                file
            } else {
                Log.w(TAG, "Discarding invalid take (${duration}ms, ${file?.length() ?: 0} bytes)")
                file?.delete()
                null
            }
        } catch (e: RuntimeException) {
            // stop() throws when no frames were muxed — i.e. the tap-tap case.
            Log.w(TAG, "stop() failed; discarding take", e)
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
            file?.delete()
            null
        }
    }

    /** Aborts the current take and deletes its file. Safe to call when idle. */
    fun cancel() {
        val activeRecorder = recorder ?: return
        val file = currentFile

        recorder = null
        currentFile = null
        startedAtElapsedMs = 0L

        runCatching { activeRecorder.stop() }
        runCatching { activeRecorder.reset() }
        runCatching { activeRecorder.release() }
        file?.delete()
    }

    // -----------------------------------------------------------------------
    // Metering
    // -----------------------------------------------------------------------

    /**
     * Input level in 0f..1f on a perceptual (log) curve, for the UI ring.
     * Returns 0f when idle or when the OEM recorder doesn't report amplitude.
     */
    fun normalizedLevel(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (amplitude <= 0) return 0f
        // Map roughly -40 dBFS..0 dBFS onto 0..1.
        val db = 20f * log10(amplitude / MAX_AMPLITUDE)
        return ((db + 40f) / 40f).coerceIn(0f, 1f)
    }

    // -----------------------------------------------------------------------
    // File management
    // -----------------------------------------------------------------------

    /** Deletes every stored take except [keep]. Call after a successful upload. */
    fun purgeAll(keep: File? = null) {
        outputDir.listFiles()?.forEach { candidate ->
            if (candidate.absolutePath != keep?.absolutePath) {
                runCatching { candidate.delete() }
            }
        }
    }

    /** The most recent take still on disk, if any — used to resume a failed upload. */
    fun latestPendingFile(): File? =
        outputDir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }

    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}