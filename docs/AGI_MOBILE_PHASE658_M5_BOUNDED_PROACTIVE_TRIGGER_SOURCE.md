# Phase658 — Bounded Proactive Trigger-Source Contract

Phase658 defines the first canonical source contract above the Phase657 Android EVENT_WAKE scheduler.
It does not add a polling loop, receiver, service, AlarmManager path, planner, tool executor, or model
runtime.

## Allowed source kinds

The source contract intentionally contains only:

- SCHEDULED_WINDOW — a user-configured finite scheduled observation with a minimum 15 minute cadence;
- APP_LOCAL_EVENT — an explicit app-local event with a minimum 60 second cooldown.

There is no generic POLL, CONTINUOUS, SENSOR_LOOP, or high-frequency monitor source kind.

## User-configured source identity

AmperAgentProactiveTriggerSource binds:

- stable source id;
- stable configuration id;
- exact configuration SHA-256 revision;
- source kind;
- admitted objective;
- bounded capability envelope;
- expected runtime;
- minimum source interval;
- enabled state.

Phase658 requires userConfigured=true at construction. Expected runtime is capped at 10 minutes for
this mobile trigger-source layer.

These fields describe eligibility only. They do not grant ToolFabric or AuthorityGate permission.

## Observation identity

AmperAgentProactiveTriggerObservation contains only:

- exact source id;
- observation timestamp;
- payload SHA-256.

The payload itself is not copied into the Agent control contract. A deterministic observation
identity SHA-256 is derived from source identity, configuration revision, source kind, timestamp, and
payload digest.

Identical source/config/observation inputs therefore generate the same task identity. Changing the
configuration revision or observed payload changes the identity.

## Cooldown qualification

AmperAgentProactiveTriggerSourcePolicy.qualify accepts an optional last accepted observation time.

When supplied, the new observation must be at least source.minimumIntervalMs later. A burst inside
the cooldown fails closed and creates no Agent admission.

This is a pure contract check. Durable source state is not introduced in Phase658; persistence of
source registrations/cooldown state belongs to the next slice.

## Canonical Agent admission

A qualified observation creates exactly one AmperAgentTaskRequest:

- origin = PROACTIVE_TRIGGER;
- task id = deterministic bounded observation identity;
- objective/capabilities = the user-configured source envelope;
- mustSurviveUiExit = true;
- canBeDeferred = true;
- trigger id = source id;
- trigger provenance = user-configured source kind + hashed configuration identity/revision;
- trigger observation time/payload digest = exact observation provenance.

The existing AmperAgentTaskAdmissionPolicy must resolve this request to EVENT_WAKE.

The result preserves:

- checkpointRequired = true;
- toolAuthorityRemainsExternal = true;
- auditRequired = true.

Phase658 performs zero planning, zero tool calls, zero Android scheduling, and zero approval.

## Relationship to Phases654–657

The flow is now:

bounded user-configured source observation
→ Phase658 qualification
→ canonical PROACTIVE_TRIGGER / EVENT_WAKE admission
→ Phase654 proactive persistent-plan coordinator
→ Phase655 verified trigger + durable-plan handoff
→ Phase657 governed Android JobScheduler adapter
→ Phase656 verified one-step EVENT_WAKE consumer.

Every later layer therefore receives explicit source provenance without creating a source-specific
planner, ToolFabric, AuthorityGate, or inference path.

## Tests

Phase658 covers:

- scheduled source → canonical EVENT_WAKE admission;
- app-local event source and exact allowed source-kind set;
- cooldown burst rejection and boundary acceptance;
- scheduled-window minimum cadence;
- app-local minimum cooldown;
- disabled/non-user-configured rejection;
- mismatched source observation rejection;
- deterministic observation/task identity;
- configuration revision identity rotation;
- bounded mobile trigger runtime.

## Architecture lock

Phase658 adds:

proactive-trigger-sources-are-user-configured-bounded-and-non-polling

qualified-trigger-observations-enter-canonical-event-wake-admission

## Next M5 slice

Phase659 should persist user-configured trigger-source registrations and last accepted observation
state in canonical encrypted sovereign storage.

That store should:

- persist only source configuration/control metadata, not a second task/plan database;
- use configuration SHA-256 revision binding;
- atomically dedupe accepted observations;
- survive process death/reboot;
- expose enabled/disabled source reconciliation;
- never persist or manufacture tool authority;
- feed accepted observations only into the Phase658 qualification → Phase654/655/657 canonical path.

Phase659 should still avoid continuous high-frequency polling.
