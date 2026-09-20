# Phase646 — M4 Consolidated Qualification / Closure

Phase646 adds one fail-closed qualification surface for the complete AMCF foundation milestone.

## Qualification evidence

`AmcfM4RunEvidence` is derived from the bounded result of a frozen cognitive AMCF run. It carries
only:

- OMEGA compute mode;
- AMPER foundation binding;
- frozen cognitive-state digest;
- planned total/deliberation depth;
- committed cycle kinds;
- early-exit actions;
- terminal recurrent-state digest.

No candidate prose, hidden reasoning, tool handles, approvals, or mutable cognitive state are part of
qualification evidence.

## Required qualification set

M4 requires exactly one representative run for each mode:

- FAST
- REASON
- DEEP
- VERIFY

All four runs must remain bound to the same AMPER foundation and canonical AMNE2 execution engine.

The consolidated gate verifies:

1. one foundation / AMNE2 binding;
2. bounded adaptive depth increasing from FAST -> REASON -> DEEP, with VERIFY at least as deep;
3. bounded structured recurrent-state completion;
4. grounded REASON early exit into VERIFY or FINALIZE;
5. paired VERIFY -> REVISE processing before FINALIZE;
6. frozen cognitive snapshot identity;
7. successful FINALIZE termination.

Any missing mode, duplicate evidence, foundation drift, broken verification/revision pair, or other
missing criterion leaves the report unqualified.

## Architecture closure

Phase646 locks:

`m4-amcf-foundation-has-consolidated-qualification-gate`

This closes the implementation surface for M4. The gate does not claim that architecture alone
constitutes AGI; it verifies that the AMCF layer now satisfies the OMEGA roadmap contracts required
before Agent Core work begins.

## M4 result

The implemented M4 chain is now:

`OMEGA adaptive compute -> AMCF bounded cycles -> structured recurrent state -> transactional
orchestrator -> single-core AMI2/AMNE2 production endpoint -> grounded cognitive quality -> frozen
run snapshot -> consolidated qualification`

No additional model, judge, runtime, backend, or authority lane was introduced.

## Next milestone

The next roadmap work begins M5 — Agent Core + always-on execution.

The first M5 slice should establish one canonical agent-task contract separating passive user-request
execution from proactive triggered work, while reusing the existing governed ToolFabric,
approval/audit boundaries, checkpoint semantics, and Android background execution policy.
