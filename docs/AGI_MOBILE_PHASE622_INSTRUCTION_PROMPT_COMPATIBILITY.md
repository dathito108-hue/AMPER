# Phase622 — Instruction Prompt Compatibility for AMPER Core

Phase622 fixes a semantic-answering defect in direct AMI inference.

## Root cause

AMI already preserves the original GGUF tokenizer metadata, including the scalar
`tokenizer.chat_template` when present.

The direct AMPER Core runtime previously tokenized the complete internal prompt as raw completion
text. Instruction/chat-tuned foundations are commonly trained to answer only after their expected
role/control framing and assistant-generation marker. Without that framing, a healthy decoder can
continue or imitate the prompt rather than answer the current question.

## Trusted prompt compiler

AMPER Core now compiles its internal prompt into token ids using a model-compatible instruction
framing layer.

Recognized families:

- Llama 3 header tokens
- ChatML / Qwen-style `<|im_start|>` / `<|im_end|>`
- Gemma turn tokens
- Phi role tokens
- Mistral `[INST]`
- a bounded plain assistant-cue fallback when no known template is available

Known control boundaries are inserted by exact vocabulary token id. User text is always encoded as
ordinary text with special-token insertion disabled, so user content cannot manufacture trusted role
boundaries.

## AMPER identity

A short trusted system identity is inserted before the AMPER internal request:

`You are AMPER, the application's single local AI intelligence core...`

It states that foundation weights execute through AMI + AMNE and instructs the foundation to answer
the current request directly. This gives runtime-identity questions an authoritative semantic context
instead of relying on the imported foundation to guess its host environment.

## Stop semantics

Generation now stops on the foundation's normal EOS plus template-specific end-of-turn controls such
as:

- `<|eot_id|>`
- `<|im_end|>`
- `<end_of_turn>`
- `<|end|>`

Stop-control ids are removed from streamed/final visible text.

## Admission consistency

Prompt token estimation, memory admission, context fit and actual generation all use the same
instruction-compiled token sequence. Titan therefore cannot admit one prompt shape and execute a
different one.

## Expected physical result

Questions such as:

`Bạn là ai và đang dùng lõi suy luận nào?`

should now reach the imported instruct foundation in the role format it was trained on. The expected
semantic answer should identify AMPER and the AMI/AMNE single-core runtime rather than produce an
unrelated continuation.
