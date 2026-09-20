# Phase628 — M2 Direct GGUF to AMI2 Streaming Compiler

Phase628 removes the temporary AMI1 staging dependency for new GGUF imports while preserving AMI1
only as a migration path for already-installed legacy artifacts.

## One GGUF compile-semantic source

The previous GGUF compile scanner lived privately inside `GgufToAmiCompiler`. Phase628 extracts it
into `GgufCompileSemantics`.

Both paths now use the same scanner and the same graph/tensor-index encoders:

- legacy GGUF -> AMI1 migration tooling;
- direct GGUF -> AMI2 compilation.

The shared scanner also captures the optional GGUF chat template while preserving the original
metadata byte range unchanged as the tokenizer payload.

## Direct streaming compiler

`GgufToAmi2StreamingCompiler`:

1. performs full GGUF admission/identity inspection;
2. scans compiler semantics with the shared scanner;
3. hashes the canonical metadata and foundation-weight ranges with bounded memory;
4. re-inspects the source and requires the same whole-file SHA-256/header/length;
5. creates the AMI2 semantic plan directly from GGUF provenance;
6. streams metadata and foundation weights from `ModelArtifactSource` with the Phase627 64 KiB
   writer buffer;
7. emits AMI2 directly and runs the Phase626 post-write verifier before publication.

No AMI1 file is created in this path.

## Provenance separation

Direct GGUF compilation does not synthesize fake AMI1 migration evidence.

`Ami2CompilationPlan.migrationEvidence` is now optional:

- direct GGUF -> AMI2: null migration evidence; source lineage is GGUF_WEIGHTS;
- existing AMI1 -> AMI2 migration: real legacy AMI1 SHA-256 is retained.

The AMI2 manifest emits `legacy_ami1_sha256` only when an actual legacy artifact existed.

## Semantic equivalence gate

The Phase628 regression gate compiles the same GGUF through:

- direct GGUF -> AMI2;
- legacy GGUF -> AMI1 -> AMI2.

Both outputs must produce exactly the same `Ami2FoundationIdentity`. This proves that removing AMI1
staging does not change tokenizer, chat protocol, graph, tensor index, foundation weights, lineage,
or semantic identity.

## Architecture result

New imports can now follow:

`GGUF source -> shared compile semantics -> AMI2 canonical writer`

Existing installed legacy models retain:

`AMI1 -> verified migration emitter -> AMI2`

Neither path is a selectable inference backend.

## Next M2 slice

Phase629 should wire the production model-import/install boundary to prefer direct GGUF -> AMI2 for
new imports while keeping the AMI1 migrator only for legacy installed artifacts. After that routing
is canonicalized, M2 can be closed and M3 AMNE2 execution work can begin.
