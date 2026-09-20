package io.amper.neuroos.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

data class AmiHardwareSnapshot(
    val features: Set<AmiHardwareFeature>,
    val logicalProcessors: Int,
    val memoryClassMb: Int,
    val lowRamDevice: Boolean
) {
    init {
        require(logicalProcessors > 0)
        require(memoryClassMb > 0)
        if (AmiHardwareFeature.ARM64 !in features) {
            require(
                features.none {
                    it == AmiHardwareFeature.NEON ||
                        it == AmiHardwareFeature.DOTPROD ||
                        it == AmiHardwareFeature.I8MM ||
                        it == AmiHardwareFeature.FP16
                }
            ) {
                "ARM execution features require ARM64"
            }
        }
    }

    fun supports(profile: AmiExecutionProfile): Boolean =
        features.containsAll(profile.requiredFeatures)
}

/**
 * Chooses the smallest context profile that satisfies the request, then the profile exposing the
 * most hardware-specific acceleration features. This is a performance policy only; it never changes
 * foundation weights, model capabilities or authority.
 */
object AmiExecutionProfileSelector {
    fun select(
        profiles: List<AmiExecutionProfile>,
        hardware: AmiHardwareSnapshot,
        requiredContextTokens: Int
    ): AmiExecutionProfile? {
        require(requiredContextTokens > 0)
        return profiles.asSequence()
            .filter(hardware::supports)
            .filter { it.contextTier >= requiredContextTokens }
            .sortedWith(
                compareBy<AmiExecutionProfile> { it.contextTier }
                    .thenByDescending { it.requiredFeatures.size }
                    .thenByDescending { it.gpuLayerHint }
                    .thenBy { it.profileId }
            )
            .firstOrNull()
    }
}

/**
 * Conservative Android hardware capability probe.
 *
 * ARM64 implies Advanced SIMD/NEON by the architecture contract. Optional instructions are admitted
 * only when Linux CPU feature text explicitly exposes them. Vulkan is admitted only when Android
 * publishes a Vulkan hardware feature. FP16/Vulkan subfeature probing will become stricter again at
 * native kernel/device creation time before execution.
 */
class AndroidAmiHardwareProfiler(
    context: Context
) {
    private val appContext = context.applicationContext

    fun snapshot(): AmiHardwareSnapshot {
        val features = linkedSetOf<AmiHardwareFeature>()
        val arm64 = Build.SUPPORTED_ABIS.any {
            it.equals("arm64-v8a", ignoreCase = true)
        }
        val cpuInfo = runCatching {
            File("/proc/cpuinfo").readText().lowercase()
        }.getOrDefault("")

        if (arm64) {
            features += AmiHardwareFeature.ARM64
            features += AmiHardwareFeature.NEON

            if (
                "asimddp" in cpuInfo ||
                "dotprod" in cpuInfo
            ) {
                features += AmiHardwareFeature.DOTPROD
            }
            if ("i8mm" in cpuInfo) {
                features += AmiHardwareFeature.I8MM
            }
            if (
                "asimdhp" in cpuInfo ||
                "fphp" in cpuInfo ||
                "fp16" in cpuInfo
            ) {
                features += AmiHardwareFeature.FP16
            }
        }

        val packageManager = appContext.packageManager
        val hasVulkan =
            packageManager.hasSystemFeature("android.hardware.vulkan.level") ||
                packageManager.hasSystemFeature("android.hardware.vulkan.version")
        if (hasVulkan) {
            features += AmiHardwareFeature.VULKAN
        }

        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        return AmiHardwareSnapshot(
            features = features,
            logicalProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            memoryClassMb = activityManager.memoryClass.coerceAtLeast(1),
            lowRamDevice = activityManager.isLowRamDevice
        )
    }
}
