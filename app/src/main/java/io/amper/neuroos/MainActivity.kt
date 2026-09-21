package io.amper.neuroos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.amper.neuroos.core.AmperCoreInferencePort
import io.amper.neuroos.core.AmperCoreSovereignStatusSource
import io.amper.neuroos.core.AmperExecutionLanes
import io.amper.neuroos.core.AmperRuntime
import io.amper.neuroos.core.AmperSingleCoreFoundationController
import io.amper.neuroos.core.AmperSingleCoreModelRegistry
import io.amper.neuroos.core.AmperSingleCoreSourceDetachService
import io.amper.neuroos.core.AssistantStreamEvent
import io.amper.neuroos.core.AssistantTurnStage
import io.amper.neuroos.core.AutonomousGoalScheduler
import io.amper.neuroos.core.AutonomousGovernedPlanRunner
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceCancelledException
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.AndroidContextDeviceActionLauncher
import io.amper.neuroos.core.AndroidAlarmPrepareToolProvider
import io.amper.neuroos.core.AndroidAppLaunchToolProvider
import io.amper.neuroos.core.AndroidCalendarComposeToolProvider
import io.amper.neuroos.core.AndroidClipboardWriteToolProvider
import io.amper.neuroos.core.AndroidContactComposeToolProvider
import io.amper.neuroos.core.AndroidFilesBrowseToolProvider
import io.amper.neuroos.core.AndroidHomeOpenToolProvider
import io.amper.neuroos.core.AndroidMediaOpenToolProvider
import io.amper.neuroos.core.AndroidNotificationSettingsToolProvider
import io.amper.neuroos.core.AndroidWebSearchToolProvider
import io.amper.neuroos.core.AndroidDeviceStatusSource
import io.amper.neuroos.core.AndroidInferenceAttachmentLoader
import io.amper.neuroos.core.AndroidCameraVisionAttachmentEncoder
import io.amper.neuroos.core.AndroidSettingsOpenToolProvider
import io.amper.neuroos.core.AndroidShareTextToolProvider
import io.amper.neuroos.core.AndroidTimerPrepareToolProvider
import io.amper.neuroos.core.AndroidLiveAudioAttachmentCapture
import io.amper.neuroos.core.AndroidModelImportService
import io.amper.neuroos.core.AndroidAppPrivateModelArtifactResolver
import io.amper.neuroos.core.AndroidAmiCompilationService
import io.amper.neuroos.core.v2.AndroidAmi2CompilationService
import io.amper.neuroos.core.v2.AmperAgentCanonicalContinuationExecutionPort
import io.amper.neuroos.core.v2.AmperAgentExecutionContinuationCoordinator
import io.amper.neuroos.core.v2.AmperAgentPassiveTaskCoordinator
import io.amper.neuroos.core.v2.AmperAgentTaskAdmissionRegistry
import io.amper.neuroos.core.v2.PersistentSovereignAgentPlanPort
import io.amper.neuroos.core.AndroidAmiHardwareProfiler
import io.amper.neuroos.core.AmiDecoderFfnExecutor
import io.amper.neuroos.core.AmiDecoderFfnPlanner
import io.amper.neuroos.core.AmiDecoderLayerExecutor
import io.amper.neuroos.core.AmiDecoderLayerPlanner
import io.amper.neuroos.core.AmiLayerKvCache
import io.amper.neuroos.core.AmiPreservedGgufMetadataReader
import io.amper.neuroos.core.AmiTensorGraphReader
import io.amper.neuroos.core.AndroidActivePerceptionPort
import io.amper.neuroos.core.AndroidAgentContinuationProcessRegistry
import io.amper.neuroos.core.AndroidAppPrivateStorage
import io.amper.neuroos.core.AndroidMultimodalProjectorImportService
import io.amper.neuroos.core.AndroidPerceptionCapture
import io.amper.neuroos.core.AndroidResourceGovernor
import io.amper.neuroos.core.AndroidReflexLearningJobScheduler
import io.amper.neuroos.core.AndroidReflexMaintenanceProcessRegistry
import io.amper.neuroos.core.AndroidScreenVisionSession
import io.amper.neuroos.core.AndroidSovereignAgentExecutionGraphFactory
import io.amper.neuroos.core.AndroidVoiceConversationSession
import io.amper.neuroos.core.AuditedToolFabric
import io.amper.neuroos.core.ContentUriArtifactResolver
import io.amper.neuroos.core.ContentUriProjectorArtifactResolver
import io.amper.neuroos.core.CognitiveExecutiveCycleResult
import io.amper.neuroos.core.DenyByDefaultAuthorityGate
import io.amper.neuroos.core.DeviceStatusToolProvider
import io.amper.neuroos.core.FileInstalledModelCatalog
import io.amper.neuroos.core.FileMultimodalProjectorCatalog
import io.amper.neuroos.core.FileReflexLinearArtifactStore
import io.amper.neuroos.core.InMemoryModelRegistry
import io.amper.neuroos.core.InMemoryToolAuditLog
import io.amper.neuroos.core.InMemoryToolRegistry
import io.amper.neuroos.core.OmegaInternetGateway
import io.amper.neuroos.core.OmegaInternetReadToolProvider
import io.amper.neuroos.core.InferenceAttachment
import io.amper.neuroos.core.InferenceAttachmentKind
import io.amper.neuroos.core.LiveContextFusion
import io.amper.neuroos.core.LocatorArtifactResolver
import io.amper.neuroos.core.LocatorMultimodalProjectorArtifactResolver
import io.amper.neuroos.core.InstalledModelCapabilityService
import io.amper.neuroos.core.InstalledModelDetachService
import io.amper.neuroos.core.InstalledModelRegistryBootstrap
import io.amper.neuroos.core.ModelCapabilityProfile
import io.amper.neuroos.core.ModelId
import io.amper.neuroos.core.NativeCheckpointRuntimePromotionService
import io.amper.neuroos.core.NativeInstalledModelSelector
import io.amper.neuroos.core.NativeModelPreferenceInferencePort
import io.amper.neuroos.core.NativeMultimodalAdapterActivationService
import io.amper.neuroos.core.NativeRuntimeCapabilityReconciler
import io.amper.neuroos.core.LlamaNativeTextEngine
import io.amper.neuroos.core.LlamaNativeTextInferenceBackend
import io.amper.neuroos.core.MtmdNativeInferenceBackend
import io.amper.neuroos.core.OptionalBackendPackLoader
import io.amper.neuroos.core.OptionalMtmdNativeEngineLoader
import io.amper.neuroos.core.PersistentSovereignPlanCoordinator
import io.amper.neuroos.core.PerceptionModality
import io.amper.neuroos.core.PersistentGoalExecutiveResult
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PlanStepStatus
import io.amper.neuroos.core.PreferredModelInferencePort
import io.amper.neuroos.core.ProcessResidentAutonomyLoop
import io.amper.neuroos.core.ProcessResidentReflexMaintenanceLoop
import io.amper.neuroos.core.RuntimeSovereignStatusSource
import io.amper.neuroos.core.ReflexBackgroundMaintenanceCoordinator
import io.amper.neuroos.core.ReflexMaintenanceExecutionGate
import io.amper.neuroos.core.ReflexNativeModelLifecycle
import io.amper.neuroos.core.SovereignAssistantToolExposure
import io.amper.neuroos.core.SovereignAssistantTurnCoordinator
import io.amper.neuroos.core.SovereignAssistantTurnResult
import io.amper.neuroos.core.SovereignNoteDeleteToolProvider
import io.amper.neuroos.core.SovereignNoteSearchToolProvider
import io.amper.neuroos.core.SovereignNoteToolProvider
import io.amper.neuroos.core.SovereignPlanCoordinator
import io.amper.neuroos.core.SovereignPlanHistory
import io.amper.neuroos.core.SovereignStatusToolProvider
import io.amper.neuroos.core.TitanBackendPackManager
import io.amper.neuroos.core.TitanCapabilities
import io.amper.neuroos.core.TitanCortexRuntime
import io.amper.neuroos.core.TitanInferencePort
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val canonicalFilesDir = remember {
                AndroidAppPrivateStorage.canonicalFilesDir(applicationContext)
            }
            val canonicalCacheDir = remember {
                AndroidAppPrivateStorage.canonicalCacheDir(applicationContext)
            }
            val sovereignDir = remember {
                File(canonicalFilesDir, "amper-sovereign")
            }
            val nativeModelStagingDir = remember {
                File(canonicalCacheDir, "amper-native-model-stage")
            }
            val nativeProjectorStagingDir = remember {
                File(canonicalCacheDir, "amper-native-projector-stage")
            }
            val modelRegistry = remember { AmperSingleCoreModelRegistry() }
            val catalog = remember { FileInstalledModelCatalog(File(sovereignDir, "models.catalog")) }
            val coreFoundationPreferences = remember {
                getSharedPreferences("amper-core-foundation", Context.MODE_PRIVATE)
            }
            val conversationUiPreferences = remember {
                getSharedPreferences("amper-conversation-ui", Context.MODE_PRIVATE)
            }
            val legacyRoutingPreferences = remember {
                getSharedPreferences("amper-model-routing", Context.MODE_PRIVATE)
            }
            val coreFoundationController = remember {
                AmperSingleCoreFoundationController(catalog, modelRegistry)
            }
            val initialCoreFoundationState = remember {
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
            val governor = remember { AndroidResourceGovernor(applicationContext) }
            val deviceStatusSource = remember { AndroidDeviceStatusSource(applicationContext) }
            val runtime = remember {
                ReflexMaintenanceExecutionGate.exclusive {
                    AmperRuntime.persistentEncrypted(
                        canonicalFilesDir,
                        modelRegistry,
                        governor,
                        deviceStatusSource = deviceStatusSource
                    )
                }
            }
            val reflexArtifactStore = remember {
                FileReflexLinearArtifactStore(File(sovereignDir, "native-reflex"))
            }
            val reflexJobScheduler = remember {
                AndroidReflexLearningJobScheduler(applicationContext)
            }
            val reflexLifecycle = remember {
                ReflexNativeModelLifecycle(
                    runtime = runtime,
                    artifacts = reflexArtifactStore,
                    maintenanceScheduler = reflexJobScheduler
                )
            }
            val reflexMaintenanceCoordinator = remember {
                ReflexBackgroundMaintenanceCoordinator(
                    lifecycle = reflexLifecycle,
                    queue = runtime.reflexLearningMaintenanceQueue,
                    deviceStatusSource = deviceStatusSource,
                    telemetry = runtime.reflexLearningSchedulerTelemetry
                )
            }
            val reflexMaintenanceLoop = remember {
                ProcessResidentReflexMaintenanceLoop(
                    coordinator = reflexMaintenanceCoordinator
                )
            }
            val perceptionCapture = remember {
                AndroidPerceptionCapture(applicationContext, runtime.perception)
            }
            val screenVisionSession = remember {
                AndroidScreenVisionSession(applicationContext)
            }
            val voiceConversationSession = remember {
                AndroidVoiceConversationSession(applicationContext)
            }
            val attachmentLoader = remember {
                AndroidInferenceAttachmentLoader(applicationContext)
            }
            val liveAudioCapture = remember {
                AndroidLiveAudioAttachmentCapture(applicationContext)
            }
            val projectorCatalog = remember {
                FileMultimodalProjectorCatalog(
                    File(sovereignDir, "multimodal-projectors.catalog")
                )
            }
            remember {
                NativeRuntimeCapabilityReconciler(
                    catalog = catalog,
                    registry = modelRegistry,
                    projectors = projectorCatalog
                ).reconcile()
            }
            remember {
                // Reassert the user-selected AMPER foundation after legacy reconciliation.
                coreFoundationController.restore(initialCoreFoundationState.activeModelId)
            }
            val projectorImporter = remember {
                AndroidMultimodalProjectorImportService(
                    applicationContext,
                    projectorCatalog
                )
            }
            val contentProjectorArtifacts = remember {
                ContentUriProjectorArtifactResolver(
                    contentResolver,
                    nativeProjectorStagingDir
                )
            }
            val nativeModelArtifacts = remember {
                AndroidAppPrivateModelArtifactResolver(
                    File(sovereignDir, "native-models")
                )
            }
            val projectorResolver = remember {
                LocatorMultimodalProjectorArtifactResolver(
                    listOf(
                        { projector -> contentProjectorArtifacts.resolve(projector) },
                        { projector -> nativeModelArtifacts.resolve(projector) }
                    )
                )
            }
            val importer = remember { AndroidModelImportService(this, catalog, modelRegistry) }
            val capabilityManager = remember { InstalledModelCapabilityService(catalog, modelRegistry) }
            val contentModelArtifacts = remember {
                ContentUriArtifactResolver(
                    contentResolver,
                    nativeModelStagingDir
                )
            }
            val modelArtifacts = remember {
                LocatorArtifactResolver(
                    listOf(
                        { model -> contentModelArtifacts.resolve(model) },
                        { model -> nativeModelArtifacts.resolve(model) }
                    )
                )
            }
            val ami2CompilationService = remember {
                AndroidAmi2CompilationService(
                    rootDir = File(sovereignDir, "ami2-models"),
                    modelArtifacts = modelArtifacts
                )
            }
            val amiCompilationService = remember {
                AndroidAmiCompilationService(
                    rootDir = File(sovereignDir, "ami-models"),
                    modelArtifacts = modelArtifacts
                )
            }
            val amiHardwareProfiler = remember {
                AndroidAmiHardwareProfiler(this)
            }
            val amperCore = remember {
                AmperCoreInferencePort(
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
            }
            val hasRuntimeBackend = amperCore.inferenceEndpointCount == 1
            val titan = remember {
                TitanCortexRuntime(
                    models = modelRegistry,
                    catalog = catalog,
                    artifacts = modelArtifacts,
                    core = amperCore,
                    governor = governor
                )
            }
            val detachManager = remember {
                AmperSingleCoreSourceDetachService(catalog, modelRegistry, titan::unload)
            }
            val nativePromotion = remember {
                NativeCheckpointRuntimePromotionService(
                    foundation = runtime.nativeModelFoundation,
                    training = runtime.nativeTrainingPipeline,
                    catalog = catalog,
                    registry = modelRegistry,
                    unloadRuntime = titan::unload
                )
            }
            remember {
                NativeMultimodalAdapterActivationService(
                    foundation = runtime.nativeModelFoundation,
                    promotion = nativePromotion,
                    catalog = catalog,
                    registry = modelRegistry,
                    projectors = projectorCatalog
                )
            }
            val agentExecutionGraph = remember {
                AndroidSovereignAgentExecutionGraphFactory.build(
                    context = applicationContext,
                    runtime = runtime,
                    titan = titan,
                    catalog = catalog,
                    core = amperCore,
                    governor = governor
                )
            }
            val assistantCapabilities = agentExecutionGraph.capabilities
            val toolRegistry = agentExecutionGraph.toolRegistry
            val toolAudit = agentExecutionGraph.toolAudit
            val toolFabric = agentExecutionGraph.toolFabric
            val actionLoop = agentExecutionGraph.actionLoop
            val inferencePort = agentExecutionGraph.inferencePort
            val assistant = agentExecutionGraph.assistant
            val planningCoordinator = agentExecutionGraph.planningCoordinator
            val planner = agentExecutionGraph.planner
            val agentPlanPort = agentExecutionGraph.agentPlanPort
            val agentAdmissions = agentExecutionGraph.agentAdmissions
            val agentPassiveTasks = agentExecutionGraph.agentPassiveTasks
            val agentContinuation = agentExecutionGraph.agentContinuation
            val agentContinuationExecution = agentExecutionGraph.agentContinuationExecution
            val activePerceptionPort = remember {
                AndroidActivePerceptionPort(applicationContext, runtime.perception)
            }
            val persistentGoalExecutive = remember {
                planningCoordinator.persistentGoalExecutive(
                    observation = activePerceptionPort
                )
            }
            val goalSatisfactionVerifier = remember {
                planningCoordinator.goalSatisfactionVerifier()
            }
            val autonomousPlanRunner = remember {
                AutonomousGovernedPlanRunner(
                    planner = planner,
                    plans = runtime.plans,
                    goals = persistentGoalExecutive,
                    completionVerifier = goalSatisfactionVerifier
                )
            }
            val autonomousGoalScheduler = remember {
                AutonomousGoalScheduler(
                    runGoal = persistentGoalExecutive::runNext,
                    resourceAllowed = { governor.allows(agentCount = 1) },
                    runPlan = autonomousPlanRunner::runBounded
                )
            }
            val planHistory = remember { SovereignPlanHistory(runtime.plans) }
            val recoveryConsole = remember {
                io.amper.neuroos.core.SovereignRecoveryConsole(runtime.plans, planner)
            }
            val restoredApproval = remember { assistant.restorePendingApproval() }
            val restoredPlan = remember { planner.latest() }
            val initialConversationId = remember {
                val primary = runtime.conversations.primary()
                val persistedConversation = conversationUiPreferences
                    .getString("active_conversation_id", null)
                    ?.let { io.amper.neuroos.core.ConversationId(it) }
                    ?.takeIf { candidate ->
                        candidate == primary ||
                            runtime.conversations.recent(candidate, limit = 1).isNotEmpty()
                    }
                restoredApproval?.conversationId
                    ?: persistedConversation
                    ?: restoredPlan?.conversationId
                    ?: runtime.conversations.recentThreads(limit = 1)
                        .firstOrNull()
                        ?.conversationId
                    ?: primary
            }
            val initialConversationTurns = remember {
                runtime.conversations.recent(initialConversationId, limit = 12)
            }
            val initialProfileModel = remember { catalog.list().firstOrNull() }
            val executionLanes = remember { AmperExecutionLanes() }
            val amneQualificationBusyState = remember {
                mutableStateOf(amperCore.nativeRuntimePackaged())
            }
            val amneQualificationStatusState = remember {
                mutableStateOf(
                    if (amperCore.nativeRuntimePackaged()) {
                        "AMPER Core native · automatic qualification + benchmark queued"
                    } else {
                        "AMPER Core native runtime not packaged in this APK"
                    }
                )
            }
            DisposableEffect(Unit) {
                AndroidReflexMaintenanceProcessRegistry.register(
                    reflexMaintenanceCoordinator
                )
                AndroidAgentContinuationProcessRegistry.register(
                    agentContinuationExecution
                )
                reflexJobScheduler.reconcile(
                    runtime.reflexLearningMaintenanceQueue.pending()
                )
                executionLanes.executeMaintenance {
                    runCatching { reflexLifecycle.maintain() }
                }
                if (amperCore.nativeRuntimePackaged()) {
                    executionLanes.executeMaintenance {
                        val startedNs = System.nanoTime()
                        val result = amperCore.bootstrapNativeAdmission()
                        val wallMs =
                            (System.nanoTime() - startedNs) / 1_000_000L
                        runOnUiThread {
                            amneQualificationBusyState.value = false
                            result.fold(
                                onSuccess = { report ->
                                    val matrixSummary = report.matrixCoverage
                                        .sortedBy { it.ordinal }
                                        .joinToString(" · ") {
                                            it.name.removePrefix("MATVEC_")
                                        }
                                    amneQualificationStatusState.value =
                                        "AMPER Core auto-admission · numeric=" +
                                            if (report.qualificationPassed) "PASS" else "FAIL" +
                                            " · native " +
                                            report.admittedPrimitives.size + "/" +
                                            report.benchmarkedPrimitives.size +
                                            " primitives" +
                                            if (matrixSummary.isNotBlank()) {
                                                " · matrix $matrixSummary"
                                            } else {
                                                " · no native matrix admitted"
                                            } +
                                            " · wall ${wallMs}ms"
                                },
                                onFailure = { error ->
                                    amneQualificationStatusState.value =
                                        "AMPER Core auto-admission failed · " +
                                            (error.message
                                                ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
                reflexMaintenanceLoop.start()
                onDispose {
                    AndroidAgentContinuationProcessRegistry.unregister(
                        agentContinuationExecution
                    )
                    AndroidReflexMaintenanceProcessRegistry.unregister(
                        reflexMaintenanceCoordinator
                    )
                    reflexMaintenanceLoop.close()
                    runCatching {
                        executionLanes.executeInteractive { titan.unloadAll() }
                    }
                    executionLanes.close()
                }
            }

            var importStatus by remember { mutableStateOf("Model catalog ready") }
            var amiCompileBusy by remember { mutableStateOf(false) }
            var amiStatus by remember {
                mutableStateOf(
                    "AMI mobile compiler ready · SOURCE_EXACT foundation preservation"
                )
            }
            var amneQualificationBusy by amneQualificationBusyState
            var amneQualificationStatus by amneQualificationStatusState
            var importCodeGeneration by remember { mutableStateOf(false) }
            var importPlanning by remember { mutableStateOf(false) }
            var importVision by remember { mutableStateOf(false) }
            var importAudioUnderstanding by remember { mutableStateOf(false) }
            var pendingImportProfile by remember { mutableStateOf(ModelCapabilityProfile()) }
            var profileModelId by remember { mutableStateOf<ModelId?>(initialProfileModel?.descriptor?.id) }
            var profileCodeGeneration by remember {
                mutableStateOf(initialProfileModel?.descriptor?.capabilities?.contains(TitanCapabilities.CODE_GENERATION) == true)
            }
            var profilePlanning by remember {
                mutableStateOf(initialProfileModel?.descriptor?.capabilities?.contains(TitanCapabilities.PLANNING) == true)
            }
            var profileVision by remember {
                mutableStateOf(initialProfileModel?.descriptor?.capabilities?.contains(TitanCapabilities.VISION) == true)
            }
            var profileAudioUnderstanding by remember {
                mutableStateOf(
                    initialProfileModel?.descriptor?.capabilities
                        ?.contains(TitanCapabilities.AUDIO_UNDERSTANDING) == true
                )
            }
            var profileStatus by remember { mutableStateOf("Select an installed model to review its profile") }
            var pendingProjectorModelId by remember { mutableStateOf<ModelId?>(null) }
            var projectorStatus by remember {
                mutableStateOf(
                    if (projectorCatalog.list().isEmpty()) {
                        "No mmproj pairing installed"
                    } else {
                        "${projectorCatalog.list().size} mmproj pairing(s) installed"
                    }
                )
            }
            var detachArmedModelId by remember { mutableStateOf<ModelId?>(null) }
            var coreFoundationModelId by remember {
                mutableStateOf(initialCoreFoundationState.activeModelId)
            }
            var modelSummary by remember {
                mutableStateOf(
                    catalog.list().joinToString { it.displayName }
                        .ifBlank { "No imported weight source" }
                )
            }
            var hasModel by remember {
                mutableStateOf(initialCoreFoundationState.activeModelId != null)
            }
            var prompt by remember {
                mutableStateOf("Bạn là ai và đang dùng lõi suy luận nào?")
            }
            var conversationId by remember {
                mutableStateOf(initialConversationId)
            }
            var pendingApproval by remember {
                mutableStateOf<SovereignAssistantTurnResult.PendingApproval?>(restoredApproval)
            }
            var inferenceStatus by remember {
                mutableStateOf(
                    restoredApproval?.let {
                        "Restored pending action: ${it.proposal.capability.value} · not executed"
                    } ?: if (initialConversationTurns.isNotEmpty()) {
                        "Restored conversation ${initialConversationId.value.take(12)} · " +
                            "${initialConversationTurns.size} recent turn(s) · AMPER Core ready"
                    } else if (hasRuntimeBackend) {
                        "AMPER Single-Core ready · " + amperCore.id
                    } else {
                        "AMPER Core runtime unavailable"
                    }
                )
            }
            var inferenceOutput by remember {
                mutableStateOf(
                    if (restoredApproval != null) {
                        "AMPER restored an unexecuted side-effect approval checkpoint. Review it before approving or rejecting."
                    } else {
                        initialConversationTurns.joinToString("\n\n") { turn ->
                            "${turn.role.name}: ${turn.text}"
                        }
                    }
                )
            }
            var routeObservation by remember {
                mutableStateOf(titan.latestRouteObservation())
            }
            DisposableEffect(conversationId) {
                conversationUiPreferences.edit()
                    .putString("active_conversation_id", conversationId.value)
                    .apply()
                onDispose { }
            }
            var smokeTestBusy by remember { mutableStateOf(false) }
            var smokeTestStatus by remember {
                mutableStateOf("Physical local inference smoke test not run")
            }
            var smokeTestOutput by remember { mutableStateOf("") }
            var activeInferenceCancellation by remember {
                mutableStateOf<InferenceCancellationSignal?>(null)
            }
            var perceptionStatus by remember {
                mutableStateOf("Live perception idle · capture is user initiated")
            }
            var screenVisionStatus by remember {
                mutableStateOf(screenVisionSession.status().detail)
            }
            var voiceSessionStatus by remember {
                mutableStateOf(voiceConversationSession.status().detail)
            }
            var pendingInferenceAttachments by remember {
                mutableStateOf<List<InferenceAttachment>>(emptyList())
            }
            var attachmentStatus by remember {
                mutableStateOf("No multimodal attachment selected")
            }
            var liveAudioCaptureInProgress by remember { mutableStateOf(false) }
            var planGoal by remember {
                mutableStateOf("Check device status and report AMPER runtime resources")
            }
            var activePlan by remember { mutableStateOf(restoredPlan) }
            var planStatus by remember {
                mutableStateOf(
                    restoredPlan?.let { "Restored encrypted plan ${it.id.value.take(8)}" }
                        ?: "No persistent plan restored"
                )
            }
            var autonomyLoopEnabled by remember { mutableStateOf(false) }
            var autonomyLoopStatus by remember {
                mutableStateOf("Autonomy scheduler stopped")
            }
            val autonomyLoop = remember {
                ProcessResidentAutonomyLoop(
                    scheduler = autonomousGoalScheduler,
                    conversationId = {
                        persistentGoalExecutive.current()?.conversationId
                            ?: runtime.conversations.primary()
                    },
                    onTick = { tick ->
                        runOnUiThread {
                            val checkpoint = tick.checkpointStage?.name ?: "none"
                            val planRun = tick.planRunStage?.name?.let { " · plan=$it" } ?: ""
                            autonomyLoopStatus =
                                "Autonomy ${tick.stage.name} · checkpoint=$checkpoint" +
                                    planRun + " · next=" + (tick.nextDelayMs / 1_000L) + "s"
                            tick.planId
                                ?.let(runtime.plans::load)
                                ?.let { planned ->
                                    activePlan = planned
                                    conversationId = planned.conversationId
                                    planStatus =
                                        "Autonomous scheduler handed off plan " +
                                            planned.id.value.take(8)
                                }
                        }
                    }
                )
            }
            DisposableEffect(autonomyLoop) {
                onDispose {
                    autonomyLoop.close()
                }
            }
            val report = remember { runtime.tick("bootstrap-self-check") }
            val budget = governor.currentBudget()
            val captureMicrophone: () -> Unit = {
                perceptionStatus = "Sampling microphone locally..."
                executionLanes.executeInteractive {
                    val result = perceptionCapture.captureMicrophone()
                    runOnUiThread {
                        result.fold(
                            onSuccess = { percept ->
                                perceptionStatus =
                                    "Audio percept captured · " + percept.payload
                            },
                            onFailure = { error ->
                                perceptionStatus =
                                    "Audio capture unavailable: " +
                                        (error.message ?: error::class.java.simpleName)
                            }
                        )
                    }
                }
            }

            val screenVisionConsentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data
                if (result.resultCode == RESULT_OK && data != null) {
                    screenVisionSession.start(result.resultCode, data).fold(
                        onSuccess = {
                            screenVisionStatus =
                                "Screen vision starting · Android sharing indicator/notification stays visible"
                        },
                        onFailure = { error ->
                            screenVisionStatus =
                                "Screen vision start failed: " +
                                    (error.message ?: error::class.java.simpleName)
                        }
                    )
                } else {
                    screenVisionStatus = "Screen vision consent cancelled; no capture started"
                }
            }

            val captureLiveAudioAttachment: () -> Unit = {
                if (!liveAudioCaptureInProgress) {
                    liveAudioCaptureInProgress = true
                    attachmentStatus =
                        "Recording 4 seconds of microphone audio in RAM for model-native understanding..."
                    executionLanes.executeInteractive {
                        val result = liveAudioCapture.capture()
                        runOnUiThread {
                            liveAudioCaptureInProgress = false
                            result.fold(
                                onSuccess = { encoded ->
                                    val attachment = encoded.toAttachment(
                                        displayName = "live-microphone-" +
                                            System.currentTimeMillis() + ".wav"
                                    )
                                    val next = pendingInferenceAttachments + attachment
                                    runCatching {
                                        io.amper.neuroos.core.MultimodalInferencePolicy
                                            .validateAttachments(next)
                                    }.fold(
                                        onSuccess = {
                                            pendingInferenceAttachments = next
                                            val metrics = encoded.metrics
                                            attachmentStatus =
                                                "Live audio attached · " +
                                                    metrics.durationMs + " ms · " +
                                                    metrics.sampleRateHz + " Hz · " +
                                                    attachment.lengthBytes + " bytes · " +
                                                    "RMS %.3f".format(metrics.rms)
                                        },
                                        onFailure = { error ->
                                            attachmentStatus =
                                                "Live audio attachment rejected: " +
                                                    (error.message
                                                        ?: error::class.java.simpleName)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    attachmentStatus =
                                        "Live audio capture failed: " +
                                            (error.message ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
            }

            val cameraLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.TakePicturePreview()
            ) { bitmap ->
                if (bitmap == null) {
                    perceptionStatus = "Camera capture cancelled"
                } else {
                    perceptionCapture.ingestBitmap(
                        modality = PerceptionModality.CAMERA,
                        bitmap = bitmap,
                        source = "android-camera-preview"
                    ).fold(
                        onSuccess = { percept ->
                            perceptionStatus = "Camera percept captured · " + percept.payload
                        },
                        onFailure = { error ->
                            perceptionStatus =
                                "Camera percept rejected: " +
                                    (error.message ?: error::class.java.simpleName)
                        }
                    )
                }
            }
            val cameraPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    cameraLauncher.launch(null)
                } else {
                    perceptionStatus = "Camera permission denied; no capture occurred"
                }
            }
            val cameraVisionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.TakePicturePreview()
            ) { bitmap ->
                if (bitmap == null) {
                    attachmentStatus =
                        "Camera vision capture cancelled; no image attachment was created"
                } else {
                    attachmentStatus =
                        "Encoding camera vision snapshot in RAM for model-native inference..."
                    executionLanes.executeInteractive {
                        val encoded = try {
                            AndroidCameraVisionAttachmentEncoder.encode(bitmap)
                        } finally {
                            if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                        runOnUiThread {
                            encoded.fold(
                                onSuccess = { frame ->
                                    val attachment = frame.toAttachment(
                                        displayName = "camera-vision-" +
                                            System.currentTimeMillis() + ".jpg"
                                    )
                                    val next = pendingInferenceAttachments + attachment
                                    runCatching {
                                        io.amper.neuroos.core.MultimodalInferencePolicy
                                            .validateAttachments(next)
                                    }.fold(
                                        onSuccess = {
                                            pendingInferenceAttachments = next
                                            attachmentStatus =
                                                "Camera vision attached · " +
                                                    frame.width + "x" + frame.height + " · JPEG q" +
                                                    frame.jpegQuality + " · " +
                                                    attachment.lengthBytes + " bytes · RAM only"
                                        },
                                        onFailure = { error ->
                                            attachmentStatus =
                                                "Camera vision attachment rejected: " +
                                                    (error.message ?: error::class.java.simpleName)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    attachmentStatus =
                                        "Camera vision encoding failed: " +
                                            (error.message ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
            }
            val cameraVisionPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    cameraVisionLauncher.launch(null)
                } else {
                    attachmentStatus =
                        "Camera permission denied; no model-native image was captured"
                }
            }
            val microphonePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    captureMicrophone()
                } else {
                    perceptionStatus = "Microphone permission denied; no capture occurred"
                }
            }
            val liveAudioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    captureLiveAudioAttachment()
                } else {
                    liveAudioCaptureInProgress = false
                    attachmentStatus =
                        "Microphone permission denied; no model-native audio was captured"
                }
            }
            val voiceSessionPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    voiceConversationSession.start().fold(
                        onSuccess = {
                            voiceSessionStatus =
                                "Voice session starting · foreground microphone notification will remain visible"
                        },
                        onFailure = { error ->
                            voiceSessionStatus =
                                "Voice session start failed: " +
                                    (error.message ?: error::class.java.simpleName)
                        }
                    )
                } else {
                    voiceSessionStatus =
                        "Microphone permission denied; voice session did not start"
                }
            }

            val imageAttachmentPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    attachmentStatus = "Loading image attachment locally..."
                    executionLanes.executeInteractive {
                        val result = attachmentLoader.load(uri, InferenceAttachmentKind.IMAGE)
                        runOnUiThread {
                            result.fold(
                                onSuccess = { attachment ->
                                    val next = pendingInferenceAttachments + attachment
                                    runCatching {
                                        io.amper.neuroos.core.MultimodalInferencePolicy
                                            .validateAttachments(next)
                                    }.fold(
                                        onSuccess = {
                                            pendingInferenceAttachments = next
                                            attachmentStatus =
                                                "Attached image: " + attachment.displayName +
                                                    " · " + attachment.lengthBytes + " bytes"
                                        },
                                        onFailure = { error ->
                                            attachmentStatus =
                                                "Attachment rejected: " +
                                                    (error.message ?: error::class.java.simpleName)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    attachmentStatus =
                                        "Image attachment unavailable: " +
                                            (error.message ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
            }
            val audioAttachmentPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    attachmentStatus = "Loading audio attachment locally..."
                    executionLanes.executeInteractive {
                        val result = attachmentLoader.load(uri, InferenceAttachmentKind.AUDIO)
                        runOnUiThread {
                            result.fold(
                                onSuccess = { attachment ->
                                    val next = pendingInferenceAttachments + attachment
                                    runCatching {
                                        io.amper.neuroos.core.MultimodalInferencePolicy
                                            .validateAttachments(next)
                                    }.fold(
                                        onSuccess = {
                                            pendingInferenceAttachments = next
                                            attachmentStatus =
                                                "Attached audio: " + attachment.displayName +
                                                    " · " + attachment.lengthBytes + " bytes"
                                        },
                                        onFailure = { error ->
                                            attachmentStatus =
                                                "Attachment rejected: " +
                                                    (error.message ?: error::class.java.simpleName)
                                        }
                                    )
                                },
                                onFailure = { error ->
                                    attachmentStatus =
                                        "Audio attachment unavailable: " +
                                            (error.message ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
            }

            val projectorPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val targetId = pendingProjectorModelId
                    val target = targetId?.let(catalog::get)
                    if (target == null) {
                        projectorStatus = "mmproj import rejected: selected model is no longer installed"
                    } else {
                        val expectedKinds = buildSet {
                            if (TitanCapabilities.VISION in target.descriptor.capabilities) {
                                add(InferenceAttachmentKind.IMAGE)
                            }
                            if (TitanCapabilities.AUDIO_UNDERSTANDING in target.descriptor.capabilities) {
                                add(InferenceAttachmentKind.AUDIO)
                            }
                        }
                        if (expectedKinds.isEmpty()) {
                            projectorStatus =
                                "mmproj import rejected: model has no explicit vision/audio capability"
                        } else {
                            projectorImporter.persistReadPermission(uri).fold(
                                onSuccess = {
                                    projectorStatus =
                                        "Inspecting mmproj GGUF and computing SHA-256..."
                                    executionLanes.executeInteractive {
                                        val result = projectorImporter.install(
                                            model = target,
                                            uri = uri,
                                            expectedKinds = expectedKinds
                                        )
                                        runOnUiThread {
                                            result.fold(
                                                onSuccess = { projector ->
                                                    pendingProjectorModelId = null
                                                    projectorStatus =
                                                        "Paired ${projector.displayName} with " +
                                                            "${target.displayName} · " +
                                                            projector.expectedKinds.joinToString {
                                                                it.name.lowercase()
                                                            } +
                                                            " · sha256 " +
                                                            projector.sha256.take(12)
                                                },
                                                onFailure = { error ->
                                                    projectorStatus =
                                                        "mmproj rejected: " +
                                                            (error.message
                                                                ?: error::class.java.simpleName)
                                                }
                                            )
                                        }
                                    }
                                },
                                onFailure = { error ->
                                    projectorStatus =
                                        "mmproj storage permission failed: " +
                                            (error.message ?: error::class.java.simpleName)
                                }
                            )
                        }
                    }
                }
            }

            val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    val profile = pendingImportProfile
                    importer.persistReadPermission(uri).fold(
                        onSuccess = {
                            importStatus = "Inspecting GGUF and computing SHA-256..."
                            executionLanes.executeInteractive {
                                val result = importer.install(uri, profile).mapCatching { model ->
                                    // Canonical production import: GGUF -> AMI2 -> AMNE2.
                                    ami2CompilationService.compileDirect(model).getOrThrow()
                                    titan.unloadAll().getOrThrow()
                                    coreFoundationController.activate(model.descriptor.id)
                                    coreFoundationPreferences.edit()
                                        .putString("active_source_model_id", model.descriptor.id.value)
                                        .apply()
                                    model
                                }
                                runOnUiThread {
                                    result.fold(
                                        onSuccess = { model ->
                                            val capabilities = model.descriptor.capabilities
                                                .sortedBy { it.value }
                                                .joinToString { it.value }
                                            coreFoundationModelId = model.descriptor.id
                                            importStatus =
                                                "AMPER Core foundation updated from ${model.displayName} · " +
                                                    "GGUF → AMI2 → AMNE2 · $capabilities"
                                            modelSummary = catalog.list().joinToString { it.displayName }
                                            hasModel = true
                                            profileModelId = model.descriptor.id
                                            profileCodeGeneration =
                                                model.descriptor.capabilities.contains(TitanCapabilities.CODE_GENERATION)
                                            profilePlanning =
                                                model.descriptor.capabilities.contains(TitanCapabilities.PLANNING)
                                            profileVision =
                                                model.descriptor.capabilities.contains(TitanCapabilities.VISION)
                                            profileAudioUnderstanding =
                                                model.descriptor.capabilities.contains(TitanCapabilities.AUDIO_UNDERSTANDING)
                                            profileStatus =
                                                "Active AMPER foundation: ${model.displayName}"
                                        },
                                        onFailure = { error ->
                                            importStatus =
                                                "AMPER foundation import rejected: " +
                                                    (error.message ?: error::class.java.simpleName)
                                        }
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            importStatus = "Storage permission failed: ${error.message ?: error::class.java.simpleName}"
                        }
                    )
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("AMPER", style = MaterialTheme.typography.headlineLarge)
                        Text("APEX–MUXER SOVEREIGN NEURO-OS")
                        Text("Kernel: ${report.kernelState}")
                        Text("Identity: ${report.selfIdentity}")
                        Text("Memory records: ${report.memoryRecords}")
                        Text("Core route: ${report.modelRoute}")
                        Text("Imported weight sources: $modelSummary")
                        Text(
                            "AMPER Core foundation: " +
                                (coreFoundationModelId
                                    ?.let { catalog.get(it)?.displayName }
                                    ?: "not initialized")
                        )
                        Text("Inference architecture: AMI + AMNE · single AMPER core")
                        Text("Titan budget: ${budget.memoryMb} MiB · thermal ${budget.thermalClass}")
                        Text("Governed tools: ${assistantCapabilities.joinToString(" · ") { it.value }}")

                        Text("AMNE mobile runtime", style = MaterialTheme.typography.titleMedium)
                        Text(amneQualificationStatus)
                        Button(
                            enabled = amperCore.nativeRuntimePackaged() && !amneQualificationBusy,
                            onClick = {
                                amneQualificationBusy = true
                                amneQualificationStatus =
                                    "AMPER Core · qualifying + benchmarking native primitives..."
                                executionLanes.executeMaintenance {
                                    val startedNs = System.nanoTime()
                                    val result = amperCore.bootstrapNativeAdmission()
                                    val wallMs =
                                        (System.nanoTime() - startedNs) / 1_000_000L
                                    runOnUiThread {
                                        amneQualificationBusy = false
                                        result.fold(
                                            onSuccess = { report ->
                                                val matrixSummary = report.matrixCoverage
                                                    .sortedBy { it.ordinal }
                                                    .joinToString(" · ") {
                                                        it.name.removePrefix("MATVEC_")
                                                    }
                                                amneQualificationStatus =
                                                    "AMPER Core admission · numeric=" +
                                                        if (report.qualificationPassed) "PASS" else "FAIL" +
                                                        " · native " +
                                                        report.admittedPrimitives.size + "/" +
                                                        report.benchmarkedPrimitives.size +
                                                        " primitives" +
                                                        if (matrixSummary.isNotBlank()) {
                                                            " · matrix $matrixSummary"
                                                        } else {
                                                            " · no native matrix admitted"
                                                        } +
                                                        " · wall ${wallMs}ms"
                                            },
                                            onFailure = { error ->
                                                amneQualificationStatus =
                                                    "AMNE qualification unavailable · " +
                                                        (error.message
                                                            ?: error::class.java.simpleName)
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Re-qualify AMPER Core")
                        }

                        Text("GGUF capability profile", style = MaterialTheme.typography.titleMedium)
                        Text("Reasoning · always enabled")
                        Row {
                            Checkbox(
                                checked = importCodeGeneration,
                                onCheckedChange = { importCodeGeneration = it }
                            )
                            Text("Code generation")
                        }
                        Row {
                            Checkbox(
                                checked = importPlanning,
                                onCheckedChange = { importPlanning = it }
                            )
                            Text("Planning")
                        }
                        Row {
                            Checkbox(
                                checked = importVision,
                                onCheckedChange = { importVision = it }
                            )
                            Text("Vision input")
                        }
                        Row {
                            Checkbox(
                                checked = importAudioUnderstanding,
                                onCheckedChange = { importAudioUnderstanding = it }
                            )
                            Text("Audio understanding")
                        }
                        Text(
                            "Specialist capabilities are explicit. Vision/audio also require an " +
                                "attachment-aware backend; model metadata alone never enables them."
                        )
                        Button(
                            onClick = {
                                pendingImportProfile = ModelCapabilityProfile(
                                    codeGeneration = importCodeGeneration,
                                    planning = importPlanning,
                                    vision = importVision,
                                    audioUnderstanding = importAudioUnderstanding
                                )
                                modelPicker.launch(arrayOf("*/*"))
                            }
                        ) {
                            Text("Import GGUF")
                        }
                        Text(importStatus)

                        val installedModels = catalog.list()
                        if (installedModels.isNotEmpty()) {
                            Text("Installed model profiles", style = MaterialTheme.typography.titleMedium)
                            Text("Select a model to edit capabilities, prefer it for soft routing, or safely detach it without deleting the source GGUF.")
                            installedModels.forEach { model ->
                                val capabilityLabel = model.descriptor.capabilities
                                    .sortedBy { it.value }
                                    .joinToString { it.value }
                                Button(
                                    onClick = {
                                        detachArmedModelId = null
                                        profileModelId = model.descriptor.id
                                        profileCodeGeneration = model.descriptor.capabilities.contains(TitanCapabilities.CODE_GENERATION)
                                        profilePlanning = model.descriptor.capabilities.contains(TitanCapabilities.PLANNING)
                                        profileVision = model.descriptor.capabilities.contains(TitanCapabilities.VISION)
                                        profileAudioUnderstanding =
                                            model.descriptor.capabilities.contains(TitanCapabilities.AUDIO_UNDERSTANDING)
                                        profileStatus = "Selected ${model.displayName} · $capabilityLabel"
                                    }
                                ) {
                                    Text("${model.displayName} · $capabilityLabel")
                                }
                            }

                            profileModelId?.let { selectedId ->
                                val selected = catalog.get(selectedId)
                                if (selected != null) {
                                    Text("Editing ${selected.displayName}")
                                    Text("Reasoning · always enabled")
                                    Row {
                                        Checkbox(
                                            checked = profileCodeGeneration,
                                            onCheckedChange = { profileCodeGeneration = it }
                                        )
                                        Text("Code generation")
                                    }
                                    Row {
                                        Checkbox(
                                            checked = profilePlanning,
                                            onCheckedChange = { profilePlanning = it }
                                        )
                                        Text("Planning")
                                    }
                                    Row {
                                        Checkbox(
                                            checked = profileVision,
                                            onCheckedChange = { profileVision = it }
                                        )
                                        Text("Vision input")
                                    }
                                    Row {
                                        Checkbox(
                                            checked = profileAudioUnderstanding,
                                            onCheckedChange = { profileAudioUnderstanding = it }
                                        )
                                        Text("Audio understanding")
                                    }
                                    Button(
                                        onClick = {
                                            val modelId = selectedId
                                            val requestedProfile = ModelCapabilityProfile(
                                                codeGeneration = profileCodeGeneration,
                                                planning = profilePlanning,
                                                vision = profileVision,
                                                audioUnderstanding = profileAudioUnderstanding
                                            )
                                            profileStatus = "Updating ${selected.displayName} capability metadata..."
                                            executionLanes.executeInteractive {
                                                val result = capabilityManager.reclassify(modelId, requestedProfile)
                                                runOnUiThread {
                                                    result.fold(
                                                        onSuccess = { updated ->
                                                            val capabilities = updated.descriptor.capabilities
                                                                .sortedBy { it.value }
                                                                .joinToString { it.value }
                                                            profileStatus = "Updated ${updated.displayName} · $capabilities · GGUF unchanged"
                                                        },
                                                        onFailure = { error ->
                                                            profileStatus = "Profile update failed: ${error.message ?: error::class.java.simpleName}"
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Text("Save installed model profile")
                                    }

                                    Text(
                                        "AMPER Mobile Intelligence (.ami)",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "Compile the selected GGUF directly into canonical AMI2. " +
                                            "SOURCE_EXACT preserves foundation tensor bytes; production execution uses AMNE2."
                                    )
                                    Button(
                                        enabled = !amiCompileBusy,
                                        onClick = {
                                            amiCompileBusy = true
                                            amiStatus =
                                                "AMI2 · compiling ${selected.displayName} · " +
                                                    "verifying source → semantic identity → streaming foundation copy..."
                                            val startedNs = System.nanoTime()
                                            executionLanes.executeMaintenance {
                                                val result = ami2CompilationService.compileDirect(selected)
                                                val wallMs =
                                                    (System.nanoTime() - startedNs) / 1_000_000L
                                                runOnUiThread {
                                                    amiCompileBusy = false
                                                    result.fold(
                                                        onSuccess = { stored ->
                                                            val sizeMiB =
                                                                stored.file.length().toDouble() /
                                                                    (1024.0 * 1024.0)
                                                            amiStatus =
                                                                "AMI2 PASS · ${stored.file.name} · " +
                                                                    "arch=${stored.architectureId} · " +
                                                                    "SOURCE_EXACT · %.1f MiB".format(sizeMiB) +
                                                                    " · semantic sha256 " +
                                                                    stored.semanticSha256.take(12) +
                                                                    " · foundation sha256 " +
                                                                    stored.foundationSha256.take(12) +
                                                                    " · verified all sections · wall ${wallMs}ms"
                                                        },
                                                        onFailure = { error ->
                                                            amiStatus =
                                                                "AMI2 compile rejected · " +
                                                                    (error.message
                                                                        ?: error::class.java.simpleName) +
                                                                    " · source GGUF unchanged"
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            if (amiCompileBusy) {
                                                "Compiling GGUF → AMI2..."
                                            } else {
                                                "Compile selected GGUF → AMI2"
                                            }
                                        )
                                    }
                                    Button(
                                        enabled = !amiCompileBusy,
                                        onClick = {
                                            amiCompileBusy = true
                                            amiStatus =
                                                "AMI FFN · loading verified .ami · binding layer 0 · bounded mmap execution..."
                                            executionLanes.executeMaintenance {
                                                val result = runCatching {
                                                    val stored = requireNotNull(
                                                        amiCompilationService.existing(selected)
                                                    ) {
                                                        "no verified AMI exists for selected GGUF; compile it first"
                                                    }
                                                    val graph = AmiTensorGraphReader()
                                                        .read(stored.loaded)
                                                        .getOrThrow()
                                                    val metadata =
                                                        AmiPreservedGgufMetadataReader()
                                                            .read(stored.loaded)
                                                            .getOrThrow()
                                                    val plan = AmiDecoderFfnPlanner.plan(
                                                        graph = graph,
                                                        metadata = metadata,
                                                        layerIndex = 0
                                                    ).getOrThrow()
                                                    val input = FloatArray(plan.hiddenSize) { index ->
                                                        ((index % 29) - 14).toFloat() * 0.01f
                                                    }
                                                    val hardware =
                                                        AndroidAmiHardwareProfiler(
                                                            this@MainActivity
                                                        ).snapshot()
                                                    AmiDecoderFfnExecutor().execute(
                                                        loaded = stored.loaded,
                                                        graph = graph,
                                                        plan = plan,
                                                        input = input,
                                                        hardware = hardware
                                                    ).getOrThrow()
                                                }
                                                runOnUiThread {
                                                    amiCompileBusy = false
                                                    result.fold(
                                                        onSuccess = { execution ->
                                                            val trace = execution.trace
                                                            val mappedMiB =
                                                                trace.mappedBytes.toDouble() /
                                                                    (1024.0 * 1024.0)
                                                            val backends = listOf(
                                                                trace.normBackendId,
                                                                trace.gateBackendId,
                                                                trace.upBackendId,
                                                                trace.activationBackendId,
                                                                trace.downBackendId
                                                            ).distinct().joinToString(",")
                                                            val l1 = execution.output.fold(0.0) {
                                                                    acc,
                                                                    value ->
                                                                acc + kotlin.math.abs(
                                                                    value.toDouble()
                                                                )
                                                            }
                                                            amiStatus =
                                                                "AMI FFN PASS · layer=${trace.layerIndex} · " +
                                                                    "hidden=${trace.hiddenSize} · " +
                                                                    "ffn=${trace.feedForwardSize} · " +
                                                                    "mapped=%.2f MiB".format(mappedMiB) +
                                                                    " · windows=${trace.matrixWindows} · " +
                                                                    "backend=$backends · " +
                                                                    "L1=%.4f".format(l1) +
                                                                    " · wall=${trace.wallTimeMs}ms"
                                                        },
                                                        onFailure = { error ->
                                                            amiStatus =
                                                                "AMI FFN rejected · " +
                                                                    (error.message
                                                                        ?: error::class.java.simpleName) +
                                                                    " · no tensor was guessed or rewritten"
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            if (amiCompileBusy) {
                                                "AMI work in progress..."
                                            } else {
                                                "Run AMI layer-0 FFN probe"
                                            }
                                        )
                                    }
                                    Button(
                                        enabled = !amiCompileBusy,
                                        onClick = {
                                            amiCompileBusy = true
                                            amiStatus =
                                                "AMI decoder · loading layer 0 attention + KV + FFN · 2-token causal probe..."
                                            executionLanes.executeMaintenance {
                                                val result = runCatching {
                                                    val stored = requireNotNull(
                                                        amiCompilationService.existing(selected)
                                                    ) {
                                                        "no verified AMI exists for selected GGUF; compile it first"
                                                    }
                                                    val graph = AmiTensorGraphReader()
                                                        .read(stored.loaded)
                                                        .getOrThrow()
                                                    val metadata =
                                                        AmiPreservedGgufMetadataReader()
                                                            .read(stored.loaded)
                                                            .getOrThrow()
                                                    val plan = AmiDecoderLayerPlanner.plan(
                                                        graph = graph,
                                                        metadata = metadata,
                                                        layerIndex = 0
                                                    ).getOrThrow()
                                                    val hardware =
                                                        AndroidAmiHardwareProfiler(
                                                            this@MainActivity
                                                        ).snapshot()
                                                    val cache = AmiLayerKvCache(
                                                        layerIndex = 0,
                                                        kvWidth = plan.attention.kvWidth,
                                                        maxTokens = 2
                                                    )
                                                    val executor = AmiDecoderLayerExecutor()
                                                    val firstInput =
                                                        FloatArray(plan.attention.hiddenSize) { index ->
                                                            ((index % 31) - 15).toFloat() * 0.01f
                                                        }
                                                    executor.execute(
                                                        loaded = stored.loaded,
                                                        graph = graph,
                                                        plan = plan,
                                                        input = firstInput,
                                                        position = 0,
                                                        kvCache = cache,
                                                        hardware = hardware
                                                    ).getOrThrow()
                                                    val secondInput =
                                                        FloatArray(plan.attention.hiddenSize) { index ->
                                                            ((index % 37) - 18).toFloat() * 0.008f
                                                        }
                                                    executor.execute(
                                                        loaded = stored.loaded,
                                                        graph = graph,
                                                        plan = plan,
                                                        input = secondInput,
                                                        position = 1,
                                                        kvCache = cache,
                                                        hardware = hardware
                                                    ).getOrThrow()
                                                }
                                                runOnUiThread {
                                                    amiCompileBusy = false
                                                    result.fold(
                                                        onSuccess = { execution ->
                                                            val attention = execution.attentionTrace
                                                            val ffn = execution.ffnTrace
                                                            val mappedMiB =
                                                                (
                                                                    attention.mappedBytes +
                                                                        ffn.mappedBytes
                                                                    ).toDouble() /
                                                                    (1024.0 * 1024.0)
                                                            val backends = listOf(
                                                                attention.normBackendId,
                                                                attention.queryBackendId,
                                                                attention.keyBackendId,
                                                                attention.valueBackendId,
                                                                attention.ropeBackendId,
                                                                attention.dotBackendId,
                                                                attention.softmaxBackendId,
                                                                attention.outputBackendId,
                                                                ffn.normBackendId,
                                                                ffn.gateBackendId,
                                                                ffn.upBackendId,
                                                                ffn.activationBackendId,
                                                                ffn.downBackendId
                                                            ).distinct().joinToString(",")
                                                            val l1 = execution.output.fold(0.0) {
                                                                    acc,
                                                                    value ->
                                                                acc + kotlin.math.abs(
                                                                    value.toDouble()
                                                                )
                                                            }
                                                            amiStatus =
                                                                "AMI DECODER PASS · layer=0 · " +
                                                                    "heads=${attention.headCount}/kv${attention.kvHeadCount} · " +
                                                                    "head_dim=${attention.headDimension} · " +
                                                                    "context=${attention.contextTokens} · " +
                                                                    "mapped=%.2f MiB".format(mappedMiB) +
                                                                    " · windows=${attention.matrixWindows + ffn.matrixWindows} · " +
                                                                    "backend=$backends · " +
                                                                    "L1=%.4f".format(l1) +
                                                                    " · attn=${attention.wallTimeMs}ms" +
                                                                    " · ffn=${ffn.wallTimeMs}ms"
                                                        },
                                                        onFailure = { error ->
                                                            amiStatus =
                                                                "AMI DECODER rejected · " +
                                                                    (error.message
                                                                        ?: error::class.java.simpleName) +
                                                                    " · llama fallback remains active"
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            if (amiCompileBusy) {
                                                "AMI work in progress..."
                                            } else {
                                                "Run AMI layer-0 full decoder probe"
                                            }
                                        )
                                    }
                                    Text(amiStatus)

                                    val projector = projectorCatalog.get(selectedId)
                                    val hasMultimodalProfile =
                                        TitanCapabilities.VISION in selected.descriptor.capabilities ||
                                            TitanCapabilities.AUDIO_UNDERSTANDING in selected.descriptor.capabilities
                                    if (hasMultimodalProfile) {
                                        Text(
                                            projector?.let {
                                                "Paired mmproj: ${it.displayName} · " +
                                                    it.expectedKinds.joinToString {
                                                        kind -> kind.name.lowercase()
                                                    } +
                                                    " · sha256 " + it.sha256.take(12)
                                            } ?: "No mmproj paired with this model"
                                        )
                                        Text(
                                            "The pairing stores only verified metadata and the user-owned " +
                                                "document locator. Native libmtmd probes the projector again " +
                                                "before inference."
                                        )
                                        Button(
                                            onClick = {
                                                pendingProjectorModelId = selectedId
                                                projectorStatus =
                                                    "Choose the mmproj GGUF paired with ${selected.displayName}"
                                                projectorPicker.launch(arrayOf("*/*"))
                                            }
                                        ) {
                                            Text(
                                                if (projector == null) {
                                                    "Attach mmproj"
                                                } else {
                                                    "Replace mmproj"
                                                }
                                            )
                                        }
                                        if (projector != null) {
                                            Button(
                                                onClick = {
                                                    val removed = runCatching {
                                                        projectorCatalog.remove(selectedId)
                                                    }
                                                    projectorStatus = removed.fold(
                                                        onSuccess = {
                                                            if (it) {
                                                                "mmproj pairing removed; source file was not deleted"
                                                            } else {
                                                                "No mmproj pairing existed"
                                                            }
                                                        },
                                                        onFailure = { error ->
                                                            "Failed to remove mmproj pairing: " +
                                                                (error.message
                                                                    ?: error::class.java.simpleName)
                                                        }
                                                    )
                                                }
                                            ) {
                                                Text("Remove mmproj pairing")
                                            }
                                        }
                                    } else {
                                        Text(
                                            "Enable and save Vision or Audio understanding before pairing mmproj."
                                        )
                                    }
                                    Text(projectorStatus)

                                    Button(
                                        enabled = coreFoundationModelId != selectedId,
                                        onClick = {
                                            profileStatus =
                                                "Compiling and activating ${selected.displayName} as the single AMPER foundation..."
                                            executionLanes.executeInteractive {
                                                val result = runCatching {
                                                    ami2CompilationService.compileDirect(selected).getOrThrow()
                                                    titan.unloadAll().getOrThrow()
                                                    coreFoundationController.activate(selectedId)
                                                    coreFoundationPreferences.edit()
                                                        .putString("active_source_model_id", selectedId.value)
                                                        .apply()
                                                    selected
                                                }
                                                runOnUiThread {
                                                    result.fold(
                                                        onSuccess = { activated ->
                                                            coreFoundationModelId = activated.descriptor.id
                                                            hasModel = true
                                                            profileStatus =
                                                                "AMPER Core foundation active · ${activated.displayName} · AMI2/AMNE2 production"
                                                        },
                                                        onFailure = { error ->
                                                            profileStatus =
                                                                "Foundation activation failed: " +
                                                                    (error.message ?: error::class.java.simpleName)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Text(
                                            if (coreFoundationModelId == selectedId) {
                                                "Active AMPER foundation"
                                            } else {
                                                "Use as AMPER foundation"
                                            }
                                        )
                                    }

                                    if (detachArmedModelId != selectedId) {
                                        Button(
                                            onClick = {
                                                detachArmedModelId = selectedId
                                                profileStatus = "Review detach for ${selected.displayName}; source GGUF will not be deleted"
                                            }
                                        ) {
                                            Text("Review detach from AMPER")
                                        }
                                    } else {
                                        Text("Detach removes this imported weight source from AMPER. The original GGUF file remains untouched; the live AMPER core still exposes at most one foundation.")
                                        Button(
                                            onClick = {
                                                profileStatus = "Detaching ${selected.displayName} weight source from AMPER..."
                                                executionLanes.executeInteractive {
                                                    val result = detachManager.detach(selectedId)
                                                    runOnUiThread {
                                                        result.fold(
                                                            onSuccess = { detached ->
                                                                detachArmedModelId = null
                                                                runCatching {
                                                                    projectorCatalog.remove(detached.descriptor.id)
                                                                }
                                                                if (pendingProjectorModelId == detached.descriptor.id) {
                                                                    pendingProjectorModelId = null
                                                                }
                                                                val remaining = catalog.list()
                                                                if (coreFoundationModelId == detached.descriptor.id) {
                                                                    val restored =
                                                                        coreFoundationController.restore()
                                                                    coreFoundationModelId =
                                                                        restored.activeModelId
                                                                    restored.activeModelId?.let { nextId ->
                                                                        coreFoundationPreferences.edit()
                                                                            .putString(
                                                                                "active_source_model_id",
                                                                                nextId.value
                                                                            )
                                                                            .apply()
                                                                    } ?: coreFoundationPreferences.edit()
                                                                        .remove("active_source_model_id")
                                                                        .apply()
                                                                }
                                                                modelSummary = remaining.joinToString { it.displayName }
                                                                    .ifBlank { "No imported weight source" }
                                                                hasModel = coreFoundationModelId != null
                                                                val next = coreFoundationModelId
                                                                    ?.let(catalog::get)
                                                                    ?: remaining.firstOrNull()
                                                                profileModelId = next?.descriptor?.id
                                                                profileCodeGeneration = next?.descriptor?.capabilities
                                                                    ?.contains(TitanCapabilities.CODE_GENERATION) == true
                                                                profilePlanning = next?.descriptor?.capabilities
                                                                    ?.contains(TitanCapabilities.PLANNING) == true
                                                                profileVision = next?.descriptor?.capabilities
                                                                    ?.contains(TitanCapabilities.VISION) == true
                                                                profileAudioUnderstanding = next?.descriptor?.capabilities
                                                                    ?.contains(TitanCapabilities.AUDIO_UNDERSTANDING) == true
                                                                profileStatus =
                                                                    "Detached weight source ${detached.displayName}; AMPER Core foundation=" +
                                                                        (coreFoundationModelId
                                                                            ?.let { catalog.get(it)?.displayName }
                                                                            ?: "none")
                                                            },
                                                            onFailure = { error ->
                                                                detachArmedModelId = null
                                                                profileStatus = "Detach failed; model kept attached: ${error.message ?: error::class.java.simpleName}"
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("Confirm detach from AMPER")
                                        }
                                        Button(
                                            onClick = {
                                                detachArmedModelId = null
                                                profileStatus = "Detach cancelled; ${selected.displayName} remains attached"
                                            }
                                        ) {
                                            Text("Keep model attached")
                                        }
                                    }
                                }
                            }
                            Text(profileStatus)
                        }

                        Text("Live perception", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Camera, this AMPER screen, microphone signal and device sensors are " +
                                "sampled only when you tap a capture control. Raw media is not persisted."
                        )
                        Button(
                            onClick = {
                                if (checkSelfPermission(Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    cameraLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        ) {
                            Text("Capture camera percept")
                        }
                        Button(
                            onClick = {
                                perceptionCapture.captureActivityScreen(this@MainActivity).fold(
                                    onSuccess = { percept ->
                                        perceptionStatus = "Screen percept captured · " + percept.payload
                                    },
                                    onFailure = { error ->
                                        perceptionStatus =
                                            "Screen capture unavailable: " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Capture AMPER screen percept")
                        }
                        Button(
                            onClick = {
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    captureMicrophone()
                                } else {
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ) {
                            Text("Sample microphone percept")
                        }
                        Button(
                            onClick = {
                                perceptionStatus = "Sampling device sensors..."
                                executionLanes.executeInteractive {
                                    val result = perceptionCapture.captureSensors()
                                    runOnUiThread {
                                        result.fold(
                                            onSuccess = { percept ->
                                                perceptionStatus =
                                                    "Sensor percept captured · " + percept.payload
                                            },
                                            onFailure = { error ->
                                                perceptionStatus =
                                                    "Sensor capture unavailable: " +
                                                        (error.message ?: error::class.java.simpleName)
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Sample sensor fusion")
                        }
                        Text(perceptionStatus)

                        Text("Device screen vision", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Global screen vision uses Android MediaProjection. Android shows the system " +
                                "consent UI before every new session, and a foreground-service notification " +
                                "remains visible while capture is active. Frames stay in RAM only."
                        )
                        Button(
                            onClick = {
                                screenVisionStatus = "Requesting Android screen-share consent..."
                                screenVisionConsentLauncher.launch(
                                    screenVisionSession.createConsentIntent()
                                )
                            }
                        ) {
                            Text("Start device screen vision")
                        }
                        Button(
                            onClick = {
                                screenVisionSession.latestFrameAttachment().fold(
                                    onSuccess = { attachment ->
                                        val next = pendingInferenceAttachments + attachment
                                        runCatching {
                                            io.amper.neuroos.core.MultimodalInferencePolicy
                                                .validateAttachments(next)
                                        }.fold(
                                            onSuccess = {
                                                pendingInferenceAttachments = next
                                                screenVisionStatus =
                                                    "Attached fresh device-screen frame · " +
                                                        attachment.lengthBytes + " bytes · RAM only"
                                                attachmentStatus =
                                                    "Live screen frame attached for the next inference turn"
                                            },
                                            onFailure = { error ->
                                                screenVisionStatus =
                                                    "Screen frame rejected: " +
                                                        (error.message
                                                            ?: error::class.java.simpleName)
                                            }
                                        )
                                    },
                                    onFailure = { error ->
                                        val current = screenVisionSession.status()
                                        screenVisionStatus =
                                            current.detail + " · " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Attach current device screen")
                        }
                        Button(
                            onClick = {
                                screenVisionSession.stop().fold(
                                    onSuccess = {
                                        screenVisionStatus =
                                            "Stopping screen vision; captured frame buffer will be cleared"
                                    },
                                    onFailure = { error ->
                                        screenVisionStatus =
                                            "Screen vision stop failed: " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Stop device screen vision")
                        }
                        Text(screenVisionStatus)

                        Text("Governed voice conversation session", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "This is an explicit foreground microphone session with local voice activity " +
                                "detection. Only the newest completed utterance is retained in RAM. " +
                                "Nothing is sent to the model until you attach that utterance."
                        )
                        Button(
                            onClick = {
                                if (
                                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    voiceConversationSession.start().fold(
                                        onSuccess = {
                                            voiceSessionStatus =
                                                "Voice session starting · speak normally; local VAD will segment utterances"
                                        },
                                        onFailure = { error ->
                                            voiceSessionStatus =
                                                "Voice session start failed: " +
                                                    (error.message ?: error::class.java.simpleName)
                                        }
                                    )
                                } else {
                                    voiceSessionStatus =
                                        "Microphone permission is required for the governed voice session"
                                    voiceSessionPermissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                        ) {
                            Text("Start governed voice session")
                        }
                        Button(
                            onClick = {
                                voiceConversationSession.latestUtteranceAttachment().fold(
                                    onSuccess = { attachment ->
                                        val next = pendingInferenceAttachments + attachment
                                        runCatching {
                                            io.amper.neuroos.core.MultimodalInferencePolicy
                                                .validateAttachments(next)
                                        }.fold(
                                            onSuccess = {
                                                pendingInferenceAttachments = next
                                                voiceConversationSession.clearLatestUtterance()
                                                voiceSessionStatus =
                                                    "Latest utterance attached and removed from voice-session buffer"
                                                attachmentStatus =
                                                    "Voice-session audio attached for the next inference turn"
                                            },
                                            onFailure = { error ->
                                                voiceSessionStatus =
                                                    "Voice utterance rejected: " +
                                                        (error.message ?: error::class.java.simpleName)
                                            }
                                        )
                                    },
                                    onFailure = { error ->
                                        val current = voiceConversationSession.status()
                                        voiceSessionStatus =
                                            current.detail + " · " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Attach latest voice utterance")
                        }
                        Button(
                            onClick = {
                                voiceConversationSession.clearLatestUtterance()
                                voiceSessionStatus =
                                    voiceConversationSession.status().detail
                            }
                        ) {
                            Text("Clear latest voice utterance")
                        }
                        Button(
                            onClick = {
                                voiceConversationSession.stop().fold(
                                    onSuccess = {
                                        voiceSessionStatus =
                                            "Stopping voice session; microphone foreground service is ending"
                                    },
                                    onFailure = { error ->
                                        voiceSessionStatus =
                                            "Voice session stop failed: " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Stop governed voice session")
                        }
                        Text(voiceSessionStatus)

                        Text("Live multimodal context fusion", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Fusion is RAM-only and explicit. It snapshots the freshest available " +
                                "device-screen frame and completed voice utterance, then combines them " +
                                "with pending camera, live-microphone, or user-selected attachments. " +
                                "Fresh live sources replace only older payloads from the same source."
                        )
                        Button(
                            onClick = {
                                val screen = screenVisionSession.latestFrameAttachment()
                                val voice = voiceConversationSession.latestUtteranceAttachment()
                                val fresh = buildList {
                                    screen.getOrNull()?.let { add(it) }
                                    voice.getOrNull()?.let { add(it) }
                                }

                                LiveContextFusion.fuse(
                                    existing = pendingInferenceAttachments,
                                    fresh = fresh
                                ).fold(
                                    onSuccess = { bundle ->
                                        pendingInferenceAttachments = bundle.attachments
                                        if (voice.isSuccess) {
                                            voiceConversationSession.clearLatestUtterance()
                                        }
                                        val capabilityLabel = bundle.requiredCapabilities
                                            .sortedBy { it.value }
                                            .joinToString { it.value }
                                        attachmentStatus =
                                            "Live context fused · sources=" +
                                                bundle.sources.joinToString {
                                                    it.name.lowercase()
                                                } +
                                                " · bytes=" + bundle.totalBytes +
                                                " · " +
                                                (if (bundle.crossModal) {
                                                    "cross-modal"
                                                } else {
                                                    "single-modality"
                                                }) +
                                                " · capabilities=" + capabilityLabel
                                        screenVisionStatus =
                                            if (screen.isSuccess) {
                                                "Fresh device-screen frame fused into pending context"
                                            } else {
                                                screenVisionSession.status().detail
                                            }
                                        voiceSessionStatus =
                                            if (voice.isSuccess) {
                                                "Latest voice utterance fused and removed from voice-session buffer"
                                            } else {
                                                voiceConversationSession.status().detail
                                            }
                                    },
                                    onFailure = { error ->
                                        attachmentStatus =
                                            "Live context fusion unavailable: " +
                                                (error.message ?: error::class.java.simpleName)
                                    }
                                )
                            }
                        ) {
                            Text("Fuse latest live context")
                        }

                        Text("Model-native multimodal input", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Attachments are transient RAM-only request payloads. Titan requires " +
                                "explicit model capability and attachment-aware backend admission."
                        )
                        Button(onClick = { imageAttachmentPicker.launch(arrayOf("image/*")) }) {
                            Text("Attach image")
                        }
                        Button(
                            onClick = {
                                if (
                                    checkSelfPermission(Manifest.permission.CAMERA) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    cameraVisionLauncher.launch(null)
                                } else {
                                    attachmentStatus =
                                        "Camera permission is required for model-native vision capture"
                                    cameraVisionPermissionLauncher.launch(
                                        Manifest.permission.CAMERA
                                    )
                                }
                            }
                        ) {
                            Text("Capture camera for model-native vision")
                        }
                        Text(
                            "Camera vision uses Android's user-visible camera capture UI. The returned " +
                                "bitmap is compressed to a bounded JPEG entirely in RAM, attached only " +
                                "to the next inference turn, and never written to sovereign storage."
                        )
                        Button(onClick = { audioAttachmentPicker.launch(arrayOf("audio/*")) }) {
                            Text("Attach audio file")
                        }
                        Button(
                            enabled = !liveAudioCaptureInProgress,
                            onClick = {
                                if (
                                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    captureLiveAudioAttachment()
                                } else {
                                    liveAudioCaptureInProgress = true
                                    attachmentStatus =
                                        "Microphone permission is required for live audio understanding"
                                    liveAudioPermissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (liveAudioCaptureInProgress) {
                                    "Recording live audio..."
                                } else {
                                    "Capture live audio for model-native understanding"
                                }
                            )
                        }
                        Text(
                            "Live capture records a bounded 4-second 16 kHz mono PCM16 WAV in RAM. " +
                                "The audio is not saved to disk or conversation memory; it is released " +
                                "from UI state after the inference turn succeeds."
                        )
                        if (pendingInferenceAttachments.isNotEmpty()) {
                            pendingInferenceAttachments.forEach { attachment ->
                                Text(
                                    attachment.kind.name.lowercase() + " · " +
                                        attachment.source.name.lowercase() + " · " +
                                        attachment.displayName + " · " +
                                        attachment.lengthBytes + " bytes"
                                )
                            }
                            Button(
                                onClick = {
                                    pendingInferenceAttachments = emptyList()
                                    attachmentStatus = "Attachments cleared from RAM"
                                }
                            ) {
                                Text("Clear attachments")
                            }
                        }
                        Text(attachmentStatus)

                        Text("Conversation: ${conversationId.value.take(12)}")
                        Button(
                            onClick = {
                                val newThread = runtime.conversations.newConversation()
                                conversationId = newThread
                                pendingApproval = assistant.restorePendingApproval(newThread)
                                inferenceOutput = ""
                                pendingInferenceAttachments = emptyList()
                                attachmentStatus = "No multimodal attachment selected"
                                inferenceStatus = "New sovereign conversation started"
                            }
                        ) {
                            Text("New conversation")
                        }
                        Button(
                            onClick = {
                                val restored = assistant.restorePendingApproval()
                                pendingApproval = restored
                                if (restored != null) {
                                    conversationId = restored.conversationId
                                    inferenceStatus = "Restored pending action: ${restored.proposal.capability.value} · not executed"
                                    inferenceOutput = "AMPER restored an unexecuted side-effect approval checkpoint."
                                } else {
                                    inferenceStatus = "No restorable pending assistant action"
                                }
                            }
                        ) {
                            Text("Restore pending action")
                        }

                        SovereignConversationHistoryPanel(
                            conversations = runtime.conversations,
                            activeConversationId = conversationId,
                            onOpen = { openedId ->
                                conversationId = openedId
                                val restored = assistant.restorePendingApproval(openedId)
                                pendingApproval = restored
                                val turns = runtime.conversations.recent(openedId, 12)
                                inferenceOutput = turns.joinToString("\n\n") { turn ->
                                    "${turn.role.name}: ${turn.text}"
                                }
                                if (activePlan?.conversationId != openedId) {
                                    activePlan = null
                                    planStatus = "Conversation opened; no plan selected. Persisted plans were not changed."
                                }
                                inferenceStatus = if (restored != null) {
                                    "Opened persisted conversation ${openedId.value.take(12)} · pending action restored, not executed"
                                } else {
                                    "Opened persisted conversation ${openedId.value.take(12)} · ${turns.size} recent turn(s) · no inference or tool invoked"
                                }
                            }
                        )

                        ConversationInferenceProfilePanel(
                            conversations = runtime.conversations,
                            profiles = runtime.inferenceProfiles,
                            conversationId = conversationId
                        )

                        TitanRouteObservatoryPanel(routeObservation)

                        Text("Physical local inference smoke test")
                        Text(smokeTestStatus)
                        if (smokeTestOutput.isNotBlank()) {
                            Text(smokeTestOutput)
                        }
                        Button(
                            enabled = hasModel && hasRuntimeBackend && !smokeTestBusy,
                            onClick = {
                                smokeTestBusy = true
                                smokeTestStatus = "QUEUED · waiting for interactive inference lane..."
                                smokeTestOutput = ""
                                executionLanes.executeInteractive {
                                    runOnUiThread {
                                        smokeTestStatus =
                                            "RUNNING · AMPER Core admission → AMI/AMNE generation · single-core local inference..."
                                    }
                                    val startedNs = System.nanoTime()
                                    val result = inferencePort.infer(
                                        InferenceRequest(
                                            prompt = "Reply with one short sentence confirming local inference is working.",
                                            maxOutputTokens = 32,
                                            temperature = 0.7
                                        )
                                    )
                                    val wallMs = (System.nanoTime() - startedNs) / 1_000_000L
                                    runOnUiThread {
                                        smokeTestBusy = false
                                        routeObservation = titan.latestRouteObservation()
                                        result.fold(
                                            onSuccess = { response ->
                                                val speed = response.tokensPerSecond
                                                    ?.let { value -> " · %.1f tok/s".format(value) }
                                                    .orEmpty()
                                                val generated = response.outputTokens
                                                    ?.let { " · $it output tok" }
                                                    .orEmpty()
                                                val generation = response.generationTimeMs
                                                    ?.let { " · generation ${it}ms" }
                                                    .orEmpty()
                                                smokeTestStatus =
                                                    "PASS · model=${response.modelId.value} · backend=${response.backendId}" +
                                                        generated + speed + generation + " · wall=${wallMs}ms"
                                                smokeTestOutput = response.text
                                            },
                                            onFailure = { error ->
                                                smokeTestStatus =
                                                    "FAIL · ${error::class.java.simpleName}: " +
                                                        (error.message ?: "no detail") +
                                                        " · wall=${wallMs}ms"
                                                smokeTestOutput = ""
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(if (smokeTestBusy) "Running local inference..." else "Run local inference smoke test")
                        }

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("Ask AMPER") }
                        )
                        Button(
                            enabled = prompt.isNotBlank() && hasModel && hasRuntimeBackend,
                            onClick = {
                                val userPrompt = prompt
                                val thread = conversationId
                                val turnAttachments = pendingInferenceAttachments
                                val cancellation = InferenceCancellationSignal()
                                                                activeInferenceCancellation = cancellation
                                pendingApproval = null
                                inferenceStatus = "QUEUED · waiting for interactive inference lane..."
                                inferenceOutput = ""
                                executionLanes.executeInteractive {
                                    runOnUiThread {
                                        if (activeInferenceCancellation === cancellation) {
                                            inferenceStatus =
                                                "RUNNING · AMPER cognition → single AMI/AMNE core..."
                                        }
                                    }
                                    val assistantStartedNs = System.nanoTime()
                                    val result = assistant.respondStreamingObserved(
                                        conversationId = thread,
                                        userPrompt = userPrompt,
                                        attachments = turnAttachments,
                                        cancellation = cancellation,
                                        onStage = { stage ->
                                            runOnUiThread {
                                                if (
                                                    conversationId == thread &&
                                                    activeInferenceCancellation === cancellation
                                                ) {
                                                    inferenceStatus = when (stage) {
                                                        AssistantTurnStage.RUNTIME_TICK ->
                                                            "RUNNING · stage RUNTIME_TICK"
                                                        AssistantTurnStage.REFLEX ->
                                                            "RUNNING · stage REFLEX"
                                                        AssistantTurnStage.NATIVE_SYSTEM2 ->
                                                            "RUNNING · stage NATIVE_SYSTEM2"
                                                        AssistantTurnStage.TITAN_INFERENCE ->
                                                            "RUNNING · stage TITAN_INFERENCE · AMPER single-core execution"
                                                        AssistantTurnStage.ACTION_EVALUATION ->
                                                            "RUNNING · stage ACTION_EVALUATION"
                                                        AssistantTurnStage.FINALIZING ->
                                                            "RUNNING · stage FINALIZING"
                                                    }
                                                }
                                            }
                                        },
                                        onEvent = { event ->
                                            runOnUiThread {
                                                if (conversationId == thread) {
                                                    when (event) {
                                                        AssistantStreamEvent.Reset -> inferenceOutput = ""
                                                        is AssistantStreamEvent.Text -> inferenceOutput += event.text
                                                    }
                                                }
                                            }
                                        }
                                    )
                                    val assistantWallMs =
                                        (System.nanoTime() - assistantStartedNs) / 1_000_000L
                                    runOnUiThread {
                                        if (activeInferenceCancellation === cancellation) {
                                            activeInferenceCancellation = null
                                        }
                                        if (conversationId == thread) {
                                            routeObservation = titan.latestRouteObservation()
                                            result.fold(
                                                onSuccess = { turn ->
                                                    pendingInferenceAttachments = emptyList()
                                                    attachmentStatus =
                                                        if (turnAttachments.isEmpty()) {
                                                            "No multimodal attachment selected"
                                                        } else {
                                                            "Attachments consumed and released from UI state"
                                                        }
                                                    when (turn) {
                                                        is SovereignAssistantTurnResult.Final -> {
                                                            inferenceOutput = turn.response.text
                                                            val action = turn.actionOutcome?.status?.name?.lowercase()
                                                                ?.let { " · action $it" }
                                                                .orEmpty()
                                                            val speed = turn.response.tokensPerSecond
                                                                ?.let { value -> " · %.1f tok/s".format(value) }
                                                                .orEmpty()
                                                            val outputTokens = turn.response.outputTokens
                                                                ?.let { " · $it tok" }
                                                                .orEmpty()
                                                            val promptEval = turn.response.promptEvalTimeMs
                                                                ?.let { " · prompt ${it}ms" }
                                                                .orEmpty()
                                                            val generation = turn.response.generationTimeMs
                                                                ?.let { " · generation ${it}ms" }
                                                                .orEmpty()
                                                            val session = if (turn.response.sessionReused) {
                                                                " · warm"
                                                            } else {
                                                                " · cold"
                                                            }
                                                            val turns = runtime.conversations.recent(thread).size
                                                            inferenceStatus =
                                                                "${turn.response.backendId} · ${turn.inferencePasses} pass · turns $turns" +
                                                                    "$action$session$outputTokens$speed$promptEval$generation" +
                                                                    " · wall ${assistantWallMs}ms"
                                                        }

                                                        is SovereignAssistantTurnResult.PendingApproval -> {
                                                            pendingApproval = turn
                                                            inferenceStatus = "Action requires confirmation: ${turn.proposal.capability.value}"
                                                            inferenceOutput = "AMPER proposed a side-effect action and has not executed it."
                                                        }
                                                    }
                                                },
                                                onFailure = {
                                                    inferenceOutput = ""
                                                    inferenceStatus = if (it is InferenceCancelledException) {
                                                        "Generation stopped by user; partial answer was not saved"
                                                    } else {
                                                        "Inference unavailable: ${it.message ?: it::class.java.simpleName}"
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    if (result.isSuccess) {
                                        runCatching {
                                            executionLanes.executeMaintenance {
                                                runCatching { reflexLifecycle.maintain() }
                                            }
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Ask AMPER")
                        }
                        activeInferenceCancellation?.let { cancellation ->
                            Button(
                                onClick = {
                                    if (cancellation.cancel()) {
                                        inferenceStatus = "Stopping current generation..."
                                    }
                                }
                            ) {
                                Text("Stop generation")
                            }
                        }

                        pendingApproval?.let { pending ->
                            Text("Pending: ${pending.proposal.capability.value} — ${pending.proposal.reason}")
                            Text("Proposed input:")
                            Text(pending.proposal.input)
                            Text("Nothing is executed until you approve this exact proposal.")
                            Button(
                                onClick = {
                                    val thread = pending.conversationId
                                    val cancellation = InferenceCancellationSignal()
                                    activeInferenceCancellation = cancellation
                                    inferenceStatus = "Executing approved action through Authority Gate..."
                                    executionLanes.executeInteractive {
                                        val result = assistant.approveStreaming(
                                            pending,
                                            cancellation
                                        ) { event ->
                                            runOnUiThread {
                                                if (conversationId == thread) {
                                                    when (event) {
                                                        AssistantStreamEvent.Reset -> inferenceOutput = ""
                                                        is AssistantStreamEvent.Text -> inferenceOutput += event.text
                                                    }
                                                }
                                            }
                                        }
                                        val stillPending = if (result.isFailure) {
                                            assistant.restorePendingApproval(thread)
                                        } else {
                                            null
                                        }
                                        runOnUiThread {
                                            if (activeInferenceCancellation === cancellation) {
                                                activeInferenceCancellation = null
                                            }
                                            if (conversationId == thread) {
                                                routeObservation = titan.latestRouteObservation()
                                                result.fold(
                                                    onSuccess = { turn ->
                                                        pendingApproval = null
                                                        inferenceOutput = turn.response.text
                                                        inferenceStatus = "Approved action ${turn.actionOutcome?.status?.name?.lowercase()} · ${turn.inferencePasses} pass"
                                                    },
                                                    onFailure = { error ->
                                                        pendingApproval = stillPending
                                                        inferenceOutput = ""
                                                        inferenceStatus = when {
                                                            error is InferenceCancelledException && stillPending == null ->
                                                                "Approved action completed its durable handoff; answer generation was stopped"
                                                            error is InferenceCancelledException ->
                                                                "Generation stopped before durable action execution; approval checkpoint retained"
                                                            stillPending != null ->
                                                                "Approval failed before execution; checkpoint retained: ${error.message ?: error::class.java.simpleName}"
                                                            else ->
                                                                "Approved action failed after durable transaction handoff: ${error.message ?: error::class.java.simpleName}"
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                        if (result.isSuccess) {
                                            runCatching { reflexLifecycle.maintain() }
                                        }
                                    }
                                }
                            ) {
                                Text("Approve action")
                            }
                            Button(
                                onClick = {
                                    val thread = pending.conversationId
                                    inferenceStatus = "Rejecting pending action without tool execution..."
                                    executionLanes.executeInteractive {
                                        val result = assistant.reject(pending)
                                        val stillPending = if (result.isFailure) {
                                            assistant.restorePendingApproval(thread)
                                        } else {
                                            null
                                        }
                                        runOnUiThread {
                                            result.fold(
                                                onSuccess = {
                                                    pendingApproval = null
                                                    inferenceStatus = "Pending action rejected by user; no tool invoked"
                                                },
                                                onFailure = { error ->
                                                    pendingApproval = stillPending
                                                    inferenceStatus = "Reject failed: ${error.message ?: error::class.java.simpleName}"
                                                }
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text("Reject action")
                            }
                        }

                        Text("Persistent Sovereign Plan", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = planGoal,
                            onValueChange = { planGoal = it },
                            label = { Text("Plan goal") }
                        )
                        Button(
                            enabled = planGoal.isNotBlank() && hasModel && hasRuntimeBackend,
                            onClick = {
                                val goal = planGoal
                                val thread = conversationId
                                planStatus = "Creating bounded plan; no tool execution allowed during planning..."
                                executionLanes.executeInteractive {
                                    val result = planner.create(thread, goal)
                                    runOnUiThread {
                                        routeObservation = titan.latestRouteObservation()
                                        result.fold(
                                            onSuccess = { plan ->
                                                activePlan = plan
                                                planStatus = "Encrypted plan ${plan.id.value.take(8)} saved with ${plan.steps.size} step(s)"
                                            },
                                            onFailure = { error ->
                                                planStatus = "Plan creation failed: ${error.message ?: error::class.java.simpleName}"
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Create governed plan")
                        }
                        Button(
                            enabled = hasModel && hasRuntimeBackend,
                            onClick = {
                                val thread = conversationId
                                planStatus =
                                    "Running one bounded persistent-goal cognitive cycle..."
                                executionLanes.executeInteractive {
                                    val result = persistentGoalExecutive.runNext(thread)
                                    runOnUiThread {
                                        routeObservation = titan.latestRouteObservation()
                                        result.fold(
                                            onSuccess = { outcome ->
                                                when (outcome) {
                                                    is PersistentGoalExecutiveResult.NoGoal -> {
                                                        planStatus =
                                                            "No eligible active sovereign goal is waiting"
                                                    }
                                                    is PersistentGoalExecutiveResult.Decomposed -> {
                                                        planStatus =
                                                            "Autonomous goal " +
                                                                outcome.parentGoalId.take(8) +
                                                                " decomposed into " +
                                                                outcome.childGoalIds.size +
                                                                " bounded subgoals"
                                                    }
                                                    is PersistentGoalExecutiveResult.Replanned -> {
                                                        planStatus =
                                                            "Adaptive goal branch " +
                                                                outcome.supersededGoalId.take(8) +
                                                                " replaced by " +
                                                                outcome.replacementGoalIds.size +
                                                                " bounded alternative" +
                                                                if (outcome.replacementGoalIds.size == 1) "" else "s"
                                                    }
                                                    is PersistentGoalExecutiveResult.Deferred -> {
                                                        planStatus =
                                                            "Autonomous goal " +
                                                                outcome.checkpoint.sourceGoalId.take(8) +
                                                                " deferred: " + outcome.reason
                                                    }
                                                    is PersistentGoalExecutiveResult.Ran -> {
                                                        val last = outcome.run.cycles.lastOrNull()
                                                        val planned =
                                                            last as? CognitiveExecutiveCycleResult.Planned
                                                        if (planned != null) {
                                                            activePlan = planned.plan
                                                            conversationId = planned.plan.conversationId
                                                        }
                                                        val observation =
                                                            last as? CognitiveExecutiveCycleResult.ObservationRequired
                                                        val observationDetail = when {
                                                            observation?.refreshed == true ->
                                                                " · perception refreshed"
                                                            observation?.acquisitionFailureCode != null ->
                                                                " · perception=" +
                                                                    observation.acquisitionFailureCode
                                                            else -> ""
                                                        }
                                                        planStatus =
                                                            "Autonomous goal " +
                                                                outcome.checkpoint.sourceGoalId.take(8) +
                                                                " → " + outcome.checkpoint.stage.name +
                                                                " · cycles=" + outcome.run.cycles.size +
                                                                observationDetail
                                                    }
                                                }
                                            },
                                            onFailure = { error ->
                                                planStatus =
                                                    "Autonomous goal cycle failed: " +
                                                        (error.message
                                                            ?: error::class.java.simpleName)
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Run autonomous goal cycle")
                        }
                        Button(
                            enabled = hasModel && hasRuntimeBackend,
                            onClick = {
                                if (autonomyLoop.isActive()) {
                                    autonomyLoop.stop()
                                    autonomyLoopEnabled = false
                                    autonomyLoopStatus = "Autonomy scheduler stopped"
                                } else {
                                    autonomyLoop.start()
                                    autonomyLoopEnabled = autonomyLoop.isActive()
                                    autonomyLoopStatus =
                                        if (autonomyLoopEnabled) {
                                            "Autonomy scheduler active · resource-gated bounded ticks"
                                        } else {
                                            "Autonomy scheduler could not start"
                                        }
                                }
                            }
                        ) {
                            Text(
                                if (autonomyLoopEnabled) {
                                    "Stop autonomy scheduler"
                                } else {
                                    "Start autonomy scheduler"
                                }
                            )
                        }
                        Text(autonomyLoopStatus)
                        Button(
                            onClick = {
                                val restored = planner.latest()
                                activePlan = restored
                                if (restored != null) {
                                    conversationId = restored.conversationId
                                    pendingApproval = assistant.restorePendingApproval(restored.conversationId)
                                    planStatus = "Restored encrypted plan ${restored.id.value.take(8)}"
                                } else {
                                    planStatus = "No persistent plan available"
                                }
                            }
                        ) {
                            Text("Restore latest plan")
                        }

                        SovereignPlanHistoryPanel(
                            history = planHistory,
                            activePlanId = activePlan?.id,
                            onOpen = { opened ->
                                activePlan = opened
                                conversationId = opened.conversationId
                                pendingApproval = assistant.restorePendingApproval(opened.conversationId)
                                planStatus = "Opened persisted plan ${opened.id.value.take(8)}; no plan step executed"
                            }
                        )

                        activePlan?.let { plan ->
                            GovernedPlanExecutionConsolePanel(
                                plan = plan,
                                receiptLedger = runtime.plans.receipts,
                                onAdvance = {
                                    val current = plan
                                    planStatus = "Advancing exactly one governed plan step..."
                                    executionLanes.executeInteractive {
                                        val result = planner.advance(current)
                                        val advance = result.getOrNull()
                                        val refreshed = if (advance is PlanAdvanceResult.ContextChanged) {
                                            planner.refreshContext(advance.plan)
                                        } else {
                                            null
                                        }
                                        val reboundGoal = if (advance is PlanAdvanceResult.ContextChanged) {
                                            refreshed?.getOrNull()?.let { replacement ->
                                                if (
                                                    persistentGoalExecutive.current()?.plannedPlanId ==
                                                    advance.plan.id
                                                ) {
                                                    persistentGoalExecutive.rebindPlannedHandoff(
                                                        previousPlanId = advance.plan.id,
                                                        replacementPlanId = replacement.id
                                                    )
                                                } else {
                                                    null
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                        val resolvedGoal = if (
                                            advance is PlanAdvanceResult.Complete &&
                                            persistentGoalExecutive.current()?.plannedPlanId ==
                                            advance.plan.id
                                        ) {
                                            if (
                                                advance.plan.steps.all {
                                                    it.status == PlanStepStatus.EXECUTED
                                                }
                                            ) {
                                                val checkpoint =
                                                    requireNotNull(
                                                        persistentGoalExecutive.current()
                                                    )
                                                goalSatisfactionVerifier
                                                    .verify(checkpoint, advance.plan)
                                                    .mapCatching { assessment ->
                                                        persistentGoalExecutive
                                                            .resolveVerifiedSuccess(
                                                                planId = advance.plan.id,
                                                                assessment = assessment
                                                            )
                                                            .getOrThrow()
                                                    }
                                            } else {
                                                persistentGoalExecutive
                                                    .resolveTerminalPlan(advance.plan.id)
                                            }
                                        } else {
                                            null
                                        }
                                        runOnUiThread {
                                            if (
                                                advance is PlanAdvanceResult.ContextChanged &&
                                                refreshed != null
                                            ) {
                                                refreshed.fold(
                                                    onSuccess = { replacement ->
                                                        activePlan = replacement
                                                        routeObservation = titan.latestRouteObservation()
                                                        planStatus =
                                                            "Grounded context changed; fresh child plan " +
                                                                replacement.id.value.take(8) +
                                                                " created with zero tool execution" +
                                                                when {
                                                                    reboundGoal == null -> ""
                                                                    reboundGoal.isSuccess ->
                                                                        " · autonomous goal handoff rebound"
                                                                    else ->
                                                                        " · goal handoff rebind failed: " +
                                                                            (
                                                                                reboundGoal.exceptionOrNull()?.message
                                                                                    ?: "unknown"
                                                                                )
                                                                }
                                                    },
                                                    onFailure = { error ->
                                                        activePlan = advance.plan
                                                        planStatus =
                                                            "Context changed and bounded replanning failed: " +
                                                                (error.message ?: error::class.java.simpleName)
                                                    }
                                                )
                                            } else {
                                                result.fold(
                                                    onSuccess = { resolved ->
                                                        when (resolved) {
                                                            is PlanAdvanceResult.StepProcessed -> {
                                                                activePlan = resolved.plan
                                                                planStatus =
                                                                    "Step ${resolved.step.index}: ${resolved.outcome.status}"
                                                            }
                                                            is PlanAdvanceResult.PendingApproval -> {
                                                                activePlan = resolved.plan
                                                                planStatus =
                                                                    "Step ${resolved.step.index} requires explicit approval"
                                                            }
                                                            is PlanAdvanceResult.ContextChanged -> {
                                                                activePlan = resolved.plan
                                                                planStatus =
                                                                    "Grounded context changed; bounded replanning unavailable"
                                                            }
                                                            is PlanAdvanceResult.Complete -> {
                                                                activePlan = resolved.plan
                                                                planStatus = when {
                                                                    resolvedGoal == null ->
                                                                        "Plan complete"
                                                                    resolvedGoal.isSuccess ->
                                                                        "Plan complete · autonomous goal → " +
                                                                            requireNotNull(
                                                                                resolvedGoal.getOrNull()
                                                                            ).stage.name
                                                                    else ->
                                                                        "Plan complete · autonomous goal resolution failed: " +
                                                                            (
                                                                                resolvedGoal.exceptionOrNull()?.message
                                                                                    ?: "unknown"
                                                                                )
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onFailure = { error ->
                                                        planStatus =
                                                            "Plan advance failed: " +
                                                                (error.message ?: error::class.java.simpleName)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                onApprove = { stepIndex ->
                                    val current = plan
                                    planStatus = "Executing approved plan step through Authority Gate..."
                                    executionLanes.executeInteractive {
                                        val result = planner.approve(current, stepIndex)
                                        runOnUiThread {
                                            result.fold(
                                                onSuccess = { processed ->
                                                    activePlan = processed.plan
                                                    planStatus = "Approved step ${processed.step.index}: ${processed.outcome.status}"
                                                },
                                                onFailure = { error ->
                                                    planStatus = "Plan approval failed: ${error.message ?: error::class.java.simpleName}"
                                                }
                                            )
                                        }
                                    }
                                },
                                onReject = { stepIndex ->
                                    val rejected = planner.reject(plan, stepIndex)
                                    activePlan = rejected
                                    planStatus = "Rejected plan step $stepIndex; no tool invoked"
                                }
                            )
                        }
                        Text(planStatus)

                        SovereignRecoveryConsolePanel(
                            console = recoveryConsole,
                            onPlanUpdated = { recovered ->
                                activePlan = recovered
                                conversationId = recovered.conversationId
                                pendingApproval = assistant.restorePendingApproval(recovered.conversationId)
                                planStatus = "Reconciled plan ${recovered.id.value.take(8)}; provider was not replayed"
                            }
                        )

                        Button(
                            enabled = hasModel && hasRuntimeBackend,
                            onClick = {
                                inferenceStatus = "Releasing Titan model..."
                                executionLanes.executeInteractive {
                                    val result = titan.unloadAll()
                                    runOnUiThread {
                                        inferenceStatus = result.fold(
                                            onSuccess = { "Titan model released" },
                                            onFailure = { "Release failed: ${it.message ?: it::class.java.simpleName}"
                                            }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text("Unload Titan model")
                        }
                        Text(inferenceStatus)
                        if (inferenceOutput.isNotBlank()) Text(inferenceOutput)
                        Text("Tool audit entries: ${toolAudit.snapshot().size}")
                    }
                }
            }
        }
    }
}