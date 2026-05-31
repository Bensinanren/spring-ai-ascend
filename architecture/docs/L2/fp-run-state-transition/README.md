---
level: L2
view: scenarios
status: active
feature: "fp-run-state-transition"
relates_to:
  - "architecture/features/function-points.dsl"
  - "architecture/features/engineering-frames.dsl"
  - "architecture/features/verification.dsl"
  - "architecture/docs/L1/frames/EF-SESSION-TASK-STATE.md"
  - "architecture/docs/L1/agent-service/ARCHITECTURE.md"
authority: "ADR-0068 (Layered 4+1 + Architecture Graph) + ADR-0161 (EngineeringFrame package-cluster anchor + Card over DSL) + ADR-0118 (atomic CAS Run-state transition) + ADR-0142 (Run aggregate single owner) + ADR-0157 (EngineeringFrame Ontology)"
---

# L2 FunctionPoint Spec — `FP-RUN-STATE-TRANSITION` (Run State Transition)

This is the **L2 technical-detail home** for the single FunctionPoint
`FP-RUN-STATE-TRANSITION`: the atomic, DFA-validated, tenant-scoped Run status
transition. It carries the compare-and-set method contract, the validator, the
status alphabet, and the concurrent-writer runtime sequence that the layer-purity
verdict ruled does **not** belong in L0 / L1 prose (Rule 145 / E194-E195).

> **This document is a READABLE INTERPRETATION layer (Rule 146 / E196).** It
> invents no FunctionPoint ID, frame ID, type name, method descriptor, or status
> value. Every identity is copied from the authoring DSL; every code/test fact is
> cited from the generated facts. Cascade on disagreement:
> `generated facts > DSL > Card/prose`.

## Authority chain (read top-down)

1. **FunctionPoint identity (authoring DSL)** — element `fpRunStateTransition`
   (`saa.id` = `FP-RUN-STATE-TRANSITION`) in
   [`../../../features/function-points.dsl`](../../../features/function-points.dsl).
   Its `saa.status`, `saa.channel`, `saa.actor`, `saa.trigger`, `saa.requirement`,
   and `saa.sourceAdr` are copied verbatim into §1.
2. **Owning EngineeringFrame (structural parent)** — `EF-SESSION-TASK-STATE`
   (`efSessionTaskState`, owner `agent-service`) holds the `anchors` edge
   `efSessionTaskState -> fpRunStateTransition` in
   [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
   Frame Card: [`../../L1/frames/EF-SESSION-TASK-STATE.md`](../../L1/frames/EF-SESSION-TASK-STATE.md).
3. **Generated facts (binding factual authority)** — the `code-symbol/*` and
   `test/*` facts cited in §4 / §7 resolve in
   [`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json)
   and [`../../../facts/generated/tests.json`](../../../facts/generated/tests.json).
4. **Contract surface** — none. This is an `internal`-channel FunctionPoint
   realized over the `RunRepository` SPI, not over an HTTP/AsyncAPI wire operation
   (see §6).
5. **L0 constraint authority** — the L0 §4 constraint that fixes the `RunStatus`
   alphabet, the `PENDING` initial state, and the single-writer transition rule
   (ADR-0118 / ADR-0142) without carrying the CAS mechanics. This spec carries the
   detail; L0 keeps the invariant.

---

## 1. Behavior

`FP-RUN-STATE-TRANSITION` realizes the **single atomic state-advance primitive**
for a Run: it moves a Run's status compare-and-set style — applying the change
**only** from a non-terminal state, tenant-scoped, with the move validated against
the `RunStatus` alphabet. There is exactly one mutation path; no second writer
may advance a Run's status outside this primitive (ADR-0118 atomic CAS; ADR-0142
Run-aggregate single owner).

On the value axis this FunctionPoint serves
`PC-001|PC-003 -> REQ-001 -> FEAT-RUN-LIFECYCLE-CONTROL -> FP-RUN-STATE-TRANSITION`;
on the structural axis it is `agent-service -> EF-SESSION-TASK-STATE ->
FP-RUN-STATE-TRANSITION` (an `anchors` reach across the structural map — the
`runs` package itself is owned by `EF-TASK-CONTROL`, ADR-0157 §2).

| Field | Value (copied from the DSL element) |
|---|---|
| FunctionPoint ID | `FP-RUN-STATE-TRANSITION` |
| Status | `shipped` (`saa.status`) |
| Owning EngineeringFrame | `EF-SESSION-TASK-STATE` (the `anchors` parent) |
| Owner module | `agent-service` (`saa.owner`) |
| Requirement | `REQ-001` (`saa.requirement`) |
| Channel | `internal` (`saa.channel`) |
| Actor | `platform-runtime` (`saa.actor`) |
| Trigger | `internal-orchestration-event` (`saa.trigger`) |
| Source ADR | `ADR-0142` (`saa.sourceAdr`) |
| Value-axis Feature | `FEAT-RUN-LIFECYCLE-CONTROL` (`requires` edge, owner `agent-service`) |

## 2. I/O

- **Input** — three arguments to the atomic-update SPI method
  `RunRepository.updateIfNotTerminal`: the tenant scope (`String`), the Run id
  (`UUID`), and a transition transformation (`java.util.function.UnaryOperator`
  over the Run aggregate). The transformation expresses the intended status move;
  it is gated by the DFA validator (§4) so an illegal move is rejected before the
  store.
- **Output (success)** — `java.util.Optional` of the updated Run aggregate: the
  transition winner observes the transformed Run; a caller that loses the race (or
  targets a terminal Run) observes the unchanged / terminal state through the
  empty-or-unchanged result.
- **Side effects** — a single atomic write to the Run aggregate, tenant-scoped,
  guarded by the non-terminal precondition. The persistence realization (the
  reference `InMemoryRunRegistry`, or a durable store) is named as a boundary in
  §4; this spec never inlines the underlying SQL / persistence statement (that is
  L2 runtime detail delegated to the reference impl, not restated as a wire form).

## 3. Runtime Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Caller as Orchestrator (platform-runtime)
    participant Repo as RunRepository.updateIfNotTerminal
    participant Dfa as RunStateMachine.validate
    participant Run as Run aggregate (tenant-scoped)

    Caller->>Repo: updateIfNotTerminal(tenant, runId, UnaryOperator<Run>)
    Repo->>Run: load only if status is non-terminal
    Repo->>Dfa: validate(fromStatus, toStatus)
    Dfa-->>Repo: legal (or reject illegal move)
    Repo->>Run: atomic compare-and-set store
    Repo-->>Caller: Optional<Run> (winner sees updated; loser/terminal sees unchanged)
```

The single boundary hop owned by this anchor is `updateIfNotTerminal`; the DFA
table, the terminal-state set, and the cancel-vs-complete race **classification**
are the validator's and the reference impl's detail, named here as participants
and held by the tests in §7.

## 4. Class / Method Anchors (from facts)

| Role | Symbol | Fact id (+ method descriptor) |
|---|---|---|
| Entry SPI (atomic state-transition) | `RunRepository.updateIfNotTerminal` | `code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository#updateIfNotTerminal(Ljava/lang/String;Ljava/util/UUID;Ljava/util/function/UnaryOperator;)Ljava/util/Optional;` |
| Transition validator | `RunStateMachine.validate` | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatemachine#validate(Lcom/huawei/ascend/service/runtime/runs/RunStatus;Lcom/huawei/ascend/service/runtime/runs/RunStatus;)V` |
| Terminal check | `RunStateMachine.isTerminal` | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatemachine#isTerminal(Lcom/huawei/ascend/service/runtime/runs/RunStatus;)Z` |
| Allowed transitions | `RunStateMachine.allowedTransitions` | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatemachine#allowedTransitions(Lcom/huawei/ascend/service/runtime/runs/RunStatus;)Ljava/util/Set;` |
| Status alphabet (type) | `RunStatus` | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatus` |
| Reference realization (in-memory) | `InMemoryRunRegistry` | `code-symbol/com-huawei-ascend-service-runtime-orchestration-inmemory-inmemoryrunregistry` |

The DSL element declares no `saa.code_entrypoint_refs`; the entry above is the SPI
method the owning frame anchors (`efSessionTaskState -> fpRunStateTransition`),
copied from the Frame Card §6 and resolving in
[`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json).
`InMemoryRunRegistry` is fact-confirmed to implement `RunRepository`.

## 5. Error Paths

The non-terminal precondition and the DFA validator are the two refusal points.

| Cause (observable) | Outcome | Status / signal | `error.code` / exception |
|---|---|---|---|
| Target Run is already in a terminal status | no-op (precondition fails) | empty / unchanged result | `Optional.empty()` from `updateIfNotTerminal` |
| Requested move is not in `allowedTransitions(fromStatus)` | rejected by validator | illegal-transition signal | `RunStateMachine.validate` rejects the illegal `from -> to` |
| Concurrent writer already advanced the Run (CAS loser) | loser no-op | unchanged result | the compare-and-set sees a changed state; loser observes unchanged |
| Cross-tenant target (tenant scope mismatch) | no-op | tenant-scoped guard | the tenant-scoped lookup yields no match |

This is an `internal`-channel FunctionPoint with no HTTP wire, so no
`response_status_codes` apply; the outcomes above are method-level signals over
the `RunRepository` SPI and the `RunStatus` DFA, cited as types/methods in §4.

## 6. Contracts

`FP-RUN-STATE-TRANSITION` has **no external contract surface** — it is an internal
boundary; the contract is the owning frame's SPI type `RunRepository` (cited in
§4), realized over the atomic-update method, not over an HTTP/AsyncAPI operation.
The Run-lifecycle HTTP operations (`createRun` / `getRun` / `cancelRun`) belong to
the access / task-control frames, not to this state-transition anchor.

## 7. Tests

The `verifies` edge `testRunStateMachineTest -> fpRunStateTransition` in
[`../../../features/verification.dsl`](../../../features/verification.dsl) is the
authoring-DSL record of the primary test; the broader fact-cited evidence the
Frame Card carries is listed below, each resolving in
[`../../../facts/generated/tests.json`](../../../facts/generated/tests.json).

| Layer | Test class | Fact id | Covers |
|---|---|---|---|
| Unit / domain | `RunStateMachineTest` | `test/com-huawei-ascend-service-runtime-runs-runstatemachinetest` | the `RunStatus` DFA — every legal/illegal/terminal transition asserted (the `verifies`-edge primary test). |
| Unit / domain | `RunStateMachineLibraryTest` | `test/com-huawei-ascend-service-runtime-runs-runstatemachinelibrarytest` | the DFA validator's library-level transition rules. |
| Integration / contract | `RunRepositoryAtomicContractTest` | `test/com-huawei-ascend-service-runtime-architecture-runrepositoryatomiccontracttest` | the atomic-update contract — single-writer compare-and-set on the `RunRepository` SPI. |
| Integration / contract | `RunRepositorySaveGuardTest` | `test/com-huawei-ascend-service-runtime-architecture-runrepositorysaveguardtest` | the create-only save guard (status advances go through `updateIfNotTerminal`, not `save`). |
| Architecture / enforcer | `RunStatusEnumTest` | `test/com-huawei-ascend-service-platform-architecture-runstatusenumtest` | the fixed `RunStatus` alphabet + the `PENDING` initial state. |

Per-test asserted behaviour lives with these test facts' `test_methods[]` (e.g.
the `RunStateMachineTest` fact catalogues the named transitions such as
`failed_can_retry_to_running` and `pending_cannot_jump_to_succeeded`); this table
is a readable view of that evidence, not a re-inventory.

## 8. Gates

| Concern | Gate rule / enforcer | What it blocks |
|---|---|---|
| Atomic single-writer state transition | `RunRepositoryAtomicContractTest` + `RunRepositorySaveGuardTest` (runtime-architecture enforcers) | a second mutation path advancing a Run's status outside `updateIfNotTerminal`. |
| SPI purity (framework-free boundary) | `SpiPurityGeneralizedArchTest` (`test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest`) | the `runs.spi` / `session.spi` packages importing Spring / platform / reference impls. |
| Fixed `RunStatus` alphabet + initial state | `RunStatusEnumTest` (platform-architecture enforcer) | a change to the canonical `RunStatus` enum or the `PENDING` initial state. |
| Frame anchors >= 1 FP (shipped) | Rule G-23 (enforcer `E188`) | promoting `EF-SESSION-TASK-STATE` to `shipped` without the `anchors` edge to `FP-RUN-STATE-TRANSITION`. |
| Card / spec is a readable interpretation | Rule 146 / E196 | a citation here (`code-symbol/*`, `test/*`, method descriptor) that does not resolve, or an FP/frame relationship absent from the DSL. |
| No L2 detail left upstream | Rule 145 / E194-E195 | the CAS method / DFA table / race-classification detail this spec carries being left in L0 / L1 prose. |

---

## What stays upstream (NOT carried here)

Per the layer-purity keep-list, the following remain at L0 / L1 and are only
*referenced* here, never duplicated (Rule 145):

- the L0 §4 *invariant* — the fixed `RunStatus` alphabet, the `PENDING` initial
  state, and the single-writer transition rule (ADR-0118 / ADR-0142) — L0 owns the
  invariant; this spec owns the CAS method, the validator hop, and the
  concurrent-writer sequence;
- naming `RunRepository` as the Run-aggregate single-owner state-transition
  boundary (ADR-0142) and the development-view package decomposition (Frame Card /
  L1 material);
- the DFA transition **table**, the terminal-state set, and the
  cancel-vs-complete race classification — these are the validator's and the
  reference impl's runtime detail, named here as participants and held by the tests
  in §7, not re-tabulated as prose;
- citing the ArchUnit / gate enforcer that pins the boundary (named in §8, not
  re-specified).

## Authority

- ADR-0068 — Layered 4+1 + Architecture Graph as twin sources of truth.
- ADR-0118 — Atomic compare-and-set Run-state transition (the CAS primitive).
- ADR-0142 — Run aggregate single owner (the `RunRepository` boundary).
- ADR-0157 — EngineeringFrame Ontology (`EF-SESSION-TASK-STATE` structural anchor).
- ADR-0161 — EngineeringFrame package-cluster anchor + Card over DSL.
- Rule 33 — Layered 4+1 Discipline; Rule 145 — L2 detail sink; Rule 146 — Frame
  Card / FunctionPoint-spec is a readable interpretation (`CLAUDE.md`).
- L2 corpus index: [`../README.md`](../README.md).
