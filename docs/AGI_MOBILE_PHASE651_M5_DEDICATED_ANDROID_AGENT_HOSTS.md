# Phase651 — M5 Dedicated Android Agent Continuation Hosts

Phase651 implements dedicated Android hosts for the verified continuation handoff created in
Phase650. It does not reuse the voice, screen-capture, or reflex-maintenance services because those
components own different lifecycles and capabilities.

## Foreground continuation host

FOREGROUND_CONTINUATION is routed to `AgentContinuationForegroundService`.

The service:

- starts only from the explicit Phase650 foreground handoff;
- runs with Android foreground-service type `specialUse`;
- displays a visible continuation notification;
- verifies the handoff before dispatch;
- requests at most one verified Agent Core advance;
- never calls planner, ToolFabric, AuthorityGate, approve, or approveBound directly;
- keeps WAITING_APPROVAL as a non-executing user-visible notification;
- stops after one bounded dispatch.

The notification action pauses the Android host. It does not pretend to delete the durable task.

## Persisted job host

PERSISTED_JOB is routed to `AgentContinuationJobService` using `JobScheduler`.

The scheduler:

- uses `setPersisted(true)`;
- carries the exact bounded Phase650 handoff in PersistableBundle extras;
- derives a stable job id from the task/plan dedupe key;
- therefore lets a newer checkpoint replace an older pending job for the same task/plan;
- supports independent concurrent jobs by job id;
- requests OS reschedule only when the canonical execution port is temporarily unavailable.

The app already declares RECEIVE_BOOT_COMPLETED, so Android may retain persisted jobs across reboot.

## Android 14/15 foreground-service contract

AMPER targets API 35. Android requires foreground services to declare an appropriate foreground
service type and matching permission.

Agent continuation is not microphone, media projection, media playback, location, or data transfer.
Phase651 therefore uses `specialUse` with an explicit manifest subtype describing user-requested AI
task continuation after UI exit.

The foreground path is intended to start from direct user-initiated work. Background/reboot recovery
uses the persisted JobScheduler path instead.

## Verified dispatch boundary

`AmperAgentAndroidContinuationHostDispatcher` is platform-neutral and enforces:

- transport verification before execution;
- READY_FOR_EXPLICIT_ADVANCE -> at most one execution-port call;
- WAITING_GOVERNED_APPROVAL -> zero execution-port calls;
- TERMINAL_NOOP -> zero execution-port calls;
- unavailable canonical execution port -> RETRY_LATER.

The Android components therefore remain lifecycle hosts, not execution authorities.

## Architecture lock

Phase651 adds:

`android-agent-continuation-hosts-are-dedicated-and-one-step-bounded`

## Next M5 slice

Phase652 should provide canonical Agent Core execution-port wiring and cold-process restoration:

- warm process: register the existing passive-task + continuation coordinators;
- cold persisted job: reconstruct the same encrypted runtime, persistent sovereign-plan coordinator,
  and task admission context from durable state;
- verify the exact Phase649 envelope before one-step advance;
- publish the next checkpoint/handoff when more work remains;
- never restore WAITING_APPROVAL into automatic execution.
