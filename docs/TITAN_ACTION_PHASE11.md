# Titan governed action loop — Phase 11

Phase 11 turns Titan from a conversational engine into a governed action proposer without giving the model direct access to tools, Android APIs, files, network services or external accounts.

## Invariants

- Model output is data. Titan never receives a `ToolProvider` reference.
- An action is recognized only when the entire model output is one strict `AMPER_ACTION_V1` envelope.
- Quoted, surrounded or malformed action examples never execute.
- Unknown capabilities fail closed.
- Read-only actions may auto-execute only when policy permits and the Authority Gate grants the capability.
- `LOCAL_STATE` and `EXTERNAL` actions always return `REQUIRES_CONFIRMATION` before execution.
- Explicit approval does not bypass policy enforcement: approved actions still execute through `AuditedToolFabric` and its `AuthorityGate`.
- Tool outcomes are written to sovereign Memory OS and published to the Global Cognitive Workspace.
- Production Memory OS remains AES-GCM encrypted with Android Keystore key custody.

## Protocol

A model that needs a tool must output only:

```text
<AMPER_ACTION_V1>
capability=<allowed capability>
reason=<short user-centered reason>
input=<single-line input>
</AMPER_ACTION_V1>
```

Any text outside the envelope makes it non-executable.

## Execution states

`NO_ACTION`, `MALFORMED`, `UNAVAILABLE`, `REQUIRES_CONFIRMATION`, `EXECUTED`, `DENIED`, and `FAILED` are explicit outcomes. This allows the UI and later agent orchestration to distinguish a normal answer from a pending user decision, an authority denial, or a provider failure.

## Next integration boundary

Android/device providers can be added independently behind `ToolProvider`. They do not change Titan, sovereign identity, conversation storage, or model backends. High-impact providers should remain explicit-consent operations even when a model proposes them.
