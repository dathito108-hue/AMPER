# Phase 10 — Encrypted Sovereign Memory

AMPER's Android production runtime now encrypts the persistent Memory OS journal at rest.

## Design

- Each journal record or tombstone is independently encrypted with AES-256-GCM.
- Every encrypted line uses a fresh 96-bit nonce and a 128-bit authentication tag.
- Envelope metadata is authenticated as AES-GCM additional authenticated data.
- The production AES key is generated and retained inside `AndroidKeyStore` under alias `amper-sovereign-memory-v1` and is never written into the journal.
- Hardware-backed key storage depends on the Android device; AMPER does not assume or claim hardware backing when the device does not provide it.
- Authentication failure, key mismatch, malformed ciphertext, and unknown journal lines fail closed.

## Plaintext migration

Existing `memory.journal` files from earlier AMPER phases are not discarded. On first startup with Phase 10:

1. existing `R|` record and `D|` tombstone lines are read;
2. each line is encrypted into an `E1|...` AES-GCM envelope;
3. any already-encrypted lines are authenticated before migration proceeds;
4. the converted journal is written through a temporary sibling file and atomically replaces the plaintext journal when the filesystem supports atomic replacement.

After migration, all subsequent writes are encrypted.

## Runtime separation

`AmperRuntime.persistentEncrypted(...)` is the Android production constructor. `AmperRuntime.persistent(...)` remains available for deterministic JVM/reference tests and migration tooling. This keeps Android Keystore outside the portable cognitive contracts while ensuring the installed Android app uses encrypted sovereign memory.

## Security boundary

Encryption protects journal contents at rest. It does not make data secret from AMPER while the app is legitimately running and holding access to the Android Keystore key. Model weights remain user-owned external artifacts and are not copied into the Memory OS.
