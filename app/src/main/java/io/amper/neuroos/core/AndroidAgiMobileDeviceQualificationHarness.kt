package io.amper.neuroos.core

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

class AndroidAgiMobileDeviceQualificationHarness(
    context: Context,
    private val runtime: AmperRuntime,
    private val deviceStatusSource: DeviceStatusSource,
    private val governor: ResourceGovernor
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "amper-sovereign/device-qualification")
    private val store: AgiMobileDeviceQualificationStore =
        FileAgiMobileDeviceQualificationStore(File(root, "device-evidence-v1.txt"))
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val subject: AgiMobileQualificationSubject by lazy(::installedSubject)
    private val deviceDigest: String by lazy(::currentDeviceDigest)

    fun onProcessStart(): AgiMobileDeviceQualificationSample? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        val expectedDigest = prefs.getString(KEY_PENDING_EXPECTED_DIGEST, null) ?: return null
        val pendingSubject = prefs.getString(KEY_PENDING_SUBJECT_DIGEST, null)
        val pendingDevice = prefs.getString(KEY_PENDING_DEVICE_DIGEST, null)
        if (
            pendingSubject != subject.canonicalDigest ||
            pendingDevice != deviceDigest
        ) {
            clearPendingRestart()
            Log.w(TAG, "Discarded restart checkpoint because artifact/device identity changed")
            return null
        }

        val previousProcessNonce = prefs.getString(KEY_PENDING_PROCESS_NONCE, null)
        val processChanged =
            previousProcessNonce != null &&
                previousProcessNonce != AndroidQualificationProcessIdentity.nonce
        if (!processChanged) return null

        val previousBoot = prefs.getInt(KEY_PENDING_BOOT_COUNT, -1)
        val previousElapsed = prefs.getLong(KEY_PENDING_ELAPSED_MS, -1L)
        val currentBoot = bootCount()
        val currentElapsed = SystemClock.elapsedRealtime()
        val bootChanged =
            (previousBoot >= 0 && currentBoot >= 0 && previousBoot != currentBoot) ||
                (previousElapsed >= 0L && currentElapsed < previousElapsed)

        val restored = runtime.agiMobileQualificationStore.latest()?.canonicalDigest
        val snapshot = deviceStatusSource.snapshot()
        val budget = governor.currentBudget()
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val kind = if (bootChanged) {
            AgiMobileDeviceSampleKind.REBOOT_RECOVERY
        } else {
            AgiMobileDeviceSampleKind.PROCESS_RESTART
        }
        val sample = AgiMobileDeviceQualificationSample(
            id = "restart-" + agiQualificationSha256(
                "$expectedDigest|$now|${AndroidQualificationProcessIdentity.nonce}"
            ).take(32),
            subjectDigest = subject.canonicalDigest,
            artifactDigest = subject.artifactDigest,
            deviceDigest = deviceDigest,
            kind = kind,
            scenario = if (bootChanged) "encrypted-runtime-after-reboot" else
                "encrypted-runtime-after-process-restart",
            batteryPercent = snapshot.batteryPercent,
            charging = snapshot.charging,
            availableMemoryMb = snapshot.availableMemoryMb,
            lowMemory = snapshot.lowMemory,
            storageFreeMb = snapshot.appStorageFreeMb,
            thermalStatus = snapshot.thermalStatus,
            governorMemoryMb = budget.memoryMb.coerceAtLeast(0),
            governorThermalClass = budget.thermalClass,
            workloadPassed = runCatching {
                runtime.tick("device qualification restart continuity")
            }.isSuccess,
            continuityExpectedDigest = expectedDigest,
            continuityRestoredDigest = restored,
            processChanged = true,
            bootChanged = bootChanged,
            observedAtEpochMs = now
        )
        store.append(sample)
        clearPendingRestart()
        Log.i(
            TAG,
            "Recorded ${kind.name} qualification sample passed=${sample.passed}"
        )
        return sample
    }

    fun handleCommand(command: String): String = when (command.trim().lowercase()) {
        COMMAND_START -> {
            startSession()
            "device qualification session started for ${subject.artifactDigest}"
        }
        COMMAND_RESOURCE -> {
            val sample = recordNextResourceStressSample()
            "resource sample ${sample.id} tier=${sample.stressTier} passed=${sample.passed}"
        }
        COMMAND_PREPARE_RESTART -> {
            val digest = prepareRestartCheckpoint()
            "restart checkpoint prepared digest=$digest"
        }
        COMMAND_STATUS -> statusSummary()
        COMMAND_FINALIZE -> {
            val report = finalizeQualification()
            "qualification verdict=${report.verdict} digest=${report.canonicalDigest}"
        }
        else -> error("unknown device qualification command: $command")
    }.also { Log.i(TAG, it) }

    fun startSession() {
        root.mkdirs()
        require(store.reset()) { "failed to reset device qualification evidence" }
        clearPendingRestart()
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SESSION_SUBJECT_DIGEST, subject.canonicalDigest)
            .putString(KEY_SESSION_ARTIFACT_DIGEST, subject.artifactDigest)
            .putString(KEY_SESSION_DEVICE_DIGEST, deviceDigest)
            .apply()
        File(root, "final-report.txt").delete()
    }

    fun recordNextResourceStressSample(): AgiMobileDeviceQualificationSample {
        requireActiveSession()
        val report = store.load()
        val existing = report?.samples.orEmpty().count {
            it.kind == AgiMobileDeviceSampleKind.RESOURCE_STRESS
        }
        val tier = existing % STRESS_MIB.size
        val requestedBytes = STRESS_MIB[tier].toLong() * MIB
        val before = deviceStatusSource.snapshot()
        val budget = governor.currentBudget()
        val safeBytes = (
            (before.availableMemoryMb - SAFETY_RESERVE_MIB)
                .coerceAtLeast(0L) * MIB / 4L
            ).coerceAtMost(MAX_STRESS_BYTES)
        val actualBytes = min(requestedBytes, safeBytes)
        val learningDecision = runtime.reflexLearningResourcePolicy.evaluate(
            ReflexLearningDemand(
                freshCandidates = 64,
                replayCandidates = 32,
                learningValue = 0.80,
                hardExamples = 16,
                disagreementExamples = 16,
                novelCapabilities = 1
            )
        )
        val severePressure =
            budget.thermalClass >= ResourceGovernorReflexLearningResourcePolicy.SEVERE_THERMAL_CLASS ||
                budget.memoryMb < ResourceGovernorReflexLearningResourcePolicy.CRITICAL_MEMORY_MB ||
                before.lowMemory ||
                before.appStorageFreeMb <
                    ResourceGovernorReflexLearningResourcePolicy.MIN_STORAGE_FREE_MB ||
                (
                    before.charging != true &&
                        before.batteryPercent != null &&
                        before.batteryPercent <=
                            ResourceGovernorReflexLearningResourcePolicy.MIN_BATTERY_PERCENT
                    )
        val policyConsistent = !severePressure || !learningDecision.allowTraining

        val workloadPassed = runCatching {
            val stress = if (actualBytes > 0L) {
                ByteArray(actualBytes.toInt())
            } else {
                ByteArray(0)
            }
            var i = 0
            while (i < stress.size) {
                stress[i] = (i and 0x7f).toByte()
                i += PAGE_TOUCH_BYTES
            }
            val tick = runtime.tick(
                "device qualification resource stress tier $tier"
            )
            require(tick.kernelState.isNotBlank())
            if (stress.isNotEmpty()) {
                require(stress[0].toInt() == 0)
            }
        }.isSuccess

        val after = deviceStatusSource.snapshot()
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        val sample = AgiMobileDeviceQualificationSample(
            id = "resource-" + agiQualificationSha256(
                "${subject.canonicalDigest}|$deviceDigest|$tier|$existing|$now"
            ).take(32),
            subjectDigest = subject.canonicalDigest,
            artifactDigest = subject.artifactDigest,
            deviceDigest = deviceDigest,
            kind = AgiMobileDeviceSampleKind.RESOURCE_STRESS,
            scenario = "tier-$tier-${STRESS_MIB[tier]}mib",
            stressTier = tier,
            requestedStressBytes = requestedBytes,
            actualStressBytes = actualBytes,
            batteryPercent = after.batteryPercent,
            charging = after.charging,
            availableMemoryMb = after.availableMemoryMb,
            lowMemory = after.lowMemory,
            storageFreeMb = after.appStorageFreeMb,
            thermalStatus = after.thermalStatus,
            governorMemoryMb = budget.memoryMb.coerceAtLeast(0),
            governorThermalClass = budget.thermalClass,
            policyAllowsTraining = learningDecision.allowTraining,
            policyConsistent = policyConsistent,
            workloadPassed = workloadPassed,
            observedAtEpochMs = now
        )
        store.append(sample)
        return sample
    }

    fun recordResourceBatch(sampleCount: Int = 4): List<AgiMobileDeviceQualificationSample> {
        require(sampleCount in 1..8)
        return List(sampleCount) { recordNextResourceStressSample() }
    }

    fun statusSummaryOrIdle(): String =
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            runCatching { statusSummary() }
                .getOrElse { "qualification status unavailable: " + (it.message ?: "unknown") }
        } else {
            "Qualification idle · start a new APK-bound session"
        }

    fun prepareAndTerminateForProcessRestart(): Nothing {
        prepareRestartCheckpoint()
        Process.killProcess(Process.myPid())
        kotlin.system.exitProcess(0)
    }

    fun prepareRestartCheckpoint(): String {
        requireActiveSession()
        require(
            prefs.getString(KEY_PENDING_EXPECTED_DIGEST, null) == null
        ) { "a restart qualification checkpoint is already pending" }
        val checkpoint = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes = emptyList()
        )
        val persisted = prefs.edit()
            .putString(KEY_PENDING_EXPECTED_DIGEST, checkpoint.canonicalDigest)
            .putString(KEY_PENDING_SUBJECT_DIGEST, subject.canonicalDigest)
            .putString(KEY_PENDING_DEVICE_DIGEST, deviceDigest)
            .putString(
                KEY_PENDING_PROCESS_NONCE,
                AndroidQualificationProcessIdentity.nonce
            )
            .putInt(KEY_PENDING_PID, Process.myPid())
            .putInt(KEY_PENDING_BOOT_COUNT, bootCount())
            .putLong(KEY_PENDING_ELAPSED_MS, SystemClock.elapsedRealtime())
            .commit()
        require(persisted) { "failed to persist restart qualification checkpoint" }
        return checkpoint.canonicalDigest
    }

    fun statusSummary(): String {
        requireActiveSession()
        val samples = store.load()?.samples.orEmpty()
        val resource = samples.count {
            it.kind == AgiMobileDeviceSampleKind.RESOURCE_STRESS
        }
        val process = samples.count {
            it.kind == AgiMobileDeviceSampleKind.PROCESS_RESTART
        }
        val reboot = samples.count {
            it.kind == AgiMobileDeviceSampleKind.REBOOT_RECOVERY
        }
        val passed = samples.count { it.passed }
        return "subject=${subject.artifactDigest};resource=$resource/" +
            "${AgiMobileDeviceQualificationProbes.MIN_RESOURCE_SAMPLES};" +
            "process_restart=$process/" +
            "${AgiMobileDeviceQualificationProbes.MIN_PROCESS_RESTART_SAMPLES};" +
            "reboot=$reboot/" +
            "${AgiMobileDeviceQualificationProbes.MIN_REBOOT_SAMPLES};" +
            "total_restart=${process + reboot}/" +
            "${AgiMobileDeviceQualificationProbes.MIN_RESTART_SAMPLES};" +
            "passed=$passed/${samples.size}"
    }

    fun finalizeQualification(): AgiMobileQualificationReport {
        requireActiveSession()
        val deviceReport = requireNotNull(store.load()) {
            "device qualification evidence is empty"
        }
        require(deviceReport.subjectDigest == subject.canonicalDigest)
        require(deviceReport.artifactDigest == subject.artifactDigest)
        require(deviceReport.deviceDigest == deviceDigest)

        val resourceCount = deviceReport.samples.count {
            it.kind == AgiMobileDeviceSampleKind.RESOURCE_STRESS
        }
        val processCount = deviceReport.samples.count {
            it.kind == AgiMobileDeviceSampleKind.PROCESS_RESTART
        }
        val rebootCount = deviceReport.samples.count {
            it.kind == AgiMobileDeviceSampleKind.REBOOT_RECOVERY
        }
        require(
            resourceCount >= AgiMobileDeviceQualificationProbes.MIN_RESOURCE_SAMPLES
        ) { "need at least 16 physical resource samples" }
        require(
            processCount + rebootCount >=
                AgiMobileDeviceQualificationProbes.MIN_RESTART_SAMPLES
        ) { "need at least 16 physical restart/reboot samples" }
        require(
            processCount >=
                AgiMobileDeviceQualificationProbes.MIN_PROCESS_RESTART_SAMPLES
        ) { "need at least 8 process-restart samples" }
        require(
            rebootCount >= AgiMobileDeviceQualificationProbes.MIN_REBOOT_SAMPLES
        ) { "need at least 4 reboot-recovery samples" }

        val cognitive = runtime.agiMobileCognitiveQualificationProbes(subject)
        val longHorizon = runtime.agiMobileLongHorizonQualificationProbes(subject)
        val learningEvolution =
            runtime.agiMobileLearningEvolutionQualificationProbes(subject)
        val authority = runtime.agiMobileAuthorityQualificationProbes(subject)
        val device = AgiMobileDeviceQualificationProbes.fromReport(
            subject = subject,
            report = deviceReport
        )

        val report = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes =
                cognitive.probes +
                    longHorizon.probes +
                    learningEvolution.probes +
                    authority.probes +
                    device.probes
        )
        root.mkdirs()
        File(root, "final-report.txt").writeText(
            buildString {
                appendLine("suite=${report.suiteId}")
                appendLine("suite_digest=${report.suiteDigest}")
                appendLine("subject_revision=${report.subjectRevision}")
                appendLine("artifact_sha256=${report.subjectArtifactDigest}")
                appendLine("device_digest=$deviceDigest")
                appendLine("verdict=${report.verdict}")
                appendLine("aggregate_score=${report.aggregateScore ?: "~"}")
                appendLine(
                    "hard_blockers=" +
                        report.hardBlockers.joinToString(",") { it.name }
                )
                report.results.sortedBy { it.domain.name }.forEach {
                    appendLine(
                        "domain=${it.domain.name};status=${it.status};" +
                            "score=${it.score ?: "~"};samples=${it.samples};" +
                            "failure=${it.failureCode ?: "~"}"
                    )
                }
                appendLine("report_digest=${report.canonicalDigest}")
                appendLine("device_evidence_digest=${deviceReport.canonicalDigest}")
            }
        )
        return report
    }

    private fun requireActiveSession() {
        require(prefs.getBoolean(KEY_ACTIVE, false)) {
            "device qualification session is not active"
        }
        require(
            prefs.getString(KEY_SESSION_SUBJECT_DIGEST, null) ==
                subject.canonicalDigest
        ) { "qualification session belongs to another APK subject" }
        require(
            prefs.getString(KEY_SESSION_DEVICE_DIGEST, null) == deviceDigest
        ) { "qualification session belongs to another device" }
    }

    private fun clearPendingRestart() {
        prefs.edit()
            .remove(KEY_PENDING_EXPECTED_DIGEST)
            .remove(KEY_PENDING_SUBJECT_DIGEST)
            .remove(KEY_PENDING_DEVICE_DIGEST)
            .remove(KEY_PENDING_PROCESS_NONCE)
            .remove(KEY_PENDING_PID)
            .remove(KEY_PENDING_BOOT_COUNT)
            .remove(KEY_PENDING_ELAPSED_MS)
            .apply()
    }

    private fun installedSubject(): AgiMobileQualificationSubject {
        val packageInfo = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            0
        )
        val revision = buildString {
            append("android:")
            append(appContext.packageName).append(':')
            append(packageInfo.longVersionCode).append(':')
            append(packageInfo.versionName ?: "unknown")
        }
        return AgiMobileQualificationSubject(
            revision = revision,
            artifactDigest = sha256File(File(appContext.applicationInfo.sourceDir))
        )
    }

    private fun currentDeviceDigest(): String =
        agiQualificationSha256(
            listOf(
                "ANDROID_DEVICE_V1",
                Build.FINGERPRINT ?: "",
                Build.MANUFACTURER ?: "",
                Build.MODEL ?: "",
                Build.DEVICE ?: "",
                Build.VERSION.SDK_INT.toString(),
                Build.SUPPORTED_ABIS.joinToString(",")
            ).joinToString("|")
        )

    private fun bootCount(): Int =
        runCatching {
            Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.BOOT_COUNT
            )
        }.getOrDefault(-1)

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val EXTRA_COMMAND = "amper_device_qualification_command"
        const val COMMAND_START = "start"
        const val COMMAND_RESOURCE = "resource"
        const val COMMAND_PREPARE_RESTART = "prepare-restart"
        const val COMMAND_STATUS = "status"
        const val COMMAND_FINALIZE = "finalize"

        private const val TAG = "AMPER-DeviceQualification"
        private const val PREFS = "amper-device-qualification-v1"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSION_SUBJECT_DIGEST = "session_subject_digest"
        private const val KEY_SESSION_ARTIFACT_DIGEST = "session_artifact_digest"
        private const val KEY_SESSION_DEVICE_DIGEST = "session_device_digest"
        private const val KEY_PENDING_EXPECTED_DIGEST = "pending_expected_digest"
        private const val KEY_PENDING_SUBJECT_DIGEST = "pending_subject_digest"
        private const val KEY_PENDING_DEVICE_DIGEST = "pending_device_digest"
        private const val KEY_PENDING_PROCESS_NONCE = "pending_process_nonce"
        private const val KEY_PENDING_PID = "pending_pid"
        private const val KEY_PENDING_BOOT_COUNT = "pending_boot_count"
        private const val KEY_PENDING_ELAPSED_MS = "pending_elapsed_ms"

        private val STRESS_MIB = intArrayOf(0, 16, 32, 64)
        private const val MIB = 1024L * 1024L
        private const val MAX_STRESS_BYTES = 64L * MIB
        private const val SAFETY_RESERVE_MIB = 512L
        private const val PAGE_TOUCH_BYTES = 4096
    }
}

private object AndroidQualificationProcessIdentity {
    val nonce: String = UUID.randomUUID().toString()
}
