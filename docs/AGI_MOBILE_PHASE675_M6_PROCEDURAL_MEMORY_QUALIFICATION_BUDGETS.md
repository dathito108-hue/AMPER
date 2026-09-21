# Phase675 — M6 Procedural Memory Qualification / Retrieval Budgets

Phase675 hardens the existing procedural-memory surfaces without creating a new procedural store.

## Existing stores remain canonical

Durable procedure evidence remains in:

- MemoryBackedSkillGenesisModel;
- MemoryBackedSkillGeneralizationModel;
- MemoryBackedGoalRepairStrategyMemory.

All continue to use the same sovereign MemoryOs and their existing record/index formats.

ProceduralMemoryPolicy persists nothing. It only centralizes mobile read budgets and qualification
predicates so the three retrieval paths cannot drift apart.

## Hard budgets

- skill recent: max 64;
- skill guidance lookback: 16;
- skill guidance output: max 4;
- skill composition output: max 3;
- generalization recent: max 64;
- generalization lookback: 24;
- generalization guidance output: max 4;
- generalized chains: max 3;
- repair-strategy recent/support scan: max 32.

Oversized recent() requests fail immediately rather than expanding a memory scan.

## Qualification freshness

Skill guidance is eligible only when the current skill contract is ACTIVE, non-authority-bearing,
and every capability remains both allowed and present on the live ToolDescriptor surface.

Generalization guidance keeps its existing current-snapshot rebind: a transfer profile is usable only
when TRANSFERABLE or GENERALIZED, and the current underlying skill snapshot remains ACTIVE and live.

Repair memory keeps live requalification on every support() call. A stored pattern contributes zero
unless it is active, structural similarity is at least 0.75, and GoalRepairValidation currently
returns positive requalified transfer confidence.

No wall-clock expiry is invented because these paths already have stronger live-state qualification.

## Authority

Procedural evidence remains advisory planning data. It cannot invoke tools, approve side effects,
grant permissions or bypass Titan / ToolFabric / DenyByDefaultAuthorityGate.

Base: main@7866d8f7c68bab88587c91a7f58439fb36affd4a.

## Next M6 slice

Phase676 should audit cognitive-memory context assembly as one bounded cross-domain retrieval budget:
working, episodic, semantic and procedural evidence must fit one mobile context envelope without
duplicating records or letting one memory domain starve the others.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
