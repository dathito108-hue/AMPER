package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiAutoregressiveGenerator
import io.amper.neuroos.core.AmiDecoderStackExecutionResult
import io.amper.neuroos.core.AmiDecoderStackExecutor
import io.amper.neuroos.core.AmiDecoderStackPlan
import io.amper.neuroos.core.AmiDecoderStackPlanner
import io.amper.neuroos.core.AmiDecoderStackState
import io.amper.neuroos.core.AmiGenerationConfig
import io.amper.neuroos.core.AmiGenerationResult
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.InferenceCancellationSignal
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class Amne2ExecutionSessionIdentity(
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String
)

enum class Amne2ExecutionSessionState {
    OPEN,
    CLOSED
}

/**
 * One mutable execution owner for one verified AMI2 foundation.
 *
 * The session owns decoder KV state and serializes execution over the already-qualified decoder
 * stack. It does not create a second decoder/backend. All tensor/model semantics come from the
 * Phase632 binding over the Phase631 verified AMI2 execution view.
 */
class Amne2ExecutionSession internal constructor(
    val view: Amne2ExecutionView,
    val binding: Amne2DecoderSemanticBinding,
    val stackPlan: AmiDecoderStackPlan,
    private val stackState: AmiDecoderStackState,
    private val hardware: AmiHardwareSnapshot,
    private val maxWindowBytes: Int
) {
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        require(maxWindowBytes > 0)
        require(view.identity.foundationId == binding.identity.foundationId)
        require(view.identity.semanticSha256 == binding.identity.semanticSha256)
        require(view.identity.artifactSha256 == binding.identity.artifactSha256)
        require(stackPlan.architecture == binding.tensorGraph.architecture)
        require(stackPlan.vocabularySize == view.loaded.bundle.foundation.vocabularySize)
    }

    val identity: Amne2ExecutionSessionIdentity = Amne2ExecutionSessionIdentity(
        foundationId = view.identity.foundationId,
        semanticSha256 = view.identity.semanticSha256,
        artifactSha256 = view.identity.artifactSha256
    )

    val state: Amne2ExecutionSessionState
        get() = if (closed.get()) {
            Amne2ExecutionSessionState.CLOSED
        } else {
            Amne2ExecutionSessionState.OPEN
        }

    val position: Int
        get() {
            requireOpen()
            return stackState.position
        }

    val maxContextTokens: Int
        get() = stackState.maxContextTokens

    /**
     * Executes exactly one token through the existing decoder stack.
     *
     * A cancellation observed after the decoder returns is still transactional: KV is rolled back
     * to the pre-call position before the failure is exposed.
     */
    fun executeToken(
        tokenId: Int,
        cancellation: InferenceCancellationSignal? = null
    ): Result<AmiDecoderStackExecutionResult> =
        withExecutionLease {
            val originalPosition = stackState.position
            try {
                cancellation?.throwIfCancelled()
                val result = AmiDecoderStackExecutor(maxWindowBytes)
                    .executeToken(
                        loaded = binding.decoderArtifactView,
                        graph = binding.tensorGraph,
                        plan = stackPlan,
                        state = stackState,
                        tokenId = tokenId,
                        hardware = hardware
                    )
                    .getOrThrow()
                cancellation?.throwIfCancelled()
                result
            } catch (error: Throwable) {
                runCatching { stackState.rollbackTo(originalPosition) }
                throw error
            }
        }

    /**
     * Runs the existing autoregressive generator inside the same session-owned KV state.
     */
    fun generate(
        promptTokenIds: IntArray,
        config: AmiGenerationConfig,
        cancellation: InferenceCancellationSignal? = null
    ): Result<AmiGenerationResult> =
        withExecutionLease {
            AmiAutoregressiveGenerator(maxWindowBytes)
                .generate(
                    loaded = binding.decoderArtifactView,
                    graph = binding.tensorGraph,
                    metadata = binding.preservedMetadata,
                    stackPlan = stackPlan,
                    state = stackState,
                    promptTokenIds = promptTokenIds,
                    config = config,
                    hardware = hardware,
                    cancellation = cancellation
                )
                .getOrThrow()
        }

    fun resetKv(): Result<Unit> = runCatching {
        requireOpen()
        require(busy.compareAndSet(false, true)) {
            "AMNE2 execution session is busy"
        }
        try {
            stackState.clear()
        } finally {
            busy.set(false)
        }
    }

    fun close(): Result<Unit> = runCatching {
        if (closed.get()) return@runCatching
        require(busy.compareAndSet(false, true)) {
            "AMNE2 execution session is busy"
        }
        try {
            stackState.clear()
            closed.set(true)
        } finally {
            busy.set(false)
        }
    }

    private fun <T> withExecutionLease(block: () -> T): Result<T> = runCatching {
        requireOpen()
        require(busy.compareAndSet(false, true)) {
            "AMNE2 execution session already has an active request"
        }
        try {
            requireOpen()
            block()
        } finally {
            busy.set(false)
        }
    }

    private fun requireOpen() {
        require(!closed.get()) { "AMNE2 execution session is closed" }
    }
}

/**
 * Canonical M3 session factory:
 *
 * verified AMI2 -> bounded execution view -> decoder semantic binding -> decoder plan -> KV owner.
 */
class Amne2ExecutionSessionFactory(
    private val viewFactory: Amne2ExecutionViewFactory = Amne2ExecutionViewFactory(),
    private val bindingFactory: Amne2DecoderSemanticBindingFactory =
        Amne2DecoderSemanticBindingFactory(),
    private val maxWindowBytes: Int = Amne2ExecutionViewFactory.DEFAULT_MAX_WINDOW_BYTES
) {
    init {
        require(maxWindowBytes > 0)
    }

    fun open(
        artifactFile: File,
        hardware: AmiHardwareSnapshot,
        maxContextTokens: Int? = null
    ): Result<Amne2ExecutionSession> = runCatching {
        val view = viewFactory.open(artifactFile, hardware).getOrThrow()
        val binding = bindingFactory.bind(view).getOrThrow()
        val plan = AmiDecoderStackPlanner
            .plan(binding.tensorGraph, binding.preservedMetadata)
            .getOrThrow()

        val contextLimit = maxContextTokens
            ?.also { require(it > 0) }
            ?.let { minOf(it, plan.maxContextTokens) }
            ?: plan.maxContextTokens

        Amne2ExecutionSession(
            view = view,
            binding = binding,
            stackPlan = plan,
            stackState = AmiDecoderStackState(
                plan = plan,
                maxContextTokens = contextLimit
            ),
            hardware = hardware,
            maxWindowBytes = maxWindowBytes
        )
    }
}
