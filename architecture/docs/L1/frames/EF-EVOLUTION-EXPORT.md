---
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-EVOLUTION-EXPORT
dsl_element: efEvolutionExport
owner_module: agent-evolve
primary_package: ""
source_adr: ADR-0157

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. ---
fact_refs:
  - code-symbol/com-huawei-ascend-evolve-online-spi-slowtrackjudge
  - code-symbol/com-huawei-ascend-service-runtime-evolution-evolutionexport
  - contract-yaml/run-event
  - test/com-huawei-ascend-service-runtime-evolution-everyruneventdeclaresevolutionexporttest
---

# `EF-EVOLUTION-EXPORT` — Evolution Export Frame

> The evolution-plane responsibility slice that scopes which emitted run events leave the
> compute-control plane for the evolution plane (the `EvolutionExport` discriminator) and
> hosts the online trajectory-evaluation hook — without owning the discriminator type or the
> run-event surface that carries it.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-EVOLUTION-EXPORT` | DSL element |
| DSL element | `efEvolutionExport` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-evolve` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0157` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-EVOLUTION-EXPORT.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame:

- Scope *which* emitted run events are in-scope for the evolution plane, against the
  `EvolutionExport` discriminator (`IN_SCOPE | OUT_OF_SCOPE | OPT_IN`). The discriminator
  enum itself is owned upstream by `agent-service`
  (`code-symbol/com-huawei-ascend-service-runtime-evolution-evolutionexport`); this frame is
  the evolution-side reader of that export scope, not its owner.
- Host the online trajectory-evaluation hook — the LLM-as-Judge critique SPI under
  `com.huawei.ascend.evolve.online.spi`
  (`code-symbol/com-huawei-ascend-evolve-online-spi-slowtrackjudge`) — the one production
  type that already exists in this frame's owner module (ADR-0102).
- Act as the Java-side, read-only consumer boundary between the runtime's emitted run events
  and the (external) Python ML pipeline: in-scope events flow to the evolution plane,
  out-of-scope events stay on the compute-control plane, opt-in events are reserved for the
  future telemetry-export contract.

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Own or declare the `EvolutionExport` discriminator type. That enum lives in `agent-service`
  (`code-symbol/com-huawei-ascend-service-runtime-evolution-evolutionexport`, ADR-0145); the
  evolution plane reads the export scope it carries, it does not define it.
- Own the run-event surface that carries the discriminator. The sealed `RunEvent` family and
  its per-variant export defaults are an `agent-service` contract concern
  (`contract-yaml/run-event`); this frame consumes that surface across the contract, never via
  a Maven edge (the module forbids depending on `agent-service`).
- Ship a production telemetry-export adapter. No such adapter exists yet: the frame declares
  no `saa.primaryPackage`, and the bulk offline-export Java adapter is deferred (see section 7).
- Mutate runtime state, place direct LLM-gateway calls, or carry the over-the-wire mechanics
  of the online critique. The `ReflectionEnvelope` S2C wire shape, the Slow-Track judge call
  chain, and its latency/confidence thresholds are L2 / contract material delegated to the
  frame's L2 sink and the reflection-envelope contract, not restated here.

**Owned state** — the data/state this frame is the structural home for:

- The online trajectory-evaluation hook SPI under `com.huawei.ascend.evolve.online.spi`
  (`code-symbol/com-huawei-ascend-evolve-online-spi-slowtrackjudge`) — the boundary identity
  the evolution plane publishes today.
- The *export-scope decision* for the evolution plane — i.e. *which run events this plane
  consumes* — but **not** the discriminator enum, the run-event records, or any persisted
  run-event/trajectory data, all of which are owned upstream.

**External dependencies** — frames / modules this frame is allowed to depend on:

- None via a Maven edge: the owner module's `module-metadata.yaml` declares
  `allowed_dependencies: []`. The evolution plane is a downstream, read-only consumer; it
  reaches the run-event surface and the `ReflectionEnvelope` S2C transport across published
  contracts, not through an inbound module dependency (ADR-0075 / ADR-0102).

**Forbidden dependencies** — dependencies the boundary must never take (held structurally by
the module-metadata fact layer; cite it in section 7):

- `agent-service`, `agent-execution-engine`, `agent-middleware`, `agent-bus`, `agent-client`
  — every inbound domain-module dependency is forbidden by the owner module's
  `module-metadata.yaml#forbidden_dependencies` (Rule R-C module dependency direction). The
  evolution plane consumes emitted events across the contract surface; it must not import the
  compute-control or bus modules.

**Included / excluded packages** (this frame's owner module ships a single SPI package today):

- Included: `com.huawei.ascend.evolve.online.spi` (the online trajectory-evaluation hook —
  the home of `SlowTrackJudge`, cited under section 5). A future export-adapter package is
  contemplated but not declared in `module-metadata.yaml#spi_packages`.
- Excluded: `com.huawei.ascend.service.runtime.evolution` (the `EvolutionExport` discriminator
  enum — owned by `agent-service`, surfaced as a consumed/upstream identity in sections 2 and
  5, not part of this frame's boundary).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). The Card generator owns this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element, so
  no fact-cited Type Inventory is generated here (README §"Status-conditional rules"). The
  one production type that already exists in the frame's owner module — the online
  trajectory-evaluation hook SPI `com.huawei.ascend.evolve.online.spi.SlowTrackJudge` — is
  cited as authored prose in sections 2 and 5; this block stays empty (with stable markers)
  until the frame is promoted to `shipped` with a declared `primaryPackage`. See section 7
  for the missing-proof statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships among the in-boundary types listed in section 3.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`, so no
  generated structural relationship table. Stable markers retained for the gate's block
  anchor. The frame's owner module currently holds a single in-boundary type
  (`SlowTrackJudge`), so there is no intra-frame collaboration edge to render; the cross-plane
  relationship that matters today — the evolution plane reading the `EvolutionExport` export
  scope off the `agent-service` run-event surface — is stated as authored prose in sections 2
  and 5.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.

**Exposed SPI / public surface (boundary identity):**

- `com.huawei.ascend.evolve.online.spi.SlowTrackJudge`
  (`code-symbol/com-huawei-ascend-evolve-online-spi-slowtrackjudge`) — the online
  trajectory-evaluation hook the evolution plane publishes (LLM-as-Judge critique, ADR-0102).
  This is the only SPI surface the frame's owner module ships today.

**Consumed contracts (surfaces this frame reads, owned upstream):**

- `contract-yaml/run-event` (`docs/contracts/run-event.v1.yaml`, `status: design_only`) — the
  sealed `RunEvent` family and its per-variant export defaults; the carrier of the
  `EvolutionExport` discriminator the evolution plane scopes against. Owned by `agent-service`.
- `com.huawei.ascend.service.runtime.evolution.EvolutionExport`
  (`code-symbol/com-huawei-ascend-service-runtime-evolution-evolutionexport`) — the
  discriminator enum (`IN_SCOPE | OUT_OF_SCOPE | OPT_IN`) this frame reads, owned by
  `agent-service`. Its governance-vocabulary declaration lives in
  [`evolution-scope.v1.yaml`](../../../../docs/governance/evolution-scope.v1.yaml) (Rule
  R-M.e); that file is a governance surface, not a generated contract fact, so it is linked
  rather than fact-cited.

**Contract operations (OpenAPI / AsyncAPI):**

- None. This frame exposes no wire operation; the generated `contract-surfaces.json` carries no
  `contract-op/*` for the evolution plane. The online-evolution `ReflectionEnvelope` S2C
  envelope and its over-the-wire mechanics are L2 detail, delegated to the
  `reflection-envelope.v1.yaml` contract and the frame's L2 sink, not restated here.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame. A `design_only` frame may anchor zero FunctionPoints — say so.

**This frame anchors zero FunctionPoints.** The DSL element `efEvolutionExport` carries no
`anchors` edge in `engineering-frames.dsl` (its only relationship is
`genModule_agent_evolve -> efEvolutionExport`, `saa.rel "contains"`). No FunctionPoint is
mapped here; listing one would have no backing `anchors` edge and the gate would fail it.

The frame's scope — export-scope gating and the online trajectory-evaluation hook — is
SPI-and-discriminator shaped today, not yet decomposed into a FunctionPoint. A FunctionPoint
(e.g. an export-scope decision or an online-critique entry point) is declared against this
frame, with the corresponding `anchors` edge, only when the production realization lands and
the frame is promoted toward `shipped`.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier.

**Constraints / enforcers holding the boundary:**

- The owner module's dependency envelope is held structurally by its `module-metadata.yaml`
  (`allowed_dependencies: []`, `forbidden_dependencies: [agent-service, agent-execution-engine,
  agent-middleware, agent-bus, agent-client]`) under the module-metadata completeness gate
  rule; the module-build fact layer is the authority for that envelope (Rule R-C module
  dependency direction).
- Enforcer `E87`
  (`EveryRunEventDeclaresEvolutionExportTest#every_run_event_record_declares_evolution_export`)
  — asserts every record implementing the `RunEvent` contract declares an `evolutionExport()`
  accessor returning the `EvolutionExport` discriminator (Rule R-M.e). This enforcer lives in
  `agent-service` (the run-event owner); it guards the export-scope vocabulary this frame reads.
- The Frame-Card consistency gate holds this card's identity block against the
  `efEvolutionExport` DSL element (`saa.id` / `saa.owner` / `saa.status` /
  `saa.primaryPackage`) and cross-checks every `fact_refs` entry against
  `architecture/facts/generated/*.json` (ADR-0161).

**Tests anchoring the behaviour** (fact-cited):

- `test/com-huawei-ascend-service-runtime-evolution-everyruneventdeclaresevolutionexporttest`
  — proves every emitted `RunEvent` record carries an `EvolutionExport` accessor (the
  export-scope vocabulary is structurally present on the surface this frame consumes). The
  test lives in `agent-service`, the owner of the run-event surface; this frame's owner module
  ships no test class of its own today.

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`; the frame anchors zero FunctionPoints; the owner module ships only the
> `SlowTrackJudge` SPI and no production telemetry-export adapter (the bulk offline-export Java
> adapter is deferred per ADR-0075). The `EvolutionExport` discriminator and the run-event
> surface it reads are owned upstream by `agent-service`, not by this frame. Until the frame
> declares a `primaryPackage`, contributes a real export/critique implementation, anchors at
> least one fact- and test-backed FunctionPoint, this frame stays `design_only` and carries no
> fact-cited Type Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Owner module L1 design: [`../agent-evolve/ARCHITECTURE.md`](../agent-evolve/ARCHITECTURE.md).
- Export-scope discriminator schema (governance surface): [`../../../../docs/governance/evolution-scope.v1.yaml`](../../../../docs/governance/evolution-scope.v1.yaml).
- Run-event surface carrying the discriminator (authority): [`../../../../docs/contracts/run-event.v1.yaml`](../../../../docs/contracts/run-event.v1.yaml).
- This frame's L2 detail sink (online-critique runtime mechanics + `ReflectionEnvelope` wire shape, when authored): `architecture/docs/L2/evolution-export/`.
