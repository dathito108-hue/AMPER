# Titan Cortex Phase 33 — Journal Chain / Order Integrity

Phase 33 adds an ordered hash chain above Phase 32 F1 framing and below optional E1 AES-GCM encryption.

## Canonical entry stack

Plaintext journal:

`C1(sequence, previousDigest, F1(payload), chainDigest)`

Encrypted production journal:

`E1(AES-GCM(C1(sequence, previousDigest, F1(payload), chainDigest)))`

F1 remains the payload length/SHA-256 corruption frame. C1 adds sequence and predecessor binding. E1 remains the cryptographic authenticity boundary for encrypted production memory.

## Invariants

- Sequence starts at 1 and increments exactly by one.
- Entry 1 points to the fixed all-zero genesis digest.
- Every later entry carries the exact digest of its immediate predecessor.
- The C1 digest covers version, sequence, predecessor digest and the encoded F1 frame.
- Replay fails closed on sequence gaps, reordering, duplicated entries, predecessor mismatch, C1 digest mismatch, F1 corruption, invalid mutation payloads, or AES-GCM authentication failure.
- A journal that mixes chained C1 entries and legacy unchained entries is not silently normalized; it fails closed.
- Fully legacy journals are validated and then atomically migrated in their existing order to C1.
- Phase 32 plaintext F1 and encrypted E1(F1) journals migrate without losing records.
- New journal appends are fd-synced before the in-process chain state advances.
- Phase 31 torn-tail recovery remains unchanged: only an unterminated final fragment may be truncated.

## Security boundary

For plaintext journals, C1 is tamper-evident against accidental or non-recomputed corruption; an active writer that can rewrite the whole plaintext file can recompute an unkeyed chain.

For encrypted journals, C1 metadata is inside AES-GCM, so an attacker without the memory key cannot forge a modified internal chain. Reordering, duplication, insertion of old entries, or deleting an internal entry causes replay to fail once the chain is verified.

C1 alone does **not** prove that the final suffix is complete. An attacker that can roll the journal back by removing the entire final suffix can leave a shorter internally valid chain. Detecting that requires a trusted external/monotonic head anchor and is intentionally deferred to a later phase.

Phase 33 does not add multi-process locking, distributed transactions, automatic replay/retry, background execution, or autonomous side-effect approval.
