# Titan Cortex Phase 37 — Crash-Safe Memory Key Rotation

Phase 37 rotates the AES-GCM key used by sovereign-memory storage without changing the inner B1/C1/F1 lineage.

## K1 transition manifest

Managed encrypted journals keep a small non-secret sidecar:

```
K1 | active-key-id | fallback-key-ids | retire-pending-key-ids | checksum
```

Key aliases are recovery metadata, not cryptographic trust. E1 AES-GCM authentication remains the authenticity boundary for journal and H1 content.

Normal stable state has one active key and no fallback. Rotation publishes the new key as active and the previous key as fallback **before** either durable encrypted file is rewrapped.

## Rotation protocol

Rotation executes under the Phase 35 process/file lock.

1. Verify B1/C1/F1 journal state against H1.
2. Persist K1 with `active=new` and `fallback=old`.
3. Install a dual-key decrypt ring that encrypts only with `new` but can authenticate/decrypt E1 envelopes from `new` or `old`.
4. Rewrap every journal E1 envelope under `new`; inner plaintext frames are unchanged.
5. Rewrap H1 under `new` without changing its sequence/digest.
6. Persist K1 with no fallback and `retire-pending=old`.
7. Re-open/verify journal and H1 using the new key alone.
8. Retire the old AndroidKeyStore alias. Successful retirements are removed from K1.

## Crash recovery

The K1 transition is persisted first. Therefore restart can recover these windows:

- K1 changed but journal and H1 are still old.
- Journal is new but H1 is old.
- Journal and H1 are new but K1 still lists the old fallback.
- K1 says the old alias is retirement-pending after both encrypted files are already new.

Opening a managed encrypted journal automatically resumes an incomplete rotation before normal use.

## Runtime behavior

`AmperRuntime.persistentEncrypted(...)` uses managed AndroidKeyStore-backed encrypted memory by default. The runtime exposes `rotateMemoryEncryption(newKeyId)` for managed production storage.

Supplying an explicit `MemoryLineCipher` preserves the single-cipher test/migration path; that path intentionally refuses managed rotation because it has no durable key-provider context.

## Preserved invariants

- No model weights or backend architecture changes.
- B1/C1/F1 bytes remain unchanged by re-encryption.
- H1 sequence/digest remains unchanged by re-encryption.
- Memory plaintext is not written to the journal or H1 during rotation.
- E1 authentication is still required for every encrypted journal/head envelope.
- C1/H1 rollback, ordering, torn-tail, compaction and process-lock rules remain in force.

## Scope limits

K1 is checksum-protected recovery metadata, not a keyed trust anchor. Modifying it cannot make forged E1 ciphertext authenticate, but an attacker able to rewrite local files can cause denial of service. Coordinated rollback of journal, H1 and K1 to a mutually consistent older snapshot remains outside the current trusted-hardware boundary.
