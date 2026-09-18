package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignAssistantTurnTest {
    private class QueueInference(
        responses: List<String>,
        modelIds: List<ModelId> = List(responses.size) { ModelId("fake-model") }
    ) : CognitiveInferencePort {
        private val queued = ArrayDeque(responses)
        private val queuedModels = ArrayDeque(modelIds)
        val prompts = mutableListOf<String>()
        val requests = mutableListOf<InferenceRequest>()

        init {
            require(responses.size == modelIds.size)
        }

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            requests += request
            prompts += request.prompt
            val text = queued.removeFirst()
            val modelId = queuedModels.removeFirst()
            InferenceResponse(
                modelId = modelId,
                backendId = "fake-backend",
                text = text
            )
        }
    }

    private class RecordingProvider(
        capability: String,
        sideEffect: ToolSideEffect
    ) : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("provider-$capability"),
            name = "Provider $capability",
            capability = CapabilityId(capability),
            sideEffect = sideEffect
        )
        val inputs = mutableListOf<String>()
        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("TOOL_DATA:$input")
        }
    }

    private data class Fixture(
        val coordinator: SovereignAssistantTurnCoordinator,
        val inference: QueueInference,
        val provider: RecordingProvider
    )

    private fun fixture(
        capability: String,
        sideEffect: ToolSideEffect,
        granted: Boolean,
        responses: List<String>,
        modelIds: List<ModelId> = List(responses.size) { ModelId("fake-model") }
    ): Fixture {
        val registry = InMemoryToolRegistry()
        val provider = RecordingProvider(capability, sideEffect)
        registry.register(provider)
        val audit = InMemoryToolAuditLog()
        val grantedCapabilities = if (granted) setOf(CapabilityId(capability)) else emptySet()
        val actionLoop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(grantedCapabilities),
                registry = registry,
                audit = audit
            ),
            memory = InMemoryMemoryOs(),
            workspace = InMemoryWorkspace()
        )
        val inference = QueueInference(responses, modelIds)
        return Fixture(
            coordinator = SovereignAssistantTurnCoordinator(
                runtime = AmperRuntime.reference(),
                inference = inference,
                actions = actionLoop,
                advertisedCapabilities = setOf(CapabilityId(capability)),
                maxPromptChars = 6000
            ),
            inference = inference,
            provider = provider
        )
    }

    private fun action(capability: String, input: String = "scope=status") = """
        <AMPER_ACTION_V1>
        capability=$capability
        reason=The user request needs this tool
        input=$input
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun normalAnswerFinishesInOneInferencePass() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf("AMPER final answer")
        )

        val result = f.coordinator.respond(ConversationId("normal"), "hello").getOrThrow()
        val final = result as SovereignAssistantTurnResult.Final
        assertEquals(1, final.inferencePasses)
        assertEquals("AMPER final answer", final.response.text)
        assertEquals(1, f.inference.prompts.size)
        assertTrue(f.inference.requests.single().preferredCapabilityProfiles.isEmpty())
        assertEquals(null, f.inference.requests.single().preferredModelId)
        assertTrue(f.provider.inputs.isEmpty())
    }

    @Test
    fun codeRequestCarriesSpecialistPreferenceIntoInference() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf("Here is the Kotlin implementation")
        )

        f.coordinator.respond(
            ConversationId("code"),
            "Hãy viết code Kotlin để parse dữ liệu này"
        ).getOrThrow()

        val request = f.inference.requests.single()
        assertEquals(setOf(TitanCapabilities.REASONING), request.requiredCapabilities)
        assertEquals(
            listOf(setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)),
            request.preferredCapabilityProfiles
        )
        assertEquals(null, request.preferredModelId)
    }

    @Test
    fun readOnlyToolGetsOneExecutionThenOneSynthesisPass() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf(action("device.read", "scope=battery"), "Battery data summarized")
        )

        val result = f.coordinator.respond(ConversationId("readonly"), "How is the battery?").getOrThrow()
        val final = result as SovereignAssistantTurnResult.Final
        assertEquals(2, final.inferencePasses)
        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(listOf("scope=battery"), f.provider.inputs)
        assertEquals(2, f.inference.prompts.size)
        assertEquals(null, f.inference.requests[0].preferredModelId)
        assertEquals(ModelId("fake-model"), f.inference.requests[1].preferredModelId)
        assertTrue(f.inference.prompts[1].contains("TOOL_DATA:scope=battery"))
        assertTrue(f.inference.prompts[1].contains("Do not request or execute another tool"))
        assertTrue(f.inference.prompts.all { it.length <= 6000 })
    }

    @Test
    fun specialistPreferenceIsStableAcrossToolSynthesisPass() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf(action("device.read", "scope=status"), "Code-aware final answer")
        )

        f.coordinator.respond(
            ConversationId("code-tool"),
            "Debug code Kotlin rồi đọc trạng thái thiết bị"
        ).getOrThrow()

        assertEquals(2, f.inference.requests.size)
        assertEquals(
            f.inference.requests[0].preferredCapabilityProfiles,
            f.inference.requests[1].preferredCapabilityProfiles
        )
        assertEquals(
            listOf(setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)),
            f.inference.requests[1].preferredCapabilityProfiles
        )
        assertEquals(ModelId("fake-model"), f.inference.requests[1].preferredModelId)
    }

    @Test
    fun completedModelBecomesSoftPreferenceForNextTurnInSameConversation() {
        val modelA = ModelId("model-a")
        val modelB = ModelId("model-b")
        val modelC = ModelId("model-c")
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf("first answer", "second answer", "third answer"),
            modelIds = listOf(modelA, modelB, modelC)
        )
        val conversation = ConversationId("cross-turn")

        f.coordinator.respond(conversation, "first question").getOrThrow()
        f.coordinator.respond(conversation, "follow up").getOrThrow()
        f.coordinator.respond(conversation, "one more follow up").getOrThrow()

        assertEquals(null, f.inference.requests[0].preferredModelId)
        assertEquals(modelA, f.inference.requests[1].preferredModelId)
        assertEquals(modelB, f.inference.requests[2].preferredModelId)
    }

    @Test
    fun crossTurnContinuityDoesNotCrossConversationBoundary() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf("alpha answer", "beta answer"),
            modelIds = listOf(ModelId("alpha-model"), ModelId("beta-model"))
        )

        f.coordinator.respond(ConversationId("alpha-thread"), "alpha").getOrThrow()
        f.coordinator.respond(ConversationId("beta-thread"), "beta").getOrThrow()

        assertEquals(null, f.inference.requests[0].preferredModelId)
        assertEquals(null, f.inference.requests[1].preferredModelId)
    }

    @Test
    fun finalSynthesisModelNotFirstPassModelDrivesNextTurnContinuity() {
        val firstPassModel = ModelId("first-pass-model")
        val synthesisModel = ModelId("synthesis-model")
        val nextModel = ModelId("next-model")
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf(
                action("device.read", "scope=status"),
                "tool-aware final answer",
                "next answer"
            ),
            modelIds = listOf(firstPassModel, synthesisModel, nextModel)
        )
        val conversation = ConversationId("synthesis-continuity")

        f.coordinator.respond(conversation, "check device").getOrThrow()
        f.coordinator.respond(conversation, "continue from that").getOrThrow()

        assertEquals(null, f.inference.requests[0].preferredModelId)
        assertEquals(firstPassModel, f.inference.requests[1].preferredModelId)
        assertEquals(synthesisModel, f.inference.requests[2].preferredModelId)
    }

    @Test
    fun previousModelHintDoesNotReplaceNewSpecialistCapabilityPreference() {
        val priorModel = ModelId("prior-general-model")
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf("general answer", "code answer"),
            modelIds = listOf(priorModel, ModelId("code-model"))
        )
        val conversation = ConversationId("specialist-after-general")

        f.coordinator.respond(conversation, "hello").getOrThrow()
        f.coordinator.respond(conversation, "Hãy viết code Kotlin để parse JSON").getOrThrow()

        val codeRequest = f.inference.requests[1]
        assertEquals(priorModel, codeRequest.preferredModelId)
        assertEquals(
            listOf(setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)),
            codeRequest.preferredCapabilityProfiles
        )
        assertEquals(setOf(TitanCapabilities.REASONING), codeRequest.requiredCapabilities)
    }

    @Test
    fun externalToolStopsForApprovalBeforeExecution() {
        val f = fixture(
            capability = "network.send",
            sideEffect = ToolSideEffect.EXTERNAL,
            granted = true,
            responses = listOf(action("network.send", "payload=hello"), "Message operation completed")
        )

        val first = f.coordinator.respond(ConversationId("external"), "Send hello").getOrThrow()
        val pending = first as SovereignAssistantTurnResult.PendingApproval
        assertTrue(f.provider.inputs.isEmpty())
        assertEquals(1, f.inference.prompts.size)

        val final = f.coordinator.approve(pending).getOrThrow()
        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(listOf("payload=hello"), f.provider.inputs)
        assertEquals(2, f.inference.prompts.size)
        assertEquals(ModelId("fake-model"), f.inference.requests[1].preferredModelId)
    }

    @Test
    fun explicitApprovalStillCannotBypassAuthorityGate() {
        val f = fixture(
            capability = "network.send",
            sideEffect = ToolSideEffect.EXTERNAL,
            granted = false,
            responses = listOf(action("network.send", "payload=blocked"), "I could not perform that action")
        )

        val pending = f.coordinator.respond(ConversationId("denied"), "Send blocked payload").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val final = f.coordinator.approve(pending).getOrThrow()

        assertEquals(ActionStatus.DENIED, final.actionOutcome?.status)
        assertTrue(f.provider.inputs.isEmpty())
        assertEquals(2, f.inference.prompts.size)
        assertEquals(ModelId("fake-model"), f.inference.requests[1].preferredModelId)
    }

    @Test
    fun secondModelPassCannotStartAnotherToolLoop() {
        val f = fixture(
            capability = "device.read",
            sideEffect = ToolSideEffect.READ_ONLY,
            granted = true,
            responses = listOf(
                action("device.read", "scope=first"),
                action("device.read", "scope=second")
            )
        )

        val result = f.coordinator.respond(ConversationId("bounded"), "Check twice").getOrThrow()
        val final = result as SovereignAssistantTurnResult.Final
        assertEquals(2, final.inferencePasses)
        assertEquals(listOf("scope=first"), f.provider.inputs)
        assertEquals(2, f.inference.prompts.size)
        assertEquals(ModelId("fake-model"), f.inference.requests[1].preferredModelId)
    }
}
