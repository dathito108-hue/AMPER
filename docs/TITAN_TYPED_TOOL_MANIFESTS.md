# Typed Tool Manifests — Phase 16

Phase 16 upgrades AMPER's tool contract from capability-name-only discovery to typed, bounded manifests.

Each `ToolDescriptor` now carries a `ToolInputContract` with:
- a short human/model-readable description
- an optional finite set of accepted single-line values
- a maximum input length

`ToolRegistry.descriptors()` exposes registered descriptors to the sovereign action layer. `SovereignAssistantTurnCoordinator` includes only descriptors whose capabilities are already whitelisted for that assistant turn.

## Defense in depth

Typed manifests are not prompt-only hints. `SovereignActionLoop` validates model-proposed input against the registered descriptor before any provider or `AuthorityGate` invocation. Inputs outside the declared contract become `MALFORMED` action outcomes and are recorded in sovereign memory/workspace without executing the provider.

The action envelope remains strict `AMPER_ACTION_V1`, and one assistant turn remains bounded to at most one action attempt and one final synthesis pass.

## Current manifests

- `device.status.read`: accepted inputs `summary` or `status`, max 16 characters.
- `sovereign.status.read`: accepted inputs `summary`, `models`, `backends`, or `resources`, max 16 characters.

Manifest descriptions are sanitized and bounded before entering model context. Non-whitelisted registered tools are not advertised.
