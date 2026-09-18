package io.amper.neuroos.core

import java.util.UUID

@JvmInline value class ToolId(val value: String)

enum class ToolSideEffect {
    READ_ONLY,
    LOCAL_STATE,
    EXTERNAL
}

data class ToolInputContract(
    val description: String,
    val acceptedValues: Set<String> = emptySet(),
    val maxLength: Int = 256
) {
    init {
        require(description.isNotBlank())
        require(description.length <= 256)
        require(maxLength in 1..4096)
        require(acceptedValues.size <= 32)
        require(acceptedValues.all { it.isNotBlank() && it.length <= 128 && '\n' !in it && '\r' !in it })
    }
}

data class ToolDescriptor(
    val id: ToolId,
    val name: String,
    val capability: CapabilityId,
    val sideEffect: ToolSideEffect,
    val inputContract: ToolInputContract = ToolInputContract("single-line text")
) {
    init { require(name.isNotBlank()) }
}

interface ToolProvider {
    val descriptor: ToolDescriptor
    fun execute(input: String): Result<String>
}

interface ToolRegistry {
    fun register(provider: ToolProvider)
    fun unregister(id: ToolId): Boolean
    fun route(capability: CapabilityId): ToolProvider?
    fun descriptors(): List<ToolDescriptor> = emptyList()
}

class InMemoryToolRegistry : ToolRegistry {
    private val providers = linkedMapOf<ToolId, ToolProvider>()

    @Synchronized
    override fun register(provider: ToolProvider) {
        providers[provider.descriptor.id] = provider
    }

    @Synchronized
    override fun unregister(id: ToolId): Boolean = providers.remove(id) != null

    @Synchronized
    override fun route(capability: CapabilityId): ToolProvider? = providers.values
        .firstOrNull { it.descriptor.capability == capability }

    @Synchronized
    override fun descriptors(): List<ToolDescriptor> = providers.values
        .map { it.descriptor }
        .sortedBy { it.capability.value }
}

data class ToolAuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val toolId: ToolId?,
    val capability: CapabilityId,
    val reason: String,
    val authorized: Boolean,
    val success: Boolean,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

interface ToolAuditLog {
    fun append(entry: ToolAuditEntry)
    fun snapshot(): List<ToolAuditEntry>
}

class InMemoryToolAuditLog : ToolAuditLog {
    private val entries = mutableListOf<ToolAuditEntry>()
    @Synchronized override fun append(entry: ToolAuditEntry) { entries += entry }
    @Synchronized override fun snapshot(): List<ToolAuditEntry> = entries.toList()
}

/**
 * Tool fabric extension for approvals that must remain bound to the exact provider
 * identity and side-effect class reviewed by the user.
 */
interface BoundToolFabric : ToolFabric {
    fun invokeBound(
        capability: CapabilityId,
        expectedToolId: ToolId,
        expectedSideEffect: ToolSideEffect,
        input: String,
        reason: String
    ): Result<String>
}

class AuditedToolFabric(
    private val gate: AuthorityGate,
    private val registry: ToolRegistry,
    private val audit: ToolAuditLog
) : BoundToolFabric {
    override fun invoke(capability: CapabilityId, input: String, reason: String): Result<String> {
        val provider = registry.route(capability)
            ?: return unavailable(capability, reason)
        return invokeResolved(provider, capability, input, reason)
    }

    override fun invokeBound(
        capability: CapabilityId,
        expectedToolId: ToolId,
        expectedSideEffect: ToolSideEffect,
        input: String,
        reason: String
    ): Result<String> {
        val provider = registry.route(capability)
            ?: return unavailable(capability, reason)
        val descriptor = provider.descriptor
        if (descriptor.id != expectedToolId || descriptor.sideEffect != expectedSideEffect) {
            audit.append(
                ToolAuditEntry(
                    toolId = descriptor.id,
                    capability = capability,
                    reason = reason,
                    authorized = false,
                    success = false
                )
            )
            return Result.failure(
                SecurityException(
                    "approved tool binding changed: expected ${expectedToolId.value}/${expectedSideEffect.name}, " +
                        "live ${descriptor.id.value}/${descriptor.sideEffect.name}"
                )
            )
        }
        return invokeResolved(provider, capability, input, reason)
    }

    private fun unavailable(capability: CapabilityId, reason: String): Result<String> {
        audit.append(
            ToolAuditEntry(
                toolId = null,
                capability = capability,
                reason = reason,
                authorized = false,
                success = false
            )
        )
        return Result.failure(IllegalStateException("no tool provider for capability ${capability.value}"))
    }

    private fun invokeResolved(
        provider: ToolProvider,
        capability: CapabilityId,
        input: String,
        reason: String
    ): Result<String> {
        if (!gate.authorize(capability, reason)) {
            audit.append(
                ToolAuditEntry(
                    toolId = provider.descriptor.id,
                    capability = capability,
                    reason = reason,
                    authorized = false,
                    success = false
                )
            )
            return Result.failure(SecurityException("tool capability ${capability.value} denied"))
        }

        val result = runCatching { provider.execute(input).getOrThrow() }
        audit.append(
            ToolAuditEntry(
                toolId = provider.descriptor.id,
                capability = capability,
                reason = reason,
                authorized = true,
                success = result.isSuccess
            )
        )
        return result
    }
}
