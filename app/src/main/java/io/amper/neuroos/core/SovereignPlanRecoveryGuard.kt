package io.amper.neuroos.core

data class ApprovedToolBinding(
    val toolId: ToolId,
    val sideEffect: ToolSideEffect
)

/**
 * Revalidates persisted plans against the live tool registry before any recovered
 * step may execute. This closes the gap between plan creation time and a later
 * process restart where providers, contracts or side-effect classifications may
 * have changed.
 */
class SovereignPlanRecoveryGuard(
    private val actions: SovereignActionLoop,
    private val advertisedCapabilities: Set<CapabilityId>
) {
    init { require(advertisedCapabilities.isNotEmpty()) }

    fun verifyAdvance(plan: SovereignPlan): Result<Unit> = runCatching {
        verifyShape(plan)
        val next = plan.steps.firstOrNull { it.isActive() } ?: return@runCatching
        val descriptor = liveDescriptor(next)
        verifyInput(next, descriptor)
        when (next.status) {
            PlanStepStatus.PLANNED -> verifyPlanBinding(next, descriptor, required = true)
            PlanStepStatus.REQUIRES_CONFIRMATION -> {
                verifyPlanBinding(next, descriptor, required = false)
                verifyApprovalBinding(next, descriptor)
            }
            else -> Unit
        }
    }

    fun verifyApproval(plan: SovereignPlan, stepIndex: Int): Result<Unit> =
        approvalBinding(plan, stepIndex).map { Unit }

    /**
     * Returns the exact tool identity and side-effect class recorded at the approval
     * checkpoint after verifying it still matches the live registry and contract.
     * The caller must carry this binding into BoundToolFabric execution.
     */
    fun approvalBinding(plan: SovereignPlan, stepIndex: Int): Result<ApprovedToolBinding> = runCatching {
        verifyShape(plan)
        val step = plan.steps.single { it.index == stepIndex }
        require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            "plan step $stepIndex is not awaiting approval"
        }
        val firstActive = plan.steps.firstOrNull { it.isActive() }
        require(firstActive?.index == stepIndex) {
            "approval replay rejected: step $stepIndex is not the next active step"
        }
        val descriptor = liveDescriptor(step)
        verifyInput(step, descriptor)
        verifyPlanBinding(step, descriptor, required = false)
        verifyApprovalBinding(step, descriptor)
    }

    private fun verifyShape(plan: SovereignPlan) {
        require(plan.steps.map { it.requestId }.distinct().size == plan.steps.size) {
            "plan contains duplicate action request ids"
        }
        require(plan.steps.count { it.status == PlanStepStatus.REQUIRES_CONFIRMATION } <= 1) {
            "plan contains multiple simultaneous approval checkpoints"
        }

        var activeSeen = false
        plan.steps.forEach { step ->
            if (step.isActive()) {
                activeSeen = true
            } else if (activeSeen) {
                error("plan state ordering is invalid: terminal step follows an active step")
            }
        }
        val pending = plan.steps.firstOrNull { it.status == PlanStepStatus.REQUIRES_CONFIRMATION }
        if (pending != null) {
            val firstActive = plan.steps.firstOrNull { it.isActive() }
            require(firstActive?.index == pending.index) {
                "approval checkpoint is not the next active step"
            }
        }
    }

    private fun liveDescriptor(step: SovereignPlanStep): ToolDescriptor {
        require(step.capability in advertisedCapabilities) {
            "recovered capability ${step.capability.value} is no longer whitelisted"
        }
        return actions.descriptorFor(step.capability)
            ?: error("recovered capability ${step.capability.value} has no live provider")
    }

    private fun verifyInput(step: SovereignPlanStep, descriptor: ToolDescriptor) {
        val contract = descriptor.inputContract
        require(step.input.length <= contract.maxLength) {
            "recovered input exceeds current contract for ${step.capability.value}"
        }
        if (contract.acceptedValues.isNotEmpty()) {
            val accepted = contract.acceptedValues.map { it.trim().lowercase() }.toSet()
            require(step.input.trim().lowercase() in accepted) {
                "recovered input is outside current contract for ${step.capability.value}"
            }
        }
    }

    private fun verifyPlanBinding(
        step: SovereignPlanStep,
        descriptor: ToolDescriptor,
        required: Boolean
    ) {
        val expectedTool = step.boundToolId
        val expectedSideEffect = step.boundSideEffect
        if (expectedTool == null && expectedSideEffect == null) {
            require(!required) {
                "recovered planned step has no plan-time tool binding; recreate plan"
            }
            return
        }
        require(expectedTool != null && expectedSideEffect != null) {
            "plan-time tool binding is incomplete"
        }
        require(descriptor.id == expectedTool) {
            "planned tool provider changed from ${expectedTool.value} to ${descriptor.id.value}"
        }
        require(descriptor.sideEffect == expectedSideEffect) {
            "planned tool side-effect changed from $expectedSideEffect to ${descriptor.sideEffect}"
        }
    }

    private fun verifyApprovalBinding(
        step: SovereignPlanStep,
        descriptor: ToolDescriptor
    ): ApprovedToolBinding {
        val outcome = requireNotNull(step.outcome) {
            "approval checkpoint has no recorded governed outcome"
        }
        require(outcome.status == ActionStatus.REQUIRES_CONFIRMATION) {
            "approval checkpoint outcome is not REQUIRES_CONFIRMATION"
        }
        val expectedTool = requireNotNull(outcome.toolId) {
            "approval checkpoint has no bound tool id"
        }
        require(descriptor.id == expectedTool) {
            "approval replay rejected: tool provider changed from ${expectedTool.value} to ${descriptor.id.value}"
        }
        val legacySideEffect = outcome.detail?.let(::sideEffectFromDetail)
        val expectedSideEffect = outcome.sideEffect
            ?: legacySideEffect
            ?: error("approval checkpoint has no bound side-effect class")
        if (outcome.sideEffect != null && legacySideEffect != null) {
            require(outcome.sideEffect == legacySideEffect) {
                "approval checkpoint typed side-effect conflicts with legacy detail"
            }
        }
        require(descriptor.sideEffect == expectedSideEffect) {
            "approval replay rejected: side-effect changed from $expectedSideEffect to ${descriptor.sideEffect}"
        }
        require(descriptor.sideEffect != ToolSideEffect.READ_ONLY) {
            "approval checkpoint unexpectedly resolves to a read-only tool"
        }
        require(outcome.proposal?.requestId == step.requestId) {
            "approval checkpoint request id binding changed"
        }
        step.boundToolId?.let { boundTool ->
            require(boundTool == expectedTool) {
                "approval checkpoint tool binding conflicts with plan-time binding"
            }
        }
        step.boundSideEffect?.let { boundSideEffect ->
            require(boundSideEffect == expectedSideEffect) {
                "approval checkpoint side-effect conflicts with plan-time binding"
            }
        }
        return ApprovedToolBinding(expectedTool, expectedSideEffect)
    }

    private fun sideEffectFromDetail(detail: String): ToolSideEffect? = ToolSideEffect.entries
        .firstOrNull { detail.startsWith("${it.name} action requires explicit approval") }

    private fun SovereignPlanStep.isActive(): Boolean =
        status == PlanStepStatus.PLANNED || status == PlanStepStatus.REQUIRES_CONFIRMATION
}
