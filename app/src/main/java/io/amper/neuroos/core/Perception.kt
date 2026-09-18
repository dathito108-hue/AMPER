package io.amper.neuroos.core

import java.util.UUID

enum class PerceptionModality {
    TEXT,
    IMAGE,
    AUDIO,
    SCREEN,
    CAMERA,
    SENSOR
}

data class Percept(
    val id: String = UUID.randomUUID().toString(),
    val modality: PerceptionModality,
    val payload: String,
    val salience: Double = 0.5,
    val provenance: Provenance
) {
    init {
        require(payload.isNotBlank())
        require(salience in 0.0..1.0)
    }
}

interface PerceptionBus {
    fun ingest(percept: Percept): CognitiveEvent
}

class CanonicalPerceptionBus(
    private val workspace: GlobalWorkspace,
    private val world: WorldModel
) : PerceptionBus {
    override fun ingest(percept: Percept): CognitiveEvent {
        val event = CognitiveEvent(
            id = percept.id,
            topic = "perception.${percept.modality.name.lowercase()}",
            payload = percept.payload,
            salience = percept.salience
        )
        workspace.publish(event)
        world.observe(event, percept.provenance)
        return event
    }
}
