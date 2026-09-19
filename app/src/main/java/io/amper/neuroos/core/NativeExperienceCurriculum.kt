package io.amper.neuroos.core

data class NativeExperienceTrainingBundle(
    val shard: NativeExperienceDatasetShard,
    val curriculum: NativeCurriculumManifest,
    val datasetSnapshotDigest: String,
    val capabilityExampleCounts: Map<CapabilityId, Int>
) {
    init {
        require(capabilityExampleCounts.isNotEmpty())
        require(capabilityExampleCounts.values.all { it > 0 })
        require(datasetSnapshotDigest.matches(Regex("[0-9a-f]{64}")))
        require(curriculum.stages.all { stage ->
            stage.capabilities.all { capability ->
                capabilityExampleCounts.getOrDefault(capability, 0) >= stage.minExamples
            }
        })
    }

    val authorityBearing: Boolean
        get() = false
}

interface NativeExperienceCurriculumPlanner {
    fun synthesize(
        shardId: NativeDatasetShardId,
        curriculumId: NativeCurriculumId,
        minExamplesPerCapability: Int = 2,
        limit: Int = 256
    ): NativeExperienceTrainingBundle
}

/**
 * Phase401-405 evidence-derived native curriculum synthesis.
 *
 * The planner does not invent examples and does not call a trainer. It materializes one immutable
 * verified-experience shard, derives capability stages only from examples in that exact shard, and
 * registers the resulting curriculum in the existing NativeModelFoundation.
 */
class EvidenceNativeExperienceCurriculumPlanner(
    private val store: NativeExperienceDatasetStore,
    private val foundation: NativeModelFoundation
) : NativeExperienceCurriculumPlanner {
    override fun synthesize(
        shardId: NativeDatasetShardId,
        curriculumId: NativeCurriculumId,
        minExamplesPerCapability: Int,
        limit: Int
    ): NativeExperienceTrainingBundle {
        require(minExamplesPerCapability > 0)
        require(limit in minExamplesPerCapability..MemoryBackedNativeExperienceDatasetStore.MAX_SHARD_EXAMPLES)
        require(foundation.getCurriculum(curriculumId) == null) {
            "native curriculum id already exists"
        }

        val shard = store.materializeShard(
            id = shardId,
            minExamples = minExamplesPerCapability,
            limit = limit
        )
        val examples = shard.exampleIds.map { id ->
            requireNotNull(store.getExample(id)) {
                "native experience shard references missing example"
            }
        }

        val counts = linkedMapOf<CapabilityId, Int>()
        val positionTotals = linkedMapOf<CapabilityId, Int>()
        examples.forEach { example ->
            example.strategy.capabilities.forEachIndexed { index, capability ->
                counts[capability] = counts.getOrDefault(capability, 0) + 1
                positionTotals[capability] = positionTotals.getOrDefault(capability, 0) + index
            }
        }

        val eligible = counts
            .filterValues { it >= minExamplesPerCapability }
            .keys
            .sortedWith(
                compareBy<CapabilityId> { capability ->
                    positionTotals.getValue(capability).toDouble() /
                        counts.getValue(capability).toDouble()
                }.thenByDescending { capability -> counts.getValue(capability) }
                    .thenBy { it.value }
            )
        require(eligible.isNotEmpty()) {
            "verified native experience has no capability with sufficient evidence"
        }
        require(eligible.size <= NativeCurriculumManifest.MAX_STAGES) {
            "verified native experience exceeds bounded curriculum stages"
        }

        val maxCount = eligible.maxOf { counts.getValue(it) }.coerceAtLeast(1)
        val curriculum = NativeCurriculumManifest(
            id = curriculumId,
            stages = eligible.mapIndexed { index, capability ->
                NativeCurriculumStage(
                    index = index + 1,
                    name = "verified-" + capability.value.take(96),
                    capabilities = setOf(capability),
                    minExamples = minExamplesPerCapability.toLong(),
                    weight = (
                        counts.getValue(capability).toDouble() / maxCount.toDouble()
                        ).coerceIn(0.01, 1.0)
                )
            }
        )
        foundation.putCurriculum(curriculum)

        return NativeExperienceTrainingBundle(
            shard = shard,
            curriculum = curriculum,
            datasetSnapshotDigest = foundation.datasetSnapshotDigest(listOf(shard.manifest.id)),
            capabilityExampleCounts = counts.toMap()
        )
    }
}
