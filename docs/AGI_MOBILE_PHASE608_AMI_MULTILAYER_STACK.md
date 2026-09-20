# Phase608 — AMI Token Embedding + Transactional Multi-Layer Decoder Stack

Phase608 turns the complete single decoder layer from Phase607 into a model-depth execution stack.

## Token embedding

AMPER binds the canonical `token_embd.weight` tensor and reads only the requested token row from
verified AMI `FOUNDATION_WEIGHTS`.

Supported row encodings in this phase:

- F32
- F16
- Q4_0
- Q8_0

The full vocabulary matrix is never copied into JVM memory.

Quantized rows are decoded directly from their source blocks without requantizing or rewriting the
AMI foundation.

## Model depth and context

The stack planner requires preserved GGUF metadata:

- `<architecture>.block_count`
- `<architecture>.context_length`

It then plans every decoder layer from zero through `block_count - 1` using the Phase607 complete
attention + FFN layer planner.

Every layer must preserve the embedding hidden width.

## Per-layer KV ownership

Each decoder layer owns an independent KV cache.

All caches must stay at one logical token position. The cache implementation no longer reserves the
complete advertised context at construction time; storage grows lazily as tokens arrive. This avoids
large up-front reference-array allocations for long-context mobile models.

## Transactional token execution

One token execution is treated as a transaction across all layers:

1. read one embedding row;
2. execute layer 0;
3. execute every subsequent layer in order;
4. require every layer KV cache to advance by exactly one position;
5. publish the resulting hidden state.

If any later layer fails after earlier layers already appended K/V, every cache is rolled back to
the token position that existed before execution.

AMPER therefore never continues from a partially committed token state.

## Mobile bounds

- embedding lookup maps only one row;
- decoder matrix execution retains the Phase606/607 bounded mmap windows;
- context capacity is explicit and cannot silently overflow;
- unsupported tensor encodings fail closed;
- no hidden conversion modifies source intelligence.

## Current boundary

Phase608 produces the final hidden state after all decoder blocks, but not vocabulary logits yet.

Normal assistant inference still uses the existing llama fallback.

## Next

Phase609 adds:

- final output RMSNorm;
- output/lm-head binding, including tied-token-embedding fallback only when the architecture/source
  explicitly supports that relationship;
- bounded vocabulary logit projection;
- a complete one-token AMI forward pass.

Phase610 then adds tokenizer-to-token-id ingestion and deterministic sampling so AMI can generate its
first autonomous text token.
