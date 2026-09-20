package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileLearningEvolutionQualificationProbesTest {
    private val subject = AgiMobileQualificationSubject(
        revision = "learning-evolution-qualification-subject-v1",
        artifactDigest = "f".repeat(64)
    )

    @Test
    fun firstThreeQualificationPacksQualifyExactlySevenDomains() {
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
        val learningEvolution =
            runtime.agiMobileLearningEvolutionQualificationProbes(subject)

        val report = runtime.agiMobileQualificationRunner().run(
            subject = subject,
            probes = cognitive.probes + longHorizon.probes + learningEvolution.probes
        )

        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
        val expectedQualified = setOf(
            AgiMobileQualificationDomain.SYSTEM1_FAST_PATH,
            AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
            AgiMobileQualificationDomain.GENERALIZATION,
            AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION,
            AgiMobileQualificationDomain.MEMORY_WORLD_MODEL,
            AgiMobileQualificationDomain.SELF_LEARNING,
            AgiMobileQualificationDomain.SELF_EVOLUTION
        )
        assertEquals(
            expectedQualified,
            report.results
                .filter { it.status == AgiMobileDomainStatus.QUALIFIED }
                .mapTo(linkedSetOf()) { it.domain }
        )
        assertEquals(
            setOf(
                AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE,
                AgiMobileQualificationDomain.RESTART_RECOVERY,
                AgiMobileQualificationDomain.AUTHORITY_INVARIANTS
            ),
            report.results
                .filter { it.status == AgiMobileDomainStatus.UNMEASURED }
                .mapTo(linkedSetOf()) { it.domain }
        )

        val learning = report.results.single {
            it.domain == AgiMobileQualificationDomain.SELF_LEARNING
        }
        assertEquals(AgiMobileDomainStatus.QUALIFIED, learning.status)
        assertEquals(
            AgiMobileLearningEvolutionQualificationProbes.SELF_LEARNING_SAMPLE_COUNT,
            learning.samples
        )
        assertEquals(1.0, requireNotNull(learning.score), 0.0001)

        val evolution = report.results.single {
            it.domain == AgiMobileQualificationDomain.SELF_EVOLUTION
        }
        assertEquals(AgiMobileDomainStatus.QUALIFIED, evolution.status)
        assertEquals(
            AgiMobileLearningEvolutionQualificationProbes.SELF_EVOLUTION_SAMPLE_COUNT,
            evolution.samples
        )
        assertEquals(1.0, requireNotNull(evolution.score), 0.0001)

        assertFalse(
            AgiMobileQualificationDomain.SELF_EVOLUTION in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.RESTART_RECOVERY in report.hardBlockers
        )
        assertTrue(
            AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers
        )
    }
}
