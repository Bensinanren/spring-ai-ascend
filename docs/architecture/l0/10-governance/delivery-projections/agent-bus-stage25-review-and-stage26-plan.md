---
artifact_type: delivery_projection
version: agent-bus-stage25-review-and-stage26-plan
status: stage-26-landed
source_commit: 93004e5f
stage26_planned: 2026-07-02
source_stage25_plan: docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage24-review-and-stage25-plan.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
source_transport_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-transport-decision.md
source_transport_candidates: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-transport-candidates.md
source_icd_runtime: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md
source_icd_forwarding: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding.md
source_l2: architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md
target_module: agent-bus（**首个 broker 生产代码阶段**：broker-agnostic SPI 骨架 + in-memory 替身 + 锁定 RocketMQ；纯 Java 不引 broker client；解除 §6.1 第 1 项引 broker 圈 `transport.broker` 子包、守 §6.2 精神 + 第②③④⑤项不变；relay/consumer 接 worker + AWAITING_ACK 状态机 deferred Stage 27+）
---

# agent-bus Stage 25 评审与 Stage 26 计划

**broker transport adapter PoC：broker-agnostic SPI 骨架 + 锁定 RocketMQ，首个 broker 生产代码阶段**

本文是 agent-bus 转发运行态验证序列的交付投影（delivery projection）：先评审上一阶段（Stage 25）的实际落点，再规划下一阶段（Stage 26）的范围与设计。**Stage 26 是 Stage 25 裁决 T4 hybrid 后的第一个写 broker 生产代码的阶段**——但形态是 **broker-agnostic SPI 骨架**（纯 Java，**不引任何 broker client**）：在 `forwarding.runtime.transport.broker` 子包落地两 SPI 端口（`BrokerForwardingRelayPort` relay 形态 / `BrokerForwardingConsumerPort` receiver 形态）+ 消息/配置抽象 + in-memory 替身 + 契约测试验证治理不变量 + ArchUnit 三处豁免，**产品锁定 RocketMQ**（用户裁决），真实实例 PoC deferred 部署环境（同 Stage 12 embedded-postgres / Stage 15 MockWebServer 哲学：broker-agnostic SPI 设计上不在开发期强依赖真实 broker 实例）。

---

## §0 结论

- **Stage 25 接受**（commit `93004e5f`，200 tests 不变，ArchUnit 不变，已 push）。纯文档裁决阶段（性质同 Stage 5 / 6 / 13），产出 = transport-decision packet（`adopted-t4`）+ decision §6.1/§6.2/§4/§8 治理落位 + L2 + 7 L1 视图 + ICD + yaml + 双语 plan。裁决投递模型 = **T4 hybrid（outbox + broker）**：保留 Stage 12 outbox 事务一致性 + relay produce broker + receiver pull（pull = 反压内核，MQ 本质是 pull）；解除 §6.1 第 1 项引 concrete broker 禁令、守 §6.2 第①项精神 + 第②③④⑤项不变。
- **Stage 26 落地 broker-agnostic SPI 骨架 + 锁定 RocketMQ**，**217 tests green**（Stage 25 的 200 + 16 契约 + 1 in-memory 替身辅助，contract test 36→52），ArchUnit green。**首个 broker 生产代码阶段**，但**纯 Java 不引 broker client**（broker-agnostic 治理骨架先行，真实 adapter 接线 deferred Stage 27+）。
- **产品锁定 RocketMQ**（用户裁决）：原生顺序消息 + namespace 租户分层 + 原生 retry/DLQ + 华为云 DMS 托管 + pull consumer 对应 T4 反压内核 + 概念收敛度对 broker-agnostic 治理最友好。真实实例 PoC（顺序/性能/原生 retry-DLQ/namespace 租户实测）**deferred 部署环境**（本开发机无自己部署的 RocketMQ 实例 + Docker 死路 [代理 407 + 无 sudo，`agent-bus/pom.xml:130-141` 证 testcontainers 不可行]；开发态用 in-memory 替身，与 Stage 12 Zonky embedded-pg 不连生产 PG、Stage 15 MockWebServer 不连真实 agent-runtime **完全同构**）。
- **核心设计张力（Stage 26 关键判断）**：`ForwardingDeliveryPort.deliver(record, nowMillisEpoch)` 返回**终态导向**的 `ForwardingDeliveryResult`（ACKED/RETRY/DLQ/EXPIRED），是为 A2A **同步 push** 设计；而 broker **produce 是 fire-and-forget**（produce 成功 ≠ receiver 处理完，模型 B 要 Stage 27 的 AWAITING_ACK 反向 ack）。因此 Stage 26 的 `BrokerForwardingRelayPort` 是**独立 SPI**（**非** `ForwardingDeliveryPort` 子类型），`produce` 返回 `BrokerProduceOutcome`（ACCEPTED / UNAVAILABLE[retryable] / ROUTE_NOT_FOUND[non-retryable]，非终态）。调和 Stage 25 §4「broker adapter 是新 ForwardingDeliveryPort 实现」表述：那指 Stage 27+ relay 接 worker 后的最终态；Stage 26 先定独立 SPI，Stage 27 决定 relay adapter 是否包装 `ForwardingDeliveryPort` 或 worker 改调 relay port。
- **§6.2 解除落位**：解除 §6.1 第 1 项（引 concrete broker 禁令），broker client 圈进 `forwarding.runtime.transport.broker` 子包（同 Stage 12 `persistence.jdbc` / Stage 15 `transport.a2a` 范式）；守 §6.2 第①项精神（broker 产品概念——topic/partition/offset/consumer-group——不反向定义治理语义、不泄漏 `transport.broker` 之外）+ 第②③④⑤项全部不变。ArchUnit 三处豁免：`SpiPurityTest` 加 rocketmq 规则（圈 `transport.broker`，现阶段 vacuous pass，为 Stage 27+ 真实 adapter 授权 + 防回归；Kafka/NATS 仍全禁）+ §6.2 文本扫描排除 `transport.broker` 子树 + Stage 4 broker-agnostic trip-wire 解除（仅放行 `transport.broker`）。
- **预计**：217 tests green（已落地），ArchUnit green。产出 = transport-decision §5/§9/§10 锁定 RocketMQ + decision §8 Stage 26 段 + transport.broker 子包 8 生产文件 + InMemoryBroker + 16 契约 + ArchUnit 三处豁免 + L2 §26 + outbox-inbox T4 标注 + 7 L1 视图 + ICD + yaml stage26_scope + 双语 plan（本文）。

---

## §1 Stage 25 评审

### 1.1 已落地

纯文档裁决阶段（无生产代码），200 tests 不变，ArchUnit 不变。产出 14 文件：

- **transport-decision packet**（新建，§0-§11）：裁决边界 / 裁决项表 / §6.2 解除论证 / T4 数据流（5 跳）/ 治理边界 / broker 选型矩阵（倾向 RocketMQ deferred Stage 26）/ 租户纵深 L1-L5 / 资产命运表 / rejection criteria R1-R5 / 后续 Stage 切片 / 护栏清单 7 项。
- **decision §6.1/§6.2/§4/§8**：MI25-001 四处治理落位（§6.1 解除引 broker / §6.2 守精神 + ②③④⑤ / §4 正反向许可 / §8 Stage 25 裁决段）。
- **L2×2 + 7 L1 视图 + ICD + yaml stage25_scope + 双语 plan**：按 [[agent-bus-4plus1-view-rebound]] 回灌。

### 1.2 测试与提交

- 200 tests 不变（无 Java 改动），ArchUnit 不变（无新代码）。
- commit `93004e5f`（experimental，已 push；与 Stage 22 `852765c9` / Stage 23 `e827291c` / Stage 24 `d73b6ecd` 同批 PAT 过期后用户 `! git push origin experimental` 推送）。

### 1.3 关键发现（Stage 25 裁决）

- **MQ 本质是 pull**（Kafka poll / RocketMQ pull / broker prefetch = `claimDue` + `SKIP LOCKED` 同内核）—— 用户独立重新发现 Stage 13 结论。
- **MQ client 自带 consumer loop** 一次性解决 §6.1 第 3 项 scheduler 障碍（receiver 侧无需自建 TickSource）。
- **broker 独立中介**解决模块依赖障碍（receiver 从 broker pull 不碰 agent-bus 表，绕开 `AgentBusDependencyBoundaryTest`）。
- **T4 ≠ C4**：保留 outbox 事务一致性，broker 仅投递通道（C4 抛弃 outbox 已被 Stage 13 §3 拒绝）。
- **broker retry 配 off**：agent-bus Stage 14 retry policy 主导，避免双重重投。

### 1.4 deferred 结转（Stage 25 → Stage 26+）

broker 产品选型实测 / broker 物理接线 / relay adapter / receiver consumer / 模型 B 反向 ack / AWAITING_ACK 状态机 / 生产 TickSource（§6.1 第 3 项）/ 端到端 T4 / T1→T4 切换 —— **其中 broker 产品选型 + SPI 骨架正是 Stage 26 要闭合的**。

### 1.5 Stage 25 里程碑总结

C3 投递模型最终裁决落位（T4 hybrid，`adopted-t4`），解除 §6.1 第 1 项引 broker 禁令、守 §6.2 精神。Stages 15–24 在 T1 push 上端到端验证了三条生命线（retry 往返 / 租约回收 / 断路器）+ 三终态（ACKED / DLQ / EXPIRED）+ payloadRef 传递 + RLS 纵深，**200 tests green** —— T1 push 路径充分证明可行，Stage 25 裁决升级到 receiver 主导控速的 pull 目标态（T4）。

---

## §2 Stage 26 范围与设计

### 2.1 形态裁决（本会话用户拍板）

- **broker-agnostic SPI 骨架**（纯 Java，**不引任何 broker client**）：类比 Stage 12 引 JDBC 前先定端口、Stage 15 引 A2A SDK 前先定 `ForwardingDeliveryPort` —— Stage 26 先把 broker transport 的治理边界（两 SPI + 消息/配置抽象）落位，真实 adapter 接线 deferred Stage 27+。
- **产品锁定 RocketMQ**（用户裁决）。
- **broker 是项目自己部署的基础设施，不是公共 MQ**（用户立场 = §6.2 broker-agnostic 设计意图本身）。本开发机无自己部署的 RocketMQ 实例 + Docker 死路 → 开发态用 in-memory 替身，真实实例 PoC deferred 部署环境（那里有自己部署的 RocketMQ 集群）。与 Stage 12 Zonky embedded-pg / Stage 15 MockWebServer **完全同构**。

### 2.2 核心设计张力：独立 SPI 非 ForwardingDeliveryPort 子类型

`ForwardingDeliveryPort.deliver(record, nowMillisEpoch)` 返回**终态导向**的 `ForwardingDeliveryResult`（ACKED/RETRY/DLQ/EXPIRED），为 A2A 同步 push 设计；broker produce 是 fire-and-forget（非终态）。故：

- `BrokerForwardingRelayPort`（relay 形态）是**独立 SPI**，`produce` 返回 `BrokerProduceOutcome`（ACCEPTED / UNAVAILABLE[retryable] / ROUTE_NOT_FOUND[non-retryable]）。
- `BrokerForwardingConsumerPort`（receiver 形态）`poll` / `commit` / `reject`（模型 B ack-after-consume，手动 commit）。
- Stage 27 决定 relay adapter 是否包装 `ForwardingDeliveryPort` 或 worker 改调 relay port（调和 Stage 25 §4 表述 = 最终态）。

### 2.3 transport.broker SPI（broker-agnostic，守 §6.2 第①项精神）

新增包 `com.huawei.ascend.bus.forwarding.runtime.transport.broker`，8 生产文件（纯 Java）：

| 类型 | 职责 | §6.2 护栏 |
|---|---|---|
| `BrokerForwardingRelayPort` | relay: `produce(ForwardingOutboxRecord)→BrokerProduceOutcome`；routeHandle 经 `ForwardingEndpointResolver` 映射 topic（HD4 opaque，**不读 `routeHandle.value()`**） | 独立 SPI 非终态 |
| `BrokerForwardingConsumerPort` | receiver: `poll`/`commit`/`reject`（模型 B ack-after-consume） | ⑤ reject 不 commit |
| `BrokerOutboundMessage` | body = routing descriptor only；headers = {tenantId 必需, payloadRef 条件, messageId, sourceServiceId, targetServiceId} | ② payloadRef 走 header 不进 body |
| `BrokerInboundMessage` | 暴露 dedup 用 messageId + tenantId + consumerServiceId，**不暴露 offset** | broker 概念不泄漏 |
| `BrokerProduceOutcome` | ACCEPTED / UNAVAILABLE[retryable] / ROUTE_NOT_FOUND[non-retryable] | 非终态 |
| `BrokerMessageHeaders` | broker-agnostic header 抽象 | tenantId 强制 |
| `BrokerClientProperties` | 产品无关连接配置（nameserverEndpoints / namespace 等 generic 字段，**不绑 RocketMQ 类型**） | 产品无关 |
| `package-info` | 子包边界说明 | — |

**护栏（transport-decision §10，逐条守）**：payloadRef 走 header 不进 body（②）/ routeHandle opaque（HD4）/ tenantId 强制 + L2 header 校验 reject 不 commit（⑤）/ consumerServiceId=consumer-group（inbox dedup key）/ broker retry off（替身不模拟 broker retry，agent-bus retry policy Stage 14 主导，Stage 27 接）/ outbox 保留（T4≠C4）。

### 2.4 in-memory broker 替身（test scope）

`InMemoryBroker`（类比 `forwarding/test/InMemoryForwardingOutbox.java` / `Inbox`）：纯 Java 模拟 broker 语义 —— topic-per-tenant（routeHandle→topic 映射含 tenantId）/ produce→内存 queue（partition = `hash(messageId)`）/ poll 按 consumerServiceId(consumer-group) 取下一条未 commit / commit→offset 前移 / **至少一次**（poll 后未 commit 可被重新 poll，模拟 redelivery，由 `ForwardingInboxPort` dedup 吸收）。实现 `BrokerForwardingRelayPort` + `BrokerForwardingConsumerPort`。

### 2.5 ArchUnit 三处豁免

- **`AgentBusForwardingSpiPurityTest`**：加 `forwarding_core_does_not_import_rocketmq_outside_broker_adapter`（`org.apache.rocketmq..` 圈 `transport.broker`，because 子句引 Stage 25 §6.1 第 1 项解除 + 锁定 RocketMQ）。**现有 `forwarding_does_not_import_kafka` / `_nats` 保持全禁**。现阶段 vacuously pass（in-memory 替身纯 Java 不引 RocketMQ client）—— **防回归 + 为 Stage 27+ 真实 adapter 授权**。
- **§6.2 文本扫描测试**：排除 `transport.broker` 子树（同 Stage 15 a2a 范式）。
- **`DesignContractTest` Stage 4 broker-agnostic trip-wire**：解除（仅放行 `transport.broker`，broker 概念圈进子包不外泄）。

### 2.6 锁定 RocketMQ + 真实例 PoC deferred

- **产品锁定 RocketMQ**（用户裁决）：原生顺序消息 + namespace 租户分层 + 原生 retry/DLQ + 华为云 DMS 托管 + pull consumer 对应 T4 反压内核 + 概念收敛度对 broker-agnostic 治理最友好。
- **真实实例 PoC deferred 部署环境**：本开发机无自己部署的 RocketMQ 实例 + Docker 死路（代理 407 + 无 sudo → testcontainers 不可行）；本机起 RocketMQ binary 重且≠生产拓扑。开发态 in-memory 替身同 Stage 12/15 哲学。
- **RocketMQ client Maven 依赖 deferred Stage 27+**（真实 adapter 引，Stage 26 不引 → ArchUnit rocketmq 规则现阶段 vacuous）。

### 2.7 边界 + ArchUnit + §6.2

| 护栏 | Stage 26 状态 |
|---|---|
| 生产代码形态 | ✅ broker-agnostic SPI 骨架（纯 Java，**不引 broker client**）；`transport.broker` 子包 8 生产文件 + in-memory 替身（test）+ 16 契约 |
| §6.2 解除落位 | ✅ 解除 §6.1 第 1 项（引 concrete broker）；守 §6.2 第①项精神（broker 产品概念不反向定义语义、不泄漏 `transport.broker` 之外）+ 第②③④⑤项全部不变 |
| ArchUnit | ✅ 三处豁免（rocketmq 圈 `transport.broker` [vacuous now, Stage 27+ 授权] / §6.2 文本扫描排除 / Stage 4 trip-wire 解除）；Kafka/NATS 仍全禁 |
| 现有测试 | ✅ 217 tests green（200 + 16 契约 + 1 替身辅助，contract test 36→52） |
| 4+1 视图回灌 | ✅ 按 [[agent-bus-4plus1-view-rebound]] 清单，7 L1 视图 + 2 L2 + ICD + yaml + decision 全同步 |
| 模块依赖边界 | ✅ 不变（纯 Java SPI 不引 broker client，`AgentBusDependencyBoundaryTest.bus_does_not_depend_on_agent_runtime` 仍 green；broker client Maven 依赖 deferred Stage 27+） |

---

## §3 关键发现（Stage 26 前置/落地分析）

| # | 发现 | 影响 |
|---|---|---|
| F1 | **broker produce 非终态**（fire-and-forget） | `BrokerForwardingRelayPort` 独立 SPI 非 `ForwardingDeliveryPort` 子类型，`produce` 返回 `BrokerProduceOutcome`（非 `ForwardingDeliveryResult`） |
| F2 | **routeHandle opaque（HD4）** | resolver 映射 routeHandle→topic，adapter 不读 `routeHandle.value()` |
| F3 | **payloadRef 走 header 不进 body（§6.2②）** | `BrokerOutboundMessage.body()` 只载 routing descriptor，payloadRef 进 headers |
| F4 | **broker 产品概念不进 SPI 类型** | topic 作 `produce` 内部参数、partition/offset 是 adapter 内部细节（`BrokerInboundMessage` 只暴露 dedup 用 messageId + tenantId + consumerServiceId） |
| F5 | **L2 tenant 校验 reject 不 commit（§6.2⑤）** | poll 跨 tenant 消息 → reject 不 commit（显式失败不静默） |
| F6 | **broker 是自部署基础设施非公共 MQ** | 真实例 PoC deferred 部署环境；开发态 in-memory 替身同 Stage 12/15 哲学 |
| F7 | **ArchUnit rocketmq 规则 vacuous now** | in-memory 替身纯 Java 不引 client；规则为 Stage 27+ 真实 adapter 授权 + 防回归 |
| F8 | **模块依赖边界不变** | 纯 Java SPI 不引 broker client，`AgentBusDependencyBoundaryTest` 仍 green |

---

## §4 切片 + MI 表（Stage 26 = 首个 broker 生产代码阶段）

| MI | 切片 | 产出 |
|---|---|---|
| MI26-001 | 0 治理落位 | decision §8 Stage 26 段 + transport-decision §5 锁定 RocketMQ + §9 Stage 26 行更新（替身形态 + 真实 PoC deferred）+ §10 护栏引用 |
| MI26-002 | 1 SPI 骨架 | `transport.broker` 子包：`BrokerForwardingRelayPort` / `BrokerForwardingConsumerPort` / `BrokerClientProperties` / `BrokerOutboundMessage` / `BrokerInboundMessage` / `BrokerProduceOutcome` / `BrokerMessageHeaders` / `package-info`（broker-agnostic，纯 Java 不引 broker client） |
| MI26-003 | 2 in-memory 替身 | `InMemoryBroker`（test）实现两 SPI；topic-per-tenant / poll / commit / redelivery / consumer-group |
| MI26-004 | 3 契约测试 | `BrokerForwardingPortsContractTest`：16 契约验证治理不变量（payloadRef header / routeHandle opaque / L2 tenant reject / consumer 隔离 / redelivery） |
| MI26-005 | 4 ArchUnit + 文本豁免 | `SpiPurityTest` +rocketmq 规则（圈 `transport.broker`，vacuous now）+ §6.2 文本扫描排除 `transport.broker` + Stage 4 trip-wire 解除 |
| MI26-006 | 5 文档同步 | L2（forwarding-persistence §26 + outbox-inbox T4 标注）/ 7 L1 视图 / ICD（边界 + stage26_scope）/ yaml（stage26_scope）/ 双语 plan（本文）—— 按 [[agent-bus-4plus1-view-rebound]] 回灌 |
| — | 6 验证 + 提交 | `mvn -f agent-bus/pom.xml test` green（217 tests）/ ArchUnit green / commit experimental（store 凭据已配可 push） |

---

## §5 deferred + 风险

### 5.1 deferred（Stage 26 不触及，记录为后续 Stage 27–30）

- **Stage 27 relay adapter 接 worker + 模型 B 反向 ack + AWAITING_ACK 状态机**：relay adapter 是否包装 `ForwardingDeliveryPort` 或 worker 改调 relay port；是否加第 7 态 AWAITING_ACK；反向 ack 通道；`BrokerProduceOutcome` 与 `ForwardingDeliveryResult` 映射；RocketMQ client Maven 依赖引入。
- **Stage 28 receiver consumer + 生产 TickSource**：`BrokerForwardingConsumerPort` 真实实现；receiver poll → inbox；生产 scheduler 落地（解除 §6.1 第 3 项）。
- **Stage 29 端到端 T4**：sender outbox → relay → broker → receiver → ack 全链端到端 IT（真实 RocketMQ 实例）。
- **Stage 30 T1→T4 切换共存**：routeHandle 级别路由 / 灰度切换；T1 push PoC 退役计划。
- **真实 RocketMQ 实例 PoC**（顺序/性能/原生 retry-DLQ/namespace 租户实测）deferred 部署环境。
- **沿用 Stages 15–25 deferred**：FORCE/WITH CHECK RLS / app_role 生产部署 / 连接池治理 / PAYLOAD_REF_INVALID 接线 / EXPIRED 真实触发源 / payloadPolicy 持久化 / 真实 agent handler / registry resolver —— 均不变。

### 5.2 风险

- **真实 RocketMQ 实例 PoC 未做**：产品锁定基于特性矩阵论证（原生顺序 / namespace 租户 / retry-DLQ / DMS 托管 / 概念收敛），真实 produce/pull/retry/DLQ/租户隔离实测 deferred 部署环境；若 PoC 推翻倾向则回更 transport-decision §5。
- **独立 SPI 与 ForwardingDeliveryPort 的调和**：Stage 26 先定独立 SPI（`BrokerProduceOutcome` 非终态），Stage 27 决定 relay adapter 接 worker 的最终形态（包装 `ForwardingDeliveryPort` 或 worker 改调 relay port）—— 此期间两 SPI 并存，需 Stage 27 明确收敛点。
- **双层 ordering 一致性**：outbox + broker 双层 ordering 是 T4 主要工程负担（Stage 13 §2.4），Stage 27 / 29 需建立因果关系 / 全局序号。
- **broker 侧租户隔离纵深**：破 §6.2 引 broker 后，broker 侧（无 RLS）是新攻击面，L1-L5 纵深必须在 Stage 27-28 真实接线时落地，否则跨租户泄露风险（Stage 26 in-memory 替身 + 契约已证 L1/L2/L3，L4 复用 Stage 24 RLS，L5 应用层 reject）。
- **§6.2 守恒自证**：解除 §6.1 第 1 项（明确禁令）+ 守 §6.2 第①项精神（broker 产品概念不反向定义语义、不泄漏 `transport.broker` 之外）+ 第②③④⑤项不变；broker 是投递通道非 payload body / token stream / Task state / registry 定义仓库；跨租户 R-C.c 显式 reject（L2 header 校验失败必 reject）。

---

## 相关文档

- Stage 25 计划（本文评审对象）：[`agent-bus-stage24-review-and-stage25-plan`](agent-bus-stage24-review-and-stage25-plan.md)
- 运行态裁决：[`agent-bus-forwarding-runtime-decision`](../review-packets/agent-bus-forwarding-runtime-decision.md) §8
- **Stage 25 投递模型裁决（T4 hybrid）**：[`agent-bus-forwarding-runtime-transport-decision`](../review-packets/agent-bus-forwarding-runtime-transport-decision.md)
- transport 候选评审（Stage 13）：[`agent-bus-forwarding-runtime-transport-candidates`](../review-packets/agent-bus-forwarding-runtime-transport-candidates.md)
- 运行态 ICD：[`ICD-agent-bus-forwarding-runtime`](../../05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md)
- forwarding ICD（HD4 / payloadRef）：[`ICD-agent-bus-forwarding`](../../05-contracts/human-readable/ICD-agent-bus-forwarding.md)
- L2 持久化：[`forwarding-persistence`](../../../../architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md)
- yaml（machine-readable）：[`agent-bus-forwarding-runtime.v1`](../../05-contracts/machine-readable/agent-bus-forwarding-runtime.v1.yaml)
