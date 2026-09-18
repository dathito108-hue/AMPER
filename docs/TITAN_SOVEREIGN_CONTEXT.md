# Titan sovereign context grounding

Phase 9 connects Titan inference to AMPER's sovereign cognitive state without moving identity or memory into the model backend.

## Flow

1. The sovereign kernel observes the user intent and updates Self/Goal/World/Memory/Workspace.
2. `SovereignContextSource` captures bounded relevant memory, active goals, world facts, recent workspace events, and the current self snapshot.
3. A bounded prompt is rendered with explicit context delimiters; recalled content is treated as data, not executable instructions.
4. Titan routes the grounded request to the selected replaceable inference backend.
5. The assistant response is written back to Memory OS with `titan-inference` provenance and published to the Global Cognitive Workspace.

## Invariants

- The GGUF backend remains a replaceable cognitive engine, never the owner of sovereign identity.
- Conversation continuity comes from AMPER Memory OS / Workspace, not hidden backend KV state.
- Context retrieval is bounded for mobile prompt budgets.
- User input is preserved as an explicit final request even when contextual material is truncated.
- Response memory carries backend provenance so later consolidation can distinguish generated content from direct observations.
- Canonical builds still work with no native backend installed.

This phase intentionally keeps retrieval lexical and bounded. Embedding/RAG indexing can be added later behind the same `SovereignContextSource` contract without changing Titan or model backends.
