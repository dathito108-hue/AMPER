# Phase626 — M2 AMI2 Canonical Binary Writer/Reader

Phase626 materializes the deterministic AMI2 foundation identity from Phase625 into a real bounded
binary container. It remains format/compiler infrastructure only; no second inference runtime or
backend is introduced.

## Canonical binary layout

AMI2 uses:

- a fixed 64-byte header;
- an 8 KiB reserved header/table region;
- fixed 64-byte section descriptors;
- bounded section count;
- SHA-256 digest per section;
- 64-byte control-section alignment;
- 4 KiB foundation-weight alignment.

The canonical foundation file contains exactly one copy of each mandatory AMI2 artifact:
MANIFEST, SOURCE_LINEAGE, TOKENIZER, CHAT_PROTOCOL, LOGICAL_GRAPH, TENSOR_INDEX,
FOUNDATION_WEIGHTS, and INTEGRITY.

DEVICE_PACK and ADAPTATION_DELTA remain outside this initial canonical foundation writer.

## Semantic binding

Before publishing a file, the writer verifies that TOKENIZER, CHAT_PROTOCOL, LOGICAL_GRAPH,
TENSOR_INDEX, and FOUNDATION_WEIGHTS match the digests stored in the AMI2 foundation identity.

Phase626 strengthens the Phase625 identity by adding TENSOR_INDEX to the canonical semantic digest.
Tensor names/offset mappings affect how foundation weights are interpreted, so they must not be able
to change while retaining the same semantic identity.

The canonical foundation id is also derived from semanticSha256:
amper-<first 32 hex characters of semanticSha256>.

## Integrity and fail-closed read

The writer generates MANIFEST, SOURCE_LINEAGE, and INTEGRITY payloads deterministically, writes the
container to a staging file, then immediately reads it back with the AMI2 verifier before atomic
publication.

The reader:

- checks AMI2 magic/version and declared file length;
- rejects invalid/duplicate/missing canonical sections;
- validates bounds, alignment, overlap, and section SHA-256;
- validates manifest section bindings;
- reconstructs semanticSha256 independently;
- checks the header, manifest, and INTEGRITY semantic bindings;
- checks canonical foundation id derivation;
- rejects tampered sections or semantic headers.

## Migration boundary

The legacy AMI1 whole-file hash remains migration evidence only. It is not part of foundation
semantics. GGUF remains an import source only, and AMI1 remains a temporary verified migration source.

## Next M2 slice

Phase627 can add the streaming AMI1 -> AMI2 migration emitter so large tokenizer/index/weight
sections are copied by bounded ranges instead of materialized as ByteArray payloads. That will make
the canonical AMI2 writer usable for real multi-gigabyte mobile foundation imports while preserving
the same semantic and integrity contracts.
