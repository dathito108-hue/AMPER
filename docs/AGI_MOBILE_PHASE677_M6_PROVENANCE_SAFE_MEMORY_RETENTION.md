# Phase677 — M6 Provenance-Safe Memory Retention / Forgetting

Phase677 hardens logical MemoryOs retention. It does not change the memory journal encoding,
encryption, head anchor, or physical compaction algorithm.

## Problem

PersistentMemoryOs previously enforced maxRecords after append by sorting all live records by
importance/time and tombstoning overflow.

That could:

- delete a low-importance record still referenced by another record's provenance.parents;
- remove a live-index record before a tombstone had durably succeeded;
- silently auto-evict the record that the caller had just asked MemoryOs to remember.

## Provenance-safe preflight

Before durable append, ProvenanceSafeMemoryRetentionPlanner projects the post-write record graph.

The incoming record is protected.

For overflow, the planner removes only graph leaves: records with no incoming provenance reference
from another record that will remain live. When a selected leaf references a parent, removing that
leaf may make the parent eligible on the next iteration.

This allows bounded lineage peeling while guaranteeing that the final retained set contains no
reference to an automatically evicted parent.

If capacity cannot be satisfied without deleting live provenance evidence, the write fails before the
candidate is appended.

## Durable ordering

For accepted writes:

1. append the candidate;
2. durably tombstone each preflighted eviction;
3. only after each tombstone succeeds, remove that record from the in-memory index.

Candidate-first ordering means a crash between append and tombstone may temporarily replay above
maxRecords, but it does not lose the newly accepted candidate or referenced evidence.

Construction remains read-only. The next mutation preflights the replayed overflow and reconciles it.

If a tombstone operation fails, the record remains in the live index and the operation fails visibly.
Temporary retention debt is safer than silent evidence loss.

## Explicit forget

MemoryOs.forget now checks live provenance blockers first.

A record referenced as provenance parent by another live record cannot be explicitly forgotten.
For an unreferenced record, the durable tombstone succeeds before live-index removal.

## Physical compaction unchanged

MemoryJournal.compact still rewrites the current logical live set and preserves its chain/head
semantics. Phase677 does not modify FileMemoryJournal or EncryptedFileMemoryJournal compaction.

## Architecture locks

Phase677 adds:

- memory-retention-preflights-provenance-safe-leaf-eviction
- memory-retention-never-auto-evicts-incoming-candidate
- memory-forget-rejects-live-parent-evidence
- memory-tombstone-precedes-live-index-removal
- memory-retention-failure-preserves-evidence-and-fails-visible
- memory-replay-overflow-is-reconciled-on-next-mutation
- physical-memory-journal-compaction-remains-logical-state-preserving

## Architecture audit

Base: main@55fab93655c72d2d4a2f57e8b40b6d4d3453453f.

No native/build change. No MemoryRecord or journal codec migration. AMI2/AMNE2, Titan, ToolFabric,
AuthorityGate, M5 execution, and Phase671-676 domain stores remain unchanged.

## Next M6 slice

Phase678 should audit canonical memory lineage roots and explicit retention classes for durable
conversation, plan/recovery and active cognitive-state records that may not be linked solely through
provenance.parents, without adding a second MemoryOs.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
