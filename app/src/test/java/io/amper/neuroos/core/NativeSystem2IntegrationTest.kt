package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSystem2IntegrationTest {
    @Test
    fun capturedDeliberationUsesExactPlannerPacketWithoutRecapture() {
        val capability = CapabilityId("device.status")
        val state = packet("reason about current device status")
        var captures = 0
        val source = object : IntegratedCognitiveStateSource {
            override fun capture(
                query: String,
                allowedCapabilities: Set<CapabilityId>,
                descriptors: Collection<ToolDescriptor>
            ): IntegratedCognitiveStatePacket {
                captures += 1
                error("captured planning state must not be recaptured")
            }
        }
        val core = CanonicalNativeSystem2Core(
            stateSource = source,
            workingStateStore =
                MemoryBackedNativeSystem2WorkingStateStore(InMemoryMemoryOs()),
            clock = { 2_000L }
        )

        val result = core.deliberateCaptured(
            goal = "reason about current device status",
            state = state,
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()

        assertEquals(0, captures)
        assertEquals(state.canonicalDigest, result.workingState.cognitiveStateDigest)
        assertEquals(
            CognitiveContinuityPolicy.executionContextDigest(state),
            result.workingState.executionContextDigest
        )
        assertFalse(result.authorityBearing)
    }

    @Test
    fun system2CanAlignOnlyNearBestAlreadyValidatedPlan() {
        val firstCapability = CapabilityId("device.status")
        val secondCapability = CapabilityId("web.search")
        val first = evaluation(
            candidate = candidate(1, firstCapability),
            score = 0.80
        )
        val second = evaluation(
            candidate = candidate(2, secondCapability),
            score = 0.75
        )
        val canonical = DeliberationSelection(
            selected = first,
            evaluated = listOf(first, second)
        )
        val system2 = deliberation(
            selectedCapabilities = listOf(secondCapability),
            strategyScore = 0.90
        )

        val aligned = NativeSystem2PlanAlignmentPolicy.align(canonical, system2)

        assertEquals(second.candidate.signature, aligned.selected.candidate.signature)
        assertEquals(canonical.evaluated, aligned.evaluated)

        val weakMatch = second.copy(totalScore = 0.60)
        val protectedCanonical = DeliberationSelection(
            selected = first,
            evaluated = listOf(first, weakMatch)
        )
        val protected = NativeSystem2PlanAlignmentPolicy.align(
            protectedCanonical,
            system2
        )
        assertEquals(first.candidate.signature, protected.selected.candidate.signature)
    }

    @Test
    fun deepSystem2AddsPlanningAsPreferenceOnly() {
        val baseline = setOf(TitanCapabilities.REASONING)
        val deep = deliberation(
            selectedCapabilities = emptyList(),
            strategyScore = 0.50,
            mode = NativeSystem2Mode.DECOMPOSE_THEN_PLAN,
            reasoningDepth = 4
        )

        val profiles = NativeSystem2InferencePreferencePolicy.preferredProfiles(
            deliberation = deep,
            baselineRequired = baseline,
            existing = listOf(baseline)
        )

        assertEquals(
            baseline + TitanCapabilities.PLANNING,
            profiles.first()
        )
        assertTrue(profiles.all { it.containsAll(baseline) })
    }

    @Test
    fun renderedSystem2GuidanceIsBoundedAndNonAuthoritative() {
        val guidance = NativeSystem2GuidanceRenderer.render(
            deliberation = deliberation(
                selectedCapabilities = listOf(CapabilityId("device.status")),
                strategyScore = 0.88
            ),
            charBudget = 900
        )

        assertTrue(guidance.length <= 900)
        assertTrue(guidance.contains("authority=false"))
        assertTrue(guidance.contains("mode=REUSE_GOVERNED_STRATEGY"))
        assertTrue(guidance.contains("selected_capability_sequence=device.status"))
        assertFalse(guidance.contains("input="))
        assertFalse(guidance.contains("approval=true"))
    }

    private fun candidate(
        index: Int,
        capability: CapabilityId
    ) = DeliberationCandidate(
        index = index,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("request-$index"),
                capability = capability,
                reason = "test governed candidate",
                input = "bounded-input",
                boundToolId = ToolId("tool-" + capability.value),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        )
    )

    private fun evaluation(
        candidate: DeliberationCandidate,
        score: Double
    ) = DeliberationEvaluation(
        candidate = candidate,
        historicalEvidenceSupport = 0.70,
        evidenceObserved = true,
        stepEfficiency = 1.0,
        readOnlySafety = 1.0,
        sideEffectSteps = 0,
        counterfactualViability = 0.70,
        counterfactualConfidence = 0.70,
        totalScore = score
    )

    private fun deliberation(
        selectedCapabilities: List<CapabilityId>,
        strategyScore: Double,
        mode: NativeSystem2Mode = NativeSystem2Mode.REUSE_GOVERNED_STRATEGY,
        reasoningDepth: Int = 2
    ): NativeSystem2Deliberation {
        val readiness = IntegratedCognitiveReadiness(
            epistemicConfidence = 0.80,
            worldConfidence = 0.80,
            skillConfidence = 0.80,
            competenceConfidence = 0.80,
            uncertainty = 0.15,
            learningPressure = 0.10,
            overallReadiness = 0.82
        )
        val strategy = NativeSystem2StrategyCandidate(
            id = nativeSystem2Sha256(
                "test-strategy|" +
                    selectedCapabilities.joinToString(">") { it.value } +
                    "|" + strategyScore
            ),
            source = if (selectedCapabilities.isEmpty()) {
                NativeSystem2StrategySource.OPEN_DELIBERATION
            } else {
                NativeSystem2StrategySource.DIRECT_SKILL
            },
            capabilities = selectedCapabilities,
            confidence = strategyScore,
            goalRelevance = 0.90,
            preconditionsSatisfied = true,
            novelContext = false,
            score = strategyScore
        )
        val working = NativeSystem2WorkingState(
            goalDigest = nativeSystem2Sha256("goal"),
            cognitiveStateDigest = nativeSystem2Sha256("cognitive"),
            executionContextDigest = nativeSystem2Sha256("execution"),
            continuity = NativeSystem2Continuity.STABLE,
            readiness = readiness,
            complexity = 0.60,
            maxReasoningDepth = reasoningDepth,
            unresolvedBeliefs = 0,
            unknownWorldStates = 0,
            planningEligibleSemanticFacts = 1,
            worldPredictions = 1,
            causalHypotheses = 0,
            relevantSkills = selectedCapabilities.size,
            transferCandidates = 0,
            learningNeeds = 0,
            stalePercepts = 0,
            capturedAtEpochMs = 1_000L
        )
        return NativeSystem2Deliberation(
            workingState = working,
            agenda = listOf(
                NativeSystem2ReasoningTask(
                    index = 1,
                    kind = NativeSystem2ReasoningTaskKind.SYNTHESIZE_PLAN,
                    rationale = "synthesize bounded plan"
                ),
                NativeSystem2ReasoningTask(
                    index = 2,
                    kind = NativeSystem2ReasoningTaskKind.VERIFY_PLAN,
                    dependsOn = setOf(1),
                    rationale = "verify bounded plan"
                )
            ),
            strategyCandidates = listOf(strategy),
            selectedStrategy = strategy,
            mode = mode
        )
    }

    private fun packet(goal: String): IntegratedCognitiveStatePacket =
        IntegratedCognitiveStatePacket(
            queryDigest = nativeSystem2Sha256(
                goal.trim()
                    .lowercase(java.util.Locale.ROOT)
                    .replace(Regex("\\s+"), " ")
                    .take(1024)
            ),
            context = SovereignContextSnapshot(
                self = SelfSnapshot(
                    identity = "AMPER",
                    architecture = "APEX-MUXER SOVEREIGN NEURO-OS",
                    invariants = setOf("external-actions-require-authority")
                ),
                goals = emptyList(),
                memories = emptyList(),
                worldFacts = emptyList(),
                workspaceEvents = emptyList()
            ),
            skillGuidance = emptyList(),
            skillCompositions = emptyList(),
            transferGuidance = emptyList(),
            generalizedChains = emptyList(),
            learningNeeds = emptyList(),
            readiness = IntegratedCognitiveReadiness(
                epistemicConfidence = 0.60,
                worldConfidence = 0.60,
                skillConfidence = 0.50,
                competenceConfidence = 0.50,
                uncertainty = 0.20,
                learningPressure = 0.10,
                overallReadiness = 0.65
            ),
            capturedAtEpochMs = 1_500L
        )

    private fun descriptor(capability: CapabilityId) = ToolDescriptor(
        id = ToolId("tool-" + capability.value),
        name = capability.value,
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY
    )
}
