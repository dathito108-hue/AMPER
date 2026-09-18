# Titan Cortex Phase 38 — Directory Durability & Atomic Replace Hardening

Phase 38 closes a local-filesystem crash window that existed after Phase 37.

Before this phase, AMPER synced journal/sidecar file bytes but did not force the parent-directory metadata after create or rename/replace. On filesystems with writeback caching, a process or device crash could therefore leave the file contents durable while the directory entry update was not yet forced.

## Durability boundary

`DurableJournalIo.rewriteUtf8LinesAtomically()` now performs the following sequence:

1. write the temporary file,
2. flush and `FileDescriptor.sync()` the temporary bytes,
3. atomically move/replace the destination when supported,
4. sync the destination file,
5. force the parent directory with `FileChannel.force(true)`,
6. return only after the directory metadata force succeeds.

This path is shared by H1 head anchors, K1 key-transition manifests, B1/C1 compaction rewrites, encrypted rewrap/key rotation, and legacy migration rewrites.

`DurableJournalIo.ensureFileExistsDurably()` adds the same directory durability boundary for newly created plaintext journal files.

## Invariants preserved

Phase 38 does not alter F1 payload integrity, C1 sequence/predecessor chaining, H1 rollback anchoring, B1 compaction lineage, E1 AES-GCM authentication, K1 crash-safe key rotation, or the Phase 35 process/file lock.

No model weights are bundled and no inference/backend architecture changes are introduced.

## Scope

This is a local-filesystem durability hardening layer. It relies on the runtime/filesystem implementing directory channels and `force(true)` semantics. It is not a distributed filesystem consensus protocol, a hardware monotonic counter, or protection against storage/controller failures that violate fsync/force contracts.

The encrypted journal constructor still follows its existing creation path; all encrypted sidecar/journal rewrites after creation use the hardened shared atomic-rewrite primitive. A future platform-specific Android durability probe can replace or augment the generic NIO directory-force implementation if device validation shows vendor-specific behavior.
