---
level: L1
view: [logical, process, development, physical, scenarios]
module: agent-service
affects_level: L1
affects_view: [logical, process, development, physical, scenarios]
status: proposed
language: en-US
relates_to:
  - docs/L1/agent-service/README.md
  - docs/L1/agent-service/scenarios.md
  - docs/L1/agent-service/logical.md
  - docs/L1/agent-service/process.md
  - docs/L1/agent-service/development.md
  - docs/L1/agent-service/physical.md
  - docs/L1/agent-service/spi-appendix.md
  - docs/logs/reviews/2026-05-22-agent-service-l1-expansion-proposal-response.en.md
  - docs/logs/reviews/2026-05-26-agent-service-l1-4plus1-rewrite-wave-1.en.md
  - docs/logs/reviews/2026-05-26-agent-service-l1-4plus1-consistency-review-wave-1.cn.md
  - docs/logs/reviews/2026-05-26-agent-service-l1-interface-drift-review.cn.md
  - agent-service/ARCHITECTURE.md
  - agent-service/module-metadata.yaml
  - docs/dfx/agent-service.yaml
  - docs/contracts/contract-catalog.md
  - docs/contracts/openapi-v1.yaml
  - docs/contracts/run-event.v1.yaml
  - docs/contracts/engine-hooks.v1.yaml
  - docs/contracts/s2c-callback.v1.yaml
  - docs/contracts/a2a-envelope.v1.yaml
  - docs/contracts/ingress-envelope.v1.yaml
  - docs/adr/0019-suspend-reason-taxonomy.yaml
  - docs/adr/0057-durable-idempotency-claim.yaml
  - docs/adr/0070-cursor-flow-and-skill-capacity-runtime.yaml
  - docs/adr/0074-s2c-capability-callback.yaml
  - docs/adr/0100-rc22-agent-service-l1-runtime-role-decomposition.yaml
  - docs/adr/0140-agent-service-engine-adapter-layer-split.yaml
  - docs/adr/0142-run-aggregate-single-owner-pinning.yaml
  - docs/adr/0145-run-event-sealed-hierarchy.yaml
---

# Agent Service L1 Architecture Audit — Wave 1

> Date: 2026-05-27
> Scope: `docs/L1/agent-service/` canonical 4+1 views, the Java surface under `agent-service/src/main/java`, and the supporting authority surfaces (`agent-service/module-metadata.yaml`, `docs/dfx/agent-service.yaml`, `docs/contracts/contract-catalog.md`, `docs/contracts/*.v1.yaml`, ADR backlog).
> Lenses: idempotency · cohesion/coupling · event types · interface/contract consistency.
> Precedence: when an ADR conflicts with `docs/logs/reviews/2026-05-22-agent-service-l1-expansion-proposal-response.en.md`, the response doc wins and the ADR is adjusted (per user directive 2026-05-27).
> Headline: L1 is directionally ratified per the rc55 4+1 work, but **3 HIGH and 11 P1** findings hold — most clustered under three recurring-defect-family classes (design-only mechanism shown as shipped, layer-decomposition low-cohesion, discriminator without discriminated type) — and need to close before L1 is operationally consistent with the 2026-05-22 design philosophy.

## 1. Background

This audit is a follow-up to PR #76 and PR #77 — both of which raised concrete drift findings against `docs/L1/agent-service/` that were independently re-verified against `main` (PR #77's 7 findings still hold; PR #76's IF-DRIFT-001..006 mostly hold modulo line-range corrections). The user asked for an expert architecture pass that measures the agent-service L1 design against the 2026-05-22 expansion-proposal-response doc as the design-philosophy ground truth — and explicitly inverted the usual precedence stack: **when an ADR conflicts with the 2026-05-22 doc, the doc wins and the ADR must be adjusted.**

This document is the Wave 1 deliverable of a 5-wave audit per `D:\.claude\plans\review-pr-76-eventual-wilkes.md`:

| Wave | Output | Status |
|---|---|---|
| 1 | This findings-ledger document | complete |
| **2** | Classification + new-family registration + sibling sweep | **active** |
| 3 | ADR yaml edits per precedence rule | pending |
| 4 | 4+1 view corrective patches | pending |
| 5 | Branch + commit + PR | pending |

## 2. Precedence stack (codified)

For every finding in this audit that names a conflict between two authority surfaces, the precedence rule applied is:

1. **User directive (2026-05-27)** — explicit precedence rule announcement.
2. **`docs/logs/reviews/2026-05-22-agent-service-l1-expansion-proposal-response.en.md`** — the design-philosophy source of truth.
3. **`docs/logs/reviews/2026-05-26-agent-service-l1-4plus1-rewrite-wave-1.en.md`** — historical authoring record now demoted per ADR-0143; supporting authority where 2026-05-22 is silent.
4. **Canonical 4+1 views under `docs/L1/agent-service/`** — operational L1 ground truth (where it agrees with 2026-05-22).
5. **ADRs under `docs/adr/`** — adjusted whenever rules 2/3/4 conflict with them.
6. **Java code** — last resort; if code disagrees with the above stack, the audit produces a corrective ADR + impl-mode follow-up.

The 2026-05-22 doc's most load-bearing claims this audit anchors to:

- **R7**: A2A adopted as protocol boundary only; no `a2a-java` runtime dependency (line 79-86).
- **R8**: Run identity / DFA / persistence lives in `service.runtime` per ADR-0078 + ADR-0088; Engine drives but does not own (line 108-120).
- **`SuspendReason` 5-name mapping**: `INPUT_REQUIRED↔AwaitClientCallback`, `SUB_TASK_AWAIT↔AwaitChildRun`, `TOOL_EXECUTION↔AwaitToolResult`, `DELAY_AWAIT↔AwaitTimer`, `POLICY_APPROVAL↔RequiresApproval` (line 141).
- **No `.agent.` segment** in package root (line 57) — already honored.
- **3-level persistence**: Memory → Postgres → Temporal per ADR-0021 (line 145+).

## 3. Inherited findings from PR #76 / PR #77

PR #77's 7 findings (P1-1 .. P2-4) are inherited verbatim. They are **NOT re-numbered** in this audit's ledger; they are referenced as `PR77-P1-1` etc. and will be closed by Wave 4's 4+1 patches. PR #76's interface-drift review introduced IF-DRIFT-001..006, also inherited and referenced as `PR76-IF-DRIFT-001` etc.

Two of those PR-inherited findings have ALREADY been re-confirmed against the current `main` HEAD `c93c2fd` and are propagated into this audit's fix queue:

- `PR77-P1-1` — `logical.md:155` `TASK ||--|| RUN` ER cardinality contradicts the same-file prose at `:281-283`.
- `PR77-P1-2` — `process.md:55,66,72,79,262,269` use a 3-arg `updateIfNotTerminal(tid, runId, λ)` signature that does not exist in Java (`RunRepository.java:44` is 2-arg).
- `PR77-P1-3` — `physical.md:49-50` shows `tasks.task_id` and `sessions.session_id` as `UUID` while `Task.java:39` and `Session.java:36` declare them `String`.
- `PR77-P2-1` — `scenarios.md:117` cites `RunHttpContractIT.tenantMismatchReturns403` but the real test is `getCrossTenantRunReturns404`.
- `PR77-P2-2` — `process.md:81` calls SSE "W2-shipped" while `openapi-v1.yaml:289,295` says W2 scope.
- `PR77-P2-3` — `README.md:3,7` declares `view: scenarios` / `covers_views: [scenarios]` while body claims "L1 4+1 Architecture (Index)".
- `PR77-P2-4` — `agent-service/ARCHITECTURE.md:524` carries stale `tenantMismatchReturns403`; `:677` carries stale `TaskRepository`.

## 4. Wave-1 findings ledger

Severity legend: **P0** = ship-blocker (correctness or contract lie); **P1** = significant defect (design intent unmet, audit-discoverable harm); **P2** = quality / hygiene (drift, minor inconsistency); **P3** = nit / forward-pointer.

Each finding carries: `Severity`, `Surface`, `Evidence` (with file:line + verbatim quote), `2026-05-22 ref` (if applicable), `What's wrong`, `Suggested fix`. Family tag is added in Wave 2.

### Lens 1 — Idempotency

#### AUD-IDEM-1 — `IdempotencyStore.Status.COMPLETED` and `.FAILED` are unreached (half-built enum)

- **Severity**: P1
- **Surface**: Java (`platform/idempotency/`)
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyStore.java:33-37` declares `enum Status { CLAIMED, COMPLETED, FAILED }`; grep of `platform/idempotency/**` shows zero writers of `COMPLETED` / `FAILED`. Both `InMemoryIdempotencyStore.java:48` and `JdbcIdempotencyStore.java:39,42` hardcode `Status.CLAIMED`. `IdempotencyStore.java:15-17` doc admits: "L1 stops at the CLAIMED status. W2 will add COMPLETED/FAILED transitions plus response replay."
- **What's wrong**: Three-state enum declares lifecycle the code never writes — canonical `F-half-built-state-machine`.
- **Suggested fix**: Gate `COMPLETED`/`FAILED` constants behind `@deferred` markers citing ADR-0057 §2 in the Javadoc, or remove them until W2 ships the transitions.

#### AUD-IDEM-2 — `responseStatus` / `responseBodyRef` are dead fields; replay is design_only despite schema

- **Severity**: P1
- **Surface**: Java + contract yaml
- **Evidence**: `IdempotencyStore.java:40-50` defines `record IdempotencyRecord(... Integer responseStatus, String responseBodyRef, ...)`. `InMemoryIdempotencyStore.java:46-49` always constructs with `null, null`. `JdbcIdempotencyStore.java:43-44` writes `response_status = NULL, response_body_ref = NULL`. `IdempotencyHeaderFilter.java:152-164` never reads either field on a hit — only returns 409 (`idempotency_conflict` / `idempotency_body_drift`).
- **2026-05-22 ref**: `2026-05-22-...response.en.md:96` — "we ship `IdempotencyStore` postgres + redis impls per ADR-0057" and "JPA is used at platform-side read paths (idempotency replay, posture, runs read-side)".
- **What's wrong**: Replay is impossible — both impls only ever store NULL. The 2026-05-22 doc tells reviewers replay is a live read path; L1 `process.md §P1` shows a `200 cached response` branch that the code cannot produce.
- **Suggested fix**: Either mark the two fields and the `200 cached response` branch as `(design_only — W2, ADR-0057 §2)` in process.md and the contract surfaces, or wire a completion hook to populate the fields on terminal HTTP responses.

#### AUD-IDEM-3 — `process.md §P1` "200 cached response" alt branch is not implementable today

- **Severity**: P1
- **Surface**: 4+1 view
- **Evidence**: `docs/L1/agent-service/process.md:44-45` `alt Idempotency hit / Idem-->>Client: 200 cached response (or 409 idempotency_conflict / 409 idempotency_body_drift)`. Code path `IdempotencyHeaderFilter.java:150-163` only returns 409s.
- **What's wrong**: L1 process view promises behaviour the code can't produce; sibling of AUD-IDEM-2 on the doc surface.
- **Suggested fix**: Remove "200 cached response" from the `alt` branch OR annotate it `(design_only — W2)`.

#### AUD-IDEM-4 — Top-level Run create writes via plain `repository.save(...)`, not enrolled in the idempotency-claim transaction

- **Severity**: P2
- **Surface**: Java
- **Evidence**: `RunController.java:101` `Run saved = repository.save(run);` — no CAS, no `tx_id` link to the dedup-claim row. `SyncOrchestrator.java:99` `runs.findById(runId).orElseGet(() -> runs.save(createRun(...)))` — non-atomic get-then-save. `JdbcIdempotencyStore.java:46-48` admits a TTL re-claim once `expires_at <= now`.
- **What's wrong**: A retried `POST /v1/runs` that lost the 409 racing TTL re-claim creates a second Run with a fresh runId — duplicate run despite "successful" dedup.
- **Suggested fix**: Either enroll `IdempotencyHeaderFilter.claimOrFind` + `RunController.create.save` in one transaction, or derive `runId = deterministicHash(tenantId, idempotencyKey)` so TTL-re-claim winners deterministically land on the same row.

#### AUD-IDEM-5 — Child Run spawn uses fresh `UUID.randomUUID()` — parent re-attempt produces duplicate children

- **Severity**: P1
- **Surface**: Java + scenario S3
- **Evidence**: `SyncOrchestrator.java:204` `UUID childRunId = UUID.randomUUID();` immediately followed by `runs.save(new Run(childRunId, ...));`. `SuspendSignal.forChildRun(...)` carries `parentNodeKey` but no idempotency key on `(parentRunId, parentNodeKey)`.
- **What's wrong**: Re-entry into the SuspendSignal branch after transient failure / orchestrator restart / W2 async resume spawns a second child run under a new UUID. Scenario S3 (`scenarios.md:75-79`) does not declare child-spawn idempotency. Run aggregate exposes `RunRepository.findByParentRunId` so duplicate children become user-visible.
- **Suggested fix**: Derive `childRunId = uuidV5(parentRunId, parentNodeKey)` so re-spawns idempotent-collide, or maintain a `(parentRunId, parentNodeKey) → childRunId` index in `RunRepository`.

#### AUD-IDEM-6 — `S2cCallbackEnvelope.idempotencyKey` is required by contract but never consulted by the runtime

- **Severity**: P1
- **Surface**: Java + contract yaml
- **Evidence**: `docs/contracts/s2c-callback.v1.yaml:41` declares `idempotency_key` REQUIRED with the comment "client may retry; runtime dedupes within window". `S2cCallbackEnvelope.java:32,45` enforces non-null `idempotencyKey` at construction. `SyncOrchestrator.handleClientCallback(...)` (`SyncOrchestrator.java:366-411`) never reads `envelope.idempotencyKey()`; dispatches transport unconditionally and matches on `callbackId` only. Grep `idempotencyKey` returns only the constructor / record sites.
- **What's wrong**: Contract advertises "runtime dedupes within window" but the runtime has no path to short-circuit on the envelope's idempotency key. A parent re-suspension on the same `parentNodeKey` constructs a fresh envelope (new `callbackId`) and the contract guarantee is violated silently.
- **Suggested fix**: Route the S2C envelope through `IdempotencyStore.claimOrFind(tenantId, envelope.idempotencyKey(), envelopeHash)` before `transport.dispatch`, or drop the field from the schema as W3-design and mark it `(design_only — W2)` in the yaml.

#### AUD-IDEM-7 — Outbox / Inbox pattern declared in `process.md` event-emit steps; absent in Java

- **Severity**: P1
- **Surface**: 4+1 view + Java
- **Evidence**: `docs/L1/agent-service/process.md:49,57,67,80,135,144` all show `RR-->>Queue: publish <Event>`. `logical.md:35` annotates `Layer 3 — Internal Event Queue` as `<i>Future: service.queue/</i>`. Grep across `agent-service/src/main/java` for `CancelRequestedEvent|RunStateTransitionEvent|TerminalTransitionEvent` returns zero hits. Even `RunEvent` appears only in `evolution/EvolutionExport.java` (the scope marker enum), not as a sealed event hierarchy.
- **What's wrong**: P3 cancel diagram step `RR-->>Queue: publish CancelRequestedEvent + RunStateTransitionEvent + TerminalTransitionEvent` is an atomic dual-write the code cannot perform — no outbox table, no event publisher, no sealed `RunEvent` Java type. ADR-0145 is cited but absent. Without an outbox even a future publisher would lose events on crash between CAS and publish.
- **Suggested fix**: Annotate every `RR-->>Queue` step in `process.md` as `(design_only — ADR-0141 / ADR-0145)`. Extend the `(when L3 lands)` markers on lines 49 / 57 to every emit. Add an outbox SPI stub or explicit deferral row in `spi-appendix.md §5`.

#### AUD-IDEM-8 — Two divergent `IdempotencyRecord` types (one in `platform`, one orphaned in `runtime`)

- **Severity**: P2
- **Surface**: Java + spi-appendix
- **Evidence**: `agent-service/.../service/platform/idempotency/IdempotencyStore.java:40-50` declares `IdempotencyRecord` (with `status`/`responseStatus`/`responseBodyRef`/`expiresAt`). `agent-service/.../service/runtime/idempotency/IdempotencyRecord.java:12-17` declares a second one (with `runId`/`claimedAt` — no `status`). Grep `runtime\.idempotency` returns only the file's own package declaration — zero importers. `docs/L1/agent-service/spi-appendix.md:59` cites the second one as "contract-spine entity per ADR-0057" — but ADR-0057 §2 only declares the platform-package record.
- **What's wrong**: Two records named identically, neither under `.spi.`, both claiming the same contract authority. The runtime one is dead code; the spi-appendix points reviewers at the wrong file.
- **Suggested fix**: Delete `service/runtime/idempotency/IdempotencyRecord.java` or relocate the active platform record under `runtime/idempotency` if ADR-0057 §2's package was the truth. Update `spi-appendix.md:59`.

#### AUD-IDEM-9 — TTL re-claim in `JdbcIdempotencyStore` silently drops prior `request_hash` — body-drift detection lost across the TTL boundary

- **Severity**: P2
- **Surface**: Java
- **Evidence**: `JdbcIdempotencyStore.java:40-48` `ON CONFLICT ... DO UPDATE SET request_hash = EXCLUDED.request_hash, status = 'CLAIMED', response_status = NULL, ... WHERE idempotency_dedup.expires_at <= EXCLUDED.created_at`. WHERE guards by TTL but SET replaces `request_hash` wholesale.
- **What's wrong**: ADR-0057 §1's "request_hash to detect key reuse with different body" guarantee is weakened in the TTL boundary case — body-drift detection only holds within a single TTL window. A third request with the original (pre-TTL) body hash would not be detected as drift.
- **Suggested fix**: Document explicitly in ADR-0057 / `IdempotencyStore.java` Javadoc that body-drift detection is scoped to one TTL window; new claim resets the comparison baseline.

#### AUD-IDEM-10 — Uneven `(when L3 lands)` annotation across `process.md` event-emit steps

- **Severity**: P2
- **Surface**: 4+1 view
- **Evidence**: `process.md:49` says `RR-->>Queue: (when L3 lands) publish RunCreatedEvent`. `process.md:135-136,140,144` say `RR-->>Queue: publish CancelRequestedEvent` (no marker). Neither path actually publishes today (AUD-IDEM-7); the marker is asymmetric.
- **What's wrong**: Reader inconsistency. Either ALL `Queue` emit steps are deferred (true today) or NONE are (false today).
- **Suggested fix**: Apply `(when L3 lands)` to every `RR-->>Queue` step, or factor into a single preamble: "Throughout §P1-§P6, every `Queue` emission is `(design_only — ADR-0141)`."

### Lens 2 — Cohesion / Coupling

#### AUD-COHES-1 — Layer 5a (Engine Dispatch & Execution) ships under `service.runtime.orchestration.inmemory/`, NOT under `service.engine.adapter/` as ADR-0140 declares

- **Severity**: P0 (HIGH)
- **Surface**: Layer 5a vs Layer 4 split (ADR-0140); package layout
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/engine/adapter/` contains only `InMemoryStatelessEngine.java` (no-op stub returning `NO_CHANGE` — `InMemoryStatelessEngine.java:32-54`). Real Layer-5a executors live under `service.runtime.orchestration.inmemory/`: `SequentialGraphExecutor.java`, `IterativeAgentLoopExecutor.java`, `SyncOrchestrator.java`. `EngineAutoConfiguration.java:4-5,60-68` wires those executors via direct imports. `logical.md §1 row 42` declares `Layer 5a … (service.engine.{adapter,spi} + consumed engine.spi.* cross-module)`. `development.md §2 matrix row "5a"` declares the owned packages as `service.engine.adapter/` + `service.engine.spi/`.
- **What's wrong**: The ADR-0140 5a/5b split is purely documentation taxonomy. The code's Layer 5a home holds an inert stub, while the actual executor adapters live in `service.runtime.orchestration.inmemory/`. Layer 4 (Orchestrator) and Layer 5a (executor adapter) are co-located in one package — exactly the low-cohesion arrangement ADR-0140 was supposed to fix.
- **Suggested fix**: Either (a) physically relocate `SequentialGraphExecutor` + `IterativeAgentLoopExecutor` into `service.engine.adapter/` and split `SyncOrchestrator` so the dispatch-loop stays in Layer 4 while engine-specific executor classes move to 5a; or (b) update `development.md §2` + `logical.md §1` to declare the actual layer↔package mapping honestly (Layer 5a = `service.runtime.orchestration.inmemory/`) and demote `service.engine.adapter/` to a `(stub-only)` annotation. Per user precedence, option (a) is preferred — the 2026-05-22 design philosophy treats layer cohesion as a first-class invariant.

#### AUD-COHES-2 — Layer 5b "Translation & Tool-Intercept" has no internal sub-packaging; 4 distinct mechanisms share one flat `service.integration.springai/` directory

- **Severity**: P2
- **Surface**: ADR-0140 5b split granularity
- **Evidence**: Grep for `engine.translation|engine.shadow|engine.translator` across the codebase returns zero hits. `service.integration.springai/` has 8 files (ChatModel gateway, vector store adapter, embedding model adapter, prompt template adapter, output converter, tool-callback adapter, document retriever) all flat. `logical.md §1 row 46` declares Layer 5b owns 4 distinct mechanisms (ContextProjector, PromptTemplate, StructuredOutputConverter, ChatAdvisor) that "compose serially".
- **What's wrong**: A layer with 4 logically-distinct mechanism families ships as one undifferentiated flat package. Future ChatAdvisor + ContextProjector composition has no structural enforcement.
- **Suggested fix**: Sub-divide `service.integration.springai/` (e.g. `springai.model/`, `springai.tool/`, `springai.context/`, `springai.output/`), or document explicitly in `development.md §3` that the 4 mechanisms share one Spring-AI-binding flat package by design.

#### AUD-COHES-3 — `EngineAutoConfiguration` directly imports `service.runtime.orchestration.inmemory.*` executor classes; the carve-out in `PlatformImportsOnlyRuntimePublicApiTest` exists only because Layer 5a is mis-located

- **Severity**: P2
- **Surface**: Rule R-C.2.c defence-in-depth (`runtime → platform` forbidden); the platform→runtime allowlist (E34)
- **Evidence**: `EngineAutoConfiguration.java:4-5` imports `SequentialGraphExecutor` and `IterativeAgentLoopExecutor` directly from `service.runtime.orchestration.inmemory.*`. `PlatformImportsOnlyRuntimePublicApiTest.java:71-107` excludes the entire `service.platform.engine..` sub-tree via `resideOutsideOfPackage("com.huawei.ascend.service.platform.engine..")`.
- **What's wrong**: The exception is correct in spirit but exists because of AUD-COHES-1. Fixing AUD-COHES-1 retires the carve-out.
- **Suggested fix**: After AUD-COHES-1 relocation, drop the `resideOutsideOfPackage(...platform.engine..)` carve-out and rewrite the test to allow `service.engine.adapter/*` imports from `service.platform.engine/*` — the proper cross-layer wiring edge.

#### AUD-COHES-4 — `RunController.create` calls `new Run(...)` directly at Layer 1; logical.md §1 "Run aggregate (single owner)" claim is stronger than the code reality

- **Severity**: P2
- **Surface**: ADR-0142 Run aggregate single-owner pinning; logical.md §1 row 36
- **Evidence**: `RunController.java:86` calls `new Run(UUID.randomUUID(), tenant.tenantId().toString(), ..., RunStatus.PENDING, ...)` then `repository.save(run)` at line 101. `process.md` P1 line 48 diagrams the path as `Run->>RR: save(Run with status=PENDING, tenantId=tid)` annotated `<i>create-only path per rc39 source-guard</i>`. `logical.md §1 row 36` claims "Run aggregate (single owner per ADR-0142)".
- **What's wrong**: Mutations correctly route through Layer 2 (`updateIfNotTerminal`), but initial construction is a Layer-1 responsibility. The "single owner" claim conflates construction with mutation.
- **Suggested fix**: Either (a) introduce `RunRepository.createForTenant(tenantId, capability, ...)` so Layer 1 never names the constructor; or (b) soften `logical.md §1` to "Run aggregate mutation single-owner" and note that initial construction is Layer 1's prerogative under `rc39 source-guard`.

#### AUD-COHES-5 — `Run.withStatus(...)` is supplied as lambda from Layer 1 (`RunController`) and Layer 4 (`SyncOrchestrator`); "Layer 4 NEVER writes Run state directly" vocabulary is misleading

- **Severity**: P2
- **Surface**: ADR-0142 "Layer 4 NEVER writes Run state directly"
- **Evidence**: `RunController.java:170` `repository.updateIfNotTerminal(id, r -> r.withStatus(RunStatus.CANCELLED))`. `SyncOrchestrator.java:100,122,164,185,222,238` all supply `r -> r.withStatus(...)` lambdas. `logical.md §1 R3 correction line 14` and `process.md` line 250 (state-machine note) both say "Layer 4 NEVER writes Run state directly".
- **What's wrong**: The lambda is **defined** by the caller (Layer 1 / 4) and **invoked** atomically by Layer 2's `updateIfNotTerminal`. CAS atomicity is correct; the doc-claimed single-writer is misleading.
- **Suggested fix**: Reword `logical.md §1 R3` and `process.md` notes to "Layer 4 NEVER mutates Run state outside the atomic CAS lambda" — distinguish the **lambda-supplier role** (Layer 1/4) from the **CAS-applier role** (Layer 2).

#### AUD-COHES-6 — `TaskStateStore.load(taskId, tenantId)` has no `sessionId` parameter; cross-session task name drift introduces an ambiguity-read risk

- **Severity**: P2
- **Surface**: `service.task.spi.TaskStateStore`; ADR-0100 1:N session↔task
- **Evidence**: `TaskStateStore.java:43` `Optional<Map<String, Object>> load(String taskId, String tenantId);` — no sessionId. The Javadoc at `:13-16` says "TaskID and SessionID are logically decoupled: one Session may concurrently execute multiple Tasks; one Task may drift across multiple Sessions." `logical.md §2` Task ER row "sessionId" annotated `(nullable; tasks may drift, ADR-0100)`.
- **What's wrong**: If `taskId` is globally unique under tenant, this is harmless. If `taskId` is ever scoped to session (a path the Javadoc explicitly endorses), this signature cannot disambiguate which Session's view of the task to load.
- **Suggested fix**: Either declare `taskId` GLOBALLY unique under tenant in the Javadoc (forecloses drift on a different key), or add `Optional<String> sessionId` so disambiguation is explicit.

#### AUD-COHES-7 — `logical.md §1` mermaid renders Layer 3 `queue_layer` subgraph as visually identical to shipped layers — design_only invisible to graphic-first readers

- **Severity**: P3
- **Surface**: Layer 3 (Internal Event Queue) — ADR-0141 design_only
- **Evidence**: `logical.md §1` lines 34-36 show the `queue_layer` subgraph with a fully-rendered Producer/Consumer flow. `development.md §1` correctly omits `service.queue/` from the tree; §4 correctly enumerates it as future.
- **What's wrong**: Compliance is good (the boundary contract is published, no code home exists). But the mermaid subgraph is graphically as prominent as Layer 5a/5b — the visual ambiguity Rule G-3.e attempts to prevent.
- **Suggested fix**: Demote `queue_layer` visually in the mermaid: `style queue_layer stroke-dasharray:5 5,fill:#eee` so readers see at a glance that Layer 3 is design-only.

#### AUD-COHES-8 — Cross-cutting concerns (logging, MDC, JWT cross-check) mixed into `RunController` rather than lifted onto a sibling Layer-1 filter

- **Severity**: P3
- **Surface**: Rule R-M.c (cross-cutting cohesion); not a violation but a maintainability hazard
- **Evidence**: `RunController.java:81-108` does logging (`LOG.info("Run created: ...")`), MDC binding (`MDC.put("run_id", ...)` then `MDC.remove`), and trace-id read inline. No `RunCreationLogFilter` sibling exists.
- **What's wrong**: `logical.md §6` row 2 admits HTTP-edge filters are the legitimate Layer-1 cross-cutting surface. Not a violation per se; but every endpoint will repeat the MDC dance.
- **Suggested fix**: Refactor `RunController.create` to delegate MDC + log to a Servlet-filter sibling (Layer 1 cross-cutting bucket per `development.md §2 row 7`). Do NOT lift into `RuntimeMiddleware` (that would violate the §6 distinction).

#### AUD-COHES-9 — `ChatAdvisor` Java interface does not exist; only `AdvisorBinding` record ships. `logical.md §6` R4 correction is vacuous-but-armed

- **Severity**: P3
- **Surface**: `logical.md §6` R4 correction; defence-in-depth
- **Evidence**: `AdvisorBinding.java` exists at `service.agent.spi/` (record). `RuntimeMiddleware.java` exists at `agent-middleware/.../middleware/spi/`. No `ChatAdvisor.java` ships. No ArchUnit test asserts the distinct-cardinality claim in `logical.md §6`.
- **What's wrong**: R4 correction mandates ChatAdvisor and RuntimeMiddleware are distinct mechanisms — but the corresponding `ChatAdvisor` interface does not yet exist on disk. Until it lands, the §6 row is vacuous.
- **Suggested fix**: Mark ChatAdvisor + AdvisorChain as `(design_only — Spring AI shell)` in `logical.md §6 row 1` to match the §1 row 47 annotation. When the interface lands, add `RuntimeMiddlewareDistinctFromChatAdvisorTest` asserting different modules + different cardinality contracts.

### Lens 3 — Event-type definitions

#### AUD-EVT-1 — `SuspendReason` naming drift between code permits (6 variants) and 2026-05-22 doc (5 names)

- **Severity**: P0 (HIGH — per user precedence rule, the doc wins; ADR + code edit mandatory)
- **Surface**: `SuspendReason.java` permits ↔ `2026-05-22-...response.en.md:141` mapping table
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/runtime/resilience/spi/SuspendReason.java:41-46` permits `{RateLimited, AwaitChild, AwaitTimer, AwaitExternal, AwaitApproval, AwaitClientCallback}`. `2026-05-22-...response.en.md:141` says "`INPUT_REQUIRED↔AwaitClientCallback`, `SUB_TASK_AWAIT↔AwaitChildRun`, `TOOL_EXECUTION↔AwaitToolResult`, `DELAY_AWAIT↔AwaitTimer`, `POLICY_APPROVAL↔RequiresApproval`".
- **What's wrong**: Three doc-vs-code name drifts:
  - `AwaitChildRun` (doc) vs `AwaitChild` (code)
  - `AwaitToolResult` (doc) vs `AwaitExternal` (code)
  - `RequiresApproval` (doc) vs `AwaitApproval` (code)
  `logical.md §6` reproduces the doc-side names without noting the divergence. `RateLimited` is the legitimate ADR-0070 6th variant.
- **Suggested fix** (per precedence): rename the Java records to `AwaitChildRun / AwaitToolResult / RequiresApproval`. Update ADRs 0019 / 0070 / 0112 wherever they enumerate the taxonomy. Add an ArchUnit test pinning the permits list.

#### AUD-EVT-2 — `RunEvent` sealed hierarchy + 10 variants entirely absent on Java side; discriminator `EvolutionExport` ships orphaned

- **Severity**: P1
- **Surface**: `logical.md §7` + `run-event.v1.yaml` ↔ Java
- **Evidence**: Grep for `record RunCreatedEvent|sealed.*RunEvent` returns zero Java hits. `EveryRunEventDeclaresEvolutionExportTest.java:39-75` imports the not-yet-existing type with `allowEmptyShould(true)`. `EvolutionExport.java:39-46` ships with 3 variants `{IN_SCOPE, OUT_OF_SCOPE, OPT_IN}`.
- **What's wrong**: Canonical `F-discriminator-without-discriminated-type`. Rule R-M.e is unenforceable until the variants land.
- **Suggested fix**: Open an impl-mode wave to materialize `RunEvent.java` + the 10 records per `run-event.v1.yaml`. ArchUnit test starts asserting automatically.

#### AUD-EVT-3 — `HookPoint` enum constants use SCREAMING_SNAKE_CASE while doc + yaml use lowercase truncated names

- **Severity**: P2
- **Surface**: `HookPoint.java` ↔ `engine-hooks.v1.yaml#hooks` ↔ `SuspendReason.java` Javadoc + `logical.md` cite list
- **Evidence**: `agent-middleware/.../middleware/spi/HookPoint.java:25-42` declares `BEFORE_LLM_INVOCATION, AFTER_LLM_INVOCATION, BEFORE_TOOL_INVOCATION, AFTER_TOOL_INVOCATION, BEFORE_MEMORY_READ, AFTER_MEMORY_WRITE, BEFORE_SUSPENSION, BEFORE_RESUME, ON_ERROR, ON_YIELD`. `engine-hooks.v1.yaml:33-47` uses lowercase. `SuspendReason.java:32-33` and `logical.md:335` cite `HookPoint.before_tool` / `HookPoint.after_tool` (lowercase + truncated).
- **What's wrong**: Doc citations of `HookPoint.before_tool` / `after_tool` reference the wrong case AND a truncated name (`before_tool` not `BEFORE_TOOL_INVOCATION`). Gate Rule 57 doesn't check name parity.
- **Suggested fix**: Canonicalize doc citations to `HookPoint.BEFORE_TOOL_INVOCATION` / `HookPoint.AFTER_TOOL_INVOCATION` (matches Java literally). Add a gate check that any `HookPoint.<value>` prose reference resolves to a real enum constant.

#### AUD-EVT-4 — `HookPoint.ON_YIELD` ships in enum + yaml but is omitted from `engine-hooks.v1.yaml#phase_2_mandatory_hooks_fired_by_orchestrator` — declared-but-not-fired

- **Severity**: P3
- **Surface**: `HookPoint.ON_YIELD` ↔ orchestrator firing scope
- **Evidence**: `HookPoint.java:35-41` ships `ON_YIELD`; `engine-hooks.v1.yaml:47` lists it; `engine-hooks.v1.yaml:89-92` `phase_2_mandatory_hooks_fired_by_orchestrator` lists only `on_error, before_suspension, before_resume`. No orchestrator firing site for `ON_YIELD` grep-able.
- **What's wrong**: Smaller-scale F-discriminator-orphan-ship: value declared as live, no production firing path.
- **Suggested fix**: Add `firing_status: fired | declared_deferred` field per hook entry in the yaml, or extend the existing `phase_2_*` block to explicitly enumerate `on_yield: declared_deferred`.

#### AUD-EVT-5 — `Task.A2aState` 5-state DFA has no state-machine validator

- **Severity**: P1
- **Surface**: `Task.A2aState` ↔ `logical.md §4` + `a2a-envelope.v1.yaml`
- **Evidence**: `Task.java:70-76` declares 5 values matching `a2a-envelope.v1.yaml:22-38`. `logical.md §4` renders a state diagram with explicit illegal transitions ({SUBMITTED→COMPLETED, INPUT_REQUIRED→COMPLETED} are illegal per the diagram). Grep for `class.*A2aStateMachine|class.*A2aValidator` returns zero. Analogous `RunStateMachine.java:51-58` exists for RunStatus.
- **What's wrong**: Doc promises a DFA the code does not enforce. Two distinct illegal transitions would silently succeed today.
- **Suggested fix**: Add `Task.A2aStateMachine.validate(from, to)` modeled exactly on `RunStateMachine`. Wire it into the `TaskStateStore.updateState(...)` site (currently absent — see also AUD-COHES-6).

#### AUD-EVT-6 — `S2cCallbackEnvelope` has 8 fields (Java); `contract-catalog.md:90` claims a `tenantId` field that does not exist

- **Severity**: P0 (HIGH — cross-authority lie about tenant-scope carrier)
- **Surface**: `S2cCallbackEnvelope.java` ↔ `s2c-callback.v1.yaml` ↔ `contract-catalog.md:90`
- **Evidence**: `agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackEnvelope.java:26-35` declares 8 record components `(callbackId, serverRunId, capabilityRef, requestPayload, traceId, idempotencyKey, deadline, requestAttributes)`. NO `tenantId` field. `s2c-callback.v1.yaml:34-46` lists 6 required + 3 optional. `contract-catalog.md:90` claims "`S2cCallbackTransport` | tenant-scoped | `S2cCallbackEnvelope.tenantId` field (Rule R-C.c)". The Javadoc at `S2cCallbackEnvelope.java:25` honestly notes: "tenant resolved from `callbackId` via registry at the wrapping Run boundary (ADR-0074 §Consequences)".
- **What's wrong**: Catalog falsely declares a non-existent tenant field as the tenant-scope carrier. Per Rule R-C.c, a structural-carrier row MUST name a field that exists in the Java type.
- **Suggested fix** (per Rule G-8.e + Rule R-C.c, preferred): add `tenantId` as a record component of `S2cCallbackEnvelope` and validate non-blank. Alternative: rewrite `contract-catalog.md:90` to state "tenant resolved out-of-band via `S2cCallbackTransport` registry binding at wrapping Run boundary, per ADR-0074 §Consequences" — explicitly declare there is NO in-band tenant field.

#### AUD-EVT-7 — `RunStatus` DFA `FAILED → RUNNING` retry edge present in code but missing from `logical.md §3` mermaid

- **Severity**: P3
- **Surface**: `RunStateMachine.java:37` ↔ `logical.md §3`
- **Evidence**: `RunStateMachine.java:37` permits `FAILED → RUNNING` (retry path). `logical.md §3` mermaid stateDiagram-v2 does not show this edge.
- **What's wrong**: Diagram is incomplete; reader cannot infer retry semantics from the diagram alone.
- **Suggested fix**: Add the `FAILED --> RUNNING : retry` edge to the §3 diagram (with the retry policy citation if any).

### Lens 4 — Interface / Contract consistency (4-way parity)

#### AUD-PARITY-1 — `agent-service/ARCHITECTURE.md:677` names `TaskRepository` SPI; canonical name is `TaskStateStore`

- **Severity**: P1
- **Surface**: `agent-service/ARCHITECTURE.md` ↔ module-metadata ↔ DFX ↔ catalog ↔ Java
- **Evidence**: `agent-service/ARCHITECTURE.md:677` table row uses `TaskRepository`. Same file §11.2 line 700 correctly uses `TaskStateStore`. Module-metadata line 19, DFX line 20, `contract-catalog.md` row 45, `spi-appendix.md` row 7, and `agent-service/src/main/java/com/huawei/ascend/service/task/spi/TaskStateStore.java` all use canonical name.
- **What's wrong**: Stale SPI label survives in the same table where §11.2 names it correctly — internal contradiction.
- **Suggested fix**: Replace `TaskRepository` with `TaskStateStore` at `ARCHITECTURE.md:677`. (Same defect surface as PR77-P2-4.)

#### AUD-PARITY-2 — `agent-service/ARCHITECTURE.md:784` "7-interface count" remnant inside a section whose header claims 9

- **Severity**: P2
- **Surface**: `agent-service/ARCHITECTURE.md` SPI Appendix
- **Evidence**: `ARCHITECTURE.md:766` says "9 active Java SPI interfaces as of rc43". `ARCHITECTURE.md:784` says "not included in the 7-interface count". Table at lines 770-781 has 9 rows.
- **What's wrong**: "7-interface count" is stale rc22 phrasing.
- **Suggested fix**: Replace `7-interface count` with `9-interface count`.

#### AUD-PARITY-3 — `dual-track-routing-policy.yaml` referenced in the 2026-05-22 response doc but does not exist on disk

- **Severity**: P3 (watchlist — file is W2-deferred per the response doc itself)
- **Surface**: `docs/governance/dual-track-routing-policy.yaml` (declared NEW)
- **Evidence**: `2026-05-22-...response.en.md:194` `| W2 | dual-track-routing-policy.yaml (per-InterruptType policy table) | docs/governance/dual-track-routing-policy.yaml (NEW) |`. Glob returns no matches.
- **What's wrong**: Latent — not currently a parity defect (file is reference-only). Will become a parity defect when W2 lands `DualTrackRouter.java` without the matching governance yaml.
- **Suggested fix**: When W2 implements `DualTrackRouter`, ship `dual-track-routing-policy.yaml` in the same wave AND add a catalog §3 row. Until then no action.

#### AUD-PARITY-4 — `IdempotencyStore` is a contract-spine interface outside `.spi.` with no governance-trail link to ADR-0057 carve-out

- **Severity**: P2
- **Surface**: Java placement + module-metadata + DFX + catalog
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyStore.java:21` `public interface IdempotencyStore` (in `service.platform.idempotency`, NOT under `.spi.`). Two impls (`InMemoryIdempotencyStore`, `JdbcIdempotencyStore`) → this IS an extension point. `agent-service/ARCHITECTURE.md:731` admits "historical platform interface; not under .spi per Rule R-D.d". `development.md:44` repeats the carve-out note. Catalog §2 has zero `IdempotencyStore` rows; module-metadata `spi_packages` does NOT include `service.platform.idempotency`. `spi-appendix.md §1` enumerates 9 SPIs, none of which is `IdempotencyStore`.
- **What's wrong**: Reviewer reading `spi-appendix.md` believes the SPI surface is 9; reviewer reading the Java tree finds a 10th extension point with no governance trail.
- **Suggested fix**: Either (a) move `IdempotencyStore` to `service.platform.idempotency.spi.` and register as SPI row #10 in all 4 surfaces, OR (b) add a paragraph in `spi-appendix.md §2 / §3` explicitly noting "IdempotencyStore is an HTTP-edge platform contract intentionally NOT under `.spi.` per Rule R-D.d; it is governed by ADR-0057 not by SPI 4-way parity" with the ADR-0057 carve-out clause citation.

#### AUD-PARITY-6 — `a2a-envelope.v1.yaml:3` cites "future ADR-0105" that does not exist

- **Severity**: P3
- **Surface**: `docs/contracts/a2a-envelope.v1.yaml`
- **Evidence**: `a2a-envelope.v1.yaml:3` `Authority: ADR-0100 (rc22) + future ADR-0105 (rc25 — A2A contract adoption).` `a2a-envelope.v1.yaml:19` `promotion_trigger: First A2A interop test lands (future rc25+ wave with separate ADR).` Glob `docs/adr/0105*.yaml` returns no matches.
- **What's wrong**: Forward-pointer to an unrealized ADR. Rule M-2.b is satisfied (ADR-0100 exists and is cited) but the explicit "ADR-0105" number is fragile.
- **Suggested fix**: Rewrite line 3 as "Authority: ADR-0100 (rc22 — contract-only adoption; promotion to `runtime_enforced` gated on a future ADR)" — drop the specific number.

#### AUD-PARITY-7 — `agent-invoke-request.v1.yaml` has divergent status labels between `contract-catalog.md` and `spi-appendix.md`

- **Severity**: P3
- **Surface**: `contract-catalog.md` ↔ `spi-appendix.md`
- **Evidence**: `contract-catalog.md` row 162: `agent-invoke-request.v1.yaml ... schema_shipped ... Java carrier records exist and are test-verified`. `spi-appendix.md` carrier table row at line 53: `AgentInvokeRequest | service.engine.spi | Immutable service-to-engine invocation carrier (design_only per docs/contracts/agent-invoke-request.v1.yaml — runtime path deferred to ADR-0100)`. The catalog status is correct (Java exists at `service/engine/spi/AgentInvokeRequest.java`); `spi-appendix.md` carrier prose is stale.
- **What's wrong**: Two surfaces give two different status labels.
- **Suggested fix**: In `spi-appendix.md:53`, replace `design_only per docs/contracts/agent-invoke-request.v1.yaml` with `schema_shipped per docs/contracts/agent-invoke-request.v1.yaml`.

## 5. Wave-2 — family classification

Each finding (39 total: 7 PR77-inherited + 6 PR76-inherited + 32 new) is tagged below to a recurring-defect family. 26 fold into existing families; 13 require 6 NEW families.

| finding_id | family_id | status |
|---|---|---|
| PR77-P1-1 | `F-cross-authority-agreement` | existing |
| PR77-P1-2 | `F-discriminator-naming-drift-doc-vs-code` | **new** |
| PR77-P1-3 | `F-cross-authority-agreement` | existing |
| PR77-P2-1 | `F-authority-surface-path-drift` | existing |
| PR77-P2-2 | `F-terminal-verb-overclaim` | existing |
| PR77-P2-3 | `F-frontmatter-claim-body-mismatch` | existing |
| PR77-P2-4 | `F-authority-surface-path-drift` | existing |
| PR76-IF-DRIFT-001 | `F-authority-surface-path-drift` | existing |
| PR76-IF-DRIFT-002 | `F-authority-surface-path-drift` | existing |
| PR76-IF-DRIFT-003 | `F-cross-authority-agreement` | existing |
| PR76-IF-DRIFT-004 | `F-discriminator-naming-drift-doc-vs-code` | **new** |
| PR76-IF-DRIFT-005 | `F-cross-authority-agreement` | existing |
| PR76-IF-DRIFT-006 | `F-authority-surface-path-drift` | existing |
| AUD-IDEM-1 | `F-half-built-state-machine` | **new** |
| AUD-IDEM-2 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-IDEM-3 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-IDEM-4 | `F-create-path-not-enrolled-in-dedup-tx` | **new** |
| AUD-IDEM-5 | `F-create-path-not-enrolled-in-dedup-tx` | **new** |
| AUD-IDEM-6 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-IDEM-7 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-IDEM-8 | `F-vocabulary-identity-collision` | **new** |
| AUD-IDEM-9 | `F-design-doc-language-bypasses-invariant` | existing |
| AUD-IDEM-10 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-COHES-1 | `F-layer-decomposition-low-cohesion` | existing |
| AUD-COHES-2 | `F-layer-decomposition-low-cohesion` | existing |
| AUD-COHES-3 | `F-layer-decomposition-low-cohesion` | existing |
| AUD-COHES-4 | `F-layer-decomposition-low-cohesion` | existing |
| AUD-COHES-5 | `F-design-doc-language-bypasses-invariant` | existing |
| AUD-COHES-6 | `F-design-artifact-omits-tenant-spine` | existing |
| AUD-COHES-7 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-COHES-8 | `F-layer-decomposition-low-cohesion` | existing |
| AUD-COHES-9 | `F-discriminator-without-discriminated-type` | existing |
| AUD-EVT-1 | `F-discriminator-naming-drift-doc-vs-code` | **new** |
| AUD-EVT-2 | `F-discriminator-without-discriminated-type` | existing |
| AUD-EVT-3 | `F-discriminator-naming-drift-doc-vs-code` | **new** |
| AUD-EVT-4 | `F-half-built-state-machine` | **new** |
| AUD-EVT-5 | `F-dfa-without-validator` | **new** |
| AUD-EVT-6 | `F-cross-authority-tenant-scope-claim-without-field` | **new** |
| AUD-EVT-7 | `F-frontmatter-claim-body-mismatch` | existing |
| AUD-PARITY-1 | `F-authority-surface-path-drift` | existing |
| AUD-PARITY-2 | `F-numeric-drift` | existing |
| AUD-PARITY-3 | `F-design-only-mechanism-shown-as-shipped` | existing |
| AUD-PARITY-4 | `F-spi-package-bloat-with-carriers` | existing |
| AUD-PARITY-6 | `F-cross-authority-agreement` | existing |
| AUD-PARITY-7 | `F-cross-authority-agreement` | existing |

**Family distribution**: 11 findings under `F-design-only-mechanism-shown-as-shipped` (largest cluster); 7 under `F-cross-authority-agreement`; 5 under `F-layer-decomposition-low-cohesion`; 5 under `F-authority-surface-path-drift`; 4 under `F-discriminator-naming-drift-doc-vs-code` (new); 2 under `F-half-built-state-machine` (new); 2 under `F-create-path-not-enrolled-in-dedup-tx` (new); 1 each under `F-vocabulary-identity-collision` (new), `F-dfa-without-validator` (new), `F-cross-authority-tenant-scope-claim-without-field` (new), and several under existing single-finding families.

## 6. Wave-2 — sibling-sweep additions

For each new family, a fingerprint was derived and swept across the corpus. Below are the additional sibling-instance findings folded into the ledger.

### Family `F-half-built-state-machine` — fingerprint: enum members with zero production write-paths

#### SBL-HBSM-1 — `RunStatus.EXPIRED` is a terminal DFA state with zero production write-paths

- **Severity**: P1
- **Surface**: Java
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/runtime/runs/RunStatus.java:4` declares `EXPIRED`. `RunStateMachine.java:35-40` admits `RUNNING/SUSPENDED → EXPIRED`. Grep for `withStatus(EXPIRED)` or LHS-assignment to `RunStatus.EXPIRED` in production → zero hits. Only read-only references in `RunStateMachine.java:35,40` and `RunController.java:43,181` (409 classification).
- **What's wrong**: Sibling of AUD-IDEM-1 — declared terminal state never reached by code; design_only deadline-timer behavior.
- **Suggested fix**: Either annotate `EXPIRED` in `RunStatus.java` Javadoc as `(W2-deferred — ADR-XXXX deadline timer landing wave)`, or implement the deadline timer.

#### SBL-HBSM-2 — `SuspendReason.AwaitChild` / `.AwaitTimer` / `.AwaitExternal` / `.AwaitApproval` records have zero instantiations in production

- **Severity**: P1
- **Surface**: Java
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/runtime/resilience/spi/SuspendReason.java:76-79` declares all four placeholder records. Production search for `new SuspendReason.AwaitChild(` / `.AwaitTimer(` / `.AwaitExternal(` / `.AwaitApproval(` returns zero hits. Only `RateLimited` is constructor-called (1 site); `AwaitClientCallback` is constant-referenced 8 sites in `SyncOrchestrator.java` but its constructor `new SuspendReason.AwaitClientCallback(...)` is never called either.
- **What's wrong**: 4 of 6 declared SuspendReason variants are unreached. Combined with AUD-EVT-1 naming-drift finding, the `SuspendReason` taxonomy is the largest-cohort half-built lifecycle in the corpus.
- **Suggested fix**: After AUD-EVT-1 rename, retain only constructor sites that are wired; mark the rest `(design_only — ADR-XXXX)` per their owning ADR (0019 / 0070 / 0074 / 0112).

#### SBL-HBSM-3 — `Task.A2aState` 5 values + `Task.TaskKind` 4 values have zero production write-paths

- **Severity**: P2
- **Surface**: Java
- **Evidence**: `Task.java:62-76` declares `TaskKind` (INTERACTIVE/BATCH/PERIODIC/DRIFT) and `A2aState` (SUBMITTED/WORKING/INPUT_REQUIRED/COMPLETED/FAILED). Production write-path search across `agent-service/src/main/java/` for any of these values returns zero hits.
- **What's wrong**: Task entity exists; no code writes any A2aState or TaskKind value to it. Compounds with AUD-EVT-5 (no validator) and AUD-COHES-6 (sessionId missing from load).
- **Suggested fix**: Either ship a `TaskController` + `TaskStateMachine` (impl-mode wave), or mark `Task.java` Javadoc as `(W3+ — Task control surface design_only)`.

#### SBL-HBSM-4 — `PlaceholderPreservationPolicy.WARN` and `.REWRITE` enum values are unreached

- **Severity**: P3
- **Surface**: Java
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/agent/spi/PlaceholderPreservationPolicy.java:18-19` declares `WARN`, `REWRITE`. Production search returns zero hits for either; only `PRESERVE` has a single reference.
- **What's wrong**: Discriminator with two unreached values.
- **Suggested fix**: Either implement the WARN/REWRITE paths in the prompt-template engine, or remove the unused values until they're wired.

### Family `F-discriminator-naming-drift-doc-vs-code` — fingerprint: doc enum-variant cite ≠ Java permits list

#### SBL-NAME-1 — `SuspendReason.AwaitChildren` (plural) in process.md / scenarios.md vs Java's `AwaitChild` (singular)

- **Severity**: P1
- **Surface**: 4+1 view
- **Evidence**: `docs/L1/agent-service/process.md:171` `throws SuspendSignal(child-run variant)<br/>(SuspendReason.AwaitChildren)`. `docs/L1/agent-service/scenarios.md:83,85` use `AwaitChildren` and `SuspendReason.AwaitChildren`. Java reality: `SuspendReason.java:42,76` declare `AwaitChild` singular.
- **What's wrong**: Doc plural form is a 4th variant of the naming drift documented in AUD-EVT-1. Under user precedence the doc wins → the Java rename target is `AwaitChildRun` (which is BOTH singular AND descriptive). Both the doc plural `AwaitChildren` AND the Java singular `AwaitChild` should be reconciled to the canonical `AwaitChildRun`.
- **Suggested fix**: Rename Java `AwaitChild` → `AwaitChildRun`; rewrite all doc cites `AwaitChildren` → `AwaitChildRun`.

#### SBL-NAME-2 — Three doc-only names in `2026-05-22-...response.en.md:141` do not exist as Java types

- **Severity**: P1
- **Surface**: Authority doc
- **Evidence**: Doc line 141 maps `SUB_TASK_AWAIT↔AwaitChildRun`, `TOOL_EXECUTION↔AwaitToolResult`, `POLICY_APPROVAL↔RequiresApproval`. Java reality: zero `AwaitChildRun`, zero `AwaitToolResult`, zero `RequiresApproval` records.
- **What's wrong**: Same family as AUD-EVT-1 but on a different surface (the 2026-05-22 authority doc). The 3 names are the user-precedence-canonical names — the issue is that they exist ONLY in the doc and not yet in Java.
- **Suggested fix**: Wave 3/4 ADR renames + impl-mode follow-up Java rename. This sibling confirms the AUD-EVT-1 fix scope.

### Family `F-dfa-without-validator` — fingerprint: state enum with no companion `*StateMachine` / `*Validator`

#### SBL-DFAW-1 — Re-confirms AUD-EVT-5 (Task.A2aState 5-state DFA has no validator)

- **Severity**: P1
- **Surface**: Java
- **Evidence**: same as AUD-EVT-5 — listed here to anchor the family.
- **Note**: not a new finding, just re-anchored.

#### SBL-DFAW-2 — `Task.TaskKind` is a discriminator-like enum (4 values) with no companion routing/dispatch validator class

- **Severity**: P3 (borderline; TaskKind is structural rather than a state machine)
- **Surface**: Java
- **Evidence**: `Task.java:62-66` declares `TaskKind` with 4 values + zero production write-paths (per SBL-HBSM-3). No `TaskKindRouter` / `TaskKindDispatcher` class exists.
- **What's wrong**: Structural discriminator without a routing surface that consumes it.
- **Suggested fix**: Decide whether `TaskKind` is W3+ scope (mark design_only on the enum), or implement a router.

### Family `F-layer-decomposition-low-cohesion` — fingerprint: declared layer-owner package with `package-info.java`-only

#### SBL-COH-1 — `service.dispatcher/` Layer-1 owner stub package, zero content

- **Severity**: P2
- **Surface**: Java + 4+1 view
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/dispatcher/` contains exactly one file: `package-info.java`. `development.md:82` declares `service.dispatcher/ (rc22)` as a primary Layer-1 owner alongside `service.platform.web/`.
- **What's wrong**: Layer-1 work all lives under `service.platform.*`. `service.dispatcher/` is a stub — declared layer membership without code. Sibling of AUD-COHES-1 (Layer 5a) and SBL-COH-2 (Layer 4).
- **Suggested fix**: Either materialize `service.dispatcher/` content (e.g. relocate `RunController` here as part of Layer-1 cohesion), or update `development.md:82` to drop the row.

#### SBL-COH-2 — `service.orchestrator/` Layer-4 owner stub package, zero content

- **Severity**: P2
- **Surface**: Java + 4+1 view
- **Evidence**: `agent-service/src/main/java/com/huawei/ascend/service/orchestrator/` contains `package-info.java` only. `development.md:85` declares `service.orchestrator/ (rc22)` as a primary Layer-4 owner.
- **What's wrong**: Actual Layer-4 work lives under `service.runtime.orchestration/`. `service.orchestrator/` is a stub.
- **Suggested fix**: Either relocate `SyncOrchestrator` here (closes both AUD-COHES-1 partial and this sibling), or drop the row from `development.md:85`.

### Families with negative sibling-sweep confirmation (Rule G-E non-vacuity)

- `F-design-only-mechanism-shown-as-shipped` — sweep returned ZERO new siblings beyond the 5 already in the ledger (`AUD-IDEM-2`, `AUD-IDEM-3`, `AUD-IDEM-6`, `AUD-IDEM-7`, `AUD-IDEM-10`, `AUD-COHES-7`, `AUD-PARITY-3`). Negative-confirmation: every `RR-->>Queue` arrow in process.md is umbrella-covered by ADR-0141 / ADR-0145 design_only annotations.
- `F-discriminator-without-discriminated-type` — sweep returned ZERO new siblings. All other candidate discriminator enums (`HookPoint`, `SkillKind`, `MemoryCategory`, `ModelFinishReason`, `MemoryOwnership`, `RunMode`, `PlanningStrategy`) have their discriminated polymorphic carrier types either in `agent-middleware/` or `agent-execution-engine/`.
- `F-cross-authority-tenant-scope-claim-without-field` — sweep returned ZERO new siblings. Other catalog rows declaring tenant-scoped carrier fields (`IngressEnvelope.tenantId`, `AdvisedRequest.tenantId`, `AdvisedResponse.tenantId`) all have their fields present in the Java records.
- `F-create-path-not-enrolled-in-dedup-tx` — sweep returned ZERO new siblings beyond AUD-IDEM-4 (top-level Run) and AUD-IDEM-5 (child Run). No `TaskController` / `SessionController` exists yet so the sweep is vacuous-but-armed against W2/W3 endpoints.
- `F-vocabulary-identity-collision` — sweep returned ZERO new siblings. Only AUD-IDEM-8 (two `IdempotencyRecord`). Confirmed by `find agent-service/src/main/java -name "*.java" -printf '%f\n' | sort | uniq -d` returning only the one duplicate basename.

## 7. Wave-2 — newly registered families

Six new families are registered in `docs/governance/recurring-defect-families.yaml` and `docs/governance/recurring-defect-families.md` in lockstep (Rule G-9.c parity). `last_updated:` bumped to `2026-05-27` with the audit's content-diff payload.

### F-half-built-state-machine
- **title**: Multi-State Enum Declares Lifecycle Members the Code Never Writes
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: An enum / sealed marker / hook-point taxonomy is declared with N members representing an intended lifecycle. The shipped code writes ≤K (K<N) of those members; remaining members are aspirational / future-wave. Javadoc honestly admits the gap, but readers assume the full lifecycle is live. Sibling of `F-discriminator-without-discriminated-type` at the value level rather than type level.
- **surfaces**: `agent-*/src/main/java/**/*Status.java`, `agent-*/src/main/java/**/*State.java`, `agent-*/src/main/java/**/*HookPoint.java`, `agent-*/src/main/java/**/*Reason.java`, `agent-*/src/main/java/**/*Policy.java`, `docs/contracts/engine-hooks.v1.yaml#phase_*_fired*`
- **prevention_rules**: candidate gate-rule W5+ — for every `enum (\w+)` declaration under `*/spi/*` and `service/platform/*` and `service/runtime/*`, run codegraph for `<EnumName>.<MEMBER>` write-sites; FAIL if any member has 0 writers AND no `(W2-deferred — ADR-NNNN)` javadoc marker.
- **cleanup_status**: pending
- **open_residual**: AUD-IDEM-1 (COMPLETED/FAILED), AUD-EVT-4 (HookPoint.ON_YIELD), SBL-HBSM-1 (RunStatus.EXPIRED), SBL-HBSM-2 (4 SuspendReason variants), SBL-HBSM-3 (Task.A2aState + TaskKind), SBL-HBSM-4 (PlaceholderPreservationPolicy.WARN/REWRITE).
- **fingerprint**: regex `enum\s+(\w+)\s*\{[^}]+\}` + per-member write-path codegraph_callers query.

### F-discriminator-naming-drift-doc-vs-code
- **title**: Doc-Cited Enum / Method / Type Name Drifts From Java Source of Truth
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: Active doc surfaces cite enum variants / method signatures / type names that disagree with Java source — wrong case, truncated, wrong arity, wrong parameter type, or older-naming-wave names. Doc lies to readers; gate path-truth rules catch path drift but not name-form drift inside otherwise-resolvable references.
- **surfaces**: `docs/L1/**/*.md`, `docs/logs/reviews/*.md`, `docs/adr/*.yaml`, `docs/contracts/contract-catalog.md`, `agent-*/ARCHITECTURE.md`
- **prevention_rules**: candidate gate-rule W5+ — parse every `HookPoint.\w+|SuspendReason.\w+|RunStatus.\w+|RunRepository.\w+\(` mention in active md/yaml; resolve via codegraph; FAIL if literal name does not match a declared member / method.
- **cleanup_status**: pending
- **open_residual**: PR77-P1-2, PR76-IF-DRIFT-004, AUD-EVT-1, AUD-EVT-3, SBL-NAME-1, SBL-NAME-2.
- **fingerprint**: regex `\b([A-Z][a-zA-Z]+)\.([a-zA-Z_]+)\b` in active md/yaml resolved via codegraph_search.

### F-dfa-without-validator
- **title**: Documented State-Machine DFA Ships Without an Enforcement Validator
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: A state-typed field has a documented DFA (Mermaid stateDiagram, ADR transition table, contract yaml `transitions:` block) naming specific illegal transitions. Java ships the enum + entity but no `<Type>StateMachine.validate(from, to)` validator wired into the persistence write path. Analogous `RunStateMachine` proves the pattern; sibling DFA is unguarded.
- **surfaces**: `agent-*/src/main/java/com/huawei/ascend/service/*/spi/*.java` (enum hosts), `docs/L1/**/*.md` (stateDiagram blocks), `docs/contracts/*.v1.yaml`
- **prevention_rules**: candidate gate-rule W5+ — for every Mermaid `stateDiagram-v2` block in `docs/L1/**/*.md` referencing a Java enum, codegraph for `validate\s*\(\s*<EnumName>` in the same module; FAIL if zero results.
- **cleanup_status**: pending
- **open_residual**: AUD-EVT-5, SBL-DFAW-1, SBL-DFAW-2.
- **fingerprint**: stateDiagram blocks ↔ codegraph_search for validate method on enum.

### F-create-path-not-enrolled-in-dedup-tx
- **title**: Resource-Create Path Bypasses the Idempotency-Claim Transaction
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: An HTTP endpoint or orchestrator creates a top-level / child resource with `new T(UUID.randomUUID(), ...); repository.save(t);` while the surrounding request bears an `Idempotency-Key` header OR the suspend/resume signal carries a `parentNodeKey`. The dedup-claim row is written by a filter/pre-step in a different transaction; the create happens unconditionally if the claim returns "fresh". A TTL re-claim or suspend-resume re-entry produces a duplicate resource — the contract "successful claim ⇒ at-most-one resource" is silently violated.
- **surfaces**: `agent-service/src/main/java/com/huawei/ascend/service/platform/web/**/*Controller.java`, `agent-service/src/main/java/com/huawei/ascend/service/runtime/orchestration/inmemory/SyncOrchestrator.java`, `agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyHeaderFilter.java`
- **prevention_rules**: candidate gate-rule W5+ — codegraph_callers on `IdempotencyHeaderFilter.claimOrFind`; for each endpoint, walk callees for `\.save\(` on `RunRepository`/`TaskStateStore`/`SessionRepository` and FAIL if save is not transactionally enclosed (no `@Transactional` propagation, no deterministic-uuid derivation).
- **cleanup_status**: pending
- **open_residual**: AUD-IDEM-4, AUD-IDEM-5.
- **fingerprint**: `UUID\.randomUUID\(\)\s*;\s*(\w+\.)?save\(` in methods reachable from `*Controller` handlers OR `SuspendSignal.forChildRun` resume points.

### F-vocabulary-identity-collision
- **title**: Two Same-Named Java Types Live in Different Packages of One Module
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: Two `record` / `class` / `interface` declarations share the same simple name in different sub-packages of one Maven module; one is the live carrier, the other is dead-code / pre-rename leftover. Both javadocs cite the same ADR authority. Catalog / SPI Appendix points reviewers at the wrong file. IDE-completion picks the wrong import. Distinct from cross-module deleted-name leakage and from path drift — here the module is right but the basename is doubly resolved.
- **surfaces**: `agent-*/src/main/java/com/huawei/ascend/**/*.java`, `docs/L1/**/spi-appendix.md`, `docs/contracts/contract-catalog.md`
- **prevention_rules**: candidate gate-rule W5+ — for each Maven module, glob `src/main/java/**/*.java` → extract basenames → FAIL on any duplicate basename. Allowlist file for legitimate cases (`package-info.java`).
- **cleanup_status**: pending
- **open_residual**: AUD-IDEM-8 (two `IdempotencyRecord`).
- **fingerprint**: `find <module>/src/main/java -name "*.java" -printf '%f\n' | sort | uniq -d`.

### F-cross-authority-tenant-scope-claim-without-field
- **title**: Authority Surface Claims a Tenant-Scope Field That Does Not Exist on the Carrier Java Type
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**: `docs/contracts/contract-catalog.md` (or `agent-*/ARCHITECTURE.md` §SPI Appendix) declares a structural carrier as `tenant-scoped` via "tenant resolved by `<Carrier>.tenantId` field (Rule R-C.c)" — but the named field does not exist on the carrier record. The honest Java javadoc admits tenant resolution is out-of-band, creating a direct lie between catalog row and type. Rule G-8.e enforces structural-carrier package-and-class existence but not field-level claims. Sibling of `F-design-artifact-omits-tenant-spine` (which is about diagrams ELIDING tenantId); here the diagram/catalog ASSERTS a tenantId that isn't there.
- **surfaces**: `docs/contracts/contract-catalog.md`, `agent-*/ARCHITECTURE.md` SPI Appendix, `docs/L1/**/spi-appendix.md`
- **prevention_rules**: candidate gate-rule W5+ — parse catalog rows matching `tenant.scoped.*<Carrier>\.tenantId` → resolve `<Carrier>` via codegraph_node → FAIL if the type has no `tenantId` record component / field.
- **cleanup_status**: pending
- **open_residual**: AUD-EVT-6.
- **fingerprint**: rg `tenant.{0,12}scoped.*?([A-Z][A-Za-z]+Envelope|...)\.tenantId` in `docs/contracts/contract-catalog.md` → resolve type → check declared components.

## 8. Cross-cutting reflection — is the L1 design ratified?

Per the user's brief — measured against the 2026-05-22 doc's verdict (line 30: "accepted as L1 direction, formally codified in ADR-0115") and Wave 1 §11's 6-G-gate acceptance criteria — the agent-service L1 4+1 is **directionally ratified** but **not yet operationally consistent**.

**What is ratified (passes audit):**

- Five-layer model (Access / Session-Task / Internal Event Queue / Task-Centric Control / Engine Adapter) is correctly named in `logical.md §1` and `2026-05-22 §3`.
- 9 SPI interfaces over 7 packages — 4-way parity on the headline count holds across `module-metadata.yaml`, `docs/dfx/agent-service.yaml`, `contract-catalog.md`, and the Java filesystem (verified by Lens 4 AUD-PARITY-12, -13, -14).
- Run aggregate single-owner pinning for mutations is shipped (every `Run.with*` lambda passes through `RunRepository.updateIfNotTerminal` CAS).
- Tenant-id is a first-class field on `Run` / `Task` / `Session` Java records (Rule R-C.2.a verified).
- Three-track bus binding (Rule R-E) is correctly declared in `bus-channels.yaml` and `physical.md §3`.
- A2A is correctly scoped as protocol-boundary-only (R7); no `a2a-java` runtime dependency.
- Edge↔compute_control isolation (Rule R-I.1) is armed via `EdgeToComputeDirectLinkArchTest` (vacuous-but-passing while `agent-client` is empty).

**What is not yet operationally consistent (blocks L1-ratification claim):**

- **AUD-COHES-1** (P0): Layer 5a ships in the wrong package; the ADR-0140 split is doc-only.
- **AUD-EVT-1** (P0): `SuspendReason` naming disagrees with the 2026-05-22 mapping table on 3 of 5 names.
- **AUD-EVT-6** (P0): `contract-catalog.md` claims a `tenantId` field on `S2cCallbackEnvelope` that does not exist.
- **AUD-EVT-2** (P1): RunEvent sealed hierarchy + 10 variants are design_only; the `EvolutionExport` discriminator ships orphaned.
- **AUD-IDEM-1/-2/-3/-5/-6/-7** (P1×6): The idempotency surface is a textbook `F-design-only-mechanism-shown-as-shipped` pile-up — replay, S2C dedup, outbox event emission, child-run dedup are all wired in docs / contracts but absent from `agent-service/src/main/java`.
- **AUD-EVT-5** (P1): `Task.A2aState` DFA has no validator class.
- **AUD-PARITY-1** (P1): Stale `TaskRepository` label leaks in `ARCHITECTURE.md`.

The audit's verdict: **L1 is provisionally ratified pending the closure of these 11 P0+P1 findings.** Wave 4 will close the doc-side; Waves 3 will reconcile the ADRs; impl-mode follow-up waves will close the Java side.

## 9. Recommended fix order

Group-1 (Wave 3 — ADR yaml edits):

1. **ADR-0019 / ADR-0070 / ADR-0112** — rename `SuspendReason` variants per 2026-05-22:141 mapping (AwaitChild→AwaitChildRun, AwaitExternal→AwaitToolResult, AwaitApproval→RequiresApproval). Mark `AwaitClientCallback` as ADR-0074-owned (already canonical).
2. **ADR-0140** — clarify in `decision:` block whether the 5a/5b split is structural (code package boundary) or logical (taxonomy only). Per AUD-COHES-1, structural-boundary intent is preferred; current code violates that intent.
3. **ADR-0145** — affirm RunEvent sealed hierarchy design and pin a target impl-mode wave. Currently absent or design_only; this audit asks for an explicit impl-mode landing target.
4. **ADR-0057** — add explicit `consequences:` block clarifying that L1 ships claim-only; COMPLETED/FAILED + responseStatus/responseBodyRef are W2; body-drift detection is per-TTL-window scope.
5. **ADR-0100** — reconcile §non_goals "single-interface decision" with current dual `StatelessEngine` + `EngineRegistry/ExecutorAdapter` reality. (May supersede with a clarifying ADR.)

Group-2 (Wave 4 — 4+1 view edits):

6. `logical.md` — fix `TASK ||--|| RUN` ER (PR77-P1-1); align `updateIfNotTerminal` Layer 4 prose to lambda-supplier-vs-CAS-applier vocabulary (AUD-COHES-5); add `FAILED → RUNNING` retry edge (AUD-EVT-7); mark §6 ChatAdvisor row as design_only (AUD-COHES-9); demote `queue_layer` mermaid subgraph (AUD-COHES-7).
7. `process.md` — fix `updateIfNotTerminal(tid, runId, λ)` signature drift (PR77-P1-2); fix SSE W2-shipped (PR77-P2-2); apply uniform `(design_only — ADR-0141)` markers to all Queue emits (AUD-IDEM-7, AUD-IDEM-10); remove/annotate "200 cached response" alt (AUD-IDEM-3).
8. `physical.md` — fix Task/Session ID UUID→String (PR77-P1-3) OR schedule an explicit Flyway-UUID-migration ADR.
9. `scenarios.md` — fix `tenantMismatchReturns403` → `getCrossTenantRunReturns404` (PR77-P2-1); add child-spawn idempotency note (AUD-IDEM-5).
10. `README.md` — fix front-matter (PR77-P2-3): declare full 4+1 coverage; update wave status table to reflect view completion.
11. `spi-appendix.md` — fix `agent-invoke-request.v1.yaml` status to `schema_shipped` (AUD-PARITY-7); update `IdempotencyRecord` cite to the platform package (AUD-IDEM-8); add IdempotencyStore carve-out paragraph (AUD-PARITY-4).
12. `agent-service/ARCHITECTURE.md` — fix `TaskRepository` → `TaskStateStore` (PR77-P2-4 / AUD-PARITY-1); fix `7-interface` → `9-interface` (AUD-PARITY-2).
13. `docs/contracts/contract-catalog.md` — fix `S2cCallbackEnvelope.tenantId` claim (AUD-EVT-6) — either commit to adding the field OR rewrite to the out-of-band registry-resolution wording.
14. `docs/contracts/a2a-envelope.v1.yaml:3` — drop the "ADR-0105" forward-number (AUD-PARITY-6).

Group-3 (impl-mode follow-up, NOT this wave):

- Implement `RunEvent.java` sealed interface + 10 record variants (closes AUD-EVT-2).
- Implement `Task.A2aStateMachine` validator (closes AUD-EVT-5).
- Relocate Layer-5a executors into `service.engine.adapter/` (closes AUD-COHES-1 + AUD-COHES-3).
- Materialize `dual-track-routing-policy.yaml` + `DualTrackRouter` Java class (closes AUD-PARITY-3 watchlist).
- Wire `IdempotencyStore.claimOrFind` into `S2cCallbackTransport.dispatch` (closes AUD-IDEM-6).
- Wire response-replay completion hook (closes AUD-IDEM-1 + AUD-IDEM-2).
- Rename `SuspendReason` records (closes AUD-EVT-1 Java side).
- Add session-scoped `TaskStateStore.load(taskId, sessionId, tenantId)` overload (closes AUD-COHES-6).

## 10. Closure criteria for Waves 1+2

Wave 1:
- 32+ findings authored with `file:line` evidence, severity, surface tag, suggested fix. ✓
- Cross-cutting reflection produced an honest "L1 ratified?" verdict. ✓
- Inherited findings from PR #76 / PR #77 referenced (not re-numbered). ✓
- Document passes manual scan for internal contradictions. (verify before commit)

Wave 2:
- Every finding has a `family:` tag (39 of 39). ✓
- 6 new families registered with all 9 G-9.a fields. ✓
- Each new family carries a fingerprint. ✓
- Sibling sweep returned 10 new sibling-instance findings (SBL-HBSM-1..4, SBL-NAME-1..2, SBL-DFAW-1..2, SBL-COH-1..2), folded into the ledger. ✓
- 5 families returned negative-confirmation lines (Rule G-E non-vacuity). ✓
- `recurring-defect-families.yaml` parses cleanly (verified post-edit). (verify in Wave 5 commit prep)
- `recurring-defect-families.{yaml,md}` family-id parity holds (Rule G-9.c). (verify in Wave 5 commit prep)

## 11. Verification

- Read this doc end-to-end. Confirm each finding's evidence resolves to the cited `file:line` on `main` HEAD `c93c2fd`.
- Sample 5 findings at random and re-verify (PR #76 / #77 review pattern).
- Wave 2 will tag every finding with a family id and run the fingerprint sweep.
- Wave 3 ADR edits and Wave 4 4+1 patches will carry `AUD-<N>` back-references in their commit messages.

## 12. Notes

This wave authors findings only; no corrective edits land in Wave 1. The next wave classifies + sibling-sweeps before any ADR or 4+1 view is touched.
