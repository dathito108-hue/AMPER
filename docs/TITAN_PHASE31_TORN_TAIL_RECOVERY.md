# Titan Cortex Phase 31 — Torn-Tail Journal Recovery

Phase 31 distinguishes a provably incomplete final append from corruption of a complete sovereign journal entry.

## Invariants

1. **Only an unterminated final fragment may be discarded.**
   - Journal writers always complete an entry with `\n` before the durable `FileDescriptor.sync()` boundary.
   - If a reopened journal does not end in `\n`, the bytes after the last complete newline are treated as an incomplete append.
   - That final fragment is truncated and the repaired file is synced before replay continues.

2. **Complete lines are never silently skipped.**
   - A newline-terminated plaintext `R|` or `D|` line that cannot decode causes replay to fail closed.
   - An unknown complete plaintext line also causes replay to fail closed.

3. **Encrypted complete lines must authenticate.**
   - A torn unterminated encrypted tail is removed before decrypt/authentication.
   - Every complete `E1|` line must decrypt successfully with the configured AES-GCM key and decode to a valid memory record/tombstone.
   - Authentication failure, wrong key, malformed envelope, or unknown complete line fails closed.

4. **Migration uses the same torn-tail boundary.**
   - Legacy plaintext-to-encrypted migration first removes only a provably incomplete final fragment.
   - Every remaining complete plaintext or encrypted line must validate before the migration rewrite occurs.

5. **Recovery itself is durable.**
   - Torn-tail truncation calls `FileDescriptor.sync()` before returning recovered lines.

## Safety interpretation

Because provider execution is allowed only after a claim append has written its terminating newline and completed `FileDescriptor.sync()`, an unterminated claim fragment cannot represent a successfully completed pre-execution durability boundary. Removing only that fragment preserves the no-replay invariant while avoiding false recovery debt from an interrupted write.

This phase does not attempt to repair corruption in complete entries, infer missing data, bypass AES-GCM authentication, or provide multi-process locking.
