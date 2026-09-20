package io.amper.neuroos.core.v2

enum class OmegaComputeMode {
    REFLEX,
    FAST,
    STANDARD,
    REASON,
    DEEP,
    VERIFY
}

data class OmegaComputeBudget(
    val mode: OmegaComputeMode,
    val recurrentCycles: Int,
    val verifyPasses: Int,
    val targetFirstTokenMs: Long?,
    val allowInternetVerification: Boolean,
    val allowToolUse: Boolean
) {
    init {
        require(recurrentCycles >= 0)
        require(verifyPasses >= 0)
        require(targetFirstTokenMs == null || targetFirstTokenMs > 0L)
    }
}

data class OmegaReasoningRequest(
    val userComplexity: Int,
    val uncertainty: Double,
    val highConsequence: Boolean,
    val toolUseful: Boolean,
    val internetUseful: Boolean,
    val explicitDeepReasoning: Boolean = false
) {
    init {
        require(userComplexity in 0..100)
        require(uncertainty in 0.0..1.0)
    }
}

/**
 * Canonical adaptive-compute policy for AMPER OMEGA.
 *
 * One AMPER foundation is reused at different compute depths. The policy never chooses a second
 * model or inference runtime.
 */
object OmegaAdaptiveComputePolicy {
    fun plan(request: OmegaReasoningRequest): OmegaComputeBudget {
        if (
            request.highConsequence ||
            request.explicitDeepReasoning ||
            request.uncertainty >= 0.75
        ) {
            return OmegaComputeBudget(
                mode = if (request.highConsequence) OmegaComputeMode.VERIFY else OmegaComputeMode.DEEP,
                recurrentCycles = if (request.highConsequence) 8 else 6,
                verifyPasses = if (request.highConsequence) 2 else 1,
                targetFirstTokenMs = null,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        if (request.userComplexity >= 65 || request.uncertainty >= 0.45) {
            return OmegaComputeBudget(
                mode = OmegaComputeMode.REASON,
                recurrentCycles = 3,
                verifyPasses = 1,
                targetFirstTokenMs = 4_000L,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        if (request.userComplexity >= 30) {
            return OmegaComputeBudget(
                mode = OmegaComputeMode.STANDARD,
                recurrentCycles = 1,
                verifyPasses = 0,
                targetFirstTokenMs = 2_500L,
                allowInternetVerification = request.internetUseful,
                allowToolUse = request.toolUseful
            )
        }

        return OmegaComputeBudget(
            mode = OmegaComputeMode.FAST,
            recurrentCycles = 0,
            verifyPasses = 0,
            targetFirstTokenMs = 1_500L,
            allowInternetVerification = false,
            allowToolUse = request.toolUseful
        )
    }
}

enum class OmegaBackgroundMode {
    UI_BOUND,
    FOREGROUND_CONTINUATION,
    PERSISTED_JOB,
    EVENT_WAKE
}

data class OmegaBackgroundWork(
    val expectedRuntimeMs: Long,
    val userInitiated: Boolean,
    val mustSurviveUiExit: Boolean,
    val canBeDeferred: Boolean,
    val hasFutureTrigger: Boolean
) {
    init {
        require(expectedRuntimeMs >= 0L)
    }
}

/**
 * Android-safe background execution policy.
 *
 * UI exit and process death are different events. Long user-started work continues in a visible
 * foreground service; deferrable/proactive work is checkpointed into an OS-persisted job/event wake.
 */
object OmegaBackgroundExecutionPolicy {
    fun choose(work: OmegaBackgroundWork): OmegaBackgroundMode = when {
        work.hasFutureTrigger -> OmegaBackgroundMode.EVENT_WAKE
        work.canBeDeferred -> OmegaBackgroundMode.PERSISTED_JOB
        work.mustSurviveUiExit && work.userInitiated ->
            OmegaBackgroundMode.FOREGROUND_CONTINUATION
        else -> OmegaBackgroundMode.UI_BOUND
    }
}

enum class OmegaMilestoneId {
    M1_ARCHITECTURE_CONSOLIDATION,
    M2_AMI2_COMPILER,
    M3_AMNE2_RUNTIME,
    M4_AMCF_FOUNDATION,
    M5_AGENT_CORE_ALWAYS_ON,
    M6_COGNITIVE_MEMORY,
    M7_MULTIMODAL_VOICE,
    M8_3D_EMBODIMENT_HARDENING
}

data class OmegaMilestone(
    val id: OmegaMilestoneId,
    val exitCriteria: List<String>
) {
    init {
        require(exitCriteria.isNotEmpty())
        require(exitCriteria.all(String::isNotBlank))
    }
}

/**
 * Architecture lock for AMPER MOBILE OMEGA.
 *
 * This is intentionally code, not only documentation: unit tests can fail if the canonical roadmap
 * or invariants drift back toward multiple model engines.
 */
object OmegaArchitectureLock {
    const val architectureId: String = "AMPER-MOBILE-OMEGA"
    const val architectureVersion: Int = 2

    val invariants: Set<String> = linkedSetOf(
        "one-amper-foundation-runtime",
        "ami2-is-canonical-model-format",
        "amne2-is-canonical-execution-engine",
        "gguf-is-import-source-only",
        "adaptive-compute-fast-to-deep",
        "verification-before-confidence",
        "internet-is-governed-tool-not-model",
        "background-work-is-checkpointed",
        "foreground-work-survives-ui-exit",
        "portable-hardware-profile-not-device-name",
        "agent-actions-remain-audited",
        "memory-is-working-episodic-semantic-procedural",
        "3d-avatar-is-embodiment-not-cognition"
    )

    val milestones: List<OmegaMilestone> = listOf(
        OmegaMilestone(
            OmegaMilestoneId.M1_ARCHITECTURE_CONSOLIDATION,
            listOf(
                "OMEGA invariants locked in code and docs",
                "single AMPER Core production boundary retained",
                "connected internet gateway established",
                "background execution modes defined for Android",
                "AMI/AMNE v1 migration path to AMI2/AMNE2 is explicit"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M2_AMI2_COMPILER,
            listOf(
                "GGUF/source weights compile into AMI2",
                "tokenizer/chat protocol preserved",
                "device-independent logical graph separated from device packs",
                "deterministic lineage and integrity verified"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M3_AMNE2_RUNTIME,
            listOf(
                "ARM64 kernel dispatch",
                "NEON DOTPROD I8MM FP16 qualification",
                "mmap paging and KV management",
                "device autotuner",
                "Vulkan enabled only when physical benchmark wins"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M4_AMCF_FOUNDATION,
            listOf(
                "single mobile cognitive architecture",
                "adaptive depth and recurrent reasoning",
                "deep reasoning and verify/revise loop",
                "early exit for fast requests"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M5_AGENT_CORE_ALWAYS_ON,
            listOf(
                "passive tool execution",
                "proactive goals and triggers",
                "foreground continuation after UI exit",
                "persisted jobs restore after process death/reboot",
                "internet observation and task checkpointing"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M6_COGNITIVE_MEMORY,
            listOf(
                "working memory",
                "episodic memory",
                "semantic memory",
                "procedural skill memory"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M7_MULTIMODAL_VOICE,
            listOf(
                "vision",
                "screen context",
                "speech input",
                "speech output",
                "multimodal tool execution"
            )
        ),
        OmegaMilestone(
            OmegaMilestoneId.M8_3D_EMBODIMENT_HARDENING,
            listOf(
                "interactive 3D avatar",
                "lip sync gaze gestures and state animation",
                "thermal battery and memory adaptation",
                "crash/update migration",
                "cross-device physical qualification suite"
            )
        )
    )
}
