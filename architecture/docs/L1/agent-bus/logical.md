---
level: L1
module: agent-bus
view: logical
status: draft
---

# agent-bus 逻辑视图

## 1. 逻辑模型

`agent-bus` 的逻辑模型由两块组成：

| 逻辑块 | 面向对象 | 核心问题 |
|---|---|---|
| Gateway | 外部调用方进入内部平台 | 请求能否进入、进入哪里、如何返回同步确认、如何保持幂等和 trace。 |
| 真 bus | 内部 service 与 service 之间的相互调用 | 服务之间如何路由、如何关联、如何治理跨服务 envelope、类 MQ 转发底座、agent 注册发现和 future control/rhythm 流量。 |

Gateway 和真 bus 共享一个原则：它们治理跨边界流量，但不拥有业务 Task 生命周期。

## 2. 逻辑元素

| 元素 | 类型 | 职责 | 所有者 |
|---|---|---|---|
| `agent-bus` | 模块 | 跨平面契约与治理中心 | bus 模块负责人 |
| Gateway | 逻辑子模块 | 外部到内部的入口治理、转发和调度 | bus 模块负责人 |
| 真 bus | 逻辑子模块 | service 与 service 之间的相互调用和跨服务治理 | bus 模块负责人 |
| 类 MQ 转发底座 | 目标态能力 | 跨 service 异步转发、队列/主题抽象、ack/retry、correlation、DLQ/replay、ordering/fairness、backpressure | bus 模块负责人 / 运维负责人 |
| Agent 注册与发现 | 目标态能力 | 提供运行时路由所需的 agent/service/capability 注册、发现、健康和路由元数据 | bus 模块负责人 |
| `IngressGateway` | SPI | C2S 入口表面 | bus 模块负责人 |
| `S2cCallbackTransport` | SPI | service 到 client 的能力回调表面 | bus 模块负责人 |
| `FederationGateway` | SPI | 跨网络/跨部署 ingress 转发 | bus 模块负责人 |
| `ReflectionEnvelopeRouter` | SPI | reflection envelope 路由 | bus 模块负责人 |
| `EnginePort` | SPI | service 与 execution engine 的中立边界 | bus/engine 共同负责 |
| Task execution state | 状态 | Task 生命周期持久化状态 | `agent-service` |

## 3. 逻辑关系

| 从 | 到 | 关系 | 说明 |
|---|---|---|---|
| `agent-client` | Gateway | 调用 | client 通过 `IngressGateway` 发起 C2S 请求。 |
| Gateway | `agent-service` | 转发/调度 | Gateway 把请求路由到生命周期所有者。 |
| `agent-service` | `S2cCallbackTransport` | 调用 | service 请求客户端托管能力。 |
| `S2cCallbackTransport` | `agent-client` | 派发 | callback 通过具体 transport 到达 client。 |
| `agent-service` | `EnginePort` | 驱动 | service 驱动 engine 执行。 |
| `agent-execution-engine` | `EnginePort` | 实现 | execution engine 提供具体执行能力。 |
| 真 bus | 远端 `agent-service` | 跨服务调用治理 | federation/reflection/control envelope 进入跨服务流。 |
| 真 bus | 类 MQ 转发底座 | 使用 | 真 bus 目标态通过转发底座承载跨 service 异步消息。 |
| 真 bus | Agent 注册与发现 | 查询/维护 | 真 bus 目标态基于注册发现结果选择目标 service、agent capability 或 endpoint。 |
| Agent 注册与发现 | `agent-service` | 读取/注册 | service 可以注册运行时可调用 agent/capability 元数据，但仍拥有 Task 生命周期。 |
| `agent-bus` | Task execution state | 禁止写入 | Task 状态所有权不属于 bus。 |

## 4. 状态所有权

`agent-bus` 不拥有下面这些状态：

- Task execution state。
- Task hierarchy。
- Task sleep / suspend / resume 的最终状态机。
- 单个 service instance 内部的多 agent 协同状态。

`agent-bus` 可以拥有或治理下面这些跨边界信息：

- callback correlation。
- request id / idempotency key。
- trace id。
- tenant scope。
- future mailbox/backpressure/tick 的 envelope 和治理规则。
- service / agent / capability 的运行时注册发现索引。

`agent-bus` 不应拥有下面这些注册发现之外的业务事实：

- agent 的业务定义源。
- agent 的代码包或工具实现。
- Task / Run 的执行状态。
- 单个 service 内部的 agent 编排细节。

## 5. S2C tenant 目标态

当前 `S2cCallbackEnvelope` 通过 callback/run registry 绑定解析租户范围。目标态是 envelope 直接携带 `tenantId`。

目标态的原因：

- S2C 是跨边界 envelope，应当自包含 tenant scope。
- 真 bus 和 federation 场景不能依赖单个 service runtime 的隐式 registry。
- 审计、重放、DLQ 和跨服务排查需要从 envelope 本身看到 tenant。

该目标态已接受，但代码迁移必须单独施工。

## 6. 逻辑风险

| 风险 | 说明 | 处理方式 |
|---|---|---|
| Gateway 被误认为 service API owner | Gateway 只负责入口治理和转发，不创建或持久化 Task。 | 文档和测试中重复 service-owned Task invariant。 |
| 真 bus 被误认为运行时 broker 已存在 | 当前只有 SPI 和契约事实，物理 bus 未实现。 | 在物理视图和 feature catalog 标注成熟度。 |
| Agent 注册发现被误认为 agent 定义所有权 | 真 bus 需要发现能力，但不应接管 agent 定义、实现和 Task 状态。 | 把注册发现限定为运行时路由索引。 |
| S2C tenant 事实不一致 | 目标态要求 `tenantId`，当前代码未实现。 | 记录冲突通知，后续独立迁移。 |
