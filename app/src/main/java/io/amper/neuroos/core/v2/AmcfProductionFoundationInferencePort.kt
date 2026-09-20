package io.amper.neuroos.core.v2

import io.amper.neuroos.core.CapabilityId
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.InferenceResponse
import io.amper.neuroos.core.TitanCortexRuntime
import java.security.MessageDigest

data class AmcfFoundationInferenceContext(
    val userPrompt: String,
    val maxOutputTokensPerCycle: Int = 384,
    val temperature: Double = 0.2,
    val conversationSessionId: String? = null
) {
    init {
        require(userPrompt.isNotBlank())
        require(userPrompt.length <= MAX_USER_PROMPT_CHARS) {
            "AMCF foundation prompt exceeds bounded mobile control limit"
        }
        require(maxOutputTokensPerCycle in 1..MAX_OUTPUT_TOKENS_PER_CYCLE)
        require(temperature in 0.0..1.0)
        conversationSessionId?.let {
            require(it.isNotBlank())
            require(it.length <= 160)
        }
    }

    companion object {
        const val MAX_USER_PROMPT_CHARS: Int = 64 * 1024
        const val MAX_OUTPUT_TOKENS_PER_CYCLE: Int = 512
    }
}

data class AmcfCycleQuality(
    val confidence: Double,
    val uncertainty: Double,
    val evidenceSufficiency: Double
) {
    init {
        require(confidence in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        require(evidenceSufficiency in 0.0..1.0)
    }
}

data class AmcfCycleQualityInput(
    val cycle: AmcfComputeCycle,
    val previousState: AmcfRecurrentState?,
    val response: InferenceResponse,
    val candidateDigest: String,
    val evidenceDigest: String
) {
    init {
        require(response.text.isNotBlank())
        require(candidateDigest.matches(Regex("[0-9a-f]{64}")))
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        previousState?.let { require(it.foundation == cycle.foundation) }
    }
}

/**
 * Structured quality evaluator only. Implementations may reuse existing metacognitive/cognitive
 * components, but they do not own inference, recurrent-state commit, tools, or authority.
 */
fun interface AmcfCycleQualityEvaluator {
    fun evaluate(input: AmcfCycleQualityInput): AmcfCycleQuality
}

/**
 * Safe fallback until richer existing metacognitive signals are wired.
 *
 * Deliberation never crosses the Phase641 early-exit thresholds under this fallback, so AMCF spends
 * the complete planned depth rather than inventing confidence from generated prose.
 */
object AmcfConservativeCycleQualityEvaluator : AmcfCycleQualityEvaluator {
    override fun evaluate(input: AmcfCycleQualityInput): AmcfCycleQuality =
        when (input.cycle.kind) {
            AmcfComputeCycleKind.DELIBERATE -> AmcfCycleQuality(
                confidence = 0.60,
                uncertainty = 0.40,
                evidenceSufficiency = 0.60
            )
            AmcfComputeCycleKind.VERIFY -> AmcfCycleQuality(
                confidence = 0.70,
                uncertainty = 0.30,
                evidenceSufficiency = 0.70
            )
            AmcfComputeCycleKind.REVISE -> AmcfCycleQuality(
                confidence = 0.72,
                uncertainty = 0.28,
                evidenceSufficiency = 0.72
            )
            AmcfComputeCycleKind.FINALIZE -> AmcfCycleQuality(
                confidence = 0.75,
                uncertainty = 0.25,
                evidenceSufficiency = 0.75
            )
        }
}

interface AmcfFoundationInferenceEndpoint {
    val foundation: AmcfFoundationBinding

    fun infer(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferenceResponse>
}

/**
 * Thin production adapter over the existing Titan runtime. In production Titan itself is built from
 * the sealed [io.amper.neuroos.core.AmperCoreInferencePort], so this adapter cannot register or
 * select a second inference backend.
 */
class TitanAmcfFoundationInferenceEndpoint(
    private val titan: TitanCortexRuntime,
    override val foundation: AmcfFoundationBinding
) : AmcfFoundationInferenceEndpoint {
    override fun infer(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferenceResponse> =
        titan.infer(
            request = request,
            cancellation = cancellation
        )
}

/**
 * Production M4 execution port for Phase642.
 *
 * Each AMCF cycle becomes one bounded reasoning-only request through the already-existing Titan ->
 * single AMPER Core -> AMI2/AMNE2 path. The port keeps only a bounded transient candidate answer
 * between cycles so VERIFY/REVISE/FINALIZE can refine the prior candidate. Authoritative recurrent
 * state remains digest/metric-only and is committed exclusively by [AmcfCycleOrchestrator].
 */
class AmcfProductionFoundationInferencePort(
    private val endpoint: AmcfFoundationInferenceEndpoint,
    private val context: AmcfFoundationInferenceContext,
    private val qualityEvaluator: AmcfCycleQualityEvaluator =
        AmcfConservativeCycleQualityEvaluator
) : AmcfCycleExecutionPort {
    private var lastExecutedCycleIndex: Int = 0
    private var transientCandidate: String? = null

    override fun execute(
        request: AmcfCycleExecutionRequest,
        cancellation: InferenceCancellationSignal?
    ): Result<AmcfCycleObservation> = runCatching {
        require(request.plan.foundation == endpoint.foundation) {
            "AMCF production port foundation does not match the active AMI2/AMNE2 endpoint"
        }
        require(request.cycle.foundation == endpoint.foundation)
        require(request.cycle.index > lastExecutedCycleIndex) {
            "AMCF production cycle execution must advance monotonically"
        }
        request.previousState?.let { previous ->
            require(previous.foundation == endpoint.foundation)
            require(previous.completedCycleIndex == lastExecutedCycleIndex) {
                "AMCF production port transient state is not aligned with committed recurrent state"
            }
        }
        if (request.previousState == null) {
            require(lastExecutedCycleIndex == 0) {
                "AMCF production port cannot restart a run with stale transient state"
            }
        }

        val signal = cancellation ?: InferenceCancellationSignal()
        signal.throwIfCancelled()

        val boundedRequest = InferenceRequest(
            prompt = renderCyclePrompt(request),
            requiredCapabilities = setOf(REASONING_CAPABILITY),
            maxOutputTokens = outputBudget(request.cycle.kind),
            temperature = context.temperature,
            conversationSessionId = context.conversationSessionId
        )

        val response = endpoint
            .infer(
                request = boundedRequest,
                cancellation = signal
            )
            .getOrThrow()

        signal.throwIfCancelled()
        require(response.text.isNotBlank()) {
            "AMCF production foundation inference returned an empty candidate"
        }

        val candidateText = response.text
            .trim()
            .take(MAX_TRANSIENT_CANDIDATE_CHARS)
        require(candidateText.isNotBlank())

        val candidateDigest = sha256(
            listOf(
                "AMCF-CANDIDATE-V1",
                endpoint.foundation.foundationId,
                endpoint.foundation.semanticSha256,
                request.cycle.index.toString(),
                request.cycle.kind.name,
                candidateText
            ).joinToString("|")
        )
        val previousEvidence = request.previousState?.evidenceDigest ?: EMPTY_EVIDENCE_DIGEST
        val evidenceDigest = sha256(
            listOf(
                "AMCF-EVIDENCE-V1",
                endpoint.foundation.foundationId,
                endpoint.foundation.semanticSha256,
                request.cycle.index.toString(),
                request.cycle.kind.name,
                previousEvidence,
                candidateDigest,
                sha256(context.userPrompt)
            ).joinToString("|")
        )
        val quality = qualityEvaluator.evaluate(
            AmcfCycleQualityInput(
                cycle = request.cycle,
                previousState = request.previousState,
                response = response,
                candidateDigest = candidateDigest,
                evidenceDigest = evidenceDigest
            )
        )

        signal.throwIfCancelled()

        // Transient candidate is not an authoritative recurrent-state commit. It exists only so the
        // next bounded cycle can refine the prior answer without storing hidden reasoning text.
        transientCandidate = candidateText
        lastExecutedCycleIndex = request.cycle.index

        AmcfCycleObservation(
            candidateDigest = candidateDigest,
            evidenceDigest = evidenceDigest,
            confidence = quality.confidence,
            uncertainty = quality.uncertainty,
            evidenceSufficiency = quality.evidenceSufficiency
        )
    }

    private fun renderCyclePrompt(request: AmcfCycleExecutionRequest): String = buildString {
        appendLine("<AMCF_FOUNDATION_CYCLE>")
        appendLine("authority=false")
        appendLine("foundation_id=" + endpoint.foundation.foundationId)
        appendLine("semantic_sha256=" + endpoint.foundation.semanticSha256)
        appendLine("execution_engine=" + endpoint.foundation.executionEngine)
        appendLine("cycle_index=" + request.cycle.index)
        appendLine("cycle_kind=" + request.cycle.kind.name)
        appendLine("compute_mode=" + request.plan.mode.name)
        request.previousState?.let { previous ->
            appendLine("previous_candidate_digest=" + previous.candidateDigest)
            appendLine("previous_evidence_digest=" + previous.evidenceDigest)
            appendLine("previous_confidence=" + format(previous.confidence))
            appendLine("previous_uncertainty=" + format(previous.uncertainty))
            appendLine("previous_evidence_sufficiency=" + format(previous.evidenceSufficiency))
        }
        transientCandidate?.let { candidate ->
            appendLine("<PREVIOUS_CANDIDATE>")
            appendLine(candidate)
            appendLine("</PREVIOUS_CANDIDATE>")
        }
        appendLine("<USER_REQUEST>")
        appendLine(context.userPrompt)
        appendLine("</USER_REQUEST>")
        appendLine(cycleInstruction(request.cycle.kind))
        appendLine(
            "instruction=Return only the next concise candidate answer. Do not expose hidden " +
                "chain-of-thought, tool claims, approvals, or authority."
        )
        append("</AMCF_FOUNDATION_CYCLE>")
    }

    private fun cycleInstruction(kind: AmcfComputeCycleKind): String =
        when (kind) {
            AmcfComputeCycleKind.DELIBERATE ->
                "instruction=Improve the candidate by resolving uncertainty and checking consistency."
            AmcfComputeCycleKind.VERIFY ->
                "instruction=Verify factual/logical consistency and return a corrected candidate only."
            AmcfComputeCycleKind.REVISE ->
                "instruction=Revise the candidate using the verification result; return the revision only."
            AmcfComputeCycleKind.FINALIZE ->
                "instruction=Produce the final user-facing answer only."
        }

    private fun outputBudget(kind: AmcfComputeCycleKind): Int {
        val requested = when (kind) {
            AmcfComputeCycleKind.DELIBERATE -> minOf(context.maxOutputTokensPerCycle, 256)
            AmcfComputeCycleKind.VERIFY -> minOf(context.maxOutputTokensPerCycle, 192)
            AmcfComputeCycleKind.REVISE -> minOf(context.maxOutputTokensPerCycle, 256)
            AmcfComputeCycleKind.FINALIZE -> context.maxOutputTokensPerCycle
        }
        return requested.coerceAtLeast(1)
    }

    companion object {
        private val REASONING_CAPABILITY = CapabilityId("reasoning")
        private const val MAX_TRANSIENT_CANDIDATE_CHARS: Int = 8 * 1024
        private val EMPTY_EVIDENCE_DIGEST: String = sha256("AMCF-EVIDENCE-EMPTY-V1")
    }
}

private fun format(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.6f", value)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
