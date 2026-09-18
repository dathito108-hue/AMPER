package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectiveAnsweringTest {
    private class QueueInference(responses: List<Result<InferenceResponse>>) : CognitiveInferencePort {
        private val queued = ArrayDeque(responses)
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> {
            requests += request
            return if (queued.isEmpty()) {
                Result.failure(IllegalStateException("unexpected inference request"))
            } else {
                queued.removeFirst()
            }
        }
    }

    private class RecordingProvider(
        override val descriptor: ToolDescriptor
    ) : ToolProvider {
        val inputs = mutableListOf<String>()

        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("executed:$input")
        }
    }

    private data class Fixture(
        val runtime: AmperRuntime,
        val coordinator: SovereignAssistantTurnCoordinator,
        val inference: QueueInference,
        val provider: RecordingProvider
    )

    private fun response(
        text: String,
        model: String,
        capabilities: Set<CapabilityId> = setOf(TitanCapabilities.REASONING)
    ): Result<InferenceResponse> = Result.success(
        InferenceResponse(
            modelId = ModelId(model),
            backendId = "backend-$model",
            text = text,
            selectedCapabilities = capabilities
        )
    )

    private fun fixture(responses: List<Result<InferenceResponse>>): Fixture {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("reflect.write")
        val provider = RecordingProvider(
            ToolDescriptor(
                id = ToolId("reflect-writer"),
                name = "Reflect writer",
                capability = capability,
                sideEffect = ToolSideEffect.LOCAL_STATE
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
                maxPromptChars = 6_000,
                maxOutputTokens = 256,
                temperature = 0.7
            ),
            inference = inference,
            provider = provider
        )
    }

    private fun enableVerify(f: Fixture, conversationId: ConversationId) {
        f.runtime.conversations.prepare(conversationId, "persist profile host", charBudget = 1_024)
        f.runtime.inferenceProfiles.put(
            conversationId,
            ConversationInferenceProfile(
                maxOutputTokens = 700,
                temperature = 0.2,
                sessionRoutingPreference = TitanSessionRoutingPreference.PREFER_REUSE,
                maxPromptChars = 4_096,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
    }

    @Test
    fun standardModePreservesOnePassToolFreeBehavior() {
        val f = fixture(listOf(response("standard answer", "standard-model")))
        val conversation = ConversationId("standard")

        val final = f.coordinator.respond(conversation, "answer normally").getOrThrow()
            as SovereignAssistantTurnResult.Final

        assertEquals(1, final.inferencePasses)
        assertFalse(final.reflectionApplied)
        assertEquals("standard answer", final.response.text)
        assertEquals(1, f.inference.requests.size)
        assertTrue(f.provider.inputs.isEmpty())
    }

    @Test
    fun verifyModeUsesOneBoundedSecondPassForToolFreeAnswer() {
        val draft = "Draft with <unsafe-tag> that should be reviewed"
        val improved = "Improved final answer with the missing caveat."
        val f = fixture(
            listOf(
                response(
                    draft,
                    "draft-model",
                    setOf(TitanCapabilities.REASONING, TitanCapabilities.CODE_GENERATION)
                ),
                response(improved, "review-model")
            )
        )
        val conversation = ConversationId("verify")
        enableVerify(f, conversation)

        val final = f.coordinator.respond(conversation, "Give me the careful answer").getOrThrow()
            as SovereignAssistantTurnResult.Final

        assertEquals(2, final.inferencePasses)
        assertTrue(final.reflectionApplied)
        assertEquals(improved, final.response.text)
        assertEquals(2, f.inference.requests.size)
        val reviewRequest = f.inference.requests[1]
        assertTrue(reviewRequest.prompt.length <= 4_096)
        assertTrue(reviewRequest.prompt.contains("<REFLECTION_RULES>"))
        assertTrue(reviewRequest.prompt.contains("<AMPER_CANDIDATE_ANSWER_UNTRUSTED_DATA>"))
        assertTrue(reviewRequest.prompt.contains("&lt;unsafe-tag&gt;"))
        assertFalse(reviewRequest.prompt.contains("<ACTION_PROTOCOL>"))
        assertEquals(700, reviewRequest.maxOutputTokens)
        assertEquals(0.2, reviewRequest.temperature, 0.0)
        assertEquals(TitanSessionRoutingPreference.PREFER_REUSE, reviewRequest.sessionRoutingPreference)
        assertEquals(ModelId("draft-model"), reviewRequest.preferredModelId)
        assertEquals(improved, f.runtime.conversations.recent(conversation, 16).last().text)
        assertTrue(f.provider.inputs.isEmpty())
    }

    @Test
    fun verifierActionEnvelopeIsRejectedWithoutToolOrApproval() {
        val verifierAction = """
            <AMPER_ACTION_V1>
            capability=reflect.write
            reason=Verifier must not execute tools
            input=payload=forbidden
            </AMPER_ACTION_V1>
        """.trimIndent()
        val f = fixture(
            listOf(
                response("safe first answer", "draft-model"),
                response(verifierAction, "review-model")
            )
        )
        val conversation = ConversationId("reject-verifier-action")
        enableVerify(f, conversation)

        val final = f.coordinator.respond(conversation, "Do not run tools").getOrThrow()
            as SovereignAssistantTurnResult.Final

        assertEquals(2, final.inferencePasses)
        assertFalse(final.reflectionApplied)
        assertEquals("safe first answer", final.response.text)
        assertTrue(f.provider.inputs.isEmpty())
        assertNull(f.runtime.pendingApprovals.latest(conversation))
        assertEquals("safe first answer", f.runtime.conversations.recent(conversation, 16).last().text)
    }

    @Test
    fun reflectionInferenceFailureKeepsCompletedFirstAnswer() {
        val f = fixture(
            listOf(
                response("completed first answer", "draft-model"),
                Result.failure(IllegalStateException("review backend failed"))
            )
        )
        val conversation = ConversationId("reflection-failure")
        enableVerify(f, conversation)

        val final = f.coordinator.respond(conversation, "Give a resilient answer").getOrThrow()
            as SovereignAssistantTurnResult.Final

        assertEquals(2, final.inferencePasses)
        assertFalse(final.reflectionApplied)
        assertEquals("completed first answer", final.response.text)
        assertEquals("completed first answer", f.runtime.conversations.recent(conversation, 16).last().text)
    }

    @Test
    fun sideEffectActionPathNeverGainsAThirdReflectionPass() {
        val action = """
            <AMPER_ACTION_V1>
            capability=reflect.write
            reason=The user explicitly requested this local state write
            input=payload=alpha
            </AMPER_ACTION_V1>
        """.trimIndent()
        val f = fixture(
            listOf(
                response(action, "action-model"),
                response("final after approved write", "synthesis-model")
            )
        )
        val conversation = ConversationId("verify-action")
        enableVerify(f, conversation)

        val pending = f.coordinator.respond(conversation, "write alpha").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval

        assertEquals(1, f.inference.requests.size)
        assertEquals(ConversationReflectionMode.VERIFY, pending.boundInferenceProfile?.reflectionMode)
        val final = f.coordinator.approve(pending).getOrThrow()

        assertEquals(2, final.inferencePasses)
        assertFalse(final.reflectionApplied)
        assertEquals(2, f.inference.requests.size)
        assertEquals(listOf("payload=alpha"), f.provider.inputs)
        assertEquals("final after approved write", final.response.text)
        assertNull(f.runtime.pendingApprovals.load(pending.proposal.requestId))
    }
}
