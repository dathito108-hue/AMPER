package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class IntegratedCognitiveReadiness(
    val epistemicConfidence: Double,
    val worldConfidence: Double,
    val skillConfidence: Double,
    val competenceConfidence: Double,
    val uncertainty: Double,
    val learningPressure: Double,
    val overallReadiness: Double
) {
    init {
        listOf(
            epistemicConfidence,
            worldConfidence,
            skillConfidence,
            competenceConfidence,
            uncertainty,
            learningPressure,
            overallReadiness
        ).forEach { require(it in 0.0..1.0) }
    }

    val authorityBearing: Boolean
        get() = false
}

data class IntegratedCognitiveStatePacket(
    val queryDigest: String,
    val context: SovereignContextSnapshot,
    val skillGuidance: List<SkillGuidance>,
    val skillCompositions: List<SkillComposition>,
    val transferGuidance: List<GeneralizationGuidance>,
    val generalizedChains: List<GeneralizedSkillChain>,
    val learningNeeds: List<LearningNeed>,
    val readiness: IntegratedCognitiveReadiness,
    val capturedAtEpochMs: Long,
    val perceptualEvidence: List<PerceptualGroundingEvidence> = emptyList()
) {
    init {
        require(queryDigest.matches(SHA256))
        require(skillGuidance.size <= MAX_SKILLS)
        require(skillCompositions.size <= MAX_COMPOSITIONS)
        require(transferGuidance.size <= MAX_TRANSFER)
        require(generalizedChains.size <= MAX_CHAINS)
        require(learningNeeds.size <= MAX_NEEDS)
        require(perceptualEvidence.size <= MAX_PERCEPTS)
        require(capturedAtEpochMs >= 0L)
        require(perceptualEvidence.none { it.authorityBearing })
        require(skillGuidance.none { it.contract.authorityBearing })
        require(transferGuidance.none { it.profile.authorityBearing || it.skill.authorityBearing })
        require(generalizedChains.none { it.authorityBearing })
        require(learningNeeds.none { it.authorityBearing })
        require(!readiness.authorityBearing)
    }

    val authorityBearing: Boolean
        get() = false

    val canonicalDigest: String
        get() = integratedCognitiveSha256(canonicalLines().joinToString("\n"))

    internal fun canonicalLines(): List<String> = buildList {
        add("query=" + queryDigest)
        add("identity=" + context.self.identity)
        add("architecture=" + context.self.architecture)
        context.self.invariants.sorted().forEach { add("invariant=" + it) }
        context.goals
            .sortedWith(compareByDescending<GoalState> { it.priority }.thenBy { it.id.value })
            .forEach {
                add(
                    "goal=" + it.id.value + "|" + it.status + "|" +
                        fmt(it.priority)
                )
            }
        context.episodicMemories
            .sortedWith(
                compareBy<EpisodicMemoryEntry> { it.observedAtEpochMs }
                    .thenBy { it.id.value }
            )
            .forEach {
                add(
                    "episode=" + it.id.value + "|" + it.origin.name + "|" +
                        fmt(it.importance) + "|" + it.fingerprintSha256
                )
            }
        context.semanticKnowledge
            .sortedWith(compareBy<SemanticKnowledgeEntry> { it.subject }.thenBy { it.predicate })
            .forEach {
                add(
                    "semantic=" + it.subject + "|" + it.predicate + "|" +
                        (it.value ?: "unknown") + "|" + fmt(it.confidence)
                )
            }
        context.epistemicBeliefs
            .sortedWith(compareBy<EpistemicAssessment> { it.subject }.thenBy { it.predicate })
            .forEach {
                add(
                    "belief=" + it.subject + "|" + it.predicate + "|" +
                        (it.preferredValue ?: "unknown") + "|" + it.status + "|" +
                        it.planningEligible + "|" + fmt(it.confidence)
                )
            }
        context.structuredWorldStates
            .sortedBy { it.key.canonical }
            .forEach {
                add(
                    "world=" + it.key.canonical + "|" + (it.value ?: "unknown") + "|" +
                        it.status + "|" + fmt(it.confidence)
                )
            }
        context.worldPredictions
            .sortedBy { it.targetKey.canonical }
            .forEach {
                add(
                    "prediction=" + it.targetKey.canonical + "|" + it.predictedValue + "|" +
                        it.basis + "|" + fmt(it.confidence)
                )
            }
        context.causalHypotheses
            .sortedWith(
                compareBy<CausalWorldHypothesis> { it.causeKey.canonical }
                    .thenBy { it.effectKey.canonical }
            )
            .forEach {
                add(
                    "causal=" + it.causeKey.canonical + "|" + it.causeValue + "|" +
                        it.effectKey.canonical + "|" + it.effectValue + "|" +
                        it.support + "|" + it.contradictions + "|" + fmt(it.confidence)
                )
            }
        context.capabilityCompetence
            .sortedBy { it.capability.value }
            .forEach {
                add(
                    "competence=" + it.capability.value + "|" + it.executed + "|" +
                        it.failed + "|" + fmt(it.evidenceConfidence)
                )
            }
        skillGuidance
            .sortedBy { it.contract.id.value }
            .forEach {
                add(
                    "skill=" + it.contract.id.value + "|" +
                        it.contract.signature.capabilities.joinToString(">") { capability ->
                            capability.value
                        } + "|" + fmt(it.contract.confidence) + "|" +
                        it.preconditionsSatisfied + "|" + fmt(it.goalRelevance)
                )
            }
        skillCompositions
            .sortedWith(
                compareBy<SkillComposition> { it.first.id.value }
                    .thenBy { it.second.id.value }
            )
            .forEach {
                add(
                    "composition=" + it.first.id.value + ">" + it.second.id.value + "|" +
                        it.capabilities.joinToString(">") { capability -> capability.value } +
                        "|" + fmt(it.confidence)
                )
            }
        transferGuidance
            .sortedBy { it.skill.id.value }
            .forEach {
                add(
                    "transfer=" + it.skill.id.value + "|" + it.profile.maturity + "|" +
                        it.novelContext + "|" + fmt(it.transferConfidence) + "|" +
                        fmt(it.goalRelevance)
                )
            }
        generalizedChains
            .sortedBy { chain -> chain.skills.joinToString(">") { it.id.value } }
            .forEach {
                add(
                    "chain=" + it.skills.joinToString(">") { skill -> skill.id.value } + "|" +
                        it.capabilities.joinToString(">") { capability -> capability.value } + "|" +
                        fmt(it.confidence) + "|" + it.novelContext
                )
            }
        perceptualEvidence
            .sortedWith(
                compareBy<PerceptualGroundingEvidence> { it.modality.name }
                    .thenBy { it.source }
                    .thenBy { it.observedAtEpochMs }
            )
            .forEach {
                add(
                    "percept=" + it.modality.name + "|" + it.source + "|" + it.producer + "|" +
                        it.summary + "|" + fmt(it.confidence) + "|" + it.observedAtEpochMs + "|" +
                        it.freshness.name + "|" + it.planningEligible + "|" + fmt(it.queryRelevance)
                )
            }
        learningNeeds
            .sortedWith(
                compareByDescending<LearningNeed> { it.severity }
                    .thenBy { it.capability.value }
                    .thenBy { it.kind.name }
            )
            .forEach {
                add(
                    "need=" + it.capability.value + "|" + it.kind + "|" +
                        fmt(it.severity) + "|" + fmt(it.evidenceConfidence)
                )
            }
        add(
            "readiness=" + fmt(readiness.epistemicConfidence) + "|" +
                fmt(readiness.worldConfidence) + "|" +
                fmt(readiness.skillConfidence) + "|" +
                fmt(readiness.competenceConfidence) + "|" +
                fmt(readiness.uncertainty) + "|" +
                fmt(readiness.learningPressure) + "|" +
                fmt(readiness.overallReadiness)
        )
    }

    companion object {
        const val MAX_SKILLS = 4
        const val MAX_COMPOSITIONS = 3
        const val MAX_TRANSFER = 4
        const val MAX_CHAINS = 3
        const val MAX_NEEDS = 8
        const val MAX_PERCEPTS = WorldBackedPerceptualGroundingSource.MAX_EVIDENCE
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun fmt(value: Double): String =
            "%.6f".format(Locale.US, value)
    }
}

interface IntegratedCognitiveStateSource {
    fun capture(
        query: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): IntegratedCognitiveStatePacket
}

/**
 * Phase251-255 integrated cognitive decision state.
 *
 * Phase251 captures epistemic/semantic/world state once for a decision.
 * Phase252 folds live skill/generalization evidence into the same ephemeral packet.
 * Phase253 derives bounded uncertainty/readiness and active-learning pressure.
 * Phase254 gives planning a single packet digest and shared advisory evidence.
 * Phase255 gives the independent critic the exact same packet rather than recapturing mutable state.
 * Phase261-265 additionally bind typed, freshness-aware perception.* world evidence into the same
 * packet/digest so live device observations cannot drift between planner and critic.
 *
 * The packet is never persisted, contains no ToolProvider handles or approval bits, and cannot grant
 * authority. Live ToolDescriptor binding and AuthorityGate remain external and authoritative.
 */
class CanonicalIntegratedCognitiveStateSource(
    private val context: SovereignContextSource,
    private val skills: SkillGenesisModel,
    private val generalization: SkillGeneralizationModel,
    private val autonomousLearning: AutonomousLearningModel,
    private val perceptualGrounding: PerceptualGroundingSource? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : IntegratedCognitiveStateSource {
    override fun capture(
        query: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): IntegratedCognitiveStatePacket {
        require(query.isNotBlank())
        val liveDescriptors = descriptors
            .filter { it.capability in allowedCapabilities }
            .distinctBy { it.id }
            .sortedBy { it.capability.value }

        val snapshot = context.capture(
            query = query,
            memoryLimit = 6,
            worldLimit = 4,
            workspaceLimit = 6
        )
        val percepts = perceptualGrounding?.capture(
            query = query,
            limit = IntegratedCognitiveStatePacket.MAX_PERCEPTS
        ).orEmpty()
        val worldStates = snapshot.structuredWorldStates

        val directSkills = skills.guidance(
            goal = query,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors,
            worldStates = worldStates,
            limit = IntegratedCognitiveStatePacket.MAX_SKILLS
        )
        val compositions = skills.compositions(
            goal = query,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors,
            worldStates = worldStates,
            limit = IntegratedCognitiveStatePacket.MAX_COMPOSITIONS
        )
        val transfer = generalization.guidance(
            goal = query,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors,
            worldStates = worldStates,
            limit = IntegratedCognitiveStatePacket.MAX_TRANSFER
        )
        val chains = generalization.chains(
            goal = query,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors,
            worldStates = worldStates,
            limit = IntegratedCognitiveStatePacket.MAX_CHAINS
        )
        val needs = autonomousLearning.diagnose(
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors,
            limit = IntegratedCognitiveStatePacket.MAX_NEEDS
        )

        val readiness = readiness(
            snapshot = snapshot,
            skills = directSkills,
            compositions = compositions,
            transfer = transfer,
            chains = chains,
            needs = needs,
            percepts = percepts
        )

        return IntegratedCognitiveStatePacket(
            queryDigest = integratedCognitiveSha256(normalizeQuery(query)),
            context = snapshot,
            skillGuidance = directSkills,
            skillCompositions = compositions,
            transferGuidance = transfer,
            generalizedChains = chains,
            learningNeeds = needs,
            readiness = readiness,
            capturedAtEpochMs = clock(),
            perceptualEvidence = percepts
        )
    }

    private fun readiness(
        snapshot: SovereignContextSnapshot,
        skills: List<SkillGuidance>,
        compositions: List<SkillComposition>,
        transfer: List<GeneralizationGuidance>,
        chains: List<GeneralizedSkillChain>,
        needs: List<LearningNeed>,
        percepts: List<PerceptualGroundingEvidence>
    ): IntegratedCognitiveReadiness {
        val epistemicValues = buildList {
            snapshot.semanticKnowledge.forEach { add(it.confidence) }
            snapshot.epistemicBeliefs
                .filter { it.planningEligible }
                .forEach { add(it.confidence) }
        }
        val worldValues = buildList {
            snapshot.structuredWorldStates
                .filter { it.status == StructuredWorldStateStatus.KNOWN }
                .forEach { add(it.confidence) }
            snapshot.worldPredictions.forEach { add(it.confidence) }
            snapshot.causalHypotheses.forEach { add(it.confidence) }
            percepts
                .filter { it.planningEligible }
                .forEach { percept ->
                    val freshnessWeight = when (percept.freshness) {
                        PerceptualFreshness.FRESH -> 1.0
                        PerceptualFreshness.RECENT -> 0.85
                        PerceptualFreshness.STALE -> 0.0
                    }
                    add((percept.confidence * freshnessWeight).coerceIn(0.0, 1.0))
                }
        }
        val skillValues = buildList {
            skills.forEach { add(it.contract.confidence * it.goalRelevance) }
            compositions.forEach { add(it.confidence) }
            transfer.forEach { add(it.transferConfidence * it.goalRelevance) }
            chains.forEach { add(it.confidence) }
        }
        val competenceValues = snapshot.capabilityCompetence.map { it.evidenceConfidence }

        val epistemicConfidence = averageOrNeutral(epistemicValues)
        val worldConfidence = averageOrNeutral(worldValues)
        val skillConfidence = averageOrNeutral(skillValues)
        val competenceConfidence = averageOrNeutral(competenceValues)

        val unresolvedBeliefs = snapshot.epistemicBeliefs.count { !it.planningEligible }
        val beliefUncertainty = if (snapshot.epistemicBeliefs.isEmpty()) {
            0.0
        } else {
            unresolvedBeliefs.toDouble() / snapshot.epistemicBeliefs.size.toDouble()
        }
        val unknownWorld = snapshot.structuredWorldStates.count {
            it.status != StructuredWorldStateStatus.KNOWN
        }
        val worldUncertainty = if (snapshot.structuredWorldStates.isEmpty()) {
            0.0
        } else {
            unknownWorld.toDouble() / snapshot.structuredWorldStates.size.toDouble()
        }
        val predictionUncertainty = if (snapshot.worldPredictions.isEmpty()) {
            0.0
        } else {
            1.0 - snapshot.worldPredictions.map { it.confidence }.average()
        }
        val perceptualUncertainty = if (percepts.isEmpty()) {
            null
        } else {
            val staleFraction =
                percepts.count { !it.planningEligible }.toDouble() / percepts.size.toDouble()
            val confidenceUncertainty =
                1.0 - percepts.map { it.confidence }.average().coerceIn(0.0, 1.0)
            (0.65 * staleFraction + 0.35 * confidenceUncertainty).coerceIn(0.0, 1.0)
        }
        val uncertaintyParts = mutableListOf(
            beliefUncertainty,
            worldUncertainty,
            predictionUncertainty
        )
        perceptualUncertainty?.let(uncertaintyParts::add)
        val uncertainty = uncertaintyParts.average().coerceIn(0.0, 1.0)

        val learningPressure = needs.maxOfOrNull { it.severity }?.coerceIn(0.0, 1.0) ?: 0.0
        val evidenceReadiness = listOf(
            epistemicConfidence,
            worldConfidence,
            skillConfidence,
            competenceConfidence
        ).average()
        val overall = (
            evidenceReadiness *
                (1.0 - uncertainty * 0.45) *
                (1.0 - learningPressure * 0.20)
            ).coerceIn(0.0, 1.0)

        return IntegratedCognitiveReadiness(
            epistemicConfidence = epistemicConfidence,
            worldConfidence = worldConfidence,
            skillConfidence = skillConfidence,
            competenceConfidence = competenceConfidence,
            uncertainty = uncertainty,
            learningPressure = learningPressure,
            overallReadiness = overall
        )
    }

    private fun averageOrNeutral(values: List<Double>): Double =
        if (values.isEmpty()) 0.5 else values.average().coerceIn(0.0, 1.0)

    private fun normalizeQuery(query: String): String =
        query.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").take(1024)
}

object IntegratedCognitiveStateRenderer {
    fun render(
        packet: IntegratedCognitiveStatePacket,
        charBudget: Int
    ): String {
        require(charBudget >= 256)
        val open = "<INTEGRATED_COGNITIVE_STATE>\n"
        val close = "</INTEGRATED_COGNITIVE_STATE>"
        val lines = buildList {
            add("digest=" + packet.canonicalDigest)
            add("query_digest=" + packet.queryDigest)
            add("authority=false")
            add(
                "readiness overall=" + fmt(packet.readiness.overallReadiness) +
                    " uncertainty=" + fmt(packet.readiness.uncertainty) +
                    " learning_pressure=" + fmt(packet.readiness.learningPressure)
            )
            packet.context.semanticKnowledge.take(4).forEach {
                add(
                    "semantic " + safe(it.subject, 48) + " " + safe(it.predicate, 48) + "=" +
                        safe(it.value ?: "unknown", 80) +
                        " confidence=" + fmt(it.confidence)
                )
            }
            packet.context.structuredWorldStates.take(4).forEach {
                add(
                    "world " + safe(it.key.canonical, 96) + "=" +
                        safe(it.value ?: "unknown", 80) +
                        " status=" + it.status.name +
                        " confidence=" + fmt(it.confidence)
                )
            }
            packet.context.worldPredictions.take(3).forEach {
                add(
                    "prediction " + safe(it.targetKey.canonical, 96) + "=" +
                        safe(it.predictedValue, 80) +
                        " confidence=" + fmt(it.confidence)
                )
            }
            packet.perceptualEvidence.take(4).forEach {
                add(
                    "perception modality=" + it.modality.name +
                        " summary=" + safe(it.summary, 160) +
                        " freshness=" + it.freshness.name +
                        " confidence=" + fmt(it.confidence) +
                        " relevance=" + fmt(it.queryRelevance) +
                        " source=" + safe(it.source, 64) +
                        " planning_eligible=" + it.planningEligible +
                        " authority=false"
                )
            }
            packet.skillGuidance.take(3).forEach {
                add(
                    "skill " + safe(it.contract.id.value, 64) +
                        " capabilities=" +
                        it.contract.signature.capabilities.joinToString(">") { capability ->
                            safe(capability.value, 48)
                        } +
                        " confidence=" + fmt(it.contract.confidence) +
                        " relevance=" + fmt(it.goalRelevance)
                )
            }
            packet.transferGuidance.take(3).forEach {
                add(
                    "transfer " + safe(it.skill.id.value, 64) +
                        " maturity=" + it.profile.maturity.name +
                        " confidence=" + fmt(it.transferConfidence) +
                        " novel_context=" + it.novelContext
                )
            }
            packet.learningNeeds.take(4).forEach {
                add(
                    "learning_need capability=" + safe(it.capability.value, 64) +
                        " kind=" + it.kind.name +
                        " severity=" + fmt(it.severity)
                )
            }
        }

        val accepted = mutableListOf<String>()
        lines.forEach { line ->
            val candidate = open + (accepted + line).joinToString("\n") + "\n" + close
            if (candidate.length <= charBudget) accepted += line
        }
        return open + accepted.joinToString("\n") + "\n" + close
    }

    private fun safe(value: String, limit: Int): String =
        SovereignPromptData.bounded(value, limit)
            .replace('\n', ' ')
            .replace('\r', ' ')

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}

private fun integratedCognitiveSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }