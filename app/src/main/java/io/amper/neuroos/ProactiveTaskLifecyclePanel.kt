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
import io.amper.neuroos.core.SovereignPlan
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleCoordinator
import io.amper.neuroos.core.v2.AmperAgentTaskState

/**
 * Phase662 user-visible read-only proactive lifecycle surface.
 *
 * It never advances, approves, rejects, schedules, or executes a task. Opening a plan delegates to
 * the existing governed plan console, where exact side-effect approval remains unchanged.
 */
@Composable
fun ProactiveTaskLifecyclePanel(
    lifecycle: AmperAgentProactiveTaskLifecycleCoordinator,
    notificationsAllowed: Boolean,
    notificationPermissionRequired: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpen: (SovereignPlan) -> Unit
) {
    var refreshEpoch by remember(lifecycle) { mutableStateOf(0) }
    val snapshot = remember(lifecycle, refreshEpoch) {
        runCatching { lifecycle.inspect(limit = 8) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Proactive Agent Lifecycle", style = MaterialTheme.typography.titleMedium)
        Text(
            "Read-only trigger → task → canonical plan provenance. " +
                "Task state is derived from persistent plan state, not a parallel task database."
        )
        Button(onClick = { refreshEpoch += 1 }) {
            Text("Refresh proactive tasks")
        }

        when {
            notificationsAllowed ->
                Text("Proactive attention notifications: enabled")
            notificationPermissionRequired -> {
                Text(
                    "Proactive attention notifications are optional and currently not permitted. " +
                        "Tasks and governed approval continue to work inside AMPER."
                )
                Button(onClick = onRequestNotificationPermission) {
                    Text("Enable proactive notifications")
                }
            }
            else ->
                Text(
                    "Proactive attention notifications are disabled by Android settings. " +
                        "The lifecycle panel remains the governed attention fallback."
                )
        }

        val entries = snapshot.getOrNull()
        if (entries == null) {
            val error = snapshot.exceptionOrNull()
            Text(
                "Lifecycle provenance unavailable; proactive execution remains fail-closed: " +
                    (error?.message ?: error?.javaClass?.simpleName ?: "unknown")
            )
        } else {
            if (entries.isEmpty()) {
                Text("No tracked proactive task")
            }

            entries.forEach { view ->
                val binding = view.binding
                Text(
                    "Source ${binding.sourceId} · plan ${binding.planId.value.takeLast(12)}"
                )
                Text(
                    "Trigger ${binding.trigger.observedAtEpochMs} · " +
                        "payload ${binding.trigger.payloadDigest.take(12)}…"
                )
                if (!view.planAvailable) {
                    Text("Fail-closed: canonical persistent plan is unavailable")
                } else {
                    val state = requireNotNull(view.taskState)
                    Text(
                        "State: ${state.name} · ${view.completedSteps}/${view.totalSteps} step(s)"
                    )
                    Text("Goal: ${view.goal}")
                    view.waitingApprovalStepIndex?.let { step ->
                        Text(
                            "Governed approval required at step $step. Open the canonical plan to inspect " +
                                "the exact request and approve or reject it."
                        )
                    }
                    if (state == AmperAgentTaskState.CHECKPOINTED) {
                        Text("Ready for governed EVENT_WAKE continuation; manual step advance is disabled.")
                    }
                    if (view.terminal) {
                        Text("Terminal durable plan; no background execution is requested.")
                    }
                    Button(
                        onClick = {
                            lifecycle.openPlan(binding.planId)?.let(onOpen)
                        }
                    ) {
                        Text("Open governed plan")
                    }
                }
            }
        }
    }
}
