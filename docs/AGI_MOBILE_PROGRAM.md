# AMPER AGI-Mobile Program

## Canonical destination

AMPER is developed as a sovereign mobile cognitive system, not as an Android wrapper around one
replaceable LLM. The target stack is:

1. Sovereign Neuro-OS: identity, goals, memory, planning, metacognition and resource control.
2. AMPER Native Cognitive Model family: AMPER-owned trainable checkpoints and adapters.
3. Predictive world model: structured state, time, causality, uncertainty and counterfactuals.
4. Skill system: reusable learned programs/policies composed across tasks.
5. Multimodal representation: text, vision, audio, screen, camera and device state.
6. Tool/device runtime: Android actions and external capabilities behind the authority boundary.
7. Autonomous self-evolution: strategy, code, model and internal architecture can improve without
   routine human approval.

Teacher models are development accelerators, not permanent sovereign identity. The intended
dependency path is teacher-dependent -> teacher-assisted -> teacher-optional -> AMPER-independent.

## Maximum-autonomy self-evolution

Inside the containment boundary AMPER may autonomously:

- change planning, retrieval, memory, world-model and skill strategies;
- generate and modify its own source code;
- add, replace, split or retire internal subsystems;
- train, distill or adapt AMPER-owned model checkpoints and adapters;
- generate tests and benchmarks;
- run competing candidate implementations;
- select and promote an objectively better candidate;
- roll back a degraded candidate and start a new improvement campaign.

Human approval is not a required step for routine internal evolution.

The external containment/root-of-trust remains outside the authority of the evolving candidate.
Evidence, memory or learned policy cannot grant new device/external authority. Evolution may change
the implementation of internal architecture but may not redefine this external boundary from inside
the candidate being evaluated.

## Intelligence roadmap

### Mega-Phase 186-190 — Epistemic Intelligence
Provenance-bearing claims, uncertainty, contradiction resolution, freshness and semantic
consolidation. Planning consumes reconciled beliefs rather than treating every memory as fact.

Checkpoint sequence:
- Phase186: provenance-backed epistemic state.
- Phase187: contradiction resolution and independent-producer reconciliation.
- Phase188: durable semantic knowledge consolidation.
- Phase189: immutable knowledge revision/supersession lineage.
- Phase190: planning admission policy for semantic vs unresolved/provisional evidence.

### Mega-Phase 191-195 — Predictive World Model
Structured entities/state, temporal transitions, causal relations, predictions and counterfactual
updates.

### Mega-Phase 196-200 — Skill Genesis
Turn successful governed episodes into reusable skills with explicit preconditions, effects,
confidence and composition contracts.

### Mega-Phase 201-205 — Generalization
Cross-task skill composition, transfer, abstraction and strategy reuse outside the original task.

### Mega-Phase 206-210 — Autonomous Learning
Competence maps, weakness detection, active curriculum generation and evidence-driven practice.

### Mega-Phase 211-230 — AMPER Native Model
Dataset/curriculum pipeline, model contracts, AMPER-owned student checkpoints, distillation,
multimodal adapters and on-device inference integration.

### Mega-Phase 231-250 — Autonomous Evolution
Self-benchmarking, candidate code/model generation, sandbox tournaments, automated promotion,
rollback and architecture evolution.

### 251+ — Open-ended mobile intelligence
Integrate model, world state, memory, skills, multimodal perception and self-evolution; measure
progress by capability/generalization benchmarks rather than phase count.

## CI budget policy

GitHub Actions is a scarce verification resource.

- Intermediate commits do not trigger CI.
- Pull requests always run FAST verification: JVM unit tests plus canonical debug APK compile.
- Native llama/MTMD/Vulkan jobs run only when native/build/workflow surfaces change, or when FULL is
  explicitly requested.
- A Mega-Phase uses one exact-head canonical verification rather than a full run for every small
  implementation slice.
- Release artifacts are uploaded only for explicit FULL runs.
- New commits cancel obsolete in-progress CI for the same PR.

This changes when verification runs, not the canonical correctness requirements.