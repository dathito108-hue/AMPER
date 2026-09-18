# Titan Cortex Phase 32 — Journal Integrity Framing

Phase 32 adds a versioned integrity frame around each sovereign memory mutation while retaining backward compatibility with legacy journals.

## F1 frame

Each new plaintext memory mutation is stored as:

`F1 | payload-byte-length | SHA-256(payload-bytes) | base64url(payload-bytes)`

The payload remains the existing `MemoryJournalCodec` `R|...` or `D|...` mutation.

## Invariants

1. **New plaintext journal writes are framed.**
   - `FileMemoryJournal` writes only newline-terminated `F1` entries.
   - Replay validates frame version, byte length and SHA-256 before parsing the memory mutation.

2. **Encrypted journals keep AES-GCM as the authenticity boundary.**
   - New encrypted writes are `E1(AES-GCM(F1(memory-mutation)))`.
   - F1 SHA-256 is corruption detection/versioned framing, not a replacement for cryptographic authentication.

3. **Legacy data remains readable.**
   - Plaintext replay still accepts complete legacy `R|`/`D|` entries.
   - Encrypted journal initialization recognizes legacy plaintext, plaintext F1, and legacy `E1(R|/D|)` entries.

4. **Encrypted legacy entries migrate once to framed encrypted form.**
   - Every complete legacy entry is decoded/validated before rewrite.
   - The rewritten journal uses `E1(F1(...))` and the existing durable atomic rewrite path.
   - Already framed encrypted entries are validated and retained without needless rewrite.

5. **Corruption remains fail-closed.**
   - F1 length mismatch, digest mismatch, malformed base64, invalid payload, AES-GCM authentication failure, unknown complete line, or blank complete line stops replay/migration.
   - Phase 31 remains the only repair rule: a provably unterminated final fragment may be truncated.

## Compatibility and security scope

F1 is a storage-integrity/versioning layer. Its unkeyed SHA-256 does not defend plaintext journals from a malicious writer that can recompute the digest. Android production encrypted journals continue to rely on AES-GCM/AndroidKeyStore for authenticity. This phase does not provide multi-process locking, suffix-truncation detection against an adversary, or distributed durability.
