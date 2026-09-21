package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

enum class SkillGeneralizationMaturity {
    LOCAL,
    TRANSFERABLE,
    GENERALIZED,
    DEGRADED
}

enum class GeneralizationObservationOutcome {
    SUCCESS,
    EXECUTION_FAILURE
}

data class SkillGeneralizationProfile(
    val skillId: SkillId,
    val signature: StrategySignature,
    val successes: Int,
    val executionFailures: Int,
    val successfulContexts: List<String>,
    val failedContexts: List<String>,
    val maturity: SkillGeneralizationMaturity,
    val confidence: Double,
    val lastObservedAtEpochMs: Long
) {
    init {
        require(successes >= 0)
        require(executionFailures >= 0)
        require(successes + executionFailures > 0)
        require(successfulContexts.distinct().size == successfulContexts.size)
        require(failedContexts.distinct().size == failedContexts.size)
        require((successfulContexts + failedContexts).all { it.matches(SHA256) })
        require(confidence in 0.0..1.0)
        require(lastObservedAtEpochMs >= 0L)
    }

    val attempts: Int
        get() = successes + executionFailures

    val successRate: Double
        get() = successes.toDouble() / attempts.toDouble()

    val successfulContextCount: Int
        get() = successfulContexts.size

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class GeneralizationGuidance(
    val profile: SkillGeneralizationProfile,
    val skill: SkillContract,
    val novelContext: Boolean,
    val goalRelevance: Double,
    val transferConfidence: Double
) {
    init {
        require(profile.skillId == skill.id)
        require(profile.signature == skill.signature)
        require(profile.maturity == SkillGeneralizationMaturity.TRANSFERABLE ||
            profile.maturity == SkillGeneralizationMaturity.GENERALIZED)
        require(skill.maturity == SkillMaturity.ACTIVE)
        require(goalRelevance in 0.0..1.0)
        require(transferConfidence in 0.0..1.0)
        require(!profile.authorityBearing && !skill.authorityBearing)
    }
}

data class GeneralizedSkillChain(
    val skills: List<SkillContract>,
    val capabilities: List<CapabilityId>,
    val preconditions: List<SkillStateCondition>,
    val effects: List<SkillStateCondition>,
    val confidence: Double,
    val novelContext: Boolean
) {
    init {
        require(skills.size in 2..MAX_SKILLS)
        require(skills.map { it.id }.distinct().size == skills.size)
        require(capabilities.isNotEmpty())
        require(capabilities.size <= TitanPlanProtocol.MAX_STEPS)
        require(confidence in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_SKILLS = 3
    }
}

interface SkillGeneralizationModel {
    fun observe(plan: SovereignPlan, skill: SkillContract?): SkillGeneralizationProfile?
    fun snapshot(signature: StrategySignature): SkillGeneralizationProfile?
    fun recent(limit: Int = 8): List<SkillGeneralizationProfile>

    fun guidance(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int = 4
    ): List<GeneralizationGuidance>

    fun chains(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int = 3
    ): List<GeneralizedSkillChain>
}

/**
 * Phase201-205 evidence-grounded cross-task generalization.
 *
 * Phase201 hashes a normalized goal-context fingerprint. Raw goal text is never persisted in the
 * generalization model.
 *
 * Phase202 records transfer evidence only from an exact governed terminal execution outcome. A
 * repeated plan observation is idempotent and authority/environment/protocol/user-abort outcomes
 * provide no positive or negative transfer credit.
 *
 * Phase203 promotes LOCAL -> TRANSFERABLE -> GENERALIZED only when the same skill succeeds across
 * distinct goal contexts. Execution failures can degrade previously transferable evidence.
 *
 * Phase204 searches bounded multi-skill chains. A next skill may be appended only when the
 * simulated effects of earlier skills satisfy its live learned preconditions and at least one of
 * those preconditions is actually produced by the chain.
 *
 * Phase205 exposes cross-task guidance to planning as escaped advisory data. It contains no raw
 * inputs, outputs, ToolProvider handles, approval bits or authority. The model must still emit a
 * normal AMPER plan which TitanPlanProtocol binds against the current live tool surface.
 */
class MemoryBackedSkillGeneralizationModel(
    private val memory: MemoryOs,
    private val skills: SkillGenesisModel,
    private val clock: () -> Long = System::currentTimeMillis
) : SkillGeneralizationModel {
    override fun observe(
        plan: SovereignPlan,
        skill: SkillContract?
    ): SkillGeneralizationProfile? {
        if (!plan.complete || skill == null) return null
        val outcome = classify(plan) ?: return null
        val signature = StrategySignature.from(plan)
        require(skill.signature == signature) { "generalization skill signature changed" }
        val contextId = contextFingerprint(plan.goal, signature)
        val markerId = markerId(plan.id)

        return memory.transaction {
            val existingMarker = get(markerId)
            if (existingMarker != null) {
                val marker = GeneralizationCodec.decodeMarker(existingMarker.content)
                    ?: error("generalization observation marker is malformed")
                require(marker.signatureDigest == signature.digest) {
                    "generalization observation signature changed"
                }
                require(marker.contextDigest == contextId) {
                    "generalization observation goal context changed"
                }
                require(marker.outcome == outcome) {
                    "generalization observation outcome changed"
                }
                return@transaction snapshotLocked(signature)
            }

            val previous = snapshotLocked(signature)
            val successes = (previous?.successes ?: 0) +
                if (outcome == GeneralizationObservationOutcome.SUCCESS) 1 else 0
            val failures = (previous?.executionFailures ?: 0) +
                if (outcome == GeneralizationObservationOutcome.EXECUTION_FAILURE) 1 else 0

            val successfulContexts = if (outcome == GeneralizationObservationOutcome.SUCCESS) {
                boundedContexts(previous?.successfulContexts.orEmpty(), contextId)
            } else {
                previous?.successfulContexts.orEmpty()
            }
            val failedContexts = if (outcome == GeneralizationObservationOutcome.EXECUTION_FAILURE) {
                boundedContexts(previous?.failedContexts.orEmpty(), contextId)
            } else {
                previous?.failedContexts.orEmpty()
            }

            val attempts = successes + failures
            val successRate = successes.toDouble() / attempts.toDouble()
            val evidenceConfidence = attempts.toDouble() / (attempts.toDouble() + 3.0)
            val diversityConfidence =
                successfulContexts.size.toDouble() / (successfulContexts.size.toDouble() + 2.0)
            val confidence = (
                successRate * (0.55 * evidenceConfidence + 0.45 * diversityConfidence)
            ).coerceIn(0.0, 1.0)
            val maturity = maturity(
                previous = previous?.maturity,
                successes = successes,
                successRate = successRate,
                successfulContexts = successfulContexts.size
            )
            val now = clock().coerceAtLeast(previous?.lastObservedAtEpochMs ?: 0L)

            val updated = SkillGeneralizationProfile(
                skillId = skill.id,
                signature = signature,
                successes = successes,
                executionFailures = failures,
                successfulContexts = successfulContexts,
                failedContexts = failedContexts,
                maturity = maturity,
                confidence = confidence,
                lastObservedAtEpochMs = now
            )
            remember(
                MemoryRecord(
                    id = markerId,
                    kind = OBSERVATION_KIND,
                    content = GeneralizationCodec.encodeMarker(
                        signatureDigest = signature.digest,
                        contextDigest = contextId,
                        outcome = outcome
                    ),
                    importance = 0.62,
                    provenance = Provenance(
                        source = "governed-cross-task-outcome",
                        producer = "skill-generalization",
                        confidence = 1.0,
                        parents = setOf(MemoryId("skill-contract:${signature.digest}"))
                    ),
                    createdAtEpochMs = now
                )
            )
            remember(
                MemoryRecord(
                    id = snapshotId(signature),
                    kind = SNAPSHOT_KIND,
                    content = GeneralizationCodec.encodeProfile(updated),
                    importance = when (maturity) {
                        SkillGeneralizationMaturity.GENERALIZED -> 0.94
                        SkillGeneralizationMaturity.TRANSFERABLE -> 0.88
                        SkillGeneralizationMaturity.LOCAL -> 0.72
                        SkillGeneralizationMaturity.DEGRADED -> 0.68
                    },
                    provenance = Provenance(
                        source = "cross-task-skill-evidence",
                        producer = "skill-generalization",
                        confidence = confidence,
                        parents = setOf(markerId)
                    ),
                    createdAtEpochMs = now
                )
            )
            updateIndexLocked(signature.digest, now)
            updated
        }
    }

    override fun snapshot(signature: StrategySignature): SkillGeneralizationProfile? =
        memory.transaction { snapshotLocked(signature) }

    override fun recent(limit: Int): List<SkillGeneralizationProfile> {
        require(limit in 0..ProceduralMemoryPolicy.MAX_GENERALIZATION_RECENT)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { digest ->
                    get(MemoryId("skill-generalization:$digest"))
                        ?.takeIf { it.kind == SNAPSHOT_KIND }
                        ?.let { GeneralizationCodec.decodeProfile(it.content) }
                }
                .take(limit)
                .toList()
        }
    }

    override fun guidance(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int
    ): List<GeneralizationGuidance> {
        require(goal.isNotBlank())
        require(limit in 0..MAX_GUIDANCE)
        if (limit == 0) return emptyList()

        val descriptorByCapability = descriptors.associateBy { it.capability }
        val current = currentConditions(worldStates)
        val goalTerms = terms(goal)

        return recent(GENERALIZATION_LOOKBACK)
            .asSequence()
            .filter(ProceduralMemoryPolicy::generalizationQualified)
            .mapNotNull { profile ->
                val skill = skills.snapshot(profile.signature) ?: return@mapNotNull null
                if (
                    !ProceduralMemoryPolicy.skillCapabilityQualified(
                        skill = skill,
                        allowedCapabilities = allowedCapabilities,
                        liveCapabilities = descriptorByCapability.keys
                    )
                ) return@mapNotNull null
                if (!conditionsSatisfied(skill.preconditions, current)) return@mapNotNull null

                val context = contextFingerprint(goal, profile.signature)
                val novel = context !in profile.successfulContexts
                val relevance = relevance(
                    capabilities = skill.signature.capabilities,
                    conditions = skill.preconditions + skill.effects,
                    goalTerms = goalTerms,
                    descriptors = descriptorByCapability
                )
                val noveltyFactor = if (novel) NOVEL_CONTEXT_DISCOUNT else 1.0
                GeneralizationGuidance(
                    profile = profile,
                    skill = skill,
                    novelContext = novel,
                    goalRelevance = relevance,
                    transferConfidence = (
                        profile.confidence * skill.confidence *
                            (0.80 + 0.20 * relevance) * noveltyFactor
                    ).coerceIn(0.0, 1.0)
                )
            }
            .sortedWith(
                compareByDescending<GeneralizationGuidance> { it.transferConfidence }
                    .thenByDescending { it.profile.successfulContextCount }
                    .thenBy { it.skill.signature.canonical }
            )
            .take(limit)
            .toList()
    }

    override fun chains(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int
    ): List<GeneralizedSkillChain> {
        require(goal.isNotBlank())
        require(limit in 0..MAX_CHAINS)
        if (limit == 0) return emptyList()

        val descriptorByCapability = descriptors.associateBy { it.capability }
        val profiles = recent(GENERALIZATION_LOOKBACK)
            .filter(ProceduralMemoryPolicy::generalizationQualified)
            .associateBy { it.signature.digest }
        val eligible = skills.recent(SKILL_LOOKBACK)
            .filter { profiles.containsKey(it.signature.digest) }
            .filter { skill ->
                ProceduralMemoryPolicy.skillCapabilityQualified(
                    skill = skill,
                    allowedCapabilities = allowedCapabilities,
                    liveCapabilities = descriptorByCapability.keys
                )
            }
        if (eligible.size < 2) return emptyList()

        val initialState = currentConditions(worldStates)
        val goalTerms = terms(goal)
        val results = mutableListOf<GeneralizedSkillChain>()

        data class Node(
            val skills: List<SkillContract>,
            val state: Map<String, String>,
            val producedKeys: Set<String>,
            val capabilities: List<CapabilityId>,
            val confidence: Double,
            val novelContext: Boolean
        )

        var frontier = eligible
            .filter { conditionsSatisfied(it.preconditions, initialState) }
            .map { first ->
                val profile = profiles.getValue(first.signature.digest)
                val context = contextFingerprint(goal, first.signature)
                val novel = context !in profile.successfulContexts
                Node(
                    skills = listOf(first),
                    state = applyEffects(initialState, first.effects),
                    producedKeys = first.effects.map { it.key.canonical }.toSet(),
                    capabilities = first.signature.capabilities,
                    confidence = (
                        first.confidence * profile.confidence *
                            (if (novel) NOVEL_CONTEXT_DISCOUNT else 1.0)
                    ).coerceIn(0.0, 1.0),
                    novelContext = novel
                )
            }

        repeat(GeneralizedSkillChain.MAX_SKILLS - 1) {
            val nextFrontier = mutableListOf<Node>()
            frontier.forEach { node ->
                eligible.forEach candidateLoop@ { next ->
                    if (node.skills.any { it.id == next.id }) return@candidateLoop
                    if (next.preconditions.isEmpty()) return@candidateLoop
                    if (!conditionsSatisfied(next.preconditions, node.state)) return@candidateLoop

                    val producedDependency = next.preconditions.any { condition ->
                        condition.key.canonical in node.producedKeys
                    }
                    if (!producedDependency) return@candidateLoop

                    val capabilities = node.capabilities + next.signature.capabilities
                    if (capabilities.size > TitanPlanProtocol.MAX_STEPS) return@candidateLoop

                    val profile = profiles.getValue(next.signature.digest)
                    val context = contextFingerprint(goal, next.signature)
                    val novel = context !in profile.successfulContexts
                    val nextConfidence = minOf(
                        node.confidence,
                        next.confidence * profile.confidence *
                            (if (novel) NOVEL_CONTEXT_DISCOUNT else 1.0)
                    ).coerceIn(0.0, 1.0)
                    val nextNode = Node(
                        skills = node.skills + next,
                        state = applyEffects(node.state, next.effects),
                        producedKeys = node.producedKeys + next.effects.map { it.key.canonical },
                        capabilities = capabilities,
                        confidence = nextConfidence,
                        novelContext = node.novelContext || novel
                    )
                    val relevance = relevance(
                        capabilities = capabilities,
                        conditions = nextNode.skills.flatMap {
                            it.preconditions + it.effects
                        },
                        goalTerms = goalTerms,
                        descriptors = descriptorByCapability
                    )
                    results += GeneralizedSkillChain(
                        skills = nextNode.skills,
                        capabilities = capabilities,
                        preconditions = node.skills.first().preconditions,
                        effects = next.effects,
                        confidence = (
                            nextConfidence * (0.85 + 0.15 * relevance)
                        ).coerceIn(0.0, 1.0),
                        novelContext = nextNode.novelContext
                    )
                    nextFrontier += nextNode
                }
            }
            frontier = nextFrontier
            if (frontier.isEmpty()) return@repeat
        }

        return results
            .distinctBy { chain -> chain.skills.joinToString(">") { it.id.value } }
            .sortedWith(
                compareByDescending<GeneralizedSkillChain> { it.confidence }
                    .thenByDescending { it.skills.size }
                    .thenBy { it.capabilities.joinToString(">") { capability -> capability.value } }
            )
            .take(limit)
    }

    private fun maturity(
        previous: SkillGeneralizationMaturity?,
        successes: Int,
        successRate: Double,
        successfulContexts: Int
    ): SkillGeneralizationMaturity = when {
        successfulContexts >= MIN_GENERALIZED_CONTEXTS &&
            successes >= MIN_GENERALIZED_SUCCESSES &&
            successRate >= MIN_GENERALIZED_SUCCESS_RATE ->
            SkillGeneralizationMaturity.GENERALIZED

        successfulContexts >= MIN_TRANSFER_CONTEXTS &&
            successes >= MIN_TRANSFER_SUCCESSES &&
            successRate >= MIN_TRANSFER_SUCCESS_RATE ->
            SkillGeneralizationMaturity.TRANSFERABLE

        previous == SkillGeneralizationMaturity.TRANSFERABLE ||
            previous == SkillGeneralizationMaturity.GENERALIZED ->
            SkillGeneralizationMaturity.DEGRADED

        else -> SkillGeneralizationMaturity.LOCAL
    }

    private fun classify(plan: SovereignPlan): GeneralizationObservationOutcome? {
        val exactExecuted = plan.steps.all { step ->
            val outcome = step.outcome
            val proposal = outcome?.proposal
            outcome != null &&
                proposal != null &&
                step.status == PlanStepStatus.EXECUTED &&
                outcome.status == ActionStatus.EXECUTED &&
                outcome.toolId == step.boundToolId &&
                outcome.sideEffect == step.boundSideEffect &&
                proposal.requestId == step.requestId &&
                proposal.capability == step.capability
        }
        if (exactExecuted) return GeneralizationObservationOutcome.SUCCESS

        val exactFailure = plan.steps.any { step ->
            val outcome = step.outcome
            val proposal = outcome?.proposal
            outcome != null &&
                proposal != null &&
                step.status == PlanStepStatus.FAILED &&
                outcome.status == ActionStatus.FAILED &&
                outcome.toolId == step.boundToolId &&
                outcome.sideEffect == step.boundSideEffect &&
                proposal.requestId == step.requestId &&
                proposal.capability == step.capability
        }
        return if (exactFailure) GeneralizationObservationOutcome.EXECUTION_FAILURE else null
    }

    private fun contextFingerprint(goal: String, signature: StrategySignature): String {
        require(goal.isNotBlank())
        val normalizedTerms = terms(goal).sorted()
        val goalMaterial = if (normalizedTerms.isEmpty()) {
            goal.trim().lowercase(Locale.ROOT).take(MAX_FINGERPRINT_GOAL_CHARS)
        } else {
            normalizedTerms.joinToString("|")
        }
        return sha256(signature.digest + "|" + goalMaterial)
    }

    private fun boundedContexts(existing: List<String>, context: String): List<String> =
        (existing.filterNot { it == context } + context).takeLast(MAX_CONTEXTS)

    private fun currentConditions(states: List<StructuredWorldState>): Map<String, String> =
        states.asSequence()
            .filter { it.status == StructuredWorldStateStatus.KNOWN && !it.value.isNullOrBlank() }
            .associate { it.key.canonical to normalize(requireNotNull(it.value)) }

    private fun conditionsSatisfied(
        conditions: List<SkillStateCondition>,
        state: Map<String, String>
    ): Boolean = conditions.all {
        state[it.key.canonical] == normalize(it.value)
    }

    private fun applyEffects(
        state: Map<String, String>,
        effects: List<SkillStateCondition>
    ): Map<String, String> = state.toMutableMap().apply {
        effects.forEach { this[it.key.canonical] = normalize(it.value) }
    }.toMap()

    private fun relevance(
        capabilities: List<CapabilityId>,
        conditions: List<SkillStateCondition>,
        goalTerms: Set<String>,
        descriptors: Map<CapabilityId, ToolDescriptor>
    ): Double {
        if (goalTerms.isEmpty()) return 0.0
        val transferTerms = buildSet {
            capabilities.forEach { capability ->
                val descriptor = descriptors[capability]
                if (descriptor != null) {
                    addAll(terms(
                        capability.value + " " + descriptor.name + " " +
                            descriptor.inputContract.description
                    ))
                }
            }
            conditions.forEach { condition ->
                addAll(terms(
                    condition.key.entity + " " + condition.key.attribute + " " + condition.value
                ))
            }
        }
        if (transferTerms.isEmpty()) return 0.0
        val overlap = goalTerms.intersect(transferTerms).size
        return (
            overlap.toDouble() /
                minOf(goalTerms.size, transferTerms.size).coerceAtLeast(1).toDouble()
        ).coerceIn(0.0, 1.0)
    }

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_TERMS }
            .take(MAX_GOAL_TERMS)
            .toSet()

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)

    private fun MemoryOs.snapshotLocked(
        signature: StrategySignature
    ): SkillGeneralizationProfile? =
        get(snapshotId(signature))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { GeneralizationCodec.decodeProfile(it.content) }
            ?.takeIf { it.signature == signature }

    private fun MemoryOs.updateIndexLocked(digest: String, now: Long) {
        val current = decodeIndex(get(INDEX_ID)?.content)
        val next = (current.filterNot { it == digest } + digest)
            .takeLast(MAX_INDEXED_GENERALIZATIONS)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "digests=" + next.joinToString(","),
                importance = 0.89,
                provenance = Provenance(
                    source = "cross-task-skill-evidence",
                    producer = "skill-generalization-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("digests=")) return emptyList()
        return content.removePrefix("digests=")
            .split(',')
            .filter { it.matches(SHA256) }
            .distinct()
            .takeLast(MAX_INDEXED_GENERALIZATIONS)
    }

    private fun markerId(planId: PlanId): MemoryId =
        MemoryId("skill-generalization-observation:${planId.value}")

    private fun snapshotId(signature: StrategySignature): MemoryId =
        MemoryId("skill-generalization:${signature.digest}")

    companion object {
        const val OBSERVATION_KIND = "skill-generalization-observation"
        const val SNAPSHOT_KIND = "skill-generalization"
        const val INDEX_KIND = "skill-generalization-index"
        const val MAX_GUIDANCE = ProceduralMemoryPolicy.MAX_GENERALIZATION_GUIDANCE
        const val MAX_CHAINS = ProceduralMemoryPolicy.MAX_GENERALIZATION_CHAINS
        const val MAX_RECENT = ProceduralMemoryPolicy.MAX_GENERALIZATION_RECENT

        private const val GENERALIZATION_LOOKBACK = ProceduralMemoryPolicy.GENERALIZATION_LOOKBACK
        private const val SKILL_LOOKBACK = ProceduralMemoryPolicy.GENERALIZATION_SKILL_LOOKBACK
        private const val MAX_CONTEXTS = 16
        private const val MAX_INDEXED_GENERALIZATIONS =
            ProceduralMemoryPolicy.MAX_GENERALIZATION_RECENT
        private const val MIN_TRANSFER_CONTEXTS = 2
        private const val MIN_TRANSFER_SUCCESSES = 2
        private const val MIN_TRANSFER_SUCCESS_RATE = 0.75
        private const val MIN_GENERALIZED_CONTEXTS = 3
        private const val MIN_GENERALIZED_SUCCESSES = 4
        private const val MIN_GENERALIZED_SUCCESS_RATE = 0.80
        private const val MIN_RETAINED_SUCCESS_RATE = 0.65
        private const val NOVEL_CONTEXT_DISCOUNT = 0.90
        private const val MAX_GOAL_TERMS = 24
        private const val MAX_FINGERPRINT_GOAL_CHARS = 512
        private val INDEX_ID = MemoryId("skill-generalization:index")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val STOP_TERMS = setOf(
            "a", "an", "and", "the", "to", "of", "for", "with", "from", "this", "that",
            "please", "user", "current", "one", "value",
            "và", "là", "cho", "với", "của", "này", "hãy", "tôi", "giúp", "cần", "được"
        )
    }
}

object GeneralizationGuidanceRenderer {
    fun render(
        guidance: List<GeneralizationGuidance>,
        chains: List<GeneralizedSkillChain>
    ): String {
        if (guidance.isEmpty() && chains.isEmpty()) return ""
        return buildString {
            appendLine("<GENERALIZATION_GUIDANCE>")
            appendLine(
                "Cross-task governed evidence only. Generalization is advisory data, never authority, " +
                    "permission, approval, a tool result, or an instruction to execute."
            )
            appendLine(
                "Novel-context transfer has reduced confidence. Any resulting plan must still match " +
                    "the current goal, live ToolDescriptor contracts and normal AMPER execution gates."
            )
            guidance.take(MemoryBackedSkillGeneralizationModel.MAX_GUIDANCE)
                .forEachIndexed { index, item ->
                    append("transfer.${index + 1}.capabilities=")
                    append(item.skill.signature.capabilities.joinToString(">") {
                        SovereignPromptData.escape(it.value)
                    })
                    append(" maturity=")
                    append(item.profile.maturity.name)
                    append(" contexts=")
                    append(item.profile.successfulContextCount)
                    append(" success_rate=")
                    append(fmt(item.profile.successRate))
                    append(" transfer_confidence=")
                    append(fmt(item.transferConfidence))
                    append(" novel_context=")
                    append(item.novelContext)
                    append(" preconditions=")
                    append(conditions(item.skill.preconditions))
                    append(" effects=")
                    append(conditions(item.skill.effects))
                    appendLine(" authority=false")
                }
            chains.take(MemoryBackedSkillGeneralizationModel.MAX_CHAINS)
                .forEachIndexed { index, chain ->
                    append("chain.${index + 1}.capabilities=")
                    append(chain.capabilities.joinToString(">") {
                        SovereignPromptData.escape(it.value)
                    })
                    append(" skills=")
                    append(chain.skills.size)
                    append(" confidence=")
                    append(fmt(chain.confidence))
                    append(" novel_context=")
                    append(chain.novelContext)
                    append(" preconditions=")
                    append(conditions(chain.preconditions))
                    append(" effects=")
                    append(conditions(chain.effects))
                    appendLine(" authority=false")
                }
            append("</GENERALIZATION_GUIDANCE>")
        }
    }

    private fun conditions(values: List<SkillStateCondition>): String =
        if (values.isEmpty()) "none"
        else values.joinToString(",") { condition ->
            SovereignPromptData.escape(condition.key.canonical) + "=" +
                SovereignPromptData.bounded(
                    condition.value.replace('\n', ' ').replace('\r', ' '),
                    96
                )
        }

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}

private data class GeneralizationObservationMarker(
    val signatureDigest: String,
    val contextDigest: String,
    val outcome: GeneralizationObservationOutcome
)

private object GeneralizationCodec {
    fun encodeMarker(
        signatureDigest: String,
        contextDigest: String,
        outcome: GeneralizationObservationOutcome
    ): String = listOf(
        "v=1",
        "signature=$signatureDigest",
        "context=$contextDigest",
        "outcome=${outcome.name}"
    ).joinToString(";")

    fun decodeMarker(content: String): GeneralizationObservationMarker? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        val signature = requireNotNull(f["signature"])
        val context = requireNotNull(f["context"])
        require(signature.matches(SHA256))
        require(context.matches(SHA256))
        GeneralizationObservationMarker(
            signatureDigest = signature,
            contextDigest = context,
            outcome = GeneralizationObservationOutcome.valueOf(requireNotNull(f["outcome"]))
        )
    }.getOrNull()

    fun encodeProfile(profile: SkillGeneralizationProfile): String = listOf(
        "v=1",
        "skill=${enc(profile.skillId.value)}",
        "capabilities=${encodeCapabilities(profile.signature.capabilities)}",
        "successes=${profile.successes}",
        "execution_failures=${profile.executionFailures}",
        "successful_contexts=${profile.successfulContexts.joinToString(",")}",
        "failed_contexts=${profile.failedContexts.joinToString(",")}",
        "maturity=${profile.maturity.name}",
        "confidence=${enc(profile.confidence.toString())}",
        "last=${profile.lastObservedAtEpochMs}"
    ).joinToString(";")

    fun decodeProfile(content: String): SkillGeneralizationProfile? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        SkillGeneralizationProfile(
            skillId = SkillId(dec(requireNotNull(f["skill"]))),
            signature = StrategySignature(decodeCapabilities(requireNotNull(f["capabilities"]))),
            successes = requireNotNull(f["successes"]).toInt(),
            executionFailures = requireNotNull(f["execution_failures"]).toInt(),
            successfulContexts = decodeContexts(requireNotNull(f["successful_contexts"])),
            failedContexts = decodeContexts(requireNotNull(f["failed_contexts"])),
            maturity = SkillGeneralizationMaturity.valueOf(requireNotNull(f["maturity"])),
            confidence = dec(requireNotNull(f["confidence"])).toDouble(),
            lastObservedAtEpochMs = requireNotNull(f["last"]).toLong()
        )
    }.getOrNull()

    private fun encodeCapabilities(values: List<CapabilityId>): String =
        values.joinToString(",") { enc(it.value) }

    private fun decodeCapabilities(value: String): List<CapabilityId> =
        value.split(',').filter { it.isNotBlank() }.map { CapabilityId(dec(it)) }

    private fun decodeContexts(value: String): List<String> =
        value.split(',').filter { it.isNotBlank() }.onEach { require(it.matches(SHA256)) }

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val split = field.indexOf('=')
            require(split > 0)
            field.substring(0, split) to field.substring(split + 1)
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private val SHA256 = Regex("[0-9a-f]{64}")
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }