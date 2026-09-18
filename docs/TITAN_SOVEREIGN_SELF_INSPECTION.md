# Sovereign Self-Inspection Pack — Phase 15

Phase 15 adds a second governed read-only capability: `sovereign.status.read`.

It lets AMPER answer questions about its currently installed user models, registered inference backends and current resource budget using live runtime state instead of model guesses.

## Exposed data

- up to eight installed model display names and AMPER model ids
- up to eight registered inference backend ids
- backend health state when the backend exposes `ManagedInferenceBackend`
- backend hardware-acceleration flag when available
- current Titan memory budget
- current Android thermal class
- maximum concurrent agent budget

## Privacy boundary

The tool does not expose model artifact locators, SAF content URIs, file paths, SHA-256 hashes, raw GGUF metadata, account data or stable device identifiers.

## Authority boundary

- `sovereign.status.read` is `READ_ONLY`.
- Titan receives the capability only when AMPER advertises it.
- Invocation still goes through `SovereignActionLoop`, `AuditedToolFabric` and `AuthorityGate`.
- Tool output is bounded and re-enters Titan only as untrusted data for the final synthesis pass.
- Recursive tool execution remains prohibited within one assistant turn.

The Android UI now advertises exactly two read-only tools: `device.status.read` and `sovereign.status.read`.
