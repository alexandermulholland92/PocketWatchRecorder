package com.pocket.watchrecorder.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.pocket.watchrecorder.MainActivity
import com.pocket.watchrecorder.R
import com.pocket.watchrecorder.upload.UploadQueue
import com.pocket.watchrecorder.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the microphone for the lifetime of a take.
 *
 * Capture used to live in the ViewModel, kept alive by nothing more than
 * `View.keepScreenOn`. On API 30+ — this app's minSdk — a process that isn't
 * foreground is handed *silence* rather than an error, so a palm-cover, a
 * swipe back or an activity timeout produced a full-length recording of
 * nothing, which was then dutifully uploaded and summarized. A foreground
 * service with `microphone` type is the only supported way to keep recording
 * when the UI goes away.
 *
 * Started, never bound: the UI talks to it through [RecordingBus], so there is
 * no connection to tear down and the service outlives the Activity by design.
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"

        private const val ACTION_START = "com.pocket.watchrecorder.action.START_RECORDING"
        private const val ACTION_STOP = "com.pocket.watchrecorder.action.STOP_RECORDING"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1001

        private const val METER_INTERVAL_MS = 80L

        /** Begins a take. Must be called while the app is foreground. */
        fun start(context: Context) {
            context.startForegroundService(intent(context, ACTION_START))
        }

        /**
         * Ends the current take, queues it, and lets the service stop.
         *
         * Plain startService, not startForegroundService: if the service has
         * already died, startForegroundService would oblige us to post a
         * notification for a service that is about to stop, and failing to do
         * so inside five seconds crashes the app. Stop is always driven from
         * the visible UI, so the background-start restriction doesn't apply.
         */
        fun stop(context: Context) {
            runCatching { context.startService(intent(context, ACTION_STOP)) }
                .onFailure { Log.w(TAG, "Could not deliver stop", it) }
        }

        private fun intent(context: Context, action: String) =
            Intent(context, RecordingService::class.java).setAction(action)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder by lazy { AudioRecorderManager(this) }
    private val queue by lazy { UploadQueue(applicationContext) }

    private var meterJob: Job? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> finishRecording()
            else -> {
                // Restarted by the system with no action (START_NOT_STICKY makes
                // this unlikely, but a null intent is still possible).
                Log.w(TAG, "Unknown action ${intent?.action}; stopping")
                stopSelf()
            }
        }
        // Do not resurrect a take the system killed: the audio is unrecoverable
        // and a silent restart would just hold the mic for nothing.
        return START_NOT_STICKY
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    private fun beginRecording() {
        if (recorder.isRecording) return

        // Must come before recorder.start(): the system gives us about five
        // seconds from startForegroundService() to post a notification.
        if (!promoteToForeground()) return

        try {
            val file = recorder.start()
            Log.i(TAG, "Recording into ${file.name}")
        } catch (storage: InsufficientStorageException) {
            Log.e(TAG, "Not enough space to record", storage)
            fail("Storage full — ${storage.freeBytes / (1024 * 1024)} MB free")
            return
        } catch (t: Throwable) {
            Log.e(TAG, "Could not open the microphone", t)
            fail("Mic unavailable")
            return
        }

        meterJob = scope.launch {
            while (isActive && recorder.isRecording) {
                RecordingBus.publishMic(recorder.elapsedMillis, recorder.normalizedLevel())
                delay(METER_INTERVAL_MS)
            }
        }
    }

    private fun finishRecording() {
        if (stopping) return
        stopping = true

        meterJob?.cancel()
        // Read the length before stop() clears it — Pocket wants it at provisioning.
        val durationMs = recorder.elapsedMillis
        val file = recorder.stop()
        RecordingBus.clearMic()

        if (file == null) {
            fail("Too short — hold for a second")
            return
        }

        scope.launch {
            try {
                val entry = queue.enqueue(
                    audio = file,
                    title = "Recording", // local label only; Pocket supplies the real one
                    durationMs = durationMs
                )
                UploadWorker.schedule(applicationContext)
                RecordingBus.publish(RecordingBus.Outcome.Queued(entry.id))
            } catch (t: Throwable) {
                Log.e(TAG, "Could not queue the take", t)
                RecordingBus.publish(RecordingBus.Outcome.Failed("Could not save the recording"))
            } finally {
                finish()
            }
        }
    }

    private fun fail(message: String) {
        RecordingBus.clearMic()
        RecordingBus.publish(RecordingBus.Outcome.Failed(message))
        finish()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        meterJob?.cancel()
        // Killed mid-take: drop the partial file rather than leave it orphaned.
        if (recorder.isRecording) {
            Log.w(TAG, "Destroyed while recording; discarding the take")
            recorder.cancel()
            RecordingBus.clearMic()
        }
        scope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    private fun promoteToForeground(): Boolean = try {
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        true
    } catch (t: Throwable) {
        // Notifications denied, or the app was not in a state allowed to start
        // a microphone service. Recording anyway would capture silence.
        Log.e(TAG, "Could not start in the foreground", t)
        fail("Recording needs the app open")
        false
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
