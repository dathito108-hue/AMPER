# Phase650 — M5 Android Continuation Handoff Contract

Phase650 defines the verified handoff between Phase649 continuation state and Android execution
components without giving Agent Core ownership of Context, Service, JobScheduler, planner, or tools.

## Routing

- FOREGROUND_CONTINUATION -> VISIBLE_FOREGROUND_SERVICE
- PERSISTED_JOB -> PERSISTED_JOB_SCHEDULER

The handoff records whether visible notification or reboot persistence is required.

## Transport integrity

The exact Phase649 envelope is encoded into a bounded payload (max 64 KiB), SHA-256 bound, and
identity-bound to task id, plan id, host route, and a deterministic dedupe key.

Android hosts must call verifyAndDecode before restoring any task state.

## Wake disposition

The handoff never means "execute now".

It carries one of:

- READY_FOR_EXPLICIT_ADVANCE
- WAITING_GOVERNED_APPROVAL
- TERMINAL_NOOP

WAITING_APPROVAL can therefore never become automatic side-effect execution after UI exit or process
restart. Terminal work is a no-op, preventing replay.

## Architecture boundary

Phase650 adds:

`android-continuation-handoff-is-verified-and-non-authoritative`

No Android component is added in this phase because the existing foreground services are specialized
for voice/screen capture and the existing JobService is specialized for reflex maintenance. Reusing
those components would mix lifecycle authority and create hidden coupling.

## Next M5 slice

Phase651 should implement dedicated Android Agent continuation hosts:
- a visible foreground service for FOREGROUND_CONTINUATION;
- a persisted JobScheduler JobService for PERSISTED_JOB.

Both hosts must consume only the Phase650 verified handoff and delegate restoration/execution back to
the canonical Agent Core coordinators.
