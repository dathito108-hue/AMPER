# Verified Execution Receipts — Phase 20

Phase 20 binds persisted plan progression to durable execution evidence stored in AMPER's existing sovereign Memory OS.

## Terminal receipts

Every plan step that becomes terminal through `PersistentSovereignPlanCoordinator` receives an immutable receipt keyed by the plan id and stable `ActionRequestId`. The receipt binds:
- plan id and step index
- request id and capability
- SHA-256 of the reason and typed input
- terminal plan status and governed action status
- ToolId when a tool was selected
- SHA-256 of bounded output/detail when present
- a SHA-256 receipt fingerprint over the canonical bound fields

Before AMPER advances beyond completed steps, the Plan OS recomputes the expected receipt from the restored plan and fails closed if a receipt is missing or mismatched. This prevents a persisted plan from silently changing a prior request, result, tool binding or status while retaining the same step identity.

SHA-256 is used here as deterministic binding, not as a standalone signature. Authenticity and tamper detection at rest continue to come from the Phase 10 AES-GCM encrypted journal and AndroidKeyStore key.

## Side-effect claim before execution

For an explicitly approved `LOCAL_STATE` or `EXTERNAL` step, AMPER writes a durable claim before calling the provider. The claim binds the request, capability, ToolId, side-effect class and hashes of reason/input.

If the process stops after that claim but before a final receipt and plan snapshot are committed, the claim remains. A later approval attempt detects the existing claim and refuses automatic replay with `manual reconciliation required`. This intentionally prefers a potentially blocked step over accidentally performing a side effect twice.

Read-only steps do not need a pre-execution claim because repeating a correctly classified read-only operation does not mutate state; they still receive terminal receipts after processing.

## Execution bound

Phase 20 does not add a Run All path, background continuation or recursive tool loop. The existing one-action-per-advance rule and explicit approval gate remain unchanged.
