package io.amper.neuroos.core

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class AgiMobileDeviceSampleKind {
    RESOURCE_STRESS,
    PROCESS_RESTART,
    REBOOT_RECOVERY
}

data class AgiMobileDeviceQualificationSample(
    val id: String,
    val subjectDigest: String,
    val artifactDigest: String,
    val deviceDigest: String,
    val kind: AgiMobileDeviceSampleKind,
    val scenario: String,
    val stressTier: Int = 0,
    val requestedStressBytes: Long = 0L,
    val actualStressBytes: Long = 0L,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val availableMemoryMb: Long = 0L,
    val lowMemory: Boolean = false,
    val storageFreeMb: Long = 0L,
    val thermalStatus: Int? = null,
    val governorMemoryMb: Int = 0,
    val governorThermalClass: Int = 0,
    val policyAllowsTraining: Boolean? = null,
    val policyConsistent: Boolean = true,
    val workloadPassed: Boolean = false,
    val continuityExpectedDigest: String? = null,
    val continuityRestoredDigest: String? = null,
    val processChanged: Boolean = false,
    val bootChanged: Boolean = false,
    val observedAtEpochMs: Long
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(subjectDigest.matches(SHA256))
        require(artifactDigest.matches(SHA256))
        require(deviceDigest.matches(SHA256))
        require(scenario.isNotBlank() && scenario.length <= 128)
        require(stressTier in 0..3)
        require(requestedStressBytes >= 0L)
        require(actualStressBytes >= 0L)
        require(batteryPercent == null || batteryPercent in 0..100)
        require(availableMemoryMb >= 0L)
        require(storageFreeMb >= 0L)
        require(governorMemoryMb >= 0)
        require(observedAtEpochMs >= 0L)
        require(
            continuityExpectedDigest == null ||
                continuityExpectedDigest.matches(SHA256)
        )
        require(
            continuityRestoredDigest == null ||
                continuityRestoredDigest.matches(SHA256)
        )
        when (kind) {
            AgiMobileDeviceSampleKind.RESOURCE_STRESS -> {
                require(continuityExpectedDigest == null)
                require(continuityRestoredDigest == null)
            }
            AgiMobileDeviceSampleKind.PROCESS_RESTART,
            AgiMobileDeviceSampleKind.REBOOT_RECOVERY -> {
                require(continuityExpectedDigest != null)
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    val passed: Boolean
        get() = when (kind) {
            AgiMobileDeviceSampleKind.RESOURCE_STRESS ->
                workloadPassed && policyConsistent
            AgiMobileDeviceSampleKind.PROCESS_RESTART ->
                processChanged &&
                    !bootChanged &&
                    continuityExpectedDigest == continuityRestoredDigest
            AgiMobileDeviceSampleKind.REBOOT_RECOVERY ->
                processChanged &&
                    bootChanged &&
                    continuityExpectedDigest == continuityRestoredDigest
        }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class AgiMobileDeviceQualificationReport(
    val subjectDigest: String,
    val artifactDigest: String,
    val deviceDigest: String,
    val collector: String,
    val samples: List<AgiMobileDeviceQualificationSample>,
    val createdAtEpochMs: Long
) {
    init {
        require(subjectDigest.matches(SHA256))
        require(artifactDigest.matches(SHA256))
        require(deviceDigest.matches(SHA256))
        require(collector == COLLECTOR)
        require(samples.map { it.id }.distinct().size == samples.size)
        require(samples.all { it.subjectDigest == subjectDigest })
        require(samples.all { it.artifactDigest == artifactDigest })
        require(samples.all { it.deviceDigest == deviceDigest })
        require(createdAtEpochMs >= 0L)
    }

    val canonicalDigest: String
        get() = agiQualificationSha256(
            buildString {
                append("AMQ_DEVICE_REPORT_V1|")
                append(subjectDigest).append('|')
                append(artifactDigest).append('|')
                append(deviceDigest).append('|')
                append(collector).append('|')
                append(createdAtEpochMs)
                samples.sortedWith(
                    compareBy<AgiMobileDeviceQualificationSample> { it.observedAtEpochMs }
                        .thenBy { it.id }
                ).forEach { sample ->
                    append('|').append(AgiMobileDeviceQualificationCodec.encodeSample(sample))
                }
            }
        )

    val authorityBearing: Boolean
        get() = false

    companion object {
        const val COLLECTOR = "android-device-v1"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface AgiMobileDeviceQualificationStore {
    fun load(): AgiMobileDeviceQualificationReport?
    fun append(sample: AgiMobileDeviceQualificationSample): AgiMobileDeviceQualificationReport
    fun reset(): Boolean
}

class FileAgiMobileDeviceQualificationStore(
    private val file: File
) : AgiMobileDeviceQualificationStore {
    @Synchronized
    override fun load(): AgiMobileDeviceQualificationReport? {
        if (!file.isFile) return null
        return AgiMobileDeviceQualificationCodec.decodeReport(file.readText())
    }

    @Synchronized
    override fun append(
        sample: AgiMobileDeviceQualificationSample
    ): AgiMobileDeviceQualificationReport {
        val current = load()
        val samples = when {
            current == null -> listOf(sample)
            current.subjectDigest != sample.subjectDigest ||
                current.artifactDigest != sample.artifactDigest ||
                current.deviceDigest != sample.deviceDigest -> listOf(sample)
            current.samples.any { it.id == sample.id } -> current.samples
            else -> current.samples + sample
        }
        val report = AgiMobileDeviceQualificationReport(
            subjectDigest = sample.subjectDigest,
            artifactDigest = sample.artifactDigest,
            deviceDigest = sample.deviceDigest,
            collector = AgiMobileDeviceQualificationReport.COLLECTOR,
            samples = samples,
            createdAtEpochMs =
                samples.minOfOrNull { it.observedAtEpochMs } ?: sample.observedAtEpochMs
        )
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(AgiMobileDeviceQualificationCodec.encodeReport(report))
        if (file.exists()) {
            require(file.delete()) { "failed to replace device qualification evidence" }
        }
        require(tmp.renameTo(file)) { "failed to publish device qualification evidence" }
        return report
    }

    @Synchronized
    override fun reset(): Boolean =
        !file.exists() || file.delete()
}

data class AgiMobileDeviceProbePack(
    val mobileResourceResilience: AgiMobileQualificationProbe,
    val restartRecovery: AgiMobileQualificationProbe
) {
    val probes: List<AgiMobileQualificationProbe>
        get() = listOf(mobileResourceResilience, restartRecovery)
}

object AgiMobileDeviceQualificationProbes {
    const val MIN_RESOURCE_SAMPLES = 16
    const val MIN_RESTART_SAMPLES = 16
    const val MIN_REBOOT_SAMPLES = 4
    const val MIN_PROCESS_RESTART_SAMPLES = 8

    fun fromReport(
        subject: AgiMobileQualificationSubject,
        report: AgiMobileDeviceQualificationReport
    ): AgiMobileDeviceProbePack =
        AgiMobileDeviceProbePack(
            mobileResourceResilience = resourceProbe(subject, report),
            restartRecovery = restartProbe(subject, report)
        )

    private fun resourceProbe(
        subject: AgiMobileQualificationSubject,
        report: AgiMobileDeviceQualificationReport
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "mobile-resource-resilience",
            domain = AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE
        ) {
            runCatching {
                validateReport(subject, report)
                val samples = report.samples.filter {
                    it.kind == AgiMobileDeviceSampleKind.RESOURCE_STRESS
                }
                val passed = samples.count { it.passed }
                val score =
                    if (samples.isEmpty()) 0.0
                    else passed.toDouble() / samples.size.toDouble()
                val tierCounts = samples.groupingBy { it.stressTier }.eachCount()
                val assertions = listOf(
                    samples.size >= MIN_RESOURCE_SAMPLES,
                    (0..3).all { (tierCounts[it] ?: 0) >= 4 },
                    samples.all { it.requestedStressBytes >= 0L },
                    samples.all { it.actualStressBytes <= it.requestedStressBytes },
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain ==
                                AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE
                        }.minScore
                )
                AgiMobileQualificationEvidence(
                    probeId = "mobile-resource-resilience",
                    domain = AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE,
                    score = score,
                    samples = samples.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(
                        "RESOURCE|" + report.canonicalDigest
                    ),
                    observedAtEpochMs =
                        samples.maxOfOrNull { it.observedAtEpochMs }
                            ?: report.createdAtEpochMs
                )
            }
        }

    private fun restartProbe(
        subject: AgiMobileQualificationSubject,
        report: AgiMobileDeviceQualificationReport
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "restart-recovery",
            domain = AgiMobileQualificationDomain.RESTART_RECOVERY
        ) {
            runCatching {
                validateReport(subject, report)
                val samples = report.samples.filter {
                    it.kind == AgiMobileDeviceSampleKind.PROCESS_RESTART ||
                        it.kind == AgiMobileDeviceSampleKind.REBOOT_RECOVERY
                }
                val passed = samples.count { it.passed }
                val score =
                    if (samples.isEmpty()) 0.0
                    else passed.toDouble() / samples.size.toDouble()
                val processSamples = samples.count {
                    it.kind == AgiMobileDeviceSampleKind.PROCESS_RESTART
                }
                val rebootSamples = samples.count {
                    it.kind == AgiMobileDeviceSampleKind.REBOOT_RECOVERY
                }
                val assertions = listOf(
                    samples.size >= MIN_RESTART_SAMPLES,
                    processSamples >= MIN_PROCESS_RESTART_SAMPLES,
                    rebootSamples >= MIN_REBOOT_SAMPLES,
                    samples.all { it.processChanged },
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.RESTART_RECOVERY
                        }.minScore
                )
                AgiMobileQualificationEvidence(
                    probeId = "restart-recovery",
                    domain = AgiMobileQualificationDomain.RESTART_RECOVERY,
                    score = score,
                    samples = samples.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(
                        "RESTART|" + report.canonicalDigest
                    ),
                    observedAtEpochMs =
                        samples.maxOfOrNull { it.observedAtEpochMs }
                            ?: report.createdAtEpochMs
                )
            }
        }

    private fun validateReport(
        subject: AgiMobileQualificationSubject,
        report: AgiMobileDeviceQualificationReport
    ) {
        require(report.collector == AgiMobileDeviceQualificationReport.COLLECTOR)
        require(report.subjectDigest == subject.canonicalDigest)
        require(report.artifactDigest == subject.artifactDigest)
        require(report.samples.isNotEmpty())
    }
}

internal object AgiMobileDeviceQualificationCodec {
    private const val VERSION = "AMQDEV1"

    fun encodeReport(report: AgiMobileDeviceQualificationReport): String = buildString {
        append(VERSION).append('|')
        append(report.subjectDigest).append('|')
        append(report.artifactDigest).append('|')
        append(report.deviceDigest).append('|')
        append(report.collector).append('|')
        append(report.createdAtEpochMs).append('\n')
        report.samples
            .sortedWith(
                compareBy<AgiMobileDeviceQualificationSample> { it.observedAtEpochMs }
                    .thenBy { it.id }
            )
            .forEach { append(encodeSample(it)).append('\n') }
    }.trimEnd()

    fun decodeReport(content: String): AgiMobileDeviceQualificationReport {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "empty device qualification report" }
        val header = lines.first().split('|')
        require(header.size == 6 && header[0] == VERSION) {
            "unsupported device qualification report"
        }
        return AgiMobileDeviceQualificationReport(
            subjectDigest = header[1],
            artifactDigest = header[2],
            deviceDigest = header[3],
            collector = header[4],
            samples = lines.drop(1).map(::decodeSample),
            createdAtEpochMs = header[5].toLong()
        )
    }

    fun encodeSample(sample: AgiMobileDeviceQualificationSample): String =
        listOf(
            enc(sample.id),
            sample.subjectDigest,
            sample.artifactDigest,
            sample.deviceDigest,
            sample.kind.name,
            enc(sample.scenario),
            sample.stressTier.toString(),
            sample.requestedStressBytes.toString(),
            sample.actualStressBytes.toString(),
            sample.batteryPercent?.toString() ?: "~",
            sample.charging?.toString() ?: "~",
            sample.availableMemoryMb.toString(),
            sample.lowMemory.toString(),
            sample.storageFreeMb.toString(),
            sample.thermalStatus?.toString() ?: "~",
            sample.governorMemoryMb.toString(),
            sample.governorThermalClass.toString(),
            sample.policyAllowsTraining?.toString() ?: "~",
            sample.policyConsistent.toString(),
            sample.workloadPassed.toString(),
            sample.continuityExpectedDigest ?: "~",
            sample.continuityRestoredDigest ?: "~",
            sample.processChanged.toString(),
            sample.bootChanged.toString(),
            sample.observedAtEpochMs.toString()
        ).joinToString("|")

    private fun decodeSample(line: String): AgiMobileDeviceQualificationSample {
        val f = line.split('|')
        require(f.size == 25) { "malformed device qualification sample" }
        return AgiMobileDeviceQualificationSample(
            id = dec(f[0]),
            subjectDigest = f[1],
            artifactDigest = f[2],
            deviceDigest = f[3],
            kind = AgiMobileDeviceSampleKind.valueOf(f[4]),
            scenario = dec(f[5]),
            stressTier = f[6].toInt(),
            requestedStressBytes = f[7].toLong(),
            actualStressBytes = f[8].toLong(),
            batteryPercent = f[9].takeUnless { it == "~" }?.toInt(),
            charging = f[10].takeUnless { it == "~" }?.toBooleanStrict(),
            availableMemoryMb = f[11].toLong(),
            lowMemory = f[12].toBooleanStrict(),
            storageFreeMb = f[13].toLong(),
            thermalStatus = f[14].takeUnless { it == "~" }?.toInt(),
            governorMemoryMb = f[15].toInt(),
            governorThermalClass = f[16].toInt(),
            policyAllowsTraining = f[17].takeUnless { it == "~" }?.toBooleanStrict(),
            policyConsistent = f[18].toBooleanStrict(),
            workloadPassed = f[19].toBooleanStrict(),
            continuityExpectedDigest = f[20].takeUnless { it == "~" },
            continuityRestoredDigest = f[21].takeUnless { it == "~" },
            processChanged = f[22].toBooleanStrict(),
            bootChanged = f[23].toBooleanStrict(),
            observedAtEpochMs = f[24].toLong()
        )
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
