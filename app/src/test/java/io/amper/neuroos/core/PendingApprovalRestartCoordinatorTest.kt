package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingApprovalRestartCoordinatorTest {
    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queue = ArrayDeque(responses)
        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            InferenceResponse(
                modelId = ModelId("restart-model"),
                backendId = "restart-backend",
                text = queue.removeFirst()
            )
        }
    }

    private class RecordingProvider : ToolProvider {
        override val descriptor = ToolDescriptor(
            id = ToolId("restart-network-provider"),
            name = "Restart network provider",
            capability = CapabilityId("network.send"),
            sideEffect = ToolSideEffect.EXTERNAL
        )
        val inputs = mutableListOf<String>()
        override fun execute(input: String): Result<String> {
            inputs += input
            return Result.success("sent:$input")
        }
    }

    private data class Host(
        val coordinator: SovereignAssistantTurnCoordinator,
        val provider: RecordingProvider
    )

    private fun host(runtime: AmperRuntime, responses: List<String>): Host {
        val provider = RecordingProvider()
        val registry = InMemoryToolRegistry().also { it.register(provider) }
        val actions = runtime.actionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(provider.descriptor.capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            )
        )
        return Host(
            coordinator = SovereignAssistantTurnCoordinator(
                runtime = runtime,
                inference = QueueInference(responses),
                actions = actions,
                advertisedCapabilities = setOf(provider.descriptor.capability),
                maxPromptChars = 6000
            ),
            provider = provider
        )
    }

    private fun action() = """
        <AMPER_ACTION_V1>
        capability=network.send
        reason=The user explicitly requested this external action
        input=payload=restart
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun freshCoordinatorRestoresApprovalAfterPersistentRuntimeRestart() {
        val root = Files.createTempDirectory("amper-approval-restart-restore").toFile()
        try {
            val conversation = ConversationId("restart-thread")
            val firstRuntime = AmperRuntime.persistent(root)
            val firstHost = host(firstRuntime, listOf(action()))
            val original = firstHost.coordinator.respond(conversation, "Send after confirmation").getOrThrow()
                as SovereignAssistantTurnResult.PendingApproval
            assertTrue(firstHost.provider.inputs.isEmpty())

            val restartedRuntime = AmperRuntime.persistent(root)
            val restartedHost = host(restartedRuntime, emptyList())
            val restored = restartedHost.coordinator.restorePendingApproval()

            assertEquals(original.conversationId, restored?.conversationId)
            assertEquals(original.proposal, restored?.proposal)
            assertEquals(original.toolId, restored?.toolId)
            assertEquals(original.firstResponse.modelId, restored?.firstResponse?.modelId)
            assertTrue(restartedHost.provider.inputs.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectingRestoredApprovalSurvivesAnotherRestartAsDeleted() {
        val root = Files.createTempDirectory("amper-approval-restart-reject").toFile()
        try {
            val firstRuntime = AmperRuntime.persistent(root)
            val firstHost = host(firstRuntime, listOf(action()))
            firstHost.coordinator.respond(ConversationId("reject-after-restart"), "Send later").getOrThrow()

            val restartedRuntime = AmperRuntime.persistent(root)
            val restartedHost = host(restartedRuntime, emptyList())
            val restored = requireNotNull(restartedHost.coordinator.restorePendingApproval())
            restartedHost.coordinator.reject(restored).getOrThrow()
            assertTrue(restartedHost.provider.inputs.isEmpty())

            val thirdRuntime = AmperRuntime.persistent(root)
            val thirdHost = host(thirdRuntime, emptyList())
            assertNull(thirdHost.coordinator.restorePendingApproval())
            assertTrue(thirdHost.provider.inputs.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun approvingRestoredApprovalAfterRestartExecutesOnceAndClearsCheckpoint() {
        val root = Files.createTempDirectory("amper-approval-restart-approve").toFile()
        try {
            val firstRuntime = AmperRuntime.persistent(root)
            val firstHost = host(firstRuntime, listOf(action()))
            val original = firstHost.coordinator.respond(
                ConversationId("approve-after-restart"),
                "Send after restart"
            ).getOrThrow() as SovereignAssistantTurnResult.PendingApproval
            assertTrue(firstHost.provider.inputs.isEmpty())

            val restartedRuntime = AmperRuntime.persistent(root)
            val restartedHost = host(restartedRuntime, listOf("Action completed"))
            val restored = requireNotNull(restartedHost.coordinator.restorePendingApproval())
            val final = restartedHost.coordinator.approve(restored).getOrThrow()
            val planId = AssistantActionTransaction.planId(original.proposal.requestId)

            assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
            assertEquals(listOf("payload=restart"), restartedHost.provider.inputs)
            assertNull(restartedHost.coordinator.restorePendingApproval())
            assertEquals(PlanStepStatus.EXECUTED, restartedRuntime.plans.load(planId)?.steps?.single()?.status)

            val thirdRuntime = AmperRuntime.persistent(root)
            val thirdHost = host(thirdRuntime, emptyList())
            assertNull(thirdHost.coordinator.restorePendingApproval())
            assertTrue(thirdHost.provider.inputs.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
