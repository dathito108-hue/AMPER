package io.amper.neuroos.core

/**
 * Installable inference capability boundary.
 *
 * AMPER's sovereign kernel and Titan Cortex must compile and run without any
 * particular native runtime. A backend pack owns its vendor/native dependencies
 * and only contributes InferenceBackend implementations through this contract.
 */
interface TitanBackendPack {
    val id: String

    /**
     * Register zero or more usable backends. A failed pack is isolated and must
     * not prevent the sovereign runtime from starting.
     */
    fun attach(registry: InferenceBackendRegistry): Result<Int>
}

data class BackendPackStatus(
    val id: String,
    val attachedBackends: Int,
    val available: Boolean,
    val detail: String
)

class TitanBackendPackManager(
    private val registry: InferenceBackendRegistry
) {
    private val statuses = linkedMapOf<String, BackendPackStatus>()

    @Synchronized
    fun attach(pack: TitanBackendPack): BackendPackStatus {
        require(pack.id.isNotBlank())
        val status = pack.attach(registry).fold(
            onSuccess = { count ->
                require(count >= 0)
                BackendPackStatus(
                    id = pack.id,
                    attachedBackends = count,
                    available = count > 0,
                    detail = if (count > 0) "$count backend(s) attached" else "pack available but no backend attached"
                )
            },
            onFailure = { error ->
                BackendPackStatus(
                    id = pack.id,
                    attachedBackends = 0,
                    available = false,
                    detail = error.message ?: error::class.java.simpleName
                )
            }
        )
        statuses[pack.id] = status
        return status
    }

    @Synchronized
    fun status(id: String): BackendPackStatus? = statuses[id]

    @Synchronized
    fun list(): List<BackendPackStatus> = statuses.values.toList()
}
