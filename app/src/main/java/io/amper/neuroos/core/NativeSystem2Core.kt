package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.roundToInt

enum class NativeSystem2Continuity {
    NEW,
    STABLE,
    CONTEXT_CHANGED
}

enum class NativeSystem2Mode {
    GATHER_EVIDENCE,
    DELIBERATE,
    DECOMPOSE_THEN_PLAN,
    REUSE_GOVERNED_STRATEGY
}

enum class NativeSystem2ReasoningTaskKind {
    RESOLVE_EPISTEMIC_UNCERTAINTY,
    REFRESH_WORLD_STATE,
    EVALUATE_COUNTERFACTUALS,
    REVIEW_GOVERNED_SKILLS,
    DECOMPOSE_GOAL,
    SYNTHESIZE_PLAN,
    VERIFY_PLAN
}

data class NativeSystem2ReasoningTask(
    val index: Int,
    val kind: NativeSystem2ReasoningTaskKind,
    val dependsOn: Set<Int> = emptySet(),
    val rationale: String
) {
    init {
        require(index > 0)
        require(dependsOn.all { it in 1 until index })
        require(rationale.isNotBlank() && rationale.length <= 192)
    }

    val authorityBearing: Boolean
        get() = false
}

enum class NativeSystem2StrategySource {
    DIRECT_SKILL,
    SKILL_COMPOSITION,
    TRANSFERRED_SKILL,
    GENERALIZED_CHAIN,
    OPEN_DELIBERATION
}

data class NativeSystem2StrategyCandidate(
    val id: String,
    val source: NativeSystem2StrategySource,
    val capabilities: List<CapabilityId>,
    val confidence: Double,
    val goalRelevance: Double,
    val preconditionsSatisfied: Boolean,
    val novelContext: Boolean,
    val score: Double
) {
    init {
        require(id.matches(SHA256))
        require(capabilities.distinct().size == capabilities.size)
        require(capabilities.size <= TitanPlanProtocol.MAX_STEPS)
        require(source == NativeSystem2StrategySource.OPEN_DELIBERATION || capabilities.isNotEmpty())
        require(source != NativeSystem2StrategySource.OPEN_DELIBERATION || capabilities.isEmpty())
        require(confidence in 0.0..1.0)
        require(goalRelevance in 0.0..1.0)
        require(score in 0.0..1.0)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeSystem2WorkingState(
    val goalDigest: String,
    val cognitiveStateDigest: String,
    val executionContextDigest: String,
    val continuity: NativeSystem2Continuity,
    val readiness: IntegratedCognitiveReadiness,
    val complexity: Double,
    val maxReasoningDepth: Int,
    val unresolvedBeliefs: Int,
    val unknownWorldStates: Int,
    val planningEligibleSemanticFacts: Int,
    val worldPredictions: Int,
    val causalHypotheses: Int,
    val relevantSkills: Int,
    val transferCandidates: Int,
    val learningNeeds: Int,
    val stalePercepts: Int,
    val capturedAtEpochMs: Long
) {
    init {
        require(goalDigest.matches(SHA256))
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
        require(complexity in 0.0..1.0)
        require(maxReasoningDepth in 1..MAX_REASONING_DEPTH)
        listOf(
            unresolvedBeliefs,
            unknownWorldStates,
            planningEligibleSemanticFacts,
            worldPredictions,
            causalHypotheses,
            relevantSkills,
            transferCandidates,
            learningNeeds,
            stalePercepts
        ).forEach { require(it >= 0) }
        require(capturedAtEpochMs >= 0L)
        require(!readiness.authorityBearing)
    }

    val authorityBearing: Boolean
        get() = false

    val canonicalDigest: String
        get() = nativeSystem2Sha256(
            listOf(
                "AMPER_NATIVE_SYSTEM2_WORKING_V1",
                goalDigest,
                cognitiveStateDigest,
                executionContextDigest,
                continuity.name,
                six(complexity),
                maxReasoningDepth.toString(),
                unresolvedBeliefs.toString(),
                unknownWorldStates.toString(),
                planningEligibleSemanticFacts.toString(),
                worldPredictions.toString(),
                causalHypotheses.toString(),
                relevantSkills.toString(),
                transferCandidates.toString(),
                learningNeeds.toString(),
                stalePercepts.toString()
            ).joinToString("|")
        )

    companion object {
        const val MAX_REASONING_DEPTH = 4
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class NativeSystem2Deliberation(
    val workingState: NativeSystem2WorkingState,
    val agenda: List<NativeSystem2ReasoningTask>,
    val strategyCandidates: List<NativeSystem2StrategyCandidate>,
    val selectedStrategy: NativeSystem2StrategyCandidate,
    val mode: NativeSystem2Mode
) {
    init {
        require(agenda.isNotEmpty())
        require(agenda.size <= MAX_AGENDA_TASKS)
        require(strategyCandidates.isNotEmpty())
        require(strategyCandidates.size <= MAX_STRATEGY_CANDIDATES)
        require(selectedStrategy in strategyCandidates)
        require(agenda.none { it.authorityBearing })
        require(strategyCandidates.none { it.authorityBearing })
        require(!workingState.authorityBearing)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val MAX_AGENDA_TASKS = 7
        const val MAX_STRATEGY_CANDIDATES = 8
    }
}

data class NativeSystem2WorkingSnapshot(
    val goalDigest: String,
    val cognitiveStateDigest: String,
    val executionContextDigest: String,
    val workingStateDigest: String,
    val mode: NativeSystem2Mode,
    val selectedStrategyId: String,
    val updatedAtEpochMs: Long
) {
    init {
        require(goalDigest.matches(SHA256))
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
        require(workingStateDigest.matches(SHA256))
        require(selectedStrategyId.matches(SHA256))
        require(updatedAtEpochMs >= 0L)
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface NativeSystem2WorkingStateStore {
    fun get(goalDigest: String): NativeSystem2WorkingSnapshot?
    fun put(snapshot: NativeSystem2WorkingSnapshot)
}

class MemoryBackedNativeSystem2WorkingStateStore(
    private val memory: MemoryOs
) : NativeSystem2WorkingStateStore {
    override fun get(goalDigest: String): NativeSystem2WorkingSnapshot? {
        require(goalDigest.matches(SHA256))
        return memory.get(memoryId(goalDigest))
            ?.takeIf { it.kind == KIND }
            ?.let { decode(it.content) }
    }

    override fun put(snapshot: NativeSystem2WorkingSnapshot) {
        memory.remember(
            MemoryRecord(
                id = memoryId(snapshot.goalDigest),
                kind = KIND,
                content = encode(snapshot),
                importance = 0.76,
                provenance = Provenance(
                    source = "native-system2-working-state",
                    producer = "amper-native-system2",
                    confidence = 1.0
                ),
                createdAtEpochMs = snapshot.updatedAtEpochMs
            )
        )
    }

    private fun encode(snapshot: NativeSystem2WorkingSnapshot): String =
        listOf(
            VERSION,
            snapshot.goalDigest,
            snapshot.cognitiveStateDigest,
            snapshot.executionContextDigest,
            snapshot.workingStateDigest,
            snapshot.mode.name,
            snapshot.selectedStrategyId,
            snapshot.updatedAtEpochMs
        ).joinToString("|")

    private fun decode(content: String): NativeSystem2WorkingSnapshot {
        val fields = content.split('|')
        require(fields.size == 8 && fields[0] == VERSION) {
            "unsupported native System-2 working-state snapshot"
        }
        return NativeSystem2WorkingSnapshot(
            goalDigest = fields[1],
            cognitiveStateDigest = fields[2],
            executionContextDigest = fields[3],
            workingStateDigest = fields[4],
            mode = NativeSystem2Mode.valueOf(fields[5]),
            selectedStrategyId = fields[6],
            updatedAtEpochMs = fields[7].toLong()
        )
    }

    private fun memoryId(goalDigest: String) =
        MemoryId("native-system2:working:$goalDigest")

    companion object {
        const val KIND = "native-system2-working-v1"
        private const val VERSION = "NS2W1"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface NativeSystem2Core {
    fun deliberate(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): Result<NativeSystem2Deliberation>
}

/**
 * Phase536-540 AMPER-native System-2 core.
 *
 * This is a native deliberation layer over AMPER's already-canonical integrated cognition. It does
 * not create a second world model, skill store, planner or authority path. Instead it binds one
 * immutable cognitive packet, derives a bounded reasoning agenda, evaluates evidence-backed strategy
 * candidates, tracks context continuity by digest and returns data for the later planning/inference
 * integration phase.
 *
 * Capability sequences are advisory strategy identities only. They contain no raw tool arguments,
 * provider handles, approval bits or execution authority.
 */
class CanonicalNativeSystem2Core(
    private val stateSource: IntegratedCognitiveStateSource,
    private val workingStateStore: NativeSystem2WorkingStateStore,
    private val clock: () -> Long = System::currentTimeMillis
) : NativeSystem2Core {
    override fun deliberate(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): Result<NativeSystem2Deliberation> = runCatching {
        require(goal.isNotBlank())
        require(allowedCapabilities.isNotEmpty())

        val liveDescriptors = descriptors
            .filter { it.capability in allowedCapabilities }
            .distinctBy { it.id }
            .sortedBy { it.capability.value }
        val state = stateSource.capture(
            query = goal,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors
        )
        val goalDigest = nativeSystem2Sha256(normalizeSystem2Goal(goal))
        val executionDigest = CognitiveContinuityPolicy.executionContextDigest(state)
        val previous = workingStateStore.get(goalDigest)
        val continuity = when {
            previous == null -> NativeSystem2Continuity.NEW
            previous.executionContextDigest == executionDigest -> NativeSystem2Continuity.STABLE
            else -> NativeSystem2Continuity.CONTEXT_CHANGED
        }

        val candidates = strategyCandidates(
            state = state,
            liveCapabilities = liveDescriptors.map { it.capability }.toSet()
        )
        val selected = candidates.maxWithOrNull(
            compareBy<NativeSystem2StrategyCandidate> { it.score }
                .thenBy { it.confidence }
                .thenByDescending { it.id }
        ) ?: error("native System-2 produced no strategy candidates")

        val unresolved = state.context.epistemicBeliefs.count { !it.planningEligible }
        val unknownWorld = state.context.structuredWorldStates.count {
            it.status != StructuredWorldStateStatus.KNOWN
        }
        val stalePercepts = state.perceptualEvidence.count {
            !it.planningEligible
        }
        val complexity = complexity(
            state = state,
            candidateCount = candidates.count {
                it.source != NativeSystem2StrategySource.OPEN_DELIBERATION
            },
            unresolvedBeliefs = unresolved,
            unknownWorldStates = unknownWorld,
            stalePercepts = stalePercepts
        )
        val reasoningDepth = (1 + (complexity * 3.0).roundToInt())
            .coerceIn(1, NativeSystem2WorkingState.MAX_REASONING_DEPTH)

        val working = NativeSystem2WorkingState(
            goalDigest = goalDigest,
            cognitiveStateDigest = state.canonicalDigest,
            executionContextDigest = executionDigest,
            continuity = continuity,
            readiness = state.readiness,
            complexity = complexity,
            maxReasoningDepth = reasoningDepth,
            unresolvedBeliefs = unresolved,
            unknownWorldStates = unknownWorld,
            planningEligibleSemanticFacts = state.context.semanticKnowledge.size,
            worldPredictions = state.context.worldPredictions.size,
            causalHypotheses = state.context.causalHypotheses.size,
            relevantSkills = state.skillGuidance.size + state.skillCompositions.size,
            transferCandidates = state.transferGuidance.size + state.generalizedChains.size,
            learningNeeds = state.learningNeeds.size,
            stalePercepts = stalePercepts,
            capturedAtEpochMs = state.capturedAtEpochMs
        )
        val mode = mode(
            working = working,
            selected = selected
        )
        val agenda = agenda(
            state = state,
            working = working,
            selected = selected,
            mode = mode
        )

        workingStateStore.put(
            NativeSystem2WorkingSnapshot(
                goalDigest = goalDigest,
                cognitiveStateDigest = state.canonicalDigest,
                executionContextDigest = executionDigest,
                workingStateDigest = working.canonicalDigest,
                mode = mode,
                selectedStrategyId = selected.id,
                updatedAtEpochMs = clock().coerceAtLeast(0L)
            )
        )

        NativeSystem2Deliberation(
            workingState = working,
            agenda = agenda,
            strategyCandidates = candidates,
            selectedStrategy = selected,
            mode = mode
        )
    }

    private fun strategyCandidates(
        state: IntegratedCognitiveStatePacket,
        liveCapabilities: Set<CapabilityId>
    ): List<NativeSystem2StrategyCandidate> {
        val candidates = mutableListOf<NativeSystem2StrategyCandidate>()

        state.skillGuidance.forEach { guidance ->
            val caps = guidance.contract.signature.capabilities
            if (caps.all { it in liveCapabilities }) {
                candidates += candidate(
                    source = NativeSystem2StrategySource.DIRECT_SKILL,
                    capabilities = caps,
                    confidence = guidance.contract.confidence,
                    relevance = guidance.goalRelevance,
                    preconditionsSatisfied = guidance.preconditionsSatisfied,
                    novelContext = false,
                    readiness = state.readiness
                )
            }
        }
        state.skillCompositions.forEach { composition ->
            if (composition.capabilities.all { it in liveCapabilities }) {
                candidates += candidate(
                    source = NativeSystem2StrategySource.SKILL_COMPOSITION,
                    capabilities = composition.capabilities,
                    confidence = composition.confidence,
                    relevance = 0.82,
                    preconditionsSatisfied = true,
                    novelContext = false,
                    readiness = state.readiness
                )
            }
        }
        state.transferGuidance.forEach { guidance ->
            val caps = guidance.skill.signature.capabilities
            if (caps.all { it in liveCapabilities }) {
                candidates += candidate(
                    source = NativeSystem2StrategySource.TRANSFERRED_SKILL,
                    capabilities = caps,
                    confidence = guidance.transferConfidence,
                    relevance = guidance.goalRelevance,
                    preconditionsSatisfied = true,
                    novelContext = guidance.novelContext,
                    readiness = state.readiness
                )
            }
        }
        state.generalizedChains.forEach { chain ->
            if (chain.capabilities.all { it in liveCapabilities }) {
                candidates += candidate(
                    source = NativeSystem2StrategySource.GENERALIZED_CHAIN,
                    capabilities = chain.capabilities,
                    confidence = chain.confidence,
                    relevance = 0.78,
                    preconditionsSatisfied = true,
                    novelContext = chain.novelContext,
                    readiness = state.readiness
                )
            }
        }

        candidates += candidate(
            source = NativeSystem2StrategySource.OPEN_DELIBERATION,
            capabilities = emptyList(),
            confidence = state.readiness.overallReadiness,
            relevance = 0.50,
            preconditionsSatisfied = true,
            novelContext = false,
            readiness = state.readiness
        )

        return candidates
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<NativeSystem2StrategyCandidate> { it.score }
                    .thenByDescending { it.confidence }
                    .thenBy { it.id }
            )
            .take(NativeSystem2Deliberation.MAX_STRATEGY_CANDIDATES)
    }

    private fun candidate(
        source: NativeSystem2StrategySource,
        capabilities: List<CapabilityId>,
        confidence: Double,
        relevance: Double,
        preconditionsSatisfied: Boolean,
        novelContext: Boolean,
        readiness: IntegratedCognitiveReadiness
    ): NativeSystem2StrategyCandidate {
        val preconditionFactor = if (preconditionsSatisfied) 1.0 else 0.45
        val noveltyFactor = if (novelContext) 0.88 else 1.0
        val sourceBias = when (source) {
            NativeSystem2StrategySource.DIRECT_SKILL -> 0.08
            NativeSystem2StrategySource.SKILL_COMPOSITION -> 0.06
            NativeSystem2StrategySource.TRANSFERRED_SKILL -> 0.02
            NativeSystem2StrategySource.GENERALIZED_CHAIN -> 0.04
            NativeSystem2StrategySource.OPEN_DELIBERATION -> 0.0
        }
        val raw = (
            0.38 * confidence +
                0.24 * relevance +
                0.14 * preconditionFactor +
                0.14 * readiness.worldConfidence +
                0.10 * readiness.epistemicConfidence +
                sourceBias -
                0.16 * readiness.uncertainty
            ) * noveltyFactor
        val score = raw.coerceIn(0.0, 1.0)
        val id = nativeSystem2Sha256(
            listOf(
                "AMPER_NATIVE_SYSTEM2_STRATEGY_V1",
                source.name,
                capabilities.joinToString(">") { it.value },
                six(confidence),
                six(relevance),
                preconditionsSatisfied.toString(),
                novelContext.toString()
            ).joinToString("|")
        )
        return NativeSystem2StrategyCandidate(
            id = id,
            source = source,
            capabilities = capabilities,
            confidence = confidence.coerceIn(0.0, 1.0),
            goalRelevance = relevance.coerceIn(0.0, 1.0),
            preconditionsSatisfied = preconditionsSatisfied,
            novelContext = novelContext,
            score = score
        )
    }

    private fun complexity(
        state: IntegratedCognitiveStatePacket,
        candidateCount: Int,
        unresolvedBeliefs: Int,
        unknownWorldStates: Int,
        stalePercepts: Int
    ): Double {
        val beliefTotal = state.context.epistemicBeliefs.size
        val worldTotal = state.context.structuredWorldStates.size
        val perceptTotal = state.perceptualEvidence.size
        val beliefRatio = ratio(unresolvedBeliefs, beliefTotal)
        val worldRatio = ratio(unknownWorldStates, worldTotal)
        val perceptRatio = ratio(stalePercepts, perceptTotal)
        val strategyAmbiguity = when {
            candidateCount <= 1 -> 0.0
            candidateCount == 2 -> 0.35
            else -> 0.65
        }
        return (
            0.34 * state.readiness.uncertainty +
                0.18 * beliefRatio +
                0.16 * worldRatio +
                0.08 * perceptRatio +
                0.12 * strategyAmbiguity +
                0.12 * state.readiness.learningPressure
            ).coerceIn(0.0, 1.0)
    }

    private fun mode(
        working: NativeSystem2WorkingState,
        selected: NativeSystem2StrategyCandidate
    ): NativeSystem2Mode = when {
        (
            working.unresolvedBeliefs > 0 ||
                working.unknownWorldStates > 0 ||
                working.stalePercepts > 0
            ) &&
            working.readiness.uncertainty >= GATHER_EVIDENCE_UNCERTAINTY ->
            NativeSystem2Mode.GATHER_EVIDENCE

        working.complexity >= DECOMPOSE_COMPLEXITY ->
            NativeSystem2Mode.DECOMPOSE_THEN_PLAN

        selected.source != NativeSystem2StrategySource.OPEN_DELIBERATION &&
            selected.score >= REUSE_STRATEGY_SCORE ->
            NativeSystem2Mode.REUSE_GOVERNED_STRATEGY

        else -> NativeSystem2Mode.DELIBERATE
    }

    private fun agenda(
        state: IntegratedCognitiveStatePacket,
        working: NativeSystem2WorkingState,
        selected: NativeSystem2StrategyCandidate,
        mode: NativeSystem2Mode
    ): List<NativeSystem2ReasoningTask> {
        val kinds = mutableListOf<Pair<NativeSystem2ReasoningTaskKind, String>>()
        if (working.unresolvedBeliefs > 0) {
            kinds += NativeSystem2ReasoningTaskKind.RESOLVE_EPISTEMIC_UNCERTAINTY to
                "resolve planning-ineligible beliefs before relying on them"
        }
        if (working.unknownWorldStates > 0 || working.stalePercepts > 0) {
            kinds += NativeSystem2ReasoningTaskKind.REFRESH_WORLD_STATE to
                "refresh unknown or stale grounded world evidence"
        }
        if (state.context.worldPredictions.isNotEmpty() || state.context.causalHypotheses.isNotEmpty()) {
            kinds += NativeSystem2ReasoningTaskKind.EVALUATE_COUNTERFACTUALS to
                "compare predictions and causal hypotheses against the current goal"
        }
        if (selected.source != NativeSystem2StrategySource.OPEN_DELIBERATION) {
            kinds += NativeSystem2ReasoningTaskKind.REVIEW_GOVERNED_SKILLS to
                "review evidence-backed reusable strategy without treating it as authority"
        }
        if (mode == NativeSystem2Mode.DECOMPOSE_THEN_PLAN) {
            kinds += NativeSystem2ReasoningTaskKind.DECOMPOSE_GOAL to
                "split the goal into bounded independently verifiable subgoals"
        }
        kinds += NativeSystem2ReasoningTaskKind.SYNTHESIZE_PLAN to
            "synthesize one bounded plan against live capability contracts"
        kinds += NativeSystem2ReasoningTaskKind.VERIFY_PLAN to
            "verify evidence continuity, feasibility and authority separation"

        return kinds
            .take(NativeSystem2Deliberation.MAX_AGENDA_TASKS)
            .mapIndexed { zeroIndex, pair ->
                val index = zeroIndex + 1
                NativeSystem2ReasoningTask(
                    index = index,
                    kind = pair.first,
                    dependsOn = if (index == 1) emptySet() else setOf(index - 1),
                    rationale = pair.second
                )
            }
    }

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator <= 0) 0.0
        else numerator.toDouble().div(denominator.toDouble()).coerceIn(0.0, 1.0)

    companion object {
        const val GATHER_EVIDENCE_UNCERTAINTY = 0.45
        const val DECOMPOSE_COMPLEXITY = 0.62
        const val REUSE_STRATEGY_SCORE = 0.60
    }
}

internal fun nativeSystem2Sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun normalizeSystem2Goal(value: String): String =
    value.trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .take(2048)

private fun six(value: Double): String =
    java.lang.String.format(java.util.Locale.ROOT, "%.6f", value)
