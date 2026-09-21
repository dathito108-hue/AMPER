package io.amper.neuroos.core

import android.content.Context
import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationHandoff
import io.amper.neuroos.core.v2.AmperAgentAndroidContinuationHostDispatcher
import io.amper.neuroos.core.v2.AmperAgentAndroidHostExecutionResult
import io.amper.neuroos.core.v2.AndroidAmi2CompilationService
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AndroidAgentColdExecutionSession(
    val graph: AndroidSovereignAgentExecutionGraph,
    private val titan: TitanCortexRuntime
) : AutoCloseable {
    override fun close() {
        runCatching { titan.unloadAll().getOrThrow() }
    }
}

/**
 * Cold-process bootstrap for verified Agent continuation wakes.
 *
 * The bootstrap deliberately reconstructs the same sovereign execution graph as MainActivity via
 * [AndroidSovereignAgentExecutionGraphFactory]. It does not own a separate tool set, planner,
 * authority gate, or task store.
 */
object AndroidAgentColdExecutionBootstrap {
    private val coldExecutionLock = ReentrantLock()

    fun dispatch(
        context: Context,
        handoff: AmperAgentAndroidContinuationHandoff
    ): Result<AmperAgentAndroidHostExecutionResult> = runCatching {
        coldExecutionLock.withLock {
            AndroidAgentContinuationProcessRegistry.current()?.let { warm ->
                return@withLock AmperAgentAndroidContinuationHostDispatcher
                    .dispatch(handoff, warm)
                    .getOrThrow()
            }

            val session = open(context.applicationContext)
            try {
                AmperAgentAndroidContinuationHostDispatcher
                    .dispatch(
                        handoff = handoff,
                        execution = session.graph.agentContinuationExecution
                    )
                    .getOrThrow()
            } finally {
                session.close()
            }
        }
    }

    internal fun open(context: Context): AndroidAgentColdExecutionSession {
        val appContext = context.applicationContext
        val canonicalFilesDir = AndroidAppPrivateStorage.canonicalFilesDir(appContext)
        val canonicalCacheDir = AndroidAppPrivateStorage.canonicalCacheDir(appContext)
        val sovereignDir = File(canonicalFilesDir, "amper-sovereign")

        val modelRegistry = AmperSingleCoreModelRegistry()
        val catalog = FileInstalledModelCatalog(File(sovereignDir, "models.catalog"))
        val foundationController = AmperSingleCoreFoundationController(catalog, modelRegistry)
        val requestedFoundation = selectedFoundationId(appContext, catalog)

        val governor = AndroidResourceGovernor(appContext)
        val deviceStatus = AndroidDeviceStatusSource(appContext)
        val runtime = AmperRuntime.persistentEncrypted(
            rootDir = canonicalFilesDir,
            models = modelRegistry,
            governor = governor,
            deviceStatusSource = deviceStatus
        )

        // persistentEncrypted installs the contract placeholder during construction. Reassert the
        // same user-selected single foundation after runtime creation, exactly as the UI host does.
        foundationController.restore(requestedFoundation)

        val nativeModelArtifacts = AndroidAppPrivateModelArtifactResolver(
            File(sovereignDir, "native-models")
        )
        val contentModelArtifacts = ContentUriArtifactResolver(
            appContext.contentResolver,
            File(canonicalCacheDir, "amper-native-model-stage")
        )
        val modelArtifacts = LocatorArtifactResolver(
            listOf(
                { model -> contentModelArtifacts.resolve(model) },
                { model -> nativeModelArtifacts.resolve(model) }
            )
        )

        val ami2 = AndroidAmi2CompilationService(
            rootDir = File(sovereignDir, "ami2-models"),
            modelArtifacts = modelArtifacts
        )
        val legacyAmi = AndroidAmiCompilationService(
            rootDir = File(sovereignDir, "ami-models"),
            modelArtifacts = modelArtifacts
        )
        val hardware = AndroidAmiHardwareProfiler(appContext)
        val core = AmperCoreInferencePort(
            artifactLookup = { model ->
                ami2.existing(model)
                    ?: legacyAmi.existing(model)?.let { legacy ->
                        ami2.migrateLegacy(model, legacy.file).getOrNull()
                    }
            },
            hardwareSnapshot = hardware::snapshot
        )
        if (core.nativeRuntimePackaged()) {
            // Qualification failure is fail-closed to the existing reference kernels. It must not
            // turn a valid persisted task into an infinite JobScheduler retry loop.
            core.bootstrapNativeAdmission()
        }

        val titan = TitanCortexRuntime(
            models = modelRegistry,
            catalog = catalog,
            artifacts = modelArtifacts,
            core = core,
            governor = governor,
            deviceStatusSource = deviceStatus
        )
        val graph = AndroidSovereignAgentExecutionGraphFactory.build(
            context = appContext,
            runtime = runtime,
            titan = titan,
            catalog = catalog,
            core = core,
            governor = governor
        )
        return AndroidAgentColdExecutionSession(
            graph = graph,
            titan = titan
        )
    }

    private fun selectedFoundationId(
        context: Context,
        catalog: InstalledModelCatalog
    ): ModelId? {
        val canonical = context
            .getSharedPreferences("amper-core-foundation", Context.MODE_PRIVATE)
            .getString("active_source_model_id", null)
            ?.let(::ModelId)
            ?.takeIf { catalog.get(it) != null }
        if (canonical != null) return canonical

        return context
            .getSharedPreferences("amper-model-routing", Context.MODE_PRIVATE)
            .getString("preferred_model_id", null)
            ?.let(::ModelId)
            ?.takeIf { catalog.get(it) != null }
    }
}
