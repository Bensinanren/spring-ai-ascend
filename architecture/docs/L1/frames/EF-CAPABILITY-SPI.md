---
# Frame Card frontmatter. The identity block is COPIED from the frame's DSL
# element in architecture/features/engineering-frames.dsl. Every value here MUST
# match the DSL; the gate fails a card whose frontmatter disagrees with the DSL
# element's saa.id / saa.owner / saa.status / saa.primaryPackage.
level: L1
view: development
status: design_only
authority: "ADR-0161 (Frame Card shape + Card-over-DSL); ADR-0157 (EngineeringFrame Ontology)"

# --- Identity block: COPIED from the DSL frame element (do not invent) ---
frame_id: EF-CAPABILITY-SPI
dsl_element: efCapabilitySpi
owner_module: agent-middleware
primary_package: ""
source_adr: ADR-0157

# --- fact_refs: every generated fact_id this card cites. Each MUST resolve in
#     architecture/facts/generated/*.json. The gate cross-checks these. ---
fact_refs:
  - code-symbol/com-huawei-ascend-middleware-model-spi-modelgateway
  - code-symbol/com-huawei-ascend-middleware-skill-spi-skillregistry
  - code-symbol/com-huawei-ascend-middleware-memory-spi-memorystore
  - code-symbol/com-huawei-ascend-middleware-vector-spi-vectorstore
  - code-symbol/com-huawei-ascend-middleware-retrieval-spi-retriever
  - code-symbol/com-huawei-ascend-middleware-embedding-spi-embeddingmodel
  - code-symbol/com-huawei-ascend-middleware-prompt-spi-prompttemplate
  - code-symbol/com-huawei-ascend-middleware-advisor-spi-chatadvisor
  - code-symbol/com-huawei-ascend-middleware-advisor-spi-advisorchain
  - contract-yaml/model-invocation
  - contract-yaml/model-streaming
  - contract-yaml/skill-definition
  - contract-yaml/memory-store
  - contract-yaml/vector-store
  - contract-yaml/prompt-template
  - contract-yaml/chat-advisor
  - test/com-huawei-ascend-middleware-model-spi-modelstreamingchunkcarrierimmutabilitytest
  - test/com-huawei-ascend-middleware-skill-spi-skilldefinitioncarrierinvarianttest
  - test/com-huawei-ascend-middleware-memory-spi-conversationmemorycarrierimmutabilitytest
  - test/com-huawei-ascend-middleware-prompt-spi-prompttemplatecarrierimmutabilitytest
  - test/com-huawei-ascend-middleware-advisor-spi-advisorspicarrierimmutabilitytest
  - test/com-huawei-ascend-middleware-advisor-adapter-advisedmodelenvelopeadaptertest
  - test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest
  - test/com-huawei-ascend-service-runtime-architecture-llmgatewayhookchainonlytest
---

# `EF-CAPABILITY-SPI` — Capability SPI Frame

> The agent-middleware home for the cross-cutting capability SPI families — model
> gateway, skill/tool, memory, vector/retrieval/embedding, and prompt/advisor — each a
> framework-free Java SPI a compute_control orchestrator binds when it needs that
> capability.

## 1. Identity

> COPIED from the DSL frame element. These fields MUST match the DSL byte-for-byte;
> the gate fails a card that disagrees.

| Field | Value | Source |
|---|---|---|
| Frame ID (`saa.id`) | `EF-CAPABILITY-SPI` | DSL element |
| DSL element | `efCapabilitySpi` | `architecture/features/engineering-frames.dsl` |
| Owner module (`saa.owner`) | `agent-middleware` | DSL element |
| Status (`saa.status`) | `design_only` | DSL element |
| Primary package (`saa.primaryPackage`) | `—` (none declared) | DSL element |
| Source ADR (`saa.sourceAdr`) | `ADR-0157` | DSL element |
| Card path (`saa.cardPath`) | `architecture/docs/L1/frames/EF-CAPABILITY-SPI.md` | DSL element ↔ this file |

## 2. Capability Boundary

> AUTHORED prose. Package names are CITED (they must exist); the lists below are the
> human-readable boundary, not a second registry.

This frame is a **package cluster**, not a single root: it gathers the sibling capability
SPI packages under `com.huawei.ascend.middleware.*.spi`, each of which declares one
capability family's pure-Java contract. It is `design_only` — the DSL declares no
`saa.primaryPackage` and the frame anchors no FunctionPoint yet (section 6) — even though
the SPI declarations themselves already exist and are fact-cited below.

**Can do** — the responsibilities that live inside this frame:

- Declare the **model gateway** capability SPI — the boundary an orchestrator calls to
  reach a chat/completion model, with its typed invocation and response carriers
  (`com.huawei.ascend.middleware.model.spi`, key interface
  `code-symbol/com-huawei-ascend-middleware-model-spi-modelgateway`).
- Declare the **skill / tool** capability SPI — the registry and invocation contract by
  which a named skill or tool is resolved and run
  (`com.huawei.ascend.middleware.skill.spi`, key interface
  `code-symbol/com-huawei-ascend-middleware-skill-spi-skillregistry`).
- Declare the **memory** capability SPI family — conversation, semantic, and knowledge
  memory read/write contracts and their carriers
  (`com.huawei.ascend.middleware.memory.spi`, key interface
  `code-symbol/com-huawei-ascend-middleware-memory-spi-memorystore`).
- Declare the **vector / retrieval / embedding** capability SPI family — the store,
  retriever, and embedding-model contracts behind retrieval-augmented capabilities
  (`com.huawei.ascend.middleware.vector.spi`,
  `com.huawei.ascend.middleware.retrieval.spi`,
  `com.huawei.ascend.middleware.embedding.spi`; key interfaces
  `code-symbol/com-huawei-ascend-middleware-vector-spi-vectorstore`,
  `code-symbol/com-huawei-ascend-middleware-retrieval-spi-retriever`,
  `code-symbol/com-huawei-ascend-middleware-embedding-spi-embeddingmodel`).
- Declare the **prompt / advisor** capability SPI family — prompt-template rendering and
  the advisor-chain composition contract that wraps a model call
  (`com.huawei.ascend.middleware.prompt.spi`,
  `com.huawei.ascend.middleware.advisor.spi`; key interfaces
  `code-symbol/com-huawei-ascend-middleware-prompt-spi-prompttemplate`,
  `code-symbol/com-huawei-ascend-middleware-advisor-spi-chatadvisor`,
  `code-symbol/com-huawei-ascend-middleware-advisor-spi-advisorchain`).

**Cannot do** — explicitly out of scope (handled by another frame or an L2 detail):

- Dispatch runtime middleware hooks. The `HookPoint` / `RuntimeMiddleware` /
  `HookContext` / `HookOutcome` SPI and the `HookDispatcher` belong to the sibling frame
  `EF-HOOK-SURFACE` (`com.huawei.ascend.middleware.spi` +
  `com.huawei.ascend.middleware.HookDispatcher`), not to this frame.
- Provide any production capability implementation. No `ModelGateway`, `SkillRegistry`,
  `MemoryStore`, `VectorStore`, `Retriever`, `EmbeddingModel`, `PromptTemplate`, or
  `ChatAdvisor` *implementation* lives in this module; the concrete adapters (for example
  the Spring AI model-gateway adapter) live downstream in `agent-service`, behind these
  SPIs — see section 5's consumed list and section 7.
- Drive a run, own session/task state, or admit a request. Those are the agent-service
  frames (`EF-SESSION-TASK-STATE`, `EF-TASK-CONTROL`, `EF-ACCESS-ADMISSION`); a capability
  SPI is invoked *within* a run, it does not orchestrate one.
- Carry the on-the-wire model/memory/vector mechanics, the streaming chunk framing, the
  provider request/response wire shape, or any persistence/index detail. Those are L2
  runtime detail delegated to the per-family contract surfaces (section 5) and the frame's
  L2 sink, not restated in this card.

**Owned state** — the data/state this frame is the structural home for:

- The cross-cutting capability SPI *surface* itself: the family of framework-free
  interfaces and their immutable value carriers under `com.huawei.ascend.middleware.*.spi`
  (model / skill / memory / vector / retrieval / embedding / prompt / advisor). The frame
  owns no mutable runtime state — each package is a pure contract surface; the state a
  capability touches lives behind its implementation, not here.

**External dependencies** — frames / modules this frame is allowed to depend on:

- `java.*` only. As SPI packages, the capability families depend on the JDK and their own
  same-package siblings; they take no framework or cross-module dependency. The
  agent-middleware module declares an empty `allowed_dependencies` list in the module-build
  fact layer, so these SPIs sit at the dependency floor of the module graph.

**Forbidden dependencies** — dependencies the boundary must never take (held by an
ArchUnit enforcer; cited in section 7):

- Spring, Reactor, Jackson, Micrometer / OpenTelemetry, the `com.huawei.ascend.service.platform`
  layer, and in-memory reference implementations — forbidden because an SPI package must stay
  framework-free so any plane can realize it (held by `SpiPurityGeneralizedArchTest`, enforcer
  `E48`, which generalises to every `com.huawei.ascend..spi..` package, including all of this
  frame's capability families).
- The other domain modules (`agent-service`, `agent-execution-engine`, `agent-bus`,
  `agent-client`, `agent-evolve`) — declared as the module's `forbidden_dependencies` in the
  module-build fact layer; a capability SPI must not reach sideways into another plane's code.

**Included / excluded packages** (this frame is a package *cluster*, not a single root):

- Included: `com.huawei.ascend.middleware.model.spi`, `com.huawei.ascend.middleware.skill.spi`,
  `com.huawei.ascend.middleware.memory.spi`, `com.huawei.ascend.middleware.vector.spi`,
  `com.huawei.ascend.middleware.retrieval.spi`, `com.huawei.ascend.middleware.embedding.spi`,
  `com.huawei.ascend.middleware.prompt.spi`, `com.huawei.ascend.middleware.advisor.spi`
  (and the advisor adapter package `com.huawei.ascend.middleware.advisor.adapter`).
- Excluded: `com.huawei.ascend.middleware.spi` and `com.huawei.ascend.middleware.HookDispatcher`
  (the hook-dispatch SPI + dispatcher — owned by `EF-HOOK-SURFACE`, a sibling frame in the same
  module).

## 3. Type Inventory

> GENERATED — do not hand-edit between the markers. Rendered from
> `architecture/facts/generated/code-symbols.json`, filtered to the frame's in-boundary
> package(s). Every row cites its `code-symbol/<kebab-fqn>` fact ID. The Card generator owns
> this region and overwrites it on every re-render.

<!-- BEGIN GENERATED: type-inventory -->
<!--
  This frame is `design_only` and declares no `saa.primaryPackage` in its DSL element, so
  no fact-cited Type Inventory is generated here (README §"Status-conditional rules"). The
  capability SPI types that already exist across the cluster packages are cited as authored
  prose in sections 2 and 5; this block stays empty (with stable markers) until the frame is
  promoted to `shipped` with a declared `primaryPackage`. See section 7 for the missing-proof
  statement that governs promotion.
-->
<!-- END GENERATED: type-inventory -->

## 4. Internal Collaboration

> GENERATED — do not hand-edit between the markers. Rendered from `code-symbols.json`:
> the structural relationships (implements / extends / references) among the in-boundary
> types listed in section 3. This is the *structural* collaboration only — runtime call
> sequences belong in the frame's L2 sink, not here.

<!-- BEGIN GENERATED: internal-collaboration -->
<!--
  Empty for the same reason as section 3: `design_only`, no declared `primaryPackage`, so
  no generated structural relationship table. Stable markers retained for the gate's block
  anchor. The cross-family structural relationships that matter today — e.g. each capability
  interface referencing its own immutable carriers (`ModelGateway` → `ModelInvocation` /
  `ModelResponse`, `ChatAdvisor` → `AdvisedRequest` / `AdvisedResponse` via `AdvisorChain`) —
  are governed by the per-family contract surfaces in section 5, not restated here.
-->
<!-- END GENERATED: internal-collaboration -->

## 5. Contracts

> AUTHORED prose. The communication contracts this frame exposes or consumes. Cite each
> contract operation by its fact ID and each SPI by its package identity. Wire-field and
> over-the-wire mechanics are L2 — link down, do not inline.

**Exposed SPI / public surface (boundary identity):**

The frame's public surface is the set of capability SPI packages it declares. Each package
*is* a boundary identity; the key interface of each family is cited here.

- `com.huawei.ascend.middleware.model.spi` — model gateway
  (`code-symbol/com-huawei-ascend-middleware-model-spi-modelgateway`).
- `com.huawei.ascend.middleware.skill.spi` — skill / tool registry
  (`code-symbol/com-huawei-ascend-middleware-skill-spi-skillregistry`).
- `com.huawei.ascend.middleware.memory.spi` — memory store family
  (`code-symbol/com-huawei-ascend-middleware-memory-spi-memorystore`).
- `com.huawei.ascend.middleware.vector.spi` /
  `com.huawei.ascend.middleware.retrieval.spi` /
  `com.huawei.ascend.middleware.embedding.spi` — vector store, retriever, embedding model
  (`code-symbol/com-huawei-ascend-middleware-vector-spi-vectorstore`,
  `code-symbol/com-huawei-ascend-middleware-retrieval-spi-retriever`,
  `code-symbol/com-huawei-ascend-middleware-embedding-spi-embeddingmodel`).
- `com.huawei.ascend.middleware.prompt.spi` /
  `com.huawei.ascend.middleware.advisor.spi` — prompt template, advisor chain
  (`code-symbol/com-huawei-ascend-middleware-prompt-spi-prompttemplate`,
  `code-symbol/com-huawei-ascend-middleware-advisor-spi-chatadvisor`,
  `code-symbol/com-huawei-ascend-middleware-advisor-spi-advisorchain`).

**Contract surfaces (schema contracts):**

Each capability family has a YAML *schema* surface that fixes the contract shape; these are
`fact_kind: schema` documents, not OpenAPI/AsyncAPI operations, so no `contract-op/*` fact
exists for them. The on-the-wire mechanics each schema governs are L2 detail — link down,
do not inline.

| Contract | Fact ID | Contract source |
|---|---|---|
| Model invocation (synchronous gateway shape) | `contract-yaml/model-invocation` | `docs/contracts/model-invocation.v1.yaml` |
| Model streaming (chunk framing shape) | `contract-yaml/model-streaming` | `docs/contracts/model-streaming.v1.yaml` |
| Skill definition (skill/tool descriptor) | `contract-yaml/skill-definition` | `docs/contracts/skill-definition.v1.yaml` |
| Memory store (memory family shape) | `contract-yaml/memory-store` | `docs/contracts/memory-store.v1.yaml` |
| Vector store (vector/retrieval shape) | `contract-yaml/vector-store` | `docs/contracts/vector-store.v1.yaml` |
| Prompt template (render contract) | `contract-yaml/prompt-template` | `docs/contracts/prompt-template.v1.yaml` |
| Chat advisor (advisor-chain composition) | `contract-yaml/chat-advisor` | `docs/contracts/chat-advisor.v1.yaml` |

> The public HTTP `contract-op/*` operations (`createrun`, `getrun`, `cancelrun`,
> `gethealth`) belong to the agent-service edge frames, not to this frame; a capability SPI
> is an in-process Java boundary, not a wire endpoint.

**Consumed contracts** (surfaces this frame is realized by, owned downstream):

- This frame *exposes* boundary identities and *consumes* nothing from another frame. The
  realization direction is downstream: `agent-service` provides the concrete adapters that
  implement these SPIs (for example the Spring AI model-gateway adapter
  `com.huawei.ascend.service.integration.springai.SpringAiChatModelGateway`, which the fact
  layer records as implementing `com.huawei.ascend.middleware.model.spi.ModelGateway`). The
  adapter binding is a consumer concern in `agent-service`, not part of this frame's
  boundary.

## 6. FunctionPoint Mapping

> AUTHORED prose over the frame's DSL `anchors` edges. List ONLY FunctionPoints the DSL
> anchors to this frame. A `design_only` frame may anchor zero FunctionPoints — say so.

**This frame anchors zero FunctionPoints.** The DSL element `efCapabilitySpi` carries no
`anchors` edge in `architecture/features/engineering-frames.dsl` (it has only the
`genModule_agent_middleware -> efCapabilitySpi` *contains* edge). The capability SPI packages
declare the *boundaries* a future capability FunctionPoint would be specified against, but no
FunctionPoint is anchored to this frame today, and none may be claimed here without a backing
`anchors` edge (the gate fails a card that lists an unanchored FunctionPoint).

How the capability families participate in the wider model today is as a **shared contract
seam**, not an anchor: a cross-cutting concern such as the model gateway is realized as the
pair `EF-CAPABILITY-SPI` (agent-middleware, this frame, declaring the SPI) **+** a
per-module sibling frame (e.g. `EF-TRANSLATION-INTERCEPT` in agent-service) linked by the
shared contract (`model-invocation.v1.yaml`, `model-streaming.v1.yaml`); the memory family
pairs analogously with `EF-GRAPHMEMORY-AUTOCONFIG` over `memory-store.v1.yaml` /
`vector-store.v1.yaml`. That participation is carried by the *contracts* in section 5, not by
an `anchors` edge on this frame.

## 7. Verification

> AUTHORED prose. The constraints, ArchUnit enforcers, and gate rules that hold this
> boundary. Cite each enforcer / rule as a structural identifier (not version metadata).

**Constraints / enforcers holding the boundary:**

- `SpiPurityGeneralizedArchTest` (enforcer `E48`) — asserts that any
  `com.huawei.ascend..spi..` package, which includes all eight capability SPI packages in
  this cluster, depends only on the JDK and same-package siblings (no Spring, no
  `com.huawei.ascend.service.platform`, no in-memory reference impls, no Micrometer, no
  OpenTelemetry). This is the boundary's primary purity guarantee.
- Enforcer `E176` — Rule `128` (`model_gateway_authority_truth`) — asserts ADR-0121, the
  agent-middleware `ModelGateway` source, and `docs/contracts/contract-catalog.md` agree that
  the model gateway lives under `com.huawei.ascend.middleware.model.spi` and exposes its
  declared synchronous SPI surface (the package identity is gate-validated; the signature
  itself is contract material, not restated in this card).
- Enforcer `E177` — Rule `129` (`contract_spi_count_truth`) — asserts the contract-catalog
  active-SPI total agrees across catalog / module-count / release note, and that promoted
  capability SPIs (such as `Skill`) and advisor composition claims are backed by typed
  carriers and the shared advisor/model hook sequence — i.e. this frame's families cannot
  drift out of the cross-authority count.
- Enforcer `E43` — Rule G-2 sub-clause .a (`LlmGatewayHookChainOnlyTest`) — pins the model
  gateway's hook-chain-only invocation discipline in the consuming `agent-service` layer (the
  capability is reached through the hook chain, not by a direct provider import).
- Enforcer `E196` — Rule `146` / Rule G-29 (`frame_card_consistency`) — holds *this card*:
  its identity block must match the `efCapabilitySpi` DSL element (`saa.id` / `saa.owner` /
  `saa.status` / `saa.primaryPackage`), every `fact_refs` entry must resolve in
  `architecture/facts/generated/*.json`, and no FunctionPoint may be named without a backing
  `anchors` edge.

**Tests anchoring the behaviour** (fact-cited):

- `test/com-huawei-ascend-middleware-model-spi-modelstreamingchunkcarrierimmutabilitytest` —
  proves the model-family streaming chunk carriers are immutable value types.
- `test/com-huawei-ascend-middleware-skill-spi-skilldefinitioncarrierinvarianttest` —
  proves the skill-definition carrier holds its construction invariants.
- `test/com-huawei-ascend-middleware-memory-spi-conversationmemorycarrierimmutabilitytest` —
  proves the conversation-memory carriers are immutable.
- `test/com-huawei-ascend-middleware-prompt-spi-prompttemplatecarrierimmutabilitytest` —
  proves the prompt-template carriers are immutable.
- `test/com-huawei-ascend-middleware-advisor-spi-advisorspicarrierimmutabilitytest` —
  proves the advisor SPI carriers are immutable.
- `test/com-huawei-ascend-middleware-advisor-adapter-advisedmodelenvelopeadaptertest` —
  proves the advisor↔model envelope adapter mapping is structurally sound.
- `test/com-huawei-ascend-service-runtime-architecture-spipuritygeneralizedarchtest` —
  the running evidence behind `E48`: the capability SPI packages stay framework-free.
- `test/com-huawei-ascend-service-runtime-architecture-llmgatewayhookchainonlytest` —
  the running evidence behind `E43`: the gateway is reached only through the hook chain.

> **Missing proof before promotion to `shipped`:** the DSL element `efCapabilitySpi`
> declares no `saa.primaryPackage` and anchors zero FunctionPoints; the cluster ships SPI
> declarations + their immutable carriers and contract schemas only, with no production
> capability implementation in this module (the concrete adapters live downstream in
> `agent-service`). Promotion to `shipped` requires the frame to declare a `primaryPackage`
> (which forces resolving the package-cluster down to a single declared root, or splitting
> the families into per-family frames) **and** to anchor at least one capability
> FunctionPoint whose method, test, and contract anchors all resolve in the fact layer
> (Rule G-23). Until then this frame stays `design_only` and carries no fact-cited Type
> Inventory in sections 3–4.

## Cross-references

- Frames directory + Card-over-DSL rules: [`README.md`](README.md).
- This frame's DSL element (authority): [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl).
- Generated facts cited above (authority over this prose): [`../../../facts/generated/`](../../../facts/generated/).
- Collective structural map: [`../engineering-frames.md`](../engineering-frames.md).
- Sibling frame in the same module (hook dispatch, excluded here): [`EF-HOOK-SURFACE.md`](EF-HOOK-SURFACE.md).
- Cross-cutting concern → per-module-frame + shared-contract model: [`../../../../docs/logs/reviews/2026-05-29-cross-cutting-frames-and-fact-layer-design-note.en.md`](../../../../docs/logs/reviews/2026-05-29-cross-cutting-frames-and-fact-layer-design-note.en.md).
- This frame's L2 detail sink (capability runtime mechanics, when an implementation lands): `architecture/docs/L2/capability-spi/`.
