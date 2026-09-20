package io.amper.neuroos.core

data class AmperCoreFoundationState(
    val activeModelId: ModelId?,
    val retainedSourceCount: Int
)

/**
 * Enforces AMPER's single logical inference foundation.
 *
 * The durable catalog may retain multiple imported weight sources so the user never loses source
 * lineage, but the live ModelRegistry contains at most one descriptor. Titan therefore cannot route
 * between multiple model identities: every inference request belongs to the single active AMPER core.
 */
class AmperSingleCoreFoundationController(
    private val catalog: InstalledModelCatalog,
    private val registry: MutableModelRegistry
) {
    @Synchronized
    fun restore(
        requested: ModelId? = null
    ): AmperCoreFoundationState {
        val sources = catalog.list()
        val selected = requested
            ?.let(catalog::get)
            ?: sources.maxByOrNull { it.installedAtEpochMs }

        sources.forEach { registry.unregister(it.descriptor.id) }
        selected?.let { registry.register(it.descriptor) }

        return AmperCoreFoundationState(
            activeModelId = selected?.descriptor?.id,
            retainedSourceCount = sources.size
        )
    }

    @Synchronized
    fun activate(
        modelId: ModelId
    ): InstalledModel {
        val selected = requireNotNull(catalog.get(modelId)) {
            "AMPER core foundation source is not installed: ${modelId.value}"
        }
        catalog.list().forEach { source ->
            if (source.descriptor.id != selected.descriptor.id) {
                registry.unregister(source.descriptor.id)
            }
        }
        registry.register(selected.descriptor)
        return selected
    }

    @Synchronized
    fun deactivate(): AmperCoreFoundationState {
        val sources = catalog.list()
        sources.forEach { registry.unregister(it.descriptor.id) }
        return AmperCoreFoundationState(
            activeModelId = null,
            retainedSourceCount = sources.size
        )
    }
}


/**
 * Detaches an imported weight source without assuming every retained source is live in ModelRegistry.
 * Only the single active foundation may be registered at runtime.
 */
class AmperSingleCoreSourceDetachService(
    private val catalog: InstalledModelCatalog,
    private val registry: MutableModelRegistry,
    private val unloadRuntime: (ModelId) -> Result<Unit>
) {
    @Synchronized
    fun detach(id: ModelId): Result<InstalledModel> = runCatching {
        val current = requireNotNull(catalog.get(id)) {
            "imported AMPER weight source not found: ${id.value}"
        }

        unloadRuntime(id).getOrThrow()
        val wasLive = registry.unregister(id)

        try {
            require(catalog.remove(id)) {
                "weight source disappeared during detach: ${id.value}"
            }
        } catch (catalogFailure: Throwable) {
            if (wasLive) {
                try {
                    registry.register(current.descriptor)
                } catch (rollbackFailure: Throwable) {
                    catalogFailure.addSuppressed(rollbackFailure)
                }
            }
            throw catalogFailure
        }

        current
    }
}
