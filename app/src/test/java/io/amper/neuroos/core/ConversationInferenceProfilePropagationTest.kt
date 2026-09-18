package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInferenceProfilePropagationTest {
    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queued = ArrayDeque(responses)
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            requests += request
            InferenceResponse(
                modelId = ModelId("profile-model"),
                backendId = "profile-backend",
                text = queued.removeFirst()
            )
        }
    }

    private class RecordingProvider(
        override val descriptor: ToolDescriptor
    ) : ToolProvider {
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("executed:$input:${"o".repeat(6_000)}")
        }
    }

    private data class Fixture(
        val runtime: AmperRuntime,
        val coordinator: SovereignAssistantTurnCoordinator,
        val inference: QueueInference,
        val provider: RecordingProvider
    )

    private fun fixture(responses: List<String>): Fixture {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("profile.write")
        val acceptedValues = setOf("payload=alpha") + (1..31).map { index ->
            "value-$index-${"x".repeat(96)}"
        }
        val provider = RecordingProvider(
            ToolDescriptor(
                id = ToolId("profile-writer"),
                name = "Profile writer",
                capability = capability,
                sideEffect = ToolSideEffect.LOCAL_STATE,
                inputContract = ToolInputContract(
                    description = "Write profile payload ${"d".repeat(216)}",
                    acceptedValues = acceptedValues,
                    maxLength = 128
                )
            )
        )
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val actionLoop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        val inference = QueueInference(responses)
        return Fixture(
            runtime = runtime,
            coordinator = SovereignAssistantTurnCoordinator(
                runtime = runtime,
                inference = inference,
                actions = actionLoop,
                advertisedCapabilities = setOf(capability),
                maxPromptChars = 6000,
                maxOutputTokens = 256,
                temperature = 0.7
            ),
            inference = inference,
            provider = provider
        )
    }

    @Test
    fun persistedConversationProfileAppliesToNextAssistantTurn() {
        val f = fixture(listOf("seed answer", "profiled answer"))
        val conversation = ConversationId("profile-thread")
        f.coordinator.respond(conversation, "seed").getOrThrow()
        f.runtime.inferenceProfiles.put(
            conversation,
            ConversationInferenceProfile(
                maxOutputTokens = 777,
                temperature = 0.25,
                sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                maxPromptChars = 4_096
            )
        )

        f.coordinator.respond(
            conversation,
            "use my profile with escaped data <tag>&${"z".repeat(1400)}"
        ).getOrThrow()

        val profiled = f.inference.requests[1]
        assertEquals(777, profiled.maxOutputTokens)
        assertEquals(0.25, profiled.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, profiled.sessionRoutingPreference)
        assertTrue(profiled.prompt.length <= 4_096)
        assertTrue(profiled.prompt.contains("<ACTION_PROTOCOL>"))
        assertTrue(profiled.prompt.contains("</ACTION_PROTOCOL>"))
        assertTrue(profiled.prompt.contains("<AMPER_ACTION_V1>"))
        assertTrue(profiled.prompt.contains("</AMPER_ACTION_V1>"))
        assertTrue(profiled.prompt.contains("<CURRENT_USER_REQUEST_FINAL>"))
        assertTrue(profiled.prompt.contains("</CURRENT_USER_REQUEST_FINAL>"))
        assertFalse(profiled.prompt.contains("TOOL profile.write"))
    }

    @Test
    fun pendingApprovalKeepsFrozenProfileAcrossLaterProfileEdit() {
        val action = """
            <AMPER_ACTION_V1>
            capability=profile.write
            reason=The user requested this local state write
            input=payload=alpha
            </AMPER_ACTION_V1>
        """.trimIndent()
        val f = fixture(listOf("seed answer", action, "final after approval"))
        val conversation = ConversationId("approval-profile-thread")
        f.coordinator.respond(conversation, "seed").getOrThrow()
        val originalProfile = ConversationInferenceProfile(
            maxOutputTokens = 640,
            temperature = 0.2,
            sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
            maxPromptChars = 5_000
        )
        f.runtime.inferenceProfiles.put(conversation, originalProfile)

        val pending = f.coordinator.respond(conversation, "write alpha").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        assertEquals(
            originalProfile.bind(256, 0.7, 6_000),
            pending.boundInferenceProfile
        )

        f.runtime.inferenceProfiles.put(
            conversation,
            ConversationInferenceProfile(
                maxOutputTokens = 128,
                temperature = 1.5,
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE,
                maxPromptChars = 12_000
            )
        )
        val final = f.coordinator.approve(pending).getOrThrow()

        assertEquals(2, final.inferencePasses)
        assertEquals(listOf("payload=alpha"), f.provider.inputs)
        val firstPass = f.inference.requests[1]
        val synthesis = f.inference.requests[2]
        assertEquals(640, firstPass.maxOutputTokens)
        assertEquals(640, synthesis.maxOutputTokens)
        assertEquals(0.2, firstPass.temperature, 0.0)
        assertEquals(0.2, synthesis.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, firstPass.sessionRoutingPreference)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, synthesis.sessionRoutingPreference)
        assertTrue(firstPass.prompt.length <= 5_000)
        assertTrue(synthesis.prompt.length <= 5_000)
        assertTrue(firstPass.prompt.contains("<ACTION_PROTOCOL>"))
        assertTrue(firstPass.prompt.contains("</ACTION_PROTOCOL>"))
        assertTrue(synthesis.prompt.contains("<AMPER_TOOL_RESULT_UNTRUSTED_DATA>"))
        assertTrue(synthesis.prompt.contains("</AMPER_TOOL_RESULT_UNTRUSTED_DATA>"))
        assertTrue(synthesis.prompt.contains("<FINALIZATION_RULES>"))
        assertTrue(synthesis.prompt.contains("</FINALIZATION_RULES>"))
        assertTrue(synthesis.prompt.contains("<CURRENT_USER_REQUEST_FINAL>"))
        assertTrue(synthesis.prompt.contains("</CURRENT_USER_REQUEST_FINAL>"))
        assertFalse(synthesis.prompt.contains("o".repeat(6_000)))
        assertTrue(f.runtime.pendingApprovals.load(pending.proposal.requestId) == null)
    }
}
