# Titan Cortex Phase 30 — Crash-Durable Journal Writes

Phase 30 strengthens the claim-before-side-effect invariant by requiring sovereign journal mutations to reach the file descriptor durability boundary before execution may continue.

## Invariants

1. **Journal append/tombstone is fd-synced before success returns.**
   - `DurableJournalIo.appendUtf8Line()` writes, flushes, then calls `FileDescriptor.sync()`.
   - `FileMemoryJournal` uses this path for records and tombstones.
   - `EncryptedFileMemoryJournal` encrypts first, then uses the same durable append path.

2. **Durability failure fails closed before in-memory publication.**
   - `PersistentMemoryOs.remember()` and `rememberIfAbsent()` call the journal before mutating the in-memory index.
   - If write/flush/sync fails, the exception propagates and the memory record is not published.
   - Therefore a side-effect claim that cannot become durable prevents provider execution.

3. **Encrypted-at-rest semantics remain unchanged.**
   - AES-GCM envelopes are still one encrypted line per journal mutation.
   - No plaintext claim/receipt/reconciliation payload is introduced.

4. **Legacy plaintext migration is synced before and after replacement.**
   - Migration temp content is written and synced before move.
   - The final file is synced after the move.

## Scope

This phase provides file-descriptor durability for journal contents in the current process. It does not claim parent-directory fsync, power-loss guarantees beyond the platform/filesystem contract of `FileDescriptor.sync()`, multi-process serialization, distributed transactions, or remote durability.

No automatic retry/replay, recursive tool loop, Run All path, background continuation, or automatic side-effect approval is introduced.
