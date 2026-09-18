package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

@JvmInline
value class PracticeTaskId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class LearningNeedKind {
    EVIDENCE_DEPTH,
    EXECUTION_RELIABILITY,
    TRANSFER_COVERAGE,
    CONTRACT_PRACTICE
}

data class LearningNeed(
    val capability: CapabilityId,
    val kind: LearningNeedKind,
    val severity: Double,
    val evidenceConfidence: Double,
    val rationale: String
) {
    init {
        require(severity in 0.0..1.0)
        require(evidenceConfidence in 0.0..1.0)
        require(rationale.isNotBlank() && rationale.length <= 256)
    }

    val authorityBearing: Boolean
        get() = false
}

enum class AutonomousPracticeKind {
    CONTRACT_PLAN,
    NOVEL_TRANSFER_PLAN
}

data class AutonomousPracticeTask(
    val id: PracticeTaskId,
    val capability: CapabilityId,
    val kind: AutonomousPracticeKind,
    val objective: String,
    val priority: Double,
    val sourceNeeds: Set<LearningNeedKind>
) {
    init {
        require(objective.isNotBlank() && objective.length <= 384)
        require(priority in 0.0..1.0)
        require(sourceNeeds.isNotEmpty())
    }

    val authorityBearing: Boolean
        get() = false
}

enum class AutonomousPracticeVerdict {
    PASS,
    FAIL
}

data class AutonomousPracticeEvidence(
    val id: MemoryId,
    val taskId: PracticeTaskId,
    val capability: CapabilityId,
    val kind: AutonomousPracticeKind,
    val verdict: AutonomousPracticeVerdict,
    val validatedStepCount: Int,
    val observedAtEpochMs: Long
) {
    init {
        require(validatedStepCount in 0..TitanPlanProtocol.MAX_STEPS)
        require(observedAtEpochMs >= 0L)
    }

    val authorityBearing: Boolean
        get() = false
}

data class PracticeCompetenceSnapshot(
    val capability: CapabilityId,
    val passed: Int = 0,
    val failed: Int = 0,
    val lastObservedAtEpochMs: Long = 0L
) {
    init {
        require(passed >= 0 && failed >= 0)
        require(lastObservedAtEpochMs >= 0L)
    }

    val attempts: Int
        get() = passed + failed

    val passRate: Double?
        get() = attempts.takeIf { it > 0 }?.let { passed.toDouble() / it.toDouble() }

    val evidenceConfidence: Double
        get() = attempts.toDouble() / (attempts.toDouble() + 3.0)

    val authorityBearing: Boolean
        get() = false
}

sealed interface AutonomousLearningCycleResult {
    data class NoPractice(
        val needs: List<LearningNeed>
    ) : AutonomousLearningCycleResult

    data class Assessed(
        val task: AutonomousPracticeTask,
        val evidence: AutonomousPracticeEvidence,
        val backendId: String,
        val modelId: ModelId?
    ) : AutonomousLearningCycleResult
}

interface AutonomousLearningModel {
    fun diagnose(
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        limit: Int = 8
    ): List<LearningNeed>

    fun curriculum(
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        limit: Int = 4
    ): List<AutonomousPracticeTask>

    fun assess(
        task: AutonomousPracticeTask,
        modelOutput: String,
        descriptors: Collection<ToolDescriptor>
    ): AutonomousPracticeEvidence

    fun practiceSnapshot(capability: CapabilityId): PracticeCompetenceSnapshot?
    fun recentPractice(limit: Int = 8): List<PracticeCompetenceSnapshot>
}

/**
 * Phase206-210 governed autonomous-learning substrate.
 *
 * Phase206 builds a weakness map from governed execution competence, learned skills and transfer
 * profiles. Authority denial, environment unavailability and pending approval never become
 * execution-skill weakness signals.
 *
 * Phase207 produces a bounded active curriculum over the live capability surface. Curriculum tasks
 * contain only capability-level synthetic objectives and never copy user goals, raw tool input or
 * tool output.
 *
 * Phase208 evaluates one zero-tool planning practice against the exact live TitanPlanProtocol and
 * ToolDescriptor contract. Practice output is parsed as data and is never sent to ToolFabric.
 *
 * Phase209 persists a separate practice-competence profile. Practice PASS cannot increment
 * CapabilityCompetenceSnapshot.executed, promote a skill, grant authority or satisfy real-world
 * execution evidence.
 *
 * Phase210 enables one bounded autonomous-learning cycle per explicit coordinator call. There is no
 * unbounded self-loop and no tool execution path inside practice.
 */
class MemoryBackedAutonomousLearningModel(
    private val memory: MemoryOs,
    private val competence: CapabilityCompetenceModel,
    private val skills: SkillGenesisModel,
    private val generalization: SkillGeneralizationModel,
    private val clock: () -> Long = System::currentTimeMillis
) : AutonomousLearningModel {
    override fun diagnose(
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        limit: Int
    ): List<LearningNeed> {
        require(limit >= 0)
        if (limit == 0) return emptyList()

        val live = descriptors
            .filter { it.capability in allowedCapabilities }
            .associateBy { it.capability }
        val activeSkills = skills.recent(SKILL_LOOKBACK)
            .filter { it.maturity == SkillMaturity.ACTIVE }
        val transferProfiles = generalization.recent(GENERALIZATION_LOOKBACK)
            .associateBy { it.signature.digest }

        val needs = mutableListOf<LearningNeed>()
        live.keys.sortedBy { it.value }.forEach { capability ->
            val execution = competence.snapshot(capability)
            val executionAttempts = execution?.executionAttempts ?: 0
            val executionConfidence = execution?.evidenceConfidence ?: 0.0

            if (executionAttempts < MIN_REAL_EXECUTION_EVIDENCE) {
                needs += LearningNeed(
                    capability = capability,
                    kind = LearningNeedKind.EVIDENCE_DEPTH,
                    severity = (0.85 - executionConfidence * 0.45).coerceIn(0.35, 0.85),
                    evidenceConfidence = executionConfidence,
                    rationale = "real governed execution evidence is still sparse"
                )
            }

            val successRate = execution?.executionSuccessRate
            if (
                successRate != null &&
                executionAttempts >= MIN_RELIABILITY_ATTEMPTS &&
                successRate < TARGET_EXECUTION_SUCCESS_RATE
            ) {
                needs += LearningNeed(
                    capability = capability,
                    kind = LearningNeedKind.EXECUTION_RELIABILITY,
                    severity = (
                        0.55 +
                            (TARGET_EXECUTION_SUCCESS_RATE - successRate) * 0.45
                    ).coerceIn(0.55, 1.0),
                    evidenceConfidence = executionConfidence,
                    rationale = "governed execution success rate is below target"
                )
            }

            val relatedSkills = activeSkills.filter { capability in it.signature.capabilities }
            if (relatedSkills.isNotEmpty()) {
                val bestTransfer = relatedSkills
                    .mapNotNull { transferProfiles[it.signature.digest] }
                    .maxByOrNull { profile ->
                        when (profile.maturity) {
                            SkillGeneralizationMaturity.GENERALIZED -> 4
                            SkillGeneralizationMaturity.TRANSFERABLE -> 3
                            SkillGeneralizationMaturity.LOCAL -> 2
                            SkillGeneralizationMaturity.DEGRADED -> 1
                        }
                    }
                if (
                    bestTransfer == null ||
                    bestTransfer.maturity == SkillGeneralizationMaturity.LOCAL ||
                    bestTransfer.maturity == SkillGeneralizationMaturity.DEGRADED
                ) {
                    needs += LearningNeed(
                        capability = capability,
                        kind = LearningNeedKind.TRANSFER_COVERAGE,
                        severity = if (bestTransfer?.maturity == SkillGeneralizationMaturity.DEGRADED) {
                            0.82
                        } else {
                            0.68
                        },
                        evidenceConfidence = bestTransfer?.confidence ?: 0.0,
                        rationale = "active skill lacks stable cross-context transfer evidence"
                    )
                }
            }

            val practice = practiceSnapshot(capability)
            val practiceRate = practice?.passRate
            if (
                practice == null ||
                practice.attempts < MIN_PRACTICE_EVIDENCE ||
                (practiceRate != null && practiceRate < TARGET_PRACTICE_PASS_RATE)
            ) {
                needs += LearningNeed(
                    capability = capability,
                    kind = LearningNeedKind.CONTRACT_PRACTICE,
                    severity = when {
                        practice == null -> 0.58
                        practiceRate == null -> 0.58
                        practiceRate < TARGET_PRACTICE_PASS_RATE -> 0.70
                        else -> 0.48
                    },
                    evidenceConfidence = practice?.evidenceConfidence ?: 0.0,
                    rationale = "bounded contract-planning practice evidence is insufficient"
                )
            }
        }

        return needs
            .sortedWith(
                compareByDescending<LearningNeed> { it.severity }
                    .thenBy { it.evidenceConfidence }
                    .thenBy { it.capability.value }
                    .thenBy { it.kind.name }
            )
            .take(limit)
    }

    override fun curriculum(
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        limit: Int
    ): List<AutonomousPracticeTask> {
        require(limit in 0..MAX_CURRICULUM_TASKS)
        if (limit == 0) return emptyList()
        val live = descriptors
            .filter { it.capability in allowedCapabilities }
            .associateBy { it.capability }
        val needs = diagnose(
            allowedCapabilities = allowedCapabilities,
            descriptors = descriptors,
            limit = MAX_DIAGNOSED_NEEDS
        )

        return needs
            .groupBy { it.capability }
            .mapNotNull { (capability, capabilityNeeds) ->
                if (live[capability] == null) return@mapNotNull null
                val sorted = capabilityNeeds.sortedByDescending { it.severity }
                val kind = if (sorted.any { it.kind == LearningNeedKind.TRANSFER_COVERAGE }) {
                    AutonomousPracticeKind.NOVEL_TRANSFER_PLAN
                } else {
                    AutonomousPracticeKind.CONTRACT_PLAN
                }
                val sourceKinds = sorted.map { it.kind }.toSet()
                val priority = sorted.maxOf { it.severity }
                val objective = when (kind) {
                    AutonomousPracticeKind.CONTRACT_PLAN ->
                        "Practice a valid bounded plan for capability ${capability.value} using the current live contract."
                    AutonomousPracticeKind.NOVEL_TRANSFER_PLAN ->
                        "Practice adapting capability ${capability.value} to a novel synthetic context using the current live contract."
                }
                AutonomousPracticeTask(
                    id = practiceTaskId(capability, kind, sourceKinds),
                    capability = capability,
                    kind = kind,
                    objective = objective,
                    priority = priority,
                    sourceNeeds = sourceKinds
                )
            }
            .sortedWith(
                compareByDescending<AutonomousPracticeTask> { it.priority }
                    .thenBy { it.capability.value }
            )
            .take(limit)
    }

    override fun assess(
        task: AutonomousPracticeTask,
        modelOutput: String,
        descriptors: Collection<ToolDescriptor>
    ): AutonomousPracticeEvidence {
        val descriptor = descriptors.singleOrNull { it.capability == task.capability }
        val parsed = if (descriptor == null) {
            Result.failure(IllegalStateException("practice capability has no live descriptor"))
        } else {
            TitanPlanProtocol.parse(
                modelOutput = modelOutput,
                allowedCapabilities = setOf(task.capability),
                descriptors = listOf(descriptor)
            )
        }
        val steps = parsed.getOrNull().orEmpty()
        val verdict = if (parsed.isSuccess && steps.isNotEmpty()) {
            AutonomousPracticeVerdict.PASS
        } else {
            AutonomousPracticeVerdict.FAIL
        }
        val now = clock()
        val record = MemoryRecord(
            kind = PRACTICE_EVIDENCE_KIND,
            content = AutonomousLearningCodec.encodeEvidence(
                taskId = task.id,
                capability = task.capability,
                kind = task.kind,
                verdict = verdict,
                validatedStepCount = steps.size,
                observedAtEpochMs = now
            ),
            importance = if (verdict == AutonomousPracticeVerdict.PASS) 0.66 else 0.60,
            provenance = Provenance(
                source = "zero-tool-autonomous-practice",
                producer = "autonomous-learning-model",
                confidence = 1.0
            ),
            createdAtEpochMs = now
        )
        memory.remember(record)

        val previous = practiceSnapshot(task.capability) ?: PracticeCompetenceSnapshot(task.capability)
        val updated = previous.copy(
            passed = previous.passed + if (verdict == AutonomousPracticeVerdict.PASS) 1 else 0,
            failed = previous.failed + if (verdict == AutonomousPracticeVerdict.FAIL) 1 else 0,
            lastObservedAtEpochMs = now.coerceAtLeast(previous.lastObservedAtEpochMs)
        )
        memory.transaction {
            remember(
                MemoryRecord(
                    id = practiceSnapshotId(task.capability),
                    kind = PRACTICE_SNAPSHOT_KIND,
                    content = AutonomousLearningCodec.encodeSnapshot(updated),
                    importance = 0.70,
                    provenance = Provenance(
                        source = "zero-tool-autonomous-practice",
                        producer = "autonomous-learning-model",
                        confidence = updated.evidenceConfidence,
                        parents = setOf(record.id)
                    ),
                    createdAtEpochMs = updated.lastObservedAtEpochMs
                )
            )
            updatePracticeIndexLocked(task.capability, updated.lastObservedAtEpochMs)
        }

        return AutonomousPracticeEvidence(
            id = record.id,
            taskId = task.id,
            capability = task.capability,
            kind = task.kind,
            verdict = verdict,
            validatedStepCount = steps.size,
            observedAtEpochMs = now
        )
    }

    override fun practiceSnapshot(capability: CapabilityId): PracticeCompetenceSnapshot? =
        memory.get(practiceSnapshotId(capability))
            ?.takeIf { it.kind == PRACTICE_SNAPSHOT_KIND }
            ?.let { AutonomousLearningCodec.decodeSnapshot(it.content) }
            ?.takeIf { it.capability == capability }

    override fun recentPractice(limit: Int): List<PracticeCompetenceSnapshot> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodePracticeIndex(get(PRACTICE_INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { capability ->
                    get(practiceSnapshotId(capability))
                        ?.takeIf { it.kind == PRACTICE_SNAPSHOT_KIND }
                        ?.let { AutonomousLearningCodec.decodeSnapshot(it.content) }
                }
                .take(limit)
                .toList()
        }
    }

    private fun MemoryOs.updatePracticeIndexLocked(capability: CapabilityId, now: Long) {
        val current = decodePracticeIndex(get(PRACTICE_INDEX_ID)?.content)
        val next = (current.filterNot { it == capability } + capability)
            .takeLast(MAX_INDEXED_PRACTICE_CAPABILITIES)
        remember(
            MemoryRecord(
                id = PRACTICE_INDEX_ID,
                kind = PRACTICE_INDEX_KIND,
                content = "capabilities=" + next.joinToString(",") { it.value },
                importance = 0.52,
                provenance = Provenance(
                    source = "zero-tool-autonomous-practice",
                    producer = "autonomous-learning-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun decodePracticeIndex(content: String?): List<CapabilityId> {
        if (content == null || !content.startsWith("capabilities=")) return emptyList()
        return content.removePrefix("capabilities=")
            .split(',')
            .filter { it.matches(CAPABILITY_PATTERN) }
            .distinct()
            .map(::CapabilityId)
            .takeLast(MAX_INDEXED_PRACTICE_CAPABILITIES)
    }

    private fun practiceTaskId(
        capability: CapabilityId,
        kind: AutonomousPracticeKind,
        needs: Set<LearningNeedKind>
    ): PracticeTaskId {
        val material = capability.value + "|" + kind.name + "|" +
            needs.map { it.name }.sorted().joinToString(",")
        return PracticeTaskId("practice:" + sha256(material).take(32))
    }

    private fun practiceSnapshotId(capability: CapabilityId): MemoryId =
        MemoryId("autonomous-practice:${capability.value}")

    companion object {
        const val PRACTICE_EVIDENCE_KIND = "autonomous-practice-evidence"
        const val PRACTICE_SNAPSHOT_KIND = "autonomous-practice-competence"
        const val PRACTICE_INDEX_KIND = "autonomous-practice-index"
        const val MAX_CURRICULUM_TASKS = 4
        const val MAX_DIAGNOSED_NEEDS = 16

        private const val SKILL_LOOKBACK = 24
        private const val GENERALIZATION_LOOKBACK = 24
        private const val MIN_REAL_EXECUTION_EVIDENCE = 3
        private const val MIN_RELIABILITY_ATTEMPTS = 2
        private const val TARGET_EXECUTION_SUCCESS_RATE = 0.80
        private const val MIN_PRACTICE_EVIDENCE = 2
        private const val TARGET_PRACTICE_PASS_RATE = 0.80
        private const val MAX_INDEXED_PRACTICE_CAPABILITIES = 64
        private val PRACTICE_INDEX_ID = MemoryId("autonomous-practice:index")
        private val CAPABILITY_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

object AutonomousLearningPracticePrompt {
    fun build(
        task: AutonomousPracticeTask,
        descriptor: ToolDescriptor,
        charBudget: Int = 4096
    ): String {
        require(task.capability == descriptor.capability)
        require(charBudget >= 1024)
        val protocol = TitanPlanProtocol.instructions(
            capabilities = setOf(task.capability),
            descriptors = listOf(descriptor)
        )
        val prefix = buildString {
            appendLine("AMPER autonomous practice. This is a zero-tool simulation.")
            appendLine("Do not claim execution and do not output authority, approval or permission.")
            appendLine("The output will only be parsed as a planning exercise.")
            appendLine("<PRACTICE_OBJECTIVE>")
            appendLine(SovereignPromptData.bounded(task.objective, 384))
            appendLine("</PRACTICE_OBJECTIVE>")
            appendLine()
        }
        require(prefix.length + protocol.length <= charBudget) {
            "practice prompt budget cannot preserve mandatory live planning protocol"
        }
        return (prefix + protocol).take(charBudget)
    }
}

private object AutonomousLearningCodec {
    fun encodeEvidence(
        taskId: PracticeTaskId,
        capability: CapabilityId,
        kind: AutonomousPracticeKind,
        verdict: AutonomousPracticeVerdict,
        validatedStepCount: Int,
        observedAtEpochMs: Long
    ): String = listOf(
        "v=1",
        "task=${enc(taskId.value)}",
        "capability=${enc(capability.value)}",
        "kind=${kind.name}",
        "verdict=${verdict.name}",
        "steps=$validatedStepCount",
        "observed=$observedAtEpochMs"
    ).joinToString(";")

    fun encodeSnapshot(snapshot: PracticeCompetenceSnapshot): String = listOf(
        "v=1",
        "capability=${enc(snapshot.capability.value)}",
        "passed=${snapshot.passed}",
        "failed=${snapshot.failed}",
        "last=${snapshot.lastObservedAtEpochMs}"
    ).joinToString(";")

    fun decodeSnapshot(content: String): PracticeCompetenceSnapshot? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        PracticeCompetenceSnapshot(
            capability = CapabilityId(dec(requireNotNull(f["capability"]))),
            passed = requireNotNull(f["passed"]).toInt(),
            failed = requireNotNull(f["failed"]).toInt(),
            lastObservedAtEpochMs = requireNotNull(f["last"]).toLong()
        )
    }.getOrNull()

    private fun fields(content: String): Map<String, String> =
        content.split(';').associate { field ->
            val split = field.indexOf('=')
            require(split > 0)
            field.substring(0, split) to field.substring(split + 1)
        }

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
