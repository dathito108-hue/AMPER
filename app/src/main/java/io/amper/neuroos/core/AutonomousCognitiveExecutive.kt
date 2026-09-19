package io.amper.neuroos.core

enum class CognitiveExecutiveAction {
    PLAN,
    PRACTICE,
    OBSERVE,
    EVOLVE
}

data class CognitiveExecutiveDirective(
    val cognitiveStateDigest: String,
    val executionContextDigest: String,
    val action: CognitiveExecutiveAction,
    val overallReadiness: Double,
    val uncertainty: Double,
    val learningPressure: Double,
    val triggeringCapability: CapabilityId? = null,
    val rationale: String
) {
    init {
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
        require(overallReadiness in 0.0..1.0)
        require(uncertainty in 0.0..1.0)
        require(learningPressure in 0.0..1.0)
        require(rationale.isNotBlank() && rationale.length <= 256)
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Phase276-280 deterministic executive policy.
 *
 * The executive selects one bounded cognitive operation from the already-integrated state. It does
 * not grant tool/device authority and it never treats practice or evolution evidence as execution.
 */
object AutonomousCognitiveExecutivePolicy {
    const val OBSERVATION_UNCERTAINTY = 0.55
    const val OBSERVATION_WORLD_CONFIDENCE = 0.45
    const val RELEVANT_PERCEPT_THRESHOLD = 0.60
    const val EVOLUTION_SEVERITY = 0.80
    const val EVOLUTION_EVIDENCE_CONFIDENCE = 0.45
    const val PRACTICE_PRESSURE = 0.55

    fun decide(
        state: IntegratedCognitiveStatePacket,
        evolutionAvailable: Boolean
    ): CognitiveExecutiveDirective {
        val readiness = state.readiness
        val staleRelevantPerception = state.perceptualEvidence.any {
            !it.planningEligible && it.queryRelevance >= RELEVANT_PERCEPT_THRESHOLD
        }
        val unresolvedBeliefs = state.context.epistemicBeliefs.any { !it.planningEligible }
        val observationGap =
            (staleRelevantPerception || unresolvedBeliefs) &&
                (
                    readiness.uncertainty >= OBSERVATION_UNCERTAINTY ||
                        readiness.worldConfidence < OBSERVATION_WORLD_CONFIDENCE
                )

        val evolutionNeed = state.learningNeeds
            .asSequence()
            .filter { it.kind == LearningNeedKind.EXECUTION_RELIABILITY }
            .filter { it.severity >= EVOLUTION_SEVERITY }
            .filter { it.evidenceConfidence >= EVOLUTION_EVIDENCE_CONFIDENCE }
            .sortedWith(
                compareByDescending<LearningNeed> { it.severity }
                    .thenByDescending { it.evidenceConfidence }
                    .thenBy { it.capability.value }
            )
            .firstOrNull()

        val action: CognitiveExecutiveAction
        val trigger: CapabilityId?
        val rationale: String

        when {
            observationGap -> {
                action = CognitiveExecutiveAction.OBSERVE
                trigger = null
                rationale =
                    "grounded evidence is unresolved or stale while world uncertainty remains high"
            }
            evolutionAvailable && evolutionNeed != null -> {
                action = CognitiveExecutiveAction.EVOLVE
                trigger = evolutionNeed.capability
                rationale =
                    "repeated governed execution weakness has sufficient evidence for one bounded evolution cycle"
            }
            readiness.learningPressure >= PRACTICE_PRESSURE -> {
                action = CognitiveExecutiveAction.PRACTICE
                trigger = state.learningNeeds.maxByOrNull { it.severity }?.capability
                rationale =
                    if (evolutionNeed != null && !evolutionAvailable) {
                        "execution weakness is evidenced but evolution is unavailable; use bounded zero-tool practice"
                    } else {
                        "current learning pressure warrants bounded zero-tool practice before goal planning"
                    }
            }
            else -> {
                action = CognitiveExecutiveAction.PLAN
                trigger = null
                rationale = "current grounded cognition is ready for bounded goal planning"
            }
        }

        return CognitiveExecutiveDirective(
            cognitiveStateDigest = state.canonicalDigest,
            executionContextDigest = CognitiveContinuityPolicy.executionContextDigest(state),
            action = action,
            overallReadiness = readiness.overallReadiness,
            uncertainty = readiness.uncertainty,
            learningPressure = readiness.learningPressure,
            triggeringCapability = trigger,
            rationale = rationale
        )
    }
}

sealed interface CognitiveExecutiveCycleResult {
    val directive: CognitiveExecutiveDirective

    data class Planned(
        override val directive: CognitiveExecutiveDirective,
        val plan: SovereignPlan
    ) : CognitiveExecutiveCycleResult

    data class Practiced(
        override val directive: CognitiveExecutiveDirective,
        val cycle: AutonomousLearningCycleResult
    ) : CognitiveExecutiveCycleResult

    data class ObservationRequired(
        override val directive: CognitiveExecutiveDirective,
        val staleRelevantModalities: Set<PerceptionModality>,
        val unresolvedBeliefCount: Int
    ) : CognitiveExecutiveCycleResult {
        init { require(unresolvedBeliefCount >= 0) }
    }

    data class Evolved(
        override val directive: CognitiveExecutiveDirective,
        val run: EvolutionAutonomyRunResult
    ) : CognitiveExecutiveCycleResult
}

data class CognitiveExecutiveRunResult(
    val cycles: List<CognitiveExecutiveCycleResult>
) {
    init {
        require(cycles.isNotEmpty())
        require(cycles.size <= AutonomousCognitiveExecutive.MAX_CYCLES)
    }

    val terminalAction: CognitiveExecutiveAction
        get() = cycles.last().directive.action

    val authorityBearing: Boolean
        get() = false
}

fun interface CognitiveExecutiveEvolutionPort {
    fun run(directive: CognitiveExecutiveDirective): Result<EvolutionAutonomyRunResult>
}

/**
 * Bridges executive escalation to the existing transactional autonomous-evolution orchestrator.
 * Exactly one evolution cycle is allowed per executive escalation.
 */
class BoundedAutonomousEvolutionExecutivePort(
    private val orchestrator: AutonomousEvolutionOrchestrator,
    private val suiteId: EvolutionBenchmarkSuiteId,
    private val allowedKinds: Set<AutonomousEvolutionCandidateKind> =
        AutonomousEvolutionCandidateKind.entries.toSet(),
    private val maxCandidates: Int = EvolutionCandidateGenerationRequest.MAX_CANDIDATES,
    private val clock: () -> Long = System::currentTimeMillis
) : CognitiveExecutiveEvolutionPort {
    init {
        require(allowedKinds.isNotEmpty())
        require(maxCandidates in 1..EvolutionCandidateGenerationRequest.MAX_CANDIDATES)
    }

    override fun run(
        directive: CognitiveExecutiveDirective
    ): Result<EvolutionAutonomyRunResult> = runCatching {
        val runId = EvolutionAutonomyRunId(
            "executive-" +
                directive.executionContextDigest.take(16) +
                "-" +
                clock().coerceAtLeast(0L)
        )
        orchestrator.runBounded(
            id = runId,
            suiteId = suiteId,
            allowedKinds = allowedKinds,
            maxCandidates = maxCandidates,
            maxCycles = 1
        )
    }
}

/**
 * Bounded autonomous cognitive executive.
 *
 * Phase276 derives one deterministic action from the integrated cognitive packet.
 * Phase277 prioritizes evidence acquisition when relevant percepts/beliefs are stale or unresolved.
 * Phase278 can perform bounded zero-tool practice and then recapture cognition.
 * Phase279 can escalate evidence-backed execution weakness into exactly one existing governed
 * autonomous-evolution cycle through an injected port.
 * Phase280 permits a short recapture loop only across successful practice cycles; PLAN, OBSERVE and
 * EVOLVE are terminal for the call. The executive itself has no ToolFabric/AuthorityGate handle.
 */
class AutonomousCognitiveExecutive(
    private val stateSource: IntegratedCognitiveStateSource,
    private val allowedCapabilities: Set<CapabilityId>,
    private val descriptors: () -> List<ToolDescriptor>,
    private val createPlan: (ConversationId, String) -> Result<SovereignPlan>,
    private val practiceOne: () -> Result<AutonomousLearningCycleResult>,
    private val evolution: CognitiveExecutiveEvolutionPort? = null
) {
    init {
        require(allowedCapabilities.isNotEmpty())
    }

    fun step(
        conversationId: ConversationId,
        userGoal: String
    ): Result<CognitiveExecutiveCycleResult> = runCatching {
        require(userGoal.isNotBlank())
        val liveDescriptors = descriptors()
            .filter { it.capability in allowedCapabilities }
            .distinctBy { it.id }
            .sortedBy { it.capability.value }

        val state = stateSource.capture(
            query = userGoal,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors
        )
        val directive = AutonomousCognitiveExecutivePolicy.decide(
            state = state,
            evolutionAvailable = evolution != null
        )

        when (directive.action) {
            CognitiveExecutiveAction.PLAN -> CognitiveExecutiveCycleResult.Planned(
                directive = directive,
                plan = createPlan(conversationId, userGoal).getOrThrow()
            )
            CognitiveExecutiveAction.PRACTICE -> CognitiveExecutiveCycleResult.Practiced(
                directive = directive,
                cycle = practiceOne().getOrThrow()
            )
            CognitiveExecutiveAction.OBSERVE -> CognitiveExecutiveCycleResult.ObservationRequired(
                directive = directive,
                staleRelevantModalities = state.perceptualEvidence
                    .filter {
                        !it.planningEligible &&
                            it.queryRelevance >=
                            AutonomousCognitiveExecutivePolicy.RELEVANT_PERCEPT_THRESHOLD
                    }
                    .mapTo(linkedSetOf()) { it.modality },
                unresolvedBeliefCount = state.context.epistemicBeliefs.count {
                    !it.planningEligible
                }
            )
            CognitiveExecutiveAction.EVOLVE -> CognitiveExecutiveCycleResult.Evolved(
                directive = directive,
                run = requireNotNull(evolution) {
                    "evolution directive requires an injected evolution port"
                }.run(directive).getOrThrow()
            )
        }
    }

    fun runBounded(
        conversationId: ConversationId,
        userGoal: String,
        maxCycles: Int = MAX_CYCLES
    ): Result<CognitiveExecutiveRunResult> = runCatching {
        require(maxCycles in 1..MAX_CYCLES)
        val cycles = mutableListOf<CognitiveExecutiveCycleResult>()

        repeat(maxCycles) {
            val cycle = step(conversationId, userGoal).getOrThrow()
            cycles += cycle

            val continueAfterPractice =
                cycle is CognitiveExecutiveCycleResult.Practiced &&
                    cycle.cycle is AutonomousLearningCycleResult.Assessed &&
                    cycles.size < maxCycles
            if (!continueAfterPractice) {
                return@runCatching CognitiveExecutiveRunResult(cycles)
            }
        }

        CognitiveExecutiveRunResult(cycles)
    }

    companion object {
        const val MAX_CYCLES = 4
    }
}
