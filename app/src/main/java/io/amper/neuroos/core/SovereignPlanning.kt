package io.amper.neuroos.core

import java.util.UUID

@JvmInline
value class PlanId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class PlanStepStatus {
    PLANNED,
    REQUIRES_CONFIRMATION,
    EXECUTED,
    DENIED,
    FAILED,
    MALFORMED,
    UNAVAILABLE,
    REJECTED
}

data class SovereignPlanStep(
    val index: Int,
    val requestId: ActionRequestId,
    val capability: CapabilityId,
    val reason: String,
    val input: String,
    val status: PlanStepStatus = PlanStepStatus.PLANNED,
    val outcome: ActionOutcome? = null,
    val boundToolId: ToolId? = null,
    val boundSideEffect: ToolSideEffect? = null
) {
    init {
        require(index > 0)
        require(reason.isNotBlank())
        require((boundToolId == null) == (boundSideEffect == null)) {
            "plan-time tool binding must include both tool id and side-effect class"
        }
    }

    fun proposal(): ActionProposal = ActionProposal(
        requestId = requestId,
        capability = capability,
        reason = reason,
        input = input
    )
}

data class SovereignPlan(
    val id: PlanId = PlanId(UUID.randomUUID().toString()),
    val conversationId: ConversationId,
    val goal: String,
    val steps: List<SovereignPlanStep>,
    val planningBackendId: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val planningModelId: ModelId? = null,
    val planningSelectedCapabilities: Set<CapabilityId> = emptySet(),
    val parentPlanId: PlanId? = null,
    val recoveryDepth: Int = 0,
    val deliberationCandidateCount: Int = 1,
    val deliberationScore: Double? = null,
    val counterfactualViability: Double? = null,
    val counterfactualConfidence: Double? = null,
    val planningCognitiveStateDigest: String? = null,
    val planningExecutionContextDigest: String? = null
) {
    init {
        require(goal.isNotBlank())
        require(steps.isNotEmpty())
        require(steps.size <= TitanPlanProtocol.MAX_STEPS)
        require(steps.map { it.index } == (1..steps.size).toList())
        require(planningBackendId.isNotBlank())
        require(recoveryDepth >= 0)
        require((parentPlanId == null) == (recoveryDepth == 0)) {
            "recovery lineage requires both parent plan id and positive recovery depth"
        }
        require(deliberationCandidateCount in 1..TitanDeliberationProtocol.MAX_CANDIDATES)
        require(deliberationScore == null || deliberationScore in 0.0..1.0)
        require(counterfactualViability == null || counterfactualViability in 0.0..1.0)
        require(counterfactualConfidence == null || counterfactualConfidence in 0.0..1.0)
        require((planningCognitiveStateDigest == null) == (planningExecutionContextDigest == null)) {
            "cognitive continuity binding requires both cognitive and execution-context digests"
        }
        planningCognitiveStateDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "invalid planning cognitive-state digest" }
        }
        planningExecutionContextDigest?.let {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "invalid planning execution-context digest" }
        }
    }

    val complete: Boolean
        get() = steps.none { it.status == PlanStepStatus.PLANNED || it.status == PlanStepStatus.REQUIRES_CONFIRMATION }
}

sealed interface PlanAdvanceResult {
    data class StepProcessed(
        val plan: SovereignPlan,
        val step: SovereignPlanStep,
        val outcome: ActionOutcome
    ) : PlanAdvanceResult

    data class PendingApproval(
        val plan: SovereignPlan,
        val step: SovereignPlanStep,
        val proposal: ActionProposal
    ) : PlanAdvanceResult

    data class ContextChanged(
        val plan: SovereignPlan,
        val assessment: CognitiveContinuityAssessment
    ) : PlanAdvanceResult {
        init { require(assessment.blocksExecution) }
    }

    data class Complete(val plan: SovereignPlan) : PlanAdvanceResult
}

/**
 * Strict planning protocol. A valid plan is data only: parsing never executes a tool.
 * Every step is pre-validated against the same typed ToolDescriptor manifests used by
 * the sovereign action loop.
 */
object TitanPlanProtocol {
    const val MAX_STEPS = 4
    private const val OPEN = "<AMPER_PLAN_V1>"
    private const val CLOSE = "</AMPER_PLAN_V1>"
    private val FIELD = Regex("step\\.(\\d+)\\.(capability|reason|input)")

    fun parse(
        modelOutput: String,
        allowedCapabilities: Collection<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): Result<List<SovereignPlanStep>> = runCatching {
        val text = modelOutput.trim()
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "plan envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "plan must contain at least one step" }

        val fields = linkedMapOf<String, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "plan field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(FIELD.matches(key)) { "unknown plan field: $key" }
                require(fields.put(key, value) == null) { "duplicate plan field: $key" }
            }

        val indices = fields.keys
            .mapNotNull { FIELD.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
        require(indices.isNotEmpty()) { "plan must contain at least one step" }
        require(indices.size <= MAX_STEPS) { "plan exceeds $MAX_STEPS steps" }
        require(indices == (1..indices.size).toList()) { "plan step numbers must be contiguous from 1" }

        val allowed = allowedCapabilities.map { it.value }.toSet()
        val descriptorByCapability = descriptors
            .filter { it.capability.value in allowed }
            .associateBy { it.capability.value }

        indices.map { index ->
            val expected = listOf(
                "step.$index.capability",
                "step.$index.reason",
                "step.$index.input"
            )
            require(expected.all(fields::containsKey)) { "step $index requires capability, reason and input" }
            val capability = CapabilityId(fields.getValue("step.$index.capability"))
            require(capability.value in allowed) { "step $index capability is not whitelisted" }
            val descriptor = descriptorByCapability[capability.value]
                ?: error("step $index has no advertised tool descriptor")
            val proposal = ActionProposal(
                capability = capability,
                reason = fields.getValue("step.$index.reason"),
                input = fields.getValue("step.$index.input")
            )
            validateInput(descriptor, proposal.input)?.let { detail ->
                error("step $index $detail")
            }
            SovereignPlanStep(
                index = index,
                requestId = proposal.requestId,
                capability = proposal.capability,
                reason = proposal.reason,
                input = proposal.input,
                boundToolId = descriptor.id,
                boundSideEffect = descriptor.sideEffect
            )
        }
    }

    fun instructions(
        capabilities: Collection<CapabilityId>,
        descriptors: Collection<ToolDescriptor>
    ): String = buildString {
        appendLine("Create a bounded execution plan only. Do not claim any tool has executed.")
        appendLine("Output only one $OPEN envelope and nothing else.")
        appendLine("Use between 1 and $MAX_STEPS contiguous steps.")
        appendLine(OPEN)
        appendLine("step.1.capability=<allowed capability>")
        appendLine("step.1.reason=<short user-centered reason>")
        appendLine("step.1.input=<single-line input matching the tool contract>")
        appendLine("step.2.capability=<optional second capability>")
        appendLine("step.2.reason=<optional second reason>")
        appendLine("step.2.input=<optional second input>")
        appendLine(CLOSE)
        if (capabilities.isNotEmpty()) {
            appendLine("Allowed capability names: ${capabilities.joinToString(",") { it.value }}")
        }
        val allowed = capabilities.map { it.value }.toSet()
        descriptors
            .asSequence()
            .filter { it.capability.value in allowed }
            .sortedBy { it.capability.value }
            .take(16)
            .forEach { descriptor ->
                val contract = descriptor.inputContract
                val values = contract.acceptedValues
                    .sorted()
                    .joinToString("|")
                    .ifBlank { "free-text" }
                appendLine(
                    "TOOL ${descriptor.capability.value} side_effect=${descriptor.sideEffect} " +
                        "input_values=$values max_input_chars=${contract.maxLength} " +
                        "description=${sanitize(contract.description)}"
                )
            }
    }.trim()

    private fun validateInput(descriptor: ToolDescriptor, input: String): String? {
        val contract = descriptor.inputContract
        if (input.length > contract.maxLength) {
            return "input exceeds ${contract.maxLength} characters for ${descriptor.capability.value}"
        }
        if (contract.acceptedValues.isNotEmpty()) {
            val normalized = input.trim().lowercase()
            val accepted = contract.acceptedValues.map { it.trim().lowercase() }.toSet()
            if (normalized !in accepted) {
                return "input is outside declared contract for ${descriptor.capability.value}"
            }
        }
        return null
    }

    private fun sanitize(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('<', '[')
        .replace('>', ']')
        .take(256)
}

/**
 * Planning is separated from execution. create() performs one inference and zero tool
 * calls. advance() processes at most one plan step. A side-effect step remains blocked
 * until approve() is called explicitly, and approval still flows through SovereignActionLoop.
 * Persisted/recovered plans are revalidated against the live registry before execution.
 * Planning-capable models are preferred, with reasoning kept as the mandatory fallback.
 * Phase 139 also carries the conversation-scoped explicit user model preference into planning as
 * a soft routing hint; it cannot bypass capability, backend, feedback or resource admission.
 * Phase 140 applies the same persisted conversation inference profile to planning requests and
 * prompt construction, so context budget, output budget, temperature and session-ranking policy
 * are consistent with assistant inference. Phase 176 may add bounded historical strategy guidance
 * to the planning protocol, but that evidence is advisory and cannot alter authority or execution.
 * Phase 177 adaptively reweights that guidance from governed outcome streaks only; the planner still
 * cannot bypass current tool contracts, exact bindings, AuthorityGate, or explicit confirmation.
 * Phase 178 performs ephemeral goal-conditioned retrieval over that already-governed guidance.
 * Goal terms are not persisted by strategy learning and can only re-rank currently valid evidence.
 * Phase 179 permits one explicitly requested recovery-planning inference after repeated recoverable
 * failures. Denial/rejection never trigger recovery, replacement strategy must differ structurally,
 * and the recovered plan remains subject to the same binding, authority and confirmation gates.
 * Phase 180 upgrades planning to one-inference hierarchical deliberation: up to three independently
 * validated candidate plans are scored deterministically before one plan is materialized. Candidate
 * evaluation performs zero tool calls and cannot change capability admission or execution authority.
 * Phase 182 adds a causal counterfactual world projection over those validated candidates. It
 * separates execution quality from environment/protocol fragility and keeps authority history
 * diagnostic-only, never as an optimization signal for bypassing governance.
 * Phase 185 inserts an independent bounded reflective critic after candidate selection and before
 * SovereignPlan materialization. Production wiring gives the critic only an inference port: it has
 * no ToolFabric/AuthorityGate handle, may perform at most one critique/revision pass, and any revised
 * steps are re-parsed through TitanPlanProtocol so exact live binding and side-effect classification
 * remain authoritative.
 * Phase256-260 derives one bounded metacognitive control directive from the integrated cognitive
 * state and freezes it across planner plus critic. It may reduce temperature, vary bounded output
 * budget and request deeper deliberation under uncertainty, but it never widens user profile limits,
 * changes live tool contracts, grants authority, approves side effects or creates an inference loop.
 */
class SovereignPlanCoordinator(
    private val runtime: AmperRuntime,
    private val inference: CognitiveInferencePort,
    private val actions: SovereignActionLoop,
    private val advertisedCapabilities: Set<CapabilityId>,
    private val maxPromptChars: Int = 9000,
    private val maxOutputTokens: Int = 384,
    private val temperature: Double = 0.7,
    private val criticInference: CognitiveInferencePort? = null
) {
    private val recoveryGuard = SovereignPlanRecoveryGuard(actions, advertisedCapabilities)
    private val baselineCapabilities = setOf(TitanCapabilities.REASONING)

    init {
        require(advertisedCapabilities.isNotEmpty())
        require(maxPromptChars in ConversationInferenceProfile.MIN_PROMPT_CHARS..ConversationInferenceProfile.MAX_PROMPT_CHARS)
        require(maxOutputTokens > 0)
        require(temperature in 0.0..2.0)
    }

    /**
     * Run one bounded zero-tool autonomous-learning practice.
     *
     * The generated plan-shaped output is parsed only as practice evidence. It is never passed to
     * [SovereignActionLoop], so PASS cannot become execution competence or authority.
     */
    fun practiceOne(): Result<AutonomousLearningCycleResult> = runCatching {
        val descriptors = routedDescriptors()
        val tasks = runtime.autonomousLearning.curriculum(
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors,
            limit = 1
        )
        val task = tasks.firstOrNull()
            ?: return@runCatching AutonomousLearningCycleResult.NoPractice(
                needs = runtime.autonomousLearning.diagnose(
                    allowedCapabilities = advertisedCapabilities,
                    descriptors = descriptors,
                    limit = MemoryBackedAutonomousLearningModel.MAX_DIAGNOSED_NEEDS
                )
            )
        val descriptor = requireNotNull(
            descriptors.singleOrNull { it.capability == task.capability }
        ) { "autonomous practice capability lost its live descriptor" }

        val prompt = AutonomousLearningPracticePrompt.build(
            task = task,
            descriptor = descriptor,
            charBudget = minOf(maxPromptChars, 4096)
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = minOf(maxOutputTokens, 256),
                temperature = 0.2,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities)
            )
        ).getOrThrow()
        val evidence = runtime.autonomousLearning.assess(
            task = task,
            modelOutput = response.text,
            descriptors = descriptors
        )
        AutonomousLearningCycleResult.Assessed(
            task = task,
            evidence = evidence,
            backendId = response.backendId,
            modelId = response.modelId
        )
    }

    fun goalSatisfactionVerifier(
        verifierInference: CognitiveInferencePort = criticInference ?: inference
    ): GoalSatisfactionVerifier = InferenceGoalSatisfactionVerifier(
        inference = verifierInference,
        stateSource = runtime.integratedCognition,
        allowedCapabilities = advertisedCapabilities,
        descriptors = ::routedDescriptors,
        maxPromptChars = minOf(maxPromptChars, 6_000),
        maxOutputTokens = minOf(maxOutputTokens, 160)
    )

    fun autonomousExecutive(
        evolution: CognitiveExecutiveEvolutionPort? = null,
        observation: CognitiveExecutiveObservationPort? = null
    ): AutonomousCognitiveExecutive = AutonomousCognitiveExecutive(
        stateSource = runtime.integratedCognition,
        allowedCapabilities = advertisedCapabilities,
        descriptors = ::routedDescriptors,
        createPlan = this::create,
        practiceOne = this::practiceOne,
        evolution = evolution,
        observation = observation,
        perceptionBus = runtime.perception
    )

    fun persistentGoalExecutive(
        evolution: CognitiveExecutiveEvolutionPort? = null,
        observation: CognitiveExecutiveObservationPort? = null
    ): PersistentGoalExecutiveCoordinator = PersistentGoalExecutiveCoordinator(
        context = runtime.context,
        executive = autonomousExecutive(evolution, observation),
        store = runtime.persistentGoalExecutiveStore,
        plans = runtime.plans,
        portfolio = runtime.goalPortfolio
    )

    fun create(
        conversationId: ConversationId,
        userGoal: String
    ): Result<SovereignPlan> = runCatching {
        require(userGoal.isNotBlank())
        val boundInferenceProfile = runtime.inferenceProfiles.bind(
            conversationId = conversationId,
            fallbackMaxOutputTokens = maxOutputTokens,
            fallbackTemperature = temperature,
            fallbackMaxPromptChars = maxPromptChars
        )
        runtime.tick(userGoal)
        val descriptors = routedDescriptors()
        val cognitiveState = runtime.integratedCognition.capture(
            query = userGoal,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        )
        val continuityBinding = CognitiveContinuityPolicy.bind(cognitiveState)
        val metacognitiveControl = MetacognitiveInferenceControlPolicy.derive(
            state = cognitiveState,
            profile = boundInferenceProfile
        )
        val prompt = buildPlanningPrompt(
            conversationId = conversationId,
            userGoal = userGoal,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            promptBudgetChars = boundInferenceProfile.maxPromptChars
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = metacognitiveControl.planningMaxOutputTokens,
                temperature = metacognitiveControl.planningTemperature,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities),
                userPreferredModelId = runtime.conversations.preferredModelId(conversationId),
                sessionRoutingPreference = boundInferenceProfile.sessionRoutingPreference
            )
        ).getOrThrow()
        val candidates = TitanDeliberationProtocol.parse(
            modelOutput = response.text,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        ).getOrThrow()
        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = candidates,
            strategies = runtime.strategies,
            allowedCapabilities = advertisedCapabilities
        )
        val critique = critiqueSelection(
            conversationId = conversationId,
            userGoal = userGoal,
            selected = selection.selected,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            profile = boundInferenceProfile
        )
        val finalEvaluation = postCriticEvaluation(selection.selected, critique)
        val plan = SovereignPlan(
            conversationId = conversationId,
            goal = userGoal,
            steps = finalEvaluation.candidate.steps,
            planningBackendId = response.backendId,
            planningModelId = response.modelId,
            planningSelectedCapabilities = response.selectedCapabilities,
            deliberationCandidateCount = selection.evaluated.size,
            deliberationScore = finalEvaluation.totalScore,
            counterfactualViability = finalEvaluation.counterfactualViability,
            counterfactualConfidence = finalEvaluation.counterfactualConfidence,
            planningCognitiveStateDigest = continuityBinding.cognitiveStateDigest,
            planningExecutionContextDigest = continuityBinding.executionContextDigest
        )
        runCatching {
            runtime.skills.begin(
                plan = plan,
                worldStates = cognitiveState.context.structuredWorldStates
            )
        }
        runtime.conversations.commitAssistant(
            conversationId = conversationId,
            userPrompt = userGoal,
            response = renderSummary(plan),
            backendId = response.backendId,
            confidence = 0.8,
            modelId = response.modelId,
            selectedCapabilities = response.selectedCapabilities
        )
        plan
    }

    /**
     * Create at most one alternative plan after repeated recoverable strategy failure.
     * This performs one planning inference and zero tool executions.
     */
    fun recover(plan: SovereignPlan): Result<SovereignPlan> = runCatching {
        require(plan.complete) { "recovery requires a terminal plan" }
        observeCompletedStrategy(plan)

        val signature = StrategySignature.from(plan)
        val decision = GovernedStrategyRecovery.assess(
            plan = plan,
            evidence = runtime.strategies.snapshot(signature),
            allowedCapabilities = advertisedCapabilities
        )
        require(decision.eligible) {
            "strategy recovery blocked: ${decision.blockReason}"
        }

        val boundInferenceProfile = runtime.inferenceProfiles.bind(
            conversationId = plan.conversationId,
            fallbackMaxOutputTokens = maxOutputTokens,
            fallbackTemperature = temperature,
            fallbackMaxPromptChars = maxPromptChars
        )
        val descriptors = routedDescriptors()
        val cognitiveState = runtime.integratedCognition.capture(
            query = plan.goal,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        )
        val continuityBinding = CognitiveContinuityPolicy.bind(cognitiveState)
        val metacognitiveControl = MetacognitiveInferenceControlPolicy.derive(
            state = cognitiveState,
            profile = boundInferenceProfile
        )
        val prompt = buildPlanningPrompt(
            conversationId = plan.conversationId,
            userGoal = plan.goal,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            promptBudgetChars = boundInferenceProfile.maxPromptChars,
            recoveryDecision = decision
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = metacognitiveControl.planningMaxOutputTokens,
                temperature = metacognitiveControl.planningTemperature,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities),
                userPreferredModelId = runtime.conversations.preferredModelId(plan.conversationId),
                sessionRoutingPreference = boundInferenceProfile.sessionRoutingPreference
            )
        ).getOrThrow()
        val candidates = TitanDeliberationProtocol.parse(
            modelOutput = response.text,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        ).getOrThrow()
        val viableCandidates = candidates.filterNot { candidate ->
            candidate.signature == decision.failedSignature
        }
        require(viableCandidates.isNotEmpty()) {
            "recovery plan repeated the failed capability sequence"
        }
        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = viableCandidates,
            strategies = runtime.strategies,
            allowedCapabilities = advertisedCapabilities
        )
        val critique = critiqueSelection(
            conversationId = plan.conversationId,
            userGoal = plan.goal,
            selected = selection.selected,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            profile = boundInferenceProfile
        )
        val finalEvaluation = postCriticEvaluation(selection.selected, critique)
        val steps = finalEvaluation.candidate.steps
        GovernedStrategyRecovery.validateReplacement(plan, steps)

        SovereignPlan(
            conversationId = plan.conversationId,
            goal = plan.goal,
            steps = steps,
            planningBackendId = response.backendId,
            planningModelId = response.modelId,
            planningSelectedCapabilities = response.selectedCapabilities,
            parentPlanId = plan.id,
            recoveryDepth = plan.recoveryDepth + 1,
            deliberationCandidateCount = selection.evaluated.size,
            deliberationScore = finalEvaluation.totalScore,
            counterfactualViability = finalEvaluation.counterfactualViability,
            counterfactualConfidence = finalEvaluation.counterfactualConfidence,
            planningCognitiveStateDigest = continuityBinding.cognitiveStateDigest,
            planningExecutionContextDigest = continuityBinding.executionContextDigest
        ).also { replacement ->
            runCatching {
                runtime.skills.begin(
                    plan = replacement,
                    worldStates = cognitiveState.context.structuredWorldStates
                )
            }
            runtime.conversations.commitAssistant(
                conversationId = plan.conversationId,
                userPrompt = plan.goal,
                response = renderRecoverySummary(plan, replacement),
                backendId = response.backendId,
                confidence = 0.8,
                modelId = response.modelId,
                selectedCapabilities = response.selectedCapabilities
            )
        }
    }

    /**
     * Replan a still-active plan only after its execution-relevant cognitive context has changed.
     *
     * Phase271-275 keeps replanning separate from execution: this method performs exactly one
     * planning inference plus the already-bounded optional critic pass and invokes zero tools.
     * The replacement is a child plan with a fresh continuity binding and fresh request ids.
     */
    fun refreshContext(plan: SovereignPlan): Result<SovereignPlan> = runCatching {
        require(!plan.complete) { "context refresh requires a nonterminal plan" }
        val expectedContextDigest = requireNotNull(plan.planningExecutionContextDigest) {
            "legacy unbound plans require explicit manual replanning"
        }
        require(plan.recoveryDepth < MAX_CONTEXT_REPLANS) {
            "context refresh depth limit reached"
        }

        val boundInferenceProfile = runtime.inferenceProfiles.bind(
            conversationId = plan.conversationId,
            fallbackMaxOutputTokens = maxOutputTokens,
            fallbackTemperature = temperature,
            fallbackMaxPromptChars = maxPromptChars
        )
        val descriptors = routedDescriptors()
        val cognitiveState = runtime.integratedCognition.capture(
            query = plan.goal,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        )
        val continuity = CognitiveContinuityPolicy.assess(
            expectedExecutionContextDigest = expectedContextDigest,
            current = cognitiveState
        )
        require(continuity.blocksExecution) {
            "context refresh requires a changed execution context"
        }

        val continuityBinding = CognitiveContinuityPolicy.bind(cognitiveState)
        val metacognitiveControl = MetacognitiveInferenceControlPolicy.derive(
            state = cognitiveState,
            profile = boundInferenceProfile
        )
        val prompt = buildPlanningPrompt(
            conversationId = plan.conversationId,
            userGoal = plan.goal,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            promptBudgetChars = boundInferenceProfile.maxPromptChars,
            contextRefreshPlan = plan,
            contextRefreshAssessment = continuity
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = metacognitiveControl.planningMaxOutputTokens,
                temperature = metacognitiveControl.planningTemperature,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities),
                userPreferredModelId = runtime.conversations.preferredModelId(plan.conversationId),
                sessionRoutingPreference = boundInferenceProfile.sessionRoutingPreference
            )
        ).getOrThrow()
        val candidates = TitanDeliberationProtocol.parse(
            modelOutput = response.text,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        ).getOrThrow()
        val selection = EvidenceGroundedDeliberationEvaluator.select(
            candidates = candidates,
            strategies = runtime.strategies,
            allowedCapabilities = advertisedCapabilities
        )
        val critique = critiqueSelection(
            conversationId = plan.conversationId,
            userGoal = plan.goal,
            selected = selection.selected,
            descriptors = descriptors,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            profile = boundInferenceProfile
        )
        val finalEvaluation = postCriticEvaluation(selection.selected, critique)

        SovereignPlan(
            conversationId = plan.conversationId,
            goal = plan.goal,
            steps = finalEvaluation.candidate.steps,
            planningBackendId = response.backendId,
            planningModelId = response.modelId,
            planningSelectedCapabilities = response.selectedCapabilities,
            parentPlanId = plan.id,
            recoveryDepth = plan.recoveryDepth + 1,
            deliberationCandidateCount = selection.evaluated.size,
            deliberationScore = finalEvaluation.totalScore,
            counterfactualViability = finalEvaluation.counterfactualViability,
            counterfactualConfidence = finalEvaluation.counterfactualConfidence,
            planningCognitiveStateDigest = continuityBinding.cognitiveStateDigest,
            planningExecutionContextDigest = continuityBinding.executionContextDigest
        ).also { replacement ->
            require(replacement.id != plan.id)
            replacement.steps.forEach { replacementStep ->
                require(plan.steps.none { it.requestId == replacementStep.requestId }) {
                    "context refresh must issue fresh action request ids"
                }
            }
            runCatching {
                runtime.skills.begin(
                    plan = replacement,
                    worldStates = cognitiveState.context.structuredWorldStates
                )
            }
            runtime.conversations.commitAssistant(
                conversationId = plan.conversationId,
                userPrompt = plan.goal,
                response = renderContextRefreshSummary(plan, replacement),
                backendId = response.backendId,
                confidence = 0.8,
                modelId = response.modelId,
                selectedCapabilities = response.selectedCapabilities
            )
        }
    }

    private fun critiqueSelection(
        conversationId: ConversationId,
        userGoal: String,
        selected: DeliberationEvaluation,
        descriptors: List<ToolDescriptor>,
        cognitiveState: IntegratedCognitiveStatePacket,
        metacognitiveControl: MetacognitiveControlDirective,
        profile: BoundConversationInferenceProfile
    ): ReflectivePlanCritique {
        val structural = ReflectivePlanCriticGate.structuralVerify(
            evaluation = selected,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        )
        val critic = criticInference ?: return structural

        val prompt = ReflectivePlanCriticPrompt.build(
            userGoal = userGoal,
            evaluation = selected,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors,
            charBudget = profile.maxPromptChars,
            integratedCognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl
        )
        val response = critic.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = metacognitiveControl.criticMaxOutputTokens,
                temperature = metacognitiveControl.criticTemperature,
                sessionRoutingPreference = profile.sessionRoutingPreference
            )
        ).getOrThrow()

        return ReflectivePlanCriticProtocol.parse(
            modelOutput = response.text,
            allowedCapabilities = advertisedCapabilities,
            descriptors = descriptors
        ).getOrThrow()
    }

    private fun postCriticEvaluation(
        selected: DeliberationEvaluation,
        critique: ReflectivePlanCritique
    ): DeliberationEvaluation {
        if (!critique.revised) return selected
        val revisedCandidate = DeliberationCandidate(
            index = selected.candidate.index,
            steps = requireNotNull(critique.revisedSteps)
        )
        return EvidenceGroundedDeliberationEvaluator.select(
            candidates = listOf(revisedCandidate),
            strategies = runtime.strategies,
            allowedCapabilities = advertisedCapabilities
        ).selected
    }

    /** Process at most one step. No loop is permitted inside this method. */
    fun advance(plan: SovereignPlan): Result<PlanAdvanceResult> = runCatching {
        recoveryGuard.verifyAdvance(plan).getOrThrow()
        val next = plan.steps.firstOrNull {
            it.status == PlanStepStatus.PLANNED || it.status == PlanStepStatus.REQUIRES_CONFIRMATION
        } ?: return@runCatching PlanAdvanceResult.Complete(plan).also {
            observeCompletedStrategy(plan)
        }

        val continuity = assessContinuity(plan)
        if (continuity?.blocksExecution == true) {
            return@runCatching PlanAdvanceResult.ContextChanged(plan, continuity)
        }

        if (next.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            return@runCatching PlanAdvanceResult.PendingApproval(plan, next, next.proposal())
        }

        val outcome = actions.evaluateBound(
            proposal = next.proposal(),
            expectedToolId = requireNotNull(next.boundToolId) { "planned step has no bound tool id" },
            expectedSideEffect = requireNotNull(next.boundSideEffect) { "planned step has no bound side-effect class" }
        )
        val updatedStep = next.copy(
            status = outcome.toPlanStatus(),
            outcome = outcome
        )
        val updatedPlan = plan.replace(updatedStep)
        if (outcome.status == ActionStatus.REQUIRES_CONFIRMATION) {
            PlanAdvanceResult.PendingApproval(updatedPlan, updatedStep, updatedStep.proposal())
        } else {
            observeCompletedStrategy(updatedPlan)
            PlanAdvanceResult.StepProcessed(updatedPlan, updatedStep, outcome)
        }
    }

    /** Revalidate cognitive context and the approval checkpoint before a durable claim is written. */
    fun approvalBinding(plan: SovereignPlan, stepIndex: Int): Result<ApprovedToolBinding> =
        runCatching {
            val continuity = assessContinuity(plan)
            require(continuity?.blocksExecution != true) {
                "approval blocked because cognitive execution context changed"
            }
            recoveryGuard.approvalBinding(plan, stepIndex).getOrThrow()
        }

    /** Execute one already-preflighted approval against its exact binding. */
    fun approveBound(
        plan: SovereignPlan,
        stepIndex: Int,
        binding: ApprovedToolBinding
    ): Result<PlanAdvanceResult.StepProcessed> = runCatching {
        val continuity = assessContinuity(plan)
        require(continuity?.blocksExecution != true) {
            "approval blocked because cognitive execution context changed"
        }
        val step = plan.steps.single { it.index == stepIndex }
        require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            "plan step $stepIndex is not awaiting approval"
        }
        val outcome = actions.approveBound(
            proposal = step.proposal(),
            expectedToolId = binding.toolId,
            expectedSideEffect = binding.sideEffect
        )
        val updatedStep = step.copy(status = outcome.toPlanStatus(), outcome = outcome)
        val updatedPlan = plan.replace(updatedStep)
        observeCompletedStrategy(updatedPlan)
        PlanAdvanceResult.StepProcessed(updatedPlan, updatedStep, outcome)
    }

    /** Execute exactly one previously blocked side-effect step after explicit approval. */
    fun approve(plan: SovereignPlan, stepIndex: Int): Result<PlanAdvanceResult.StepProcessed> =
        approvalBinding(plan, stepIndex).mapCatching { binding ->
            approveBound(plan, stepIndex, binding).getOrThrow()
        }

    /** Rejecting a pending step performs no tool invocation. */
    fun reject(plan: SovereignPlan, stepIndex: Int): SovereignPlan {
        val step = plan.steps.single { it.index == stepIndex }
        require(step.status == PlanStepStatus.REQUIRES_CONFIRMATION) {
            "plan step $stepIndex is not awaiting approval"
        }
        return plan.replace(step.copy(status = PlanStepStatus.REJECTED)).also {
            observeCompletedStrategy(it)
        }
    }

    private fun observeCompletedStrategy(plan: SovereignPlan) {
        if (!plan.complete) return
        // Procedural/skill learning is observational. It cannot change plan status, execute a tool,
        // grant authority, or turn a denied/failed action into success.
        runCatching { runtime.strategies.observe(plan) }
        val worldStates = runtime.predictiveWorld.queryStates(plan.goal, 8)
        val learnedSkill = runCatching {
            runtime.skills.observe(
                plan = plan,
                worldStates = worldStates
            )
        }.getOrNull()
        runCatching {
            runtime.generalization.observe(
                plan = plan,
                skill = learnedSkill
            )
        }
    }

    private fun buildPlanningPrompt(
        conversationId: ConversationId,
        userGoal: String,
        descriptors: List<ToolDescriptor>,
        cognitiveState: IntegratedCognitiveStatePacket,
        metacognitiveControl: MetacognitiveControlDirective,
        promptBudgetChars: Int,
        recoveryDecision: StrategyRecoveryDecision? = null,
        contextRefreshPlan: SovereignPlan? = null,
        contextRefreshAssessment: CognitiveContinuityAssessment? = null
    ): String {
        require(promptBudgetChars in ConversationInferenceProfile.MIN_PROMPT_CHARS..ConversationInferenceProfile.MAX_PROMPT_CHARS)
        val capabilities = advertisedCapabilities
            .sortedBy { it.value }
            .take(8)
        val currentSection = buildString {
            appendLine("<CURRENT_GOAL_FINAL>")
            appendLine(SovereignPromptData.bounded(userGoal, 1024))
            appendLine("</CURRENT_GOAL_FINAL>")
        }
        val recoverySection = recoveryDecision?.let { decision ->
            "\n" + GovernedStrategyRecovery.renderConstraint(decision) + "\n"
        }.orEmpty()
        require((contextRefreshPlan == null) == (contextRefreshAssessment == null)) {
            "context refresh planning requires both plan and assessment"
        }
        val contextRefreshSection = if (
            contextRefreshPlan != null && contextRefreshAssessment != null
        ) {
            "\n" + renderContextRefreshConstraint(
                plan = contextRefreshPlan,
                assessment = contextRefreshAssessment
            ) + "\n"
        } else {
            ""
        }
        val wrapperChars = "\n<PLANNING_PROTOCOL>\n".length + "</PLANNING_PROTOCOL>\n".length
        val protocolBudget = promptBudgetChars -
            MIN_GROUNDED_CONTEXT_CHARS -
            currentSection.length -
            recoverySection.length -
            contextRefreshSection.length -
            wrapperChars
        require(protocolBudget > 0) {
            "conversation prompt budget is too small for planning protocol and recovery constraints"
        }
        val protocol = boundedPlanningProtocol(
            capabilities = capabilities,
            descriptors = descriptors,
            userGoal = userGoal,
            cognitiveState = cognitiveState,
            metacognitiveControl = metacognitiveControl,
            charBudget = protocolBudget
        )
        val tail = buildString {
            appendLine()
            appendLine("<PLANNING_PROTOCOL>")
            appendLine(protocol)
            appendLine("</PLANNING_PROTOCOL>")
            append(recoverySection)
            append(contextRefreshSection)
            append(currentSection)
        }
        val conversationBudget = promptBudgetChars - tail.length
        require(conversationBudget >= MIN_GROUNDED_CONTEXT_CHARS) {
            "conversation prompt budget cannot preserve grounded context and planning protocol"
        }
        val grounded = runtime.conversations.prepare(
            conversationId = conversationId,
            userPrompt = userGoal,
            charBudget = conversationBudget
        )
        return grounded.take(conversationBudget) + tail
    }

    private fun boundedPlanningProtocol(
        capabilities: List<CapabilityId>,
        descriptors: List<ToolDescriptor>,
        userGoal: String,
        cognitiveState: IntegratedCognitiveStatePacket,
        metacognitiveControl: MetacognitiveControlDirective,
        charBudget: Int
    ): String {
        require(metacognitiveControl.cognitiveStateDigest == cognitiveState.canonicalDigest) {
            "metacognitive control must bind the exact cognitive state"
        }
        val control = MetacognitiveControlRenderer.render(metacognitiveControl)
        val base = TitanDeliberationProtocol.instructions(capabilities, emptyList())
        require(
            base.length + 2 + control.length + 2 + COGNITIVE_STATE_MIN_CHARS <= charBudget
        ) {
            "conversation prompt budget cannot preserve planning protocol, metacognition and cognitive state"
        }
        val allowed = capabilities.toSet()
        val selected = mutableListOf<ToolDescriptor>()
        descriptors
            .asSequence()
            .filter { it.capability in allowed }
            .sortedBy { it.capability.value }
            .forEach { descriptor ->
                val candidate = TitanDeliberationProtocol.instructions(capabilities, selected + descriptor)
                if (
                    candidate.length + 2 + control.length + 2 +
                        COGNITIVE_STATE_MIN_CHARS <= charBudget
                ) {
                    selected += descriptor
                }
            }

        val protocol = TitanDeliberationProtocol.instructions(capabilities, selected)
        val fixedPrefix = protocol + "\n\n" + control
        val cognitiveAvailable = charBudget - fixedPrefix.length - 2
        require(cognitiveAvailable >= COGNITIVE_STATE_MIN_CHARS)
        val cognitiveBudget = minOf(
            COGNITIVE_STATE_MAX_CHARS,
            cognitiveAvailable
        )
        val renderedCognitive = IntegratedCognitiveStateRenderer.render(
            cognitiveState,
            cognitiveBudget
        )
        var bounded = fixedPrefix + "\n\n" + renderedCognitive

        val selectedCapabilities = selected.map { it.capability }.toSet()
        val skillGuidance = cognitiveState.skillGuidance.filter { guidance ->
            guidance.contract.signature.capabilities.all { it in selectedCapabilities }
        }
        val compositions = cognitiveState.skillCompositions.filter { composition ->
            composition.capabilities.all { it in selectedCapabilities }
        }

        // Live tool contracts are mandatory. Learned skills are advisory and added only if the
        // bounded protocol can hold them. They contain no authority/approval/tool-id fields.
        outer@ for (skillCount in skillGuidance.size downTo 0) {
            for (compositionCount in compositions.size downTo 0) {
                if (skillCount == 0 && compositionCount == 0) continue
                val rendered = SkillGuidanceRenderer.render(
                    skills = skillGuidance.take(skillCount),
                    compositions = compositions.take(compositionCount)
                )
                val candidate = bounded + "\n\n" + rendered
                if (candidate.length <= charBudget) {
                    bounded = candidate
                    break@outer
                }
            }
        }

        val transferGuidance = cognitiveState.transferGuidance.filter { guidance ->
            guidance.skill.signature.capabilities.all { it in selectedCapabilities }
        }
        val generalizedChains = cognitiveState.generalizedChains.filter { chain ->
            chain.capabilities.all { it in selectedCapabilities }
        }

        // Cross-task transfer is optional advisory evidence. It can only consume remaining prompt
        // budget after the mandatory live tool contracts (and any bounded direct skill evidence).
        generalization@ for (transferCount in transferGuidance.size downTo 0) {
            for (chainCount in generalizedChains.size downTo 0) {
                if (transferCount == 0 && chainCount == 0) continue
                val rendered = GeneralizationGuidanceRenderer.render(
                    guidance = transferGuidance.take(transferCount),
                    chains = generalizedChains.take(chainCount)
                )
                val candidate = bounded + "\n\n" + rendered
                if (candidate.length <= charBudget) {
                    bounded = candidate
                    break@generalization
                }
            }
        }

        val evidenceGuidance = EvidenceGroundedStrategyGuidance.select(
            evidence = runtime.strategies.recent(EvidenceGroundedStrategyGuidance.LOOKBACK),
            allowedCapabilities = allowed,
            limit = EvidenceGroundedStrategyGuidance.MAX_CANDIDATES
        )
        val strategyGuidance = GoalConditionedStrategyRetrieval.rank(
            candidates = evidenceGuidance,
            goal = userGoal,
            descriptors = selected,
            limit = EvidenceGroundedStrategyGuidance.MAX_CANDIDATES
        )

        for (count in strategyGuidance.size downTo 1) {
            val rendered = EvidenceGroundedStrategyGuidance.render(strategyGuidance.take(count))
            val candidate = bounded + "\n\n" + rendered
            if (candidate.length <= charBudget) return candidate
        }
        return bounded
    }

    private fun routedDescriptors(): List<ToolDescriptor> = advertisedCapabilities
        .mapNotNull(actions::descriptorFor)
        .sortedBy { it.capability.value }

    private fun assessContinuity(plan: SovereignPlan): CognitiveContinuityAssessment? {
        val expected = plan.planningExecutionContextDigest ?: return null
        val current = runtime.integratedCognition.capture(
            query = plan.goal,
            allowedCapabilities = advertisedCapabilities,
            descriptors = routedDescriptors()
        )
        return CognitiveContinuityPolicy.assess(
            expectedExecutionContextDigest = expected,
            current = current
        )
    }

    private fun renderContextRefreshConstraint(
        plan: SovereignPlan,
        assessment: CognitiveContinuityAssessment
    ): String = buildString {
        require(assessment.blocksExecution)
        appendLine("<CONTEXT_REFRESH_CONSTRAINT>")
        appendLine("reason=EXECUTION_CONTEXT_CHANGED")
        appendLine("parent_plan=" + plan.id.value.take(64))
        appendLine("previous_execution_context=" + requireNotNull(assessment.expectedExecutionContextDigest))
        appendLine("current_execution_context=" + assessment.currentExecutionContextDigest)
        appendLine("current_cognitive_state=" + assessment.currentCognitiveStateDigest)
        plan.steps
            .filter { it.status != PlanStepStatus.PLANNED && it.status != PlanStepStatus.REQUIRES_CONFIRMATION }
            .take(TitanPlanProtocol.MAX_STEPS)
            .forEach { step ->
                appendLine(
                    "terminal_step=" + step.index + "|" +
                        step.capability.value.take(64) + "|" + step.status.name
                )
            }
        appendLine("old_approval_transfer=false")
        appendLine("old_request_id_reuse=false")
        appendLine(
            "instruction=Create a fresh bounded plan from current grounded state. Treat terminal " +
                "steps above as historical outcomes and never assume an old pending approval remains valid."
        )
        append("</CONTEXT_REFRESH_CONSTRAINT>")
    }

    private fun renderContextRefreshSummary(
        previous: SovereignPlan,
        replacement: SovereignPlan
    ): String = buildString {
        append("Context-refreshed plan ")
        append(replacement.id.value.take(8))
        append(" replaces stale plan ")
        append(previous.id.value.take(8))
        append(" with ")
        append(replacement.steps.size)
        append(" newly bound governed step")
        if (replacement.steps.size != 1) append('s')
        append(". No replacement step has executed and no prior approval was transferred.")
    }

    private fun renderRecoverySummary(
        failedPlan: SovereignPlan,
        replacement: SovereignPlan
    ): String = buildString {
        append("Recovery plan ")
        append(replacement.id.value.take(8))
        append(" replaces terminal plan ")
        append(failedPlan.id.value.take(8))
        append(" with ")
        append(replacement.steps.size)
        append(" governed step")
        if (replacement.steps.size != 1) append('s')
        appendLine(":")
        replacement.steps.forEach { step ->
            appendLine("${step.index}. ${step.capability.value} — ${step.reason.take(160)}")
        }
        if (replacement.deliberationCandidateCount > 1) {
            append(
                "Recovery deliberation evaluated ${replacement.deliberationCandidateCount} validated candidates; " +
                    "selected score=${"%.3f".format(java.util.Locale.US, replacement.deliberationScore ?: 0.0)}; " +
                    "counterfactual viability=${"%.3f".format(java.util.Locale.US, replacement.counterfactualViability ?: 0.0)}; " +
                    "counterfactual confidence=${"%.3f".format(java.util.Locale.US, replacement.counterfactualConfidence ?: 0.0)}. "
            )
        }
        append("No recovery-plan step has executed yet.")
    }

    private fun renderSummary(plan: SovereignPlan): String = buildString {
        append("Sovereign plan ${plan.id.value.take(8)} with ${plan.steps.size} governed step")
        if (plan.steps.size != 1) append('s')
        appendLine(":")
        plan.steps.forEach { step ->
            appendLine("${step.index}. ${step.capability.value} — ${step.reason.take(160)}")
        }
        if (plan.deliberationCandidateCount > 1) {
            append(
                "Deliberation evaluated ${plan.deliberationCandidateCount} validated candidates; " +
                    "selected score=${"%.3f".format(java.util.Locale.US, plan.deliberationScore ?: 0.0)}; " +
                    "counterfactual viability=${"%.3f".format(java.util.Locale.US, plan.counterfactualViability ?: 0.0)}; " +
                    "counterfactual confidence=${"%.3f".format(java.util.Locale.US, plan.counterfactualConfidence ?: 0.0)}. "
            )
        }
        append("No plan step has executed yet.")
    }

    private fun SovereignPlan.replace(step: SovereignPlanStep): SovereignPlan = copy(
        steps = steps.map { if (it.index == step.index) step else it }
    )

    private fun ActionOutcome.toPlanStatus(): PlanStepStatus = when (status) {
        ActionStatus.REQUIRES_CONFIRMATION -> PlanStepStatus.REQUIRES_CONFIRMATION
        ActionStatus.EXECUTED -> PlanStepStatus.EXECUTED
        ActionStatus.DENIED -> PlanStepStatus.DENIED
        ActionStatus.FAILED -> PlanStepStatus.FAILED
        ActionStatus.MALFORMED -> PlanStepStatus.MALFORMED
        ActionStatus.UNAVAILABLE -> PlanStepStatus.UNAVAILABLE
        ActionStatus.NO_ACTION -> PlanStepStatus.MALFORMED
    }

    private fun escapePromptData(value: String, limit: Int): String =
        SovereignPromptData.bounded(value, limit)

    companion object {
        private const val MIN_GROUNDED_CONTEXT_CHARS = 1024
        private const val COGNITIVE_STATE_MIN_CHARS = 512
        private const val COGNITIVE_STATE_MAX_CHARS = 1400
        const val MAX_CONTEXT_REPLANS = 3
    }
}