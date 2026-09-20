# Phase649 — M5 Execution Continuation Checkpoint Envelope

Phase649 makes non-UI-bound USER_REQUEST tasks resumable after UI exit or process recreation without
replaying already-completed work.

## Exact durable-state binding

The continuation envelope binds:

- task id;
- persistent sovereign plan id;
- background mode;
- normalized task state;
- completed/total step counts;
- exact SHA-256 of the persisted sovereign-plan representation;
- exact pending approval step when applicable;
- checkpoint timestamp.

The envelope supports only FOREGROUND_CONTINUATION and PERSISTED_JOB in Phase649. UI-bound tasks do
not need continuation persistence, while proactive EVENT_WAKE work is handled by a later M5 slice.

## No implicit replay

Checkpoint creation and restore never call:

- planner advance;
- ToolFabric;
- AuthorityGate;
- approve/approveBound;
- provider execution.

READY/RUNNING work is normalized to CHECKPOINTED before persistence. On restore, the current durable
plan must hash to the exact same digest. If the plan changed, restore fails closed instead of replaying
or guessing which step should run.

WAITING_APPROVAL persists the exact pending step index and remains WAITING_APPROVAL after restore.
The existing governed approval surface must resolve it before the passive coordinator can resume.

## Capability envelope hardening

Phase649 also closes a Phase648 boundary: every persistent sovereign plan bound to an Agent Core
USER_REQUEST must keep all plan-step capabilities inside the task's allowedCapabilities envelope.

This check now applies when the passive task:

- starts;
- advances from a durable checkpoint;
- resumes after externally governed approval;
- creates an execution-continuation envelope.

The planner may still reason over its canonical global tool registry, but Agent Core fails closed if
the resulting persistent plan escapes the task-specific capability envelope.

## Architecture lock

Phase649 adds:

`agent-continuation-restores-exact-durable-plan-without-replay`

M5 now explicitly requires foreground and persisted user tasks to restore exact durable state without
replay.

## Next M5 slice

Phase650 should bind these continuation envelopes to the Android execution adapters:
FOREGROUND_CONTINUATION should map to the existing visible foreground-service continuation path and
PERSISTED_JOB to the existing persisted-job scheduling path, while keeping Agent Core free of Android
component ownership and tool authority.
