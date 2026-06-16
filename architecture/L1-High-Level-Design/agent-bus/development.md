---
level: L1
module: agent-bus
view: development
status: active
---

# agent-bus 开发视图

## 1. 代码结构

当前 `agent-bus` 的开发结构以 SPI 包为中心：

```text
agent-bus/
  module-metadata.yaml
  pom.xml
  src/main/java/com/huawei/ascend/bus/
    forwarding/
      spi/        # C3 转发运行态领域模型 + 端口 + record 模型 + claim/lease + delivery（Stage 7 + Stage 8，纯 Java）
      runtime/    # C3 转发状态机 + dispatcher worker + dispatch loop（Stage 7 + Stage 8 + Stage 10，纯 Java）
    spi/
      engine/
      federation/
      ingress/
      s2c/
  src/test/java/com/huawei/ascend/bus/
    forwarding/
      test/       # in-memory 测试替身（non-production）
    architecture/  # contract + purity harness
    spi/
      engine/
      s2c/
```

> 命名说明：本文架构语义（所有权）使用 L0 逻辑名 `agent-runtime`（已落地为同名模块，原 `agent-service` 已重命名）；forbidden dependencies 列表与 runtime 构造点引用使用当前 artifact 名。完整映射见 [`README.md`](README.md)「命名说明」。

## 2. 包职责

| 包 | 职责 | 成熟度 |
|---|---|---|
| `bus.spi.ingress` | C2S 入口 envelope、response、gateway | SPI 已存在，测试待补 |
| `bus.spi.s2c` | S2C callback envelope、response、transport、reflection router | SPI 已存在，S2C tenant 已迁移，runtime 构造点待后续波次 |
| `bus.spi.federation` | 跨网络 federation gateway | SPI 已存在，运行时实现待定 |
| `bus.spi.engine` | service-engine 中立执行边界和相关基础类型 | SPI 已存在，被 engine/service 消费 |
| `bus.forwarding.spi` | C3 转发运行态领域模型（envelope / route handle / status / failure code / receipt）+ outbox / inbox / dispatcher 端口；Stage 8 增 record 模型（outbox / inbox / lease）+ claim / lease 端口 + delivery 端口 | 纯 Java 已落地（Stage 7 + Stage 8），真实 JDBC 持久化 deferred Stage 9+ |
| `bus.forwarding.runtime` | C3 转发状态机（outbox / inbox 转换表）+ Stage 8 dispatcher worker（claim / deliver / ack / retry）+ Stage 10 dispatch loop（lease 异常恢复 / 续约 / 调度责任，`TickSource` / `IdleStrategy` 注入） | 纯 Java 状态机 + worker + dispatch loop 已落地，真实投递绑定 deferred 后续阶段 |

## 3. 依赖规则

`agent-bus` 生产代码允许：

- Java 标准库。
- `agent-bus` 内部 sibling SPI 类型。

`agent-bus` 生产代码禁止：

- 依赖 `agent-runtime`。
- 依赖 `agent-execution-engine`。
- 依赖 `agent-client`。
- 依赖 `agent-middleware`。
- 依赖 `agent-evolve`。
- 在 SPI 包中引入 Spring、Reactor、Jackson、HTTP framework 或 broker runtime。

## 4. 测试现状

| 测试 | 覆盖 | 缺口 |
|---|---|---|
| `S2cCallbackEnvelopeLibraryTest` | S2C envelope 基础字段和 trace 校验 | tenantId required-field harness 已补齐 |
| `SuspendSignalTest` / engine 相关测试 | engine/suspend 基础语义 | 需要确认 terminal event harness |
| ingress 测试 | 暂缺 | 需要补 required fields、trace、tenant、response status |
| federation 测试 | 暂缺 | 需要补 broker-agnostic 和 ingress carrier type |
| reflection 测试 | 暂缺 | 需要决定 map validator 或 typed record |
| `AgentBusForwardingRuntimeContractTest` | C3 outbox / inbox 记录字段、唯一键、去重键、禁止字段、状态机、失败码；Stage 8 增 record source/target、claim / lease 语义、dispatcher worker、persistence 纯度；Stage 9 增 lease-owner guarded mutation、record 不变量、failure-code 分类、SQL contract；Stage 10 增 worker lease 异常恢复 skip、lease 续约、dispatch loop | 7 契约（方法名镜像 ICD）+ Stage 7 / Stage 8 / Stage 9 / Stage 10 行为，34 tests 已 green |
| `AgentBusForwardingSpiPurityTest` | forwarding 生产代码纯 Java（无 Spring / JDBC / broker client） | 10 纯度 + 1 活跃度守卫，已 green（Stage 8 新增 record / claim / delivery / worker 均满足） |

## 5. 生成物边界

允许生成：

- L1 graph model。
- schema fixture。
- contract test skeleton。
- drift check manifest。

禁止自动生成：

- 运行时 broker 实现。
- 修改 production dependency graph 的代码。
- 未经 owner 裁决的 breaking 契约变更（如 S2C v1 这种 pre-GA 内部契约的字段增删，MI-005 方案 A）。
- 将 W2 workflow primitives 直接生成为运行时代码。

## 6. S2C tenant 迁移结果与剩余影响

Stage 2 已完成的迁移（commit `d894f494`）：

- `docs/contracts/s2c-callback.v1.yaml`：`tenant_id` 加入 request required fields（第七个必填字段，Rule R-C.c）。
- `agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackEnvelope.java`：新增 `tenantId` 组件，compact constructor 校验非 null、非 blank。
- `agent-bus/src/test/java/com/huawei/ascend/bus/spi/s2c/S2cCallbackEnvelopeLibraryTest.java`：补齐 null/blank `tenantId` 负向用例与既有构造点更新。
- `contract-catalog.md` / `contract-catalog.md.j2` / `spi-appendix.md`：preferred fix 升级为 migrated fact。

仍待后续波次补齐（不改 Task lifecycle 所有权，S2C-TENANT-006）：

- 构造 `S2cCallbackEnvelope` 的 runtime 侧构造点（`agent-runtime`）。
- runtime-side schema validation integration。
- downstream 文档与治理模板的剩余同步。

本迁移已通知所有冲突方（CN-001..CN-007）；不改变 `agent-runtime` 对 Task lifecycle 的所有权。

## 7. C3 转发运行态（Stage 7 最小骨架 → Stage 8 持久化准备 → Stage 9 lease-safe → Stage 10 dispatch-loop runtime）

C3（database outbox / inbox）已最终确认为类 MQ 转发的生产候选路径（裁决见 [`agent-bus-forwarding-runtime-decision`](../../../docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md)，`adopted-c3`）。Stage 7 交付最小可测运行态骨架；Stage 8 补齐持久化准备（[`forwarding-persistence`](../../L2-Low-Level-Design/agent-bus/forwarding-persistence.md)）。

**已落地（生产代码，纯 Java）：**

- `bus.forwarding.spi`：领域模型 `ForwardingEnvelope`（强制 tenant 隔离 + `payloadRef` 条件必填）、`ForwardingRouteHandle`、`ForwardingMessageId`、`ForwardingStatus`（outbox / inbox 终态枚举）、`ForwardingFailureCode`（7 个 wire 失败码）、`ForwardingReceipt`；端口 `ForwardingOutboxPort`（enqueue + mark* + statusOf）、`ForwardingInboxPort`、`ForwardingDispatcher`（accept / enqueue 网关角色，MI8-003）。
- `bus.forwarding.spi`（Stage 8 增补）：record 模型 `ForwardingOutboxRecord` / `ForwardingInboxRecord`（承载 runtime ICD 必填字段，含 source / target，MI8-002）、`ForwardingLease`；端口 `ForwardingOutboxClaimPort`（claim / lease，`claimDue` 取代 `findRetryable`，MI8-001）、`ForwardingDeliveryPort` + `ForwardingDeliveryResult`（抽象投递，4 种 outcome）。
- `bus.forwarding.runtime`：纯状态机 `ForwardingStateMachine`（outbox `ENQUEUE → PENDING → DISPATCHING → {ACKED | RETRY_SCHEDULED → DISPATCHING | DLQ | EXPIRED}`、inbox `ARRIVE_NEW → RECEIVED → {CONSUMED | REJECTED}`，非法迁移抛 `IllegalStateTransitionException`）；Stage 8 增 `ForwardingDispatcherWorker`（claim / deliver / ack / retry 半边，单次 `runOnce` tick：claimDue → deliver → markAcked / scheduleRetry / moveToDlq / markExpired）。

**仅测试夹具（non-production）：**

- `bus.forwarding.test`：`InMemoryForwardingOutbox`（同时实现 outbox + claim / lease 端口，验证并发抢占语义）/ `InMemoryForwardingInbox` / `InMemoryForwardingDispatcher` / `InMemoryForwardingDelivery`（fake delivery port），HashMap 支撑。明确标注 non-production，真实持久化实现为 Stage 9+。

**边界（Stage 7 / Stage 8，与 Stage 4 / 6 一致，强化）：**

- 生产代码不引入 concrete broker / MQ / JDBC driver / Flyway（由 `AgentBusForwardingSpiPurityTest` ArchUnit 强制）。
- 不改变远端 Task lifecycle owner；不写 Task execution state；不携带 payload body / token stream / physical endpoint。
- 真实 JDBC adapter / Flyway migration 归属 / lease store 物理实现 / polling / 并发抢占原语 / 真实投递绑定（dispatcher worker → receiver transport）均 deferred 到 Stage 9+。**§6 护栏：数据库产品或 migration 归属未确认前，停在 schema 草案 + repository port + in-memory lease harness，不引入生产数据库依赖。**
