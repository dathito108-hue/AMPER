package io.amper.neuroos.core

@JvmInline value class ProviderId(val value: String)

data class CapabilityProviderDescriptor(
    val id: ProviderId,
    val name: String,
    val capabilities: Set<CapabilityId>,
    val priority: Int = 0,
    val local: Boolean = true,
    val available: Boolean = true
) {
    init {
        require(name.isNotBlank())
        require(capabilities.isNotEmpty())
    }
}

interface CapabilityRegistry {
    fun register(provider: CapabilityProviderDescriptor)
    fun unregister(id: ProviderId): Boolean
    fun route(capability: CapabilityId): CapabilityProviderDescriptor?
    fun providers(): List<CapabilityProviderDescriptor>
}

class InMemoryCapabilityRegistry : CapabilityRegistry {
    private val entries = linkedMapOf<ProviderId, CapabilityProviderDescriptor>()

    @Synchronized
    override fun register(provider: CapabilityProviderDescriptor) {
        entries[provider.id] = provider
    }

    @Synchronized
    override fun unregister(id: ProviderId): Boolean = entries.remove(id) != null

    @Synchronized
    override fun route(capability: CapabilityId): CapabilityProviderDescriptor? = entries.values
        .asSequence()
        .filter { it.available && capability in it.capabilities }
        .sortedWith(compareByDescending<CapabilityProviderDescriptor> { it.local }
            .thenByDescending { it.priority }
            .thenBy { it.id.value })
        .firstOrNull()

    @Synchronized
    override fun providers(): List<CapabilityProviderDescriptor> = entries.values.toList()
}
