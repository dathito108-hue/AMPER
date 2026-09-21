# Phase658 — Bounded Proactive Trigger-Source Contract

Phase658 introduces the first canonical source contract above the Phase657 EVENT_WAKE scheduler.
It does not add a polling thread, broadcast receiver, alarm loop, WorkManager chain, planner, tool
executor, or model path.

## Audit result

AMPER already has two different autonomous mechanisms:

- the older process-resident goal scheduler, which drives persistent goals while the app process is
  alive;
- the new M5 Agent EVENT_WAKE path, which carries explicit proactive trigger provenance through one
  persistent sovereign plan and Android JobScheduler.

Phase658 does not merge these into a new autonomous executor. Instead it defines the missing boundary
that turns one finite user-configured observation into the existing canonical PROACTIVE_TRIGGER task
contract.

## Finite user-configured source

`AmperAgentProactiveTriggerSourceDefinition` describes control data only:

- stable trigger id and source;
- source kind: SCHEDULED_WINDOW or APP_LOCAL_CONDITION;
- admitted objective and capability envelope;
- bounded expected runtime;
- finite active-from / expires-at window;
- minimum observation interval;
- bounded maximum firing count;
- user configuration time.

Hard bounds:

- active lifetime <= 30 days;
- cadence >= 15 seconds;
- firing count <= 32;
- SCHEDULED_WINDOW is exactly one-shot;
- expected task runtime <= 6 hours.

These bounds make the contract unsuitable for hidden high-frequency continuous polling by design.

## One-shot observation

`AmperAgentProactiveTriggerObservation` carries only:

- trigger id;
- source;
- observed time;
- SHA-256 payload identity.

The raw observed payload does not cross the Agent trigger boundary.

Phase658 receives observations as one-shot input. It does not contain any API that starts a loop,
sleeps, reschedules itself, accesses Android Context, or repeatedly polls a provider.

## Qualification

`AmperAgentProactiveTriggerSourcePolicy.qualify` fails closed unless:

- observation id/source exactly match the configured source;
- observation time is inside the finite configured window;
- the firing budget is not exhausted;
- the payload digest has not already been consumed;
- time never moves backwards;
- the configured minimum cadence has elapsed.

A successful observation is narrowed into exactly one Phase647 task request:

`PROACTIVE_TRIGGER -> EVENT_WAKE -> checkpoint required`.

The request inherits the configured objective and exact capability envelope. Source qualification
cannot add a capability, approve a side effect, choose a model, call ToolFabric, or execute a plan.

Task identity is deterministic from trigger/source/time/payload SHA-256 so one observation has a
stable Agent task identity.

## Bounded source state

`AmperAgentProactiveTriggerSourceState` contains only replay/cadence accounting:

- fire count;
- last fired time;
- bounded set of consumed payload digests.

It carries no authority and no execution state.

Phase658 intentionally keeps this state representation pure. It is not yet an Android or persistent
registry.

## Canonical plan/handoff bridge

`AmperAgentProactiveTriggerStartCoordinator` accepts only an already qualified observation.

It performs exactly:

1. one call to the existing Phase654 `AmperAgentProactiveTaskCoordinator.start`;
2. creation of one persistent sovereign plan through the same
   `PersistentSovereignAgentPlanPort`;
3. one Phase655 verified EVENT_WAKE handoff.

It advances zero plan steps and invokes zero tools.

The Phase653 shared runtime graph exposes this coordinator beside the already canonical proactive
task and EVENT_WAKE coordinators. It adds no planner/store/executor.

## Why Phase658 does not install a live source host yet

A live trigger host must durably commit source replay state together with activation of the first
persistent plan/handoff. Otherwise a crash between “observation consumed” and “job scheduled” could
lose work, while the reverse order could duplicate work.

Phase658 therefore locks the source semantics first rather than shipping a non-atomic monitor.

## Tests

Phase658 covers:

- scheduled-window source is one-shot;
- high-frequency source definitions are rejected;
- successful observation becomes canonical PROACTIVE_TRIGGER / EVENT_WAKE admission;
- observations outside the finite window are rejected;
- duplicate payload replay is rejected;
- minimum cadence is enforced for distinct observations;
- first start creates one persistent plan plus verified handoff with zero plan advances;
- a trigger source cannot widen its configured capability envelope.

## Architecture lock

Phase658 adds:

`proactive-trigger-sources-are-user-configured-finite-and-non-polling`

`trigger-observations-only-narrow-into-canonical-event-wake-admission`

## Next M5 slice

Phase659 should add a durable trigger-source registry and activation transaction.

It should persist:

- the user-configured finite source definition;
- replay/cadence state;
- activation identity for the first persistent Agent plan/handoff.

The transaction must make crash recovery idempotent before any Android source host is enabled.
It must not persist execution authority, and it must continue to route actual work through the same
Phase654-657 canonical Agent path.
