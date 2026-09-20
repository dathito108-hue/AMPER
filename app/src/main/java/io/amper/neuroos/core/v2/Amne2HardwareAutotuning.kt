package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmiHardwareFeature
import io.amper.neuroos.core.AmiHardwareSnapshot
import io.amper.neuroos.core.AmneBackendAdmission
import io.amper.neuroos.core.AmneDeviceAdmissionReport
import io.amper.neuroos.core.AmneDispatchAdmissionPolicy
import io.amper.neuroos.core.AmneKernelPrimitive
import io.amper.neuroos.core.AmneNativeRuntimeProbe
import io.amper.neuroos.core.AmneProcessKernelRuntime
import io.amper.neuroos.core.AmneReferenceCpuKernels

data class Amne2HardwareFingerprint(
    val features: Set<AmiHardwareFeature>,
    val logicalProcessors: Int,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean
) {
    init {
        require(logicalProcessors > 0)
        require(memoryClassMb > 0)
    }

    companion object {
        fun from(snapshot: AmiHardwareSnapshot): Amne2HardwareFingerprint =
            Amne2HardwareFingerprint(
                features = snapshot.features
                    .sortedBy { it.ordinal }
                    .toCollection(linkedSetOf()),
                logicalProcessors = snapshot.logicalProcessors,
                memoryClassMb = snapshot.memoryClassMb,
                lowRamDevice = snapshot.lowRamDevice
            )
    }
}

data class Amne2HardwareAutotuneDecision(
    val fingerprint: Amne2HardwareFingerprint,
    val referenceBaselineBackendId: String,
    val activeAcceleratedBackendId: String?,
    val admittedPrimitives: Set<AmneKernelPrimitive>,
    val nativeBackendPackaged: Boolean,
    val numericalQualificationPassed: Boolean,
    val benchmarkExecuted: Boolean,
    val cpuAccelerationFeatures: Set<AmiHardwareFeature>
) {
    init {
        require(referenceBaselineBackendId.isNotBlank())
        if (!numericalQualificationPassed) {
            require(admittedPrimitives.isEmpty())
            require(activeAcceleratedBackendId == null)
        }
        if (activeAcceleratedBackendId != null) {
            require(admittedPrimitives.isNotEmpty())
            require(benchmarkExecuted)
        }
    }

    val usesReferenceOnly: Boolean
        get() = activeAcceleratedBackendId == null
}

data class Amne2VulkanBenchmarkEvidence(
    val referenceCpuMedianNs: Long,
    val vulkanMedianNs: Long,
    val numericalQualificationPassed: Boolean,
    val measuredOnPhysicalDevice: Boolean
) {
    init {
        require(referenceCpuMedianNs > 0L)
        require(vulkanMedianNs > 0L)
    }

    val speedup: Double
        get() = referenceCpuMedianNs.toDouble() / vulkanMedianNs.toDouble()
}

/**
 * Vulkan remains disabled unless a physical-device benchmark proves both numerical correctness and
 * a meaningful speedup over the already-qualified CPU path.
 */
object Amne2VulkanAdmissionPolicy {
    fun admits(
        hardware: AmiHardwareSnapshot,
        evidence: Amne2VulkanBenchmarkEvidence,
        minimumSpeedup: Double = AmneDispatchAdmissionPolicy.DEFAULT_MINIMUM_SPEEDUP
    ): Boolean {
        require(minimumSpeedup >= 1.0)
        return AmiHardwareFeature.VULKAN in hardware.features &&
            evidence.measuredOnPhysicalDevice &&
            evidence.numericalQualificationPassed &&
            evidence.vulkanMedianNs < evidence.referenceCpuMedianNs &&
            evidence.speedup >= minimumSpeedup
    }
}

internal interface Amne2NativeAutotuneProbe {
    fun isPackaged(): Boolean

    fun benchmarkAndAdmit(
        minimumSpeedup: Double
    ): Result<AmneDeviceAdmissionReport>
}

private object DefaultAmne2NativeAutotuneProbe : Amne2NativeAutotuneProbe {
    override fun isPackaged(): Boolean =
        AmneNativeRuntimeProbe.isPackaged()

    override fun benchmarkAndAdmit(
        minimumSpeedup: Double
    ): Result<AmneDeviceAdmissionReport> =
        AmneNativeRuntimeProbe.benchmarkAndAdmit(
            minimumSpeedup = minimumSpeedup
        )
}

/**
 * Process-local AMNE2 hardware autotuner.
 *
 * One hardware fingerprint is benchmarked at most once by this instance. The deterministic
 * reference registry is installed first, so any absent backend, qualification error, benchmark
 * failure, or insufficient speedup leaves execution on the correctness baseline.
 */
class Amne2HardwareAutotuner internal constructor(
    private val nativeProbe: Amne2NativeAutotuneProbe =
        DefaultAmne2NativeAutotuneProbe,
    private val resetToReference: () -> Unit = AmneProcessKernelRuntime::resetToReference,
    private val minimumSpeedup: Double =
        AmneDispatchAdmissionPolicy.DEFAULT_MINIMUM_SPEEDUP
) {
    init {
        require(minimumSpeedup >= 1.0)
    }

    @Volatile
    private var cachedDecision: Amne2HardwareAutotuneDecision? = null

    @Synchronized
    fun tune(
        hardware: AmiHardwareSnapshot
    ): Result<Amne2HardwareAutotuneDecision> = runCatching {
        val fingerprint = Amne2HardwareFingerprint.from(hardware)
        cachedDecision
            ?.takeIf { it.fingerprint == fingerprint }
            ?.let { return@runCatching it }

        resetToReference()

        val cpuFeatures = hardware.features
            .filterTo(linkedSetOf()) {
                it == AmiHardwareFeature.ARM64 ||
                    it == AmiHardwareFeature.NEON ||
                    it == AmiHardwareFeature.DOTPROD ||
                    it == AmiHardwareFeature.I8MM ||
                    it == AmiHardwareFeature.FP16
            }

        val canUseArmNative =
            AmiHardwareFeature.ARM64 in hardware.features &&
                AmiHardwareFeature.NEON in hardware.features
        val packaged = canUseArmNative && nativeProbe.isPackaged()

        val decision = if (!packaged) {
            referenceDecision(
                fingerprint = fingerprint,
                packaged = false,
                cpuFeatures = cpuFeatures
            )
        } else {
            val reportResult = nativeProbe.benchmarkAndAdmit(minimumSpeedup)
            val report = reportResult.getOrNull()
            if (report == null) {
                resetToReference()
                referenceDecision(
                    fingerprint = fingerprint,
                    packaged = true,
                    cpuFeatures = cpuFeatures,
                    benchmarkExecuted = true
                )
            } else {
                decisionFromAdmission(
                    fingerprint = fingerprint,
                    report = report,
                    cpuFeatures = cpuFeatures
                )
            }
        }

        cachedDecision = decision
        decision
    }

    @Synchronized
    fun invalidate() {
        cachedDecision = null
        resetToReference()
    }

    private fun decisionFromAdmission(
        fingerprint: Amne2HardwareFingerprint,
        report: AmneDeviceAdmissionReport,
        cpuFeatures: Set<AmiHardwareFeature>
    ): Amne2HardwareAutotuneDecision {
        val admission: AmneBackendAdmission = report.admission
        val admitted = if (report.qualification.passed) {
            admission.admittedPrimitives
        } else {
            emptySet()
        }
        if (admitted.isEmpty()) {
            resetToReference()
        }

        return Amne2HardwareAutotuneDecision(
            fingerprint = fingerprint,
            referenceBaselineBackendId = AmneReferenceCpuKernels.descriptor.backendId,
            activeAcceleratedBackendId =
                admission.backendId.takeIf { admitted.isNotEmpty() },
            admittedPrimitives = admitted,
            nativeBackendPackaged = true,
            numericalQualificationPassed = report.qualification.passed,
            benchmarkExecuted = true,
            cpuAccelerationFeatures = cpuFeatures
        )
    }

    private fun referenceDecision(
        fingerprint: Amne2HardwareFingerprint,
        packaged: Boolean,
        cpuFeatures: Set<AmiHardwareFeature>,
        benchmarkExecuted: Boolean = false
    ): Amne2HardwareAutotuneDecision =
        Amne2HardwareAutotuneDecision(
            fingerprint = fingerprint,
            referenceBaselineBackendId = AmneReferenceCpuKernels.descriptor.backendId,
            activeAcceleratedBackendId = null,
            admittedPrimitives = emptySet(),
            nativeBackendPackaged = packaged,
            numericalQualificationPassed = false,
            benchmarkExecuted = benchmarkExecuted,
            cpuAccelerationFeatures = cpuFeatures
        )
}

object Amne2ProcessHardwareAutotuning {
    val autotuner: Amne2HardwareAutotuner = Amne2HardwareAutotuner()
}
