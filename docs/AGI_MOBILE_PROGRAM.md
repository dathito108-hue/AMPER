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


### Implemented checkpoint — Phase311-315
Phase311 adds a bounded durable multi-goal portfolio in Memory OS so long-horizon objectives no longer
exist only inside the in-memory GoalSystem. Phase312 wires the portfolio into AmperRuntime and excludes
its raw index record from ordinary planning recall. Phase313 mirrors live normalized goals into the
portfolio while selecting pending work from durable state, so a pending objective can be resumed after
process restart even before the live GoalSystem has reconstructed it. Phase314 writes a durable
completion tombstone whenever a persistent goal reaches verified COMPLETED state; observing the same
live goal ID again cannot resurrect that completed objective. Phase315 bounds retention to 32 pending
goals plus 64 recent completion tombstones, preserving deterministic priority ordering without
unbounded mobile-memory growth. The portfolio stores goal metadata only, carries no tool/device
authority, and does not execute or approve plan steps.


### Implemented checkpoint — Phase316-320
Phase316 extends each durable pending goal with restart-safe selection count and last-selected
timestamp metadata. Phase317 keeps priority as the dominant scheduling signal while a goal is within
its normal wait window. Phase318 adds a deterministic hard anti-starvation bound: once a pending goal
has waited at least 24 hours since first observation or its previous selection, starved goals are
chosen by oldest wait anchor before priority, preventing an endless stream of newer high-priority work
from suppressing older objectives forever. Phase319 upgrades the portfolio payload to V2 while
retaining V1 decoding with zero selection history. Phase320 routes PersistentGoalExecutive selection
through this durable selector and records selection before checkpoint handoff, so process restart
cannot reset fairness. Goal scheduling metadata remains orchestration-only and cannot widen
capabilities, grant authority, or approve side effects.


### Implemented checkpoint — Phase321-325
Phase321 adds up to eight durable prerequisite goal IDs and one optional absolute deadline to each
pending portfolio goal. Phase322 makes dependency mutation fail closed: prerequisites must already
exist, self-links are rejected, completed goal metadata is immutable and cycles are rejected before
persistence. Missing/pruned prerequisite tombstones conservatively keep dependents blocked rather
than assuming success. Phase323 adds bounded deadline urgency over a 24-hour horizon and combines it
with durable priority only among runnable, non-starved goals. Phase324 preserves the hard Phase318
anti-starvation lane above deadline urgency, so old runnable work cannot be suppressed by a stream of
fresh urgent goals; dependencies remain a hard gate even for overdue/starved dependents. Phase325
routes DurableGoalPortfolio.selectNext() through this combined arbitration and upgrades the portfolio
payload to V3 while retaining canonical V1 and fairness-V2 decoding. Dependency/deadline/fairness
metadata is scheduling evidence only and cannot bypass capability, authority, approval, resource or
governed execution gates.


### Implemented checkpoint — Phase326-330
Phase326 adds a strict one-inference decomposition protocol that classifies a newly selected durable
goal as ATOMIC or splits it into only 2-4 independently verifiable subgoals. Subgoal dependencies may
reference only earlier indices, making every generated structure a bounded DAG; the decomposition
model never supplies durable IDs, tool calls, execution claims, approvals or authority. Phase327
generates deterministic content-addressed child IDs inside AMPER, caps every child priority at its
parent priority, inherits the parent deadline and limits recursive decomposition to depth two.
Phase328 applies the entire decomposition to the durable portfolio in one persistence transaction,
fails closed when pending capacity or dependency bounds would be exceeded, marks the parent
DECOMPOSED and hard-blocks it on all generated children. Phase329 integrates decomposition before the
normal persistent-goal executive: explicit ATOMIC state and depth caps survive restart, while a
successful split releases the parent checkpoint so the existing dependency/deadline/fairness
scheduler can select runnable children without hot looping. Phase330 requires all children to reach
normal governed completion before the parent becomes runnable again; the parent is not auto-completed
by decomposition and must still create a normal governed plan and pass the existing terminal
goal-satisfaction verification. The portfolio payload is upgraded to V4 with V1/V2/V3 decoding.
Decomposition owns no ToolFabric or AuthorityGate handle and cannot widen capabilities, execute a
side effect or turn planning structure into evidence that the user objective succeeded.


### Implemented checkpoint — Phase331-335
Phase331 adds a deterministic hierarchy-progress projection over the durable goal portfolio. Direct
parent lineage is persisted for decomposition children, SUPERSEDED branch tombstones remain visible
for audit/history, and progress distinguishes active, completed, superseded, runnable and blocked
subgoals without treating superseded work as successful completion. Phase332 adds a strict
one-inference adaptive branch-replanning protocol that can return KEEP_BLOCKED or only 1-3 bounded
replacement objectives; replacement dependencies may reference earlier replacement indices only and
the model cannot emit tool calls, authority, approvals, execution or completion claims. Phase333
permits adaptive rewrite only for a pending hierarchy leaf after governed recovery is exhausted and
only when every terminal plan step is FAILED, MALFORMED or UNAVAILABLE: any executed, denied,
rejected, partial-execution or unresolved side-effect evidence is ineligible. The replan-attempt
marker is persisted before inference so each failed leaf receives at most one adaptive inference.
Phase334 atomically rewires only pending dependants from the failed leaf to deterministic replacement
IDs, caps replacement priority at the original/parent priority, preserves deadline and hierarchy
depth, retains all completed evidence unchanged, and releases the obsolete persistent checkpoint only
after the replacement transaction commits. Phase335 bounds replacement lineage to two generations,
integrates REPLANNED scheduling/UI state, and upgrades the durable portfolio payload to V5 with
V1/V2/V3/V4 decoding plus conservative V4 parent-lineage migration. Adaptive goal replanning owns no
ToolFabric or AuthorityGate handle and cannot turn structural adaptation into evidence that a parent
goal succeeded.


### Implemented checkpoint — Phase336-340
Phase336 adds bounded durable goal-outcome evidence keyed by hashed normalized goal terms rather than
raw goal text. Each record contains only a capability-sequence strategy signature, causal terminal
outcome, bounded hierarchy statistics and optional verification confidence; tool inputs/outputs,
request IDs, tool IDs and approvals are never persisted in this channel. Phase337 wires the outcome
learner into AmperRuntime and excludes its raw record/index kinds from ordinary sovereign-memory
recall. Phase338 records VERIFIED_SUCCESS only after the existing independent goal-satisfaction
verifier returns SATISFIED above its confidence threshold, records EXECUTION_EXHAUSTED only after
bounded recovery is truly exhausted with zero executed steps, and keeps authority, partial-execution
and evidence-exhaustion outcomes diagnostic rather than execution-skill failures. Phase339 performs
ephemeral similarity matching against hashed current-goal terms, groups analogous evidence by
capability sequence, and derives bounded transfer support from similarity, execution-attributable
success rate, evidence confidence and hierarchy completion. Phase340 exposes at most three
goal-outcome strategy candidates to planning only after live tool contracts, integrated cognition,
skill/generalization evidence and existing governed strategy guidance. Transfer is structural and
advisory: old inputs, outputs, approvals, request IDs and tool bindings cannot be reused, and every
new plan remains subject to current descriptor binding, AuthorityGate, confirmation, continuity and
goal-satisfaction verification.

### Implemented checkpoint — Phase341-345
Phase341 adds a deterministic counterfactual validator in front of Phase336-340 goal-outcome
strategy transfer. Every historical candidate is rebound to the exact current integrated cognitive
state digest and the currently selected live ToolDescriptor capability surface before it can reach
the planner. Phase342 derives current-context fit from world confidence, epistemic confidence,
execution-attributable capability competence, planning-eligible perceptual grounding and overall
readiness; current learning needs reduce fit and a severe need on any required capability hard-blocks
transfer. Phase343 rejects strategies whose live capability coverage is incomplete or whose
context/projected support falls below bounded thresholds instead of assuming historical similarity
implies present applicability. Phase344 reranks surviving candidates by projected support under the
current context. Historical authority-block counts remain diagnostic only and are deliberately absent
from the viability formula, preventing transfer learning from preferring routes around permission or
confirmation boundaries. Phase345 replaces direct historical transfer rendering with at most three
counterfactually validated structural candidates after live contracts, cognition, skills,
generalization and existing strategy evidence. Validation performs zero inference and zero tool
execution, transfers no old input/output/request/approval/tool-binding data, and every resulting plan
still passes canonical live binding, AuthorityGate, continuity, confirmation and terminal
goal-satisfaction verification.

### Implemented checkpoint — Phase346-350
Phase346 adds a restart-safe transfer-calibration model that records outcomes only for sovereign
plans carrying an exact validated goal-transfer binding. The binding contains the selected structural
strategy, exact planning cognitive-state digest, historical/context/projected support and no raw goal,
tool input/output, request ID, approval or authority token. Phase347 adds recency decay for historical
goal evidence and outcome-backed calibration multipliers; old evidence loses weight gradually rather
than remaining permanently trusted, while three evidence-backed consecutive transfer failures can
suppress that strategy. Fresh verified goal evidence newer than the last transfer failure may reopen
it cautiously; a subsequent successful transfer resets the failure streak. Authority/user blocks and
partial-execution states are diagnostic-neutral and never reduce transfer competence. Phase348
upgrades Plan OS to V7 while retaining V1-V6 decoding and persists transfer attribution across process
restart only when the final post-critic plan signature exactly matches a transfer candidate that was
actually rendered within the prompt budget. Phase349 calibrates a transfer-bound zero-executed FAILED
terminal plan immediately instead of waiting for recovery exhaustion, but all-executed plans receive
positive credit only after the existing independent goal-satisfaction verifier returns SATISFIED;
goal-evidence exhaustion is negative transfer evidence and authority/partial outcomes remain neutral.
Phase350 feeds the calibrated support back through counterfactual transfer validation so stale or
repeatedly failing strategies are down-ranked/suppressed before future planning. Calibration performs
zero inference and zero tool execution, cannot change live ToolDescriptors, AuthorityGate,
confirmation, continuity checks or goal verification, and cannot convert historical success into
execution permission.

### Implemented checkpoint — Phase351-355
Phase351 adds a restart-safe contextual strategy portfolio above the Phase341-350 validated/calibrated
transfer stack. Context is represented only by bounded hashed goal fingerprints; the portfolio stores
strategy signatures, selection counts, governed outcome counters and cumulative realized-regret proxy,
never raw goals, tool inputs/outputs, request IDs, tool IDs or approvals. Phase352 aggregates evidence
from sufficiently similar goal contexts and estimates exploitation from current calibrated support plus
contextual verified reward, with bounded regret penalty and evidence-confidence weighting. Phase353
adds deterministic bounded exploration: an under-sampled strategy may receive at most a 0.12 bonus,
that bonus decays with prior selections, and it is disabled when the strategy trails the best
exploitation score by more than 0.12. Exploration only reorders already counterfactually validated
guidance; it does not add capabilities, invoke inference, execute tools or alter authority. Phase354
persists the exact planId-to-context/strategy decision before outcome learning and updates reward/regret
only from the exact terminal plan. Verified success receives reward only after normal goal-satisfaction
verification; zero-executed failure/evidence exhaustion are negative, while authority/user blocks and
partial execution remain neutral diagnostics. Regret is explicitly a bounded realized proxy against
the best exploitation estimate available at selection time, not a fabricated counterfactual outcome
for an unchosen strategy. Phase355 feeds contextual reward/regret and bounded exploration back into
future candidate ordering while preserving Phase346 exact transfer attribution, live ToolDescriptor
binding, AuthorityGate, explicit confirmation, cognitive continuity, recovery limits and independent
terminal goal verification.

### Implemented checkpoint — Phase356-360
Phase356 adds restart-safe hierarchical strategy credit assignment over exact governed terminal plans.
Each observation is decomposed into individual STEP capability components, bounded PREFIX components
and the complete SEQUENCE, and is attached only to hashed current/root goal fingerprints plus durable
decomposition depth; raw goal IDs, tool inputs/outputs, tool IDs, request IDs and approvals are never
persisted in this learning channel. Phase357 keeps execution credit separate from end-goal credit:
verified success rewards executed steps/prefixes and the complete sequence, zero-executed failures
penalize only the concrete failing execution components, and goal-evidence exhaustion preserves
execution credit for steps/prefixes while penalizing only the complete sequence's goal contribution.
Authority/user blocks and partial execution remain diagnostic-neutral. Phase358 feeds exact
persistent-goal hierarchy lineage into credit observations and makes each plan outcome idempotent, so
restart or later recovery bookkeeping cannot double-count the same plan. Phase359 aggregates only
sufficiently similar hashed goal contexts and applies weighted step/prefix/sequence credit to future
strategy candidates; sibling branches with dissimilar objectives therefore do not inherit each
other's failure merely because they share a parent. Phase360 feeds the resulting hierarchical credit
back into the contextual strategy portfolio with a hard ±0.10 score adjustment bound. Credit can
reorder already counterfactually validated/calibrated candidates but cannot add capabilities, create
plans, invoke inference, execute tools, grant authority, reuse approvals, alter continuity checks or
replace independent goal-satisfaction verification.

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

### Implemented checkpoint — Phase361-365
Phase361 exposes bounded learning signals from negative hierarchical STEP/PREFIX/SEQUENCE credit
without exposing raw goals, tool payloads, approvals or tool ids. Phase362 keeps authority/user and
partial-execution evidence neutral, so denied actions cannot manufacture self-training pressure.
Phase363 feeds confidence-weighted negative credit into the existing autonomous weakness map as
HIERARCHICAL_STRATEGY_REPAIR while keeping real execution competence and practice competence separate.
Phase364 turns that pressure into bounded zero-tool repair curriculum over the current live capability
contract; practice output is still parsed only as a plan exercise and is never sent to ToolFabric.
Phase365 wires the restart-durable credit model into the canonical autonomous-learning runtime so
repeated governed strategy failures can redirect future practice automatically. Learning signals are
authority=false and cannot grant approval, permission, tool access or device/external authority.


### Implemented checkpoint — Phase366-370
Phase366 persists a restart-safe validation snapshot only for practice tasks explicitly sourced from
HIERARCHICAL_STRATEGY_REPAIR. Phase367 binds each repair generation to the newest negative governed
credit timestamp, so older practice cannot silently validate newer failures. Phase368 requires at
least two zero-tool repair attempts with an 80% pass rate before protocol repair becomes VALIDATED;
practice still creates no execution competence, skill credit, approval or authority. Phase369 lets
validated repair reduce autonomous repair pressure by at most 35% and attenuate a negative
hierarchical portfolio adjustment by at most 50%; the adjustment can approach zero but can never
cross into positive credit from practice alone. Phase370 invalidates the effective repair immediately
when newer negative execution evidence arrives and excludes repair journals from generic sovereign
context retrieval. Real governed success remains the only path that can turn hierarchical credit
positive.


### Implemented checkpoint — Phase371-375
Phase371 separates zero-tool repair validation from real-world requalification: a repair becomes
REQUALIFIED only after a later governed VERIFIED_SUCCESS on a strategy that actually uses the repaired
capability. Phase372 persists restart-safe real-outcome markers and makes replay idempotent without
copying raw goals, tool payloads, approvals or tool ids. Phase373 treats execution exhaustion and
goal-evidence exhaustion as real repair invalidation while authority/user and partial-execution
outcomes remain neutral. Phase374 enables structural repair transfer only through capability-sequence
similarity; transfer requires at least 0.50 structural similarity and is confidence weighted. Phase375
feeds requalified evidence back into learning and contextual strategy ranking with hard bounds:
requalified learning-pressure relief is capped at 60% and negative portfolio-penalty attenuation at
80%. Neither path can mint authority, count practice as execution, or turn a negative hierarchical
adjustment positive; real governed success still updates hierarchical credit independently.


### Implemented checkpoint — Phase376-380
Phase376 derives a time-aware requalification assessment at read time, so aging requires no timer,
background loop or memory write. Phase377 keeps full real-world repair confidence for seven days,
then linearly decays it until hard expiry at thirty days. Phase378 makes a later VERIFIED_SUCCESS renew
the same repair generation, reset recency and deepen its real-execution confidence. Phase379 prevents
an invalidated repair from being requalified by a later success unless fresh HIERARCHICAL_STRATEGY_REPAIR
practice occurred after the latest real failure. Phase380 applies the same aged effective confidence
to learning-pressure relief and structural strategy transfer; expired or invalidated evidence
contributes zero, while authority/user outcomes remain neutral. Aging never changes authority and
never turns practice into execution evidence.


### Implemented checkpoint — Phase381-385
Phase381 distills only real-world requalified repair evidence into restart-safe structural strategy
memory keyed by ordered capability sequence. Phase382 requires two consecutive governed
VERIFIED_SUCCESS outcomes before a distilled pattern becomes active; practice-only evidence cannot
enter this memory. Phase383 resets the consecutive-success streak on execution or goal-evidence
failure while authority/user and partial-execution outcomes remain neutral. Phase384 retrieves
active patterns only when structural similarity is at least 0.75 and the target strategy still has
live, non-expired requalification coverage for every required capability. Phase385 adds at most
+0.03 bounded portfolio support from the distilled memory. This bonus never modifies hierarchical
credit, never widens tool admission or approval, and disappears when live requalification expires
or is invalidated.


### Architecture audit and de-duplication checkpoint — Phase386-390
A canonical architecture audit after Phase385 found no duplicate class names or memory-kind
collisions in the active goal-learning stack, but it did find duplicated decision influence.
The legacy StrategyLearning -> EvidenceGroundedStrategyGuidance -> GoalConditionedStrategyRetrieval
path and the newer verified goal-outcome portfolio path were both able to inject historical strategy
evidence into the same planner prompt. Phase386 removes the legacy path from canonical planning while
retaining StrategyLearning and GoalConditionedStrategyRetrieval as diagnostic/compatibility utilities.
Phase387 establishes GoalOutcomeLearning -> counterfactual validation -> transfer calibration ->
contextual portfolio as the single canonical strategy-history planning path. Phase388 centralizes
repair structural similarity in StrategyStructuralSimilarity so repair validation and distilled
repair memory cannot silently diverge. Phase389 confirms GoalVerification and
GoalSatisfactionVerification are not duplicates: the former verifies hierarchical subgoals while the
latter verifies persistent/root-goal satisfaction after an all-executed plan. Phase390 keeps the
generic LearningConsolidation utility outside canonical runtime/planning; it must not become a second
strategy-memory influence path without an explicit migration. No authority, tool admission, approval
or execution semantics are widened by this cleanup.
