package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAssistantApprovalStoreTest {
    private fun pending(
        requestId: String = "request-1",
        conversationId: String = "conversation-1",
        boundInferenceProfile: BoundConversationInferenceProfile? = BoundConversationInferenceProfile(
            maxOutputTokens = 384,
            temperature = 0.7,
            sessionRoutingPreference = TitanSessionRoutingPreference.STANDARD,
            maxPromptChars = ConversationInferenceProfile.DEFAULT_PROMPT_CHARS,
            reflectionMode = ConversationReflectionMode.STANDARD
        )
    ): SovereignAssistantTurnResult.PendingApproval = SovereignAssistantTurnResult.PendingApproval(
        conversationId = ConversationId(conversationId),
        userPrompt = "Send this payload\nwith a second line",
        proposal = ActionProposal(
            requestId = ActionRequestId(requestId),
            capability = CapabilityId("network.send"),
            reason = "User explicitly requested delivery",
            input = "payload=a|b%20"
        ),
        toolId = ToolId("provider-network.send"),
        sideEffect = ToolSideEffect.EXTERNAL,
        firstResponse = InferenceResponse(
            modelId = ModelId("model-special"),
            backendId = "backend-special",
            text = "<AMPER_ACTION_V1>\ncapability=network.send\nreason=User explicitly requested delivery\ninput=payload=a|b%20\n</AMPER_ACTION_V1>",
            selectedCapabilities = setOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.CODE_GENERATION
            )
        ),
        firstPrompt = "grounded prompt\nwith history",
        preferredCapabilityProfiles = listOf(
            setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
        ),
        boundInferenceProfile = boundInferenceProfile
    )

    @Test
    fun memoryStoreRoundTripsCompleteApprovalSnapshot() {
        val store = MemoryBackedPendingAssistantApprovalStore(InMemoryMemoryOs())
        val original = pending()

        store.save(original)
        val restored = store.load(original.proposal.requestId)

        assertEquals(original, restored)
        assertEquals(original, store.latest(original.conversationId))
    }

    @Test
    fun phase142V4RoundTripsBoundInferenceProfileIncludingReflectionMode() {
        val profile = BoundConversationInferenceProfile(
            maxOutputTokens = 777,
            temperature = 0.25,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
            maxPromptChars = 12_000,
            reflectionMode = ConversationReflectionMode.VERIFY
        )
        val original = pending(boundInferenceProfile = profile)

        val encoded = PendingAssistantApprovalCodec.encode(original)
        val decoded = PendingAssistantApprovalCodec.decode(encoded).getOrThrow()

        assertTrue(encoded.startsWith("AMPER_ASSISTANT_PENDING_APPROVAL_V4"))
        assertEquals(profile, decoded.boundInferenceProfile)
        assertEquals(original, decoded)
    }

    @Test
    fun legacyV3CheckpointDefaultsReflectionToStandard() {
        val original = pending(
            boundInferenceProfile = BoundConversationInferenceProfile(
                maxOutputTokens = 720,
                temperature = 0.2,
                sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                maxPromptChars = 13_000,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
        val legacyV3 = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("BOUND_REFLECTION_MODE") }
            .joinToString("\n")
            .replace(
                "AMPER_ASSISTANT_PENDING_APPROVAL_V4",
                "AMPER_ASSISTANT_PENDING_APPROVAL_V3"
            )

        val decoded = PendingAssistantApprovalCodec.decode(legacyV3).getOrThrow()

        assertEquals(
            original.boundInferenceProfile?.copy(reflectionMode = ConversationReflectionMode.STANDARD),
            decoded.boundInferenceProfile
        )
    }

    @Test
    fun legacyV2CheckpointUsesOriginalProductionPromptAndReflectionDefaults() {
        val original = pending(
            boundInferenceProfile = BoundConversationInferenceProfile(
                maxOutputTokens = 700,
                temperature = 0.3,
                sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                maxPromptChars = 14_000,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
        val legacyV2 = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("BOUND_PROMPT_CHARS") || it.startsWith("BOUND_REFLECTION_MODE") }
            .joinToString("\n")
            .replace(
                "AMPER_ASSISTANT_PENDING_APPROVAL_V4",
                "AMPER_ASSISTANT_PENDING_APPROVAL_V2"
            )

        val decoded = PendingAssistantApprovalCodec.decode(legacyV2).getOrThrow()

        assertEquals(
            original.boundInferenceProfile?.copy(
                maxPromptChars = ConversationInferenceProfile.DEFAULT_PROMPT_CHARS,
                reflectionMode = ConversationReflectionMode.STANDARD
            ),
            decoded.boundInferenceProfile
        )
    }

    @Test
    fun legacyV1CheckpointStillDecodesWithoutInventingInferenceProfile() {
        val original = pending()
        val legacy = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("BOUND_") }
            .joinToString("\n")
            .replace(
                "AMPER_ASSISTANT_PENDING_APPROVAL_V4",
                "AMPER_ASSISTANT_PENDING_APPROVAL_V1"
            )

        val decoded = PendingAssistantApprovalCodec.decode(legacy).getOrThrow()

        assertNull(decoded.boundInferenceProfile)
        assertEquals(original.copy(boundInferenceProfile = null), decoded)
    }

    @Test
    fun v4WithoutBoundInferenceProfileFailsClosedOnEncodeAndDecode() {
        assertTrue(
            runCatching {
                PendingAssistantApprovalCodec.encode(pending(boundInferenceProfile = null))
            }.isFailure
        )

        val malformed = PendingAssistantApprovalCodec.encode(pending())
            .lineSequence()
            .filterNot { it.startsWith("BOUND_") }
            .joinToString("\n")

        assertTrue(PendingAssistantApprovalCodec.decode(malformed).isFailure)
    }

    @Test
    fun partialV4InferenceProfileFailsClosed() {
        val original = pending(
            boundInferenceProfile = BoundConversationInferenceProfile(
                maxOutputTokens = 500,
                temperature = 0.4,
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE,
                maxPromptChars = 8_000,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
        val malformed = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("BOUND_REFLECTION_MODE") }
            .joinToString("\n")

        assertTrue(PendingAssistantApprovalCodec.decode(malformed).isFailure)
    }

    @Test
    fun partialV3InferenceProfileStillFailsClosed() {
        val original = pending(
            boundInferenceProfile = BoundConversationInferenceProfile(
                maxOutputTokens = 500,
                temperature = 0.4,
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE,
                maxPromptChars = 8_000,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
        val malformedV3 = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot { it.startsWith("BOUND_REFLECTION_MODE") || it.startsWith("BOUND_TEMPERATURE") }
            .joinToString("\n")
            .replace(
                "AMPER_ASSISTANT_PENDING_APPROVAL_V4",
                "AMPER_ASSISTANT_PENDING_APPROVAL_V3"
            )

        assertTrue(PendingAssistantApprovalCodec.decode(malformedV3).isFailure)
    }

    @Test
    fun partialV2InferenceProfileStillFailsClosed() {
        val original = pending(
            boundInferenceProfile = BoundConversationInferenceProfile(
                maxOutputTokens = 500,
                temperature = 0.4,
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE,
                maxPromptChars = 8_000,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
        val malformedV2 = PendingAssistantApprovalCodec.encode(original)
            .lineSequence()
            .filterNot {
                it.startsWith("BOUND_PROMPT_CHARS") ||
                    it.startsWith("BOUND_REFLECTION_MODE") ||
                    it.startsWith("BOUND_TEMPERATURE")
            }
            .joinToString("\n")
            .replace(
                "AMPER_ASSISTANT_PENDING_APPROVAL_V4",
                "AMPER_ASSISTANT_PENDING_APPROVAL_V2"
            )

        assertTrue(PendingAssistantApprovalCodec.decode(malformedV2).isFailure)
    }

    @Test
    fun deleteRemovesOnlyRequestedApproval() {
        val store = MemoryBackedPendingAssistantApprovalStore(InMemoryMemoryOs())
        val first = pending(requestId = "first", conversationId = "thread-a")
        val second = pending(requestId = "second", conversationId = "thread-b")
        store.save(first)
        store.save(second)

        assertTrue(store.delete(first.proposal.requestId))
        assertNull(store.load(first.proposal.requestId))
        assertEquals(second, store.load(second.proposal.requestId))
    }

    @Test
    fun pendingApprovalSurvivesPersistentRuntimeRestart() {
        val root = Files.createTempDirectory("amper-pending-approval").toFile()
        try {
            val original = pending(
                requestId = "restart-request",
                conversationId = "restart-thread",
                boundInferenceProfile = BoundConversationInferenceProfile(
                    maxOutputTokens = 640,
                    temperature = 0.15,
                    sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                    maxPromptChars = 11_000,
                    reflectionMode = ConversationReflectionMode.VERIFY
                )
            )
            val firstRuntime = AmperRuntime.persistent(root)
            firstRuntime.pendingApprovals.save(original)

            val restartedRuntime = AmperRuntime.persistent(root)
            val restored = restartedRuntime.pendingApprovals.load(original.proposal.requestId)

            assertEquals(original, restored)
            assertEquals(original, restartedRuntime.pendingApprovals.latest(original.conversationId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedSnapshotIsIgnoredInsteadOfInventingApproval() {
        val memory = InMemoryMemoryOs()
        val requestId = ActionRequestId("malformed")
        memory.remember(
            MemoryRecord(
                id = MemoryId("assistant-pending-approval:${requestId.value}"),
                kind = MemoryBackedPendingAssistantApprovalStore.KIND,
                content = "AMPER_ASSISTANT_PENDING_APPROVAL_V1\nREQUEST\tnot-complete",
                importance = 0.95
            )
        )
        val store = MemoryBackedPendingAssistantApprovalStore(memory)

        assertNull(store.load(requestId))
        assertTrue(store.list().isEmpty())
    }
}
