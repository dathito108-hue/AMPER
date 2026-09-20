# Phase610 — AMI Output Head and Autoregressive Token Generation

Phase610 closes the first token-id generation loop over AMPER Mobile Intelligence.

## Execution path

The direct AMI path is now:

```
prompt token ids
  -> token embedding
  -> transactional decoder stack
  -> final RMSNorm
  -> output projection
  -> vocabulary logits
  -> sampler
  -> next token id
  -> append to KV-backed decoder state
  -> repeat
```

This is the first AMI path that can produce new model token IDs without asking llama.cpp to execute
the decoder graph.

## Output head

`AmiOutputHeadPlanner` requires `output_norm.weight`.

For the vocabulary projection it uses:

1. `output.weight` when present;
2. otherwise the verified `token_embd.weight` as a tied output matrix.

The projection must match both decoder hidden width and AMI vocabulary size.

Final norm uses the model's preserved RMS epsilon.

## F16 tied-output compatibility

Phase610 admits F16 matrix tensors by decoding only the current bounded mmap tile into F32 and then
using the existing qualified MATVEC_F32 kernel.

This does not rewrite, requantize or replace AMI FOUNDATION_WEIGHTS. The source tensor remains F16
and SOURCE_EXACT.

## Sampling

The first sampler supports:

- greedy decoding with temperature 0;
- temperature scaling;
- top-k;
- top-p nucleus filtering;
- repetition penalty;
- deterministic seed.

Sampling validates finite logits and vocabulary-bounded history before selecting a token.

## Transactional generation state

Prompt and generated non-stop tokens are committed into the decoder/KV state.

If any decoder or output-head operation fails, the complete multi-layer KV state is rolled back to
the position it had before the generation request.

A sampled stop token terminates immediately and is returned in generated token IDs without being
inserted into KV state.

## Scope

Phase610 consumes **token IDs** and emits **token IDs**.

It does not yet claim text-level standalone inference because the preserved GGUF tokenizer arrays
are currently retained but intentionally skipped by the scalar metadata reader.

## Next

Phase611 will compile/read the preserved tokenizer vocabulary and token metadata into an AMI-native
tokenizer representation, then connect:

`UTF-8 prompt -> AMI tokenizer -> Phase610 generator -> AMI detokenizer -> streamed text`.

That is the boundary required before an AMNE backend can compete with the llama fallback on a real
assistant turn.
