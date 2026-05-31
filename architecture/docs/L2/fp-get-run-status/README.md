---
level: L2
view: scenarios
status: active
feature: "fp-get-run-status"
relates_to:
  - "architecture/features/function-points.dsl"
  - "architecture/features/engineering-frames.dsl"
  - "architecture/docs/L1/frames/EF-ACCESS-ADMISSION.md"
  - "architecture/docs/L2/run-http-contract/README.md"
  - "docs/contracts/openapi-v1.yaml"
authority: "ADR-0068 (Layered 4+1 + Architecture Graph) + ADR-0161 (EngineeringFrame package-cluster anchor + Card over DSL) + ADR-0040 (W1 HTTP contract reconciliation) + ADR-0056 (JWT validation + tenant claim cross-check) + ADR-0142 (Run aggregate single owner)"
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

# L2 FunctionPoint — `FP-GET-RUN-STATUS` (tenant-scoped Run polling)

> Read the current state of one Run over `GET /v1/runs/{runId}`: re-validate
> tenant ownership and return the Run's status plus last error. This spec is the
> **method-level detail home** for the read verb; the feature-level wire matrix
> (status-code table, response field shapes) lives in the
> [`../run-http-contract/`](../run-http-contract/) sink and is cited, not
> duplicated, here.

## Authority chain (read top-down)

1. **FunctionPoint identity (authoring DSL)** — element `fpGetRunStatus` in
   [`../../../features/function-points.dsl`](../../../features/function-points.dsl),
   `saa.id` = `FP-GET-RUN-STATUS`. The §1 identity table copies its `saa.*`
   properties verbatim.
2. **Owning EngineeringFrame (structural parent)** — `EF-ACCESS-ADMISSION`
   (element `efAccessAdmission`) holds the `anchors` edge to `fpGetRunStatus` in
   [`../../../features/engineering-frames.dsl`](../../../features/engineering-frames.dsl);
   its Frame Card is
   [`../../L1/frames/EF-ACCESS-ADMISSION.md`](../../L1/frames/EF-ACCESS-ADMISSION.md).
3. **Generated facts (binding factual authority)** — the `code-symbol/*`,
   `test/*`, and `contract-op/*` facts in
   [`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json),
   [`../../../facts/generated/tests.json`](../../../facts/generated/tests.json), and
   [`../../../facts/generated/contract-surfaces.json`](../../../facts/generated/contract-surfaces.json).
4. **Contract surface (binding wire authority)** — operation `getRun`
   (`GET /v1/runs/{runId}`) in
   [`../../../../docs/contracts/openapi-v1.yaml`](../../../../docs/contracts/openapi-v1.yaml),
   extracted as `contract-op/getrun`.
5. **L0 constraint authority** —
   [`../../L0/ARCHITECTURE.md`](../../L0/ARCHITECTURE.md) §4 owns the invariant
   that every tenant-scoped read re-validates tenancy (cross-tenant reads collapse
   to not-found); this spec owns the verb, route, and status codes.

---

## 1. Behavior

This FunctionPoint realizes one behaviour: return the current `RunStatus` (and
last error, if any) for a Run owned by the caller's tenant, or not-found when the
Run does not exist or belongs to another tenant. It is a read-only polling
endpoint — it performs no state write. On the value axis it is
`Run Lifecycle Control` (feature `featRunLifecycleControl`) under requirement
`REQ-001`; on the structural axis it is
`agent-service -> EF-ACCESS-ADMISSION -> FP-GET-RUN-STATUS`.

| Field | Value (copied from the DSL element) |
|---|---|
| FunctionPoint ID | `FP-GET-RUN-STATUS` |
| Status | `shipped` (`saa.status`) |
| Owning EngineeringFrame | `EF-ACCESS-ADMISSION` (the `anchors` parent) |
| Owner module | `agent-service` (`saa.owner`) |
| Requirement | `REQ-001` (`saa.requirement`) |
| Channel | `http` (`saa.channel`) |
| Actor | `tenant-developer` (`saa.actor`) |
| Trigger | `HTTP GET /v1/runs/(runId)` (`saa.trigger`) |
| Source ADR | `ADR-0040` (`saa.sourceAdr`) |

## 2. I/O

- **Input** — the `runId` path parameter (a `String`/UUID), carried with the
  `X-Tenant-Id` header and bearer token. There is no request body.
- **Output (success)** — a `RunResponse`
  (`code-symbol/com-huawei-ascend-service-platform-web-runs-runresponse`) carrying
  the Run's current `RunStatus` and last error, at `200 OK`.
- **Side effects** — none. This is a pure read through the Run aggregate's
  single-owner SPI
  (`code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository`,
  `findById(...)`), filtered to the caller's tenant.

## 3. Runtime Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Actor as tenant-developer
    participant Entry as RunController.get
    participant RR as RunRepository (Run aggregate owner)

    Actor->>Entry: GET /v1/runs/{runId} (X-Tenant-Id, bearer)
    Entry->>RR: findById(runId)
    alt found and owned by caller's tenant
        RR-->>Entry: Run (status, lastError)
        Entry-->>Actor: 200 OK (RunResponse)
    else absent or owned by another tenant
        RR-->>Entry: empty / different tenant
        Entry-->>Actor: 404 not_found
    end
```

## 4. Class / Method Anchors (from facts)

| Role | Symbol | Fact id (+ method descriptor) |
|---|---|---|
| Entry (`saa.code_entrypoint_refs`) | `RunController.get` | `code-symbol/com-huawei-ascend-service-platform-web-runs-runcontroller#get(Ljava/lang/String;)Lorg/springframework/http/ResponseEntity;` |
| Response type | `RunResponse` | `code-symbol/com-huawei-ascend-service-platform-web-runs-runresponse` |
| Run aggregate SPI | `RunRepository.findById` | `code-symbol/com-huawei-ascend-service-runtime-runs-spi-runrepository#findById(Ljava/util/UUID;)Ljava/util/Optional;` |

The `saa.code_entrypoint_refs` source-path form on the DSL element
(`agent-service/.../RunController.java#get`) is the human pointer; the
`code-symbol/*` fact-id form above is the gate-resolvable citation. All fact ids
in this section resolve in
[`../../../facts/generated/code-symbols.json`](../../../facts/generated/code-symbols.json).

## 5. Error Paths

| Cause (observable) | Outcome | Status / signal | `error.code` / exception |
|---|---|---|---|
| Malformed `runId` path parameter | rejected at validation | `400` | request validation failure |
| Missing / invalid bearer token | rejected at auth edge | `401` | unauthenticated |
| `runId` not found, or owned by another tenant (tenant-scope-as-not-found) | rejected | `404` | `not_found` |

Every status code in this table appears in the `getRun` fact's
`response_status_codes` (`200`, `400`, `401`, `404` — see §6). The happy path is
exercised by `RunHttpContractIT.getOwnRunReturns200`; the cross-tenant `404` by
`RunHttpContractIT.getCrossTenantRunReturns404`; the `401`/`403` unauthenticated
case by `RunHttpContractIT.get_run_without_bearer_returns_401_or_403`.

## 6. Contracts

| Operation | Fact id | Method + path | Success | Status codes |
|---|---|---|---|---|
| `getRun` | `contract-op/getrun` | `GET /v1/runs/{runId}` | `200` (`RunResponse`) | `200`, `400`, `401`, `404` |

- The contract-op fact resolves in
  [`../../../facts/generated/contract-surfaces.json`](../../../facts/generated/contract-surfaces.json).
- The binding wire authority is the OpenAPI document
  ([`../../../../docs/contracts/openapi-v1.yaml`](../../../../docs/contracts/openapi-v1.yaml),
  operation `getRun`). This table is a readable interpretation of it; the response
  field shapes are expanded in
  [`../run-http-contract/logical.md`](../run-http-contract/logical.md).

## 7. Tests

| Layer | Test class | Fact id | Covers |
|---|---|---|---|
| Integration / contract | `com.huawei.ascend.service.platform.web.runs.RunHttpContractIT` | `test/com-huawei-ascend-service-platform-web-runs-runhttpcontractit` | the read verb over the wire — `getOwnRunReturns200`, `getCrossTenantRunReturns404`, `get_run_without_bearer_returns_401_or_403`. |

- The fact id resolves in
  [`../../../facts/generated/tests.json`](../../../facts/generated/tests.json).
- The authoring-DSL `verifies` edges for this FunctionPoint are in
  [`../../../features/verification.dsl`](../../../features/verification.dsl); this
  table is a readable view of those edges joined to the generated `test/*` facts.
- The Run-state DFA invariant the returned status obeys is proven by
  `RunStateMachineTest` (cited in the sibling `FP-CREATE-RUN` / `FP-CANCEL-RUN`
  specs); this read FunctionPoint asserts no transition itself, so it does not
  re-cite that unit test as its own coverage.

## 8. Gates

| Concern | Gate rule / enforcer | What it blocks |
|---|---|---|
| FunctionPoint element well-formedness | Rule G-14 | a profile-tagged FP element missing a required `saa.*` property. |
| Frame anchors >= 1 FP (shipped) | Rule G-23 | promoting `EF-ACCESS-ADMISSION` to `shipped` without anchoring >= 1 FunctionPoint. |
| Card / spec is a readable interpretation | Rule 146 / E196 | a citation here (`code-symbol/*`, `test/*`, `contract-op/*`, method descriptor) that does not resolve in the generated facts, or an FP/frame relationship not present in the DSL. |
| No L2 detail left upstream | Rule 145 / E194-E195 | the method-hop / sequence / wire detail this spec carries being left in L0 / L1 prose instead. |
| FunctionPoint readiness | Rule 147 / E197 (kernel Rule G-30) | a FunctionPoint marked ready whose axis obligations (frame anchors + module implements; a Feature contains; contract-or-rationale + a resolving generated-fact ref + test-or-exception + a gate ref; a citeable normalized ADR view) are absent — `gate/lib/check_feature_readiness.py`, ADVISORY at the ADR-0159 §13.3 landing rung. |

---

## What stays upstream (NOT carried here)

- the L0 §4 *invariant* that every tenant-scoped read re-validates tenancy
  (cross-tenant collapses to not-found) — L0 owns the invariant; this spec owns the
  verb, route, and status codes;
- naming `RunController` / the Access Layer as a **boundary identity** and the
  development-view package decomposition of the owning module (L1 / Frame Card
  material);
- the feature-level wire matrix and response field shapes — owned by the
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
- ADR-0142 — Run aggregate single owner
  ([`../../../../docs/adr/0142-run-aggregate-single-owner.yaml`](../../../../docs/adr/0142-run-aggregate-single-owner.yaml)).
- L2 corpus index: [`../README.md`](../README.md).
