package io.amper.neuroos.core

enum class NativeMultimodalAdapterStatus {
    ACTIVE,
    INACTIVE
}

data class NativeMultimodalAdapterEvaluation(
    val checkpointId: NativeCheckpointId,
    val projectorSha256: String,
    val evaluatedKinds: Set<InferenceAttachmentKind>,
    val passRate: Double,
    val samples: Int
) {
    init {
        require(projectorSha256.matches(Regex("[0-9a-f]{64}")))
        require(evaluatedKinds.isNotEmpty())
        require(passRate in 0.0..1.0)
        require(samples >= 0)
    }

    val admitted: Boolean
        get() = samples >= MIN_SAMPLES && passRate >= MIN_PASS_RATE

    companion object {
        const val MIN_SAMPLES = 16
        const val MIN_PASS_RATE = 0.90
    }
}

data class NativeMultimodalAdapterActivation(
    val checkpointId: NativeCheckpointId,
    val modelId: ModelId,
    val projector: InstalledMultimodalProjector,
    val enabledCapabilities: Set<CapabilityId>,
    val evaluation: NativeMultimodalAdapterEvaluation,
    val status: NativeMultimodalAdapterStatus,
    val changedAtEpochMs: Long
) {
    init {
        require(projector.modelId == modelId)
        require(enabledCapabilities.isNotEmpty())
        require(changedAtEpochMs >= 0L)
        if (status == NativeMultimodalAdapterStatus.ACTIVE) {
            require(evaluation.admitted)
        }
    }

    val authorityBearing: Boolean
        get() = false
}

/**
 * Runtime capability admission for AMPER-native models.
 *
 * Multimodal capability declarations remain latent until a compatible projector has passed explicit
 * adapter admission. Text/reasoning/planning/code capabilities can be admitted directly from the
 * AMPER-owned model contract.
 */
object NativeRuntimeCapabilityAdmission {
    private val adapterCapabilities = setOf(
        TitanCapabilities.VISION,
        TitanCapabilities.AUDIO_UNDERSTANDING
    )

    fun baseCapabilities(contractCapabilities: Set<CapabilityId>): Set<CapabilityId> {
        require(contractCapabilities.isNotEmpty())
        val base = contractCapabilities - adapterCapabilities
        require(base.isNotEmpty()) {
            "AMPER-native contract must expose at least one non-adapter runtime capability"
        }
        return base
    }

    fun capabilitiesForKinds(kinds: Set<InferenceAttachmentKind>): Set<CapabilityId> {
        require(kinds.isNotEmpty())
        return buildSet {
            if (InferenceAttachmentKind.IMAGE in kinds) add(TitanCapabilities.VISION)
            if (InferenceAttachmentKind.AUDIO in kinds) add(TitanCapabilities.AUDIO_UNDERSTANDING)
        }.also {
            require(it.isNotEmpty()) { "adapter kinds do not map to a native model capability" }
        }
    }

    fun kindsAllowedByContract(contractCapabilities: Set<CapabilityId>): Set<InferenceAttachmentKind> =
        buildSet {
            if (TitanCapabilities.VISION in contractCapabilities) {
                add(InferenceAttachmentKind.IMAGE)
            }
            if (TitanCapabilities.AUDIO_UNDERSTANDING in contractCapabilities) {
                add(InferenceAttachmentKind.AUDIO)
            }
        }
}

/**
 * Phase226-228 adapter admission for AMPER-native multimodal capability.
 *
 * A promoted text model cannot claim VISION/AUDIO merely because its contract names those
 * capabilities. A descriptor-bound projector must pass structural identity inspection and held-out
 * evaluation first. Catalog/registry publication is rolled back if any later step fails.
 */
class NativeMultimodalAdapterActivationService(
    private val foundation: NativeModelFoundation,
    private val promotion: NativeCheckpointRuntimePromotionService,
    private val catalog: InstalledModelCatalog,
    private val registry: MutableModelRegistry,
    private val projectors: MultimodalProjectorCatalog,
    private val inspector: GgufInspector = GgufInspector(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Synchronized
    fun activate(
        checkpointId: NativeCheckpointId,
        source: ModelArtifactSource,
        kinds: Set<InferenceAttachmentKind>,
        evaluation: NativeMultimodalAdapterEvaluation
    ): Result<NativeMultimodalAdapterActivation> = runCatching {
        require(source is DescriptorBoundNativeModelPathSource) {
            "native multimodal adapter requires a descriptor-bound projector source"
        }
        require(evaluation.checkpointId == checkpointId) {
            "native multimodal adapter evaluation checkpoint mismatch"
        }
        require(evaluation.evaluatedKinds == kinds) {
            "native multimodal adapter evaluation kinds mismatch"
        }
        require(evaluation.admitted) {
            "native multimodal adapter evaluation is below admission gate"
        }

        val checkpoint = requireNotNull(foundation.getCheckpoint(checkpointId)) {
            "native checkpoint lineage missing"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId)) {
            "native model contract missing"
        }
        require(contract.ownedByAmper) { "native adapter requires AMPER-owned model contract" }
        require(contract.canonicalDigest == checkpoint.contractDigest) {
            "native adapter checkpoint contract digest mismatch"
        }

        val allowedKinds = NativeRuntimeCapabilityAdmission
            .kindsAllowedByContract(contract.capabilities)
        require(allowedKinds.containsAll(kinds)) {
            "native adapter kinds exceed AMPER model contract"
        }
        val capabilities = NativeRuntimeCapabilityAdmission.capabilitiesForKinds(kinds)
        val modelId = promotion.expectedModelId(checkpointId)
        val current = requireNotNull(catalog.get(modelId)) {
            "AMPER-native model must be live before multimodal adapter activation"
        }
        require(current.sha256 == checkpoint.weightArtifactSha256) {
            "live AMPER-native model does not match checkpoint weights"
        }
        require(current.descriptor.capabilities.all { it in contract.capabilities }) {
            "live AMPER-native model exposes capability outside model contract"
        }

        val inspection = inspector.inspect(source).getOrThrow()
        require(inspection.sha256 == evaluation.projectorSha256) {
            "native multimodal adapter evaluation projector digest mismatch"
        }
        val projector = InstalledMultimodalProjector(
            modelId = modelId,
            displayName = inspection.displayName,
            locator = inspection.locator,
            lengthBytes = inspection.lengthBytes,
            sha256 = inspection.sha256,
            ggufVersion = inspection.header.version,
            tensorCount = inspection.header.tensorCount,
            metadataKeyValueCount = inspection.header.metadataKeyValueCount,
            expectedKinds = kinds,
            installedAtEpochMs = clock()
        )
        val updated = current.copy(
            descriptor = current.descriptor.copy(
                capabilities =
                    NativeRuntimeCapabilityAdmission.baseCapabilities(contract.capabilities) +
                        capabilities
            )
        )
        require(updated.descriptor.capabilities.all { it in contract.capabilities }) {
            "adapter activation would expose capability outside AMPER model contract"
        }

        val previousProjector = projectors.get(modelId)
        if (
            previousProjector != null &&
            sameProjectorIdentity(previousProjector, projector) &&
            current.descriptor == updated.descriptor
        ) {
            registry.register(current.descriptor)
            return@runCatching NativeMultimodalAdapterActivation(
                checkpointId = checkpointId,
                modelId = modelId,
                projector = previousProjector,
                enabledCapabilities = capabilities,
                evaluation = evaluation,
                status = NativeMultimodalAdapterStatus.ACTIVE,
                changedAtEpochMs = previousProjector.installedAtEpochMs
            )
        }

        projectors.put(projector)
        try {
            catalog.put(updated)
            registry.register(updated.descriptor)
        } catch (failure: Throwable) {
            runCatching { catalog.put(current) }.onFailure(failure::addSuppressed)
            runCatching {
                if (previousProjector == null) {
                    projectors.remove(modelId)
                } else {
                    projectors.put(previousProjector)
                }
            }.onFailure(failure::addSuppressed)
            runCatching { registry.register(current.descriptor) }.onFailure(failure::addSuppressed)
            throw failure
        }

        NativeMultimodalAdapterActivation(
            checkpointId = checkpointId,
            modelId = modelId,
            projector = projector,
            enabledCapabilities = capabilities,
            evaluation = evaluation,
            status = NativeMultimodalAdapterStatus.ACTIVE,
            changedAtEpochMs = projector.installedAtEpochMs
        )
    }

    @Synchronized
    fun deactivate(
        activation: NativeMultimodalAdapterActivation
    ): Result<NativeMultimodalAdapterActivation> = runCatching {
        require(activation.status == NativeMultimodalAdapterStatus.ACTIVE) {
            "native multimodal adapter is not active"
        }
        val checkpoint = requireNotNull(foundation.getCheckpoint(activation.checkpointId)) {
            "native checkpoint lineage missing"
        }
        val contract = requireNotNull(foundation.getContract(checkpoint.contractId))
        val current = requireNotNull(catalog.get(activation.modelId)) {
            "AMPER-native model is unavailable"
        }
        val baseCapabilities = NativeRuntimeCapabilityAdmission
            .baseCapabilities(contract.capabilities)
        val reverted = current.copy(
            descriptor = current.descriptor.copy(capabilities = baseCapabilities)
        )
        val previousProjector = requireNotNull(projectors.get(activation.modelId)) {
            "active native projector record is missing"
        }

        catalog.put(reverted)
        try {
            registry.register(reverted.descriptor)
            require(projectors.remove(activation.modelId)) {
                "native projector disappeared during deactivation"
            }
        } catch (failure: Throwable) {
            runCatching { catalog.put(current) }.onFailure(failure::addSuppressed)
            runCatching { registry.register(current.descriptor) }.onFailure(failure::addSuppressed)
            runCatching { projectors.put(previousProjector) }.onFailure(failure::addSuppressed)
            throw failure
        }

        activation.copy(
            status = NativeMultimodalAdapterStatus.INACTIVE,
            changedAtEpochMs = clock().coerceAtLeast(activation.changedAtEpochMs)
        )
    }

    private fun sameProjectorIdentity(
        first: InstalledMultimodalProjector,
        second: InstalledMultimodalProjector
    ): Boolean =
        first.modelId == second.modelId &&
            first.locator == second.locator &&
            first.lengthBytes == second.lengthBytes &&
            first.sha256 == second.sha256 &&
            first.ggufVersion == second.ggufVersion &&
            first.tensorCount == second.tensorCount &&
            first.metadataKeyValueCount == second.metadataKeyValueCount &&
            first.expectedKinds == second.expectedKinds
}

/**
 * Fail-closed restart reconciliation for adapter-dependent runtime capabilities.
 *
 * This may only remove VISION/AUDIO from an AMPER-native descriptor when its durable projector
 * pairing no longer supports that capability. It never adds a capability and therefore cannot
 * substitute for adapter evidence/admission.
 */
class NativeRuntimeCapabilityReconciler(
    private val catalog: InstalledModelCatalog,
    private val registry: ModelRegistry,
    private val projectors: MultimodalProjectorCatalog
) {
    @Synchronized
    fun reconcile(): Int {
        var changed = 0
        catalog.list()
            .filter { it.descriptor.id.value.startsWith("amper-native-") }
            .forEach { current ->
                val allowedAdapterCapabilities = projectors.get(current.descriptor.id)
                    ?.expectedKinds
                    ?.let(NativeRuntimeCapabilityAdmission::capabilitiesForKinds)
                    .orEmpty()
                val nextCapabilities = current.descriptor.capabilities.filterTo(linkedSetOf()) {
                    it !in ADAPTER_CAPABILITIES || it in allowedAdapterCapabilities
                }
                require(nextCapabilities.isNotEmpty()) {
                    "AMPER-native capability reconciliation cannot remove the entire runtime profile"
                }
                if (nextCapabilities == current.descriptor.capabilities) return@forEach

                val updated = current.copy(
                    descriptor = current.descriptor.copy(capabilities = nextCapabilities)
                )
                catalog.put(updated)
                try {
                    registry.register(updated.descriptor)
                } catch (failure: Throwable) {
                    runCatching { catalog.put(current) }.onFailure(failure::addSuppressed)
                    runCatching { registry.register(current.descriptor) }.onFailure(failure::addSuppressed)
                    throw failure
                }
                changed += 1
            }
        return changed
    }

    companion object {
        private val ADAPTER_CAPABILITIES = setOf(
            TitanCapabilities.VISION,
            TitanCapabilities.AUDIO_UNDERSTANDING
        )
    }
}

/**
 * Phase229-230 soft preference for an installed AMPER-native model.
 *
 * This occupies only the low-priority turn-continuity lane. Explicit per-request or global user
 * choices remain stronger. Titan still owns capability/artifact/backend/resource admission and can
 * bypass the native preference when the route is infeasible.
 */
class NativeModelPreferenceInferencePort(
    private val delegate: CognitiveInferencePort,
    private val preferredNativeModel: () -> ModelId?
) : CancellableStreamingCognitiveInferencePort, CancellablePreparableCognitiveInferencePort {
    override fun infer(request: InferenceRequest): Result<InferenceResponse> =
        delegate.infer(withNativePreference(request))

    override fun prepare(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal
    ): Result<InferencePreparation> {
        cancellation.throwIfCancelled()
        val effective = withNativePreference(request)
        val cancellable = delegate as? CancellablePreparableCognitiveInferencePort
        if (cancellable != null) {
            return cancellable.prepare(effective, cancellation)
        }
        val legacy = delegate as? PreparableCognitiveInferencePort
            ?: return Result.failure(
                IllegalStateException("inference delegate does not support preparation")
            )
        val result = legacy.prepare(effective)
        cancellation.throwIfCancelled()
        return result
    }

    override fun inferStream(
        request: InferenceRequest,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> =
        inferStream(
            request = request,
            cancellation = InferenceCancellationSignal(),
            onChunk = onChunk
        )

    override fun inferStream(
        request: InferenceRequest,
        cancellation: InferenceCancellationSignal,
        onChunk: (InferenceChunk) -> Unit
    ): Result<InferenceResponse> {
        val effective = withNativePreference(request)
        cancellation.throwIfCancelled()

        val cancellable = delegate as? CancellableStreamingCognitiveInferencePort
        if (cancellable != null) {
            return cancellable.inferStream(effective, cancellation, onChunk)
        }
        val streaming = delegate as? StreamingCognitiveInferencePort
        if (streaming != null) {
            val result = streaming.inferStream(effective, onChunk)
            cancellation.throwIfCancelled()
            return result
        }

        val result = delegate.infer(effective)
        cancellation.throwIfCancelled()
        result.getOrNull()?.let { response ->
            if (response.text.isNotEmpty()) {
                runCatching { onChunk(InferenceChunk(response.text, 0)) }
            }
            runCatching {
                onChunk(
                    InferenceChunk(
                        text = "",
                        index = if (response.text.isEmpty()) 0 else 1,
                        finished = true
                    )
                )
            }
        }
        cancellation.throwIfCancelled()
        return result
    }

    private fun withNativePreference(request: InferenceRequest): InferenceRequest {
        if (request.userPreferredModelId != null || request.preferredModelId != null) {
            return request
        }
        val native = preferredNativeModel() ?: return request
        return request.copy(preferredModelId = native)
    }
}

object NativeInstalledModelSelector {
    fun preferredModelId(catalog: InstalledModelCatalog): ModelId? =
        catalog.list()
            .asSequence()
            .filter { it.descriptor.id.value.startsWith(NATIVE_MODEL_PREFIX) }
            .filter { it.descriptor.local && it.descriptor.format.equals("gguf", ignoreCase = true) }
            .maxWithOrNull(
                compareBy<InstalledModel> { it.installedAtEpochMs }
                    .thenBy { it.descriptor.id.value }
            )
            ?.descriptor
            ?.id

    private const val NATIVE_MODEL_PREFIX = "amper-native-"
}