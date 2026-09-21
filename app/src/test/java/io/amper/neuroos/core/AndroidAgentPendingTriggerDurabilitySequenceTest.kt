package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentPendingTriggerDurabilitySequenceTest {
    @Test
    fun successPersistsProvenanceBeforeSchedulingAndAcknowledgesFifoLast() {
        val order = mutableListOf<String>()

        val result = AndroidAgentPendingTriggerDurabilitySequence.commit(
            expectedEventWakeSchedule = true,
            persistProvenance = {
                order += "provenance"
                Result.success(Unit)
            },
            installEventWake = {
                order += "phase657"
                Result.success(true)
            },
            acknowledgeFifo = {
                order += "fifo-ack"
                Result.success("acknowledged")
            }
        )

        assertEquals("acknowledged", result.getOrThrow())
        assertEquals(listOf("provenance", "phase657", "fifo-ack"), order)
    }

    @Test
    fun provenanceFailurePreventsSchedulerAndFifoAck() {
        val order = mutableListOf<String>()

        val result = AndroidAgentPendingTriggerDurabilitySequence.commit(
            expectedEventWakeSchedule = true,
            persistProvenance = {
                order += "provenance"
                Result.failure(IllegalStateException("journal unavailable"))
            },
            installEventWake = {
                order += "phase657"
                Result.success(true)
            },
            acknowledgeFifo = {
                order += "fifo-ack"
                Result.success(Unit)
            }
        )

        assertTrue(result.isFailure)
        assertEquals(listOf("provenance"), order)
    }

    @Test
    fun schedulerFailureOrDispositionDriftPreventsFifoAck() {
        val failedOrder = mutableListOf<String>()
        val failed = AndroidAgentPendingTriggerDurabilitySequence.commit(
            expectedEventWakeSchedule = true,
            persistProvenance = {
                failedOrder += "provenance"
                Result.success(Unit)
            },
            installEventWake = {
                failedOrder += "phase657"
                Result.failure(IllegalStateException("scheduler unavailable"))
            },
            acknowledgeFifo = {
                failedOrder += "fifo-ack"
                Result.success(Unit)
            }
        )

        assertTrue(failed.isFailure)
        assertEquals(listOf("provenance", "phase657"), failedOrder)

        val driftOrder = mutableListOf<String>()
        val drifted = AndroidAgentPendingTriggerDurabilitySequence.commit(
            expectedEventWakeSchedule = true,
            persistProvenance = {
                driftOrder += "provenance"
                Result.success(Unit)
            },
            installEventWake = {
                driftOrder += "phase657"
                Result.success(false)
            },
            acknowledgeFifo = {
                driftOrder += "fifo-ack"
                Result.success(Unit)
            }
        )

        assertTrue(drifted.isFailure)
        assertEquals(listOf("provenance", "phase657"), driftOrder)
    }

    @Test
    fun verifiedNonRunnableHandoffMayCancelWakeThenAcknowledge() {
        val order = mutableListOf<String>()

        val result = AndroidAgentPendingTriggerDurabilitySequence.commit(
            expectedEventWakeSchedule = false,
            persistProvenance = {
                order += "provenance"
                Result.success(Unit)
            },
            installEventWake = {
                order += "phase657-cancel"
                Result.success(false)
            },
            acknowledgeFifo = {
                order += "fifo-ack"
                Result.success(Unit)
            }
        )

        result.getOrThrow()
        assertEquals(listOf("provenance", "phase657-cancel", "fifo-ack"), order)
    }
}
