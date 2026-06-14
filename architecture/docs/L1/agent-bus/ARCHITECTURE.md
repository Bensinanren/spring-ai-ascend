---
level: L1
module: agent-bus
view: architecture
status: draft
source_review_packet: docs/architecture/l0/10-governance/review-packets/agent-bus-architecture-review-draft.md
---

# agent-bus L1 架构总览

## 1. 模块定位

`agent-bus` 是平台的跨平面通信与治理模块。它负责定义和承载跨边界 envelope、SPI 和治理规则，使外部请求、服务间调用、客户端能力回调、federation、reflection 等流量不直接穿透模块边界。

它不是 Task 生命周期中心。Task 创建、状态持久化、suspend/resume、Task hierarchy 和 service API 仍由 `agent-service` / agent runtime 拥有。`agent-bus` 负责的是“流量如何跨边界进入、离开、转发、关联和治理”。

## 2. 两块逻辑职责

H2 已接受 `agent-bus` 内部分为两个逻辑子模块：

| 逻辑子模块 | 职责 | 当前代码对应 |
|---|---|---|
| Gateway | 外部到内部的入口治理、转发和调度。典型流量是 edge/client 到 compute_control 的 C2S ingress。 | `com.huawei.ascend.bus.spi.ingress` |
| 真 bus | service 与 service 之间的相互调用、跨服务路由和跨服务治理。典型流量包括 federation、reflection、未来 control/rhythm 通道。 | `com.huawei.ascend.bus.spi.federation`、`com.huawei.ascend.bus.spi.s2c`、`com.huawei.ascend.bus.spi.engine` 的跨服务边界事实 |

这个拆分是 L1 逻辑架构拆分，不表示当前仓库已经拆成两个 Maven module。

真 bus 还需要进一步包含两个目标态能力：

| 目标态能力 | 职责 | 当前状态 |
|---|---|---|
| 类 MQ 转发底座 | 提供跨 service 的异步转发、队列/主题抽象、correlation、ack/retry、backpressure、DLQ/replay、ordering/fairness 等运行时语义。 | 设计态；当前没有 broker 绑定或 runtime 实现。 |
| Agent 注册与发现 | 维护运行时路由所需的 agent/service/capability 注册发现索引，包括实例、租户、能力、版本、region、endpoint、health、负载等路由元数据。 | 设计态；当前没有注册表实现或发现 API。 |

这里的注册发现只服务于运行时路由和治理，不拥有 agent 的业务定义、Task 生命周期或执行状态。

## 3. 当前已接受的 SPI 范围

首批 L1 范围覆盖当前代码中已存在的 SPI 包：

- `com.huawei.ascend.bus.spi.ingress`
- `com.huawei.ascend.bus.spi.s2c`
- `com.huawei.ascend.bus.spi.federation`
- `com.huawei.ascend.bus.spi.engine`

W2 workflow primitives 只保留设计态，不进入自动实现范围。它们包括 mailbox、admission、backpressure、sleep、wakeup、tick 等运行时治理能力。

## 4. 关键边界

| 边界 | 规则 |
|---|---|
| Task 生命周期 | `agent-service` 拥有，`agent-bus` 不直接写 Task execution state。 |
| Service 与 Engine | `EnginePort` 是中立边界；service 驱动，execution engine 实现，bus 提供 SPI home。 |
| Client 到 Service | `agent-client` 不直接依赖 compute_control 内部模块；通过 `IngressGateway` 进入。 |
| Service 到 Client | 通过 `S2cCallbackTransport` 派发 S2C callback；后续 envelope 必须显式携带 `tenantId`。 |
| Service 到 Service | 由真 bus 负责跨服务调用治理；当前以 federation/reflection 等 SPI 和契约事实表达。 |
| 物理 bus | broker、ordering、DLQ、mailbox fairness 等运行时实现未进入当前切片。 |
| 注册发现 | 真 bus 目标态需要 agent/service/capability 注册发现；当前只记录为设计态，不进入 Stage 1 harness。 |

## 5. 当前事实来源

| 类型 | 来源 |
|---|---|
| 模块元数据 | `agent-bus/module-metadata.yaml` |
| 构建定义 | `agent-bus/pom.xml` |
| 契约目录 | `docs/contracts/contract-catalog.md` |
| 具体契约 | `docs/contracts/ingress-envelope.v1.yaml`、`s2c-callback.v1.yaml`、`engine-port.v1.yaml`、`federation-envelope.v1.yaml`、`reflection-envelope.v1.yaml` |
| L0 边界 | `architecture/L0-Top-Level-Design/boundaries.md`、`architecture/L0-Top-Level-Design/views.md` |
| 相关 L1 | `architecture/L1-High-Level-Design/agent-service/**` |
| 当前代码 | `agent-bus/src/main/java/com/huawei/ascend/bus/spi/**` |
| 当前测试 | `agent-bus/src/test/java/**` |

## 6. 已知冲突与迁移

S2C tenant 边界存在事实冲突：当前 `S2cCallbackEnvelope` Java record 没有 `tenantId`，但正确的跨边界 envelope 设计要求它显式携带租户范围。部分 service L1 文档和模板已经把 `S2cCallbackEnvelope.tenant_id` 写成目标态或事实态。

H2 已接受后续给 `S2cCallbackEnvelope` 增加 `tenantId`。该变更必须作为独立契约迁移切片施工，并在施工前通知冲突方：

- `agent-bus` 契约与测试 owner。
- `agent-service` / runtime owner。
- `agent-execution-engine` callback suspension 路径 owner。
- `agent-client` / edge capability owner。
- 既有 L1 文档和治理模板 owner。

## 7. 自动化边界

自动化可以基于本 L1 文档生成图、schema fixture、测试骨架和漂移检查清单。自动化不得直接执行以下变更：

- 改变 Task 生命周期所有权。
- 给 `agent-bus` 增加到 sibling module 的生产依赖。
- 实现 broker、mailbox、backpressure、tick、DLQ、ordering 等运行时语义。
- 未完成冲突通知就修改 S2C `tenantId` 契约。

## 8. 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 职责漂移 | bus 容易被误写成 Task lifecycle owner。 | 在每个视图重复 service-owned Task invariant。 |
| 文档超前 | 部分文档已描述 `S2cCallbackEnvelope.tenant_id`，但代码未实现。 | 标记为待迁移冲突，后续独立切片修复。 |
| 运行时夸大 | 当前是 SPI 和契约脚手架，不是完整物理 bus。 | 对每个能力标注成熟度。 |
| 注册发现所有权漂移 | agent registry 容易被误写成 agent 定义仓库或 Task 状态仓库。 | 限定为运行时路由发现索引，不拥有 agent 定义和 Task 状态。 |
| 自动生成反客为主 | Swagger/schema/stub 可能被误当语义事实源。 | 生成物必须引用 human ICD 和 source fact。 |
