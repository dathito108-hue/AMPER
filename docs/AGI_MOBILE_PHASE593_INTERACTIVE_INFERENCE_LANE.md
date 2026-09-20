# Phase593 — Dedicated Interactive Inference Lane

## Physical finding

After Phase592 removed the Android procfs handoff failure, the app could enter local inference
without crashing. A later physical run showed:

- `Stop generation` visible;
- no assistant response;
- Titan Route Observatory still reported no route planned in the current process.

That combination means the UI had created a cancellation token and queued the turn, but the turn
had not reached Titan.

## Root cause

`MainActivity` used one `Executors.newSingleThreadExecutor()` for all asynchronous UI work and
also for `reflexLifecycle.maintain()`.

At process startup the maintenance task was submitted before user inference. The same maintenance
routine was also invoked inline on the interactive executor after successful assistant turns.
A slow maintenance cycle could therefore starve Ask AMPER and the physical smoke test before route
planning even began.

## Phase593

- add `AmperExecutionLanes` with independent single-thread interactive and maintenance lanes;
- startup reflex maintenance runs only on the maintenance lane;
- post-turn reflex maintenance is submitted to the maintenance lane instead of running inline;
- all user-initiated model/import/inference/plan work stays serialized on the interactive lane;
- Ask AMPER cancels an optional outstanding model-preparation request before queueing a new turn;
- smoke-test and assistant UI distinguish `QUEUED` from `RUNNING`;
- regression test blocks the maintenance lane and proves interactive work still executes.

Titan's execution-admission, backend concurrency, resource governance, authority gates and durable
state remain authoritative. Phase593 changes scheduling only; it does not create a second inference
or planning architecture.
