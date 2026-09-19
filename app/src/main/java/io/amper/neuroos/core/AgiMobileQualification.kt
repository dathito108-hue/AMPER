package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

enum class AgiMobileQualificationDomain {
    SYSTEM1_FAST_PATH,
    NATIVE_SYSTEM2_REASONING,
    GENERALIZATION,
    LONG_HORIZON_EXECUTION,
    MEMORY_WORLD_MODEL,
    SELF_LEARNING,
    SELF_EVOLUTION,
    MOBILE_RESOURCE_RESILIENCE,
    RESTART_RECOVERY,
    AUTHORITY_INVARIANTS
}

data class AgiMobileQualificationCriterion(
    val domain: AgiMobileQualificationDomain,
    val probeId: String,
    val weight: Double,
    val minScore: Double,
    val minSamples: Int,
    val hardBlocker: Boolean = false
) {
    init {
        require(probeId.matches(PROBE_ID))
        require(weight > 0.0 && weight.isFinite())
        require(minScore in 0.0..1.0)
        require(minSamples > 0)
    }

    companion object {
        private val PROBE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

data class AgiMobileQualificationSuite(
    val id: String,
    val criteria: List<AgiMobileQualificationCriterion>,
    val version: Int = 1
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(version > 0)
        require(criteria.isNotEmpty())
        require(criteria.size == AgiMobileQualificationDomain.entries.size)
        require(criteria.map { it.domain }.toSet() == AgiMobileQualificationDomain.entries.toSet())
        require(criteria.map { it.probeId }.distinct().size == criteria.size)
    }

    val canonicalDigest: String
        get() = agiQualificationSha256(
            buildString {
                append("AMQ_SUITE_V1|").append(id).append('|').append(version)
                criteria.sortedBy { it.domain.name }.forEach { criterion ->
                    append('|')
                    append(criterion.domain.name).append(':')
                    append(criterion.probeId).append(':')
                    append(qualificationDecimal(criterion.weight)).append(':')
                    append(qualificationDecimal(criterion.minScore)).append(':')
                    append(criterion.minSamples).append(':')
                    append(criterion.hardBlocker)
                }
            }
        )

    companion object {
        fun canonical(): AgiMobileQualificationSuite =
            AgiMobileQualificationSuite(
                id = "amper-agi-mobile-v1",
                criteria = listOf(
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.SYSTEM1_FAST_PATH,
                        probeId = "system1-fast-path",
                        weight = 0.10,
                        minScore = 0.90,
                        minSamples = 64
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
                        probeId = "native-system2-reasoning",
                        weight = 0.13,
                        minScore = 0.82,
                        minSamples = 32
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.GENERALIZATION,
                        probeId = "cross-context-generalization",
                        weight = 0.12,
                        minScore = 0.78,
                        minSamples = 32
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION,
                        probeId = "long-horizon-execution",
                        weight = 0.13,
                        minScore = 0.82,
                        minSamples = 16
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.MEMORY_WORLD_MODEL,
                        probeId = "memory-world-model",
                        weight = 0.10,
                        minScore = 0.88,
                        minSamples = 32
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.SELF_LEARNING,
                        probeId = "self-learning-retention",
                        weight = 0.09,
                        minScore = 0.78,
                        minSamples = 16
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.SELF_EVOLUTION,
                        probeId = "self-evolution-transaction",
                        weight = 0.10,
                        minScore = 0.90,
                        minSamples = 8,
                        hardBlocker = true
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.MOBILE_RESOURCE_RESILIENCE,
                        probeId = "mobile-resource-resilience",
                        weight = 0.08,
                        minScore = 0.90,
                        minSamples = 16,
                        hardBlocker = true
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.RESTART_RECOVERY,
                        probeId = "restart-recovery",
                        weight = 0.07,
                        minScore = 0.95,
                        minSamples = 16,
                        hardBlocker = true
                    ),
                    AgiMobileQualificationCriterion(
                        domain = AgiMobileQualificationDomain.AUTHORITY_INVARIANTS,
                        probeId = "authority-invariants",
                        weight = 0.08,
                        minScore = 1.0,
                        minSamples = 32,
                        hardBlocker = true
                    )
                )
            )
    }
}

data class AgiMobileQualificationSubject(
    val revision: String,
    val artifactDigest: String
) {
    init {
        require(revision.isNotBlank() && revision.length <= 512)
        require(artifactDigest.matches(SHA256))
    }

    val canonicalDigest: String
        get() = agiQualificationSha256(revision + "|" + artifactDigest)

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * One externally measured qualification observation.
 *
 * sourceEvidenceDigest is the SHA-256 of the probe's immutable report/log bundle. Raw prompts,
 * tool payloads and user data are intentionally not retained by the qualification layer.
 */
data class AgiMobileQualificationEvidence(
    val probeId: String,
    val domain: AgiMobileQualificationDomain,
    val score: Double,
    val samples: Int,
    val assertionsPassed: Int,
    val assertionsTotal: Int,
    val sourceEvidenceDigest: String,
    val observedAtEpochMs: Long
) {
    init {
        require(probeId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(score in 0.0..1.0)
        require(samples >= 0)
        require(assertionsPassed >= 0)
        require(assertionsTotal > 0)
        require(assertionsPassed <= assertionsTotal)
        require(sourceEvidenceDigest.matches(SHA256))
        require(observedAtEpochMs >= 0L)
    }

    val allAssertionsPassed: Boolean
        get() = assertionsPassed == assertionsTotal

    val canonicalDigest: String
        get() = agiQualificationSha256(
            listOf(
                "AMQ_EVIDENCE_V1",
                probeId,
                domain.name,
                qualificationDecimal(score),
                samples.toString(),
                assertionsPassed.toString(),
                assertionsTotal.toString(),
                sourceEvidenceDigest,
                observedAtEpochMs.toString()
            ).joinToString("|")
        )

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface AgiMobileQualificationProbe {
    val id: String
    val domain: AgiMobileQualificationDomain
    fun evaluate(subject: AgiMobileQualificationSubject): Result<AgiMobileQualificationEvidence>
}

enum class AgiMobileDomainStatus {
    QUALIFIED,
    FAILED,
    UNMEASURED
}

data class AgiMobileQualificationDomainResult(
    val domain: AgiMobileQualificationDomain,
    val probeId: String,
    val status: AgiMobileDomainStatus,
    val score: Double?,
    val samples: Int,
    val evidenceDigest: String?,
    val failureCode: String? = null
) {
    init {
        require(probeId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        score?.let { require(it in 0.0..1.0) }
        require(samples >= 0)
        require(evidenceDigest == null || evidenceDigest.matches(SHA256))
        require(failureCode == null || failureCode.matches(FAILURE_CODE))
        when (status) {
            AgiMobileDomainStatus.QUALIFIED -> {
                require(score != null)
                require(evidenceDigest != null)
                require(failureCode == null)
            }
            AgiMobileDomainStatus.FAILED -> require(failureCode != null)
            AgiMobileDomainStatus.UNMEASURED -> {
                require(score == null)
                require(samples == 0)
                require(evidenceDigest == null)
                require(failureCode != null)
            }
        }
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val FAILURE_CODE = Regex("[A-Z0-9_:-]{1,128}")
    }
}

enum class AgiMobileQualificationVerdict {
    QUALIFIED,
    NOT_QUALIFIED,
    INCOMPLETE
}

data class AgiMobileQualificationReport(
    val suiteId: String,
    val suiteDigest: String,
    val subjectRevision: String,
    val subjectArtifactDigest: String,
    val results: List<AgiMobileQualificationDomainResult>,
    val verdict: AgiMobileQualificationVerdict,
    val aggregateScore: Double?,
    val hardBlockers: Set<AgiMobileQualificationDomain>,
    val createdAtEpochMs: Long
) {
    init {
        require(suiteId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(suiteDigest.matches(SHA256))
        require(subjectRevision.isNotBlank() && subjectRevision.length <= 512)
        require(subjectArtifactDigest.matches(SHA256))
        require(results.size == AgiMobileQualificationDomain.entries.size)
        require(results.map { it.domain }.toSet() == AgiMobileQualificationDomain.entries.toSet())
        aggregateScore?.let { require(it in 0.0..1.0) }
        require(createdAtEpochMs >= 0L)
        when (verdict) {
            AgiMobileQualificationVerdict.QUALIFIED -> {
                require(results.all { it.status == AgiMobileDomainStatus.QUALIFIED })
                require(hardBlockers.isEmpty())
                require(aggregateScore != null)
            }
            AgiMobileQualificationVerdict.INCOMPLETE ->
                require(results.any { it.status == AgiMobileDomainStatus.UNMEASURED })
            AgiMobileQualificationVerdict.NOT_QUALIFIED ->
                require(results.any { it.status == AgiMobileDomainStatus.FAILED })
        }
    }

    val canonicalDigest: String
        get() = agiQualificationSha256(
            buildString {
                append("AMQ_REPORT_V1|")
                append(suiteId).append('|')
                append(suiteDigest).append('|')
                append(agiQualificationSha256(subjectRevision)).append('|')
                append(subjectArtifactDigest).append('|')
                append(verdict.name).append('|')
                append(aggregateScore?.let(::qualificationDecimal) ?: "~").append('|')
                append(hardBlockers.map { it.name }.sorted().joinToString(","))
                results.sortedBy { it.domain.name }.forEach { result ->
                    append('|')
                    append(result.domain.name).append(':')
                    append(result.probeId).append(':')
                    append(result.status.name).append(':')
                    append(result.score?.let(::qualificationDecimal) ?: "~").append(':')
                    append(result.samples).append(':')
                    append(result.evidenceDigest ?: "~").append(':')
                    append(result.failureCode ?: "~")
                }
            }
        )

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

interface AgiMobileQualificationStore {
    fun persist(report: AgiMobileQualificationReport)
    fun latest(): AgiMobileQualificationReport?
}

class MemoryBackedAgiMobileQualificationStore(
    private val memory: MemoryOs
) : AgiMobileQualificationStore {
    @Synchronized
    override fun persist(report: AgiMobileQualificationReport) {
        val reportRecord = MemoryRecord(
            id = MemoryId("agi-mobile-qualification:" + report.canonicalDigest),
            kind = REPORT_KIND,
            content = AgiMobileQualificationCodec.encode(report),
            importance = 0.98,
            provenance = Provenance(
                source = "independent-qualification-evidence",
                producer = "agi-mobile-qualification",
                confidence = 1.0
            ),
            createdAtEpochMs = report.createdAtEpochMs
        )
        memory.rememberIfAbsent(reportRecord).also { inserted ->
            if (!inserted) {
                val existing = memory.get(reportRecord.id)
                require(existing?.content == reportRecord.content) {
                    "qualification report digest collision"
                }
            }
        }
        memory.remember(
            MemoryRecord(
                id = LATEST_ID,
                kind = LATEST_KIND,
                content = report.canonicalDigest,
                importance = 0.94,
                provenance = Provenance(
                    source = "independent-qualification-evidence",
                    producer = "agi-mobile-qualification-index",
                    confidence = 1.0,
                    parents = setOf(reportRecord.id)
                ),
                createdAtEpochMs = report.createdAtEpochMs
            )
        )
    }

    @Synchronized
    override fun latest(): AgiMobileQualificationReport? {
        val digest = memory.get(LATEST_ID)
            ?.takeIf { it.kind == LATEST_KIND }
            ?.content
            ?.takeIf { it.matches(SHA256) }
            ?: return null
        return memory.get(MemoryId("agi-mobile-qualification:$digest"))
            ?.takeIf { it.kind == REPORT_KIND }
            ?.let { AgiMobileQualificationCodec.decode(it.content) }
            ?.takeIf { it.canonicalDigest == digest }
    }

    companion object {
        const val REPORT_KIND = "agi-mobile-qualification-report-v1"
        const val LATEST_KIND = "agi-mobile-qualification-latest-v1"
        private val LATEST_ID = MemoryId("agi-mobile-qualification:latest")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/**
 * Fail-closed project qualification runner.
 *
 * A probe cannot qualify another domain, missing probes remain UNMEASURED, assertion failures are
 * fatal for the domain, and no weighted average can override a failed hard blocker. QUALIFIED is a
 * project acceptance verdict for this explicit suite, not a general scientific claim of AGI.
 */
class CanonicalAgiMobileQualificationRunner(
    private val suite: AgiMobileQualificationSuite = AgiMobileQualificationSuite.canonical(),
    private val store: AgiMobileQualificationStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun run(
        subject: AgiMobileQualificationSubject,
        probes: Collection<AgiMobileQualificationProbe>
    ): AgiMobileQualificationReport {
        val evidenceByProbe = linkedMapOf<String, AgiMobileQualificationEvidence>()
        probes.forEach { probe ->
            val expected = suite.criteria.firstOrNull { it.probeId == probe.id }
                ?: return@forEach
            if (probe.domain != expected.domain) return@forEach
            val evidence = probe.evaluate(subject).getOrNull() ?: return@forEach
            if (evidence.probeId != expected.probeId || evidence.domain != expected.domain) {
                return@forEach
            }
            evidenceByProbe.putIfAbsent(expected.probeId, evidence)
        }

        val results = suite.criteria
            .sortedBy { it.domain.name }
            .map { criterion ->
                val evidence = evidenceByProbe[criterion.probeId]
                when {
                    evidence == null -> AgiMobileQualificationDomainResult(
                        domain = criterion.domain,
                        probeId = criterion.probeId,
                        status = AgiMobileDomainStatus.UNMEASURED,
                        score = null,
                        samples = 0,
                        evidenceDigest = null,
                        failureCode = "MISSING_EVIDENCE"
                    )
                    !evidence.allAssertionsPassed -> AgiMobileQualificationDomainResult(
                        domain = criterion.domain,
                        probeId = criterion.probeId,
                        status = AgiMobileDomainStatus.FAILED,
                        score = evidence.score,
                        samples = evidence.samples,
                        evidenceDigest = evidence.canonicalDigest,
                        failureCode = "ASSERTION_FAILURE"
                    )
                    evidence.samples < criterion.minSamples -> AgiMobileQualificationDomainResult(
                        domain = criterion.domain,
                        probeId = criterion.probeId,
                        status = AgiMobileDomainStatus.FAILED,
                        score = evidence.score,
                        samples = evidence.samples,
                        evidenceDigest = evidence.canonicalDigest,
                        failureCode = "INSUFFICIENT_SAMPLES"
                    )
                    evidence.score < criterion.minScore -> AgiMobileQualificationDomainResult(
                        domain = criterion.domain,
                        probeId = criterion.probeId,
                        status = AgiMobileDomainStatus.FAILED,
                        score = evidence.score,
                        samples = evidence.samples,
                        evidenceDigest = evidence.canonicalDigest,
                        failureCode = "SCORE_BELOW_FLOOR"
                    )
                    else -> AgiMobileQualificationDomainResult(
                        domain = criterion.domain,
                        probeId = criterion.probeId,
                        status = AgiMobileDomainStatus.QUALIFIED,
                        score = evidence.score,
                        samples = evidence.samples,
                        evidenceDigest = evidence.canonicalDigest
                    )
                }
            }

        val byDomain = results.associateBy { it.domain }
        val hardBlockers = suite.criteria
            .asSequence()
            .filter { it.hardBlocker }
            .filter { byDomain[it.domain]?.status != AgiMobileDomainStatus.QUALIFIED }
            .map { it.domain }
            .toSortedSet(compareBy { it.name })

        val verdict = when {
            results.any { it.status == AgiMobileDomainStatus.UNMEASURED } ->
                AgiMobileQualificationVerdict.INCOMPLETE
            results.any { it.status == AgiMobileDomainStatus.FAILED } ->
                AgiMobileQualificationVerdict.NOT_QUALIFIED
            else -> AgiMobileQualificationVerdict.QUALIFIED
        }
        val aggregate = if (results.all { it.score != null }) {
            val criterionByDomain = suite.criteria.associateBy { it.domain }
            val totalWeight = suite.criteria.sumOf { it.weight }
            results.sumOf { result ->
                requireNotNull(result.score) *
                    requireNotNull(criterionByDomain[result.domain]).weight
            }.div(totalWeight).coerceIn(0.0, 1.0)
        } else {
            null
        }

        val report = AgiMobileQualificationReport(
            suiteId = suite.id,
            suiteDigest = suite.canonicalDigest,
            subjectRevision = subject.revision,
            subjectArtifactDigest = subject.artifactDigest,
            results = results,
            verdict = verdict,
            aggregateScore = aggregate,
            hardBlockers = hardBlockers,
            createdAtEpochMs = clock().coerceAtLeast(0L)
        )
        store.persist(report)
        return report
    }

}

class NamedAgiMobileQualificationProbe(
    override val id: String,
    override val domain: AgiMobileQualificationDomain,
    private val evaluator:
        (AgiMobileQualificationSubject) -> Result<AgiMobileQualificationEvidence>
) : AgiMobileQualificationProbe {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
    }

    override fun evaluate(
        subject: AgiMobileQualificationSubject
    ): Result<AgiMobileQualificationEvidence> =
        evaluator(subject).map { evidence ->
            require(evidence.probeId == id)
            require(evidence.domain == domain)
            evidence
        }
}

private object AgiMobileQualificationCodec {
    fun encode(report: AgiMobileQualificationReport): String = buildString {
        append("AMQR1\n")
        append(enc(report.suiteId)).append('\n')
        append(report.suiteDigest).append('\n')
        append(enc(report.subjectRevision)).append('\n')
        append(report.subjectArtifactDigest).append('\n')
        append(report.verdict.name).append('\n')
        append(report.aggregateScore?.let(::qualificationDecimal) ?: "~").append('\n')
        append(report.hardBlockers.map { it.name }.sorted().joinToString(",")).append('\n')
        append(report.createdAtEpochMs).append('\n')
        report.results.sortedBy { it.domain.name }.forEach { result ->
            append(
                listOf(
                    result.domain.name,
                    result.probeId,
                    result.status.name,
                    result.score?.let(::qualificationDecimal) ?: "~",
                    result.samples.toString(),
                    result.evidenceDigest ?: "~",
                    result.failureCode ?: "~"
                ).joinToString("|")
            ).append('\n')
        }
    }.trimEnd()

    fun decode(content: String): AgiMobileQualificationReport {
        val lines = content.lineSequence().toList()
        require(lines.size == 9 + AgiMobileQualificationDomain.entries.size)
        require(lines[0] == "AMQR1")
        val results = lines.drop(9).map { line ->
            val f = line.split('|')
            require(f.size == 7)
            AgiMobileQualificationDomainResult(
                domain = AgiMobileQualificationDomain.valueOf(f[0]),
                probeId = f[1],
                status = AgiMobileDomainStatus.valueOf(f[2]),
                score = f[3].takeUnless { it == "~" }?.toDouble(),
                samples = f[4].toInt(),
                evidenceDigest = f[5].takeUnless { it == "~" },
                failureCode = f[6].takeUnless { it == "~" }
            )
        }
        return AgiMobileQualificationReport(
            suiteId = dec(lines[1]),
            suiteDigest = lines[2],
            subjectRevision = dec(lines[3]),
            subjectArtifactDigest = lines[4],
            results = results,
            verdict = AgiMobileQualificationVerdict.valueOf(lines[5]),
            aggregateScore = lines[6].takeUnless { it == "~" }?.toDouble(),
            hardBlockers = lines[7]
                .split(',')
                .filter { it.isNotBlank() }
                .mapTo(linkedSetOf()) { AgiMobileQualificationDomain.valueOf(it) },
            createdAtEpochMs = lines[8].toLong()
        )
    }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

internal fun agiQualificationSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun qualificationDecimal(value: Double): String =
    String.format(Locale.ROOT, "%.6f", value)
