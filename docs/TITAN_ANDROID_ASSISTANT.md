# Governed Android Assistant — Phase 14

Phase 14 connects AMPER's Android UI to the bounded sovereign assistant-turn coordinator and the first real device-awareness provider.

## Runtime flow

1. The active sovereign conversation and encrypted Memory OS provide context.
2. Titan receives the user request plus the `AMPER_ACTION_V1` protocol and only the capabilities explicitly advertised by AMPER.
3. For device-resource questions, Titan may propose `device.status.read`.
4. `SovereignActionLoop` parses the complete action envelope.
5. `AuditedToolFabric` routes through `AuthorityGate` to `DeviceStatusToolProvider`.
6. The tool result is bounded and labelled as untrusted data before a second Titan pass produces the final answer.
7. The final assistant response and tool-action provenance are persisted into sovereign memory.

## Hard bounds

- A normal answer uses one inference pass.
- A tool-assisted answer uses at most one tool attempt and one final synthesis pass.
- The second model output is never evaluated for another action in the same turn.
- Only `device.status.read` is advertised in the Android UI in this phase.
- Device status is read-only and does not expose stable personal identifiers.
- Any future `LOCAL_STATE` or `EXTERNAL` provider still stops at explicit user confirmation before execution.
- User approval cannot bypass `AuthorityGate` or tool auditing.

## Android UI

`Ask AMPER` now routes through `SovereignAssistantTurnCoordinator` instead of calling Titan directly. The UI can surface pending side-effect approval, approve or reject it, and displays the resulting action status and audit count.

This phase does not add background autonomy, unrestricted Android permissions, recursive tool use, or direct model access to Android APIs.
