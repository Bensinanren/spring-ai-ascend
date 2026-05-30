---
level: L1
view: [logical, process, development, physical, scenarios]
module: agent-service
affects_level: L1
affects_view: [logical, process, development, physical, scenarios]
status: proposed
language: zh-CN
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

# Agent Service L1 架构审计 — Wave 1

> 日期：2026-05-27
> 范围：`docs/L1/agent-service/` 下规范的 4+1 视图、`agent-service/src/main/java` 下的 Java 实现面，以及配套的权威面（`agent-service/module-metadata.yaml`、`docs/dfx/agent-service.yaml`、`docs/contracts/contract-catalog.md`、`docs/contracts/*.v1.yaml`、ADR backlog）。
> 维度：幂等性 · 高内聚/低耦合 · 事件类型 · 接口/契约一致性。
> 优先级：当 ADR 与 `docs/logs/reviews/2026-05-22-agent-service-l1-expansion-proposal-response.en.md` 冲突时，以 response 文档为准、ADR 须随之调整（按用户指令 2026-05-27）。
> 标题结论：L1 按 rc55 的 4+1 工作已**方向性获准**，但仍有 **3 个 HIGH 和 11 个 P1** 发现 — 大多聚集在三类复发缺陷家族下（design-only 机制被当作已交付、分层切分低内聚、判别字段缺判别类型） — 必须在 L1 与 2026-05-22 设计哲学达到运行一致前关闭。

## 1. 背景

本次审计是 PR #76 和 PR #77 的后续 — 两份 PR 均对 `docs/L1/agent-service/` 提出了具体的漂移发现，并已独立在 `main` 上复核（PR #77 的 7 条发现仍然成立；PR #76 的 IF-DRIFT-001..006 在行号修正后基本仍然成立）。用户要求以专家架构视角，将 agent-service L1 设计对照 2026-05-22 expansion-proposal-response 文档作为设计哲学的事实基准 — 并明确反转了通常的优先级栈：**当 ADR 与 2026-05-22 文档冲突时，以文档为准、ADR 必须随之调整。**

本文档是按 `D:\.claude\plans\review-pr-76-eventual-wilkes.md` 拆解的 5-wave 审计的 Wave 1 交付物：

| Wave | 输出 | 状态 |
|---|---|---|
| 1 | 本份发现清单文档 | complete |
| **2** | 分类 + 新家族注册 + 同形扫描 | **active** |
| 3 | 按优先级规则调整 ADR yaml | pending |
| 4 | 4+1 视图修正补丁 | pending |
| 5 | 分支 + commit + PR | pending |

## 2. 权威优先级（成文化）

对本次审计中任何指出两个权威面冲突的发现，所应用的优先级规则为：

1. **用户指令（2026-05-27）** — 显式优先级规则宣告。
2. **`docs/logs/reviews/2026-05-22-agent-service-l1-expansion-proposal-response.en.md`** — 设计哲学事实基准。
3. **`docs/logs/reviews/2026-05-26-agent-service-l1-4plus1-rewrite-wave-1.en.md`** — 历史撰写记录，按 ADR-0143 现已降级；在 2026-05-22 沉默处作为补充权威。
4. **`docs/L1/agent-service/` 下规范的 4+1 视图** — 与 2026-05-22 一致时为 L1 运行事实基准。
5. **`docs/adr/` 下的 ADR** — 与上面 2/3/4 冲突时随之调整。
6. **Java 代码** — 兜底；代码与上述权威栈不一致时，审计产出一份纠错 ADR + impl-mode 后续 wave。

本次审计锚定的 2026-05-22 文档最具承载力的几条声明：

- **R7**：A2A 仅作为协议边界采用；不引入 `a2a-java` 运行时依赖（line 79-86）。
- **R8**：Run 身份 / DFA / 持久化按 ADR-0078 + ADR-0088 留在 `service.runtime`；Engine 驱动但不拥有（line 108-120）。
- **`SuspendReason` 5 名映射**：`INPUT_REQUIRED↔AwaitClientCallback`、`SUB_TASK_AWAIT↔AwaitChildRun`、`TOOL_EXECUTION↔AwaitToolResult`、`DELAY_AWAIT↔AwaitTimer`、`POLICY_APPROVAL↔RequiresApproval`（line 141）。
- **包根中无 `.agent.` 段**（line 57） — 已遵循。
- **三级持久化**：Memory → Postgres → Temporal，按 ADR-0021（line 145+）。

## 3. 自 PR #76 / PR #77 继承的发现

PR #77 的 7 条发现（P1-1 .. P2-4）按原编号继承。本次审计清单中**不重新编号**；以 `PR77-P1-1` 等形式引用，将由 Wave 4 的 4+1 补丁关闭。PR #76 的接口漂移评审引入 IF-DRIFT-001..006，同样继承并以 `PR76-IF-DRIFT-001` 等形式引用。

其中两条 PR 继承发现已经在当前 `main` HEAD `c93c2fd` 上再次确认，并纳入本次审计的修复队列：

- `PR77-P1-1` — `logical.md:155` `TASK ||--|| RUN` ER 基数与同文件 `:281-283` 的散文表述相矛盾。
- `PR77-P1-2` — `process.md:55,66,72,79,262,269` 使用 3-参 `updateIfNotTerminal(tid, runId, λ)` 签名，但 Java 中并不存在（`RunRepository.java:44` 是 2-参）。
- `PR77-P1-3` — `physical.md:49-50` 把 `tasks.task_id` 和 `sessions.session_id` 标为 `UUID`，而 `Task.java:39` 和 `Session.java:36` 声明为 `String`。
- `PR77-P2-1` — `scenarios.md:117` 引用 `RunHttpContractIT.tenantMismatchReturns403`，但实际测试名为 `getCrossTenantRunReturns404`。
- `PR77-P2-2` — `process.md:81` 把 SSE 称为 "W2-shipped"，而 `openapi-v1.yaml:289,295` 表明其属于 W2 范围。
- `PR77-P2-3` — `README.md:3,7` 声明 `view: scenarios` / `covers_views: [scenarios]`，正文却声明 "L1 4+1 Architecture (Index)"。
- `PR77-P2-4` — `agent-service/ARCHITECTURE.md:524` 仍残留 `tenantMismatchReturns403`；`:677` 仍残留 `TaskRepository`。

## 4. Wave-1 发现清单

严重程度图例：**P0** = 阻塞发布（正确性或契约谎报）；**P1** = 重大缺陷（设计意图未达成、有审计可发现的危害）；**P2** = 质量 / 卫生（漂移、轻微不一致）；**P3** = 细节 / 前向指针。

每条发现包含：`Severity`、`Surface`、`Evidence`（含 file:line + 原文引用）、`2026-05-22 ref`（如适用）、`What's wrong`、`Suggested fix`。家族标签在 Wave 2 添加。

### 维度 1 — 幂等性 (Idempotency)

#### AUD-IDEM-1 — `IdempotencyStore.Status.COMPLETED` 与 `.FAILED` 从未被写入（半成品枚举）

- **Severity**：P1
- **Surface**：Java（`platform/idempotency/`）
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyStore.java:33-37` 声明 `enum Status { CLAIMED, COMPLETED, FAILED }`；对 `platform/idempotency/**` 的 grep 显示零处写入 `COMPLETED` / `FAILED`。`InMemoryIdempotencyStore.java:48` 与 `JdbcIdempotencyStore.java:39,42` 均硬编码 `Status.CLAIMED`。`IdempotencyStore.java:15-17` 的注释自承："L1 stops at the CLAIMED status. W2 will add COMPLETED/FAILED transitions plus response replay."
- **What's wrong**：三态枚举声明了代码从未写入的生命周期 — 教科书级 `F-half-built-state-machine`。
- **Suggested fix**：把 `COMPLETED`/`FAILED` 常量在 Javadoc 中以 `@deferred` 标记并引用 ADR-0057 §2，或者在 W2 落地这些转换前直接移除。

#### AUD-IDEM-2 — `responseStatus` / `responseBodyRef` 是死字段；尽管 schema 存在，replay 仍是 design_only

- **Severity**：P1
- **Surface**：Java + 契约 yaml
- **Evidence**：`IdempotencyStore.java:40-50` 定义 `record IdempotencyRecord(... Integer responseStatus, String responseBodyRef, ...)`。`InMemoryIdempotencyStore.java:46-49` 永远以 `null, null` 构造。`JdbcIdempotencyStore.java:43-44` 写入 `response_status = NULL, response_body_ref = NULL`。`IdempotencyHeaderFilter.java:152-164` 在命中时从不读取这两个字段 — 仅返回 409（`idempotency_conflict` / `idempotency_body_drift`）。
- **2026-05-22 ref**：`2026-05-22-...response.en.md:96` — "we ship `IdempotencyStore` postgres + redis impls per ADR-0057" 以及 "JPA is used at platform-side read paths (idempotency replay, posture, runs read-side)"。
- **What's wrong**：Replay 不可能完成 — 两个实现都只写入 NULL。2026-05-22 文档告诉评审者 replay 是活的读路径；L1 `process.md §P1` 展示了 `200 cached response` 分支，但代码无法产出。
- **Suggested fix**：要么在 process.md 与契约面上将这两个字段和 `200 cached response` 分支标记为 `(design_only — W2, ADR-0057 §2)`，要么接入一个 completion hook 在终态 HTTP 响应时填充字段。

#### AUD-IDEM-3 — `process.md §P1` 的 "200 cached response" alt 分支今天不可实现

- **Severity**：P1
- **Surface**：4+1 视图
- **Evidence**：`docs/L1/agent-service/process.md:44-45` `alt Idempotency hit / Idem-->>Client: 200 cached response (or 409 idempotency_conflict / 409 idempotency_body_drift)`。代码路径 `IdempotencyHeaderFilter.java:150-163` 只返回 409。
- **What's wrong**：L1 过程视图承诺了代码无法产出的行为；AUD-IDEM-2 在文档面的同形发现。
- **Suggested fix**：从 `alt` 分支移除 "200 cached response"，或标注 `(design_only — W2)`。

#### AUD-IDEM-4 — 顶层 Run 创建通过普通 `repository.save(...)` 写入，未纳入幂等 claim 的事务

- **Severity**：P2
- **Surface**：Java
- **Evidence**：`RunController.java:101` `Run saved = repository.save(run);` — 无 CAS、无 `tx_id` 关联到 dedup-claim 行。`SyncOrchestrator.java:99` `runs.findById(runId).orElseGet(() -> runs.save(createRun(...)))` — 非原子的 get-then-save。`JdbcIdempotencyStore.java:46-48` 自承当 `expires_at <= now` 后存在 TTL 二次 claim。
- **What's wrong**：一个重试的 `POST /v1/runs` 在错失 409 而恰好遇上 TTL 二次 claim 时会以新的 runId 创建第二个 Run — 即使 dedup "成功" 也产生重复运行。
- **Suggested fix**：要么把 `IdempotencyHeaderFilter.claimOrFind` + `RunController.create.save` 纳入一个事务，要么用 `runId = deterministicHash(tenantId, idempotencyKey)` 派生，使 TTL 二次 claim 的赢家确定性落在同一行。

#### AUD-IDEM-5 — 子 Run 派生使用新生成的 `UUID.randomUUID()` — 父级重试会产生重复子运行

- **Severity**：P1
- **Surface**：Java + 场景 S3
- **Evidence**：`SyncOrchestrator.java:204` `UUID childRunId = UUID.randomUUID();` 紧随其后是 `runs.save(new Run(childRunId, ...));`。`SuspendSignal.forChildRun(...)` 携带 `parentNodeKey` 但在 `(parentRunId, parentNodeKey)` 上无幂等键。
- **What's wrong**：在瞬态故障 / orchestrator 重启 / W2 异步恢复后，再次进入 SuspendSignal 分支会以新 UUID 派生第二个子运行。场景 S3（`scenarios.md:75-79`）未声明子派生幂等性。Run 聚合暴露 `RunRepository.findByParentRunId`，因此重复子项会被用户可见。
- **Suggested fix**：以 `childRunId = uuidV5(parentRunId, parentNodeKey)` 派生，使重派幂等碰撞；或在 `RunRepository` 中维护 `(parentRunId, parentNodeKey) → childRunId` 索引。

#### AUD-IDEM-6 — `S2cCallbackEnvelope.idempotencyKey` 在契约中是必填，但运行时从不查询

- **Severity**：P1
- **Surface**：Java + 契约 yaml
- **Evidence**：`docs/contracts/s2c-callback.v1.yaml:41` 把 `idempotency_key` 标为 REQUIRED 并注释 "client may retry; runtime dedupes within window"。`S2cCallbackEnvelope.java:32,45` 在构造时强制 `idempotencyKey` 非空。`SyncOrchestrator.handleClientCallback(...)`（`SyncOrchestrator.java:366-411`）从不读取 `envelope.idempotencyKey()`；无条件派发 transport 并仅按 `callbackId` 匹配。`idempotencyKey` 的 grep 结果只命中构造器 / record 站点。
- **What's wrong**：契约宣告 "runtime dedupes within window"，但运行时没有任何路径基于该 envelope 的幂等键短路。父级在同一 `parentNodeKey` 上再次挂起会构造新 envelope（新的 `callbackId`），契约保证被静默违反。
- **Suggested fix**：在 `transport.dispatch` 之前把 S2C envelope 经由 `IdempotencyStore.claimOrFind(tenantId, envelope.idempotencyKey(), envelopeHash)` 路由；或把该字段从 schema 中移除并在 yaml 中标记 `(design_only — W2)` 作为 W3-design。

#### AUD-IDEM-7 — Outbox / Inbox 模式在 `process.md` 事件发出步骤中声明；Java 中缺席

- **Severity**：P1
- **Surface**：4+1 视图 + Java
- **Evidence**：`docs/L1/agent-service/process.md:49,57,67,80,135,144` 均展示 `RR-->>Queue: publish <Event>`。`logical.md:35` 把 `Layer 3 — Internal Event Queue` 标注为 `<i>Future: service.queue/</i>`。对 `agent-service/src/main/java` 中 `CancelRequestedEvent|RunStateTransitionEvent|TerminalTransitionEvent` 的 grep 命中零。即使 `RunEvent` 也仅出现在 `evolution/EvolutionExport.java`（scope marker 枚举）中，并非密封事件层级。
- **What's wrong**：P3 cancel 流程图步骤 `RR-->>Queue: publish CancelRequestedEvent + RunStateTransitionEvent + TerminalTransitionEvent` 是代码无法完成的原子双写 — 无 outbox 表、无事件发布器、无密封 `RunEvent` Java 类型。ADR-0145 被引用但缺席。即使未来出现发布器，若无 outbox，崩溃会丢失 CAS 与 publish 之间的事件。
- **Suggested fix**：把 `process.md` 中每一个 `RR-->>Queue` 步骤标注为 `(design_only — ADR-0141 / ADR-0145)`。把第 49 / 57 行的 `(when L3 lands)` 标注扩展到每个 emit。在 `spi-appendix.md §5` 中添加 outbox SPI 占位或显式延迟行。

#### AUD-IDEM-8 — 两个发散的 `IdempotencyRecord` 类型（一个在 `platform`，一个孤悬在 `runtime`）

- **Severity**：P2
- **Surface**：Java + spi-appendix
- **Evidence**：`agent-service/.../service/platform/idempotency/IdempotencyStore.java:40-50` 声明 `IdempotencyRecord`（含 `status`/`responseStatus`/`responseBodyRef`/`expiresAt`）。`agent-service/.../service/runtime/idempotency/IdempotencyRecord.java:12-17` 声明另一个（含 `runId`/`claimedAt` — 无 `status`）。对 `runtime\.idempotency` 的 grep 只命中该文件自身的包声明 — 零引入方。`docs/L1/agent-service/spi-appendix.md:59` 把后者引用为 "contract-spine entity per ADR-0057" — 但 ADR-0057 §2 仅声明了 platform 包下的那个 record。
- **What's wrong**：两个同名 record，均不在 `.spi.` 下，且都自称承载同一契约权威。runtime 那个是死代码；spi-appendix 把评审者指向了错误文件。
- **Suggested fix**：删除 `service/runtime/idempotency/IdempotencyRecord.java`，或若 ADR-0057 §2 的包是真相，则把活跃的 platform record 重定位到 `runtime/idempotency` 下。同时更新 `spi-appendix.md:59`。

#### AUD-IDEM-9 — `JdbcIdempotencyStore` 的 TTL 二次 claim 静默丢弃旧 `request_hash` — 跨 TTL 边界的 body-drift 检测失效

- **Severity**：P2
- **Surface**：Java
- **Evidence**：`JdbcIdempotencyStore.java:40-48` `ON CONFLICT ... DO UPDATE SET request_hash = EXCLUDED.request_hash, status = 'CLAIMED', response_status = NULL, ... WHERE idempotency_dedup.expires_at <= EXCLUDED.created_at`。WHERE 用 TTL 守门，SET 却把 `request_hash` 整体替换。
- **What's wrong**：ADR-0057 §1 的 "request_hash to detect key reuse with different body" 保证在 TTL 边界下被削弱 — body-drift 检测仅在单个 TTL 窗口内成立。一个携带原始（TTL 前）body hash 的第三次请求不会被检测为漂移。
- **Suggested fix**：在 ADR-0057 / `IdempotencyStore.java` Javadoc 中显式记录：body-drift 检测的作用域限定在一个 TTL 窗口内；新 claim 会重置比较基线。

#### AUD-IDEM-10 — `(when L3 lands)` 标注在 `process.md` 事件发出步骤中不均匀

- **Severity**：P2
- **Surface**：4+1 视图
- **Evidence**：`process.md:49` 写 `RR-->>Queue: (when L3 lands) publish RunCreatedEvent`。`process.md:135-136,140,144` 写 `RR-->>Queue: publish CancelRequestedEvent`（无标注）。今天两条路径都不发布（AUD-IDEM-7）；标注存在不对称。
- **What's wrong**：读者一致性破坏。要么所有 `Queue` 发出步骤都被延迟（今天为真），要么没有（今天为假）。
- **Suggested fix**：把 `(when L3 lands)` 应用到每一个 `RR-->>Queue` 步骤；或抽到单一前言："在 §P1-§P6 中，每一处 `Queue` 发出都是 `(design_only — ADR-0141)`。"

### 维度 2 — 高内聚 / 低耦合

#### AUD-COHES-1 — Layer 5a（Engine Dispatch & Execution）实际落在 `service.runtime.orchestration.inmemory/` 之下，而非 ADR-0140 所宣告的 `service.engine.adapter/`

- **Severity**：P0（HIGH）
- **Surface**：Layer 5a vs Layer 4 拆分（ADR-0140）；包布局
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/engine/adapter/` 仅包含 `InMemoryStatelessEngine.java`（返回 `NO_CHANGE` 的空操作占位 — `InMemoryStatelessEngine.java:32-54`）。真正的 Layer-5a 执行器位于 `service.runtime.orchestration.inmemory/`：`SequentialGraphExecutor.java`、`IterativeAgentLoopExecutor.java`、`SyncOrchestrator.java`。`EngineAutoConfiguration.java:4-5,60-68` 通过直接 import 装配这些执行器。`logical.md §1 row 42` 声明 `Layer 5a … (service.engine.{adapter,spi} + consumed engine.spi.* cross-module)`。`development.md §2 matrix row "5a"` 声明所属包为 `service.engine.adapter/` + `service.engine.spi/`。
- **What's wrong**：ADR-0140 的 5a/5b 拆分仅是文档分类学。代码的 Layer 5a 之家放着一个惰性 stub，真正的执行器适配器却住在 `service.runtime.orchestration.inmemory/` 下。Layer 4（Orchestrator）与 Layer 5a（executor adapter）共置于同一个包中 — 恰是 ADR-0140 试图修复的低内聚形态。
- **Suggested fix**：要么 (a) 将 `SequentialGraphExecutor` + `IterativeAgentLoopExecutor` 物理迁入 `service.engine.adapter/`，并拆分 `SyncOrchestrator` 使派发循环留在 Layer 4 而引擎特定的 executor 类移到 5a；要么 (b) 更新 `development.md §2` + `logical.md §1`，诚实地声明实际的 layer↔package 映射（Layer 5a = `service.runtime.orchestration.inmemory/`），并把 `service.engine.adapter/` 降级为 `(stub-only)` 注释。按用户优先级，选项 (a) 更优 — 2026-05-22 设计哲学把层内聚视为一等不变量。

#### AUD-COHES-2 — Layer 5b "Translation & Tool-Intercept" 无内部子分包；4 个不同机制共享一个扁平的 `service.integration.springai/` 目录

- **Severity**：P2
- **Surface**：ADR-0140 5b 拆分粒度
- **Evidence**：对 `engine.translation|engine.shadow|engine.translator` 的 grep 返回零命中。`service.integration.springai/` 有 8 个文件（ChatModel gateway、vector store adapter、embedding model adapter、prompt template adapter、output converter、tool-callback adapter、document retriever），全部扁平。`logical.md §1 row 46` 声明 Layer 5b 拥有 4 个逻辑上不同的机制族（ContextProjector、PromptTemplate、StructuredOutputConverter、ChatAdvisor）且 "compose serially"。
- **What's wrong**：拥有 4 个逻辑上不同机制族的层落地为一个未区分的扁平包。未来 ChatAdvisor + ContextProjector 的组合无结构性约束。
- **Suggested fix**：对 `service.integration.springai/` 做子分包（如 `springai.model/`、`springai.tool/`、`springai.context/`、`springai.output/`），或在 `development.md §3` 中显式说明这 4 个机制按设计共享一个 Spring-AI 绑定的扁平包。

#### AUD-COHES-3 — `EngineAutoConfiguration` 直接 import `service.runtime.orchestration.inmemory.*` 执行器类；`PlatformImportsOnlyRuntimePublicApiTest` 中的豁免之所以存在，仅是因为 Layer 5a 错位

- **Severity**：P2
- **Surface**：Rule R-C.2.c 防御性约束（禁止 `runtime → platform`）；platform→runtime 白名单（E34）
- **Evidence**：`EngineAutoConfiguration.java:4-5` 从 `service.runtime.orchestration.inmemory.*` 直接 import `SequentialGraphExecutor` 与 `IterativeAgentLoopExecutor`。`PlatformImportsOnlyRuntimePublicApiTest.java:71-107` 通过 `resideOutsideOfPackage("com.huawei.ascend.service.platform.engine..")` 排除整个 `service.platform.engine..` 子树。
- **What's wrong**：该豁免精神上正确，存在却因 AUD-COHES-1。修复 AUD-COHES-1 即可回收该豁免。
- **Suggested fix**：在 AUD-COHES-1 重定位之后，移除 `resideOutsideOfPackage(...platform.engine..)` 豁免并重写该测试以允许 `service.engine.adapter/*` 从 `service.platform.engine/*` 引入 — 这才是合规的跨层装配边。

#### AUD-COHES-4 — `RunController.create` 在 Layer 1 直接调用 `new Run(...)`；logical.md §1 的 "Run aggregate (single owner)" 声明强于代码现实

- **Severity**：P2
- **Surface**：ADR-0142 Run aggregate 单一所有者 pinning；logical.md §1 row 36
- **Evidence**：`RunController.java:86` 调用 `new Run(UUID.randomUUID(), tenant.tenantId().toString(), ..., RunStatus.PENDING, ...)`，随后在 101 行 `repository.save(run)`。`process.md` P1 line 48 把该路径画为 `Run->>RR: save(Run with status=PENDING, tenantId=tid)`，并标注 `<i>create-only path per rc39 source-guard</i>`。`logical.md §1 row 36` 声明 "Run aggregate (single owner per ADR-0142)"。
- **What's wrong**：变更确实经由 Layer 2（`updateIfNotTerminal`）路由，但初始构造是 Layer 1 的职责。"single owner" 声明把构造与变更混为一谈。
- **Suggested fix**：要么 (a) 引入 `RunRepository.createForTenant(tenantId, capability, ...)`，使 Layer 1 不再直呼构造器；要么 (b) 把 `logical.md §1` 弱化为 "Run aggregate mutation single-owner"，并注明初始构造按 `rc39 source-guard` 是 Layer 1 的特权。

#### AUD-COHES-5 — `Run.withStatus(...)` 由 Layer 1（`RunController`）与 Layer 4（`SyncOrchestrator`）以 lambda 提供；"Layer 4 NEVER writes Run state directly" 表述具有误导性

- **Severity**：P2
- **Surface**：ADR-0142 "Layer 4 NEVER writes Run state directly"
- **Evidence**：`RunController.java:170` `repository.updateIfNotTerminal(id, r -> r.withStatus(RunStatus.CANCELLED))`。`SyncOrchestrator.java:100,122,164,185,222,238` 全部提供 `r -> r.withStatus(...)` lambda。`logical.md §1 R3 correction line 14` 与 `process.md` line 250（state-machine note）都写 "Layer 4 NEVER writes Run state directly"。
- **What's wrong**：lambda 由调用方（Layer 1 / 4）**定义**，并由 Layer 2 的 `updateIfNotTerminal` 原子**调用**。CAS 原子性是正确的；文档所宣称的 single-writer 具有误导性。
- **Suggested fix**：把 `logical.md §1 R3` 与 `process.md` 注释改写为 "Layer 4 NEVER mutates Run state outside the atomic CAS lambda" — 区分 **lambda-supplier 角色**（Layer 1/4）与 **CAS-applier 角色**（Layer 2）。

#### AUD-COHES-6 — `TaskStateStore.load(taskId, tenantId)` 无 `sessionId` 参数；跨 session 任务名漂移引入歧义读取风险

- **Severity**：P2
- **Surface**：`service.task.spi.TaskStateStore`；ADR-0100 1:N session↔task
- **Evidence**：`TaskStateStore.java:43` `Optional<Map<String, Object>> load(String taskId, String tenantId);` — 无 sessionId。`:13-16` 的 Javadoc 写 "TaskID and SessionID are logically decoupled: one Session may concurrently execute multiple Tasks; one Task may drift across multiple Sessions."。`logical.md §2` Task ER 中 "sessionId" 行标注 `(nullable; tasks may drift, ADR-0100)`。
- **What's wrong**：若 `taskId` 在租户下全局唯一，则无害。若 `taskId` 曾经被会话作用域化（Javadoc 明确允许这一路径），则该签名无法区分应加载哪一个 Session 视角的 task。
- **Suggested fix**：在 Javadoc 中声明 `taskId` 在租户下**全局唯一**（禁止在另一键上漂移），或新增 `Optional<String> sessionId` 使消歧显式化。

#### AUD-COHES-7 — `logical.md §1` mermaid 把 Layer 3 `queue_layer` 子图渲染得与已交付层视觉等同 — 对以图为先的读者，design_only 不可见

- **Severity**：P3
- **Surface**：Layer 3（Internal Event Queue） — ADR-0141 design_only
- **Evidence**：`logical.md §1` 34-36 行展示 `queue_layer` 子图，完整呈现 Producer/Consumer 流。`development.md §1` 正确地从树中省略 `service.queue/`；§4 正确地将其列为 future。
- **What's wrong**：合规性良好（边界契约已发布、无代码之家）。但 mermaid 子图在图上与 Layer 5a/5b 同等显眼 — 恰是 Rule G-3.e 试图防止的视觉歧义。
- **Suggested fix**：在 mermaid 中以视觉方式降级 `queue_layer`：`style queue_layer stroke-dasharray:5 5,fill:#eee`，使读者一眼即知 Layer 3 为 design-only。

#### AUD-COHES-8 — 横切关注点（logging、MDC、JWT 交叉校验）被混入 `RunController` 而非提升到 Layer-1 同级 filter

- **Severity**：P3
- **Surface**：Rule R-M.c（横切内聚）；非违规但具维护性风险
- **Evidence**：`RunController.java:81-108` 内联执行 logging（`LOG.info("Run created: ...")`）、MDC 绑定（`MDC.put("run_id", ...)` 然后 `MDC.remove`）、trace-id 读取。无 `RunCreationLogFilter` 同级文件存在。
- **What's wrong**：`logical.md §6` row 2 自承 HTTP-edge filters 是合法的 Layer-1 横切面。本身非违规；但每个 endpoint 都将重复该 MDC 仪式。
- **Suggested fix**：重构 `RunController.create`，把 MDC + log 委托到 Servlet-filter 同级（Layer 1 横切桶，参见 `development.md §2 row 7`）。**不**要上移到 `RuntimeMiddleware`（那会违背 §6 区分）。

#### AUD-COHES-9 — `ChatAdvisor` Java 接口不存在；仅 `AdvisorBinding` record 已交付。`logical.md §6` R4 修正是空载-但已布防的

- **Severity**：P3
- **Surface**：`logical.md §6` R4 修正；防御性约束
- **Evidence**：`AdvisorBinding.java` 存在于 `service.agent.spi/`（record）。`RuntimeMiddleware.java` 存在于 `agent-middleware/.../middleware/spi/`。无 `ChatAdvisor.java` 交付。也没有 ArchUnit 测试断言 `logical.md §6` 所宣称的基数差异。
- **What's wrong**：R4 修正要求 ChatAdvisor 与 RuntimeMiddleware 是不同机制 — 但对应的 `ChatAdvisor` 接口尚未落盘。在它落地之前，§6 行是空载的。
- **Suggested fix**：把 `logical.md §6 row 1` 中的 ChatAdvisor + AdvisorChain 标记为 `(design_only — Spring AI shell)`，与 §1 row 47 的标注一致。接口落地后，添加 `RuntimeMiddlewareDistinctFromChatAdvisorTest` 断言不同模块 + 不同基数契约。

### 维度 3 — 事件类型定义

#### AUD-EVT-1 — `SuspendReason` 命名漂移：代码 permits（6 个变体）vs 2026-05-22 文档（5 个名）

- **Severity**：P0（HIGH — 按用户优先级规则，文档为准；ADR + 代码必须修改）
- **Surface**：`SuspendReason.java` permits ↔ `2026-05-22-...response.en.md:141` 映射表
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/runtime/resilience/spi/SuspendReason.java:41-46` permits `{RateLimited, AwaitChild, AwaitTimer, AwaitExternal, AwaitApproval, AwaitClientCallback}`。`2026-05-22-...response.en.md:141` 写 "`INPUT_REQUIRED↔AwaitClientCallback`, `SUB_TASK_AWAIT↔AwaitChildRun`, `TOOL_EXECUTION↔AwaitToolResult`, `DELAY_AWAIT↔AwaitTimer`, `POLICY_APPROVAL↔RequiresApproval`"。
- **What's wrong**：3 处 doc-vs-code 名称漂移：
  - `AwaitChildRun`（doc）vs `AwaitChild`（code）
  - `AwaitToolResult`（doc）vs `AwaitExternal`（code）
  - `RequiresApproval`（doc）vs `AwaitApproval`（code）
  `logical.md §6` 复制了 doc 侧名称却未指出该分歧。`RateLimited` 是 ADR-0070 合法的第 6 个变体。
- **Suggested fix**（按优先级）：把 Java records 重命名为 `AwaitChildRun / AwaitToolResult / RequiresApproval`。更新 ADRs 0019 / 0070 / 0112 中所有列举该分类的地方。新增一个 ArchUnit 测试锁定 permits 列表。

#### AUD-EVT-2 — `RunEvent` 密封层级 + 10 个变体在 Java 侧完全缺席；判别枚举 `EvolutionExport` 孤悬交付

- **Severity**：P1
- **Surface**：`logical.md §7` + `run-event.v1.yaml` ↔ Java
- **Evidence**：对 `record RunCreatedEvent|sealed.*RunEvent` 的 Java grep 返回零命中。`EveryRunEventDeclaresEvolutionExportTest.java:39-75` 以 `allowEmptyShould(true)` 引入尚不存在的类型。`EvolutionExport.java:39-46` 已交付 3 个变体 `{IN_SCOPE, OUT_OF_SCOPE, OPT_IN}`。
- **What's wrong**：教科书级 `F-discriminator-without-discriminated-type`。在变体落地前 Rule R-M.e 无法执行。
- **Suggested fix**：开启一个 impl-mode wave，按 `run-event.v1.yaml` 物化 `RunEvent.java` + 10 个 records。ArchUnit 测试届时自动开始断言。

#### AUD-EVT-3 — `HookPoint` 枚举常量使用 SCREAMING_SNAKE_CASE，而文档 + yaml 使用小写截断名

- **Severity**：P2
- **Surface**：`HookPoint.java` ↔ `engine-hooks.v1.yaml#hooks` ↔ `SuspendReason.java` Javadoc + `logical.md` 引用列表
- **Evidence**：`agent-middleware/.../middleware/spi/HookPoint.java:25-42` 声明 `BEFORE_LLM_INVOCATION, AFTER_LLM_INVOCATION, BEFORE_TOOL_INVOCATION, AFTER_TOOL_INVOCATION, BEFORE_MEMORY_READ, AFTER_MEMORY_WRITE, BEFORE_SUSPENSION, BEFORE_RESUME, ON_ERROR, ON_YIELD`。`engine-hooks.v1.yaml:33-47` 使用小写。`SuspendReason.java:32-33` 与 `logical.md:335` 引用 `HookPoint.before_tool` / `HookPoint.after_tool`（小写 + 截断）。
- **What's wrong**：文档引用的 `HookPoint.before_tool` / `after_tool` 既大小写错误又是截断名（`before_tool` 而非 `BEFORE_TOOL_INVOCATION`）。Gate Rule 57 不检测名称对齐。
- **Suggested fix**：把文档引用规范化为 `HookPoint.BEFORE_TOOL_INVOCATION` / `HookPoint.AFTER_TOOL_INVOCATION`（与 Java 字面一致）。添加 gate check，使任何 `HookPoint.<value>` 散文引用都能解析为真实的枚举常量。

#### AUD-EVT-4 — `HookPoint.ON_YIELD` 在枚举 + yaml 中交付，却未列入 `engine-hooks.v1.yaml#phase_2_mandatory_hooks_fired_by_orchestrator` — 声明但不点火

- **Severity**：P3
- **Surface**：`HookPoint.ON_YIELD` ↔ orchestrator 点火范围
- **Evidence**：`HookPoint.java:35-41` 交付 `ON_YIELD`；`engine-hooks.v1.yaml:47` 列出；`engine-hooks.v1.yaml:89-92` `phase_2_mandatory_hooks_fired_by_orchestrator` 仅列 `on_error, before_suspension, before_resume`。grep 不到 `ON_YIELD` 的 orchestrator 点火点。
- **What's wrong**：小尺度的 F-discriminator-orphan-ship：值已声明为存活，无生产点火路径。
- **Suggested fix**：在 yaml 中为每个 hook 条目添加 `firing_status: fired | declared_deferred` 字段；或扩展现有 `phase_2_*` 块以显式列出 `on_yield: declared_deferred`。

#### AUD-EVT-5 — `Task.A2aState` 5 态 DFA 无状态机校验器

- **Severity**：P1
- **Surface**：`Task.A2aState` ↔ `logical.md §4` + `a2a-envelope.v1.yaml`
- **Evidence**：`Task.java:70-76` 声明的 5 个值与 `a2a-envelope.v1.yaml:22-38` 对齐。`logical.md §4` 渲染状态图并显式禁止 {SUBMITTED→COMPLETED, INPUT_REQUIRED→COMPLETED} 转移。grep `class.*A2aStateMachine|class.*A2aValidator` 返回零。对应的 `RunStateMachine.java:51-58` 已为 RunStatus 存在。
- **What's wrong**：文档承诺了一个代码不强制的 DFA。今天两类非法转移会静默成功。
- **Suggested fix**：参照 `RunStateMachine` 添加 `Task.A2aStateMachine.validate(from, to)`。把它接入 `TaskStateStore.updateState(...)` 站点（目前缺席 — 参见 AUD-COHES-6）。

#### AUD-EVT-6 — `S2cCallbackEnvelope` 有 8 个字段（Java）；`contract-catalog.md:90` 声明的 `tenantId` 字段不存在

- **Severity**：P0（HIGH — 跨权威关于 tenant-scope 承载的谎报）
- **Surface**：`S2cCallbackEnvelope.java` ↔ `s2c-callback.v1.yaml` ↔ `contract-catalog.md:90`
- **Evidence**：`agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackEnvelope.java:26-35` 声明 8 个 record 组件 `(callbackId, serverRunId, capabilityRef, requestPayload, traceId, idempotencyKey, deadline, requestAttributes)`。无 `tenantId` 字段。`s2c-callback.v1.yaml:34-46` 列 6 个必填 + 3 个可选。`contract-catalog.md:90` 声明 "`S2cCallbackTransport` | tenant-scoped | `S2cCallbackEnvelope.tenantId` field (Rule R-C.c)"。`S2cCallbackEnvelope.java:25` 的 Javadoc 诚实记录："tenant resolved from `callbackId` via registry at the wrapping Run boundary (ADR-0074 §Consequences)"。
- **What's wrong**：catalog 错误地把一个不存在的 tenant 字段宣告为 tenant-scope 承载。按 Rule R-C.c，一行结构承载记录必须指向 Java 类型中真实存在的字段。
- **Suggested fix**（按 Rule G-8.e + Rule R-C.c，首选）：把 `tenantId` 作为 `S2cCallbackEnvelope` 的 record 组件加入，并校验非空。备选：把 `contract-catalog.md:90` 改写为 "tenant resolved out-of-band via `S2cCallbackTransport` registry binding at wrapping Run boundary, per ADR-0074 §Consequences" — 显式声明**没有**带内 tenant 字段。

#### AUD-EVT-7 — `RunStatus` DFA 的 `FAILED → RUNNING` 重试边在代码中存在，但 `logical.md §3` mermaid 中缺失

- **Severity**：P3
- **Surface**：`RunStateMachine.java:37` ↔ `logical.md §3`
- **Evidence**：`RunStateMachine.java:37` 允许 `FAILED → RUNNING`（重试路径）。`logical.md §3` mermaid stateDiagram-v2 未展示此边。
- **What's wrong**：图不完整；读者无法仅凭图推断重试语义。
- **Suggested fix**：在 §3 图中添加 `FAILED --> RUNNING : retry` 边（如有重试策略亦一并引用）。

### 维度 4 — 接口与契约一致性（四方对齐）

#### AUD-PARITY-1 — `agent-service/ARCHITECTURE.md:677` 提到 `TaskRepository` SPI；规范名是 `TaskStateStore`

- **Severity**：P1
- **Surface**：`agent-service/ARCHITECTURE.md` ↔ module-metadata ↔ DFX ↔ catalog ↔ Java
- **Evidence**：`agent-service/ARCHITECTURE.md:677` 表格行使用 `TaskRepository`。同文件 §11.2 line 700 正确使用 `TaskStateStore`。Module-metadata line 19、DFX line 20、`contract-catalog.md` row 45、`spi-appendix.md` row 7，以及 `agent-service/src/main/java/com/huawei/ascend/service/task/spi/TaskStateStore.java` 全部使用规范名。
- **What's wrong**：陈旧 SPI 标签在 §11.2 正确命名同一行的同一表格中残留 — 内部自相矛盾。
- **Suggested fix**：把 `ARCHITECTURE.md:677` 的 `TaskRepository` 替换为 `TaskStateStore`。（与 PR77-P2-4 同一缺陷面。）

#### AUD-PARITY-2 — `agent-service/ARCHITECTURE.md:784` "7-interface count" 残留出现在标题声明 9 的小节中

- **Severity**：P2
- **Surface**：`agent-service/ARCHITECTURE.md` SPI Appendix
- **Evidence**：`ARCHITECTURE.md:766` 写 "9 active Java SPI interfaces as of rc43"。`ARCHITECTURE.md:784` 写 "not included in the 7-interface count"。770-781 行的表格共 9 行。
- **What's wrong**："7-interface count" 是过时的 rc22 措辞。
- **Suggested fix**：把 `7-interface count` 替换为 `9-interface count`。

#### AUD-PARITY-3 — `dual-track-routing-policy.yaml` 在 2026-05-22 response 文档中被引用但磁盘上不存在

- **Severity**：P3（观察名单 — 该文件按 response 文档自身已 W2-延期）
- **Surface**：`docs/governance/dual-track-routing-policy.yaml`（声明为 NEW）
- **Evidence**：`2026-05-22-...response.en.md:194` `| W2 | dual-track-routing-policy.yaml (per-InterruptType policy table) | docs/governance/dual-track-routing-policy.yaml (NEW) |`。Glob 无匹配。
- **What's wrong**：潜伏 — 目前不构成对齐缺陷（文件仅作引用）。一旦 W2 落地 `DualTrackRouter.java` 而未落地配套治理 yaml，将转为对齐缺陷。
- **Suggested fix**：W2 实现 `DualTrackRouter` 时在同 wave 内落地 `dual-track-routing-policy.yaml` 并新增 catalog §3 行。在此之前无动作。

#### AUD-PARITY-4 — `IdempotencyStore` 是位于 `.spi.` 之外的契约主干接口，且无治理链路指向 ADR-0057 的豁免

- **Severity**：P2
- **Surface**：Java 放置 + module-metadata + DFX + catalog
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyStore.java:21` `public interface IdempotencyStore`（在 `service.platform.idempotency` 下，**不**在 `.spi.` 下）。两个实现（`InMemoryIdempotencyStore`、`JdbcIdempotencyStore`）→ 它实质上是一个扩展点。`agent-service/ARCHITECTURE.md:731` 自承 "historical platform interface; not under .spi per Rule R-D.d"。`development.md:44` 重复该豁免备注。Catalog §2 中零 `IdempotencyStore` 行；module-metadata 的 `spi_packages` **不**包含 `service.platform.idempotency`。`spi-appendix.md §1` 列出 9 个 SPI，均不是 `IdempotencyStore`。
- **What's wrong**：阅读 `spi-appendix.md` 的评审者认为 SPI 面是 9；阅读 Java 树的评审者会发现第 10 个扩展点没有治理链路。
- **Suggested fix**：要么 (a) 把 `IdempotencyStore` 迁到 `service.platform.idempotency.spi.` 下，并在 4 个权威面中均注册为第 10 行 SPI；要么 (b) 在 `spi-appendix.md §2 / §3` 显式加一段："IdempotencyStore is an HTTP-edge platform contract intentionally NOT under `.spi.` per Rule R-D.d; it is governed by ADR-0057 not by SPI 4-way parity"，并引用 ADR-0057 豁免子句。

#### AUD-PARITY-6 — `a2a-envelope.v1.yaml:3` 引用不存在的 "future ADR-0105"

- **Severity**：P3
- **Surface**：`docs/contracts/a2a-envelope.v1.yaml`
- **Evidence**：`a2a-envelope.v1.yaml:3` `Authority: ADR-0100 (rc22) + future ADR-0105 (rc25 — A2A contract adoption).`。`a2a-envelope.v1.yaml:19` `promotion_trigger: First A2A interop test lands (future rc25+ wave with separate ADR).`。Glob `docs/adr/0105*.yaml` 无匹配。
- **What's wrong**：指向未实现 ADR 的前向指针。Rule M-2.b 已满足（ADR-0100 存在且被引用），但显式的 "ADR-0105" 编号脆弱。
- **Suggested fix**：把第 3 行改写为 "Authority: ADR-0100 (rc22 — contract-only adoption; promotion to `runtime_enforced` gated on a future ADR)" — 移除具体编号。

#### AUD-PARITY-7 — `agent-invoke-request.v1.yaml` 在 `contract-catalog.md` 与 `spi-appendix.md` 之间状态标签发散

- **Severity**：P3
- **Surface**：`contract-catalog.md` ↔ `spi-appendix.md`
- **Evidence**：`contract-catalog.md` row 162：`agent-invoke-request.v1.yaml ... schema_shipped ... Java carrier records exist and are test-verified`。`spi-appendix.md` 承载表格 line 53 行：`AgentInvokeRequest | service.engine.spi | Immutable service-to-engine invocation carrier (design_only per docs/contracts/agent-invoke-request.v1.yaml — runtime path deferred to ADR-0100)`。catalog 状态正确（`service/engine/spi/AgentInvokeRequest.java` 真实存在）；`spi-appendix.md` 的散文已过时。
- **What's wrong**：两个权威面给出两种不同状态标签。
- **Suggested fix**：在 `spi-appendix.md:53`，把 `design_only per docs/contracts/agent-invoke-request.v1.yaml` 替换为 `schema_shipped per docs/contracts/agent-invoke-request.v1.yaml`。

## 5. Wave-2 — 家族分类（Family Classification）

每条发现（共 39 条：7 条 PR77 继承 + 6 条 PR76 继承 + 32 条新增）在下表中均被打上一个复发缺陷家族标签。其中 26 条归入既有家族；13 条需要 6 个**新**家族。

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

**家族分布**：11 条发现归入 `F-design-only-mechanism-shown-as-shipped`（最大群簇）；7 条归入 `F-cross-authority-agreement`；5 条归入 `F-layer-decomposition-low-cohesion`；5 条归入 `F-authority-surface-path-drift`；4 条归入 `F-discriminator-naming-drift-doc-vs-code`（新）；2 条归入 `F-half-built-state-machine`（新）；2 条归入 `F-create-path-not-enrolled-in-dedup-tx`（新）；`F-vocabulary-identity-collision`（新）、`F-dfa-without-validator`（新）、`F-cross-authority-tenant-scope-claim-without-field`（新）各 1 条，以及若干既有的单发现家族。

## 6. Wave-2 — 兄弟扫描追加（Sibling-Sweep Additions）

为每个新家族派生指纹并在语料库范围内扫描。以下是折入清单的额外同形发现实例。

### 家族 `F-half-built-state-machine` — 指纹：枚举成员在生产中零写入路径

#### SBL-HBSM-1 — `RunStatus.EXPIRED` 是终态 DFA 状态，零生产写入路径

- **Severity**：P1
- **Surface**：Java
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/runtime/runs/RunStatus.java:4` 声明 `EXPIRED`。`RunStateMachine.java:35-40` 允许 `RUNNING/SUSPENDED → EXPIRED`。grep `withStatus(EXPIRED)` 或对 `RunStatus.EXPIRED` 左值赋值 → 零命中。仅 `RunStateMachine.java:35,40` 与 `RunController.java:43,181`（409 分类）有只读引用。
- **What's wrong**：AUD-IDEM-1 的同形 — 已声明的终态在代码中从未到达；deadline-timer 行为属 design_only。
- **Suggested fix**：要么在 `RunStatus.java` Javadoc 中把 `EXPIRED` 标注为 `(W2-deferred — ADR-XXXX deadline timer landing wave)`，要么实现 deadline timer。

#### SBL-HBSM-2 — `SuspendReason.AwaitChild` / `.AwaitTimer` / `.AwaitExternal` / `.AwaitApproval` 在生产中零实例化

- **Severity**：P1
- **Surface**：Java
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/runtime/resilience/spi/SuspendReason.java:76-79` 声明了全部 4 个占位 records。对 `new SuspendReason.AwaitChild(` / `.AwaitTimer(` / `.AwaitExternal(` / `.AwaitApproval(` 的生产搜索返回零命中。仅 `RateLimited` 被构造器调用（1 站点）；`AwaitClientCallback` 在 `SyncOrchestrator.java` 中以常量被引用 8 处，但其构造器 `new SuspendReason.AwaitClientCallback(...)` 也从未被调用。
- **What's wrong**：6 个已声明的 SuspendReason 变体中有 4 个未被触达。结合 AUD-EVT-1 命名漂移发现，`SuspendReason` 分类是语料库中规模最大的半成品生命周期。
- **Suggested fix**：在 AUD-EVT-1 重命名之后，仅保留已接线的构造器站点；其余按所属 ADR（0019 / 0070 / 0074 / 0112）标注为 `(design_only — ADR-XXXX)`。

#### SBL-HBSM-3 — `Task.A2aState` 5 个值 + `Task.TaskKind` 4 个值零生产写入路径

- **Severity**：P2
- **Surface**：Java
- **Evidence**：`Task.java:62-76` 声明 `TaskKind`（INTERACTIVE/BATCH/PERIODIC/DRIFT）与 `A2aState`（SUBMITTED/WORKING/INPUT_REQUIRED/COMPLETED/FAILED）。对 `agent-service/src/main/java/` 中任意这些值的生产写路径搜索返回零命中。
- **What's wrong**：Task 实体存在；无代码写入任何 A2aState 或 TaskKind 值。与 AUD-EVT-5（无校验器）和 AUD-COHES-6（load 签名缺 sessionId）叠加。
- **Suggested fix**：要么交付 `TaskController` + `TaskStateMachine`（impl-mode wave），要么在 `Task.java` Javadoc 中标注为 `(W3+ — Task control surface design_only)`。

#### SBL-HBSM-4 — `PlaceholderPreservationPolicy.WARN` 与 `.REWRITE` 枚举值未被触达

- **Severity**：P3
- **Surface**：Java
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/agent/spi/PlaceholderPreservationPolicy.java:18-19` 声明 `WARN`、`REWRITE`。生产搜索对两者均返回零命中；仅 `PRESERVE` 被引用 1 次。
- **What's wrong**：含 2 个未触达值的判别枚举。
- **Suggested fix**：要么在 prompt-template engine 中实现 WARN/REWRITE 路径，要么在接线之前移除未使用的值。

### 家族 `F-discriminator-naming-drift-doc-vs-code` — 指纹：文档引用的枚举变体 ≠ Java permits 列表

#### SBL-NAME-1 — process.md / scenarios.md 中的 `SuspendReason.AwaitChildren`（复数）vs Java 中的 `AwaitChild`（单数）

- **Severity**：P1
- **Surface**：4+1 视图
- **Evidence**：`docs/L1/agent-service/process.md:171` `throws SuspendSignal(child-run variant)<br/>(SuspendReason.AwaitChildren)`。`docs/L1/agent-service/scenarios.md:83,85` 使用 `AwaitChildren` 与 `SuspendReason.AwaitChildren`。Java 现实：`SuspendReason.java:42,76` 声明单数 `AwaitChild`。
- **What's wrong**：文档复数形式是 AUD-EVT-1 命名漂移记录的第 4 个变体。按用户优先级，文档为准 → Java 重命名目标是 `AwaitChildRun`（既是单数，又具描述性）。文档复数 `AwaitChildren` 与 Java 单数 `AwaitChild` 都应当统一到规范名 `AwaitChildRun`。
- **Suggested fix**：把 Java 的 `AwaitChild` 重命名为 `AwaitChildRun`；把所有文档引用 `AwaitChildren` 改写为 `AwaitChildRun`。

#### SBL-NAME-2 — `2026-05-22-...response.en.md:141` 中三个仅存在于文档的名字在 Java 中不存在

- **Severity**：P1
- **Surface**：权威文档
- **Evidence**：文档第 141 行映射 `SUB_TASK_AWAIT↔AwaitChildRun`、`TOOL_EXECUTION↔AwaitToolResult`、`POLICY_APPROVAL↔RequiresApproval`。Java 现实：零 `AwaitChildRun`、零 `AwaitToolResult`、零 `RequiresApproval` records。
- **What's wrong**：与 AUD-EVT-1 同家族，但出现在不同面（2026-05-22 权威文档）上。这 3 个名字是用户优先级下的规范名 — 问题在于它们目前**仅**存在于文档而尚未落到 Java。
- **Suggested fix**：Wave 3/4 ADR 重命名 + impl-mode 后续 Java 重命名。本同形发现确认 AUD-EVT-1 的修复范围。

### 家族 `F-dfa-without-validator` — 指纹：状态枚举无配套 `*StateMachine` / `*Validator`

#### SBL-DFAW-1 — 重新确认 AUD-EVT-5（Task.A2aState 5 态 DFA 无校验器）

- **Severity**：P1
- **Surface**：Java
- **Evidence**：与 AUD-EVT-5 相同 — 列于此以锚定家族。
- **Note**：非新发现，仅作为家族锚点。

#### SBL-DFAW-2 — `Task.TaskKind` 是类判别枚举（4 个值），无配套路由/派发校验器类

- **Severity**：P3（临界 — TaskKind 更偏结构性而非状态机）
- **Surface**：Java
- **Evidence**：`Task.java:62-66` 声明 `TaskKind` 含 4 个值 + 零生产写路径（详见 SBL-HBSM-3）。无 `TaskKindRouter` / `TaskKindDispatcher` 类存在。
- **What's wrong**：结构性判别枚举无路由面消费它。
- **Suggested fix**：决定 `TaskKind` 是否属 W3+ 范围（在该枚举上标注 design_only），或实现路由器。

### 家族 `F-layer-decomposition-low-cohesion` — 指纹：声明的层所有者包仅含 `package-info.java`

#### SBL-COH-1 — `service.dispatcher/` Layer-1 所有者占位包，零内容

- **Severity**：P2
- **Surface**：Java + 4+1 视图
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/dispatcher/` 仅含一个文件：`package-info.java`。`development.md:82` 把 `service.dispatcher/ (rc22)` 与 `service.platform.web/` 并列声明为 Layer-1 主要所有者。
- **What's wrong**：Layer-1 工作全部位于 `service.platform.*` 下。`service.dispatcher/` 是占位 — 已声明层归属，却无代码。AUD-COHES-1（Layer 5a）与 SBL-COH-2（Layer 4）的同形。
- **Suggested fix**：要么物化 `service.dispatcher/` 的内容（例如把 `RunController` 迁入此处作为 Layer-1 内聚的一部分），要么更新 `development.md:82` 并移除该行。

#### SBL-COH-2 — `service.orchestrator/` Layer-4 所有者占位包，零内容

- **Severity**：P2
- **Surface**：Java + 4+1 视图
- **Evidence**：`agent-service/src/main/java/com/huawei/ascend/service/orchestrator/` 仅含 `package-info.java`。`development.md:85` 把 `service.orchestrator/ (rc22)` 声明为 Layer-4 主要所有者。
- **What's wrong**：实际 Layer-4 工作位于 `service.runtime.orchestration/`。`service.orchestrator/` 是占位。
- **Suggested fix**：要么把 `SyncOrchestrator` 迁入此处（同时关闭 AUD-COHES-1 的部分与本同形），要么从 `development.md:85` 移除该行。

### 兄弟扫描反向确认（Rule G-E 非空守护）

- `F-design-only-mechanism-shown-as-shipped` — 扫描在清单已有 5 条之外返回零个新同形（`AUD-IDEM-2`、`AUD-IDEM-3`、`AUD-IDEM-6`、`AUD-IDEM-7`、`AUD-IDEM-10`、`AUD-COHES-7`、`AUD-PARITY-3`）。反向确认：process.md 中每个 `RR-->>Queue` 箭头均被 ADR-0141 / ADR-0145 的 design_only 标注总括覆盖。
- `F-discriminator-without-discriminated-type` — 扫描返回零个新同形。其他候选判别枚举（`HookPoint`、`SkillKind`、`MemoryCategory`、`ModelFinishReason`、`MemoryOwnership`、`RunMode`、`PlanningStrategy`）的多态承载类型均存在于 `agent-middleware/` 或 `agent-execution-engine/` 中。
- `F-cross-authority-tenant-scope-claim-without-field` — 扫描返回零个新同形。其他声明 tenant 作用域承载字段的 catalog 行（`IngressEnvelope.tenantId`、`AdvisedRequest.tenantId`、`AdvisedResponse.tenantId`）的字段在 Java records 中均存在。
- `F-create-path-not-enrolled-in-dedup-tx` — 扫描在 AUD-IDEM-4（顶层 Run）和 AUD-IDEM-5（子 Run）之外返回零个新同形。当前无 `TaskController` / `SessionController` 存在，因此扫描对 W2/W3 端点空载-但已布防。
- `F-vocabulary-identity-collision` — 扫描返回零个新同形。仅 AUD-IDEM-8（两个 `IdempotencyRecord`）。经 `find agent-service/src/main/java -name "*.java" -printf '%f\n' | sort | uniq -d` 验证，仅返回该一个重复 basename。

## 7. Wave-2 — 新登记的家族（Newly Registered Families）

按 Rule G-9.c 同步在 `docs/governance/recurring-defect-families.yaml` 与 `docs/governance/recurring-defect-families.md` 中登记 6 个新家族。`last_updated:` 同步到 `2026-05-27`，并附本次审计的内容变更负载。

### F-half-built-state-machine
- **title**: Multi-State Enum Declares Lifecycle Members the Code Never Writes
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：一个 enum / sealed marker / hook-point 分类被声明为 N 个成员，表达一个意图中的生命周期。已交付代码仅写入其中 ≤K（K<N）个；其余成员属于设想 / 未来 wave。Javadoc 诚实承认差距，但读者会假设完整生命周期已存活。它是 `F-discriminator-without-discriminated-type` 在值层级（而非类型层级）的同形。
- **surfaces**：`agent-*/src/main/java/**/*Status.java`、`agent-*/src/main/java/**/*State.java`、`agent-*/src/main/java/**/*HookPoint.java`、`agent-*/src/main/java/**/*Reason.java`、`agent-*/src/main/java/**/*Policy.java`、`docs/contracts/engine-hooks.v1.yaml#phase_*_fired*`
- **prevention_rules**：候选 W5+ gate-rule — 对每个位于 `*/spi/*`、`service/platform/*`、`service/runtime/*` 下的 `enum (\w+)` 声明，跑 codegraph 查询 `<EnumName>.<MEMBER>` 写入站点；当某成员存在 0 写入者且无 `(W2-deferred — ADR-NNNN)` javadoc 标记时 FAIL。
- **cleanup_status**：pending
- **open_residual**：AUD-IDEM-1（COMPLETED/FAILED）、AUD-EVT-4（HookPoint.ON_YIELD）、SBL-HBSM-1（RunStatus.EXPIRED）、SBL-HBSM-2（4 个 SuspendReason 变体）、SBL-HBSM-3（Task.A2aState + TaskKind）、SBL-HBSM-4（PlaceholderPreservationPolicy.WARN/REWRITE）。
- **fingerprint**：regex `enum\s+(\w+)\s*\{[^}]+\}` + 逐成员写路径 codegraph_callers 查询。

### F-discriminator-naming-drift-doc-vs-code
- **title**: Doc-Cited Enum / Method / Type Name Drifts From Java Source of Truth
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：活跃文档面引用的枚举变体 / 方法签名 / 类型名与 Java 源不一致 — 大小写错误、被截断、参数个数错、参数类型错，或使用旧命名 wave 的名字。文档对读者说谎；gate 的路径真伪规则能抓到路径漂移，但无法抓到原本可解析引用内部的名称形态漂移。
- **surfaces**：`docs/L1/**/*.md`、`docs/logs/reviews/*.md`、`docs/adr/*.yaml`、`docs/contracts/contract-catalog.md`、`agent-*/ARCHITECTURE.md`
- **prevention_rules**：候选 W5+ gate-rule — 解析活跃 md/yaml 中每一处 `HookPoint.\w+|SuspendReason.\w+|RunStatus.\w+|RunRepository.\w+\(` 提及；经 codegraph 解析；如字面名称不匹配任一已声明成员 / 方法则 FAIL。
- **cleanup_status**：pending
- **open_residual**：PR77-P1-2、PR76-IF-DRIFT-004、AUD-EVT-1、AUD-EVT-3、SBL-NAME-1、SBL-NAME-2。
- **fingerprint**：活跃 md/yaml 中 `\b([A-Z][a-zA-Z]+)\.([a-zA-Z_]+)\b` 经 codegraph_search 解析。

### F-dfa-without-validator
- **title**: Documented State-Machine DFA Ships Without an Enforcement Validator
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：一个状态类型字段拥有文档化 DFA（Mermaid stateDiagram、ADR 转移表、契约 yaml `transitions:` 块），并具名指出特定非法转移。Java 仅交付枚举 + 实体，但没有 `<Type>StateMachine.validate(from, to)` 校验器接入持久化写路径。同模块的 `RunStateMachine` 证明该模式存在；同形 DFA 无保护。
- **surfaces**：`agent-*/src/main/java/com/huawei/ascend/service/*/spi/*.java`（枚举宿主）、`docs/L1/**/*.md`（stateDiagram 块）、`docs/contracts/*.v1.yaml`
- **prevention_rules**：候选 W5+ gate-rule — 对 `docs/L1/**/*.md` 中每个引用 Java 枚举的 Mermaid `stateDiagram-v2` 块，在同模块下 codegraph 搜索 `validate\s*\(\s*<EnumName>`；零结果时 FAIL。
- **cleanup_status**：pending
- **open_residual**：AUD-EVT-5、SBL-DFAW-1、SBL-DFAW-2。
- **fingerprint**：stateDiagram 块 ↔ codegraph_search 查询枚举上的 validate 方法。

### F-create-path-not-enrolled-in-dedup-tx
- **title**: Resource-Create Path Bypasses the Idempotency-Claim Transaction
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：HTTP endpoint 或 orchestrator 在外围请求带有 `Idempotency-Key` header 或 suspend/resume 信号携带 `parentNodeKey` 的情况下，用 `new T(UUID.randomUUID(), ...); repository.save(t);` 创建顶层 / 子资源。dedup-claim 行由不同事务中的 filter / pre-step 写入；若 claim 返回 "fresh"，create 无条件发生。TTL 二次 claim 或 suspend-resume 重入将产生重复资源 — "successful claim ⇒ at-most-one resource" 契约被静默违反。
- **surfaces**：`agent-service/src/main/java/com/huawei/ascend/service/platform/web/**/*Controller.java`、`agent-service/src/main/java/com/huawei/ascend/service/runtime/orchestration/inmemory/SyncOrchestrator.java`、`agent-service/src/main/java/com/huawei/ascend/service/platform/idempotency/IdempotencyHeaderFilter.java`
- **prevention_rules**：候选 W5+ gate-rule — 对 `IdempotencyHeaderFilter.claimOrFind` 做 codegraph_callers；对每个 endpoint，沿 callees 寻找 `RunRepository`/`TaskStateStore`/`SessionRepository` 上的 `\.save\(`；当 save 未在事务中（无 `@Transactional` 传播、无确定性 uuid 派生）时 FAIL。
- **cleanup_status**：pending
- **open_residual**：AUD-IDEM-4、AUD-IDEM-5。
- **fingerprint**：从 `*Controller` 处理器或 `SuspendSignal.forChildRun` 恢复点可达的方法中 `UUID\.randomUUID\(\)\s*;\s*(\w+\.)?save\(`。

### F-vocabulary-identity-collision
- **title**: Two Same-Named Java Types Live in Different Packages of One Module
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：同一 Maven 模块的不同子包中两个 `record` / `class` / `interface` 声明共享相同的简单名；一个是活承载，另一个是死代码 / 重命名前残留。两者 javadoc 都引用同一 ADR 权威。Catalog / SPI Appendix 把评审者指向了错误文件。IDE 补全选错 import。与跨模块的"已删除名字泄漏"和路径漂移不同 — 这里模块正确，但 basename 被双重解析。
- **surfaces**：`agent-*/src/main/java/com/huawei/ascend/**/*.java`、`docs/L1/**/spi-appendix.md`、`docs/contracts/contract-catalog.md`
- **prevention_rules**：候选 W5+ gate-rule — 对每个 Maven 模块，glob `src/main/java/**/*.java` → 抽取 basename → 任何重复 basename 时 FAIL。合法场景（`package-info.java`）列入白名单文件。
- **cleanup_status**：pending
- **open_residual**：AUD-IDEM-8（两个 `IdempotencyRecord`）。
- **fingerprint**：`find <module>/src/main/java -name "*.java" -printf '%f\n' | sort | uniq -d`。

### F-cross-authority-tenant-scope-claim-without-field
- **title**: Authority Surface Claims a Tenant-Scope Field That Does Not Exist on the Carrier Java Type
- **first_observed_rc**: audit-2026-05-27
- **occurrences**: [audit-2026-05-27]
- **root_cause**：`docs/contracts/contract-catalog.md`（或 `agent-*/ARCHITECTURE.md` §SPI Appendix）通过 "tenant resolved by `<Carrier>.tenantId` field (Rule R-C.c)" 把结构性承载声明为 `tenant-scoped` — 但具名字段在承载 record 上并不存在。诚实的 Java javadoc 承认 tenant 解析是带外的，于是在 catalog 行与类型之间形成直接谎报。Rule G-8.e 强制结构性承载的包与类存在，但不查字段级声明。它是 `F-design-artifact-omits-tenant-spine`（图省略 tenantId）的同形；此处图 / catalog 反过来**主张**了一个不存在的 tenantId。
- **surfaces**：`docs/contracts/contract-catalog.md`、`agent-*/ARCHITECTURE.md` SPI Appendix、`docs/L1/**/spi-appendix.md`
- **prevention_rules**：候选 W5+ gate-rule — 解析匹配 `tenant.scoped.*<Carrier>\.tenantId` 的 catalog 行 → 经 codegraph_node 解析 `<Carrier>` → 当类型无 `tenantId` record 组件 / 字段时 FAIL。
- **cleanup_status**：pending
- **open_residual**：AUD-EVT-6。
- **fingerprint**：在 `docs/contracts/contract-catalog.md` 中 rg `tenant.{0,12}scoped.*?([A-Z][A-Za-z]+Envelope|...)\.tenantId` → 解析类型 → 检查已声明组件。

## 8. 横切反思 — L1 设计已经获准了吗？

按用户简报 — 以 2026-05-22 文档（line 30："accepted as L1 direction, formally codified in ADR-0115"）的裁定与 Wave 1 §11 的 6-G-gate 受理标准为衡量 — agent-service L1 4+1 已**方向性获准**，但**尚未达到运行一致**。

**已获准（通过审计）：**

- 五层模型（Access / Session-Task / Internal Event Queue / Task-Centric Control / Engine Adapter）在 `logical.md §1` 与 `2026-05-22 §3` 中命名一致。
- 9 个 SPI 接口分布于 7 个包 — 头条计数的四方对齐成立于 `module-metadata.yaml`、`docs/dfx/agent-service.yaml`、`contract-catalog.md` 与 Java 文件系统之间（由维度 4 的 AUD-PARITY-12、-13、-14 验证）。
- Run 聚合在变更上的单一所有者 pinning 已交付（每一处 `Run.with*` lambda 都通过 `RunRepository.updateIfNotTerminal` CAS）。
- tenant-id 是 `Run` / `Task` / `Session` Java records 的一等字段（Rule R-C.2.a 已校验）。
- 三轨总线绑定（Rule R-E）在 `bus-channels.yaml` 与 `physical.md §3` 中正确声明。
- A2A 正确地仅作为协议边界界定（R7）；无 `a2a-java` 运行时依赖。
- Edge↔compute_control 隔离（Rule R-I.1）经 `EdgeToComputeDirectLinkArchTest` 已布防（`agent-client` 空时为空载-但通过）。

**尚未达到运行一致（阻塞 L1 获准声明）：**

- **AUD-COHES-1**（P0）：Layer 5a 交付在错误包；ADR-0140 拆分仅止于文档。
- **AUD-EVT-1**（P0）：`SuspendReason` 命名与 2026-05-22 映射表在 5 个名中有 3 个不一致。
- **AUD-EVT-6**（P0）：`contract-catalog.md` 声明 `S2cCallbackEnvelope` 上不存在的 `tenantId` 字段。
- **AUD-EVT-2**（P1）：RunEvent 密封层级 + 10 变体仍是 design_only；`EvolutionExport` 判别枚举孤悬交付。
- **AUD-IDEM-1/-2/-3/-5/-6/-7**（P1×6）：幂等面是教科书级 `F-design-only-mechanism-shown-as-shipped` 堆积 — replay、S2C 去重、outbox 事件发出、子运行去重全部在文档 / 契约中接线，却在 `agent-service/src/main/java` 中缺席。
- **AUD-EVT-5**（P1）：`Task.A2aState` DFA 无校验器类。
- **AUD-PARITY-1**（P1）：陈旧的 `TaskRepository` 标签在 `ARCHITECTURE.md` 残留。

审计裁定：**L1 在以下 11 个 P0+P1 发现关闭前为暂行获准**。Wave 4 关闭文档侧；Wave 3 调和 ADR；impl-mode 后续 wave 关闭 Java 侧。

## 9. 建议修复顺序

第 1 组（Wave 3 — ADR yaml 编辑）：

1. **ADR-0019 / ADR-0070 / ADR-0112** — 按 2026-05-22:141 映射重命名 `SuspendReason` 变体（AwaitChild→AwaitChildRun、AwaitExternal→AwaitToolResult、AwaitApproval→RequiresApproval）。把 `AwaitClientCallback` 标为 ADR-0074 所有（已规范）。
2. **ADR-0140** — 在 `decision:` 块中澄清 5a/5b 拆分是结构性（代码包边界）还是逻辑性（仅分类学）。按 AUD-COHES-1，结构边界意图优选；当前代码违背该意图。
3. **ADR-0145** — 确认 RunEvent 密封层级设计并定下目标 impl-mode wave。目前缺席或 design_only；本次审计要求显式的 impl-mode 落地目标。
4. **ADR-0057** — 新增显式 `consequences:` 块，澄清 L1 仅交付 claim；COMPLETED/FAILED + responseStatus/responseBodyRef 属 W2；body-drift 检测限定为单 TTL 窗口范围。
5. **ADR-0100** — 调和 §non_goals "single-interface decision" 与当前 `StatelessEngine` + `EngineRegistry/ExecutorAdapter` 并存的现实。（可能通过澄清性 ADR 取代。）

第 2 组（Wave 4 — 4+1 视图编辑）：

6. `logical.md` — 修复 `TASK ||--|| RUN` ER（PR77-P1-1）；把 `updateIfNotTerminal` Layer 4 散文对齐到 lambda-supplier 对 CAS-applier 词汇（AUD-COHES-5）；添加 `FAILED → RUNNING` 重试边（AUD-EVT-7）；把 §6 ChatAdvisor 行标记为 design_only（AUD-COHES-9）；视觉降级 `queue_layer` mermaid 子图（AUD-COHES-7）。
7. `process.md` — 修复 `updateIfNotTerminal(tid, runId, λ)` 签名漂移（PR77-P1-2）；修复 SSE W2-shipped（PR77-P2-2）；对所有 Queue 发出统一应用 `(design_only — ADR-0141)` 标注（AUD-IDEM-7、AUD-IDEM-10）；移除/标注 "200 cached response" alt（AUD-IDEM-3）。
8. `physical.md` — 修复 Task/Session ID UUID→String（PR77-P1-3），或排定显式的 Flyway-UUID-migration ADR。
9. `scenarios.md` — 修复 `tenantMismatchReturns403` → `getCrossTenantRunReturns404`（PR77-P2-1）；添加子派生幂等性备注（AUD-IDEM-5）。
10. `README.md` — 修复 front-matter（PR77-P2-3）：声明全 4+1 覆盖；更新 wave 状态表以反映视图完成度。
11. `spi-appendix.md` — 把 `agent-invoke-request.v1.yaml` 状态修为 `schema_shipped`（AUD-PARITY-7）；把 `IdempotencyRecord` 引用更新到 platform 包（AUD-IDEM-8）；添加 IdempotencyStore 豁免段落（AUD-PARITY-4）。
12. `agent-service/ARCHITECTURE.md` — 把 `TaskRepository` → `TaskStateStore`（PR77-P2-4 / AUD-PARITY-1）；把 `7-interface` → `9-interface`（AUD-PARITY-2）。
13. `docs/contracts/contract-catalog.md` — 修复 `S2cCallbackEnvelope.tenantId` 声明（AUD-EVT-6） — 要么承诺加上该字段，要么改写为带外注册解析措辞。
14. `docs/contracts/a2a-envelope.v1.yaml:3` — 删除 "ADR-0105" 前向编号（AUD-PARITY-6）。

第 3 组（impl-mode 后续，**不在**本 wave）：

- 实现 `RunEvent.java` 密封接口 + 10 个 record 变体（关闭 AUD-EVT-2）。
- 实现 `Task.A2aStateMachine` 校验器（关闭 AUD-EVT-5）。
- 把 Layer-5a 执行器迁入 `service.engine.adapter/`（关闭 AUD-COHES-1 + AUD-COHES-3）。
- 物化 `dual-track-routing-policy.yaml` + `DualTrackRouter` Java 类（关闭 AUD-PARITY-3 观察名单）。
- 把 `IdempotencyStore.claimOrFind` 接入 `S2cCallbackTransport.dispatch`（关闭 AUD-IDEM-6）。
- 接入 response-replay completion hook（关闭 AUD-IDEM-1 + AUD-IDEM-2）。
- 重命名 `SuspendReason` records（关闭 AUD-EVT-1 Java 侧）。
- 新增按会话作用域的 `TaskStateStore.load(taskId, sessionId, tenantId)` 重载（关闭 AUD-COHES-6）。

## 10. Waves 1+2 关闭标准

Wave 1：
- 已撰写 32+ 条带 `file:line` 证据、严重程度、surface 标签与建议修复的发现。✓
- 横切反思给出诚实的 "L1 已获准？" 裁定。✓
- 自 PR #76 / PR #77 继承的发现被引用（未重新编号）。✓
- 文档通过对内部矛盾的人工扫描。（commit 前验证）

Wave 2：
- 每条发现均带 `family:` 标签（39 / 39）。✓
- 6 个新家族按 G-9.a 的 9 个字段完整登记。✓
- 每个新家族均携带 fingerprint。✓
- 兄弟扫描追加 10 条同形发现（SBL-HBSM-1..4、SBL-NAME-1..2、SBL-DFAW-1..2、SBL-COH-1..2），已折入清单。✓
- 5 个家族返回反向确认行（Rule G-E 非空守护）。✓
- `recurring-defect-families.yaml` 可干净解析（编辑后验证）。（Wave 5 commit 准备时验证）
- `recurring-defect-families.{yaml,md}` 家族 id 对齐成立（Rule G-9.c）。（Wave 5 commit 准备时验证）

## 11. 验证

- 端到端阅读本文档。确认每条发现的证据都解析到所引用 `file:line` 在 `main` HEAD `c93c2fd` 的位置。
- 随机抽样 5 条发现并重新核对（PR #76 / #77 评审模式）。
- Wave 2 将为每条发现打上家族标签并跑同形扫描。
- Wave 3 的 ADR 编辑与 Wave 4 的 4+1 补丁将在 commit 消息中携带 `AUD-<N>` 反向引用。

## 12. 备注

本 wave 仅撰写发现；Wave 1 不落地任何纠错编辑。下一 wave 在触动任何 ADR 或 4+1 视图前先做分类 + 同形扫描。
