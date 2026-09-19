package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@JvmInline
value class EvolutionCampaignId(val value: String) {
    init { require(value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
}

data class EvolutionGapObjective(
    val metricId: EvolutionBenchmarkMetricId,
    val currentScore: Double,
    val targetScore: Double,
    val priority: Double
) {
    init {
        require(currentScore in 0.0..1.0)
        require(targetScore in 0.0..1.0)
        require(targetScore >= currentScore)
        require(priority in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false
}

data class EvolutionCandidateBlueprint(
    val id: String,
    val kind: AutonomousEvolutionCandidateKind,
    val description: String,
    val payload: String,
    val targetMetrics: Set<EvolutionBenchmarkMetricId>
) {
    init {
        require(id.matches(Regex("[0-9a-f]{24}")))
        require(description.isNotBlank() && description.length <= 512)
        require(payload.isNotBlank() && payload.length <= MAX_PAYLOAD_CHARS)
        require(targetMetrics.isNotEmpty())
        require(targetMetrics.size <= EvolutionBenchmarkSuite.MAX_METRICS)
    }

    val canonicalDigest: String
        get() = evolutionCampaignSha256(
            listOf(
                kind.name,
                description,
                payload,
                targetMetrics.map { it.value }.sorted().joinToString(",")
            ).joinToString("|")
        )

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_PAYLOAD_CHARS = 12_000
    }
}

data class EvolutionCandidateGenerationRequest(
    val campaignId: EvolutionCampaignId,
    val baselineRevision: String,
    val suite: EvolutionBenchmarkSuite,
    val baseline: EvolutionBenchmarkSnapshot,
    val objectives: List<EvolutionGapObjective>,
    val allowedKinds: Set<AutonomousEvolutionCandidateKind>,
    val maxCandidates: Int
) {
    init {
        require(baselineRevision.isNotBlank() && baselineRevision.length <= 512)
        require(baseline.suiteId == suite.id)
        require(baseline.suiteDigest == suite.canonicalDigest)
        requireNotNull(baseline.artifactDigest)
        require(objectives.isNotEmpty())
        require(objectives.size <= EvolutionBenchmarkSuite.MAX_METRICS)
        require(allowedKinds.isNotEmpty())
        require(maxCandidates in 1..MAX_CANDIDATES)
    }

    companion object {
        const val MAX_CANDIDATES = 4
    }
}

data class EvolutionCandidateGenerationBatch(
    val blueprints: List<EvolutionCandidateBlueprint>,
    val backendId: String? = null,
    val modelId: ModelId? = null
) {
    init {
        require(blueprints.isNotEmpty())
        require(blueprints.size <= EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
        require(blueprints.map { it.id }.distinct().size == blueprints.size)
        require(backendId == null || backendId.isNotBlank())
    }
}

fun interface EvolutionCandidateGeneratorPort {
    fun generate(
        request: EvolutionCandidateGenerationRequest
    ): Result<EvolutionCandidateGenerationBatch>
}

data class EvolutionSandboxCandidateEvidence(
    val artifactDigest: String,
    val proposedRevision: String,
    val metrics: Map<EvolutionBenchmarkMetricId, EvolutionMetricObservation>,
    val sandboxPassed: Boolean,
    val testsPassed: Boolean,
    val invariantResults: Map<String, Boolean>,
    val rollbackToken: String?,
    val observedAtEpochMs: Long
) {
    init {
        require(artifactDigest.matches(Regex("[0-9a-f]{64}")))
        require(proposedRevision.isNotBlank() && proposedRevision.length <= 512)
        require(metrics.isNotEmpty())
        require(metrics.size <= EvolutionBenchmarkSuite.MAX_METRICS)
        require(observedAtEpochMs >= 0L)
    }
}

fun interface EvolutionSandboxRunner {
    fun run(
        blueprint: EvolutionCandidateBlueprint,
        request: EvolutionCandidateGenerationRequest
    ): Result<EvolutionSandboxCandidateEvidence>
}

data class AutonomousEvolutionCampaignResult(
    val campaignId: EvolutionCampaignId,
    val objectives: List<EvolutionGapObjective>,
    val blueprintDigests: List<String>,
    val candidateIds: List<EvolutionId>,
    val tournamentDecision: EvolutionTournamentDecision?,
    val promotionProposal: EvolutionPromotionProposal?,
    val generatorBackendId: String?,
    val generatorModelId: ModelId?,
    val createdAtEpochMs: Long
) {
    init {
        require(objectives.isNotEmpty())
        require(blueprintDigests.size <= EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
        require(blueprintDigests.all { it.matches(Regex("[0-9a-f]{64}")) })
        require(candidateIds.size <= EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
        require(createdAtEpochMs >= 0L)
        if (promotionProposal != null) {
            require(tournamentDecision?.winnerCandidateId == promotionProposal.candidateId)
        }
    }

    val livePromoted: Boolean
        get() = false

    val authorityBearing: Boolean
        get() = false
}

object EvolutionGapDetector {
    fun detect(
        suite: EvolutionBenchmarkSuite,
        baseline: EvolutionBenchmarkSnapshot,
        limit: Int = 8
    ): List<EvolutionGapObjective> {
        require(limit >= 0)
        require(baseline.suiteId == suite.id)
        require(baseline.suiteDigest == suite.canonicalDigest)
        if (limit == 0) return emptyList()

        return suite.metrics.mapNotNull { metric ->
            val observation = baseline.metrics[metric.id] ?: return@mapNotNull null
            if (observation.score >= 1.0) return@mapNotNull null
            val target = maxOf(
                metric.minCandidateScore,
                (observation.score + 0.02).coerceAtMost(1.0)
            )
            val floorGap = (metric.minCandidateScore - observation.score).coerceAtLeast(0.0)
            val headroom = 1.0 - observation.score
            val rawPriority = (floorGap * 0.65 + headroom * 0.35) * metric.weight
            EvolutionGapObjective(
                metricId = metric.id,
                currentScore = observation.score,
                targetScore = target,
                priority = rawPriority.coerceIn(0.0, 1.0)
            )
        }.sortedWith(
            compareByDescending<EvolutionGapObjective> { it.priority }
                .thenBy { it.metricId.value }
        ).take(limit)
    }
}

/**
 * Phase242 model-facing generator. The model returns data-only blueprints; no tool execution,
 * deployment result, benchmark score, artifact digest or promotion claim is accepted from it.
 */
class InferenceEvolutionCandidateGenerator(
    private val inference: CognitiveInferencePort,
    private val maxOutputTokens: Int = 2048
) : EvolutionCandidateGeneratorPort {
    init {
        require(maxOutputTokens in 128..8192)
    }

    override fun generate(
        request: EvolutionCandidateGenerationRequest
    ): Result<EvolutionCandidateGenerationBatch> = runCatching {
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt(request),
                requiredCapabilities = setOf(TitanCapabilities.REASONING),
                maxOutputTokens = maxOutputTokens,
                temperature = 0.35,
                preferredCapabilityProfiles = listOf(
                    setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
                )
            )
        ).getOrThrow()
        val blueprints = EvolutionCandidateBlueprintProtocol.parse(
            response.text,
            request
        )
        EvolutionCandidateGenerationBatch(
            blueprints = blueprints,
            backendId = response.backendId,
            modelId = response.modelId
        )
    }

    private fun prompt(request: EvolutionCandidateGenerationRequest): String = buildString {
        appendLine("AMPER autonomous evolution candidate generation.")
        appendLine("Return data only. Do not claim builds, tests, benchmarks, deployment, permission, authority, or external actions.")
        appendLine("Generate at most ${request.maxCandidates} distinct candidate blueprints.")
        appendLine("Baseline revision=${SovereignPromptData.escape(request.baselineRevision)}")
        appendLine(
            "Allowed kinds=" +
                request.allowedKinds.map { it.name }.sorted().joinToString(",")
        )
        appendLine("Objectives:")
        request.objectives.forEach { objective ->
            appendLine(
                "- metric=${objective.metricId.value} current=${objective.currentScore} " +
                    "target=${objective.targetScore} priority=${objective.priority}"
            )
        }
        appendLine("Output exact protocol:")
        appendLine("AMPER_EVOLUTION_BLUEPRINTS_V1")
        appendLine("C|<KIND>|<DESCRIPTION_BASE64URL>|<PAYLOAD_BASE64URL>|<metric1,metric2>")
        appendLine("One C line per candidate; no other lines.")
    }
}

internal object EvolutionCandidateBlueprintProtocol {
    private const val HEADER = "AMPER_EVOLUTION_BLUEPRINTS_V1"

    fun parse(
        output: String,
        request: EvolutionCandidateGenerationRequest
    ): List<EvolutionCandidateBlueprint> {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == HEADER) { "evolution blueprint protocol header missing" }
        require(lines.size in 2..(request.maxCandidates + 1)) {
            "evolution blueprint protocol candidate count out of bounds"
        }
        val allowedMetrics = request.objectives.map { it.metricId }.toSet()
        val decoded = lines.drop(1).map { line ->
            val parts = line.split('|')
            require(parts.size == 5 && parts[0] == "C") {
                "invalid evolution blueprint record"
            }
            val kind = AutonomousEvolutionCandidateKind.valueOf(parts[1])
            require(kind in request.allowedKinds) { "evolution blueprint kind is not allowed" }
            val description = decode(parts[2])
            val payload = decode(parts[3])
            val metrics = parts[4].split(',')
                .filter { it.isNotBlank() }
                .map(::EvolutionBenchmarkMetricId)
                .toSet()
            require(metrics.isNotEmpty() && allowedMetrics.containsAll(metrics)) {
                "evolution blueprint targets unknown objective metric"
            }
            val digest = evolutionCampaignSha256(
                listOf(
                    kind.name,
                    description,
                    payload,
                    metrics.map { it.value }.sorted().joinToString(",")
                ).joinToString("|")
            )
            EvolutionCandidateBlueprint(
                id = digest.take(24),
                kind = kind,
                description = description,
                payload = payload,
                targetMetrics = metrics
            )
        }
        require(decoded.map { it.id }.distinct().size == decoded.size) {
            "duplicate evolution blueprints are not allowed"
        }
        return decoded
    }

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

/**
 * Phase241-245 closes the loop from benchmark weakness to autonomous tournament entry.
 *
 * Phase241 derives bounded improvement objectives from the current benchmark baseline.
 * Phase242 performs one model inference to generate bounded data-only candidate blueprints.
 * Phase243 sends each blueprint to an injected sandbox runner; sandbox evidence, not model text,
 * supplies artifact digest, test/invariant results and benchmark metrics.
 * Phase244 derives candidate identity from blueprint+artifact evidence and runs all valid entries
 * through the canonical tournament.
 * Phase245 automatically requests a canonical promotion ticket for the winner. It still does not
 * mutate live state; Phase236-240 remains the only deployment transaction path.
 */
class MemoryBackedAutonomousEvolutionCampaignCoordinator(
    private val memory: MemoryOs,
    private val evolution: AutonomousEvolutionModel,
    private val generator: EvolutionCandidateGeneratorPort,
    private val sandbox: EvolutionSandboxRunner,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun run(
        campaignId: EvolutionCampaignId,
        baselineRevision: String,
        suiteId: EvolutionBenchmarkSuiteId,
        baseline: EvolutionBenchmarkSnapshot,
        allowedKinds: Set<AutonomousEvolutionCandidateKind> =
            AutonomousEvolutionCandidateKind.entries.toSet(),
        maxCandidates: Int = EvolutionCandidateGenerationRequest.MAX_CANDIDATES
    ): AutonomousEvolutionCampaignResult {
        require(maxCandidates in 1..EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
        require(allowedKinds.isNotEmpty())
        val suite = requireNotNull(evolution.getSuite(suiteId)) {
            "evolution campaign benchmark suite unavailable"
        }
        require(baseline.suiteId == suite.id)
        require(baseline.suiteDigest == suite.canonicalDigest)
        requireNotNull(baseline.artifactDigest) {
            "evolution campaign baseline must be content-addressed"
        }
        val objectives = EvolutionGapDetector.detect(suite, baseline)
        require(objectives.isNotEmpty()) {
            "evolution campaign has no remaining benchmark gap"
        }
        val request = EvolutionCandidateGenerationRequest(
            campaignId = campaignId,
            baselineRevision = baselineRevision,
            suite = suite,
            baseline = baseline,
            objectives = objectives,
            allowedKinds = allowedKinds,
            maxCandidates = maxCandidates
        )
        val generated = generator.generate(request).getOrThrow()
        val blueprints = generated.blueprints.take(maxCandidates)
        require(blueprints.all { it.kind in allowedKinds })
        val allowedMetrics = objectives.map { it.metricId }.toSet()
        require(blueprints.all { allowedMetrics.containsAll(it.targetMetrics) })

        val entries = blueprints.mapNotNull { blueprint ->
            val evidence = sandbox.run(blueprint, request).getOrNull()
                ?: return@mapNotNull null
            val candidateId = EvolutionId(
                "candidate-" +
                    evolutionCampaignSha256(
                        blueprint.canonicalDigest + "|" + evidence.artifactDigest
                    ).take(24)
            )
            val candidate = EvolutionCandidate(
                id = candidateId,
                description = blueprint.description,
                baseRevision = baselineRevision,
                proposedRevision = evidence.proposedRevision,
                artifactDigest = evidence.artifactDigest
            )
            EvolutionTournamentEntry(
                candidate = candidate,
                kind = blueprint.kind,
                benchmark = EvolutionBenchmarkSnapshot(
                    subjectId = EvolutionBenchmarkSubjectId(candidateId.value),
                    suiteId = suite.id,
                    suiteDigest = suite.canonicalDigest,
                    metrics = evidence.metrics,
                    artifactDigest = evidence.artifactDigest,
                    observedAtEpochMs = evidence.observedAtEpochMs
                ),
                verification = VerificationEvidence(
                    sandboxPassed = evidence.sandboxPassed,
                    testsPassed = evidence.testsPassed,
                    invariantResults = evidence.invariantResults,
                    rollbackToken = evidence.rollbackToken
                )
            )
        }

        val decision = entries.takeIf { it.isNotEmpty() }?.let {
            evolution.runTournament(
                id = EvolutionTournamentId(
                    "campaign-" + evolutionCampaignSha256(campaignId.value).take(24)
                ),
                baseline = baseline,
                candidates = it
            )
        }
        val proposal = decision?.let(evolution::promotionProposal)
        val result = AutonomousEvolutionCampaignResult(
            campaignId = campaignId,
            objectives = objectives,
            blueprintDigests = blueprints.map { it.canonicalDigest },
            candidateIds = entries.map { it.candidate.id },
            tournamentDecision = decision,
            promotionProposal = proposal,
            generatorBackendId = generated.backendId,
            generatorModelId = generated.modelId,
            createdAtEpochMs = clock()
        )
        persist(result)
        return result
    }

    private fun persist(result: AutonomousEvolutionCampaignResult) {
        memory.rememberIfAbsent(
            MemoryRecord(
                id = MemoryId("evolution-campaign:" + result.campaignId.value),
                kind = CAMPAIGN_KIND,
                content = listOf(
                    "v=1",
                    "campaign=" + encodeEvolutionCampaign(result.campaignId.value),
                    "blueprints=" + result.blueprintDigests.joinToString(","),
                    "candidates=" + result.candidateIds.joinToString(",") {
                        encodeEvolutionCampaign(it.value)
                    },
                    "winner=" + (
                        result.tournamentDecision?.winnerCandidateId?.value
                            ?.let(::encodeEvolutionCampaign) ?: "~"
                    ),
                    "promotion=" + (result.promotionProposal?.ticketDigest ?: "~"),
                    "created=" + result.createdAtEpochMs
                ).joinToString(";"),
                importance = if (result.promotionProposal == null) 0.82 else 0.98,
                provenance = Provenance(
                    source = "autonomous-evolution-campaign",
                    producer = "evolution-campaign-coordinator",
                    confidence = 1.0
                ),
                createdAtEpochMs = result.createdAtEpochMs
            )
        ).also { inserted ->
            require(inserted) { "evolution campaign id already exists" }
        }
    }

    companion object {
        const val CAMPAIGN_KIND = "evolution-campaign"
    }
}

private fun encodeEvolutionCampaign(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun evolutionCampaignSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }