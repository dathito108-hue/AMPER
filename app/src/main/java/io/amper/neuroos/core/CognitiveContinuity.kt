package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CognitiveContinuityBinding(
    val cognitiveStateDigest: String,
    val executionContextDigest: String
) {
    init {
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class CognitiveContinuityStatus {
    LEGACY_UNBOUND,
    STABLE,
    CONTEXT_CHANGED
}

data class CognitiveContinuityAssessment(
    val status: CognitiveContinuityStatus,
    val expectedExecutionContextDigest: String?,
    val currentExecutionContextDigest: String,
    val currentCognitiveStateDigest: String
) {
    init {
        require(expectedExecutionContextDigest == null || expectedExecutionContextDigest.matches(SHA256))
        require(currentExecutionContextDigest.matches(SHA256))
        require(currentCognitiveStateDigest.matches(SHA256))
    }

    val blocksExecution: Boolean
        get() = status == CognitiveContinuityStatus.CONTEXT_CHANGED

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Phase266-270 cognitive continuity binding.
 *
 * The full integrated cognitive digest is retained for audit/reproducibility while the execution
 * context digest intentionally excludes competence, learned-skill, transfer, curriculum and
 * metacognitive-readiness lines. Those learning signals may change after each governed step and
 * must not invalidate an otherwise still-grounded plan.
 *
 * Execution context includes identity/architecture invariants, the query, active goals, reconciled
 * semantic/epistemic evidence, predictive world state/causality and freshness-aware perceptual
 * grounding. A change therefore invalidates a newly bound plan before any further tool execution.
 *
 * Legacy plans without the Phase266 binding remain executable through the pre-existing live tool
 * revalidation path; they are marked LEGACY_UNBOUND rather than retroactively inventing evidence.
 */
object CognitiveContinuityPolicy {
    fun bind(state: IntegratedCognitiveStatePacket): CognitiveContinuityBinding =
        CognitiveContinuityBinding(
            cognitiveStateDigest = state.canonicalDigest,
            executionContextDigest = executionContextDigest(state)
        )

    fun assess(
        expectedExecutionContextDigest: String?,
        current: IntegratedCognitiveStatePacket
    ): CognitiveContinuityAssessment {
        val currentDigest = executionContextDigest(current)
        val status = when {
            expectedExecutionContextDigest == null -> CognitiveContinuityStatus.LEGACY_UNBOUND
            expectedExecutionContextDigest == currentDigest -> CognitiveContinuityStatus.STABLE
            else -> CognitiveContinuityStatus.CONTEXT_CHANGED
        }
        return CognitiveContinuityAssessment(
            status = status,
            expectedExecutionContextDigest = expectedExecutionContextDigest,
            currentExecutionContextDigest = currentDigest,
            currentCognitiveStateDigest = current.canonicalDigest
        )
    }

    fun executionContextDigest(state: IntegratedCognitiveStatePacket): String {
        val lines = state.canonicalLines().filter(::executionRelevant)
        require(lines.any { it.startsWith("query=") })
        return sha256(lines.joinToString("\n"))
    }

    private fun executionRelevant(line: String): Boolean =
        line.startsWith("query=") ||
            line.startsWith("identity=") ||
            line.startsWith("architecture=") ||
            line.startsWith("invariant=") ||
            line.startsWith("goal=") ||
            line.startsWith("semantic=") ||
            line.startsWith("belief=") ||
            line.startsWith("world=") ||
            line.startsWith("prediction=") ||
            line.startsWith("causal=") ||
            line.startsWith("percept=")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
