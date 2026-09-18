# Titan Cortex Phase 26 — Bound Planner Approval Execution

Phase 26 closes the provider-binding race for explicitly approved persistent plan steps.

## Invariants

1. **The approval checkpoint is the binding source.**
   - A side-effect step may be approved only when its persisted governed outcome is `REQUIRES_CONFIRMATION`.
   - The checkpoint must contain the exact `ToolId`, request id and side-effect class originally presented for approval.

2. **Recovery validation returns an execution binding, not only a boolean.**
   - `SovereignPlanRecoveryGuard.approvalBinding()` validates plan ordering, capability whitelist, live input contract, `ToolId`, side-effect class and request-id binding.
   - It returns `ApprovedToolBinding(toolId, sideEffect)` only after all checks pass.

3. **Planner approval carries that exact binding into the action layer.**
   - `SovereignPlanCoordinator.approve()` calls `SovereignActionLoop.approveBound()` with the guard-produced binding.
   - It never falls back to capability-only `approve()` for an explicit plan approval.

4. **BoundToolFabric independently verifies the live provider at invocation time.**
   - The fabric resolves the provider immediately before authorization/execution.
   - If `ToolId` or `ToolSideEffect` changed after guard validation, execution terminalizes as `DENIED` and neither old nor replacement provider runs.
   - This closes the guard-to-fabric TOCTOU window.

5. **Persistent side-effect claims still precede provider execution.**
   - `PersistentSovereignPlanCoordinator` performs preflight validation, writes the durable claim, then delegates to the bound approval path.
   - A binding race detected after the claim receives a terminal denied receipt, so it does not remain unresolved recovery debt.

6. **No replay or autonomous continuation is introduced.**
   - One approval processes exactly one checkpoint.
   - There is no automatic retry, provider replay, recursive tool loop, Run All path or background continuation.

## Expected race behavior

When the registry presents the reviewed provider during recovery validation but swaps to a capability-compatible replacement before fabric invocation:

- durable claim: present;
- Authority audit: denied;
- original provider invocation: zero;
- replacement provider invocation: zero;
- terminal receipt: present with denied outcome;
- unresolved recovery debt: false.
