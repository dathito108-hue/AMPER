package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexAssistantSideEffectTest {
    @Test
    fun explicitTimerSkipsFirstInferenceButStillRequiresApproval() {
        val runtime = AmperRuntime.reference()
        var launcherCalls = 0
        val launcher = object : AndroidDeviceActionLauncher {
            override fun openSettings(target: AndroidSettingsTarget): Result<Unit> =
                Result.success(Unit)
            override fun openTimer(command: AndroidTimerCommand): Result<Unit> {
                launcherCalls += 1
                return Result.success(Unit)
            }
            override fun openShareSheet(text: String): Result<Unit> =
                Result.success(Unit)
        }
        val registry = InMemoryToolRegistry().also {
            it.register(AndroidTimerPrepareToolProvider(launcher))
        }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(AndroidTimerPrepareToolContract.capability)),
            registry = registry,
            audit = audit
        )
        var inferenceCalls = 0
        val inference = CognitiveInferencePort {
            inferenceCalls += 1
            Result.success(
                InferenceResponse(
                    modelId = ModelId("system2-test"),
                    backendId = "system2-test",
                    text = "Timer action completed."
                )
            )
        }
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = runtime.actionLoop(registry, fabric),
            advertisedCapabilities = setOf(AndroidTimerPrepareToolContract.capability)
        )

        val result = assistant.respond(
            ConversationId("reflex-timer"),
            "Hẹn giờ 5 phút"
        ).getOrThrow()

        val pending = result as SovereignAssistantTurnResult.PendingApproval
        assertEquals(0, inferenceCalls)
        assertEquals(0, launcherCalls)
        assertEquals("seconds=300", pending.proposal.input)
        assertEquals(AndroidTimerPrepareToolContract.capability, pending.proposal.capability)
        assertEquals(ToolSideEffect.EXTERNAL, pending.sideEffect)
        assertEquals(ReflexDecisionRuntimeContract.BACKEND_ID, pending.firstResponse.backendId)
        assertTrue(runtime.pendingApprovals.load(pending.proposal.requestId) != null)
        assertEquals(0, audit.snapshot().size)

        val final = assistant.approve(pending).getOrThrow()
        assertEquals(1, final.inferencePasses)
        assertEquals(ActionStatus.EXECUTED, final.actionOutcome?.status)
        assertEquals(1, inferenceCalls)
        assertEquals(1, launcherCalls)
        assertEquals(1, audit.snapshot().size)
        val experience = runtime.reflexExperienceDatasets.recentExamples(4).single()
        assertEquals(AndroidTimerPrepareToolContract.capability, experience.targetCapability)
        assertEquals(ToolSideEffect.EXTERNAL, experience.targetSideEffect)
        assertEquals(ReflexExperienceSource.REFLEX_CORTEX, experience.source)
    }
}
