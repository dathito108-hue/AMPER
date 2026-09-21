package io.amper.neuroos.core

import android.content.Context
import io.amper.neuroos.core.v2.AmperAgentCanonicalContinuationExecutionPort
import io.amper.neuroos.core.v2.AmperAgentExecutionContinuationCoordinator
import io.amper.neuroos.core.v2.AmperAgentPassiveTaskCoordinator
import io.amper.neuroos.core.v2.AmperAgentTaskAdmissionRegistry
import io.amper.neuroos.core.v2.PersistentSovereignAgentPlanPort

data class AndroidSovereignAgentExecutionGraph(
    val capabilities: Set<CapabilityId>,
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
    val agentContinuation: AmperAgentExecutionContinuationCoordinator,
    val agentContinuationExecution: AmperAgentCanonicalContinuationExecutionPort
)

/**
 * Canonical Android sovereign execution graph shared by the interactive Activity and cold Agent
 * continuation hosts. All tool registration, authority, action-loop, inference, planning and Agent
 * continuation wiring lives here so process state cannot select a different execution architecture.
 */
object AndroidSovereignAgentExecutionGraphFactory {
    fun build(
        context: Context,
        runtime: AmperRuntime,
        titan: TitanCortexRuntime,
        catalog: InstalledModelCatalog,
        core: AmperCoreInferencePort,
        governor: ResourceGovernor,
        deviceStatusSource: DeviceStatusSource? = null
    ): AndroidSovereignAgentExecutionGraph {
        val appContext = context.applicationContext
        val capabilities = SovereignAssistantToolExposure.capabilities
        val launcher = AndroidContextDeviceActionLauncher(appContext)
        val statusSource = deviceStatusSource ?: AndroidDeviceStatusSource(appContext)

        val registry = InMemoryToolRegistry().also { tools ->
            tools.register(DeviceStatusToolProvider(statusSource))
            tools.register(
                SovereignStatusToolProvider(
                    AmperCoreSovereignStatusSource(catalog, core, governor)
                )
            )
            tools.register(SovereignNoteToolProvider(runtime.notes))
            tools.register(SovereignNoteSearchToolProvider(runtime.notes))
            tools.register(SovereignNoteDeleteToolProvider(runtime.notes))
            tools.register(AndroidSettingsOpenToolProvider(launcher))
            tools.register(AndroidTimerPrepareToolProvider(launcher))
            tools.register(AndroidShareTextToolProvider(launcher))
            tools.register(AndroidAppLaunchToolProvider(launcher))
            tools.register(AndroidWebSearchToolProvider(launcher))
            tools.register(OmegaInternetReadToolProvider(OmegaInternetGateway()))
            tools.register(AndroidClipboardWriteToolProvider(launcher))
            tools.register(AndroidFilesBrowseToolProvider(launcher))
            tools.register(AndroidContactComposeToolProvider(launcher))
            tools.register(AndroidCalendarComposeToolProvider(launcher))
            tools.register(AndroidAlarmPrepareToolProvider(launcher))
            tools.register(AndroidMediaOpenToolProvider(launcher))
            tools.register(AndroidNotificationSettingsToolProvider(launcher))
            tools.register(AndroidHomeOpenToolProvider(launcher))
        }

        val audit = InMemoryToolAuditLog()
        val fabric = AuditedToolFabric(
            gate = DenyByDefaultAuthorityGate(capabilities),
            registry = registry,
            audit = audit
        )
        val actionLoop = runtime.actionLoop(registry, fabric)
        val inference = TitanInferencePort(titan)
        val assistant = SovereignAssistantTurnCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actionLoop,
            advertisedCapabilities = capabilities,
            maxOutputTokens = 256
        )
        val planning = SovereignPlanCoordinator(
            runtime = runtime,
            inference = inference,
            actions = actionLoop,
            advertisedCapabilities = capabilities,
            maxOutputTokens = 256,
            criticInference = inference
        )
        val planner = PersistentSovereignPlanCoordinator(
            delegate = planning,
            store = runtime.plans
        )
        val agentPlanPort = PersistentSovereignAgentPlanPort(
            coordinator = planner,
            store = runtime.plans
        )
        val admissions = AmperAgentTaskAdmissionRegistry()
        val passive = AmperAgentPassiveTaskCoordinator(agentPlanPort)
        val continuation = AmperAgentExecutionContinuationCoordinator(agentPlanPort)
        val execution = AmperAgentCanonicalContinuationExecutionPort(
            admissions = admissions,
            plans = agentPlanPort,
            passive = passive,
            continuation = continuation
        )

        return AndroidSovereignAgentExecutionGraph(
            capabilities = capabilities,
            toolRegistry = registry,
            toolAudit = audit,
            toolFabric = fabric,
            actionLoop = actionLoop,
            inferencePort = inference,
            assistant = assistant,
            planningCoordinator = planning,
            planner = planner,
            agentPlanPort = agentPlanPort,
            agentAdmissions = admissions,
            agentPassiveTasks = passive,
            agentContinuation = continuation,
            agentContinuationExecution = execution
        )
    }
}
