# Phase673 — M6 Episodic Memory Admission / Retention

Phase673 replaces the kernel's direct episodic write with one canonical episodic store over the
existing sovereign MemoryOs.

## No second episodic database

CanonicalEpisodicMemoryStore writes episodic-v1 records into the same MemoryOs used by all cognitive
memory domains. Its index is read-only: bounded MemoryOs scan + kind filter. No sidecar/vector/model
or backend database is created.

## Admission and privacy bounds

Default admission requires an allowed origin, importance >= 0.35, nonblank sanitized content, and
retained content <= 1024 characters.

Allowed origins: user intent, perception, tool outcome, goal transition.

Conversation turns, semantic knowledge and procedural memory are explicitly disallowed origins so
their canonical stores are never copied into episodic memory.

## Duplicate suppression

Fingerprint = SHA-256 over origin, sanitized content, provenance source and producer.

Equal fingerprints inside the default ten-minute duplicate window are suppressed without a write.
The same retained event may become a distinct episode outside that window.

## Retention

At most 256 episodes are retained. Retention prefers higher importance, then newer observation time,
then stable record id. A weak candidate that would be immediately evicted is rejected before write.

## Retrieval integrity

recent() scores only episodic-v1 records with the existing deterministic MemoryRetrievalScorer.
Corrupt episodic records or MemoryOs scan-bound overflow fail visible rather than returning partial
history.

## Kernel integration

CanonicalSovereignKernel no longer calls MemoryOs.remember directly for episodic intent. It uses the
single runtime EpisodicMemoryStore. The first canonical tick still produces one durable episode.

## Architecture audit

Base: main@39cf0bafef11a844e8bac665aadd0a3e7904f082.

No native/build changes. AMI2/AMNE2, Titan, ToolFabric, AuthorityGate, working-memory continuity,
semantic/procedural memory, conversation encoding and M5 execution remain unchanged.

## Next M6 slice

Phase674 should harden semantic retrieval/consolidation budgets and evidence freshness over the
existing MemoryBackedSemanticKnowledgeStore without creating a second semantic index or giving
semantic memory authority.

## CI gate

Use one PR-triggered Android CI run. Merge only if verify + unit tests + canonical debug APK PASS and
amper-core-arm64 SKIP.
