package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

data class AgiMobileLearningEvolutionProbePack(
    val selfLearning: AgiMobileQualificationProbe,
    val selfEvolution: AgiMobileQualificationProbe
) {
    val probes: List<AgiMobileQualificationProbe>
        get() = listOf(selfLearning, selfEvolution)
}

/**
 * Phase566-570 qualification probes for retained continual learning and the existing
 * closed-loop self-evolution transaction.
 *
 * Workloads are synthetic and contain no user data. Self-learning executes the real AMPER-owned
 * Reflex trainer/evaluator/replacement path. Self-evolution executes the real campaign/tournament/
 * promotion/checkpoint/apply/canary/commit machinery while sandbox and deployment effects remain
 * synthetic and local to each qualification sample.
 */
object AgiMobileLearningEvolutionQualificationProbes {
    const val SELF_LEARNING_SAMPLE_COUNT = 16
    const val SELF_EVOLUTION_SAMPLE_COUNT = 8

    fun canonical(
        subject: AgiMobileQualificationSubject
    ): AgiMobileLearningEvolutionProbePack =
        AgiMobileLearningEvolutionProbePack(
            selfLearning = selfLearningProbe(subject),
            selfEvolution = selfEvolutionProbe(subject)
        )

    fun selfLearningProbe(
        subject: AgiMobileQualificationSubject
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "self-learning-retention",
            domain = AgiMobileQualificationDomain.SELF_LEARNING
        ) {
            runCatching {
                val result = runSelfLearningRetention()
                val score =
                    result.retainedHeldout.toDouble() / SELF_LEARNING_SAMPLE_COUNT.toDouble()
                val assertions = listOf(
                    result.initialStage == ReflexNativeLifecycleStage.ACTIVE,
                    result.replacementStage == ReflexNativeLifecycleStage.REPLACED,
                    result.challengerDiffers,
                    result.parentBound,
                    result.activeBound,
                    result.evaluationAdmitted,
                    result.replayOverlap,
                    result.retainedHeldout == SELF_LEARNING_SAMPLE_COUNT,
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.SELF_LEARNING
                        }.minScore
                )
                val material = listOf(
                    "SELF_LEARNING_RETENTION_V1",
                    subject.canonicalDigest,
                    result.initialStage.name,
                    result.replacementStage.name,
                    result.retainedHeldout.toString(),
                    SELF_LEARNING_SAMPLE_COUNT.toString(),
                    result.challengerDiffers.toString(),
                    result.parentBound.toString(),
                    result.activeBound.toString(),
                    result.evaluationAdmitted.toString(),
                    result.replayOverlap.toString()
                ).joinToString("|")
                AgiMobileQualificationEvidence(
                    probeId = "self-learning-retention",
                    domain = AgiMobileQualificationDomain.SELF_LEARNING,
                    score = score,
                    samples = SELF_LEARNING_SAMPLE_COUNT,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    fun selfEvolutionProbe(
        subject: AgiMobileQualificationSubject
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "self-evolution-transaction",
            domain = AgiMobileQualificationDomain.SELF_EVOLUTION
        ) {
            runCatching {
                val results = (0 until SELF_EVOLUTION_SAMPLE_COUNT).map(::runSelfEvolutionSample)
                val passed = results.count { it.passed }
                val score = passed.toDouble() / results.size.toDouble()
                val assertions = listOf(
                    results.all { it.terminalStage == EvolutionAutonomyCycleStage.COMMITTED },
                    results.all { it.committedCount == 1 },
                    results.all { it.checkpointCalls == 1 },
                    results.all { it.applyCalls == 1 },
                    results.all { it.rollbackCalls == 0 },
                    results.all { it.identityAdvanced },
                    results.all { it.ledgerCommitted },
                    results.all { it.duplicateSuppressed },
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.SELF_EVOLUTION
                        }.minScore
                )
                val material = buildString {
                    append("SELF_EVOLUTION_TRANSACTION_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(passed).append('/').append(results.size).append('|')
                    results.forEachIndexed { index, result ->
                        append(index).append(':')
                        append(result.terminalStage.name).append(':')
                        append(result.committedCount).append(':')
                        append(result.checkpointCalls).append(':')
                        append(result.applyCalls).append(':')
                        append(result.rollbackCalls).append(':')
                        append(if (result.identityAdvanced) '1' else '0').append(':')
                        append(if (result.ledgerCommitted) '1' else '0').append(':')
                        append(if (result.duplicateSuppressed) '1' else '0').append(';')
                    }
                }
                AgiMobileQualificationEvidence(
                    probeId = "self-evolution-transaction",
                    domain = AgiMobileQualificationDomain.SELF_EVOLUTION,
                    score = score,
                    samples = results.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    private fun runSelfLearningRetention(): SelfLearningResult {
        val runtime = AmperRuntime.reference()
        val artifacts = InMemoryReflexLinearArtifactStore()
        seedInitialReflexEvidence(runtime.reflexExperienceDatasets, "qualification-learning-base")
        val lifecycle = ReflexNativeModelLifecycle(runtime, artifacts)

        val initial = lifecycle.maintain().getOrThrow()
        val championId = requireNotNull(initial.checkpointId)
        val champion = requireNotNull(runtime.nativeModelFoundation.getCheckpoint(championId))
        val championTrainingIds = champion.datasetShardIds.flatMap { shardId ->
            requireNotNull(runtime.reflexExperienceDatasets.getShard(shardId)).exampleIds
        }.toSet()

        val heldoutInputs = (0 until SELF_LEARNING_SAMPLE_COUNT).map { index ->
            "check battery and ram right now qualification heldout $index"
        }
        val descriptors = listOf(deviceDescriptor())
        val before = heldoutInputs.map { input ->
            runtime.reflexDecisionCortex.decide(
                ReflexDecisionRequest(
                    userInput = input,
                    descriptors = descriptors
                )
            )
        }

        seedFreshWebSearchEvidence(
            runtime.reflexExperienceDatasets,
            "qualification-learning-fresh"
        )
        val replacement = lifecycle.maintain().getOrThrow()
        val challengerId = requireNotNull(replacement.checkpointId)
        val challenger = requireNotNull(runtime.nativeModelFoundation.getCheckpoint(challengerId))
        val challengerTrainingIds = challenger.datasetShardIds.flatMap { shardId ->
            requireNotNull(runtime.reflexExperienceDatasets.getShard(shardId)).exampleIds
        }.toSet()
        val evaluation = runtime.nativeTrainingPipeline.getEvaluation(challengerId)

        val after = heldoutInputs.map { input ->
            runtime.reflexDecisionCortex.decide(
                ReflexDecisionRequest(
                    userInput = input,
                    descriptors = descriptors
                )
            )
        }
        val retained = before.zip(after).count { (baseline, learned) ->
            baseline.source == ReflexDecisionSource.NATIVE_SYSTEM1 &&
                baseline.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                baseline.capability == DeviceStatusToolContract.capability &&
                learned.source == ReflexDecisionSource.NATIVE_SYSTEM1 &&
                learned.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                learned.capability == baseline.capability &&
                learned.capability == DeviceStatusToolContract.capability
        }

        return SelfLearningResult(
            initialStage = initial.stage,
            replacementStage = replacement.stage,
            retainedHeldout = retained,
            challengerDiffers = challengerId != championId,
            parentBound = challenger.parentCheckpointId == championId,
            activeBound = runtime.reflexDecisionRuntime.active()?.checkpointId == challengerId,
            evaluationAdmitted = evaluation?.admission?.admitted == true,
            replayOverlap =
                challengerTrainingIds.intersect(championTrainingIds).isNotEmpty()
        )
    }

    private fun runSelfEvolutionSample(sample: Int): SelfEvolutionSampleResult {
        val runtime = AmperRuntime.reference()
        val capability = CapabilityId("qualification.evolution.$sample")
        repeat(4) { index ->
            runtime.competence.observe(
                ActionOutcome(
                    status = ActionStatus.FAILED,
                    proposal = ActionProposal(
                        requestId = ActionRequestId("qualification-evolution-$sample-failed-$index"),
                        capability = capability,
                        reason = "synthetic governed qualification failure",
                        input = ""
                    ),
                    toolId = ToolId("qualification-evolution-tool-$sample"),
                    sideEffect = ToolSideEffect.READ_ONLY,
                    detail = "qualification failure"
                )
            )
        }

        val initialIdentity = EvolutionRuntimeIdentity(
            revision = "qualification-base-$sample",
            artifactDigest = agiQualificationSha256("qualification-base-artifact-$sample")
        )
        val candidateArtifact =
            agiQualificationSha256("qualification-candidate-artifact-$sample")
        val candidateRevision = "qualification-candidate-$sample-v2"
        val deployment = QualificationDeploymentIdentityPort(initialIdentity)

        val inference = object : CognitiveInferencePort {
            override fun infer(request: InferenceRequest): Result<InferenceResponse> {
                val metric = Regex("""- metric=([^ ]+) current=""")
                    .find(request.prompt)
                    ?.groupValues
                    ?.get(1)
                    ?: error("qualification evolution metric missing")
                val output = buildString {
                    appendLine("AMPER_EVOLUTION_BLUEPRINTS_V1")
                    append("C|STRATEGY|")
                    append(b64("repair synthetic governed execution weakness $sample"))
                    append("|")
                    append(b64("qualification-strategy-$sample-v2"))
                    append("|")
                    append(metric)
                }
                return Result.success(
                    InferenceResponse(
                        modelId = ModelId("qualification-evolution-generator"),
                        backendId = "qualification-evolution",
                        text = output,
                        selectedCapabilities = setOf(TitanCapabilities.REASONING)
                    )
                )
            }
        }

        val invariants = CanonicalSelfModel().snapshot().invariants.associateWith { true }
        val sandbox = EvolutionSandboxRunner { _, request ->
            Result.success(
                EvolutionSandboxCandidateEvidence(
                    artifactDigest = candidateArtifact,
                    proposedRevision = candidateRevision,
                    metrics = request.suite.metrics.associate { metric ->
                        metric.id to EvolutionMetricObservation(
                            score = 0.92,
                            samples = 4
                        )
                    },
                    sandboxPassed = true,
                    testsPassed = true,
                    invariantResults = invariants,
                    rollbackToken = "qualification-rollback-$sample",
                    observedAtEpochMs = 20_000L + sample
                )
            )
        }

        val canary = object : EvolutionCanaryEvaluator {
            override fun evaluate(
                proposal: EvolutionPromotionProposal,
                receipt: EvolutionDeploymentReceipt
            ): Result<EvolutionBenchmarkSnapshot> {
                val suite = requireNotNull(runtime.autonomousEvolution.getSuite(proposal.suiteId))
                return Result.success(
                    EvolutionBenchmarkSnapshot(
                        subjectId = EvolutionBenchmarkSubjectId(proposal.candidateId.value),
                        suiteId = suite.id,
                        suiteDigest = suite.canonicalDigest,
                        metrics = suite.metrics.associate { metric ->
                            metric.id to EvolutionMetricObservation(
                                score = 0.90,
                                samples = 4
                            )
                        },
                        artifactDigest = receipt.artifactDigest,
                        observedAtEpochMs = receipt.appliedAtEpochMs + 1
                    )
                )
            }
        }

        val port = runtime.closedLoopEvolutionExecutive(
            inference = inference,
            sandbox = sandbox,
            deployment = deployment,
            canary = canary,
            maxCandidates = 1
        )
        val directive = CognitiveExecutiveDirective(
            cognitiveStateDigest =
                agiQualificationSha256("qualification-cognitive-$sample"),
            executionContextDigest =
                agiQualificationSha256("qualification-execution-$sample"),
            action = CognitiveExecutiveAction.EVOLVE,
            overallReadiness = 0.30,
            uncertainty = 0.20,
            learningPressure = 0.91,
            triggeringCapability = capability,
            triggeringNeedKind = LearningNeedKind.EXECUTION_RELIABILITY,
            triggeringEvidenceConfidence = 0.50,
            rationale = "synthetic governed weakness is eligible for qualification evolution"
        )

        val result = port.run(directive).getOrThrow()
        val ledger = runtime.closedLoopEvolutionLedger.get(capability)
        val duplicate = port.run(directive).getOrThrow()

        val identityAdvanced =
            deployment.current().revision == candidateRevision &&
                deployment.current().artifactDigest == candidateArtifact
        val ledgerCommitted = ledger?.committedCount == 1 &&
            ledger.terminalStage == EvolutionAutonomyCycleStage.COMMITTED &&
            !ledger.authorityBearing
        val duplicateSuppressed =
            duplicate.terminalStage == EvolutionAutonomyCycleStage.NO_GAP &&
                duplicate.cycles.singleOrNull()?.failureCode == "UNCHANGED_LIVE_EVIDENCE" &&
                deployment.applyCalls == 1

        val passed =
            result.terminalStage == EvolutionAutonomyCycleStage.COMMITTED &&
                result.committedCount == 1 &&
                deployment.checkpointCalls == 1 &&
                deployment.applyCalls == 1 &&
                deployment.rollbackCalls == 0 &&
                identityAdvanced &&
                ledgerCommitted &&
                duplicateSuppressed

        return SelfEvolutionSampleResult(
            passed = passed,
            terminalStage = result.terminalStage,
            committedCount = result.committedCount,
            checkpointCalls = deployment.checkpointCalls,
            applyCalls = deployment.applyCalls,
            rollbackCalls = deployment.rollbackCalls,
            identityAdvanced = identityAdvanced,
            ledgerCommitted = ledgerCommitted,
            duplicateSuppressed = duplicateSuppressed
        )
    }

    private fun seedInitialReflexEvidence(
        store: ReflexExperienceDatasetStore,
        prefix: String
    ) {
        val descriptor = deviceDescriptor()
        repeat(40) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "read device status",
                input = "summary"
            )
            store.observeExecuted(
                userInput = "check battery and ram right now sample $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "battery_percent=50"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 1_000L + index
            )
        }
        repeat(40) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain a difficult concept in detail sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("qualification-teacher"),
                    backendId = "qualification-teacher",
                    text = "bounded synthetic reasoned answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 2_000L + index
            )
        }
    }

    private fun seedFreshWebSearchEvidence(
        store: ReflexExperienceDatasetStore,
        prefix: String
    ) {
        val descriptor = webSearchDescriptor()
        repeat(24) { index ->
            val proposal = ActionProposal(
                requestId = ActionRequestId("$prefix-action-$index"),
                capability = descriptor.capability,
                reason = "search the web",
                input = "android battery optimization sample $index"
            )
            store.observeExecuted(
                userInput = "search web for android battery optimization sample $index",
                descriptors = listOf(descriptor),
                action = ActionOutcome(
                    status = ActionStatus.EXECUTED,
                    proposal = proposal,
                    toolId = descriptor.id,
                    sideEffect = descriptor.sideEffect,
                    output = "web_search_requested"
                ),
                source = ReflexExperienceSource.SYSTEM2_TEACHER,
                labelConfidence = 0.99,
                observedAtEpochMs = 3_000L + index
            )
        }
        repeat(24) { index ->
            store.observeEscalation(
                conversationId = ConversationId("$prefix-thread-$index"),
                userInput = "explain quantum field theory carefully sample $index",
                descriptors = listOf(descriptor),
                response = InferenceResponse(
                    modelId = ModelId("qualification-teacher"),
                    backendId = "qualification-teacher",
                    text = "bounded synthetic fresh reasoned answer $index"
                ),
                labelConfidence = 0.90,
                observedAtEpochMs = 4_000L + index
            )
        }
    }

    private fun deviceDescriptor(): ToolDescriptor =
        ToolDescriptor(
            id = DeviceStatusToolContract.toolId,
            name = "device status",
            capability = DeviceStatusToolContract.capability,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract(
                description = "status",
                acceptedValues = setOf("summary", "status"),
                maxLength = 16
            )
        )

    private fun webSearchDescriptor(): ToolDescriptor =
        ToolDescriptor(
            id = AndroidWebSearchToolContract.toolId,
            name = "web search",
            capability = AndroidWebSearchToolContract.capability,
            sideEffect = ToolSideEffect.EXTERNAL,
            inputContract = ToolInputContract(
                description = "web query",
                maxLength = AndroidWebSearchToolContract.MAX_QUERY_CHARS
            )
        )

    private class QualificationDeploymentIdentityPort(
        initial: EvolutionRuntimeIdentity
    ) : EvolutionDeploymentIdentityPort {
        private val baseline = initial
        private var identity = initial
        var checkpointCalls = 0
        var applyCalls = 0
        var rollbackCalls = 0

        override fun current(): EvolutionRuntimeIdentity = identity

        override fun checkpoint(
            proposal: EvolutionPromotionProposal
        ): Result<EvolutionDeploymentCheckpoint> = runCatching {
            checkpointCalls += 1
            require(identity.revision == proposal.baseRevision)
            EvolutionDeploymentCheckpoint(
                checkpointToken = "qualification-checkpoint-" + proposal.ticketDigest.take(24),
                baselineRevision = identity.revision,
                capturedAtEpochMs = 30_000L
            )
        }

        override fun apply(
            proposal: EvolutionPromotionProposal,
            checkpoint: EvolutionDeploymentCheckpoint
        ): Result<EvolutionDeploymentReceipt> = runCatching {
            applyCalls += 1
            require(checkpoint.baselineRevision == identity.revision)
            identity = EvolutionRuntimeIdentity(
                revision = proposal.proposedRevision,
                artifactDigest = proposal.artifactDigest
            )
            EvolutionDeploymentReceipt(
                artifactDigest = identity.artifactDigest,
                activeRevision = identity.revision,
                appliedAtEpochMs = 31_000L
            )
        }

        override fun rollback(
            checkpoint: EvolutionDeploymentCheckpoint,
            receipt: EvolutionDeploymentReceipt?
        ): Result<Unit> = runCatching {
            rollbackCalls += 1
            identity = baseline
        }
    }

    private data class SelfLearningResult(
        val initialStage: ReflexNativeLifecycleStage,
        val replacementStage: ReflexNativeLifecycleStage,
        val retainedHeldout: Int,
        val challengerDiffers: Boolean,
        val parentBound: Boolean,
        val activeBound: Boolean,
        val evaluationAdmitted: Boolean,
        val replayOverlap: Boolean
    )

    private data class SelfEvolutionSampleResult(
        val passed: Boolean,
        val terminalStage: EvolutionAutonomyCycleStage,
        val committedCount: Int,
        val checkpointCalls: Int,
        val applyCalls: Int,
        val rollbackCalls: Int,
        val identityAdvanced: Boolean,
        val ledgerCommitted: Boolean,
        val duplicateSuppressed: Boolean
    )

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
