package io.amper.neuroos.backend

import io.amper.neuroos.core.BackendHealth
import io.amper.neuroos.core.BackendState
import io.amper.neuroos.core.InferenceAttachmentKind
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceCancelledException
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.LlamaNativeGeneration
import io.amper.neuroos.core.LlamaNativePromptTokenEngine
import io.amper.neuroos.core.LlamaNativeTextEngine
import io.amper.neuroos.core.MtmdNativeEngine
import io.amper.neuroos.core.MtmdNativePromptTokenEngine
import io.amper.neuroos.core.MtmdNativeAccelerationAwareEngine
import io.amper.neuroos.core.MtmdNativeGeneration
import io.amper.neuroos.core.NativeWarmSessionResidencyAwareEngine
import io.amper.neuroos.core.CancellablePreparableWarmSessionLlamaNativeTextEngine
import io.amper.neuroos.core.CancellablePreparableWarmSessionMtmdNativeEngine
import io.amper.neuroos.core.Utf8StreamDecoder
import io.amper.neuroos.core.WarmSessionLlamaNativeTextEngine
import io.amper.neuroos.core.WarmSessionMtmdNativeEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * JNI bridge backed by a pinned llama.cpp + libmtmd build.
 *
 * This class exists only in the opt-in mtmdNative source set. Canonical builds discover it
 * reflectively and remain completely independent of the NDK/native dependency graph.
 */
class AndroidMtmdNativeEngine :
    MtmdNativeEngine,
    MtmdNativePromptTokenEngine,
    CancellablePreparableWarmSessionMtmdNativeEngine,
    LlamaNativeTextEngine,
    LlamaNativePromptTokenEngine,
    CancellablePreparableWarmSessionLlamaNativeTextEngine,
    NativeWarmSessionResidencyAwareEngine,
    MtmdNativeAccelerationAwareEngine {
    override val engineId: String = "llama.cpp-mtmd-jni"

    override fun health(): BackendHealth = runCatching {
        val acceleration = nativeAccelerationIdentity()
        BackendHealth(
            state = BackendState.READY,
            detail = (nativeSystemInfo() + " | acceleration=" + acceleration).take(320),
            hardwareAcceleration = acceleration.startsWith("vulkan:")
        )
    }.getOrElse { error ->
        BackendHealth(
            state = BackendState.UNAVAILABLE,
            detail = error.message ?: error::class.java.simpleName
        )
    }

    override fun accelerationIdentity(): String =
        nativeAccelerationIdentity().ifBlank { "cpu" }

    override fun estimateWarmTextPromptTokens(
        sessionKey: String,
        prompt: String
    ): Result<Int> = runCatching {
        require(sessionKey.isNotBlank()) { "warm native text session key is blank" }
        require(prompt.isNotBlank()) { "native text prompt is blank" }
        nativeEstimateWarmTextPromptTokens(sessionKey, prompt).also {
            require(it > 0) { "native warm tokenizer produced no prompt tokens" }
        }
    }

    override fun estimateWarmMtmdPromptTokens(
        sessionKey: String,
        request: InferenceRequest
    ): Result<Int> = runCatching {
        require(sessionKey.isNotBlank()) { "warm MTMD session key is blank" }
        require(request.prompt.isNotBlank()) { "MTMD prompt is blank" }
        require(request.attachments.isNotEmpty()) {
            "warm MTMD token preflight requires multimodal attachments"
        }
        val payloads = request.attachments.map { it.readBytes() }.toTypedArray()
        val kinds = request.attachments.map {
            when (it.kind) {
                InferenceAttachmentKind.IMAGE -> KIND_IMAGE
                InferenceAttachmentKind.AUDIO -> KIND_AUDIO
            }
        }.toIntArray()
        nativeEstimateWarmMtmdPromptTokens(
            sessionKey = sessionKey,
            prompt = request.prompt,
            payloads = payloads,
            kinds = kinds
        ).also {
            require(it > 0) { "native warm MTMD tokenizer produced no prompt tokens" }
        }
    }

    override fun isWarmTextSessionResident(sessionKey: String): Boolean {
        require(sessionKey.isNotBlank()) { "warm native text session key is blank" }
        return nativeIsWarmTextSessionResident(sessionKey)
    }

    override fun isWarmMtmdSessionResident(sessionKey: String): Boolean {
        require(sessionKey.isNotBlank()) { "warm MTMD session key is blank" }
        return nativeIsWarmMtmdSessionResident(sessionKey)
    }

    override fun prepareWarmTextSession(
        sessionKey: String,
        modelPath: String,
        threads: Int
    ): Result<Unit> = prepareWarmTextSession(
        sessionKey = sessionKey,
        modelPath = modelPath,
        threads = threads,
        cancellation = InferenceCancellationSignal()
    )

    override fun prepareWarmTextSession(
        sessionKey: String,
        modelPath: String,
        threads: Int,
        cancellation: InferenceCancellationSignal
    ): Result<Unit> = runCancellablePreparation(
        threadNamePrefix = "amper-text-prepare-cancel",
        cancellation = cancellation
    ) { requestId ->
        require(sessionKey.isNotBlank()) { "warm native text session key is blank" }
        require(modelPath.isNotBlank()) { "native text model path is blank" }
        require(threads > 0)
        check(nativePrepareWarmTextSession(requestId, sessionKey, modelPath, threads)) {
            "failed to prepare warm native text session"
        }
    }

    override fun prepareWarmSession(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        threads: Int
    ): Result<Unit> = prepareWarmSession(
        sessionKey = sessionKey,
        modelPath = modelPath,
        projectorPath = projectorPath,
        threads = threads,
        cancellation = InferenceCancellationSignal()
    )

    override fun prepareWarmSession(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        threads: Int,
        cancellation: InferenceCancellationSignal
    ): Result<Unit> = runCancellablePreparation(
        threadNamePrefix = "amper-mtmd-prepare-cancel",
        cancellation = cancellation
    ) { requestId ->
        require(sessionKey.isNotBlank()) { "warm MTMD session key is blank" }
        require(modelPath.isNotBlank()) { "MTMD text model path is blank" }
        require(projectorPath.isNotBlank()) { "MTMD projector path is blank" }
        require(threads > 0)
        check(nativePrepareWarmSession(requestId, sessionKey, modelPath, projectorPath, threads)) {
            "failed to prepare warm MTMD session"
        }
    }

    private fun runCancellablePreparation(
        threadNamePrefix: String,
        cancellation: InferenceCancellationSignal,
        block: (Long) -> Unit
    ): Result<Unit> = runCatching {
        cancellation.throwIfCancelled()
        val requestId = REQUEST_IDS.incrementAndGet()
        val done = AtomicBoolean(false)
        val watcher = thread(
            start = true,
            isDaemon = true,
            name = "$threadNamePrefix-$requestId"
        ) {
            while (!done.get()) {
                if (cancellation.isCancelled) {
                    if (nativeCancel(requestId)) return@thread
                }
                try {
                    Thread.sleep(CANCEL_POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        try {
            try {
                block(requestId)
            } catch (error: RuntimeException) {
                if (
                    cancellation.isCancelled ||
                    error.message?.contains(NATIVE_CANCELLED_MARKER) == true
                ) {
                    throw InferenceCancelledException()
                }
                throw error
            }
            cancellation.throwIfCancelled()
        } finally {
            done.set(true)
            watcher.interrupt()
            nativeReleaseRequest(requestId)
        }
    }

    override fun generateText(
        modelPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<LlamaNativeGeneration> = generateTextInternal(
        sessionKey = null,
        modelPath = modelPath,
        request = request,
        threads = threads,
        contextTokens = contextTokens,
        cancellation = cancellation,
        onToken = onToken
    )

    override fun generateTextWarm(
        sessionKey: String,
        modelPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<LlamaNativeGeneration> {
        require(sessionKey.isNotBlank()) { "warm native text session key is blank" }
        return generateTextInternal(
            sessionKey = sessionKey,
            modelPath = modelPath,
            request = request,
            threads = threads,
            contextTokens = contextTokens,
            cancellation = cancellation,
            onToken = onToken
        )
    }

    override fun releaseWarmTextSession(sessionKey: String): Result<Unit> = runCatching {
        require(sessionKey.isNotBlank()) { "warm native text session key is blank" }
        nativeReleaseWarmTextSession(sessionKey)
        Unit
    }

    private fun generateTextInternal(
        sessionKey: String?,
        modelPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<LlamaNativeGeneration> = runCatching {
        require(modelPath.isNotBlank())
        require(request.attachments.isEmpty()) {
            "native text engine does not accept multimodal attachments"
        }
        require(threads > 0)
        require(contextTokens > 0)
        cancellation.throwIfCancelled()

        val requestId = REQUEST_IDS.incrementAndGet()
        val done = AtomicBoolean(false)
        val watcher = thread(
            start = true,
            isDaemon = true,
            name = "amper-text-cancel-$requestId"
        ) {
            while (!done.get()) {
                if (cancellation.isCancelled) {
                    nativeCancel(requestId)
                    return@thread
                }
                try {
                    Thread.sleep(CANCEL_POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }

        val startedNs = System.nanoTime()
        val nativeMetrics = LongArray(NATIVE_METRIC_COUNT)
        val streamDecoder = Utf8StreamDecoder()
        val streamedText = StringBuilder()
        try {
            val outputBytes = try {
                nativeGenerateText(
                    requestId = requestId,
                    sessionKey = sessionKey,
                    modelPath = modelPath,
                    prompt = request.prompt,
                    maxOutputTokens = request.maxOutputTokens,
                    temperature = request.temperature.toFloat(),
                    threads = threads,
                    contextTokens = contextTokens,
                    nativeMetrics = nativeMetrics,
                    sink = object : NativeTokenSink {
                        override fun onBytes(bytes: ByteArray) {
                            cancellation.throwIfCancelled()
                            val text = streamDecoder.append(bytes)
                            if (text.isNotEmpty()) {
                                streamedText.append(text)
                                onToken(text)
                            }
                        }
                    }
                )
            } catch (error: RuntimeException) {
                if (
                    cancellation.isCancelled ||
                    error.message?.contains(NATIVE_CANCELLED_MARKER) == true
                ) {
                    throw InferenceCancelledException()
                }
                throw error
            }

            cancellation.throwIfCancelled()
            val finalChunk = streamDecoder.finish()
            if (finalChunk.isNotEmpty()) {
                streamedText.append(finalChunk)
                onToken(finalChunk)
            }
            val text = decodeCompleteUtf8(outputBytes)
            check(streamedText.toString() == text) {
                "native text byte stream does not match authoritative output"
            }
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            val generationMs = nativeMetrics[METRIC_GENERATION_MS]
                .takeIf { it > 0L }
                ?: elapsedMs
            val outputTokens = nativeMetrics[METRIC_OUTPUT_TOKENS].also {
                check(it in 0L..Int.MAX_VALUE.toLong()) {
                    "native output token count is out of range: $it"
                }
            }.toInt()
            LlamaNativeGeneration(
                text = text,
                promptTokens = nativeMetrics[METRIC_PROMPT_TOKENS]
                    .takeIf { it > 0L && it <= Int.MAX_VALUE.toLong() }
                    ?.toInt(),
                outputTokens = outputTokens,
                promptEvalTimeMs = nativeMetrics[METRIC_PROMPT_EVAL_MS]
                    .takeIf { it > 0L },
                generationTimeMs = generationMs,
                tokensPerSecond = outputTokens
                    .takeIf { it > 0 }
                    ?.let { it.toDouble() * 1000.0 / generationMs.toDouble() }
            )
        } finally {
            done.set(true)
            watcher.interrupt()
            nativeReleaseRequest(requestId)
        }
    }

    override fun probeProjector(
        projectorPath: String
    ): Result<Set<InferenceAttachmentKind>> = runCatching {
        require(projectorPath.isNotBlank())
        val mask = nativeProbeProjector(projectorPath)
        buildSet {
            if (mask and CAP_IMAGE != 0) add(InferenceAttachmentKind.IMAGE)
            if (mask and CAP_AUDIO != 0) add(InferenceAttachmentKind.AUDIO)
        }
    }

    override fun generate(
        modelPath: String,
        projectorPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<MtmdNativeGeneration> = generateInternal(
        sessionKey = null,
        modelPath = modelPath,
        projectorPath = projectorPath,
        request = request,
        threads = threads,
        contextTokens = contextTokens,
        cancellation = cancellation,
        onToken = onToken
    )

    override fun generateWarm(
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<MtmdNativeGeneration> {
        require(sessionKey.isNotBlank()) { "warm MTMD session key is blank" }
        return generateInternal(
            sessionKey = sessionKey,
            modelPath = modelPath,
            projectorPath = projectorPath,
            request = request,
            threads = threads,
            contextTokens = contextTokens,
            cancellation = cancellation,
            onToken = onToken
        )
    }

    override fun releaseWarmSession(sessionKey: String): Result<Unit> = runCatching {
        require(sessionKey.isNotBlank()) { "warm MTMD session key is blank" }
        nativeReleaseWarmSession(sessionKey)
        Unit
    }

    private fun generateInternal(
        sessionKey: String?,
        modelPath: String,
        projectorPath: String,
        request: InferenceRequest,
        threads: Int,
        contextTokens: Int,
        cancellation: InferenceCancellationSignal,
        onToken: (String) -> Unit
    ): Result<MtmdNativeGeneration> = runCatching {
        require(modelPath.isNotBlank())
        require(projectorPath.isNotBlank())
        require(threads > 0)
        require(contextTokens > 0)
        cancellation.throwIfCancelled()

        val requestId = REQUEST_IDS.incrementAndGet()
        val done = AtomicBoolean(false)
        val watcher = thread(
            start = true,
            isDaemon = true,
            name = "amper-mtmd-cancel-$requestId"
        ) {
            while (!done.get()) {
                if (cancellation.isCancelled) {
                    nativeCancel(requestId)
                    return@thread
                }
                try {
                    Thread.sleep(CANCEL_POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }

        val payloads = request.attachments.map { it.readBytes() }.toTypedArray()
        val kinds = request.attachments.map {
            when (it.kind) {
                InferenceAttachmentKind.IMAGE -> KIND_IMAGE
                InferenceAttachmentKind.AUDIO -> KIND_AUDIO
            }
        }.toIntArray()

        val startedNs = System.nanoTime()
        val nativeMetrics = LongArray(NATIVE_METRIC_COUNT)
        val streamDecoder = Utf8StreamDecoder()
        val streamedText = StringBuilder()
        try {
            val outputBytes = try {
                nativeGenerate(
                    requestId = requestId,
                    sessionKey = sessionKey,
                    modelPath = modelPath,
                    projectorPath = projectorPath,
                    prompt = request.prompt,
                    payloads = payloads,
                    kinds = kinds,
                    maxOutputTokens = request.maxOutputTokens,
                    temperature = request.temperature.toFloat(),
                    threads = threads,
                    contextTokens = contextTokens,
                    nativeMetrics = nativeMetrics,
                    sink = object : NativeTokenSink {
                        override fun onBytes(bytes: ByteArray) {
                            cancellation.throwIfCancelled()
                            val text = streamDecoder.append(bytes)
                            if (text.isNotEmpty()) {
                                streamedText.append(text)
                                onToken(text)
                            }
                        }
                    }
                )
            } catch (error: RuntimeException) {
                if (
                    cancellation.isCancelled ||
                    error.message?.contains(NATIVE_CANCELLED_MARKER) == true
                ) {
                    throw InferenceCancelledException()
                }
                throw error
            }
            cancellation.throwIfCancelled()
            val finalChunk = streamDecoder.finish()
            if (finalChunk.isNotEmpty()) {
                streamedText.append(finalChunk)
                onToken(finalChunk)
            }
            val text = decodeCompleteUtf8(outputBytes)
            check(streamedText.toString() == text) {
                "native MTMD byte stream does not match authoritative output"
            }
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
            val generationMs = nativeMetrics[METRIC_GENERATION_MS]
                .takeIf { it > 0L }
                ?: elapsedMs
            val outputTokens = nativeMetrics[METRIC_OUTPUT_TOKENS].also {
                check(it in 0L..Int.MAX_VALUE.toLong()) {
                    "native output token count is out of range: $it"
                }
            }.toInt()
            MtmdNativeGeneration(
                text = text,
                promptTokens = nativeMetrics[METRIC_PROMPT_TOKENS]
                    .takeIf { it > 0L && it <= Int.MAX_VALUE.toLong() }
                    ?.toInt(),
                outputTokens = outputTokens,
                promptEvalTimeMs = nativeMetrics[METRIC_PROMPT_EVAL_MS]
                    .takeIf { it > 0L },
                generationTimeMs = generationMs,
                tokensPerSecond = outputTokens
                    .takeIf { it > 0 }
                    ?.let { it.toDouble() * 1000.0 / generationMs.toDouble() }
            )
        } finally {
            done.set(true)
            watcher.interrupt()
            nativeReleaseRequest(requestId)
        }
    }

    private interface NativeTokenSink {
        fun onBytes(bytes: ByteArray)
    }

    private fun decodeCompleteUtf8(bytes: ByteArray): String {
        val decoder = Utf8StreamDecoder()
        return decoder.append(bytes) + decoder.finish()
    }

    private external fun nativeSystemInfo(): String
    private external fun nativeAccelerationIdentity(): String
    private external fun nativeProbeProjector(projectorPath: String): Int

    private external fun nativeEstimateWarmTextPromptTokens(
        sessionKey: String,
        prompt: String
    ): Int

    private external fun nativeEstimateWarmMtmdPromptTokens(
        sessionKey: String,
        prompt: String,
        payloads: Array<ByteArray>,
        kinds: IntArray
    ): Int

    private external fun nativePrepareWarmTextSession(
        requestId: Long,
        sessionKey: String,
        modelPath: String,
        threads: Int
    ): Boolean

    private external fun nativePrepareWarmSession(
        requestId: Long,
        sessionKey: String,
        modelPath: String,
        projectorPath: String,
        threads: Int
    ): Boolean

    private external fun nativeGenerateText(
        requestId: Long,
        sessionKey: String?,
        modelPath: String,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        threads: Int,
        contextTokens: Int,
        nativeMetrics: LongArray,
        sink: NativeTokenSink
    ): ByteArray

    private external fun nativeGenerate(
        requestId: Long,
        sessionKey: String?,
        modelPath: String,
        projectorPath: String,
        prompt: String,
        payloads: Array<ByteArray>,
        kinds: IntArray,
        maxOutputTokens: Int,
        temperature: Float,
        threads: Int,
        contextTokens: Int,
        nativeMetrics: LongArray,
        sink: NativeTokenSink
    ): ByteArray

    private external fun nativeCancel(requestId: Long): Boolean
    private external fun nativeReleaseRequest(requestId: Long)
    private external fun nativeReleaseWarmSession(sessionKey: String): Boolean
    private external fun nativeReleaseWarmTextSession(sessionKey: String): Boolean
    private external fun nativeIsWarmTextSessionResident(sessionKey: String): Boolean
    private external fun nativeIsWarmMtmdSessionResident(sessionKey: String): Boolean

    companion object {
        private const val KIND_IMAGE = 1
        private const val KIND_AUDIO = 2
        private const val CAP_IMAGE = 1
        private const val CAP_AUDIO = 2
        private const val CANCEL_POLL_MS = 12L
        private const val NATIVE_CANCELLED_MARKER = "AMPER_MTMD_CANCELLED"
        private const val METRIC_PROMPT_TOKENS = 0
        private const val METRIC_PROMPT_EVAL_MS = 1
        private const val METRIC_GENERATION_MS = 2
        private const val METRIC_OUTPUT_TOKENS = 3
        private const val NATIVE_METRIC_COUNT = 4
        private val REQUEST_IDS = AtomicLong(0L)

        init {
            System.loadLibrary("amper_mtmd")
        }
    }
}
