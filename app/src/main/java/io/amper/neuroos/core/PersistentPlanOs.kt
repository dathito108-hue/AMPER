package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

interface SovereignPlanStore {
    val receipts: SovereignPlanReceiptLedger? get() = null
    fun save(plan: SovereignPlan): SovereignPlan
    fun load(id: PlanId): SovereignPlan?
    fun list(limit: Int = 16): List<SovereignPlan>
    fun delete(id: PlanId): Boolean
}

class MemoryBackedSovereignPlanStore(
    private val memory: MemoryOs
) : SovereignPlanStore {
    override val receipts: SovereignPlanReceiptLedger = MemoryBackedSovereignPlanReceiptLedger(memory)

    override fun save(plan: SovereignPlan): SovereignPlan {
        memory.remember(
            MemoryRecord(
                id = recordId(plan.id),
                kind = KIND,
                content = SovereignPlanCodec.encode(plan),
                importance = 0.96,
                provenance = Provenance(
                    source = "sovereign-plan-state",
                    producer = "persistent-plan-os",
                    confidence = 1.0
                ),
                createdAtEpochMs = plan.createdAtEpochMs
            )
        )
        return plan
    }

    override fun load(id: PlanId): SovereignPlan? = memory.get(recordId(id))
        ?.takeIf { it.kind == KIND }
        ?.let { SovereignPlanCodec.decode(it.content).getOrNull() }

    override fun list(limit: Int): List<SovereignPlan> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(KIND, (limit * 2).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == KIND }
            .mapNotNull { SovereignPlanCodec.decode(it.content).getOrNull() }
            .sortedByDescending { it.createdAtEpochMs }
            .take(limit)
            .toList()
    }

    override fun delete(id: PlanId): Boolean = memory.forget(recordId(id))

    private fun recordId(id: PlanId) = MemoryId("sovereign-plan:${id.value}")

    companion object { const val KIND = "sovereign-plan-v1" }
}

/**
 * Persistent bounded planner with verified execution receipts and explicit
 * reconciliation for interrupted side-effect claims. Reconciliation never calls a
 * tool: the user records whether the already-claimed side effect was observed as
 * executed or not executed, then AMPER persists a terminal receipt and plan state.
 */
class PersistentSovereignPlanCoordinator(
    private val delegate: SovereignPlanCoordinator,
    private val store: SovereignPlanStore,
    private val recoveryGuard: SovereignPlanRecoveryGuard? = null
) {
    private val receipts: SovereignPlanReceiptLedger? = store.receipts
    private val recoveryInterlock = SovereignRecoveryInterlock(
        SovereignRecoveryState(receipts)
    )

    fun create(conversationId: ConversationId, userGoal: String): Result<SovereignPlan> =
        delegate.create(conversationId, userGoal).map(store::save)

    fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> {
        receipts?.verifyCompletedPrefix(plan)?.exceptionOrNull()?.let { return Result.failure(it) }
        recoveryGuard?.verifyAdvance(plan)?.exceptionOrNull()?.let { return Result.failure(it) }
        return delegate.advance(plan).mapCatching { result ->
            when (result) {
                is PlanAdvanceResult.StepProcessed -> {
                    receipts?.recordTerminal(result.plan, result.step)?.getOrThrow()
                    store.save(result.plan)
                }
                is PlanAdvanceResult.PendingApproval -> store.save(result.plan)
                is PlanAdvanceResult.Complete -> {
                    receipts?.verifyCompletedPrefix(result.plan)?.getOrThrow()
                    store.save(result.plan)
                }
            }
            result
        }
    }

    fun approve(plan: SovereignPlan, stepIndex: Int): Result<PlanAdvanceResult.StepProcessed> {
        receipts?.verifyCompletedPrefix(plan)?.exceptionOrNull()?.let { return Result.failure(it) }
        recoveryInterlock.requireApprovalAllowed().exceptionOrNull()?.let { return Result.failure(it) }

        val bindingResult = recoveryGuard?.approvalBinding(plan, stepIndex)
            ?: delegate.approvalBinding(plan, stepIndex)
        val binding = bindingResult.getOrElse { return Result.failure(it) }
        val step = plan.steps.single { it.index == stepIndex }

        receipts?.claimSideEffect(plan, step)?.exceptionOrNull()?.let { return Result.failure(it) }

        return delegate.approveBound(plan, stepIndex, binding).mapCatching { result ->
            receipts?.recordTerminal(result.plan, result.step)?.getOrThrow()
            store.save(result.plan)
            result
        }
    }

    fun reject(plan: SovereignPlan, stepIndex: Int): SovereignPlan {
        receipts?.verifyCompletedPrefix(plan)?.getOrThrow()
        val rejected = delegate.reject(plan, stepIndex)
        val step = rejected.steps.single { it.index == stepIndex }
        receipts?.recordTerminal(rejected, step)?.getOrThrow()
        return store.save(rejected)
    }

    /**
     * Resolve an interrupted side-effect claim without invoking the provider.
     * CONFIRMED_EXECUTED records a terminal EXECUTED outcome; CONFIRMED_NOT_EXECUTED
     * records FAILED so the old request cannot be replayed implicitly. Reconciliation
     * is deliberately exempt from the approval interlock because it is the only
     * operation that can clear recovery debt without replaying the provider.
     */
    fun reconcile(
        plan: SovereignPlan,
        stepIndex: Int,
        decision: PlanClaimDecision,
        note: String
    ): Result<PlanAdvanceResult.StepProcessed> = runCatching {
        val ledger = requireNotNull(receipts) { "persistent receipt ledger is unavailable" }
        ledger.verifyCompletedPrefix(plan).getOrThrow()
        val firstActive = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED || it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        }
        require(firstActive?.index == stepIndex) {
            "plan step $stepIndex is not the current reconciliation checkpoint"
        }
        val step = plan.steps.single { it.index == stepIndex }
        require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            "plan step $stepIndex is not awaiting side-effect reconciliation"
        }
        val resolution = ledger.reconcileClaim(plan, step, decision, note).getOrThrow()
        val (planStatus, actionStatus, detail) = when (decision) {
            PlanClaimDecision.CONFIRMED_EXECUTED -> Triple(
                PlanStepStatus.EXECUTED,
                ActionStatus.EXECUTED,
                "Manual reconciliation confirmed claimed side effect executed; provider was not replayed"
            )
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED -> Triple(
                PlanStepStatus.FAILED,
                ActionStatus.FAILED,
                "Manual reconciliation confirmed claimed side effect did not execute; provider was not replayed"
            )
        }
        val outcome = ActionOutcome(
            status = actionStatus,
            proposal = step.proposal(),
            toolId = resolution.toolId,
            sideEffect = resolution.sideEffect,
            detail = detail
        )
        val updatedStep = step.copy(status = planStatus, outcome = outcome)
        val updatedPlan = plan.copy(
            steps = plan.steps.map { if (it.index == stepIndex) updatedStep else it }
        )
        ledger.recordTerminal(updatedPlan, updatedStep).getOrThrow()
        store.save(updatedPlan)
        PlanAdvanceResult.StepProcessed(updatedPlan, updatedStep, outcome)
    }

    fun pendingClaims(limit: Int = 32): List<PlanSideEffectClaim> =
        receipts?.pendingClaims(limit).orEmpty()

    fun recoveryLocked(): Boolean = recoveryInterlock.locked()

    fun reconciliation(planId: PlanId, requestId: ActionRequestId): PlanClaimReconciliation? =
        receipts?.reconciliation(planId, requestId)

    fun latest(conversationId: ConversationId? = null): SovereignPlan? = store.list(32)
        .firstOrNull {
            !AssistantActionTransaction.isTransaction(it) &&
                (conversationId == null || it.conversationId == conversationId)
        }
}

internal object SovereignPlanCodec {
    private const val VERSION_V1 = "AMPER_PLAN_STATE_V1"
    private const val VERSION_V2 = "AMPER_PLAN_STATE_V2"
    private const val VERSION_V3 = "AMPER_PLAN_STATE_V3"
    private const val VERSION_V4 = "AMPER_PLAN_STATE_V4"
    private const val VERSION = "AMPER_PLAN_STATE_V5"

    fun encode(plan: SovereignPlan): String {
        val extendedPlanningState =
            plan.parentPlanId != null ||
                plan.recoveryDepth != 0 ||
                plan.deliberationCandidateCount != 1 ||
                plan.deliberationScore != null ||
                plan.counterfactualViability != null ||
                plan.counterfactualConfidence != null

        return buildString {
        appendLine(if (extendedPlanningState) VERSION else VERSION_V4)
        appendLine("ID\t${enc(plan.id.value)}")
        appendLine("CONVERSATION\t${enc(plan.conversationId.value)}")
        appendLine("GOAL\t${enc(plan.goal)}")
        appendLine("BACKEND\t${enc(plan.planningBackendId)}")
        appendLine("CREATED\t${plan.createdAtEpochMs}")
        appendLine("MODEL\t${plan.planningModelId?.value?.let(::enc) ?: "~"}")
        appendLine(
            "CAPABILITIES\t${
                plan.planningSelectedCapabilities
                    .sortedBy { it.value }
                    .joinToString(",") { enc(it.value) }
                    .ifBlank { "~" }
            }"
        )
        if (extendedPlanningState) {
            appendLine("PARENT_PLAN\t${plan.parentPlanId?.value?.let(::enc) ?: "~"}")
            appendLine("RECOVERY_DEPTH\t${plan.recoveryDepth}")
            appendLine("DELIBERATION_COUNT\t${plan.deliberationCandidateCount}")
            appendLine("DELIBERATION_SCORE\t${plan.deliberationScore?.toString() ?: "~"}")
            appendLine("COUNTERFACTUAL_VIABILITY\t${plan.counterfactualViability?.toString() ?: "~"}")
            appendLine("COUNTERFACTUAL_CONFIDENCE\t${plan.counterfactualConfidence?.toString() ?: "~"}")
        }
        plan.steps.forEach { step ->
            val outcome = step.outcome
            appendLine(
                listOf(
                    "STEP", step.index.toString(), enc(step.requestId.value), enc(step.capability.value),
                    enc(step.reason), enc(step.input), step.status.name,
                    outcome?.status?.name ?: "~", outcome?.toolId?.value?.let(::enc) ?: "~",
                    outcome?.output?.let(::enc) ?: "~", outcome?.detail?.let(::enc) ?: "~",
                    outcome?.sideEffect?.name ?: "~",
                    step.boundToolId?.value?.let(::enc) ?: "~",
                    step.boundSideEffect?.name ?: "~"
                ).joinToString("\t")
            )
        }
        }.trimEnd()
    }

    fun decode(content: String): Result<SovereignPlan> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(
            version == VERSION ||
                version == VERSION_V4 ||
                version == VERSION_V3 ||
                version == VERSION_V2 ||
                version == VERSION_V1
        ) {
            "unsupported sovereign plan state"
        }
        val typedSideEffect = version != VERSION_V1
        val routeProvenance = version == VERSION || version == VERSION_V4 || version == VERSION_V3
        val planBinding = version == VERSION || version == VERSION_V4
        val extendedPlanningState = version == VERSION
        val scalars = linkedMapOf<String, String>()
        val stepLines = mutableListOf<List<String>>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "STEP" -> {
                    val expectedSize = when {
                        planBinding -> 14
                        typedSideEffect -> 12
                        else -> 11
                    }
                    require(parts.size == expectedSize) { "invalid persisted plan step" }
                    stepLines += parts
                }
                "ID", "CONVERSATION", "GOAL", "BACKEND", "CREATED" -> {
                    require(parts.size == 2) { "invalid persisted plan scalar" }
                    require(scalars.put(parts[0], parts[1]) == null) { "duplicate persisted plan scalar" }
                }
                "MODEL", "CAPABILITIES" -> {
                    require(routeProvenance) { "planning route provenance is not valid for $version" }
                    require(parts.size == 2) { "invalid persisted plan route provenance" }
                    require(scalars.put(parts[0], parts[1]) == null) { "duplicate persisted plan scalar" }
                }
                "PARENT_PLAN",
                "RECOVERY_DEPTH",
                "DELIBERATION_COUNT",
                "DELIBERATION_SCORE",
                "COUNTERFACTUAL_VIABILITY",
                "COUNTERFACTUAL_CONFIDENCE" -> {
                    require(extendedPlanningState) { "extended planning state is not valid for $version" }
                    require(parts.size == 2) { "invalid persisted extended planning scalar" }
                    require(scalars.put(parts[0], parts[1]) == null) { "duplicate persisted plan scalar" }
                }
                else -> error("unknown persisted plan field")
            }
        }
        val required = mutableSetOf("ID", "CONVERSATION", "GOAL", "BACKEND", "CREATED")
        if (routeProvenance) required += setOf("MODEL", "CAPABILITIES")
        if (extendedPlanningState) {
            required += setOf(
                "PARENT_PLAN",
                "RECOVERY_DEPTH",
                "DELIBERATION_COUNT",
                "DELIBERATION_SCORE",
                "COUNTERFACTUAL_VIABILITY",
                "COUNTERFACTUAL_CONFIDENCE"
            )
        }
        require(scalars.keys.containsAll(required))
        require(stepLines.isNotEmpty()) { "persisted plan has no steps" }

        val steps = stepLines.map { p ->
            val requestId = ActionRequestId(dec(p[2]))
            val capability = CapabilityId(dec(p[3]))
            val reason = dec(p[4])
            val input = dec(p[5])
            val proposal = ActionProposal(requestId, capability, reason, input)
            val outcomeStatus = p[7].takeUnless { it == "~" }?.let(ActionStatus::valueOf)
            val outcome = outcomeStatus?.let { status ->
                ActionOutcome(
                    status = status,
                    proposal = proposal,
                    toolId = p[8].takeUnless { it == "~" }?.let { ToolId(dec(it)) },
                    output = p[9].takeUnless { it == "~" }?.let(::dec),
                    detail = p[10].takeUnless { it == "~" }?.let(::dec),
                    sideEffect = if (typedSideEffect) {
                        p[11].takeUnless { it == "~" }?.let(ToolSideEffect::valueOf)
                    } else {
                        null
                    }
                )
            }
            SovereignPlanStep(
                index = p[1].toInt(),
                requestId = requestId,
                capability = capability,
                reason = reason,
                input = input,
                status = PlanStepStatus.valueOf(p[6]),
                outcome = outcome,
                boundToolId = if (planBinding) {
                    p[12].takeUnless { it == "~" }?.let { ToolId(dec(it)) }
                } else {
                    null
                },
                boundSideEffect = if (planBinding) {
                    p[13].takeUnless { it == "~" }?.let(ToolSideEffect::valueOf)
                } else {
                    null
                }
            )
        }.sortedBy { it.index }

        val planningModelId = scalars["MODEL"]
            ?.takeUnless { it == "~" }
            ?.let(::dec)
            ?.let(::ModelId)
        val planningSelectedCapabilities = scalars["CAPABILITIES"]
            ?.takeUnless { it == "~" }
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.mapTo(linkedSetOf()) { CapabilityId(dec(it)) }
            .orEmpty()

        val parentPlanId = if (extendedPlanningState) {
            scalars.getValue("PARENT_PLAN")
                .takeUnless { it == "~" }
                ?.let(::dec)
                ?.let(::PlanId)
        } else {
            null
        }
        val recoveryDepth = if (extendedPlanningState) {
            scalars.getValue("RECOVERY_DEPTH").toInt()
        } else {
            0
        }
        val deliberationCandidateCount = if (extendedPlanningState) {
            scalars.getValue("DELIBERATION_COUNT").toInt()
        } else {
            1
        }
        val deliberationScore = if (extendedPlanningState) {
            scalars.getValue("DELIBERATION_SCORE").takeUnless { it == "~" }?.toDouble()
        } else {
            null
        }
        val counterfactualViability = if (extendedPlanningState) {
            scalars.getValue("COUNTERFACTUAL_VIABILITY").takeUnless { it == "~" }?.toDouble()
        } else {
            null
        }
        val counterfactualConfidence = if (extendedPlanningState) {
            scalars.getValue("COUNTERFACTUAL_CONFIDENCE").takeUnless { it == "~" }?.toDouble()
        } else {
            null
        }

        SovereignPlan(
            id = PlanId(dec(scalars.getValue("ID"))),
            conversationId = ConversationId(dec(scalars.getValue("CONVERSATION"))),
            goal = dec(scalars.getValue("GOAL")),
            steps = steps,
            planningBackendId = dec(scalars.getValue("BACKEND")),
            createdAtEpochMs = scalars.getValue("CREATED").toLong(),
            planningModelId = planningModelId,
            planningSelectedCapabilities = planningSelectedCapabilities,
            parentPlanId = parentPlanId,
            recoveryDepth = recoveryDepth,
            deliberationCandidateCount = deliberationCandidateCount,
            deliberationScore = deliberationScore,
            counterfactualViability = counterfactualViability,
            counterfactualConfidence = counterfactualConfidence
        )
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun dec(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
