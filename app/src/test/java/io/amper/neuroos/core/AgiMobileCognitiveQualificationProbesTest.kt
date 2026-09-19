package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileCognitiveQualificationProbesTest {
    private val subject = AgiMobileQualificationSubject(
        revision = "cognitive-probe-subject-v1",
        artifactDigest = "a".repeat(64)
    )

    @Test
    fun canonicalSystem1ProbeMeasures64RealRoutingDecisions() {
        var nanos = 0L
        val clock = {
            nanos += 1_000_000L
            nanos
        }
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileCognitiveQualificationProbes(
            subject = subject,
            monotonicNanos = clock
        )

        val evidence = pack.system1.evaluate(subject).getOrThrow()

        assertEquals(AgiMobileQualificationDomain.SYSTEM1_FAST_PATH, evidence.domain)
        assertEquals(AgiMobileCognitiveQualificationProbes.SYSTEM1_SAMPLE_COUNT, evidence.samples)
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertEquals(subject.canonicalDigest, evidence.subjectDigest)
        assertFalse(evidence.authorityBearing)
    }

    @Test
    fun nativeSystem2ProbeCoversEvidenceGatheringReuseAndOpenDeliberation() {
        val pack = AmperRuntime.reference()
            .agiMobileCognitiveQualificationProbes(subject)

        val evidence = pack.system2.evaluate(subject).getOrThrow()

        assertEquals(
            AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
            evidence.domain
        )
        assertEquals(AgiMobileCognitiveQualificationProbes.SYSTEM2_SAMPLE_COUNT, evidence.samples)
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertFalse(evidence.authorityBearing)
    }

    @Test
    fun generalizationProbeUsesRealCrossContextModelAndLiveDescriptorGate() {
        val pack = AmperRuntime.reference()
            .agiMobileCognitiveQualificationProbes(subject)

        val evidence = pack.generalization.evaluate(subject).getOrThrow()

        assertEquals(AgiMobileQualificationDomain.GENERALIZATION, evidence.domain)
        assertEquals(
            AgiMobileCognitiveQualificationProbes.GENERALIZATION_SAMPLE_COUNT,
            evidence.samples
        )
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertFalse(evidence.authorityBearing)
    }

    @Test
    fun cognitiveProbePackQualifiesOnlyItsThreeMeasuredDomains() {
        var nanos = 0L
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileCognitiveQualificationProbes(
            subject = subject,
            monotonicNanos = {
                nanos += 1_000_000L
                nanos
            }
        )

        val report = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes = pack.probes
        )

        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
        val measured = report.results.filter {
            it.domain in setOf(
                AgiMobileQualificationDomain.SYSTEM1_FAST_PATH,
                AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
                AgiMobileQualificationDomain.GENERALIZATION
            )
        }
        assertEquals(3, measured.size)
        assertTrue(measured.all { it.status == AgiMobileDomainStatus.QUALIFIED })
        assertEquals(
            7,
            report.results.count { it.status == AgiMobileDomainStatus.UNMEASURED }
        )
        assertTrue(
            AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers
        )
    }

    @Test
    fun probeEvidenceIsRejectedForAnotherSubject() {
        var nanos = 0L
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileCognitiveQualificationProbes(
            subject = subject,
            monotonicNanos = {
                nanos += 1_000_000L
                nanos
            }
        )
        val other = AgiMobileQualificationSubject(
            revision = "different-subject",
            artifactDigest = "b".repeat(64)
        )

        val report = runtime.agiMobileQualificationRunner().run(
            subject = other,
            probes = pack.probes
        )

        assertTrue(report.results.all { it.status == AgiMobileDomainStatus.UNMEASURED })
        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
    }
}
