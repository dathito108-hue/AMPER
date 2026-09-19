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


### Implemented checkpoint — Phase391-395
Phase391 introduces GoalOutcomeSemantics as the single canonical definition of terminal
outcome/status compatibility. Phase392 migrates transfer calibration and contextual strategy
portfolio to the shared validator and pre-verification classifier. Phase393 migrates hierarchical
strategy credit so malformed and unavailable terminal execution failures are classified identically
across all learning layers. Phase394 applies the same contract to repair requalification, distilled
repair strategy memory and root goal-outcome learning while preserving each layer's own stage,
verification and binding invariants. Phase395 adds cross-layer regression coverage for all-executed,
malformed, unavailable, authority-blocked and partial-execution terminal plans. All-executed plans
remain unclassified before satisfaction verification; authority/user outcomes remain neutral where
their owning learning policy specifies neutrality. This phase removes duplicated outcome semantics
without adding a new planning influence layer.


### Implemented checkpoint — Phase396-400
Phase396 adds a verified-governed-experience schema for AMPER-native training. Only root-goal
VERIFIED_SUCCESS outcomes at or above the satisfaction-confidence threshold are admitted. Raw goals,
reasons, tool inputs, tool outputs, approvals and tool ids are never persisted in this dataset;
examples contain only hashed goal fingerprints, ordered capability strategies and verification
confidence. Phase397 wires persistent goal completion into the experience store with idempotent
per-plan example identity. Phase398 materializes immutable GENERATED_INTERNAL dataset shards with
real canonical payload bytes, SHA-256, example count, byte count and capability coverage, then
registers the manifest through the existing NativeModelFoundation. Phase399 extends
NativeTrainingRequest with optional generatedExperienceShards so a concrete NativeTrainerPort can
consume AMPER-generated payloads rather than receiving metadata alone; existing external datasets
remain compatible. Phase400 exposes the store through AmperRuntime, excludes its records from generic
sovereign prompt context, and preserves the existing checkpoint/admission/promotion gates. Experience
data is non-authoritative and cannot register or promote a live model by itself.


### Implemented checkpoint — Phase401-405
Phase401 adds evidence-derived curriculum synthesis over the exact immutable verified-experience
shard selected for training. Phase402 counts capability coverage only from examples contained in that
shard and excludes capabilities below a configurable minimum-evidence threshold. Phase403 orders
curriculum stages by observed strategy position, then evidence depth, without inventing difficulty
labels or synthetic successes. Phase404 registers the resulting bounded NativeCurriculumManifest in
the existing NativeModelFoundation and binds it to the shard's canonical dataset snapshot digest.
Phase405 exposes the planner through AmperRuntime as nativeExperienceCurriculum. The planner never
invokes a trainer, never promotes a model, and carries no authority; it only turns verified internal
experience into a reproducible dataset+curriculum bundle for the existing native training pipeline.


### Implemented checkpoint — Phase406-410
Phase406 adds a canonical verified-experience training coordinator that compiles the existing
immutable NativeExperienceTrainingBundle into the existing NativeDistillationManifest and
NativeTrainingRun types instead of creating a parallel trainer stack. Phase407 binds the exact shard,
dataset snapshot digest, synthesized curriculum digest, AMPER-owned student contract, eligible teacher
identities, bounded training recipe and mobile target into one immutable manifest. Phase408 makes
preparation restart-safe: an existing shard/curriculum/manifest/PREPARED run is reconstructed and must
match the same spec exactly before it is reused. Phase409 inherits the existing native pipeline's
teacher-rights, capability-coverage, parent-lineage, dataset-rights and Android-arm64 gates and adds no
authority surface. Phase410 exposes the coordinator as nativeExperienceTraining through AmperRuntime.
Preparation never invokes NativeTrainerPort, never creates a checkpoint, never registers a live model
and never promotes a model; execution, held-out evaluation, admission and runtime promotion remain the
existing downstream gates.


### Implemented checkpoint — Phase411-415
Phase411 gives every NativeTrainingRequest a canonical executionBindingDigest over the exact run,
manifest, student contract, curriculum, dataset manifests, generated-experience shard payload digests,
teacher artifact identities and optional parent checkpoint weights. Phase412 extends
NativeTrainingArtifact with backward-compatible optional execution-binding, dataset-snapshot and
curriculum-digest echoes. Phase413 requires all three echoes for any request that carries generated
verified-experience shards; a mismatch fails the run before checkpoint publication. Phase414 persists
the request execution binding on newly successful NativeTrainingRun records while keeping legacy run
decoding compatible when the field is absent. Phase415 adds regression coverage proving a correctly
bound trainer can publish the immutable checkpoint lineage, while a trainer that returns the correct
run/manifest identity but the wrong verified-experience binding produces a FAILED run and no
checkpoint. These checks do not promote or register the model; held-out evaluation, admission and
runtime promotion remain downstream gates.


### Implemented checkpoint — Phase416-420
Phase416 adds deterministic train/holdout partitioning over verified AMPER experience using an
identity-derived SHA-256 ordering rather than insertion order. Phase417 extends the canonical
NativeExperienceDatasetStore with explicit example-set shard materialization so both sides retain the
same GENERATED_INTERNAL rights, payload SHA-256 and provenance guarantees as earlier shards.
Phase418 makes train and holdout shards strictly disjoint while requiring their union to equal the
exact bounded selected example set. Phase419 allows the existing curriculum/training coordinator to
reuse a pre-materialized training shard unchanged; because materializeShard returns the immutable
existing shard, holdout examples cannot leak back into curriculum synthesis or training. Phase420
exposes the partitioner as nativeExperiencePartition through AmperRuntime and fails closed when the
verified-experience pool cannot satisfy both minimum training and holdout depths. The holdout shard is
data only: it grants no authority and is not automatically used for checkpoint admission until a
later evaluation phase explicitly consumes it.


### Implemented checkpoint — Phase421-425
Phase421 introduces a typed AMPER Reflex Decision Cortex contract ahead of System-2 assistant
inference. A reflex decision can only escalate or propose one typed ActionProposal with bounded
confidence/uncertainty; it carries no authority. Phase422 adds a deterministic on-device bootstrap
implementation for a narrow set of explicit assistant intents (current device status, AMPER runtime
status, Android settings, timers and explicit share requests). Unknown or ambiguous requests fail
closed to the existing System-2 path. Phase423 wires high-confidence READ_ONLY reflex decisions into
SovereignAssistantTurn so canonical status requests can execute through the normal SovereignActionLoop,
ToolDescriptor and AuthorityGate with zero LLM inference passes, then return a bounded deterministic
result. Phase424 lets equally explicit side-effect intents skip the first LLM proposal pass but only
produce the same durable PendingApproval checkpoint used by the existing assistant; no side-effect
tool is invoked before explicit approval, and post-approval synthesis remains governed by the existing
System-2 path. Phase425 exposes ReflexDecisionCortex through AmperRuntime and keeps multimodal turns,
VERIFY reflection mode, missing descriptors, unsupported commands and uncertain intent on the existing
System-2 path. The bootstrap classifier is replaceable by a future AMPER-owned native System-1 model
using the same typed interface, allowing Phase396-420 verified experience/training infrastructure to
train faster learned decisions without changing the authority boundary or the AGI-mobile target.


### Implemented checkpoint — Phase426-430
Phase426 adds a restart-safe ReflexExperienceDatasetStore for System-1 decision learning. Raw user
text is converted ephemerally into bounded hashed unigram/bigram features; raw prompts, action reasons,
tool inputs, tool outputs, tool ids, approvals and conversation ids are never retained in the training
example. Phase427 admits successful governed actions as typed ACTION targets containing only the live
capability and side-effect class, and records ordinary completed System-2 no-action turns as
conservative ESCALATE_SYSTEM2 teacher examples. Side-effect proposals are not admitted merely because
they were proposed: Reflex/System-2 side effects enter the action dataset only after explicit approval
and terminal EXECUTED outcome. Phase428 wires both zero-LLM Reflex executions and legacy System-2 tool
executions into the same dataset while keeping one canonical live-descriptor projection; rejected,
denied, unavailable, malformed and failed actions are not promoted to positive training labels.
Phase429 materializes bounded immutable GENERATED_INTERNAL decision shards through the existing
NativeModelFoundation with target capability reflex-decision, canonical payload SHA-256 and provenance,
so future native System-1 training can reuse the established dataset-rights/checkpoint pipeline instead
of creating a parallel trainer stack. Phase430 exposes the dataset through AmperRuntime, excludes its
records from generic sovereign prompt retrieval, and corrects Reflex side-effect telemetry so a
proposal created with zero LLM passes plus one post-approval synthesis reports one inference pass.
Decision experience is non-authoritative and cannot execute a tool, grant approval or promote a model.


### Implemented checkpoint — Phase431-435
Phase431 adds deterministic stratified train/holdout partitioning for Reflex experience. ACTION and
ESCALATE_SYSTEM2 examples are ordered independently by immutable example identity, so both training
and holdout retain class coverage and insertion order cannot leak examples across the split. Phase432
adds an evidence-backed one-stage NativeCurriculumManifest for the canonical reflex-decision
capability and requires minimum training evidence from both decision classes. Phase433 introduces a
ReflexDecisionTrainingCoordinator that compiles only the training shard into the existing
NativeDistillationManifest/NativeTrainingRun path; the disjoint holdout shard is explicitly excluded
from the training manifest for later evaluation. Phase434 extends NativeTrainingRequest
backward-compatibly with generatedReflexExperienceShards so NativeTrainerPort receives the real
privacy-preserving System-1 payload bytes rather than manifest metadata alone. The canonical execution
binding digest includes those immutable reflex payload digests. Phase435 applies the existing generated
dataset echo gates to Reflex training as well: the trainer must echo the exact execution binding,
dataset snapshot and curriculum digest before a checkpoint can be published. AmperRuntime now exposes
reflexExperiencePartition, reflexDecisionCurriculum and reflexDecisionTraining. Preparation/training
remains non-authoritative; no checkpoint becomes a live model without downstream held-out evaluation,
admission and runtime promotion.


### Architecture de-duplication checkpoint — Phase436-440
Phase436 audits the post-System-1 training architecture and confirms NativeExperience and
ReflexExperience are separate evidence domains, not duplicate learning paths: the former trains
verified root-goal/strategy intelligence while the latter trains typed assistant routing decisions.
Phase437 confirms assistant execution does not double-record a governed action: read-only Reflex,
approved Reflex side effects and System-2 tool actions each contribute at most one successful action
label. Phase438 removes the remaining type-specific payload plumbing from NativeTrainingRequest.
All AMPER-generated training data now enters NativeTrainerPort through one
NativeGeneratedDatasetPayload envelope carrying an explicit dataset kind, immutable manifest and
verified payload bytes. Phase439 replaces separate native/reflex payload branches in
MemoryBackedNativeTrainingPipeline with one NativeGeneratedDatasetResolver; production composes the
verified-goal and Reflex stores through CompositeNativeGeneratedDatasetResolver, which rejects a shard
id resolving from multiple providers. Phase440 binds dataset kind + manifest id + payload digest into
the existing executionBindingDigest and retains the same generated-data trainer echo gates. Future
vision/audio/evolution datasets can add resolvers without adding fields to NativeTrainingRequest or
creating a second trainer stack. No authority, model promotion, tool execution or approval boundary is
changed by this unification.


### Implemented checkpoint — Phase441-445
Phase441 makes native checkpoint evaluation capability-aware instead of forcing every model family
through System-2 planning/tool/generalization gates. Contracts containing ordinary cognitive
capabilities retain the existing four foundation gates unchanged; a reflex-decision-only contract is
evaluated by its own held-out decision evidence. Phase442 adds a ReflexDecisionEvaluatorPort that sees
only immutable checkpoint lineage plus privacy-preserving hashed holdout examples and must return one
typed prediction for every exact holdout example. Phase443 computes exact decision accuracy, action
precision, escalation recall, capability accuracy and mean absolute confidence-calibration error
inside AMPER rather than trusting evaluator-supplied aggregate metrics. The resulting metrics are
bound to the exact holdout shard id and payload SHA-256. Phase444 adds Reflex admission gates requiring
at least 32 total held-out samples, both classes to have evidence, high action precision/route accuracy,
high escalation recall and bounded calibration error; the holdout must be GENERATED_INTERNAL,
reflex-decision capable, digest-matching and outside checkpoint training lineage. Phase445 keeps one
canonical NativeTrainingPipeline evaluation/admission/promotion path. Evaluation record encoding is
upgraded backward-compatibly, checkpoint comparison uses only metrics relevant to the model capability
profile, and Reflex candidate/baseline comparison requires the same holdout payload. No evaluator can
execute tools, grant authority or make a checkpoint live.


### Implemented checkpoint — Phase446-450
Phase446 adds a typed NativeReflexDecisionPort for an admitted AMPER-owned System-1 checkpoint. The
runtime port receives only the same hashed lexical features used by Reflex training plus the current
live capability set; it has no ToolFabric, approval or authority handle. Phase447 adds a canonical
runtime activation gate that recomputes eligibility from immutable checkpoint lineage, AMPER-owned
reflex-decision-only contract, held-out admission and the existing NativeTrainingPipeline promotion
candidate, and requires the runtime weight SHA-256 to match the checkpoint exactly. Phase448 introduces
CanonicalReflexActionArgumentBinder: the learned model may predict only ACTION-vs-ESCALATE and a
capability; raw tool input is never accepted from model output. The existing deterministic Reflex
parser must independently recognize the same request/capability and produce the contract-valid input,
otherwise the turn escalates to System-2. Phase449 adds CanonicalReflexDecisionRuntimeController with
atomic in-process activation/rollback. Missing model, runtime failure, unsupported capability,
low-confidence/uncertain action or failed argument binding cannot execute a tool; runtime failure falls
back to the deterministic bootstrap and unsafe learned output escalates. Phase450 wires the controller
as AmperRuntime.reflexDecisionCortex while exposing reflexDecisionRuntime for explicit checkpoint
activation. This is the first runtime bridge from an admitted learned System-1 checkpoint into the
assistant fast path, without changing SovereignActionLoop, ToolDescriptor, AuthorityGate or explicit
approval semantics.


### Implemented checkpoint — Phase451-455
Phase451 adds a durable ReflexRuntimeActivationIntent containing only checkpoint id, immutable weight
SHA-256, ACTIVE/DISABLED status, monotonic generation and timestamps; backend objects, prompts, tool
data and approvals are never serialized. Phase452 persists ACTIVE intent only after the learned port
passes the existing canonical activation gate, and the in-memory port is published only after the
durable record round-trips successfully. Phase453 adds NativeReflexDecisionPortResolver and restart
recovery: a new process resolves the exact checkpoint/hash to a fresh runtime port, verifies the
returned identity, and reruns the full checkpoint/admission/promotion activation gate before restoring
System-1 service. A missing or invalid backend leaves learned Reflex inactive and preserves fail-safe
bootstrap/System-2 behavior. Phase454 makes rollback durable by writing a newer DISABLED generation
before clearing the in-memory port, preventing an older checkpoint from silently reactivating after a
restart. Phase455 wires the MemoryOs-backed activation store into AmperRuntime, exposes the persisted
intent through the runtime controller, and excludes activation records from generic sovereign prompt
retrieval. Persistence carries no tool authority and cannot itself instantiate or approve a model.


### Implemented checkpoint — Phase456-460
Phase456 adds a baseline-aware Reflex runtime replacement gate. Initial activation still requires the
canonical checkpoint/admission/promotion gate; replacing an active champion additionally requires the
challenger to be promotable against that exact baseline through NativeTrainingPipeline comparison.
Phase457 adds bounded runtime health telemetry for learned System-1 inference: total predictions,
prediction failures, consecutive failures, slow predictions, consecutive slow calls, last/max latency
and automatic rollback count. Phase458 enforces configurable operational failure/latency budgets
(default three consecutive prediction failures or three predictions above 250 ms). Low confidence or
argument-binding rejection remains task uncertainty and does not poison runtime health. Phase459 keeps
the prior champion as an in-process standby during a validated replacement; if the challenger exceeds
the health budget, AMPER durably restores the champion identity before switching the live port back.
When no standby exists, the unhealthy learned model is durably disabled and assistant routing falls
back to the deterministic Reflex bootstrap/System-2 path. Phase460 wires canonical replacement
comparison into AmperRuntime. Explicit rollback still disables learned Reflex rather than restoring a
standby, and no health/replacement mechanism changes tool authority, approval or safe argument binding.


### Implemented checkpoint — Phase461-465
Phase461 adds ReflexRuntimeCalibration with a held-out-evidence prior and durable privacy-preserving
runtime aggregates. Calibration stores only checkpoint identity, bounded counters/EWMAs and temporary
ActionRequestId-to-checkpoint/confidence bindings; raw prompts, tool inputs/outputs and approval
content are excluded. Phase462 feeds measured learned-System-1 prediction latency into an EWMA and
adapts the operational slow-call budget only after sixteen samples, bounded to 100-350 ms. The runtime
health controller uses this adaptive budget under its existing hard health ceiling, so calibration
cannot make an arbitrarily slow model healthy. Phase463 derives per-checkpoint confidence/uncertainty
gates from admitted held-out action precision and calibration error, then permits online governed
action evidence to adjust the confidence threshold by only +/-0.002 after at least thirty-two resolved
samples. The absolute confidence range remains 0.985-0.997. Phase464 binds learned Reflex decisions to
their ActionRequestId before ActionLoop evaluation and resolves calibration only from terminal governed
outcomes. EXECUTED is positive; MALFORMED/UNAVAILABLE are negative; REQUIRES_CONFIRMATION remains
pending; authority DENIED and tool-runtime FAILED are neutral. Pending bindings survive restart and are
removed on explicit rejection. Phase465 propagates the exact adaptive threshold inside ReflexDecision
so the runtime and assistant fast-path gate cannot disagree, wires the MemoryOs-backed calibrator into
AmperRuntime, and excludes calibration records from generic sovereign prompt retrieval. Calibration is
non-authoritative and cannot bypass safe argument binding, ToolDescriptor, AuthorityGate or approval.


### Implemented checkpoint — Phase466-470
Phase466 adds ReflexRuntimeResourcePolicy as a small scheduling input to the existing learned Reflex
controller, reusing ResourceGovernor rather than creating a second Android telemetry stack. Phase467
maps live governor memory/thermal pressure into NORMAL, PRESSURED or BLOCKED per-turn decisions. A
critical governor denial, thermal class >=4 or memory budget below 96 MiB blocks learned inference;
severe pressure (thermal >=3 or memory below 192 MiB) also blocks it. Phase468 allows moderate pressure
(thermal >=2 or memory below 384 MiB) only when the learned checkpoint's calibrated latency EWMA is
unknown or <=180 ms. Resource skips immediately fall back to deterministic Reflex/System-2 and do not
alter checkpoint activation. Phase469 records only in-memory resource scheduling telemetry in the
existing ReflexRuntimeHealthSnapshot (skip count plus last mode/memory/thermal); resource pressure is
not persisted as model-failure evidence and cannot trigger champion demotion by itself. Phase470 wires
ResourceGovernorReflexRuntimeResourcePolicy into AmperRuntime, so AndroidResourceGovernor's existing
live RAM/thermal signals now directly shape System-1 scheduling without duplicating device monitoring.
No resource policy can grant tool authority, bypass approval, or change safe argument binding.


### Implemented checkpoint — Phase471-475
Phase471 extends the existing Reflex resource policy with battery/charging awareness using an optional
shared DeviceStatusSource; Android creates one AndroidDeviceStatusSource and reuses the same instance
for both the device-status tool and System-1 scheduling, avoiding duplicate BatteryManager/memory
sampling infrastructure. Phase472 adds bounded battery conservation: <=8% and not charging blocks
learned Reflex, <=15% permits only checkpoints with calibrated latency <=120 ms and imposes a 2 s burst
interval, while <=30% uses a 750 ms interval and 180 ms latency ceiling. Charging removes battery-only
restrictions but never bypasses thermal/RAM gates. Phase473 adds optional
NativeReflexResidencyAwarePort.releaseTransientResources(); pressured battery/thermal decisions request
transient release after prediction, while unsupported backends safely ignore the hint. Residency
release failure is telemetry only and cannot invalidate a prediction or alter authority. Phase474 adds
burst throttling and an explicitly non-physical work-cost score derived from measured latency plus
thermal/battery pressure. Cost EWMA, throttle skips and residency counters stay in the existing
in-memory ReflexRuntimeHealthSnapshot; they are not represented as Joules and are not used as synthetic
training success. Phase475 wires DeviceStatusSource through the existing AmperRuntime Android
constructor into ResourceGovernorReflexRuntimeResourcePolicy. Battery/resource shaping is per-turn,
does not demote a checkpoint by itself, and cannot bypass safe argument binding, ToolDescriptor,
AuthorityGate or explicit approval.


### Completion checkpoint — Phase476-480
Phase476 implements AMPER Reflex Linear V1, the first concrete AMPER-owned decision model rather than a
training/runtime interface. It is a fixed 4,096-feature, 33-output-slot softmax linear classifier
(ESCALATE plus up to 32 capabilities) with exactly 135,201 trainable FP32 parameters. Input remains the
privacy-preserving hashed unigram/bigram feature set plus live capability presence; the artifact never
contains raw prompts, tool inputs, outputs or approvals. Phase477 implements ReflexLinearNativeTrainer,
a deterministic local CPU NativeTrainerPort that consumes the exact immutable
REFLEX_DECISION_EXPERIENCE payload supplied by NativeTrainingPipeline, performs weighted multiclass SGD,
serializes a bounded AMPER binary artifact, writes it to a content-addressed artifact store, and echoes
the canonical execution-binding, dataset-snapshot and curriculum digests required before checkpoint
publication. Phase478 implements FileReflexLinearArtifactStore plus a concrete
ReflexLinearDecisionPortResolver and held-out evaluator. Artifact filenames are SHA-256 identities;
load/recovery recomputes the digest and validates checkpoint lineage plus the exact AMPER-owned model
contract before inference. Phase479 adds ReflexNativeModelLifecycle. It waits for at least 32 ACTION and
32 ESCALATE governed examples, preserves at least 16 examples per class for the immutable disjoint
holdout, seeds one canonical AMPER-owned model contract/governed-label teacher identity, then executes
the existing partition -> curriculum -> NativeTrainingPipeline -> checkpoint -> real held-out model
prediction -> foundation admission -> promotion -> Reflex runtime activation path. A rejected model
does not become live. Phase480 wires the lifecycle into the Android host using app-private
amper-sovereign/native-reflex storage. Startup maintenance resolves/revalidates any durable active
checkpoint; successful assistant/approved-action turns opportunistically run bounded maintenance off
the UI thread. Once a learned checkpoint is active, maintenance stops retraining it. The learned model
still predicts only routing/capability; CanonicalReflexActionArgumentBinder, ToolDescriptor,
SovereignActionLoop, AuthorityGate and explicit approval remain unchanged.


### Completion checkpoint — Phase481-485
Phase481-485 expands the existing governed ToolRegistry into a concrete Universal Mobile Action Fabric
without adding a generic "execute anything" escape hatch. AMPER now exposes typed Android capabilities
for exact-package app launch, web search, clipboard write, document browsing, contact composition,
calendar event composition, alarm preparation, http/https media opening, AMPER notification settings,
and home-screen navigation. Existing android.settings.open remains the canonical path for display and
accessibility settings, so this phase does not duplicate those device-action surfaces.

Each capability has a bounded ToolInputContract and a dedicated AndroidUniversalActionLauncher method.
App/contact/calendar/alarm/files/media/notification/home operations are EXTERNAL and therefore always
stop at REQUIRES_CONFIRMATION before platform launch. Clipboard mutation is LOCAL_STATE and also requires
explicit approval. Contact and calendar providers open user-visible compose UIs and never claim the
record was saved; alarms keep EXTRA_SKIP_UI=false; file browsing never claims a document was selected;
notification settings never claim a setting changed. Media input is restricted to http/https and app
launch accepts only an exact Android package identifier.

Phase483 wires every new provider into the production Android host and the same
SovereignAssistantToolExposure set used by both model advertisement and DenyByDefaultAuthorityGate.
The action manifest bound increases from 16 to 32 descriptors, matching the existing learned Reflex
maximum capability surface while remaining bounded. Phase484 adds regression tests for explicit
approval, exact provider binding, malformed-input rejection, side-effect classification and full
manifest exposure. Phase485 preserves the existing canonical execution chain:
typed contract -> live descriptor -> SovereignActionLoop -> bound AuditedToolFabric -> AuthorityGate ->
platform launcher -> recorded ActionOutcome. Assistant side effects continue through
DurableAssistantActionExecutor and the existing PlanExecutionReceipt ledger, while governed plans keep
their existing receipt path. No new authority, approval, receipt, or recovery path is introduced.


### Completion checkpoint — Phase486-490
Phase486 reuses the exact bounded parsers from the Universal Mobile Action Fabric inside the Reflex
safe-argument binder path instead of creating a second validation stack. The validators remain
non-authoritative: they only decide whether a candidate input is syntactically eligible for a typed
ToolDescriptor.

Phase487 expands the deterministic System-1 bootstrap with explicit mobile skills for exact-package
app launch, web search, clipboard write, document browsing, typed contact composition, typed calendar
composition, clock-time alarm preparation, http/https media opening, AMPER notification settings and
home-screen navigation. Ambiguous app names, malformed URLs/times/contracts and missing live
descriptors escalate to System-2 rather than guessing.

Phase488 automatically extends learned Reflex execution because CanonicalReflexActionArgumentBinder
still requires the deterministic parser to independently recognize the same capability and construct
the tool input. The learned AMPER-owned model therefore remains limited to ACTION-vs-ESCALATE plus
capability prediction; it cannot invent raw tool arguments, package identities, URLs, contacts,
calendar records or alarm parameters.

Phase489 adds truthful fast-path rendering for the new Android skills. UI-mediated contact, calendar,
file and notification operations explicitly report that the user-controlled operation has not been
completed by AMPER; alarms continue to report that Android confirmation UI was not skipped.

Phase490 adds regression coverage for all ten new Reflex skill routes, malformed/ambiguous fallback,
learned-capability versus deterministic-binder agreement, and non-deceptive external-action rendering.
The existing ToolDescriptor -> SovereignActionLoop -> AuthorityGate -> explicit approval ->
DurableAssistantActionExecutor/receipt chain is unchanged.


### Completion checkpoint — Phase491-495
Phase491 extends the canonical deterministic Reflex partitioner with an optional explicit immutable
example-id selection. This lets lifecycle code isolate genuinely fresh governed evidence without
creating a second dataset, sharding, curriculum or training stack. Normal callers retain the previous
recent-evidence behavior.

Phase492 changes ReflexNativeModelLifecycle from one-shot training into bounded continual learning.
An active champion no longer permanently stops maintenance. AMPER walks the champion's parent lineage,
collects every training-shard and held-out example already consumed by that lineage, and considers only
new privacy-preserving Reflex examples. Retraining begins only after at least 24 fresh ACTION and 24
fresh ESCALATE examples exist. The candidate uses the active checkpoint as parent, preserving learned
weights while applying a lower continual-learning rate.

Phase493 keeps promotion statistically comparable without mutating historical evaluation records.
Each continual candidate reserves at least 16 fresh examples per class for a common holdout that is
disjoint from the full champion lineage. The candidate's admission evaluation remains immutable and
persisted; the champion is scored ephemerally on that exact fresh holdout. The existing
NativeTrainingPipeline metric/regression logic then builds a canonical promotion candidate against
that supplied common-holdout baseline. A rejected challenger never replaces the live checkpoint, and
identical rejected evidence is not retrained repeatedly during the same process.

Phase494 activates only promotable challengers through an explicit verified-replacement path on the
existing CanonicalReflexDecisionRuntimeController. Its replacement gate revalidates candidate
admission, checkpoint/baseline identity and the complete common-holdout comparison before swapping the
live port. The former champion remains the runtime standby, so the existing health controller can
automatically roll back after repeated failures or slow predictions. Candidate lineage records the
champion as parent; no separate model authority path is introduced.

Phase495 adds regression coverage for explicit fresh-evidence partition isolation and for a real
parented challenger that learns a previously unseen mobile capability, wins the common holdout,
replaces its champion, and then returns to waiting for new evidence. ToolDescriptor,
CanonicalReflexActionArgumentBinder, SovereignActionLoop, AuthorityGate, explicit approval and durable
receipts remain unchanged.


### Completion checkpoint — Phase496-500
Phase496 adds bounded rehearsal to continual Reflex training without introducing a second training
stack. Fresh governed evidence is still partitioned independently, while up to 96 deterministic
historical TRAINING examples are injected only into the candidate training shard. Historical holdout
examples are never replayed, so evaluation evidence cannot leak back into optimization.

Phase497 adds lineage-aware drift analysis. AMPER compares fresh ACTION capability distribution and
ESCALATE share against its bounded historical replay view and detects newly observed action
capabilities. Small, statistically stable 48-example windows are accumulated instead of immediately
retraining; novel capabilities or material distribution drift may still trigger the existing minimum
24-ACTION/24-ESCALATE continual-learning path. This reduces unnecessary on-device retraining while
remaining responsive to genuinely new behavior.

Phase498 extends the canonical NativeTrainingPipeline with non-persisting checkpoint comparison over
explicit evaluation pairs. The same metric-delta and material-regression logic used by native model
promotion can therefore be reused for stability checks without overwriting immutable historical
evaluation records.

Phase499 builds a separate deterministic stability holdout from historical HELD-OUT lineage evidence.
After a challenger clears admission and improves the fresh common holdout, both challenger and
champion are scored on the same historical stability shard. Any material regression blocks
replacement. The runtime replacement gate revalidates this anti-forgetting comparison proof before
swapping the active port.

Phase500 closes the stability loop: successful continual challengers are parented from the current
champion, rehearse prior training evidence, pass both plasticity and retention gates, and keep the
former champion as the existing automatic-rollback standby. Tests cover replay/holdout isolation,
retention of an older device-status skill while learning a new web-search skill, and low-drift
batching. Tool binding, AuthorityGate, explicit side-effect approval, durable receipts and all
execution authority remain unchanged.


### Completion checkpoint — Phase501-505
Phase501 adds a privacy-preserving active-learning scorer over retained Reflex examples. The scorer
never sees raw user text: it evaluates the current admitted champion directly against hashed features,
live-capability sets and governed ACTION/ESCALATE labels already stored by the Reflex experience
pipeline. Champion disagreement, uncertainty, low confidence and previously unseen action
capabilities become explicit non-authoritative learning signals.

Phase502 adds an evidence-quality gate. Examples below label-confidence 0.75 cannot satisfy the
continual-learning class floors, and label confidence also scales hard-example priority. AMPER does
not manufacture missing labels or turn model uncertainty into ground truth; insufficient
high-quality governed evidence simply leaves the active champion unchanged.

Phase503 adds deterministic hard-example mining. From up to 160 fresh examples AMPER trains on at
most 96 high-value examples, preserving minimum ACTION/ESCALATE coverage and deterministic
novel-capability coverage. Current champion errors and novel capabilities are selected ahead of
already-mastered easy examples. The existing trainer still applies per-example label-confidence and
class-balance weighting, while Phase496-500 replay supplies bounded historical rehearsal.

Phase504 integrates the miner into continual lifecycle scheduling. Stable small windows continue to
batch instead of retraining, but a sufficient concentration of champion disagreements, uncertainty
or a novel capability can trigger learning as soon as the governed 24-ACTION/24-ESCALATE floors are
met. Checkpoint identity is versioned with the exact selected hard-example ids plus replay ids, so
restart/rejection behavior stays deterministic and content bound.

Phase505 keeps promotion conservative: active-learning challengers still pass fresh held-out
admission, common-holdout champion comparison, historical anti-forgetting stability comparison and
the verified runtime replacement gate before becoming live. The former champion remains rollback
standby. Hard-example priority is training evidence only and never changes ToolDescriptor binding,
AuthorityGate, explicit approval, durable receipts or execution authority.


### Completion checkpoint — Phase506-510
Phase506 adds a deterministic adaptive curriculum over the active Reflex champion. AMPER scores the
champion on bounded historical TRAINING evidence only and derives per-action-capability weakness,
escalation weakness and uncertainty. These signals are scheduling inputs, not validation results;
held-out admission and anti-forgetting evaluation remain independent.

Phase507 feeds that curriculum back into active hard-example mining. Weak learned capabilities receive
higher selection priority without altering their governed labels, while novel capabilities retain the
existing explicit coverage floor. Selection remains deterministic, privacy-preserving and bounded.

Phase508 adapts continual-learning rate inside a conservative 0.018-0.040 band from measured
curriculum difficulty; newly observed capabilities never receive less than the established 0.03
continual baseline. The exact curriculum digest, effective learning rate, selected fresh-example ids
and replay ids are bound into the V4 continual checkpoint identity, preventing a restart or later
algorithm change from silently reusing a checkpoint trained under a different learning program.

Phase509 introduces lossless sparse ARL2 artifact encoding. Reflex still uses the same FP32 linear
parameters at inference, but only non-zero weight cells are written when that representation is
smaller; otherwise the codec falls back to the legacy dense layout, so encoding never expands the
artifact relative to the dense reference. Existing dense ARL1 artifacts remain readable and keep the
historical .arl1 file suffix, so installed champions can be recovered after upgrade without migration
or retraining. No quantization error is introduced.

Phase510 closes the mobile knowledge-compression loop: active learning reduces fresh examples,
Phase496 replay compresses historical rehearsal to a fixed bound, adaptive curriculum spends that
budget on weak skills, and sparse artifacts reduce checkpoint bytes without changing predictions.
Every challenger still passes fresh held-out admission, common-holdout comparison, historical
stability comparison and verified runtime replacement before activation. ToolDescriptor binding,
deterministic argument reconstruction, SovereignActionLoop, AuthorityGate, explicit approval and
durable execution receipts are unchanged.


### Completion checkpoint — Phase511-515
Phase511 adds a dedicated Reflex continual-learning resource policy, separate from the lightweight
inference resource policy. Training is treated as the more expensive operation and is evaluated
against live ResourceGovernor RAM/thermal state plus optional battery, charging, low-memory and app
storage signals. Severe thermal pressure, critical memory, low-memory state, storage reserve pressure
or battery <=20% while unplugged defer training without touching the active champion.

Phase512 introduces value-aware mobile learning budgets. Healthy devices may use up to 96 fresh and
96 replay examples; resource pressure reduces this to 64/64, while battery-conservation mode uses
64 fresh and 48 replay examples and permits only high-value updates. Novel capabilities, champion
disagreements and active-learning value can justify a bounded update under non-critical pressure;
low-value updates simply remain pending.

Phase513 wires the policy into the canonical AmperRuntime using the same ResourceGovernor and
DeviceStatusSource already shared by Android runtime/inference policy. JVM/reference runtimes keep a
deterministic healthy budget, while Android production automatically receives live device state
without adding another monitor.

Phase514 makes ReflexNativeModelLifecycle resource-aware before any training execution. Initial model
training uses a deterministic balanced evidence subset within the current resource budget. Continual
learning first estimates update value, then either returns RESOURCE_DEFERRED with the current champion
unchanged or re-materializes fresh/replay evidence to the granted budget before training. Deferred
evidence is not consumed, so the next successful maintenance call automatically re-evaluates current
resources and resumes the same pending learning opportunity when conditions recover.

Phase515 binds resource-budgeted evidence into V5 continual checkpoint identity and adds regression
coverage for thermal/battery/memory deferral, high-value limited updates and defer-then-resume
behavior. Resource scheduling never bypasses fresh held-out admission, champion comparison,
historical anti-forgetting stability comparison or verified runtime replacement. ToolDescriptor
binding, deterministic argument reconstruction, SovereignActionLoop, AuthorityGate, explicit
side-effect approval and durable receipts remain unchanged.


### Completion checkpoint — Phase516-520
Phase516 adds a durable Reflex learning-cost model. Every real local training execution records its
wall-clock duration, actual training-shard example count, success/failure and scheduled learning
value. AMPER maintains EWMA duration, throughput, success rate and learning-value-per-second in the
encrypted sovereign MemoryOs so the scheduler can learn the cost of its own updates across process
restarts.

Phase517 uses the learned throughput to predict the duration of a proposed fresh+replay update before
training begins. The prediction is advisory and non-authoritative: it can reduce or defer compute,
but it cannot manufacture evidence, alter labels, weaken evaluation or promote a checkpoint.

Phase518 adds cost-aware budget shaping. Unplugged updates predicted above the normal target are
reduced to the limited 64/64 budget; extreme non-novel updates are deferred. Battery-conservation and
resource-pressure modes apply stricter predicted-duration limits. After enough observations, low
historical learning-value-per-second can also defer low-value unplugged retraining while novel or
high-value evidence remains eligible for bounded learning.

Phase519 adds scheduler hysteresis. A transient resource deferral is not immediately flipped back to
normal by one healthy sample; ordinary updates require two consecutive healthy evaluations before
resuming. Charging or high-value evidence may bypass that recovery delay. This suppresses training
thrash when thermal/RAM/battery conditions oscillate around a boundary while preserving responsiveness
to genuinely valuable learning opportunities.

Phase520 closes the self-cost loop inside ReflexNativeModelLifecycle. Initial and continual training
both feed measured cost back into the durable model; RESOURCE_DEFERRED paths create no cost sample and
consume no evidence. Tests cover durable cost restoration, throughput-based duration prediction,
cost-based limiting/deferral, recovery hysteresis and defer-then-resume behavior. Fresh held-out
admission, common-holdout champion comparison, historical anti-forgetting stability comparison and
verified runtime replacement remain mandatory, and execution authority remains unchanged.


### Completion checkpoint — Phase521-525
Phase521 adds one durable coalescing Reflex learning-maintenance ticket inside sovereign MemoryOs.
A ticket contains only a content digest, reason set, priority, timing and attempt metadata; the actual
privacy-preserving training examples remain in the governed Reflex dataset store. Repeated triggers
for the same evidence collapse into one ticket, while newer evidence supersedes stale work instead of
building an unbounded queue.

Phase522 wires learning opportunities into ReflexNativeModelLifecycle. Initial-evidence and
continual hard/fresh opportunities are queued before expensive work; resource deferral adds a reason
to the same ticket. Successful activation/replacement clears the ticket, deterministic
rejection/failure clears stale work, and evidence that no longer meets governed class/quality floors
does not remain as background work.

Phase523 adds a process-resident background maintenance coordinator and loop. Android starts it beside
the existing Reflex lifecycle. Pending work is attempted after a quiet period, while charging may
bypass queue delay. The attempt/backoff state is durably advanced before lifecycle execution, so a
process restart cannot create a hot retry loop. Because checkpoint/run identities remain
content-bound, replaying already-completed work after a crash stays idempotent. The durable ticket is
restored automatically on the next app process start.

Phase524 adds bounded artifact garbage collection. The Reflex artifact store can enumerate/prune only
content-addressed model artifacts; lifecycle maintenance protects the active checkpoint plus the two
nearest parent checkpoints and deletes at most eight other artifacts per successful/rejected
maintenance cycle. Checkpoint lineage, dataset provenance, evaluations and promotion records remain
in sovereign memory; pruning removes obsolete executable weight bytes only.

Phase525 closes the long-running mobile maintenance loop: user turns still provide immediate learning
opportunities, while deferred opportunities survive process restarts and can resume in quiet or
charging windows without consuming evidence twice. Tests cover queue coalescing/restoration, newer
evidence superseding stale work, background defer-then-resume and protected artifact pruning.
Fresh held-out admission, common-holdout champion comparison, historical anti-forgetting stability,
verified replacement, ToolDescriptor binding, AuthorityGate, explicit approval and durable execution
receipts remain unchanged.


### Completion checkpoint — Phase526-530
Phase526 introduces a platform-neutral deferred-learning job plan plus a maintenance-scheduler contract.
A pending durable Reflex maintenance ticket becomes an OS scheduling intent with minimum latency bound
to the ticket's durable not-before time. The OS-native path is intentionally conservative and requires
charging, device-idle, battery-not-low and storage-not-low conditions; the process-resident loop keeps
handling faster opportunistic maintenance while the app is alive.

Phase527 wires queue mutation directly to scheduling reconciliation. Whenever ReflexNativeModelLifecycle
creates/coalesces an actionable maintenance ticket, the Android scheduler can publish or update the
corresponding OS job immediately; successful/rejected/stale work clears the ticket and cancels the OS
job. A process-wide maintenance execution gate serializes UI-triggered and OS-triggered Reflex lifecycle
execution so two lifecycle instances cannot train the same evidence concurrently during Android
component handoff.

Phase528 adds a native Android JobScheduler implementation and JobService without introducing another
runtime dependency. The job is persisted across reboot and the manifest grants RECEIVE_BOOT_COMPLETED;
the service itself is non-exported and protected by android.permission.BIND_JOB_SERVICE. JobScheduler
applies charging, idle, battery and storage constraints before AMPER is awakened.

Phase529 makes the JobService reuse the Activity's in-process maintenance coordinator whenever one is
registered. If no UI runtime exists, the service restores the encrypted sovereign runtime and the same
content-addressed Reflex artifact store, then runs the canonical maintenance coordinator. Scheduling
reconciliation is temporarily suppressed while an OS job is executing to prevent the worker from
cancelling itself; after jobFinished the remaining durable ticket is reconciled into the next job.
Restore/execution failures request an OS retry instead of leaving the job unfinished.

Phase530 closes restart/reboot continuity. Android startup reconciles any restored maintenance ticket,
the OS worker can recreate the runtime from encrypted storage when the app UI is absent, and durable
attempt/backoff metadata plus content-bound run/checkpoint identities keep retried work idempotent.
Tests cover persistent constrained job planning and lifecycle-to-scheduler reconciliation. The worker
has no tool authority and cannot weaken fresh holdout admission, champion comparison, historical
anti-forgetting stability, verified replacement, ToolDescriptor binding, AuthorityGate, explicit
approval or durable execution receipts.


### Completion checkpoint — Phase531-535
Phase531 adds durable, device-local telemetry for the Reflex learning scheduler. AMPER records READY,
LIMITED and DEFERRED resource decisions, high-value deferrals, maintenance attempts/completions/failures,
queue-wait EWMA, battery/thermal observations and categorized defer causes. The snapshot lives in
sovereign MemoryOs and contains scheduling metadata only; no raw user text, tool arguments or new
execution authority is introduced.

Phase532 derives a conservative self-tuning profile from accumulated device telemetry. The profile
has four bounded controls: training-budget scale, predicted-duration scale, minimum learning-value
boost and retry-delay multiplier. Tuning activates only after enough observations and can never make
the scheduler more permissive than the canonical Phase511-520 policy: budgets/duration limits may
shrink, learning-value requirements may rise and retry delay may increase, but critical battery,
thermal, RAM and storage gates cannot be relaxed.

Phase533 feeds that durable profile into ResourceGovernorReflexLearningResourcePolicy. Healthy devices
retain the canonical 96/96 ceiling. Devices with repeated thermal/memory pressure or maintenance
failures progressively reduce fresh/replay budgets and tighten duration/value thresholds while still
respecting the hard 48-fresh/16-replay minimums required by the governed training path. Every final
resource decision is written back into telemetry, closing the scheduler-observation loop across
process restarts.

Phase534 feeds background-maintenance outcomes back into the same model. Queue wait time, successful
terminal maintenance, training failures and execution errors become durable scheduler evidence.
Exponential maintenance backoff is multiplied by the conservative tuning profile, so a device that
repeatedly struggles with background learning automatically spaces retries farther apart instead of
thrashing battery/thermal resources. Android UI and JobService coordinators both use the same
encrypted telemetry model.

Phase535 adds regression coverage for telemetry persistence, pressure classification, bounded profile
generation, self-tuned budget reduction, preservation of the severe thermal fail-closed gate and
background-maintenance feedback. The self-tuner remains scheduling-only: it cannot change labels,
training targets, model authority, held-out admission, common-holdout comparison, historical
anti-forgetting stability, verified replacement, ToolDescriptor binding, AuthorityGate, explicit
approval or durable execution receipts.


### Roadmap correction — Phase536-540: AMPER Native System-2 Core
Phase536 restores the accelerated AGI-Mobile roadmap after the Reflex-support detour and introduces
one canonical AMPER-native System-2 deliberation layer. It is deliberately built on top of the
existing IntegratedCognitiveStateSource rather than creating parallel memory, world-model, skill or
planning architectures. A System-2 call binds one integrated cognitive packet and its execution
context digest as the immutable reasoning state for that deliberation.

Phase537 adds bounded reasoning decomposition. System-2 converts uncertainty in epistemic beliefs,
structured world state and perceptual grounding into an ordered reasoning agenda, optionally adds
counterfactual/world-model analysis and skill review, and always terminates with bounded plan
synthesis plus verification. These are cognitive tasks only; they contain no tool arguments,
provider handles, approval bits or execution authority.

Phase538 adds evidence-grounded strategy deliberation across the existing direct-skill,
skill-composition, cross-context transfer and generalized-chain stores. Candidates are accepted only
when every capability is present on the current live descriptor surface. Confidence, goal relevance,
precondition satisfaction, world confidence and uncertainty produce a deterministic bounded score.
An OPEN_DELIBERATION candidate always exists, so weak historical skill evidence can never force
execution reuse.

Phase539 adds digest-only System-2 working continuity. For each normalized goal digest AMPER stores
the cognitive-state digest, execution-context digest, selected strategy identity and deliberation
mode in sovereign MemoryOs. Repeated reasoning over an unchanged execution context is STABLE;
changed goals/world/epistemic grounding are detected as CONTEXT_CHANGED. The working snapshot stores
no raw user request and is excluded from normal user-facing memory/context retrieval.

Phase540 wires NativeSystem2Core into canonical AmperRuntime and adds regression coverage for strong
governed-skill reuse, evidence-gathering under unresolved world state, stable/context-changed working
continuity and runtime exposure. This phase does not yet replace the existing inference planner;
Phase541-545 will connect Reflex escalation and assistant planning to this native System-2 core.
ToolDescriptor binding, SovereignActionLoop, AuthorityGate, explicit side-effect approval, durable
receipts, held-out model admission and rollback invariants are unchanged.


### Completion checkpoint — Phase541-545: Native System-2 Integration
Phase541 adds exact-state Native System-2 deliberation. The planner can now hand the already-captured
IntegratedCognitiveStatePacket directly to NativeSystem2Core, so planner, critic and System-2 bind the
same cognitive-state and execution-context digests instead of independently recapturing mutable state.

Phase542 connects the assistant fallback path to Native System-2. Reflex still owns the zero/low-pass
fast path for high-confidence typed mobile actions. Any turn that does not complete through that fast
path obtains a Native System-2 deliberation before normal model inference. Bounded System-2 guidance
is injected ahead of the canonical action protocol, and deep deliberation may prefer a planning-capable
model profile without widening mandatory capabilities or disabling normal Titan fallback.

Phase543 connects sovereign planning to the same Native System-2 state. Planning prompts now include
bounded System-2 mode, continuity, reasoning depth, selected governed strategy identity and ordered
reasoning agenda alongside the existing metacognitive and integrated-cognitive sections. Under tight
prompt budgets System-2 prompt rendering degrades gracefully while the underlying deliberation and
canonical planning path remain active.

Phase544 makes System-2 strategy reuse operational without replacing canonical candidate evaluation.
System-2 may align the selected plan only when its capability sequence exactly matches an already
TitanPlanProtocol-validated candidate, the System-2 strategy score is strong enough, and that candidate
is within a narrow score gap of the existing evidence/counterfactual evaluator's best plan. Raw tool
arguments never participate in System-2 strategy identity, and live ToolDescriptor binding remains
mandatory.

Phase545 adds regression coverage for exact captured-state reuse, non-authoritative bounded guidance,
planning-specialist preference as preference-only routing, and guarded System-2 plan alignment.
The integrated execution chain is now:
Reflex/System-1 -> Native System-2 -> bounded inference/planning -> TitanAction/TitanPlan parsing ->
live ToolDescriptor binding -> SovereignActionLoop -> AuthorityGate -> explicit approval when needed ->
durable receipts. Phase546-550 now moves to closed-loop self-evolution rather than more Reflex tuning.


### Completion checkpoint — Phase546-550: Closed-Loop Self-Evolution
Phase546 reconnects AMPER's existing verified-evolution machinery to live governed outcome evidence
instead of inventing another self-modification stack. The autonomous cognitive executive can now
escalate both repeated capability execution weakness and strongly evidenced hierarchical strategy
repair weakness. The directive carries the typed weakness class and its evidence confidence, while
authority denials, unavailable tools and pending approvals remain excluded from execution-competence
evidence.

Phase547 converts each eligible weakness into a frozen content-addressed benchmark baseline. Real
execution reliability uses the exact governed executed/failed counts for the triggering capability;
hierarchical repair uses the already-aggregated governed repair signal as its own one-sample strategy
benchmark. Each weakness receives an immutable benchmark-suite identity and metric id tied to the
capability and weakness kind, so candidate generation and sandbox evaluation target the actual live
deficit rather than a generic synthetic score.

Phase548 routes that baseline through the pre-existing autonomous evolution campaign:
weakness -> model-generated data-only candidate blueprints -> injected sandbox -> benchmark/test/
canonical-invariant evidence -> deterministic tournament -> canonical promotion proposal. Severe
execution weakness may consider MODEL/CODE/STRATEGY/ARCHITECTURE candidates; hierarchical repair is
scoped to STRATEGY/CODE/ARCHITECTURE. Candidate text itself never supplies benchmark truth,
verification status, deployment identity or authority.

Phase549 closes transactional promotion and deduplication. A deployment backend must expose the exact
currently active revision + artifact SHA-256 before the campaign. Promotion still uses the existing
checkpoint -> apply -> fresh canary -> commit path and automatically rolls back apply identity
mismatch, failed canary or benchmark regression. A sovereign MemoryOs ledger records the exact live
evidence digest and terminal stage, preventing unchanged outcomes from repeatedly mutating the system.
After a committed promotion, new real governed outcomes are required before the same weakness can
trigger another evolution cycle. The ledger is excluded from user-facing context retrieval.

Phase550 exposes this entire path as AmperRuntime.closedLoopEvolutionExecutive(...), allowing the
PersistentGoalExecutive/AutonomousCognitiveExecutive to consume it as the existing evolution port.
Regression coverage drives a real weak capability through candidate generation, sandbox verification,
tournament selection, deployment, canary and COMMITTED promotion; verifies unchanged evidence is
deduplicated; proves DENIED/UNAVAILABLE/REQUIRES_CONFIRMATION do not become self-evolution weakness;
and covers the hierarchical strategy benchmark path.

The canonical authority chain is unchanged. Closed-loop self-evolution has no ToolFabric handle and
cannot grant permissions, approve side effects or bypass ToolDescriptor/TitanPlan parsing. CODE and
ARCHITECTURE candidates become live only through a concrete EvolutionDeploymentIdentityPort whose
sandbox, exact artifact identity, rollback checkpoint and canary satisfy the existing verified
promotion transaction. The next roadmap step is AGI-Mobile Qualification rather than another Reflex
or scheduler expansion.
