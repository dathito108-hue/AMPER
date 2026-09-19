package io.amper.neuroos.core

enum class ReflexRuntimeActivationIntentStatus {
    ACTIVE,
    DISABLED
}

data class ReflexRuntimeActivationIntent(
    val status: ReflexRuntimeActivationIntentStatus,
    val checkpointId: NativeCheckpointId?,
    val weightArtifactSha256: String?,
    val activatedAtEpochMs: Long?,
    val generation: Long,
    val updatedAtEpochMs: Long
) {
    init {
        require(generation > 0L)
        require(updatedAtEpochMs >= 0L)
        when (status) {
            ReflexRuntimeActivationIntentStatus.ACTIVE -> {
                require(checkpointId != null)
                require(weightArtifactSha256?.matches(SHA256) == true)
                require(activatedAtEpochMs != null && activatedAtEpochMs >= 0L)
                require(updatedAtEpochMs >= activatedAtEpochMs)
            }
            ReflexRuntimeActivationIntentStatus.DISABLED -> {
                require(checkpointId == null)
                require(weightArtifactSha256 == null)
                require(activatedAtEpochMs == null)
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface ReflexDecisionRuntimeActivationStore {
    fun load(): ReflexRuntimeActivationIntent?

    fun persistActive(
        activation: ReflexDecisionRuntimeActivation,
        updatedAtEpochMs: Long
    ): ReflexRuntimeActivationIntent

    fun persistDisabled(updatedAtEpochMs: Long): ReflexRuntimeActivationIntent
}

/**
 * Default volatile store used by isolated controller tests/callers. Production runtime uses the
 * MemoryOs-backed store below so activation intent survives process death.
 */
class VolatileReflexDecisionRuntimeActivationStore : ReflexDecisionRuntimeActivationStore {
    @Volatile
    private var current: ReflexRuntimeActivationIntent? = null

    override fun load(): ReflexRuntimeActivationIntent? = current

    @Synchronized
    override fun persistActive(
        activation: ReflexDecisionRuntimeActivation,
        updatedAtEpochMs: Long
    ): ReflexRuntimeActivationIntent {
        val next = ReflexRuntimeActivationIntent(
            status = ReflexRuntimeActivationIntentStatus.ACTIVE,
            checkpointId = activation.checkpointId,
            weightArtifactSha256 = activation.weightArtifactSha256,
            activatedAtEpochMs = activation.activatedAtEpochMs,
            generation = nextGeneration(),
            updatedAtEpochMs = updatedAtEpochMs
        )
        current = next
        return next
    }

    @Synchronized
    override fun persistDisabled(updatedAtEpochMs: Long): ReflexRuntimeActivationIntent {
        val next = ReflexRuntimeActivationIntent(
            status = ReflexRuntimeActivationIntentStatus.DISABLED,
            checkpointId = null,
            weightArtifactSha256 = null,
            activatedAtEpochMs = null,
            generation = nextGeneration(),
            updatedAtEpochMs = updatedAtEpochMs
        )
        current = next
        return next
    }

    private fun nextGeneration(): Long = (current?.generation ?: 0L) + 1L
}

/**
 * Durable activation intent contains identity only: checkpoint id, immutable weight digest, status,
 * generation and timestamps. Backend objects, prompts, tool data and approvals are never persisted.
 */
class MemoryBackedReflexDecisionRuntimeActivationStore(
    private val memory: MemoryOs
) : ReflexDecisionRuntimeActivationStore {
    override fun load(): ReflexRuntimeActivationIntent? =
        memory.get(RECORD_ID)
            ?.takeIf { it.kind == KIND }
            ?.let { ReflexRuntimeActivationCodec.decode(it.content) }

    @Synchronized
    override fun persistActive(
        activation: ReflexDecisionRuntimeActivation,
        updatedAtEpochMs: Long
    ): ReflexRuntimeActivationIntent {
        require(updatedAtEpochMs >= activation.activatedAtEpochMs)
        val next = ReflexRuntimeActivationIntent(
            status = ReflexRuntimeActivationIntentStatus.ACTIVE,
            checkpointId = activation.checkpointId,
            weightArtifactSha256 = activation.weightArtifactSha256,
            activatedAtEpochMs = activation.activatedAtEpochMs,
            generation = nextGeneration(),
            updatedAtEpochMs = updatedAtEpochMs
        )
        persist(next)
        return next
    }

    @Synchronized
    override fun persistDisabled(updatedAtEpochMs: Long): ReflexRuntimeActivationIntent {
        require(updatedAtEpochMs >= 0L)
        val next = ReflexRuntimeActivationIntent(
            status = ReflexRuntimeActivationIntentStatus.DISABLED,
            checkpointId = null,
            weightArtifactSha256 = null,
            activatedAtEpochMs = null,
            generation = nextGeneration(),
            updatedAtEpochMs = updatedAtEpochMs
        )
        persist(next)
        return next
    }

    private fun nextGeneration(): Long = (load()?.generation ?: 0L) + 1L

    private fun persist(intent: ReflexRuntimeActivationIntent) {
        memory.remember(
            MemoryRecord(
                id = RECORD_ID,
                kind = KIND,
                content = ReflexRuntimeActivationCodec.encode(intent),
                importance = 0.97,
                provenance = Provenance(
                    source = "amper-reflex-runtime",
                    producer = "reflex-runtime-activation-store",
                    confidence = 1.0
                ),
                createdAtEpochMs = intent.updatedAtEpochMs
            )
        )
        require(load() == intent) {
            "durable Reflex runtime activation intent did not round-trip"
        }
    }

    companion object {
        const val KIND = "reflex-runtime-activation-intent-v1"
        private val RECORD_ID = MemoryId("reflex-runtime:activation-intent")
    }
}

fun interface NativeReflexDecisionPortResolver {
    fun resolve(
        checkpointId: NativeCheckpointId,
        weightArtifactSha256: String
    ): Result<NativeReflexDecisionPort>
}

private object ReflexRuntimeActivationCodec {
    fun encode(value: ReflexRuntimeActivationIntent): String = listOf(
        "v=1",
        "status=" + value.status.name,
        "checkpoint=" + (value.checkpointId?.value ?: "~"),
        "weight=" + (value.weightArtifactSha256 ?: "~"),
        "activated=" + (value.activatedAtEpochMs?.toString() ?: "~"),
        "generation=" + value.generation,
        "updated=" + value.updatedAtEpochMs
    ).joinToString(";")

    fun decode(content: String): ReflexRuntimeActivationIntent? = runCatching {
        val fields = content.split(';').associate { field ->
            val split = field.indexOf('=')
            require(split > 0)
            field.substring(0, split) to field.substring(split + 1)
        }
        require(fields["v"] == "1")
        ReflexRuntimeActivationIntent(
            status = ReflexRuntimeActivationIntentStatus.valueOf(
                requireNotNull(fields["status"])
            ),
            checkpointId = requireNotNull(fields["checkpoint"])
                .takeUnless { it == "~" }
                ?.let(::NativeCheckpointId),
            weightArtifactSha256 = requireNotNull(fields["weight"])
                .takeUnless { it == "~" },
            activatedAtEpochMs = requireNotNull(fields["activated"])
                .takeUnless { it == "~" }
                ?.toLong(),
            generation = requireNotNull(fields["generation"]).toLong(),
            updatedAtEpochMs = requireNotNull(fields["updated"]).toLong()
        )
    }.getOrNull()
}
