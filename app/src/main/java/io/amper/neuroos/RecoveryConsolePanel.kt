package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.PlanClaimDecision
import io.amper.neuroos.core.RecoveryClaimItem
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignRecoveryConsole

/**
 * Manual recovery surface for interrupted side-effect claims.
 * There is intentionally no retry/replay control on this panel.
 */
@Composable
fun SovereignRecoveryConsolePanel(
    console: SovereignRecoveryConsole,
    onPlanUpdated: (SovereignPlan) -> Unit
) {
    val initial = remember(console) { console.pending() }
    var items by remember(console) { mutableStateOf(initial.getOrElse { emptyList() }) }
    var selectedRequestId by remember(console) {
        mutableStateOf(items.firstOrNull()?.claim?.requestId?.value)
    }
    var note by remember(console) { mutableStateOf("") }
    var status by remember(console) {
        mutableStateOf(
            initial.exceptionOrNull()?.let { "Recovery console blocked: ${it.message}" }
                ?: if (items.isEmpty()) "No unresolved side-effect claims"
                else "${items.size} unresolved side-effect claim(s) require manual verification"
        )
    }

    val selected = items.firstOrNull { it.claim.requestId.value == selectedRequestId }
        ?: items.firstOrNull()

    fun refreshClaims(message: String? = null) {
        console.pending().fold(
            onSuccess = { refreshed ->
                items = refreshed
                selectedRequestId = refreshed.firstOrNull()?.claim?.requestId?.value
                if (message != null) {
                    status = message
                } else {
                    status = if (refreshed.isEmpty()) {
                        "No unresolved side-effect claims"
                    } else {
                        "${refreshed.size} unresolved side-effect claim(s) require manual verification"
                    }
                }
            },
            onFailure = { error ->
                status = "Recovery console blocked: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    fun reconcile(item: RecoveryClaimItem, decision: PlanClaimDecision) {
        console.reconcile(item, decision, note).fold(
            onSuccess = { processed ->
                onPlanUpdated(processed.plan)
                note = ""
                val label = when (decision) {
                    PlanClaimDecision.CONFIRMED_EXECUTED -> "confirmed executed"
                    PlanClaimDecision.CONFIRMED_NOT_EXECUTED -> "confirmed not executed"
                }
                refreshClaims("Step ${processed.step.index} $label; provider was not replayed")
            },
            onFailure = { error ->
                status = "Reconciliation blocked: ${error.message ?: error::class.java.simpleName}"
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recovery Console", style = MaterialTheme.typography.titleLarge)
        Text("Interrupted side-effect claims are never replayed automatically.")
        Button(onClick = { refreshClaims() }) {
            Text("Refresh unresolved claims")
        }
        Text(status)

        items.forEach { item ->
            Button(onClick = { selectedRequestId = item.claim.requestId.value }) {
                Text(
                    "Claim ${item.claim.requestId.value.take(10)} · " +
                        "step ${item.claim.stepIndex} · ${item.claim.sideEffect}"
                )
            }
        }

        selected?.let { item ->
            Text("Plan: ${item.plan.id.value.take(12)}")
            Text("Step: ${item.step.index} — ${item.step.reason}")
            Text("Capability: ${item.claim.capability.value}")
            Text("Tool: ${item.claim.toolId.value}")
            Text("Side effect: ${item.claim.sideEffect}")
            Text("Request: ${item.claim.requestId.value}")
            Text("Verify the real-world/device state before choosing a resolution.")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(512) },
                label = { Text("Verification note (required)") }
            )
            Button(
                enabled = note.isNotBlank(),
                onClick = { reconcile(item, PlanClaimDecision.CONFIRMED_EXECUTED) }
            ) {
                Text("Mark confirmed executed")
            }
            Button(
                enabled = note.isNotBlank(),
                onClick = { reconcile(item, PlanClaimDecision.CONFIRMED_NOT_EXECUTED) }
            ) {
                Text("Mark confirmed not executed")
            }
            Text("Neither resolution invokes the tool provider or retries the old request.")
        }
    }
}
