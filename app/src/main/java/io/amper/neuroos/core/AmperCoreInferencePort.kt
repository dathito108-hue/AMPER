package io.amper.neuroos.core

data class AmperCoreBootstrapReport(
    val nativePackaged: Boolean,
    val qualificationPassed: Boolean,
    val admittedPrimitives: Set<AmneKernelPrimitive>,
    val benchmarkedPrimitives: Set<AmneKernelPrimitive>,
    val matrixCoverage: Set<AmneKernelPrimitive>
)

/**
 * Production inference boundary for the single AMPER intelligence core.
 *
 * Android owns exactly one instance of this port. Titan receives only the sealed one-entry registry
 * derived from this core and therefore cannot acquire a second production inference endpoint.
 */
class AmperCoreInferencePort(
    artifactLookup: (InstalledModel) -> StoredAmiArtifact?,
    hardwareSnapshot: () -> AmiHardwareSnapshot?
) {
    private val coreBackend = AmiDirectStreamingInferenceBackend(
        artifactLookup = artifactLookup,
        hardwareSnapshot = hardwareSnapshot
    )

    private val registry = InferenceBackendRegistry.singleCore(coreBackend)

    val id: String
        get() = CORE_ID

    val inferenceEndpointCount: Int
        get() = registry.list().size

    fun health(): BackendHealth = coreBackend.health()

    fun nativeRuntimePackaged(): Boolean = AmneNativeRuntimeProbe.isPackaged()

    /**
     * Performs process-local numerical qualification and device benchmarking, then installs only
     * admitted AMNE primitives into the live AMPER Core dispatch table.
     *
     * Canonical non-native/debug builds return a descriptive failure instead of fabricating an
     * accelerated runtime.
     */
    fun bootstrapNativeAdmission(): Result<AmperCoreBootstrapReport> {
        if (!nativeRuntimePackaged()) {
            return Result.failure(
                IllegalStateException("AMPER Core native runtime is not packaged in this APK")
            )
        }

        return AmneNativeRuntimeProbe.benchmarkAndAdmit().map { report ->
            val benchmarks = report.admission.benchmarkResults
            val matrixPrimitives = setOf(
                AmneKernelPrimitive.MATVEC_F32,
                AmneKernelPrimitive.MATVEC_Q4_0,
                AmneKernelPrimitive.MATVEC_Q8_0,
                AmneKernelPrimitive.MATVEC_Q4_K,
                AmneKernelPrimitive.MATVEC_Q5_K,
                AmneKernelPrimitive.MATVEC_Q6_K
            )
            AmperCoreBootstrapReport(
                nativePackaged = true,
                qualificationPassed = report.qualification.passed,
                admittedPrimitives = report.admission.admittedPrimitives.toSet(),
                benchmarkedPrimitives = benchmarks.keys.toSet(),
                matrixCoverage = report.admission.admittedPrimitives
                    .filterTo(linkedSetOf()) { it in matrixPrimitives }
            )
        }
    }

    internal fun titanRegistry(): InferenceBackendRegistry {
        require(registry.isSealedSingleCore()) {
            "AMPER Core registry lost single-core seal"
        }
        require(registry.list().size == 1 && registry.list().single() === coreBackend) {
            "AMPER Core registry contains an unexpected inference endpoint"
        }
        return registry
    }

    companion object {
        const val CORE_ID: String = "amper-core"
    }
}


/**
 * Sovereign status source for the production single-core architecture.
 *
 * Imported sources are reported for lineage, but runtime status exposes exactly one AMPER Core
 * endpoint rather than a list of competing inference backends.
 */
class AmperCoreSovereignStatusSource(
    private val catalog: InstalledModelCatalog,
    private val core: AmperCoreInferencePort,
    private val governor: ResourceGovernor
) : SovereignStatusSource {
    override fun snapshot(): SovereignStatusSnapshot {
        val health = core.health()
        return SovereignStatusSnapshot(
            models = catalog.list()
                .sortedBy { it.displayName.lowercase() }
                .take(8)
                .map { "${it.displayName}:${it.descriptor.id.value}" },
            backends = listOf(
                SovereignBackendStatus(
                    id = core.id,
                    state = health.state.name,
                    hardwareAcceleration = health.hardwareAcceleration
                )
            ),
            budget = governor.currentBudget()
        )
    }
}
