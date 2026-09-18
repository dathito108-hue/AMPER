package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedAssistantStreamingTest {
    private data class Script(
        val response: Result<InferenceResponse>,
        val chunks: List<String> = emptyList()
    )

    private class QueueStreamingInference(
        scripts: List<Script>
    ) : StreamingCognitiveInferencePort {
        private val queue = ArrayDeque(scripts)
        val requests = mutableListOf<InferenceRequest>()

        override fun infer(request: InferenceRequest): Result<InferenceResponse> =
            next(request, onChunk = null)

        override fun inferStream(
            request: InferenceRequest,
            onChunk: (InferenceChunk) -> Unit
        ): Result<InferenceResponse> =
            next(request, onChunk)

        private fun next(
            request: InferenceRequest,
            onChunk: ((InferenceChunk) -> Unit)?
        ): Result<InferenceResponse> {
            requests += request
            if (queue.isEmpty()) return Result.failure(IllegalStateException("unexpected inference request"))
            val script = queue.removeFirst()
            script.chunks.forEachIndexed { index, text ->
                onChunk?.invoke(InferenceChunk(text, index))
            }
            if (script.response.isSuccess) {
                onChunk?.invoke(InferenceChunk("", script.chunks.size, finished = true))
            }
            return script.response
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
        val assistant: SovereignAssistantTurnCoordinator,
        val inference: QueueStreamingInference,
        val provider: RecordingProvider
    )

    private fun response(text: String, model: String): InferenceResponse =
        InferenceResponse(
            modelId = ModelId(model),
            backendId = "backend-$model",
            text = text,
            selectedCapabilities = setOf(TitanCapabilities.REASONING)
        )

    private fun fixture(scripts: List<Script>): Fixture {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("stream.write")
        val provider = RecordingProvider(
            ToolDescriptor(
                id = ToolId("stream-writer"),
                name = "Stream writer",
                capability = capability,
                sideEffect = ToolSideEffect.LOCAL_STATE
            )
        )
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val loop = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        val inference = QueueStreamingInference(scripts)
        return Fixture(
            runtime = runtime,
            assistant = SovereignAssistantTurnCoordinator(
                runtime = runtime,
                inference = inference,
                actions = loop,
                advertisedCapabilities = setOf(capability),
                maxPromptChars = 6_000,
                maxOutputTokens = 256
            ),
            inference = inference,
            provider = provider
        )
    }

    private fun render(events: List<AssistantStreamEvent>): String {
        val out = StringBuilder()
        events.forEach { event ->
            when (event) {
                AssistantStreamEvent.Reset -> out.setLength(0)
                is AssistantStreamEvent.Text -> out.append(event.text)
            }
        }
        return out.toString()
    }

    private fun enableVerify(f: Fixture, conversationId: ConversationId) {
        f.runtime.conversations.prepare(conversationId, "persist profile", charBudget = 1_024)
        f.runtime.inferenceProfiles.put(
            conversationId,
            ConversationInferenceProfile(
                maxPromptChars = 4_096,
                reflectionMode = ConversationReflectionMode.VERIFY
            )
        )
    }

    @Test
    fun ordinaryToolFreeAnswerStreamsAndCommitsExactlyOnce() {
        val answer = "Hello streamed world"
        val f = fixture(
            listOf(
                Script(
                    response = Result.success(response(answer, "stream-model")),
                    chunks = listOf("He", "llo ", "streamed ", "world")
                )
            )
        )
        val conversation = ConversationId("stream-normal")
        val events = mutableListOf<AssistantStreamEvent>()

        val final = f.assistant.respondStreaming(conversation, "say hello", events::add)
            .getOrThrow() as SovereignAssistantTurnResult.Final

        assertEquals(answer, render(events))
        assertEquals(answer, final.response.text)
        assertEquals(1, final.inferencePasses)
        assertEquals(answer, f.runtime.conversations.recent(conversation, 16).last().text)
        assertTrue(f.provider.inputs.isEmpty())
    }

    @Test
    fun exactExecutableActionEnvelopeNeverLeaksIntoStream() {
        val action = """
            <AMPER_ACTION_V1>
            capability=stream.write
            reason=User requested a local write
            input=alpha
            </AMPER_ACTION_V1>
        """.trimIndent()
        val f = fixture(
            listOf(
                Script(
                    response = Result.success(response(action, "action-model")),
                    chunks = listOf(
                        "  <AMPER_",
                        "ACTION_V1>\ncapability=stream.write\n",
                        "reason=User requested a local write\ninput=alpha\n",
                        "</AMPER_ACTION_V1>"
                    )
                )
            )
        )
        val conversation = ConversationId("stream-action")
        val events = mutableListOf<AssistantStreamEvent>()

        val pending = f.assistant.respondStreaming(conversation, "write alpha", events::add)
            .getOrThrow() as SovereignAssistantTurnResult.PendingApproval

        assertTrue(events.isEmpty())
        assertEquals(CapabilityId("stream.write"), pending.proposal.capability)
        assertTrue(f.provider.inputs.isEmpty())
        assertEquals(pending, f.runtime.pendingApprovals.latest(conversation))
    }

    @Test
    fun malformedActionLikeFirstPassResetsBeforeFinalSynthesis() {
        val malformed = """
            Draft preamble
            <AMPER_ACTION_V1>
            capability=stream.write
            reason=not executable because envelope is not entire output
            input=alpha
            </AMPER_ACTION_V1>
        """.trimIndent()
        val finalText = "Final safe synthesis"
        val f = fixture(
            listOf(
                Script(
                    response = Result.success(response(malformed, "first-model")),
                    chunks = listOf("Draft ", "preamble\n<AMPER_ACTION_V1>", "\ncapability=stream.write")
                ),
                Script(
                    response = Result.success(response(finalText, "final-model")),
                    chunks = listOf("Final ", "safe ", "synthesis")
                )
            )
        )
        val conversation = ConversationId("stream-reset")
        val events = mutableListOf<AssistantStreamEvent>()

        val final = f.assistant.respondStreaming(conversation, "handle malformed output", events::add)
            .getOrThrow() as SovereignAssistantTurnResult.Final

        assertTrue(events.any { it == AssistantStreamEvent.Reset })
        assertEquals(finalText, render(events))
        assertEquals(finalText, final.response.text)
        assertTrue(f.provider.inputs.isEmpty())
    }

    @Test
    fun verifyModeStreamsOnlyReflectedFinalAnswerNotDraft() {
        val f = fixture(
            listOf(
                Script(
                    response = Result.success(response("draft answer", "draft-model")),
                    chunks = listOf("draft ", "answer")
                ),
                Script(
                    response = Result.success(response("verified answer", "verify-model")),
                    chunks = listOf("verified ", "answer")
                )
            )
        )
        val conversation = ConversationId("stream-verify")
        enableVerify(f, conversation)
        val events = mutableListOf<AssistantStreamEvent>()

        val final = f.assistant.respondStreaming(conversation, "be careful", events::add)
            .getOrThrow() as SovereignAssistantTurnResult.Final

        assertEquals("verified answer", render(events))
        assertFalse(events.filterIsInstance<AssistantStreamEvent.Text>().any { it.text.contains("draft") })
        assertTrue(final.reflectionApplied)
        assertEquals(2, final.inferencePasses)
    }

    @Test
    fun verifierActionEnvelopeIsWithheldAndFallsBackToFirstAnswer() {
        val verifierAction = """
            <AMPER_ACTION_V1>
            capability=stream.write
            reason=must not execute from reflection
            input=forbidden
            </AMPER_ACTION_V1>
        """.trimIndent()
        val f = fixture(
            listOf(
                Script(Result.success(response("safe draft", "draft-model"))),
                Script(
                    response = Result.success(response(verifierAction, "verify-model")),
                    chunks = listOf("<AMPER_ACTION_V1>", "\ncapability=stream.write", "\n</AMPER_ACTION_V1>")
                )
            )
        )
        val conversation = ConversationId("stream-verify-action")
        enableVerify(f, conversation)
        val events = mutableListOf<AssistantStreamEvent>()

        val final = f.assistant.respondStreaming(conversation, "verify safely", events::add)
            .getOrThrow() as SovereignAssistantTurnResult.Final

        assertEquals("safe draft", render(events))
        assertFalse(final.reflectionApplied)
        assertTrue(f.provider.inputs.isEmpty())
        assertNull(f.runtime.pendingApprovals.latest(conversation))
    }

    @Test
    fun failedStreamingInferenceClearsTransientPartialTextAndDoesNotCommitAssistant() {
        val f = fixture(
            listOf(
                Script(
                    response = Result.failure(IllegalStateException("backend failed")),
                    chunks = listOf("partial")
                )
            )
        )
        val conversation = ConversationId("stream-failure")
        val events = mutableListOf<AssistantStreamEvent>()

        val result = f.assistant.respondStreaming(conversation, "fail after a token", events::add)

        assertTrue(result.isFailure)
        assertEquals("", render(events))
        assertTrue(events.any { it == AssistantStreamEvent.Reset })
        assertFalse(
            f.runtime.conversations.recent(conversation, 16)
                .any { it.role == ConversationRole.ASSISTANT }
        )
    }
}
