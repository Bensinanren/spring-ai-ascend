---
# Frame Card frontmatter. The identity block is COPIED from the frame's DSL
# element efEngineDispatch in architecture/features/features.dsl (agent-service
# frames are re-tagged from the ADR-0138 Layer features there). Every value here
# MUST match the DSL; the gate fails a card whose frontmatter disagrees with the
# DSL element's saa.id / saa.owner / saa.status / saa.primaryPackage.
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-ENGINE-DISPATCH
dsl_element: efEngineDispatch
owner_module: agent-service
primary_package: ""               # design_only — no single runtime.engine Java home is wired yet
source_adr: ADR-0138|ADR-0155

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. The gate cross-checks these. The only
#     types that exist under this frame's dev paths today are the executor SPI
#     surface; the dispatch/registry core is unimplemented (design_only). ---
fact_refs:
  - code-symbol/com-huawei-ascend-service-runtime-executor-spi-executoradapter
  - code-symbol/com-huawei-ascend-service-runtime-executor-spi-injectionmode
---

# `EF-ENGINE-DISPATCH` — Engine Dispatch Frame

> Anchors the service-side engine-adapter dispatch boundary: resolving a service runtime
> envelope to its registered `ExecutorAdapter` and driving that adapter's invocation as
> Layer 4 of the agent-service per-layer architecture.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-ENGINE-DISPATCH` | DSL element |
| DSL element | `efEngineDispatch` | `architecture/features/features.dsl` (agent-service frame) |
| Owner module (`saa.owner`) | `agent-service` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (design_only — none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0138 \| ADR-0155` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-ENGINE-DISPATCH.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry. Items marked `[design_only]` name a
> responsibility whose Java home is not wired yet — no code backs them today.

**Can do** — the durable responsibilities this frame is the structural home for:

- Define the service-side executor SPI a resolved engine type is bound to — the adapter
  invocation surface and its injection-mode declaration
  (`code-symbol/com-huawei-ascend-service-runtime-executor-spi-executoradapter`,
  `code-symbol/com-huawei-ascend-service-runtime-executor-spi-injectionmode`).
- `[design_only]` Resolve a service runtime envelope to the single registered
  `ExecutorAdapter` for its engine type, and reject a payload that matches no registered
  adapter rather than silently reinterpreting it (the `com.huawei.ascend.service.runtime.engine`
  dispatch core; no type exists yet).
- `[design_only]` Drive the resolved adapter's invocation pathway that advances the Run
  state machine (the executor invocation pathway; no type exists yet).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Own the *engine-module* in-process registry, envelope value, or `EnginePort` realization —
  `com.huawei.ascend.engine.runtime` (`EngineRegistry`, `EngineEnvelope`, `InProcessEnginePort`)
  belongs to the agent-execution-engine frame `EF-ENGINE-REGISTRY`. This frame is the
  *service-side* adapter-dispatch boundary, distinct from that engine-module frame.
- Define the engine boundary SPI itself — `com.huawei.ascend.bus.spi.engine.EnginePort` and
  its request/descriptor types are owned by the agent-bus frame `EF-ENGINE-PORT`.
- Own the Session / Task / Run aggregate lifecycles — those are agent-service frames
  `EF-SESSION-TASK-STATE` (Layer 3) and `EF-TASK-CONTROL`; this frame consumes the Run state it
  advances, it does not define it.
- Fire cross-cutting lifecycle hooks — hook dispatch is the agent-middleware frame
  `EF-HOOK-SURFACE`; the cross-module Engine Contract feature is `FEAT-ENGINE-DISPATCH-AND-HOOKS`,
  which *traverses* this frame (it does not own it).
- The runtime sequence of matching, the strict-mismatch failure path, and the executor
  invocation mechanics are L2 detail — delegated to the contract surface and the frame's L2
  sink, not restated in this card.

**Owned state** — the data/state this frame is the structural home for:

- The service-side executor SPI contract surface
  (`code-symbol/com-huawei-ascend-service-runtime-executor-spi-executoradapter`) and its
  injection-mode enum (`code-symbol/com-huawei-ascend-service-runtime-executor-spi-injectionmode`).
- `[design_only]` The in-memory engine-type → `ExecutorAdapter` registration/resolution surface
  under `com.huawei.ascend.service.runtime.engine` — not yet implemented.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `com.huawei.ascend.bus.spi.engine` (frame `EF-ENGINE-PORT`) — the neutral engine boundary SPI
  whose request/definition types the service-side dispatch resolves against.
- `com.huawei.ascend.engine.runtime` (frame `EF-ENGINE-REGISTRY`) — the engine-module registry /
  in-process `EnginePort` realization this service-side dispatch delegates execution to.
- `com.huawei.ascend.service.runtime.runs` (frame `EF-TASK-CONTROL`) — the Run aggregate whose
  state the dispatched adapter invocation advances.
- `com.huawei.ascend.middleware.spi` (frame `EF-HOOK-SURFACE`) — the `RuntimeMiddleware` hook
  surface cross-cutting policies are expressed through.

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cite it in section 7):

- The agent-service web / HTTP edge (`com.huawei.ascend.service.platform.web`) — the dispatch
  layer is a runtime-internal concern and must not import the inbound web adapter.
- Out-of-registry pattern-matching on engine/definition subtypes — once the dispatch core lands,
  resolution MUST flow through a registry, never an `instanceof` ladder (the engine-module
  invariant held by enforcer `E74`; the service-side equivalent is a promotion pre-condition).

**Included / excluded packages** (this frame is a package *cluster* spanning two dev paths):

- Included: `com.huawei.ascend.service.runtime.engine` (dispatch core — `[design_only]`, no type yet),
  `com.huawei.ascend.service.runtime.executor.spi` (executor SPI — present).
- Excluded: `com.huawei.ascend.engine.runtime` (the engine-module registry — frame `EF-ENGINE-REGISTRY`),
  `com.huawei.ascend.bus.spi.engine` (the engine boundary SPI — frame `EF-ENGINE-PORT`).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). Every row cites its `code-symbol/<kebab-fqn>` fact ID. The Card generator owns
> this region and overwrites it on every re-render. Only the executor SPI surface
> (`com.huawei.ascend.service.runtime.executor.spi`) has extracted types today; the dispatch
> core (`com.huawei.ascend.service.runtime.engine`) is design_only and contributes no rows.

<!-- BEGIN GENERATED: type-inventory -->
| Type | Kind | Fact ID |
|---|---|---|
| `com.huawei.ascend.service.runtime.executor.spi.ExecutorAdapter` | interface | `code-symbol/com-huawei-ascend-service-runtime-executor-spi-executoradapter` |
| `com.huawei.ascend.service.runtime.executor.spi.InjectionMode` | enum | `code-symbol/com-huawei-ascend-service-runtime-executor-spi-injectionmode` |
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
| From | Relationship | To |
|---|---|---|
| `ExecutorAdapter` | references | `InjectionMode` |
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- `com.huawei.ascend.service.runtime.executor.spi.ExecutorAdapter`
  (`code-symbol/com-huawei-ascend-service-runtime-executor-spi-executoradapter`) — the
  service-side executor SPI a resolved engine type binds to; the structural boundary other
  service runtime layers depend on for adapter invocation.
- `com.huawei.ascend.service.runtime.executor.spi.InjectionMode`
  (`code-symbol/com-huawei-ascend-service-runtime-executor-spi-injectionmode`) — the
  injection-mode declaration an adapter exposes, part of the same SPI surface.

**Contract surfaces (schema contracts):** none owned by this frame. The engine schema
contracts (`engine-envelope`, `engine-hooks`, `engine-port`) are owned by the engine-module
and agent-bus frames, surfaced below as *consumed*, not as this frame's exposed surface.

**Consumed contracts** (surfaces this frame depends on, owned by another frame):

- `com.huawei.ascend.bus.spi.engine` SPI (frame `EF-ENGINE-PORT`) — the neutral engine boundary
  (`EnginePort` / `ExecuteRequest` / `ExecutorDefinition`) the service-side dispatch resolves
  against; its schema is `contract-yaml/engine-port`.
- `contract-yaml/engine-envelope` (engine module, frame `EF-ENGINE-REGISTRY`) — the envelope
  shape + `known_engines` set the registry resolves; consumed when dispatching.
- `contract-yaml/engine-hooks` (engine module / frame `EF-HOOK-SURFACE`) — the canonical
  `HookPoint` set + ordering cross-cutting policy is delivered through.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. Lists ONLY FunctionPoints the DSL
> anchors to this frame (`efEngineDispatch -> fp<Name>` with `saa.rel "anchors"` in
> `engineering-frames.dsl`). A `design_only` frame may anchor zero FunctionPoints.

This frame anchors **zero** FunctionPoints. The only DSL edges incident on `efEngineDispatch`
are `genModule_agent_service -> efEngineDispatch` (`saa.rel "contains"`) and
`featEngineDispatchAndHooks -> efEngineDispatch` (`saa.rel "traverses"`); neither is an
`anchors` edge. No FunctionPoint will be listed here until the dispatch core lands a concrete
entry method and the DSL gains an `anchors` edge to it.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- Frame-Card consistency gate — fails this card if its frontmatter identity block disagrees
  with the `efEngineDispatch` DSL element (`saa.id` / `saa.owner` / `saa.status` /
  `saa.primaryPackage`), or if any cited fact ID does not resolve in
  `architecture/facts/generated/*.json`.
- Planned dispatch-through-registry invariant — the engine-module enforcer `E74`
  (`EnginePayloadDispatchOnlyViaRegistryTest`) asserts dispatch flows through a registry rather
  than an out-of-registry `instanceof` ladder; the service-side dispatch core must satisfy the
  equivalent shape before promotion.

**Tests anchoring the behaviour** (fact-cited): none. The frame's verification surface
(`saa.verificationTestFqns` = `com.huawei.ascend.service.runtime.engine.*IT`) names an
integration-test target that has no test class yet — the dispatch core is unimplemented.

> **Missing proof before promotion to `shipped`:** the dispatch core under
> `com.huawei.ascend.service.runtime.engine` does not exist — no `saa.primaryPackage` Java home
> is declared, no resolution/registration type is implemented, and the
> `com.huawei.ascend.service.runtime.engine.*IT` verification target has no test class. This
> frame anchors zero FunctionPoints. Today only the executor SPI surface
> (`com.huawei.ascend.service.runtime.executor.spi`) exists; the registry/dispatch core and its
> Run-advancing invocation pathway must land — with a fact-cited entry method, an `anchors`
> edge, and passing integration tests — before this frame can leave `design_only`.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/features.dsl`](../../../features/features.dsl) (agent-service frame `efEngineDispatch`).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- The engine-module dispatch frame (distinct from this service-side frame): `EF-ENGINE-REGISTRY` (agent-execution-engine).
- The neutral engine boundary SPI this frame resolves against: `EF-ENGINE-PORT` (agent-bus, ADR-0158).
- The deep-dive inventory (proto-L2): [`../agent-service/features/engine-dispatch-execution.md`](../agent-service/features/engine-dispatch-execution.md).
- This frame's L2 detail sink (dispatch + invocation mechanics, once the core lands): `architecture/docs/L2/engine-dispatch/`.
