# Phase656 — Canonical EVENT_WAKE Consumer

Phase656 turns the verified Phase655 proactive handoff into one bounded canonical execution request.
It still does not add an Android scheduler, monitor, receiver, alarm, or periodic trigger source.

## One verified wake -> at most one proactive plan step

`AmperAgentCanonicalEventWakeExecutionPort` consumes only a Phase655 handoff that has already passed
`AmperAgentEventWakeHandoffPolicy.verifyAndDecode`.

For a runnable wake it:

1. restores or narrowly reconstructs the canonical PROACTIVE_TRIGGER admission;
2. restores the exact Phase655 checkpoint against the current persisted plan SHA-256;
3. enters the same `AmperAgentCanonicalWakeExecutionGate` used by user continuation;
4. calls `AmperAgentProactiveTaskCoordinator.advance` exactly once;
5. maps the new durable state;
6. emits a fresh verified EVENT_WAKE handoff only when additional work remains.

No loop advances multiple plan steps inside one wake.

## Narrow cold admission restoration

The process-local admission registry is now shared by both canonical Agent origins.

If proactive RAM admission state is absent, Phase656 reconstructs only what the exact verified wake
and durable plan already authorize:

- origin = PROACTIVE_TRIGGER;
- task id = handoff task id;
- trigger = exact Phase655 trigger provenance;
- objective = persisted plan goal;
- capabilities = exactly capabilities present in persisted plan steps;
- background mode = EVENT_WAKE.

This can only narrow the original capability envelope. The Phase655 restore still verifies trigger,
goal, capability containment, plan digest, task state, counts, and approval step before execution.

## Approval and terminal isolation

`AmperAgentEventWakeHostDispatcher` verifies the handoff before it considers any execution port or
cold fallback.

- WAITING_GOVERNED_APPROVAL returns WAITING_APPROVAL without acquiring execution infrastructure.
- TERMINAL_NOOP returns without execution.
- tampered/canonical-mismatch handoffs fail before fallback.
- only READY_FOR_EXPLICIT_ADVANCE may acquire an execution port.

A proactive step that itself reaches WAITING_APPROVAL returns no next handoff. There is no automatic
approval path.

## Android boundary

`AndroidAgentEventWakeConsumer` is the Android-facing Phase656 boundary. It accepts an already
constructed Phase655 handoff and delegates to the pure dispatcher.

Only after verification of a runnable wake may its fallback acquire:

`AndroidCanonicalSovereignRuntimeBootstrap → agent.eventWakeExecution`.

Therefore cold process restoration uses the exact same single foundation, AMI2/AMNE2, Titan,
ToolFabric, DenyByDefaultAuthorityGate, persistent planner, plan store, proactive coordinator, and
wake gate as the foreground process.

The consumer schedules nothing. A later phase may bind qualified Android/OS event sources to this
consumer without creating another execution architecture.

## Replay safety

Both USER_REQUEST continuation and EVENT_WAKE execution share
`AmperAgentCanonicalWakeExecutionGate`.

Concurrent delivery of the same old EVENT_WAKE handoff is serialized:

- the first consumer may advance one step;
- the persisted plan digest changes;
- the second consumer then fails exact restore before another plan advance.

The durable plan SHA-256 remains the replay authority; the gate only closes the process-local race.

## Tests

Phase656 covers:

- one verified proactive wake advances exactly one persistent step;
- fresh handoff contains the updated durable checkpoint;
- a step reaching governed approval stops the chain;
- approval-blocked handoff never invokes execution or cold fallback;
- tampered disposition fails before fallback;
- unavailable execution requests retry without advancing;
- duplicate concurrent EVENT_WAKE delivery advances only once.

## Architecture lock

Phase656 adds:

`verified-event-wake-consumption-advances-one-canonical-persistent-step`

`event-wake-consumer-acquires-canonical-runtime-only-after-verification`

## Next M5 slice

Phase657 should bind a bounded Android event-source/scheduling adapter to
`AndroidAgentEventWakeConsumer`.

That adapter must not own planning or tool execution. It should only:

- create/deliver Phase655 handoffs from explicitly qualified proactive trigger observations;
- use stable dedupe identity;
- schedule at Android-safe cadence;
- hand off exactly once per OS wake;
- reschedule only from a fresh Phase656 checkpoint;
- never schedule WAITING_APPROVAL or terminal handoffs;
- remain battery/thermal/resource governed.
