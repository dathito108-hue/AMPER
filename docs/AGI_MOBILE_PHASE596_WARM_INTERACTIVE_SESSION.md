# Phase596 — Warm Interactive Session + Short Blocking Completion

## Physical finding

Phase595 proved the local GGUF route is operational but the Ask AMPER path can still look
unresponsive on the free `llama-android 0.1.1` backend.

The packaged backend is completion-only: it does not expose true incremental token streaming.
Two latency multipliers remained in the physical test path:

1. the smoke test used temperature `0.0`, while the canonical assistant uses `0.7`;
2. the llama AAR binds temperature into the loaded session configuration, so changing temperature
   makes the existing session incompatible and forces a model release/reload before the next turn.

Phase595 also allowed up to 64 output tokens for a blocking interactive fallback. At the measured
~4.7 tok/s generation rate, generation alone can consume roughly the low-teens of seconds before
the UI receives any completed text.

## Phase596

- keep the canonical Titan / SovereignAssistantTurnCoordinator / AuthorityGate architecture;
- make the physical smoke test use temperature `0.7`, matching the assistant default;
- therefore a smoke test can prime an assistant-compatible llama session instead of invalidating it;
- reduce `TitanBlockingStreamFallbackPolicy.MAX_OUTPUT_TOKENS` from 64 to 32;
- true streaming backends still retain the caller's complete output budget;
- direct non-streaming inference semantics remain unchanged;
- expose the 32-token blocking cap in the Android stage telemetry;
- lock the 32-token budget with a regression assertion.

At the observed ~4.7 tok/s generation speed, 32 tokens is about 6.8 seconds of generation plus
prompt evaluation/model overhead, rather than ~13.6 seconds for 64 tokens or >80 seconds for the
original 384-token assistant allowance.

## Scope

This is a latency and session-continuity correction, not a second inference path. It does not bypass
model verification, capability/resource admission, action parsing, approval, authority, persistence,
or cancellation semantics.
