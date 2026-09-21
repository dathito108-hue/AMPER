# Phase663 — Governed Proactive Attention Routing

Phase663 makes Phase662 proactive lifecycle states visible to the user without creating a new
execution, approval, planning, scheduling, or authority path.

The canonical chain remains:

```
bounded proactive trigger
  -> deterministic persistent plan
  -> immutable lifecycle provenance
  -> Phase655 verified EVENT_WAKE
  -> Phase657 scheduler
  -> Phase656 one-step execution
  -> Phase662 canonical lifecycle view
  -> Phase663 read-only attention signal
```

Attention is downstream of canonical state. It never changes that state.

## Pure attention policy

`AmperAgentProactiveAttentionPolicy` consumes only
`AmperAgentProactiveTaskLifecycleView`.

It produces external attention only for:

- `WAITING_APPROVAL` → governed review required;
- `COMPLETED` → terminal success;
- `FAILED/CANCELLED` → terminal non-success.

`ADMITTED`, `READY`, `RUNNING`, and `CHECKPOINTED` produce no notification signal. This keeps
normal background progress quiet and prevents notification polling/spam.

Missing canonical plans also produce no external notification. The existing Phase662 lifecycle panel
continues to show the fail-closed diagnostic.

## Privacy-preserving notification content

The notification signal contains only:

- canonical plan identity internally;
- attention kind;
- stable SHA-256 attention fingerprint;
- waiting step index internally when approval is required.

User-visible notification copy is generic. It does not include:

- goal;
- prompt/input;
- capability;
- trigger source/configuration;
- trigger payload digest;
- tool/model/backend details;
- approval payload.

The exact request remains visible only after the user opens AMPER and inspects the existing governed
plan console.

## No direct authority

Android notifications have no approve, reject, execute, retry, or tool action buttons.

Tapping a notification opens `SafeLauncherActivity` only. The safe launcher explicitly states that
the attention signal did not approve or execute anything. The user must deliberately open full
AMPER, locate the proactive lifecycle entry, and use the existing governed plan approval surface.

No notification receiver or action service is introduced.

## Android permission model

Because the app targets Android 35, Phase663 declares `POST_NOTIFICATIONS`.

Permission is requested only after an explicit user tap in the proactive lifecycle panel. Background
JobServices never request permission.

If permission is denied or Android notifications are disabled:

- canonical proactive planning continues;
- persistent checkpointing continues;
- EVENT_WAKE scheduling/execution continues;
- WAITING_APPROVAL remains durable;
- the in-app lifecycle panel remains the attention fallback;
- no attention fingerprint is marked delivered.

If the user later grants permission, foreground reconciliation can surface the still-current signal.

## Bounded delivery metadata

`AndroidAgentProactiveAttentionDelivery` stores only transport metadata in a dedicated
SharedPreferences file:

```
hashed-plan-key -> stable notification id + delivered attention fingerprint
```

This metadata is not task state and does not contain goals, inputs, trigger payloads, capabilities,
approval state, or plan state.

The delivery fingerprint is deterministic from canonical plan id + attention kind + canonical state
(+ waiting step for approval).

Consequences:

- same lifecycle state is suppressed after one delivery;
- state transition changes the fingerprint;
- CHECKPOINTED/no-signal cancels a stale notification and clears its transport checkpoint;
- terminal state can replace an earlier approval notification under the same stable plan identity;
- stale delivery checkpoints are pruned against the bounded Phase662 lifecycle set;
- notification permission suppression does not write a delivered checkpoint.

Delivery failure is never part of task/job success criteria.

## Routing points

Phase663 reuses existing hosts instead of adding a scheduler.

Attention reconciliation occurs:

1. after Phase661/662 pending dispatch has durably completed;
2. after an existing Phase656 EVENT_WAKE advances to CHECKPOINTED, WAITING_APPROVAL, or terminal;
3. during foreground lifecycle reconciliation;
4. after the existing governed plan approve/reject surface durably resolves a proactive step;
5. after plan cancellation/recovery mutations already handled by the canonical plan surface.

All calls are read-only with respect to plan/task authority. Background delivery errors are isolated
from the EVENT_WAKE result.

## UI

`ProactiveTaskLifecyclePanel` remains the authoritative user-visible lifecycle browser.

Phase663 adds:

- notification status;
- an explicit “Enable proactive notifications” button only when Android runtime permission is
  required;
- a clear fallback message when notifications are disabled in Android settings.

No approval action is moved into the notification or attention layer.

## Architecture locks

Phase663 adds:

- `proactive-attention-is-read-only-and-never-authorizes-or-executes`
- `proactive-attention-surfaces-only-approval-or-terminal-state`
- `proactive-notifications-have-no-direct-approval-or-execution-actions`
- `proactive-attention-delivery-state-is-transport-metadata-not-task-state`
- `proactive-attention-reuses-lifecycle-and-adds-no-scheduler`
- `notification-permission-never-blocks-canonical-task-state`
- `proactive-attention-content-excludes-goal-input-and-trigger-payload`

M5 exit criteria are extended with the same constraints.

## Tests

Phase663 unit coverage verifies:

- CHECKPOINTED work produces no external attention;
- missing canonical plans produce no external attention;
- WAITING_APPROVAL produces a stable governed-review signal;
- generic notification copy does not expose the goal or trigger payload digest;
- terminal success/failure changes fingerprint and meaning;
- same fingerprint is duplicate-suppressed;
- no signal produces cancellation;
- permission-denied changed signals remain undelivered and can be delivered after permission is
  available;
- notification identity is deterministic and plan-specific;
- Omega architecture locks and M5 exit criteria.

## Next M5 slice

Phase664 should harden proactive attention navigation and lifecycle UX without moving authority into
notifications: safe deep-link selection of the tracked plan, bounded unread/read presentation, and
user-facing diagnostics for missing-plan/recovery states should still terminate at the existing
governed plan console.
