# Phase625 — M2 Deterministic AMI2 Compilation Plan

Phase625 starts Milestone 2 without creating a duplicate GGUF parser or a second inference backend.

## Duplicate-path audit

The repository already has a verified GGUF -> AMI1 SOURCE_EXACT compiler, AMI1 reader, tokenizer
metadata preservation, and AMPER chat-template compatibility. Reimplementing those scanners under
`core/v2` would create two parser/compiler truths and increase both bug surface and CI cost.

Phase625 therefore treats the verified AMI1 artifact as a temporary migration source only. It does
not make AMI1 a production OMEGA runtime.

## Deterministic AMI2 semantic plan

`Ami2CompilationPlanner` projects the verified canonical sections into the AMI2 foundation identity:

- original source lineage;
- tokenizer digest;
- preserved chat-protocol digest;
- logical graph digest;
- canonical foundation-weight digest;
- tensor and vocabulary counts.

These values are serialized in one canonical order and SHA-256 hashed into
`Ami2FoundationIdentity.semanticSha256`. The stable foundation id is derived from that semantic
digest.

The legacy AMI1 whole-file digest is retained separately as `Ami2MigrationEvidence`; changing the
physical legacy container cannot change the AMI2 foundation identity when the canonical semantic
sections are unchanged.

## Device-pack separation

AMI1 execution-profile sections are deliberately ignored by AMI2 semantic identity. Device packs are
not allowed into the initial compilation plan and can only be generated after the canonical
foundation semantics have been fixed.

This establishes the M2 rule:

`foundation semantics first -> device acceleration packs second`

rather than allowing hardware packing to become a competing model identity.

## Chat protocol preservation

The exact preserved GGUF chat template, when available, is hashed as part of AMI2 semantics. If a
source has no template, the existing AMPER plain-assistant-cue compatibility contract receives a
stable explicit protocol identity.

## Next M2 slice

The next phase can add the AMI2 binary writer/reader around this deterministic plan. That writer must
materialize the mandatory AMI2 artifacts without changing `semanticSha256`, after which direct
GGUF -> AMI2 compilation can replace the temporary verified AMI1 migration stage.
