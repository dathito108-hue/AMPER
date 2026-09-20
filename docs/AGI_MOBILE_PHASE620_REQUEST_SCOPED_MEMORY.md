# Phase620 — Request-Scoped AMPER Core Memory Admission

Phase620 fixes a physical routing failure where AMPER Core was rejected by Titan with
`memory-budget` before inference began.

## Root cause

The AMI decoder already allocates KV cache only for the current request:

`prompt tokens + requested output tokens`

but backend admission estimated KV memory using the foundation's full advertised context length.

For long-context foundations this could turn a short mobile request into a multi-gigabyte theoretical
estimate even though AMPER would never allocate that KV capacity for the turn.

## Correct admission model

AMPER Core now estimates:

- exact tokenizer prompt tokens;
- requested generation horizon;
- per-layer K/V payload for that request horizon only;
- bounded mmap/tile working set;
- bounded attention/FFN/JNI transient scratch.

The complete AMI foundation weight file is not charged as an eager heap allocation because execution
uses bounded read-only mmap windows over SOURCE_EXACT weights.

Context capability remains unchanged: the backend still advertises the foundation's maximum context
window, and requests exceeding that capacity fail closed.

## Diagnostics

Titan memory rejection now includes concrete numbers:

`memory-budget:estimated=<N>MiB,budget=<M>MiB`

so a physical device screenshot can distinguish a real low-memory condition from a bad estimator.

## Example geometry

For a 32-layer grouped-query model with KV width 1024 and a 32K advertised context:

- old admission could reserve KV as if all 32K tokens were active;
- a short turn with 16 prompt tokens + 384 output tokens now budgets only 400 KV positions.

That example drops the KV payload from roughly 8 GiB to 100 MiB before bounded transient headroom.

## Single-core invariant

No foreign fallback engine is reintroduced. If the corrected AMPER Core estimate still exceeds the
live Android budget, the turn is rejected with explicit estimated/budget values rather than invoking
another model runtime.

## Next

After physical confirmation, Phase621 can add persistent prefix/KV reuse so repeated conversation
prefixes avoid prompt re-evaluation while remaining bounded by the same mobile resource governor.
