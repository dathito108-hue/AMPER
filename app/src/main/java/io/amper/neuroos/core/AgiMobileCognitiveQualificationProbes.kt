package io.amper.neuroos.core

import java.util.Locale

data class AgiMobileCognitiveProbePack(
    val system1: AgiMobileQualificationProbe,
    val system2: AgiMobileQualificationProbe,
    val generalization: AgiMobileQualificationProbe
) {
    val probes: List<AgiMobileQualificationProbe>
        get() = listOf(system1, system2, generalization)
}

/**
 * Canonical cognitive qualification probes for Phase556-560.
 *
 * The workloads are synthetic and contain no user data, but they execute AMPER's real deterministic
 * Reflex router, NativeSystem2Core and MemoryBackedSkillGeneralizationModel. Evidence is bound to the
 * exact subject revision/artifact supplied by the qualification host.
 */
object AgiMobileCognitiveQualificationProbes {
    const val SYSTEM1_SAMPLE_COUNT = 64
    const val SYSTEM2_SAMPLE_COUNT = 32
    const val GENERALIZATION_SAMPLE_COUNT = 32
    const val SYSTEM1_P95_BUDGET_NS = 50_000_000L

    fun canonical(
        subject: AgiMobileQualificationSubject,
        reflex: ReflexDecisionCortex = DeterministicReflexDecisionCortex,
        monotonicNanos: () -> Long = System::nanoTime
    ): AgiMobileCognitiveProbePack {
        val descriptors = canonicalDescriptors()
        return AgiMobileCognitiveProbePack(
            system1 = system1Probe(subject, reflex, descriptors, monotonicNanos),
            system2 = system2Probe(subject, descriptors),
            generalization = generalizationProbe(
                subject,
                descriptors.first {
                    it.capability == AndroidWebSearchToolContract.capability
                }
            )
        )
    }

    fun system1Probe(
        subject: AgiMobileQualificationSubject,
        reflex: ReflexDecisionCortex,
        descriptors: List<ToolDescriptor>,
        monotonicNanos: () -> Long = System::nanoTime
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "system1-fast-path",
            domain = AgiMobileQualificationDomain.SYSTEM1_FAST_PATH
        ) {
            runCatching {
                val cases = system1Cases()
                require(cases.size == SYSTEM1_SAMPLE_COUNT)
                val observations = cases.map { case ->
                    val start = monotonicNanos()
                    val decision = reflex.decide(
                        ReflexDecisionRequest(case.input, descriptors)
                    )
                    val elapsed = (monotonicNanos() - start).coerceAtLeast(0L)
                    val correct = if (case.expectedCapability == null) {
                        decision.disposition == ReflexDecisionDisposition.ESCALATE_SYSTEM2
                    } else {
                        decision.disposition == ReflexDecisionDisposition.PROPOSE_ACTION &&
                            decision.capability == case.expectedCapability &&
                            decision.fastPathEligible &&
                            !decision.authorityBearing
                    }
                    System1Observation(
                        correct = correct,
                        elapsedNanos = elapsed,
                        disposition = decision.disposition,
                        capability = decision.capability
                    )
                }
                val correct = observations.count { it.correct }
                val p95 = percentile95(observations.map { it.elapsedNanos })
                val score = correct.toDouble() / observations.size.toDouble()
                val assertions = listOf(
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.SYSTEM1_FAST_PATH
                        }.minScore,
                    p95 <= SYSTEM1_P95_BUDGET_NS,
                    observations.all { observation ->
                        observation.capability == null ||
                            descriptors.any {
                                it.capability == observation.capability
                            }
                    }
                )
                val material = buildString {
                    append("SYSTEM1_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(correct).append('/').append(observations.size).append('|')
                    append(p95).append('|')
                    observations.forEachIndexed { index, observation ->
                        append(index).append(':')
                        append(if (observation.correct) '1' else '0').append(':')
                        append(observation.disposition.name).append(':')
                        append(observation.capability?.value ?: "~").append(';')
                    }
                }
                AgiMobileQualificationEvidence(
                    probeId = "system1-fast-path",
                    domain = AgiMobileQualificationDomain.SYSTEM1_FAST_PATH,
                    score = score,
                    samples = observations.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    fun system2Probe(
        subject: AgiMobileQualificationSubject,
        descriptors: List<ToolDescriptor>
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "native-system2-reasoning",
            domain = AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING
        ) {
            runCatching {
                val scenarios = system2Scenarios()
                require(scenarios.size == SYSTEM2_SAMPLE_COUNT)
                val observations = scenarios.mapIndexed { index, scenario ->
                    val source = object : IntegratedCognitiveStateSource {
                        override fun capture(
                            query: String,
                            allowedCapabilities: Set<CapabilityId>,
                            descriptors: Collection<ToolDescriptor>
                        ): IntegratedCognitiveStatePacket =
                            scenario.packet.copy(
                                queryDigest = nativeSystem2Sha256(
                                    normalizeQualificationGoal(query)
                                )
                            )
                    }
                    val core = CanonicalNativeSystem2Core(
                        stateSource = source,
                        workingStateStore =
                            MemoryBackedNativeSystem2WorkingStateStore(InMemoryMemoryOs()),
                        clock = { 10_000L + index }
                    )
                    val deliberation = core.deliberate(
                        goal = scenario.goal,
                        allowedCapabilities = descriptors.map { it.capability }.toSet(),
                        descriptors = descriptors
                    ).getOrThrow()
                    val pass =
                        deliberation.mode == scenario.expectedMode &&
                            deliberation.agenda.isNotEmpty() &&
                            deliberation.agenda.last().kind ==
                                NativeSystem2ReasoningTaskKind.VERIFY_PLAN &&
                            deliberation.workingState.canonicalDigest.length == 64 &&
                            deliberation.selectedStrategy.capabilities.all { capability ->
                                descriptors.any { it.capability == capability }
                            } &&
                            !deliberation.authorityBearing &&
                            !deliberation.selectedStrategy.authorityBearing
                    System2Observation(
                        pass = pass,
                        mode = deliberation.mode,
                        reasoningDepth = deliberation.workingState.maxReasoningDepth,
                        selectedSource = deliberation.selectedStrategy.source
                    )
                }
                val passed = observations.count { it.pass }
                val score = passed.toDouble() / observations.size.toDouble()
                val assertions = listOf(
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING
                        }.minScore,
                    observations.any { it.mode == NativeSystem2Mode.GATHER_EVIDENCE },
                    observations.any { it.mode == NativeSystem2Mode.REUSE_GOVERNED_STRATEGY },
                    observations.any { it.mode == NativeSystem2Mode.DELIBERATE }
                )
                val material = buildString {
                    append("SYSTEM2_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(passed).append('/').append(observations.size).append('|')
                    observations.forEachIndexed { index, observation ->
                        append(index).append(':')
                        append(if (observation.pass) '1' else '0').append(':')
                        append(observation.mode.name).append(':')
                        append(observation.reasoningDepth).append(':')
                        append(observation.selectedSource.name).append(';')
                    }
                }
                AgiMobileQualificationEvidence(
                    probeId = "native-system2-reasoning",
                    domain = AgiMobileQualificationDomain.NATIVE_SYSTEM2_REASONING,
                    score = score,
                    samples = observations.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    fun generalizationProbe(
        subject: AgiMobileQualificationSubject,
        descriptor: ToolDescriptor
    ): AgiMobileQualificationProbe =
        NamedAgiMobileQualificationProbe(
            id = "cross-context-generalization",
            domain = AgiMobileQualificationDomain.GENERALIZATION
        ) {
            runCatching {
                val capability = descriptor.capability
                val skill = SkillContract(
                    id = SkillId("qualification-web-search"),
                    signature = StrategySignature(listOf(capability)),
                    preconditions = emptyList(),
                    effects = emptyList(),
                    successes = 8,
                    executionFailures = 0,
                    maturity = SkillMaturity.ACTIVE,
                    confidence = 0.92,
                    lastObservedAtEpochMs = 1_000L
                )
                val memory = InMemoryMemoryOs()
                val generalization = MemoryBackedSkillGeneralizationModel(
                    memory = memory,
                    skills = QualificationSkillGenesisModel(skill),
                    clock = { 2_000L }
                )
                listOf(
                    "research local weather",
                    "find release documentation",
                    "look up API reference",
                    "search recent technical notes"
                ).forEachIndexed { index, goal ->
                    generalization.observe(
                        completedSingleStepPlan(
                            id = "qualification-generalization-train-$index",
                            goal = goal,
                            descriptor = descriptor
                        ),
                        skill
                    )
                }
                val profile = requireNotNull(
                    generalization.snapshot(skill.signature)
                )
                val heldout = (0 until GENERALIZATION_SAMPLE_COUNT).map { index ->
                    val guidance = generalization.guidance(
                        goal = "novel cross context search objective $index",
                        allowedCapabilities = setOf(capability),
                        descriptors = listOf(descriptor),
                        worldStates = emptyList(),
                        limit = 4
                    )
                    val match = guidance.firstOrNull { it.skill.id == skill.id }
                    match != null &&
                        match.novelContext &&
                        match.transferConfidence > 0.0 &&
                        !match.profile.authorityBearing &&
                        !match.skill.authorityBearing
                }
                val blockedWithoutDescriptor = generalization.guidance(
                    goal = "novel missing descriptor context",
                    allowedCapabilities = setOf(capability),
                    descriptors = emptyList(),
                    worldStates = emptyList(),
                    limit = 4
                ).isEmpty()
                val passed = heldout.count { it }
                val score = passed.toDouble() / heldout.size.toDouble()
                val assertions = listOf(
                    profile.maturity == SkillGeneralizationMaturity.GENERALIZED,
                    profile.successfulContextCount >= 3,
                    blockedWithoutDescriptor,
                    score >= AgiMobileQualificationSuite.canonical()
                        .criteria.single {
                            it.domain == AgiMobileQualificationDomain.GENERALIZATION
                        }.minScore,
                    !profile.authorityBearing
                )
                val material = buildString {
                    append("GENERALIZATION_V1|")
                    append(subject.canonicalDigest).append('|')
                    append(profile.maturity.name).append('|')
                    append(profile.successfulContextCount).append('|')
                    append(passed).append('/').append(heldout.size).append('|')
                    append(if (blockedWithoutDescriptor) '1' else '0')
                }
                AgiMobileQualificationEvidence(
                    probeId = "cross-context-generalization",
                    domain = AgiMobileQualificationDomain.GENERALIZATION,
                    score = score,
                    samples = heldout.size,
                    assertionsPassed = assertions.count { it },
                    assertionsTotal = assertions.size,
                    subjectDigest = subject.canonicalDigest,
                    sourceEvidenceDigest = agiQualificationSha256(material),
                    observedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0L)
                )
            }
        }

    private fun canonicalDescriptors(): List<ToolDescriptor> =
        listOf(
            qualificationDescriptor(
                AndroidWebSearchToolContract.toolId,
                AndroidWebSearchToolContract.capability,
                "Web search"
            ),
            qualificationDescriptor(
                AndroidClipboardWriteToolContract.toolId,
                AndroidClipboardWriteToolContract.capability,
                "Clipboard write"
            ),
            qualificationDescriptor(
                AndroidFilesBrowseToolContract.toolId,
                AndroidFilesBrowseToolContract.capability,
                "Files browse"
            ),
            qualificationDescriptor(
                AndroidAlarmPrepareToolContract.toolId,
                AndroidAlarmPrepareToolContract.capability,
                "Alarm prepare"
            ),
            qualificationDescriptor(
                AndroidMediaOpenToolContract.toolId,
                AndroidMediaOpenToolContract.capability,
                "Media open"
            ),
            qualificationDescriptor(
                AndroidAppLaunchToolContract.toolId,
                AndroidAppLaunchToolContract.capability,
                "App launch"
            ),
            qualificationDescriptor(
                AndroidHomeOpenToolContract.toolId,
                AndroidHomeOpenToolContract.capability,
                "Home"
            ),
            qualificationDescriptor(
                DeviceStatusToolContract.toolId,
                DeviceStatusToolContract.capability,
                "Device status"
            )
        )

    private fun qualificationDescriptor(
        id: ToolId,
        capability: CapabilityId,
        name: String
    ): ToolDescriptor =
        ToolDescriptor(
            id = id,
            name = name,
            capability = capability,
            sideEffect = ToolSideEffect.READ_ONLY,
            inputContract = ToolInputContract(
                description = "qualification descriptor",
                maxLength = 4096
            )
        )

    private fun system1Cases(): List<System1Case> = buildList {
        repeat(8) { index ->
            add(System1Case(
                "search web for amper qualification $index",
                AndroidWebSearchToolContract.capability
            ))
            add(System1Case(
                "copy: qualification text $index",
                AndroidClipboardWriteToolContract.capability
            ))
            add(System1Case(
                if (index % 2 == 0) "open files" else "mo tep",
                AndroidFilesBrowseToolContract.capability
            ))
            add(System1Case(
                "set alarm ${"%02d".format((6 + index) % 24)}:30",
                AndroidAlarmPrepareToolContract.capability
            ))
            add(System1Case(
                "media: https://example.com/qualification/$index",
                AndroidMediaOpenToolContract.capability
            ))
            add(System1Case(
                "open app com.example.qualification$index",
                AndroidAppLaunchToolContract.capability
            ))
        }
        repeat(16) { index ->
            add(System1Case(
                "analyze ambiguous multi-step objective $index and decide the best strategy",
                null
            ))
        }
    }

    private fun system2Scenarios(): List<System2Scenario> = buildList {
        repeat(12) { index ->
            add(
                System2Scenario(
                    goal = "resolve uncertain qualification state $index",
                    expectedMode = NativeSystem2Mode.GATHER_EVIDENCE,
                    packet = qualificationPacket(
                        readiness = IntegratedCognitiveReadiness(
                            epistemicConfidence = 0.40,
                            worldConfidence = 0.15,
                            skillConfidence = 0.40,
                            competenceConfidence = 0.50,
                            uncertainty = 0.78,
                            learningPressure = 0.25,
                            overallReadiness = 0.30
                        ),
                        worldStates = listOf(
                            StructuredWorldState(
                                id = MemoryId("qualification-world-unknown-$index"),
                                key = WorldStateKey("qualification", "unknown-$index"),
                                value = null,
                                confidence = 0.0,
                                status = StructuredWorldStateStatus.UNKNOWN,
                                evidenceIds = listOf(
                                    MemoryId("qualification-evidence-$index")
                                ),
                                observedAtEpochMs = 1_000L + index
                            )
                        )
                    )
                )
            )
        }
        repeat(10) { index ->
            val capability = AndroidWebSearchToolContract.capability
            val skill = SkillContract(
                id = SkillId("qualification-system2-skill-$index"),
                signature = StrategySignature(listOf(capability)),
                preconditions = emptyList(),
                effects = emptyList(),
                successes = 12,
                executionFailures = 0,
                maturity = SkillMaturity.ACTIVE,
                confidence = 0.96,
                lastObservedAtEpochMs = 2_000L + index
            )
            add(
                System2Scenario(
                    goal = "search web qualification known strategy $index",
                    expectedMode = NativeSystem2Mode.REUSE_GOVERNED_STRATEGY,
                    packet = qualificationPacket(
                        readiness = IntegratedCognitiveReadiness(
                            epistemicConfidence = 0.92,
                            worldConfidence = 0.92,
                            skillConfidence = 0.96,
                            competenceConfidence = 0.90,
                            uncertainty = 0.05,
                            learningPressure = 0.05,
                            overallReadiness = 0.93
                        ),
                        skillGuidance = listOf(
                            SkillGuidance(
                                contract = skill,
                                preconditionsSatisfied = true,
                                goalRelevance = 0.98
                            )
                        )
                    )
                )
            )
        }
        repeat(10) { index ->
            add(
                System2Scenario(
                    goal = "reason about open qualification problem $index",
                    expectedMode = NativeSystem2Mode.DELIBERATE,
                    packet = qualificationPacket(
                        readiness = IntegratedCognitiveReadiness(
                            epistemicConfidence = 0.70,
                            worldConfidence = 0.72,
                            skillConfidence = 0.40,
                            competenceConfidence = 0.60,
                            uncertainty = 0.28,
                            learningPressure = 0.20,
                            overallReadiness = 0.64
                        )
                    )
                )
            )
        }
    }

    private fun qualificationPacket(
        readiness: IntegratedCognitiveReadiness,
        worldStates: List<StructuredWorldState> = emptyList(),
        skillGuidance: List<SkillGuidance> = emptyList()
    ): IntegratedCognitiveStatePacket =
        IntegratedCognitiveStatePacket(
            queryDigest = nativeSystem2Sha256("placeholder"),
            context = SovereignContextSnapshot(
                self = SelfSnapshot(
                    identity = "AMPER",
                    architecture = "APEX-MUXER SOVEREIGN NEURO-OS",
                    invariants = setOf("external-actions-require-authority")
                ),
                goals = emptyList(),
                memories = emptyList(),
                worldFacts = emptyList(),
                workspaceEvents = emptyList(),
                structuredWorldStates = worldStates
            ),
            skillGuidance = skillGuidance,
            skillCompositions = emptyList(),
            transferGuidance = emptyList(),
            generalizedChains = emptyList(),
            learningNeeds = emptyList(),
            readiness = readiness,
            capturedAtEpochMs = 3_000L
        )

    private fun completedSingleStepPlan(
        id: String,
        goal: String,
        descriptor: ToolDescriptor
    ): SovereignPlan {
        val requestId = ActionRequestId("$id-request")
        val proposal = ActionProposal(
            requestId = requestId,
            capability = descriptor.capability,
            reason = "qualification governed execution",
            input = "qualification"
        )
        return SovereignPlan(
            id = PlanId(id),
            conversationId = ConversationId("qualification-conversation"),
            goal = goal,
            steps = listOf(
                SovereignPlanStep(
                    index = 1,
                    requestId = requestId,
                    capability = descriptor.capability,
                    reason = proposal.reason,
                    input = proposal.input,
                    status = PlanStepStatus.EXECUTED,
                    outcome = ActionOutcome(
                        status = ActionStatus.EXECUTED,
                        proposal = proposal,
                        toolId = descriptor.id,
                        sideEffect = descriptor.sideEffect,
                        output = "qualification-success"
                    ),
                    boundToolId = descriptor.id,
                    boundSideEffect = descriptor.sideEffect
                )
            ),
            planningBackendId = "qualification"
        )
    }

    private fun percentile95(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * 0.95).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun normalizeQualificationGoal(value: String): String =
        value.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .take(1024)

    private data class System1Case(
        val input: String,
        val expectedCapability: CapabilityId?
    )

    private data class System1Observation(
        val correct: Boolean,
        val elapsedNanos: Long,
        val disposition: ReflexDecisionDisposition,
        val capability: CapabilityId?
    )

    private data class System2Scenario(
        val goal: String,
        val expectedMode: NativeSystem2Mode,
        val packet: IntegratedCognitiveStatePacket
    )

    private data class System2Observation(
        val pass: Boolean,
        val mode: NativeSystem2Mode,
        val reasoningDepth: Int,
        val selectedSource: NativeSystem2StrategySource
    )

    private class QualificationSkillGenesisModel(
        private val skill: SkillContract
    ) : SkillGenesisModel {
        override fun begin(
            plan: SovereignPlan,
            worldStates: List<StructuredWorldState>
        ) = Unit

        override fun observe(
            plan: SovereignPlan,
            worldStates: List<StructuredWorldState>
        ): SkillContract? = skill.takeIf {
            StrategySignature.from(plan) == it.signature
        }

        override fun snapshot(signature: StrategySignature): SkillContract? =
            skill.takeIf { it.signature == signature }

        override fun recent(limit: Int): List<SkillContract> =
            if (limit <= 0) emptyList() else listOf(skill)

        override fun guidance(
            goal: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>,
            worldStates: List<StructuredWorldState>,
            limit: Int
        ): List<SkillGuidance> = emptyList()

        override fun compositions(
            goal: String,
            allowedCapabilities: Set<CapabilityId>,
            descriptors: Collection<ToolDescriptor>,
            worldStates: List<StructuredWorldState>,
            limit: Int
        ): List<SkillComposition> = emptyList()
    }
}
