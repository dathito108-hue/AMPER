package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentReflexDecisionRuntimeTest {
    @Test
    fun durableActiveIntentRecoversOnlyAfterResolverAndGateRevalidation() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedReflexDecisionRuntimeActivationStore(memory)
        var now = 100L
        var gateChecks = 0
        val gate = ReflexDecisionRuntimeActivationGate {
            gateChecks += 1
            Result.success(Unit)
        }
        val first = CanonicalReflexDecisionRuntimeController(
            activationGate = gate,
            activationStore = store,
            clock = { now++ }
        )
        val port = port("persistent-reflex-a", "a".repeat(64))

        first.activate(port).getOrThrow()
        assertEquals(1, gateChecks)
        val persisted = requireNotNull(store.load())
        assertEquals(ReflexRuntimeActivationIntentStatus.ACTIVE, persisted.status)
        assertEquals(port.checkpointId, persisted.checkpointId)

        val restarted = CanonicalReflexDecisionRuntimeController(
            activationGate = gate,
            activationStore = store,
            clock = { now++ }
        )
        assertNull(restarted.active())

        val recovered = restarted.recover(
            NativeReflexDecisionPortResolver { checkpointId, digest ->
                assertEquals(port.checkpointId, checkpointId)
                assertEquals(port.weightArtifactSha256, digest)
                Result.success(port)
            }
        ).getOrThrow()

        assertNotNull(recovered)
        assertEquals(port.checkpointId, restarted.active()?.checkpointId)
        assertEquals(2, gateChecks)
        assertEquals(persisted.generation, restarted.persistedIntent()?.generation)
    }

    @Test
    fun resolverIdentityMismatchFailsClosedWithoutActivatingPort() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedReflexDecisionRuntimeActivationStore(memory)
        val first = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            clock = { 200L }
        )
        first.activate(port("persistent-reflex-b", "b".repeat(64))).getOrThrow()

        val restarted = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            clock = { 201L }
        )
        val result = restarted.recover(
            NativeReflexDecisionPortResolver { _, _ ->
                Result.success(port("wrong-checkpoint", "b".repeat(64)))
            }
        )

        assertTrue(result.isFailure)
        assertNull(restarted.active())
        assertEquals(
            ReflexRuntimeActivationIntentStatus.ACTIVE,
            restarted.persistedIntent()?.status
        )
    }

    @Test
    fun durableRollbackPreventsOldCheckpointFromReturningAfterRestart() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedReflexDecisionRuntimeActivationStore(memory)
        var now = 300L
        val first = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            clock = { now++ }
        )
        val port = port("persistent-reflex-c", "c".repeat(64))
        first.activate(port).getOrThrow()
        val activeGeneration = requireNotNull(store.load()).generation

        val removed = first.rollback()
        assertEquals(port.checkpointId, removed?.checkpointId)
        val disabled = requireNotNull(store.load())
        assertEquals(ReflexRuntimeActivationIntentStatus.DISABLED, disabled.status)
        assertTrue(disabled.generation > activeGeneration)

        var resolverCalled = false
        val restarted = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            clock = { now++ }
        )
        val recovered = restarted.recover(
            NativeReflexDecisionPortResolver { _, _ ->
                resolverCalled = true
                Result.success(port)
            }
        ).getOrThrow()

        assertNull(recovered)
        assertFalse(resolverCalled)
        assertNull(restarted.active())
    }

    @Test
    fun durableIntentContainsNoToolPromptOrBackendPayload() {
        val memory = InMemoryMemoryOs()
        val store = MemoryBackedReflexDecisionRuntimeActivationStore(memory)
        val controller = CanonicalReflexDecisionRuntimeController(
            activationGate = ReflexDecisionRuntimeActivationGate { Result.success(Unit) },
            activationStore = store,
            clock = { 400L }
        )
        val port = port("persistent-reflex-private", "d".repeat(64))
        controller.activate(port).getOrThrow()

        val record = memory.recall(port.checkpointId.value, 16)
            .firstOrNull { it.kind == MemoryBackedReflexDecisionRuntimeActivationStore.KIND }
        assertNotNull(record)
        val content = requireNotNull(record).content
        assertTrue(content.contains(port.checkpointId.value))
        assertTrue(content.contains(port.weightArtifactSha256))
        assertFalse(content.contains("tool"))
        assertFalse(content.contains("prompt"))
        assertFalse(content.contains("approval"))
    }

    private fun port(
        checkpoint: String,
        digest: String
    ): NativeReflexDecisionPort = object : NativeReflexDecisionPort {
        override val checkpointId = NativeCheckpointId(checkpoint)
        override val weightArtifactSha256 = digest

        override fun predict(
            input: NativeReflexDecisionInput
        ): Result<NativeReflexDecisionPrediction> = Result.success(
            NativeReflexDecisionPrediction(
                disposition = ReflexDecisionDisposition.ESCALATE_SYSTEM2,
                confidence = 0.999,
                uncertainty = 0.001
            )
        )
    }
}
