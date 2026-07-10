---
version: 0715
module: agent-bus
feature_type: functional
feature_id: FEAT-2026-012
status: active
---

# agent-gateway 组件客户端调用总线转发特性文档

## 1. 特性定位

FEAT-2026-012 定义 `agent-gateway` 当前版本把客户端调用事件化转发到 `Event Bus` 的事实：Gateway 可以将 agent-client 的异步调用标准化为控制事件，返回 `clientInvocationId`，由后端 worker / agent-service 消费事件并投递到 runtime 标准入口，再通过状态投影事件把接受、流准备、等待输入和终态反馈给客户端。

本特性解决的问题是：长任务、多 Agent 协同、削峰填谷和存量服务兼容场景不适合让客户端同步等待目标 runtime。Gateway 需要提供事件化入队、幂等关联、payloadRef、大载荷引用、状态投影和流桥接能力；Event Bus 只承载控制事件、状态投影和引用，不执行 Agent、不承载 token chunk、不持有 Task 权威状态。

在总体架构中，本特性位于 agent-client、Gateway、Event Bus、forwarding worker / agent-service 和 agent-runtime 之间。Gateway 是客户端入口和投影交付者，Event Bus 是发布订阅与缓存设施，runtime 仍是 Task owner。

本特性面向以下角色：

- Gateway 开发者：实现异步入队、事件发布、状态订阅和流桥接。
- Event Bus 集成方：提供发布订阅、缓存、分区、重试和死信。
- forwarding worker / agent-service 开发者：消费事件并投递 runtime 标准入口。
- agent-client 接入方：使用 clientInvocationId 观察异步调用状态。
- 企业异步任务运维方：配置背压、配额、DLQ、告警和重试策略。
- 测试与验收团队：验证入队、接受、流准备、终态、取消、幂等和大载荷引用。

本特性只定义客户端调用的总线转发通道。直接路由转发由 FEAT-2026-011 承接；runtime worker 如何消费事件由 FEAT-2026-017 承接；Agent 执行、业务聚合和 TaskStore 不属于 Event Bus 职责。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 异步调用入队 | MUST | Gateway 必须发布 `CLIENT_INVOCATION_REQUESTED` 并返回 `clientInvocationId`。 |
| 业务上下文传递 | MUST | 事件必须携带 tenant、user、session、correlation、routeHandle、幂等键和输入引用。 |
| 大载荷引用 | MUST | 大输入、大结果、文件、多模态内容必须使用 payloadRef / artifactRef，不写入事件正文。 |
| 服务端接受投影 | MUST | runtime 创建或复用 Task 后，消费者必须发布 `INVOCATION_ACCEPTED` 和 taskId。 |
| 流准备投影 | MUST | 流式任务必须发布 `INVOCATION_STREAM_READY` 或 streamRef，Gateway 再桥接 A2A SSE。 |
| 等待输入投影 | MUST | runtime INPUT_REQUIRED 必须可投影为 `INVOCATION_INPUT_REQUIRED`。 |
| 终态投影 | MUST | completed、failed、canceled 等结果必须通过 `INVOCATION_TERMINAL` 表达。 |
| 取消请求事件 | MUST | Gateway 必须能发布 `CLIENT_INVOCATION_CANCEL_REQUESTED`，由消费者转发到 Task owner。 |
| 幂等与去重 | MUST | eventId、clientInvocationId、taskId 和幂等键必须支持重复投递去重。 |
| 背压与死信 | SHOULD | Event Bus 应支持分区、重试、DLQ、租户配额和积压可观测。 |
| Event Bus 不执行 Agent | MUST | 总线不得执行 Agent、调用模型、运行工具或承载业务 checkpoint。 |
| token 流入总线 | OUT | 当前版本不允许 token-by-token、SSE frame 或大对象正文进入 Event Bus。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| `CLIENT_INVOCATION_REQUESTED` | Event Bus 控制事件 | 表示客户端调用已入队，携带 clientInvocationId、tenant、routeHandle、payloadRef 和幂等信息。 |
| `INVOCATION_ACCEPTED` | Event Bus 投影事件 | 表示 runtime / 目标服务已接受调用，必须携带 taskId。 |
| `INVOCATION_STREAM_READY` | Event Bus 投影事件 | 表示流可订阅，携带 streamRef 或桥接所需引用，不携带 token。 |
| `INVOCATION_INPUT_REQUIRED` | Event Bus 投影事件 | 表示等待用户输入或端侧能力结果。 |
| `CLIENT_INPUT_PROVIDED` | Event Bus 控制事件 | 表示客户端提交续接输入，用于恢复等待任务。 |
| `CLIENT_CAPABILITY_RESULT_PROVIDED` | Event Bus 控制事件 | 表示客户端提交端侧能力结果。 |
| `CLIENT_INVOCATION_CANCEL_REQUESTED` | Event Bus 控制事件 | 表示客户端请求取消，由消费者映射到 CancelTask。 |
| `INVOCATION_TERMINAL` | Event Bus 投影事件 | 表示任务 completed、failed、canceled 或 rejected。 |
| payloadRef / artifactRef | 引用字段 | 指向大载荷或结果，不要求总线保存正文。 |
| clientInvocationId | 关联 id | Gateway 生成，用于入队、状态查询和幂等恢复，不能替代 taskId。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 异步提交长任务 | Gateway 和 Event Bus 可用 | client 提交报告、分析或批量任务 | Gateway 发布请求事件并返回 clientInvocationId；后续接受、流准备和终态通过投影返回。 |
| 多 Agent 协同 | 协同 worker 可消费父任务 | Gateway 入队协同目标 | 子任务由各自 runtime 持有 Task，聚合服务发布父任务终态；Event Bus 只传控制事件和引用。 |
| 批量任务削峰 | 高峰期间大量请求进入 | Gateway 快速入队 | worker 按租户配额和并发消费，积压可观测，重试耗尽进入 DLQ。 |
| 流式任务观察 | runtime 支持 SSE | 消费者发布 stream-ready | Gateway 按 streamRef 桥接 runtime SSE，Event Bus 不承载 token 流。 |
| 等待输入恢复 | Task 投影为 INPUT_REQUIRED | client 提交输入或能力结果 | Gateway 发布续接事件，worker 恢复原 runtime Task。 |
| 取消异步任务 | client 持有 clientInvocationId 或 taskId | client 发起 cancel | Gateway 发布取消事件，消费者定位 Task owner 并调用 CancelTask。 |
| 接受状态未知 | 请求已入队但未确认 taskId | client 查询状态 | Gateway 返回 queued、accepted、dead-letter、unknown 或终态，允许幂等重试。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 入队与关联语义

- `clientInvocationId` 表示 Gateway 入队关联，不表示 runtime 已创建 Task。
- 只有 `INVOCATION_ACCEPTED` 携带的 taskId 才是 runtime Task 句柄。
- 重复提交必须通过幂等键和 clientInvocationId 避免重复副作用。

#### 5.1.1 事件投影语义

- Event Bus 事件表达调用控制和状态投影，不表达 runtime 内部状态机全部细节。
- 流式内容、token、SSE frame 和大对象正文必须留在 runtime stream 或引用对象中。
- 投影事件必须携带 tenant、correlation、trace、schemaVersion 和时间戳，便于审计和恢复。

#### 5.1.2 查询、取消和续接语义

- 查询应区分 queued、accepted、working、input_required、terminal、dead_lettered 和 unknown。
- 取消请求必须最终路由到 Task owner，不得只在总线侧标记取消。
- 续接输入和端侧能力结果必须绑定 taskId、callbackId 或 correlationId。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 入队失败 | Gateway 返回明确错误，不生成成功 clientInvocationId。 |
| 背压 | 返回 BUS_BACKPRESSURE 或排队状态，并可观测积压。 |
| 消费失败 | 按策略重试，耗尽后进入 DLQ 并发布失败投影。 |
| 重复事件 | 消费者幂等处理，不重复创建 Task 或副作用。 |
| streamRef 失效 | Gateway 返回流不可用并允许 GetTask 查询。 |
| 终态投影丢失 | 可通过 taskId 查询 runtime 或重建投影。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| Event Bus 执行 Agent | 总线不调用模型、工具、memory、knowledge 或业务 Agent。 |
| Task 权威状态 | Event Bus 不保存 runtime TaskStore，不决定 Task 终态。 |
| token 流承载 | 不通过总线传 token chunk、SSE frame 或大对象正文。 |
| 物理 endpoint 暴露 | routeHandle 不等于 endpoint，不向 client 暴露内部拓扑。 |
| 强一致编排 | 总线投影是异步事实，不替代 runtime Task 查询。 |
| 私有 runtime 入口 | 消费者应投递标准 A2A 或等价 Task control path。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为客户端调用总线转发事实来源，Event Bus 只能承载控制事件、状态投影和引用。
- Gateway、Event Bus、worker、agent-service 和 runtime 必须共享 clientInvocationId、taskId、routeHandle、payloadRef、streamRef、schemaVersion、eventId 和幂等语义。
- 测试必须覆盖异步入队、接受投影、流准备、等待输入、续接、取消、终态、背压、重试、死信、重复事件、大载荷引用和 token 不入总线。
- 开发指南不得把 Event Bus 描述为 Agent 执行引擎或 TaskStore。
- 任何对总线承载 token 流、执行 Agent、保存业务 checkpoint 或绕过 runtime 标准入口的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：CLIENT_INVOCATION_REQUESTED、INVOCATION_ACCEPTED、INVOCATION_STREAM_READY、INVOCATION_INPUT_REQUIRED、INVOCATION_TERMINAL、clientInvocationId、payloadRef、streamRef、DLQ。

## 7. 关联文档

- `agent-sdk/Docs/agent-gateway组件客户端调用总线转发特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-011-agent-gateway-client-invocation-route-forwarding.md`
- `Docs/FEAT_Design/FEAT-2026-017-agent-runtime-bus-event-subscription-consumption.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`
