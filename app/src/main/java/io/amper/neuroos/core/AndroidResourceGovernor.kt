package io.amper.neuroos.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlin.math.max
import kotlin.math.min

/**
 * Live mobile resource governor backed by Android memory and thermal signals.
 * Native model loading is allowed to use only a conservative slice of currently
 * available memory so Titan does not treat the phone as a desktop workstation.
 */
class AndroidResourceGovernor(context: Context) : ResourceGovernor {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    override fun currentBudget(): ResourceBudget {
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val mib = 1024L * 1024L
        val availableMb = (info.availMem / mib).coerceAtLeast(1L)
        val totalMb = (info.totalMem / mib).coerceAtLeast(1L)

        // Keep at least 25% of total RAM and 512 MiB of currently available RAM
        // outside Titan's native-model budget.
        val systemReserveMb = max(512L, totalMb / 4L)
        val fromAvailable = max(256L, availableMb - 512L)
        val fromTotal = max(256L, totalMb - systemReserveMb)
        val modelBudgetMb = min(fromAvailable, fromTotal)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.currentThermalStatus
        } else {
            1
        }

        val concurrentAgents = if (
            info.lowMemory || thermal >= PowerManager.THERMAL_STATUS_SEVERE
        ) 1 else 2

        return ResourceBudget(
            maxConcurrentAgents = concurrentAgents,
            memoryMb = modelBudgetMb,
            thermalClass = thermal
        )
    }

    override fun allows(agentCount: Int): Boolean {
        val budget = currentBudget()
        return agentCount <= budget.maxConcurrentAgents &&
            budget.thermalClass < PowerManager.THERMAL_STATUS_CRITICAL
    }
}
