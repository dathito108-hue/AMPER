package io.amper.neuroos.core

import java.util.Locale

/**
 * Ephemeral goal-conditioned retrieval over already-governed strategy evidence.
 *
 * The current goal is tokenized only in memory for this ranking call. No goal text or goal tokens
 * are written to StrategyLearningModel. Relevance can only re-rank evidence that has already
 * passed Phase176/177 evidence and adaptation gates; it cannot resurrect zero-support strategies
 * or grant any execution authority.
 */
object GoalConditionedStrategyRetrieval {
    const val MAX_GOAL_TERMS = 24
    private const val EVIDENCE_WEIGHT = 0.75
    private const val GOAL_WEIGHT = 0.25

    fun rank(
        candidates: List<StrategyGuidanceCandidate>,
        goal: String,
        descriptors: Collection<ToolDescriptor>,
        limit: Int = EvidenceGroundedStrategyGuidance.MAX_CANDIDATES
    ): List<StrategyGuidanceCandidate> {
        require(limit in 0..EvidenceGroundedStrategyGuidance.MAX_CANDIDATES)
        if (limit == 0 || candidates.isEmpty()) return emptyList()

        val bounded = candidates.take(EvidenceGroundedStrategyGuidance.MAX_CANDIDATES)
        val goalTerms = terms(goal).take(MAX_GOAL_TERMS).toSet()
        if (goalTerms.isEmpty()) return bounded.take(limit)

        val descriptorTerms = descriptors
            .associate { descriptor ->
                descriptor.capability to terms(
                    buildString {
                        append(descriptor.capability.value)
                        append(' ')
                        append(descriptor.name)
                        append(' ')
                        append(descriptor.inputContract.description)
                        if (descriptor.inputContract.acceptedValues.isNotEmpty()) {
                            append(' ')
                            append(descriptor.inputContract.acceptedValues.joinToString(" "))
                        }
                    }
                ).toSet()
            }

        val scored = bounded.map { candidate ->
            val relevance = relevance(
                candidate = candidate,
                goalTerms = goalTerms,
                descriptorTerms = descriptorTerms
            )
            ScoredCandidate(
                candidate = candidate,
                relevance = relevance,
                retrievalScore = candidate.evidenceSupport *
                    (EVIDENCE_WEIGHT + GOAL_WEIGHT * relevance)
            )
        }

        // If the current goal has no lexical signal against the live tool surface, do not invent
        // a preference. Preserve the evidence-grounded ordering from Phase176/177.
        if (scored.none { it.relevance > 0.0 }) return bounded.take(limit)

        return scored
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.retrievalScore }
                    .thenByDescending { it.candidate.evidenceSupport }
                    .thenByDescending { it.candidate.completedAttempts }
                    .thenBy { it.candidate.signature.canonical }
            )
            .map { it.candidate }
            .take(limit)
    }

    private fun relevance(
        candidate: StrategyGuidanceCandidate,
        goalTerms: Set<String>,
        descriptorTerms: Map<CapabilityId, Set<String>>
    ): Double {
        val perCapability = candidate.signature.capabilities.map { capability ->
            descriptorTerms[capability].orEmpty()
        }
        if (perCapability.isEmpty()) return 0.0

        val matchedCapabilities = perCapability.count { terms ->
            terms.any { it in goalTerms }
        }
        val capabilityCoverage = matchedCapabilities.toDouble() / perCapability.size.toDouble()

        val strategyTerms = perCapability.flatten().toSet()
        if (strategyTerms.isEmpty()) return capabilityCoverage

        val overlap = goalTerms.intersect(strategyTerms).size
        val overlapDenominator = minOf(goalTerms.size, strategyTerms.size).coerceAtLeast(1)
        val lexicalOverlap = overlap.toDouble() / overlapDenominator.toDouble()

        return (capabilityCoverage * 0.70 + lexicalOverlap * 0.30).coerceIn(0.0, 1.0)
    }

    private fun terms(value: String): List<String> {
        val normalized = value
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .lowercase(Locale.ROOT)
        return TOKEN.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_TERMS }
            .distinct()
            .take(MAX_GOAL_TERMS * 2)
            .toList()
    }

    private data class ScoredCandidate(
        val candidate: StrategyGuidanceCandidate,
        val relevance: Double,
        val retrievalScore: Double
    )

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val STOP_TERMS = setOf(
        "a", "an", "and", "the", "to", "of", "for", "with", "from", "this", "that",
        "please", "user", "current", "one", "value",
        "và", "là", "cho", "với", "của", "này", "hãy", "tôi", "giúp", "cần", "được"
    )
}
