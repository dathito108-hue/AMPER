package io.amper.neuroos.core

enum class GoalSatisfactionVerdict {
    SATISFIED,
    FOLLOW_UP_REQUIRED
}

data class GoalSatisfactionAssessment(
    val planId: PlanId,
    val verdict: GoalSatisfactionVerdict,
    val confidence: Double,
    val reason: String,
    val cognitiveStateDigest: String,
    val executionContextDigest: String,
    val backendId: String? = null,
    val modelId: ModelId? = null
) {
    init {
        require(confidence in 0.0..1.0)
        require(reason.isNotBlank() && reason.length <= 256)
        require(cognitiveStateDigest.matches(SHA256))
        require(executionContextDigest.matches(SHA256))
    }

    val authorityBearing: Boolean
        get() = false

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

fun interface GoalSatisfactionVerifier {
    fun verify(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan
    ): Result<GoalSatisfactionAssessment>
}

object GoalSatisfactionProtocol {
    const val MIN_SATISFIED_CONFIDENCE = 0.80
    private const val OPEN = "<AMPER_GOAL_VERIFY_V1>"
    private const val CLOSE = "</AMPER_GOAL_VERIFY_V1>"
    private val FIELDS = setOf("verdict", "confidence", "reason")

    fun parse(
        planId: PlanId,
        output: String,
        cognitiveStateDigest: String,
        executionContextDigest: String,
        backendId: String? = null,
        modelId: ModelId? = null
    ): Result<GoalSatisfactionAssessment> = runCatching {
        val text = output.trim()
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "goal-verification envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        val fields = linkedMapOf<String, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "goal-verification field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(key in FIELDS) { "unknown goal-verification field: $key" }
                require(value.isNotBlank()) { "blank goal-verification value: $key" }
                require(fields.put(key, value) == null) {
                    "duplicate goal-verification field: $key"
                }
            }
        require(fields.keys == FIELDS) {
            "goal-verification fields must be exactly verdict/confidence/reason"
        }

        val rawVerdict = GoalSatisfactionVerdict.valueOf(fields.getValue("verdict"))
        val confidence = fields.getValue("confidence").toDouble()
        require(confidence in 0.0..1.0)
        val reason = sanitizeReason(fields.getValue("reason"))

        val conservativeVerdict =
            if (
                rawVerdict == GoalSatisfactionVerdict.SATISFIED &&
                confidence < MIN_SATISFIED_CONFIDENCE
            ) {
                GoalSatisfactionVerdict.FOLLOW_UP_REQUIRED
            } else {
                rawVerdict
            }

        GoalSatisfactionAssessment(
            planId = planId,
            verdict = conservativeVerdict,
            confidence = confidence,
            reason = if (conservativeVerdict != rawVerdict) {
                sanitizeReason("low-confidence satisfaction downgraded: $reason")
            } else {
                reason
            },
            cognitiveStateDigest = cognitiveStateDigest,
            executionContextDigest = executionContextDigest,
            backendId = backendId,
            modelId = modelId
        )
    }

    fun prompt(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan,
        state: IntegratedCognitiveStatePacket,
        maxChars: Int = 6_000
    ): String {
        require(terminalPlan.complete)
        require(terminalPlan.steps.all { it.status == PlanStepStatus.EXECUTED })
        require(maxChars in 2_000..12_000)

        val stepEvidence = terminalPlan.steps.joinToString("\n") { step ->
            val outcome = step.outcome
            buildString {
                append("step.").append(step.index)
                append(".capability=").append(sanitize(step.capability.value, 96))
                append(";status=").append(step.status.name)
                append(";sideEffect=").append(step.boundSideEffect?.name ?: "UNKNOWN")
                append(";output=").append(sanitize(outcome?.output.orEmpty(), 512))
            }
        }
        val stateEvidence = state.canonicalLines()
            .joinToString("\n")
            .take(2_800)

        return buildString {
            appendLine("You are AMPER's independent goal-satisfaction verifier.")
            appendLine("Use only the supplied executed-step outcomes and current cognitive evidence.")
            appendLine("Do not assume a real-world result that is not evidenced.")
            appendLine("Return SATISFIED only when the original objective is evidenced as achieved.")
            appendLine("If evidence is incomplete, ambiguous, stale, or only shows actions attempted, return FOLLOW_UP_REQUIRED.")
            appendLine("SATISFIED confidence must be at least ${MIN_SATISFIED_CONFIDENCE}.")
            appendLine()
            appendLine("goal.id=" + sanitize(checkpoint.sourceGoalId, 256))
            appendLine("goal.objective=" + sanitize(checkpoint.objective, 1024))
            appendLine("plan.id=" + sanitize(terminalPlan.id.value, 256))
            appendLine("executed.steps:")
            appendLine(stepEvidence)
            appendLine("current.cognitive.evidence:")
            appendLine(stateEvidence)
            appendLine()
            appendLine("Return exactly:")
            appendLine(OPEN)
            appendLine("verdict=SATISFIED|FOLLOW_UP_REQUIRED")
            appendLine("confidence=0.00")
            appendLine("reason=single concise evidence-based reason")
            append(CLOSE)
        }.take(maxChars)
    }

    private fun sanitizeReason(value: String): String = sanitize(value, 256)

    private fun sanitize(value: String, maxChars: Int): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('<', '[')
        .replace('>', ']')
        .trim()
        .take(maxChars)
        .ifBlank { "no evidence supplied" }
}

class InferenceGoalSatisfactionVerifier(
    private val inference: CognitiveInferencePort,
    private val stateSource: IntegratedCognitiveStateSource,
    private val allowedCapabilities: Set<CapabilityId>,
    private val descriptors: () -> List<ToolDescriptor>,
    private val maxPromptChars: Int = 6_000,
    private val maxOutputTokens: Int = 160
) : GoalSatisfactionVerifier {
    init {
        require(allowedCapabilities.isNotEmpty())
        require(maxPromptChars in 2_000..12_000)
        require(maxOutputTokens in 64..256)
    }

    override fun verify(
        checkpoint: PersistentGoalExecutiveCheckpoint,
        terminalPlan: SovereignPlan
    ): Result<GoalSatisfactionAssessment> = runCatching {
        require(terminalPlan.complete)
        require(terminalPlan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
            "goal satisfaction may only verify an all-executed terminal plan"
        }
        val liveDescriptors = descriptors()
            .filter { it.capability in allowedCapabilities }
            .distinctBy { it.id }
            .sortedBy { it.capability.value }
        val state = stateSource.capture(
            query = checkpoint.objective,
            allowedCapabilities = allowedCapabilities,
            descriptors = liveDescriptors
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = GoalSatisfactionProtocol.prompt(
                    checkpoint = checkpoint,
                    terminalPlan = terminalPlan,
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

        GoalSatisfactionProtocol.parse(
            planId = terminalPlan.id,
            output = response.text,
            cognitiveStateDigest = state.canonicalDigest,
            executionContextDigest = CognitiveContinuityPolicy.executionContextDigest(state),
            backendId = response.backendId,
            modelId = response.modelId
        ).getOrThrow()
    }
}
