# Phase638 — M3 Context Pressure / Hot-Session Rebuild Policy

Phase638 makes long-conversation pressure explicit in the single production AMNE2 backend.

## Three deterministic outcomes

Every conversation turn is classified into exactly one action:

- REUSE_HOT_SESSION — only when lifecycle/artifact identity matches and the new prompt strictly
  extends the exact committed token prefix within the current session capacity.
- REBUILD_FULL_PROMPT — when reuse is invalid but the complete prompt plus requested output still
  fits the hardware-admitted safe context.
- REQUIRE_COMPACTION — when the complete request exceeds the Phase637 safe context.

There is no hidden KV eviction, sliding truncation, or silent token loss.

## Production backend binding

The prepared AMPER Core runtime now retains the Phase637 memory budget obtained from its verified
AMNE2 preparation session.

Therefore:

- supportsRequest uses safeContextTokens rather than the model-advertised context;
- requestRejectionReason returns an explicit
  amper-core-context-compaction-required:required=<n>,safe=<n> signal;
- cost estimates use the admitted mmap window and safe context;
- non-conversation sessions are opened only for the exact required context;
- hot sessions rebuild from the complete prompt when reuse is invalid but safe;
- requests beyond safe context do not enter generation.

## Checkpoint/compaction handoff

Phase638 deliberately does not invent summarization inside AMNE2. AMNE2 is the execution engine and
must not rewrite conversation meaning.

REQUIRE_COMPACTION is an explicit handoff point for the later cognitive/conversation layer to produce
a deterministic compacted checkpoint, after which the complete compacted prompt can be tokenized and
executed as a fresh AMNE2 session.

This preserves separation of concerns:

conversation cognition decides what may be compacted;
AMNE2 enforces the physical context budget.

## Phase638 exit criteria

Phase638 is complete when:

- exact-prefix reuse remains unchanged for valid hot sessions;
- identity or prefix mismatch rebuilds from the full prompt rather than reusing stale KV;
- a too-small old session rebuilds when the request still fits the hardware-safe context;
- requests beyond the safe context are rejected before generation with an explicit compaction signal;
- production support checks and cost estimates use the hardware-admitted context;
- no KV state is silently evicted or truncated.

## Next M3 slice

Phase639 should close M3 by exposing the AMNE2 runtime readiness contract as one consolidated
diagnostic/telemetry surface: verified foundation identity, safe context, mmap budget, active
qualified kernel dispatch, hot-session state, and explicit degraded reasons. This should make M3
observable before M4 AMCF begins.
