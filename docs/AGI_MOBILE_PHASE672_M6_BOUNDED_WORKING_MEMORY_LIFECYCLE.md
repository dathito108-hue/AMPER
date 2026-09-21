# Phase672 — M6 Bounded Working-Memory Lifecycle

Phase672 turns the M6 working-memory contract into a bounded mobile runtime policy without creating
a second working-memory database.

## Canonical workspace

Production/reference runtime construction now uses `CanonicalWorkingMemoryWorkspace` rather than
the unbounded test scaffold `InMemoryWorkspace`.

`InMemoryWorkspace` remains unchanged for focused unit tests and migration/reference helpers.

The canonical workspace still implements the existing `GlobalWorkspace` interface, so cognition,
context capture and kernel components do not gain a second working-memory API.

## Mobile bounds

Default policy:

- maximum 128 transient events;
- 15-minute maximum age;
- minimum salience 0.05;
- continuity checkpoint at most once per 8 state mutations.

Low-salience noise is not admitted.

Expired events are removed on publish/snapshot.

When capacity is exceeded, eviction is deterministic:

1. lowest normalized salience;
2. oldest publish time;
3. oldest remaining list position.

This preserves higher-value recent cognition under mobile memory pressure.

## Durable continuity without raw reasoning

`MemoryBackedWorkingMemoryContinuityStore` writes exactly one logical record id inside the same
canonical `MemoryOs`.

The record contains only:

- monotonic checkpoint sequence;
- previous checkpoint SHA-256;
- current workspace-state SHA-256;
- retained-event count;
- capture time;
- checkpoint SHA-256.

It stores no `CognitiveEvent.topic`, no payload, no raw prompt, no raw hidden reasoning and no tool
arguments.

Workspace-state SHA-256 is computed in-process over event digests; only the aggregate digest is
persisted.

## Verified checkpoint chain

Before overwrite, the store requires:

- sequence == previous sequence + 1;
- previous-checkpoint digest == currently persisted checkpoint digest;
- checkpoint self-digest is valid.

A broken chain fails without overwriting the current checkpoint.

A recreated workspace reads the last verified checkpoint and chains the next checkpoint from it; it
does not reconstruct raw transient events from durable storage.

## Non-gating continuity failure

Working-memory cognition remains transient. The continuity path is non-authority-bearing and cannot
approve, schedule, execute, or invoke tools.

The workspace tracks continuity failure rather than inventing a successful checkpoint.

## Write amplification

Default checkpoint cadence is 8 state mutations, intentionally greater than the six workspace
events emitted by one basic canonical kernel tick. This keeps the existing first-tick durable-memory
behavior unchanged while still providing bounded periodic continuity.

Tests can use interval=1 to verify chain behavior deterministically.

## Architecture locks

Phase672 adds:

- `working-memory-is-capacity-salience-and-expiry-bounded`
- `working-memory-capacity-evicts-lowest-salience-oldest-first`
- `working-memory-continuity-checkpoints-use-same-memory-os`
- `working-memory-checkpoints-store-digest-metadata-only`
- `working-memory-continuity-chain-is-verified`
- `working-memory-checkpoint-write-cadence-is-bounded`
- `working-memory-continuity-is-non-authority`

## Tests

Phase672 verifies capacity eviction, salience filtering, expiry, digest-only checkpoint content,
restart chaining, broken-chain rejection, one logical checkpoint record, and bounded default write
cadence.

## Architecture audit

Base: `main@5f57bf8184f1b2233d22787e259da6b9a6be4fff`.

No change to native kernels, build configuration, memory journal/encryption format, AMI2/AMNE2,
Titan, ToolFabric, AuthorityGate, M5 scheduler/execution, semantic memory or procedural memory.

## Next M6 slice

Phase673 should harden episodic-memory admission/retention: provenance, bounded episodic indexing,
salience/importance policy, duplicate suppression and privacy-safe retention over the same MemoryOs,
without duplicating conversation history or semantic knowledge.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
`amper-core-arm64` SKIP.
