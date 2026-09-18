package io.amper.neuroos.core

data class DeviceStatusSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val availableMemoryMb: Long,
    val lowMemory: Boolean,
    val appStorageFreeMb: Long,
    val appStorageTotalMb: Long,
    val thermalStatus: Int?,
    val processors: Int,
    val sdkInt: Int,
    val supportedAbis: List<String>
) {
    init {
        batteryPercent?.let { require(it in 0..100) }
        require(availableMemoryMb >= 0)
        require(appStorageFreeMb >= 0)
        require(appStorageTotalMb >= 0)
        require(processors > 0)
        require(sdkInt > 0)
    }
}

fun interface DeviceStatusSource {
    fun snapshot(): DeviceStatusSnapshot
}

object DeviceStatusToolContract {
    val capability = CapabilityId("device.status.read")
    val toolId = ToolId("android-device-status")
}

/**
 * Read-only device-awareness provider. It intentionally exposes capacity/health
 * signals useful for mobile inference scheduling, not stable device identifiers.
 */
class DeviceStatusToolProvider(
    private val source: DeviceStatusSource
) : ToolProvider {
    override val descriptor = ToolDescriptor(
        id = DeviceStatusToolContract.toolId,
        name = "Android device status",
        capability = DeviceStatusToolContract.capability,
        sideEffect = ToolSideEffect.READ_ONLY,
        inputContract = ToolInputContract(
            description = "Read current battery, RAM, storage, thermal and CPU status",
            acceptedValues = setOf("summary", "status"),
            maxLength = 16
        )
    )

    override fun execute(input: String): Result<String> = runCatching {
        val request = input.trim().lowercase()
        require(request.isEmpty() || request == "summary" || request == "status") {
            "device.status.read accepts only summary/status"
        }
        val status = source.snapshot()
        buildString {
            append("battery_percent=${status.batteryPercent ?: "unknown"}")
            append(";charging=${status.charging ?: "unknown"}")
            append(";memory_available_mb=${status.availableMemoryMb}")
            append(";low_memory=${status.lowMemory}")
            append(";app_storage_free_mb=${status.appStorageFreeMb}")
            append(";app_storage_total_mb=${status.appStorageTotalMb}")
            append(";thermal_status=${status.thermalStatus ?: "unknown"}")
            append(";processors=${status.processors}")
            append(";sdk=${status.sdkInt}")
            append(";abis=${status.supportedAbis.take(4).joinToString(",")}")
        }
    }
}
