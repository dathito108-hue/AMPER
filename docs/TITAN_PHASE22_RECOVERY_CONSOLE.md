# Titan Cortex Phase 22 — Android Recovery Console

Phase 22 exposes Phase 21 manual side-effect reconciliation in the Android UI without adding any provider replay path.

## Invariants

- Recovery is manual and fail-closed.
- The console only displays durable unresolved side-effect claims that still bind to a persisted plan and pending step.
- A missing/stale/mismatched plan causes the console to block instead of hiding or auto-fixing the claim.
- The user must provide a non-blank verification note (maximum 512 characters).
- The only decisions are `CONFIRMED_EXECUTED` and `CONFIRMED_NOT_EXECUTED`.
- Neither decision invokes the provider, retries the old request, or advances more than the reconciled step.
- The original claim remains immutable evidence.
- Reconciliation, terminal receipt, and updated plan snapshot remain in the existing encrypted Memory OS journal on Android.
- No Run All, automatic replay, recursive tool loop, or background continuation is introduced.

## UI flow

1. Open **Recovery Console** below Persistent Sovereign Plan.
2. Refresh unresolved claims.
3. Select a claim and verify its plan, step, capability, tool, side-effect class, and request id.
4. Independently verify the real device/external state.
5. Enter a verification note.
6. Choose **Mark confirmed executed** or **Mark confirmed not executed**.
7. AMPER persists the reconciliation + receipt + updated plan without calling the original provider.
