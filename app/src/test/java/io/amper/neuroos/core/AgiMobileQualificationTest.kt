package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgiMobileQualificationTest {
    private val suite = AgiMobileQualificationSuite.canonical()
    private val subject = AgiMobileQualificationSubject(
        revision = "qualification-subject-v1",
        artifactDigest = "a".repeat(64)
    )

    @Test
    fun allCanonicalDomainsMustQualifyBeforeProjectQualification() {
        val store = MemoryBackedAgiMobileQualificationStore(InMemoryMemoryOs())
        val runner = CanonicalAgiMobileQualificationRunner(
            suite = suite,
            store = store,
            clock = { 10_000L }
        )

        val report = runner.run(
            subject = subject,
            probes = suite.criteria.map { criterion ->
                passingProbe(criterion)
            }
        )

        assertEquals(AgiMobileQualificationVerdict.QUALIFIED, report.verdict)
        assertTrue(report.results.all { it.status == AgiMobileDomainStatus.QUALIFIED })
        assertTrue(report.hardBlockers.isEmpty())
        assertNotNull(report.aggregateScore)
        assertTrue(requireNotNull(report.aggregateScore) >= 0.85)
        assertFalse(report.authorityBearing)

        val restored = requireNotNull(store.latest())
        assertEquals(report.canonicalDigest, restored.canonicalDigest)
        assertEquals(subject.artifactDigest, restored.subjectArtifactDigest)
    }

    @Test
    fun missingCriticalEvidenceLeavesQualificationIncomplete() {
        val store = MemoryBackedAgiMobileQualificationStore(InMemoryMemoryOs())
        val runner = CanonicalAgiMobileQualificationRunner(
            suite = suite,
            store = store,
            clock = { 20_000L }
        )
        val probes = suite.criteria
            .filterNot { it.domain == AgiMobileQualificationDomain.AUTHORITY_INVARIANTS }
            .map(::passingProbe)

        val report = runner.run(subject, probes)

        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
        val authority = report.results.single {
            it.domain == AgiMobileQualificationDomain.AUTHORITY_INVARIANTS
        }
        assertEquals(AgiMobileDomainStatus.UNMEASURED, authority.status)
        assertEquals("MISSING_EVIDENCE", authority.failureCode)
        assertTrue(AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers)
    }

    @Test
    fun failedAuthorityAssertionsCannotBeMaskedByHighAggregateScores() {
        val store = MemoryBackedAgiMobileQualificationStore(InMemoryMemoryOs())
        val runner = CanonicalAgiMobileQualificationRunner(
            suite = suite,
            store = store,
            clock = { 30_000L }
        )

        val probes = suite.criteria.map { criterion ->
            if (criterion.domain == AgiMobileQualificationDomain.AUTHORITY_INVARIANTS) {
                probe(
                    criterion = criterion,
                    score = 1.0,
                    samples = criterion.minSamples * 4,
                    assertionsPassed = 31,
                    assertionsTotal = 32
                )
            } else {
                probe(
                    criterion = criterion,
                    score = 1.0,
                    samples = criterion.minSamples * 4,
                    assertionsPassed = 32,
                    assertionsTotal = 32
                )
            }
        }

        val report = runner.run(subject, probes)

        assertEquals(AgiMobileQualificationVerdict.NOT_QUALIFIED, report.verdict)
        assertTrue(requireNotNull(report.aggregateScore) > 0.99)
        val authority = report.results.single {
            it.domain == AgiMobileQualificationDomain.AUTHORITY_INVARIANTS
        }
        assertEquals(AgiMobileDomainStatus.FAILED, authority.status)
        assertEquals("ASSERTION_FAILURE", authority.failureCode)
        assertTrue(AgiMobileQualificationDomain.AUTHORITY_INVARIANTS in report.hardBlockers)
    }

    @Test
    fun evidenceForAnotherArtifactCannotQualifyCurrentSubject() {
        val store = MemoryBackedAgiMobileQualificationStore(InMemoryMemoryOs())
        val runner = CanonicalAgiMobileQualificationRunner(
            suite = suite,
            store = store,
            clock = { 40_000L }
        )
        val otherSubject = AgiMobileQualificationSubject(
            revision = "other-revision",
            artifactDigest = "b".repeat(64)
        )
        val probes = suite.criteria.map { criterion ->
            probe(
                criterion = criterion,
                score = 1.0,
                samples = criterion.minSamples,
                assertionsPassed = 8,
                assertionsTotal = 8,
                evidenceSubject = otherSubject
            )
        }

        val report = runner.run(subject, probes)

        assertEquals(AgiMobileQualificationVerdict.INCOMPLETE, report.verdict)
        assertTrue(report.results.all { it.status == AgiMobileDomainStatus.UNMEASURED })
        assertEquals(null, report.aggregateScore)
    }

    @Test
    fun insufficientLongHorizonSamplesFailEvenWithPerfectScore() {
        val store = MemoryBackedAgiMobileQualificationStore(InMemoryMemoryOs())
        val runner = CanonicalAgiMobileQualificationRunner(
            suite = suite,
            store = store,
            clock = { 50_000L }
        )
        val probes = suite.criteria.map { criterion ->
            if (criterion.domain == AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION) {
                probe(
                    criterion = criterion,
                    score = 1.0,
                    samples = criterion.minSamples - 1,
                    assertionsPassed = 8,
                    assertionsTotal = 8
                )
            } else {
                passingProbe(criterion)
            }
        }

        val report = runner.run(subject, probes)

        assertEquals(AgiMobileQualificationVerdict.NOT_QUALIFIED, report.verdict)
        val longHorizon = report.results.single {
            it.domain == AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION
        }
        assertEquals(AgiMobileDomainStatus.FAILED, longHorizon.status)
        assertEquals("INSUFFICIENT_SAMPLES", longHorizon.failureCode)
    }

    @Test
    fun canonicalRuntimeExposesDurableQualificationRunner() {
        val runtime = AmperRuntime.reference()
        val runner = runtime.agiMobileQualificationRunner()

        val report = runner.run(
            subject = subject,
            probes = suite.criteria.map(::passingProbe)
        )

        assertEquals(AgiMobileQualificationVerdict.QUALIFIED, report.verdict)
        val latest = requireNotNull(runtime.agiMobileQualificationStore.latest())
        assertEquals(report.canonicalDigest, latest.canonicalDigest)
    }

    private fun passingProbe(
        criterion: AgiMobileQualificationCriterion
    ): AgiMobileQualificationProbe =
        probe(
            criterion = criterion,
            score = if (criterion.minScore >= 0.99) 1.0 else {
                (criterion.minScore + 0.05).coerceAtMost(1.0)
            },
            samples = criterion.minSamples,
            assertionsPassed = 8,
            assertionsTotal = 8
        )

    private fun probe(
        criterion: AgiMobileQualificationCriterion,
        score: Double,
        samples: Int,
        assertionsPassed: Int,
        assertionsTotal: Int,
        evidenceSubject: AgiMobileQualificationSubject = subject
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = criterion.probeId,
            domain = criterion.domain
        ) {
            Result.success(
                AgiMobileQualificationEvidence(
                    probeId = criterion.probeId,
                    domain = criterion.domain,
                    score = score,
                    samples = samples,
                    assertionsPassed = assertionsPassed,
                    assertionsTotal = assertionsTotal,
                    subjectDigest = evidenceSubject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(
                        listOf(
                            criterion.probeId,
                            evidenceSubject.canonicalDigest,
                            score.toString(),
                            samples.toString(),
                            assertionsPassed.toString(),
                            assertionsTotal.toString()
                        ).joinToString("|")
                    ),
                    observedAtEpochMs = 1_000L
                )
            )
        }
}
