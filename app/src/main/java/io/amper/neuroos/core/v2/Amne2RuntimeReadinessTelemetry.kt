package io.amper.neuroos.core.v2

import io.amper.neuroos.core.AmneKernelPrimitive

enum class Amne2RuntimeReadinessState {
    READY,
    DEGRADED
}

enum class Amne2HotSessionTelemetryState {
    NONE,
    ACTIVE_MATCHING_MODEL,
    ACTIVE_OTHER_MODEL
}

data class Amne2HotSessionTelemetry(
    val state: Amne2HotSessionTelemetryState,
    val kvPosition: Int,
    val committedTokens: Int,
    val maxContextTokens: Int?
) {
    init {
        require(kvPosition >= 0)
        require(committedTokens >= 0)
        if (state == Amne2HotSessionTelemetryState.NONE) {
            require(kvPosition == 0)
            require(committedTokens == 0)
            require(maxContextTokens == null)
        } else {
            require(maxContextTokens != null && maxContextTokens > 0)
            require(kvPosition == committedTokens) {
                "AMNE2 hot-session telemetry requires committed token/KV parity"
            }
            require(kvPosition <= maxContextTokens) {
                "AMNE2 hot-session telemetry exceeds session context"
            }
        }
    }

    companion object {
        fun none(): Amne2HotSessionTelemetry =
            Amne2HotSessionTelemetry(
                state = Amne2HotSessionTelemetryState.NONE,
                kvPosition = 0,
                committedTokens = 0,
                maxContextTokens = null
            )
    }
}

data class Amne2RuntimeDegradedReason(
    val code: String,
    val detail: String
) {
    init {
        require(code.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}"))) {
            "AMNE2 degraded reason code must be stable lowercase text"
        }
        require(detail.isNotBlank())
    }
}

data class Amne2RuntimeReadinessSnapshot(
    val state: Amne2RuntimeReadinessState,
    val foundationId: String,
    val semanticSha256: String,
    val artifactSha256: String,
    val sourceSha256: String,
    val safeContextTokens: Int,
    val modelMaxContextTokens: Int,
    val mmapWindowBytes: Int,
    val memoryClassMb: Int,
    val reservedHeadroomMb: Int,
    val sessionBudgetMb: Int,
    val requiredMatrixPrimitives: Set<AmneKernelPrimitive>,
    val referenceOnlyMatrixPrimitives: Set<AmneKernelPrimitive>,
    val backendByPrimitive: Map<AmneKernelPrimitive, String>,
    val hotSession: Amne2HotSessionTelemetry,
    val degradedReasons: List<Amne2RuntimeDegradedReason>
) {
    init {
        require(foundationId.isNotBlank())
        require(semanticSha256.matches(Regex("[0-9a-f]{64}")))
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(safeContextTokens in 2..modelMaxContextTokens)
        require(mmapWindowBytes > 0)
        require(memoryClassMb > 0)
        require(reservedHeadroomMb > 0)
        require(sessionBudgetMb > 0)
        require(reservedHeadroomMb + sessionBudgetMb <= memoryClassMb)
        require(requiredMatrixPrimitives.isNotEmpty())
        require(referenceOnlyMatrixPrimitives.all(requiredMatrixPrimitives::contains))
        require(backendByPrimitive.keys == requiredMatrixPrimitives)
        require(
            state == if (degradedReasons.isEmpty()) {
                Amne2RuntimeReadinessState.READY
            } else {
                Amne2RuntimeReadinessState.DEGRADED
            }
        )
        require(
            degradedReasons.map(Amne2RuntimeDegradedReason::code).distinct().size ==
                degradedReasons.size
        )
    }

    val hardwareAccelerationReady: Boolean
        get() = referenceOnlyMatrixPrimitives.isEmpty()
}

/**
 * Consolidates the M3 execution facts into one model-specific, side-effect-free readiness surface.
 *
 * This is observability only. It does not perform dispatch, alter KV, retune hardware, or select a
 * different model/runtime.
 */
object Amne2RuntimeReadinessTelemetry {
    fun snapshot(
        foundationId: String,
        semanticSha256: String,
        artifactSha256: String,
        sourceSha256: String,
        memoryBudget: Amne2MemoryBudget,
        requiredMatrixPrimitives: Set<AmneKernelPrimitive>,
        referenceOnlyMatrixPrimitives: Set<AmneKernelPrimitive>,
        backendByPrimitive: Map<AmneKernelPrimitive, String>,
        hotSession: Amne2HotSessionTelemetry
    ): Amne2RuntimeReadinessSnapshot {
        val degraded = referenceOnlyMatrixPrimitives
            .sortedBy { it.ordinal }
            .map { primitive ->
                Amne2RuntimeDegradedReason(
                    code = "reference-only-" + primitive.name.lowercase().replace('_', '-'),
                    detail = "Required matrix primitive $primitive is using the deterministic reference backend"
                )
            }

        return Amne2RuntimeReadinessSnapshot(
            state = if (degraded.isEmpty()) {
                Amne2RuntimeReadinessState.READY
            } else {
                Amne2RuntimeReadinessState.DEGRADED
            },
            foundationId = foundationId,
            semanticSha256 = semanticSha256,
            artifactSha256 = artifactSha256,
            sourceSha256 = sourceSha256,
            safeContextTokens = memoryBudget.safeContextTokens,
            modelMaxContextTokens = memoryBudget.modelMaxContextTokens,
            mmapWindowBytes = memoryBudget.mmapWindowBytes,
            memoryClassMb = memoryBudget.memoryClassMb,
            reservedHeadroomMb = memoryBudget.reservedHeadroomMb,
            sessionBudgetMb = memoryBudget.sessionBudgetMb,
            requiredMatrixPrimitives = requiredMatrixPrimitives.toSet(),
            referenceOnlyMatrixPrimitives = referenceOnlyMatrixPrimitives.toSet(),
            backendByPrimitive = backendByPrimitive.toMap(),
            hotSession = hotSession,
            degradedReasons = degraded
        )
    }
}
