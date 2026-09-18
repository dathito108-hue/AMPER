# Titan bounded tool-agent turn — Phase 12

Phase 12 connects conversation continuity, Titan inference and the governed action loop into one bounded assistant turn.

## Turn algorithm

1. AMPER records the user intent in the Sovereign Kernel and active conversation.
2. The prompt contains bounded sovereign context, bounded thread history and the strict `AMPER_ACTION_V1` protocol.
3. Titan produces either a normal answer or one action proposal.
4. Normal answers are committed immediately to sovereign conversation memory.
5. A permitted read-only action may execute through the audited Tool Fabric.
6. `LOCAL_STATE` and `EXTERNAL` actions stop at `PendingApproval` until the user explicitly approves them.
7. An executed, denied, unavailable or failed action gets exactly one final Titan synthesis pass with the tool outcome marked as untrusted data.
8. The final synthesis cannot trigger another tool execution in the same turn.

## Safety and sovereignty invariants

- Maximum one tool execution attempt per assistant turn.
- Maximum two model inference passes per assistant turn.
- Explicit approval never bypasses `AuthorityGate`.
- Tool output is escaped, size-bounded and labelled untrusted before being returned to the model.
- The current user request is repeated in the final prompt and remains bounded for mobile context windows.
- Model identity and backend state remain separate from AMPER identity, memory and authority.
- Tool outcomes and conversation turns remain under sovereign Memory OS; production storage remains AES-GCM encrypted with Android Keystore key custody.

## Next boundary

Android read-only device providers can now be registered without changing Titan or the assistant-turn algorithm. Higher-impact providers should remain confirmation-gated and permission-scoped.
