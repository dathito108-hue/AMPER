# Phase618 — AMPER Core Inference Port

Phase618 removes the generic backend registry from the Android production boundary.

## Production invariant

Android now constructs exactly one:

`AmperCoreInferencePort`

That port owns the direct AMI decoder endpoint and exposes a sealed one-entry registry only to Titan's
existing internal admission/planning machinery.

The sealed registry:

- contains exactly one endpoint;
- rejects a foreign backend id;
- rejects replacement registration after the AMPER Core endpoint is installed.

This lets mature Titan resource admission, cancellation, telemetry and feedback stay intact while
preventing production from becoming a multi-backend architecture again.

## Runtime identity

The direct AMI endpoint now reports:

`amper-core`

rather than the old backend-oriented `amne-ami-direct` identity.

GGUF remains source data. AMI is the foundation container. AMNE is the native tensor engine. AMPER
Core is the single production inference identity.

## Automatic native admission

When an AMNE-enabled APK starts, AMPER automatically schedules:

1. numerical kernel qualification;
2. device microbenchmark;
3. process-local primitive admission.

This runs on the maintenance lane and no longer requires the user to press a qualification button
before ASK AMPER can use admitted native kernels.

The UI retains a re-qualification control for diagnostics.

## Sovereign status

Runtime status now reports one inference endpoint: `amper-core`.

Imported GGUF entries may still appear as retained source lineage, but they are not runtime engines
or competing model identities.

## Internal compatibility

The generic `InferenceBackendRegistry` remains available for unit tests and older internal modules.
Production obtains only `InferenceBackendRegistry.singleCore(...)`, whose mutation surface is sealed.

## Next

Phase619 removes model-choice semantics from Titan request objects and route observations that are no
longer meaningful in a one-foundation runtime, while preserving capability/resource admission.
