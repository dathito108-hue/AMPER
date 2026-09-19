package io.amper.neuroos.core

import java.security.MessageDigest

enum class NativeRuntimeActivationStatus {
    ACTIVE,
    ROLLED_BACK
}

data class NativeRuntimeActivation(
    val checkpointId: NativeCheckpointId,
    val baselineCheckpointId: NativeCheckpointId?,
    val modelId: ModelId,
    val installedModel: InstalledModel,
    val status: NativeRuntimeActivationStatus,
    val activatedAtEpochMs: Long,
    val rolledBackAtEpochMs: Long? = null
) {
    init {
        require(installedModel.descriptor.id == modelId)
        require(activatedAtEpochMs >= 0L)
        when (status) {
            NativeRuntimeActivationStatus.ACTIVE ->
                require(rolledBackAtEpochMs == null)
            NativeRuntimeActivationStatus.ROLLED_BACK ->
                requireNotNull(rolledBackAtEpochMs).also {
                    require(it >= activatedAtEpochMs)
                }
        }
    }

    val liveRegistered: Boolean
        get() = status == NativeRuntimeActivationStatus.ACTIVE

    val authorityBearing: Boolean
        get() = false
}

/**
 * Phase221-225 converts an objectively promotable AMPER-native checkpoint into a live Titan model.
 *
 * Phase221 recomputes promotion eligibility from [NativeTrainingPipeline] rather than trusting a
 * caller-provided promotion flag.
 *
 * Phase222 requires a descriptor-bound GGUF source and inspects the exact bytes. The artifact SHA-256
 * must equal the immutable checkpoint weight digest.
 *
 * Phase223 derives the live model descriptor from the AMPER-owned model contract, never from caller
 * capability claims.
 *
 * Phase224 publishes catalog + ModelRegistry atomically with rollback on registry failure.
 *
 * Phase225 supports governed runtime rollback through the existing installed-model detach path.
 * Activation changes model routing availability only; it does not grant tool/device authority.
 */
class NativeCheckpointRuntimePromotionService(
    private val foundation: NativeModelFoundation,
    private val training: NativeTrainingPipeline,
    private val catalog: InstalledModelCatalog,
    private val registry: MutableModelRegistry,
    private val inspector: GgufInspector = GgufInspector(),
    private val unloadRuntime: (ModelId) -> Result<Unit> = { Result.success(Unit) },
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun activate(
        checkpointId: NativeCheckpointId,
        source: ModelArtifactSource,
        baselineCheckpointId: NativeCheckpointId? = null
    ): Result<NativeRuntimeActivation> = runCatching {
        require(source is DescriptorBoundNativeModelPathSource) {
            "AMPER-native live activation requires a descriptor-bound model source"
        }

        val promotion = training.promotionCandidate(
            candidateCheckpointId = checkpointId,
            baselineCheckpointId = baselineCheckpointId
        )
        require(promotion.promotable) {
            "native checkpoint is not objectively promotable"
        }

        val checkpoint = requireNotNull(foundation.getCheckpoint(checkpointId)) {
            "native checkpoint lineage missing"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId)) {
            "native model contract missing"
        }
        require(contract.ownedByAmper) {
            "only an AMPER-owned model contract may enter native runtime promotion"
        }
        require(contract.canonicalDigest == checkpoint.contractDigest) {
            "native checkpoint contract digest mismatch"
        }

        val inspection = inspector.inspect(source).getOrThrow()
        require(inspection.sha256 == checkpoint.weightArtifactSha256) {
            "runtime GGUF SHA-256 does not match promoted checkpoint weights"
        }
        require(inspection.header.supported) {
            "runtime GGUF version is not supported"
        }

        val modelId = modelIdFor(checkpointId)
        val descriptor = ModelDescriptor(
            id = modelId,
            format = "gguf",
            capabilities = NativeRuntimeCapabilityAdmission.baseCapabilities(
                contract.capabilities
            ),
            local = true
        )
        val installed = InstalledModel(
            descriptor = descriptor,
            displayName = source.displayName,
            locator = source.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount,
            installedAtEpochMs = clock()
        )

        val existing = catalog.get(modelId)
        if (existing != null) {
            require(sameRuntimeIdentity(existing, installed)) {
                "AMPER-native model id is already bound to different artifact identity"
            }
            registry.register(existing.descriptor)
            return@runCatching NativeRuntimeActivation(
                checkpointId = checkpointId,
                baselineCheckpointId = baselineCheckpointId,
                modelId = modelId,
                installedModel = existing,
                status = NativeRuntimeActivationStatus.ACTIVE,
                activatedAtEpochMs = existing.installedAtEpochMs
            )
        }

        catalog.put(installed)
        try {
            registry.register(descriptor)
        } catch (registryFailure: Throwable) {
            try {
                require(catalog.remove(modelId)) {
                    "failed to roll back native model catalog after registry rejection"
                }
            } catch (rollbackFailure: Throwable) {
                registryFailure.addSuppressed(rollbackFailure)
            }
            throw registryFailure
        }

        NativeRuntimeActivation(
            checkpointId = checkpointId,
            baselineCheckpointId = baselineCheckpointId,
            modelId = modelId,
            installedModel = installed,
            status = NativeRuntimeActivationStatus.ACTIVE,
            activatedAtEpochMs = installed.installedAtEpochMs
        )
    }

    @Synchronized
    fun rollback(activation: NativeRuntimeActivation): Result<NativeRuntimeActivation> =
        runCatching {
            require(activation.status == NativeRuntimeActivationStatus.ACTIVE) {
                "only an active AMPER-native model can be rolled back"
            }
            require(activation.modelId == modelIdFor(activation.checkpointId)) {
                "activation model id does not match checkpoint identity"
            }
            val checkpoint = requireNotNull(foundation.getCheckpoint(activation.checkpointId)) {
                "native checkpoint lineage missing during rollback"
            }
            val current = requireNotNull(catalog.get(activation.modelId)) {
                "active AMPER-native catalog entry is missing"
            }
            require(current.sha256 == checkpoint.weightArtifactSha256) {
                "active AMPER-native artifact no longer matches checkpoint identity"
            }

            InstalledModelDetachService(
                catalog = catalog,
                registry = registry,
                unloadRuntime = unloadRuntime
            ).detach(activation.modelId).getOrThrow()

            activation.copy(
                status = NativeRuntimeActivationStatus.ROLLED_BACK,
                rolledBackAtEpochMs = clock().coerceAtLeast(activation.activatedAtEpochMs)
            )
        }

    fun expectedModelId(checkpointId: NativeCheckpointId): ModelId =
        modelIdFor(checkpointId)

    private fun sameRuntimeIdentity(
        existing: InstalledModel,
        proposed: InstalledModel
    ): Boolean =
        existing.descriptor == proposed.descriptor &&
            existing.locator == proposed.locator &&
            existing.lengthBytes == proposed.lengthBytes &&
            existing.sha256 == proposed.sha256 &&
            existing.ggufVersion == proposed.ggufVersion &&
            existing.tensorCount == proposed.tensorCount &&
            existing.metadataKeyValueCount == proposed.metadataKeyValueCount

    private fun modelIdFor(checkpointId: NativeCheckpointId): ModelId =
        ModelId("amper-native-" + runtimeSha256(checkpointId.value).take(24))
}

private fun runtimeSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }