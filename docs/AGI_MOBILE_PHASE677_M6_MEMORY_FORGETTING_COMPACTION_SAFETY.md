# Phase677 — M6 Memory Forgetting / Compaction Safety

Phase677 hardens the shared MemoryOs forgetting boundary across working, episodic, semantic and
procedural memory.

PersistentMemoryOs previously trimmed overflow by importance/age only. Phase677 makes automatic
retention reference-aware before durable append.

A live record is protected when its MemoryId appears in another live record's Provenance.parents or
is encoded in another live record's content. Only unreferenced records may be auto-trimmed.

Eviction remains deterministic: lowest importance, then oldest timestamp, then MemoryId. The new
record is never selected as its own eviction candidate.

If capacity cannot be reduced without breaking live lineage, the write fails before journal append.
MemoryOs.forget(id) likewise returns false while another live record still references the target.

MemoryJournal.compact remains physical log compaction only; it does not select logical records for
deletion and no journal/encryption/hash-chain format changes are introduced.

Base: main@55fab93655c72d2d4a2f57e8b40b6d4d3453453f.

## Next M6 slice

Phase678 should audit memory migration/backward compatibility for the M6 contracts and verify that
pre-M6 journals safely coexist with bounded working continuity, episodic-v1, semantic freshness and
procedural qualification without destructive migration.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
