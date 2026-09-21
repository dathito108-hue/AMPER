package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.MemoryId
import io.amper.neuroos.core.MemoryOs
import io.amper.neuroos.core.MemoryRecord
import io.amper.neuroos.core.Provenance
import java.nio.charset.StandardCharsets
import java.util.Base64

data class AmperAgentProactiveTriggerSourceState(
    val source: AmperAgentProactiveTriggerSource,
    val lastAcceptedObservationAtEpochMs: Long? = null,
    val lastAcceptedPayloadDigest: String? = null,
    val lastAcceptedObservationIdentitySha256: String? = null,
    val updatedAtEpochMs: Long
) {
    init {
        require(updatedAtEpochMs >= 0L)
        val lastFields = listOf(
            lastAcceptedObservationAtEpochMs,
            lastAcceptedPayloadDigest,
            lastAcceptedObservationIdentitySha256
        )
        require(lastFields.all { it == null } || lastFields.all { it != null }) {
            "durable proactive trigger acceptance state must be complete or empty"
        }
        lastAcceptedObservationAtEpochMs?.let { require(it >= 0L) }
        lastAcceptedPayloadDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        lastAcceptedObservationIdentitySha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
    }
}

sealed interface AmperAgentProactiveTriggerAcceptance {
    val state: AmperAgentProactiveTriggerSourceState

    data class Accepted(
        val qualified: AmperAgentQualifiedProactiveTrigger,
        override val state: AmperAgentProactiveTriggerSourceState
    ) : AmperAgentProactiveTriggerAcceptance

    data class Duplicate(
        override val state: AmperAgentProactiveTriggerSourceState
    ) : AmperAgentProactiveTriggerAcceptance
}

interface AmperAgentProactiveTriggerSourceRegistry {
    fun upsert(source: AmperAgentProactiveTriggerSource): AmperAgentProactiveTriggerSourceState
    fun get(sourceId: String): AmperAgentProactiveTriggerSourceState?
    fun list(limit: Int = 32): List<AmperAgentProactiveTriggerSourceState>
    fun setEnabled(sourceId: String, enabled: Boolean): AmperAgentProactiveTriggerSourceState?
    fun delete(sourceId: String): Boolean
    fun accept(
        observation: AmperAgentProactiveTriggerObservation
    ): Result<AmperAgentProactiveTriggerAcceptance>
}

/**
 * Phase659 durable trigger-source registry backed by the canonical sovereign MemoryOs.
 *
 * Android production therefore inherits the same encrypted AES-GCM journal used by the rest of
 * AmperRuntime. This registry is control metadata only: no plan/task database, tool authority, or
 * background execution path is introduced.
 */
class MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : AmperAgentProactiveTriggerSourceRegistry {
    override fun upsert(
        source: AmperAgentProactiveTriggerSource
    ): AmperAgentProactiveTriggerSourceState = memory.transaction {
        val existing = loadFrom(this, source.sourceId)
        val sameRevision =
            existing?.source?.configurationId == source.configurationId &&
                existing.source.configurationSha256 == source.configurationSha256
        val now = clock().coerceAtLeast(0L)
        val updated = AmperAgentProactiveTriggerSourceState(
            source = source,
            lastAcceptedObservationAtEpochMs =
                existing?.lastAcceptedObservationAtEpochMs?.takeIf { sameRevision },
            lastAcceptedPayloadDigest =
                existing?.lastAcceptedPayloadDigest?.takeIf { sameRevision },
            lastAcceptedObservationIdentitySha256 =
                existing?.lastAcceptedObservationIdentitySha256?.takeIf { sameRevision },
            updatedAtEpochMs = now
        )
        saveTo(this, updated)
    }

    override fun get(sourceId: String): AmperAgentProactiveTriggerSourceState? =
        loadFrom(memory, sourceId)

    override fun list(limit: Int): List<AmperAgentProactiveTriggerSourceState> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall("", memory.size())
            .asSequence()
            .filter { it.kind == KIND }
            .mapNotNull { AmperAgentProactiveTriggerSourceStateCodec.decode(it.content).getOrNull() }
            .sortedWith(
                compareByDescending<AmperAgentProactiveTriggerSourceState> {
                    it.updatedAtEpochMs
                }.thenBy { it.source.sourceId }
            )
            .take(limit)
            .toList()
    }

    override fun setEnabled(
        sourceId: String,
        enabled: Boolean
    ): AmperAgentProactiveTriggerSourceState? = memory.transaction {
        val existing = loadFrom(this, sourceId) ?: return@transaction null
        val updated = existing.copy(
            source = existing.source.copy(enabled = enabled),
            updatedAtEpochMs = clock().coerceAtLeast(existing.updatedAtEpochMs)
        )
        saveTo(this, updated)
    }

    override fun delete(sourceId: String): Boolean = memory.transaction {
        val id = recordId(sourceId)
        val existing = this.get(id) ?: return@transaction false
        if (existing.kind != KIND) return@transaction false
        this.forget(id)
    }

    override fun accept(
        observation: AmperAgentProactiveTriggerObservation
    ): Result<AmperAgentProactiveTriggerAcceptance> = runCatching {
        memory.transaction {
            val current = requireNotNull(loadFrom(this, observation.sourceId)) {
                "proactive trigger source is not registered"
            }
            require(current.source.enabled) {
                "disabled proactive trigger source cannot accept observations"
            }

            if (
                current.lastAcceptedObservationAtEpochMs == observation.observedAtEpochMs &&
                current.lastAcceptedPayloadDigest == observation.payloadDigest
            ) {
                return@transaction AmperAgentProactiveTriggerAcceptance.Duplicate(current)
            }

            val qualified = AmperAgentProactiveTriggerSourcePolicy
                .qualify(
                    source = current.source,
                    observation = observation,
                    lastAcceptedObservationAtEpochMs =
                        current.lastAcceptedObservationAtEpochMs
                )
                .getOrThrow()
            val updated = current.copy(
                lastAcceptedObservationAtEpochMs = observation.observedAtEpochMs,
                lastAcceptedPayloadDigest = observation.payloadDigest,
                lastAcceptedObservationIdentitySha256 =
                    qualified.observationIdentitySha256,
                updatedAtEpochMs = clock().coerceAtLeast(observation.observedAtEpochMs)
            )
            saveTo(this, updated)
            AmperAgentProactiveTriggerAcceptance.Accepted(
                qualified = qualified,
                state = updated
            )
        }
    }

    private fun loadFrom(
        target: MemoryOs,
        sourceId: String
    ): AmperAgentProactiveTriggerSourceState? =
        target.get(recordId(sourceId))
            ?.takeIf { it.kind == KIND }
            ?.let {
                AmperAgentProactiveTriggerSourceStateCodec
                    .decode(it.content)
                    .getOrNull()
            }

    private fun saveTo(
        target: MemoryOs,
        state: AmperAgentProactiveTriggerSourceState
    ): AmperAgentProactiveTriggerSourceState {
        target.remember(
            MemoryRecord(
                id = recordId(state.source.sourceId),
                kind = KIND,
                content = AmperAgentProactiveTriggerSourceStateCodec.encode(state),
                importance = 0.96,
                provenance = Provenance(
                    source = "user-configured-proactive-trigger-source",
                    producer = "canonical-agent-trigger-registry",
                    confidence = 1.0
                ),
                createdAtEpochMs = state.updatedAtEpochMs
            )
        )
        return state
    }

    private fun recordId(sourceId: String): MemoryId =
        MemoryId("agent-trigger-source:$sourceId")

    companion object {
        const val KIND = "agent-trigger-source-v1"
    }
}

internal object AmperAgentProactiveTriggerSourceStateCodec {
    private const val MAGIC = "AMPER_AGENT_TRIGGER_SOURCE_STATE_V1"

    fun encode(state: AmperAgentProactiveTriggerSourceState): String = buildString {
        appendLine(MAGIC)
        appendLine("SOURCE_ID\t${enc(state.source.sourceId)}")
        appendLine("CONFIG_ID\t${enc(state.source.configurationId)}")
        appendLine("KIND\t${state.source.kind.name}")
        appendLine("OBJECTIVE\t${enc(state.source.objective)}")
        appendLine("EXPECTED_RUNTIME_MS\t${state.source.expectedRuntimeMs}")
        appendLine("MINIMUM_INTERVAL_MS\t${state.source.minimumIntervalMs}")
        appendLine("CONFIG_SHA256\t${state.source.configurationSha256}")
        appendLine("USER_CONFIGURED\t${state.source.userConfigured}")
        appendLine("ENABLED\t${state.source.enabled}")
        state.source.allowedCapabilities
            .sortedBy { it.value }
            .forEach { appendLine("CAPABILITY\t${enc(it.value)}") }
        appendLine(
            "LAST_ACCEPTED_AT\t" +
                (state.lastAcceptedObservationAtEpochMs?.toString() ?: "~")
        )
        appendLine(
            "LAST_PAYLOAD_SHA256\t" +
                (state.lastAcceptedPayloadDigest ?: "~")
        )
        appendLine(
            "LAST_OBSERVATION_SHA256\t" +
                (state.lastAcceptedObservationIdentitySha256 ?: "~")
        )
        appendLine("UPDATED_AT\t${state.updatedAtEpochMs}")
    }.trimEnd()

    fun decode(content: String): Result<AmperAgentProactiveTriggerSourceState> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == MAGIC) {
            "unsupported proactive trigger-source state"
        }
        val scalars = linkedMapOf<String, String>()
        val capabilities = linkedSetOf<CapabilityId>()

        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "CAPABILITY" -> {
                    require(parts.size == 2)
                    capabilities += CapabilityId(dec(parts[1]))
                }
                "SOURCE_ID", "CONFIG_ID", "KIND", "OBJECTIVE", "EXPECTED_RUNTIME_MS",
                "MINIMUM_INTERVAL_MS", "CONFIG_SHA256", "USER_CONFIGURED", "ENABLED",
                "LAST_ACCEPTED_AT", "LAST_PAYLOAD_SHA256", "LAST_OBSERVATION_SHA256",
                "UPDATED_AT" -> {
                    require(parts.size == 2) {
                        "invalid proactive trigger-source scalar"
                    }
                    require(scalars.put(parts[0], parts[1]) == null) {
                        "duplicate proactive trigger-source scalar"
                    }
                }
                else -> error("unknown proactive trigger-source state field")
            }
        }

        val required = setOf(
            "SOURCE_ID", "CONFIG_ID", "KIND", "OBJECTIVE", "EXPECTED_RUNTIME_MS",
            "MINIMUM_INTERVAL_MS", "CONFIG_SHA256", "USER_CONFIGURED", "ENABLED",
            "LAST_ACCEPTED_AT", "LAST_PAYLOAD_SHA256", "LAST_OBSERVATION_SHA256",
            "UPDATED_AT"
        )
        require(scalars.keys == required) {
            "incomplete proactive trigger-source state"
        }
        val source = AmperAgentProactiveTriggerSource(
            sourceId = dec(scalars.getValue("SOURCE_ID")),
            configurationId = dec(scalars.getValue("CONFIG_ID")),
            kind = AmperAgentProactiveTriggerSourceKind.valueOf(scalars.getValue("KIND")),
            objective = dec(scalars.getValue("OBJECTIVE")),
            allowedCapabilities = capabilities,
            expectedRuntimeMs = scalars.getValue("EXPECTED_RUNTIME_MS").toLong(),
            minimumIntervalMs = scalars.getValue("MINIMUM_INTERVAL_MS").toLong(),
            configurationSha256 = scalars.getValue("CONFIG_SHA256"),
            userConfigured = scalars.getValue("USER_CONFIGURED").toBooleanStrict(),
            enabled = scalars.getValue("ENABLED").toBooleanStrict()
        )

        fun optional(name: String): String? =
            scalars.getValue(name).takeUnless { it == "~" }

        AmperAgentProactiveTriggerSourceState(
            source = source,
            lastAcceptedObservationAtEpochMs =
                optional("LAST_ACCEPTED_AT")?.toLong(),
            lastAcceptedPayloadDigest = optional("LAST_PAYLOAD_SHA256"),
            lastAcceptedObservationIdentitySha256 =
                optional("LAST_OBSERVATION_SHA256"),
            updatedAtEpochMs = scalars.getValue("UPDATED_AT").toLong()
        )
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
