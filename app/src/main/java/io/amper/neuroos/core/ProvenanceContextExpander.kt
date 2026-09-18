package io.amper.neuroos.core

/**
 * Deterministically reconnects retrieved memories to the provenance chain that produced them.
 *
 * Expansion is intentionally bounded and ancestor-only: a retrieved memory may bring its
 * explicit provenance parents into sovereign context, but unrelated descendants are never
 * scanned globally. This keeps retrieval local, explainable and independent of an embedding
 * service while preserving the existing durable MemoryRecord format.
 */
internal object ProvenanceContextExpander {
    fun expand(
        seeds: List<MemoryRecord>,
        memory: MemoryOs,
        limit: Int,
        maxDepth: Int = 2
    ): List<MemoryRecord> {
        require(limit >= 0)
        require(maxDepth >= 0)
        if (limit == 0 || seeds.isEmpty()) return emptyList()

        val result = LinkedHashMap<MemoryId, MemoryRecord>()
        val frontier = ArrayDeque<Pair<MemoryRecord, Int>>()

        seeds.forEach { seed ->
            if (result.size < limit && result.putIfAbsent(seed.id, seed) == null) {
                frontier.addLast(seed to 0)
            }
        }

        while (frontier.isNotEmpty() && result.size < limit) {
            val (record, depth) = frontier.removeFirst()
            if (depth >= maxDepth) continue

            record.provenance.parents
                .sortedBy { it.value }
                .forEach { parentId ->
                    if (result.size >= limit || result.containsKey(parentId)) return@forEach
                    val parent = memory.get(parentId) ?: return@forEach
                    result[parent.id] = parent
                    frontier.addLast(parent to (depth + 1))
                }
        }

        return result.values.toList()
    }
}
