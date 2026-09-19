package io.amper.neuroos.core

data class AgiMobileLongHorizonProbePack(
    val longHorizonExecution: AgiMobileQualificationProbe,
    val memoryWorldModel: AgiMobileQualificationProbe
) {
    val probes: List<AgiMobileQualificationProbe>
        get() = listOf(longHorizonExecution, memoryWorldModel)
}

/**
 * Phase561-565 qualification probes for bounded long-horizon execution and memory/world-model
 * continuity. Workloads are synthetic and execute only in-memory read-only providers. They exercise
 * real SovereignActionLoop binding, persistent plan encoding/receipts, MemoryOs retrieval,
 * epistemic->semantic consolidation and PredictiveWorldModel transition/prediction reconciliation.
 */
object AgiMobileLongHorizonQualificationProbes {
    const val LONG_HORIZON_SAMPLE_COUNT = 16
    const val MEMORY_WORLD_SAMPLE_COUNT = 32
    private const val PLAN_STEPS = TitanPlanProtocol.MAX_STEPS

    fun canonical(
        subject: AgiMobileQualificationSubject
    ): AgiMobileLongHorizonProbePack =
        AgiMobileLongHorizonProbePack(
            longHorizonExecution = longHorizonProbe(subject),
            memoryWorldModel = memoryWorldProbe(subject)
        )

    fun longHorizonProbe(
        subject: AgiMobileQualificationSubject
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "long-horizon-execution",
            domain = AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION
        ) {
            runCatching {
                val sampleResults = (0 until LONG_HORIZON_SAMPLE_COUNT).map { sample ->
                    runLongHorizonSample(sample)
                }
                val passed = sampleResults.count { it.passed }
                val score = passed.toDouble() / sampleResults.size.toDouble()
                val assertions = listOf(
                    sampleResults.all { it.receiptCount == PLAN_STEPS },
                    sampleResults.all { it.auditCount == PLAN_STEPS },
                    sampleResults.all { it.executedOrder == (1..PLAN_STEPS).toList() },
                    sampleResults.all { it.reloadedMidPlan },
                    sampleResults.all { it.finalComplete },
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION
                        }.minScore
                )
                val material = buildString {
                    append("LONG_HORIZON_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(passed).append('/').append(sampleResults.size).append('|')
                    sampleResults.forEachIndexed { index, result ->
                        append(index).append(':')
                        append(if (result.passed) '1' else '0').append(':')
                        append(result.receiptCount).append(':')
                        append(result.auditCount).append(':')
                        append(if (result.reloadedMidPlan) '1' else '0').append(':')
                        append(if (result.finalComplete) '1' else '0').append(';')
                    }
                }
                AgiMobileQualificationEvidence(
                    probeId = "long-horizon-execution",
                    domain = AgiMobileQualificationDomain.LONG_HORIZON_EXECUTION,
                    score = score,
                    samples = sampleResults.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    fun memoryWorldProbe(
        subject: AgiMobileQualificationSubject
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "memory-world-model",
            domain = AgiMobileQualificationDomain.MEMORY_WORLD_MODEL
        ) {
            runCatching {
                val sampleResults = (0 until MEMORY_WORLD_SAMPLE_COUNT).map { sample ->
                    runMemoryWorldSample(sample)
                }
                val passed = sampleResults.count { it.passed }
                val score = passed.toDouble() / sampleResults.size.toDouble()
                val assertions = listOf(
                    sampleResults.all { it.episodicRetrieved },
                    sampleResults.all { it.semanticActive },
                    sampleResults.all { it.semanticEvidenceParents >= 2 },
                    sampleResults.all { it.transitionCount >= 4 },
                    sampleResults.all { it.predictionBasis == WorldPredictionBasis.TEMPORAL_TRANSITION },
                    sampleResults.all { it.predictionConfirmed },
                    sampleResults.all { it.currentStateMatches },
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.MEMORY_WORLD_MODEL
                        }.minScore
                )
                val material = buildString {
                    append("MEMORY_WORLD_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(passed).append('/').append(sampleResults.size).append('|')
                    sampleResults.forEachIndexed { index, result ->
                        append(index).append(':')
                        append(if (result.passed) '1' else '0').append(':')
                        append(if (result.episodicRetrieved) '1' else '0').append(':')
                        append(if (result.semanticActive) '1' else '0').append(':')
                        append(result.semanticEvidenceParents).append(':')
                        append(result.transitionCount).append(':')
                        append(result.predictionBasis.name).append(':')
                        append(if (result.predictionConfirmed) '1' else '0').append(';')
                    }
                }
                AgiMobileQualificationEvidence(
                    probeId = "memory-world-model",
                    domain = AgiMobileQualificationDomain.MEMORY_WORLD_MODEL,
                    score = score,
                    samples = sampleResults.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    private fun runLongHorizonSample(sample: Int): LongHorizonSampleResult {
        val memory = InMemoryMemoryOs()
        var store: SovereignPlanStore = MemoryBackedSovereignPlanStore(memory)
        val registry = InMemoryToolRegistry()
        val calls = mutableListOf<Int>()
        val capabilities = (1..PLAN_STEPS).map { step ->
            CapabilityId("qualification.long.$sample.$step")
        }
        capabilities.forEachIndexed { zeroIndex, capability ->
            val step = zeroIndex + 1
            registry.register(
                object : ToolProvider {
                    override val descriptor = ToolDescriptor(
                        id = ToolId("qualification-long-$sample-$step"),
                        name = "qualification long-horizon step $step",
                        capability = capability,
                        sideEffect = ToolSideEffect.READ_ONLY,
                        inputContract = ToolInputContract(
                            description = "synthetic read-only qualification step",
                            acceptedValues = setOf("step-$step"),
                            maxLength = 16
                        )
                    )

                    override fun execute(input: String): Result<String> = runCatching {
                        require(input == "step-$step")
                        calls += step
                        "ok-$sample-$step"
                    }
                }
            )
        }
        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = object : AuthorityGate {
                override fun authorize(
                    capability: CapabilityId,
                    reason: String
                ): Boolean = capability in capabilities
            },
            registry = registry,
            audit = audit
        )
        val actionRuntime = AmperRuntime.reference()
        val actions = actionRuntime.actionLoop(
            registry = registry,
            fabric = fabric,
            policy = SovereignActionPolicy(autoExecuteReadOnly = true)
        )

        var plan = SovereignPlan(
            id = PlanId("qualification-long-plan-$sample"),
            conversationId = ConversationId("qualification-long-conversation-$sample"),
            goal = "execute four bounded read-only qualification steps $sample",
            steps = capabilities.mapIndexed { zeroIndex, capability ->
                val step = zeroIndex + 1
                val descriptor = requireNotNull(registry.route(capability)).descriptor
                SovereignPlanStep(
                    index = step,
                    requestId = ActionRequestId("qualification-long-$sample-request-$step"),
                    capability = capability,
                    reason = "qualification long-horizon governed step $step",
                    input = "step-$step",
                    boundToolId = descriptor.id,
                    boundSideEffect = descriptor.sideEffect
                )
            },
            planningBackendId = "qualification-long-horizon",
            createdAtEpochMs = 10_000L + sample
        )
        store.save(plan)
        var reloadedMidPlan = false

        for (stepIndex in 1..PLAN_STEPS) {
            plan = requireNotNull(store.load(plan.id))
            requireNotNull(store.receipts).verifyCompletedPrefix(plan).getOrThrow()
            val step = plan.steps.single { it.index == stepIndex }
            val outcome = actions.evaluateBound(
                proposal = step.proposal(),
                expectedToolId = requireNotNull(step.boundToolId),
                expectedSideEffect = requireNotNull(step.boundSideEffect)
            )
            val updatedStep = step.copy(
                status = when (outcome.status) {
                    ActionStatus.EXECUTED -> PlanStepStatus.EXECUTED
                    ActionStatus.DENIED -> PlanStepStatus.DENIED
                    ActionStatus.FAILED -> PlanStepStatus.FAILED
                    ActionStatus.MALFORMED -> PlanStepStatus.MALFORMED
                    ActionStatus.UNAVAILABLE -> PlanStepStatus.UNAVAILABLE
                    ActionStatus.REQUIRES_CONFIRMATION ->
                        PlanStepStatus.REQUIRES_CONFIRMATION
                    ActionStatus.NO_ACTION -> PlanStepStatus.FAILED
                },
                outcome = outcome
            )
            plan = plan.copy(
                steps = plan.steps.map {
                    if (it.index == stepIndex) updatedStep else it
                }
            )
            requireNotNull(store.receipts)
                .recordTerminal(plan, updatedStep)
                .getOrThrow()
            store.save(plan)

            if (stepIndex == 2) {
                store = MemoryBackedSovereignPlanStore(memory)
                val restored = requireNotNull(store.load(plan.id))
                reloadedMidPlan =
                    restored.steps.take(2).all { it.status == PlanStepStatus.EXECUTED } &&
                        restored.steps.drop(2).all { it.status == PlanStepStatus.PLANNED }
                plan = restored
            }
        }

        val restored = requireNotNull(store.load(plan.id))
        requireNotNull(store.receipts).verifyCompletedPrefix(restored).getOrThrow()
        val receipts = restored.steps.count {
            store.receipts?.receipt(restored.id, it.requestId) != null
        }
        val finalComplete = restored.complete &&
            restored.steps.all { it.status == PlanStepStatus.EXECUTED }
        val passed =
            finalComplete &&
                receipts == PLAN_STEPS &&
                audit.snapshot().size == PLAN_STEPS &&
                calls == (1..PLAN_STEPS).toList() &&
                reloadedMidPlan

        return LongHorizonSampleResult(
            passed = passed,
            receiptCount = receipts,
            auditCount = audit.snapshot().size,
            executedOrder = calls.toList(),
            reloadedMidPlan = reloadedMidPlan,
            finalComplete = finalComplete
        )
    }

    private fun runMemoryWorldSample(sample: Int): MemoryWorldSampleResult {
        val memory = InMemoryMemoryOs()
        val episodicToken = "qualification-episode-$sample"
        val episodicRecord = MemoryRecord(
            id = MemoryId("qualification-episode-id-$sample"),
            kind = "qualification-episodic",
            content = "$episodicToken bounded episodic memory",
            importance = 0.70,
            provenance = Provenance(
                source = "qualification",
                producer = "memory-world-probe",
                observedAtEpochMs = 100L + sample,
                confidence = 1.0
            ),
            createdAtEpochMs = 100L + sample
        )
        memory.remember(episodicRecord)
        val episodicRetrieved = memory.recall(episodicToken, 4)
            .any { it.id == episodicRecord.id }

        var now = 2_000L
        val epistemic = MemoryBackedEpistemicState(
            memory = memory,
            clock = { now },
            staleAfterMs = 20_000L
        )
        val subject = "qualification-entity-$sample"
        val predicate = "mode"
        val evidenceA = epistemic.observe(
            epistemicClaim(
                subject = subject,
                predicate = predicate,
                value = "mobile",
                observedAt = 1_000L,
                producer = "qualification-sensor-a-$sample"
            )
        )
        val evidenceB = epistemic.observe(
            epistemicClaim(
                subject = subject,
                predicate = predicate,
                value = "mobile",
                observedAt = 1_100L,
                producer = "qualification-sensor-b-$sample"
            )
        )
        val semantic = MemoryBackedSemanticKnowledgeStore(
            memory = memory,
            epistemic = epistemic,
            clock = { now }
        )
        val transition = semantic.consolidate(subject, predicate)
        val active = transition.current
        val semanticQuery = semantic.query("$subject $predicate", 4)
        val semanticActive =
            active?.status == SemanticKnowledgeStatus.ACTIVE &&
                active.value == "mobile" &&
                semanticQuery.any { it.id == active.id }
        val semanticParents = active?.let { entry ->
            memory.get(entry.id)?.provenance?.parents?.size ?: 0
        } ?: 0
        require(active?.evidenceIds?.toSet() == setOf(evidenceA, evidenceB))

        val worldKey = WorldStateKey("qualification-workload-$sample", "state")
        now = 550L
        val world = MemoryBackedPredictiveWorldModel(
            memory = memory,
            clock = { now }
        )
        world.observe(worldObservation(worldKey, "idle", 100L, "$sample-s1"))
        world.observe(worldObservation(worldKey, "busy", 200L, "$sample-s2"))
        world.observe(worldObservation(worldKey, "idle", 300L, "$sample-s3"))
        world.observe(worldObservation(worldKey, "busy", 400L, "$sample-s4"))
        world.observe(worldObservation(worldKey, "idle", 500L, "$sample-s5"))
        val prediction = requireNotNull(
            world.predict(worldKey, horizonMs = 1_000L)
        )
        now = 600L
        val actual = world.observe(
            worldObservation(worldKey, "busy", 600L, "$sample-s6")
        )
        val outcome = world.predictionOutcomes(16)
            .firstOrNull { it.predictionId == prediction.id }
        val transitionCount = world.transitions(worldKey, 16).size
        val current = world.current(worldKey)
        val currentStateMatches =
            current?.id == actual.id &&
                current.value == "busy" &&
                current.status == StructuredWorldStateStatus.KNOWN
        val predictionConfirmed =
            prediction.predictedValue == "busy" &&
                outcome?.status == WorldPredictionOutcomeStatus.CONFIRMED &&
                outcome.actualValue == "busy"

        val passed =
            episodicRetrieved &&
                semanticActive &&
                semanticParents >= 2 &&
                transitionCount >= 4 &&
                prediction.basis == WorldPredictionBasis.TEMPORAL_TRANSITION &&
                predictionConfirmed &&
                currentStateMatches

        return MemoryWorldSampleResult(
            passed = passed,
            episodicRetrieved = episodicRetrieved,
            semanticActive = semanticActive,
            semanticEvidenceParents = semanticParents,
            transitionCount = transitionCount,
            predictionBasis = prediction.basis,
            predictionConfirmed = predictionConfirmed,
            currentStateMatches = currentStateMatches
        )
    }

    private fun epistemicClaim(
        subject: String,
        predicate: String,
        value: String,
        observedAt: Long,
        producer: String
    ): EpistemicClaim =
        EpistemicClaim(
            subject = subject,
            predicate = predicate,
            value = value,
            confidence = 0.95,
            provenance = Provenance(
                source = "qualification-memory-world",
                producer = producer,
                observedAtEpochMs = observedAt,
                confidence = 0.95
            )
        )

    private fun worldObservation(
        key: WorldStateKey,
        value: String,
        observedAt: Long,
        evidence: String
    ): WorldStateObservation =
        WorldStateObservation(
            key = key,
            value = value,
            confidence = 0.90,
            evidenceIds = listOf(MemoryId(evidence)),
            observedAtEpochMs = observedAt
        )

    private data class LongHorizonSampleResult(
        val passed: Boolean,
        val receiptCount: Int,
        val auditCount: Int,
        val executedOrder: List<Int>,
        val reloadedMidPlan: Boolean,
        val finalComplete: Boolean
    )

    private data class MemoryWorldSampleResult(
        val passed: Boolean,
        val episodicRetrieved: Boolean,
        val semanticActive: Boolean,
        val semanticEvidenceParents: Int,
        val transitionCount: Int,
        val predictionBasis: WorldPredictionBasis,
        val predictionConfirmed: Boolean,
        val currentStateMatches: Boolean
    )
}
