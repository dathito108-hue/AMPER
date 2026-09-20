package io.amper.neuroos.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class AmperExecutionLanesTest {
    @Test
    fun blockedMaintenanceLaneDoesNotDelayInteractiveWork() {
        val lanes = AmperExecutionLanes()
        val maintenanceStarted = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val interactiveFinished = CountDownLatch(1)

        try {
            lanes.executeMaintenance {
                maintenanceStarted.countDown()
                releaseMaintenance.await(5, TimeUnit.SECONDS)
            }
            assertTrue(maintenanceStarted.await(2, TimeUnit.SECONDS))

            lanes.executeInteractive {
                interactiveFinished.countDown()
            }

            assertTrue(
                "interactive work was incorrectly queued behind maintenance",
                interactiveFinished.await(2, TimeUnit.SECONDS)
            )
        } finally {
            releaseMaintenance.countDown()
            lanes.close()
        }
    }
}
