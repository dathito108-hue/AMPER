# Phase607 — Direct AMI Attention + KV Cache + Complete Decoder Layer

Phase607 completes the first executable AMI decoder layer.

## Attention binding

For layer N, AMPER binds the canonical GGUF tensor names preserved in AMI:

- blk.N.attn_norm.weight
- blk.N.attn_q.weight
- blk.N.attn_k.weight
- blk.N.attn_v.weight
- blk.N.attn_output.weight

The planner derives hidden/query/KV geometry from tensor shapes and preserved GGUF metadata.

Required metadata:
- <architecture>.attention.head_count
- <architecture>.attention.layer_norm_rms_epsilon

Optional metadata:
- <architecture>.attention.head_count_kv (defaults to head_count)
- <architecture>.rope.freq_base (defaults to 10000 only because that is the GGUF/architecture RoPE convention already represented by the source family; invalid values still fail closed)

## GQA and RoPE

Grouped-query attention is supported when head_count is divisible by head_count_kv.

Q and K are rotated independently per head, not as one flattened vector. This preserves the correct
head-local RoPE frequency geometry.

## Bounded causal KV cache

Phase607 introduces an explicit per-layer KV cache.

Properties:
- positions must be contiguous from zero;
- K/V vectors are copied into owned cache storage;
- non-finite values are rejected;
- capacity is explicit;
- reaching capacity fails closed;
- there is no silent context eviction or sliding-window approximation.

Later phases can add an explicit sliding-window policy for architectures that declare one.

## Attention execution

For each token:

1. attention RMSNorm
2. Q/K/V matrix-vector projections from verified AMI FOUNDATION_WEIGHTS
3. per-head RoPE
4. append K/V to layer cache
5. causal scaled dot-product scores against all cached keys
6. softmax
7. grouped-query value accumulation
8. output projection
9. residual add

All math primitives are selected through the Phase605 qualified AMNE process registry.

## Complete decoder layer

Phase607 composes:

attention + residual -> FFN + residual

using Phase606 FFN execution. This is the first complete decoder transformer block executed directly
from AMI weights.

## Android physical probe

The model profile exposes:

Run AMI layer-0 full decoder probe

The physical probe executes two causal tokens through layer 0 so KV history is actually used. It
reports:
- Q/KV head geometry;
- context token count;
- mapped MiB;
- mmap window count;
- backend IDs;
- output L1 checksum;
- attention and FFN wall time.

Normal assistant generation still uses the existing llama fallback. AMI does not become the primary
language-model backend until embedding, all decoder layers, final norm, LM head, tokenizer and
sampling are connected and qualified.

## Next

Phase608 adds token embedding lookup, multi-layer decoder sequencing and per-layer KV ownership.

Phase609 then adds final normalization + LM head logits so AMI can execute a complete next-token
forward pass before sampling is introduced.
