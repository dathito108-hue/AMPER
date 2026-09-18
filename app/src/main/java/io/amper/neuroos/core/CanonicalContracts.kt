package io.amper.neuroos.core

import java.util.UUID

@JvmInline value class CapabilityId(val value: String)
@JvmInline value class ModelId(val value: String)
@JvmInline value class MemoryId(val value: String)
@JvmInline value class GoalId(val value: String)
@JvmInline value class WorldFactId(val value: String)

data class Provenance(
    val source: String,
    val producer: String,
    val observedAtEpochMs: Long = System.currentTimeMillis(),
    val confidence: Double = 1.0,
    val parents: Set<MemoryId> = emptySet()
) {
    init {
        require(source.isNotBlank())
        require(producer.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

data class CognitiveEvent(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val payload: String,
    val salience: Double = 0.5
)

data class MemoryRecord(
    val id: MemoryId = MemoryId(UUID.randomUUID().toString()),
    val kind: String,
    val content: String,
    val importance: Double,
    val provenance: Provenance = Provenance(source = "unknown", producer = "unknown"),
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(kind.isNotBlank())
        require(content.isNotBlank())
        require(importance in 0.0..1.0)
    }
}

data class ResourceBudget(
    val maxConcurrentAgents: Int = 2,
    val memoryMb: Int = 512,
    val thermalClass: Int = 1
)

data class ModelDescriptor(
    val id: ModelId,
    val format: String,
    val capabilities: Set<CapabilityId>,
    val local: Boolean
)

data class SelfSnapshot(
    val identity: String,
    val architecture: String,
    val invariants: Set<String>,
    val lastIntent: String? = null
)

data class GoalState(
    val id: GoalId,
    val objective: String,
    val priority: Double,
    val status: String = "ACTIVE"
)

data class WorldFact(
    val id: WorldFactId = WorldFactId(UUID.randomUUID().toString()),
    val subject: String,
    val statement: String,
    val confidence: Double,
    val provenance: Provenance
)

interface GlobalWorkspace {
    fun publish(event: CognitiveEvent)
    fun snapshot(): List<CognitiveEvent>
}

interface MemoryOs {
    fun remember(record: MemoryRecord)

    /**
     * Atomically inserts [record] only when its id is not already present in this
     * Memory OS instance. Persistent implementations should override this with the
     * same lock that protects their journal and in-memory index.
     */
    fun rememberIfAbsent(record: MemoryRecord): Boolean = synchronized(this) {
        if (get(record.id) != null) return@synchronized false
        remember(record)
        true
    }

    /**
     * Executes a compound memory operation under one Memory OS synchronization
     * boundary. This is process-local/object-local atomicity, not a multi-process
     * file lock or distributed transaction guarantee.
     */
    fun <T> transaction(block: MemoryOs.() -> T): T = synchronized(this) { block() }

    /**
     * Requests physical journal compaction while preserving logical memory state.
     * Returns the number of durable journal lines removed. Implementations without
     * a physical append-only journal may keep the default no-op behavior.
     */
    fun compact(): Int = 0

    fun recall(query: String, limit: Int = 8): List<MemoryRecord>
    fun get(id: MemoryId): MemoryRecord?
    fun forget(id: MemoryId): Boolean
    fun size(): Int
}

interface ModelRegistry {
    fun register(model: ModelDescriptor)
    fun route(required: Set<CapabilityId>): ModelDescriptor?

    /**
     * Deterministic candidate order for feasibility-aware runtime routing.
     *
     * The default preserves compatibility with older/custom registries by exposing only their
     * existing single route. Registries that hold multiple models should override this to return
     * every capability-compatible candidate in their stable preference order.
     */
    fun candidates(required: Set<CapabilityId>): List<ModelDescriptor> =
        route(required)?.let(::listOf) ?: emptyList()
}

interface ResourceGovernor {
    fun currentBudget(): ResourceBudget
    fun allows(agentCount: Int): Boolean
}

interface AuthorityGate {
    fun authorize(capability: CapabilityId, reason: String): Boolean
}

interface ToolFabric {
    fun invoke(capability: CapabilityId, input: String, reason: String): Result<String>
}

interface MetaCognition {
    fun inspect(workspace: List<CognitiveEvent>): CognitiveEvent
}

interface SelfModel {
    fun snapshot(): SelfSnapshot
    fun observeIntent(intent: String): SelfSnapshot
}

interface GoalSystem {
    fun align(intent: String): GoalState
    fun active(): List<GoalState>
}

interface WorldModel {
    fun observe(event: CognitiveEvent, provenance: Provenance): WorldFact
    fun query(query: String, limit: Int = 8): List<WorldFact>
    fun size(): Int
}

interface SovereignKernel {
    fun tick(intent: String): TickReport
}

data class TickReport(
    val kernelState: String,
    val workspaceEvents: Int,
    val memoryRecords: Int,
    val modelRoute: String,
    val selfIdentity: String,
    val activeGoals: Int,
    val worldFacts: Int
)
