package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSystem2CoreTest {
    @Test
    fun reusesStrongGovernedSkillWithoutCreatingAuthority() {
        val capability = CapabilityId("device.status")
        val source = MutableStateSource(
            packet(
                skillGuidance = listOf(
                    SkillGuidance(
                        contract = SkillContract(
                            id = SkillId("device-status-skill"),
                            signature = StrategySignature(listOf(capability)),
                            preconditions = emptyList(),
                            effects = emptyList(),
                            successes = 8,
                            executionFailures = 1,
                            maturity = SkillMaturity.ACTIVE,
                            confidence = 0.92,
                            lastObservedAtEpochMs = 1_000L
                        ),
                        preconditionsSatisfied = true,
                        goalRelevance = 0.95
                    )
                ),
                readiness = IntegratedCognitiveReadiness(
                    epistemicConfidence = 0.9,
                    worldConfidence = 0.9,
                    skillConfidence = 0.9,
                    competenceConfidence = 0.8,
                    uncertainty = 0.1,
                    learningPressure = 0.1,
                    overallReadiness = 0.9
                )
            )
        )
        val memory = InMemoryMemoryOs()
        val core = CanonicalNativeSystem2Core(
            stateSource = source,
            workingStateStore = MemoryBackedNativeSystem2WorkingStateStore(memory),
            clock = { 2_000L }
        )

        val result = core.deliberate(
            goal = "check current device status",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()

        assertEquals(NativeSystem2Mode.REUSE_GOVERNED_STRATEGY, result.mode)
        assertEquals(NativeSystem2StrategySource.DIRECT_SKILL, result.selectedStrategy.source)
        assertEquals(listOf(capability), result.selectedStrategy.capabilities)
        assertTrue(
            result.agenda.any {
                it.kind == NativeSystem2ReasoningTaskKind.REVIEW_GOVERNED_SKILLS
            }
        )
        assertTrue(
            result.agenda.last().kind == NativeSystem2ReasoningTaskKind.VERIFY_PLAN
        )
        assertFalse(result.authorityBearing)
        assertFalse(result.selectedStrategy.authorityBearing)
        assertNotNull(
            MemoryBackedNativeSystem2WorkingStateStore(memory)
                .get(result.workingState.goalDigest)
        )
    }

    @Test
    fun unresolvedWorldStateForcesEvidenceGatheringBeforePlanning() {
        val capability = CapabilityId("device.status")
        val unknown = StructuredWorldState(
            id = MemoryId("world-unknown"),
            key = WorldStateKey("device", "battery"),
            value = null,
            confidence = 0.0,
            status = StructuredWorldStateStatus.UNKNOWN,
            evidenceIds = listOf(MemoryId("evidence-1")),
            observedAtEpochMs = 1_000L
        )
        val source = MutableStateSource(
            packet(
                worldStates = listOf(unknown),
                readiness = IntegratedCognitiveReadiness(
                    epistemicConfidence = 0.5,
                    worldConfidence = 0.2,
                    skillConfidence = 0.5,
                    competenceConfidence = 0.5,
                    uncertainty = 0.75,
                    learningPressure = 0.2,
                    overallReadiness = 0.35
                )
            )
        )
        val core = CanonicalNativeSystem2Core(
            stateSource = source,
            workingStateStore =
                MemoryBackedNativeSystem2WorkingStateStore(InMemoryMemoryOs())
        )

        val result = core.deliberate(
            goal = "decide what to do based on battery state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()

        assertEquals(NativeSystem2Mode.GATHER_EVIDENCE, result.mode)
        assertEquals(1, result.workingState.unknownWorldStates)
        assertTrue(
            result.agenda.first().kind ==
                NativeSystem2ReasoningTaskKind.REFRESH_WORLD_STATE
        )
        assertEquals(
            NativeSystem2StrategySource.OPEN_DELIBERATION,
            result.selectedStrategy.source
        )
    }

    @Test
    fun sameGoalTracksStableThenChangedExecutionContextByDigest() {
        val capability = CapabilityId("device.status")
        val source = MutableStateSource(
            packet(goalPriority = 0.80)
        )
        val memory = InMemoryMemoryOs()
        val core = CanonicalNativeSystem2Core(
            stateSource = source,
            workingStateStore = MemoryBackedNativeSystem2WorkingStateStore(memory),
            clock = { 3_000L }
        )

        val first = core.deliberate(
            goal = "maintain device readiness",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()
        val second = core.deliberate(
            goal = "maintain   device readiness",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()

        assertEquals(NativeSystem2Continuity.NEW, first.workingState.continuity)
        assertEquals(NativeSystem2Continuity.STABLE, second.workingState.continuity)
        assertEquals(first.workingState.goalDigest, second.workingState.goalDigest)

        source.value = packet(goalPriority = 0.95)
        val changed = core.deliberate(
            goal = "maintain device readiness",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(descriptor(capability))
        ).getOrThrow()

        assertEquals(
            NativeSystem2Continuity.CONTEXT_CHANGED,
            changed.workingState.continuity
        )
        assertTrue(
            changed.workingState.executionContextDigest !=
                second.workingState.executionContextDigest
        )
    }

    @Test
    fun canonicalRuntimeExposesNativeSystem2Core() {
        val runtime = AmperRuntime.reference()
        val capability = DeviceStatusToolContract.capability

        val result = runtime.nativeSystem2.deliberate(
            goal = "reason about device state",
            allowedCapabilities = setOf(capability),
            descriptors = listOf(
                ToolDescriptor(
                    id = ToolId("test-device-status"),
                    name = "device status",
                    capability = capability,
                    sideEffect = ToolSideEffect.READ_ONLY
                )
            )
        ).getOrThrow()

        assertTrue(result.agenda.isNotEmpty())
        assertTrue(result.strategyCandidates.isNotEmpty())
        assertEquals(64, result.workingState.canonicalDigest.length)
        assertFalse(result.authorityBearing)
    }

    private fun descriptor(capability: CapabilityId) = ToolDescriptor(
        id = ToolId("tool-" + capability.value),
        name = capability.value,
        capability = capability,
        sideEffect = ToolSideEffect.READ_ONLY
    )

    private fun packet(
        goalPriority: Double = 0.80,
        worldStates: List<StructuredWorldState> = emptyList(),
        skillGuidance: List<SkillGuidance> = emptyList(),
        readiness: IntegratedCognitiveReadiness = IntegratedCognitiveReadiness(
            epistemicConfidence = 0.6,
            worldConfidence = 0.6,
            skillConfidence = 0.5,
            competenceConfidence = 0.5,
            uncertainty = 0.2,
            learningPressure = 0.1,
            overallReadiness = 0.65
        )
    ): IntegratedCognitiveStatePacket =
        IntegratedCognitiveStatePacket(
            queryDigest = nativeSystem2Sha256("test-query"),
            context = SovereignContextSnapshot(
                self = SelfSnapshot(
                    identity = "AMPER",
                    architecture = "APEX-MUXER SOVEREIGN NEURO-OS",
                    invariants = setOf("external-actions-require-authority")
                ),
                goals = listOf(
                    GoalState(
                        id = GoalId("test-goal"),
                        objective = "test objective",
                        priority = goalPriority
                    )
                ),
                memories = emptyList(),
                worldFacts = emptyList(),
                workspaceEvents = emptyList(),
                structuredWorldStates = worldStates
            ),
            skillGuidance = skillGuidance,
            skillCompositions = emptyList(),
            transferGuidance = emptyList(),
            generalizedChains = emptyList(),
            learningNeeds = emptyList(),
            readiness = readiness,
            capturedAtEpochMs = 1_500L
        )

    private class MutableStateSource(
        var value: IntegratedCognitiveStatePacket
    ) : IntegratedCognitiveStateSource {
        override fun capture(
            query: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>
        ): IntegratedCognitiveStatePacket = value.copy(
            queryDigest = nativeSystem2Sha256(
                query.trim().lowercase().replace(Regex("\\s+"), " ")
            )
        )
    }
}
