package io.amper.neuroos.core

interface AmneKernelBackendProvider {
    fun create(): Result<AmneKernelBackend>
}

/**
 * Reflection boundary for the optional native AMNE package.
 *
 * Canonical builds remain independent of libamne. Operational builds may include the provider;
 * loading fails closed when the class/library/ABI or numerical qualification is unavailable.
 */
object OptionalAmneKernelLoader {
    const val DEFAULT_PROVIDER_CLASS =
        "io.amper.neuroos.backend.AmneNativeKernelProvider"

    fun load(
        className: String = DEFAULT_PROVIDER_CLASS
    ): Result<AmneKernelBackend> = runCatching {
        require(className.isNotBlank())
        val instance = Class.forName(className)
            .getDeclaredConstructor()
            .newInstance()
        require(instance is AmneKernelBackendProvider) {
            "$className is not an AMNE kernel provider"
        }
        instance.create().getOrThrow()
    }
}
