# Phase654 — M5 Proactive Trigger Persistent-Plan Binding

Phase654 activates the PROACTIVE_TRIGGER branch that Phase647 already locked into the canonical
Agent task contract. It does not add a second autonomous planner or Android background stack.

## Audit result

The canonical task contract already requires:

- origin = PROACTIVE_TRIGGER;
- explicit trigger id/source/observation time/payload digest;
- background mode = EVENT_WAKE;
- checkpoint-required execution;
- external ToolFabric/AuthorityGate authority and audited execution.

Phases648–653 intentionally implemented only USER_REQUEST execution. The existing
AmperAgentPassiveTaskCoordinator rejected PROACTIVE_TRIGGER tasks, so proactive work had no
canonical persistent-plan execution binding yet.

AMPER already has persistent goals and a process-resident AutonomousGoalScheduler, but creating a
second trigger-specific planner/executor would duplicate authority and execution semantics. Phase654
therefore reuses the Phase648 sovereign-plan port directly.

## One persistent-plan task engine

AmperAgentPassiveTaskCoordinator and the new AmperAgentProactiveTaskCoordinator now delegate to one
shared implementation contract:

AmperAgentPersistentPlanPort
→ PersistentSovereignPlanCoordinator
→ existing SovereignPlanCoordinator
→ existing SovereignActionLoop
→ audited ToolFabric
→ DenyByDefaultAuthorityGate.

No new planner, plan store, ToolFabric, approval path, model route, or inference endpoint is created.

The Phase648 checkpoint/result data structures remain the durable plan-bound representation.
Phase654 adds neutral aliases for proactive use rather than creating a second task persistence format.

## Proactive admission invariants

A proactive coordinator call fails closed unless:

- task origin is PROACTIVE_TRIGGER;
- Phase647 admission still resolves to EVENT_WAKE;
- explicit trigger provenance remains present;
- plan goal exactly equals the admitted task objective;
- every planned capability stays inside the admitted capability envelope;
- external authority and audit requirements remain enabled.

## One call, at most one plan step

The proactive coordinator calls AmperAgentPersistentPlanPort.advance exactly once per advance call.

It does not loop over plan steps. A side effect requiring confirmation returns WAITING_APPROVAL.
Neither another advance nor resumeAfterGovernedApproval succeeds until the existing governed plan
surface has actually resolved that approval.

This keeps proactive execution under the same side-effect receipt and authority rules as passive
user work.

## Canonical runtime wiring

The Phase653 AndroidCanonicalSovereignRuntimeBootstrap now constructs
AmperAgentProactiveTaskCoordinator beside the passive coordinator using the same agentPlanPort.
This is process-shared wiring only; Phase654 does not yet create an Android event source or wake host.

## Architecture lock

Phase654 adds:

proactive-trigger-tasks-reuse-canonical-persistent-plan-engine

M5 also records:

proactive EVENT_WAKE tasks reuse the same persistent sovereign-plan engine and governed approval
boundary.

## Next M5 slice

Phase655 should create a verified EVENT_WAKE checkpoint/handoff contract for proactive tasks.
It should bind trigger provenance plus the exact durable sovereign-plan digest, reject stale or
tampered trigger wakes, and preserve WAITING_APPROVAL as non-runnable.

Phase655 must not add an Android scheduler yet. The Android trigger/monitor host should consume that
verified handoff in the following slice so core checkpoint semantics can be qualified independently.
