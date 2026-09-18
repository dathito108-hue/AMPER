package io.amper.neuroos.core

import java.nio.file.Files
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPlanOsTest {
    private fun encryptedCipher(): MemoryLineCipher {
        val key = ByteArray(32) { index -> (61 + index).toByte() }
        return AesGcmMemoryLineCipher(SecretKeySpec(key, "AES"), "phase18-plan-test")
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun encryptedRuntimeRestoresPlanAcrossProcessRecreation() {
        val root = Files.createTempDirectory("amper-plan-restart").toFile()
        val cipher = encryptedCipher()
        val proposal = ActionProposal(
            requestId = ActionRequestId("request-stable-1"),
            capability = CapabilityId("test.local"),
            reason = "Await explicit approval after restart",
            input = "apply"
        )
        val plan = SovereignPlan(
            id = PlanId("persistent-plan-1"),
            conversationId = ConversationId("conversation-persisted"),
            goal = "private persistent planning goal",
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = proposal.requestId,
                    capability = proposal.capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.REQUIRES_CONFIRMATION,
                    outcome = ActionOutcome(
                        status = ActionStatus.REQUIRES_CONFIRMATION,
                        proposal = proposal,
                        toolId = ToolId("local-test-tool"),
                        sideEffect = ToolSideEffect.LOCAL_STATE,
                        detail = "LOCAL_STATE action requires explicit approval"
                    ),
                    boundToolId = ToolId("local-test-tool"),
                    boundSideEffect = ToolSideEffect.LOCAL_STATE
                )
            ),
            planningBackendId = "planner-backend",
            createdAtEpochMs = 123456789L,
            planningModelId = ModelId("planner-model-v3"),
            planningSelectedCapabilities = linkedSetOf(
                TitanCapabilities.REASONING,
                TitanCapabilities.PLANNING
            )
        )

        val firstRuntime = AmperRuntime.persistentEncrypted(root, cipher = cipher)
        firstRuntime.plans.save(plan)

        val journal = root.resolve("amper-sovereign/memory.journal")
        val stored = journal.readText()
        assertTrue(stored.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("E1|") })
        assertFalse(stored.contains(plan.goal))
        assertFalse(stored.contains(proposal.reason))
        assertFalse(stored.contains(plan.planningModelId!!.value))
        assertFalse(stored.contains("local-test-tool"))

        val recreatedRuntime = AmperRuntime.persistentEncrypted(root, cipher = cipher)
        val restored = recreatedRuntime.plans.load(plan.id)

        assertEquals(plan, restored)
        assertEquals(proposal.requestId, restored?.steps?.single()?.requestId)
        assertEquals(PlanStepStatus.REQUIRES_CONFIRMATION, restored?.steps?.single()?.status)
        assertEquals(ToolId("local-test-tool"), restored?.steps?.single()?.boundToolId)
        assertEquals(ToolSideEffect.LOCAL_STATE, restored?.steps?.single()?.boundSideEffect)
        assertEquals(ModelId("planner-model-v3"), restored?.planningModelId)
        assertEquals(
            linkedSetOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING),
            restored?.planningSelectedCapabilities
        )
        assertEquals(plan.id, recreatedRuntime.plans.list(8).single().id)
    }

    @Test
    fun legacyV4PlanRemainsReadableWithSafeDefaultsForExtendedPlanningState() {
        val content = listOf(
            "AMPER_PLAN_STATE_V4",
            "ID\t${enc("legacy-v4-plan")}",
            "CONVERSATION\t${enc("legacy-v4-conversation")}",
            "GOAL\t${enc("legacy v4 goal")}",
            "BACKEND\t${enc("legacy-v4-backend")}",
            "CREATED\t99",
            "MODEL\t${enc("legacy-v4-model")}",
            "CAPABILITIES\t${enc(TitanCapabilities.REASONING.value)}",
            listOf(
                "STEP",
                "1",
                enc("legacy-v4-request"),
                enc("test.read"),
                enc("Read one value"),
                enc("status"),
                "PLANNED",
                "~",
                "~",
                "~",
                "~",
                "~",
                enc("legacy-v4-tool"),
                ToolSideEffect.READ_ONLY.name
            ).joinToString("\t")
        ).joinToString("\n")

        val restored = SovereignPlanCodec.decode(content).getOrThrow()

        assertEquals(PlanId("legacy-v4-plan"), restored.id)
        assertEquals(ToolId("legacy-v4-tool"), restored.steps.single().boundToolId)
        assertEquals(ToolSideEffect.READ_ONLY, restored.steps.single().boundSideEffect)
        assertNull(restored.parentPlanId)
        assertEquals(0, restored.recoveryDepth)
        assertEquals(1, restored.deliberationCandidateCount)
        assertNull(restored.deliberationScore)
        assertNull(restored.counterfactualViability)
        assertNull(restored.counterfactualConfidence)
    }

    @Test
    fun legacyV3PlanRemainsReadableWithoutInventingPlanBinding() {
        val content = listOf(
            "AMPER_PLAN_STATE_V3",
            "ID\t${enc("legacy-v3-plan")}",
            "CONVERSATION\t${enc("legacy-v3-conversation")}",
            "GOAL\t${enc("legacy v3 goal")}",
            "BACKEND\t${enc("legacy-v3-backend")}",
            "CREATED\t88",
            "MODEL\t${enc("legacy-v3-model")}",
            "CAPABILITIES\t${enc(TitanCapabilities.REASONING.value)}",
            listOf(
                "STEP",
                "1",
                enc("legacy-v3-request"),
                enc("test.read"),
                enc("Read one value"),
                enc("status"),
                "PLANNED",
                "~",
                "~",
                "~",
                "~",
                "~"
            ).joinToString("\t")
        ).joinToString("\n")

        val restored = SovereignPlanCodec.decode(content).getOrThrow()

        assertEquals(PlanId("legacy-v3-plan"), restored.id)
        assertEquals(ModelId("legacy-v3-model"), restored.planningModelId)
        assertEquals(setOf(TitanCapabilities.REASONING), restored.planningSelectedCapabilities)
        assertNull(restored.steps.single().boundToolId)
        assertNull(restored.steps.single().boundSideEffect)
    }

    @Test
    fun legacyV2PlanRemainsReadableWithoutInventingRouteProvenance() {
        val content = listOf(
            "AMPER_PLAN_STATE_V2",
            "ID\t${enc("legacy-v2-plan")}",
            "CONVERSATION\t${enc("legacy-v2-conversation")}",
            "GOAL\t${enc("legacy v2 goal")}",
            "BACKEND\t${enc("legacy-v2-backend")}",
            "CREATED\t77",
            listOf(
                "STEP",
                "1",
                enc("legacy-v2-request"),
                enc("test.read"),
                enc("Read one value"),
                enc("status"),
                "PLANNED",
                "~",
                "~",
                "~",
                "~",
                "~"
            ).joinToString("\t")
        ).joinToString("\n")

        val restored = SovereignPlanCodec.decode(content).getOrThrow()

        assertEquals(PlanId("legacy-v2-plan"), restored.id)
        assertEquals("legacy-v2-backend", restored.planningBackendId)
        assertNull(restored.planningModelId)
        assertTrue(restored.planningSelectedCapabilities.isEmpty())
        assertNull(restored.steps.single().boundToolId)
        assertNull(restored.steps.single().boundSideEffect)
        assertEquals(PlanStepStatus.PLANNED, restored.steps.single().status)
    }

    @Test
    fun persistentCoordinatorSavesCreateAndEveryAdvanceSnapshot() {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("test.read")
        val registry = InMemoryToolRegistry()
        registry.register(object : ToolProvider {
            override val descriptor = ToolDescriptor(
                id = ToolId("phase18-read"),
                name = "Phase 18 read tool",
                capability = capability,
                sideEffect = ToolSideEffect.READ_ONLY,
                inputContract = ToolInputContract(
                    description = "Read a test value",
                    acceptedValues = setOf("status"),
                    maxLength = 16
                )
            )

            override fun execute(input: String): Result<String> = Result.success("ok:$input")
        })
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(setOf(capability)),
            registry = registry,
            audit = audit
        )
        val actions = runtime.actionLoop(registry, fabric)
        val selected = linkedSetOf(TitanCapabilities.REASONING, TitanCapabilities.PLANNING)
        val inference = CognitiveInferencePort {
            Result.success(
                InferenceResponse(
                    modelId = ModelId("planner-model"),
                    backendId = "planner-backend",
                    text = """
                        <AMPER_PLAN_V1>
                        step.1.capability=test.read
                        step.1.reason=Read one governed status
                        step.1.input=status
                        </AMPER_PLAN_V1>
                    """.trimIndent(),
                    selectedCapabilities = selected
                )
            )
        }
        val persistent = PersistentSovereignPlanCoordinator(
            delegate = SovereignPlanCoordinator(
                runtime = runtime,
                inference = inference,
                actions = actions,
                advertisedCapabilities = setOf(capability)
            ),
            store = runtime.plans
        )

        val created = persistent.create(runtime.conversations.primary(), "read governed status").getOrThrow()
        val persistedCreated = runtime.plans.load(created.id)
        assertNotNull(persistedCreated)
        assertEquals(PlanStepStatus.PLANNED, persistedCreated?.steps?.single()?.status)
        assertEquals(ToolId("phase18-read"), persistedCreated?.steps?.single()?.boundToolId)
        assertEquals(ToolSideEffect.READ_ONLY, persistedCreated?.steps?.single()?.boundSideEffect)
        assertEquals(ModelId("planner-model"), persistedCreated?.planningModelId)
        assertEquals(selected, persistedCreated?.planningSelectedCapabilities)

        val advanced = persistent.advance(created).getOrThrow() as PlanAdvanceResult.StepProcessed
        val restored = runtime.plans.load(created.id)
        assertEquals(1, audit.snapshot().size)
        assertEquals(PlanStepStatus.EXECUTED, advanced.step.status)
        assertEquals(PlanStepStatus.EXECUTED, restored?.steps?.single()?.status)
        assertEquals(ToolId("phase18-read"), restored?.steps?.single()?.boundToolId)
        assertEquals(ToolSideEffect.READ_ONLY, restored?.steps?.single()?.boundSideEffect)
        assertEquals(ModelId("planner-model"), restored?.planningModelId)
        assertEquals(selected, restored?.planningSelectedCapabilities)
        assertTrue(restored?.complete == true)
    }
}
