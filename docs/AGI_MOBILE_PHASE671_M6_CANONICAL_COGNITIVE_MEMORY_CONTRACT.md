# Phase671 — M6 Canonical Cognitive Memory Contract

Phase671 begins M6 by locking one cognitive-memory topology before any new retrieval,
consolidation, forgetting, or procedural-memory behavior is added.

## One durable Memory OS

Production AMPER already constructs durable state from one encrypted `MemoryOs` through
`AmperRuntime.persistentEncrypted`.

`CanonicalCognitiveMemoryTopology` now makes that boundary explicit. It is bound inside
`AmperRuntime.build(memory, workspace)` to the exact same `MemoryOs` and existing
`GlobalWorkspace`.

The topology is metadata/identity only. It exposes no second remember/recall/write API, journal,
database, vector store, cache, model store, or backend store.

## Four canonical domains

- **Working** — transient cognition remains in `GlobalWorkspace`; durable continuity such as
  `NativeSystem2WorkingStateStore` remains in the same `MemoryOs`.
- **Episodic** — durable observations and conversation lineage remain MemoryOs-backed.
- **Semantic** — provenance-backed semantic knowledge and epistemic evidence remain MemoryOs-backed.
- **Procedural** — skills, generalization and verified repair-strategy memory remain MemoryOs-backed.

All four domains are non-authority-bearing.

## No model/backend silo

Memory belongs to AMPER, not to GGUF, imported model identity, AMI2 artifact, AMNE2 session or an
inference backend. Models may consume bounded memory context but do not own canonical memory
persistence.

GGUF remains import-source weights only.

## Runtime integration

`AmperRuntime` exposes `cognitiveMemoryTopology` so later M6 slices have one explicit contract.

No existing record format is migrated in Phase671.

## Architecture locks

Phase671 adds:

- `m6-cognitive-memory-has-one-durable-memory-os`
- `working-memory-is-global-workspace-plus-memory-os-continuity`
- `episodic-semantic-procedural-memory-share-canonical-memory-os`
- `cognitive-memory-domains-are-non-authority`
- `cognitive-memory-has-no-model-or-backend-specific-silo`
- `m6-memory-topology-is-contract-not-second-memory-facade`

## Tests

Tests verify exactly four domains, one durable MemoryOs binding, zero backend/model silos, working
workspace + durable continuity semantics, non-authority memory, runtime publication, and M6 locks.

## Architecture audit

Base: `main@71207f1e94798bcadb6bbc3f820254e275bac97f`.

No change to journal/encryption formats, semantic/conversation/skill encodings, AMI2/AMNE2, Titan,
ToolFabric, AuthorityGate, or M5 execution.

## Next M6 slice

Phase672 should harden bounded working-memory lifecycle: salience/capacity/expiry for transient
GlobalWorkspace cognition plus verified durable continuity checkpoints in the same MemoryOs, without
persisting raw hidden reasoning or creating a second working-memory database.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
`amper-core-arm64` SKIP.
