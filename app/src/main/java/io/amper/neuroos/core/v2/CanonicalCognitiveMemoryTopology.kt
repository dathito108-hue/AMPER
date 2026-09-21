package io.amper.neuroos.core.v2

import io.amper.neuroos.core.GlobalWorkspace
import io.amper.neuroos.core.MemoryOs

enum class CognitiveMemoryDomain {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PROCEDURAL
}

enum class CognitiveMemoryDurability {
    TRANSIENT_WITH_DURABLE_CONTINUITY,
    DURABLE
}

data class CognitiveMemoryDomainContract(
    val domain: CognitiveMemoryDomain,
    val durability: CognitiveMemoryDurability,
    val canonicalOwners: Set<String>,
    val durableBacking: String = DURABLE_BACKING,
    val transientBacking: String? = null,
    val authorityBearing: Boolean = false
) {
    init {
        require(canonicalOwners.isNotEmpty())
        require(canonicalOwners.none { it.isBlank() })
        require(durableBacking == DURABLE_BACKING)
        require(!authorityBearing)
        if (domain == CognitiveMemoryDomain.WORKING) {
            require(durability == CognitiveMemoryDurability.TRANSIENT_WITH_DURABLE_CONTINUITY)
            require(transientBacking == WORKING_BACKING)
        } else {
            require(durability == CognitiveMemoryDurability.DURABLE)
            require(transientBacking == null)
        }
    }

    companion object {
        const val DURABLE_BACKING = "MemoryOs"
        const val WORKING_BACKING = "GlobalWorkspace"
    }
}

/**
 * Phase671 canonical cognitive-memory topology seal.
 *
 * Metadata and identity binding only: no remember/recall/write facade is exposed. Every durable
 * cognitive-memory domain remains on the one MemoryOs supplied by AmperRuntime.build(). Working
 * memory may use the existing transient GlobalWorkspace while durable continuity snapshots remain
 * in that same MemoryOs.
 */
class CanonicalCognitiveMemoryTopology private constructor(
    private val durableMemory: MemoryOs,
    private val workingWorkspace: GlobalWorkspace,
    val domains: List<CognitiveMemoryDomainContract>
) {
    init {
        require(domains.map { it.domain }.toSet() == CognitiveMemoryDomain.entries.toSet())
        require(domains.size == CognitiveMemoryDomain.entries.size)
        require(domains.all { it.durableBacking == CognitiveMemoryDomainContract.DURABLE_BACKING })
        require(domains.none { it.authorityBearing })
    }

    internal fun usesDurableMemory(candidate: MemoryOs): Boolean = durableMemory === candidate
    internal fun usesWorkingWorkspace(candidate: GlobalWorkspace): Boolean =
        workingWorkspace === candidate

    val durableMemoryCount: Int get() = 1
    val modelBackendSpecificSilos: Int get() = 0

    companion object {
        fun bind(memory: MemoryOs, workspace: GlobalWorkspace): CanonicalCognitiveMemoryTopology =
            CanonicalCognitiveMemoryTopology(
                durableMemory = memory,
                workingWorkspace = workspace,
                domains = listOf(
                    CognitiveMemoryDomainContract(
                        domain = CognitiveMemoryDomain.WORKING,
                        durability = CognitiveMemoryDurability.TRANSIENT_WITH_DURABLE_CONTINUITY,
                        canonicalOwners = linkedSetOf(
                            "GlobalWorkspace",
                            "CanonicalWorkingMemoryWorkspace",
                            "WorkingMemoryContinuityStore",
                            "NativeSystem2WorkingStateStore"
                        ),
                        transientBacking = CognitiveMemoryDomainContract.WORKING_BACKING
                    ),
                    CognitiveMemoryDomainContract(
                        domain = CognitiveMemoryDomain.EPISODIC,
                        durability = CognitiveMemoryDurability.DURABLE,
                        canonicalOwners = linkedSetOf(
                            "CanonicalSovereignKernel",
                            "SovereignConversationCoordinator"
                        )
                    ),
                    CognitiveMemoryDomainContract(
                        domain = CognitiveMemoryDomain.SEMANTIC,
                        durability = CognitiveMemoryDurability.DURABLE,
                        canonicalOwners = linkedSetOf(
                            "SemanticKnowledgeStore",
                            "EpistemicState"
                        )
                    ),
                    CognitiveMemoryDomainContract(
                        domain = CognitiveMemoryDomain.PROCEDURAL,
                        durability = CognitiveMemoryDurability.DURABLE,
                        canonicalOwners = linkedSetOf(
                            "SkillGenesisModel",
                            "SkillGeneralizationModel",
                            "GoalRepairStrategyMemory"
                        )
                    )
                )
            )
    }
}
