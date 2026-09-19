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

### Implemented checkpoint — Phase188-190
Phase188 adds durable semantic consolidation from planning-eligible epistemic assessments with direct
evidence lineage. Phase189 adds immutable semantic versioning, supersession and retraction when a
belief changes or becomes contested/uncertain/stale. Phase190 isolates raw epistemic/semantic journal
records from general planning memory, exposes only current semantic knowledge, and feeds the same
reconciled epistemic packet to the independent plan critic.

### Mega-Phase 191-195 — Predictive World Model
Structured entities/state, temporal transitions, causal relations, predictions and counterfactual
updates.

### Implemented checkpoint — Phase191-195
Phase191 adds durable structured entity/attribute world state sourced from reconciled semantic
knowledge. Phase192 records immutable temporal transitions. Phase193 derives bounded causal
hypotheses with support/contradiction evidence and does not treat correlation as authority.
Phase194 produces horizon-bound predictions from temporal transitions, causal hypotheses, or an
explicit persistence prior. Phase195 evaluates later observations as confirmed/disconfirmed/expired,
calibrates future prediction confidence, isolates raw predictive journal records from generic memory,
and exposes bounded world evidence to planning and the independent critic.

### Mega-Phase 196-200 — Skill Genesis
Turn successful governed episodes into reusable skills with explicit preconditions, effects,
confidence and composition contracts.

### Implemented checkpoint — Phase196-200
Phase196 defines reusable skill contracts as bounded capability sequences plus structured world-state
preconditions/effects without raw inputs, outputs, tool IDs or authority. Phase197 learns only from
exact governed terminal execution evidence and ignores authority/environment/protocol blocks as skill
credit. Phase198 promotes skills after repeated success and degrades them when execution evidence
falls below the active threshold. Phase199 composes active skills only when learned effects satisfy
the next skill's preconditions and the combined plan remains bounded. Phase200 exposes escaped,
non-authoritative skill guidance to planning; every resulting plan is still parsed against live tool
contracts, rebound to exact providers, and subject to the existing authority and side-effect gates.

### Mega-Phase 201-205 — Generalization
Cross-task skill composition, transfer, abstraction and strategy reuse outside the original task.

### Implemented checkpoint — Phase201-205
Phase201 creates privacy-preserving hashed goal-context fingerprints instead of persisting raw goals
inside transfer evidence. Phase202 records only exact governed success/execution-failure evidence for
cross-task reuse, with per-plan idempotence. Phase203 promotes skills from LOCAL to TRANSFERABLE and
GENERALIZED only after success across distinct contexts, and marks previously transferable skills
DEGRADED when later execution evidence falls below the transfer threshold. Phase204 searches bounded
multi-skill chains by simulating learned effects into the next skill's preconditions and requires a
real produced-state dependency between adjacent skills. Phase205 exposes escaped novel-context
transfer/chains as advisory planning evidence; normal live tool binding, AuthorityGate and side-effect
approval remain authoritative.

### Mega-Phase 206-210 — Autonomous Learning
Competence maps, weakness detection, active curriculum generation and evidence-driven practice.

### Implemented checkpoint — Phase206-210
Phase206 derives bounded weakness signals from real governed execution competence plus skill/transfer
evidence, while excluding authority denial and environment/protocol states from execution-skill
failure. Phase207 generates a bounded synthetic curriculum over the current live capability surface
without persisting raw user goals, tool inputs or outputs. Phase208 performs zero-tool plan-contract
practice: model output is parsed through TitanPlanProtocol against the live ToolDescriptor and never
sent to ToolFabric. Phase209 stores practice competence separately from real execution competence, so
practice PASS cannot manufacture execution success, skill credit, approval or authority. Phase210
adds a one-cycle autonomous practice API with exactly one inference at most and no unbounded
self-learning loop.

### Mega-Phase 211-230 — AMPER Native Model
Dataset/curriculum pipeline, model contracts, AMPER-owned student checkpoints, distillation,
multimodal adapters and on-device inference integration.

### Implemented checkpoint — Phase211-215
Phase211 defines an explicit AMPER-owned native model architecture/capability contract. Phase212
adds content-addressed dataset shard manifests with provenance and training-rights classification;
UNKNOWN rights are retained for audit but fail closed at checkpoint admission. Phase213 freezes a
bounded multi-capability curriculum and deterministic digest. Phase214 records immutable checkpoint
lineage across parent checkpoint, contract, dataset snapshot, curriculum, training recipe and weight
artifact digests. Phase215 adds held-out admission gates for planning protocol, live tool-contract
compatibility, regression and generalization. An admitted foundation checkpoint is still not added to
live ModelRegistry; distillation/training and native inference routing remain Phase216-230 work.

### Implemented checkpoint — Phase216-220
Phase216 freezes exact teacher snapshot identity, capability coverage and distillation rights; unknown
teacher rights fail closed. Phase217 binds the AMPER student contract, dataset snapshot, curriculum,
training recipe and a mobile deployment target (format, quantization, context and runtime-memory
budget) into one immutable distillation manifest. Phase218 adds a pluggable NativeTrainerPort and
publishes a checkpoint only when the trainer result is bound to the exact run/manifest and matches
the requested mobile artifact format/quantization; trainer or artifact-contract failures publish no
checkpoint. Phase219 records held-out evaluations and compares candidate checkpoints against a
baseline with per-metric regression tolerance. Phase220 creates a non-authoritative promotion
candidate only after foundation admission plus objective comparison; it intentionally does not
register the checkpoint into live ModelRegistry.

### Implemented checkpoint — Phase221-225
Phase221 recomputes checkpoint promotion eligibility from the held-out training pipeline instead of
trusting caller-supplied promotion state. Phase222 requires an actual descriptor-bound GGUF artifact,
runs structural admission, and requires its SHA-256 to match the immutable checkpoint weight digest.
Phase223 derives live ModelDescriptor capabilities only from the AMPER-owned native model contract.
Phase224 publishes the verified model through InstalledModelCatalog plus ModelRegistry with rollback
if live registration fails, and Android Titan routing now resolves both user content-URI models and
AMPER-owned app-private artifact locators. Phase225 reuses the governed unload/detach path for runtime
rollback; live model availability never grants tool/device authority.

### Implemented checkpoint — Phase226-230
Phase226 separates runtime capability admission from the full native model contract: adapter-dependent
VISION/AUDIO capabilities stay latent when a checkpoint first enters live routing. Phase227 requires
held-out adapter evidence before those capabilities can be enabled. Phase228 binds a descriptor-bound
GGUF multimodal projector identity to the promoted checkpoint and gives Android MTMD routing a composite
resolver for both user content-URI projectors and AMPER-owned app-private projectors, with rollback on
catalog/registry failure. Phase229 adds an AMPER-native soft preference only when no explicit user model
choice or turn-continuity model is present. Phase230 proves ordinary Titan capability/backend/resource
admission remains authoritative, so an unavailable or incapable native route falls back rather than
becoming a hard pin. Native model or adapter state never grants tool/device authority.

### Mega-Phase 231-250 — Autonomous Evolution
Self-benchmarking, candidate code/model generation, sandbox tournaments, automated promotion,
rollback and architecture evolution.

### Implemented checkpoint — Phase231-235
Phase231 freezes bounded normalized benchmark suites and an exact sampled canonical baseline.
Phase232 accepts content-addressed MODEL/CODE/STRATEGY/ARCHITECTURE candidates whose benchmark result
is bound to the same artifact digest. Phase233 evaluates only supplied sandbox/test/benchmark evidence
and has no ToolFabric or AuthorityGate execution path. Phase234 requires per-metric sample floors,
candidate score floors, regression tolerances and positive weighted aggregate improvement, then passes
the candidate through the existing CanonicalEvolutionGate for sovereign invariants and rollback
capability. Phase235 deterministically selects the objectively strongest eligible candidate and emits
a non-authoritative, non-live promotion proposal. Actual deployment/promotion execution remains
Phase236+.

### Implemented checkpoint — Phase236-240
Phase236 upgrades tournament winners into immutable content-addressed promotion tickets carrying exact
candidate kind, artifact digest, base/proposed revisions, benchmark identity and verified rollback
identity. Phase237 captures and persists a rollback checkpoint before any live mutation, then requires
the deployment receipt to match the exact artifact digest and proposed revision. Phase238 persists
APPLIED state before running a fresh canary benchmark and commits only when the exact suite/artifact
meets sample floors, score floors, regression tolerances and non-negative aggregate delta; only then
does CanonicalEvolutionGate enter PROMOTED. Phase239 automatically restores the captured checkpoint on
apply failure, identity mismatch, canary regression or explicit post-commit rollback. Phase240 keeps a
bounded durable transaction index so restart recovery can roll back PREPARED/APPLIED/RECOVERY_REQUIRED
transactions instead of assuming partial evolution is safe. Promotion transaction state remains
non-authoritative for tools/device actions.

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