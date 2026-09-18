package io.amper.neuroos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.TitanRouteObservation

@Composable
fun TitanRouteObservatoryPanel(
    observation: TitanRouteObservation?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Titan Route Observatory", style = MaterialTheme.typography.titleMedium)
        if (observation == null) {
            Text("No inference route has been planned in this process yet.")
            return@Column
        }

        val selected = observation.selectedModelId?.value ?: "none"
        val backend = observation.selectedBackendId ?: "none"
        val capabilities = observation.selectedCapabilities
            .map { it.value }
            .sorted()
            .joinToString(",")
            .ifBlank { "none" }

        Text("Purpose: ${observation.purpose.name}")
        Text("Selected: $selected · backend $backend")
        Text("Capabilities: $capabilities")
        Text("Selection: ${observation.selectionReason?.name ?: "FAILED"}")
        Text(
            "Workload: ${observation.workloadClass.promptBand.name}/" +
                observation.workloadClass.outputBand.name +
                " · resources ${observation.resourceCondition.name}"
        )
        observation.estimatedMemoryMb?.let { Text("Estimated execution memory: $it MiB") }
        observation.backendPolicyScore?.let { Text("Backend policy score: $it") }

        observation.preferredModelId?.let { preferred ->
            Text("Preferred model: ${preferred.value}")
            Text("Preferred outcome: ${observation.preferredModelOutcome() ?: "unknown"}")
        }

        observation.failure?.let { Text("Route failure: $it") }

        if (observation.rejected.isNotEmpty()) {
            Text("Rejected/deferred candidates:")
            observation.rejected.take(8).forEach { reason ->
                Text("• $reason")
            }
            if (observation.rejected.size > 8) {
                Text("… ${observation.rejected.size - 8} more")
            }
        }

        Text("Read-only diagnostics; routing and admission remain authoritative.")
    }
}
