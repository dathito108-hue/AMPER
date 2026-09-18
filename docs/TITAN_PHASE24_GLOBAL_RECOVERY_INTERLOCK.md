# Titan Cortex Phase 24 — Global Recovery Interlock

Phase 24 prevents AMPER from approving another side-effect operation while an earlier durable side-effect claim is still unresolved.

## Invariants

- `SovereignRecoveryState` is the canonical source of recovery debt.
- Ordinary conversation, inference, plan inspection, recovery inspection, and automatic READ_ONLY tool execution remain available while recovery debt exists.
- Every explicit assistant approval is fail-closed while recovery debt exists.
- Every persistent-plan approval is fail-closed while recovery debt exists.
- Persistent-plan approval is blocked **before** a new claim is written and before a provider/tool invocation occurs.
- Manual reconciliation remains available while locked because it is the safe path that clears recovery debt without provider replay.
- A terminal receipt resolves its claim for recovery-lock purposes while preserving the immutable claim as audit evidence.
- No automatic retry/replay, Run All path, recursive tool loop, or background continuation is introduced.

## Why the interlock is outside SovereignActionLoop.approve

Persistent plan execution intentionally writes a durable claim immediately before invoking a side-effect provider. Putting the recovery check inside `SovereignActionLoop.approve()` would see that newly-created claim and block the very action it protects. The interlock therefore lives at the assistant/persistent-plan coordinator boundaries, before claim creation/provider invocation.
