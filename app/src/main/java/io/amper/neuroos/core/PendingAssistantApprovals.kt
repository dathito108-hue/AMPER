package io.amper.neuroos.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Durable pre-approval snapshot for assistant-proposed side effects.
 *
 * Persisting this object does not authorize or claim a side effect. Execution authority remains in
 * [DurableAssistantActionExecutor], which creates the receipt-ledger claim only after explicit user
 * approval. This store exists solely so the approval checkpoint can survive process death.
 */
interface PendingAssistantApprovalStore {
    fun save(pending: SovereignAssistantTurnResult.PendingApproval): SovereignAssistantTurnResult.PendingApproval
    fun load(requestId: ActionRequestId): SovereignAssistantTurnResult.PendingApproval?
    fun list(limit: Int = 16): List<SovereignAssistantTurnResult.PendingApproval>
    fun delete(requestId: ActionRequestId): Boolean

    fun latest(conversationId: ConversationId? = null): SovereignAssistantTurnResult.PendingApproval? =
        list(32).firstOrNull { conversationId == null || it.conversationId == conversationId }
}

class MemoryBackedPendingAssistantApprovalStore(
    private val memory: MemoryOs
) : PendingAssistantApprovalStore {
    override fun save(
        pending: SovereignAssistantTurnResult.PendingApproval
    ): SovereignAssistantTurnResult.PendingApproval {
        memory.remember(
            MemoryRecord(
                id = recordId(pending.proposal.requestId),
                kind = KIND,
                content = PendingAssistantApprovalCodec.encode(pending),
                importance = 0.95,
                provenance = Provenance(
                    source = "assistant-pending-approval",
                    producer = "sovereign-assistant",
                    confidence = 1.0
                )
            )
        )
        return pending
    }

    override fun load(requestId: ActionRequestId): SovereignAssistantTurnResult.PendingApproval? =
        memory.get(recordId(requestId))
            ?.takeIf { it.kind == KIND }
            ?.let { PendingAssistantApprovalCodec.decode(it.content).getOrNull() }

    override fun list(limit: Int): List<SovereignAssistantTurnResult.PendingApproval> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return memory.recall(KIND, (limit * 2).coerceAtLeast(limit))
            .asSequence()
            .filter { it.kind == KIND }
            .sortedByDescending { it.createdAtEpochMs }
            .mapNotNull { PendingAssistantApprovalCodec.decode(it.content).getOrNull() }
            .take(limit)
            .toList()
    }

    override fun delete(requestId: ActionRequestId): Boolean = memory.forget(recordId(requestId))

    private fun recordId(requestId: ActionRequestId): MemoryId =
        MemoryId("assistant-pending-approval:${requestId.value}")

    companion object {
        const val KIND = "assistant-pending-approval-v1"
    }
}

internal object PendingAssistantApprovalCodec {
    private const val VERSION_V1 = "AMPER_ASSISTANT_PENDING_APPROVAL_V1"
    private const val VERSION_V2 = "AMPER_ASSISTANT_PENDING_APPROVAL_V2"
    private const val VERSION_V3 = "AMPER_ASSISTANT_PENDING_APPROVAL_V3"
    private const val VERSION_V4 = "AMPER_ASSISTANT_PENDING_APPROVAL_V4"

    fun encode(pending: SovereignAssistantTurnResult.PendingApproval): String {
        val boundProfile = requireNotNull(pending.boundInferenceProfile) {
            "V4 pending assistant approval requires a complete bound inference profile"
        }
        return buildString {
        appendLine(VERSION_V4)
        appendLine("REQUEST\t${enc(pending.proposal.requestId.value)}")
        appendLine("CONVERSATION\t${enc(pending.conversationId.value)}")
        appendLine("USER_PROMPT\t${enc(pending.userPrompt)}")
        appendLine("CAPABILITY\t${enc(pending.proposal.capability.value)}")
        appendLine("REASON\t${enc(pending.proposal.reason)}")
        appendLine("INPUT\t${enc(pending.proposal.input)}")
        appendLine("TOOL\t${enc(pending.toolId.value)}")
        appendLine("SIDE_EFFECT\t${pending.sideEffect.name}")
        appendLine("FIRST_MODEL\t${enc(pending.firstResponse.modelId.value)}")
        appendLine("FIRST_BACKEND\t${enc(pending.firstResponse.backendId)}")
        appendLine("FIRST_TEXT\t${enc(pending.firstResponse.text)}")
        appendLine("FIRST_PROMPT\t${enc(pending.firstPrompt)}")
        appendLine("BOUND_OUTPUT_TOKENS\t${boundProfile.maxOutputTokens}")
        appendLine("BOUND_TEMPERATURE\t${boundProfile.temperature}")
        appendLine("BOUND_SESSION_ROUTING\t${boundProfile.sessionRoutingPreference.name}")
        appendLine("BOUND_PROMPT_CHARS\t${boundProfile.maxPromptChars}")
        appendLine("BOUND_REFLECTION_MODE\t${boundProfile.reflectionMode.name}")
        pending.firstResponse.selectedCapabilities
            .sortedBy { it.value }
            .forEach { capability -> appendLine("FIRST_CAP\t${enc(capability.value)}") }
        pending.preferredCapabilityProfiles.forEach { profile ->
            append("PROFILE")
            profile.sortedBy { it.value }.forEach { capability ->
                append('\t')
                append(enc(capability.value))
            }
            appendLine()
        }
        }.trimEnd()
    }

    fun decode(content: String): Result<SovereignAssistantTurnResult.PendingApproval> = runCatching {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        val version = lines.firstOrNull()
        require(version in setOf(VERSION_V1, VERSION_V2, VERSION_V3, VERSION_V4)) {
            "unsupported pending assistant approval state"
        }
        val scalars = linkedMapOf<String, String>()
        val firstCapabilities = linkedSetOf<CapabilityId>()
        val preferredProfiles = mutableListOf<Set<CapabilityId>>()

        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "FIRST_CAP" -> {
                    require(parts.size == 2) { "invalid pending first capability" }
                    firstCapabilities += CapabilityId(dec(parts[1]))
                }
                "PROFILE" -> {
                    require(parts.size >= 2) { "invalid pending preferred profile" }
                    preferredProfiles += parts.drop(1).mapTo(linkedSetOf()) { CapabilityId(dec(it)) }
                }
                "REQUEST", "CONVERSATION", "USER_PROMPT", "CAPABILITY", "REASON", "INPUT",
                "TOOL", "SIDE_EFFECT", "FIRST_MODEL", "FIRST_BACKEND", "FIRST_TEXT", "FIRST_PROMPT",
                "BOUND_OUTPUT_TOKENS", "BOUND_TEMPERATURE", "BOUND_SESSION_ROUTING", "BOUND_PROMPT_CHARS",
                "BOUND_REFLECTION_MODE" -> {
                    require(parts.size == 2) { "invalid pending approval scalar" }
                    require(scalars.put(parts[0], parts[1]) == null) {
                        "duplicate pending approval scalar"
                    }
                }
                else -> error("unknown pending approval field")
            }
        }

        val required = setOf(
            "REQUEST", "CONVERSATION", "USER_PROMPT", "CAPABILITY", "REASON", "INPUT",
            "TOOL", "SIDE_EFFECT", "FIRST_MODEL", "FIRST_BACKEND", "FIRST_TEXT", "FIRST_PROMPT"
        )
        require(scalars.keys.containsAll(required)) { "incomplete pending assistant approval" }
        if (version == VERSION_V1) {
            require(
                "BOUND_OUTPUT_TOKENS" !in scalars &&
                    "BOUND_TEMPERATURE" !in scalars &&
                    "BOUND_SESSION_ROUTING" !in scalars &&
                    "BOUND_PROMPT_CHARS" !in scalars &&
                    "BOUND_REFLECTION_MODE" !in scalars
            ) { "legacy V1 pending approval cannot contain inference profile fields" }
        }
        if (version == VERSION_V2) {
            require("BOUND_PROMPT_CHARS" !in scalars && "BOUND_REFLECTION_MODE" !in scalars) {
                "legacy V2 pending approval cannot contain newer inference profile fields"
            }
        }
        if (version == VERSION_V3) {
            require("BOUND_REFLECTION_MODE" !in scalars) {
                "legacy V3 pending approval cannot contain Phase 142 reflection mode"
            }
        }

        val v2Fields = listOf(
            scalars["BOUND_OUTPUT_TOKENS"],
            scalars["BOUND_TEMPERATURE"],
            scalars["BOUND_SESSION_ROUTING"]
        )
        require(v2Fields.all { it == null } || v2Fields.all { it != null }) {
            "incomplete pending bound inference profile"
        }
        if (version == VERSION_V3) {
            val v3Fields = v2Fields + scalars["BOUND_PROMPT_CHARS"]
            require(v3Fields.all { it == null } || v3Fields.all { it != null }) {
                "incomplete pending V3 bound inference profile"
            }
        }
        if (version == VERSION_V4) {
            val v4Fields = v2Fields + listOf(
                scalars["BOUND_PROMPT_CHARS"],
                scalars["BOUND_REFLECTION_MODE"]
            )
            require(v4Fields.all { it != null }) {
                "V4 pending approval requires a complete bound inference profile"
            }
        }
        val boundProfile = if (v2Fields.all { it != null }) {
            BoundConversationInferenceProfile(
                maxOutputTokens = scalars.getValue("BOUND_OUTPUT_TOKENS").toInt(),
                temperature = scalars.getValue("BOUND_TEMPERATURE").toDouble(),
                sessionRoutingPreference = TitanSessionRoutingPreference.valueOf(
                    scalars.getValue("BOUND_SESSION_ROUTING")
                ),
                maxPromptChars = when (version) {
                    VERSION_V3, VERSION_V4 -> scalars.getValue("BOUND_PROMPT_CHARS").toInt()
                    else -> ConversationInferenceProfile.DEFAULT_PROMPT_CHARS
                },
                reflectionMode = when (version) {
                    VERSION_V4 -> ConversationReflectionMode.valueOf(
                        scalars.getValue("BOUND_REFLECTION_MODE")
                    )
                    else -> ConversationReflectionMode.STANDARD
                }
            )
        } else {
            null
        }

        val proposal = ActionProposal(
            requestId = ActionRequestId(dec(scalars.getValue("REQUEST"))),
            capability = CapabilityId(dec(scalars.getValue("CAPABILITY"))),
            reason = dec(scalars.getValue("REASON")),
            input = dec(scalars.getValue("INPUT"))
        )
        SovereignAssistantTurnResult.PendingApproval(
            conversationId = ConversationId(dec(scalars.getValue("CONVERSATION"))),
            userPrompt = dec(scalars.getValue("USER_PROMPT")),
            proposal = proposal,
            toolId = ToolId(dec(scalars.getValue("TOOL"))),
            sideEffect = ToolSideEffect.valueOf(scalars.getValue("SIDE_EFFECT")),
            firstResponse = InferenceResponse(
                modelId = ModelId(dec(scalars.getValue("FIRST_MODEL"))),
                backendId = dec(scalars.getValue("FIRST_BACKEND")),
                text = dec(scalars.getValue("FIRST_TEXT")),
                selectedCapabilities = firstCapabilities.toSet()
            ),
            firstPrompt = dec(scalars.getValue("FIRST_PROMPT")),
            preferredCapabilityProfiles = preferredProfiles.toList(),
            boundInferenceProfile = boundProfile
        )
    }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
