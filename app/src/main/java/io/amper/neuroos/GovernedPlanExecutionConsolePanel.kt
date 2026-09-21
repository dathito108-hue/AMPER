package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.PlanDurabilityEvidence
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanExecutionInspector
import io.amper.neuroos.core.SovereignPlanReceiptLedger

/**
 * Phase 128 read-only execution console for a persisted sovereign plan.
 *
 * Rendering reads plan state and durable evidence only. It never invokes a tool and
 * never creates a claim, receipt, reconciliation, approval, or plan transition.
 * Mutating actions remain explicit callbacks owned by MainActivity/runtime code.
 */
@Composable
fun GovernedPlanExecutionConsolePanel(
    plan: SovereignPlan,
    receiptLedger: SovereignPlanReceiptLedger?,
    manualAdvanceEnabled: Boolean = true,
    manualAdvanceDisabledReason: String? = null,
    onAdvance: () -> Unit,
    onApprove: (Int) -> Unit,
    onReject: (Int) -> Unit
) {
    val view = SovereignPlanExecutionInspector(receiptLedger).inspect(plan)
    val pending = view.steps.firstOrNull { it.status == PlanStepStatus.REQUIRES_CONFIRMATION }
    val capabilities = view.planningSelectedCapabilities
        .sortedBy { it.value }
        .joinToString(" · ") { it.value }
        .ifBlank { "none/legacy" }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Governed Plan Execution Console", style = MaterialTheme.typography.titleLarge)
        Text("Plan ${view.planId.value.take(8)} · ${if (view.complete) "complete" else "active"}")
        Text("Goal: ${view.goal}")

        Text("Planner provenance", style = MaterialTheme.typography.titleMedium)
        Text("Backend: ${view.planningBackendId}")
        Text("Model: ${view.planningModelId?.value ?: "unknown/legacy"}")
        Text("Selected capabilities: $capabilities")
        Text("Receipt ledger: ${if (view.receiptLedgerAvailable) "available" else "unavailable"}")
        if (view.recoveryRequired) {
            Text("Recovery required: an unresolved durable side-effect claim exists; use Recovery Console before replaying it.")
        }

        Text("Execution steps", style = MaterialTheme.typography.titleMedium)
        view.steps.forEach { step ->
            Text("Step ${step.index} · ${step.status}")
            Text("Capability: ${step.capability.value}")
            Text("Reason: ${step.reason}")
            Text("Request: ${step.shortRequestId}")
            Text("Bound tool: ${step.boundToolId?.value ?: "unbound/legacy"}")
            Text("Bound side-effect: ${step.boundSideEffect?.name ?: "unbound/legacy"}")
            Text("Durable evidence: ${step.durabilityLabel}")
            step.receiptSha256?.let { receipt ->
                Text("Receipt: ${receipt.take(12)}…")
            }
            step.reconciliationDecision?.let { decision ->
                Text("Reconciliation: ${decision.name}")
            }
        }

        Button(
            enabled = manualAdvanceEnabled && !view.complete && pending == null,
            onClick = onAdvance
        ) {
            Text("Advance one step")
        }
        if (!manualAdvanceEnabled && !view.complete) {
            manualAdvanceDisabledReason?.let { Text(it) }
        }

        pending?.let { step ->
            Text("Approval checkpoint", style = MaterialTheme.typography.titleMedium)
            Text("Step ${step.index} · ${step.capability.value}")
            Text("Reason: ${step.reason}")
            Text("Exact request ID: ${step.requestId.value}")
            Text("Exact input:")
            Text(step.input)
            Text("Exact bound tool: ${step.boundToolId?.value ?: "unbound/legacy"}")
            Text("Bound side-effect class: ${step.boundSideEffect?.name ?: "unbound/legacy"}")
            Text("Durable evidence before approval: ${step.durabilityLabel}")
            if (step.durabilityEvidence == PlanDurabilityEvidence.CLAIMED_UNRESOLVED) {
                Text("This request already has an unresolved durable claim. Do not replay it; reconcile it in Recovery Console.")
            }
            Text("Nothing is executed by this console. Approve is an explicit runtime action and remains governed by the Authority Gate.")
            Button(onClick = { onApprove(step.index) }) {
                Text("Approve plan step")
            }
            Button(onClick = { onReject(step.index) }) {
                Text("Reject plan step")
            }
        }
    }
}
