package io.amper.neuroos.core

import kotlin.math.max

data class ReflexLearningCostSnapshot(
    val samples: Int = 0,
    val durationEwmaMs: Double? = null,
    val examplesPerSecondEwma: Double? = null,
    val successRateEwma: Double? = null,
    val learningValuePerSecondEwma: Double? = null,
    val lastDurationMs: Long? = null,
    val lastExamples: Int? = null,
    val updatedAtEpochMs: Long? = null
) {
    init {
        require(samples >= 0)
        require(durationEwmaMs == null || (durationEwmaMs.isFinite() && durationEwmaMs >= 0.0))
        require(
            examplesPerSecondEwma == null ||
                (examplesPerSecondEwma.isFinite() && examplesPerSecondEwma > 0.0)
        )
        require(successRateEwma == null || successRateEwma in 0.0..1.0)
        require(
            learningValuePerSecondEwma == null ||
                (learningValuePerSecondEwma.isFinite() && learningValuePerSecondEwma >= 0.0)
        )
        require(lastDurationMs == null || lastDurationMs >= 0L)
        require(lastExamples == null || lastExamples > 0)
        require(updatedAtEpochMs == null || updatedAtEpochMs >= 0L)
        if (samples == 0) {
            require(durationEwmaMs == null)
            require(examplesPerSecondEwma == null)
            require(successRateEwma == null)
            require(learningValuePerSecondEwma == null)
            require(lastDurationMs == null)
            require(lastExamples == null)
            require(updatedAtEpochMs == null)
        }
    }

    fun estimateDurationMs(exampleCount: Int): Long? {
        require(exampleCount > 0)
        val throughput = examplesPerSecondEwma
        if (throughput != null && throughput > 0.0) {
            return ((exampleCount.toDouble() / throughput) * 1_000.0)
                .toLong()
                .coerceAtLeast(1L)
        }
        val duration = durationEwmaMs
        val examples = lastExamples
        if (duration != null && examples != null) {
            return (duration * exampleCount.toDouble() / examples.toDouble())
                .toLong()
                .coerceAtLeast(1L)
        }
        return null
    }

    val authorityBearing: Boolean
        get() = false
}

data class ReflexLearningCostObservation(
    val durationMs: Long,
    val exampleCount: Int,
    val learningValue: Double,
    val succeeded: Boolean,
    val observedAtEpochMs: Long
) {
    init {
        require(durationMs >= 0L)
        require(exampleCount > 0)
        require(learningValue in 0.0..1.0)
        require(observedAtEpochMs >= 0L)
    }

    val examplesPerSecond: Double
        get() = exampleCount.toDouble() / max(durationMs, 1L).toDouble() * 1_000.0

    val learningValuePerSecond: Double
        get() = learningValue / (max(durationMs, 1L).toDouble() / 1_000.0)

    val authorityBearing: Boolean
        get() = false
}

interface ReflexLearningCostModel {
    fun snapshot(): ReflexLearningCostSnapshot
    fun observe(observation: ReflexLearningCostObservation): ReflexLearningCostSnapshot
}

class MemoryBackedReflexLearningCostModel(
    private val memory: MemoryOs
) : ReflexLearningCostModel {
    @Synchronized
    override fun snapshot(): ReflexLearningCostSnapshot =
        memory.get(SNAPSHOT_ID)
            ?.takeIf { it.kind == SNAPSHOT_KIND }
            ?.let { decode(it.content) }
            ?: ReflexLearningCostSnapshot()

    @Synchronized
    override fun observe(
        observation: ReflexLearningCostObservation
    ): ReflexLearningCostSnapshot {
        val previous = snapshot()
        val alpha = if (previous.samples == 0) 1.0 else EWMA_ALPHA

        fun ewma(previousValue: Double?, next: Double): Double =
            previousValue?.let { old ->
                alpha * next + (1.0 - alpha) * old
            } ?: next

        val next = ReflexLearningCostSnapshot(
            samples = previous.samples + 1,
            durationEwmaMs = ewma(
                previous.durationEwmaMs,
                observation.durationMs.toDouble()
            ),
            examplesPerSecondEwma = ewma(
                previous.examplesPerSecondEwma,
                observation.examplesPerSecond
            ),
            successRateEwma = ewma(
                previous.successRateEwma,
                if (observation.succeeded) 1.0 else 0.0
            ).coerceIn(0.0, 1.0),
            learningValuePerSecondEwma = ewma(
                previous.learningValuePerSecondEwma,
                observation.learningValuePerSecond
            ).coerceAtLeast(0.0),
            lastDurationMs = observation.durationMs,
            lastExamples = observation.exampleCount,
            updatedAtEpochMs = observation.observedAtEpochMs
        )
        memory.remember(
            MemoryRecord(
                id = SNAPSHOT_ID,
                kind = SNAPSHOT_KIND,
                content = encode(next),
                importance = 0.86,
                provenance = Provenance(
                    source = "amper-reflex-learning-cost",
                    producer = "reflex-learning-cost-model",
                    confidence = 1.0
                ),
                createdAtEpochMs = observation.observedAtEpochMs
            )
        )
        return next
    }

    private fun encode(snapshot: ReflexLearningCostSnapshot): String =
        listOf(
            VERSION,
            snapshot.samples.toString(),
            requireNotNull(snapshot.durationEwmaMs).toString(),
            requireNotNull(snapshot.examplesPerSecondEwma).toString(),
            requireNotNull(snapshot.successRateEwma).toString(),
            requireNotNull(snapshot.learningValuePerSecondEwma).toString(),
            requireNotNull(snapshot.lastDurationMs).toString(),
            requireNotNull(snapshot.lastExamples).toString(),
            requireNotNull(snapshot.updatedAtEpochMs).toString()
        ).joinToString("|")

    private fun decode(content: String): ReflexLearningCostSnapshot {
        val parts = content.split('|')
        require(parts.size == 9 && parts[0] == VERSION) {
            "unsupported Reflex learning-cost snapshot"
        }
        return ReflexLearningCostSnapshot(
            samples = parts[1].toInt(),
            durationEwmaMs = parts[2].toDouble(),
            examplesPerSecondEwma = parts[3].toDouble(),
            successRateEwma = parts[4].toDouble(),
            learningValuePerSecondEwma = parts[5].toDouble(),
            lastDurationMs = parts[6].toLong(),
            lastExamples = parts[7].toInt(),
            updatedAtEpochMs = parts[8].toLong()
        )
    }

    companion object {
        private val SNAPSHOT_ID = MemoryId("reflex-learning-cost:snapshot:v1")
        private const val SNAPSHOT_KIND = "reflex-learning-cost-snapshot-v1"
        private const val VERSION = "RLC1"
        private const val EWMA_ALPHA = 0.25
    }
}
