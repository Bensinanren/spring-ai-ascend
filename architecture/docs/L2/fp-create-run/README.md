---
level: L2
view: scenarios
status: active
feature: "fp-create-run"
relates_to:
  - "architecture/features/function-points.dsl"
  - "architecture/features/engineering-frames.dsl"
  - "architecture/docs/L1/frames/EF-ACCESS-ADMISSION.md"
  - "architecture/docs/L2/run-http-contract/README.md"
  - "docs/contracts/openapi-v1.yaml"
authority: "ADR-0068 (Layered 4+1 + Architecture Graph) + ADR-0161 (EngineeringFrame package-cluster anchor + Card over DSL) + ADR-0040 (W1 HTTP contract reconciliation) + ADR-0056 (JWT validation + tenant claim cross-check) + ADR-0057 (durable idempotency claim/replay) + ADR-0118 (atomic updateIfNotTerminal CAS) + ADR-0142 (Run aggregate single owner)"
---

<!--
  ALTITUDE: this is an L2 FunctionPoint spec. It carries the entry method, the
  participating method hops, the runtime scenario, the error matrix, and the
  contract/test evidence for ONE FunctionPoint. It is a READABLE INTERPRETATION
  layer (ADR-0161 / Rule 146): it invents no FunctionPoint ID, frame ID, operation
  ID, status code, error code, or method name — every identity is copied from the
  authoring DSL and every fact is cited from the generated facts. Authority
  cascade: generated facts > DSL > Card/prose.
-->

# L2 FunctionPoint — `FP-CREATE-RUN` (admit a new Run)

> Admit a new Run over `POST /v1/runs`: bind tenant identity, make the idempotency
> decision, pass the posture guard, and return a `TaskCursor` for the accepted
> Run. This spec is the **method-level detail home** for the create verb; the
> feature-level wire matrix (status-code table, request/response field shapes,
> filter-chain ordering, idempotency body-lifetime) lives in the
> [`../run-http-contract/`](../run-http-contract/) sink and is cited, not
> duplicated, here.

## Authority chain (read top-down)

1. **FunctionPoint identity (authoring DSL)** — element `fpCreateRun` in
   [`../../../features/function-points.dsl`](../../../features/function-points.dsl),
   `saa.id` = `FP-CREATE-RUN`. The §1 identity table copies its `saa.*`
   properties verbatim; this spec adds no property the element does not declare.
2. **Owning EngineeringFrame (structural parent)** — `EF-ACCESS-ADMISSION`
   (element `efAccessAdmission`) holds the `anchors` edge to `fpCreateRun` in
   [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl);
   its Frame Card is
   [`../../L1/frames/EF-ACCESS-ADMISSION.md`](../../L1/frames/EF-ACCESS-ADMISSION.md).
3. **Generated facts (binding factual authority)** — the `code-symbol/*`,
   `test/*`, and `contract-op/*` facts in
   [`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json),
   [`../../../facts/generated/tests.json`](../../../facts/generated/tests.json), and
   [`../../../facts/generated/contract-surfaces.json`](../../../facts/generated/contract-surfaces.json).
   Every anchor cited below resolves in these files; facts are never hand-edited.
4. **Contract surface (binding wire authority)** — operation `createRun`
   (`POST /v1/runs`) in
   [`../../../../docs/contracts/openapi-v1.yaml`](../../../../docs/contracts/openapi-v1.yaml),
   extracted as `contract-op/createrun`.
5. **L0 constraint authority** —
   [`../../L0/ARCHITECTURE.md`](../../L0/ARCHITECTURE.md) §4 owns the
   cross-document *invariant* (cross-check-not-replace tenant identity,
   DFA-initial Run status); this spec owns the verb, route, status codes, and
   method hops.

---

## 1. Behavior

This FunctionPoint realizes one behaviour: convert an external `POST /v1/runs`
request into exactly one admitted Run, or reject it at the edge. On the value
axis it is `Run Lifecycle Control` (feature `featRunLifecycleControl`) under
requirement `REQ-001`; on the structural axis it is
`agent-service -> EF-ACCESS-ADMISSION -> FP-CREATE-RUN`.

| Field | Value (copied from the DSL element) |
|---|---|
| FunctionPoint ID | `FP-CREATE-RUN` |
| Status | `shipped` (`saa.status`) |
| Owning EngineeringFrame | `EF-ACCESS-ADMISSION` (the `anchors` parent) |
| Owner module | `agent-service` (`saa.owner`) |
| Requirement | `REQ-001` (`saa.requirement`) |
| Channel | `http` (`saa.channel`) |
| Actor | `tenant-developer` (`saa.actor`) |
| Trigger | `HTTP POST /v1/runs` (`saa.trigger`) |
| Source ADR | `ADR-0040` (`saa.sourceAdr`) |

## 2. I/O

- **Input** — the `CreateRunRequest` JSON body
  (`code-symbol/com-huawei-ascend-service-platform-web-runs-createrunrequest`),
  carried with the `X-Tenant-Id` header and an `Idempotency-Key` header. The
  on-wire field shapes are the `createRun` request schema; they are cited from the
  contract (§6) and expanded in [`../run-http-contract/logical.md`](../run-http-contract/logical.md),
  not re-spelled here.
- **Output (success)** — a `RunCursorResponse`
  (`code-symbol/com-huawei-ascend-service-platform-web-runs-runcursorresponse`)
  carrying the `TaskCursor` for the admitted Run, at `202 Accepted` (the create
  verb admits asynchronously).
- **Side effects** — an idempotency claim recorded on the admission SPI
  (`code-symbol/com-huawei-ascend-service-platform-idempotency-idempotencystore`)
  **before** any state write, then a Run persisted through the Run aggregate's
  single-owner SPI
  (`code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository`,
  `save(...)`). Persistence mechanics (schema, RLS) are owned by the Run-aggregate
  L2 detail, cited not inlined.

## 3. Runtime Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Actor as tenant-developer
    participant Entry as RunController.create
    participant Idem as IdempotencyStore (admission SPI)
    participant RR as RunRepository (Run aggregate owner)

    Actor->>Entry: POST /v1/runs (X-Tenant-Id, Idempotency-Key, CreateRunRequest)
    Note over Entry: tenant cross-check + posture guard already passed at the edge<br/>(FP-TENANT-CROSS-CHECK / FP-POSTURE-BOOT-GUARD)
    Entry->>Idem: claim(tenantId, key, bodyHash)
    alt fresh claim
        Idem-->>Entry: CLAIMED
        Entry->>RR: save(new Run, status = PENDING)
        RR-->>Entry: persisted Run
        Entry-->>Actor: 202 Accepted (RunCursorResponse / TaskCursor)
    else duplicate / body drift
        Idem-->>Entry: HIT
        Entry-->>Actor: 409 (idempotency_conflict | idempotency_body_drift)
    end
```

The idempotency body-lifetime decision (claim hash, `idempotency_conflict` vs
`idempotency_body_drift`, W2 replay) is the
[`../run-http-contract/process.md`](../run-http-contract/process.md) §1–§3
sequence; this spec names the hop, that sink owns the body-lifetime detail.

## 4. Class / Method Anchors (from facts)

| Role | Symbol | Fact id (+ method descriptor) |
|---|---|---|
| Entry (`saa.code_entrypoint_refs`) | `RunController.create` | `code-symbol/com-huawei-ascend-service-platform-web-runs-runcontroller#create(Lcom/huawei/ascend/service/platform/web/runs/CreateRunRequest;Ljakarta/servlet/http/HttpServletRequest;)Lorg/springframework/http/ResponseEntity;` |
| Request type | `CreateRunRequest` | `code-symbol/com-huawei-ascend-service-platform-web-runs-createrunrequest` |
| Response type | `RunCursorResponse` | `code-symbol/com-huawei-ascend-service-platform-web-runs-runcursorresponse` |
| Idempotency SPI | `IdempotencyStore` | `code-symbol/com-huawei-ascend-service-platform-idempotency-idempotencystore` |
| Run aggregate SPI | `RunRepository.save` | `code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository#save(Lcom/huawei/ascend/service/runtime/runs/Run;)Lcom/huawei/ascend/service/runtime/runs/Run;` |

The `saa.code_entrypoint_refs` source-path form on the DSL element
(`agent-service/.../RunController.java#create`) is the human pointer; the
`code-symbol/*` fact-id form above is the gate-resolvable citation. All fact ids
in this section resolve in
[`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json).

## 5. Error Paths

| Cause (observable) | Outcome | Status / signal | `error.code` / exception |
|---|---|---|---|
| Malformed / unprocessable `CreateRunRequest` | rejected at validation | `400` / `422` | request validation failure |
| Missing / invalid bearer token | rejected at auth edge | `401` | unauthenticated |
| `JWT.tenant` claim disagrees with `X-Tenant-Id` | rejected at tenant cross-check | `403` | tenant claim mismatch |
| Reused `Idempotency-Key` (same or drifted body) | duplicate suppressed | `409` | `idempotency_conflict` / `idempotency_body_drift` |

Every status code in this table appears in the `createRun` fact's
`response_status_codes` (`202`, `400`, `401`, `403`, `409`, `422` — see §6). The
`403` cause is exercised by `RunHttpContractIT.jwtClaimHeaderMismatchReturns403`;
the `409` by `RunHttpContractIT.duplicateIdempotencyKeyReturns409`; the `401` by
`RunHttpContractIT.post_runs_without_bearer_returns_401_or_403`.

## 6. Contracts

| Operation | Fact id | Method + path | Success | Status codes |
|---|---|---|---|---|
| `createRun` | `contract-op/createrun` | `POST /v1/runs` | `202` (`TaskCursor`) | `202`, `400`, `401`, `403`, `409`, `422` |

- The contract-op fact resolves in
  [`../../../facts/generated/contract-surfaces.json`](../../../facts/generated/contract-surfaces.json).
- The binding wire authority is the OpenAPI document
  ([`../../../../docs/contracts/openapi-v1.yaml`](../../../../docs/contracts/openapi-v1.yaml),
  operation `createRun`). This table is a readable interpretation of it; the
  status-code matrix and field shapes are expanded in
  [`../run-http-contract/logical.md`](../run-http-contract/logical.md).

## 7. Tests

| Layer | Test class | Fact id | Covers |
|---|---|---|---|
| Integration / contract | `com.huawei.ascend.service.platform.web.runs.RunHttpContractIT` | `test/com-huawei-ascend-service-platform-web-runs-runhttpcontractit` | the `createReturns202WithCursor` happy path plus the `401` / `403` / `409` admission rejections over the wire. |
| Unit / domain | `com.huawei.ascend.service.runtime.runs.RunStateMachineTest` | `test/com-huawei-ascend-service-runtime-runs-runstatemachinetest` | the DFA-initial Run status invariant the admitted Run starts from (`pending_to_allowed_transitions`, `pending_cannot_jump_to_succeeded`). |

- Both fact ids resolve in
  [`../../../facts/generated/tests.json`](../../../facts/generated/tests.json).
- The authoring-DSL `verifies` edges for this FunctionPoint are in
  [`../../../features/verification.dsl`](../../../features/verification.dsl)
  (`testRunControllerCreateIT -> fpCreateRun`); this table is a readable view of
  those edges joined to the generated `test/*` facts.

## 8. Gates

| Concern | Gate rule / enforcer | What it blocks |
|---|---|---|
| FunctionPoint element well-formedness | Rule G-14 | a profile-tagged FP element missing a required `saa.*` property. |
| Frame anchors >= 1 FP (shipped) | Rule G-23 | promoting `EF-ACCESS-ADMISSION` to `shipped` without anchoring >= 1 FunctionPoint. |
| Card / spec is a readable interpretation | Rule 146 / E196 | a citation here (`code-symbol/*`, `test/*`, `contract-op/*`, method descriptor) that does not resolve in the generated facts, or an FP/frame relationship not present in the DSL. |
| No L2 detail left upstream | Rule 145 / E194-E195 | the method-chain / sequence / wire / idempotency-lifetime detail this spec carries being left in L0 / L1 prose instead. |
| FunctionPoint readiness | Rule 147 / E197 (kernel Rule G-30) | a FunctionPoint marked ready whose axis obligations (frame anchors + module implements; a Feature contains; contract-or-rationale + a resolving generated-fact ref + test-or-exception + a gate ref; a citeable normalized ADR view) are absent — `gate/lib/check_feature_readiness.py`, ADVISORY at the ADR-0159 §13.3 landing rung. |

---

## What stays upstream (NOT carried here)

- the L0 §4 *invariant* (cross-check-not-replace tenant identity, DFA-initial Run
  status) — L0 owns the invariant; this spec owns the verb, route, status codes,
  and method hops;
- naming `RunController` / the Access Layer as a **boundary identity** and the
  development-view package decomposition of the owning module (L1 / Frame Card
  material);
- the feature-level wire matrix, request/response field shapes, filter-chain
  registration order, and idempotency body-lifetime — owned by the
  [`../run-http-contract/`](../run-http-contract/) feature sink and cited above;
- citing the ArchUnit / gate enforcer that pins the boundary (named in §8, not
  re-specified).

## Authority

- ADR-0068 — Layered 4+1 + Architecture Graph as twin sources of truth
  ([`../../../../docs/adr/0068-layered-4plus1-and-architecture-graph.yaml`](../../../../docs/adr/0068-layered-4plus1-and-architecture-graph.yaml)).
- ADR-0161 — EngineeringFrame package-cluster anchor + Card over DSL
  ([`../../../../docs/adr/0161-engineering-frame-package-cluster-anchor-and-card-over-dsl.yaml`](../../../../docs/adr/0161-engineering-frame-package-cluster-anchor-and-card-over-dsl.yaml)).
- ADR-0040 — W1 HTTP contract reconciliation
  ([`../../../../docs/adr/0040-w1-http-contract-reconciliation.md`](../../../../docs/adr/0040-w1-http-contract-reconciliation.md)).
- ADR-0056 — JWT validation + tenant claim cross-check
  ([`../../../../docs/adr/0056-jwt-validation-and-tenant-claim-cross-check.md`](../../../../docs/adr/0056-jwt-validation-and-tenant-claim-cross-check.md)).
- ADR-0057 — durable idempotency claim / replay
  ([`../../../../docs/adr/0057-durable-idempotency-claim-replay.md`](../../../../docs/adr/0057-durable-idempotency-claim-replay.md)).
- L2 corpus index: [`../README.md`](../README.md).
