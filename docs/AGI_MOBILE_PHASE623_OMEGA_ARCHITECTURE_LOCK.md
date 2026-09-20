# Phase623 — AMPER MOBILE OMEGA Architecture Lock

Phase623 is the canonical reset point for AMPER's final mobile-AI direction.

The project is no longer developed as a generic GGUF host with successive runtime adapters. From
this phase forward, imported weights are source material for one AMPER cognitive foundation and one
AMPER execution stack.

## Non-negotiable product goals

AMPER OMEGA is built toward one mobile intelligence with all of these properties at the same time:

- high first-token and task-response speed on phones;
- accurate answers with explicit verification when uncertainty/consequence requires it;
- deep reasoning through adaptive/recurrent compute rather than a second model;
- governed Internet access as a read/tool capability;
- passive and proactive task execution through audited tools;
- continuation of user-started work when the UI is closed;
- checkpointed/persisted work that can resume after Android kills the process or after reboot;
- chat, voice, multimodal context and an interactive 3D embodiment;
- one portable architecture that profiles actual hardware instead of hard-coding phone models.

"AGI Mobile" remains a project direction, not a scientific guarantee. Architecture alone cannot
guarantee general intelligence; final capability also depends on weights, training data, reasoning
training and physical compute.

## Canonical OMEGA stack

```
AMPER EMBODIMENT
chat · voice · vision · 3D avatar
        |
AMPER AGENT CORE
goals · planner · passive/proactive tasks · tools
        |
AMCF
AMPER Mobile Cognitive Foundation
one foundation · adaptive depth · recurrent reasoning
        |
AMNE 2
mobile neural execution engine
ARM64 · NEON · DOTPROD · I8MM · FP16 · optional Vulkan/NPU adapters
        |
AMI 2
canonical AMPER mobile model format
weights · tokenizer · graph · quant/device packs · lineage
```

External formats such as GGUF are import sources only:

```
GGUF/source weights -> AMPER compiler -> AMI2 -> AMCF -> AMNE2
```

They are not competing production model engines.

## Four added core pillars

### FAST

AMPER must minimize useful-response latency rather than maximize raw model size.

Required mechanisms:

- exact request-scoped memory admission;
- resident/paged weights;
- KV and prefix reuse;
- kernel fusion;
- adaptive depth/early exit;
- big/LITTLE-aware scheduling;
- per-device autotuning;
- GPU/Vulkan only when physical benchmarks beat the CPU path.

The compute contract has six modes:

`REFLEX -> FAST -> STANDARD -> REASON -> DEEP -> VERIFY`

All modes use the same AMPER foundation.

### ACCURATE + DEEP

Deeper reasoning is expressed as more recurrent cognitive cycles and verification passes, not another
model.

The canonical policy can increase compute when:

- the request is complex;
- uncertainty is high;
- consequences are high;
- the user explicitly asks for deeper reasoning.

For high-consequence or uncertain work, accuracy takes priority over latency and Internet/tool
verification may be used when useful and authorized.

### CONNECTED

Phase623 adds the first real AMPER OMEGA Internet primitive:

`internet.read.https`

The provider:

- is READ_ONLY in the existing audited tool fabric;
- accepts one exact public HTTPS URL;
- rejects credentials, localhost, LAN/private/link-local destinations and non-default ports;
- disables redirects;
- applies bounded connect/read timeouts;
- accepts bounded text/HTML/JSON/XML;
- caps network bytes and text returned to cognition;
- strips active HTML script/style markup before returning page text.

Internet is an observation/tool channel, not a second intelligence engine.

Discovery/search and richer browsing will build on this gateway in the Agent Core milestones.

### ALWAYS-ON

Android "UI closed" and "process killed" are treated as different states.

The locked policy is:

- long user-started work that must continue after leaving the UI -> visible foreground continuation;
- deferrable work -> persisted JobScheduler/OS work;
- future conditions/events -> event wake + checkpoint;
- every long task -> persistent checkpoint before yielding;
- reboot/process death -> restore task state, never pretend an in-memory thread survived.

Phase623 locks this execution policy in code. Existing AMPER voice/screen foreground services and the
persisted reflex JobService demonstrate the Android mechanisms already used by the project. The
unified Agent Core executor is completed in Milestone 5; this phase does not falsely claim that a
generic autonomous background agent already exists.

## Locked roadmap — eight major milestones

### M1 — Architecture consolidation

Exit criteria:

- OMEGA invariants locked in code and docs;
- one AMPER Core production boundary;
- governed Internet gateway;
- Android-safe background execution modes;
- migration plan from AMI/AMNE v1 to AMI2/AMNE2.

### M2 — AMI2 compiler

Exit criteria:

- source weights -> AMI2;
- logical model graph independent from device-specific packs;
- tokenizer and chat protocol preserved;
- deterministic source lineage/integrity;
- device packs can be generated without altering foundation semantics.

### M3 — AMNE2 mobile runtime

Exit criteria:

- ARM64 dispatch and mobile tensor ABI;
- NEON, DOTPROD, I8MM and FP16 qualification;
- mmap paging and KV memory fabric;
- device microbenchmark/autotuning;
- Vulkan/NPU adapters only when physical measurements justify them.

### M4 — AMCF foundation runtime

Exit criteria:

- one mobile cognitive architecture;
- adaptive depth/early exit;
- recurrent reasoning cycles;
- deep reasoning and verify/revise loop;
- no second foundation used for System-2.

### M5 — Agent Core + always-on execution

Exit criteria:

- passive task/tool execution;
- proactive goals, triggers and monitors;
- visible foreground continuation after UI exit;
- checkpointed OS jobs after process death/reboot;
- governed Internet observation integrated into plans;
- action authority/audit remains authoritative.

### M6 — Cognitive memory

Exit criteria:

- working memory;
- episodic memory;
- semantic memory;
- procedural/skill memory;
- consolidation and retrieval tied to task outcomes.

### M7 — Multimodal + voice

Exit criteria:

- camera/image and screen understanding;
- STT/TTS;
- multimodal planning/tool use;
- interruption/cancellation;
- mobile thermal/resource integration.

### M8 — 3D embodiment + product hardening

Exit criteria:

- interactive 3D avatar;
- gaze, gesture, state animation and lip sync;
- cognition/embodiment separation;
- battery/thermal/memory adaptation;
- crash/update/data migration;
- physical qualification matrix across phone capability tiers.

## Architecture invariants

The following are enforced by `OmegaArchitectureLock` and must not be weakened by later phases:

- one AMPER foundation runtime;
- AMI2 canonical format;
- AMNE2 canonical execution engine;
- adaptive compute from fast to deep;
- verification before high-confidence conclusions when required;
- Internet is a governed tool, not a model;
- long work is checkpointed;
- foreground work can outlive the UI;
- actual hardware capability profiling, not marketing device names;
- audited action authority remains in control;
- four cognitive memory classes;
- 3D avatar remains embodiment, not cognition.

## Phase623 implementation boundary

Phase623 intentionally does not rewrite AMI/AMNE v1 or the decoder again. It creates a stable
architectural boundary first, adds governed Internet access, and establishes adaptive/background
contracts. M2 begins the new AMI2 compiler on top of this lock rather than continuing ad-hoc patches.
