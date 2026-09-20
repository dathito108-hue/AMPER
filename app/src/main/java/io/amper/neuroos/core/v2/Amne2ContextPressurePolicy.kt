package io.amper.neuroos.core.v2

enum class Amne2ContextPressureAction {
    REUSE_HOT_SESSION,
    REBUILD_FULL_PROMPT,
    REQUIRE_COMPACTION
}

data class Amne2ContextPressureDecision(
    val action: Amne2ContextPressureAction,
    val requiredContextTokens: Int,
    val safeContextTokens: Int,
    val promptSuffix: IntArray = intArrayOf()
) {
    init {
        require(requiredContextTokens > 0)
        require(safeContextTokens > 0)
        if (action == Amne2ContextPressureAction.REUSE_HOT_SESSION) {
            require(promptSuffix.isNotEmpty()) {
                "AMNE2 hot-session reuse requires a non-empty prompt suffix"
            }
        } else {
            require(promptSuffix.isEmpty()) {
                "only AMNE2 hot-session reuse may carry a prompt suffix"
            }
        }
        if (action == Amne2ContextPressureAction.REQUIRE_COMPACTION) {
            require(requiredContextTokens > safeContextTokens) {
                "AMNE2 compaction is required only when the full request exceeds safe context"
            }
        } else {
            require(requiredContextTokens <= safeContextTokens) {
                "AMNE2 executable context decision exceeds safe context"
            }
        }
    }
}

/**
 * Pure context-pressure policy for the single AMNE2 conversation hot state.
 *
 * No token is silently evicted. If the exact cached prefix cannot be reused but the complete prompt
 * still fits the hardware-admitted context, the session is rebuilt from the full prompt. If the
 * complete prompt plus output budget exceeds the safe context, execution must stop and hand control
 * to deterministic conversation compaction/checkpoint logic upstream.
 */
object Amne2ContextPressurePolicy {
    fun decide(
        cachedIdentity: Amne2ConversationHotIdentity?,
        requestedIdentity: Amne2ConversationHotIdentity,
        committedTokenIds: IntArray,
        fullPromptTokenIds: IntArray,
        requestedOutputTokens: Int,
        currentSessionMaxContextTokens: Int?,
        safeContextTokens: Int
    ): Amne2ContextPressureDecision {
        require(fullPromptTokenIds.isNotEmpty())
        require(requestedOutputTokens > 0)
        require(safeContextTokens > 0)
        currentSessionMaxContextTokens?.let { require(it > 0) }

        val required = Math.addExact(
            fullPromptTokenIds.size,
            requestedOutputTokens
        )
        if (required > safeContextTokens) {
            return Amne2ContextPressureDecision(
                action = Amne2ContextPressureAction.REQUIRE_COMPACTION,
                requiredContextTokens = required,
                safeContextTokens = safeContextTokens
            )
        }

        if (
            cachedIdentity != null &&
            currentSessionMaxContextTokens != null &&
            required <= currentSessionMaxContextTokens
        ) {
            val suffix = Amne2ConversationHotReusePolicy.reusablePromptSuffix(
                cachedIdentity = cachedIdentity,
                requestedIdentity = requestedIdentity,
                committedTokenIds = committedTokenIds,
                fullPromptTokenIds = fullPromptTokenIds,
                requestedOutputTokens = requestedOutputTokens,
                maxContextTokens = currentSessionMaxContextTokens
            )
            if (suffix != null) {
                return Amne2ContextPressureDecision(
                    action = Amne2ContextPressureAction.REUSE_HOT_SESSION,
                    requiredContextTokens = required,
                    safeContextTokens = safeContextTokens,
                    promptSuffix = suffix
                )
            }
        }

        return Amne2ContextPressureDecision(
            action = Amne2ContextPressureAction.REBUILD_FULL_PROMPT,
            requiredContextTokens = required,
            safeContextTokens = safeContextTokens
        )
    }

    fun rejectionReason(
        requiredContextTokens: Int,
        safeContextTokens: Int
    ): String {
        require(requiredContextTokens > safeContextTokens)
        return "amper-core-context-compaction-required:" +
            "required=$requiredContextTokens,safe=$safeContextTokens"
    }
}
