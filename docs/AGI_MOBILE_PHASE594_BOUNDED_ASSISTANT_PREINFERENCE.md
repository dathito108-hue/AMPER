# Phase594 — Bounded Assistant Pre-Inference Path

## Physical finding

Phase593 proved the local model path is operational on-device:

- Titan selected the imported GGUF;
- backend: `llama.cpp-aar`;
- smoke test PASS;
- 7 output tokens at about 4.5 tok/s.

However, `Ask AMPER` could still fail to reach Titan even though direct smoke inference succeeded.

## Root cause

The assistant path performs additional synchronous orchestration before Titan:

1. runtime tick;
2. Reflex Decision Cortex;
3. Native System-2 integrated cognitive capture/deliberation;
4. Titan inference;
5. action evaluation/finalization.

For ordinary tool-free conversational turns, step 3 can perform substantially more Memory/World/Skill
work than the direct inference path even when no deep deliberation is required.

## Phase594

- add a deterministic assistant Native System-2 admission policy;
- ordinary short tool-free turns can proceed from Reflex directly to Titan;
- planning, complex, long, multimodal and VERIFY-mode turns retain Native System-2;
- add assistant stage telemetry:
  `RUNTIME_TICK -> REFLEX -> NATIVE_SYSTEM2 (when admitted) -> TITAN_INFERENCE -> ACTION_EVALUATION`;
- expose those stages in the Android UI;
- retain Titan capability/resource admission, ToolDescriptor binding, ActionLoop and AuthorityGate unchanged.

This is not a second assistant path. It is a bounded admission decision inside the existing canonical
SovereignAssistantTurnCoordinator.
