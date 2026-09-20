# Phase629 — M2 Production AMI2 Import Cutover

Phase629 closes the AMI2 compiler milestone by wiring canonical AMI2 publication into the real
model-import flow without prematurely replacing the still-v1 inference runtime.

## Production import order

New GGUF imports now follow:

1. inspect and install the user-owned GGUF source;
2. compile and verify the canonical app-private AMI2 artifact;
3. only then materialize AMI1 when the current AMNE1 compatibility runtime still needs it;
4. activate the single AMPER foundation source.

This makes AMI2 the canonical compiled artifact for new imports while preserving current chat
functionality until M3 AMNE2 can execute AMI2 directly.

## App-private AMI2 store

`AndroidAmi2CompilationService` stores canonical AMI2 under the sovereign app-private directory,
content-addressed by the installed source SHA-256.

`compileDirect(model)`:

- resolves the installed GGUF source;
- reuses a valid direct AMI2 cache when lineage matches;
- otherwise compiles GGUF -> AMI2 directly with the Phase628 streaming compiler;
- verifies every AMI2 section and semantic identity before admission;
- requires source SHA-256/length to match the installed model record;
- rejects synthetic AMI1 migration evidence;
- publishes the cache read-only.

`migrateLegacy(model, legacyAmi1)` is a separate explicit compatibility path for existing AMI1
artifacts and requires real migration evidence.

## Runtime compatibility boundary

Phase629 does not claim that AMNE2 already exists.

Current inference still consumes AMI1/AMNE1, so the UI import/activation path keeps a temporary
compatibility materialization after AMI2 publication. This bridge is not the canonical model format
and is scheduled for removal when M3 AMNE2 admission can execute AMI2 directly.

The manual compile control now targets AMI2 rather than AMI1.

## M2 exit criteria

M2 is considered architecturally complete when:

- GGUF/source weights compile directly into AMI2;
- tokenizer/chat protocol, graph, tensor index, weights, lineage, and integrity are deterministic;
- large payloads stream with bounded memory;
- canonical AMI2 is stored and verified in the production import flow;
- AMI1 remains only migration/runtime compatibility, not the canonical output.

## Next milestone

Phase630 begins M3 — AMNE2 Runtime Foundation. The first slice should define one AMNE2 execution
admission boundary for verified AMI2 artifacts, reusing the existing AMNE v1 kernels where valid
instead of forking a second decoder/kernel stack.
