# Phase634 — M3 Production AMI2 / AMNE2 Inference Cutover

Phase634 moves the existing single AMPER Core production inference endpoint from the temporary
AMI1 runtime bridge onto canonical AMI2 artifacts executed through AMNE2 sessions.

## Production boundary

`AmperCoreInferencePort` still exposes exactly one backend id:

`amper-core`

The backend class remains source-compatible for Titan, but its artifact contract is now
`StoredAmi2Artifact`.

Production inference therefore follows:

`Installed GGUF lineage -> canonical AMI2 -> verified AMNE2 execution session -> existing qualified decoder/kernel stack`

GGUF is not a runtime backend.

## Streaming and cancellation

The Phase633 AMNE2 session now forwards the existing generator `onToken` callback. The production
backend retains the established stable streaming text emitter and cancellable streaming interface,
but token generation itself is owned by the AMNE2 session.

Cancellation still propagates into the existing autoregressive generator and session-owned KV
state. The final response remains authoritative.

## Legacy compatibility

New imports compile only the canonical AMI2 artifact required for production execution.

For installations that already contain a verified AMI1 artifact but no AMI2 artifact, Android may
migrate that AMI1 file into canonical AMI2 before execution. AMI1 is therefore a migration source,
not a runtime fallback. The migrated artifact is verified by the same AMI2 reader before AMNE2 can
admit it.

The AMI1 compiler/service is retained temporarily for legacy migration and low-level diagnostic
probes only.

## Single-core invariant

Phase634 does not register another backend. Titan still receives one sealed AMPER Core endpoint and
model-choice hints remain semantically inert.

## M3 progress

Completed M3 slices now include:

- verified AMI2 -> AMNE2 admission;
- bounded mmap execution view;
- decoder semantic binding;
- session-owned decoder plan / KV / cancellation;
- production AMPER Core cutover to AMI2 / AMNE2.

## Next slice

Phase635 should move conversation hot-state/session reuse onto the AMNE2 session boundary so repeated
turns can preserve reusable decoder/KV preparation where semantically valid without permitting stale
or cross-foundation state. It must bind any reusable state to foundation id, semantic digest,
artifact digest and conversation lifecycle.
