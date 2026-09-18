# Sovereign Planning Layer — Phase 17

Phase 17 adds explicit multi-step planning without introducing autonomous tool loops.

## Core boundary

Planning and execution are separate operations:

1. `SovereignPlanCoordinator.create()` performs one Titan inference and **zero tool calls**.
2. The model must return one strict `AMPER_PLAN_V1` envelope containing 1–4 contiguous steps.
3. Every step is validated against the typed manifests of capabilities already whitelisted for that planner.
4. `advance()` processes **at most one** pending plan step.
5. Read-only steps may execute through the existing `SovereignActionLoop -> AuditedToolFabric -> AuthorityGate` path.
6. `LOCAL_STATE` and `EXTERNAL` steps stop as `REQUIRES_CONFIRMATION`.
7. `approve()` executes exactly one previously blocked step and still passes through the same AuthorityGate and audit path.
8. `reject()` performs no tool invocation.

There is no internal loop that automatically advances through an entire plan.

## Protocol

A planning model may emit only:

```text
<AMPER_PLAN_V1>
step.1.capability=<allowed capability>
step.1.reason=<short user-centered reason>
step.1.input=<input matching the typed tool contract>
...
</AMPER_PLAN_V1>
```

The envelope must be the model's complete output. Step numbers must begin at 1 and remain contiguous. Plans longer than four steps are rejected.

## Typed preflight

Before a plan object is accepted, every step is checked against the same `ToolDescriptor` / `ToolInputContract` manifests introduced in Phase 16:

- the capability must be in the planner's explicit whitelist;
- a registered descriptor must exist;
- input length must fit the manifest;
- finite accepted-value contracts must match exactly after normalization.

A malformed step rejects the whole plan before any tool or AuthorityGate call.

## Provenance and continuity

The planning request still enters the Sovereign Kernel and conversation context. A successfully parsed plan is summarized back into AMPER-owned conversation memory with the planning backend id as provenance. Tool execution outcomes continue to be recorded by the existing sovereign action loop.

## Explicit non-goals

Phase 17 does not add background autonomy, recursive planning, hidden plan execution, unrestricted Android permissions, or automatic side-effect approval. Plan persistence/resume across process death and a dedicated planner UI can be layered on top of this bounded core without weakening the execution boundary.
