package io.amper.neuroos.core

enum class ReflexNativeLifecycleStage {
    INSUFFICIENT_EVIDENCE,
    DISABLED,
    TRAINING_FAILED,
    EVALUATION_REJECTED,
    RECOVERED,
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
    artifacts: ReflexLinearArtifactStore,
    private val clock: () -> Long = System::currentTimeMillis
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

    @Synchronized
    fun maintain(): Result<ReflexNativeLifecycleReport> = runCatching {
        runtime.reflexDecisionRuntime.active()?.let { active ->
            return@runCatching report(
                stage = ReflexNativeLifecycleStage.ACTIVE,
                checkpointId = active.checkpointId,
                detail = "learned Reflex checkpoint already active"
            )
        }

        val persisted = runtime.reflexDecisionRuntime.persistedIntent()
        if (persisted?.status == ReflexRuntimeActivationIntentStatus.ACTIVE) {
            val recovered = runtime.reflexDecisionRuntime.recover(resolver).getOrThrow()
            return@runCatching report(
                stage = ReflexNativeLifecycleStage.RECOVERED,
                checkpointId = recovered?.checkpointId,
                detail = "learned Reflex checkpoint recovered and revalidated"
            )
        }
        if (persisted?.status == ReflexRuntimeActivationIntentStatus.DISABLED) {
            return@runCatching report(
                stage = ReflexNativeLifecycleStage.DISABLED,
                checkpointId = null,
                detail = "durable Reflex runtime intent is disabled"
            )
        }

        val examples = runtime.reflexExperienceDatasets.recentExamples(MAX_SELECTED_EXAMPLES)
        val actionExamples = examples.count {
            it.targetDisposition == ReflexDecisionDisposition.PROPOSE_ACTION
        }
        val escalationExamples = examples.size - actionExamples
        if (
            actionExamples < MIN_TOTAL_ACTION_EXAMPLES ||
            escalationExamples < MIN_TOTAL_ESCALATION_EXAMPLES
        ) {
            return@runCatching ReflexNativeLifecycleReport(
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
        val spec = ReflexDecisionTrainingSpec(
            trainingShardId = NativeDatasetShardId("real-reflex-train-$tag"),
            holdoutShardId = NativeDatasetShardId("real-reflex-holdout-$tag"),
            curriculumId = NativeCurriculumId("real-reflex-curriculum-$tag"),
            manifestId = NativeDistillationManifestId("real-reflex-manifest-$tag"),
            runId = NativeTrainingRunId("real-reflex-run-$tag"),
            outputCheckpointId = NativeCheckpointId("real-reflex-checkpoint-$tag"),
            teacherSnapshotIds = listOf(TEACHER_ID),
            studentContractId = CONTRACT_ID,
            optimizer = "sgd-softmax",
            precision = ReflexLinearNativeTrainer.QUANTIZATION,
            maxSequenceTokens = 512,
            learningRate = 0.08,
            target = NativeMobileTargetProfile(
                outputFormat = ReflexLinearNativeTrainer.OUTPUT_FORMAT,
                quantization = ReflexLinearNativeTrainer.QUANTIZATION,
                maxRuntimeMemoryMb = 128,
                contextTokens = 512,
                androidArm64 = true
            ),
            teacherTemperature = 1.0,
            teacherLossWeight = 0.0,
            holdoutRatio = 0.25,
            minTrainingPerClass = MIN_TRAINING_PER_CLASS,
            minHoldoutPerClass = MIN_HOLDOUT_PER_CLASS,
            limit = MAX_SELECTED_EXAMPLES
        )

        var checkpoint = runtime.nativeModelFoundation.getCheckpoint(spec.outputCheckpointId)
        if (checkpoint == null) {
            runtime.nativeTrainingPipeline.getRun(spec.runId)?.let { existing ->
                if (existing.status == NativeTrainingRunStatus.FAILED) {
                    return@runCatching ReflexNativeLifecycleReport(
                        stage = ReflexNativeLifecycleStage.TRAINING_FAILED,
                        checkpointId = null,
                        actionExamples = actionExamples,
                        escalationExamples = escalationExamples,
                        detail = "previous concrete Reflex training attempt failed: " +
                            (existing.failureCode ?: "unknown")
                    )
                }
            }
            val prepared = runtime.reflexDecisionTraining.prepare(spec)
            val finished = runtime.nativeTrainingPipeline.execute(
                prepared.run.id,
                trainer
            )
            if (finished.status != NativeTrainingRunStatus.SUCCEEDED) {
                return@runCatching ReflexNativeLifecycleReport(
                    stage = ReflexNativeLifecycleStage.TRAINING_FAILED,
                    checkpointId = null,
                    actionExamples = actionExamples,
                    escalationExamples = escalationExamples,
                    detail = "concrete Reflex trainer failed: " +
                        (finished.failureCode ?: "unknown")
                )
            }
            checkpoint = requireNotNull(
                runtime.nativeModelFoundation.getCheckpoint(spec.outputCheckpointId)
            ) {
                "successful Reflex training did not publish checkpoint lineage"
            }
        }

        val evaluation = runtime.nativeTrainingPipeline.getEvaluation(checkpoint.id)
            ?: runtime.reflexDecisionEvaluation.evaluate(
                checkpointId = checkpoint.id,
                holdoutShardId = spec.holdoutShardId,
                evaluator = evaluator
            )
        if (!evaluation.admission.admitted) {
            return@runCatching ReflexNativeLifecycleReport(
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
        ReflexNativeLifecycleReport(
            stage = ReflexNativeLifecycleStage.ACTIVE,
            checkpointId = activation.checkpointId,
            actionExamples = actionExamples,
            escalationExamples = escalationExamples,
            detail = "AMPER-owned Reflex Linear V1 trained, admitted and activated"
        )
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
        const val MAX_SELECTED_EXAMPLES = 256
    }
}
