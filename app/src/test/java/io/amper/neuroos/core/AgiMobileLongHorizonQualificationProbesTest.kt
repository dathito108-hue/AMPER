package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileLongHorizonQualificationProbesTest {
    private val subject = AgiMobileQualificationSubject(
        revision = "long-horizon-memory-world-subject-v1",
        artifactDigest = "c".repeat(64)
    )

    @Test
    fun longHorizonProbeExecutes16FourStepPersistedGovernedPlans() {
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileLongHorizonQualificationProbes(subject)

        val evidence = pack.longHorizonExecution.evaluate(subject).getOrThrow()

        assertEquals(
            AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION,
            evidence.domain
        )
        assertEquals(
            AgiMobileLongHorizonQualificationProbes.LONG_HORIZON_SAMPLE_COUNT,
            evidence.samples
        )
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertEquals(subject.canonicalDigest, evidence.subjectDigest)
        assertFalse(evidence.authorityBearing)
    }

    @Test
    fun memoryWorldProbeMeasures32SemanticRetrievalTransitionPredictionCycles() {
        val runtime = AmperRuntime.reference()
        val pack = runtime.agiMobileLongHorizonQualificationProbes(subject)

        val evidence = pack.memoryWorldModel.evaluate(subject).getOrThrow()

        assertEquals(
            AgiMobileQualificationDomain.MEMORY_WORLD_MODEL,
            evidence.domain
        )
        assertEquals(
            AgiMobileLongHorizonQualificationProbes.MEMORY_WORLD_SAMPLE_COUNT,
            evidence.samples
        )
        assertEquals(1.0, evidence.score, 0.0001)
        assertEquals(evidence.assertionsTotal, evidence.assertionsPassed)
        assertEquals(subject.canonicalDigest, evidence.subjectDigest)
        assertFalse(evidence.authorityBearing)
    }

    @Test
    fun firstTwoQualificationPacksQualifyExactlyFiveDomains() {
        var nanos = 0L
        val runtime = AmperRuntime.reference()
        val cognitive = runtime.agiMobileCognitiveQualificationProbes(
            subject = subject,
            monotonicNanos = {
                nanos += 1_000_000L
                nanos
            }
        )
        val longHorizon = runtime.agiMobileLongHorizonQualificationProbes(subject)

        val report = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes = cognitive.probes + longHorizon.probes
        )

        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
        val expectedQualified = setOf(
            AgiMobileQualificationDomain.SYSTEM1_FAST_PATH,
            AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
            AgiMobileQualificationDomain.GENERALIZATION,
            AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION,
            AgiMobileQualificationDomain.MEMORY_WORLD_MODEL
        )
        assertEquals(
            expectedQualified,
            report.results
                .filter { it.status == AgiMobileDomainStatus.QUALIFIED }
                .mapTo(linkedSetOf()) { it.domain }
        )
        assertEquals(
            5,
            report.results.count { it.status == AgiMobileDomainStatus.UNMEASURED }
        )
        assertTrue(
            AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.RESTART_RECOVERY in report.hardBlockers
        )
    }
}
