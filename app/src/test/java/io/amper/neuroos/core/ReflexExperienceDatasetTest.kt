package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexExperienceDatasetTest {
    private val shareDescriptor = ToolDescriptor(
        id = AndroidShareTextToolContract.toolId,
        name = "share",
        capability = AndroidShareTextToolContract.capability,
        sideEffect = ToolSideEffect.EXTERNAL,
        inputContract = ToolInputContract(
            description = "share",
            maxLength = AndroidShareTextToolContract.MAX_TEXT_CHARS
        )
    )

    @Test
    fun executedActionStoresOnlyHashedInputFeaturesAndTypedTarget() {
        val fixture = fixture()
        val secret = "PRIVATE-REFLEX-PAYLOAD-927"
        val proposal = ActionProposal(
            requestId = ActionRequestId("reflex-experience-action"),
            capability = AndroidShareTextToolContract.capability,
            reason = "share requested text",
            input = secret
        )
        val example = fixture.store.observeExecuted(
            userInput = "Chia sẻ: $secret",
            descriptors = listOf(shareDescriptor),
            action = ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = shareDescriptor.id,
                sideEffect = ToolSideEffect.EXTERNAL,
                output = "share_ui_opened"
            ),
            source = ReflexExperienceSource.REFLEX_CORTEX,
            labelConfidence = 0.995,
            observedAtEpochMs = 100L
        )

        assertEquals(ReflexDecisionDisposition.PROPOSE_ACTION, example.targetDisposition)
        assertEquals(AndroidShareTextToolContract.capability, example.targetCapability)
        assertEquals(ToolSideEffect.EXTERNAL, example.targetSideEffect)
        assertTrue(example.featureHashes.all { it.matches(Regex("[ub]:[0-9a-f]{16}")) })
        assertFalse(example.featureHashes.any { it.contains(secret, ignoreCase = true) })

        val shard = fixture.store.materializeShard(
            NativeDatasetShardId("reflex-private-shard"),
            minExamples = 1,
            limit = 1
        )
        assertFalse(shard.payload.contains(secret))
        assertFalse(shard.payload.contains(proposal.input))
        assertFalse(shard.payload.contains(proposal.reason))
        assertEquals(setOf(TitanCapabilities.REFLEX_DECISION), shard.manifest.targetCapabilities)
        assertEquals(NativeDatasetRights.GENERATED_INTERNAL, shard.manifest.rights)
        assertNotNull(fixture.foundation.getDatasetShard(shard.manifest.id))
    }

    @Test
    fun system2NoActionBecomesConservativeEscalationTeacherExample() {
        val fixture = fixture()
        val example = fixture.store.observeEscalation(
            conversationId = ConversationId("reflex-escalation-thread"),
            userInput = "Explain quantum entanglement in simple terms",
            descriptors = listOf(shareDescriptor),
            response = InferenceResponse(
                modelId = ModelId("teacher"),
                backendId = "teacher-backend",
                text = "Quantum entanglement is a correlation between quantum systems."
            ),
            observedAtEpochMs = 200L
        )

        assertEquals(ReflexDecisionDisposition.ESCALATE_SYSTEM2, example.targetDisposition)
        assertEquals(null, example.targetCapability)
        assertEquals(null, example.targetSideEffect)
        assertEquals(ReflexExperienceSource.SYSTEM2_TEACHER, example.source)
        assertTrue(example.labelConfidence < 1.0)
        assertFalse(example.authorityBearing)
    }

    @Test
    fun actionObservationIsIdempotentByGovernedRequestIdentity() {
        val fixture = fixture()
        val proposal = ActionProposal(
            requestId = ActionRequestId("reflex-idempotent-action"),
            capability = AndroidShareTextToolContract.capability,
            reason = "share",
            input = "hello"
        )
        val action = ActionOutcome(
            status = ActionStatus.EXECUTED,
            proposal = proposal,
            toolId = shareDescriptor.id,
            sideEffect = ToolSideEffect.EXTERNAL,
            output = "ok"
        )
        val first = fixture.store.observeExecuted(
            userInput = "share: hello",
            descriptors = listOf(shareDescriptor),
            action = action,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = 0.98,
            observedAtEpochMs = 300L
        )
        val replay = fixture.store.observeExecuted(
            userInput = "share: hello",
            descriptors = listOf(shareDescriptor),
            action = action,
            source = ReflexExperienceSource.SYSTEM2_TEACHER,
            labelConfidence = 0.98,
            observedAtEpochMs = 350L
        )

        assertEquals(first.id, replay.id)
        assertEquals(1, fixture.store.recentExamples(8).size)
    }

    private fun fixture(): Fixture {
        val memory = InMemoryMemoryOs()
        val foundation = MemoryBackedNativeModelFoundation(memory)
        return Fixture(
            foundation = foundation,
            store = MemoryBackedReflexExperienceDatasetStore(memory, foundation)
        )
    }

    private data class Fixture(
        val foundation: NativeModelFoundation,
        val store: ReflexExperienceDatasetStore
    )
}
