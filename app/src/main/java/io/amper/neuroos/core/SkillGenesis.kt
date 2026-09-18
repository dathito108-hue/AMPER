package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

@JvmInline
value class SkillId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class SkillStateCondition(
    val key: WorldStateKey,
    val value: String
) {
    init { require(value.isNotBlank()) }

    val canonical: String
        get() = "${key.canonical}=${value.trim().lowercase(Locale.ROOT)}"
}

enum class SkillMaturity {
    CANDIDATE,
    ACTIVE,
    DEGRADED
}

data class SkillContract(
    val id: SkillId,
    val signature: StrategySignature,
    val preconditions: List<SkillStateCondition>,
    val effects: List<SkillStateCondition>,
    val successes: Int,
    val executionFailures: Int,
    val maturity: SkillMaturity,
    val confidence: Double,
    val lastObservedAtEpochMs: Long
) {
    init {
        require(successes >= 0)
        require(executionFailures >= 0)
        require(successes + executionFailures > 0)
        require(confidence in 0.0..1.0)
        require(lastObservedAtEpochMs >= 0L)
        require(preconditions.distinctBy { it.key.canonical }.size == preconditions.size)
        require(effects.distinctBy { it.key.canonical }.size == effects.size)
    }

    val attempts: Int
        get() = successes + executionFailures

    val successRate: Double
        get() = successes.toDouble() / attempts.toDouble()

    val authorityBearing: Boolean
        get() = false
}

data class SkillGuidance(
    val contract: SkillContract,
    val preconditionsSatisfied: Boolean,
    val goalRelevance: Double
) {
    init {
        require(goalRelevance in 0.0..1.0)
        require(contract.maturity == SkillMaturity.ACTIVE)
        require(!contract.authorityBearing)
    }
}

data class SkillComposition(
    val first: SkillContract,
    val second: SkillContract,
    val capabilities: List<CapabilityId>,
    val preconditions: List<SkillStateCondition>,
    val effects: List<SkillStateCondition>,
    val confidence: Double
) {
    init {
        require(first.id != second.id)
        require(capabilities.isNotEmpty())
        require(capabilities.size <= TitanPlanProtocol.MAX_STEPS)
        require(confidence in 0.0..1.0)
    }
}

interface SkillGenesisModel {
    fun begin(plan: SovereignPlan, worldStates: List<StructuredWorldState>)
    fun observe(plan: SovereignPlan, worldStates: List<StructuredWorldState>): SkillContract?
    fun snapshot(signature: StrategySignature): SkillContract?
    fun recent(limit: Int = 8): List<SkillContract>

    fun guidance(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int = 4
    ): List<SkillGuidance>

    fun compositions(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int = 3
    ): List<SkillComposition>
}

/**
 * Phase196-200 autonomous skill genesis.
 *
 * Phase196 defines reusable skills as capability sequences plus evidence-derived world-state
 * preconditions/effects. There are deliberately no authority, approval, tool-id, raw input or raw
 * output fields in a skill contract.
 *
 * Phase197 captures a bounded world snapshot when a plan is created and learns only from governed
 * terminal execution evidence. Successful plans can add skill credit; actual execution failures can
 * reduce it. Authority denial, environment unavailability, protocol failure and user rejection do
 * not teach an executable skill.
 *
 * Phase198 promotes a skill only after repeated execution evidence. Guidance also requires every
 * capability to exist in the live tool surface and all learned preconditions to match current
 * structured world state.
 *
 * Phase199 composes two active skills only when the first skill's learned effects satisfy the
 * second skill's learned preconditions and the composed capability sequence remains bounded.
 *
 * Phase200 exposes skill/composition guidance to planning as advisory data. The model must still
 * emit a normal plan that is parsed and rebound through TitanPlanProtocol; tool authority and
 * explicit side-effect approval therefore remain unchanged.
 */
class MemoryBackedSkillGenesisModel(
    private val memory: MemoryOs,
    private val clock: () -> Long = System::currentTimeMillis
) : SkillGenesisModel {
    override fun begin(plan: SovereignPlan, worldStates: List<StructuredWorldState>) {
        val signature = StrategySignature.from(plan)
        val marker = SkillStartSnapshot(
            planId = plan.id,
            signature = signature,
            preconditions = conditions(worldStates),
            capturedAtEpochMs = clock()
        )
        val id = startId(plan.id)
        memory.transaction {
            val existing = get(id)
            if (existing != null) {
                val decoded = requireNotNull(SkillGenesisCodec.decodeStart(existing.content)) {
                    "skill start snapshot is malformed"
                }
                require(decoded.planId == plan.id) {
                    "skill start snapshot plan identity mismatch"
                }
                require(decoded.signature == signature) {
                    "skill start snapshot signature mismatch"
                }
                require(decoded.preconditions == marker.preconditions) {
                    "skill start snapshot world preconditions changed"
                }
                return@transaction
            }
            remember(
                MemoryRecord(
                    id = id,
                    kind = START_KIND,
                    content = SkillGenesisCodec.encodeStart(marker),
                    importance = 0.60,
                    provenance = Provenance(
                        source = "governed-plan-start",
                        producer = "skill-genesis",
                        confidence = 1.0
                    ),
                    createdAtEpochMs = marker.capturedAtEpochMs
                )
            )
        }
    }

    override fun observe(
        plan: SovereignPlan,
        worldStates: List<StructuredWorldState>
    ): SkillContract? {
        if (!plan.complete) return null
        val outcome = classify(plan) ?: return null
        val signature = StrategySignature.from(plan)
        val startRecord = memory.get(startId(plan.id))
            ?.takeIf { it.kind == START_KIND }
            ?: return null
        val start = SkillGenesisCodec.decodeStart(startRecord.content)
            ?: error("skill start snapshot is malformed")
        require(start.signature == signature) {
            "completed plan changed skill signature"
        }

        val observationId = observationId(plan.id)
        return memory.transaction {
            val existingObservation = get(observationId)
            if (existingObservation != null) {
                val decoded = SkillGenesisCodec.decodeObservation(existingObservation.content)
                    ?: error("skill observation is malformed")
                require(decoded.signature == signature) {
                    "skill observation signature mismatch"
                }
                require(decoded.outcome == outcome) {
                    "skill observation outcome changed"
                }
                return@transaction snapshotLocked(signature)
            }

            val end = conditions(worldStates)
            val effects = if (outcome == SkillObservationOutcome.SUCCESS) {
                changedEffects(start.preconditions, end)
            } else {
                emptyList()
            }
            val now = clock()
            val observation = SkillObservation(
                planId = plan.id,
                signature = signature,
                outcome = outcome,
                preconditions = start.preconditions,
                effects = effects,
                observedAtEpochMs = now
            )
            remember(
                MemoryRecord(
                    id = observationId,
                    kind = OBSERVATION_KIND,
                    content = SkillGenesisCodec.encodeObservation(observation),
                    importance = if (outcome == SkillObservationOutcome.SUCCESS) 0.78 else 0.62,
                    provenance = Provenance(
                        source = "governed-plan-outcome",
                        producer = "skill-genesis",
                        confidence = 1.0,
                        parents = setOf(startId(plan.id))
                    ),
                    createdAtEpochMs = now
                )
            )

            val previous = snapshotLocked(signature)
            val successes = (previous?.successes ?: 0) +
                if (outcome == SkillObservationOutcome.SUCCESS) 1 else 0
            val failures = (previous?.executionFailures ?: 0) +
                if (outcome == SkillObservationOutcome.EXECUTION_FAILURE) 1 else 0

            val learnedPreconditions = when {
                outcome != SkillObservationOutcome.SUCCESS ->
                    previous?.preconditions.orEmpty()
                previous == null || previous.successes == 0 ->
                    start.preconditions
                else ->
                    intersectConditions(previous.preconditions, start.preconditions)
            }
            val learnedEffects = when {
                outcome != SkillObservationOutcome.SUCCESS ->
                    previous?.effects.orEmpty()
                previous == null || previous.successes == 0 ->
                    effects
                else ->
                    intersectConditions(previous.effects, effects)
            }

            val attempts = successes + failures
            val successRate = successes.toDouble() / attempts.toDouble()
            val evidenceConfidence = attempts.toDouble() / (attempts.toDouble() + 4.0)
            val confidence = (successRate * evidenceConfidence).coerceIn(0.0, 1.0)
            val maturity = when {
                successes >= MIN_SUCCESSFUL_OBSERVATIONS &&
                    successRate >= MIN_ACTIVE_SUCCESS_RATE ->
                    SkillMaturity.ACTIVE
                previous?.maturity == SkillMaturity.ACTIVE ->
                    SkillMaturity.DEGRADED
                else ->
                    SkillMaturity.CANDIDATE
            }

            val contract = SkillContract(
                id = skillId(signature),
                signature = signature,
                preconditions = learnedPreconditions,
                effects = learnedEffects,
                successes = successes,
                executionFailures = failures,
                maturity = maturity,
                confidence = confidence,
                lastObservedAtEpochMs = now
            )
            remember(
                MemoryRecord(
                    id = snapshotId(signature),
                    kind = SNAPSHOT_KIND,
                    content = SkillGenesisCodec.encodeContract(contract),
                    importance = if (maturity == SkillMaturity.ACTIVE) 0.90 else 0.72,
                    provenance = Provenance(
                        source = "skill-evidence",
                        producer = "skill-genesis",
                        confidence = confidence,
                        parents = setOf(observationId)
                    ),
                    createdAtEpochMs = now
                )
            )
            updateIndexLocked(signature.digest, now)
            contract
        }
    }

    override fun snapshot(signature: StrategySignature): SkillContract? =
        memory.transaction { snapshotLocked(signature) }

    override fun recent(limit: Int): List<SkillContract> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.transaction {
            decodeIndex(get(INDEX_ID)?.content)
                .asReversed()
                .asSequence()
                .mapNotNull { digest ->
                    get(MemoryId("skill-contract:$digest"))
                        ?.takeIf { it.kind == SNAPSHOT_KIND }
                        ?.let { SkillGenesisCodec.decodeContract(it.content) }
                }
                .take(limit)
                .toList()
        }
    }

    override fun guidance(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int
    ): List<SkillGuidance> {
        require(limit in 0..MAX_GUIDANCE)
        if (limit == 0) return emptyList()
        val liveCapabilities = descriptors.map { it.capability }.toSet()
        val current = currentConditions(worldStates)
        val goalTerms = terms(goal)
        val descriptorTerms = descriptors.associate { descriptor ->
            descriptor.capability to terms(
                descriptor.capability.value + " " +
                    descriptor.name + " " +
                    descriptor.inputContract.description
            )
        }

        return recent(SKILL_LOOKBACK)
            .asSequence()
            .filter { it.maturity == SkillMaturity.ACTIVE }
            .filter { skill ->
                skill.signature.capabilities.all {
                    it in allowedCapabilities && it in liveCapabilities
                }
            }
            .map { skill ->
                val preconditionsSatisfied = skill.preconditions.all { condition ->
                    current[condition.key.canonical]
                        ?.let { normalize(it) == normalize(condition.value) }
                        ?: false
                }
                SkillGuidance(
                    contract = skill,
                    preconditionsSatisfied = preconditionsSatisfied,
                    goalRelevance = relevance(skill, goalTerms, descriptorTerms)
                )
            }
            .filter { it.preconditionsSatisfied }
            .sortedWith(
                compareByDescending<SkillGuidance> {
                    it.contract.confidence * (0.75 + it.goalRelevance * 0.25)
                }
                    .thenByDescending { it.contract.successes }
                    .thenBy { it.contract.signature.canonical }
            )
            .take(limit)
            .toList()
    }

    override fun compositions(
        goal: String,
        allowedCapabilities: Set<CapabilityId>,
        descriptors: Collection<ToolDescriptor>,
        worldStates: List<StructuredWorldState>,
        limit: Int
    ): List<SkillComposition> {
        require(limit in 0..MAX_COMPOSITIONS)
        if (limit == 0) return emptyList()
        val liveCapabilities = descriptors.map { it.capability }.toSet()
        val current = currentConditions(worldStates)
        val active = recent(SKILL_LOOKBACK)
            .filter { it.maturity == SkillMaturity.ACTIVE }
            .filter { skill ->
                skill.signature.capabilities.all {
                    it in allowedCapabilities && it in liveCapabilities
                }
            }
        val firstSkills = active.filter { skill ->
            skill.preconditions.all { condition ->
                current[condition.key.canonical]
                    ?.let { normalize(it) == normalize(condition.value) }
                    ?: false
            }
        }

        return firstSkills.flatMap { first ->
            active.mapNotNull { second ->
                if (first.id == second.id || second.preconditions.isEmpty()) return@mapNotNull null
                val firstEffects = first.effects.associate { it.key.canonical to normalize(it.value) }
                if (!second.preconditions.all { condition ->
                        firstEffects[condition.key.canonical] == normalize(condition.value)
                    }
                ) return@mapNotNull null

                val capabilities = first.signature.capabilities + second.signature.capabilities
                if (capabilities.size > TitanPlanProtocol.MAX_STEPS) return@mapNotNull null
                val relevance = maxOf(
                    relevance(first, terms(goal), descriptors.associate { it.capability to terms(
                        it.capability.value + " " + it.name + " " + it.inputContract.description
                    ) }),
                    relevance(second, terms(goal), descriptors.associate { it.capability to terms(
                        it.capability.value + " " + it.name + " " + it.inputContract.description
                    ) })
                )
                SkillComposition(
                    first = first,
                    second = second,
                    capabilities = capabilities,
                    preconditions = first.preconditions,
                    effects = second.effects,
                    confidence = (minOf(first.confidence, second.confidence) * (0.85 + relevance * 0.15))
                        .coerceIn(0.0, 1.0)
                )
            }
        }
            .distinctBy { it.first.id.value + ">" + it.second.id.value }
            .sortedWith(
                compareByDescending<SkillComposition> { it.confidence }
                    .thenBy { it.capabilities.size }
                    .thenBy { it.capabilities.joinToString(">") { capability -> capability.value } }
            )
            .take(limit)
    }

    private fun MemoryOs.snapshotLocked(signature: StrategySignature): SkillContract? =
        get(snapshotId(signature))
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { SkillGenesisCodec.decodeContract(it.content) }
            ?.takeIf { it.signature == signature }

    private fun MemoryOs.updateIndexLocked(digest: String, now: Long) {
        val current = decodeIndex(get(INDEX_ID)?.content)
        val next = (current.filterNot { it == digest } + digest)
            .takeLast(MAX_INDEXED_SKILLS)
        remember(
            MemoryRecord(
                id = INDEX_ID,
                kind = INDEX_KIND,
                content = "digests=" + next.joinToString(","),
                importance = 0.88,
                provenance = Provenance(
                    source = "skill-evidence",
                    producer = "skill-genesis-index",
                    confidence = 1.0
                ),
                createdAtEpochMs = now
            )
        )
    }

    private fun classify(plan: SovereignPlan): SkillObservationOutcome? {
        val exactExecuted = plan.steps.all { step ->
            val outcome = step.outcome
            val proposal = outcome?.proposal
            step.status == PlanStepStatus.EXECUTED &&
                outcome?.status == ActionStatus.EXECUTED &&
                outcome.toolId == step.boundToolId &&
                outcome.sideEffect == step.boundSideEffect &&
                proposal?.requestId == step.requestId &&
                proposal?.capability == step.capability
        }
        if (exactExecuted) return SkillObservationOutcome.SUCCESS

        val executionFailure = plan.steps.any { step ->
            val outcome = step.outcome
            val proposal = outcome?.proposal
            step.status == PlanStepStatus.FAILED &&
                outcome?.status == ActionStatus.FAILED &&
                outcome.toolId == step.boundToolId &&
                outcome.sideEffect == step.boundSideEffect &&
                proposal?.requestId == step.requestId &&
                proposal?.capability == step.capability
        }
        return if (executionFailure) SkillObservationOutcome.EXECUTION_FAILURE else null
    }

    private fun conditions(states: List<StructuredWorldState>): List<SkillStateCondition> =
        states.asSequence()
            .filter { it.status == StructuredWorldStateStatus.KNOWN && !it.value.isNullOrBlank() }
            .map {
                SkillStateCondition(
                    key = WorldStateKey(it.key.entity, it.key.attribute),
                    value = requireNotNull(it.value)
                )
            }
            .distinctBy { it.key.canonical }
            .sortedBy { it.key.canonical }
            .take(MAX_STATE_CONDITIONS)
            .toList()

    private fun currentConditions(states: List<StructuredWorldState>): Map<String, String> =
        conditions(states).associate { it.key.canonical to it.value }

    private fun changedEffects(
        before: List<SkillStateCondition>,
        after: List<SkillStateCondition>
    ): List<SkillStateCondition> {
        val initial = before.associate { it.key.canonical to normalize(it.value) }
        return after
            .filter { condition ->
                initial[condition.key.canonical] != normalize(condition.value)
            }
            .take(MAX_STATE_CONDITIONS)
    }

    private fun intersectConditions(
        left: List<SkillStateCondition>,
        right: List<SkillStateCondition>
    ): List<SkillStateCondition> {
        val rightByKey = right.associateBy { it.key.canonical }
        return left.filter { condition ->
            rightByKey[condition.key.canonical]
                ?.let { normalize(it.value) == normalize(condition.value) }
                ?: false
        }
    }

    private fun relevance(
        skill: SkillContract,
        goalTerms: Set<String>,
        descriptors: Map<CapabilityId, Set<String>>
    ): Double {
        if (goalTerms.isEmpty()) return 0.0
        val skillTerms = buildSet {
            skill.signature.capabilities.forEach { addAll(descriptors[it].orEmpty()) }
            skill.preconditions.forEach {
                addAll(terms(it.key.entity + " " + it.key.attribute + " " + it.value))
            }
            skill.effects.forEach {
                addAll(terms(it.key.entity + " " + it.key.attribute + " " + it.value))
            }
        }
        if (skillTerms.isEmpty()) return 0.0
        val overlap = goalTerms.intersect(skillTerms).size
        return (overlap.toDouble() / minOf(goalTerms.size, skillTerms.size).coerceAtLeast(1).toDouble())
            .coerceIn(0.0, 1.0)
    }

    private fun terms(value: String): Set<String> =
        TOKEN.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length >= 2 }
            .filterNot { it in STOP_TERMS }
            .take(MAX_GOAL_TERMS)
            .toSet()

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT)

    private fun startId(planId: PlanId): MemoryId =
        MemoryId("skill-start:${planId.value}")

    private fun observationId(planId: PlanId): MemoryId =
        MemoryId("skill-observation:${planId.value}")

    private fun snapshotId(signature: StrategySignature): MemoryId =
        MemoryId("skill-contract:${signature.digest}")

    private fun skillId(signature: StrategySignature): SkillId =
        SkillId("skill:${signature.digest}")

    private fun decodeIndex(content: String?): List<String> {
        if (content == null || !content.startsWith("digests=")) return emptyList()
        return content.removePrefix("digests=")
            .split(',')
            .filter { it.matches(SHA256) }
            .distinct()
            .takeLast(MAX_INDEXED_SKILLS)
    }

    companion object {
        const val START_KIND = "skill-start"
        const val OBSERVATION_KIND = "skill-observation"
        const val SNAPSHOT_KIND = "skill-contract"
        const val INDEX_KIND = "skill-index"
        const val MIN_SUCCESSFUL_OBSERVATIONS = 2
        const val MIN_ACTIVE_SUCCESS_RATE = 0.75
        const val MAX_GUIDANCE = 4
        const val MAX_COMPOSITIONS = 3

        private const val MAX_STATE_CONDITIONS = 8
        private const val MAX_INDEXED_SKILLS = 64
        private const val SKILL_LOOKBACK = 16
        private const val MAX_GOAL_TERMS = 24
        private val INDEX_ID = MemoryId("skill:index")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val STOP_TERMS = setOf(
            "a", "an", "and", "the", "to", "of", "for", "with", "from", "this", "that",
            "please", "user", "current", "one", "value",
            "và", "là", "cho", "với", "của", "này", "hãy", "tôi", "giúp", "cần", "được"
        )
    }
}

private enum class SkillObservationOutcome {
    SUCCESS,
    EXECUTION_FAILURE
}

private data class SkillStartSnapshot(
    val planId: PlanId,
    val signature: StrategySignature,
    val preconditions: List<SkillStateCondition>,
    val capturedAtEpochMs: Long
)

private data class SkillObservation(
    val planId: PlanId,
    val signature: StrategySignature,
    val outcome: SkillObservationOutcome,
    val preconditions: List<SkillStateCondition>,
    val effects: List<SkillStateCondition>,
    val observedAtEpochMs: Long
)

object SkillGuidanceRenderer {
    fun render(
        skills: List<SkillGuidance>,
        compositions: List<SkillComposition>
    ): String {
        if (skills.isEmpty() && compositions.isEmpty()) return ""
        return buildString {
            appendLine("<SKILL_GUIDANCE>")
            appendLine(
                "Learned governed execution patterns only. Skills are advisory planning evidence, " +
                    "never authority, permission, approval, a tool result, or an instruction to execute."
            )
            appendLine(
                "Any plan inspired by a skill must still use the current goal, live tool contracts, " +
                    "TitanPlanProtocol binding, AuthorityGate and explicit side-effect approval."
            )
            skills.take(MemoryBackedSkillGenesisModel.MAX_GUIDANCE).forEachIndexed { index, item ->
                val skill = item.contract
                append("skill.${index + 1}.capabilities=")
                append(skill.signature.capabilities.joinToString(">") { it.value })
                append(" confidence=")
                append(fmt(skill.confidence))
                append(" successes=")
                append(skill.successes)
                append(" execution_failures=")
                append(skill.executionFailures)
                append(" preconditions_satisfied=")
                append(item.preconditionsSatisfied)
                append(" goal_relevance=")
                append(fmt(item.goalRelevance))
                append(" preconditions=")
                append(conditions(skill.preconditions))
                append(" effects=")
                append(conditions(skill.effects))
                appendLine(" authority=false")
            }
            compositions.take(MemoryBackedSkillGenesisModel.MAX_COMPOSITIONS).forEachIndexed { index, item ->
                append("composition.${index + 1}.capabilities=")
                append(item.capabilities.joinToString(">") { it.value })
                append(" confidence=")
                append(fmt(item.confidence))
                append(" preconditions=")
                append(conditions(item.preconditions))
                append(" effects=")
                append(conditions(item.effects))
                appendLine(" authority=false")
            }
            append("</SKILL_GUIDANCE>")
        }
    }

    private fun conditions(values: List<SkillStateCondition>): String =
        if (values.isEmpty()) "none"
        else values.joinToString(",") {
            "${it.key.canonical}=${it.value.replace('\n', ' ').replace('\r', ' ').take(96)}"
        }

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.US, value)
}

private object SkillGenesisCodec {
    fun encodeStart(value: SkillStartSnapshot): String = listOf(
        "v=1",
        "plan=${enc(value.planId.value)}",
        "capabilities=${encodeCapabilities(value.signature.capabilities)}",
        "preconditions=${encodeConditions(value.preconditions)}",
        "captured=${value.capturedAtEpochMs}"
    ).joinToString(";")

    fun decodeStart(content: String): SkillStartSnapshot? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        SkillStartSnapshot(
            planId = PlanId(dec(requireNotNull(f["plan"]))),
            signature = StrategySignature(decodeCapabilities(requireNotNull(f["capabilities"]))),
            preconditions = decodeConditions(requireNotNull(f["preconditions"])),
            capturedAtEpochMs = requireNotNull(f["captured"]).toLong()
        )
    }.getOrNull()

    fun encodeObservation(value: SkillObservation): String = listOf(
        "v=1",
        "plan=${enc(value.planId.value)}",
        "capabilities=${encodeCapabilities(value.signature.capabilities)}",
        "outcome=${value.outcome.name}",
        "preconditions=${encodeConditions(value.preconditions)}",
        "effects=${encodeConditions(value.effects)}",
        "observed=${value.observedAtEpochMs}"
    ).joinToString(";")

    fun decodeObservation(content: String): SkillObservation? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        SkillObservation(
            planId = PlanId(dec(requireNotNull(f["plan"]))),
            signature = StrategySignature(decodeCapabilities(requireNotNull(f["capabilities"]))),
            outcome = SkillObservationOutcome.valueOf(requireNotNull(f["outcome"])),
            preconditions = decodeConditions(requireNotNull(f["preconditions"])),
            effects = decodeConditions(requireNotNull(f["effects"])),
            observedAtEpochMs = requireNotNull(f["observed"]).toLong()
        )
    }.getOrNull()

    fun encodeContract(value: SkillContract): String = listOf(
        "v=1",
        "id=${enc(value.id.value)}",
        "capabilities=${encodeCapabilities(value.signature.capabilities)}",
        "preconditions=${encodeConditions(value.preconditions)}",
        "effects=${encodeConditions(value.effects)}",
        "successes=${value.successes}",
        "execution_failures=${value.executionFailures}",
        "maturity=${value.maturity.name}",
        "confidence=${enc(value.confidence.toString())}",
        "observed=${value.lastObservedAtEpochMs}"
    ).joinToString(";")

    fun decodeContract(content: String): SkillContract? = runCatching {
        val f = fields(content)
        require(f["v"] == "1")
        SkillContract(
            id = SkillId(dec(requireNotNull(f["id"]))),
            signature = StrategySignature(decodeCapabilities(requireNotNull(f["capabilities"]))),
            preconditions = decodeConditions(requireNotNull(f["preconditions"])),
            effects = decodeConditions(requireNotNull(f["effects"])),
            successes = requireNotNull(f["successes"]).toInt(),
            executionFailures = requireNotNull(f["execution_failures"]).toInt(),
            maturity = SkillMaturity.valueOf(requireNotNull(f["maturity"])),
            confidence = dec(requireNotNull(f["confidence"])).toDouble(),
            lastObservedAtEpochMs = requireNotNull(f["observed"]).toLong()
        )
    }.getOrNull()

    private fun encodeCapabilities(values: List<CapabilityId>): String =
        values.joinToString(",") { enc(it.value) }

    private fun decodeCapabilities(value: String): List<CapabilityId> =
        value.split(',').filter { it.isNotBlank() }.map { CapabilityId(dec(it)) }

    private fun encodeConditions(values: List<SkillStateCondition>): String =
        if (values.isEmpty()) "~" else values.joinToString(",") { condition ->
            listOf(
                enc(condition.key.entity),
                enc(condition.key.attribute),
                enc(condition.value)
            ).joinToString("~")
        }

    private fun decodeConditions(value: String): List<SkillStateCondition> =
        if (value == "~") emptyList()
        else value.split(',').map { encoded ->
            val p = encoded.split('~')
            require(p.size == 3)
            SkillStateCondition(
                key = WorldStateKey(dec(p[0]), dec(p[1])),
                value = dec(p[2])
            )
        }

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
