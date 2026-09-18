package io.amper.neuroos.core

import java.util.UUID

@JvmInline value class AgentId(val value: String)

data class AgentSpec(
    val role: String,
    val requiredCapabilities: Set<CapabilityId>,
    val purpose: String,
    val maxOutputTokens: Int = 512,
    val temperature: Double = 0.7,
    val preferredCapabilityProfiles: List<Set<CapabilityId>> = emptyList()
) {
    init {
        require(role.isNotBlank())
        require(requiredCapabilities.isNotEmpty())
        require(purpose.isNotBlank())
        require(maxOutputTokens > 0)
        require(temperature in 0.0..2.0)
        preferredCapabilityProfiles.forEach { profile ->
            require(profile.isNotEmpty()) { "preferred agent capability profile must not be empty" }
            require(profile.containsAll(requiredCapabilities)) {
                "preferred agent capability profile must contain all required capabilities"
            }
        }
    }
}

data class AgentLease(
    val id: AgentId = AgentId(UUID.randomUUID().toString()),
    val role: String,
    val model: ModelId,
    val purpose: String,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    val backendId: String? = null,
    val runtimeIdentity: ModelRuntimeIdentity? = null,
    val selectedCapabilities: Set<CapabilityId> = emptySet()
)

interface DynamicAgentFabric {
    fun spawn(spec: AgentSpec): Result<AgentLease>
    fun release(id: AgentId): Boolean
    fun active(): List<AgentLease>
}

/**
 * Ephemeral cognitive-agent lease manager.
 *
 * Reference/contract runtimes may omit [routePlanner] and lease the deterministic registry route.
 * Execution-capable runtimes should supply it: the lease is then issued only after the same
 * installed-artifact, identity, backend, capability-profile and resource checks used by Titan
 * inference have passed.
 */
class EphemeralAgentFabric(
    private val governor: ResourceGovernor,
    private val models: ModelRegistry,
    private val routePlanner: TitanInferenceRoutePlanner? = null
) : DynamicAgentFabric {
    private val leases = linkedMapOf<AgentId, AgentLease>()

    @Synchronized
    override fun spawn(spec: AgentSpec): Result<AgentLease> {
        val requestedCount = leases.size + 1
        if (!governor.allows(requestedCount)) {
            return Result.failure(IllegalStateException("resource governor denied agent count=$requestedCount"))
        }

        val lease = routePlanner?.let { planner ->
            val request = InferenceRequest(
                prompt = spec.purpose,
                requiredCapabilities = spec.requiredCapabilities,
                maxOutputTokens = spec.maxOutputTokens,
                temperature = spec.temperature,
                preferredCapabilityProfiles = spec.preferredCapabilityProfiles
            )
            val budget = governor.currentBudget()
            val route = planner.plan(request, budget).getOrElse { error ->
                return Result.failure(error)
            }
            AgentLease(
                role = spec.role,
                model = route.installed.descriptor.id,
                purpose = spec.purpose,
                backendId = route.backend.id,
                runtimeIdentity = route.runtimeIdentity,
                selectedCapabilities = route.selectedCapabilities
            )
        } ?: run {
            val model = models.route(spec.requiredCapabilities)
                ?: return Result.failure(
                    IllegalStateException(
                        "no model route for ${spec.requiredCapabilities.map { it.value }}"
                    )
                )
            AgentLease(
                role = spec.role,
                model = model.id,
                purpose = spec.purpose,
                selectedCapabilities = spec.requiredCapabilities
            )
        }

        leases[lease.id] = lease
        return Result.success(lease)
    }

    @Synchronized
    override fun release(id: AgentId): Boolean = leases.remove(id) != null

    @Synchronized
    override fun active(): List<AgentLease> = leases.values.toList()
}
