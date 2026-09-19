package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexActiveLearningTest {
    @Test
    fun minerPrioritizesChampionDisagreementAndNovelCapability() {
        val examples = buildList {
            repeat(40) { index ->
                add(
                    actionExample(
                        index = index,
                        capability = AndroidWebSearchToolContract.capability,
                        confidence = 0.99
                    )
                )
            }
            repeat(40) { index ->
                add(
                    escalationExample(
                        index = index,
                        capability = AndroidWebSearchToolContract.capability,
                        confidence = 0.90
                    )
                )
            }
        }

        val batch = requireNotNull(
            ReflexActiveLearningMiner.mine(
                fresh = examples,
                champion = AlwaysEscalateChampion,
                seenActionCapabilities = emptySet(),
                minActionExamples = 16,
                minEscalationExamples = 16,
                maxExamples = 48
            )
        )

        assertEquals(48, batch.selectedExampleIds.size)
        assertEquals(40, batch.disagreementExamples)
        assertEquals(40, batch.hardExamples)
        assertTrue(AndroidWebSearchToolContract.capability in batch.novelCapabilities)
        assertTrue(batch.actionExamples > batch.escalationExamples)
        assertTrue(batch.highValueSignal)
        assertEquals(0, batch.rejectedLowQualityExamples)
    }

    @Test
    fun minerWaitsWhenGovernedLabelsDoNotMeetQualityFloor() {
        val examples = buildList {
            repeat(24) { index ->
                add(
                    actionExample(
                        index = index,
                        capability = DeviceStatusToolContract.capability,
                        confidence = 0.60
                    )
                )
            }
            repeat(24) { index ->
                add(
                    escalationExample(
                        index = index,
                        capability = DeviceStatusToolContract.capability,
                        confidence = 0.90
                    )
                )
            }
        }

        assertNull(
            ReflexActiveLearningMiner.mine(
                fresh = examples,
                champion = AlwaysEscalateChampion,
                seenActionCapabilities = setOf(DeviceStatusToolContract.capability),
                minActionExamples = 16,
                minEscalationExamples = 16,
                maxExamples = 48
            )
        )
    }

    private fun actionExample(
        index: Int,
        capability: CapabilityId,
        confidence: Double
    ): ReflexExperienceTrainingExample =
        ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "action:" + (index + 1).toString(16).padStart(64, '0')
            ),
            featureHashes = setOf(
                "u:" + (index + 1).toString(16).padStart(16, '0')
            ),
            availableCapabilities = setOf(capability),
            targetDisposition = ReflexDecisionDisposition.PROPOSE_ACTION,
            targetCapability = capability,
            targetSideEffect = ToolSideEffect.EXTERNAL,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = confidence,
            observedAtEpochMs = 1_000L + index
        )

    private fun escalationExample(
        index: Int,
        capability: CapabilityId,
        confidence: Double
    ): ReflexExperienceTrainingExample =
        ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "escalate:" + (index + 10_000).toString(16).padStart(64, '0')
            ),
            featureHashes = setOf(
                "u:" + (index + 10_000).toString(16).padStart(16, '0')
            ),
            availableCapabilities = setOf(capability),
            targetDisposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = confidence,
            observedAtEpochMs = 2_000L + index
        )

    private object AlwaysEscalateChampion : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId("active-learning-test")
        override val weightArtifactSha256 = "0".repeat(64)

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> =
            Result.success(
                NativeReflexDecisionPrediction(
                    disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                    confidence = 0.99,
                    uncertainty = 0.01
                )
            )
    }
}
