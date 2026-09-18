# Titan Cortex Phase 35 — Multi-Process Journal Serialization

Phase 35 closes the stale-head race between independent AMPER journal instances that point at the same memory files.

## Lock boundary

Every file-backed plaintext or encrypted journal now uses two lock layers for the same `<journal>.lock` path:

1. a fair JVM-local `ReentrantLock`, keyed by absolute lock-file path, serializes independent instances/threads inside one process and prevents `OverlappingFileLockException`;
2. an exclusive `FileChannel.lock()` serializes cooperating AMPER processes through the filesystem.

The OS lock covers journal migration, C1 verification, H1 verification/reconciliation, append/tombstone mutation, durable journal fsync, and durable head-anchor replacement.

## Stale-head rule

Before every append or tombstone, an instance re-reads and verifies the current C1 journal and H1 anchor while holding the process lock. It does not trust the `chainState` cached when that object was constructed.

Therefore two independent instances cannot both derive the same next sequence from an old head. The second writer observes the first writer's durable sequence/digest and chains from it.

## Existing durability/security layers remain

- F1: payload length + SHA-256 framing.
- C1: sequence/predecessor/order hash chain.
- E1: AES-GCM authenticity for encrypted memory.
- H1: durable accepted-head sidecar used for suffix rollback detection when the anchor survives.
- Phase 31: only an unterminated final journal tail may be truncated.

## Scope

This is cooperative local-filesystem process serialization. It assumes the platform/filesystem honors Java `FileChannel.lock()` semantics for processes using the same lock file.

It is not a distributed lock, network-filesystem consensus protocol, hardware monotonic counter, or defense against another actor that ignores the lock and directly rewrites both journal and anchor. Phase 34's limitation also remains: rolling both journal and H1 sidecar back to the same older consistent snapshot is not detectable by these files alone.

No automatic tool retry/replay, autonomous side-effect approval, or background execution is introduced.
