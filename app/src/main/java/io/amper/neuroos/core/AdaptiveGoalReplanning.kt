package io.amper.neuroos.core

enum class GoalAdaptiveReplanVerdict {
    KEEP_BLOCKED,
    REPLACE
}

data class GoalAdaptiveReplanAssessment(
    val verdict: GoalAdaptiveReplanVerdict,
    val replacements: List<DurableGoalDecompositionSpec> = emptyList(),
    val backendId: String? = null,
    val modelId: ModelId? = null
) {
    init {
        when (verdict) {
            GoalAdaptiveReplanVerdict.KEEP_BLOCKED ->
                require(replacements.isEmpty()) {
                    "KEEP_BLOCKED adaptive-replan verdict cannot carry replacements"
                }
            GoalAdaptiveReplanVerdict.REPLACE -> {
                require(replacements.size in 1..DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS)
                require(replacements.map { it.index } == (1..replacements.size).toList()) {
                    "adaptive replacement indices must be contiguous from 1"
                }
            }
        }
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface GoalAdaptiveReplanner {
    fun replan(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        failedPlan: SovereignPlan,
        goal: DurableGoalRecord,
        progress: DurableGoalHierarchyProgress
    ): Result<GoalAdaptiveReplanAssessment>
}

object GoalAdaptiveReplanningEligibility {
    fun hasSafeUnexecutedFailureEvidence(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        failedPlan: SovereignPlan,
        goal: DurableGoalRecord
    ): Boolean {
        if (checkpoint.stage != PersistentGoalExecutiveStage.RECOVERY_EXHAUSTED) return false
        if (goal.status != DurableGoalStatus.PENDING) return false
        if (goal.parentGoalId == null) return false
        if (goal.decompositionState == DurableGoalDecompositionState.DECOMPOSED) return false
        if (goal.replanGeneration >= DurableGoalRecord.MAX_ADAPTIVE_REPLAN_GENERATIONS) return false
        if (checkpoint.plannedPlanId != failedPlan.id) return false
        if (!failedPlan.complete) return false
        return failedPlan.steps.all { step ->
            step.status == PlanStepStatus.FAILED ||
                step.status == PlanStepStatus.MALFORMED ||
                step.status == PlanStepStatus.UNAVAILABLE
        }
    }

    fun isEligible(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        failedPlan: SovereignPlan,
        goal: DurableGoalRecord,
        records: Collection<DurableGoalRecord>
    ): Boolean =
        hasSafeUnexecutedFailureEvidence(checkpoint, failedPlan, goal) &&
            goal.adaptiveReplanAttemptedAtEpochMs == null &&
            records.none { it.parentGoalId == goal.sourceGoalId }
}

/**
 * Strict one-inference adaptive branch-replanning protocol.
 *
 * It can replace only a failed, unexecuted leaf subgoal. The model returns bounded objective data;
 * it cannot name tools, mutate authority, erase completed evidence, or claim the parent goal done.
 */
object GoalAdaptiveReplanProtocol {
    private const val OPEN = "<AMPER_GOAL_REPLAN_V1>"
    private const val CLOSE = "</AMPER_GOAL_REPLAN_V1>"
    private val REPLACEMENT_FIELD = Regex("replacement\\.(\\d+)\\.(objective|priority|depends)")

    fun parse(output: String): Result<GoalAdaptiveReplanAssessment> = runCatching {
        val text = output.trim()
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "adaptive-replan envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "adaptive-replan envelope is empty" }

        var verdict: GoalAdaptiveReplanVerdict? = null
        val fields = linkedMapOf<Pair<Int, String>, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "adaptive-replan field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(value.isNotBlank()) { "blank adaptive-replan value: $key" }

                if (key == "verdict") {
                    require(verdict == null) { "duplicate adaptive-replan verdict" }
                    verdict = GoalAdaptiveReplanVerdict.valueOf(value)
                } else {
                    val match = requireNotNull(REPLACEMENT_FIELD.matchEntire(key)) {
                        "unknown adaptive-replan field: $key"
                    }
                    val index = match.groupValues[1].toInt()
                    val field = match.groupValues[2]
                    require(fields.put(index to field, value) == null) {
                        "duplicate adaptive-replan field: $key"
                    }
                }
            }

        when (val resolved = requireNotNull(verdict) { "adaptive-replan verdict is required" }) {
            GoalAdaptiveReplanVerdict.KEEP_BLOCKED -> {
                require(fields.isEmpty()) {
                    "KEEP_BLOCKED adaptive-replan verdict cannot include replacements"
                }
                GoalAdaptiveReplanAssessment(resolved)
            }
            GoalAdaptiveReplanVerdict.REPLACE -> {
                val indices = fields.keys.map { it.first }.distinct().sorted()
                require(indices.size in 1..DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS) {
                    "adaptive replan must contain 1-" +
                        DurableGoalRecord.MAX_ADAPTIVE_REPLACEMENTS + " replacements"
                }
                require(indices == (1..indices.size).toList()) {
                    "adaptive replacement indices must be contiguous from 1"
                }
                val replacements = indices.map { index ->
                    val required = setOf("objective", "priority", "depends")
                    val present = fields.keys
                        .filter { it.first == index }
                        .map { it.second }
                        .toSet()
                    require(present == required) {
                        "replacement $index requires objective, priority and depends"
                    }
                    val objective = sanitizeObjective(fields.getValue(index to "objective"))
                    val priority = fields.getValue(index to "priority").toDouble()
                    require(priority in 0.0..1.0) {
                        "replacement $index priority is out of range"
                    }
                    val rawDependencies = fields.getValue(index to "depends")
                    val dependencies = if (rawDependencies == "~") {
                        emptySet()
                    } else {
                        rawDependencies.split(',')
                            .map { it.trim().toInt() }
                            .also { parsed ->
                                require(parsed.size == parsed.toSet().size) {
                                    "replacement $index dependency indices must be unique"
                                }
                            }
                            .toSortedSet()
                    }
                    require(dependencies.all { it in 1 until index }) {
                        "replacement $index dependencies may reference only earlier replacements"
                    }
                    DurableGoalDecompositionSpec(
                        index = index,
                        objective = objective,
                        priority = priority,
                        dependsOnIndices = dependencies
                    )
                }
                GoalAdaptiveReplanAssessment(
                    verdict = resolved,
                    replacements = replacements
                )
            }
        }
    }

    fun prompt(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        failedPlan: SovereignPlan,
        goal: DurableGoalRecord,
        progress: DurableGoalHierarchyProgress,
        state: IntegratedCognitiveStatePacket,
        maxChars: Int = 5_500
    ): String {
        require(
            GoalAdaptiveReplanningEligibility.hasSafeUnexecutedFailureEvidence(
                checkpoint = checkpoint,
                failedPlan = failedPlan,
                goal = goal
            )
        ) { "adaptive-replan prompt requires safe unexecuted recovery-exhausted evidence" }
        require(maxChars in 2_000..8_000)

        val planEvidence = failedPlan.steps.joinToString("\n") { step ->
            buildString {
                append("step.").append(step.index)
                append(".status=").append(step.status.name)
                append(";capability=").append(sanitize(step.capability.value, 96))
                append(";output=").append(sanitize(step.outcome?.output.orEmpty(), 220))
            }
        }.take(1_500)
        val cognitiveEvidence = state.canonicalLines()
            .joinToString("\n")
            .take(1_400)

        val prompt = buildString {
            appendLine("You are AMPER's bounded adaptive goal-branch replanner.")
            appendLine("The current leaf subgoal exhausted governed recovery without any executed step.")
            appendLine("Either KEEP_BLOCKED or replace only this unfinished leaf with 1-3 bounded alternatives.")
            appendLine("Never rewrite completed goals, verified evidence, sibling history, authority, approvals or tool contracts.")
            appendLine("Replacement dependencies may reference only earlier replacement indices.")
            appendLine("Do not emit tool calls, capabilities, execution claims, approvals or completion claims.")
            appendLine()
            appendLine("goal.id=" + sanitize(goal.sourceGoalId, 256))
            appendLine("goal.objective=" + sanitize(goal.objective, 1024))
            appendLine("goal.priority=" + goal.priority)
            appendLine("goal.replanGeneration=" + goal.replanGeneration)
            appendLine("checkpoint.failure=" + sanitize(checkpoint.lastFailureCode.orEmpty(), 128))
            appendLine("hierarchy.root=" + sanitize(progress.rootGoalId, 256))
            appendLine("hierarchy.completionRatio=" + progress.completionRatio)
            appendLine("hierarchy.completed=" + progress.completedGoalIds.size)
            appendLine("hierarchy.pending=" + progress.pendingGoalIds.size)
            appendLine("hierarchy.superseded=" + progress.supersededGoalIds.size)
            appendLine("failed.plan.evidence:")
            appendLine(planEvidence)
            appendLine("current.cognitive.evidence:")
            appendLine(cognitiveEvidence)
            appendLine()
            appendLine("For no safe structural alternative return exactly:")
            appendLine(OPEN)
            appendLine("verdict=KEEP_BLOCKED")
            appendLine(CLOSE)
            appendLine()
            appendLine("For replacement return exactly:")
            appendLine(OPEN)
            appendLine("verdict=REPLACE")
            appendLine("replacement.1.objective=<bounded independently verifiable objective>")
            appendLine("replacement.1.priority=<0.0..1.0>")
            appendLine("replacement.1.depends=~")
            appendLine("replacement.2.objective=<optional next bounded objective>")
            appendLine("replacement.2.priority=<0.0..1.0>")
            appendLine("replacement.2.depends=<~ or comma-separated earlier indices>")
            appendLine(CLOSE)
        }
        require(prompt.length <= maxChars) {
            "adaptive-replan prompt exceeds bounded character budget"
        }
        return prompt
    }

    private fun sanitizeObjective(value: String): String =
        sanitize(value, DurableGoalRecord.MAX_OBJECTIVE_CHARS)
            .ifBlank { error("adaptive replacement objective is blank") }

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('<', '[')
        .replace('>', ']')
        .trim()
        .take(maxChars)
}

class InferenceGoalAdaptiveReplanner(
    private val inference: CognitiveInferencePort,
    private val stateSource: IntegratedCognitiveStateSource,
    private val allowedCapabilities: Set<CapabilityId>,
    private val descriptors: () -> List<ToolDescriptor>,
    private val maxPromptChars: Int = 5_500,
    private val maxOutputTokens: Int = 220
) : GoalAdaptiveReplanner {
    init {
        require(allowedCapabilities.isNotEmpty())
        require(maxPromptChars in 2_000..8_000)
        require(maxOutputTokens in 16..256)
    }

    override fun replan(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        failedPlan: SovereignPlan,
        goal: DurableGoalRecord,
        progress: DurableGoalHierarchyProgress
    ): Result<GoalAdaptiveReplanAssessment> = runCatching {
        require(
            GoalAdaptiveReplanningEligibility.hasSafeUnexecutedFailureEvidence(
                checkpoint = checkpoint,
                failedPlan = failedPlan,
                goal = goal
            )
        ) { "adaptive replanning requires safe unexecuted recovery-exhausted evidence" }

        val liveDescriptors = descriptors()
            .filter { it.capability in allowedCapabilities }
            .distinctBy { it.id }
            .sortedBy { it.capability.value }
        val state = stateSource.capture(
            query = goal.objective,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = GoalAdaptiveReplanProtocol.prompt(
                    checkpoint = checkpoint,
                    failedPlan = failedPlan,
                    goal = goal,
                    progress = progress,
                    state = state,
                    maxChars = maxPromptChars
                ),
                requiredCapabilities = setOf(TitanCapabilities.REASONING),
                maxOutputTokens = maxOutputTokens,
                temperature = 0.0,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(
                        setOf(TitanCapabilities.REASONING)
                    )
            )
        ).getOrThrow()

        GoalAdaptiveReplanProtocol.parse(response.text).getOrThrow().copy(
            backendId = response.backendId,
            modelId = response.modelId
        )
    }
}
