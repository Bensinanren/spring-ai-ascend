---
rule_id: 79
title: "Evidence-First Debug Sequence"
level: L0
view: process
principle_ref: P-D
authority_refs: [ADR-0067]
enforcer_refs: [E112]
status: active
kernel_cap: 12
kernel: |
  **When a Run fails, a test regresses, or a self-audit finding is opened, the first artefact captured MUST be observable evidence — the failing test class FQN, the trace ID (if present), the MDC slice (runId, tenantId, fromStatus→toStatus), and the raw error message including stack frame line numbers. ARCHITECTURE.md / ADR consultation is permitted only AFTER evidence is recorded in the finding. Self-audit findings under Rule 9 that omit evidence citation are blocked. Operationalised by `docs/runbooks/debug-first-evidence.md`.**
---

# Rule 79 — Evidence-First Debug Sequence

## Motivation

The 2026-05-18 architecture review (`docs/reviews/spring-ai-ascend-beyond-sdd-en.md`) identified a failure mode in AI-assisted debugging: when an integration test regresses, the AI agent's first reflex is to read `ARCHITECTURE.md` and reason about the architectural contract. The contract becomes a "Narrative Shield" — the agent cites it to defend the broken code rather than confronting the raw failure.

The fix is not to forbid spec reading. The state-machine DFA in `RunStateMachine` *is* in the spec; refusing to read it leaves the agent blind on the other axis. The fix is to **re-order the steps**: evidence first, then spec consultation against that evidence.

## Algorithm

When any one of the following triggers fires, the debugging sequence below MUST execute IN ORDER. Skipping forward is a Rule 79 violation.

**Triggers**
- A Maven test fails (`./mvnw verify` non-zero exit).
- A gate self-test fails (`bash gate/check_architecture_sync.sh` non-zero exit).
- A self-audit finding under Rule 9 is opened.
- A production Run terminates in FAILED.

**Sequence**
1. **Failing test FQN** — capture the fully-qualified class name (e.g. `ascend.springai.service.runtime.runs.RunStateMachineLibraryTest#illegal_transition_throws`) AND the first 5 lines of stack trace from the test report.
2. **Trace ID** — if the failing path emits a W3C trace ID, capture it (32 lowercase hex). For library-mode tests with no tracing, capture the JUnit method id.
3. **MDC slice** — when the failure involves a Run, capture `runId`, `tenantId`, `fromStatus`, `toStatus`, `attemptId` from the log line immediately preceding the failure. For test-only failures, capture the assertion's `expected:` and `actual:` values.
4. **Raw error message** — verbatim, including line numbers. No paraphrase.
5. **State-machine transition history** — when the failure involves a Run, list every `RunStatus.withStatus(...)` call that preceded the failure (from `Run.updatedAt` ordering).
6. **THEN** consult `ARCHITECTURE.md` / `docs/adr/` / `docs/governance/rules/*.md` to interpret the evidence captured in steps 1-5.

The full runbook with copy-paste-ready commands lives at [`docs/runbooks/debug-first-evidence.md`](../../runbooks/debug-first-evidence.md).

## Enforcement

Enforced by E112 (Gate Rule 79 — `rule_79_runbook_present_and_cited`):

1. `docs/runbooks/debug-first-evidence.md` MUST exist.
2. `docs/runbooks/debug-first-evidence.md` MUST contain the literal string `Evidence-First Debug Sequence` (so the runbook can't drift to a different topic while satisfying the gate by name alone).
3. `docs/governance/rules/rule-79.md` MUST reference `docs/runbooks/debug-first-evidence.md` (so the card-runbook link survives renames).

The rule does not enforce that humans / AI agents actually FOLLOW the sequence — that is a Rule 9 self-audit obligation. The gate enforces only the artefact-existence + link-integrity surface.

## Why not blanket prohibition

The reviewer's original wording — "AI agents must be strictly prohibited from reading architectural specs as a first step" — was rejected by the response (see `docs/reviews/spring-ai-ascend-beyond-sdd-response.en.md` §2). The DFA contracts that the agent must validate against (RunStateMachine, S2cCallbackEnvelope.requireLowerHex32, etc.) live IN the spec. Forbidding spec consultation would create a different failure mode (blind reasoning). The strongest valid reading is "evidence first, then spec" — that reading is what Rule 79 codifies.

## Activation

Activated 2026-05-18 by the Beyond-SDD review response (see `D:\.claude\plans\d-chao-workspace-spring-ai-ascend-docs-shimmering-milner.md` Track A).

## Cross-references

- ADR-0067 — origin decision record for P-D (SPI-Aligned, DFX-Explicit, Spec-Driven, TCK-Tested).
- P-D — governing principle that Rule 79 operationalises (anchors the "TCK-Tested" pillar with the "evidence flows in both directions" reading).
- Rule 1 (Root-Cause + Strongest-Interpretation) — Rule 79 sequences the first artefacts; Rule 1 then governs which interpretation of them survives.
- Rule 9 (Self-Audit is a Ship Gate) — Rule 79 evidence captures are a precondition for a Rule 9 finding to be admissible.
- `docs/runbooks/debug-first-evidence.md` — operational playbook.
- `docs/reviews/spring-ai-ascend-beyond-sdd-en.md` + `docs/reviews/spring-ai-ascend-beyond-sdd-response.en.md` — origin review + response.
