package io.amper.neuroos.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileDeviceQualificationTest {
    private val subject = AgiMobileQualificationSubject(
        revision = "synthetic-device-contract-test",
        artifactDigest = "7".repeat(64)
    )
    private val deviceDigest = "8".repeat(64)

    @Test
    fun codecAndProbeContractAcceptCompleteSyntheticFixtureWithoutClaimingHardwareRun() {
        val root = createTempDir(prefix = "amper-device-qualification-")
        val store = FileAgiMobileDeviceQualificationStore(
            File(root, "device-evidence.txt")
        )
        val now = 10_000L

        repeat(16) { index ->
            store.append(
                AgiMobileDeviceQualificationSample(
                    id = "resource-$index",
                    subjectDigest = subject.canonicalDigest,
                    artifactDigest = subject.artifactDigest,
                    deviceDigest = deviceDigest,
                    kind = AgiMobileDeviceSampleKind.RESOURCE_STRESS,
                    scenario = "contract-fixture-tier-${index % 4}",
                    stressTier = index % 4,
                    requestedStressBytes = (index % 4) * 16L * 1024L * 1024L,
                    actualStressBytes = (index % 4) * 16L * 1024L * 1024L,
                    batteryPercent = 80,
                    charging = true,
                    availableMemoryMb = 2048,
                    storageFreeMb = 4096,
                    thermalStatus = 0,
                    governorMemoryMb = 1024,
                    governorThermalClass = 0,
                    policyAllowsTraining = true,
                    policyConsistent = true,
                    workloadPassed = true,
                    observedAtEpochMs = now + index
                )
            )
        }

        repeat(12) { index ->
            store.append(
                restartSample(
                    id = "process-$index",
                    kind = AgiMobileDeviceSampleKind.PROCESS_RESTART,
                    bootChanged = false,
                    observedAt = now + 100 + index
                )
            )
        }
        repeat(4) { index ->
            store.append(
                restartSample(
                    id = "reboot-$index",
                    kind = AgiMobileDeviceSampleKind.REBOOT_RECOVERY,
                    bootChanged = true,
                    observedAt = now + 200 + index
                )
            )
        }

        val report = requireNotNull(store.load())
        val probes = AgiMobileDeviceQualificationProbes.fromReport(subject, report)

        val resource = probes.mobileResourceResilience.evaluate(subject).getOrThrow()
        val restart = probes.restartRecovery.evaluate(subject).getOrThrow()

        assertEquals(16, resource.samples)
        assertEquals(1.0, resource.score, 0.0001)
        assertEquals(resource.assertionsTotal, resource.assertionsPassed)
        assertEquals(16, restart.samples)
        assertEquals(1.0, restart.score, 0.0001)
        assertEquals(restart.assertionsTotal, restart.assertionsPassed)
        assertFalse(report.authorityBearing)
        assertTrue(report.canonicalDigest.matches(Regex("[0-9a-f]{64}")))

        root.deleteRecursively()
    }

    @Test
    fun deviceEvidenceCannotCrossArtifactBoundary() {
        val report = AgiMobileDeviceQualificationReport(
            subjectDigest = subject.canonicalDigest,
            artifactDigest = subject.artifactDigest,
            deviceDigest = deviceDigest,
            collector = AgiMobileDeviceQualificationReport.COLLECTOR,
            samples = listOf(
                AgiMobileDeviceQualificationSample(
                    id = "resource-only",
                    subjectDigest = subject.canonicalDigest,
                    artifactDigest = subject.artifactDigest,
                    deviceDigest = deviceDigest,
                    kind = AgiMobileDeviceSampleKind.RESOURCE_STRESS,
                    scenario = "contract-fixture",
                    workloadPassed = true,
                    observedAtEpochMs = 1L
                )
            ),
            createdAtEpochMs = 1L
        )
        val other = AgiMobileQualificationSubject(
            revision = "other-artifact",
            artifactDigest = "6".repeat(64)
        )

        val probe =
            AgiMobileDeviceQualificationProbes.fromReport(other, report)
                .mobileResourceResilience

        assertTrue(probe.evaluate(other).isFailure)
    }

    private fun restartSample(
        id: String,
        kind: AgiMobileDeviceSampleKind,
        bootChanged: Boolean,
        observedAt: Long
    ): AgiMobileDeviceQualificationSample {
        val continuity = agiQualificationSha256("continuity-$id")
        return AgiMobileDeviceQualificationSample(
            id = id,
            subjectDigest = subject.canonicalDigest,
            artifactDigest = subject.artifactDigest,
            deviceDigest = deviceDigest,
            kind = kind,
            scenario = "contract-fixture-restart",
            batteryPercent = 80,
            charging = true,
            availableMemoryMb = 2048,
            storageFreeMb = 4096,
            thermalStatus = 0,
            governorMemoryMb = 1024,
            governorThermalClass = 0,
            workloadPassed = true,
            continuityExpectedDigest = continuity,
            continuityRestoredDigest = continuity,
            processChanged = true,
            bootChanged = bootChanged,
            observedAtEpochMs = observedAt
        )
    }
}
