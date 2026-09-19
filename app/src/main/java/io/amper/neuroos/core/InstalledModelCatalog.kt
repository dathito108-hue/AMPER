package io.amper.neuroos.core

data class InstalledModel(
    val descriptor: ModelDescriptor,
    val displayName: String,
    val locator: String,
    val lengthBytes: Long?,
    val sha256: String,
    val ggufVersion: Long,
    val tensorCount: ULong,
    val metadataKeyValueCount: ULong,
    val installedAtEpochMs: Long = System.currentTimeMillis()
)

interface InstalledModelCatalog {
    fun put(model: InstalledModel)
    fun get(id: ModelId): InstalledModel?
    fun list(): List<InstalledModel>
    fun remove(id: ModelId): Boolean
}

/** Model registry surface that supports explicit user-driven detach from live routing. */
interface MutableModelRegistry : ModelRegistry {
    fun unregister(id: ModelId): Boolean
}

class InMemoryInstalledModelCatalog : InstalledModelCatalog {
    private val models = linkedMapOf<ModelId, InstalledModel>()

    @Synchronized
    override fun put(model: InstalledModel) {
        models[model.descriptor.id] = model
    }

    @Synchronized
    override fun get(id: ModelId): InstalledModel? = models[id]

    @Synchronized
    override fun list(): List<InstalledModel> = models.values.toList()

    @Synchronized
    override fun remove(id: ModelId): Boolean = models.remove(id) != null
}

/**
 * Safely detaches one user-installed model from AMPER without touching the user-owned artifact.
 *
 * Runtime backend state is unloaded first. The model is then removed from live routing before the
 * durable catalog entry is removed. If live removal or durable catalog publication fails, the live
 * descriptor is registered again so the process remains consistent with the still-persisted catalog.
 * The source GGUF locator is never opened, deleted, rewritten, or permission-revoked by this service.
 */
class InstalledModelDetachService(
    private val catalog: InstalledModelCatalog,
    private val registry: MutableModelRegistry,
    private val unloadRuntime: (ModelId) -> Result<Unit>
) {
    @Synchronized
    fun detach(id: ModelId): Result<InstalledModel> = runCatching {
        val current = requireNotNull(catalog.get(id)) {
            "installed model not found: ${id.value}"
        }

        unloadRuntime(id).getOrThrow()
        try {
            require(registry.unregister(id)) {
                "installed model is missing from live registry: ${id.value}"
            }
        } catch (registryFailure: Throwable) {
            try {
                registry.register(current.descriptor)
            } catch (rollbackFailure: Throwable) {
                registryFailure.addSuppressed(rollbackFailure)
            }
            throw registryFailure
        }

        try {
            require(catalog.remove(id)) {
                "installed model disappeared from catalog during detach: ${id.value}"
            }
        } catch (catalogFailure: Throwable) {
            try {
                registry.register(current.descriptor)
            } catch (rollbackFailure: Throwable) {
                catalogFailure.addSuppressed(rollbackFailure)
            }
            throw catalogFailure
        }

        current
    }
}

/**
 * Reclassifies an already-installed user GGUF without reopening or rewriting its artifact.
 *
 * Only the descriptor capability set may change. Artifact locator, digest, parsed GGUF identity,
 * installation time and model id remain byte-for-byte metadata-identical. The durable catalog is
 * published first; the live registry is then replaced by id. If the live registry rejects the
 * update, the catalog is rolled back to the original record before the failure is surfaced.
 *
 * This gives models imported before Phase 111 a safe path out of over-claimed specialist metadata
 * without forcing the user to select and hash the GGUF again.
 */
class InstalledModelCapabilityService(
    private val catalog: InstalledModelCatalog,
    private val modelRegistry: ModelRegistry
) {
    @Synchronized
    fun reclassify(
        id: ModelId,
        profile: ModelCapabilityProfile
    ): Result<InstalledModel> = runCatching {
        require(!id.value.startsWith("amper-native-")) {
            "AMPER-native capabilities are governed by native runtime admission"
        }
        val current = requireNotNull(catalog.get(id)) {
            "installed model not found: ${id.value}"
        }
        require(current.descriptor.local) {
            "only local installed models can be reclassified"
        }
        require(current.descriptor.format.equals("gguf", ignoreCase = true)) {
            "only installed GGUF models can be reclassified"
        }

        val capabilities = profile.capabilities
        if (current.descriptor.capabilities == capabilities) return@runCatching current

        val updated = current.copy(
            descriptor = current.descriptor.copy(capabilities = capabilities)
        )
        catalog.put(updated)
        try {
            modelRegistry.register(updated.descriptor)
        } catch (registryFailure: Throwable) {
            try {
                catalog.put(current)
            } catch (rollbackFailure: Throwable) {
                registryFailure.addSuppressed(rollbackFailure)
            }
            throw registryFailure
        }
        updated
    }
}

class LocalModelInstallService(
    private val inspector: GgufInspector,
    private val catalog: InstalledModelCatalog,
    private val modelRegistry: ModelRegistry
) {
    fun install(
        id: ModelId,
        source: ModelArtifactSource,
        capabilities: Set<CapabilityId>
    ): Result<InstalledModel> = runCatching {
        require(id.value.isNotBlank())
        require(capabilities.isNotEmpty()) { "at least one capability is required" }
        val inspection = inspector.inspect(source).getOrThrow()
        val descriptor = ModelDescriptor(
            id = id,
            format = "gguf",
            capabilities = capabilities,
            local = true
        )
        val model = InstalledModel(
            descriptor = descriptor,
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount
        )
        catalog.put(model)
        modelRegistry.register(descriptor)
        model
    }
}