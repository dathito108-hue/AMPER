# Phase655 — Verified EVENT_WAKE Checkpoint / Handoff Contract

Phase655 qualifies proactive-trigger continuation state before any Android monitor or scheduler is
allowed to consume it.

## Audit result

Phase654 already bound PROACTIVE_TRIGGER tasks to the canonical persistent sovereign-plan engine and
fixed their background mode to EVENT_WAKE. What was still missing was a transport-safe identity
contract equivalent in rigor to the USER_REQUEST continuation path.

Reusing the Phase649/650 user-continuation envelope directly was rejected because EVENT_WAKE has
additional authority-relevant provenance: the trigger id, source, observation time, and payload
digest. Hiding that provenance behind the user continuation format would make stale or substituted
trigger wakes harder to reject.

## EVENT_WAKE envelope

`AmperAgentEventWakeEnvelope` binds:

- canonical task id;
- exact persistent sovereign plan id;
- complete `AmperAgentTrigger` provenance;
- normalized durable task state;
- completed/total step counts;
- exact SHA-256 of the canonical persisted `SovereignPlanCodec` representation;
- exact waiting-approval step when present;
- checkpoint time.

Active READY/RUNNING state is normalized to CHECKPOINTED. WAITING_APPROVAL stays explicitly blocked.
Completed/failed plans become terminal no-op envelopes.

## Verified handoff

`AmperAgentEventWakeHandoffPolicy` encodes and hashes the envelope and derives a canonical
disposition:

- READY_FOR_EXPLICIT_ADVANCE;
- WAITING_GOVERNED_APPROVAL;
- TERMINAL_NOOP.

The handoff is non-authoritative. It has no tool, planner, Android Context, JobScheduler, AlarmManager,
receiver, or monitor handle.

Verification recomputes the envelope digest, decodes the bounded envelope, reconstructs the canonical
handoff, and requires identity equality for task, plan, trigger, disposition, and dedupe key.

## Restore semantics

`AmperAgentProactiveEventWakeCoordinator` restores only when:

- admission origin remains PROACTIVE_TRIGGER;
- background mode remains EVENT_WAKE;
- exact trigger provenance equals the admitted trigger;
- persistent plan goal still equals the admitted objective;
- every plan capability remains inside the admitted capability envelope;
- the current persisted plan SHA-256 equals the checkpoint digest;
- durable state, completed-step count, total-step count, and approval step still match.

Restore performs zero plan advances and zero tool calls.

A WAITING_APPROVAL restore is explicitly non-runnable. Phase655 therefore cannot convert an observed
event into implicit side-effect approval.

## Duplicate wake replay hardening

Audit of the superseded Phase653 branch found one useful concern: two concurrent verified wakes could
race between restore and one-step advance.

Phase655 retains the merged Phase653 architecture but adds one neutral
`AmperAgentCanonicalWakeExecutionGate`. The existing Android USER_REQUEST continuation execution
port now serializes the exact restore → one-step advance transaction through this gate.

The durable plan digest remains the replay authority. With serialization, the first duplicate wake
may advance once; the second observes the changed durable plan digest and fails before another
advance.

Future EVENT_WAKE execution must reuse this same gate rather than introducing a second executor lock.

## Canonical runtime graph

The Phase653 process-shared Android graph now exposes one
`AmperAgentProactiveEventWakeCoordinator` built over the same `PersistentSovereignAgentPlanPort`
already used by passive and proactive tasks.

No second planner, plan store, ToolFabric, AuthorityGate, model route, or inference endpoint is added.

## Tests

Phase655 adds focused coverage for:

- trigger + exact plan digest round-trip;
- WAITING_APPROVAL non-runnable semantics;
- stale durable plan rejection;
- trigger provenance drift rejection;
- transport tamper rejection;
- USER_REQUEST rejection at the EVENT_WAKE boundary;
- concurrent duplicate USER_REQUEST wake serialization.

## Architecture lock

Phase655 adds:

`proactive-event-wakes-bind-trigger-and-exact-durable-plan-state`

`canonical-agent-wake-execution-is-serialized-against-duplicate-replay`

## Next M5 slice

Phase656 should add the Android EVENT_WAKE consumption boundary over this verified contract.

That host may translate a qualified OS/event source into one explicit wake consumption, but it must:

- verify the Phase655 handoff before acquiring execution;
- restore the exact proactive admission/checkpoint;
- reuse the same canonical wake execution gate and persistent sovereign-plan engine;
- advance at most one step;
- never auto-run WAITING_APPROVAL;
- emit a fresh verified EVENT_WAKE handoff when more work remains;
- avoid creating a second monitor-specific planner, tool path, or inference runtime.
