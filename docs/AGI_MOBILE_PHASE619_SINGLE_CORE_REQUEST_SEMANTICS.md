# Phase619 — Single-Core Request Semantics

Phase619 removes model-choice semantics from the production Titan path without rewriting Titan's
mature capability/resource/cancellation machinery.

## Production normalization

Every request entering Titan through `AmperCoreInferencePort` is normalized before planning.

The production normalizer clears:

- `preferredModelId`;
- `userPreferredModelId`.

It preserves exactly:

- prompt text;
- mandatory capabilities;
- preferred capability profiles;
- output-token budget;
- temperature;
- session policy;
- attachments.

Titan therefore plans only the capability/resource execution of the one active AMPER foundation.

## Route observations

Because the route planner receives the normalized request, production route observations no longer
carry a meaningful preferred-model identity. The active foundation is already fixed by
`AmperSingleCoreModelRegistry`.

## Internal compatibility

The legacy request fields remain in the shared data class temporarily so older tests/internal modules
do not require a risky all-at-once migration. They are semantically inert at the AMPER Core production
boundary.

## Safety invariant

The Titan request normalizer is constrained to model-choice removal only. Titan rejects a normalizer
that changes prompt text, capabilities, output budget, temperature, session policy or attachments.

## Next

Phase620 focuses on inference latency inside the one AMPER Core: reusable prefix/KV state and
first-token timing, without adding any alternate model/runtime path.
