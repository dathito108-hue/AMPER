package io.amper.neuroos.core

data class CognitiveMemoryContextUsage(
    val workingItems: Int,
    val episodicItems: Int,
    val semanticKnowledgeItems: Int,
    val epistemicBeliefItems: Int,
    val proceduralItems: Int
) {
    init {
        require(workingItems >= 0)
        require(episodicItems >= 0)
        require(semanticKnowledgeItems >= 0)
        require(epistemicBeliefItems >= 0)
        require(proceduralItems >= 0)
    }

    val totalCognitiveItems: Int
        get() =
            workingItems +
                episodicItems +
                semanticKnowledgeItems +
                epistemicBeliefItems +
                proceduralItems
}

/**
 * Phase676 one mobile envelope over the four cognitive-memory domains exposed to planning context.
 *
 * Generic sovereign memories keep a separate bounded lane because notes/assistant responses are not
 * one of the four M6 memory domains. This policy persists nothing and owns no retrieval store.
 */
data class CognitiveMemoryContextEnvelope(
    val maxGenericMemories: Int = DEFAULT_GENERIC_MEMORIES,
    val maxWorkingItems: Int = DEFAULT_WORKING_ITEMS,
    val maxEpisodicItems: Int = DEFAULT_EPISODIC_ITEMS,
    val maxSemanticKnowledgeItems: Int = DEFAULT_SEMANTIC_KNOWLEDGE_ITEMS,
    val maxEpistemicBeliefItems: Int = DEFAULT_EPISTEMIC_BELIEF_ITEMS,
    val maxProceduralItems: Int = DEFAULT_PROCEDURAL_ITEMS
) {
    init {
        require(maxGenericMemories in 0..MAX_GENERIC_MEMORIES)
        require(maxWorkingItems in 0..MAX_WORKING_ITEMS)
        require(maxEpisodicItems in 0..MAX_EPISODIC_ITEMS)
        require(maxSemanticKnowledgeItems in 0..MAX_SEMANTIC_KNOWLEDGE_ITEMS)
        require(maxEpistemicBeliefItems in 0..MAX_EPISTEMIC_BELIEF_ITEMS)
        require(maxProceduralItems in 0..MAX_PROCEDURAL_ITEMS)
    }

    val maxTotalCognitiveItems: Int
        get() =
            maxWorkingItems +
                maxEpisodicItems +
                maxSemanticKnowledgeItems +
                maxEpistemicBeliefItems +
                maxProceduralItems

    fun usage(snapshot: SovereignContextSnapshot): CognitiveMemoryContextUsage =
        CognitiveMemoryContextUsage(
            workingItems = snapshot.workspaceEvents.size,
            episodicItems = snapshot.episodicMemories.size,
            semanticKnowledgeItems = snapshot.semanticKnowledge.size,
            epistemicBeliefItems = snapshot.epistemicBeliefs.size,
            proceduralItems = snapshot.strategyEvidence.size
        )

    fun validate(snapshot: SovereignContextSnapshot): CognitiveMemoryContextUsage {
        require(snapshot.memories.size <= maxGenericMemories) {
            "generic sovereign memory context exceeded envelope"
        }
        val usage = usage(snapshot)
        require(usage.workingItems <= maxWorkingItems) {
            "working-memory context exceeded envelope"
        }
        require(usage.episodicItems <= maxEpisodicItems) {
            "episodic-memory context exceeded envelope"
        }
        require(usage.semanticKnowledgeItems <= maxSemanticKnowledgeItems) {
            "semantic-knowledge context exceeded envelope"
        }
        require(usage.epistemicBeliefItems <= maxEpistemicBeliefItems) {
            "epistemic-belief context exceeded envelope"
        }
        require(usage.proceduralItems <= maxProceduralItems) {
            "procedural-memory context exceeded envelope"
        }
        require(usage.totalCognitiveItems <= maxTotalCognitiveItems) {
            "cognitive-memory context exceeded total envelope"
        }
        return usage
    }

    companion object {
        const val DEFAULT_GENERIC_MEMORIES: Int = 6
        const val DEFAULT_WORKING_ITEMS: Int = 6
        const val DEFAULT_EPISODIC_ITEMS: Int = 6
        const val DEFAULT_SEMANTIC_KNOWLEDGE_ITEMS: Int = 6
        const val DEFAULT_EPISTEMIC_BELIEF_ITEMS: Int = 6
        const val DEFAULT_PROCEDURAL_ITEMS: Int = 4

        const val MAX_GENERIC_MEMORIES: Int = 12
        const val MAX_WORKING_ITEMS: Int = 12
        const val MAX_EPISODIC_ITEMS: Int = 12
        const val MAX_SEMANTIC_KNOWLEDGE_ITEMS: Int = 16
        const val MAX_EPISTEMIC_BELIEF_ITEMS: Int = 16
        const val MAX_PROCEDURAL_ITEMS: Int = 8
    }
}
