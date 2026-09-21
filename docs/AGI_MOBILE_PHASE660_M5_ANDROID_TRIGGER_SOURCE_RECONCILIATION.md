# Phase660 — Android Trigger-Source Reconciliation

Phase660 binds the persisted Phase659 trigger-source registry to bounded Android source adapters
without adding a monitor-specific planner, tool path, model path, or polling loop.

## Source kinds remain intentionally asymmetric

### SCHEDULED_WINDOW

An enabled SCHEDULED_WINDOW may reconcile to one Android JobScheduler periodic source job.

The job:

- uses the source minimum interval, which Phase658 already bounds to at least 15 minutes;
- is persisted across reboot;
- requires battery-not-low and storage-not-low;
- uses a stable source-id-derived namespaced job id;
- carries the exact configuration SHA-256 revision;
- emits only one scheduled-window observation into the Phase659 registry.

It does not create a sovereign plan, invoke ToolFabric, load a model for source-specific inference, or
execute an Agent step.

### APP_LOCAL_EVENT

APP_LOCAL_EVENT receives no JobScheduler entry and no polling service.

The only Android-facing path is an explicit app-local event call into
AndroidAgentProactiveTriggerSourceController.observeAppLocalEvent. That event is still subject to the
persisted Phase659 cooldown/dedupe transaction.

## Reconciliation

AndroidAgentProactiveTriggerSourceController is the canonical Android registration mutation boundary.

After upsert, enable/disable, or remove it reconciles persisted source state with the source-job
scheduler.

Reconciliation:

- supports at most 32 registered sources;
- supports at most 16 scheduled-window jobs;
- cancels stale source jobs no longer represented by enabled scheduled registrations;
- uses one stable job id per source, so a configuration revision or cadence update replaces the old
  pending schedule instead of accumulating another job;
- never schedules APP_LOCAL_EVENT;
- never owns Agent execution authority.

MainActivity performs a reconciliation pass when the foreground canonical graph starts. Persisted
scheduled jobs themselves survive reboot through the existing RECEIVE_BOOT_COMPLETED contract.

## Cold scheduled-source wake

AgentTriggerSourceJobService is exported=false and protected by BIND_JOB_SERVICE.

On an OS wake it:

1. reads the persisted source id + configuration SHA token;
2. acquires the Phase653 shared canonical runtime graph;
3. reloads the source from the Phase659 encrypted registry;
4. fails closed if the source was removed, disabled, or changed to APP_LOCAL_EVENT;
5. ignores a stale configuration-revision wake without cancelling a newer replacement schedule;
6. derives one deterministic scheduled-window observation digest from source/config revision and
   actual wake time;
7. submits that observation only to Phase659 atomic acceptance;
8. finishes the source clock wake without creating an Agent execution loop.

There is no immediate retry loop. JobScheduler provides the next bounded period.

## Crash-safe accepted observation FIFO

During audit, simply accepting an observation and discarding the returned admission was rejected:
process death between source acceptance and the next canonical dispatch phase could otherwise lose
the accepted trigger permanently.

Phase660 therefore evolves the existing *same source MemoryRecord* to V2 and stores a bounded FIFO of
accepted observation control metadata:

- observation timestamp;
- payload SHA-256;
- deterministic observation identity SHA-256.

No payload body is stored. No second queue file/database is created.

The FIFO is bounded to four pending observations per source. When full, acceptance fails before
advancing the source's last-accepted checkpoint. This creates backpressure instead of silent loss.

Acknowledgement is FIFO-only and updates the same encrypted source record. The V2 codec remains
backward-compatible with Phase659 V1 source records.

While the FIFO is non-empty, the source configuration may not change and the source may not be
removed. Enabled/disabled state may still change to pause/resume delivery. This preserves the exact
configuration provenance under which every pending observation was accepted.

## Canonical graph

The shared Android Agent graph now exposes one
AndroidAgentProactiveTriggerSourceController over runtime.proactiveTriggerSources.

The controller owns only source registration/reconciliation and app-local observation acceptance.
It contains no:

- SovereignPlanCoordinator;
- ToolFabric;
- AuthorityGate;
- Titan/model endpoint;
- EVENT_WAKE execution port.

## Architecture locks

Phase660 adds:

- scheduled-trigger-sources-use-bounded-persisted-jobs-not-polling
- app-local-trigger-sources-remain-event-driven
- accepted-trigger-observations-queue-inside-the-same-source-record
- pending-trigger-observations-lock-source-revision-and-removal

## Next M5 slice

Phase661 should add the durable accepted-observation → canonical Agent dispatch binding.

It must consume the Phase660 FIFO strictly in order:

1. load the oldest pending accepted observation;
2. reconstruct/verify its exact Phase658 qualified admission against the current source revision;
3. bind it to the existing Phase654 persistent-plan coordinator;
4. create the Phase655 verified EVENT_WAKE handoff;
5. schedule only through Phase657;
6. acknowledge the FIFO entry only after the durable plan + verified handoff have been established in
   an idempotent way.

Phase661 must solve crash/retry idempotence without creating a second task database or source-specific
planner.
