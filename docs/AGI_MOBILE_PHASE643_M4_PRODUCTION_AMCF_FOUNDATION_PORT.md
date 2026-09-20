# Phase643 — M4 Production AMCF Foundation Inference Port

Phase643 connects the bounded Phase642 orchestrator to the existing production inference path without
creating another model, decoder, runtime, or tool-authority lane.

## Existing production endpoint only

The production adapter is:

`TitanAmcfFoundationInferenceEndpoint -> TitanCortexRuntime -> sealed AMPER Core -> AMI2/AMNE2`

The endpoint is bound to one immutable `AmcfFoundationBinding`. Every cycle must match that exact
foundation id, semantic SHA-256, and AMNE2 execution engine before inference begins.

## Bounded cycle requests

Each AMCF cycle becomes one reasoning-only `InferenceRequest`.

Per-cycle output is hard bounded to at most 512 tokens:

- DELIBERATE: at most 256;
- VERIFY: at most 192;
- REVISE: at most 256;
- FINALIZE: caller budget, capped at 512.

The AMCF port does not invoke ToolFabric, approval gates, or authority-bearing actions. Tool authority
remains outside AMCF cognitive state control.

## Structured recurrent state and transient candidate

Phase641 recurrent state remains digest/metric-only.

To make VERIFY/REVISE useful, the production port retains only the previous candidate answer as
bounded transient process-local text, capped at 8 KiB. It is not committed to recurrent state,
memory, or an authority store.

Prompts explicitly request candidate answers only and forbid exposing hidden chain-of-thought.

The authoritative AMCF observation contains only:

- candidate SHA-256;
- evidence SHA-256;
- confidence;
- uncertainty;
- evidence sufficiency.

## Confidence policy

Phase643 does not infer confidence from prose.

A structured `AmcfCycleQualityEvaluator` is injected. The fallback evaluator is intentionally
conservative and never crosses Phase641 DELIBERATE early-exit thresholds. Therefore a deployment
without stronger grounded cognitive signals spends the full planned depth instead of fabricating
certainty.

Existing metacognitive/cognitive state can be wired into this evaluator in the next phase.

## Cancellation and transaction ownership

The production port checks the existing `InferenceCancellationSignal` before and after inference.

It does not commit `AmcfRecurrentState`. Phase642 remains the only commit owner, so cancellation
between inference and commit cannot publish a completed cognitive cycle.

## Phase643 exit criteria

Phase643 is complete when:

- AMCF cognitive cycles execute through the existing Titan/single-core AMI2/AMNE2 path;
- endpoint foundation identity is checked before inference;
- every cycle request is reasoning-only and token bounded;
- transient candidate text is bounded and not persisted as recurrent state;
- observations remain digest/metric-only;
- default confidence is conservative rather than synthesized from prose;
- an injected structured evaluator can activate the existing early-exit gate;
- no new model/backend/runtime/tool-authority path is introduced.

## Next M4 slice

Phase644 should add an adapter from the existing integrated cognitive readiness/metacognitive state
into `AmcfCycleQualityEvaluator`. It should use already-computed readiness, uncertainty, epistemic
confidence, world confidence and evidence freshness; it must not create a second judge model.
