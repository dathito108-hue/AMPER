# Phase597 — Compact Interactive Prompt Budget

## Physical evidence entering Phase597

Phase596 physical screenshots show:

- the packaged `llama.cpp-aar` backend is operational;
- the selected GGUF is admitted for reasoning;
- the physical local smoke test PASSes at about 4.9 tok/s;
- 13 generated tokens take about 2.6 seconds while the complete smoke-test wall time is about 17.7 seconds.

The remaining user-visible issue is latency on Ask AMPER.

## Residual latency mechanism

The physical smoke test uses a tiny prompt, but the sovereign assistant production default permits a
first-pass prompt budget of 9000 characters. That prompt can include grounded sovereign context,
conversation history and the action protocol.

On the mobile llama backend, a larger first-pass prompt has two costs:

1. more prompt-evaluation work before any completed response can be returned;
2. it can require the larger 4096-token context tier, making a previously loaded 2048-token session
   incompatible and forcing a native reload.

## Phase597

For an ordinary first-pass turn only, AMPER now uses the minimum production prompt budget
(`4096` characters) when all of these are true:

- the user has not explicitly overridden the conversation prompt budget;
- the turn is short;
- there are no image/audio attachments;
- no specialist capability profile is preferred;
- Native System-2 is not required;
- reflection mode is STANDARD.

Complex, specialist, multimodal and VERIFY turns retain the complete configured prompt budget.
Explicit user prompt-budget overrides are never silently reduced.

The prompt remains built by the existing sovereign conversation/context/action-protocol code. Titan
routing, capability/resource admission, action parsing, approval and AuthorityGate semantics are
unchanged.

## Physical telemetry

The Android result status now reports:

- warm/cold session reuse;
- output token count;
- token/s;
- prompt-evaluation time;
- generation time;
- complete assistant wall time.

This lets the next device run distinguish model load, prompt evaluation and generation latency
without introducing a second inference path.
