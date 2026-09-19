package io.amper.neuroos.core

import java.io.File

class InMemoryWorkspace : GlobalWorkspace {
    private val events = mutableListOf<CognitiveEvent>()
    @Synchronized override fun publish(event: CognitiveEvent) { events += event }
    @Synchronized override fun snapshot(): List<CognitiveEvent> = events.toList()
}

class InMemoryMemoryOs : MemoryOs {
    private val delegate = PersistentMemoryOs(InMemoryMemoryJournal())
    override fun remember(record: MemoryRecord) = delegate.remember(record)
    override fun recall(query: String, limit: Int): List<MemoryRecord> = delegate.recall(query, limit)
    override fun get(id: MemoryId): MemoryRecord? = delegate.get(id)
    override fun forget(id: MemoryId): Boolean = delegate.forget(id)
    override fun size(): Int = delegate.size()
}

class InMemoryModelRegistry : MutableModelRegistry {
    private val models = linkedMapOf<ModelId, ModelDescriptor>()

    @Synchronized
    override fun register(model: ModelDescriptor) { models[model.id] = model }

    @Synchronized
    override fun unregister(id: ModelId): Boolean = models.remove(id) != null

    @Synchronized
    override fun candidates(required: Set<CapabilityId>): List<ModelDescriptor> = models.values
        .filter { it.capabilities.containsAll(required) }
        .sortedWith(
            compareBy<ModelDescriptor> { it.format == "contract-only" }
                .thenByDescending { it.local }
                .thenBy { it.id.value }
        )

    @Synchronized
    override fun route(required: Set<CapabilityId>): ModelDescriptor? =
        candidates(required).firstOrNull()
}

class MobileResourceGovernor(private val budget: ResourceBudget = ResourceBudget()) : ResourceGovernor {
    override fun currentBudget(): ResourceBudget = budget
    override fun allows(agentCount: Int): Boolean = agentCount <= budget.maxConcurrentAgents
}

class DenyByDefaultAuthorityGate(
    private val granted: Set<CapabilityId> = emptySet()
) : AuthorityGate {
    override fun authorize(capability: CapabilityId, reason: String): Boolean = capability in granted && reason.isNotBlank()
}

class GatedToolFabric(private val gate: AuthorityGate) : ToolFabric {
    override fun invoke(capability: CapabilityId, input: String, reason: String): Result<String> =
        if (gate.authorize(capability, reason)) Result.success("accepted:$input")
        else Result.failure(SecurityException("Capability ${capability.value} not authorized"))
}

class BaselineMetaCognition : MetaCognition {
    override fun inspect(workspace: List<CognitiveEvent>): CognitiveEvent = CognitiveEvent(
        topic = "meta.reflection",
        payload = "events=${workspace.size};maxSalience=${workspace.maxOfOrNull { it.salience } ?: 0.0}",
        salience = 0.7
    )
}

class EvidenceMetaCognition(
    private val competence: CapabilityCompetenceModel,
    private val strategies: StrategyLearningModel? = null
) : MetaCognition {
    override fun inspect(workspace: List<CognitiveEvent>): CognitiveEvent {
        val evidence = competence.all(8)
        val executionAttempts = evidence.sumOf { it.executionAttempts }
        val executionSuccesses = evidence.sumOf { it.executed }
        val strategyEvidence = strategies?.recent(4).orEmpty()
        val strategyEvidenceCount = strategyEvidence.size
        val strategyAttempts = strategyEvidence.sumOf { it.completedAttempts }
        return CognitiveEvent(
            topic = "meta.reflection",
            payload = "events=${workspace.size};maxSalience=${workspace.maxOfOrNull { it.salience } ?: 0.0};" +
                "capabilityEvidence=${evidence.size};executionAttempts=$executionAttempts;" +
                "executionSuccesses=$executionSuccesses;strategyEvidence=$strategyEvidenceCount;" +
                "strategyAttempts=$strategyAttempts",
            salience = 0.7
        )
    }
}

class CanonicalSovereignKernel(
    private val workspace: GlobalWorkspace,
    private val memory: MemoryOs,
    private val models: ModelRegistry,
    private val governor: ResourceGovernor,
    private val meta: MetaCognition,
    private val selfModel: SelfModel,
    private val goals: GoalSystem,
    private val world: WorldModel,
    private val agents: DynamicAgentFabric
) : SovereignKernel {
    override fun tick(intent: String): TickReport {
        require(intent.isNotBlank())
        val provenance = Provenance(source = "user-intent", producer = "sovereign-kernel", confidence = 1.0)
        val intentEvent = CognitiveEvent(topic = "intent", payload = intent, salience = 1.0)
        workspace.publish(intentEvent)

        val self = selfModel.observeIntent(intent)
        workspace.publish(CognitiveEvent(
            topic = "self.state",
            payload = "identity=${self.identity};architecture=${self.architecture}",
            salience = 0.85
        ))

        val goal = goals.align(intent)
        workspace.publish(CognitiveEvent(
            topic = "goal.active",
            payload = "${goal.id.value}:${goal.objective}",
            salience = goal.priority
        ))

        val fact = world.observe(intentEvent, provenance)
        workspace.publish(CognitiveEvent(
            topic = "world.observation",
            payload = "${fact.subject}:${fact.statement}",
            salience = fact.confidence
        ))

        memory.remember(MemoryRecord(
            kind = "episodic",
            content = intent,
            importance = 0.8,
            provenance = provenance
        ))

        val leaseResult = agents.spawn(AgentSpec(
            role = "reasoning-specialist",
            requiredCapabilities = setOf(CapabilityId("reasoning")),
            purpose = intent
        ))
        val lease = leaseResult.getOrNull()
        workspace.publish(CognitiveEvent(
            topic = if (lease != null) "agent.leased" else "agent.gated",
            payload = lease?.let { "${it.role}:${it.model.value}" }
                ?: (leaseResult.exceptionOrNull()?.message ?: "unavailable"),
            salience = 0.75
        ))

        workspace.publish(meta.inspect(workspace.snapshot()))

        val route = lease?.model?.value
            ?: models.route(setOf(CapabilityId("reasoning")))?.id?.value
            ?: "none-installed"
        lease?.let { agents.release(it.id) }

        val state = if (governor.allows(agentCount = 1)) "READY" else "RESOURCE_GATED"
        return TickReport(
            kernelState = state,
            workspaceEvents = workspace.snapshot().size,
            memoryRecords = memory.size(),
            modelRoute = route,
            selfIdentity = self.identity,
            activeGoals = goals.active().size,
            worldFacts = world.size()
        )
    }
}

class AmperRuntime private constructor(
    private val kernel: SovereignKernel,
    val context: SovereignContextSource,
    val competence: CapabilityCompetenceModel,
    val strategies: StrategyLearningModel,
    val epistemic: EpistemicState,
    val semanticKnowledge: SemanticKnowledgeStore,
    val predictiveWorld: PredictiveWorldModel,
    val skills: SkillGenesisModel,
    val generalization: SkillGeneralizationModel,
    val autonomousLearning: AutonomousLearningModel,
    val perceptualGrounding: PerceptualGroundingSource,
    val integratedCognition: IntegratedCognitiveStateSource,
    val autonomousEvolution: AutonomousEvolutionModel,
    val autonomousEvolutionPromotionExecutor: AutonomousEvolutionPromotionExecutor,
    val nativeModelFoundation: NativeModelFoundation,
    val nativeExperienceDatasets: NativeExperienceDatasetStore,
    val nativeExperiencePartition: NativeExperiencePartitioner,
    val nativeExperienceCurriculum: NativeExperienceCurriculumPlanner,
    val nativeExperienceTraining: NativeExperienceTrainingCoordinator,
    val nativeTrainingPipeline: NativeTrainingPipeline,
    val reflexExperienceDatasets: ReflexExperienceDatasetStore,
    val reflexExperiencePartition: ReflexExperiencePartitioner,
    val reflexDecisionCurriculum: ReflexDecisionCurriculumPlanner,
    val reflexDecisionTraining: ReflexDecisionTrainingCoordinator,
    val reflexDecisionCortex: ReflexDecisionCortex,
    val conversations: SovereignConversationCoordinator,
    val inferenceProfiles: ConversationInferenceProfileStore,
    val plans: SovereignPlanStore,
    val persistentGoalExecutiveStore: PersistentGoalExecutiveStore,
    val goalPortfolio: DurableGoalPortfolio,
    val goalOutcomeLearning: GoalOutcomeLearningModel,
    val goalTransferCalibration: GoalTransferCalibrationModel,
    val goalHierarchicalStrategyCredit: GoalHierarchicalStrategyCreditModel,
    val goalRepairValidation: GoalRepairValidationModel,
    val goalRepairStrategyMemory: GoalRepairStrategyMemory,
    val goalStrategyPortfolio: GoalContextualStrategyPortfolio,
    val goalGraphs: SovereignGoalGraphStore,
    val pendingApprovals: PendingAssistantApprovalStore,
    val notes: SovereignNoteStore,
    val perception: PerceptionBus,
    private val actionLoopFactory: (ToolRegistry, ToolFabric, SovereignActionPolicy) -> SovereignActionLoop,
    private val evolutionCampaignFactory:
        (CognitiveInferencePort, EvolutionSandboxRunner) ->
            MemoryBackedAutonomousEvolutionCampaignCoordinator,
    private val evolutionAutonomyFactory:
        (
            CognitiveInferencePort,
            EvolutionSandboxRunner,
            EvolutionBaselinePort,
            EvolutionDeploymentPort,
            EvolutionCanaryEvaluator
        ) -> AutonomousEvolutionOrchestrator,
    private val memoryKeyRotator: ((String) -> Int)? = null
) {
    fun tick(intent: String): TickReport = kernel.tick(intent)

    /** Build an action loop that writes outcomes into this runtime's own sovereign memory/workspace. */
    fun actionLoop(
        registry: ToolRegistry,
        fabric: ToolFabric,
        policy: SovereignActionPolicy = SovereignActionPolicy()
    ): SovereignActionLoop = actionLoopFactory(registry, fabric, policy)

    fun evolutionCampaign(
        inference: CognitiveInferencePort,
        sandbox: EvolutionSandboxRunner
    ): MemoryBackedAutonomousEvolutionCampaignCoordinator =
        evolutionCampaignFactory(inference, sandbox)

    fun evolutionAutonomy(
        inference: CognitiveInferencePort,
        sandbox: EvolutionSandboxRunner,
        baseline: EvolutionBaselinePort,
        deployment: EvolutionDeploymentPort,
        canary: EvolutionCanaryEvaluator
    ): AutonomousEvolutionOrchestrator =
        evolutionAutonomyFactory(
            inference,
            sandbox,
            baseline,
            deployment,
            canary
        )

    /** Rewrap production encrypted memory under a new managed key alias. */
    fun rotateMemoryEncryption(newKeyId: String): Int =
        requireNotNull(memoryKeyRotator) { "memory key rotation is unavailable for this runtime" }(newKeyId)

    companion object {
        fun reference(): AmperRuntime = build(
            InMemoryMemoryOs(),
            defaultModelRegistry(),
            MobileResourceGovernor()
        )

        /** Plain journal constructor retained for JVM/reference tests and migration tooling. */
        fun persistent(
            rootDir: File,
            models: ModelRegistry = defaultModelRegistry(),
            governor: ResourceGovernor = MobileResourceGovernor(),
            agentRoutePlanner: TitanInferenceRoutePlanner? = null
        ): AmperRuntime {
            ensureContractPlaceholder(models)
            val sovereignDir = File(rootDir, "amper-sovereign")
            val memory = PersistentMemoryOs(FileMemoryJournal(File(sovereignDir, "memory.journal")))
            return build(
                memory = memory,
                models = models,
                governor = governor,
                agentRoutePlanner = agentRoutePlanner
            )
        }

        /**
         * Android production runtime: managed AES-GCM journal backed by AndroidKeyStore.
         * Supplying [cipher] keeps the explicit single-cipher path used by tests/migration tools;
         * managed key rotation is available only when [cipher] is omitted.
         *
         * Execution-capable hosts may supply [agentRoutePlanner] so cognitive-agent leases are
         * issued only for routes that pass the same feasibility checks as Titan inference.
         */
        fun persistentEncrypted(
            rootDir: File,
            models: ModelRegistry = defaultModelRegistry(),
            governor: ResourceGovernor = MobileResourceGovernor(),
            cipher: MemoryLineCipher? = null,
            agentRoutePlanner: TitanInferenceRoutePlanner? = null
        ): AmperRuntime {
            ensureContractPlaceholder(models)
            val sovereignDir = File(rootDir, "amper-sovereign")
            val memoryFile = File(sovereignDir, "memory.journal")
            val journal = cipher?.let { EncryptedFileMemoryJournal(memoryFile, it) }
                ?: EncryptedFileMemoryJournal.managed(memoryFile)
            val memory = PersistentMemoryOs(journal)
            val rotator = if (cipher == null) journal::rotateEncryption else null
            return build(
                memory = memory,
                models = models,
                governor = governor,
                memoryKeyRotator = rotator,
                agentRoutePlanner = agentRoutePlanner
            )
        }

        fun defaultModelRegistry(): InMemoryModelRegistry = InMemoryModelRegistry().also(::ensureContractPlaceholder)

        private fun ensureContractPlaceholder(models: ModelRegistry) {
            models.register(
                ModelDescriptor(
                    id = ModelId("titan-cortex-contract"),
                    format = "contract-only",
                    capabilities = setOf(CapabilityId("reasoning")),
                    local = true
                )
            )
        }

        private fun build(
            memory: MemoryOs,
            models: ModelRegistry,
            governor: ResourceGovernor,
            memoryKeyRotator: ((String) -> Int)? = null,
            agentRoutePlanner: TitanInferenceRoutePlanner? = null
        ): AmperRuntime {
            val workspace = InMemoryWorkspace()
            val competence = MemoryBackedCapabilityCompetenceModel(memory)
            val strategies = MemoryBackedStrategyLearningModel(memory)
            val epistemic = MemoryBackedEpistemicState(memory)
            val semanticKnowledge = MemoryBackedSemanticKnowledgeStore(memory, epistemic)
            val predictiveWorld = MemoryBackedPredictiveWorldModel(memory)
            val skills = MemoryBackedSkillGenesisModel(memory)
            val generalization = MemoryBackedSkillGeneralizationModel(memory, skills)
            val goalHierarchicalStrategyCredit =
                MemoryBackedGoalHierarchicalStrategyCreditModel(memory)
            val goalRepairValidation = MemoryBackedGoalRepairValidationModel(
                memory = memory,
                credit = goalHierarchicalStrategyCredit
            )
            val goalRepairStrategyMemory = MemoryBackedGoalRepairStrategyMemory(
                memory = memory,
                repairValidation = goalRepairValidation
            )
            val autonomousLearning = MemoryBackedAutonomousLearningModel(
                memory = memory,
                competence = competence,
                skills = skills,
                generalization = generalization,
                hierarchicalCredit = goalHierarchicalStrategyCredit,
                repairValidation = goalRepairValidation
            )
            val nativeModelFoundation = MemoryBackedNativeModelFoundation(memory)
            val nativeExperienceDatasets = MemoryBackedNativeExperienceDatasetStore(
                memory = memory,
                foundation = nativeModelFoundation
            )
            val nativeExperiencePartition = DeterministicNativeExperiencePartitioner(
                store = nativeExperienceDatasets
            )
            val nativeExperienceCurriculum = EvidenceNativeExperienceCurriculumPlanner(
                store = nativeExperienceDatasets,
                foundation = nativeModelFoundation
            )
            val reflexExperienceDatasets = MemoryBackedReflexExperienceDatasetStore(
                memory = memory,
                foundation = nativeModelFoundation
            )
            val reflexExperiencePartition = DeterministicReflexExperiencePartitioner(
                store = reflexExperienceDatasets
            )
            val reflexDecisionCurriculum = EvidenceReflexDecisionCurriculumPlanner(
                store = reflexExperienceDatasets,
                foundation = nativeModelFoundation
            )
            val generatedDatasetResolver = CompositeNativeGeneratedDatasetResolver(
                listOf(
                    NativeExperienceGeneratedDatasetResolver(nativeExperienceDatasets),
                    ReflexExperienceGeneratedDatasetResolver(reflexExperienceDatasets)
                )
            )
            val nativeTrainingPipeline = MemoryBackedNativeTrainingPipeline(
                memory = memory,
                foundation = nativeModelFoundation,
                generatedDatasetResolver = generatedDatasetResolver
            )
            val nativeExperienceTraining = CanonicalNativeExperienceTrainingCoordinator(
                datasets = nativeExperienceDatasets,
                curriculum = nativeExperienceCurriculum,
                foundation = nativeModelFoundation,
                training = nativeTrainingPipeline
            )
            val reflexDecisionTraining = CanonicalReflexDecisionTrainingCoordinator(
                partitioner = reflexExperiencePartition,
                curriculum = reflexDecisionCurriculum,
                foundation = nativeModelFoundation,
                training = nativeTrainingPipeline
            )
            val reflexDecisionCortex: ReflexDecisionCortex = DeterministicReflexDecisionCortex
            val selfModel = CanonicalSelfModel()
            val evolutionGate = CanonicalEvolutionGate(selfModel)
            val autonomousEvolution = MemoryBackedAutonomousEvolutionModel(
                memory = memory,
                gate = evolutionGate
            )
            val autonomousEvolutionPromotionExecutor =
                MemoryBackedAutonomousEvolutionPromotionExecutor(
                    memory = memory,
                    evolution = autonomousEvolution,
                    gate = evolutionGate
                )
            val goals = CanonicalGoalSystem()
            val world = CanonicalWorldModel()
            val context = CanonicalSovereignContextSource(
                workspace = workspace,
                memory = memory,
                selfModel = selfModel,
                goals = goals,
                world = world,
                competence = competence,
                strategies = strategies,
                epistemic = epistemic,
                semanticKnowledgeStore = semanticKnowledge,
                predictiveWorld = predictiveWorld
            )
            val perceptualGrounding = WorldBackedPerceptualGroundingSource(world)
            val integratedCognition = CanonicalIntegratedCognitiveStateSource(
                context = context,
                skills = skills,
                generalization = generalization,
                autonomousLearning = autonomousLearning,
                perceptualGrounding = perceptualGrounding
            )
            val conversations = SovereignConversationCoordinator(
                memory = memory,
                workspace = workspace,
                context = context
            )
            val inferenceProfiles = MemoryBackedConversationInferenceProfileStore(
                memory = memory,
                conversationExists = { conversationId ->
                    conversations.recent(conversationId, limit = 1).isNotEmpty()
                }
            )
            val plans = MemoryBackedSovereignPlanStore(memory)
            val persistentGoalExecutiveStore = MemoryBackedPersistentGoalExecutiveStore(memory)
            val goalPortfolio = MemoryBackedDurableGoalPortfolio(memory)
            val goalOutcomeLearning = MemoryBackedGoalOutcomeLearningModel(memory)
            val goalTransferCalibration = MemoryBackedGoalTransferCalibrationModel(memory)
            val goalStrategyPortfolio = MemoryBackedGoalContextualStrategyPortfolio(
                memory = memory,
                hierarchicalCredit = goalHierarchicalStrategyCredit,
                repairValidation = goalRepairValidation,
                repairStrategyMemory = goalRepairStrategyMemory
            )
            val goalGraphs = MemoryBackedSovereignGoalGraphStore(memory)
            val pendingApprovals = MemoryBackedPendingAssistantApprovalStore(memory)
            val notes = MemoryBackedSovereignNoteStore(memory, workspace)
            val perception = CanonicalPerceptionBus(workspace, world)
            val kernel = CanonicalSovereignKernel(
                workspace = workspace,
                memory = memory,
                models = models,
                governor = governor,
                meta = EvidenceMetaCognition(competence, strategies),
                selfModel = selfModel,
                goals = goals,
                world = world,
                agents = EphemeralAgentFabric(
                    governor = governor,
                    models = models,
                    routePlanner = agentRoutePlanner
                )
            )
            return AmperRuntime(
                kernel = kernel,
                context = context,
                competence = competence,
                strategies = strategies,
                epistemic = epistemic,
                semanticKnowledge = semanticKnowledge,
                predictiveWorld = predictiveWorld,
                skills = skills,
                generalization = generalization,
                autonomousLearning = autonomousLearning,
                perceptualGrounding = perceptualGrounding,
                integratedCognition = integratedCognition,
                autonomousEvolution = autonomousEvolution,
                autonomousEvolutionPromotionExecutor = autonomousEvolutionPromotionExecutor,
                nativeModelFoundation = nativeModelFoundation,
                nativeExperienceDatasets = nativeExperienceDatasets,
                nativeExperiencePartition = nativeExperiencePartition,
                nativeExperienceCurriculum = nativeExperienceCurriculum,
                nativeExperienceTraining = nativeExperienceTraining,
                nativeTrainingPipeline = nativeTrainingPipeline,
                reflexExperienceDatasets = reflexExperienceDatasets,
                reflexExperiencePartition = reflexExperiencePartition,
                reflexDecisionCurriculum = reflexDecisionCurriculum,
                reflexDecisionTraining = reflexDecisionTraining,
                reflexDecisionCortex = reflexDecisionCortex,
                conversations = conversations,
                inferenceProfiles = inferenceProfiles,
                plans = plans,
                persistentGoalExecutiveStore = persistentGoalExecutiveStore,
                goalPortfolio = goalPortfolio,
                goalOutcomeLearning = goalOutcomeLearning,
                goalTransferCalibration = goalTransferCalibration,
                goalHierarchicalStrategyCredit = goalHierarchicalStrategyCredit,
                goalRepairValidation = goalRepairValidation,
                goalRepairStrategyMemory = goalRepairStrategyMemory,
                goalStrategyPortfolio = goalStrategyPortfolio,
                goalGraphs = goalGraphs,
                pendingApprovals = pendingApprovals,
                notes = notes,
                perception = perception,
                actionLoopFactory = { registry, fabric, policy ->
                    SovereignActionLoop(
                        registry = registry,
                        fabric = fabric,
                        memory = memory,
                        workspace = workspace,
                        policy = policy,
                        competence = competence
                    )
                },
                evolutionCampaignFactory = { inference, sandbox ->
                    MemoryBackedAutonomousEvolutionCampaignCoordinator(
                        memory = memory,
                        evolution = autonomousEvolution,
                        generator = InferenceEvolutionCandidateGenerator(inference),
                        sandbox = sandbox
                    )
                },
                evolutionAutonomyFactory = { inference, sandbox, baseline, deployment, canary ->
                    AutonomousEvolutionOrchestrator(
                        evolution = autonomousEvolution,
                        campaign = MemoryBackedAutonomousEvolutionCampaignCoordinator(
                            memory = memory,
                            evolution = autonomousEvolution,
                            generator = InferenceEvolutionCandidateGenerator(inference),
                            sandbox = sandbox
                        ),
                        promotion = autonomousEvolutionPromotionExecutor,
                        baseline = baseline,
                        deployment = deployment,
                        canary = canary
                    )
                },
                memoryKeyRotator = memoryKeyRotator
            )
        }
    }
}