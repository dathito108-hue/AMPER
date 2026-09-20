# Phase624 — M1 Architecture Consolidation / AMI2 Foundation Contract

Phase624 closes the remaining M1 architecture gap after the Phase623 OMEGA lock. It does not add a
second model, backend, compiler, decoder or execution engine.

## Duplicate-architecture audit

The pre-change tree already had one production AMPER Core boundary and the AMI/AMNE v1 runtime
implementation. `core/v2/OmegaCoreV2.kt` contained the OMEGA roadmap and policies, but there was no
AMI2 semantic foundation contract yet. The existing `AmperMobileIntelligenceFormat`,
`GgufToAmiCompiler`, decoder and AMNE classes are therefore treated as legacy implementation to be
migrated, not copied into a parallel v2 backend.

Phase624 keeps those implementation classes untouched and introduces only the contract needed to
make the migration unambiguous.

## AMI2 foundation contract

`Ami2FoundationContract` locks:

- file identity `AMI2`, major version 2;
- exactly one logical foundation slot;
- mandatory lineage, tokenizer, chat protocol, logical graph, tensor index, canonical foundation
  weights and integrity artifacts;
- a stable foundation semantic identity that binds graph/protocol/weights semantics;
- device packs as optional acceleration artifacts bound to the same foundation identity;
- device packs may change physical representation but cannot become independent foundations.

## Import and migration boundary

`Ami2MigrationContract` locks the target state:

- GGUF is accepted only as a weight import source;
- legacy AMI1 is accepted only as a migration source;
- neither GGUF nor AMI1 is a selectable production runtime/backend in the OMEGA target;
- production format is AMI2;
- production execution engine is AMNE2;
- multiple-foundation routing is forbidden.

This is a target contract. Phase624 intentionally does not remove the existing AMI/AMNE v1 code,
because M2 and M3 still need it as the verified source implementation while the compiler/runtime are
migrated.

## M1 closure

The architecture lock now explicitly includes:

- `gguf-is-import-source-only`;
- an M1 exit criterion requiring an explicit AMI/AMNE v1 -> AMI2/AMNE2 migration path.

M2 can now implement the AMI2 compiler against one stable contract instead of adding another runtime
adapter.
