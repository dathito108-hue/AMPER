# Phase635 — M3 AMNE2 Conversation Hot-State / Session Reuse

Phase635 adds bounded conversation KV reuse to the single production AMPER Core after the Phase634
AMI2/AMNE2 cutover.

## One hot session

AMPER keeps at most one conversation hot session in the production text backend.

This is intentional for mobile hardware: switching to another conversation or verified foundation
closes the previous AMNE2 session instead of retaining multiple large KV caches.

Auxiliary reflection, verification and synthesis inference passes do not carry a conversation hot
session key, so they use ephemeral AMNE2 sessions and cannot replace the primary conversation KV
state.

## Identity binding

A reusable session is bound to:

- conversation lifecycle id;
- installed model id;
- AMI2 foundation id;
- AMI2 semantic SHA-256;
- full AMI2 artifact SHA-256.

Any identity change closes the existing session and opens a new one.

The conversation lifecycle id is an opaque execution-state key only. It is not a routing preference
and cannot choose another model or backend.

## Exact token-prefix admission

KV reuse occurs only if the newly tokenized full prompt strictly extends the exact token sequence
already committed to session KV.

Reuse is rejected when:

- the lifecycle or verified artifact identity changes;
- any token in the cached prefix differs;
- the prompt is not a strict extension;
- the request plus output budget would exceed the session context limit;
- the live KV position no longer equals the committed token history.

A rejection is not an inference failure. The stale session is closed and the request starts from a
fresh AMNE2 session.

## Sampling correctness

When only the unevaluated prompt suffix is sent through the decoder, the previously committed token
prefix is still supplied to the sampler as history. This preserves full-prompt repetition/sampling
semantics while avoiding duplicate decoder work.

The generator's new sampling-history argument is optional and appended to its API, so all existing
non-reuse call sites retain their previous behavior.

## Cancellation and rollback

Existing AMNE2 transactional generation remains authoritative.

- a failed or cancelled reused request rolls KV back to the pre-request position and keeps the
  previously committed hot state;
- a failed first request for a newly-created hot session closes and discards that session;
- successful requests commit exactly the full prompt tokens plus generated tokens that were actually
  executed into KV;
- a terminal stop token that was sampled but not executed is not added to committed KV history.

## Lifecycle propagation

Only the primary SovereignAssistantTurn request carries conversationSessionId.

Titan request normalization is forbidden from mutating the lifecycle key, and the production AMPER
Core normalizer preserves it while continuing to erase legacy model-choice hints.

## Phase635 exit criteria

Phase635 is complete when:

- one mobile-bounded hot session is retained at most;
- reuse requires exact conversation/foundation/artifact identity;
- reuse requires exact token-prefix continuity;
- sampler history remains equivalent to the full prompt;
- reflection/synthesis passes cannot evict primary conversation hot state;
- unload closes matching hot state;
- cancellation/failure cannot commit partial KV state.

## Next M3 slice

Phase636 should bind AMNE2 execution to the portable hardware autotuning contract: choose qualified
ARM64/NEON/DOTPROD/I8MM/FP16 kernel paths from measured device performance, keep deterministic
reference kernels as the correctness baseline, and permit Vulkan only when physical-device
benchmark evidence beats CPU execution.
