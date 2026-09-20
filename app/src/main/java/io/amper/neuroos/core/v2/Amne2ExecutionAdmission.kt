package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmneBackendAdmission
import io.amper.neuroos.core.AmneKernelPrimitive
import io.amper.neuroos.core.AmneKernelRegistry
import io.amper.neuroos.core.AmneProcessKernelRuntime
import java.io.File

enum class Amne2ExecutionTier {
    /**
     * Artifact and semantics are valid and the deterministic reference kernels are available.
     * This tier is for correctness bring-up; M3 does not call it performance-ready.
     */
    REFERENCE_VALIDATION,

    /** At least one numerically-qualified, benchmark-admitted optimized primitive is active. */
    QUALIFIED_ACCELERATED
}

data class Amne2HardwareProfile(
    val features: Set<Ami2HardwareFeature>,
    val logicalProcessors: Int,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean
) {
    init {
        require(Ami2HardwareFeature.ARM64 in features) {
            "AMNE2 mobile execution requires ARM64"
        }
        require(Ami2HardwareFeature.NEON in features) {
            "AMNE2 ARM64 execution requires NEON"
        }
        require(logicalProcessors > 0)
        require(memoryClassMb > 0)
    }
}

data class Amne2AdmittedFoundation(
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String,
    val sourceSha256: String,
    val hardware: Amne2HardwareProfile,
    val executionTier: Amne2ExecutionTier,
    val registeredKernelBackends: Set<String>,
    val acceleratedPrimitives: Set<AmneKernelPrimitive>
) {
    init {
        require(foundationId.isNotBlank())
        require(semanticSha256.matches(Regex("[0-9a-f]{64}")))
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(registeredKernelBackends.isNotEmpty())
        if (executionTier == Amne2ExecutionTier.REFERENCE_VALIDATION) {
            require(acceleratedPrimitives.isEmpty()) {
                "AMNE2 reference-validation tier cannot claim accelerated primitives"
            }
        }
    }
}

/**
 * First M3 boundary: verified AMI2 -> AMNE2 execution admission.
 *
 * This does not create a new decoder or kernel stack. It reuses the already-qualified AMNE kernel
 * registry and process admission while binding execution to one verified AMI2 semantic identity.
 */
class Amne2ExecutionAdmission(
    private val reader: Ami2CanonicalBinaryReader = Ami2CanonicalBinaryReader(),
    private val registryProvider: () -> AmneKernelRegistry = {
        AmneProcessKernelRuntime.registry()
    },
    private val optimizedAdmissionProvider: () -> AmneBackendAdmission? = {
        AmneProcessKernelRuntime.admission()
    }
) {
    fun admit(
        artifactFile: File,
        hardware: AmiHardwareSnapshot
    ): Result<Amne2AdmittedFoundation> = runCatching {
        require(Ami2MigrationContract.productionExecutionEngine == "AMNE2") {
            "OMEGA execution contract no longer targets AMNE2"
        }

        val loaded = reader.read(
            file = artifactFile,
            verifySectionDigests = true
        ).getOrThrow()
        val foundation = loaded.bundle.foundation

        require(loaded.bundle.artifacts == Ami2FoundationContract.mandatoryArtifacts) {
            "AMNE2 foundation admission requires canonical AMI2 artifacts only"
        }
        require(loaded.bundle.devicePacks.isEmpty()) {
            "AMNE2 foundation admission cannot treat device packs as a second foundation"
        }
        require(
            foundation.foundationId ==
                Ami2CompilationPlanner.foundationIdForSemantic(foundation.semanticSha256)
        ) {
            "AMNE2 foundation id is not canonical for its semantic identity"
        }

        val hardwareProfile = hardware.toAmne2HardwareProfile()
        val registry = registryProvider()
        val descriptors = registry.descriptors()
        require(descriptors.any { it.deterministicReference }) {
            "AMNE2 admission requires the deterministic AMNE reference backend"
        }

        val optimized = optimizedAdmissionProvider()
        val accelerated = optimized
            ?.takeIf { it.numericalQualificationPassed }
            ?.admittedPrimitives
            .orEmpty()

        val tier = if (accelerated.isEmpty()) {
            Amne2ExecutionTier.REFERENCE_VALIDATION
        } else {
            Amne2ExecutionTier.QUALIFIED_ACCELERATED
        }

        Amne2AdmittedFoundation(
            foundationId = foundation.foundationId,
            semanticSha256 = foundation.semanticSha256,
            artifactSha256 = loaded.fileSha256,
            sourceSha256 = foundation.lineage.sourceSha256,
            hardware = hardwareProfile,
            executionTier = tier,
            registeredKernelBackends = descriptors
                .mapTo(linkedSetOf()) { it.backendId },
            acceleratedPrimitives = accelerated.toSet()
        )
    }

    private fun AmiHardwareSnapshot.toAmne2HardwareProfile(): Amne2HardwareProfile {
        val mapped = linkedSetOf<Ami2HardwareFeature>()
        features.forEach { feature ->
            when (feature) {
                AmiHardwareFeature.ARM64 -> mapped += Ami2HardwareFeature.ARM64
                AmiHardwareFeature.NEON -> mapped += Ami2HardwareFeature.NEON
                AmiHardwareFeature.DOTPROD -> mapped += Ami2HardwareFeature.DOTPROD
                AmiHardwareFeature.I8MM -> mapped += Ami2HardwareFeature.I8MM
                AmiHardwareFeature.FP16 -> mapped += Ami2HardwareFeature.FP16
                AmiHardwareFeature.VULKAN,
                AmiHardwareFeature.VULKAN_FP16,
                AmiHardwareFeature.VULKAN_INT8 -> mapped += Ami2HardwareFeature.VULKAN
            }
        }
        return Amne2HardwareProfile(
            features = mapped,
            logicalProcessors = logicalProcessors,
            memoryClassMb = memoryClassMb,
            lowRamDevice = lowRamDevice
        )
    }
}
