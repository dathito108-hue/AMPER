package io.amper.neuroos.core

import android.content.Context
import io.amper.neuroos.core.v2.AndroidAmi2CompilationService
import io.amper.neuroos.core.v2.AmperAgentCanonicalContinuationExecutionPort
import io.amper.neuroos.core.v2.AmperAgentCanonicalEventWakeExecutionPort
import io.amper.neuroos.core.v2.AmperAgentExecutionContinuationCoordinator
import io.amper.neuroos.core.v2.AmperAgentPassiveTaskCoordinator
import io.amper.neuroos.core.v2.AmperAgentPendingTriggerDispatchCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveTaskLifecycleCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveEventWakeCoordinator
import io.amper.neuroos.core.v2.AmperAgentProactiveTriggerSourceRegistry
import io.amper.neuroos.core.v2.AmperAgentTaskAdmissionRegistry
import io.amper.neuroos.core.v2.PersistentSovereignAgentPlanPort
import java.io.File

data class AndroidCanonicalAgentRuntimeGraph(
    val assistantCapabilities: Set<CapabilityId>,
    val toolRegistry: InMemoryToolRegistry,
    val toolAudit: InMemoryToolAuditLog,
    val toolFabric: AuditedToolFabric,
    val actionLoop: SovereignActionLoop,
    val inferencePort: TitanInferencePort,
    val assistant: SovereignAssistantTurnCoordinator,
    val planningCoordinator: SovereignPlanCoordinator,
    val planner: PersistentSovereignPlanCoordinator,
    val agentPlanPort: PersistentSovereignAgentPlanPort,
    val agentAdmissions: AmperAgentTaskAdmissionRegistry,
    val agentPassiveTasks: AmperAgentPassiveTaskCoordinator,
    val agentProactiveTasks: AmperAgentProactiveTaskCoordinator,
    val agentTriggerSources: AmperAgentProactiveTriggerSourceRegistry,
    val agentTriggerSourceController: AndroidAgentProactiveTriggerSourceController,
    val agentPendingTriggerDispatch: AmperAgentPendingTriggerDispatchCoordinator,
    val agentEventWake: AmperAgentProactiveEventWakeCoordinator,
    val agentProactiveLifecycle: AmperAgentProactiveTaskLifecycleCoordinator,
    val agentProactiveLifecycleController: AndroidAgentProactiveTaskLifecycleController,
    val agentProactiveAttention: AndroidAgentProactiveAttentionController,
    val agentContinuation: AmperAgentExecutionContinuationCoordinator,
    val execution: AmperAgentCanonicalContinuationExecutionPort,
    val eventWakeExecution: AmperAgentCanonicalEventWakeExecutionPort
)

/**
 * Process-wide canonical Android sovereign runtime graph.
 *
 * Foreground UI and cold persisted-job continuation acquire this same graph construction. There is
 * no background-only planner, ToolFabric, AuthorityGate, inference endpoint, or plan store.
 */
class AndroidCanonicalSovereignRuntimeGraph internal constructor(context: Context) {
    private val appContext = context.applicationContext

    val canonicalFilesDir: File =
        AndroidAppPrivateStorage.canonicalFilesDir(appContext)
    val canonicalCacheDir: File =
        AndroidAppPrivateStorage.canonicalCacheDir(appContext)
    val sovereignDir: File =
        File(canonicalFilesDir, "amper-sovereign")
    val nativeModelStagingDir: File =
        File(canonicalCacheDir, "amper-native-model-stage")
    val nativeProjectorStagingDir: File =
        File(canonicalCacheDir, "amper-native-projector-stage")

    val modelRegistry = AmperSingleCoreModelRegistry()
    val catalog = FileInstalledModelCatalog(File(sovereignDir, "models.catalog"))
    val coreFoundationPreferences =
        appContext.getSharedPreferences("amper-core-foundation", Context.MODE_PRIVATE)
    val coreFoundationController =
        AmperSingleCoreFoundationController(catalog, modelRegistry)

    val initialCoreFoundationState: AmperCoreFoundationState = run {
        val legacyRoutingPreferences =
            appContext.getSharedPreferences("amper-model-routing", Context.MODE_PRIVATE)
        val persisted = coreFoundationPreferences
            .getString("active_source_model_id", null)
            ?.let(::ModelId)
            ?.takeIf { catalog.get(it) != null }
        val migrated = persisted ?: legacyRoutingPreferences
            .getString("preferred_model_id", null)
            ?.let(::ModelId)
            ?.takeIf { catalog.get(it) != null }

        coreFoundationController.restore(migrated).also { state ->
            state.activeModelId?.let { active ->
                coreFoundationPreferences.edit()
                    .putString("active_source_model_id", active.value)
                    .apply()
            }
            legacyRoutingPreferences.edit()
                .remove("preferred_model_id")
                .apply()
        }
    }

    val governor = AndroidResourceGovernor(appContext)
    val deviceStatusSource = AndroidDeviceStatusSource(appContext)

    val runtime: AmperRuntime = ReflexMaintenanceExecutionGate.exclusive {
        AmperRuntime.persistentEncrypted(
            canonicalFilesDir,
            modelRegistry,
            governor,
            deviceStatusSource = deviceStatusSource
        )
    }

    val projectorCatalog =
        FileMultimodalProjectorCatalog(File(sovereignDir, "multimodal-projectors.catalog"))

    init {
        NativeRuntimeCapabilityReconciler(
            catalog = catalog,
            registry = modelRegistry,
            projectors = projectorCatalog
        ).reconcile()
        // Reassert the one user-selected AMPER foundation after legacy reconciliation.
        coreFoundationController.restore(initialCoreFoundationState.activeModelId)
    }

    val nativeModelArtifacts =
        AndroidAppPrivateModelArtifactResolver(File(sovereignDir, "native-models"))
    val contentModelArtifacts =
        ContentUriArtifactResolver(appContext.contentResolver, nativeModelStagingDir)
    val modelArtifacts = LocatorArtifactResolver(
        listOf(
            { model -> contentModelArtifacts.resolve(model) },
            { model -> nativeModelArtifacts.resolve(model) }
        )
    )

    val ami2CompilationService = AndroidAmi2CompilationService(
        rootDir = File(sovereignDir, "ami2-models"),
        modelArtifacts = modelArtifacts
    )
    val amiCompilationService = AndroidAmiCompilationService(
        rootDir = File(sovereignDir, "ami-models"),
        modelArtifacts = modelArtifacts
    )
    val amiHardwareProfiler = AndroidAmiHardwareProfiler(appContext)

    val amperCore = AmperCoreInferencePort(
        artifactLookup = { model ->
            ami2CompilationService.existing(model)
                ?: amiCompilationService.existing(model)?.let { legacy ->
                    ami2CompilationService
                        .migrateLegacy(model, legacy.file)
                        .getOrNull()
                }
        },
        hardwareSnapshot = amiHardwareProfiler::snapshot
    )

    val titan = TitanCortexRuntime(
        models = modelRegistry,
        catalog = catalog,
        artifacts = modelArtifacts,
        core = amperCore,
        governor = governor
    )

    private var nativeAdmissionResult: Result<AmperCoreBootstrapReport>? = null

    /**
     * Shares AMNE numerical qualification/admission across foreground and cold hosts.
     *
     * Failure is cached for this process just like a failed foreground qualification; callers may
     * continue on the canonical reference path instead of inventing another backend.
     */
    @Synchronized
    fun ensureNativeAdmission(): Result<AmperCoreBootstrapReport> {
        nativeAdmissionResult?.let { return it }
        return amperCore.bootstrapNativeAdmission().also { nativeAdmissionResult = it }
    }

    /**
     * Lazily builds exactly one canonical Agent execution graph for this Android process.
     *
     * The JobService reaches this only after the persisted handoff has been verified as
     * READY_FOR_EXPLICIT_ADVANCE, so WAITING_APPROVAL cannot bootstrap or execute the graph.
     */
    val agent: AndroidCanonicalAgentRuntimeGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val assistantCapabilities = SovereignAssistantToolExposure.capabilities
        val androidActionLauncher = AndroidContextDeviceActionLauncher(appContext)
        val toolRegistry = InMemoryToolRegistry().also { registry ->
            registry.register(DeviceStatusToolProvider(deviceStatusSource))
            registry.register(
                SovereignStatusToolProvider(
                    AmperCoreSovereignStatusSource(catalog, amperCore, governor)
                )
            )
            registry.register(SovereignNoteToolProvider(runtime.notes))
            registry.register(SovereignNoteSearchToolProvider(runtime.notes))
            registry.register(SovereignNoteDeleteToolProvider(runtime.notes))
            registry.register(AndroidSettingsOpenToolProvider(androidActionLauncher))
            registry.register(AndroidTimerPrepareToolProvider(androidActionLauncher))
            registry.register(AndroidShareTextToolProvider(androidActionLauncher))
            registry.register(AndroidAppLaunchToolProvider(androidActionLauncher))
            registry.register(AndroidWebSearchToolProvider(androidActionLauncher))
            registry.register(OmegaInternetReadToolProvider(OmegaInternetGateway()))
            registry.register(AndroidClipboardWriteToolProvider(androidActionLauncher))
            registry.register(AndroidFilesBrowseToolProvider(androidActionLauncher))
            registry.register(AndroidContactComposeToolProvider(androidActionLauncher))
            registry.register(AndroidCalendarComposeToolProvider(androidActionLauncher))
            registry.register(AndroidAlarmPrepareToolProvider(androidActionLauncher))
            registry.register(AndroidMediaOpenToolProvider(androidActionLauncher))
            registry.register(AndroidNotificationSettingsToolProvider(androidActionLauncher))
            registry.register(AndroidHomeOpenToolProvider(androidActionLauncher))
        }
        val toolAudit = InMemoryToolAuditLog()
        val toolFabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(assistantCapabilities),
            registry = toolRegistry,
            audit = toolAudit
        )
        val actionLoop = runtime.actionLoop(toolRegistry, toolFabric)
        val inferencePort = TitanInferencePort(titan)
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inferencePort,
            actions = actionLoop,
            advertisedCapabilities = assistantCapabilities,
            maxOutputTokens = 256
        )
        val planningCoordinator = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inferencePort,
            actions = actionLoop,
            advertisedCapabilities = assistantCapabilities,
            maxOutputTokens = 256,
            criticInference = inferencePort
        )
        val planner = PersistentSovereignPlanCoordinator(
            delegate = planningCoordinator,
            store = runtime.plans
        )
        val agentPlanPort = PersistentSovereignAgentPlanPort(
            coordinator = planner,
            store = runtime.plans
        )
        val agentAdmissions = AmperAgentTaskAdmissionRegistry()
        val agentPassiveTasks = AmperAgentPassiveTaskCoordinator(agentPlanPort)
        val agentProactiveTasks = AmperAgentProactiveTaskCoordinator(agentPlanPort)
        val agentTriggerSourceController = AndroidAgentProactiveTriggerSourceController(
            context = appContext,
            registry = runtime.proactiveTriggerSources
        )
        val agentEventWake = AmperAgentProactiveEventWakeCoordinator(agentPlanPort)
        val agentProactiveLifecycle = AmperAgentProactiveTaskLifecycleCoordinator(
            ledger = runtime.proactiveTaskLifecycle,
            plans = agentPlanPort,
            admissions = agentAdmissions,
            proactive = agentProactiveTasks,
            eventWake = agentEventWake
        )
        val agentProactiveLifecycleController = AndroidAgentProactiveTaskLifecycleController(
            context = appContext,
            lifecycle = agentProactiveLifecycle,
            governor = governor
        )
        val agentProactiveAttention = AndroidAgentProactiveAttentionController(
            context = appContext,
            lifecycle = agentProactiveLifecycle,
            acknowledgements = runtime.proactiveAttentionAcknowledgements
        )
        val agentPendingTriggerDispatch = AmperAgentPendingTriggerDispatchCoordinator(
            registry = runtime.proactiveTriggerSources,
            admissions = agentAdmissions,
            proactive = agentProactiveTasks,
            eventWake = agentEventWake
        )
        val agentContinuation = AmperAgentExecutionContinuationCoordinator(agentPlanPort)
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = agentAdmissions,
            plans = agentPlanPort,
            passive = agentPassiveTasks,
            continuation = agentContinuation
        )
        val eventWakeExecution = AmperAgentCanonicalEventWakeExecutionPort(
            admissions = agentAdmissions,
            plans = agentPlanPort,
            proactive = agentProactiveTasks,
            eventWake = agentEventWake
        )

        AndroidCanonicalAgentRuntimeGraph(
            assistantCapabilities = assistantCapabilities,
            toolRegistry = toolRegistry,
            toolAudit = toolAudit,
            toolFabric = toolFabric,
            actionLoop = actionLoop,
            inferencePort = inferencePort,
            assistant = assistant,
            planningCoordinator = planningCoordinator,
            planner = planner,
            agentPlanPort = agentPlanPort,
            agentAdmissions = agentAdmissions,
            agentPassiveTasks = agentPassiveTasks,
            agentProactiveTasks = agentProactiveTasks,
            agentTriggerSources = runtime.proactiveTriggerSources,
            agentTriggerSourceController = agentTriggerSourceController,
            agentPendingTriggerDispatch = agentPendingTriggerDispatch,
            agentEventWake = agentEventWake,
            agentProactiveLifecycle = agentProactiveLifecycle,
            agentProactiveLifecycleController = agentProactiveLifecycleController,
            agentProactiveAttention = agentProactiveAttention,
            agentContinuation = agentContinuation,
            execution = execution,
            eventWakeExecution = eventWakeExecution
        )
    }
}

/**
 * Single process bootstrap used by both foreground and cold Android hosts.
 *
 * Persisted JobScheduler wakes after process death/reboot create this graph on demand; subsequent
 * wakes and a concurrently opened MainActivity reuse the same process instance.
 */
object AndroidCanonicalSovereignRuntimeBootstrap {
    @Volatile
    private var processGraph: AndroidCanonicalSovereignRuntimeGraph? = null

    fun acquire(context: Context): AndroidCanonicalSovereignRuntimeGraph {
        processGraph?.let { return it }
        return synchronized(this) {
            processGraph
                ?: AndroidCanonicalSovereignRuntimeGraph(context.applicationContext)
                    .also { processGraph = it }
        }
    }
}
