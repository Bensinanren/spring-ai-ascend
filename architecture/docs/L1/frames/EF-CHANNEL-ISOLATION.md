---
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-CHANNEL-ISOLATION
dsl_element: efChannelIsolation
owner_module: agent-bus
primary_package: ""
source_adr: ADR-0157

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. This frame has no production Java home
#     yet (no saa.primaryPackage), so it cites no generated facts — the channel
#     declaration it governs is a schema surface, not a code-symbol / test /
#     contract-op fact. See section 7 for the missing-proof statement. ---
fact_refs: []
---

# `EF-CHANNEL-ISOLATION` — Channel Isolation Frame

> Anchors the durable engineering responsibility for three-track physical channel
> isolation of cross-service bus traffic — `control` / `data` / `rhythm` separation so
> that congestion on one track cannot paralyse another (Rule R-E).

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-CHANNEL-ISOLATION` | DSL element |
| DSL element | `efChannelIsolation` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-bus` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0157` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-CHANNEL-ISOLATION.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry. This frame is `design_only`: the
> boundary is declared, but no production Java home realizes it yet (section 7).

**Can do** — the responsibilities that live inside this frame:

- Own the structural decision that cross-service internal communication is sliced into
  three physically isolated tracks — `control` (out-of-band, highest priority), `data`
  (in-band, heavy-load), and `rhythm` (heartbeat / liveness) — as declared in
  `docs/governance/bus-channels.yaml`.
- Hold the invariant that no two tracks may share a `physical_channel:` identifier, so a
  single congestion event on one track cannot block traffic on another (Rule R-E).
- Carry the `data`-track inline-payload cap (16 KiB) as the boundary at which a payload
  must switch to the envelope-plus-reference form rather than ride a track inline.

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Define or own the S2C callback SPI. The server-to-client capability callback contract
  and its transport are owned by the sibling agent-bus frame `EF-S2C-TRANSPORT`; the S2C
  logical mappings merely *ride* the three tracks this frame isolates — they add no new
  physical channel.
- Realize the physical transport behind any track. The concrete broker primitives (e.g.
  partitioned topics, separate streams, a bus-level tick source) are a deferred runtime
  obligation, not part of this frame today (section 7); the present declaration is an
  in-process schema stub with no physical isolation guarantee.
- Define the run lifecycle, the bus envelope value types, or intent semantics carried on
  the `control` track (PAUSE / KILL / CANCEL / RESUME / DEADLINE_SHIFT). Those carriers
  and their state semantics are owned by the agent-bus logical-model and S2C frames, not
  by the channel-isolation boundary.
- The per-track delivery-guarantee mechanics, routing-key behaviour, queue back-pressure,
  and over-the-wire framing are L2 runtime detail — delegated to the track transport
  implementation when it lands, not restated in this card.

**Owned state** — the data/state this frame is the structural home for:

- The three-track channel *declaration* and its isolation invariant — i.e. *which tracks
  exist, their priority ordering, and the uniqueness of each `physical_channel:`
  identifier* — expressed today in `docs/governance/bus-channels.yaml` as a schema
  surface. This frame owns no runtime queue, broker resource, or message buffer yet.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `agent-bus` (its owning module) — the channel-isolation boundary is an agent-bus
  structural concern; it depends on no other domain module.
- The bus channel schema (`docs/governance/bus-channels.yaml`) — the governance surface
  that enumerates the tracks and is gate-validated for the isolation invariant.

**Forbidden dependencies** — dependencies the boundary must never take (held by an
enforcer; cite it in section 7):

- Co-locating two tracks on the same physical transport — even under distinct routing
  keys — is forbidden once a physical realization exists, because the failure-isolation
  guarantee requires distinct underlying queues (the deferred runtime obligation of
  Rule R-E; held structurally by enforcer `E64` on the `physical_channel:` uniqueness
  invariant today).
- Any dependency on the agent-service edge or persistence layers — channel isolation is a
  bus-plane concern and must not import the service edge.

**Included / excluded packages:**

- Included: none yet. This frame declares no `saa.primaryPackage`; it has no Java home
  package. Its present artifact is the `docs/governance/bus-channels.yaml` schema surface.
- Excluded: `com.huawei.ascend.bus.spi.s2c` (the S2C callback SPI — owned by
  `EF-S2C-TRANSPORT`, surfaced as a consumed/co-designed contract in section 5, not part
  of this frame's boundary).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). The Card generator owns this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element, and
  no production Java type realizes the channel-isolation boundary yet, so no fact-cited Type
  Inventory is generated here (README §"Status-conditional rules"). The channel declaration
  this frame governs lives in `docs/governance/bus-channels.yaml` as a schema surface — a
  governance/contract artifact, not a `code-symbol/*` fact. This block stays empty (with
  stable markers) until the frame is promoted to `shipped` with a declared `primaryPackage`.
  See section 7 for the missing-proof statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`, no
  in-boundary production types, so no generated structural relationship table. Stable markers
  retained for the gate's block anchor. The only structural relationship that matters today —
  that the S2C callback logical mappings ride these three tracks rather than adding a fourth —
  is stated as authored prose in sections 2 and 5.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- This frame exposes **no Java SPI of its own** — it declares no `primaryPackage`. Its
  present public surface is the three-track channel *schema declaration* in
  `docs/governance/bus-channels.yaml` (`control` / `data` / `rhythm`, each with a unique
  `physical_channel:` identifier and a declared delivery guarantee). That schema is the
  enumerable, gate-checkable contract today; the runtime transport behind it is deferred.

**Contract operations (OpenAPI / AsyncAPI):**

- None. This frame exposes no HTTP or AsyncAPI wire operation, and the generated
  `contract-surfaces.json` carries no `contract-op/*` for channel isolation. The channel
  schema is a YAML governance surface, not an operation set; the over-the-wire framing of
  any future physical track is L2 detail, delegated to the track transport implementation.

**Consumed / co-designed contracts** (surfaces this frame coordinates with, owned elsewhere):

- The S2C capability-callback logical mappings (owned by `EF-S2C-TRANSPORT`) route on these
  three tracks: the callback request rides `control`, the callback response rides `data`
  (inheriting the 16 KiB inline cap), and the callback heartbeat rides `rhythm`. The
  mappings are co-declared in `docs/governance/bus-channels.yaml`; the S2C SPI and its
  carrier types remain owned by `EF-S2C-TRANSPORT`, not by this frame.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame. A `design_only` frame may anchor zero FunctionPoints — say so.

This frame anchors **no FunctionPoint**. Its only DSL relationship is the structural
`contains` edge from its owning module (`genModule_agent_bus -> efChannelIsolation`,
`saa.rel "contains"`); `architecture/features/engineering-frames.dsl` declares no
`efChannelIsolation -> fp*` `anchors` edge. There is therefore no entry / method / contract
/ test anchor to enumerate here. A FunctionPoint will be anchored to this frame only when a
physical track transport is realized and a concrete send/receive-with-isolation scenario
exists to anchor; until then, this section is intentionally empty (the gate fails a card that
*lists* a FunctionPoint with no backing `anchors` edge, not one that anchors none).

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- Enforcer `E64` (`gate/check_architecture_sync.sh#bus_channels_three_track_present`) —
  asserts that `docs/governance/bus-channels.yaml` exists, declares exactly three tracks
  (`control` / `data` / `rhythm`), each with a **unique** `physical_channel:` identifier,
  and that the `data` track inherits the 16 KiB inline-payload cap. This is the running
  structural evidence that the isolation *declaration* holds, even before a physical
  transport exists.
- Gate Rule `R-M` sub-clause `.c` (`bus_channels_three_track_present`) — the schema check on
  the channel YAML and the uniqueness of each `physical_channel:`, the gate-side companion to
  enforcer `E64`.
- The Frame-Card consistency gate holds this card's identity block against the
  `efChannelIsolation` DSL element (`saa.id` / `saa.owner` / `saa.status` /
  `saa.primaryPackage`) and cross-checks every `fact_refs` entry against
  `architecture/facts/generated/*.json` (ADR-0161). With `fact_refs: []`, the card asserts no
  code, test, or contract fact — consistent with a frame that has no Java home yet.

**Tests anchoring the behaviour** (fact-cited):

- None. No production type or integration test backs this frame yet; the only present
  evidence is the gate-side schema check (`E64`) on the channel declaration, which is a
  gate-script enforcer, not a `test/*` fact.

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`, and no production Java type realizes the three-track isolation — the
> tracks in `docs/governance/bus-channels.yaml` carry in-process stub `physical_channel:`
> values (`in_memory_*`) that provide **no** physical isolation between `control` / `data` /
> `rhythm`. The physical-transport obligation (each track backed by a distinct broker
> primitive; co-location forbidden) is the deferred runtime clause of Rule R-E, re-introduced
> only when a deployable agent-bus reactor module ships with more than one service instance.
> Until the frame declares a `primaryPackage`, a real per-track transport exists, and its
> isolation behaviour is contract- and test-backed, this frame stays `design_only`, anchors
> no FunctionPoint, and carries no fact-cited Type Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Owner module L1 design: [`../agent-bus/ARCHITECTURE.md`](../agent-bus/ARCHITECTURE.md) (physical view: [`../agent-bus/physical.md`](../agent-bus/physical.md)).
- Channel declaration surface this frame governs: [`../../../../docs/governance/bus-channels.yaml`](../../../../docs/governance/bus-channels.yaml).
- Three-track isolation rule (authority): [`../../../../docs/governance/rules/rule-R-E.md`](../../../../docs/governance/rules/rule-R-E.md) (ADR-0069).
- Sibling frame riding these tracks: `EF-S2C-TRANSPORT` ([`EF-S2C-TRANSPORT.md`](EF-S2C-TRANSPORT.md)).
- This frame's L2 detail sink (per-track transport runtime mechanics, when it lands): `architecture/docs/L2/channel-isolation/`.
