# Titan Cortex Phase 34 — Durable Journal Head Anchor

Phase 34 adds a durable sidecar anchor for the C1 journal head. The anchor stores the last accepted `(sequence, digest)` so a later process can detect when the journal has been rolled back to a shorter otherwise-valid C1 prefix.

## Storage stack

Plaintext memory:

- journal: `C1(F1(payload))`
- sidecar: `H1(sequence, digest, checksum)`

Encrypted production memory:

- journal: `E1(AES-GCM(C1(F1(payload))))`
- sidecar: `E1(AES-GCM(H1(sequence, digest, checksum)))`

The sidecar path is `<journal>.head`.

## Commit ordering

A mutation commits in this order:

1. validate and construct the next C1 entry;
2. append the journal entry and `FileDescriptor.sync()` it;
3. advance the in-process chain state;
4. atomically replace + sync the head sidecar.

The journal intentionally becomes durable before the anchor. A crash between steps 2 and 4 can therefore leave a valid journal extension with an older anchor.

On startup/replay:

- missing anchor: bootstrap it to the fully verified journal head (upgrade/first-use boundary);
- journal head equals anchor: accept;
- journal extends anchor and the anchored digest matches the corresponding verified prefix: accept the extension and advance the anchor;
- anchor sequence is ahead of the journal: fail closed as suffix rollback;
- equal sequence with different digest: fail closed as divergence;
- journal extension that does not descend from the anchor: fail closed.

## Security boundary

For encrypted memory, H1 is inside E1 AES-GCM and is authenticated by the same memory key. For plaintext memory, H1's checksum is corruption detection only and is not keyed authentication.

The anchor detects suffix deletion/rollback only when the anchor survives with a newer state. It does **not** provide a hardware monotonic counter or independent trusted storage. If an attacker can roll back both the journal and its sidecar to the same older consistent snapshot, Phase 34 cannot distinguish that snapshot from a legitimate older state.

The first Phase 34 open of a pre-anchor journal establishes the initial anchor from the journal that is present at that moment; rollback that occurred before that bootstrap cannot be detected retroactively.

Phase 34 does not claim parent-directory fsync, multi-process serialization, rollback-resistant hardware storage, distributed transactions, automatic replay/retry, or autonomous side-effect approval.
