package io.amper.neuroos.core

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovereignNoteLifecycleTest {
    private class QueueInference(responses: List<String>) : CognitiveInferencePort {
        private val queue = ArrayDeque(responses)

        override fun infer(request: InferenceRequest): Result<InferenceResponse> = runCatching {
            InferenceResponse(
                modelId = ModelId("note-lifecycle-model"),
                backendId = "note-lifecycle-backend",
                text = queue.removeFirst(),
                selectedCapabilities = setOf(TitanCapabilities.REASONING)
            )
        }
    }

    private fun deleteCoordinator(
        runtime: AmperRuntime,
        responses: List<String>
    ): SovereignAssistantTurnCoordinator {
        val registry = InMemoryToolRegistry().also {
            it.register(SovereignNoteDeleteToolProvider(runtime.notes))
        }
        val capability = SovereignNoteDeleteToolContract.capability
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = InMemoryToolAuditLog()
        )
        return SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = QueueInference(responses),
            actions = runtime.actionLoop(registry, fabric),
            advertisedCapabilities = setOf(capability),
            maxPromptChars = 6000
        )
    }

    private fun deleteAction(id: MemoryId) = """
        <AMPER_ACTION_V1>
        capability=sovereign.note.delete
        reason=The user explicitly asked AMPER to forget this exact sovereign note
        input=${id.value}
        </AMPER_ACTION_V1>
    """.trimIndent()

    @Test
    fun searchIsReadOnlyAutoExecutableAndReturnsOnlySovereignNotes() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val store = MemoryBackedSovereignNoteStore(memory, workspace)
        val note = store.remember("Project Phoenix release channel is stable")
        memory.remember(
            MemoryRecord(
                kind = "episodic",
                content = "Project Phoenix generic runtime event",
                importance = 0.8
            )
        )
        val registry = InMemoryToolRegistry().also {
            it.register(SovereignNoteSearchToolProvider(store))
        }
        val capability = SovereignNoteSearchToolContract.capability
        val loop = SovereignActionLoop(
            registry = registry,
            fabric = AuditedToolFabric(
                gate = DenyByDefaultAuthorityGate(setOf(capability)),
                registry = registry,
                audit = InMemoryToolAuditLog()
            ),
            memory = memory,
            workspace = workspace
        )

        val outcome = loop.evaluate(
            ActionProposal(
                capability = capability,
                reason = "Find the user's saved Phoenix note",
                input = "Phoenix"
            )
        )

        assertEquals(ActionStatus.EXECUTED, outcome.status)
        assertEquals(ToolSideEffect.READ_ONLY, outcome.sideEffect)
        assertTrue(outcome.output.orEmpty().contains(note.id.value))
        assertTrue(outcome.output.orEmpty().contains(note.text))
        assertFalse(outcome.output.orEmpty().contains("generic runtime event"))
    }

    @Test
    fun deleteRequiresApprovalThenRecordsDurableTerminalReceipt() {
        val runtime = AmperRuntime.reference()
        val note = runtime.notes.remember("Delete token birch-221 after explicit approval")
        val coordinator = deleteCoordinator(
            runtime,
            listOf(deleteAction(note.id), "The sovereign note was deleted.")
        )
        val conversation = ConversationId("delete-note-approved")

        val pending = coordinator.respond(conversation, "Forget note ${note.id.value}").getOrThrow()
            as SovereignAssistantTurnResult.PendingApproval

        assertEquals(SovereignNoteDeleteToolContract.toolId, pending.toolId)
        assertEquals(ToolSideEffect.LOCAL_STATE, pending.sideEffect)
        assertNotNull(runtime.notes.get(note.id))
        val planId = AssistantActionTransaction.planId(pending.proposal.requestId)
        assertNull(runtime.plans.load(planId))

        val final = coordinator.approve(pending).getOrThrow()

        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertNull(runtime.notes.get(note.id))
        assertEquals(PlanStepStatus.EXECUTED, runtime.plans.load(planId)?.steps?.single()?.status)
        assertEquals(
            ActionStatus.EXECUTED,
            runtime.plans.receipts?.receipt(planId, pending.proposal.requestId)?.actionStatus
        )
    }

    @Test
    fun pendingDeleteSurvivesRestartAndDeletionTombstoneRemainsDurable() {
        val root = Files.createTempDirectory("amper-phase123-note-delete").toFile()
        try {
            val conversation = ConversationId("delete-note-restart")
            val firstRuntime = AmperRuntime.persistent(root)
            val note = firstRuntime.notes.remember("Restart delete token cedar-551")
            val firstCoordinator = deleteCoordinator(firstRuntime, listOf(deleteAction(note.id)))
            val pending = firstCoordinator.respond(
                conversation,
                "Forget sovereign note ${note.id.value}"
            ).getOrThrow() as SovereignAssistantTurnResult.PendingApproval

            assertNotNull(firstRuntime.notes.get(note.id))

            val restartedRuntime = AmperRuntime.persistent(root)
            val restartedCoordinator = deleteCoordinator(
                restartedRuntime,
                listOf("The restored delete request completed.")
            )
            val restored = requireNotNull(restartedCoordinator.restorePendingApproval(conversation))
            assertEquals(pending.proposal.requestId, restored.proposal.requestId)
            assertNotNull(restartedRuntime.notes.get(note.id))

            val final = restartedCoordinator.approve(restored).getOrThrow()
            assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
            assertNull(restartedRuntime.notes.get(note.id))

            val secondRestart = AmperRuntime.persistent(root)
            assertNull(secondRestart.notes.get(note.id))
            assertTrue(secondRestart.notes.find("cedar-551").isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noteStoreCannotDeleteArbitraryMemoryRecord() {
        val memory = InMemoryMemoryOs()
        val workspace = InMemoryWorkspace()
        val store = MemoryBackedSovereignNoteStore(memory, workspace)
        val generic = MemoryRecord(
            kind = "episodic",
            content = "protected generic memory",
            importance = 0.8
        )
        memory.remember(generic)

        assertFalse(store.forget(generic.id))
        assertNotNull(memory.get(generic.id))
    }

    @Test
    fun deleteRejectsMalformedOrUnknownNoteIds() {
        val runtime = AmperRuntime.reference()
        val provider = SovereignNoteDeleteToolProvider(runtime.notes)

        assertTrue(provider.execute("not-a-uuid").isFailure)
        assertTrue(provider.execute("00000000-0000-0000-0000-000000000000").isFailure)
    }
}
