# Phase676 — M6 Cross-Domain Cognitive Memory Context Envelope

Phase676 adds one mobile context envelope across M6 working, episodic, semantic and procedural memory
surfaces. It creates no new memory store.

## Explicit episodic lane

Canonical runtime now passes its existing EpisodicMemoryStore into CanonicalSovereignContextSource.

SovereignContextSnapshot gains an explicit episodicMemories list. When that store is present,
episodic-v1 records are excluded from the generic MemoryOs projection, preventing the same episode
from appearing twice in planning context.

Legacy kind="episodic" records remain readable through the generic lane for backward compatibility.

## Default quotas

- generic sovereign memories: 6;
- working workspace events: 6;
- explicit episodic entries: 6;
- current semantic knowledge: 6;
- epistemic beliefs: 6;
- procedural strategy evidence: 4.

The four cognitive-memory surfaces therefore contribute at most 28 bounded items to the canonical
snapshot before other world/competence evidence.

Caller-supplied generic-memory or workspace limits above the envelope fail immediately.

## Prompt and digest coherence

Grounded prompt rendering has a distinct episodic_memory section.

IntegratedCognitiveStatePacket canonical digest binds each explicit episode by id, origin, importance
and fingerprint only. Raw episodic content is not copied into the digest material.

Planner and critic therefore remain bound to the same episodic evidence identity without adding
another persistence layer.

## Architecture

CognitiveMemoryContextEnvelope is policy/validation metadata only. It owns no MemoryOs, database,
cache, scheduler, model state or tool authority.

Base: main@08d6edb32231a0af767fb47c165c7a10cc6cb231.

## Next M6 slice

Phase677 should audit memory forgetting/compaction policy across all four domains: removal must remain
bounded, provenance-aware and never silently delete evidence still referenced by active semantic,
procedural, plan, recovery or conversation lineage.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
