# Titan sovereign conversations — Phase 10

Phase 10 moves multi-turn continuity into AMPER-owned memory instead of backend-local KV state.

## Invariants

- Every conversation has an explicit `ConversationId`.
- User and assistant turns are persisted as `conversation-turn` memory records with provenance.
- Conversation history is filtered by exact thread id before prompt rendering.
- Sovereign context and conversation history are bounded independently for mobile use.
- The current user request is appended after truncation so context pressure cannot remove it.
- Starting a new conversation does not delete previous memory; it only changes the active thread.
- Backend replacement or model unload does not erase conversational continuity.
- Assistant responses still enter semantic Memory OS through the Phase 9 provenance path.

## Runtime flow

1. Sovereign Kernel observes the user intent.
2. `SovereignConversationCoordinator.prepare()` captures prior turns for the active thread and records the new user turn.
3. AMPER renders sovereign context plus bounded thread history.
4. Titan routes the resulting request to an eligible backend.
5. `commitAssistant()` stores the assistant turn with backend provenance and publishes a conversation event.

The model remains a replaceable cognitive engine; AMPER owns identity, continuity and memory.
