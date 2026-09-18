package io.amper.neuroos.core

/**
 * Discovers a backend pack only when that pack was deliberately packaged into
 * the APK. Canonical builds therefore have no compile-time dependency on it.
 */
object OptionalBackendPackLoader {
    fun attach(className: String, manager: TitanBackendPackManager): BackendPackStatus {
        require(className.isNotBlank())
        return runCatching {
            val instance = Class.forName(className).getDeclaredConstructor().newInstance()
            require(instance is TitanBackendPack) { "$className is not a TitanBackendPack" }
            manager.attach(instance)
        }.getOrElse { error ->
            BackendPackStatus(
                id = className,
                attachedBackends = 0,
                available = false,
                detail = "optional pack not available: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }
}
