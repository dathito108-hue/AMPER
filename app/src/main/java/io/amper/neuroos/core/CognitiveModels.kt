package io.amper.neuroos.core

class CanonicalSelfModel : SelfModel {
    private val invariants = linkedSetOf(
        "one-sovereign-identity",
        "models-are-replaceable-capabilities",
        "external-actions-require-authority",
        "self-evolution-stays-inside-containment",
        "evolution-keeps-recoverable-canonical-checkpoints"
    )
    @Volatile private var lastIntent: String? = null

    override fun snapshot(): SelfSnapshot = SelfSnapshot(
        identity = "AMPER",
        architecture = "APEX-MUXER SOVEREIGN NEURO-OS",
        invariants = invariants.toSet(),
        lastIntent = lastIntent
    )

    override fun observeIntent(intent: String): SelfSnapshot {
        require(intent.isNotBlank())
        lastIntent = intent
        return snapshot()
    }
}

class CanonicalGoalSystem : GoalSystem {
    private val goals = linkedMapOf<GoalId, GoalState>()

    init {
        val root = GoalState(
            id = GoalId("canonical-continuity"),
            objective = "Preserve sovereign identity and containment while autonomously evolving internal architecture, models, skills and capabilities",
            priority = 1.0
        )
        goals[root.id] = root
    }

    @Synchronized
    override fun align(intent: String): GoalState {
        require(intent.isNotBlank())
        val id = GoalId("intent-${Integer.toUnsignedString(intent.hashCode(), 16)}")
        return goals.getOrPut(id) {
            GoalState(id = id, objective = intent, priority = 0.8)
        }
    }

    @Synchronized
    override fun active(): List<GoalState> = goals.values.filter { it.status == "ACTIVE" }
        .sortedByDescending { it.priority }
}

class CanonicalWorldModel : WorldModel {
    private val facts = linkedMapOf<WorldFactId, WorldFact>()

    @Synchronized
    override fun observe(event: CognitiveEvent, provenance: Provenance): WorldFact {
        val fact = WorldFact(
            subject = event.topic,
            statement = event.payload,
            confidence = provenance.confidence,
            provenance = provenance
        )
        facts[fact.id] = fact
        return fact
    }

    @Synchronized
    override fun query(query: String, limit: Int): List<WorldFact> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val q = query.trim().lowercase()
        return facts.values.asSequence()
            .filter { q.isBlank() || it.subject.lowercase().contains(q) || it.statement.lowercase().contains(q) }
            .sortedByDescending { it.provenance.observedAtEpochMs }
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun size(): Int = facts.size
}