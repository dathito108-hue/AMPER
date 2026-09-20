# Phase627 — M2 Streaming AMI1 to AMI2 Migration Emitter

Phase627 makes the Phase626 AMI2 binary format usable for real mobile-scale imports without
materializing multi-gigabyte canonical sections in Java heap memory.

## One writer, two payload modes

Phase627 does not add a second AMI2 writer.

`Ami2CanonicalBinaryWriter` now accepts canonical payload sources:

- `Ami2ByteArrayPayloadSource` for small/generated control payloads;
- `Ami2FileRangePayloadSource` for verified ranges in an existing file.

The original ByteArray API remains as a compatibility adapter over the same writer.

Large ranges are copied with one shared 64 KiB buffer. The writer hashes each range while copying
and compares the observed digest with the digest fixed in the AMI2 compilation plan. If the source
changes between planning and copy, publication fails closed.

## Verified AMI1 migration

`Ami1ToAmi2StreamingMigrationEmitter` performs the migration:

1. Re-read AMI1 with `AmiBinaryReader` and full section digest verification.
2. Require the existing verified GGUF-origin AMI1 lineage supported by the current v1 compiler.
3. Read preserved GGUF scalar metadata only to recover the chat template.
4. Build the deterministic AMI2 compilation plan.
5. Reference AMI1 TOKENIZER, GRAPH_IR, TENSOR_INDEX, and FOUNDATION_WEIGHTS as file ranges.
6. Generate only the small AMI2 CHAT_PROTOCOL/control payloads in memory.
7. Stream the canonical ranges into the Phase626 writer.
8. Re-read and verify the completed AMI2 staging file before atomic publish.

AMI1 remains migration-only. It is not reintroduced as a production runtime.

## Mobile memory property

The migration path no longer allocates ByteArrays proportional to model weights.

Peak copy-buffer allocation for the large canonical sections is bounded by:

`Ami2BinaryLayout.STREAM_COPY_BUFFER_BYTES = 64 KiB`

Control payloads remain small and bounded by the AMI2 reader/writer contracts.

## Integrity properties

Phase627 preserves all Phase626 checks:

- AMI2 semantic identity;
- canonical foundation id;
- tokenizer/chat/graph/tensor-index/weights digests;
- section order and alignment;
- per-section SHA-256;
- integrity section bindings;
- post-write round-trip verification.

It additionally protects the migration source against time-of-check/time-of-copy modification by
hashing every streamed range while it is copied.

## Phase627 exit criteria

Phase627 is complete when:

- a verified AMI1 produced by the existing GGUF compiler migrates to readable AMI2;
- canonical v1 section digests are preserved in the AMI2 foundation identity;
- corrupt AMI1 is rejected before emission;
- changed file ranges are rejected during streaming copy;
- no native/runtime/backend path is added.

## Next M2 slice

Phase628 should remove the temporary AMI1 staging dependency for new imports by introducing a direct
GGUF -> AMI2 streaming compiler path that reuses the already-qualified GGUF scan semantics instead
of creating a parallel parser. AMI1 migration remains available only for existing installed models.
