package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalCounterfactualWorldModelTest {
    private val read = CapabilityId("test.read")
    private val cache = CapabilityId("test.cache")

    private class StaticStrategies(
        snapshots: Collection<StrategyEvidenceSnapshot>
    ) : StrategyLearningModel {
        private val bySignature = snapshots.associateBy { it.signature }

        override fun observe(plan: SovereignPlan): StrategyEvidenceSnapshot? =
            error("read-only test strategy model")

        override fun snapshot(signature: StrategySignature): StrategyEvidenceSnapshot? =
            bySignature[signature]

        override fun recent(limit: Int): List<StrategyEvidenceSnapshot> =
            bySignature.values.take(limit)
    }

    private fun candidate(
        index: Int,
        capability: CapabilityId
    ): DeliberationCandidate = DeliberationCandidate(
        index = index,
        steps = listOf(
            SovereignPlanStep(
                index = 1,
                requestId = ActionRequestId("candidate-$index"),
                capability = capability,
                reason = "bounded structural reason",
                input = "one",
                status = PlanStepStatus.PLANNED,
                boundToolId = ToolId("tool-${capability.value}"),
                boundSideEffect = ToolSideEffect.READ_ONLY
            )
        )
    )

    private fun snapshot(
        capability: CapabilityId,
        successes: Int,
        executionFailures: Int,
        authorityBlocked: Int = 0,
        environmentUnavailable: Int = 0,
        protocolFailures: Int = 0,
        legacyUnattributedFailures: Int = 0
    ): StrategyEvidenceSnapshot {
        val failures =
            executionFailures +
                authorityBlocked +
                environmentUnavailable +
                protocolFailures +
                legacyUnattributedFailures
        return StrategyEvidenceSnapshot(
            signature = StrategySignature(listOf(capability)),
            successes = successes,
            failures = failures,
            executionFailures = executionFailures,
            authorityBlocked = authorityBlocked,
            environmentUnavailable = environmentUnavailable,
            protocolFailures = protocolFailures,
            legacyUnattributedFailures = legacyUnattributedFailures,
            lastObservedAtEpochMs = 2_000L
        )
    }

    @Test
    fun authorityHistoryIsDiagnosticOnlyAndCannotChangeCausalViability() {
        val signature = StrategySignature(listOf(read))
        val clean = StaticStrategies(
            listOf(snapshot(read, successes = 4, executionFailures = 1))
        )
        val blocked = StaticStrategies(
            listOf(
                snapshot(
                    read,
                    successes = 4,
                    executionFailures = 1,
                    authorityBlocked = 12
                )
            )
        )

        val cleanProjection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            clean
        )
        val blockedProjection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            blocked
        )

        assertEquals(signature, cleanProjection.signature)
        assertEquals(
            cleanProjection.executionSuccessExpectation,
            blockedProjection.executionSuccessExpectation,
            0.000001
        )
        assertEquals(
            cleanProjection.epistemicConfidence,
            blockedProjection.epistemicConfidence,
            0.000001
        )
        assertEquals(
            cleanProjection.causalViability,
            blockedProjection.causalViability,
            0.000001
        )
        assertEquals(0.0, cleanProjection.authorityBlockRisk, 0.000001)
        assertTrue(blockedProjection.authorityBlockRisk > 0.0)
    }

    @Test
    fun environmentAndProtocolFragilityReduceCounterfactualViabilityWithoutBecomingSkillFailure() {
        val stable = StaticStrategies(
            listOf(snapshot(read, successes = 4, executionFailures = 1))
        )
        val fragile = StaticStrategies(
            listOf(
                snapshot(
                    read,
                    successes = 4,
                    executionFailures = 1,
                    environmentUnavailable = 4,
                    protocolFailures = 3
                )
            )
        )

        val stableProjection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            stable
        )
        val fragileProjection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            fragile
        )

        assertEquals(
            stableProjection.executionSuccessExpectation,
            fragileProjection.executionSuccessExpectation,
            0.000001
        )
        assertTrue(fragileProjection.environmentUnavailableRisk > 0.0)
        assertTrue(fragileProjection.protocolFailureRisk > 0.0)
        assertTrue(fragileProjection.causalViability < stableProjection.causalViability)
    }

    @Test
    fun unknownStrategyGetsBoundedNeutralProjectionWithoutFabricatedConfidence() {
        val projection = CausalCounterfactualWorldModel.project(
            candidate = candidate(1, read),
            strategies = StaticStrategies(emptyList())
        )

        assertFalse(projection.evidenceObserved)
        assertEquals(0.50, projection.executionSuccessExpectation, 0.000001)
        assertEquals(0.50, projection.causalViability, 0.000001)
        assertEquals(0.0, projection.epistemicConfidence, 0.000001)
        assertEquals(0.0, projection.authorityBlockRisk, 0.000001)
    }

    @Test
    fun counterfactualEvaluatorCanPreferMoreWorldRobustStrategyWithEqualExecutionEvidence() {
        val stable = snapshot(
            capability = read,
            successes = 4,
            executionFailures = 1
        )
        val fragile = snapshot(
            capability = cache,
            successes = 4,
            executionFailures = 1,
            environmentUnavailable = 8,
            protocolFailures = 4
        )
        val strategies = StaticStrategies(listOf(stable, fragile))

        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = listOf(
                candidate(1, cache),
                candidate(2, read)
            ),
            strategies = strategies,
            allowedCapabilities = setOf(read, cache)
        )

        assertEquals(listOf(read), selection.selected.candidate.signature.capabilities)
        val stableEval = selection.evaluated.single {
            it.candidate.signature.capabilities == listOf(read)
        }
        val fragileEval = selection.evaluated.single {
            it.candidate.signature.capabilities == listOf(cache)
        }
        assertEquals(
            stableEval.historicalEvidenceSupport,
            fragileEval.historicalEvidenceSupport,
            0.000001
        )
        assertTrue(stableEval.counterfactualViability > fragileEval.counterfactualViability)
        assertTrue(stableEval.totalScore > fragileEval.totalScore)
    }

    @Test
    fun legacyUnattributedHistoryRaisesUncertaintyInsteadOfInventingExecutionFailure() {
        val strategies = StaticStrategies(
            listOf(
                snapshot(
                    read,
                    successes = 3,
                    executionFailures = 0,
                    legacyUnattributedFailures = 5
                )
            )
        )

        val projection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            strategies
        )

        assertTrue(projection.legacyUncertainty > 0.0)
        assertEquals(0.8, projection.executionSuccessExpectation, 0.000001)
        assertTrue(projection.causalViability in 0.0..1.0)
    }

    @Test
    fun renderingContainsOnlyAggregateCounterfactualDiagnostics() {
        val projection = CausalCounterfactualWorldModel.project(
            candidate(1, read),
            StaticStrategies(
                listOf(
                    snapshot(
                        read,
                        successes = 3,
                        executionFailures = 1,
                        authorityBlocked = 2,
                        environmentUnavailable = 1
                    )
                )
            )
        )

        val rendered = CausalCounterfactualWorldModel.render(projection)

        assertTrue(rendered.contains("signature=test.read"))
        assertTrue(rendered.contains("expected_execution_success="))
        assertTrue(rendered.contains("authority_block_risk="))
        assertTrue(rendered.contains("causal_viability="))
        assertFalse(rendered.contains("private"))
        assertFalse(rendered.contains("input="))
        assertFalse(rendered.contains("reason="))
    }
}
