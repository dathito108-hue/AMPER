# Titan Cortex Phase 23 — Canonical Recovery State

Phase 23 defines the single canonical meaning of recovery debt before any global approval interlock is introduced.

## Recovery-state invariant

A durable side-effect claim is **unresolved** only while both of these are absent:

1. a terminal execution receipt for the same `planId + requestId`, and
2. a manual claim reconciliation for the same `planId + requestId`.

The original claim remains immutable evidence even after the action is resolved. Therefore claim existence alone must never keep AMPER in recovery mode.

## Required behavior

- Claim + no receipt + no reconciliation => unresolved recovery debt.
- Claim + matching terminal receipt => resolved; do not show it in Recovery Console.
- Claim + matching reconciliation => resolved; do not show it as pending recovery debt.
- Resolved claims remain stored in encrypted Memory OS for provenance/audit.
- Recovery Console consumes `SovereignRecoveryState`, not raw claim records.
- No provider retry, action replay, Run All path, recursive loop, or background continuation is introduced.

This layer must be correct before Phase 24 can safely block new side-effect approvals while recovery debt exists.
