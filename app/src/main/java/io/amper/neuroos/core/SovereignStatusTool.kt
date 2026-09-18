package io.amper.neuroos.core

data class SovereignBackendStatus(
    val id: String,
    val state: String,
    val hardwareAcceleration: Boolean
)

data class SovereignStatusSnapshot(
    val models: List<String>,
    val backends: List<SovereignBackendStatus>,
    val budget: ResourceBudget
)

fun interface SovereignStatusSource {
    fun snapshot(): SovereignStatusSnapshot
}

class RuntimeSovereignStatusSource(
    private val catalog: InstalledModelCatalog,
    private val backends: InferenceBackendRegistry,
    private val governor: ResourceGovernor
) : SovereignStatusSource {
    override fun snapshot(): SovereignStatusSnapshot = SovereignStatusSnapshot(
        models = catalog.list()
            .sortedBy { it.displayName.lowercase() }
            .take(8)
            .map { "${it.displayName}:${it.descriptor.id.value}" },
        backends = backends.list()
            .sortedBy { it.id }
            .take(8)
            .map { backend ->
                val health = (backend as? ManagedInferenceBackend)?.health()
                SovereignBackendStatus(
                    id = backend.id,
                    state = health?.state?.name ?: "AVAILABLE",
                    hardwareAcceleration = health?.hardwareAcceleration ?: false
                )
            },
        budget = governor.currentBudget()
    )
}

object SovereignStatusToolContract {
    val capability = CapabilityId("sovereign.status.read")
    val toolId = ToolId("sovereign-status")
}

class SovereignStatusToolProvider(
    private val source: SovereignStatusSource
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = SovereignStatusToolContract.toolId,
        name = "AMPER sovereign runtime status",
        capability = SovereignStatusToolContract.capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "Read AMPER models, inference backends or current resource budget",
            acceptedValues = setOf("summary", "models", "backends", "resources"),
            maxLength = 16
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val request = input.trim().lowercase().ifBlank { "summary" }
        require(request in setOf("summary", "models", "backends", "resources")) {
            "sovereign.status.read accepts summary/models/backends/resources"
        }
        val status = source.snapshot()
        val modelText = status.models.take(8).joinToString(",").ifBlank { "none" }
        val backendText = status.backends.take(8)
            .joinToString(",") { "${it.id}:${it.state}:hw=${it.hardwareAcceleration}" }
            .ifBlank { "none" }
        when (request) {
            "models" -> "models=$modelText"
            "backends" -> "backends=$backendText"
            "resources" -> "memory_budget_mb=${status.budget.memoryMb};thermal_class=${status.budget.thermalClass};max_agents=${status.budget.maxConcurrentAgents}"
            else -> buildString {
                append("models=$modelText")
                append(";backends=$backendText")
                append(";memory_budget_mb=${status.budget.memoryMb}")
                append(";thermal_class=${status.budget.thermalClass}")
                append(";max_agents=${status.budget.maxConcurrentAgents}")
            }
        }
    }
}
