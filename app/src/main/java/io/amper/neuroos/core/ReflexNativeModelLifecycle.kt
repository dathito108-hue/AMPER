package io.amper.neuroos.core

enum class ReflexNativeLifecycleStage {
    INSUFFICIENT_EVIDENCE,
    WAITING_FOR_FRESH_EVIDENCE,
    DISABLED,
    TRAINING_FAILED,
    EVALUATION_REJECTED,
    CHALLENGER_REJECTED,
    RECOVERED,
    REPLACED,
    ACTIVE
}

data class ReflexNativeLifecycleReport(
    val stage: ReflexNativeLifecycleStage,
    val checkpointId: NativeCheckpointId?,
    val actionExamples: Int,
    val escalationExamples: Int,
    val detail: String
) {
    init {
        require(actionExamples >= 0)
        require(escalationExamples >= 0)
        require(detail.isNotBlank())
    }
}

class ReflexNativeModelLifecycle(
    private val runtime: AmperRuntime,
    artifacts: ReflexLinearArtifactStore
) {
    private val trainer = ReflexLinearNativeTrainer(artifacts)
    private val resolver = ReflexLinearDecisionPortResolver(
        foundation = runtime.nativeModelFoundation,
        artifacts = artifacts
    )
    private val evaluator = ReflexLinearCheckpointEvaluator(
        foundation = runtime.nativeModelFoundation,
        artifacts = artifacts
    )

    @Volatile
    private var lastRejectedEvidenceTag: String? = null

    @Synchronized
    fun maintain(): Result<ReflexNativeLifecycleReport> = runCatching {
        var current = runtime.reflexDecisionRuntime.active()
        var recovered = false

        if (current == null) {
            val persisted = runtime.reflexDecisionRuntime.persistedIntent()
            if (persisted?.status == ReflexRuntimeActivationIntentStatus.ACTIVE) {
                current = runtime.reflexDecisionRuntime.recover(resolver).getOrThrow()
                recovered = current != null
            } else if (persisted?.status == ReflexRuntimeActivationIntentStatus.DISABLED) {
                return@runCatching report(
                    stage = ReflexNativeLifecycleStage.DISABLED,
                    checkpointId = null,
                    detail = "durable Reflex runtime intent is disabled"
                )
            }
        }

        if (current == null) {
            return@runCatching trainInitialChampion()
        }
        maintainContinualLearning(
            champion = current,
            recovered = recovered
        )
    }

    private fun trainInitialChampion(): ReflexNativeLifecycleReport {
        val examples = runtime.reflexExperienceDatasets.recentExamples(MAX_SELECTED_EXAMPLES)
        val actionExamples = examples.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationExamples = examples.size - actionExamples
        if (
            actionExamples < MIN_TOTAL_ACTION_EXAMPLES ||
            escalationExamples < MIN_TOTAL_ESCALATION_EXAMPLES
        ) {
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.INSUFFICIENT_EVIDENCE,
                checkpointId = null,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail =
                    "waiting for at least $MIN_TOTAL_ACTION_EXAMPLES ACTION and " +
                        "$MIN_TOTAL_ESCALATION_EXAMPLES ESCALATE examples"
            )
        }

        ensureFoundation()
        val evidenceDigest = reflexLinearSha256(
            examples.map { it.id.value }.sorted().joinToString("|")
        )
        val tag = evidenceDigest.take(20)
        val spec = trainingSpec(
            tag = tag,
            parentCheckpointId = null,
            selectedExampleIds = null,
            continual = false
        )
        val checkpoint = trainCheckpoint(
            spec = spec,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples
        ) ?: return ReflexNativeLifecycleReport(
            stage = ReflexNativeLifecycleStage.TRAINING_FAILED,
            checkpointId = null,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples,
            detail = "concrete Reflex trainer failed"
        )

        val evaluation = runtime.nativeTrainingPipeline.getEvaluation(checkpoint.id)
            ?: runtime.reflexDecisionEvaluation.evaluate(
                checkpointId = checkpoint.id,
                holdoutShardId = spec.holdoutShardId,
                evaluator = evaluator
            )
        if (!evaluation.admission.admitted) {
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.EVALUATION_REJECTED,
                checkpointId = checkpoint.id,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail = evaluation.admission.reasons.joinToString("; ")
            )
        }

        val port = resolver.resolve(
            checkpointId = checkpoint.id,
            weightArtifactSha256 = checkpoint.weightArtifactSha256
        ).getOrThrow()
        val activation = runtime.reflexDecisionRuntime.activate(port).getOrThrow()
        lastRejectedEvidenceTag = null
        return ReflexNativeLifecycleReport(
            stage = ReflexNativeLifecycleStage.ACTIVE,
            checkpointId = activation.checkpointId,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples,
            detail = "AMPER-owned Reflex Linear V1 trained, admitted and activated"
        )
    }

    private fun maintainContinualLearning(
        champion: ReflexDecisionRuntimeActivation,
        recovered: Boolean
    ): ReflexNativeLifecycleReport {
        val consumed = consumedEvidenceIds(champion.checkpointId)
        val fresh = runtime.reflexExperienceDatasets
            .recentExamples(MAX_SELECTED_EXAMPLES)
            .filterNot { it.id in consumed }
        val actionExamples = fresh.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationExamples = fresh.size - actionExamples

        if (
            actionExamples < MIN_CONTINUAL_ACTION_EXAMPLES ||
            escalationExamples < MIN_CONTINUAL_ESCALATION_EXAMPLES
        ) {
            return ReflexNativeLifecycleReport(
                stage = if (recovered) {
                    ReflexNativeLifecycleStage.RECOVERED
                } else {
                    ReflexNativeLifecycleStage.WAITING_FOR_FRESH_EVIDENCE
                },
                checkpointId = champion.checkpointId,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail =
                    "champion active; waiting for at least $MIN_CONTINUAL_ACTION_EXAMPLES fresh " +
                        "ACTION and $MIN_CONTINUAL_ESCALATION_EXAMPLES fresh ESCALATE examples"
            )
        }

        ensureFoundation()
        val evidenceDigest = reflexLinearSha256(
            champion.checkpointId.value + "|" +
                fresh.map { it.id.value }.sorted().joinToString("|")
        )
        val tag = evidenceDigest.take(20)
        if (lastRejectedEvidenceTag == tag) {
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.CHALLENGER_REJECTED,
                checkpointId = champion.checkpointId,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail = "same fresh-evidence challenger was already rejected in this process"
            )
        }

        val spec = trainingSpec(
            tag = tag,
            parentCheckpointId = champion.checkpointId,
            selectedExampleIds = fresh.map { it.id },
            continual = true
        )
        val checkpoint = trainCheckpoint(
            spec = spec,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples
        ) ?: run {
            lastRejectedEvidenceTag = tag
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.TRAINING_FAILED,
                checkpointId = champion.checkpointId,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail = "continual Reflex challenger training failed"
            )
        }

        val candidateEvaluation = runtime.nativeTrainingPipeline.getEvaluation(checkpoint.id)
            ?: runtime.reflexDecisionEvaluation.evaluate(
                checkpointId = checkpoint.id,
                holdoutShardId = spec.holdoutShardId,
                evaluator = evaluator
            )
        if (!candidateEvaluation.admission.admitted) {
            lastRejectedEvidenceTag = tag
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.EVALUATION_REJECTED,
                checkpointId = champion.checkpointId,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail = "challenger rejected by admission: " +
                    candidateEvaluation.admission.reasons.joinToString("; ")
            )
        }

        val championCommonHoldout = runtime.reflexDecisionEvaluation.score(
            checkpointId = champion.checkpointId,
            holdoutShardId = spec.holdoutShardId,
            evaluator = evaluator
        )
        val promotion = runtime.nativeTrainingPipeline.promotionCandidateAgainstEvaluation(
            candidateCheckpointId = checkpoint.id,
            baselineCheckpointId = champion.checkpointId,
            baselineEvaluation = championCommonHoldout
        )
        if (!promotion.promotable) {
            lastRejectedEvidenceTag = tag
            return ReflexNativeLifecycleReport(
                stage = ReflexNativeLifecycleStage.CHALLENGER_REJECTED,
                checkpointId = champion.checkpointId,
                actionExamples = actionExamples,
                escalationExamples = escalationExamples,
                detail = promotion.comparison.reasons.joinToString("; ")
            )
        }

        val port = resolver.resolve(
            checkpointId = checkpoint.id,
            weightArtifactSha256 = checkpoint.weightArtifactSha256
        ).getOrThrow()
        val activation = runtime.reflexDecisionRuntime.replace(
            port = port,
            promotion = promotion
        ).getOrThrow()
        lastRejectedEvidenceTag = null
        return ReflexNativeLifecycleReport(
            stage = ReflexNativeLifecycleStage.REPLACED,
            checkpointId = activation.checkpointId,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples,
            detail =
                "fresh-evidence challenger improved the common holdout and replaced champion " +
                    champion.checkpointId.value
        )
    }

    private fun trainCheckpoint(
        spec: ReflexDecisionTrainingSpec,
        actionExamples: Int,
        escalationExamples: Int
    ): NativeCheckpointLineage? {
        runtime.nativeModelFoundation.getCheckpoint(spec.outputCheckpointId)?.let {
            return it
        }
        runtime.nativeTrainingPipeline.getRun(spec.runId)?.let { existing ->
            if (existing.status == NativeTrainingRunStatus.FAILED) {
                return null
            }
        }
        val prepared = runtime.reflexDecisionTraining.prepare(spec)
        val finished = runtime.nativeTrainingPipeline.execute(
            prepared.run.id,
            trainer
        )
        if (finished.status != NativeTrainingRunStatus.SUCCEEDED) {
            return null
        }
        return requireNotNull(
            runtime.nativeModelFoundation.getCheckpoint(spec.outputCheckpointId)
        ) {
            "successful Reflex training did not publish checkpoint lineage; " +
                "action=$actionExamples escalation=$escalationExamples"
        }
    }

    private fun trainingSpec(
        tag: String,
        parentCheckpointId: NativeCheckpointId?,
        selectedExampleIds: List<ReflexExperienceExampleId>?,
        continual: Boolean
    ): ReflexDecisionTrainingSpec {
        val prefix = if (continual) "real-reflex-continual" else "real-reflex"
        return ReflexDecisionTrainingSpec(
            trainingShardId = NativeDatasetShardId("$prefix-train-$tag"),
            holdoutShardId = NativeDatasetShardId("$prefix-holdout-$tag"),
            curriculumId = NativeCurriculumId("$prefix-curriculum-$tag"),
            manifestId = NativeDistillationManifestId("$prefix-manifest-$tag"),
            runId = NativeTrainingRunId("$prefix-run-$tag"),
            outputCheckpointId = NativeCheckpointId("$prefix-checkpoint-$tag"),
            teacherSnapshotIds = listOf(TEACHER_ID),
            studentContractId = CONTRACT_ID,
            parentCheckpointId = parentCheckpointId,
            optimizer = "sgd-softmax",
            precision = ReflexLinearNativeTrainer.QUANTIZATION,
            maxSequenceTokens = 512,
            learningRate = if (continual) CONTINUAL_LEARNING_RATE else 0.08,
            target = NativeMobileTargetProfile(
                outputFormat = ReflexLinearNativeTrainer.OUTPUT_FORMAT,
                quantization = ReflexLinearNativeTrainer.QUANTIZATION,
                maxRuntimeMemoryMb = 128,
                contextTokens = 512,
                androidArm64 = true
            ),
            teacherTemperature = 1.0,
            teacherLossWeight = 0.0,
            holdoutRatio = if (continual) CONTINUAL_HOLDOUT_RATIO else 0.25,
            minTrainingPerClass = if (continual) {
                MIN_CONTINUAL_TRAINING_PER_CLASS
            } else {
                MIN_TRAINING_PER_CLASS
            },
            minHoldoutPerClass = if (continual) {
                MIN_CONTINUAL_HOLDOUT_PER_CLASS
            } else {
                MIN_HOLDOUT_PER_CLASS
            },
            limit = MAX_SELECTED_EXAMPLES,
            selectedExampleIds = selectedExampleIds
        )
    }

    private fun consumedEvidenceIds(
        checkpointId: NativeCheckpointId
    ): Set<ReflexExperienceExampleId> {
        val consumed = linkedSetOf<ReflexExperienceExampleId>()
        val visited = linkedSetOf<NativeCheckpointId>()
        var cursor: NativeCheckpointId? = checkpointId
        while (cursor != null && visited.add(cursor)) {
            val checkpoint = requireNotNull(runtime.nativeModelFoundation.getCheckpoint(cursor)) {
                "active Reflex lineage is unavailable: " + cursor.value
            }
            checkpoint.datasetShardIds.forEach { shardId ->
                runtime.reflexExperienceDatasets.getShard(shardId)
                    ?.exampleIds
                    ?.let(consumed::addAll)
            }
            runtime.nativeTrainingPipeline.getEvaluation(cursor)
                ?.evaluation
                ?.reflexDecision
                ?.holdoutShardId
                ?.let(runtime.reflexExperienceDatasets::getShard)
                ?.exampleIds
                ?.let(consumed::addAll)
            cursor = checkpoint.parentCheckpointId
        }
        return consumed
    }

    fun resolver(): NativeReflexDecisionPortResolver = resolver

    private fun ensureFoundation() {
        val contract = canonicalContract()
        runtime.nativeModelFoundation.getContract(CONTRACT_ID)?.let { existing ->
            require(existing == contract) {
                "AMPER Reflex Linear V1 contract identity changed"
            }
        } ?: runtime.nativeModelFoundation.putContract(contract)

        val teacher = canonicalTeacher()
        runtime.nativeTrainingPipeline.getTeacher(TEACHER_ID)?.let { existing ->
            require(existing == teacher) {
                "AMPER Reflex governed-label teacher identity changed"
            }
        } ?: runtime.nativeTrainingPipeline.putTeacher(teacher)
    }

    private fun canonicalContract(): AmperNativeModelContract =
        AmperNativeModelContract(
            id = CONTRACT_ID,
            familyVersion = 1,
            parameterCount = ReflexLinearModel.PARAMETER_COUNT.toLong(),
            layerCount = 1,
            hiddenSize = ReflexLinearModel.FEATURE_DIMENSION,
            maxContextTokens = 512,
            capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            ownedByAmper = true
        )

    private fun canonicalTeacher(): NativeTeacherSnapshot =
        NativeTeacherSnapshot(
            id = TEACHER_ID,
            modelId = ModelId("amper-governed-reflex-labels-v1"),
            artifactSha256 = GOVERNED_LABEL_TEACHER_SHA256,
            sourceLabel = "AMPER governed assistant outcome labels v1",
            rights = NativeTeacherRights.INTERNAL,
            capabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            local = true,
            createdAtEpochMs = 1L
        )

    private fun report(
        stage: ReflexNativeLifecycleStage,
        checkpointId: NativeCheckpointId?,
        detail: String
    ): ReflexNativeLifecycleReport {
        val examples = runtime.reflexExperienceDatasets.recentExamples(MAX_SELECTED_EXAMPLES)
        val actionExamples = examples.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        return ReflexNativeLifecycleReport(
            stage = stage,
            checkpointId = checkpointId,
            actionExamples = actionExamples,
            escalationExamples = examples.size - actionExamples,
            detail = detail
        )
    }

    companion object {
        val CONTRACT_ID = NativeModelContractId("amper-reflex-linear-v1")
        val TEACHER_ID = NativeTeacherSnapshotId("amper-governed-reflex-labels-v1")
        val GOVERNED_LABEL_TEACHER_SHA256 = reflexLinearSha256(
            "AMPER_GOVERNED_REFLEX_LABEL_TEACHER_V1"
        )

        const val MIN_TOTAL_ACTION_EXAMPLES = 32
        const val MIN_TOTAL_ESCALATION_EXAMPLES = 32
        const val MIN_TRAINING_PER_CLASS = 16
        const val MIN_HOLDOUT_PER_CLASS = 16

        const val MIN_CONTINUAL_ACTION_EXAMPLES = 24
        const val MIN_CONTINUAL_ESCALATION_EXAMPLES = 24
        const val MIN_CONTINUAL_TRAINING_PER_CLASS = 8
        const val MIN_CONTINUAL_HOLDOUT_PER_CLASS = 16
        const val MAX_SELECTED_EXAMPLES = 256
        const val CONTINUAL_HOLDOUT_RATIO = 0.35
        const val CONTINUAL_LEARNING_RATE = 0.03
    }
}
