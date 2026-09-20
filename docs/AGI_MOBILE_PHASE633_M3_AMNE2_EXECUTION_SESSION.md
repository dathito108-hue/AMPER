# Phase633 — M3 AMNE2 Execution Session

Phase633 creates the first canonical stateful AMNE2 execution owner over the verified AMI2 path.

## One session, one foundation

`Amne2ExecutionSessionFactory` opens:

1. verified AMI2 execution view;
2. Phase632 decoder semantic binding;
3. existing decoder stack plan;
4. one session-owned KV state.

The session identity remains bound to the same foundation id, semantic SHA-256 and full AMI2
artifact SHA-256 admitted by AMNE2.

## Decoder reuse

The session does not add another decoder. It invokes the existing:

- AmiDecoderStackPlanner
- AmiDecoderStackExecutor
- AmiAutoregressiveGenerator
- AMNE qualified kernel registry

through the AMI2 semantic projection established in Phase632.

## KV ownership and request serialization

Each session owns exactly one `AmiDecoderStackState`. Execution requests are serialized with a
single execution lease so concurrent callers cannot mutate one KV cache concurrently.

The session exposes:

- bounded one-token execution;
- autoregressive generation;
- explicit KV reset;
- close lifecycle.

Closing a session clears KV state and rejects future execution.

## Cancellation

Cancellation is checked before and after one-token decoder execution. If cancellation is observed
after decoder work completed, session KV rolls back to its pre-request position before the
cancellation failure is exposed.

The existing autoregressive generator already provides transactional rollback on generation failure
or cancellation and is reused directly.

## Mobile execution properties

- same bounded mmap tensor windows as the existing decoder stack;
- no whole-model Java heap copy;
- no second backend;
- no second kernel registry;
- no AMI1 production runtime.

## Next M3 slice

Phase634 should cut the existing production inference boundary over to this AMNE2 execution-session
contract for AMI2 artifacts, while retaining the old AMI1 runtime bridge only as a temporary rollback
path until parity is demonstrated. The cutover must preserve streaming cancellation and conversation
hot-state behavior.
