package io.amper.neuroos.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class AndroidScreenVisionStatus(
    val active: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val lastFrameAtEpochMs: Long? = null,
    val detail: String
)

/**
 * Process-local handoff between the foreground MediaProjection service and the active AMPER UI.
 *
 * It intentionally owns no Context, file, database, or sovereign memory reference. Projection
 * pixels remain in RAM and the complete latest frame is replaced atomically.
 */
object AndroidScreenVisionSessionBridge {
    private val frames = EphemeralScreenVisionFrameStore()
    private val lock = Any()
    private var status = AndroidScreenVisionStatus(
        active = false,
        detail = "Screen vision is not active"
    )

    fun status(): AndroidScreenVisionStatus = synchronized(lock) { status }

    fun latestAttachment(maxAgeMs: Long = 2_500L): Result<InferenceAttachment> = runCatching {
        val current = status()
        check(current.active) { "screen vision session is not active" }
        val snapshot = requireNotNull(frames.latest(maxAgeMs)) {
            "no fresh screen frame is available yet"
        }
        snapshot.toAttachment()
    }

    internal fun started(width: Int, height: Int) {
        frames.clear()
        synchronized(lock) {
            status = AndroidScreenVisionStatus(
                active = true,
                width = width,
                height = height,
                detail = "Device screen vision active"
            )
        }
    }

    internal fun publishFrame(bytes: ByteArray, capturedAtEpochMs: Long) {
        frames.publish(
            mediaType = "image/jpeg",
            displayName = "live-screen-$capturedAtEpochMs.jpg",
            bytes = bytes,
            capturedAtEpochMs = capturedAtEpochMs
        )
        synchronized(lock) {
            val current = status
            status = current.copy(
                active = true,
                lastFrameAtEpochMs = capturedAtEpochMs,
                detail = "Device screen vision active · fresh frame available"
            )
        }
    }

    internal fun stopped(detail: String) {
        frames.clear()
        synchronized(lock) {
            status = AndroidScreenVisionStatus(
                active = false,
                detail = detail
            )
        }
    }
}

/**
 * User-consent entrypoint for global device-screen vision.
 *
 * Calling [createConsentIntent] always delegates scope selection to Android's MediaProjection UI.
 * No projection starts until that result is returned and the foreground service is launched.
 */
class AndroidScreenVisionSession(context: Context) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    fun createConsentIntent(): Intent = manager.createScreenCaptureIntent()

    fun start(resultCode: Int, resultData: Intent): Result<Unit> = runCatching {
        require(resultCode == Activity.RESULT_OK) {
            "screen projection was not approved"
        }
        val serviceIntent = Intent(appContext, ScreenVisionProjectionService::class.java).apply {
            action = ScreenVisionProjectionService.ACTION_START
            putExtra(ScreenVisionProjectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenVisionProjectionService.EXTRA_RESULT_DATA, resultData)
        }
        appContext.startForegroundService(serviceIntent)
    }

    fun stop(): Result<Unit> = runCatching {
        val serviceIntent = Intent(appContext, ScreenVisionProjectionService::class.java).apply {
            action = ScreenVisionProjectionService.ACTION_STOP
        }
        appContext.startService(serviceIntent)
    }

    fun status(): AndroidScreenVisionStatus = AndroidScreenVisionSessionBridge.status()

    fun latestFrameAttachment(maxAgeMs: Long = 2_500L): Result<InferenceAttachment> =
        AndroidScreenVisionSessionBridge.latestAttachment(maxAgeMs)
}

/**
 * Foreground MediaProjection owner for Phase149.
 *
 * The service continuously drains the projection surface so Android never blocks on ImageReader,
 * but JPEG encoding is throttled and downscaled for mobile thermals. Only the latest bounded frame
 * survives in process memory; no screen image is written to disk or sovereign memory.
 */
class ScreenVisionProjectionService : Service() {
    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val terminating = AtomicBoolean(false)
    private var lastEncodedElapsedMs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projection = null
            terminateSession(
                detail = "Screen vision stopped by Android or the user",
                stopProjection = false
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> terminateSession("Screen vision stopped", stopProjection = true)
            ACTION_START -> startProjectionFromIntent(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        terminateSession("Screen vision service destroyed", stopProjection = true)
        super.onDestroy()
    }

    private fun startProjectionFromIntent(intent: Intent) {
        runCatching {
            startProjectionForeground()

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = readResultData(intent)
            require(resultCode == Activity.RESULT_OK && resultData != null) {
                "missing approved MediaProjection result"
            }

            if (projection != null || virtualDisplay != null) {
                terminateCaptureObjects(stopProjection = true)
            }
            terminating.set(false)

            val metrics = captureMetrics()
            val thread = HandlerThread("amper-screen-vision").also { it.start() }
            val handler = Handler(thread.looper)
            captureThread = thread
            captureHandler = handler

            val reader = ImageReader.newInstance(
                metrics.width,
                metrics.height,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            val activeProjection = requireNotNull(
                projectionManager.getMediaProjection(resultCode, resultData)
            ) { "Android did not return a MediaProjection token" }
            projection = activeProjection
            activeProjection.registerCallback(projectionCallback, handler)

            reader.setOnImageAvailableListener(
                { available ->
                    consumeLatestFrame(available)
                },
                handler
            )

            virtualDisplay = activeProjection.createVirtualDisplay(
                "AMPER Screen Vision",
                metrics.width,
                metrics.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            AndroidScreenVisionSessionBridge.started(metrics.width, metrics.height)
        }.onFailure { error ->
            terminateSession(
                detail = "Screen vision unavailable: " +
                    (error.message ?: error::class.java.simpleName),
                stopProjection = true
            )
        }
    }

    private fun consumeLatestFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        image.use {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nowElapsed - lastEncodedElapsedMs < MIN_ENCODE_INTERVAL_MS) return
            lastEncodedElapsedMs = nowElapsed

            runCatching {
                val bitmap = imageToBitmap(it)
                try {
                    val bounded = downscale(bitmap, MAX_FRAME_EDGE)
                    try {
                        val bytes = compressBoundedJpeg(bounded)
                        AndroidScreenVisionSessionBridge.publishFrame(
                            bytes = bytes,
                            capturedAtEpochMs = System.currentTimeMillis()
                        )
                    } finally {
                        if (bounded !== bitmap) bounded.recycle()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("projection image has no pixel plane")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride > 0 && rowStride > 0) { "invalid projection pixel layout" }
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return bitmap

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        bitmap.recycle()
        return cropped
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxEdge) return bitmap
        val scale = maxEdge.toDouble() / largest.toDouble()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressBoundedJpeg(bitmap: Bitmap): ByteArray {
        var quality = 86
        while (quality >= 55) {
            val out = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                "screen JPEG compression failed"
            }
            val bytes = out.toByteArray()
            if (bytes.size <= TARGET_FRAME_BYTES) return bytes
            quality -= 8
        }
        error("screen frame remains too large after bounded JPEG compression")
    }

    private fun startProjectionForeground() {
        val stopIntent = Intent(this, ScreenVisionProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("AMPER screen vision")
            .setContentText("Device screen is shared locally with AMPER while this session is active")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop screen vision",
                    stopPending
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun terminateSession(detail: String, stopProjection: Boolean) {
        if (!terminating.compareAndSet(false, true)) return
        terminateCaptureObjects(stopProjection)
        AndroidScreenVisionSessionBridge.stopped(detail)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun terminateCaptureObjects(stopProjection: Boolean) {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        val activeProjection = projection
        projection = null
        if (activeProjection != null) {
            runCatching { activeProjection.unregisterCallback(projectionCallback) }
            if (stopProjection) runCatching { activeProjection.stop() }
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AMPER screen vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible while AMPER has an active user-approved screen vision session"
                setShowBadge(false)
            }
        )
    }

    private data class CaptureMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )

    private fun captureMetrics(): CaptureMetrics {
        val density = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.maximumWindowMetrics.bounds
            CaptureMetrics(
                width = bounds.width().coerceAtLeast(1),
                height = bounds.height().coerceAtLeast(1),
                densityDpi = density.coerceAtLeast(1)
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            CaptureMetrics(
                width = metrics.widthPixels.coerceAtLeast(1),
                height = metrics.heightPixels.coerceAtLeast(1),
                densityDpi = density.coerceAtLeast(1)
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun readResultData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        const val ACTION_START = "io.amper.neuroos.screenvision.START"
        const val ACTION_STOP = "io.amper.neuroos.screenvision.STOP"
        const val EXTRA_RESULT_CODE = "screenvision.resultCode"
        const val EXTRA_RESULT_DATA = "screenvision.resultData"

        private const val NOTIFICATION_CHANNEL_ID = "amper-screen-vision"
        private const val NOTIFICATION_ID = 149
        private const val MIN_ENCODE_INTERVAL_MS = 450L
        private const val MAX_FRAME_EDGE = 1_280
        private const val TARGET_FRAME_BYTES = 4 * 1024 * 1024
    }
}
