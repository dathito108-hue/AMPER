package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedLivePerceptionTest {
    @Test
    fun canonicalPerceptionIngressFeedsWorkspaceAndWorldWithoutPersistingRawMemory() {
        val runtime = AmperRuntime.reference()
        val percept = Percept(
            id = "camera-phase146",
            modality = PerceptionModality.CAMERA,
            payload = "width=320;height=240;meanLuma=0.5000;contrast=0.1200",
            salience = 0.9,
            provenance = Provenance(
                source = "android-camera-preview",
                producer = "phase146-live-perception",
                confidence = 0.93
            )
        )

        val event = runtime.perception.ingest(percept)
        val context = runtime.context.capture("camera")

        assertEquals("camera-phase146", event.id)
        assertEquals("perception.camera", event.topic)
        assertTrue(context.workspaceEvents.any { it.id == percept.id })
        assertTrue(
            context.worldFacts.any {
                it.subject == "perception.camera" &&
                    it.statement == percept.payload &&
                    it.provenance.source == "android-camera-preview"
            }
        )
        assertTrue(context.memories.isEmpty())
    }

    @Test
    fun livePerceptionIsPresentInTheNextGroundedInferenceContext() {
        val runtime = AmperRuntime.reference()
        runtime.perception.ingest(
            Percept(
                modality = PerceptionModality.SENSOR,
                payload = "accelerometer=0.1000,9.7000,0.2000;light=42.0000",
                salience = 0.78,
                provenance = Provenance(
                    source = "android-sensor-fusion",
                    producer = "phase146-live-perception",
                    confidence = 0.98
                )
            )
        )

        val prompt = runtime.context.groundedPrompt("sensor")

        assertTrue(prompt.contains("perception.sensor"))
        assertTrue(prompt.contains("accelerometer=0.1000,9.7000,0.2000"))
        assertTrue(prompt.contains("<USER_REQUEST>"))
    }
}
