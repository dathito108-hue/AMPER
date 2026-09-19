package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedGeneratedNativeDatasetTest {
    @Test
    fun goalAndReflexShardsShareOneCanonicalTrainerContract() {
        val goalManifest = NativeDatasetShardManifest(
            id = NativeDatasetShardId("unified-goal"),
            sha256 = "a".repeat(64),
            sourceLabel = "goal generated",
            rights = NativeDatasetRights.GENERATED_INTERNAL,
            exampleCount = 1,
            byteCount = 4,
            targetCapabilities = setOf(TitanCapabilities.REASONING),
            createdAtEpochMs = 1L
        )
        val reflexPayload = "demo"
        val reflexManifest = NativeDatasetShardManifest(
            id = NativeDatasetShardId("unified-reflex"),
            sha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(reflexPayload.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            sourceLabel = "reflex generated",
            rights = NativeDatasetRights.GENERATED_INTERNAL,
            exampleCount = 1,
            byteCount = reflexPayload.toByteArray().size.toLong(),
            targetCapabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            createdAtEpochMs = 1L
        )

        val reflexShard: GeneratedNativeDatasetShard = ReflexExperienceDatasetShard(
            manifest = reflexManifest,
            exampleIds = listOf(
                ReflexExperienceExampleId("action:" + "b".repeat(64))
            ),
            payload = reflexPayload
        )

        assertEquals(reflexManifest, reflexShard.manifest)
        assertEquals(reflexPayload, reflexShard.payload)
        assertTrue(reflexShard.authorityBearing.not())

        val store = GeneratedNativeDatasetShardStore { id ->
            if (id == reflexManifest.id) reflexShard else null
        }
        assertEquals(reflexShard, store.getGeneratedShard(reflexManifest.id))
        assertEquals(null, store.getGeneratedShard(goalManifest.id))
    }
}
