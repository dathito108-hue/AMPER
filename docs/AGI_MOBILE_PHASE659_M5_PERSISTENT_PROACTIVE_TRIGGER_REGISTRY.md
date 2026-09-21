# Phase659 — Persistent Proactive Trigger Registry / Atomic Observation Dedupe

Phase659 persists the bounded user-configured trigger-source contract from Phase658 without adding a
second task database, planner, monitor loop, tool path, or inference runtime.

## Audit result

Phase658 already defines all trigger-source authority-neutral configuration:

- stable source/configuration identity;
- configuration SHA-256 revision;
- finite source kind;
- objective and capability envelope;
- expected runtime;
- minimum cadence/cooldown;
- enabled state;
- deterministic observation identity.

The missing canonical state was registration persistence and durable cooldown/dedupe across process
death or reboot.

Creating a separate SQLite/file registry was rejected because Android production already owns the
encrypted sovereign MemoryOs journal. A second persistence substrate would duplicate durability,
migration, encryption, crash recovery, and identity semantics.

## One encrypted sovereign record per source

`MemoryBackedAmperAgentProactiveTriggerSourceRegistry` stores one MemoryRecord for each source id.

That one record contains:

- the complete Phase658 source configuration;
- exact configuration revision SHA-256;
- enabled/disabled state;
- last accepted observation timestamp;
- last accepted deterministic observation identity SHA-256;
- monotonic update timestamp.

On Android this registry is constructed inside `AmperRuntime.persistentEncrypted`, so it inherits
the existing AES-GCM, chained journal, head anchor, crash recovery, key rotation, and process-safe
journal mutation path.

No payload body is stored. Observation control state retains only timestamps and hashes.

## Atomic observation acceptance

`accept(observation)` runs under the canonical MemoryOs transaction boundary:

1. load the exact registered source;
2. require it is enabled;
3. run the Phase658 source qualification using the persisted last-accepted timestamp;
4. reject duplicate observation identity;
5. create the canonical PROACTIVE_TRIGGER / EVENT_WAKE admission;
6. update last-accepted timestamp and identity;
7. overwrite the same source MemoryRecord once.

Dedupe and cooldown therefore cannot diverge across two separate logical records. Concurrent accepts
inside the canonical process serialize on the same MemoryOs and only one identical observation can
succeed.

The encrypted journal itself retains its existing cross-process append/head locking. AMPER Android
does not introduce a second application process for Agent execution; cold JobService bootstrap
reconstructs the same canonical runtime after process death.

## Configuration revision behavior

Updating a source may change the configuration SHA-256 revision while retaining the same stable
source id, configuration id, and source kind.

Revision changes do **not** reset the last-accepted timestamp or dedupe checkpoint. This prevents a
configuration edit from creating an artificial burst window.

Changing configuration id or source kind under the same source id fails closed; such a semantic
identity change requires a new source id.

## Enabled-state reconciliation

The registry exposes:

- upsert;
- get/list;
- enable/disable;
- remove;
- atomic accept.

Disabling a source preserves its last accepted observation state. Re-enabling it therefore does not
erase cooldown or replay protection.

This registry owns no Android scheduling. A later reconciliation layer may inspect enabled sources,
but only an accepted observation may continue into the existing canonical path:

persistent registry acceptance
→ Phase658 qualified PROACTIVE_TRIGGER admission
→ Phase654 persistent sovereign plan
→ Phase655 verified EVENT_WAKE handoff
→ Phase657 governed JobScheduler
→ Phase656 one-step canonical consumer.

## Runtime wiring

`AmperRuntime` now exposes `proactiveTriggerSources`.

The Phase653 process-shared Android Agent graph exposes the same runtime-owned registry as
`agentTriggerSources`. It does not instantiate another registry.

## Tests

Phase659 covers:

- source/configuration + accepted observation survive reopening the journal;
- duplicate observation rejection;
- cooldown rejection without checkpoint mutation;
- configuration revision preserves cooldown/dedupe and rotates future observation identity;
- disable/re-enable preserves replay state;
- concurrent identical accepts produce exactly one success;
- source id cannot silently change configuration id or kind;
- canonical runtime wiring, list, and removal.

## Architecture lock

Phase659 adds:

`proactive-trigger-registrations-live-in-encrypted-sovereign-memory`

`accepted-trigger-observations-are-atomically-deduped-with-source-state`

## Next M5 slice

Phase660 should reconcile persisted enabled trigger sources with bounded Android source adapters.

It must remain source-specific and non-polling:

- SCHEDULED_WINDOW may install only Android-safe finite scheduled observation jobs;
- APP_LOCAL_EVENT remains event-driven and must not gain a background polling loop;
- disabled/removed/revised registrations must cancel or replace stale scheduled source work;
- any observation must first pass the Phase659 atomic registry acceptance;
- only the resulting canonical Phase658 admission may create a persistent plan / EVENT_WAKE handoff;
- no source adapter may own ToolFabric, AuthorityGate, planner, model, or inference execution.
