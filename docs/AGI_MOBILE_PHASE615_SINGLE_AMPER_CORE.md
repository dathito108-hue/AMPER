# Phase615 — AMPER Single-Core Intelligence Architecture

Phase615 removes multi-model runtime routing from the production AMPER path.

## Canonical runtime

```
Imported GGUF source
  -> SOURCE_EXACT AMI compilation
  -> one active AMPER foundation
  -> one live model registry slot
  -> one AMI backend
  -> AMNE kernels
  -> AMPER cognition
```

GGUF is source material only. Multiple imported files may remain in the durable catalog for lineage
and replacement, but only one descriptor may exist in the live registry at any moment.

## Removed from the production inference path

- llama.cpp AAR backend registration;
- MTMD/llama text backend registration;
- global preferred-model routing;
- native-model-preference wrapper routing;
- per-conversation GGUF preference controls;
- previous-model continuity hints in assistant inference requests.

The AMNE APK is built without llama libraries and CI fails if a `libllama*` runtime is packaged.

## Foundation semantics

Importing a GGUF now:

1. inspects and hashes the source;
2. compiles it to SOURCE_EXACT AMI;
3. unloads the previous live foundation;
4. activates the new source as the sole AMPER foundation;
5. persists the active foundation identity.

Retained GGUF entries are weight-source lineage, not parallel AI identities.

## Runtime invariant

`AmperSingleCoreModelRegistry` is a one-slot registry. Any legacy component attempting to register
another descriptor replaces the current slot rather than creating a second routable model.

The canonical self-model now records:

- `one-amper-inference-core`
- `external-weight-sources-are-import-data-not-runtime-identities`

## Current limitation

Direct AMI execution still requires native performance admission for every matrix encoding used by
the active foundation. Unsupported or reference-only K-quant paths are not silently routed.

Phase616 focuses exclusively on native Q4_K/Q5_K/Q6_K AMNE kernels so common mobile GGUF-derived AMI
foundations can execute entirely inside the single AMPER core.
