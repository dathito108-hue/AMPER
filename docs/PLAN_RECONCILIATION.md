# Phase 21 — Side-Effect Reconciliation OS

AMPER never automatically replays an interrupted side-effect request.

When Phase 20 has already written a durable `PlanSideEffectClaim` and the process stops before a terminal plan snapshot is safely committed, the request enters manual reconciliation.

## Invariants

1. Reconciliation invokes **zero tools** and crosses no Authority Gate execution path.
2. The original claim is never deleted or rewritten.
3. A reconciliation record is immutable and binds the plan id, step index, request id, capability, tool id, side-effect class, claim digest, decision, and reconciliation note digest.
4. Allowed decisions are only:
   - `CONFIRMED_EXECUTED`: the user verified that the previously claimed side effect actually occurred.
   - `CONFIRMED_NOT_EXECUTED`: the user verified that it did not occur.
5. `CONFIRMED_EXECUTED` closes the step as `EXECUTED` without provider replay.
6. `CONFIRMED_NOT_EXECUTED` closes the step as `FAILED`; AMPER does not silently retry the old request.
7. A different decision or note for an already-reconciled claim fails closed.
8. Repeating the exact same reconciliation is idempotent, including recovery after a crash between writing reconciliation, receipt, and the final plan snapshot.
9. Claim, reconciliation, receipt, and plan state share AMPER's Memory OS. In Android production they are stored through the AES-GCM/AndroidKeyStore encrypted journal.
10. Reconciliation never creates a background continuation, recursive tool loop, or `Run All` path.

A later user action may create a **new** plan/request if the user wants to attempt an operation again. It must receive a new `ActionRequestId` and traverse the normal authority and approval boundaries from the beginning.
