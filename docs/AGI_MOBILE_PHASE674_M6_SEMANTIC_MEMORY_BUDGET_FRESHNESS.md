# Phase674 — M6 Semantic Memory Budget / Freshness

Phase674 hardens the existing MemoryBackedSemanticKnowledgeStore. It does not add a semantic database,
embedding index, model-owned memory, or second consolidation path.

## Mobile budgets

SemanticKnowledgePolicy defaults:

- max query/reconcile results: 16;
- candidate semantic record scan: minimum 48, maximum 192;
- scan multiplier: 12;
- maximum freshness validations: 64.

Requests outside the result budget fail immediately.

## Freshness gate

Stored ACTIVE semantic knowledge is not automatically current forever.

current() and query() now require the latest epistemic assessment to remain:

- planning-eligible;
- equal in preferred value;
- equal in evidence-id lineage.

If evidence becomes stale, contested, uncertain, retracted, or gains new supporting evidence, the old
semantic version is hidden from read/planning views until reconcile() creates the appropriate revision
or durable retraction.

This reuses the Phase186/187 EpistemicState freshness and contradiction logic rather than inventing a
second freshness clock.

## Corruption

Records of semantic-knowledge or semantic-retraction kind are decoded strictly. Corrupt canonical
semantic records fail visible instead of being silently dropped with mapNotNull.

## Architecture

Semantic persistence format is unchanged. Versioning/supersedes/evidence parents remain unchanged.
Semantic memory is still descriptive evidence only and carries no approval/tool/execution authority.

Base: main@3642da55f6d84e71d3672779d2449ae6ebca4014.

## Next M6 slice

Phase675 should harden procedural skill/strategy retrieval budgets and qualification freshness across
the existing skill genesis/generalization/repair strategy stores, without creating a second
procedural-memory system or allowing learned procedure evidence to bypass ToolFabric authority.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
