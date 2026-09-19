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
import io.amper.neuroos.core.AmperRuntime
import io.amper.neuroos.core.AssistantStreamEvent
import io.amper.neuroos.core.InferenceCancellationSignal
import io.amper.neuroos.core.InferenceCancelledException
import io.amper.neuroos.core.InferenceRequest
import io.amper.neuroos.core.AndroidContextDeviceActionLauncher
import io.amper.neuroos.core.AndroidDeviceStatusSource
import io.amper.neuroos.core.AndroidInferenceAttachmentLoader
import io.amper.neuroos.core.AndroidCameraVisionAttachmentEncoder
import io.amper.neuroos.core.AndroidSettingsOpenToolProvider
import io.amper.neuroos.core.AndroidShareTextToolProvider
import io.amper.neuroos.core.AndroidTimerPrepareToolProvider
import io.amper.neuroos.core.AndroidLiveAudioAttachmentCapture
import io.amper.neuroos.core.AndroidModelImportService
import io.amper.neuroos.core.AndroidAppPrivateModelArtifactResolver
import io.amper.neuroos.core.AndroidMultimodalProjectorImportService
import io.amper.neuroos.core.AndroidPerceptionCapture
import io.amper.neuroos.core.AndroidResourceGovernor
import io.amper.neuroos.core.AndroidScreenVisionSession
import io.amper.neuroos.core.AndroidVoiceConversationSession
import io.amper.neuroos.core.AuditedToolFabric
import io.amper.neuroos.core.ContentUriArtifactResolver
import io.amper.neuroos.core.ContentUriProjectorArtifactResolver
import io.amper.neuroos.core.DenyByDefaultAuthorityGate
import io.amper.neuroos.core.DeviceStatusToolProvider
import io.amper.neuroos.core.FileInstalledModelCatalog
import io.amper.neuroos.core.FileMultimodalProjectorCatalog
import io.amper.neuroos.core.InMemoryModelRegistry
import io.amper.neuroos.core.InMemoryToolAuditLog
import io.amper.neuroos.core.InMemoryToolRegistry
import io.amper.neuroos.core.InferenceAttachment
import io.amper.neuroos.core.InferenceAttachmentKind
import io.amper.neuroos.core.InferenceBackendRegistry
import io.amper.neuroos.core.LiveContextFusion
import io.amper.neuroos.core.LocatorArtifactResolver
import io.amper.neuroos.core.InstalledModelCapabilityService
import io.amper.neuroos.core.InstalledModelDetachService
import io.amper.neuroos.core.InstalledModelRegistryBootstrap
import io.amper.neuroos.core.ModelCapabilityProfile
import io.amper.neuroos.core.ModelId
import io.amper.neuroos.core.LlamaNativeTextEngine
import io.amper.neuroos.core.LlamaNativeTextInferenceBackend
import io.amper.neuroos.core.MtmdNativeInferenceBackend
import io.amper.neuroos.core.OptionalBackendPackLoader
import io.amper.neuroos.core.OptionalMtmdNativeEngineLoader
import io.amper.neuroos.core.PersistentSovereignPlanCoordinator
import io.amper.neuroos.core.PerceptionModality
import io.amper.neuroos.core.PlanAdvanceResult
import io.amper.neuroos.core.PreferredModelInferencePort
import io.amper.neuroos.core.RuntimeSovereignStatusSource
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
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val sovereignDir = remember { File(filesDir, "amper-sovereign") }
            val modelRegistry = remember { InMemoryModelRegistry() }
            val catalog = remember { FileInstalledModelCatalog(File(sovereignDir, "models.catalog")) }
            remember { InstalledModelRegistryBootstrap(catalog, modelRegistry).restore() }
            val governor = remember { AndroidResourceGovernor(applicationContext) }
            val runtime = remember { AmperRuntime.persistentEncrypted(filesDir, modelRegistry, governor) }
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
            val projectorImporter = remember {
                AndroidMultimodalProjectorImportService(
                    applicationContext,
                    projectorCatalog
                )
            }
            val projectorResolver = remember {
                ContentUriProjectorArtifactResolver(contentResolver)
            }
            val importer = remember { AndroidModelImportService(this, catalog, modelRegistry) }
            val capabilityManager = remember { InstalledModelCapabilityService(catalog, modelRegistry) }
            val backends = remember { InferenceBackendRegistry() }
            val packManager = remember { TitanBackendPackManager(backends) }
            val backendStatus = remember {
                OptionalBackendPackLoader.attach(
                    "io.amper.neuroos.backend.LlamaAarBackendPack",
                    packManager
                )
            }
            val mtmdEngineStatus = remember { OptionalMtmdNativeEngineLoader.load() }
            remember {
                mtmdEngineStatus.engine?.let { engine ->
                    (engine as? LlamaNativeTextEngine)?.let { textEngine ->
                        backends.register(
                            LlamaNativeTextInferenceBackend(textEngine)
                        )
                    }
                    backends.register(
                        MtmdNativeInferenceBackend(
                            engine = engine,
                            projectors = projectorCatalog,
                            projectorArtifacts = projectorResolver
                        )
                    )
                }
            }
            val hasRuntimeBackend = backends.list().isNotEmpty()
            val contentModelArtifacts = remember {
                ContentUriArtifactResolver(contentResolver)
            }
            val nativeModelArtifacts = remember {
                AndroidAppPrivateModelArtifactResolver(
                    File(sovereignDir, "native-models")
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
            val titan = remember {
                TitanCortexRuntime(
                    models = modelRegistry,
                    catalog = catalog,
                    artifacts = modelArtifacts,
                    backends = backends,
                    governor = governor
                )
            }
            val detachManager = remember {
                InstalledModelDetachService(catalog, modelRegistry, titan::unload)
            }
            val modelRoutingPreferences = remember {
                getSharedPreferences("amper-model-routing", Context.MODE_PRIVATE)
            }
            val initialPreferredModelId = remember {
                modelRoutingPreferences.getString("preferred_model_id", null)
                    ?.let(::ModelId)
                    ?.takeIf { catalog.get(it) != null }
                    .also { valid ->
                        if (valid == null) {
                            modelRoutingPreferences.edit().remove("preferred_model_id").apply()
                        }
                    }
            }
            val assistantCapabilities = remember { SovereignAssistantToolExposure.capabilities }
            val androidActionLauncher = remember {
                AndroidContextDeviceActionLauncher(applicationContext)
            }
            val toolRegistry = remember {
                InMemoryToolRegistry().also { registry ->
                    registry.register(
                        DeviceStatusToolProvider(AndroidDeviceStatusSource(applicationContext))
                    )
                    registry.register(
                        SovereignStatusToolProvider(
                            RuntimeSovereignStatusSource(catalog, backends, governor)
                        )
                    )
                    registry.register(
                        SovereignNoteToolProvider(runtime.notes)
                    )
                    registry.register(
                        SovereignNoteSearchToolProvider(runtime.notes)
                    )
                    registry.register(
                        SovereignNoteDeleteToolProvider(runtime.notes)
                    )
                    registry.register(
                        AndroidSettingsOpenToolProvider(androidActionLauncher)
                    )
                    registry.register(
                        AndroidTimerPrepareToolProvider(androidActionLauncher)
                    )
                    registry.register(
                        AndroidShareTextToolProvider(androidActionLauncher)
                    )
                }
            }
            val toolAudit = remember { InMemoryToolAuditLog() }
            val toolFabric = remember {
                AuditedToolFabric(
                    gate = DenyByDefaultAuthorityGate(assistantCapabilities),
                    registry = toolRegistry,
                    audit = toolAudit
                )
            }
            val actionLoop = remember { runtime.actionLoop(toolRegistry, toolFabric) }
            val inferencePort = remember {
                PreferredModelInferencePort(
                    delegate = TitanInferencePort(titan),
                    initialPreferredModelId = initialPreferredModelId
                )
            }
            val assistant = remember {
                SovereignAssistantTurnCoordinator(
                    runtime = runtime,
                    inference = inferencePort,
                    actions = actionLoop,
                    advertisedCapabilities = assistantCapabilities,
                    maxOutputTokens = 256
                )
            }
            val planner = remember {
                PersistentSovereignPlanCoordinator(
                    delegate = SovereignPlanCoordinator(
                        runtime = runtime,
                        inference = inferencePort,
                        actions = actionLoop,
                        advertisedCapabilities = assistantCapabilities,
                        maxOutputTokens = 256,
                        criticInference = inferencePort
                    ),
                    store = runtime.plans
                )
            }
            val planHistory = remember { SovereignPlanHistory(runtime.plans) }
            val recoveryConsole = remember {
                io.amper.neuroos.core.SovereignRecoveryConsole(runtime.plans, planner)