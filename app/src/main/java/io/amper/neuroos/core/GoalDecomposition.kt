package io.amper.neuroos.core

enum class GoalDecompositionVerdict {
    ATOMIC,
    DECOMPOSED
}

data class GoalDecompositionAssessment(
    val verdict: GoalDecompositionVerdict,
    val subgoals: List<DurableGoalDecompositionSpec> = emptyList(),
    val backendId: String? = null,
    val modelId: ModelId? = null
) {
    init {
        when (verdict) {
            GoalDecompositionVerdict.ATOMIC ->
                require(subgoals.isEmpty()) { "atomic goal cannot carry decomposition subgoals" }
            GoalDecompositionVerdict.DECOMPOSED -> {
                require(subgoals.size in 2..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN)
                require(subgoals.map { it.index } == (1..subgoals.size).toList()) {
                    "decomposed subgoal indices must be contiguous from 1"
                }
            }
        }
    }

    val authorityBearing: Boolean
        get() = false
}

fun interface GoalDecomposer {
    fun decompose(goal: DurableGoalRecord): Result<GoalDecompositionAssessment>
}

/**
 * Strict one-inference goal decomposition protocol.
 *
 * The output is data only. It contains objectives, relative priorities and dependency indices;
 * it cannot name tools, execute actions, approve side effects or mutate authority.
 */
object GoalDecompositionProtocol {
    private const val OPEN = "<AMPER_GOAL_DECOMPOSE_V1>"
    private const val CLOSE = "</AMPER_GOAL_DECOMPOSE_V1>"
    private val SUBGOAL_FIELD = Regex("subgoal\\.(\\d+)\\.(objective|priority|depends)")

    fun parse(output: String): Result<GoalDecompositionAssessment> = runCatching {
        val text = output.trim()
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "goal-decomposition envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "goal-decomposition envelope is empty" }

        var verdict: GoalDecompositionVerdict? = null
        val fields = linkedMapOf<Pair<Int, String>, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "goal-decomposition field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(value.isNotBlank()) { "blank goal-decomposition value: $key" }

                if (key == "verdict") {
                    require(verdict == null) { "duplicate goal-decomposition verdict" }
                    verdict = GoalDecompositionVerdict.valueOf(value)
                } else {
                    val match = requireNotNull(SUBGOAL_FIELD.matchEntire(key)) {
                        "unknown goal-decomposition field: $key"
                    }
                    val index = match.groupValues[1].toInt()
                    val field = match.groupValues[2]
                    require(fields.put(index to field, value) == null) {
                        "duplicate goal-decomposition field: $key"
                    }
                }
            }

        when (val resolved = requireNotNull(verdict) { "goal-decomposition verdict is required" }) {
            GoalDecompositionVerdict.ATOMIC -> {
                require(fields.isEmpty()) {
                    "atomic goal-decomposition verdict cannot include subgoals"
                }
                GoalDecompositionAssessment(verdict = resolved)
            }
            GoalDecompositionVerdict.DECOMPOSED -> {
                val indices = fields.keys.map { it.first }.distinct().sorted()
                require(indices.size in 2..DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN) {
                    "decomposition must contain 2-" +
                        DurableGoalRecord.MAX_DECOMPOSITION_CHILDREN + " subgoals"
                }
                require(indices == (1..indices.size).toList()) {
                    "decomposition subgoal indices must be contiguous from 1"
                }
                val subgoals = indices.map { index ->
                    val required = setOf("objective", "priority", "depends")
                    val present = fields.keys.filter { it.first == index }.map { it.second }.toSet()
                    require(present == required) {
                        "subgoal $index requires objective, priority and depends"
                    }
                    val objective = sanitizeObjective(fields.getValue(index to "objective"))
                    val priority = fields.getValue(index to "priority").toDouble()
                    require(priority in 0.0..1.0) { "subgoal $index priority is out of range" }
                    val dependencyText = fields.getValue(index to "depends")
                    val dependencies = if (dependencyText == "~") {
                        emptySet()
                    } else {
                        dependencyText.split(',')
                            .map { it.trim().toInt() }
                            .also { parsed ->
                                require(parsed.size == parsed.toSet().size) {
                                    "subgoal $index dependency indices must be unique"
                                }
                            }
                            .toSortedSet()
                    }
                    require(dependencies.all { it in 1 until index }) {
                        "subgoal $index dependencies may reference only earlier subgoals"
                    }
                    DurableGoalDecompositionSpec(
                        index = index,
                        objective = objective,
                        priority = priority,
                        dependsOnIndices = dependencies
                    )
                }
                GoalDecompositionAssessment(
                    verdict = resolved,
                    subgoals = subgoals
                )
            }
        }
    }

    fun prompt(
        goal: DurableGoalRecord,
        state: IntegratedCognitiveStatePacket,
        maxChars: Int = 5_000
    ): String {
        require(goal.status == DurableGoalStatus.PENDING)
        require(goal.decompositionState == DurableGoalDecompositionState.NONE)
        require(maxChars in 2_000..8_000)

        val evidence = state.canonicalLines()
            .joinToString("\n")
            .take(1_500)
        val prompt = buildString {
            appendLine("You are AMPER's bounded goal decomposer.")
            appendLine("Decide whether this objective is atomic enough for one normal planning cycle.")
            appendLine("Use DECOMPOSED only when 2-4 independently verifiable subgoals materially improve execution.")
            appendLine("Dependencies may reference only earlier subgoal indices, so the result is a DAG.")
            appendLine("Do not emit tool calls, capabilities, execution claims, approvals, or side effects.")
            appendLine("Do not claim that decomposition itself satisfies the parent goal.")
            appendLine("Keep every child priority between 0.0 and 1.0; runtime will cap it at parent priority.")
            appendLine()
            appendLine("goal.id=" + sanitize(goal.sourceGoalId, 256))
            appendLine("goal.objective=" + sanitize(goal.objective, 1024))
            appendLine("goal.priority=" + goal.priority)
            appendLine("goal.decompositionDepth=" + goal.decompositionDepth)
            appendLine("current.cognitive.evidence:")
            appendLine(evidence)
            appendLine()
            appendLine("For an atomic goal return exactly:")
            appendLine(OPEN)
            appendLine("verdict=ATOMIC")
            appendLine(CLOSE)
            appendLine()
            appendLine("For a decomposed goal return exactly:")
            appendLine(OPEN)
            appendLine("verdict=DECOMPOSED")
            appendLine("subgoal.1.objective=<bounded independently verifiable objective>")
            appendLine("subgoal.1.priority=<0.0..1.0>")
            appendLine("subgoal.1.depends=~")
            appendLine("subgoal.2.objective=<bounded independently verifiable objective>")
            appendLine("subgoal.2.priority=<0.0..1.0>")
            appendLine("subgoal.2.depends=<~ or comma-separated earlier indices>")
            appendLine(CLOSE)
        }
        require(prompt.length <= maxChars) {
            "goal-decomposition prompt exceeds bounded character budget"
        }
        return prompt
    }

    private fun sanitizeObjective(value: String): String =
        sanitize(value, DurableGoalRecord.MAX_OBJECTIVE_CHARS)
            .ifBlank { error("subgoal objective is blank") }

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('<', '[')
        .replace('>', ']')
        .trim()
        .take(maxChars)
}

class InferenceGoalDecomposer(
    private val inference: CognitiveInferencePort,
    private val stateSource: IntegratedCognitiveStateSource,
    private val allowedCapabilities: Set<CapabilityId>,
    private val descriptors: () -> List<ToolDescriptor>,
    private val maxPromptChars: Int = 5_000,
    private val maxOutputTokens: Int = 220
) : GoalDecomposer {
    init {
        require(allowedCapabilities.isNotEmpty())
        require(maxPromptChars in 2_000..8_000)
        require(maxOutputTokens in 16..256)
    }

    override fun decompose(goal: DurableGoalRecord): Result<GoalDecompositionAssessment> =
        runCatching {
            require(goal.status == DurableGoalStatus.PENDING)
            require(goal.decompositionState == DurableGoalDecompositionState.NONE)
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
                    prompt = GoalDecompositionProtocol.prompt(
                        goal = goal,
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

            val parsed = GoalDecompositionProtocol.parse(response.text).getOrThrow()
            parsed.copy(
                backendId = response.backendId,
                modelId = response.modelId
            )
        }
}
