package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexAdaptiveCurriculumTest {
    @Test
    fun plannerFocusesWeakCapabilityAndRaisesDifficultyConservatively() {
        val device = DeviceStatusToolContract.capability
        val web = AndroidWebSearchToolContract.capability
        val examples = buildList {
            repeat(8) { index ->
                add(actionExample("device-$index", device, index))
                add(actionExample("web-$index", web, 100 + index))
            }
            repeat(8) { index ->
                add(escalationExample("esc-$index", web, 200 + index))
            }
        }

        val curriculum = ReflexAdaptiveCurriculumPlanner.plan(
            champion = SelectiveChampion(device, web),
            historicalTraining = examples
        )

        assertTrue(device in curriculum.weakCapabilities)
        assertTrue(web !in curriculum.weakCapabilities)
        assertTrue(
            requireNotNull(curriculum.capabilityWeights[device]) >
                requireNotNull(curriculum.capabilityWeights[web])
        )
        assertTrue(curriculum.actionErrorRate > 0.0)
        assertTrue(curriculum.difficulty > 0.0)
        assertTrue(
            curriculum.learningRate in
                ReflexAdaptiveCurriculum.MIN_LEARNING_RATE..
                    ReflexAdaptiveCurriculum.MAX_LEARNING_RATE
        )
        assertEquals(64, curriculum.canonicalDigest.length)
        assertTrue(curriculum.authorityBearing.not())
    }

    @Test
    fun activeMinerUsesCurriculumToPreferWeakSkillInsideBoundedBatch() {
        val device = DeviceStatusToolContract.capability
        val web = AndroidWebSearchToolContract.capability
        val fresh = buildList {
            repeat(8) { index ->
                add(actionExample("focus-device-$index", device, 300 + index))
                add(actionExample("focus-web-$index", web, 400 + index))
            }
            repeat(8) { index ->
                add(escalationExample("focus-esc-$index", web, 500 + index))
            }
        }
        val curriculum = ReflexAdaptiveCurriculum(
            capabilityWeights = mapOf(device to 2.5, web to 1.0),
            escalationWeight = 1.0,
            weakCapabilities = setOf(device),
            actionErrorRate = 0.5,
            escalationErrorRate = 0.0,
            meanUncertainty = 0.1,
            difficulty = 0.3,
            learningRate = 0.027
        )

        val batch = requireNotNull(
            ReflexActiveLearningMiner.mine(
                fresh = fresh,
                champion = UniformCorrectChampion,
                seenActionCapabilities = setOf(device, web),
                curriculum = curriculum,
                minActionExamples = 4,
                minEscalationExamples = 4,
                maxExamples = 12
            )
        )

        // IDs are hashed-format only, so verify prioritization through the source mapping.
        val selectedSet = batch.selectedExampleIds.toSet()
        val selectedDevice = fresh.count {
            it.id in selectedSet && it.targetCapability == device
        }
        val selectedWeb = fresh.count {
            it.id in selectedSet && it.targetCapability == web
        }
        assertTrue(selectedDevice > selectedWeb)
        assertTrue(batch.selectedExampleIds.size <= 12)
    }

    private fun actionExample(
        label: String,
        capability: CapabilityId,
        index: Int
    ): ReflexExperienceTrainingExample =
        ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "action:" + reflexLinearSha256("curriculum-$label")
            ),
            featureHashes = setOf(
                "u:" + index.toString(16).padStart(16, '0').takeLast(16)
            ),
            availableCapabilities = setOf(capability),
            targetDisposition = ReflexDecisionDisposition.PROPOSE_ACTION,
            targetCapability = capability,
            targetSideEffect = ToolSideEffect.READ_ONLY,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = 0.99,
            observedAtEpochMs = 1_000L + index
        )

    private fun escalationExample(
        label: String,
        capability: CapabilityId,
        index: Int
    ): ReflexExperienceTrainingExample =
        ReflexExperienceTrainingExample(
            id = ReflexExperienceExampleId(
                "escalate:" + reflexLinearSha256("curriculum-$label")
            ),
            featureHashes = setOf(
                "u:" + index.toString(16).padStart(16, '0').takeLast(16)
            ),
            availableCapabilities = emptySet(),
            targetDisposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = 0.90,
            observedAtEpochMs = 2_000L + index
        )

    private class SelectiveChampion(
        private val weak: CapabilityId,
        private val strong: CapabilityId
    ) : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId("adaptive-curriculum-selective")
        override val weightArtifactSha256 = "1".repeat(64)

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> =
            if (weak in input.availableCapabilities) {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                        confidence = 0.95,
                        uncertainty = 0.05
                    )
                )
            } else if (strong in input.availableCapabilities) {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                        capability = strong,
                        confidence = 0.98,
                        uncertainty = 0.02
                    )
                )
            } else {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                        confidence = 0.98,
                        uncertainty = 0.02
                    )
                )
            }
    }

    private object UniformCorrectChampion : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId("adaptive-curriculum-uniform")
        override val weightArtifactSha256 = "2".repeat(64)

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> {
            val capability = input.availableCapabilities.firstOrNull()
            return if (capability == null) {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                        confidence = 0.80,
                        uncertainty = 0.20
                    )
                )
            } else {
                Result.success(
                    NativeReflexDecisionPrediction(
                        disposition = ReflexDecisionDisposition.PROPOSE_ACTION,
                        capability = capability,
                        confidence = 0.80,
                        uncertainty = 0.20
                    )
                )
            }
        }
    }
}
