---
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology); ADR-0158 (EnginePort Transport-Agnostic Boundary)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-ORCHESTRATION-SPI
dsl_element: efOrchestrationSpi
owner_module: agent-bus
primary_package: ""
source_adr: ADR-0157|ADR-0158

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json (the gate cross-checks these). ---
fact_refs:
  - code-symbol/com-huawei-ascend-bus-spi-engine-orchestrator
  - code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext
  - code-symbol/com-huawei-ascend-bus-spi-engine-runcontext
  - code-symbol/com-huawei-ascend-bus-spi-engine-suspendsignal
  - code-symbol/com-huawei-ascend-bus-spi-engine-checkpointer
  - code-symbol/com-huawei-ascend-bus-spi-engine-executordefinition
  - code-symbol/com-huawei-ascend-bus-spi-engine-runmode
  - test/com-huawei-ascend-bus-spi-engine-orchestrationspiarchtest
  - test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest

# --- participating_function_points: FunctionPoints this frame does NOT anchor but
#     PARTICIPATES in via a value-axis `traverses` edge (ADR-0157 §2: a route, never
#     ownership). FP-SUSPEND-RESUME is anchored to EF-TASK-CONTROL; the DSL edge
#     featSuspendResumeControl -> efOrchestrationSpi (traverses) records that the
#     Suspend/Resume Control feature routes across this frame's SuspendSignal /
#     Checkpointer seam. Declared here so the gate reads the body mention of
#     FP-SUSPEND-RESUME as a participating reference, not an invented anchor. ---
participating_function_points:
  - FP-SUSPEND-RESUME
---

# `EF-ORCHESTRATION-SPI` — Orchestration SPI Frame

> Anchors the neutral execution model the Service drives an engine through — the
> orchestration semantics (`Orchestrator`, `ExecutionContext`/`RunContext`, `SuspendSignal`,
> `Checkpointer`, `ExecutorDefinition`, `RunMode`) that live in `bus.spi.engine`, owned by no
> single engine. Rationale lives in `source_adr` (ADR-0157 / ADR-0158); this card carries none.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate (Rule G-29) fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-ORCHESTRATION-SPI` | DSL element |
| DSL element | `efOrchestrationSpi` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-bus` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0157` \| `ADR-0158` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-ORCHESTRATION-SPI.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame:

- Declare the neutral execution-model contract the Service uses to drive an engine: the
  `Orchestrator` entry interface
  (`code-symbol/com-huawei-ascend-bus-spi-engine-orchestrator`) and the executor model
  `ExecutorDefinition` (`code-symbol/com-huawei-ascend-bus-spi-engine-executordefinition`)
  with its run mode `RunMode` (`code-symbol/com-huawei-ascend-bus-spi-engine-runmode`).
- Define the engine-facing execution context — `ExecutionContext`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext`) carrying only opaque
  correlation plus the suspend capability — and the Service-side subtype `RunContext`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-runcontext`) that adds tenant/session
  identity (the context split fixed in ADR-0158 §5).
- Declare suspend/resume as a contract surface: the `SuspendSignal` carrier
  (`code-symbol/com-huawei-ascend-bus-spi-engine-suspendsignal`) and the durability seam
  `Checkpointer` (`code-symbol/com-huawei-ascend-bus-spi-engine-checkpointer`) that a
  suspend/resume realization writes through.

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Provide the transport boundary. The neutral Service↔Engine transport contract
  (`EnginePort`, `ExecuteRequest`, `AgentEvent` stream, `DefinitionRef`, `EngineDescriptor`)
  is the co-resident frame `EF-ENGINE-PORT` (`agent-bus`, ADR-0158), not this frame. The two
  frames share the `com.huawei.ascend.bus.spi.engine` package as distinct responsibility
  slices: this frame is the execution/orchestration model, `EF-ENGINE-PORT` is the wire-form
  boundary.
- Run the state machine. Catching `SuspendSignal`, transitioning a Run to `SUSPENDED` /
  `FAILED`, and resuming it is orchestration behaviour owned by `EF-TASK-CONTROL` /
  `EF-SESSION-TASK-STATE` (`agent-service`), driven through this SPI but not implemented here.
- Provide a concrete `Orchestrator` / `Checkpointer` implementation. The in-process
  reference realizations (the orchestrator and `InMemoryCheckpointer`) live in
  `agent-service` under `com.huawei.ascend.service.runtime.orchestration.*`, outside this
  frame's package; this frame owns only the contract types.
- Carry the over-the-wire suspend/resume mechanics — the checkpoint-token protocol, the
  serialized `ExecutorDefinition` dispatch, deadline scheduling, or persistence. That runtime
  detail is delegated to the frame's L2 sink and the contract surface, not restated here.

**Owned state** — the data/state this frame is the structural home for:

- The neutral execution-model SPI surface and its types within
  `com.huawei.ascend.bus.spi.engine` (the orchestration slice named above). The frame holds
  no mutable runtime state of its own; `SuspendSignal` is a carrier and the contexts are
  interfaces realized by the Service, not state owned here.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `java.*` only. As an SPI slice in `agent-bus` — the module that depends on nothing and is
  a dependency of both `agent-service` and `agent-execution-engine` (ADR-0158 §2) — the frame
  depends on the JDK standard library and its own same-package siblings; this is asserted by
  an ArchUnit enforcer (see section 7).

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cited in section 7):

- Spring, the `com.huawei.ascend.service.platform` package, `agent-service`, the in-memory
  reference implementations, Micrometer, and OpenTelemetry. The neutral contract must not
  depend downward on its own callers, on a concrete orchestrator, or on a runtime framework —
  the engine and the Service each implement *toward* this SPI, never the reverse.
- The retired `com.huawei.ascend.engine.orchestration.spi` package. ADR-0158 re-homed the
  orchestration SPI to `com.huawei.ascend.bus.spi.engine`; naming the old home as the current
  one is banned (see section 7, Rule G-24).

**Included / excluded packages** (this frame is a same-package responsibility slice, not a
single-frame package root):

- Included: the orchestration/execution-model types within
  `com.huawei.ascend.bus.spi.engine` — `Orchestrator`, `ExecutionContext`, `RunContext`,
  `SuspendSignal`, `Checkpointer`, `ExecutorDefinition` (and its nested executor variants),
  `RunMode`.
- Excluded: the transport-boundary types in the same package — `EnginePort`,
  `ExecuteRequest`, `AgentEvent`, `DefinitionRef`, `EngineDescriptor` (owned by the co-resident
  frame `EF-ENGINE-PORT`); and the reference implementations under
  `com.huawei.ascend.service.runtime.orchestration.*` (owned by `agent-service`).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). The Card generator owns this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element, so the
  package-granular Card generator emits no fact-cited Type Inventory here (README
  §"Status-conditional rules"). The frame is a same-package responsibility SLICE of
  `com.huawei.ascend.bus.spi.engine`, which it shares with the co-resident frame
  `EF-ENGINE-PORT`; a package-level filter cannot separate the two slices, so promoting this
  block would over-claim `EF-ENGINE-PORT`'s types. The orchestration-model types that already
  exist in that package are cited as authored prose in sections 2 and 5; this block stays
  empty (with stable markers) until the frame is promoted to `shipped` with a declared
  `primaryPackage`. See section 7 for the missing-proof statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships among the in-boundary types listed in section 3.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`, and a
  package shared with `EF-ENGINE-PORT`, so no generated structural relationship table can be
  scoped to this slice alone. Stable markers retained for the gate's block anchor. The one
  structural seam that matters today — `RunContext` refining the engine-facing
  `ExecutionContext` with tenant/session, and `Orchestrator` driving an `ExecutorDefinition`
  while a suspend writes through `Checkpointer` — is stated as authored prose in section 2.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- The orchestration slice of `com.huawei.ascend.bus.spi.engine` — the public SPI types this
  frame is the structural home for. Its identity interface is `Orchestrator`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-orchestrator`); the execution context it
  exchanges is `ExecutionContext`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext`) with the Service-side
  subtype `RunContext` (`code-symbol/com-huawei-ascend-bus-spi-engine-runcontext`); the
  executor model is `ExecutorDefinition`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-executordefinition`) selected by `RunMode`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-runmode`); the suspend/resume seam is
  `SuspendSignal` (`code-symbol/com-huawei-ascend-bus-spi-engine-suspendsignal`) plus
  `Checkpointer` (`code-symbol/com-huawei-ascend-bus-spi-engine-checkpointer`).

**Schema contract (SPI envelope, not an OpenAPI operation):**

The orchestration model travels on an internal, engine-facing channel; it has no HTTP route
and therefore no `contract-op/<id>` operation. Its boundary shape is pinned alongside the
transport boundary by the schema contract
[`docs/contracts/engine-port.v1.yaml`](../../../../docs/contracts/engine-port.v1.yaml)
(`contract-yaml/engine-port`, Authority ADR-0158, `status: design_only`): the
`suspend_resume` realization (in-process `SuspendSignal` vs over-the-wire checkpoint token)
and the `ExecutorDefinition` reference resolution are contract material there, not restated
here.

**Consumed contracts** (operations this frame calls on another frame):

- None. This frame is a pure SPI slice; it invokes no operation on another frame. The
  co-resident `EF-ENGINE-PORT` consumes these orchestration types to express the
  transport-boundary surface, and `agent-service` realizes them — the dependency direction is
  toward this SPI, never outward from it.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame.

**This frame anchors no FunctionPoint.** `engineering-frames.dsl` carries no
`efOrchestrationSpi -> fp*` `anchors` edge — consistent with `design_only` status: the
contract types exist, but no FunctionPoint scenario is owned at this boundary yet. (A
`design_only` frame is permitted to anchor zero FunctionPoints; Rule G-23 / enforcer `E188`
requires an `anchors` edge only for a `shipped` frame — see section 7.)

The frame does participate on the value axis as a *traversed* node, not an owner: the DSL
edge `featSuspendResumeControl -> efOrchestrationSpi` (`saa.rel "traverses"`) records that the
Suspend/Resume Control feature routes across this frame's `SuspendSignal` / `Checkpointer`
seam. The FunctionPoint that realizes that behaviour — `FP-SUSPEND-RESUME` (`saa.owner`
`agent-service`, declared in
[`../../../features/function-points.dsl`](../../../features/function-points.dsl)) — is
*anchored* to `EF-TASK-CONTROL`, not here. `traverses` is a route, never ownership (ADR-0157
§2).

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Each enforcer / rule is cited as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- `OrchestrationSpiArchTest` (`test/com-huawei-ascend-bus-spi-engine-orchestrationspiarchtest`)
  — asserts the orchestration SPI in `com.huawei.ascend.bus.spi.engine` takes no dependency on
  the `com.huawei.ascend.service.platform` package and no Spring dependency (the
  forbidden-dependency invariant of section 2 for this slice).
- `SpiPurityGeneralizedArchTest` (enforcer `E48`,
  `test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest`) —
  asserts any package matching `com.huawei.ascend..spi..` (which includes `bus.spi.engine`)
  depends on neither Spring, the `com.huawei.ascend.service.platform` package, the in-memory
  reference implementations, Micrometer, nor OpenTelemetry.
- Rule `G-22` (enforcer `E187`) — Accepted-ADR Frame-Map Coherence: because ADR-0158 is
  accepted, `engineering-frames.dsl` MUST declare this frame `EF-ORCHESTRATION-SPI` (owner
  `agent-bus`) with a `genModule_agent_bus -> efOrchestrationSpi` contains edge; the gate
  fails if the accepted ADR's frame re-home is not reflected in the map.
- Rule `G-24` (enforcer `E189`) — Old Orchestration-SPI Package Ban: active authority surfaces
  must not name `engine.orchestration.spi` as the current home; ADR-0158 re-homed it to
  `com.huawei.ascend.bus.spi.engine`.
- Rule `G-29` (enforcer `E196`) — Frame-Card / DSL Parity: validates this card's copied
  identity fields and every fact citation above against the `efOrchestrationSpi` DSL element
  and the generated facts (ADR-0161).

**Tests anchoring the behaviour.** The neutral-contract invariants of this slice (the SPI
stays free of Spring and `com.huawei.ascend.service.platform` coupling) are proven by the test facts in this
card's frontmatter `fact_refs:` block, resolved by the gate against
`architecture/facts/generated/tests.json` and cited as the enforcing mechanism above. The
per-test asserted behaviour is the behaviour catalogue and lives with those test facts, not
as an inventory in this L1 card.

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`, and the orchestration types co-reside with the `EF-ENGINE-PORT`
> transport boundary in the shared `com.huawei.ascend.bus.spi.engine` package, so this frame
> has no package-scoped Type Inventory of its own; it anchors zero FunctionPoints; and the
> `Orchestrator` / `Checkpointer` production realizations live in `agent-service`, not behind
> this SPI. Promotion requires a frame-scoped boundary the fact layer can isolate (a declared
> `primaryPackage` or a sub-package split from `EF-ENGINE-PORT`), at least one anchored
> FunctionPoint, and a contract- and test-backed realization. Until those land, this frame
> stays `design_only` and carries no fact-cited Type Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- FunctionPoint inventory (the traversed `FP-SUSPEND-RESUME`, owned by `agent-service`): [`../../../features/function-points.dsl`](../../../features/function-points.dsl).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Co-resident transport-boundary frame: `EF-ENGINE-PORT` (same package, `agent-bus`, ADR-0158).
- Schema contract: [`../../../../docs/contracts/engine-port.v1.yaml`](../../../../docs/contracts/engine-port.v1.yaml) (`contract-yaml/engine-port`, Authority ADR-0158).
- This frame's L2 detail sink (orchestration / suspend-resume runtime mechanics): [`../../L2/engine-port-boundary/`](../../L2/engine-port-boundary/).
- ADR-0157 (EngineeringFrame ontology): [`../../../../docs/adr/0157-engineering-frame-ontology.yaml`](../../../../docs/adr/0157-engineering-frame-ontology.yaml).
- ADR-0158 (EnginePort transport-agnostic boundary; re-homes this frame to `agent-bus`): [`../../../../docs/adr/0158-engine-port-transport-agnostic-boundary.yaml`](../../../../docs/adr/0158-engine-port-transport-agnostic-boundary.yaml).
