---
# Frame Card frontmatter. The identity block is COPIED from the frame's DSL
# element in architecture/features/engineering-frames.dsl. Every value here MUST
# match the DSL; the gate fails a card whose frontmatter disagrees with the DSL
# element's saa.id / saa.owner / saa.status / saa.primaryPackage.
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology); ADR-0158 (transport-agnostic EnginePort boundary)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-ENGINE-PORT
dsl_element: efEnginePort
owner_module: agent-bus
primary_package: ""               # design_only: the DSL declares no saa.primaryPackage (the boundary is a curated slice of the shared bus.spi.engine package, not a frame-owned root)
source_adr: ADR-0158

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. The gate cross-checks these. ---
fact_refs:
  - code-symbol/com-huawei-ascend-bus-spi-engine-engineport
  - code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext
  - code-symbol/com-huawei-ascend-bus-spi-engine-executerequest
  - code-symbol/com-huawei-ascend-bus-spi-engine-agentevent
  - code-symbol/com-huawei-ascend-bus-spi-engine-definitionref
  - code-symbol/com-huawei-ascend-bus-spi-engine-enginedescriptor
  - code-symbol/com-huawei-ascend-bus-spi-engine-definitionresolver
  - contract-yaml/engine-port
  - test/com-huawei-ascend-bus-spi-engine-orchestrationspiarchtest
  - test/com-huawei-ascend-service-runtime-orchestration-engineportsignaturenoregressiontest
  - test/com-huawei-ascend-service-runtime-orchestration-enginefacingcontexthasnotenantsessiontest
  - test/com-huawei-ascend-service-runtime-orchestration-boundarydispatchusesdefinitionrefnotlambdatest
---

# `EF-ENGINE-PORT` — Engine Port Frame

> Anchors the neutral, transport-agnostic Service↔Engine boundary: the `EnginePort`
> contract, its engine-facing `ExecutionContext`, the serializable `ExecuteRequest`
> (carrying a `DefinitionRef`, never an inline definition), the streamed `AgentEvent`,
> and the `EngineDescriptor` returned by `describe()`. The boundary is one semantic
> contract for three deployment forms; rationale lives in `source_adr`.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-ENGINE-PORT` | DSL element |
| DSL element | `efEnginePort` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-bus` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (design_only; no frame-owned package root) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0158` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-ENGINE-PORT.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame:

- Declare the neutral Service↔Engine port: a Service drives an engine through
  `EnginePort` (`code-symbol/com-huawei-ascend-bus-spi-engine-engineport`); an engine
  implements it; the two modules never depend on each other.
- Carry the engine-facing execution context as the tenant/session-free supertype of
  `RunContext` — opaque correlation (`runId` / `traceId` / `spanId`) plus the suspend
  capability only (`code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext`).
- Carry the dispatch request as a serializable value naming a capability by reference,
  not by inline behaviour (`code-symbol/com-huawei-ascend-bus-spi-engine-executerequest`
  with `code-symbol/com-huawei-ascend-bus-spi-engine-definitionref`).
- Define the streamed execution outcome where suspension and failure are TERMINAL
  events, never exceptions thrown across the boundary
  (`code-symbol/com-huawei-ascend-bus-spi-engine-agentevent`).
- Advertise engine identity and health to the Service for selection
  (`code-symbol/com-huawei-ascend-bus-spi-engine-enginedescriptor` via `describe()`),
  and bridge a `DefinitionRef` back to a runnable definition
  (`code-symbol/com-huawei-ascend-bus-spi-engine-definitionresolver`).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Carry tenant / session / nested-run semantics — those belong to the Service-side
  `RunContext` and the neutral execution model owned by the sibling agent-bus frame
  `EF-ORCHESTRATION-SPI` (`Orchestrator`, `RunContext`, `SuspendSignal`, `Checkpointer`,
  `ExecutorDefinition`, `RunMode`, `TraceContext`).
- Provide any engine realization — the in-process realization (`InProcessEnginePort`)
  and the engine-internal registry are owned by the agent-execution-engine frame
  `EF-ENGINE-REGISTRY`; the networked realizations (`RpcEnginePort`, `A2aEnginePort`)
  and the in-process adapter wiring are the agent-service engine-adapter layer.
- Select or operate a transport — choosing in-process / internal-RPC / A2A by
  deployment form is the Service's adapter responsibility, not the port contract's.
- The over-the-wire suspend/resume checkpoint-token mechanics, the per-form physical
  placement, and the wire field semantics are L2 detail — delegated to the frame's L2
  sink and the contract surface, not restated in this card.

**Owned state** — the data/state this frame is the structural home for:

- No mutable runtime state. The frame is a contract surface: the boundary interface
  (`EnginePort`), its value types (`ExecuteRequest`, `AgentEvent`, `DefinitionRef`,
  `EngineDescriptor`), and the engine-facing `ExecutionContext` supertype.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `java.*` only (plus same-SPI-package siblings). The port returns a
  `java.util.concurrent.Flow.Publisher` of events; `java.util.concurrent.Flow` is part
  of `java.*` and is allowed under the SPI-purity rule (see section 7).
- The neutral execution model in the same package (`bus.spi.engine`) owned by
  `EF-ORCHESTRATION-SPI` — `ExecutorDefinition` (referenced by `DefinitionResolver`),
  `RunContext` (the Service-side subtype of this frame's `ExecutionContext`).

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cite it in section 7):

- `org.springframework.*` and any framework runtime — the SPI is pure Java
  (enforcer `E48`); a Spring import on `bus.spi.engine` is the prohibited shape.
- Any `agent-service` or `agent-execution-engine` production package — the contract is
  owned by no single engine and carries no reverse dependency on its drivers or
  realizations.

**Included / excluded packages** (this frame is a curated slice of a shared package, not a single root):

- In-boundary types (within `com.huawei.ascend.bus.spi.engine`): `EnginePort`,
  `ExecutionContext`, `ExecuteRequest`, `AgentEvent` (and its `Finished` / `Failed` /
  `InterruptRequest` / `Kind` members), `DefinitionRef`, `EngineDescriptor`,
  `DefinitionResolver`.
- Excluded (same package, owned by `EF-ORCHESTRATION-SPI`): `Orchestrator`,
  `RunContext`, `SuspendSignal`, `Checkpointer`, `ExecutorDefinition` (and members),
  `RunMode`, `TraceContext`. These are surfaced as same-package collaborators in
  section 5, not as this frame's Type Inventory.

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). Every row cites its `code-symbol/<kebab-fqn>` fact ID. The Card generator owns
> this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
| Type | Kind | Fact ID |
|---|---|---|
| _(deferred — this `design_only` frame declares no `saa.primaryPackage`, so the generator emits no fact-cited inventory; the in-boundary types are a curated slice of the shared `bus.spi.engine` package and are cited in the authored Capability Boundary and Contracts sections until the frame is promoted with a package root or an `engineering-frames.json` per-frame type set lands.)_ |  |  |
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
| From | Relationship | To |
|---|---|---|
| _(deferred — see the Type-Inventory note above; with no generator-emitted in-boundary type set there is no fact-derived collaboration table for this `design_only` frame.)_ |  |  |
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- `com.huawei.ascend.bus.spi.engine.EnginePort`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-engineport`) — the neutral port the
  Service drives and the engine implements: a streaming `execute` plus `describe`.
- `com.huawei.ascend.bus.spi.engine.ExecutionContext`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-executioncontext`) — the engine-facing
  context with no tenant/session method (the supertype of `RunContext`).
- `com.huawei.ascend.bus.spi.engine.ExecuteRequest`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-executerequest`) and
  `com.huawei.ascend.bus.spi.engine.DefinitionRef`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-definitionref`) — the serializable
  request and the by-name capability reference it carries.
- `com.huawei.ascend.bus.spi.engine.AgentEvent`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-agentevent`) — the streamed outcome
  event type (exactly one TERMINAL event per execution leg).
- `com.huawei.ascend.bus.spi.engine.EngineDescriptor`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-enginedescriptor`) and
  `com.huawei.ascend.bus.spi.engine.DefinitionResolver`
  (`code-symbol/com-huawei-ascend-bus-spi-engine-definitionresolver`) — the engine
  self-description and the reference↔definition bridge.

**Contract surfaces (schema contracts):**

| Contract | Fact ID | Contract source |
|---|---|---|
| EnginePort neutral wire shape (boundary statement, operations, deployment forms, suspend/resume) | `contract-yaml/engine-port` | `docs/contracts/engine-port.v1.yaml` |

> `engine-port.v1.yaml` is a YAML *schema* surface (`fact_kind: schema`, `status: design_only`,
> `authority: ADR-0158`), not an OpenAPI/AsyncAPI operation; the public HTTP `contract-op/*`
> operations belong to the agent-service edge frames, not to this frame.

**Consumed contracts** (surfaces this frame depends on, owned by another frame):

- The neutral execution model in the same `bus.spi.engine` package, owned by
  `EF-ORCHESTRATION-SPI` — `ExecutorDefinition` (the definition a `DefinitionRef`
  resolves to) and `RunContext` (the Service-side subtype of this frame's
  `ExecutionContext`).

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. Lists ONLY FunctionPoints the DSL
> anchors to this frame. A `design_only` frame may anchor zero FunctionPoints — and this
> one does.

This frame anchors **zero** FunctionPoints. In `architecture/features/engineering-frames.dsl`
the only edges incident on `efEnginePort` are the structural `genModule_agent_bus -> efEnginePort`
(`contains`) edge and the value-axis `featEngineDispatchAndHooks -> efEnginePort` (`traverses`)
edge; there is no `anchors` edge. As a boundary contract whose concrete behaviour is realized by
other frames (`EF-ENGINE-REGISTRY` in-process; the agent-service engine-adapter layer over the
wire), this frame carries the contract surface, and the executable behaviour is anchored by the
frames that realize and drive it. The shipped-frame anchor rule (section 7) therefore does not
apply to it while it is `design_only`.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- Enforcer `E48` (`SpiPurityGeneralizedArchTest`) — asserts any
  `com.huawei.ascend..spi..` package (which includes `bus.spi.engine`) does not depend
  on Spring, the platform, reference impls, Micrometer, or OpenTelemetry; the port stays
  pure Java.
- Rule `G-22` (enforcer `E187`, `accepted_adr_frame_map_coherence`) — asserts that while
  ADR-0158 is `accepted`, the frame map declares `EF-ENGINE-PORT` (owner `agent-bus`)
  and `EF-ORCHESTRATION-SPI` (owner `agent-bus`) under a `genModule_agent_bus` contains
  edge, so this frame's identity cannot drift from the accepted ADR.
- Rule `G-24` (enforcer `E189`, `old_orchestration_spi_package_ban`) — asserts no active
  authority surface names the `engine.orchestration.spi` package (re-homed by ADR-0158) as
  the current home of this contract; `bus.spi.engine` is the single current home.
- Rule `G-29` (enforcer `E196`, `frame_card_consistency`) — asserts this card copies the
  DSL identity exactly and that every fact ID it cites resolves in
  `architecture/facts/generated/*.json`.

**Tests anchoring the behaviour** (fact-cited):

- `test/com-huawei-ascend-bus-spi-engine-orchestrationspiarchtest` — `bus.spi.engine` has
  no Spring and no platform dependency (SPI purity of the boundary package).
- `test/com-huawei-ascend-service-runtime-orchestration-engineportsignaturenoregressiontest`
  — `execute` is the streaming signature, `describe` returns an `EngineDescriptor`, and the
  old value-returning `execute` is gone.
- `test/com-huawei-ascend-service-runtime-orchestration-enginefacingcontexthasnotenantsessiontest`
  — `ExecutionContext` declares no tenant or session method (the neutral-context invariant).
- `test/com-huawei-ascend-service-runtime-orchestration-boundarydispatchusesdefinitionrefnotlambdatest`
  — `ExecuteRequest` carries a `DefinitionRef` and no inline `ExecutorDefinition`, is
  serializable, and `EnginePort.execute` takes the wire request.

> **Missing proof before promotion to `shipped`:** the DSL declares no
> `saa.primaryPackage`, so this frame has no frame-owned Java home and no generator-emitted
> fact-cited Type Inventory; it anchors zero FunctionPoints; and it owns no engine
> realization (the realizations live in `EF-ENGINE-REGISTRY` and the agent-service
> engine-adapter layer). Until a frame-owned package root (or an `engineering-frames.json`
> per-frame type set) and at least one anchored FunctionPoint land, this frame stays
> `design_only` and carries no generated Type Inventory.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl) (`efEnginePort`).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Sibling frame that realizes this boundary in-process: `EF-ENGINE-REGISTRY` (agent-execution-engine).
- Sibling frame owning the neutral execution model in the same package: `EF-ORCHESTRATION-SPI` (agent-bus).
- This frame's L2 detail sink (suspend/resume wire mechanics, transport adapters, re-namespacing migration): [`../../L2/engine-port-boundary/`](../../L2/engine-port-boundary/).
