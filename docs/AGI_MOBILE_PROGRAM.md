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

### Implemented checkpoint — Phase241-245
Phase241 derives bounded benchmark-gap objectives directly from the current content-addressed baseline.
Phase242 gives the cognitive model one bounded inference to emit data-only MODEL/CODE/STRATEGY/
ARCHITECTURE blueprints through a strict Base64URL protocol; model output cannot assert artifact,
build, test, benchmark, authority or deployment success. Phase243 sends each blueprint to an injected
sandbox runner whose measured artifact digest, tests, invariant results and benchmark metrics become
the only candidate evidence. Phase244 derives candidate identity from blueprint digest plus sandbox
artifact digest, filters sandbox failures and automatically runs the surviving entries through the
canonical tournament. Phase245 automatically requests a canonical promotion ticket for the winner
while keeping live mutation exclusively inside the Phase236-240 promotion transaction path. Raw
blueprint payloads are not persisted into sovereign planning memory.

### Implemented checkpoint — Phase246-250
Phase246 performs promotion crash recovery before any new autonomous evolution campaign may start and
blocks new mutation while RECOVERY_REQUIRED remains unresolved. Phase247 reads a content-addressed
canonical baseline and runs the existing benchmark-gap candidate campaign. Phase248 hands only the
canonical immutable promotion ticket to the transactional Phase236-240 executor for checkpoint,
exact apply and canary. Phase249 advances the benchmark baseline through an injected compare-and-set
baseline port only after COMMITTED canary success; baseline advancement failure triggers post-commit
rollback. Phase250 closes the loop under a strict four-cycle maximum with explicit NO_GAP,
NO_CANDIDATE, NO_WINNER, ROLLED_BACK and RECOVERY_BLOCKED stop states. The orchestrator contains no
ToolFabric path and cannot widen authority.

### 251+ — Open-ended mobile intelligence
Integrate model, world state, memory, skills, multimodal perception and self-evolution; measure
progress by capability/generalization benchmarks rather than phase count.

### Implemented checkpoint — Phase251-255
Phase251 creates one ephemeral integrated cognitive state per decision from reconciled semantic and
epistemic evidence plus predictive world state. Phase252 folds live skill, composition,
cross-context transfer and generalized-chain evidence into that same bounded packet. Phase253 derives
explicit readiness, uncertainty and active-learning pressure without turning those diagnostics into
authority or permission. Phase254 binds the planning prompt to the packet's canonical digest and uses
its world/skill evidence instead of independently recapturing those subsystems. Phase255 gives the
independent reflective critic the exact same packet/digest, eliminating planner-versus-critic state
drift inside one decision while keeping live ToolDescriptor binding, side-effect classification,
AuthorityGate and explicit approval external and authoritative. The packet is ephemeral and is not
persisted as raw goal/context memory.

### Implemented checkpoint — Phase256-260
Phase256 derives a deterministic metacognitive control mode (DIRECT, DELIBERATE or CAUTIOUS) from the
integrated cognitive readiness/uncertainty state. Phase257 preserves the user's frozen planning
profile exactly while allocating a bounded mode-sensitive critic budget inside that profile.
Phase258 turns uncertainty into explicit deliberation-depth, evidence-caution and side-effect-caution
guidance without adding an inference loop or executing a tool. Phase259 gives the independent critic
a stricter temperature/budget under uncertainty while retaining the normal live descriptor and
authority checks. Phase260 freezes one metacognitive directive per decision and binds both planner
and critic to the same cognitive-state digest, so policy cannot drift between the two passes.
Metacognitive state remains authority=false and cannot approve side effects, modify ToolDescriptors
or widen device/external authority.


### Implemented checkpoint — Phase261-265
Phase261 projects canonical perception.* world observations into typed bounded perceptual evidence.
Phase262 preserves source/producer/confidence provenance and continues to exclude raw image/audio
attachment bytes from Memory OS and cognitive prompts. Phase263 classifies observations as FRESH,
RECENT or STALE so old device state cannot silently remain planning-eligible. Phase264 fuses the
bounded percept snapshot into the integrated cognitive digest/readiness and lets current perception
contribute to world confidence and uncertainty. Phase265 gives the independent critic the exact same
percept snapshot/digest seen by planning, eliminating live-perception drift across a single decision.
Perceptual evidence remains authority=false and cannot itself trigger capture, execute a tool or
grant device/external permission.


### Implemented checkpoint — Phase266-270
Phase266 stores the exact integrated cognitive-state digest on each newly created/recovered plan for
audit and reproducibility. Phase267 derives a second execution-context digest that includes sovereign
identity/invariants, goal state, reconciled semantic/epistemic evidence, predictive world/causal state
and freshness-aware perceptual grounding while intentionally excluding competence/skill/readiness
signals that can legitimately change after each governed step. Phase268 recaptures this bounded context
before every plan advance and returns CONTEXT_CHANGED instead of invoking ToolFabric when the plan is
stale. Phase269 applies the same continuity check to approval binding and final approved execution so a
previously valid side-effect approval path cannot outlive changed grounded context. Phase270 keeps
legacy unbound plans on the pre-existing live ToolDescriptor/recovery revalidation path rather than
inventing a historical digest. Continuity evidence is authority=false and never grants execution,
approval or device authority.


### Implemented checkpoint — Phase271-275
Phase271 makes a nonterminal V6 plan eligible for context refresh only after its execution-context
digest has actually changed; stable context and legacy-unbound plans cannot consume a refresh
inference. Phase272 binds the refresh prompt to the new cognitive/metacognitive state plus the old and
new execution-context digests, with terminal parent-step outcomes carried only as bounded historical
guidance. Phase273 performs exactly one fresh planning inference plus the existing bounded optional
critic, creates a child plan with a new continuity binding and forbids reuse of parent action request
IDs. Phase274 persists the child plan and lets the Android execution console automatically replace a
stale active plan without invoking ToolFabric; old pending approvals are explicitly non-transferable.
Phase275 caps context-refresh lineage at three replacement generations and blocks refresh while an
unresolved durable side-effect claim exists. Replanning remains planning-only: it cannot execute a
tool, approve a side effect or widen device/external authority.


### Implemented checkpoint — Phase276-280
Phase276 adds a deterministic autonomous cognitive executive over the exact integrated cognitive
packet and continuity digest. Phase277 gives evidence acquisition priority when relevant perceptual
grounding is stale or epistemic belief remains unresolved under high world uncertainty. Phase278
routes high learning pressure into bounded zero-tool autonomous practice and recaptures cognition
after each assessed practice cycle. Phase279 may escalate repeated, evidence-backed governed
execution-reliability weakness into exactly one existing transactional autonomous-evolution cycle
through an injected evolution port; benchmark, rollback, canary and containment rules remain
authoritative. Phase280 exposes the executive from SovereignPlanCoordinator and caps a cognitive run
at four decisions; only successful practice may continue within the same run, while PLAN, OBSERVE and
EVOLVE terminate the call. The executive owns no ToolFabric or AuthorityGate handle and cannot turn
practice/evolution evidence into device authority or claim that a planned action executed.


### Implemented checkpoint — Phase281-285
Phase281 selects the highest-priority active sovereign goal for autonomous cognitive work rather than
requiring the caller to restate that objective. Phase282 persists one bounded goal checkpoint in
Memory OS, including conversation, priority, stage and the latest cognitive/execution-context digests;
production encrypted Memory OS therefore carries the checkpoint across process restart, and the raw
checkpoint kind is excluded from ordinary planning recall. Phase283 hands the selected objective to
the existing bounded autonomous cognitive executive and persists any resulting SovereignPlan before
recording a PLANNED handoff. Phase284 resumes WAITING_OBSERVATION, LEARNING_PAUSED and
EVOLUTION_PAUSED checkpoints but blocks duplicate planning while a PLANNED handoff is outstanding.
Phase285 requires explicit matching plan-completion acknowledgement before closing that checkpoint and
selecting a different active goal. The persistent goal executive owns no ToolFabric/AuthorityGate
handle and cannot execute or approve a plan step.


### Implemented checkpoint — Phase286-290
Phase286 inspects the exact persisted terminal SovereignPlan before deciding whether a persistent goal
may recover. Phase287 automatically requeues only terminal FAILED/MALFORMED/UNAVAILABLE plans with
zero executed steps; DENIED/REJECTED plans remain blocked so autonomy cannot retry around authority or
user rejection. Phase288 blocks recovery when any step already executed or when durable side-effect
claims remain unresolved, preventing duplicate real-world effects. Phase289 persists a recovery
generation counter and stops after three fresh recovery handoffs. Phase290 upgrades the checkpoint
codec to V2 while retaining V1 decoding, so recovery lineage survives restart and older checkpoints
remain readable. Goal recovery still performs planning/cognitive work only and owns no ToolFabric or
AuthorityGate path.


### Implemented checkpoint — Phase291-295
Phase291 adds a bounded active-perception acquisition contract bound to the current cognitive-state
and execution-context digests. Phase292 lets an OBSERVE executive cycle request exactly one targeted
fresh modality, publish a returned reduced Percept into the canonical PerceptionBus, recapture
cognition and continue within the existing four-cycle executive cap. Failed acquisition remains a
terminal OBSERVE result and consumes no planning, practice or evolution work. Phase293 provides an
Android adapter over the existing live-perception paths: SENSOR uses available device sensors, AUDIO
requires an already-granted RECORD_AUDIO permission, and SCREEN consumes only a fresh frame from an
already-active user-approved MediaProjection session; CAMERA and new permission/consent flows remain
UI-driven. Raw audio/screen media stays ephemeral and only reduced bounded percept summaries enter the
world model. Phase294 exposes the observation port through SovereignPlanCoordinator and the persistent
goal executive. Phase295 wires the Android UI to run bounded autonomous goal cycles, rebinds a
persistent goal checkpoint when context-refresh creates a direct child plan, and resolves the exact
goal handoff when that plan later becomes terminal. Active perception cannot grant tool/device
authority or bypass Android permission/MediaProjection consent.


### Implemented checkpoint — Phase296-300
Phase296 adds a scheduler that performs at most one persistent-goal executive run per tick. Phase297
checks a host resource gate before any cognitive work; Android binds this to the existing live
thermal/memory ResourceGovernor. Phase298 applies checkpoint-aware backoff: observation gaps retry
quickly while planned, blocked and no-goal states remain quiet, preventing hot polling. Phase299 uses
an atomic single-flight interlock so concurrent scheduler calls return BUSY instead of spawning
competing cognition. Phase300 adds a process-resident single-thread autonomy loop and Android Start /
Stop control. The loop owns no wake lock, alarm, WorkManager dependency, foreground service,
ToolFabric or AuthorityGate handle, shuts down with the Activity process lifecycle, and relies on the
existing encrypted persistent-goal checkpoint for restart continuity. It automates cognitive/goal
orchestration while leaving plan-step execution and side-effect authority in the governed execution
layer.


### Implemented checkpoint — Phase301-305
Phase301 loads only the exact persisted SovereignPlan currently handed off by the persistent-goal
checkpoint and caps each autonomous execution call to TitanPlanProtocol.MAX_STEPS. Phase302 advances
that plan only through PersistentSovereignPlanCoordinator, preserving live ToolDescriptor rebinding,
AuthorityGate checks, audited ToolFabric execution and existing durable receipts. Read-only/otherwise
immediately authorized steps may therefore progress autonomously, but Phase303 stops at
REQUIRES_CONFIRMATION without calling approve/approveBound; a nonterminal FAILED/DENIED/
UNAVAILABLE/MALFORMED outcome moves the goal checkpoint to EXECUTION_PAUSED before any later step can
run. Phase304 handles cognitive-context drift only through the existing zero-tool refresh path and
rebinds the exact persistent-goal handoff to the fresh child plan. Phase305 lets the resource-gated
autonomy scheduler invoke this runner for PLANNED checkpoints, reports plan-progress state, and
resolves terminal plans through the existing bounded recovery policy. Autonomous execution never
manufactures approval, bypasses side-effect gates, or retries past an execution failure inside the
same plan chain.


### Implemented checkpoint — Phase306-310
Phase306 adds a strict independent goal-satisfaction protocol over the exact all-executed terminal
plan and the latest integrated cognitive evidence. The verifier may return only SATISFIED or
FOLLOW_UP_REQUIRED; a SATISFIED result below 0.80 confidence is deterministically downgraded. Phase307
wires a bounded reasoning-only verifier through SovereignPlanCoordinator and preserves the complete
verification envelope under the prompt budget. Phase308 changes the autonomous governed runner so
successful tool execution no longer closes a persistent goal by itself: the exact terminal plan is
verified before completion, while failed/denied terminal plans continue through the existing recovery
path. Phase309 persists a follow-up lineage separate from execution recovery, requeues the same goal
when evidence is insufficient, and blocks after three bounded follow-up generations instead of
looping indefinitely. Phase310 upgrades the persistent-goal codec to V3 while retaining V1/V2
decoding and stores the last verification verdict, confidence and concise reason across restart.
Goal verification has no ToolFabric/AuthorityGate handle, cannot approve side effects, and can only
change goal-orchestration state after an already-terminal all-executed plan.

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