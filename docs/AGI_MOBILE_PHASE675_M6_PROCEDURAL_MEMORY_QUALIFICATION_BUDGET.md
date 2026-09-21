# Phase675 — M6 Procedural Memory Qualification / Read Budgets

Phase675 hardens the three existing procedural-memory owners without introducing a second
procedural facade, database, model store, or learning path.

## Existing canonical owners

Procedural memory remains distributed across the existing MemoryOs-backed owners:

- `MemoryBackedSkillGenesisModel`;
- `MemoryBackedSkillGeneralizationModel`;
- `MemoryBackedGoalRepairStrategyMemory`.

No record format or learning evidence is migrated.

## Bounded public reads

Public `recent()` calls are now hard-bounded to the already-canonical durable index capacities:

- SkillGenesis: 64 skill contracts;
- SkillGeneralization: 64 profiles;
- GoalRepairStrategyMemory: 32 patterns.

Requests above those limits fail instead of allowing unbounded procedural-memory reads.

## Fail-visible index integrity

Each procedural index must have its canonical record kind and a valid bounded unique list of
lowercase SHA-256 digests.

Every indexed digest must resolve to:

- an existing snapshot;
- the correct snapshot kind;
- a decodable payload;
- a strategy/signature whose digest exactly matches the index entry.

Missing, malformed, wrong-kind, duplicate, over-bound, or identity-mismatched evidence fails visible.
The previous `mapNotNull`/filter behavior no longer silently hides durable procedural corruption.

## Qualification freshness stays canonical

Phase675 does not invent another freshness score.

Skill guidance already requires:

- ACTIVE skill maturity;
- every capability on the current live ToolDescriptor surface;
- every learned precondition satisfied by current structured world state.

Generalization guidance already requires:

- TRANSFERABLE or GENERALIZED evidence;
- the exact ACTIVE base skill;
- current live ToolDescriptors;
- satisfied current preconditions;
- novel-context confidence discount where applicable.

Repair strategy support already calls
`GoalRepairValidationModel.requalifiedTransferConfidence(strategy)` on each support query. A
strategy with no current requalification contributes zero support.

## Authority boundary

Procedural memory remains advisory data only.

It cannot approve a side effect, bind a tool provider, execute a capability, bypass
`DenyByDefaultAuthorityGate`, or replace TitanPlanProtocol/live ToolDescriptor validation.

## Architecture locks

Phase675 adds:

- `procedural-memory-public-recent-reads-are-bounded`
- `procedural-memory-index-and-snapshot-corruption-fails-visible`
- `skill-guidance-requires-live-tool-and-world-qualification`
- `generalization-guidance-requires-active-skill-and-live-context`
- `repair-strategy-support-requires-live-requalification`
- `procedural-memory-reuses-existing-memory-os-stores`
- `procedural-memory-remains-advisory-and-non-authority`

## Architecture audit

Base: `main@7866d8f7c68bab88587c91a7f58439fb36affd4a`.

No native/build change. AMI2/AMNE2, Titan, ToolFabric, AuthorityGate, skill-learning admission,
generalization-learning evidence, repair-validation evidence, and M5 execution remain unchanged.

## Next M6 slice

Phase676 should unify bounded cognitive-memory retrieval into one context-budget policy across
working, episodic, semantic and procedural projections while preserving each canonical store and
preventing one memory domain from crowding out the others.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
`amper-core-arm64` SKIP.
