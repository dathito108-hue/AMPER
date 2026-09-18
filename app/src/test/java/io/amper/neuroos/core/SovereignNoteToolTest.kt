package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignNoteToolTest {
    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queue = ArrayDeque(responses)

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            InferenceResponse(
                modelId = ModelId("note-model"),
                backendId = "note-backend",
                text = queue.removeFirst(),
                selectedCapabilities = setOf(TitanCapabilities.REASONING)
            )
        }
    }

    private data class Harness(
        val runtime: AmperRuntime,
        val coordinator: SovereignAssistantTurnCoordinator
    )

    private fun harness(
        runtime: AmperRuntime,
        responses: List<String>,
        granted: Boolean = true
    ): Harness {
        val registry = InMemoryToolRegistry().also {
            it.register(SovereignNoteToolProvider(runtime.notes))
        }
        val capability = SovereignNoteToolContract.capability
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(if (granted) setOf(capability) else emptySet()),
            registry = registry,
            audit = InMemoryToolAuditLog()
        )
        return Harness(
            runtime = runtime,
            coordinator = SovereignAssistantTurnCoordinator(
                runtime = runtime,
                inference = QueueInference(responses),
                actions = runtime.actionLoop(registry, fabric),
                advertisedCapabilities = setOf(capability),
                maxPromptChars = 6000
            )
        )
    }

    private fun noteAction(note: String) = """
        <AMPER_ACTION_V1>
        capability=sovereign.note.write
        reason=The user explicitly asked AMPER to remember this note
        input=$note
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun localStateProposalCannotWriteBeforeExplicitApproval() {
        val runtime = AmperRuntime.reference()
        val note = "Project Phoenix uses the canonical local runtime"
        val h = harness(runtime, listOf(noteAction(note)))
        val conversation = ConversationId("note-needs-approval")

        val pending = h.coordinator.respond(conversation, "Remember this: $note").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval

        assertEquals(SovereignNoteToolContract.toolId, pending.toolId)
        assertEquals(ToolSideEffect.LOCAL_STATE, pending.sideEffect)
        assertEquals(note, pending.proposal.input)
        assertTrue(runtime.notes.find("Phoenix").isEmpty())
        assertNotNull(runtime.pendingApprovals.load(pending.proposal.requestId))
        assertNull(runtime.plans.load(AssistantActionTransaction.planId(pending.proposal.requestId)))
    }

    @Test
    fun approvedNoteUsesDurableTransactionAndBecomesGroundedMemory() {
        val runtime = AmperRuntime.reference()
        val note = "Project Phoenix release channel is sovereign-stable"
        val h = harness(runtime, listOf(noteAction(note), "I saved that sovereign note."))
        val conversation = ConversationId("note-approved")
        val pending = h.coordinator.respond(conversation, "Remember this: $note").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)

        val final = h.coordinator.approve(pending).getOrThrow()

        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(PlanStepStatus.EXECUTED, runtime.plans.load(planId)?.steps?.single()?.status)
        assertEquals(
            ActionStatus.EXECUTED,
            runtime.plans.receipts?.receipt(planId, pending.proposal.requestId)?.actionStatus
        )
        assertNull(runtime.pendingApprovals.load(pending.proposal.requestId))
        assertEquals(note, runtime.notes.find("Phoenix").single().text)
        assertTrue(
            runtime.context.capture("Phoenix", memoryLimit = 8).memories.any {
                it.kind == MemoryBackedSovereignNoteStore.KIND && it.content == note
            }
        )
    }

    @Test
    fun denyByDefaultAuthorityStillBlocksApprovedLocalStateWrite() {
        val runtime = AmperRuntime.reference()
        val note = "This note must never be persisted"
        val h = harness(
            runtime = runtime,
            responses = listOf(noteAction(note), "The write was denied."),
            granted = false
        )
        val pending = h.coordinator.respond(
            ConversationId("note-authority-denied"),
            "Remember this: $note"
        ).getOrThrow() as SovereignAssistantTurnResult.PendingApproval

        val final = h.coordinator.approve(pending).getOrThrow()

        assertEquals(ActionStatus.DENIED, final.actionOutcome?.status)
        assertTrue(runtime.notes.find("persisted").isEmpty())
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)
        assertEquals(
            ActionStatus.DENIED,
            runtime.plans.receipts?.receipt(planId, pending.proposal.requestId)?.actionStatus
        )
    }

    @Test
    fun pendingNoteSurvivesRestartAndExecutesOnlyAfterRestoredApproval() {
        val root = Files.createTempDirectory("amper-phase121-note").toFile()
        try {
            val note = "Restart token cedar-947 belongs to the local notebook"
            val conversation = ConversationId("note-restart")
            val firstRuntime = AmperRuntime.persistent(root)
            val first = harness(firstRuntime, listOf(noteAction(note)))
            val pending = first.coordinator.respond(
                conversation,
                "Remember this across restart: $note"
            ).getOrThrow() as SovereignAssistantTurnResult.PendingApproval

            assertTrue(firstRuntime.notes.find("cedar-947").isEmpty())

            val restartedRuntime = AmperRuntime.persistent(root)
            val restarted = harness(
                restartedRuntime,
                listOf("The restored sovereign note was saved.")
            )
            val restored = requireNotNull(restarted.coordinator.restorePendingApproval(conversation))
            assertEquals(pending.proposal.requestId, restored.proposal.requestId)
            assertTrue(restartedRuntime.notes.find("cedar-947").isEmpty())

            val final = restarted.coordinator.approve(restored).getOrThrow()
            assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)

            val secondRestart = AmperRuntime.persistent(root)
            assertEquals(note, secondRestart.notes.find("cedar-947").single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noteStoreRejectsMultilineAndOversizedPayloads() {
        val runtime = AmperRuntime.reference()

        assertTrue(runCatching { runtime.notes.remember("line one\nline two") }.isFailure)
        assertTrue(
            runCatching {
                runtime.notes.remember("x".repeat(SovereignNoteToolContract.MAX_NOTE_CHARS + 1))
            }.isFailure
        )
        assertTrue(runtime.notes.find("line").isEmpty())
    }
}
