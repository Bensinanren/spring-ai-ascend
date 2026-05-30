---
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-TRANSLATION-INTERCEPT
dsl_element: efTranslationIntercept
owner_module: agent-service
primary_package: ""
source_adr: ADR-0138|ADR-0155

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. ---
fact_refs:
  - code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformchatclient
  - code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformtoolcallback
  - code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformmemoryprovider
  - code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformretriever
  - contract-yaml/governed-messages
  - contract-yaml/intercept-request
  - contract-yaml/tool-result
  - contract-yaml/session-snapshot
---

# `EF-TRANSLATION-INTERCEPT` — Translation Tool Intercept Frame

> The model/tool/memory/retrieval interception boundary of `agent-service`: it owns the
> Platform Resource Interception SPI surface that an in-process Agent calls instead of a
> vendor SDK, so platform policy is applied at the model-call boundary before any provider
> is reached.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-TRANSLATION-INTERCEPT` | DSL element |
| DSL element | `efTranslationIntercept` | `architecture/features/features.dsl` (re-tagged agent-service frame) |
| Owner module (`saa.owner`) | `agent-service` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0138|ADR-0155` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-TRANSLATION-INTERCEPT.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

**Can do** — the responsibilities that live inside this frame:

- Declare the Platform Resource Interception SPI surface that an in-process Agent invokes at
  the model-call boundary: model invocation
  (`code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformchatclient`),
  tool-call execution
  (`code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformtoolcallback`),
  read-only session-context provision
  (`code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformmemoryprovider`),
  and retrieval-reference lookup
  (`code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformretriever`).
- Own the *identity* of the model/tool translation + intercept boundary — the package home
  `com.huawei.ascend.service.runtime.intercept.spi` is the structural seam where an Agent's
  resource calls cross into platform governance, so that the same four contracts admit both
  Native in-process Agents (which inject these beans via DI) and Third-party adapters (which
  wrap their framework's Model / Toolkit / Memory abstractions around these contracts).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Construct prompts. Per ADR-0155 §3 the Agent constructs its own messages list; this frame
  governs the constructed messages at the model-call boundary, it does not build them. The
  v1-draft assumption that this layer would assemble prompts is reversed.
- Own the Run aggregate, the task-centric control flow, or engine dispatch. Run/session state
  is `EF-SESSION-TASK-STATE`, cursor/cancel/resume control is `EF-TASK-CONTROL`, and engine
  dispatch is `EF-ENGINE-DISPATCH` (design-only); this frame is invoked *by* the dispatch
  plane, it does not host it.
- Run the lifecycle hook middleware chain. The ordered `RuntimeMiddleware` hook surface is
  `EF-HOOK-SURFACE` (agent-middleware); translation/intercept *composes with* that surface
  but does not replace it (the orthogonality red-line of ADR-0140 — `ChatAdvisor` lives here,
  `RuntimeMiddleware` lives in the hook plane, and identity collapse between them is
  forbidden).
- Define the over-the-wire treatment mechanics — the policy chain, PII redaction,
  token-budget audit, fallback-trim sequence, vendor-adapter routing, and the
  request/response field shapes. Those runtime sequences and over-the-wire field
  mechanics are L2 detail, delegated to this frame's deep-dive and L2 sink + the contract
  surfaces (section 5), not restated here.
- Intercept a Remote Agent's *internal* model/tool/memory/RAG calls. Those execute in the
  remote process; this boundary only governs the A2A outbound message, which is owned by the
  remote-adapter path in `EF-ENGINE-DISPATCH`, not here (ADR-0155 §4).

**Owned state** — the data/state this frame is the structural home for:

- The four interception SPI contracts themselves (the boundary identity), under
  `com.huawei.ascend.service.runtime.intercept.spi`. The frame holds no aggregate, no
  persisted row, and no in-memory runtime structure — it is a contract surface plus its
  governed-messages output type, whose concrete realization is not yet implemented.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `EF-SESSION-TASK-STATE` (agent-service) — the read-only session snapshot the
  `PlatformMemoryProvider` contract exposes is sourced from session/task state; this frame
  reads it, it does not own it.
- The contract surfaces it realizes (`contract-yaml/governed-messages`,
  `contract-yaml/intercept-request`, `contract-yaml/tool-result`,
  `contract-yaml/session-snapshot`) — the schemas these SPIs are declared against (section 5).

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cite it in section 7):

- Concrete provider/vendor SDK packages. The whole point of the interception seam is that an
  Agent invokes these SPIs *instead of* a vendor SDK and an adapter must not new-up vendor
  SDKs; a direct provider import from inside this package would bypass the boundary.
- The hook-surface internals of `EF-HOOK-SURFACE` and the runtime-control internals of
  `EF-TASK-CONTROL` — this frame composes with those planes through their declared SPIs, it
  does not reach into their implementation packages.

**Included / excluded packages** (when the frame is a package *cluster*, not a single root):

- Included: `com.huawei.ascend.service.runtime.intercept.spi` (the four Platform Resource
  Interception SPI interfaces named under sections 3, 5, and 6).
- Excluded / not-yet-existing: `com.huawei.ascend.service.runtime.translation` — the DSL
  reserves this as a second development path (`saa.devPaths`) for the translation
  realization, but no Java type lives there yet, so it contributes nothing to the Type
  Inventory and is not a declared `primaryPackage` (section 7).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). Every row cites its `code-symbol/<kebab-fqn>` fact ID. The Card generator owns
> this region and overwrites it on every re-render. These four interfaces are the SPI
> surface that already exists in `com.huawei.ascend.service.runtime.intercept.spi`; the frame
> stays `design_only` because no implementation realizes them and no `primaryPackage` is
> declared (section 7).

<!-- BEGIN GENERATED: type-inventory -->
| Type | Kind | Fact ID |
|---|---|---|
| `com.huawei.ascend.service.runtime.intercept.spi.PlatformChatClient` | interface | `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformchatclient` |
| `com.huawei.ascend.service.runtime.intercept.spi.PlatformMemoryProvider` | interface | `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformmemoryprovider` |
| `com.huawei.ascend.service.runtime.intercept.spi.PlatformRetriever` | interface | `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformretriever` |
| `com.huawei.ascend.service.runtime.intercept.spi.PlatformToolCallback` | interface | `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformtoolcallback` |
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
| From | Relationship | To |
|---|---|---|
| _(none — no in-boundary inheritance, interface realization, or descriptor reference between two of this frame's types)_ |  |  |
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its `contract-op/<id>` fact ID and each SPI by its package identity.
> Wire-field and over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

- `com.huawei.ascend.service.runtime.intercept.spi` — the public package that *is* the
  interception boundary. Its four interfaces are the boundary identity:
  - `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformchatclient` ::
    `invoke(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/concurrent/Flow$Publisher;`
    — the model-call entry point.
  - `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformtoolcallback` ::
    `invoke(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;` — the tool-call entry
    point.
  - `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformmemoryprovider` ::
    `readSnapshot(Ljava/lang/String;J)Ljava/lang/Object;` — the read-only session-context
    entry point.
  - `code-symbol/com-huawei-ascend-service-runtime-intercept-spi-platformretriever` ::
    `retrieve(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;` — the
    retrieval-reference entry point.

**Contract operations (OpenAPI / AsyncAPI):**

- None. This frame exposes no HTTP/AsyncAPI wire operation — its entry points are in-process
  SPI methods, so the generated `contract-surfaces.json` carries no `contract-op/*` for it.
  The schema contracts these SPIs are declared against are document-level facts:

| Schema contract | Fact ID | Contract source |
|---|---|---|
| Governed messages (model-call boundary output) | `contract-yaml/governed-messages` | `docs/contracts/governed-messages.v1.yaml` |
| Intercept request (tool-call boundary input) | `contract-yaml/intercept-request` | `docs/contracts/intercept-request.v1.yaml` |
| Tool result (tool-call boundary output) | `contract-yaml/tool-result` | `docs/contracts/tool-result.v1.yaml` |
| Session snapshot (memory-read output) | `contract-yaml/session-snapshot` | `docs/contracts/session-snapshot.v1.yaml` |

**Consumed contracts** (operations this frame calls on another frame):

- None as a wire operation. The session snapshot the `PlatformMemoryProvider` contract
  returns is sourced from session/task state (`EF-SESSION-TASK-STATE`); that read is an
  in-process structural dependency (section 2), not a `contract-op/*` call this frame issues.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame (`efTranslationIntercept -> fp<Name>` in `engineering-frames.dsl`).
> A `design_only` frame may anchor zero FunctionPoints — say so.

**This frame anchors zero FunctionPoints.** In `architecture/features/engineering-frames.dsl`
the only edges incident on `efTranslationIntercept` are:

- `genModule_agent_service -> efTranslationIntercept` with `saa.rel "contains"` — the
  structural-axis membership edge (the `agent-service` module contains this frame), and
- `featEngineDispatchAndHooks -> efTranslationIntercept` with `saa.rel "traverses"` — a
  value-axis crossing (the Engine-Dispatch-and-Hooks Feature *routes across* this frame).
  Per ADR-0157, a `traverses` edge is a route across the structural map, **never** ownership,
  and it anchors no FunctionPoint to the frame.

There is no `saa.rel "anchors"` edge to `efTranslationIntercept`, so no FunctionPoint is
mapped here. The four SPI interfaces are the declared boundary surface (sections 3 and 5);
they become FunctionPoint anchors only when the DSL adds `anchors` edges and an
implementation backs them — until then the FunctionPoint table is intentionally empty.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- The Frame-Card consistency gate holds this card's identity block against the
  `efTranslationIntercept` DSL element (`saa.id` / `saa.owner` / `saa.status` /
  `saa.primaryPackage` / `saa.cardPath`) and cross-checks every `fact_refs` entry against
  `architecture/facts/generated/*.json` (ADR-0161). Because the frame is `design_only` with
  no declared `primaryPackage`, the gate does not require a fact-cited FunctionPoint anchor.
- The SPI-purity boundary for `agent-service` runtime SPI packages is held by the module's
  ArchUnit suite under `com.huawei.ascend.service.runtime.architecture` — the
  interception SPI package must stay free of concrete provider/vendor SDK imports so the
  interception seam cannot be bypassed (section 2 forbidden dependencies).

**Tests anchoring the behaviour** (fact-cited):

- None. No test in `architecture/facts/generated/tests.json` exercises the four interception
  SPIs — they are declarations with no implementation to drive. (The engine middleware hooks
  integration test `test/com-huawei-ascend-engine-runtime-runtimemiddlewareinterceptshooksit`
  belongs to the hook plane / `EF-HOOK-SURFACE`, not to this frame, despite the lexical
  overlap of "intercept".)

> **Missing proof before promotion to `shipped`:** the DSL element declares no
> `saa.primaryPackage`; the frame ships four SPI interfaces but **no implementation** that
> realizes them (no model-call governor, no tool/memory/retrieval adapter), the `translation`
> development path holds no Java type, the frame anchors **zero** FunctionPoints, and no
> fact-cited test exercises the boundary. Until an implementation lands behind these SPIs,
> the DSL adds `anchors` edges for the resulting FunctionPoints, a `primaryPackage` is
> declared, and that behaviour is contract- and test-backed, this frame stays `design_only`
> and carries only the SPI surface in its Type Inventory.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/features.dsl`](../../../features/features.dsl) (re-tagged agent-service frame `efTranslationIntercept`).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- This frame's L1 deep-dive (capability inventory + rationale): [`../agent-service/features/translation-tool-intercept.md`](../agent-service/features/translation-tool-intercept.md).
- Source ADRs (authority for intent): [`../../../../docs/adr/0138-agent-service-five-layer-l1-ratification.yaml`](../../../../docs/adr/0138-agent-service-five-layer-l1-ratification.yaml), [`../../../../docs/adr/0155-agent-service-l1-v1-2-internal-module-design.yaml`](../../../../docs/adr/0155-agent-service-l1-v1-2-internal-module-design.yaml).
- This frame's L2 detail sink (interception runtime mechanics, when it lands): `architecture/docs/L2/translation-intercept/`.
