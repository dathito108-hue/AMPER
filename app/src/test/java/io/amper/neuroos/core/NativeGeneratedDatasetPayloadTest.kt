package io.amper.neuroos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeGeneratedDatasetPayloadTest {
    @Test
    fun compositeResolverPreservesDatasetKindWithoutDuplicatingTrainingFields() {
        val manifest = NativeDatasetShardManifest(
            id = NativeDatasetShardId("generated-payload-test"),
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sourceLabel = "test",
            rights = NativeDatasetRights.GENERATED_INTERNAL,
            exampleCount = 1,
            byteCount = 3,
            targetCapabilities = setOf(TitanCapabilities.REFLEX_DECISION),
            createdAtEpochMs = 1L
        )
        val payload = NativeGeneratedDatasetPayload(
            kind = NativeGeneratedDatasetKind.REFLEX_DECISION_EXPERIENCE,
            manifest = manifest,
            payload = "abc"
        )
        val resolver = CompositeNativeGeneratedDatasetResolver(
            listOf(
                NativeGeneratedDatasetResolver { id ->
                    payload.takeIf { id == manifest.id }
                },
                NativeGeneratedDatasetResolver { null }
            )
        )

        val resolved = resolver.resolve(manifest.id)
        assertEquals(payload, resolved)
        assertEquals(
            NativeGeneratedDatasetKind.REFLEX_DECISION_EXPERIENCE,
            resolved?.kind
        )
        assertNull(resolver.resolve(NativeDatasetShardId("missing")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generatedPayloadRejectsManifestDigestMismatch() {
        NativeGeneratedDatasetPayload(
            kind = NativeGeneratedDatasetKind.VERIFIED_GOAL_EXPERIENCE,
            manifest = NativeDatasetShardManifest(
                id = NativeDatasetShardId("bad-generated-payload"),
                sha256 = "0".repeat(64),
                sourceLabel = "test",
                rights = NativeDatasetRights.GENERATED_INTERNAL,
                exampleCount = 1,
                byteCount = 3,
                targetCapabilities = setOf(TitanCapabilities.REASONING),
                createdAtEpochMs = 1L
            ),
            payload = "abc"
        )
    }
}
