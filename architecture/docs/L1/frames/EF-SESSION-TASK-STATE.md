---
level: L1
view: development
status: shipped
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-SESSION-TASK-STATE
dsl_element: efSessionTaskState
owner_module: agent-service
primary_package: "com.huawei.ascend.service.session"
source_adr: ADR-0138|ADR-0155

# --- fact_refs: every generated fact_id this card cites. Each resolves in
#     architecture/facts/generated/*.json. ---
fact_refs:
  - code-symbol/com-huawei-ascend-service-session-session
  - code-symbol/com-huawei-ascend-service-session-inmemorycontextprojector
  - code-symbol/com-huawei-ascend-service-session-reflectionpatchhandler
  - code-symbol/com-huawei-ascend-service-session-spi-contextprojector
  - code-symbol/com-huawei-ascend-service-session-spi-contextprojectionrequest
  - code-symbol/com-huawei-ascend-service-session-spi-projectedcontext
  - code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository
  - code-symbol/com-huawei-ascend-service-runtime-runs-runstatemachine
  - code-symbol/com-huawei-ascend-service-runtime-runs-runstatus
  - code-symbol/com-huawei-ascend-service-runtime-orchestration-inmemory-inmemoryrunregistry
  - contract-yaml/session-snapshot
  - test/com-huawei-ascend-service-runtime-runs-runstatemachinetest
  - test/com-huawei-ascend-service-runtime-architecture-runrepositoryatomiccontracttest
  - test/com-huawei-ascend-service-runtime-architecture-runrepositorysaveguardtest
  - test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest
  - test/com-huawei-ascend-service-session-inmemorycontextprojectortest
  - test/com-huawei-ascend-service-platform-architecture-runstatusenumtest
---

# `EF-SESSION-TASK-STATE` — Session Task State Frame

> Anchors the agent-service structural home for Session aggregate state and the
> context-projection SPI, and is the boundary that anchors the Run-aggregate
> lifecycle state transition (the CAS that keeps a Run's status moves legal).
> Rationale lives in `source_adr` (ADR-0138 per-layer architecture; ADR-0155 absorption).

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-SESSION-TASK-STATE` | DSL element |
| DSL element | `efSessionTaskState` | `architecture/features/features.dsl` (re-tagged agent-service frame) |
| Owner module (`saa.owner`) | `agent-service` | DSL element |
| Status (`saa.status`) | `shipped` | DSL element |
| Primary package (`saa.primaryPackage`) | `com.huawei.ascend.service.session` | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0138` \| `ADR-0155` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-SESSION-TASK-STATE.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame:

- Own the `Session` aggregate value (conversation messages, variables, tenant + session
  identity, created/updated instants) as the structural home for session context state
  (`code-symbol/com-huawei-ascend-service-session-session`).
- Own the context-projection SPI boundary — the `ContextProjector` contract and its
  request/response value types — through which the session context is read for engine
  consumption (`code-symbol/com-huawei-ascend-service-session-spi-contextprojector`).
- Provide the in-boundary reference projector that realizes that SPI
  (`code-symbol/com-huawei-ascend-service-session-inmemorycontextprojector`).
- Anchor the Run-aggregate lifecycle **state transition** boundary: the single
  compare-and-set primitive that advances a Run's status only from a non-terminal
  state, holding `FP-RUN-STATE-TRANSITION` (see §6).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Admit Runs / Tasks from the edge or carry HTTP ingress routing — that is
  `EF-ACCESS-ADMISSION` (agent-service access layer).
- Drive task-centric control (cursor flow, cancel re-authorization, resume/replay) —
  that is `EF-TASK-CONTROL`, whose package home is `com.huawei.ascend.service.runtime.runs`.
- Dispatch to or run an execution engine — that is `EF-ENGINE-DISPATCH` (design-only).
- Perform model/tool translation or intercept hooks — that is `EF-TRANSLATION-INTERCEPT`.
- Define the wire-level shape of the state transition (DFA tables, terminal-state set,
  cancel-vs-complete race classification, persistence/RLS) — that runtime detail is L2,
  not restated here; it lives in the frame's L2 sink `architecture/docs/L2/fp-run-state-transition/`
  (linked under Cross-references).

**Owned state** — the data/state this frame is the structural home for:

- The `Session` aggregate value type (`code-symbol/com-huawei-ascend-service-session-session`).
- The context-projection SPI surface and its value types in
  `com.huawei.ascend.service.session.spi`
  (`code-symbol/com-huawei-ascend-service-session-spi-contextprojector`,
  `code-symbol/com-huawei-ascend-service-session-spi-contextprojectionrequest`,
  `code-symbol/com-huawei-ascend-service-session-spi-projectedcontext`).

> The **Run aggregate itself** (`com.huawei.ascend.service.runtime.runs.Run`) is *not* a
> type of this frame's primary package — its structural home is the sibling `runs` package
> (`EF-TASK-CONTROL`). This frame *anchors* the Run-state-transition FunctionPoint
> (`anchors` edge in the DSL, §6); anchoring is a reach across the structural map, not
> ownership of the `runs` package (ADR-0157 §2).

**External dependencies** — frames / modules this frame is allowed to depend on:

- `com.huawei.ascend.service.runtime.runs.spi` — the `RunRepository` atomic-update SPI it
  invokes to realize the anchored state-transition FunctionPoint
  (`code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository`).

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cited in §7):

<!-- l2-detail-sink-allow: names forbidden framework DEPENDENCIES of an SPI boundary (a D1/D3 framework-free constraint), not a telemetry wire format; the `io.opentelemetry.*` token is a banned import package, not an on-wire attribute namespace -->
- `org.springframework.*`, `io.micrometer.*`, `io.opentelemetry.*` — the
  `com.huawei.ascend.service.session.spi` package is an SPI and must stay framework-free
  (held by `SpiPurityGeneralizedArchTest`, §7).
- In-memory reference implementations from any SPI package — an SPI must not depend on its
  own reference impl (held by `SpiPurityGeneralizedArchTest`, §7).
- `com.huawei.ascend.service.platform.*` — runtime/session code must not depend on the
  HTTP platform layer (held by the runtime-layering enforcers, §7).

**Included / excluded packages** (this frame is a package *cluster*, not a single root):

- Included: `com.huawei.ascend.service.session`, `com.huawei.ascend.service.session.spi`.
- Excluded: `com.huawei.ascend.service.runtime.runs` (belongs to `EF-TASK-CONTROL`); this
  frame only *anchors into* the `runs` SPI for `FP-RUN-STATE-TRANSITION`, it does not own
  those types.

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s) (`com.huawei.ascend.service.session` + `com.huawei.ascend.service.session.spi`).
> Every row cites its `code-symbol/<kebab-fqn>` fact ID. The Card generator owns this
> region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
| Type | Kind | Fact ID |
|---|---|---|
| `com.huawei.ascend.service.session.Session` | record | `code-symbol/com-huawei-ascend-service-session-session` |
| `com.huawei.ascend.service.session.InMemoryContextProjector` | class | `code-symbol/com-huawei-ascend-service-session-inmemorycontextprojector` |
| `com.huawei.ascend.service.session.ReflectionPatchHandler` | interface | `code-symbol/com-huawei-ascend-service-session-reflectionpatchhandler` |
| `com.huawei.ascend.service.session.spi.ContextProjector` | interface | `code-symbol/com-huawei-ascend-service-session-spi-contextprojector` |
| `com.huawei.ascend.service.session.spi.ContextProjectionRequest` | record | `code-symbol/com-huawei-ascend-service-session-spi-contextprojectionrequest` |
| `com.huawei.ascend.service.session.spi.ProjectedContext` | record | `code-symbol/com-huawei-ascend-service-session-spi-projectedcontext` |
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
| From | Relationship | To |
|---|---|---|
| `InMemoryContextProjector` | implements | `ContextProjector` |
| `InMemoryContextProjector` | references | `ContextProjectionRequest` |
| `InMemoryContextProjector` | references | `ProjectedContext` |
| `ContextProjector` | references | `ContextProjectionRequest` |
| `ContextProjector` | references | `ProjectedContext` |
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Each
> contract is CITED by its `contract-*/<id>` fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- `com.huawei.ascend.service.session.spi` — the public package that *is* the
  context-projection boundary. Key interface:
  `code-symbol/com-huawei-ascend-service-session-spi-contextprojector` (its request/response
  value types are `code-symbol/com-huawei-ascend-service-session-spi-contextprojectionrequest`
  and `code-symbol/com-huawei-ascend-service-session-spi-projectedcontext`).

**Contract operations (OpenAPI / AsyncAPI):**

| Operation | Fact ID | Contract source |
|---|---|---|
| `session-snapshot` schema (Session aggregate snapshot; `design_only`, not runtime-enforced) | `contract-yaml/session-snapshot` | `docs/contracts/session-snapshot.v1.yaml` |

> `FP-RUN-STATE-TRANSITION` is an **internal** FunctionPoint (`saa.channel = internal` in
> `function-points.dsl`): it is realized over the `RunRepository` SPI, not over an
> HTTP/AsyncAPI wire operation, so it has no `contract-op/*` of its own. The Run-lifecycle
> HTTP operations (`createrun` / `getrun` / `cancelrun`) belong to the access / task-control
> frames, not to this frame.

**Consumed contracts** (operations this frame calls on another frame):

- The `RunRepository` atomic-update SPI on `EF-TASK-CONTROL`'s `runs` package — invoked to
  perform the compare-and-set state transition
  (`code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository`).

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. Lists ONLY the FunctionPoint the DSL
> anchors to this frame (`efSessionTaskState -> fpRunStateTransition` in
> `engineering-frames.dsl`). Every anchor is CITED by generated fact ID.

This frame anchors exactly one FunctionPoint:
`efSessionTaskState -> fpRunStateTransition` (`saa.rel = anchors`).

### `FP-RUN-STATE-TRANSITION` — atomic, DFA-validated Run status transition

> The boundary advances a Run's status atomically and only from a non-terminal state,
> with the move validated against the `RunStatus` alphabet. The participating boundary
> types are named below as identities; the method-level compare-and-set signature, the
> DFA transition table, and the cancel-vs-complete ordering are L2 implementation detail
> — they live in the frame's L2 sink `architecture/docs/L2/fp-run-state-transition/`
> (linked under Cross-references), not restated here.

| Anchor (boundary identity) | Fact ID |
|---|---|
| Entry interface (atomic state-transition SPI) | `code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository` |
| Reference realization (in-memory) | `code-symbol/com-huawei-ascend-service-runtime-orchestration-inmemory-inmemoryrunregistry` |
| Transition validator | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatemachine` |
| Status alphabet | `code-symbol/com-huawei-ascend-service-runtime-runs-runstatus` |
| Contract op | — (internal FunctionPoint; realized over the `RunRepository` SPI, not a wire op) |
| Test facts | `architecture/facts/generated/tests.json` + this FunctionPoint's `saa.test_refs[]` in `function-points.dsl` |

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Each enforcer / rule is cited as a structural identifier (not version metadata).

**Constraints + their enforcing mechanism** (the boundary invariant, and the ArchUnit /
enforcer that holds it — a mechanism citation, not a behaviour catalogue):

- **Atomic single-writer state transition.** The Run status moves only through the
  `RunRepository` compare-and-set primitive (no second mutation path), tenant-scoped.
  Enforced by ArchUnit `SpiPurityGeneralizedArchTest` for the SPI-purity edge and the
  `runtime-architecture` Run-repository contract/save-guard enforcers.
- **SPI framework-freedom.** No SPI package (including
  `com.huawei.ascend.service.session.spi`) imports Spring, Micrometer/OpenTelemetry,
  the platform layer, or an in-memory reference impl. Enforced by ArchUnit
  `SpiPurityGeneralizedArchTest` (`test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest`).
- **Fixed `RunStatus` alphabet + initial state.** The DFA state set is the canonical
  `RunStatus` enum with `PENDING` as the initial status. Enforced by the
  `platform-architecture` Run-status enum enforcer.

> The per-test assertions behind these constraints (which transition each test arms, the
> cross-tenant no-op, the create-only save guard, the projection scoping cases) are the
> behaviour catalogue, not L1 boundary identity: they live in the generated test facts
> (`architecture/facts/generated/tests.json`) and this frame's FunctionPoint
> `saa.test_refs[]` in `function-points.dsl`. The frontmatter `fact_refs:` block above lists
> the fact IDs the gate resolves; the runtime behaviour each proves is L2 process detail.

<!--
  STATUS: shipped — section 3 is a non-empty fact-cited Type Inventory and
  primary_package is declared, so no status-conditional missing-proof block applies.
-->

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/features.dsl`](../../../features/features.dsl) (re-tagged agent-service frame `efSessionTaskState`); the `anchors` and `traverses` edges live in [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- The anchored FunctionPoint definition: [`../../../features/function-points.dsl`](../../../features/function-points.dsl) (`fpRunStateTransition` / `FP-RUN-STATE-TRANSITION`).
- The test→FunctionPoint verification edge: [`../../../features/verification.dsl`](../../../features/verification.dsl) (`testRunStateMachineTest -> fpRunStateTransition`).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- This frame's L2 detail sink (the anchored `FP-RUN-STATE-TRANSITION` runtime detail — the compare-and-set method contract, the DFA transition table, the terminal-state set, the cancel-vs-complete race classification, and the persistence realization): [`../../L2/fp-run-state-transition/`](../../L2/fp-run-state-transition/). This is the canonical altitude-L2 home for the runtime DFA/persistence detail this card forbids at L1; the migration target is that sink, not a sibling L1 document.
- Additional background (grandfathered L1 per-view deep-dives, not the L2 home): the Session & Task Manager feature deep-dive [`../agent-service/features/session-task-manager.md`](../agent-service/features/session-task-manager.md) and the logical-view RunStatus state-machine annotation [`../agent-service/logical.md`](../agent-service/logical.md) §3. These remain L1 documents (still under the grandfathered layer-purity locus and slated to drain into the L2 sink above); they are context pointers, not the normative L2 sink.
