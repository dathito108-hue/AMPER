package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

@JvmInline
value class GoalGraphId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class GoalNodeStatus {
    BLOCKED,
    READY,
    PLANNED,
    AWAITING_VERIFICATION,
    NEEDS_REVIEW,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class GoalGraphReviewStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}

data class SovereignGoalNode(
    val index: Int,
    val objective: String,
    val dependencies: Set<Int>,
    val status: GoalNodeStatus,
    val planId: PlanId? = null,
    val verification: GoalVerificationRecord? = null,
    val legacyUnverifiedCompletion: Boolean = false
) {
    init {
        require(index > 0)
        require(objective.isNotBlank())
        require(objective.length <= SovereignGoalGraphProtocol.MAX_OBJECTIVE_CHARS)
        require(dependencies.size <= SovereignGoalGraphProtocol.MAX_DEPENDENCIES)
        require(dependencies.all { it in 1 until index }) {
            "goal dependencies must reference earlier nodes only"
        }
        if (status == GoalNodeStatus.READY || status == GoalNodeStatus.BLOCKED) {
            require(planId == null) { "unplanned goal node cannot carry a plan id" }
            require(verification == null) { "unplanned goal node cannot carry verification" }
        }
        if (
            status == GoalNodeStatus.PLANNED ||
            status == GoalNodeStatus.AWAITING_VERIFICATION ||
            status == GoalNodeStatus.NEEDS_REVIEW ||
            status == GoalNodeStatus.COMPLETED ||
            status == GoalNodeStatus.FAILED
        ) {
            requireNotNull(planId) { "materialized goal node requires a plan id" }
        }
        if (
            status == GoalNodeStatus.PLANNED ||
            status == GoalNodeStatus.AWAITING_VERIFICATION
        ) {
            require(verification == null) {
                "unverified active goal node cannot already carry verification"
            }
        }
        if (status == GoalNodeStatus.NEEDS_REVIEW) {
            requireNotNull(verification) { "goal node needing review requires verification evidence" }
        }
        require(!legacyUnverifiedCompletion || status == GoalNodeStatus.COMPLETED) {
            "legacy unverified completion marker is valid only for completed nodes"
        }
        require(!legacyUnverifiedCompletion || verification == null) {
            "legacy completion marker cannot coexist with verification evidence"
        }
        if (status == GoalNodeStatus.COMPLETED) {
            if (verification == null) {
                require(legacyUnverifiedCompletion) {
                    "new completed goal node requires verification evidence"
                }
            } else {
                val modelVerified =
                    verification.verdict == GoalVerificationVerdict.SATISFIED &&
                        verification.confidence >=
                        GoalVerificationProtocol.MIN_AUTOMATIC_COMPLETION_CONFIDENCE
                require(modelVerified || verification.userAccepted) {
                    "completed goal node requires verified satisfaction or explicit user acceptance"
                }
            }
        }
    }
}

data class SovereignGoalGraph(
    val id: GoalGraphId = GoalGraphId(UUID.randomUUID().toString()),
    val conversationId: ConversationId,
    val rootGoal: String,
    val nodes: List<SovereignGoalNode>,
    val decompositionBackendId: String,
    val reviewStatus: GoalGraphReviewStatus = GoalGraphReviewStatus.PENDING_REVIEW,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val decompositionModelId: ModelId? = null,
    val decompositionSelectedCapabilities: Set<CapabilityId> = emptySet()
) {
    init {
        require(rootGoal.isNotBlank())
        require(nodes.isNotEmpty())
        require(nodes.size <= SovereignGoalGraphProtocol.MAX_NODES)
        require(nodes.map { it.index } == (1..nodes.size).toList())
        require(decompositionBackendId.isNotBlank())
        require(
            nodes.count {
                it.status == GoalNodeStatus.PLANNED ||
                    it.status == GoalNodeStatus.AWAITING_VERIFICATION ||
                    it.status == GoalNodeStatus.NEEDS_REVIEW
            } <= 1
        ) {
            "long-horizon orchestrator permits at most one unresolved active subgoal"
        }
        if (nodes.any { it.planId != null }) {
            require(reviewStatus == GoalGraphReviewStatus.APPROVED) {
                "materialized subgoals require an approved goal graph"
            }
        }
    }

    val complete: Boolean
        get() = nodes.all { it.status == GoalNodeStatus.COMPLETED }

    val stalled: Boolean
        get() = !complete &&
            nodes.none {
                it.status == GoalNodeStatus.PLANNED ||
                    it.status == GoalNodeStatus.AWAITING_VERIFICATION ||
                    it.status == GoalNodeStatus.NEEDS_REVIEW
            } &&
            readyNodes().isEmpty()

    fun readyNodes(): List<SovereignGoalNode> {
        val byIndex = nodes.associateBy { it.index }
        return nodes.filter { node ->
            if (node.status != GoalNodeStatus.READY && node.status != GoalNodeStatus.BLOCKED) {
                false
            } else {
                node.dependencies.all { dependency ->
                    byIndex.getValue(dependency).status == GoalNodeStatus.COMPLETED
                }
            }
        }.map { node ->
            if (node.status == GoalNodeStatus.READY) node
            else node.copy(status = GoalNodeStatus.READY)
        }
    }

    fun normalized(): SovereignGoalGraph {
        val byIndex = nodes.associateBy { it.index }
        val normalizedNodes = nodes.map { node ->
            when (node.status) {
                GoalNodeStatus.READY,
                GoalNodeStatus.BLOCKED -> {
                    val ready = node.dependencies.all { dependency ->
                        byIndex.getValue(dependency).status == GoalNodeStatus.COMPLETED
                    }
                    node.copy(status = if (ready) GoalNodeStatus.READY else GoalNodeStatus.BLOCKED)
                }
                else -> node
            }
        }
        return if (normalizedNodes == nodes) this else copy(nodes = normalizedNodes)
    }
}

/**
 * Bounded hierarchical goal decomposition protocol.
 *
 * Dependencies may reference earlier nodes only. This provides a deterministic topological order
 * and makes cycles/forward references impossible by construction. Decomposition is data only:
 * parsing never invokes a tool and never grants authority.
 */
object SovereignGoalGraphProtocol {
    const val MAX_NODES = 8
    const val MAX_DEPENDENCIES = 3
    const val MAX_OBJECTIVE_CHARS = 384
    private const val OPEN = "<AMPER_GOAL_GRAPH_V1>"
    private const val CLOSE = "</AMPER_GOAL_GRAPH_V1>"
    private val FIELD = Regex("node\\.(\\d+)\\.(objective|depends)")

    fun parse(modelOutput: String): Result<List<SovereignGoalNode>> = runCatching {
        val text = modelOutput.trim()
        require(text.startsWith(OPEN) && text.endsWith(CLOSE)) {
            "goal graph envelope must be the entire model output"
        }
        val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        require(body.isNotBlank()) { "goal graph must contain at least one node" }

        val fields = linkedMapOf<String, String>()
        body.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "goal graph field missing '='" }
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(FIELD.matches(key)) { "unknown goal graph field: $key" }
                require(fields.put(key, value) == null) { "duplicate goal graph field: $key" }
            }

        val indices = fields.keys
            .mapNotNull { FIELD.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
        require(indices.isNotEmpty())
        require(indices.size <= MAX_NODES) { "goal graph exceeds $MAX_NODES nodes" }
        require(indices == (1..indices.size).toList()) {
            "goal graph node numbers must be contiguous from 1"
        }

        indices.map { index ->
            val objective = requireNotNull(fields["node.$index.objective"]).trim()
            require(objective.isNotBlank()) { "goal node $index objective is blank" }
            require(objective.length <= MAX_OBJECTIVE_CHARS) {
                "goal node $index objective exceeds $MAX_OBJECTIVE_CHARS characters"
            }
            val rawDependencies = requireNotNull(fields["node.$index.depends"]).trim()
            val dependencies = if (
                rawDependencies.isBlank() ||
                rawDependencies.equals("none", ignoreCase = true) ||
                rawDependencies == "~"
            ) {
                emptySet()
            } else {
                rawDependencies.split(',')
                    .map { it.trim().toInt() }
                    .toCollection(linkedSetOf())
            }
            require(dependencies.size <= MAX_DEPENDENCIES) {
                "goal node $index exceeds $MAX_DEPENDENCIES dependencies"
            }
            require(dependencies.all { it in 1 until index }) {
                "goal node $index has a forward, self, or invalid dependency"
            }

            SovereignGoalNode(
                index = index,
                objective = objective,
                dependencies = dependencies,
                status = if (dependencies.isEmpty()) GoalNodeStatus.READY else GoalNodeStatus.BLOCKED
            )
        }
    }

    fun instructions(): String = buildString {
        appendLine("Decompose the current root goal into a bounded dependency graph only.")
        appendLine("Do not execute tools, claim execution, request authority, or add hidden tasks.")
        appendLine("Return between 1 and $MAX_NODES nodes in one envelope.")
        appendLine("Dependencies may reference earlier node numbers only; use 'none' when empty.")
        appendLine("Keep each objective concrete, user-aligned, and independently plannable.")
        appendLine("Output only one $OPEN envelope and nothing else.")
        appendLine(OPEN)
        appendLine("node.1.objective=<first bounded subgoal>")
        appendLine("node.1.depends=none")
        appendLine("node.2.objective=<dependent or parallel subgoal>")
        appendLine("node.2.depends=1")
        appendLine(CLOSE)
    }.trim()
}

interface SovereignGoalGraphStore {
    fun save(graph: SovereignGoalGraph): SovereignGoalGraph
    fun load(id: GoalGraphId): SovereignGoalGraph?
    fun list(limit: Int = 16): List<SovereignGoalGraph>
    fun delete(id: GoalGraphId): Boolean
}

class MemoryBackedSovereignGoalGraphStore(
    private val memory: MemoryOs
) : SovereignGoalGraphStore {
    override fun save(graph: SovereignGoalGraph): SovereignGoalGraph {
        val normalized = graph.normalized()
        memory.remember(
            MemoryRecord(
                id = recordId(normalized.id),
                kind = KIND,
                content = SovereignGoalGraphCodec.encode(normalized),
                importance = 0.97,
                provenance = Provenance(
                    source = "sovereign-goal-graph-state",
                    producer = "hierarchical-goal-orchestrator",
                    confidence = 1.0
                ),
                createdAtEpochMs = normalized.createdAtEpochMs
            )
        )
        return normalized
    }

    override fun load(id: GoalGraphId): SovereignGoalGraph? =
        memory.get(recordId(id))
            ?.takeIf { it.kind == KIND }
            ?.let { SovereignGoalGraphCodec.decode(it.content).getOrNull() }
            ?.normalized()

    override fun list(limit: Int): List<SovereignGoalGraph> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(KIND, (limit * 2).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == KIND }
            .mapNotNull { SovereignGoalGraphCodec.decode(it.content).getOrNull() }
            .map(SovereignGoalGraph::normalized)
            .sortedByDescending { it.createdAtEpochMs }
            .take(limit)
            .toList()
    }

    override fun delete(id: GoalGraphId): Boolean = memory.forget(recordId(id))

    private fun recordId(id: GoalGraphId): MemoryId =
        MemoryId("sovereign-goal-graph:${id.value}")

    companion object {
        const val KIND = "sovereign-goal-graph-v1"
    }
}

/**
 * Explicit-clock long-horizon orchestrator.
 *
 * There is intentionally no runAll/auto-loop API. create() performs one decomposition inference
 * and zero tool calls. A new graph is PENDING_REVIEW and cannot materialize a plan until an
 * explicit approveGraph() transition. planNext() materializes at most one ready subgoal through
 * the existing SovereignPlanCoordinator and still executes zero tools. reconcile() and attachRecovery() are
 * state transitions over already-persisted plan evidence; they do not invoke providers.
 */
class SovereignGoalGraphCoordinator(
    private val runtime: AmperRuntime,
    private val inference: CognitiveInferencePort,
    private val planner: SovereignPlanCoordinator,
    private val store: SovereignGoalGraphStore = runtime.goalGraphs,
    private val maxPromptChars: Int = 9000,
    private val maxOutputTokens: Int = 512,
    private val temperature: Double = 0.5
) {
    private val baselineCapabilities = setOf(TitanCapabilities.REASONING)

    fun create(
        conversationId: ConversationId,
        rootGoal: String
    ): Result<SovereignGoalGraph> = runCatching {
        require(rootGoal.isNotBlank())
        val profile = runtime.inferenceProfiles.bind(
            conversationId = conversationId,
            fallbackMaxOutputTokens = maxOutputTokens,
            fallbackTemperature = temperature,
            fallbackMaxPromptChars = maxPromptChars
        )
        runtime.tick(rootGoal)
        val prompt = decompositionPrompt(
            rootGoal = rootGoal,
            charBudget = profile.maxPromptChars
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = profile.maxOutputTokens,
                temperature = profile.temperature,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities),
                userPreferredModelId = runtime.conversations.preferredModelId(conversationId),
                sessionRoutingPreference = profile.sessionRoutingPreference
            )
        ).getOrThrow()

        val graph = SovereignGoalGraph(
            conversationId = conversationId,
            rootGoal = rootGoal,
            nodes = SovereignGoalGraphProtocol.parse(response.text).getOrThrow(),
            decompositionBackendId = response.backendId,
            decompositionModelId = response.modelId,
            decompositionSelectedCapabilities = response.selectedCapabilities
        ).normalized()

        store.save(graph).also { saved ->
            runtime.conversations.commitAssistant(
                conversationId = conversationId,
                userPrompt = rootGoal,
                response = renderGraphSummary(saved),
                backendId = response.backendId,
                confidence = 0.8,
                modelId = response.modelId,
                selectedCapabilities = response.selectedCapabilities
            )
        }
    }

    fun approveGraph(graphId: GoalGraphId): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) { "goal graph ${graphId.value} is unavailable" }
        require(graph.reviewStatus == GoalGraphReviewStatus.PENDING_REVIEW) {
            "goal graph is not pending review"
        }
        require(graph.nodes.none { it.status == GoalNodeStatus.PLANNED }) {
            "goal graph already contains a materialized subgoal"
        }
        store.save(graph.copy(reviewStatus = GoalGraphReviewStatus.APPROVED))
    }

    fun rejectGraph(graphId: GoalGraphId): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) { "goal graph ${graphId.value} is unavailable" }
        require(graph.reviewStatus == GoalGraphReviewStatus.PENDING_REVIEW) {
            "goal graph is not pending review"
        }
        require(graph.nodes.none { it.status == GoalNodeStatus.PLANNED }) {
            "goal graph already contains a materialized subgoal"
        }
        store.save(graph.copy(reviewStatus = GoalGraphReviewStatus.REJECTED))
    }

    /**
     * Materialize exactly one currently-ready node into one governed SovereignPlan.
     * The created plan is persisted but not advanced or executed here.
     */
    fun planNext(graphId: GoalGraphId): Result<Pair<SovereignGoalGraph, SovereignPlan>> = runCatching {
        val graph = requireNotNull(store.load(graphId)) { "goal graph ${graphId.value} is unavailable" }
            .normalized()
        require(graph.reviewStatus == GoalGraphReviewStatus.APPROVED) {
            "goal graph requires explicit approval before subgoal materialization"
        }
        require(
            graph.nodes.none {
                it.status == GoalNodeStatus.PLANNED ||
                    it.status == GoalNodeStatus.AWAITING_VERIFICATION ||
                    it.status == GoalNodeStatus.NEEDS_REVIEW
            }
        ) {
            "goal graph already has an unresolved active subgoal"
        }
        val node = graph.readyNodes().firstOrNull()
            ?: error("goal graph has no ready subgoal")

        val plan = planner.create(
            conversationId = graph.conversationId,
            userGoal = node.objective
        ).getOrThrow()
        runtime.plans.save(plan)

        val updated = graph.copy(
            nodes = graph.nodes.map {
                if (it.index == node.index) {
                    it.copy(status = GoalNodeStatus.PLANNED, planId = plan.id)
                } else {
                    it
                }
            }
        ).normalized()
        store.save(updated) to plan
    }

    /**
     * Reconcile one materialized subgoal from durable plan state. No provider invocation occurs.
     * Execution success moves the node to AWAITING_VERIFICATION and does not unlock dependants.
     * Rejected/failed execution remains terminal until explicit review or governed recovery.
     */
    fun reconcile(
        graphId: GoalGraphId,
        nodeIndex: Int
    ): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) { "goal graph ${graphId.value} is unavailable" }
        val node = graph.nodes.single { it.index == nodeIndex }
        require(node.status == GoalNodeStatus.PLANNED) {
            "goal node $nodeIndex is not awaiting plan reconciliation"
        }
        val planId = requireNotNull(node.planId)
        val plan = requireNotNull(runtime.plans.load(planId)) {
            "materialized plan ${planId.value} is unavailable"
        }
        require(plan.complete) { "materialized plan is not terminal" }

        val nextStatus = when {
            plan.steps.all { it.status == PlanStepStatus.EXECUTED } ->
                GoalNodeStatus.AWAITING_VERIFICATION
            plan.steps.any { it.status == PlanStepStatus.REJECTED } ->
                GoalNodeStatus.CANCELLED
            else ->
                GoalNodeStatus.FAILED
        }
        val updated = graph.copy(
            nodes = graph.nodes.map {
                if (it.index == nodeIndex) {
                    it.copy(status = nextStatus, verification = null)
                } else {
                    it
                }
            }
        ).normalized()
        store.save(updated)
    }

    /**
     * Perform one bounded completion-verification inference over terminal execution evidence.
     * No tool/provider invocation occurs. Only a high-confidence SATISFIED verdict can unlock
     * downstream dependencies automatically; every other result requires explicit review.
     */
    fun verifyNode(
        graphId: GoalGraphId,
        nodeIndex: Int
    ): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) {
            "goal graph ${graphId.value} is unavailable"
        }
        require(graph.reviewStatus == GoalGraphReviewStatus.APPROVED) {
            "goal graph is not approved"
        }
        val node = graph.nodes.single { it.index == nodeIndex }
        require(node.status == GoalNodeStatus.AWAITING_VERIFICATION) {
            "goal node $nodeIndex is not awaiting verification"
        }
        val planId = requireNotNull(node.planId)
        val plan = requireNotNull(runtime.plans.load(planId)) {
            "materialized plan ${planId.value} is unavailable"
        }
        require(plan.complete)
        require(plan.steps.all { it.status == PlanStepStatus.EXECUTED }) {
            "goal verification requires an execution-complete plan"
        }

        val profile = runtime.inferenceProfiles.bind(
            conversationId = graph.conversationId,
            fallbackMaxOutputTokens = 96,
            fallbackTemperature = 0.0,
            fallbackMaxPromptChars = maxPromptChars
        )
        val prompt = GoalVerificationEvidence.prompt(
            node = node,
            plan = plan,
            charBudget = profile.maxPromptChars
        )
        val response = inference.infer(
            InferenceRequest(
                prompt = prompt,
                requiredCapabilities = baselineCapabilities,
                maxOutputTokens = minOf(profile.maxOutputTokens, 96),
                temperature = 0.0,
                preferredCapabilityProfiles =
                    DeterministicInferenceCapabilityPolicy.planningProfile(baselineCapabilities),
                userPreferredModelId = runtime.conversations.preferredModelId(graph.conversationId),
                sessionRoutingPreference = profile.sessionRoutingPreference
            )
        ).getOrThrow()
        val (verdict, confidence) = GoalVerificationProtocol.parse(response.text).getOrThrow()
        val verification = GoalVerificationRecord(
            verdict = verdict,
            confidence = confidence,
            backendId = response.backendId,
            modelId = response.modelId
        )
        val nextStatus = if (
            verdict == GoalVerificationVerdict.SATISFIED &&
            confidence >= GoalVerificationProtocol.MIN_AUTOMATIC_COMPLETION_CONFIDENCE
        ) {
            GoalNodeStatus.COMPLETED
        } else {
            GoalNodeStatus.NEEDS_REVIEW
        }
        val updated = graph.copy(
            nodes = graph.nodes.map {
                if (it.index == nodeIndex) {
                    it.copy(status = nextStatus, verification = verification)
                } else {
                    it
                }
            }
        ).normalized()
        store.save(updated).also {
            runtime.conversations.commitAssistant(
                conversationId = graph.conversationId,
                userPrompt = node.objective,
                response =
                    "Goal verification verdict=${verdict.name} confidence=" +
                        "%.3f".format(java.util.Locale.US, confidence) +
                        "; no tool executed during verification.",
                backendId = response.backendId,
                confidence = confidence,
                modelId = response.modelId,
                selectedCapabilities = response.selectedCapabilities
            )
        }
    }

    fun acceptNodeReview(
        graphId: GoalGraphId,
        nodeIndex: Int
    ): Result<SovereignGoalGraph> = resolveNodeReview(graphId, nodeIndex, accepted = true)

    fun rejectNodeReview(
        graphId: GoalGraphId,
        nodeIndex: Int
    ): Result<SovereignGoalGraph> = resolveNodeReview(graphId, nodeIndex, accepted = false)

    private fun resolveNodeReview(
        graphId: GoalGraphId,
        nodeIndex: Int,
        accepted: Boolean
    ): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) {
            "goal graph ${graphId.value} is unavailable"
        }
        require(graph.reviewStatus == GoalGraphReviewStatus.APPROVED) {
            "goal graph is not approved"
        }
        val node = graph.nodes.single { it.index == nodeIndex }
        require(node.status == GoalNodeStatus.NEEDS_REVIEW) {
            "goal node $nodeIndex is not awaiting explicit outcome review"
        }
        val updated = graph.copy(
            nodes = graph.nodes.map {
                if (it.index == nodeIndex) {
                    it.copy(
                        status = if (accepted) GoalNodeStatus.COMPLETED else GoalNodeStatus.FAILED,
                        verification = if (accepted) {
                            requireNotNull(it.verification).copy(userAccepted = true)
                        } else {
                            it.verification
                        }
                    )
                } else {
                    it
                }
            }
        ).normalized()
        store.save(updated)
    }

    /**
     * Attach one already-created Phase179 recovery plan to a failed node. This does not create,
     * advance or execute that recovery plan.
     */
    fun attachRecovery(
        graphId: GoalGraphId,
        nodeIndex: Int,
        recoveryPlan: SovereignPlan
    ): Result<SovereignGoalGraph> = runCatching {
        val graph = requireNotNull(store.load(graphId)) { "goal graph ${graphId.value} is unavailable" }
        require(graph.reviewStatus == GoalGraphReviewStatus.APPROVED) {
            "goal graph is not approved"
        }
        val node = graph.nodes.single { it.index == nodeIndex }
        require(node.status == GoalNodeStatus.FAILED) {
            "goal node $nodeIndex is not failed"
        }
        val failedPlanId = requireNotNull(node.planId)
        require(recoveryPlan.parentPlanId == failedPlanId) {
            "recovery plan does not descend from the failed node plan"
        }
        require(recoveryPlan.recoveryDepth in 1..GovernedStrategyRecovery.MAX_RECOVERY_DEPTH) {
            "recovery plan depth is outside governed recovery bounds"
        }
        runtime.plans.save(recoveryPlan)
        val updated = graph.copy(
            nodes = graph.nodes.map {
                if (it.index == nodeIndex) {
                    it.copy(
                        status = GoalNodeStatus.PLANNED,
                        planId = recoveryPlan.id,
                        verification = null,
                        legacyUnverifiedCompletion = false
                    )
                } else {
                    it
                }
            }
        ).normalized()
        store.save(updated)
    }

    fun frontier(graphId: GoalGraphId): List<SovereignGoalNode> =
        store.load(graphId)
            ?.normalized()
            ?.takeIf { it.reviewStatus == GoalGraphReviewStatus.APPROVED }
            ?.readyNodes()
            .orEmpty()

    fun load(graphId: GoalGraphId): SovereignGoalGraph? = store.load(graphId)

    private fun decompositionPrompt(rootGoal: String, charBudget: Int): String {
        val protocol = SovereignGoalGraphProtocol.instructions()
        val open = "<CURRENT_ROOT_GOAL>\n"
        val close = "\n</CURRENT_ROOT_GOAL>"
        val fixed = protocol.length + open.length + close.length + 2
        require(fixed < charBudget) { "prompt budget is too small for goal decomposition protocol" }
        val safeGoal = SovereignPromptData.bounded(rootGoal, charBudget - fixed)
        return buildString {
            appendLine(protocol)
            appendLine()
            append(open)
            append(safeGoal)
            append(close)
        }.take(charBudget)
    }

    private fun renderGraphSummary(graph: SovereignGoalGraph): String = buildString {
        append("Created bounded goal graph ")
        append(graph.id.value.take(8))
        append(" with ")
        append(graph.nodes.size)
        append(" subgoals; ready=")
        append(graph.readyNodes().size)
        append(". Review status=")
        append(graph.reviewStatus.name)
        append(". No subgoal plan has executed.")
    }
}

internal object SovereignGoalGraphCodec {
    private const val VERSION_V1 = "AMPER_GOAL_GRAPH_STATE_V1"
    private const val VERSION = "AMPER_GOAL_GRAPH_STATE_V2"

    fun encode(graph: SovereignGoalGraph): String = buildString {
        appendLine(VERSION)
        appendLine("ID\t${enc(graph.id.value)}")
        appendLine("CONVERSATION\t${enc(graph.conversationId.value)}")
        appendLine("ROOT\t${enc(graph.rootGoal)}")
        appendLine("BACKEND\t${enc(graph.decompositionBackendId)}")
        appendLine("REVIEW\t${graph.reviewStatus.name}")
        appendLine("CREATED\t${graph.createdAtEpochMs}")
        appendLine("MODEL\t${graph.decompositionModelId?.value?.let(::enc) ?: "~"}")
        appendLine(
            "CAPABILITIES\t" +
                graph.decompositionSelectedCapabilities
                    .sortedBy { it.value }
                    .joinToString(",") { enc(it.value) }
                    .ifBlank { "~" }
        )
        graph.nodes.forEach { node ->
            appendLine(
                listOf(
                    "NODE",
                    node.index.toString(),
                    enc(node.objective),
                    node.dependencies.sorted().joinToString(",").ifBlank { "~" },
                    node.status.name,
                    node.planId?.value?.let(::enc) ?: "~",
                    node.verification?.verdict?.name ?: "~",
                    node.verification?.confidence?.toString() ?: "~",
                    node.verification?.backendId?.let(::enc) ?: "~",
                    node.verification?.modelId?.value?.let(::enc) ?: "~",
                    node.verification?.verifiedAtEpochMs?.toString() ?: "~",
                    node.verification?.userAccepted?.toString() ?: "~",
                    node.legacyUnverifiedCompletion.toString()
                ).joinToString("\t")
            )
        }
    }.trimEnd()

    fun decode(content: String): Result<SovereignGoalGraph> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(version == VERSION || version == VERSION_V1) {
            "unsupported goal graph state"
        }
        val verifiedState = version == VERSION

        val scalars = linkedMapOf<String, String>()
        val nodeLines = mutableListOf<List<String>>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "NODE" -> {
                    val expectedSize = if (verifiedState) 13 else 6
                    require(parts.size == expectedSize) { "invalid persisted goal node" }
                    nodeLines += parts
                }
                "ID", "CONVERSATION", "ROOT", "BACKEND", "REVIEW", "CREATED", "MODEL", "CAPABILITIES" -> {
                    require(parts.size == 2) { "invalid persisted goal graph scalar" }
                    require(scalars.put(parts[0], parts[1]) == null) {
                        "duplicate persisted goal graph scalar"
                    }
                }
                else -> error("unknown persisted goal graph field")
            }
        }
        require(
            scalars.keys.containsAll(
                setOf("ID", "CONVERSATION", "ROOT", "BACKEND", "REVIEW", "CREATED", "MODEL", "CAPABILITIES")
            )
        )
        require(nodeLines.isNotEmpty())

        val nodes = nodeLines.map { p ->
            val dependencies = p[3]
                .takeUnless { it == "~" }
                ?.split(',')
                ?.filter { it.isNotBlank() }
                ?.mapTo(linkedSetOf()) { it.toInt() }
                .orEmpty()
            val verification = if (verifiedState) {
                p[6].takeUnless { it == "~" }?.let { verdict ->
                    GoalVerificationRecord(
                        verdict = GoalVerificationVerdict.valueOf(verdict),
                        confidence = p[7].toDouble(),
                        backendId = dec(p[8]),
                        modelId = p[9].takeUnless { it == "~" }?.let(::dec)?.let(::ModelId),
                        verifiedAtEpochMs = p[10].toLong(),
                        userAccepted = p[11].toBooleanStrict()
                    )
                }
            } else {
                null
            }
            val status = GoalNodeStatus.valueOf(p[4])
            SovereignGoalNode(
                index = p[1].toInt(),
                objective = dec(p[2]),
                dependencies = dependencies,
                status = status,
                planId = p[5].takeUnless { it == "~" }?.let(::dec)?.let(::PlanId),
                verification = verification,
                legacyUnverifiedCompletion = if (verifiedState) {
                    p[12].toBooleanStrict()
                } else {
                    status == GoalNodeStatus.COMPLETED
                }
            )
        }.sortedBy { it.index }

        val modelId = scalars.getValue("MODEL")
            .takeUnless { it == "~" }
            ?.let(::dec)
            ?.let(::ModelId)
        val capabilities = scalars.getValue("CAPABILITIES")
            .takeUnless { it == "~" }
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.mapTo(linkedSetOf()) { CapabilityId(dec(it)) }
            .orEmpty()

        SovereignGoalGraph(
            id = GoalGraphId(dec(scalars.getValue("ID"))),
            conversationId = ConversationId(dec(scalars.getValue("CONVERSATION"))),
            rootGoal = dec(scalars.getValue("ROOT")),
            nodes = nodes,
            decompositionBackendId = dec(scalars.getValue("BACKEND")),
            reviewStatus = GoalGraphReviewStatus.valueOf(scalars.getValue("REVIEW")),
            createdAtEpochMs = scalars.getValue("CREATED").toLong(),
            decompositionModelId = modelId,
            decompositionSelectedCapabilities = capabilities
        )
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
