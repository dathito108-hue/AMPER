# Titan Cortex Phase 36 — Crash-Safe Journal Compaction

Phase 36 bounds physical sovereign-memory journal growth without discarding the verified history boundary established by F1/C1/E1/H1.

## Format

A compacted plaintext segment is:

```
B1|<old-head-sequence>|<old-head-digest>|<checksum>
C1|<old-head+1>|<old-head-digest>|<F1 snapshot record>|<digest>
C1|...
```

Encrypted production memory stores the same logical segment as independent AES-GCM E1 envelopes:

```
E1(AES-GCM(B1(...)))
E1(AES-GCM(C1(F1(record))))
...
```

B1 is not a new genesis. It records the previously verified C1 head and snapshot C1 entries continue sequence numbers from that state.

## Compaction protocol

Compaction runs under the Phase 35 process/file lock.

1. Recover only complete durable lines and validate the current B1/C1 segment.
2. Reconcile the segment with H1.
3. Replay mutations to derive the current live-record set.
4. Build a new B1 from the current verified head.
5. Encode the live snapshot as new C1 record entries descending from that B1.
6. Atomically replace and sync the journal file.
7. Replace H1 with the new snapshot head.

The rewrite is performed only when it reduces the number of physical journal lines.

## Crash window

The journal is replaced before H1 advances. If AMPER dies after the durable journal replacement but before the H1 replacement, the old H1 equals the new B1. On restart the compacted segment is therefore recognized as a verified extension of the old anchor and H1 advances to the snapshot head.

No partially written snapshot is accepted through the normal journal path: the atomic file replacement either leaves the previous journal visible or exposes the fully written replacement file.

## Preserved properties

- Logical live memory and provenance survive compaction.
- Tombstoned and superseded physical mutations can be removed.
- C1 sequence numbers do not reset.
- H1 rollback/divergence checks continue across compacted segments.
- Encrypted journals keep B1, C1, F1 payloads and H1 under E1 AES-GCM authentication.
- Stale journal instances refresh B1/C1/H1 under the process lock before appending.

## Scope limits

Phase 36 does not claim rollback-resistant hardware. If an actor can restore both the compacted journal and its H1 sidecar to the same older consistent snapshot, that coordinated rollback remains outside the current trust boundary. Parent-directory fsync semantics remain platform dependent, and the lock is a cooperative local-filesystem boundary rather than distributed consensus.
