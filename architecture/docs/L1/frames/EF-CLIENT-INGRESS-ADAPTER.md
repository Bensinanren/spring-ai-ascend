---
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-CLIENT-INGRESS-ADAPTER
dsl_element: efClientIngressAdapter
owner_module: agent-client
primary_package: ""
source_adr: ADR-0157

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. The gate cross-checks these. ---
fact_refs:
  - code-symbol/com-huawei-ascend-bus-spi-ingress-ingressgateway
  - test/com-huawei-ascend-client-architecture-edgetocomputedirectlinkarchtest
---

# `EF-CLIENT-INGRESS-ADAPTER` — Client Ingress Adapter Frame

> The edge-plane client SDK slice that submits a Run through the ingress SPI and
> consumes its outputs asynchronously via the Cursor Flow — a skeleton boundary
> with no production SDK code yet. Rationale lives in `source_adr` (ADR-0157) and
> the module authority ADR-0049 / ADR-0089.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate (Rule G-29) fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-CLIENT-INGRESS-ADAPTER` | DSL element |
| DSL element | `efClientIngressAdapter` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-client` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0157` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-CLIENT-INGRESS-ADAPTER.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame (target shape; no
production code realizes them yet, see section 7):

- Submit a client-originated Run across the edge → compute_control boundary through
  the single allowed cross-plane entry point, the ingress SPI interface
  `com.huawei.ascend.bus.spi.ingress.IngressGateway`
  (`code-symbol/com-huawei-ascend-bus-spi-ingress-ingressgateway`, owned by
  `EF-INGRESS-GATEWAY` on `agent-bus`).
- Realize the client half of the Cursor Flow (Rule R-F): submission returns a Task
  Cursor, and the SDK consumes process state and intermediate-result checkpoints
  asynchronously — the cursor / SSE / webhook consumption named in the frame's DSL
  description — rather than blocking on a synchronous response.
- Carry edge-side replay / idempotency helpers and posture-aware backoff for that
  asynchronous consumption.

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Own or declare the ingress SPI. The `IngressGateway` boundary and its
  envelope/response carriers are owned by `EF-INGRESS-GATEWAY`
  (`com.huawei.ascend.bus.spi.ingress`); this frame is a downstream *consumer* of
  that SPI, not its home.
- Reach the compute_control planes directly. The SDK MUST NOT import any class under
  `com.huawei.ascend.{service,engine,middleware}..`; all cross-plane traffic flows
  exclusively through the ingress SPI (the no-direct-link clause, ADR-0089, held by
  the enforcer in section 7).
- Run server-side orchestration (owned by `agent-service`), select a heterogeneous
  engine (owned by `agent-execution-engine`; the neutral orchestration/engine SPI is
  owned by `agent-bus` as `com.huawei.ascend.bus.spi.engine` per ADR-0158), or own a
  bus channel (owned by `agent-bus`).
- Carry the submit → cursor → stream call sequence, the envelope field shapes, the
  HTTP/transport mechanics, or the SSE/webhook framing. Those are L2 / contract /
  verification material delegated to the contract surface and the frame's L2 sink,
  not restated in this card.

**Owned state** — the data/state this frame is the structural home for:

- None today. The frame is a `design_only` boundary: the module reserves the SPI
  package name `com.huawei.ascend.client.spi` in its `module-metadata.yaml`
  (`spi_packages`), but the package carries no types yet, and the SDK produces no SPI
  of its own (it is a pure consumer module). The frame holds no mutable runtime state
  and no Java production home at this status.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `agent-bus` — the SDK legitimately consumes the `com.huawei.ascend.bus.spi.ingress`
  ingress SPI as its sole cross-plane entry point (`agent-bus` is intentionally NOT
  in the module's `forbidden_dependencies`). The module-build fact layer is the
  authority for that envelope.

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cited in section 7):

- `com.huawei.ascend.service..`, `com.huawei.ascend.engine..`,
  `com.huawei.ascend.middleware..` — the edge plane must never import compute_control
  plane code; every cross-plane call routes through the ingress SPI instead (the
  module's `forbidden_dependencies` lists `agent-service`, `agent-execution-engine`,
  `agent-middleware`, `agent-evolve`; held by `EdgeToComputeDirectLinkArchTest` and
  gate Rule 105).

**Included / excluded packages** (this frame is a single-module edge slice, not a
multi-root cluster):

- Included (reserved, no types yet): `com.huawei.ascend.client.spi` and the
  `com.huawei.ascend.client` SDK root where SDK code lands at W3+.
- Excluded: `com.huawei.ascend.bus.spi.ingress` (the ingress SPI the SDK *consumes* —
  owned by `EF-INGRESS-GATEWAY` on `agent-bus`, not by this frame).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). The Card generator owns this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element.
  No production type under the agent-client package root exists in the generated facts
  yet — the agent-client module is a skeleton (no SDK code), so no fact-cited Type
  Inventory is generated here (README §"Status-conditional rules"). This block stays
  empty (with stable markers) until the frame is promoted to `shipped` with a declared
  `primaryPackage` and the SDK types land in `code-symbols.json`. See section 7 for the
  missing-proof statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`,
  and no in-boundary production type in the generated facts, so there is no generated
  structural relationship table. Stable markers retained for the gate's block anchor.
  The one structural relationship that matters today — the SDK's intended *consumption*
  of the agent-bus ingress SPI — is a cross-frame dependency, stated as authored prose
  in sections 2 and 5, not an internal-collaboration edge.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- This frame exposes **no SPI of its own** — the agent-client SDK is a pure consumer
  module. Its `module-metadata.yaml` reserves the package name
  `com.huawei.ascend.client.spi`, but that package carries no types in the generated
  facts at this status, so there is no boundary interface to cite here yet.

**Consumed SPI (the boundary this frame calls):**

- `com.huawei.ascend.bus.spi.ingress.IngressGateway`
  (`code-symbol/com-huawei-ascend-bus-spi-ingress-ingressgateway`) — the cross-plane
  ingress boundary identity, owned by `EF-INGRESS-GATEWAY` on `agent-bus`. This is the
  SDK's sole allowed entry point across the edge → compute_control boundary.

**Contract operations (OpenAPI / AsyncAPI):**

- None. The ingress entry point is an in-process SPI method, not a wire endpoint, so the
  generated `contract-surfaces.json` carries no `contract-op/*` for it. The cross-plane
  envelope/response wire shape the SDK must speak is governed by the schema contract
  `contract-yaml/ingress-envelope`
  ([`docs/contracts/ingress-envelope.v1.yaml`](../../../../docs/contracts/ingress-envelope.v1.yaml),
  Authority ADR-0089), whose status is `design_only` and which is not runtime-enforced
  until the edge SDK lands. The on-the-wire submit / cursor / stream mechanics are L2
  detail, delegated to the contract surface and this frame's L2 sink, not restated here.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame. A `design_only` frame may anchor zero FunctionPoints.

This frame anchors **zero FunctionPoints**. The DSL
(`architecture/features/engineering-frames.dsl`) declares no `efClientIngressAdapter
-> fp…` `anchors` edge for `EF-CLIENT-INGRESS-ADAPTER`; the only edge on the element is
`genModule_agent_client -> efClientIngressAdapter` with `saa.rel "contains"` (the module
→ frame ownership edge). Because the agent-client SDK is a skeleton with no production
code, there is no FunctionPoint behaviour to anchor yet. A FunctionPoint will be
`anchors`-bound to this frame when the SDK's submission / cursor-consumption behaviour
lands and is contract- and test-backed; until then this section is intentionally empty
and the card mints no anchor (Rule G-29).

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- `EdgeToComputeDirectLinkArchTest` (enforcer `E143`) — asserts at the bytecode level
  that no edge-plane class imports any
  `com.huawei.ascend.{service,engine,middleware}..` class, forcing every cross-plane
  call through the ingress SPI. Vacuous on the empty agent-client tree today; it fires
  the moment SDK code lands and attempts a forbidden direct import.
- Rule `105` — `edge_no_direct_compute_link` (enforcer `E144`) — the source-grep
  complement to `E143`: it scans edge-plane source for forbidden compute_control imports
  and for direct HTTP-client construction against non-bus hosts.
- The agent-client dependency envelope is held structurally by its
  `module-metadata.yaml` (`allowed_dependencies: []`, `forbidden_dependencies:
  [agent-service, agent-execution-engine, agent-middleware, agent-evolve]`,
  `spi_packages: [com.huawei.ascend.client.spi]`); the module-build fact layer
  (`build-module/agent-client`) is the authority for that envelope.
- Rule `G-29` (enforcer `E196`) — Frame-Card / DSL Parity: validates this card's copied
  identity fields and every fact citation above against the DSL and the generated facts.
  Rule `G-23` (Shipped-Frame Anchor Integrity) does not apply here — it requires an
  `anchors` edge only of `shipped` frames; a `design_only` frame is permitted to anchor
  zero FunctionPoints, which is exactly why this frame is `design_only`.

**Tests anchoring the behaviour** (fact-cited):

- `test/com-huawei-ascend-client-architecture-edgetocomputedirectlinkarchtest` — the
  cross-plane no-direct-link sentinel: proves the edge plane takes no direct
  compute_control dependency, so the ingress SPI is the only cross-plane path. Armed but
  vacuous while `agent-client` is a skeleton; it begins gating PRs the moment SDK code
  lands. No behavioural test exists for the submission / cursor-consumption flow yet
  because no production SDK code exists to exercise.

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`; the agent-client module is a skeleton with no SDK production
> code, so the generated facts carry no production type under the agent-client package
> root for this frame; the frame anchors zero FunctionPoints; and the `ingress-envelope.v1.yaml`
> contract the SDK will consume is `design_only` (not runtime-enforced). Until the frame
> declares a `primaryPackage`, the SDK code and a FunctionPoint with contract- and
> test-backed submission/cursor behaviour land, this frame stays `design_only` and carries
> no fact-cited Type Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Owner module L1 design: [`../agent-client/ARCHITECTURE.md`](../agent-client/ARCHITECTURE.md).
- Consumed ingress SPI (owner frame): [`EF-INGRESS-GATEWAY.md`](EF-INGRESS-GATEWAY.md).
- Cross-plane wire contract: [`../../../../docs/contracts/ingress-envelope.v1.yaml`](../../../../docs/contracts/ingress-envelope.v1.yaml) (Authority ADR-0089).
- This frame's L2 detail sink (SDK runtime mechanics, when it lands): `architecture/docs/L2/client-ingress-adapter/`.
