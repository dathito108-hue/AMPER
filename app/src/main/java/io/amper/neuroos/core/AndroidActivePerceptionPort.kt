package io.amper.neuroos.core

import android.content.Context
import android.graphics.BitmapFactory

/**
 * Phase291-295 Android active-perception adapter.
 *
 * This adapter never requests Android permissions and never starts MediaProjection consent.
 * SENSOR uses already-available device sensors. AUDIO requires RECORD_AUDIO to already be granted.
 * SCREEN only consumes a fresh frame from an already-active user-approved screen-vision session.
 * CAMERA remains UI-driven and is intentionally not started by autonomous cognition.
 *
 * AndroidPerceptionCapture publishes the returned reduced Percept into the canonical PerceptionBus,
 * so raw microphone samples and screen pixels remain ephemeral and are not persisted.
 */
class AndroidActivePerceptionPort(
    context: Context,
    bus: PerceptionBus
) : CognitiveExecutiveObservationPort {
    private val capture = AndroidPerceptionCapture(context, bus)

    override val publishesToPerceptionBus: Boolean
        get() = true

    override fun acquire(
        request: CognitiveExecutiveObservationRequest
    ): Result<Percept> = runCatching {
        require(request.preferredModalities.isNotEmpty()) {
            "active perception requires a targeted stale modality"
        }

        var lastFailure: Throwable? = null
        request.preferredModalities.forEach { modality ->
            val attempt = when (modality) {
                PerceptionModality.SENSOR -> capture.captureSensors()
                PerceptionModality.AUDIO -> capture.captureMicrophone()
                PerceptionModality.SCREEN -> captureActiveScreen()
                PerceptionModality.CAMERA,
                PerceptionModality.IMAGE,
                PerceptionModality.TEXT -> Result.failure(
                    IllegalStateException(
                        "modality ${modality.name.lowercase()} requires an external or UI-driven acquisition flow"
                    )
                )
            }
            attempt.getOrNull()?.let { return@runCatching it }
            lastFailure = attempt.exceptionOrNull()
        }

        throw lastFailure
            ?: IllegalStateException("no supported active-perception modality is available")
    }

    private fun captureActiveScreen(): Result<Percept> = runCatching {
        val attachment = AndroidScreenVisionSessionBridge.latestAttachment(
            maxAgeMs = MAX_SCREEN_FRAME_AGE_MS
        ).getOrThrow()
        val bytes = attachment.readBytes()
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ) { "fresh screen frame could not be decoded" }

        try {
            capture.ingestBitmap(
                modality = PerceptionModality.SCREEN,
                bitmap = bitmap,
                source = "android-device-screen-active"
            ).getOrThrow()
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_SCREEN_FRAME_AGE_MS = 2_500L
    }
}
