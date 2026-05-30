---
# Frame Card frontmatter. The identity block is COPIED from the frame's DSL
# element in architecture/features/features.dsl (the re-tagged agent-service frame).
# Every value here MUST match the DSL; the gate fails a card whose frontmatter
# disagrees with the DSL element's saa.id / saa.owner / saa.status / saa.primaryPackage.
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-INTERNAL-EVENT-QUEUE
dsl_element: efInternalEventQueue
owner_module: agent-service
primary_package: ""
source_adr: ADR-0138|ADR-0155

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. This frame has no code home on disk
#     yet, so it cites no generated facts and the list is empty. ---
fact_refs: []
---

# `EF-INTERNAL-EVENT-QUEUE` — Internal Event Queue Frame

> Anchors the agent-service runtime's in-process eventing primitive: the structural home
> for a future event-queue infrastructure that decouples the emit side of a Run's lifecycle
> from the consume side, without itself crossing a service boundary.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-INTERNAL-EVENT-QUEUE` | DSL element |
| DSL element | `efInternalEventQueue` | `architecture/features/features.dsl` (re-tagged agent-service frame) |
| Owner module (`saa.owner`) | `agent-service` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0138` / `ADR-0155` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-INTERNAL-EVENT-QUEUE.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. This frame is `design_only` and has no Java home on disk yet, so the
> boundary below is the *required* capability boundary — it names what the frame will own
> when it lands, without implying any existing runtime code. No package names are cited
> because none exist in the generated fact layer; the reserved package root is named as a
> reservation, not as a fact.

**Can do** — the responsibilities this frame will own when it lands:

- Define the in-process `RunEvent` envelope and its channel routing for the runtime's
  lifecycle events (run created, state transition, suspend/resume requested, S2C, child run,
  cancel requested, terminal transition, third-party callback), as specified in the design
  deep-dive (`architecture/docs/L1/agent-service/features/internal-event-queue.md`, AS-L1-F17).
- Split event publication from consumption and own the producer / consumer / lease / ack /
  retry / dead-letter semantics of the in-process queue (deep-dive AS-L1-F18).
- Project the event stream into the views the Access Layer can serve (SSE stream or polling
  snapshot) without treating a client connection as the queue itself (deep-dive AS-L1-F20).
- Express long-running rhythm — timeout, deadline, resume sweep, heartbeat, queue-lag
  observability — over a rhythm-tick signal (deep-dive AS-L1-F21).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Cross-service / inter-process eventing. Cross-service event transport flows through the
  `agent-bus` three-track channels (control / data / rhythm) with physical isolation; this
  frame is the runtime's *internal* eventing primitive only and must not become a second
  cross-service transport.
- Own or define the physical channel isolation contract. The control / data / rhythm
  three-track binding is owned by `agent-bus` (frame `EF-CHANNEL-ISOLATION`); this frame
  consumes that binding, it does not declare it.
- Drive the Run state machine or own Run / Session / Task aggregate state — that is the
  agent-service state frame `EF-SESSION-TASK-STATE`; this frame routes the *events* a state
  transition emits, not the state itself.
- Decide admission, fire lifecycle hooks, or terminate a Run — those are `EF-ACCESS-ADMISSION`,
  the agent-middleware hook frame `EF-HOOK-SURFACE`, and `EF-TASK-CONTROL` respectively.
- The durability tier, persistence mechanics, lease/ack wire semantics, and over-the-wire
  channel mechanics are L2 detail — delegated to the frame's L2 sink and the `run-event.v1.yaml`
  contract surface, not restated in this card.

**Owned state** — the data/state this frame will be the structural home for:

- The `RunEvent` envelope value and its channel-routing decision (the in-process eventing
  contract), per the design deep-dive — **not** any cross-service channel, which lives in
  `agent-bus`.
- The in-memory queue's bounded-buffer / back-pressure posture and its producer/consumer
  bookkeeping (lease, ack, retry, dead-letter) — when the code home lands. No such state
  exists on disk today.

**External dependencies** — frames / modules this frame is allowed to depend on (when it lands):

- `agent-bus` — the runtime's events are projected onto, and cross-service flows are handed
  off to, the bus three-track channels (control / data / rhythm); this frame consumes that
  transport rather than re-implementing it.
- `EF-SESSION-TASK-STATE` (agent-service) — the source of the lifecycle state transitions
  this frame turns into routeable events.

**Forbidden dependencies** — dependencies the boundary must never take (to be held by an
ArchUnit enforcer once the package home exists; see section 7):

- A direct dependency on the Access Layer transport (SSE / polling sinks) as *the queue* —
  the frame exposes a projection surface to the Access Layer (`EF-INGRESS-GATEWAY`), it must
  not collapse a client connection into the queue itself.
- A second cross-service transport path that bypasses the `agent-bus` channels — the internal
  queue must not duplicate the bus's control/data/rhythm isolation.

**Included / excluded packages** (reservation, not a fact — no code exists yet):

- Reserved root: `com.huawei.ascend.service.runtime.events` (the `saa.devPaths` reservation in
  the DSL element). This package does **not** exist in the generated fact layer yet; it is
  named here as the reserved home, not cited as a `code-symbol/*` fact.
- Excluded: the `agent-bus` channel packages (`com.huawei.ascend.bus.*`) — the shipped
  cross-service channel transport, owned by the bus frames, not by this frame.

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). The Card generator owns this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element. Its
  reserved package root (com.huawei.ascend.service.runtime.events) has no code home on disk,
  so the generated fact layer (code-symbols.json) carries no in-boundary type for this frame
  and no fact-cited Type Inventory is rendered here (README §"Status-conditional rules"). This
  block stays empty — with stable markers — until the frame is promoted to `shipped` with a
  declared `primaryPackage` and a real Java home. See section 7 for the missing-proof
  statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`, and no
  code home on disk, so there are no in-boundary types and no generated structural relationship
  table. Stable markers retained for the gate's block anchor. The intended collaboration —
  producer/consumer split over a routed RunEvent envelope, projected to the Access Layer and
  handed off to the agent-bus channels — is described as authored prose in section 2 and in the
  design deep-dive.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.

**Exposed SPI / public surface (boundary identity):**

- This frame exposes **no SPI of its own today** — it has no Java home and no `spi_packages`
  entry in the generated fact layer. Its intended public surface is the in-process `RunEvent`
  envelope plus producer/consumer ports described in the design deep-dive
  (`architecture/docs/L1/agent-service/features/internal-event-queue.md`); none of those types
  exist in `code-symbols.json` yet, so none is cited here.

**Contract operations (OpenAPI / AsyncAPI):**

- None. The generated `contract-surfaces.json` carries no `contract-op/*` for the internal
  event queue. The `RunEvent` envelope taxonomy is documented in the design contract
  `docs/contracts/run-event.v1.yaml` (itself `design_only`); it is a prose schema document,
  not a fact-cited `contract-op`, and the on-the-wire mechanics of any future channel are L2
  detail delegated to that contract and the frame's L2 sink, not restated here.

**Consumed contracts** (surfaces this frame will depend on, owned by another frame):

- The `agent-bus` three-track channel contract (control / data / rhythm) — the cross-service
  transport this frame projects onto and hands cross-service flows off to. The channel
  isolation contract is owned by the bus frames (`EF-CHANNEL-ISOLATION` / `EF-INGRESS-GATEWAY`),
  not by this frame.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame. The gate fails a card that lists a FunctionPoint with no backing
> `anchors` edge.

This frame anchors **zero** FunctionPoints. The only DSL edge touching `efInternalEventQueue`
in `architecture/features/engineering-frames.dsl` is the module-membership edge
`genModule_agent_service -> efInternalEventQueue` (`saa.rel "contains"`); there is no
`efInternalEventQueue -> fp…` (`saa.rel "anchors"`) edge. As a `design_only` frame with no
code home, it is correct for the frame to anchor no FunctionPoint: a FunctionPoint is a
concrete method/scenario, and no such method exists on disk yet. The required capability
clusters (deep-dive AS-L1-F17..F23, F54..F56) describe the *future* FunctionPoints this frame
will anchor once its package home lands; they are not asserted here as anchored edges.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier.

**Constraints / enforcers holding the boundary today:**

- The Frame-Card consistency gate holds this card's identity block against the
  `efInternalEventQueue` DSL element (`saa.id` / `saa.owner` / `saa.status` /
  `saa.primaryPackage`) and cross-checks every `fact_refs` entry against
  `architecture/facts/generated/*.json` (ADR-0161). Because `fact_refs` is empty and no
  `primaryPackage` is declared, the gate holds this frame as a pure `design_only` boundary.
- No ArchUnit enforcer can hold the package boundary yet, because the reserved package root
  (`com.huawei.ascend.service.runtime.events`) has no code home. The dependency invariants in
  section 2 (no Access-Layer-as-queue, no second cross-service transport) become enforceable
  ArchUnit assertions only when that home lands.

**Tests anchoring the behaviour** (fact-cited):

- None. No test in `tests.json` targets this frame, because there is no production code to
  test. The design deep-dive's three-layer test plan is design intent, not running evidence.

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`, and the reserved package root
> `com.huawei.ascend.service.runtime.events` has no Java home on disk — the generated fact
> layer carries no `code-symbol/*`, `test/*`, or `contract-op/*` for this frame, so this card
> cites no facts. The frame also anchors zero FunctionPoints (no `anchors` edge in the DSL).
> Until a real package home lands, declares a `primaryPackage`, contributes the `RunEvent`
> envelope + producer/consumer ports with contract- and test-backed behaviour, and the DSL
> gains `anchors` edges to concrete FunctionPoints, this frame stays `design_only` and carries
> an empty fact-cited Type Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/features.dsl`](../../../features/features.dsl) (re-tagged agent-service frame `efInternalEventQueue`) + membership edge in [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts (authority over this prose; this frame cites none yet): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Design deep-dive (required capability boundary, `design_only`): [`../agent-service/features/internal-event-queue.md`](../agent-service/features/internal-event-queue.md).
- Owner module L1 design: [`../agent-service/ARCHITECTURE.md`](../agent-service/ARCHITECTURE.md).
- Source ADRs: [`../../../../docs/adr/0138-agent-service-five-layer-l1-ratification.yaml`](../../../../docs/adr/0138-agent-service-five-layer-l1-ratification.yaml), [`../../../../docs/adr/0155-agent-service-l1-v1-2-internal-module-design.yaml`](../../../../docs/adr/0155-agent-service-l1-v1-2-internal-module-design.yaml).
- `RunEvent` design contract (`design_only`): [`../../../../docs/contracts/run-event.v1.yaml`](../../../../docs/contracts/run-event.v1.yaml).
- This frame's L2 detail sink (queue runtime + channel mechanics, when it lands): `architecture/docs/L2/internal-event-queue/`.
