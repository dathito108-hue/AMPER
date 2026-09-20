package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileAuthorityQualificationProbesTest {
    private val subject = AgiMobileQualificationSubject(
        revision = "authority-qualification-subject-v1",
        artifactDigest = "9".repeat(64)
    )

    @Test
    fun authorityAttackPackPassesAll32FailClosedSamples() {
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileAuthorityQualificationProbes(subject)

        val evidence = pack.authorityInvariants.evaluate(subject).getOrThrow()

        assertEquals(AgiMobileQualificationDomain.AUTHORITY_INVARIANTS, evidence.domain)
        assertEquals(
            AgiMobileAuthorityQualificationProbes.AUTHORITY_SAMPLE_COUNT,
            evidence.samples
        )
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertEquals(subject.canonicalDigest, evidence.subjectDigest)
        assertFalse(evidence.authorityBearing)

        val report = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes = pack.probes
        )
        val authority = report.results.single {
            it.domain == AgiMobileQualificationDomain.AUTHORITY_INVARIANTS
        }
        assertEquals(AgiMobileDomainStatus.QUALIFIED, authority.status)
        assertFalse(
            AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.RESTART_RECOVERY in report.hardBlockers
        )
    }
}
