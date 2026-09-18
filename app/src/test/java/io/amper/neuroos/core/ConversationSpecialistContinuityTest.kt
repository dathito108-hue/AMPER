package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSpecialistContinuityTest {
    private data class Reply(
        val text: String,
        val modelId: ModelId,
        val selectedCapabilities: Set<CapabilityId>
    )

    private class QueueInference(replies: List<Reply>) : CognitiveInferencePort {
        private val queued = ArrayDeque(replies)
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            requests += request
            val reply = queued.removeFirst()
            InferenceResponse(
                modelId = reply.modelId,
                backendId = "phase120-backend",
                text = reply.text,
                selectedCapabilities = reply.selectedCapabilities
            )
        }
    }

    private fun coordinator(
        runtime: AmperRuntime,
        inference: QueueInference
    ): SovereignAssistantTurnCoordinator {
        val registry = InMemoryToolRegistry()
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = GatedToolFabric(DenyByDefaultAuthorityGate())
        )
        return SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = emptySet(),
            maxPromptChars = 6000
        )
    }

    private val reasoning = setOf(TitanCapabilities.REASONING)
    private val code = setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
    private val planning = setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)

    @Test
    fun actualSelectedCapabilitiesArePersistedOnCompletedAssistantTurn() {
        val runtime = AmperRuntime.reference()
        val inference = QueueInference(
            listOf(Reply("implemented", ModelId("code-model"), code))
        )
        val conversation = ConversationId("persist-selected-capabilities")

        coordinator(runtime, inference)
            .respond(conversation, "Hãy viết code Kotlin để parse JSON")
            .getOrThrow()

        val turn = runtime.conversations.recent(conversation).last { it.role == ConversationRole.ASSISTANT }
        assertEquals(code, turn.selectedCapabilities)
        assertEquals(code, runtime.conversations.latestAssistantSelectedCapabilities(conversation))
    }

    @Test
    fun shortExplicitFollowUpInheritsActualCodeSpecialistProfile() {
        val runtime = AmperRuntime.reference()
        val conversation = ConversationId("code-follow-up")
        runtime.conversations.commitAssistant(
            conversationId = conversation,
            userPrompt = "Viết parser Kotlin",
            response = "parser ready",
            backendId = "prior-backend",
            modelId = ModelId("prior-code-model"),
            selectedCapabilities = code
        )
        val inference = QueueInference(
            listOf(Reply("continued", ModelId("next-code-model"), code))
        )

        coordinator(runtime, inference)
            .respond(conversation, "Sửa tiếp đoạn đó")
            .getOrThrow()

        val request = inference.requests.single()
        assertEquals(listOf(code), request.preferredCapabilityProfiles)
        assertEquals(ModelId("prior-code-model"), request.preferredModelId)
        assertEquals(reasoning, request.requiredCapabilities)
    }

    @Test
    fun explicitTopicResetDoesNotInheritPriorCodeSpecialist() {
        val runtime = AmperRuntime.reference()
        val conversation = ConversationId("topic-reset")
        runtime.conversations.commitAssistant(
            conversationId = conversation,
            userPrompt = "Viết code",
            response = "done",
            backendId = "prior-backend",
            modelId = ModelId("prior-code-model"),
            selectedCapabilities = code
        )
        val inference = QueueInference(
            listOf(Reply("history answer", ModelId("general-model"), reasoning))
        )

        coordinator(runtime, inference)
            .respond(conversation, "Tiếp tục, nhưng chủ đề mới: lịch sử Java")
            .getOrThrow()

        assertTrue(inference.requests.single().preferredCapabilityProfiles.isEmpty())
    }

    @Test
    fun explicitCurrentCodeIntentOverridesPriorPlanningContinuity() {
        val runtime = AmperRuntime.reference()
        val conversation = ConversationId("explicit-wins")
        runtime.conversations.commitAssistant(
            conversationId = conversation,
            userPrompt = "make a plan",
            response = "plan ready",
            backendId = "planner-backend",
            modelId = ModelId("planning-model"),
            selectedCapabilities = planning
        )
        val inference = QueueInference(
            listOf(Reply("code answer", ModelId("code-model"), code))
        )

        coordinator(runtime, inference)
            .respond(conversation, "Hãy viết code Kotlin để parse JSON")
            .getOrThrow()

        assertEquals(listOf(code), inference.requests.single().preferredCapabilityProfiles)
    }

    @Test
    fun newestAssistantTurnWithoutCapabilityMetadataBlocksStaleSpecialistScanBack() {
        val runtime = AmperRuntime.reference()
        val conversation = ConversationId("no-stale-scan-back")
        runtime.conversations.commitAssistant(
            conversationId = conversation,
            userPrompt = "code task",
            response = "code response",
            backendId = "code-backend",
            modelId = ModelId("code-model"),
            selectedCapabilities = code
        )
        runtime.conversations.commitAssistant(
            conversationId = conversation,
            userPrompt = "later answer",
            response = "legacy-like response without selected capabilities",
            backendId = "general-backend",
            modelId = ModelId("general-model")
        )

        val beforeFollowUp = runtime.conversations.recent(conversation)
            .filter { it.role == ConversationRole.ASSISTANT }
        assertEquals(listOf("code response", "legacy-like response without selected capabilities"), beforeFollowUp.map { it.text })
        assertTrue(beforeFollowUp[1].createdAtEpochMs > beforeFollowUp[0].createdAtEpochMs)
        assertTrue(beforeFollowUp[1].selectedCapabilities.isEmpty())
        assertTrue(runtime.conversations.latestAssistantSelectedCapabilities(conversation).isEmpty())

        val inference = QueueInference(
            listOf(Reply("continued baseline", ModelId("general-model"), reasoning))
        )
        coordinator(runtime, inference)
            .respond(conversation, "Tiếp tục")
            .getOrThrow()

        assertTrue(inference.requests.single().preferredCapabilityProfiles.isEmpty())
    }

    @Test
    fun onlyCanonicalSpecialistsCanBeInherited() {
        val profile = ConversationSpecialistContinuityPolicy.preferredProfiles(
            userInput = "Continue from there",
            previousSelectedCapabilities = setOf(
                TitanCapabilities.REASONING,
                CapabilityId("vision")
            ),
            baseline = reasoning
        )

        assertTrue(profile.isEmpty())
    }

    @Test
    fun specialistContinuitySurvivesPersistentRuntimeRestart() {
        val root = Files.createTempDirectory("amper-phase120").toFile()
        try {
            val conversation = ConversationId("restart-specialist")
            val firstRuntime = AmperRuntime.persistent(root)
            val firstInference = QueueInference(
                listOf(Reply("first code answer", ModelId("persistent-code-model"), code))
            )
            coordinator(firstRuntime, firstInference)
                .respond(conversation, "Hãy viết code Kotlin để parse JSON")
                .getOrThrow()

            val restartedRuntime = AmperRuntime.persistent(root)
            assertEquals(code, restartedRuntime.conversations.latestAssistantSelectedCapabilities(conversation))
            val secondInference = QueueInference(
                listOf(Reply("continued code answer", ModelId("persistent-code-model"), code))
            )

            coordinator(restartedRuntime, secondInference)
                .respond(conversation, "Tiếp tục sửa đoạn đó")
                .getOrThrow()

            assertEquals(listOf(code), secondInference.requests.single().preferredCapabilityProfiles)
            assertEquals(ModelId("persistent-code-model"), secondInference.requests.single().preferredModelId)
        } finally {
            root.deleteRecursively()
        }
    }
}
