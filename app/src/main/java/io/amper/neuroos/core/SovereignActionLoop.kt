package io.amper.neuroos.core

import java.util.UUID

@JvmInline
value class ActionRequestId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class ActionProposal(
    val requestId: ActionRequestId = ActionRequestId(UUID.randomUUID().toString()),
    val capability: CapabilityId,
    val reason: String,
    val input: String
) {
    init {
        require(capability.value.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(reason.isNotBlank() && reason.length <= 512)
        require(input.length <= 4096)
    }
}

enum class ActionStatus {
    NO_ACTION,
    MALFORMED,
    UNAVAILABLE,
    REQUIRES_CONFIRMATION,
    EXECUTED,
    DENIED,
    FAILED
}

data class ActionOutcome(
    val status: ActionStatus,
    val proposal: ActionProposal? = null,
    val toolId: ToolId? = null,
    val sideEffect: ToolSideEffect? = null,
    val output: String? = null,
    val detail: String? = null
)

sealed interface ActionParseResult {
    data object NoAction : ActionParseResult
    data class Valid(val proposal: ActionProposal) : ActionParseResult
    data class Malformed(val detail: String) : ActionParseResult
}

/**
 * Strict text protocol between a replaceable cognitive model and AMPER's action layer.
 *
 * A tool request is recognized only when the complete model output is exactly one
 * AMPER_ACTION_V1 envelope. Text surrounding the envelope makes it malformed rather
 * than executable, which prevents examples/quoted actions from firing accidentally.
 */
object TitanActionProtocol {
    private const val OPEN = "<AMPER_ACTION_V1>"
    private const val CLOSE = "</AMPER_ACTION_V1>"

    /**
     * True while a streamed prefix could still become one exact executable action envelope.
     *
     * Leading whitespace is retained because [parse] trims the complete output. Once the first
     * non-whitespace content diverges from [OPEN], the complete output can no longer be executable
     * as an action and may be shown as ordinary assistant text.
     */
    fun couldStillBeExecutableEnvelopePrefix(modelOutputPrefix: String): Boolean {
        val text = modelOutputPrefix.dropWhile { it.isWhitespace() }
        if (text.isEmpty()) return true
        return OPEN.startsWith(text) || text.startsWith(OPEN)
    }

    fun parse(modelOutput: String): ActionParseResult {
        val text = modelOutput.trim()
        if (!text.contains(OPEN) && !text.contains(CLOSE)) return ActionParseResult.NoAction
        if (!text.startsWith(OPEN) || !text.endsWith(CLOSE)) {
            return ActionParseResult.Malformed("action envelope must be the entire model output")
        }

        return runCatching {
            val body = text.removePrefix(OPEN).removeSuffix(CLOSE).trim()
            val fields = linkedMapOf<String, String>()
            body.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val separator = line.indexOf('=')
                    require(separator > 0) { "action field missing '='" }
                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    require(key in setOf("capability", "reason", "input")) { "unknown action field: $key" }
                    require(fields.put(key, value) == null) { "duplicate action field: $key" }
                }
            require(fields.keys == setOf("capability", "reason", "input")) { "action requires capability, reason and input" }
            ActionProposal(
                capability = CapabilityId(fields.getValue("capability")),
                reason = fields.getValue("reason"),
                input = fields.getValue("input")
            )
        }.fold(
            onSuccess = ActionParseResult::Valid,
            onFailure = { ActionParseResult.Malformed(it.message ?: "malformed action") }
        )
    }

    fun instructions(
        capabilities: Collection<CapabilityId>,
        descriptors: Collection<ToolDescriptor> = emptyList()
    ): String = buildString {
        appendLine("AMPER tools are optional. Do not claim a tool ran unless AMPER returns a tool result.")
        appendLine("When a tool is necessary, output only this exact envelope and nothing else:")
        appendLine(OPEN)
        appendLine("capability=<one allowed capability>")
        appendLine("reason=<short user-centered reason>")
        appendLine("input=<single-line input matching that tool contract>")
        appendLine(CLOSE)
        if (capabilities.isNotEmpty()) {
            appendLine("Allowed capability names: ${capabilities.joinToString(",") { it.value }}")
        }
        val allowed = capabilities.map { it.value }.toSet()
        descriptors
            .asSequence()
            .filter { it.capability.value in allowed }
            .sortedBy { it.capability.value }
            .take(16)
            .forEach { descriptor ->
                val contract = descriptor.inputContract
                val values = contract.acceptedValues
                    .sorted()
                    .joinToString("|")
                    .ifBlank { "free-text" }
                appendLine(
                    "TOOL ${descriptor.capability.value} side_effect=${descriptor.sideEffect} " +
                        "input_values=$values max_input_chars=${contract.maxLength} " +
                        "description=${sanitizeManifest(contract.description)}"
                )
            }
    }.trim()

    private fun sanitizeManifest(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('<', '[')
        .replace('>', ']')
        .take(256)
}

data class SovereignActionPolicy(
    val autoExecuteReadOnly: Boolean = true
)

/**
 * Converts model proposals into governed actions. The model never receives a direct
 * ToolProvider reference. Every execution, including a user-approved side effect,
 * still passes through AuditedToolFabric and its AuthorityGate.
 */
class SovereignActionLoop(
    private val registry: ToolRegistry,
    private val fabric: ToolFabric,
    private val memory: MemoryOs,
    private val workspace: GlobalWorkspace,
    private val policy: SovereignActionPolicy = SovereignActionPolicy(),
    private val competence: CapabilityCompetenceModel? = null
) {
    fun descriptors(): List<ToolDescriptor> = registry.descriptors()

    /** Exact descriptor selected by the live registry route for this capability. */
    fun descriptorFor(capability: CapabilityId): ToolDescriptor? = registry.route(capability)?.descriptor

    fun evaluateModelOutput(modelOutput: String): ActionOutcome = when (val parsed = TitanActionProtocol.parse(modelOutput)) {
        ActionParseResult.NoAction -> ActionOutcome(ActionStatus.NO_ACTION)
        is ActionParseResult.Malformed -> record(
            ActionOutcome(ActionStatus.MALFORMED, detail = parsed.detail)
        )
        is ActionParseResult.Valid -> evaluate(parsed.proposal)
    }

    fun evaluate(proposal: ActionProposal): ActionOutcome {
        val provider = registry.route(proposal.capability)
            ?: return record(ActionOutcome(
                status = ActionStatus.UNAVAILABLE,
                proposal = proposal,
                detail = "no tool provider for capability ${proposal.capability.value}"
            ))
        val descriptor = provider.descriptor

        validateInput(descriptor, proposal)?.let { detail ->
            return record(ActionOutcome(
                status = ActionStatus.MALFORMED,
                proposal = proposal,
                toolId = descriptor.id,
                sideEffect = descriptor.sideEffect,
                detail = detail
            ))
        }

        if (descriptor.sideEffect != ToolSideEffect.READ_ONLY || !policy.autoExecuteReadOnly) {
            return record(ActionOutcome(
                status = ActionStatus.REQUIRES_CONFIRMATION,
                proposal = proposal,
                toolId = descriptor.id,
                sideEffect = descriptor.sideEffect,
                detail = "${descriptor.sideEffect} action requires explicit approval"
            ))
        }
        return executeThroughFabric(proposal, descriptor.id, descriptor.sideEffect)
    }

    /**
     * Evaluates one planned step while preserving the exact provider identity and
     * side-effect class that were bound when the plan was created. READ_ONLY execution
     * is rechecked again inside BoundToolFabric immediately before provider invocation.
     */
    fun evaluateBound(
        proposal: ActionProposal,
        expectedToolId: ToolId,
        expectedSideEffect: ToolSideEffect
    ): ActionOutcome {
        val provider = registry.route(proposal.capability)
            ?: return record(
                ActionOutcome(
                    status = ActionStatus.UNAVAILABLE,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = "no tool provider for capability ${proposal.capability.value}"
                )
            )
        val descriptor = provider.descriptor
        if (descriptor.id != expectedToolId || descriptor.sideEffect != expectedSideEffect) {
            return record(
                ActionOutcome(
                    status = ActionStatus.DENIED,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = "planned tool binding changed before execution"
                )
            )
        }
        validateInput(descriptor, proposal)?.let { detail ->
            return record(
                ActionOutcome(
                    status = ActionStatus.MALFORMED,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = detail
                )
            )
        }
        if (expectedSideEffect != ToolSideEffect.READ_ONLY || !policy.autoExecuteReadOnly) {
            return record(
                ActionOutcome(
                    status = ActionStatus.REQUIRES_CONFIRMATION,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = "$expectedSideEffect action requires explicit approval"
                )
            )
        }
        val boundFabric = fabric as? BoundToolFabric
            ?: return record(
                ActionOutcome(
                    status = ActionStatus.DENIED,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = "tool fabric does not support provider-bound execution"
                )
            )
        return executeThroughBoundFabric(
            proposal = proposal,
            toolId = expectedToolId,
            sideEffect = expectedSideEffect,
            boundFabric = boundFabric
        )
    }

    /** Called only after the UI/user has explicitly approved a pending proposal. */
    fun approve(proposal: ActionProposal): ActionOutcome {
        val provider = registry.route(proposal.capability)
            ?: return record(ActionOutcome(
                status = ActionStatus.UNAVAILABLE,
                proposal = proposal,
                detail = "no tool provider for capability ${proposal.capability.value}"
            ))
        val descriptor = provider.descriptor

        validateInput(descriptor, proposal)?.let { detail ->
            return record(ActionOutcome(
                status = ActionStatus.MALFORMED,
                proposal = proposal,
                toolId = descriptor.id,
                sideEffect = descriptor.sideEffect,
                detail = detail
            ))
        }
        return executeThroughFabric(proposal, descriptor.id, descriptor.sideEffect)
    }

    /**
     * Explicit approval bound to the provider identity and side-effect class shown to
     * the user. Provider identity/side-effect drift is deliberately adjudicated by
     * BoundToolFabric so the failed binding check is captured in the authoritative
     * tool audit immediately before any provider could execute.
     */
    fun approveBound(
        proposal: ActionProposal,
        expectedToolId: ToolId,
        expectedSideEffect: ToolSideEffect
    ): ActionOutcome {
        val boundFabric = fabric as? BoundToolFabric
            ?: return record(
                ActionOutcome(
                    status = ActionStatus.DENIED,
                    proposal = proposal,
                    toolId = expectedToolId,
                    sideEffect = expectedSideEffect,
                    detail = "tool fabric does not support provider-bound approvals"
                )
            )

        val provider = registry.route(proposal.capability)
        val descriptor = provider?.descriptor
        if (descriptor != null && descriptor.id == expectedToolId && descriptor.sideEffect == expectedSideEffect) {
            validateInput(descriptor, proposal)?.let { detail ->
                return record(
                    ActionOutcome(
                        status = ActionStatus.MALFORMED,
                        proposal = proposal,
                        toolId = expectedToolId,
                        sideEffect = expectedSideEffect,
                        detail = detail
                    )
                )
            }
        }

        return executeThroughBoundFabric(
            proposal = proposal,
            toolId = expectedToolId,
            sideEffect = expectedSideEffect,
            boundFabric = boundFabric
        )
    }

    private fun validateInput(descriptor: ToolDescriptor, proposal: ActionProposal): String? {
        val contract = descriptor.inputContract
        if (proposal.input.length > contract.maxLength) {
            return "input exceeds ${contract.maxLength} characters for ${descriptor.capability.value}"
        }
        if (contract.acceptedValues.isNotEmpty()) {
            val normalized = proposal.input.trim().lowercase()
            val accepted = contract.acceptedValues.map { it.trim().lowercase() }.toSet()
            if (normalized !in accepted) {
                return "input is outside declared contract for ${descriptor.capability.value}"
            }
        }
        return null
    }

    private fun executeThroughFabric(
        proposal: ActionProposal,
        toolId: ToolId,
        sideEffect: ToolSideEffect
    ): ActionOutcome {
        val result = fabric.invoke(proposal.capability, proposal.input, proposal.reason)
        return record(fromFabricResult(proposal, toolId, sideEffect, result))
    }

    private fun executeThroughBoundFabric(
        proposal: ActionProposal,
        toolId: ToolId,
        sideEffect: ToolSideEffect,
        boundFabric: BoundToolFabric
    ): ActionOutcome {
        val result = boundFabric.invokeBound(
            capability = proposal.capability,
            expectedToolId = toolId,
            expectedSideEffect = sideEffect,
            input = proposal.input,
            reason = proposal.reason
        )
        return record(fromFabricResult(proposal, toolId, sideEffect, result))
    }

    private fun fromFabricResult(
        proposal: ActionProposal,
        toolId: ToolId,
        sideEffect: ToolSideEffect,
        result: Result<String>
    ): ActionOutcome = result.fold(
        onSuccess = {
            ActionOutcome(
                status = ActionStatus.EXECUTED,
                proposal = proposal,
                toolId = toolId,
                sideEffect = sideEffect,
                output = it
            )
        },
        onFailure = { error ->
            ActionOutcome(
                status = if (error is SecurityException) ActionStatus.DENIED else ActionStatus.FAILED,
                proposal = proposal,
                toolId = toolId,
                sideEffect = sideEffect,
                detail = error.message ?: error::class.java.simpleName
            )
        }
    )

    private fun record(outcome: ActionOutcome): ActionOutcome {
        val proposal = outcome.proposal
        val capability = proposal?.capability?.value ?: "none"
        memory.remember(
            MemoryRecord(
                kind = "tool-action",
                content = buildString {
                    append("status=${outcome.status};capability=$capability")
                    proposal?.let { append(";request=${it.requestId.value};reason=${it.reason.take(256)}") }
                    outcome.toolId?.let { append(";tool=${it.value}") }
                    outcome.sideEffect?.let { append(";side_effect=${it.name}") }
                    outcome.output?.let { append(";output=${it.take(512)}") }
                    outcome.detail?.let { append(";detail=${it.take(256)}") }
                },
                importance = if (outcome.status == ActionStatus.EXECUTED) 0.75 else 0.65,
                provenance = Provenance(
                    source = "titan-action-loop",
                    producer = outcome.toolId?.value ?: "sovereign-action-gate",
                    confidence = 1.0
                )
            )
        )
        workspace.publish(
            CognitiveEvent(
                topic = "tool.action.${outcome.status.name.lowercase()}",
                payload = "$capability:${outcome.toolId?.value ?: "none"}",
                salience = if (outcome.status == ActionStatus.REQUIRES_CONFIRMATION) 0.95 else 0.8
            )
        )
        // Learning is observational only. A competence-ledger failure must never rewrite the
        // authoritative tool/action outcome or weaken the authority gate that produced it.
        runCatching { competence?.observe(outcome) }
        return outcome
    }
}
