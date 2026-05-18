# Beyond-SDD Review Response — Release Note (v2.0.0-rc4)

**Date:** 2026-05-18
**Driver review:** [docs/reviews/spring-ai-ascend-beyond-sdd-en.md](../reviews/spring-ai-ascend-beyond-sdd-en.md)
**Response doctrine:** [docs/reviews/spring-ai-ascend-beyond-sdd-response.en.md](../reviews/spring-ai-ascend-beyond-sdd-response.en.md)
**Wave plan:** `D:/.claude/plans/d-chao-workspace-spring-ai-ascend-docs-shimmering-milner.md`
**Tag:** `v2.0.0-rc4` (additive uplift on `v2.0.0-rc3` — rc3 NOT retracted; this release adds Rule 79 + library-mode SPI tests + TCK-promotion markers without changing any prior contract)

---

## Baseline counts (single canonical post-rc4)

| metric | count |
|---|---|
| §4 constraints | 65 |
| Active ADRs | 77 |
| Layer-0 governing principles | 13 |
| Active engineering rules | 35 |
| Active gate rules | 64 |
| Gate self-test cases | 94 |
| Enforcer rows | 94 |
| Maven tests GREEN (under `./mvnw verify`) | 306 |
| Library-mode tests in `agent-runtime-core` | 64 |
| Architecture graph | 315 nodes / 433 edges |

**Deltas vs `v2.0.0-rc3`** (additive uplift only — no rule retracted, no contract changed):

- Active engineering rules **+1** — Rule 79 (Evidence-First Debug Sequence)
- Active gate rules **+1** — `rule_79_runbook_present_and_cited`
- Gate self-test cases **+2** — Rule 79 positive + negative (gate script `TOTAL=121` includes meta-tests beyond the baseline metric)
- Enforcer rows **+1** — E112
- Maven tests GREEN **+64** — four library-mode test classes in `agent-runtime-core`
- Library-mode tests in `agent-runtime-core` **+64** (was 0)
- §4 constraints / ADRs / Layer-0 principles / architecture graph — no change

## Summary

The 2026-05-18 architecture review *"Beyond SDD: Engineering the Cognitive Breakout"* surfaced three AI-agent debugging failure modes the project's Spec-Driven Development moat does not catch. The response wave accepts two with modification, rejects one with rationale, and ships the resulting work as one cohesive change.

| # | Reviewer proposal | Verdict | Authority |
|---|---|---|---|
| 1 | Telemetry-First Debugging (anchor on raw evidence before reading specs) | **Accept (modified)** — re-order the steps, do not forbid spec reading | New Rule 79 + new runbook |
| 2 | Library-Mode TDD (ms-scale pure-function tests in `agent-runtime-core`) | **Accept (modified)** — reuse existing SPI value types as the contract surface; no parallel stubs | 4 new test classes / 64 assertions / 1.8 s wall-clock |
| 3 | Immediate TCK Activation (scaffold `agent-runtime-tck` reactor module now) | **Reject (module); accept (intent)** | TCK-promotion-candidate markers on 3 in-memory test classes + Rule 32.b W2 trigger |

The wave's structural choice is **evidence ordering, not evidence prohibition** — Rule 79 sequences debugging artefact capture (FQN → trace ID → MDC slice → raw error → state history → THEN spec consultation) and is gate-enforced by artefact existence + link integrity, leaving the social discipline of *following* the sequence to Rule 9 self-audits.

## Rules landed (1 new active L1 rule)

| Rule | Title | Card | Gate rule | Enforcer | Principle |
|---|---|---|---|---|---|
| 79 | Evidence-First Debug Sequence | [`docs/governance/rules/rule-79.md`](../governance/rules/rule-79.md) | `rule_79_runbook_present_and_cited` (gate Rule 79) | E112 | P-D (SPI-Aligned, DFX-Explicit, Spec-Driven, TCK-Tested) |

No new ADRs (Rule 79 is operationalised by the existing P-D principle, ADR-0067; the response document is the design rationale).

## Artefacts shipped

- **Rule card** — [`docs/governance/rules/rule-79.md`](../governance/rules/rule-79.md): kernel (byte-matches CLAUDE.md per Rule 68 / E98) + motivation + 6-step algorithm + enforcement spec + activation note + cross-references.
- **Runbook** — [`docs/runbooks/debug-first-evidence.md`](../runbooks/debug-first-evidence.md): copy-paste-ready commands for each of the 6 sequence steps; the literal string `Evidence-First Debug Sequence` is asserted by the gate (drift-by-replacement check).
- **Library-mode SPI test suite** — 4 pure-JUnit-Jupiter classes under `agent-runtime-core/src/test/java/`:

  | Class | Tests | Targets |
  |---|---|---|
  | `RunStateMachineLibraryTest` | 36 | Full RunStatus DFA — 11 legal transitions, 20 illegal transitions, terminal-state invariants, immutability of `allowedTransitions(...)` |
  | `RunRecordTenantLibraryTest` | 7 | Rule 11 compact-constructor invariant (`Run` + `IdempotencyRecord` reject null tenantId); `withStatus` DFA validation |
  | `SuspendSignalLibraryTest` | 10 | Child-run + S2C client-callback factory invariants; checked-exception witness (ADR-0019); evidence-capture surface for Rule 79 |
  | `S2cCallbackEnvelopeLibraryTest` | 11 | Six-field validation (Rule 46 cross-rule audit matrix); W3C 32-hex traceId rule (cross-constraint audit α-5); defensive copy of attributes map; Outcome enum closed set |

  Measured wall-clock for the entire module (clean compile + 64 tests): **1.8 seconds**. Target was <5 s; comfortably under.

- **TCK-promotion-candidate markers** — class-level comments on three in-memory test classes (`InMemoryCheckpointerTest`, `InMemoryCheckpointerSizeCapTest`, `InMemoryRunRegistryFindRootRunsTest`) that the future `agent-runtime-tck` module will absorb on the Rule 32.b trigger. The "Pre-promotion holding tank" section in `docs/CLAUDE-deferred.md` documents the mechanical move path.

## Four Competitive Pillars (Rule 30 compliance)

Canonical-name coverage from [`docs/governance/competitive-baselines.yaml`](../governance/competitive-baselines.yaml):

| Pillar | Baseline | Beyond-SDD impact |
|---|---|---|
| `performance` | latency / throughput | no regression; library-mode `agent-runtime-core` test suite runs in **1.8 s** (vs prior `agent-service` Spring-bootstrap loops dominating wall-clock) — pure-function refactoring now has a sub-second feedback loop |
| `cost` | per-call + infra | no change |
| `developer_onboarding` | time-to-first-agent + surface complexity | **improved** — debug runbook + ms-scale library tests give AI agents and humans an evidence-first path before consulting the spec corpus; library-mode tests are the "library-mode worked example" reviewers asked for |
| `governance` | tenant isolation / audit / eval / safety | **strengthened** — Rule 79 closes the "Narrative Shield" anti-pattern (spec citation defending broken code); Rule 9 self-audits now require evidence citation under E112 link-integrity check |

## What's deferred (not blockers)

- **`agent-runtime-tck` reactor module scaffold** — `docs/CLAUDE-deferred.md` Rule 32.b. Trigger: first alternative implementation of any `agent-runtime` SPI is proposed (Postgres `Checkpointer`, Temporal `RunRepository`, or Redis `IdempotencyStore`). Scaffolding a TCK harness for a single implementation would be vacuously satisfied; ship when the conformance question becomes real.
- **Mechanical TCK-promotion script** — to be written as part of the W2 first-alternate-impl PR. Until then, promotion is a social-discipline move documented in the holding-tank section.
- **Rule 79 sticky social enforcement** — the gate verifies artefact existence + link integrity, not that agents follow the sequence. A heuristic check (finding-doc structural template) is open for review at the v2.1 wave.

Every deferred entry carries an explicit re-introduction trigger per Rule 28 (Code-as-Contract) doctrine.

## Verification (full reproduction)

```bash
./mvnw -pl agent-runtime-core test                       # 64 / 0  (~1.8 s)
./mvnw -T 1C verify                                      # 306 / 0
bash gate/check_architecture_sync.sh                     # PASS (64 active gate rules incl. rule_79_runbook_present_and_cited)
bash gate/test_architecture_sync_gate.sh                 # TOTAL=121 PASS (incl. test_rule79_evidence_first_debug_runbook positive + negative)
python gate/build_architecture_graph.py                  # 315 nodes / 433 edges (idempotent — release note is not a graph input)
```

Governance metadata sync (audit performed at SHA `8267010`):

- `docs/governance/principle-coverage.yaml` — `Rule-79` registered under P-D `operationalised_by_rules`.
- `docs/governance/enforcers.yaml` — E112 row (`rule_79_runbook_present_and_cited`).
- `docs/governance/architecture-status.yaml` — `architecture_sync_gate.allowed_claim` advertises 64 active gate rules + Rule 79.
- `docs/dfx/agent-runtime-core.yaml` — library-mode test layer references the four new test classes by name.
- `CLAUDE.md` — Rule 79 row under Daily principles; kernel byte-matches the rule card's front-matter `kernel:` scalar (Rule 68 / E98).

## Authority

- Driver review: `docs/reviews/spring-ai-ascend-beyond-sdd-en.md`
- Response doctrine: `docs/reviews/spring-ai-ascend-beyond-sdd-response.en.md`
- Rule 79 card: `docs/governance/rules/rule-79.md` — operationalises P-D, enforced by E112.
- Wave plan: `D:/.claude/plans/d-chao-workspace-spring-ai-ascend-docs-shimmering-milner.md`

## Conclusion

The 2026-05-18 Beyond-SDD review wave absorbs the reviewer's "evidence before narrative" doctrine without sacrificing spec-driven contract validation — the order is the architectural change, not the prohibition. The library-mode test suite gives AI agents a sub-second refactoring feedback loop on the SPI value types; the TCK-promotion markers stage the conformance harness without committing to an empty module today.

Canonical tag: **`v2.0.0-rc4`** (additive on `v2.0.0-rc3`; rc3 remains released and not retracted). Prior wave release notes:

- [2026-05-16 W2.x Engine Contract Structural Wave](2026-05-16-W2x-engine-contract-wave.en.md) — v2.0.0-rc1 baseline; superseded canonical tag is rc2/rc3/rc4.
- [2026-05-14 L1 modular-russell release](2026-05-14-L1-modular-russell-release.en.md) — L1 module-level architecture wave.
- [2026-05-13 L0 architecture release v2](2026-05-13-L0-architecture-release-v2.en.md) — historical L0 wave.
