package io.amper.neuroos.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

class AndroidDeviceStatusSource(context: Context) : DeviceStatusSource {
    private val appContext = context.applicationContext

    override fun snapshot(): DeviceStatusSnapshot {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) ((level * 100L) / scale).toInt().coerceIn(0, 100) else null
        val batteryStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
            else -> null
        }
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) powerManager.currentThermalStatus else null
        val root = appContext.filesDir
        val mib = 1024L * 1024L

        return DeviceStatusSnapshot(
            batteryPercent = batteryPercent,
            charging = charging,
            availableMemoryMb = (memoryInfo.availMem / mib).coerceAtLeast(0),
            lowMemory = memoryInfo.lowMemory,
            appStorageFreeMb = (root.usableSpace / mib).coerceAtLeast(0),
            appStorageTotalMb = (root.totalSpace / mib).coerceAtLeast(0),
            thermalStatus = thermal,
            processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        )
    }
}
