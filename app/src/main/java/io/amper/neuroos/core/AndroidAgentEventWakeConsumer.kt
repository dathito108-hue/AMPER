package io.amper.neuroos.core

import android.content.Context
import io.amper.neuroos.core.v2.AmperAgentEventWakeHandoff
import io.amper.neuroos.core.v2.AmperAgentEventWakeHostDispatcher
import io.amper.neuroos.core.v2.AmperAgentEventWakeHostExecutionResult

/**
 * Android-facing Phase656 proactive wake consumption boundary.
 *
 * It schedules nothing and owns no monitor/receiver/job. The pure dispatcher verifies the handoff
 * before this fallback can acquire the process-shared canonical sovereign runtime graph.
 */
class AndroidAgentEventWakeConsumer(context: Context) {
    private val appContext = context.applicationContext

    fun consume(
        handoff: AmperAgentEventWakeHandoff
    ): Result<AmperAgentEventWakeHostExecutionResult> =
        AmperAgentEventWakeHostDispatcher.dispatch(
            handoff = handoff,
            execution = null,
            executionFallback = {
                runCatching {
                    AndroidCanonicalSovereignRuntimeBootstrap
                        .acquire(appContext)
                        .agent
                        .eventWakeExecution
                }
            }
        )
}
