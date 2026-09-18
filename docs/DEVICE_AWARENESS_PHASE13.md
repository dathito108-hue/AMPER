# Android Device Awareness Pack — Phase 13

Phase 13 adds the first concrete Android ToolProvider for Titan: a read-only device resource snapshot used for answering device-status questions and for later inference/resource planning.

## Capability

`device.status.read`

The provider reports only operational resource information:
- battery percentage and charging state
- available RAM and Android low-memory signal
- app storage free/total capacity
- Android thermal status when supported
- available processor count
- Android SDK level
- supported CPU ABIs

It does not expose Android ID, serial number, IMEI, phone number, account data, contacts, precise location or other stable personal identifiers.

## Authority boundary

- Provider side effect class is `READ_ONLY`.
- Titan never calls Android APIs directly.
- The provider must be registered in `ToolRegistry`.
- Invocation still passes through `SovereignActionLoop`, `AuditedToolFabric` and `AuthorityGate`.
- The production runtime can create its action loop with `AmperRuntime.actionLoop()`, which binds action outcomes to the same encrypted Memory OS and Global Cognitive Workspace as the rest of AMPER.

## Storage

Production sovereign memory remains AES-GCM encrypted with Android Keystore key custody. Device-status action records are therefore stored under the same protected journal rules as conversation and cognition records.

## Next boundary

The next integration step can register this provider in the Android UI and route the existing Phase 12 bounded assistant-turn coordinator through it. Side-effect providers remain separate and confirmation-gated.
