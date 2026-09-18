# Sovereign Plan Recovery Guard — Phase 19

Phase 19 treats every persisted plan as stale execution data until it is revalidated against the live governed tool fabric.

`SovereignPlanCoordinator.advance()` and `approve()` now call `SovereignPlanRecoveryGuard` before crossing the action boundary. This applies whether the plan was just created or restored from the encrypted Phase 18 Plan OS.

## Revalidation rules

Before execution, AMPER verifies:
- action request ids are unique inside the plan
- there is at most one simultaneous approval checkpoint
- no terminal/executed step appears after an earlier still-active step
- a pending approval is the next active step
- the capability is still in the planner's explicit whitelist
- a live provider still exists
- the persisted input still satisfies the provider's current typed input contract

Before replaying an approval after restart, AMPER additionally verifies:
- the recorded governed outcome is still `REQUIRES_CONFIRMATION`
- the stable request id still matches
- the live provider has the same `ToolId` recorded when the step was blocked
- the provider has the same side-effect class recorded by the original gate decision
- the capability has not unexpectedly become read-only

Any mismatch fails closed before `AuditedToolFabric` or the provider is invoked. There is no automatic migration of an approval to a new provider because that would turn an old user decision into authorization for a different action implementation.

The Phase 17/18 execution bound remains unchanged: one `advance()` processes at most one step, and no background or recursive tool loop is introduced.
