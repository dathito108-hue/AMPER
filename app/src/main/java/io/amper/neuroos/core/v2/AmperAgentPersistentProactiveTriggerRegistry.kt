package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.MemoryId
import io.amper.neuroos.core.MemoryOs
import io.amper.neuroos.core.MemoryRecord
import io.amper.neuroos.core.Provenance
import java.nio.charset.StandardCharsets
import java.util.Base64

data class AmperAgentPendingProactiveTriggerObservation(
    val observation: AmperAgentProactiveTriggerObservation,
    val observationIdentitySha256: String
) {
    init {
        require(observationIdentitySha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class AmperAgentPersistedProactiveTriggerSource(
    val source: AmperAgentProactiveTriggerSource,
    val lastAcceptedObservationAtEpochMs: Long? = null,
    val lastAcceptedObservationIdentitySha256: String? = null,
    val pendingObservations: List<AmperAgentPendingProactiveTriggerObservation> = emptyList(),
    val updatedAtEpochMs: Long
) {
    init {
        require(updatedAtEpochMs >= 0L)
        require(
            (lastAcceptedObservationAtEpochMs == null) ==
                (lastAcceptedObservationIdentitySha256 == null)
        ) {
            "proactive trigger observation checkpoint must be complete or absent"
        }
        lastAcceptedObservationAtEpochMs?.let { require(it >= 0L) }
        lastAcceptedObservationIdentitySha256?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "proactive trigger observation identity must use lowercase SHA-256"
            }
        }
        require(pendingObservations.size <= MAX_PENDING_OBSERVATIONS) {
            "proactive trigger pending observation queue exceeds bounded capacity"
        }
        require(pendingObservations.all { it.observation.sourceId == source.sourceId }) {
            "pending proactive observation belongs to a different source"
        }
        require(
            pendingObservations.map { it.observationIdentitySha256 }.distinct().size ==
                pendingObservations.size
        ) {
            "duplicate proactive observation identity in pending queue"
        }
        require(
            pendingObservations.zipWithNext().all { (left, right) ->
                left.observation.observedAtEpochMs <= right.observation.observedAtEpochMs
            }
        ) {
            "pending proactive observations must preserve acceptance order"
        }
        if (pendingObservations.isNotEmpty()) {
            val last = pendingObservations.last()
            require(lastAcceptedObservationAtEpochMs == last.observation.observedAtEpochMs)
            require(lastAcceptedObservationIdentitySha256 == last.observationIdentitySha256)
        }
    }

    companion object {
        const val MAX_PENDING_OBSERVATIONS: Int = 4
    }
}

data class AmperAgentAcceptedProactiveTriggerObservation(
    val state: AmperAgentPersistedProactiveTriggerSource,
    val qualified: AmperAgentQualifiedProactiveTrigger
) {
    init {
        require(state.source.sourceId == qualified.sourceId)
        require(state.source.configurationSha256 == qualified.configurationSha256)
        require(
            state.lastAcceptedObservationIdentitySha256 ==
                qualified.observationIdentitySha256
        )
    }
}

interface AmperAgentProactiveTriggerSourceRegistry {
    fun upsert(
        source: AmperAgentProactiveTriggerSource,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): AmperAgentPersistedProactiveTriggerSource

    fun get(sourceId: String): AmperAgentPersistedProactiveTriggerSource?

    fun list(limit: Int = 32): List<AmperAgentPersistedProactiveTriggerSource>

    fun setEnabled(
        sourceId: String,
        enabled: Boolean,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentPersistedProactiveTriggerSource>

    fun remove(sourceId: String): Boolean

    fun pending(sourceId: String): List<AmperAgentPendingProactiveTriggerObservation>

    fun acknowledgePending(
        sourceId: String,
        observationIdentitySha256: String,
        updatedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentPersistedProactiveTriggerSource>

    fun accept(
        observation: AmperAgentProactiveTriggerObservation,
        acceptedAtEpochMs: Long = System.currentTimeMillis()
    ): Result<AmperAgentAcceptedProactiveTriggerObservation>
}

/**
 * Phase659 registry backed by the existing sovereign MemoryOs.
 *
 * Android production obtains this through AmperRuntime.persistentEncrypted, so registration and
 * dedupe metadata inherit the same encrypted, chained, crash-recoverable sovereign journal. This
 * store contains no plan, tool authority, ToolFabric handle, scheduler, monitor loop, or model path.
 *
 * Each source is one record containing both configuration and its last accepted observation. An
 * acceptance therefore performs one journal mutation under MemoryOs.transaction instead of
 * splitting dedupe/cooldown state across a second database.
 */
class MemoryBackedAmperAgentProactiveTriggerSourceRegistry(
    private val memory: MemoryOs
) : AmperAgentProactiveTriggerSourceRegistry {
    override fun upsert(
        source: AmperAgentProactiveTriggerSource,
        updatedAtEpochMs: Long
    ): AmperAgentPersistedProactiveTriggerSource =
        memory.transaction {
            require(updatedAtEpochMs >= 0L)
            val existing = load(source.sourceId)
            if (existing != null) {
                require(existing.source.configurationId == source.configurationId) {
                    "proactive trigger configuration id cannot change under the same source id"
                }
                require(existing.source.kind == source.kind) {
                    "proactive trigger kind cannot change under the same source id"
                }
                if (existing.pendingObservations.isNotEmpty()) {
                    require(
                        existing.source.copy(enabled = source.enabled) == source
                    ) {
                        "proactive trigger configuration cannot change while observations are pending"
                    }
                }
            }

            val state = AmperAgentPersistedProactiveTriggerSource(
                source = source,
                lastAcceptedObservationAtEpochMs =
                    existing?.lastAcceptedObservationAtEpochMs,
                lastAcceptedObservationIdentitySha256 =
                    existing?.lastAcceptedObservationIdentitySha256,
                pendingObservations = existing?.pendingObservations.orEmpty(),
                updatedAtEpochMs =
                    updatedAtEpochMs.coerceAtLeast(existing?.updatedAtEpochMs ?: 0L)
            )
            remember(record(state))
            state
        }

    override fun get(sourceId: String): AmperAgentPersistedProactiveTriggerSource? =
        memory.transaction { load(sourceId) }

    override fun list(limit: Int): List<AmperAgentPersistedProactiveTriggerSource> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(KIND, (limit * 3).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == KIND }
            .mapNotNull { TriggerSourceStateCodec.decode(it.content).getOrNull() }
            .sortedWith(
                compareByDescending<AmperAgentPersistedProactiveTriggerSource> {
                    it.updatedAtEpochMs
                }.thenBy { it.source.sourceId }
            )
            .take(limit)
            .toList()
    }

    override fun setEnabled(
        sourceId: String,
        enabled: Boolean,
        updatedAtEpochMs: Long
    ): Result<AmperAgentPersistedProactiveTriggerSource> = runCatching {
        memory.transaction {
            require(updatedAtEpochMs >= 0L)
            val current = requireNotNull(load(sourceId)) {
                "proactive trigger source is not registered"
            }
            val state = current.copy(
                source = current.source.copy(enabled = enabled),
                updatedAtEpochMs = updatedAtEpochMs.coerceAtLeast(current.updatedAtEpochMs)
            )
            remember(record(state))
            state
        }
    }

    override fun remove(sourceId: String): Boolean =
        memory.transaction {
            val current = load(sourceId) ?: return@transaction false
            if (current.pendingObservations.isNotEmpty()) return@transaction false
            forget(recordId(sourceId))
        }

    override fun pending(
        sourceId: String
    ): List<AmperAgentPendingProactiveTriggerObservation> =
        memory.transaction { load(sourceId)?.pendingObservations.orEmpty() }

    override fun acknowledgePending(
        sourceId: String,
        observationIdentitySha256: String,
        updatedAtEpochMs: Long
    ): Result<AmperAgentPersistedProactiveTriggerSource> = runCatching {
        memory.transaction {
            require(observationIdentitySha256.matches(Regex("[0-9a-f]{64}")))
            require(updatedAtEpochMs >= 0L)
            val current = requireNotNull(load(sourceId)) {
                "proactive trigger source is not registered"
            }
            val first = requireNotNull(current.pendingObservations.firstOrNull()) {
                "proactive trigger source has no pending observation"
            }
            require(first.observationIdentitySha256 == observationIdentitySha256) {
                "proactive trigger pending observations must be acknowledged in FIFO order"
            }
            val next = current.copy(
                pendingObservations = current.pendingObservations.drop(1),
                updatedAtEpochMs = updatedAtEpochMs.coerceAtLeast(current.updatedAtEpochMs)
            )
            remember(record(next))
            next
        }
    }

    override fun accept(
        observation: AmperAgentProactiveTriggerObservation,
        acceptedAtEpochMs: Long
    ): Result<AmperAgentAcceptedProactiveTriggerObservation> = runCatching {
        memory.transaction {
            require(acceptedAtEpochMs >= 0L)
            val current = requireNotNull(load(observation.sourceId)) {
                "proactive trigger source is not registered"
            }

            val qualified = AmperAgentProactiveTriggerSourcePolicy
                .qualify(
                    source = current.source,
                    observation = observation,
                    lastAcceptedObservationAtEpochMs =
                        current.lastAcceptedObservationAtEpochMs
                )
                .getOrThrow()

            require(
                qualified.observationIdentitySha256 !=
                    current.lastAcceptedObservationIdentitySha256
            ) {
                "proactive trigger observation was already accepted"
            }
            require(
                current.pendingObservations.size <
                    AmperAgentPersistedProactiveTriggerSource.MAX_PENDING_OBSERVATIONS
            ) {
                "proactive trigger pending observation queue is full"
            }
            val pending = AmperAgentPendingProactiveTriggerObservation(
                observation = observation,
                observationIdentitySha256 = qualified.observationIdentitySha256
            )

            val next = current.copy(
                lastAcceptedObservationAtEpochMs = observation.observedAtEpochMs,
                lastAcceptedObservationIdentitySha256 =
                    qualified.observationIdentitySha256,
                pendingObservations = current.pendingObservations + pending,
                updatedAtEpochMs = maxOf(
                    current.updatedAtEpochMs,
                    acceptedAtEpochMs,
                    observation.observedAtEpochMs
                )
            )
            remember(record(next))
            AmperAgentAcceptedProactiveTriggerObservation(
                state = next,
                qualified = qualified
            )
        }
    }

    private fun MemoryOs.load(
        sourceId: String
    ): AmperAgentPersistedProactiveTriggerSource? =
        get(recordId(sourceId))
            ?.takeIf { it.kind == KIND }
            ?.let { TriggerSourceStateCodec.decode(it.content).getOrNull() }

    private fun record(
        state: AmperAgentPersistedProactiveTriggerSource
    ): MemoryRecord =
        MemoryRecord(
            id = recordId(state.source.sourceId),
            kind = KIND,
            content = TriggerSourceStateCodec.encode(state),
            importance = 0.97,
            provenance = Provenance(
                source = "user-configured-proactive-trigger",
                producer = "agent-proactive-trigger-registry",
                observedAtEpochMs = state.updatedAtEpochMs,
                confidence = 1.0
            ),
            createdAtEpochMs = state.updatedAtEpochMs
        )

    private fun recordId(sourceId: String): MemoryId =
        MemoryId("agent-proactive-trigger-source:${sourceId}")

    companion object {
        const val KIND = "agent-proactive-trigger-source-v1"
    }
}

internal object TriggerSourceStateCodec {
    private const val VERSION_V1 = "AMPER_AGENT_TRIGGER_SOURCE_STATE_V1"
    private const val VERSION = "AMPER_AGENT_TRIGGER_SOURCE_STATE_V2"

    fun encode(state: AmperAgentPersistedProactiveTriggerSource): String {
        val source = state.source
        return buildString {
            appendLine(VERSION)
            appendLine("SOURCE_ID\t${enc(source.sourceId)}")
            appendLine("CONFIG_ID\t${enc(source.configurationId)}")
            appendLine("KIND\t${source.kind.name}")
            appendLine("OBJECTIVE\t${enc(source.objective)}")
            source.allowedCapabilities
                .sortedBy { it.value }
                .forEach { appendLine("CAPABILITY\t${enc(it.value)}") }
            appendLine("EXPECTED_RUNTIME_MS\t${source.expectedRuntimeMs}")
            appendLine("MINIMUM_INTERVAL_MS\t${source.minimumIntervalMs}")
            appendLine("CONFIG_SHA256\t${source.configurationSha256}")
            appendLine("USER_CONFIGURED\t${source.userConfigured}")
            appendLine("ENABLED\t${source.enabled}")
            appendLine(
                "LAST_ACCEPTED_AT\t" +
                    (state.lastAcceptedObservationAtEpochMs?.toString() ?: "~")
            )
            appendLine(
                "LAST_ACCEPTED_IDENTITY\t" +
                    (state.lastAcceptedObservationIdentitySha256 ?: "~")
            )
            appendLine("UPDATED_AT\t${state.updatedAtEpochMs}")
            state.pendingObservations.forEach { pending ->
                appendLine(
                    listOf(
                        "PENDING",
                        pending.observation.observedAtEpochMs.toString(),
                        pending.observation.payloadDigest,
                        pending.observationIdentitySha256
                    ).joinToString("\t")
                )
            }
        }.trimEnd()
    }

    fun decode(content: String): Result<AmperAgentPersistedProactiveTriggerSource> =
        runCatching {
            val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
            val version = lines.firstOrNull()
            require(version == VERSION || version == VERSION_V1) {
                "unsupported proactive trigger-source state"
            }
            val scalars = linkedMapOf<String, String>()
            val capabilities = linkedSetOf<CapabilityId>()
            val pending = mutableListOf<Triple<Long, String, String>>()
            lines.drop(1).forEach { line ->
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    "CAPABILITY" -> {
                        require(parts.size == 2)
                        capabilities += CapabilityId(dec(parts[1]))
                    }
                    "PENDING" -> {
                        require(version == VERSION) {
                            "legacy proactive trigger-source state cannot contain pending observations"
                        }
                        require(parts.size == 4)
                        pending += Triple(
                            parts[1].toLong(),
                            parts[2],
                            parts[3]
                        )
                    }
                    "SOURCE_ID", "CONFIG_ID", "KIND", "OBJECTIVE",
                    "EXPECTED_RUNTIME_MS", "MINIMUM_INTERVAL_MS", "CONFIG_SHA256",
                    "USER_CONFIGURED", "ENABLED", "LAST_ACCEPTED_AT",
                    "LAST_ACCEPTED_IDENTITY", "UPDATED_AT" -> {
                        require(parts.size == 2)
                        require(scalars.put(parts[0], parts[1]) == null) {
                            "duplicate proactive trigger-source scalar"
                        }
                    }
                    else -> error("unknown proactive trigger-source field")
                }
            }
            val required = setOf(
                "SOURCE_ID",
                "CONFIG_ID",
                "KIND",
                "OBJECTIVE",
                "EXPECTED_RUNTIME_MS",
                "MINIMUM_INTERVAL_MS",
                "CONFIG_SHA256",
                "USER_CONFIGURED",
                "ENABLED",
                "LAST_ACCEPTED_AT",
                "LAST_ACCEPTED_IDENTITY",
                "UPDATED_AT"
            )
            require(scalars.keys == required)
            require(capabilities.isNotEmpty())

            val source = AmperAgentProactiveTriggerSource(
                sourceId = dec(scalars.getValue("SOURCE_ID")),
                configurationId = dec(scalars.getValue("CONFIG_ID")),
                kind = AmperAgentProactiveTriggerSourceKind.valueOf(
                    scalars.getValue("KIND")
                ),
                objective = dec(scalars.getValue("OBJECTIVE")),
                allowedCapabilities = capabilities,
                expectedRuntimeMs =
                    scalars.getValue("EXPECTED_RUNTIME_MS").toLong(),
                minimumIntervalMs =
                    scalars.getValue("MINIMUM_INTERVAL_MS").toLong(),
                configurationSha256 =
                    scalars.getValue("CONFIG_SHA256"),
                userConfigured =
                    scalars.getValue("USER_CONFIGURED").toBooleanStrict(),
                enabled = scalars.getValue("ENABLED").toBooleanStrict()
            )
            AmperAgentPersistedProactiveTriggerSource(
                source = source,
                lastAcceptedObservationAtEpochMs =
                    scalars.getValue("LAST_ACCEPTED_AT")
                        .takeUnless { it == "~" }
                        ?.toLong(),
                lastAcceptedObservationIdentitySha256 =
                    scalars.getValue("LAST_ACCEPTED_IDENTITY")
                        .takeUnless { it == "~" },
                pendingObservations = pending.map { (observedAt, payloadDigest, identity) ->
                    AmperAgentPendingProactiveTriggerObservation(
                        observation = AmperAgentProactiveTriggerObservation(
                            sourceId = source.sourceId,
                            observedAtEpochMs = observedAt,
                            payloadDigest = payloadDigest
                        ),
                        observationIdentitySha256 = identity
                    )
                },
                updatedAtEpochMs = scalars.getValue("UPDATED_AT").toLong()
            )
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(
            Base64.getUrlDecoder().decode(value),
            StandardCharsets.UTF_8
        )
}
