# AMPER — APEX–MUXER SOVEREIGN NEURO-OS

Clean-room canonical implementation of the frozen APEX–MUXER SOVEREIGN NEURO-OS architecture.

## Canonical invariants

- One sovereign identity, one cognitive kernel.
- Specialist agents are bounded/ephemeral workers, not duplicate permanent AI stacks.
- The kernel depends on stable contracts, never on one model/vendor/backend.
- Local GGUF models are user-installed and selected at runtime; none are bundled.
- Tools and external capabilities execute only through explicit authority/capability gates.
- Self-improvement is proposal -> validation -> verification -> promotion; no direct self-overwrite path.
- Mobile resource limits are first-class runtime constraints.

## Canonical subsystems

1. Sovereign Kernel
2. Global Cognitive Workspace
3. Meta-Cognition
4. Persistent Memory OS
5. Self / Goal / World-Causal Model
6. Dynamic Agent Fabric
7. Tool Fabric + Authority Gate
8. Multimodal Perception contracts
9. Learning + Consolidation
10. Verified Evolution loop
11. Resource Governor
12. Model Registry + Titan Cortex abstraction
13. Capability Registry / pluggable backends

## Bootstrap status

`canonical-bootstrap` establishes the compileable Android shell, stable contracts, deterministic in-memory reference implementations, a sovereign tick loop and CI. Subsequent slices replace reference backends behind the same contracts rather than redesigning the architecture.
