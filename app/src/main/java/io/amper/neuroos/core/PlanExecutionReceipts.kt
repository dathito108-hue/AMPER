package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class PlanSideEffectClaim(
    val planId: PlanId,
    val stepIndex: Int,
    val requestId: ActionRequestId,
    val capability: CapabilityId,
    val toolId: ToolId,
    val sideEffect: ToolSideEffect,
    val reasonSha256: String,
    val inputSha256: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(stepIndex > 0)
        require(reasonSha256.matches(Regex("[0-9a-f]{64}")))
        require(inputSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class PlanClaimDecision {
    CONFIRMED_EXECUTED,
    CONFIRMED_NOT_EXECUTED
}

data class PlanClaimReconciliation(
    val planId: PlanId,
    val stepIndex: Int,
    val requestId: ActionRequestId,
    val capability: CapabilityId,
    val toolId: ToolId,
    val sideEffect: ToolSideEffect,
    val decision: PlanClaimDecision,
    val note: String,
    val claimSha256: String,
    val noteSha256: String,
    val reconciliationSha256: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(stepIndex > 0)
        require(note.isNotBlank())
        require(note.length <= 512)
        require(claimSha256.matches(Regex("[0-9a-f]{64}")))
        require(noteSha256.matches(Regex("[0-9a-f]{64}")))
        require(reconciliationSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class PlanExecutionReceipt(
    val planId: PlanId,
    val stepIndex: Int,
    val requestId: ActionRequestId,
    val capability: CapabilityId,
    val reasonSha256: String,
    val inputSha256: String,
    val stepStatus: PlanStepStatus,
    val actionStatus: ActionStatus?,
    val toolId: ToolId?,
    val outputSha256: String?,
    val detailSha256: String?,
    val receiptSha256: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(stepIndex > 0)
        require(reasonSha256.matches(Regex("[0-9a-f]{64}")))
        require(inputSha256.matches(Regex("[0-9a-f]{64}")))
        outputSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        detailSha256?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(receiptSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

interface SovereignPlanReceiptLedger {
    fun claimSideEffect(plan: SovereignPlan, step: SovereignPlanStep): Result<PlanSideEffectClaim>
    fun recordTerminal(plan: SovereignPlan, step: SovereignPlanStep): Result<PlanExecutionReceipt>
    fun verifyCompletedPrefix(plan: SovereignPlan): Result<Unit>
    fun receipt(planId: PlanId, requestId: ActionRequestId): PlanExecutionReceipt?
    fun claim(planId: PlanId, requestId: ActionRequestId): PlanSideEffectClaim?
    fun pendingClaims(limit: Int = 32): List<PlanSideEffectClaim>
    fun reconciliation(planId: PlanId, requestId: ActionRequestId): PlanClaimReconciliation?
    fun reconcileClaim(
        plan: SovereignPlan,
        step: SovereignPlanStep,
        decision: PlanClaimDecision,
        note: String
    ): Result<PlanClaimReconciliation>
}

class MemoryBackedSovereignPlanReceiptLedger(
    private val memory: MemoryOs
) : SovereignPlanReceiptLedger {
    @Synchronized
    override fun claimSideEffect(plan: SovereignPlan, step: SovereignPlanStep): Result<PlanSideEffectClaim> = runCatching {
        memory.transaction {
            require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) { "side-effect claim requires a pending approval step" }
            val outcome = requireNotNull(step.outcome) { "pending step has no governed outcome" }
            require(outcome.status == ActionStatus.REQUIRES_CONFIRMATION) { "pending step outcome is not REQUIRES_CONFIRMATION" }
            val toolId = requireNotNull(outcome.toolId) { "pending step has no bound tool id" }
            val sideEffect = boundSideEffect(outcome)
                ?: error("pending step has no bound side-effect class")
            require(sideEffect != ToolSideEffect.READ_ONLY) { "read-only plan steps must not create side-effect claims" }

            val id = claimRecordId(plan.id, step.requestId)
            val claim = PlanSideEffectClaim(
                planId = plan.id,
                stepIndex = step.index,
                requestId = step.requestId,
                capability = step.capability,
                toolId = toolId,
                sideEffect = sideEffect,
                reasonSha256 = sha256(step.reason),
                inputSha256 = sha256(step.input)
            )
            val record = MemoryRecord(
                id = id,
                kind = CLAIM_KIND,
                content = encodeClaim(claim),
                importance = 1.0,
                provenance = Provenance(
                    source = "sovereign-plan-side-effect-claim",
                    producer = "plan-execution-ledger",
                    confidence = 1.0
                ),
                createdAtEpochMs = claim.createdAtEpochMs
            )
            require(rememberIfAbsent(record)) {
                "side-effect request ${step.requestId.value} was already claimed; manual reconciliation required"
            }
            claim
        }
    }

    @Synchronized
    override fun recordTerminal(plan: SovereignPlan, step: SovereignPlanStep): Result<PlanExecutionReceipt> = runCatching {
        memory.transaction {
            require(step.status.isTerminal()) { "execution receipt requires a terminal plan step" }
            reconciliation(plan.id, step.requestId)?.let { resolution ->
                verifyTerminalMatchesReconciliation(step, resolution)
            }

            val candidate = buildReceipt(plan, step)
            val id = receiptRecordId(plan.id, step.requestId)
            val record = MemoryRecord(
                id = id,
                kind = RECEIPT_KIND,
                content = encodeReceipt(candidate),
                importance = 1.0,
                provenance = Provenance(
                    source = "sovereign-plan-execution-receipt",
                    producer = "plan-execution-ledger",
                    confidence = 1.0
                ),
                createdAtEpochMs = candidate.createdAtEpochMs
            )
            if (rememberIfAbsent(record)) return@transaction candidate

            val existing = requireNotNull(get(id)) { "execution receipt disappeared during atomic insert" }
            require(existing.kind == RECEIPT_KIND) { "receipt id is occupied by a different record kind" }
            val decoded = decodeReceipt(existing.content).getOrThrow()
            require(decoded.receiptSha256 == candidate.receiptSha256) {
                "immutable execution receipt mismatch for ${step.requestId.value}"
            }
            decoded
        }
    }

    override fun verifyCompletedPrefix(plan: SovereignPlan): Result<Unit> = runCatching {
        val firstActive = plan.steps.firstOrNull { it.status.isActive() }?.index ?: (plan.steps.size + 1)
        plan.steps.filter { it.index < firstActive && it.status.isTerminal() }.forEach { step ->
            val stored = receipt(plan.id, step.requestId)
                ?: error("missing execution receipt for completed step ${step.index}")
            val expected = buildReceipt(plan, step, stored.createdAtEpochMs)
            require(stored.receiptSha256 == expected.receiptSha256) {
                "execution receipt mismatch for completed step ${step.index}"
            }
        }
    }

    override fun receipt(planId: PlanId, requestId: ActionRequestId): PlanExecutionReceipt? =
        memory.get(receiptRecordId(planId, requestId))
            ?.takeIf { it.kind == RECEIPT_KIND }
            ?.let { decodeReceipt(it.content).getOrNull() }

    override fun claim(planId: PlanId, requestId: ActionRequestId): PlanSideEffectClaim? =
        memory.get(claimRecordId(planId, requestId))
            ?.takeIf { it.kind == CLAIM_KIND }
            ?.let { decodeClaim(it.content).getOrNull() }

    override fun pendingClaims(limit: Int): List<PlanSideEffectClaim> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(CLAIM_KIND, (limit * 4).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == CLAIM_KIND }
            .mapNotNull { decodeClaim(it.content).getOrNull() }
            .filter { reconciliation(it.planId, it.requestId) == null }
            .sortedByDescending { it.createdAtEpochMs }
            .take(limit)
            .toList()
    }

    override fun reconciliation(planId: PlanId, requestId: ActionRequestId): PlanClaimReconciliation? =
        memory.get(reconciliationRecordId(planId, requestId))
            ?.takeIf { it.kind == RECONCILIATION_KIND }
            ?.let { decodeReconciliation(it.content).getOrNull() }

    @Synchronized
    override fun reconcileClaim(
        plan: SovereignPlan,
        step: SovereignPlanStep,
        decision: PlanClaimDecision,
        note: String
    ): Result<PlanClaimReconciliation> = runCatching {
        memory.transaction {
            require(note.isNotBlank()) { "reconciliation note is required" }
            require(note.length <= 512) { "reconciliation note exceeds 512 characters" }
            require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
                "reconciliation requires a pending approval step"
            }
            val storedClaim = claim(plan.id, step.requestId)
                ?: error("no durable side-effect claim exists for ${step.requestId.value}")
            verifyClaimMatches(plan, step, storedClaim)

            val claimHash = sha256(encodeClaim(storedClaim))
            val noteHash = sha256(note)
            val canonical = listOf(
                storedClaim.planId.value,
                storedClaim.stepIndex.toString(),
                storedClaim.requestId.value,
                storedClaim.capability.value,
                storedClaim.toolId.value,
                storedClaim.sideEffect.name,
                storedClaim.reasonSha256,
                storedClaim.inputSha256,
                claimHash,
                decision.name,
                noteHash
            ).joinToString("|")
            val candidate = PlanClaimReconciliation(
                planId = plan.id,
                stepIndex = step.index,
                requestId = step.requestId,
                capability = step.capability,
                toolId = storedClaim.toolId,
                sideEffect = storedClaim.sideEffect,
                decision = decision,
                note = note,
                claimSha256 = claimHash,
                noteSha256 = noteHash,
                reconciliationSha256 = sha256(canonical)
            )
            val id = reconciliationRecordId(plan.id, step.requestId)
            val existing = get(id)
            if (existing != null) {
                require(existing.kind == RECONCILIATION_KIND) {
                    "reconciliation id is occupied by a different record kind"
                }
                val decoded = decodeReconciliation(existing.content).getOrThrow()
                require(decoded.reconciliationSha256 == candidate.reconciliationSha256) {
                    "immutable reconciliation already exists with a different decision or note"
                }
                return@transaction decoded
            }
            require(receipt(plan.id, step.requestId) == null) {
                "terminal receipt already exists without a matching reconciliation"
            }
            val record = MemoryRecord(
                id = id,
                kind = RECONCILIATION_KIND,
                content = encodeReconciliation(candidate),
                importance = 1.0,
                provenance = Provenance(
                    source = "sovereign-plan-claim-reconciliation",
                    producer = "plan-execution-ledger",
                    confidence = 1.0
                ),
                createdAtEpochMs = candidate.createdAtEpochMs
            )
            require(rememberIfAbsent(record)) {
                "reconciliation changed concurrently"
            }
            candidate
        }
    }

    private fun verifyTerminalMatchesReconciliation(
        step: SovereignPlanStep,
        resolution: PlanClaimReconciliation
    ) {
        val action = requireNotNull(step.outcome) { "reconciled terminal step has no governed outcome" }
        require(step.index == resolution.stepIndex) { "terminal step index conflicts with reconciliation" }
        require(step.requestId == resolution.requestId) { "terminal request id conflicts with reconciliation" }
        require(step.capability == resolution.capability) { "terminal capability conflicts with reconciliation" }
        require(action.toolId == resolution.toolId) { "terminal tool binding conflicts with reconciliation" }
        action.sideEffect?.let {
            require(it == resolution.sideEffect) { "terminal side-effect class conflicts with reconciliation" }
        }
        when (resolution.decision) {
            PlanClaimDecision.CONFIRMED_EXECUTED -> {
                require(step.status == PlanStepStatus.EXECUTED && action.status == ActionStatus.EXECUTED) {
                    "terminal outcome conflicts with executed reconciliation"
                }
            }
            PlanClaimDecision.CONFIRMED_NOT_EXECUTED -> {
                require(step.status == PlanStepStatus.FAILED && action.status == ActionStatus.FAILED) {
                    "terminal outcome conflicts with not-executed reconciliation"
                }
            }
        }
    }

    private fun verifyClaimMatches(plan: SovereignPlan, step: SovereignPlanStep, stored: PlanSideEffectClaim) {
        val outcome = requireNotNull(step.outcome) { "pending step has no governed outcome" }
        val toolId = requireNotNull(outcome.toolId) { "pending step has no bound tool id" }
        val sideEffect = boundSideEffect(outcome)
            ?: error("pending step has no bound side-effect class")
        require(stored.planId == plan.id) { "claim plan id mismatch" }
        require(stored.stepIndex == step.index) { "claim step index mismatch" }
        require(stored.requestId == step.requestId) { "claim request id mismatch" }
        require(stored.capability == step.capability) { "claim capability mismatch" }
        require(stored.toolId == toolId) { "claim tool id mismatch" }
        require(stored.sideEffect == sideEffect) { "claim side-effect class mismatch" }
        require(stored.reasonSha256 == sha256(step.reason)) { "claim reason mismatch" }
        require(stored.inputSha256 == sha256(step.input)) { "claim input mismatch" }
    }

    /** V2 uses the typed field; V1 snapshots may fall back to the canonical detail text. */
    private fun boundSideEffect(outcome: ActionOutcome): ToolSideEffect? {
        val legacy = parseSideEffect(outcome.detail)
        val typed = outcome.sideEffect
        if (typed != null && legacy != null) {
            require(typed == legacy) { "typed side-effect conflicts with legacy detail" }
        }
        return typed ?: legacy
    }

    private fun buildReceipt(plan: SovereignPlan, step: SovereignPlanStep, createdAtEpochMs: Long = System.currentTimeMillis()): PlanExecutionReceipt {
        val action = step.outcome
        val reasonHash = sha256(step.reason)
        val inputHash = sha256(step.input)
        val outputHash = action?.output?.let(::sha256)
        val detailHash = action?.detail?.let(::sha256)
        val canonical = listOf(
            plan.id.value, step.index.toString(), step.requestId.value, step.capability.value,
            reasonHash, inputHash, step.status.name, action?.status?.name ?: "~",
            action?.toolId?.value ?: "~", outputHash ?: "~", detailHash ?: "~"
        ).joinToString("|")
        return PlanExecutionReceipt(
            plan.id, step.index, step.requestId, step.capability, reasonHash, inputHash,
            step.status, action?.status, action?.toolId, outputHash, detailHash,
            sha256(canonical), createdAtEpochMs
        )
    }

    private fun encodeClaim(claim: PlanSideEffectClaim): String = listOf(
        "AMPER_PLAN_CLAIM_V1", enc(claim.planId.value), claim.stepIndex.toString(), enc(claim.requestId.value),
        enc(claim.capability.value), enc(claim.toolId.value), claim.sideEffect.name,
        claim.reasonSha256, claim.inputSha256, claim.createdAtEpochMs.toString()
    ).joinToString("\t")

    private fun decodeClaim(content: String): Result<PlanSideEffectClaim> = runCatching {
        val p = content.split('\t')
        require(p.size == 10 && p[0] == "AMPER_PLAN_CLAIM_V1")
        PlanSideEffectClaim(
            planId = PlanId(dec(p[1])),
            stepIndex = p[2].toInt(),
            requestId = ActionRequestId(dec(p[3])),
            capability = CapabilityId(dec(p[4])),
            toolId = ToolId(dec(p[5])),
            sideEffect = ToolSideEffect.valueOf(p[6]),
            reasonSha256 = p[7],
            inputSha256 = p[8],
            createdAtEpochMs = p[9].toLong()
        )
    }

    private fun encodeReconciliation(value: PlanClaimReconciliation): String = listOf(
        "AMPER_PLAN_RECONCILIATION_V1",
        enc(value.planId.value),
        value.stepIndex.toString(),
        enc(value.requestId.value),
        enc(value.capability.value),
        enc(value.toolId.value),
        value.sideEffect.name,
        value.decision.name,
        enc(value.note),
        value.claimSha256,
        value.noteSha256,
        value.reconciliationSha256,
        value.createdAtEpochMs.toString()
    ).joinToString("\t")

    private fun decodeReconciliation(content: String): Result<PlanClaimReconciliation> = runCatching {
        val p = content.split('\t')
        require(p.size == 13 && p[0] == "AMPER_PLAN_RECONCILIATION_V1")
        PlanClaimReconciliation(
            planId = PlanId(dec(p[1])),
            stepIndex = p[2].toInt(),
            requestId = ActionRequestId(dec(p[3])),
            capability = CapabilityId(dec(p[4])),
            toolId = ToolId(dec(p[5])),
            sideEffect = ToolSideEffect.valueOf(p[6]),
            decision = PlanClaimDecision.valueOf(p[7]),
            note = dec(p[8]),
            claimSha256 = p[9],
            noteSha256 = p[10],
            reconciliationSha256 = p[11],
            createdAtEpochMs = p[12].toLong()
        )
    }

    private fun encodeReceipt(receipt: PlanExecutionReceipt): String = listOf(
        "AMPER_PLAN_RECEIPT_V1", enc(receipt.planId.value), receipt.stepIndex.toString(), enc(receipt.requestId.value),
        enc(receipt.capability.value), receipt.reasonSha256, receipt.inputSha256, receipt.stepStatus.name,
        receipt.actionStatus?.name ?: "~", receipt.toolId?.value?.let(::enc) ?: "~",
        receipt.outputSha256 ?: "~", receipt.detailSha256 ?: "~", receipt.receiptSha256,
        receipt.createdAtEpochMs.toString()
    ).joinToString("\t")

    private fun decodeReceipt(content: String): Result<PlanExecutionReceipt> = runCatching {
        val p = content.split('\t')
        require(p.size == 14 && p[0] == "AMPER_PLAN_RECEIPT_V1")
        PlanExecutionReceipt(
            planId = PlanId(dec(p[1])), stepIndex = p[2].toInt(), requestId = ActionRequestId(dec(p[3])),
            capability = CapabilityId(dec(p[4])), reasonSha256 = p[5], inputSha256 = p[6],
            stepStatus = PlanStepStatus.valueOf(p[7]),
            actionStatus = p[8].takeUnless { it == "~" }?.let(ActionStatus::valueOf),
            toolId = p[9].takeUnless { it == "~" }?.let { ToolId(dec(it)) },
            outputSha256 = p[10].takeUnless { it == "~" }, detailSha256 = p[11].takeUnless { it == "~" },
            receiptSha256 = p[12], createdAtEpochMs = p[13].toLong()
        )
    }

    private fun parseSideEffect(detail: String?): ToolSideEffect? = detail?.let { text ->
        ToolSideEffect.entries.firstOrNull { text.startsWith("${it.name} action requires explicit approval") }
    }

    private fun claimRecordId(planId: PlanId, requestId: ActionRequestId) = MemoryId("plan-claim:${planId.value}:${requestId.value}")
    private fun receiptRecordId(planId: PlanId, requestId: ActionRequestId) = MemoryId("plan-receipt:${planId.value}:${requestId.value}")
    private fun reconciliationRecordId(planId: PlanId, requestId: ActionRequestId) =
        MemoryId("plan-reconciliation:${planId.value}:${requestId.value}")
    private fun PlanStepStatus.isActive() = this == PlanStepStatus.PLANNED || this == PlanStepStatus.REQUIRES_CONFIRMATION
    private fun PlanStepStatus.isTerminal() = !isActive()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun enc(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun dec(value: String) = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    companion object {
        const val CLAIM_KIND = "sovereign-plan-claim-v1"
        const val RECEIPT_KIND = "sovereign-plan-receipt-v1"
        const val RECONCILIATION_KIND = "sovereign-plan-reconciliation-v1"
    }
}
