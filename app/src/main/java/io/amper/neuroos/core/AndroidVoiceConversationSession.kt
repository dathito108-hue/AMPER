package io.amper.neuroos.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidVoiceSessionStatus(
    val active: Boolean,
    val listening: Boolean,
    val utteranceCount: Long = 0L,
    val lastUtteranceAtEpochMs: Long? = null,
    val detail: String
)

object AndroidVoiceSessionBridge {
    private val store = EphemeralVoiceUtteranceStore()
    private val lock = Any()
    private var status = AndroidVoiceSessionStatus(
        active = false,
        listening = false,
        detail = "Voice session is not active"
    )

    fun status(): AndroidVoiceSessionStatus = synchronized(lock) { status }

    fun latestAttachment(maxAgeMs: Long = 120_000L): Result<InferenceAttachment> = runCatching {
        val snapshot = requireNotNull(store.latest(maxAgeMs)) {
            "no fresh completed voice utterance is available"
        }
        snapshot.toAttachment()
    }

    fun clearLatest() {
        store.clear()
        synchronized(lock) {
            status = status.copy(
                lastUtteranceAtEpochMs = null,
                detail = if (status.active) {
                    "Voice session active · waiting for speech"
                } else {
                    "Voice session stopped · latest utterance cleared"
                }
            )
        }
    }

    internal fun started() {
        store.clear()
        synchronized(lock) {
            status = AndroidVoiceSessionStatus(
                active = true,
                listening = true,
                detail = "Voice session active · local VAD waiting for speech"
            )
        }
    }

    internal fun publish(encoded: EncodedLiveAudio, capturedAtEpochMs: Long) {
        store.publish(encoded, capturedAtEpochMs)
        synchronized(lock) {
            val current = status
            status = current.copy(
                active = true,
                listening = true,
                utteranceCount = current.utteranceCount + 1L,
                lastUtteranceAtEpochMs = capturedAtEpochMs,
                detail = "Voice utterance ready · ${encoded.metrics.durationMs} ms · RAM only"
            )
        }
    }

    internal fun stopped(detail: String) {
        synchronized(lock) {
            status = status.copy(
                active = false,
                listening = false,
                detail = detail
            )
        }
    }

    internal fun failed(detail: String) {
        store.clear()
        synchronized(lock) {
            status = AndroidVoiceSessionStatus(
                active = false,
                listening = false,
                detail = detail
            )
        }
    }
}

class AndroidVoiceConversationSession(context: Context) {
    private val appContext = context.applicationContext

    fun start(): Result<Unit> = runCatching {
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is not granted" }

        val intent = Intent(appContext, VoiceConversationForegroundService::class.java).apply {
            action = VoiceConversationForegroundService.ACTION_START
        }
        appContext.startForegroundService(intent)
    }

    fun stop(): Result<Unit> = runCatching {
        val intent = Intent(appContext, VoiceConversationForegroundService::class.java).apply {
            action = VoiceConversationForegroundService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    fun status(): AndroidVoiceSessionStatus = AndroidVoiceSessionBridge.status()

    fun latestUtteranceAttachment(maxAgeMs: Long = 120_000L): Result<InferenceAttachment> =
        AndroidVoiceSessionBridge.latestAttachment(maxAgeMs)

    fun clearLatestUtterance() {
        AndroidVoiceSessionBridge.clearLatest()
    }
}

class VoiceConversationForegroundService : Service() {
    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var activeRecorder: AudioRecord? = null
    @Volatile
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVoiceSession("Voice session stopped")
            ACTION_START -> startVoiceSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested.set(true)
        runCatching { activeRecorder?.stop() }
        worker?.interrupt()
        worker = null
        activeRecorder = null
        AndroidVoiceSessionBridge.stopped("Voice session service destroyed")
        super.onDestroy()
    }

    private fun startVoiceSession() {
        if (worker?.isAlive == true) return

        runCatching {
            check(
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) { "RECORD_AUDIO permission is not granted" }

            startVoiceForeground()
            stopRequested.set(false)
            AndroidVoiceSessionBridge.started()

            worker = Thread(
                { captureLoop() },
                "amper-voice-session"
            ).also { thread ->
                thread.start()
            }
        }.onFailure { error ->
            AndroidVoiceSessionBridge.failed(
                "Voice session unavailable: ${error.message ?: error::class.java.simpleName}"
            )
            stopSelf()
        }
    }

    private fun captureLoop() {
        val sampleRate = AndroidLiveAudioAttachmentCapture.SAMPLE_RATE_HZ
        val frameSamples = 320
        val minimumBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minimumBytes <= 0) {
            AndroidVoiceSessionBridge.failed("Voice session microphone buffer unavailable")
            stopSelf()
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBytes, frameSamples * 8)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            AndroidVoiceSessionBridge.failed("Voice session microphone failed to initialize")
            stopSelf()
            return
        }

        activeRecorder = recorder
        val assembler = BoundedVoiceUtteranceAssembler(
            sampleRateHz = sampleRate,
            frameSamples = frameSamples
        )
        val frame = ShortArray(frameSamples)

        try {
            recorder.startRecording()
            while (!stopRequested.get()) {
                val read = recorder.read(
                    frame,
                    0,
                    frame.size,
                    AudioRecord.READ_BLOCKING
                )
                when {
                    read > 0 -> {
                        val completed = assembler.feed(
                            if (read == frame.size) frame else frame.copyOf(read)
                        )
                        completed?.let {
                            AndroidVoiceSessionBridge.publish(
                                encoded = it,
                                capturedAtEpochMs = System.currentTimeMillis()
                            )
                        }
                    }
                    read == 0 -> continue
                    stopRequested.get() -> break
                    else -> error("voice session microphone read failed: $read")
                }
            }

            assembler.flushActive()?.let {
                AndroidVoiceSessionBridge.publish(
                    encoded = it,
                    capturedAtEpochMs = System.currentTimeMillis()
                )
            }
        } catch (error: Throwable) {
            if (!stopRequested.get()) {
                AndroidVoiceSessionBridge.failed(
                    "Voice session capture failed: ${error.message ?: error::class.java.simpleName}"
                )
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
            activeRecorder = null
            if (!stopRequested.get()) {
                AndroidVoiceSessionBridge.stopped("Voice session ended")
            }
            stopSelf()
        }
    }

    private fun stopVoiceSession(detail: String) {
        stopRequested.set(true)
        runCatching { activeRecorder?.stop() }
        worker?.interrupt()
        AndroidVoiceSessionBridge.stopped(detail)
        stopForegroundCompat()
        stopSelf()
    }

    private fun startVoiceForeground() {
        val stopIntent = Intent(this, VoiceConversationForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            151,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AMPER voice session")
            .setContentText("Microphone is active locally; speech is segmented in RAM")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop voice session",
                    stopPending
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AMPER voice session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Visible while AMPER has an explicit microphone voice session"
                setShowBadge(false)
            }
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "io.amper.neuroos.voicesession.START"
        const val ACTION_STOP = "io.amper.neuroos.voicesession.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "amper-voice-session"
        private const val NOTIFICATION_ID = 151
    }
}
