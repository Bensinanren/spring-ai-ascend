---
artifact_type: delivery_projection
version: agent-bus-stage9-review-and-stage10-plan
status: draft
source_commit: c532b082
source_stage9_plan: docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage8-review-and-stage9-plan.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
target_module: agent-bus
---

# agent-bus Stage 9 评审与 Stage 10 计划

## 0. 结论

最新提交 `c532b082` 可以作为 Stage 9 的阶段性成果接受：lease-owner guarded mutation、lease 生命周期闭环、record 条件不变量（Java + DDL CHECK + harness）、failure-code classification、claim / state-update SQL contract 全部落地，**129 tests green**，并严格守住路径 B（不引入 JDBC / Flyway）。Stage 9 把 Stage 8 的「持久化准备」补齐为「并发安全、约束完整、可接真实持久化 adapter 的 C3 底座」。

但 Stage 9 的 lease guard 暴露了 worker 运行态的下一步硬约束：lease guard 会抛 `ForwardingLeaseException`，而 `ForwardingLeaseException` 的 javadoc 已承诺「worker 把任何 ForwardingLeaseException 当作 skip this record」——但 worker 代码尚未兑现（`runOnce` 的 per-record 处理没有 try-catch，异常会中断整个 tick）；worker 也没有 lease 续约，长 deliver 超过 lease TTL 会丢 lease 导致 ACK 失败。Stage 10 必须先把 worker 从 Stage 8 的 skeleton 推进为「正确处理 lease 生命周期」的可运行 dispatch loop，再决定是否接真实 DB / delivery 绑定。

简短判断：

- Stage 9 方向正确，lease-safe 的硬约束（guard + 不变量 + SQL contract）已到位，是接真实 JDBC adapter 的可靠前置。
- worker 运行态仍是 Stage 8 的 skeleton：lease guard 的异常路径与续约还没接入，真实并发下一次 lease 竞态就会让 worker tick 中断。
- Stage 10 应聚焦 worker 运行态完善（lease 异常恢复 + 续约 + 调度责任 + 可观测），DB / migration 归属作为决策点由人类确认，不阻塞 worker 工作。

## 1. 本次提交审查

### 1.1 完成情况

本次提交（`c532b082`，rebase 整合远端 agent-runtime / a2d 文档后 fast-forward 推送）完成：

- MI9-001 lease-owner guarded mutation：`ForwardingOutboxPort` 四方法带 `leaseOwner`；`markDispatching` 移除（DISPATCHING 只经 `claimDue` 进入）；新增 `ForwardingLeaseException`（RECORD_NOT_FOUND / NO_LEASE / OWNER_MISMATCH / NOT_DISPATCHING）；worker 传 `leaseOwner`；`InMemoryForwardingOutbox.leaseGuardedMutate` 按序校验。
- MI9-002 lease 生命周期闭环：ACKED / DLQ / EXPIRED / RETRY_SCHEDULED 清 lease，仅 DISPATCHING 持 lease。
- MI9-003 record 条件不变量：`ForwardingOutboxRecord` / `ForwardingInboxRecord.validateStatusInvariants` 固化 per-status 规则，进入 DDL CHECK 与 harness。
- MI9-004 failure-code classification：`ForwardingFailureCode.Classification`（retryable / non-retryable / dedup）；`retry(...)` 只接 retryable，`dlq(...)` 拒 dedup。
- MI9-005 `targetServiceId` 来自 discovery metadata，不解包 routeHandle。
- MI9-006 DDL CHECK + claim / state-update SQL contract。
- 路径 B：不引入 JDBC / Flyway；DDL / SQL 仍 contract / draft；in-memory lease-guard harness 作行为替身。
- 文档同步：L1 × 4 + L2 × 2 + ICD + yaml + review packet（`decision.md §8`）+ stage9-plan §7 执行记录。

验收判断：

- MI9-001..006 全部收口，129 tests green（`AgentBusForwardingRuntimeContractTest` 29 个，含 7 个 Stage 9 行为测试）。
- 生产代码仍纯 Java，无 JDBC / broker（`AgentBusForwardingSpiPurityTest` / `AgentBusDependencyBoundaryTest` green）。
- lease-safe 的并发语义有 harness 覆盖（stale-worker reclaim 竞态：`stale_worker_acks_after_lease_reclaimed_by_another_owner_fails`）。
- record 不变量三重锁（Java 构造器 + DDL CHECK + harness）。
- 仍需 Stage 10 把 lease guard 接入 worker 运行态（异常恢复 + 续约），否则真实并发下一次 lease 竞态就会让 worker tick 中断。

## 2. 当前修改意见

| 编号 | 问题 | 严重度 | 证据 | 修改意见 |
|---|---|---|---|---|
| MI10-001 | worker 未实现 `ForwardingLeaseException` 的 skip 语义：lease guard 抛异常时，worker 直接传播，中断整个 tick，而非跳过该 record 继续。 | 高 | `ForwardingDispatcherWorker.runOnce` 行 85-107 的 switch 直接调 `markAcked` / `scheduleRetry` / `moveToDlq` / `markExpired`，per-record 处理无 try-catch；`ForwardingLeaseException` javadoc 明确「worker 把任何 ForwardingLeaseException 当作 skip this record」但代码未兑现。真实 JDBC 下 lease 被 reclaim 后 ACK 抛 OWNER_MISMATCH，整个 tick 中断。 | Stage 10 在 per-record 处理外包 try-catch `ForwardingLeaseException`，skip 该 record（不计入 acked / retried / dlqd / expired，计入 skipped），tick 继续处理其余 record。 |
| MI10-002 | worker 没有 lease 续约：deliver 耗时超过 lease TTL 时，lease 过期被 reclaim，ACK 失败。 | 高 | `runOnce` 行 78-79 `claimDue` 后 deliver 期间不调 `renewLease`；`ForwardingOutboxClaimPort.renewLease` 已存在但 worker 未调用。 | Stage 10 定义 deliver 与 lease TTL 的契约（deliver 必须 < TTL，或在 deliver 前 / 中 `renewLease`）；in-memory 用 fake-slow-delivery 覆盖续约路径。 |
| MI10-003 | `DispatchTickResult` 缺少 skipped / lease-conflict 可观测：实现 skip 后结果不可观测，运维无法判断多少 record 因 lease 竞态被跳过。 | 中 | `DispatchTickResult(claimed, acked, retried, dlqd, expired)` 无 skipped；行 108 `claimed.size()` 与各计数无一致性校验。 | Stage 10 加 `skipped` 字段，并校验 `claimed == acked + retried + dlqd + expired + skipped`，保证 tick 计数自洽、可观测。 |
| MI10-004 | dispatch 调度责任未定义：`runOnce` 是单次 tick，谁驱动循环、idle backoff、并发 worker 分片未定义。 | 中 | `runOnce` javadoc 说「真实 polling cadence / threading deferred」，但没有契约定义调用方如何驱动 tick、idle 时如何退避、并发 worker 如何避免抢同一条。 | Stage 10 明确 `runOnce` 的调用契约（tick trigger 注入、idle backoff、并发分片策略），可选交付纯 Java `DispatchLoop` 骨架（trigger 由调用方注入，不接真实 scheduler）。 |
| MI10-005 | DB / migration 归属仍 deferred（路径 B），真实 JDBC adapter / Flyway / delivery 绑定未落地。 | 决策 | Stage 9 §11 + `decision.md §8` 路径 B；`agent-bus/pom.xml` 仍无 JDBC / Flyway。 | Stage 10 由人类确认 DB 产品 + migration 归属（agent-bus 自有 vs 共享 schema 模块 vs runtime 受控路径）；若确认 → 路径 A（真实 JDBC adapter + Flyway，production dependency 显式进入 module metadata / forbidden review）；若未确认 → 继续路径 B 并完成 worker 运行态。 |

## 3. Stage 10 目标

Stage 10 的目标是把 Stage 9 的 lease-safe 底座**接入 worker 运行态**，使 dispatcher worker 从 skeleton 推进为「正确处理 lease 生命周期」的可运行 dispatch loop，并裁决 DB / migration 归属：

> 完成 worker lease 异常恢复（catch `ForwardingLeaseException` + skip）、lease 续约契约、dispatch 调度责任定义、`DispatchTickResult` 可观测性，裁决 DB / migration 归属（路径 A 真实 JDBC 或继续路径 B），并在允许范围内交付最小持久化 adapter skeleton 或等价运行态契约。

Stage 10 仍应作为较大批次执行，但顺序必须清楚：先把 worker 的 lease 生命周期跑通（异常恢复 + 续约 + 可观测 + 调度契约），再接真实 DB / migration（若人类确认）。

## 4. Stage 10 开发切片

### 切片 1：worker lease 异常恢复 + skipped 可观测

把 `ForwardingLeaseException` 的 skip 语义接入 worker（兑现 javadoc 承诺）：

- `runOnce` 的 per-record 处理（deliver → mark*）外包 try-catch `ForwardingLeaseException`：捕获后 skip 该 record，tick 继续其余 record。
- `DispatchTickResult` 增 `skipped` 字段，并校验 `claimed == acked + retried + dlqd + expired + skipped`。

DoD：

- worker 在 lease 竞态下不中断 tick，被 reclaim 的 record 计入 skipped。
- harness 覆盖「worker-1 claim → worker-2 reclaim → worker-1 runOnce 不抛、record skipped、worker-2 后续 tick 成功 ack」。
- `DispatchTickResult` 计数自洽（claimed = 各分项之和）。

### 切片 2：worker lease 续约契约

定义 deliver 与 lease TTL 的关系，避免长 deliver 丢 lease：

- 文档化契约：`deliver` 必须在 `leaseUntilMillisEpoch` 之前完成；若预期超过，worker 在 deliver 前（或长 deliver 中）调 `claimPort.renewLease(...)` 延长 lease。
- worker 在 deliver 前检查 lease 剩余时间，不足则 renew（可配置续约阈值）。
- in-memory 用 fake-slow-delivery（推进 `now`）覆盖续约路径：deliver 耗时逼近 TTL 时 worker renew，ACK 成功；不续约则 ACK 失败为 OWNER_MISMATCH。

DoD：

- worker 在长 deliver 场景下不丢 lease（续约）或明确失败（不续约且超 TTL）。
- 契约写入 `forwarding-outbox-inbox.md`（worker 调度段）与 `ForwardingDispatcherWorker` javadoc。

### 切片 3：dispatch 调度责任定义

明确 `runOnce` 的调用契约，消除「谁驱动 tick」的悬置：

- 文档化调用方责任：注入 tick trigger（scheduler / 定时器 / 外部驱动）、idle backoff（无 due record 时如何退避）、并发 worker 分片（避免多 worker 抢同一条——claim lease 已防重复，但分片减少空 claim）。
- 可选：纯 Java `DispatchLoop` 骨架（消费一个注入的 `tick trigger`，循环 `runOnce`，idle backoff 由策略注入；不接真实 scheduler / 线程池）。

DoD：

- `runOnce` 调用契约写入 L2 / javadoc。
- 若交付 `DispatchLoop` 骨架，它不引入 scheduler / 线程依赖（trigger 注入），并被 harness 覆盖。

### 切片 4：DB / migration 归属裁决

由人类确认（Stage 9 §5 遗留决策点）：

- 数据库产品：是否采用 Postgres 作为 C3 outbox / inbox 第一实现。
- migration 归属：`agent-bus` 自有 Flyway vs 共享 schema 模块 vs `agent-runtime` 受控路径。
- RLS：是否启用 Postgres RLS（`forwarding-persistence.md §7.3`）。
- 测试方式：Testcontainers / embedded DB / SQL contract test。

路径 A（确认）：

- 新增 `JdbcForwardingOutboxRepository` 或等价 adapter，用受治理的 transaction / CAS 实现 claim + lease-guarded mutation（编码 `WHERE lease_owner = ? AND lease_until > now()`）。
- Flyway migration 落入受治理路径；production dependency 显式进入 `agent-bus/pom.xml`、`module-metadata.yaml`、forbidden dependency / ArchUnit 规则、L1 development view。
- 不接真实 receiver transport；不写 Task execution state；不绕过 routeHandle。

路径 B（未确认）：

- 不引入 JDBC / Flyway；继续 in-memory lease-guard harness + SQL contract。
- Stage 10 仍必须完成切片 1-3（worker 运行态）。

DoD：

- 决策写入 `forwarding-persistence.md`、`decision.md`、本 plan。
- 若路径 A，更新 pom / module metadata / ArchUnit / L1。

### 切片 5：文档同步

同步：

- `architecture/L1-High-Level-Design/agent-bus/README.md`
- `architecture/L1-High-Level-Design/agent-bus/development.md`
- `architecture/L1-High-Level-Design/agent-bus/process.md`
- `architecture/L1-High-Level-Design/agent-bus/physical.md`
- `architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md`
- `architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md`
- `docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md`
- `docs/architecture/l0/05-contracts/machine-readable/agent-bus-forwarding-runtime.v1.yaml`
- `docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md`

同步重点：

- Stage 10 是 dispatch-loop runtime（lease-safe 运行态），不要宣称完整生产转发 / 真实投递已落地（除非路径 A 的 JDBC adapter 落地，且仍不接真实 transport）。
- 若路径 B，文档继续标注 DDL / SQL 为 contract / draft。
- 若路径 A，文档写清 migration 归属、事务边界、回滚策略、运行时开关。

## 5. Stage 10 可接受结果

可以接受：

- worker lease 异常恢复完成（catch `ForwardingLeaseException` + skip），tick 不因 lease 竞态中断。
- worker lease 续约契约明确，长 deliver 不丢 lease（或明确失败）。
- `DispatchTickResult` 可观测（skipped + 计数自洽）。
- dispatch 调度责任定义清晰（`runOnce` 调用契约）。
- DB / migration 归属有明确裁决（路径 A 或 B）；若路径 A，JDBC adapter + Flyway 落地且 lease guard 被编码进 SQL。

不能接受：

- worker 仍直接传播 `ForwardingLeaseException`，tick 中断。
- 长 deliver 丢 lease 后静默失败（无续约契约、无可观测）。
- `DispatchTickResult` 计数不自洽、skipped 不可观测。
- 在 worker 运行态未跑通前就接真实 DB（顺序错）。
- 把 dispatch loop 接到真实 scheduler / transport（超出 Stage 10）。
- 让 `agent-bus` 写 Task execution state；绕过 routeHandle；放 payload body。

## 6. 给施工智能体的提示

这轮任务应继续是大批次，但不要把「接真实 DB / scheduler」放在 worker 运行态之前。推荐一次提交包含：

1. worker lease 异常恢复（catch + skip）+ `DispatchTickResult.skipped`。
2. worker lease 续约契约。
3. dispatch 调度责任定义（+ 可选 `DispatchLoop` 骨架）。
4. DB / migration 归属裁决。
5. L1 / L2 / ICD / yaml / decision 同步。

如果 DB / migration 归属没有得到人类确认，则 Stage 10 仍然必须完成 1-3（worker 运行态），不应回退成纯文档阶段。worker 运行态是 Stage 9 lease guard 的必要补充——guard 会抛异常，worker 必须正确处理，否则真实并发下第一次 lease 竞态就中断投递。

测试基线：当前 129 tests green；Stage 10 完成后应保持 green 并新增 worker 运行态 harness（lease-skip、续约、计数自洽）。构建命令见 `build-env-maven-via-settings-xml`（system mvn + settings.xml + Red Hat JDK 21）。

## 7. 执行记录

Stage 10 已按 §4 切片执行完成（dispatch-loop runtime）：

- **切片 1（MI10-001）**：`ForwardingDispatcherWorker.runOnce` 的 per-record 处理（deliver → mark*）外包 try-catch `ForwardingLeaseException`，捕获后 skip 该 record；`DispatchTickResult` 增 `skipped` 字段并校验 `claimed == acked + retried + dlqd + expired + skipped`，tick 不因 lease 竞态中断。harness：`worker_skips_record_when_lease_reclaimed_mid_tick`。
- **切片 2（MI10-002）**：`DispatchLeasePolicy` record（`renewBeforeExpiryMillis` / `leaseExtensionMillis`），deliver 前检查剩余 lease TTL，低于阈值则 `claimPort.renewLease(...)`；renew 返回 false（lease 被 reclaim / 不再 DISPATCHING）同 skip；不续约且超 TTL 则 ACK 失败为 lease guard 拒绝。harness：`worker_renews_short_lease_before_delivery` / `worker_does_not_renew_when_lease_sufficient` / `worker_skips_when_lease_renew_fails`。
- **切片 3（MI10-004）**：`ForwardingDispatchLoop` 纯 Java 骨架（`TickSource` / `IdleStrategy` 注入，无 clock / scheduler / 线程），聚合 `DispatchTickResult` 满足与单 tick 相同的自洽不变量。harness：`dispatch_loop_drives_ticks_from_injected_source_until_it_stops`。
- **切片 4（MI10-005）**：DB / migration 归属经人类再确认为 **路径 B**（不引入 JDBC / Flyway）；DDL / SQL 仍 contract / draft；真实持久化 deferred 后续阶段。
- **切片 5**：L1 × 4（README / development / process / physical）+ L2 × 2（forwarding-outbox-inbox / forwarding-persistence）+ ICD + yaml + decision.md §8 + 本 plan §7 执行记录同步。

> MI10-003（`DispatchTickResult` 可观测）由 MI10-001 的 `skipped` 字段 + 自洽校验收口，不单列行为测试。

验收：

- **134 tests green**（`AgentBusForwardingRuntimeContractTest` 34 个：29 基准 + Stage 10 worker_skips + 3 续约 + dispatch_loop），`AgentBusForwardingSpiPurityTest` / `AgentBusDependencyBoundaryTest` green。
- 生产代码仍纯 Java（无 JDBC / broker client / scheduler / 线程），由 ArchUnit 强制。
- 路径 B：不引入 JDBC / Flyway；DDL / SQL 仍 contract / draft；in-memory lease-guard harness 作行为替身。

后续 deferred：真实持久化实现（JDBC adapter / Flyway migration 归属 / lease store 物理实现 / polling / 并发抢占原语 / backpressure 参数 / 真实投递绑定 / 接入 agent-runtime 受控调用路径 / 数据库产品 + RLS 确认）。
