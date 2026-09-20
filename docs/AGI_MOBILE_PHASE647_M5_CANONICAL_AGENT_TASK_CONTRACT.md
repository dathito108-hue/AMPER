# Phase647 — M5 Canonical Agent Task Contract

Phase647 begins M5 by establishing one task contract above the existing AMPER planner, ToolFabric,
authority gate, audit log, persistent plans, goal executive, and Android background policy.

## Two task origins

Every Agent Core task has exactly one provenance:

- USER_REQUEST — passive work explicitly requested by the user;
- PROACTIVE_TRIGGER — work admitted from a bounded trigger with explicit trigger id, source,
  observation time, and payload digest.

A proactive task without trigger provenance fails closed.

## Android-safe execution mode

Task admission reuses `OmegaBackgroundExecutionPolicy`:

- short/non-surviving user work -> UI_BOUND;
- user work that must survive UI exit -> FOREGROUND_CONTINUATION;
- deferred user work -> PERSISTED_JOB;
- proactive triggered work -> EVENT_WAKE.

Every non-UI-bound task is checkpoint-required.

## Authority boundary

Agent Core does not authorize tools.

The contract explicitly locks:

- tool authority remains external;
- audited execution remains required;
- capability lists are only an execution envelope;
- existing ToolFabric, AuthorityGate, provider binding, receipt and approval paths remain
  authoritative.

## Lifecycle

The canonical task lifecycle is fail-closed:

ADMITTED -> READY/CHECKPOINTED -> RUNNING -> WAITING_APPROVAL/CHECKPOINTED/terminal

Terminal COMPLETED, FAILED, and CANCELLED states cannot restart implicitly.

WAITING_APPROVAL returns to READY only after the existing governed approval path resolves the
side-effect request.

## Architecture locks

Phase647 adds:

- `agent-task-origin-and-background-mode-are-canonical`
- `agent-core-never-owns-tool-authority`

## Next M5 slice

Phase648 should bind passive USER_REQUEST tasks to the existing persistent sovereign-plan execution
path and ToolFabric without duplicating planner or approval logic. It should checkpoint non-UI-bound
task progress around plan handoffs and expose bounded task diagnostics.
