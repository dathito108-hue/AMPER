package io.amper.neuroos.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Phase 146 Android live-perception ingress.
 *
 * Capture is deliberately user initiated. Raw camera, screen and microphone samples are reduced
 * locally to bounded signal summaries before they enter the canonical [PerceptionBus]; raw media is
 * neither persisted nor placed in the sovereign prompt. This keeps the existing reasoning baseline
 * and model-routing contract intact while giving AMPER real device observations.
 */
class AndroidPerceptionCapture(
    context: Context,
    private val bus: PerceptionBus
) {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun ingestBitmap(
        modality: PerceptionModality,
        bitmap: Bitmap,
        source: String
    ): Result<Percept> = runCatching {
        require(
            modality == PerceptionModality.IMAGE ||
                modality == PerceptionModality.CAMERA ||
                modality == PerceptionModality.SCREEN
        ) { "bitmap modality must be image, camera, or screen" }
        val percept = bitmapPercept(modality, bitmap, source)
        bus.ingest(percept)
        percept
    }

    /**
     * Captures only this AMPER activity surface. It is not a background/global screen recorder and
     * does not require MediaProjection authority.
     */
    fun captureActivityScreen(activity: Activity): Result<Percept> = runCatching {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "activity screen capture must run on the main thread"
        }
        val view = activity.window.decorView
        require(view.width > 0 && view.height > 0) { "activity surface is not laid out" }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            ingestBitmap(
                modality = PerceptionModality.SCREEN,
                bitmap = bitmap,
                source = "android-app-screen"
            ).getOrThrow()
        } finally {
            bitmap.recycle()
        }
    }

    /** Samples microphone waveform statistics for a short bounded window. No PCM is retained. */
    fun captureMicrophone(durationMs: Long = 450L): Result<Percept> = runCatching {
        require(durationMs in 100L..2_000L) { "microphone sample window must be 100..2000 ms" }
        check(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is not granted" }

        val sampleRate = 16_000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        require(minimum > 0) { "microphone buffer size is unavailable" }
        val buffer = ShortArray((minimum / 2).coerceAtLeast(1_024))
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            encoding,
            maxOf(minimum, buffer.size * 2)
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "microphone recorder failed to initialize"
        }

        var count = 0L
        var sumSquares = 0.0
        var peak = 0
        var zeroCrossings = 0L
        var previous = 0
        val startedAt = System.nanoTime()
        val deadline = startedAt + durationMs * 1_000_000L

        try {
            recorder.startRecording()
            while (System.nanoTime() < deadline) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (index in 0 until read) {
                    val sample = buffer[index].toInt()
                    val magnitude = kotlin.math.abs(sample)
                    if (magnitude > peak) peak = magnitude
                    sumSquares += sample.toDouble() * sample.toDouble()
                    if (count > 0 && ((previous < 0 && sample >= 0) || (previous >= 0 && sample < 0))) {
                        zeroCrossings += 1
                    }
                    previous = sample
                    count += 1
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }

        require(count > 0) { "microphone produced no samples" }
        val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
        val rms = sqrt(sumSquares / count.toDouble()) / Short.MAX_VALUE.toDouble()
        val peakNormalized = peak.toDouble() / Short.MAX_VALUE.toDouble()
        val zeroCrossingRate = zeroCrossings.toDouble() / count.toDouble()

        val percept = Percept(
            modality = PerceptionModality.AUDIO,
            payload = buildString {
                append("sampleRateHz=").append(sampleRate)
                append(";durationMs=").append(elapsedMs)
                append(";samples=").append(count)
                append(";rms=").append(decimal(rms))
                append(";peak=").append(decimal(peakNormalized))
                append(";zeroCrossingRate=").append(decimal(zeroCrossingRate))
            },
            salience = 0.82,
            provenance = Provenance(
                source = "android-microphone",
                producer = "phase146-live-perception",
                confidence = 0.95
            )
        )
        bus.ingest(percept)
        percept
    }

    /** Collects one bounded fusion window from sensors physically available on the device. */
    fun captureSensors(windowMs: Long = 350L): Result<Percept> = runCatching {
        require(windowMs in 100L..2_000L) { "sensor sample window must be 100..2000 ms" }
        val requested = listOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer",
            Sensor.TYPE_GYROSCOPE to "gyroscope",
            Sensor.TYPE_LIGHT to "light",
            Sensor.TYPE_PROXIMITY to "proximity"
        )
        val sensors = requested.mapNotNull { (type, label) ->
            sensorManager.getDefaultSensor(type)?.let { Triple(type, label, it) }
        }
        require(sensors.isNotEmpty()) { "no supported live sensors are available" }

        val thread = HandlerThread("amper-perception-sensors").also { it.start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(sensors.size)
        val values = linkedMapOf<Int, FloatArray>()
        val seen = linkedSetOf<Int>()
        val lock = Any()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(lock) {
                    values[event.sensor.type] = event.values.copyOf()
                    if (seen.add(event.sensor.type)) {
                        latch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        try {
            sensors.forEach { (_, _, sensor) ->
                sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    handler
                )
            }
            latch.await(windowMs, TimeUnit.MILLISECONDS)
        } finally {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
        }

        val snapshot = synchronized(lock) { values.mapValues { (_, v) -> v.copyOf() } }
        require(snapshot.isNotEmpty()) { "sensors produced no samples" }
        val payload = sensors.joinToString(";") { (type, label, _) ->
            val sample = snapshot[type]
            if (sample == null) {
                label + "=unavailable-in-window"
            } else {
                label + "=" + sample.joinToString(",") { decimal(it.toDouble()) }
            }
        }

        val percept = Percept(
            modality = PerceptionModality.SENSOR,
            payload = payload,
            salience = 0.78,
            provenance = Provenance(
                source = "android-sensor-fusion",
                producer = "phase146-live-perception",
                confidence = 0.98
            )
        )
        bus.ingest(percept)
        percept
    }

    private fun bitmapPercept(
        modality: PerceptionModality,
        bitmap: Bitmap,
        source: String
    ): Percept {
        require(!bitmap.isRecycled) { "bitmap is recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "bitmap dimensions must be positive" }
        require(source.isNotBlank())

        val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
        val stride = sqrt((totalPixels / MAX_BITMAP_SAMPLES.toDouble()).coerceAtLeast(1.0))
            .toInt()
            .coerceAtLeast(1)

        var samples = 0L
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var lumaSum = 0.0
        var lumaSquares = 0.0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel).toDouble() / 255.0
                val green = Color.green(pixel).toDouble() / 255.0
                val blue = Color.blue(pixel).toDouble() / 255.0
                val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
                redSum += red
                greenSum += green
                blueSum += blue
                lumaSum += luma
                lumaSquares += luma * luma
                samples += 1
                x += stride
            }
            y += stride
        }

        require(samples > 0)
        val meanLuma = lumaSum / samples.toDouble()
        val variance = (lumaSquares / samples.toDouble() - meanLuma * meanLuma).coerceAtLeast(0.0)
        val orientation = when {
            bitmap.width > bitmap.height -> "landscape"
            bitmap.height > bitmap.width -> "portrait"
            else -> "square"
        }

        return Percept(
            modality = modality,
            payload = buildString {
                append("width=").append(bitmap.width)
                append(";height=").append(bitmap.height)
                append(";orientation=").append(orientation)
                append(";sampledPixels=").append(samples)
                append(";meanLuma=").append(decimal(meanLuma))
                append(";contrast=").append(decimal(sqrt(variance)))
                append(";meanRgb=")
                append(decimal(redSum / samples.toDouble())).append(",")
                append(decimal(greenSum / samples.toDouble())).append(",")
                append(decimal(blueSum / samples.toDouble()))
            },
            salience = if (modality == PerceptionModality.CAMERA) 0.9 else 0.84,
            provenance = Provenance(
                source = source,
                producer = "phase146-live-perception",
                confidence = 0.93
            )
        )
    }

    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.4f", value.coerceIn(-1_000_000.0, 1_000_000.0))

    private companion object {
        const val MAX_BITMAP_SAMPLES = 4_096
    }
}
