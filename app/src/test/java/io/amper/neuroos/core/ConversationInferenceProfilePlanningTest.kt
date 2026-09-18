package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInferenceProfilePlanningTest {
    private class RecordingInference : CognitiveInferencePort {
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> {
            requests += request
            return Result.success(
                InferenceResponse(
                    modelId = ModelId("planning-profile-model"),
                    backendId = "planning-profile-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=device.read
                        step.1.reason=Read the requested status
                        step.1.input=status
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)
                )
            )
        }
    }

    @Test
    fun sovereignPlannerUsesSameConversationInferenceProfile() {
        val runtime = AmperRuntime.reference()
        val conversation = ConversationId("planning-profile-thread")
        runtime.conversations.prepare(conversation, "seed persisted conversation", charBudget = 1024)
        runtime.inferenceProfiles.put(
            conversation,
            ConversationInferenceProfile(
                maxOutputTokens = 900,
                temperature = 0.1,
                sessionRoutingPreference = TitanSessionRoutingPreference.IGNORE_REUSE,
                maxPromptChars = 4_096
            )
        )

        val capability = CapabilityId("device.read")
        val acceptedValues = setOf("status") + (1..31).map { index ->
            "value-$index-${"x".repeat(96)}"
        }
        val provider = object : ToolProvider {
            override val descriptor = ToolDescriptor(
                id = ToolId("device-reader"),
                name = "Device reader",
                capability = capability,
                sideEffect = ToolSideEffect.READ_ONLY,
                inputContract = ToolInputContract(
                    description = "Read device status ${"d".repeat(220)}",
                    maxLength = 128,
                    acceptedValues = acceptedValues
                )
            )

            override fun execute(input: String): Result<String> = Result.success("unused")
        }
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        val inference = RecordingInference()
        val planner = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actions,
            advertisedCapabilities = setOf(capability),
            maxPromptChars = 6_000,
            maxOutputTokens = 256,
            temperature = 0.7
        )

        val plan = planner.create(conversation, "Check device status").getOrThrow()
        val request = inference.requests.single()

        assertEquals(900, request.maxOutputTokens)
        assertEquals(0.1, request.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.IGNORE_REUSE, request.sessionRoutingPreference)
        assertTrue(request.prompt.length <= 4_096)
        assertTrue(request.prompt.contains("<PLANNING_PROTOCOL>"))
        assertTrue(request.prompt.contains("</PLANNING_PROTOCOL>"))
        assertTrue(request.prompt.contains("<AMPER_PLAN_V1>"))
        assertTrue(request.prompt.contains("</AMPER_PLAN_V1>"))
        assertTrue(request.prompt.contains("Allowed capability names: device.read"))
        assertFalse(request.prompt.contains("TOOL device.read"))
        assertEquals(setOf(TitanCapabilities.REASONING), request.requiredCapabilities)
        assertEquals(
            listOf(setOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)),
            request.preferredCapabilityProfiles
        )
        assertEquals(ModelId("planning-profile-model"), plan.planningModelId)
        assertEquals(ToolId("device-reader"), plan.steps.single().boundToolId)
        assertEquals(ToolSideEffect.READ_ONLY, plan.steps.single().boundSideEffect)
    }
}
