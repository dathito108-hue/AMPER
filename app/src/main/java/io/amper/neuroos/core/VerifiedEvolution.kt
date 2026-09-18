package io.amper.neuroos.core

import java.util.UUID

@JvmInline value class EvolutionId(val value: String)

enum class EvolutionStage {
    PROPOSED,
    VERIFIED,
    REJECTED,
    PROMOTED,
    ROLLED_BACK
}

data class EvolutionCandidate(
    val id: EvolutionId = EvolutionId(UUID.randomUUID().toString()),
    val description: String,
    val baseRevision: String,
    val proposedRevision: String,
    val artifactDigest: String
) {
    init {
        require(description.isNotBlank())
        require(baseRevision.isNotBlank())
        require(proposedRevision.isNotBlank())
        require(artifactDigest.isNotBlank())
    }
}

data class VerificationEvidence(
    val sandboxPassed: Boolean,
    val testsPassed: Boolean,
    val invariantResults: Map<String, Boolean>,
    val rollbackToken: String?
)

data class EvolutionDecision(
    val candidateId: EvolutionId,
    val stage: EvolutionStage,
    val promotable: Boolean,
    val reasons: List<String>,
    val rollbackToken: String?
)

interface VerifiedEvolution {
    fun evaluate(candidate: EvolutionCandidate, evidence: VerificationEvidence): EvolutionDecision
    fun promote(decision: EvolutionDecision): EvolutionDecision
    fun rollback(decision: EvolutionDecision): EvolutionDecision
}

class CanonicalEvolutionGate(private val selfModel: SelfModel) : VerifiedEvolution {
    override fun evaluate(candidate: EvolutionCandidate, evidence: VerificationEvidence): EvolutionDecision {
        val canonical = selfModel.snapshot().invariants
        val reasons = mutableListOf<String>()
        if (!evidence.sandboxPassed) reasons += "sandbox verification failed"
        if (!evidence.testsPassed) reasons += "required tests failed"
        if (evidence.rollbackToken.isNullOrBlank()) reasons += "rollback token missing"

        canonical.forEach { invariant ->
            when (evidence.invariantResults[invariant]) {
                true -> Unit
                false -> reasons += "canonical invariant failed: $invariant"
                null -> reasons += "canonical invariant not evaluated: $invariant"
            }
        }

        val verified = reasons.isEmpty()
        return EvolutionDecision(
            candidateId = candidate.id,
            stage = if (verified) EvolutionStage.VERIFIED else EvolutionStage.REJECTED,
            promotable = verified,
            reasons = reasons.toList(),
            rollbackToken = evidence.rollbackToken
        )
    }

    override fun promote(decision: EvolutionDecision): EvolutionDecision {
        require(decision.stage == EvolutionStage.VERIFIED && decision.promotable) {
            "only a verified candidate can be promoted"
        }
        require(!decision.rollbackToken.isNullOrBlank()) { "promotion requires rollback capability" }
        return decision.copy(stage = EvolutionStage.PROMOTED, promotable = false)
    }

    override fun rollback(decision: EvolutionDecision): EvolutionDecision {
        require(decision.stage == EvolutionStage.PROMOTED) { "only a promoted candidate can be rolled back" }
        require(!decision.rollbackToken.isNullOrBlank()) { "rollback token unavailable" }
        return decision.copy(stage = EvolutionStage.ROLLED_BACK, promotable = false)
    }
}
