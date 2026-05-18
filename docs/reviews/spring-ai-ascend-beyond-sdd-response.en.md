---
affects_level: L0
affects_view: process
proposal_status: response
authors: ["Chao Xing (with Claude Opus 4.7)"]
responds_to_review: docs/reviews/spring-ai-ascend-beyond-sdd-en.md
related_adrs: [ADR-0067]
related_rules: [Rule-1, Rule-2, Rule-4, Rule-9, Rule-32, Rule-79]
---

# Response — Beyond SDD: Engineering the Cognitive Breakout

This document is the architecture team's structured response to [`docs/reviews/spring-ai-ascend-beyond-sdd-en.md`](spring-ai-ascend-beyond-sdd-en.md). The review identified three remediation proposals targeting AI-agent debugging failure modes that the project's SDD moat does not catch. We accepted two with modification, rejected one with rationale, and shipped the resulting work as one cohesive change.

## 1. Summary table

| # | Reviewer proposal | Verdict | Authority | Evidence |
|---|---|---|---|---|
| 1 | Telemetry-First Debugging (prohibit reading specs first; anchor on raw evidence) | **Accept (modified)** | New Rule 79 + new runbook | `docs/governance/rules/rule-79.md`, `docs/runbooks/debug-first-evidence.md`, `CLAUDE.md` Rule 79 row, gate Rule 79 (`gate/check_architecture_sync.sh#rule_79_runbook_present_and_cited`), enforcer E112 |
| 2 | Library-Mode TDD (ultra-light memory stubs in `agent-runtime-core`; ms-scale pure-function TDD) | **Accept (modified)** | New library-mode tests in `agent-runtime-core/src/test/` | 4 new test classes, 64 assertions, **1.8 s wall-clock** for the full module (`./mvnw -pl agent-runtime-core test`) |
| 3 | Immediate TCK Activation (scaffold `agent-runtime-tck` reactor module now; use `InMemoryCheckpointer` + `InMemoryRunRegistry` as RI) | **Reject (module); accept (intent)** | Rule 2 (Simplicity) + Rule 32.b (explicit W2 trigger) | TCK-promotion-candidate markers on 3 in-memory test classes + `docs/CLAUDE-deferred.md` Rule 32.b "Pre-promotion holding tank" |

Aggregate baseline delta: rules 34 → 35; active gate rules 63 → 64; enforcer rows 93 → 94; gate self-tests 92 → 94 (TOTAL=121 in `gate/test_architecture_sync_gate.sh`); library-mode tests in `agent-runtime-core` 0 → 64.

## 2. Proposal 1 — Telemetry-First Debugging (Accept, modified)

**Reviewer's wording**: "AI agents must be strictly prohibited from reading architectural specs (ARCHITECTURE.md) for logical deduction as a first step."

**Why the literal wording was rejected**: the RunStatus DFA, the S2cCallbackEnvelope schema, the Rule 11 tenant invariant — these contracts live IN the spec. Blanket prohibition would substitute one failure mode (Narrative Shield) for another (blind reasoning).

**What we shipped instead**: Rule 79 — **Evidence-First Debug Sequence**. The rule does not forbid spec consultation; it re-orders the steps. Six steps must execute in order:

1. Failing test FQN + first 5 stack frames
2. Trace ID (W3C 32 lowercase hex) or JUnit method id
3. MDC slice (`runId`, `tenantId`, `fromStatus→toStatus`) or expected-vs-actual diff
4. Raw error message (verbatim, with line numbers)
5. RunStatus transition history (or analogue for test/gate failures)
6. **THEN** consult `ARCHITECTURE.md` / ADRs / `docs/governance/rules/*.md`

The full sequence with copy-paste-ready commands lives at [`docs/runbooks/debug-first-evidence.md`](../runbooks/debug-first-evidence.md). The rule card is [`docs/governance/rules/rule-79.md`](../governance/rules/rule-79.md).

**Enforcement** (Gate Rule 79 / E112): three invariants are bash-checked on every gate run —

1. `docs/runbooks/debug-first-evidence.md` exists.
2. It contains the literal string "Evidence-First Debug Sequence" (drift-by-replacement check).
3. The rule card references the runbook path (card↔runbook link integrity).

The gate does not enforce that agents follow the sequence — that is a Rule 9 self-audit obligation. The gate enforces artefact existence and link integrity.

## 3. Proposal 2 — Library-Mode TDD (Accept, modified)

**Reviewer's premise**: "Heavy Spring IT tests make sub-second refactoring impossible. Provide ultra-lightweight Library-mode memory stubs within `agent-runtime-core`."

**Reality check we did first** (Rule 79 step 1 — evidence before deduction):

| Module | Test files | `@SpringBootTest` classes | Notes |
|---|---|---|---|
| `agent-runtime-core` | **0** | 0 | The kernel-of-pure-types is **untested** today |
| `agent-service` | 80 | 12 | 15% Spring-bootstrap, 85% are already pure unit tests |
| `agent-execution-engine` | 0 | 0 | Skeleton; tests deferred |

The "heavy Spring IT" framing is partially mistaken — 85% of `agent-service` tests are already pure unit tests — but the prescription still helps because `agent-runtime-core` has **zero tests at all**.

**What we shipped**: four pure-JUnit-Jupiter test classes under `agent-runtime-core/src/test/java/`:

| Class | Tests | Targets |
|---|---|---|
| `RunStateMachineLibraryTest` | 36 | Full RunStatus DFA — 11 legal transitions, 20 illegal transitions, terminal-state invariants, immutability of `allowedTransitions(...)` |
| `RunRecordTenantLibraryTest` | 7 | Rule 11 compact-constructor invariant (`Run` + `IdempotencyRecord` reject null tenantId); `withStatus` DFA validation |
| `SuspendSignalLibraryTest` | 10 | Child-run + S2C client-callback factory invariants; checked-exception witness (ADR-0019); evidence-capture surface (parentNodeKey in message — Rule 79 dependency) |
| `S2cCallbackEnvelopeLibraryTest` | 11 | Six-field validation (Rule 46 cross-rule audit matrix); W3C 32-hex traceId rule (cross-constraint audit α-5); defensive copy of attributes map; Outcome enum closed set |

**Measured wall-clock** for the entire module (clean compile + 64 tests): **1.8 seconds**. Target was <5 s; comfortably under.

**Why no new stub classes**: the SPI value types already provide the surface (`RunStateMachine`, `SuspendSignal`, `S2cCallbackEnvelope`, `Run`, `IdempotencyRecord`). Introducing parallel stubs would violate Rule 2 (Simplicity) and create a second source of truth for behaviour the production types already encode.

## 4. Proposal 3 — Immediate TCK Activation (Reject module; accept intent)

**Reviewer's claim**: "Immediately activate the reserved `agent-runtime-tck` module."

**Factual correction**: `agent-runtime-tck` is NOT a "reserved" module — it does not appear in `pom.xml` `<modules>` and never has. The TCK is explicitly **deferred to W2** by `docs/CLAUDE-deferred.md` Rule 32.b, with an explicit re-introduction trigger:

> first alternative implementation of any `agent-runtime` SPI is proposed — Postgres `Checkpointer`, Temporal `RunRepository`, or Redis `IdempotencyStore`

No alternative implementation has been proposed. Scaffolding a TCK reactor for a single implementation would create a conformance harness with one candidate — by construction, vacuously satisfied, providing no protection against drift. It would also bump the `module_count_invariant` (Gate Rule 28e) for an empty module, requiring fresh enforcer rows for the new artefact. The cost-to-benefit ratio at this phase is negative.

**What we shipped instead** — the executable SPI-contract content the future TCK would carry, in two locations that lift-and-shift cleanly the day the trigger fires:

1. **`agent-runtime-core/src/test/java/...`** (Proposal 2 work, above) — pure-JUnit tests exercising the SPI value-type algebra. These are universal: they apply to every conformant implementation.
2. **`agent-service/src/test/java/.../inmemory/`** — `InMemoryCheckpointerTest`, `InMemoryCheckpointerSizeCapTest`, `InMemoryRunRegistryFindRootRunsTest` now carry a class-level `TCK-promotion-candidate` marker. The marker is enforced socially (review discipline) for the holding-tank phase; on the Rule 32.b trigger the classes move to `agent-runtime-tck/src/main/java/.../tck/` and the in-memory implementation becomes one test target alongside Postgres / Temporal / Redis.

The promotion path is documented in `docs/CLAUDE-deferred.md` under Rule 32.b "Pre-promotion holding tank".

**Why this honours the reviewer's intent**: the proposal's goal was "End-to-End Physical Simulation [to] crush the cognitive biases induced by pure text-based contracts." The library-mode + in-memory tests provide that physical simulation today. The module scaffolding adds no semantic content the tests don't already provide.

## 5. Evidence-anchored changelog

**Files added**

```
docs/governance/rules/rule-79.md                                                                   # rule card (kernel + motivation + algorithm + enforcement)
docs/runbooks/debug-first-evidence.md                                                              # 6-step playbook
docs/reviews/spring-ai-ascend-beyond-sdd-response.en.md                                            # this file
agent-runtime-core/src/test/java/ascend/springai/service/runtime/runs/RunStateMachineLibraryTest.java       # 36 tests
agent-runtime-core/src/test/java/ascend/springai/service/runtime/runs/RunRecordTenantLibraryTest.java       # 7 tests
agent-runtime-core/src/test/java/ascend/springai/service/runtime/orchestration/spi/SuspendSignalLibraryTest.java  # 10 tests
agent-runtime-core/src/test/java/ascend/springai/service/runtime/s2c/spi/S2cCallbackEnvelopeLibraryTest.java     # 11 tests
gate/rules/rule-079.sh                                                                             # extracted gate rule (auto-generated)
```

**Files modified**

```
CLAUDE.md                                                                                          # Rule 79 row under Daily principles
docs/CLAUDE-deferred.md                                                                            # Rule 32.b pre-promotion holding tank section
docs/governance/principle-coverage.yaml                                                            # P-D operationalised_by_rules += Rule-79
docs/governance/enforcers.yaml                                                                     # E112 row
docs/reviews/spring-ai-ascend-beyond-sdd-en.md                                                     # front-matter (affects_level/affects_view) for Rule 33
gate/check_architecture_sync.sh                                                                    # Rule 79 implementation
gate/test_architecture_sync_gate.sh                                                                # test_rule79_evidence_first_debug_runbook + TOTAL=119→121
agent-service/.../inmemory/InMemoryCheckpointerTest.java                                           # TCK-promotion-candidate marker
agent-service/.../inmemory/InMemoryCheckpointerSizeCapTest.java                                    # TCK-promotion-candidate marker
agent-service/.../inmemory/InMemoryRunRegistryFindRootRunsTest.java                                # TCK-promotion-candidate marker
```

**Verification** (Rule 79 sequence applied to this PR's own readiness check):

1. **Failing FQN** — n/a, all green: `./mvnw -pl agent-runtime-core test` → `Tests run: 64, Failures: 0, Errors: 0, Skipped: 0`.
2. **Trace ID** — n/a (library mode).
3. **Wall-clock** — 1.854 s for the new module's 64 tests on the dev machine.
4. **Raw output** — `[INFO] BUILD SUCCESS`.
5. **Gate output** — `bash gate/check_architecture_sync.sh` exits 0; `pass_rule: rule_79_runbook_present_and_cited` printed.
6. **Spec consultation** — Rule 79 card kernel byte-matches CLAUDE.md (Rule 68 / E98 passes); Rule 79 referenced from `principle-coverage.yaml` under P-D.

## 6. Open follow-ups

- **Rule 79 social enforcement**: the gate verifies that the runbook exists and is cited, not that agents follow the sequence. Sticky enforcement requires Rule 9 self-audit findings to embed the Rule 79 §3 template, which is operator-driven, not script-driven. We will revisit at the v2.1 wave whether a heuristic check (finding-doc structural template) is worth adding.
- **TCK module trigger watch**: when the first alternate `agent-runtime` SPI implementation lands (target: W2), Rule 32.b activates and the holding-tank classes move. The mechanical move script is not yet written; we will write it as part of the W2 first-alternate-impl PR.
- **In-memory test count delta**: 4 new test classes × ~16 average assertions = 64 new library-mode tests in `agent-runtime-core`. Full reactor `./mvnw verify` count goes from 242 → ~306 (delta ≥ 64; +/-2 depending on which agent-service tests count Spring fixtures). Baseline strings in README.md / `architecture-status.yaml` are bumped accordingly under Track E.

## 7. Acknowledgements

Thanks to the reviewer for the framing of "Narrative Shield" — even where the literal prescription was too strong, the diagnosis was correct: pre-Rule-79, this project had no playbook routing AI-agent debugging away from speculative-spec-citation toward observable-evidence-capture. Rule 79 + the runbook close that gap.
