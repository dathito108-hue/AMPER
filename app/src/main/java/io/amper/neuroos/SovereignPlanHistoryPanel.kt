package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.PlanId
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.SovereignPlanHistory

/**
 * User-facing browser and lifecycle surface for persisted sovereign plans.
 *
 * Browsing/opening is read-only. Cancellation is deliberately two-step and delegates
 * to the core Plan OS cancellation service; it never invokes a provider or creates a
 * side-effect claim, and durable claim evidence blocks cancellation fail-closed.
 */
@Composable
fun SovereignPlanHistoryPanel(
    history: SovereignPlanHistory,
    activePlanId: PlanId?,
    onOpen: (SovereignPlan) -> Unit,
    onPlanMutated: (SovereignPlan) -> Unit = {}
) {
    val entries = history.recent(limit = 8)
    var cancellationArmedPlanId by remember(history) { mutableStateOf<PlanId?>(null) }
    var lifecycleStatus by remember(history) { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Persistent Plan History", style = MaterialTheme.typography.titleMedium)
        Text("Open a saved plan to inspect or resume it. Opening history does not execute any step.")

        if (entries.isEmpty()) {
            Text("No saved sovereign plans yet")
        }

        entries.forEach { entry ->
            val current = activePlanId == entry.planId
            val state = if (entry.complete) {
                "complete"
            } else {
                "active · next step ${entry.nextActiveStepIndex ?: "unknown"}"
            }
            Text("Plan ${entry.planId.value.take(8)} · $state · ${entry.stepCount} step(s)")
            Text("Goal: ${entry.goal}")
            Text("Planner: ${entry.planningBackendId} · ${entry.planningModelId?.value ?: "unknown/legacy"}")
            if (entry.recoveryRequired) {
                Text("Recovery required: unresolved durable side-effect claim")
            }
            if (entry.cancellationBlockedByDurableClaim && !entry.recoveryRequired) {
                Text("Cancellation blocked: active durable claim state must reach its terminal recovered snapshot first")
            }
            Button(
                enabled = !current,
                onClick = {
                    cancellationArmedPlanId = null
                    history.open(entry.planId)?.let(onOpen)
                }
            ) {
                Text(if (current) "Currently open" else "Open / resume plan")
            }

            if (current && !entry.complete) {
                when {
                    entry.cancellationBlockedByDurableClaim -> {
                        Text("Cancel remaining is unavailable while an active step has durable claim evidence. Use Recovery Console first.")
                    }
                    cancellationArmedPlanId != entry.planId -> {
                        Button(onClick = { cancellationArmedPlanId = entry.planId }) {
                            Text("Review cancel remaining")
                        }
                    }
                    else -> {
                        val remaining = entry.nextActiveStepIndex
                            ?.let { entry.stepCount - it + 1 }
                            ?.coerceAtLeast(1)
                            ?: 1
                        Text(
                            "Cancel $remaining remaining step(s)? They will be marked REJECTED and terminally receipted. " +
                                "No provider will be invoked and no side-effect claim will be created."
                        )
                        Button(
                            onClick = {
                                history.cancelRemaining(entry.planId).fold(
                                    onSuccess = { result ->
                                        cancellationArmedPlanId = null
                                        lifecycleStatus =
                                            "Cancelled ${result.cancelledStepIndices.size} remaining step(s) in plan ${entry.planId.value.take(8)}"
                                        onPlanMutated(result.plan)
                                        onOpen(result.plan)
                                    },
                                    onFailure = { error ->
                                        cancellationArmedPlanId = null
                                        lifecycleStatus =
                                            "Plan cancellation blocked: ${error.message ?: error::class.java.simpleName}"
                                    }
                                )
                            }
                        ) {
                            Text("Confirm cancel remaining steps")
                        }
                        Button(onClick = { cancellationArmedPlanId = null }) {
                            Text("Keep plan active")
                        }
                    }
                }
            }
        }

        if (lifecycleStatus.isNotBlank()) {
            Text(lifecycleStatus)
        }
    }
}
