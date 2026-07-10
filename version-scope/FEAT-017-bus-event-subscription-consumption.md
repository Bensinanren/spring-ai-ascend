---
version: 0715
module: agent-runtime
feature_type: functional
feature_id: FEAT-2026-017
status: active
---

# Agent-runtime 组件订阅消费总线事件消息特性文档

## 1. 特性定位

FEAT-2026-017 定义承载 `agent-runtime` 的 service / worker 在当前版本订阅消费 agent-bus 事件的事实：worker 可以从 Event Bus 消费客户端调用、续接、取消和端侧能力结果事件，并将这些事件投递到 runtime 标准 A2A 入口或等价 Task control path，再把接受、流准备、等待输入和终态投影回总线。

本特性解决的问题是：总线异步调用进入 runtime 时，不能绕过 runtime 当前标准服务入口和 Task 状态机。worker / adapter 是投递适配器，不是新的 Agent 执行 SPI；它负责 schema 校验、幂等、租户上下文、routeHandle 校验、A2A request mapping、背压和投影事件发布。runtime 仍持有 Task 生命周期，Event Bus 仍只保存事件投影。

在总体架构中，本特性位于 Event Bus、runtime forwarding worker / adapter、agent-runtime A2A 服务入口和 AgentRuntimeHandler 之间。它与 FEAT-2026-012 构成总线转发闭环：Gateway 发布客户端事件，worker 消费并调用 runtime，worker 再发布 invocation 投影供 Gateway / client 观察。

本特性面向以下角色：

- Runtime 模块开发者：保证总线事件投递后仍对齐 A2A Task / SSE / CancelTask 语义。
- worker / adapter 开发者：实现事件消费、幂等、A2A mapping 和投影发布。
- Event Bus 集成方：提供投递、重试、分区、DLQ 和可观测能力。
- agent-service 开发者：承载 runtime 与 worker 的部署和配置。
- 企业异步任务运维方：配置并发、租户配额、背压、重试和告警。
- 测试与验收团队：验证事件消费、Task 接受、流准备、续接、取消、死信和重复事件。

本特性不要求 runtime 直接依赖 agent-bus，也不定义 runtime 私有 bus 执行口。worker 可以进程内或进程外部署，但投递语义必须对齐 runtime 标准入口。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 调用事件消费 | MUST | worker 必须能消费 `CLIENT_INVOCATION_REQUESTED` 并完成 schema、tenant、routeHandle 和幂等校验。 |
| 标准入口投递 | MUST | worker 必须优先将调用映射为 runtime `/a2a SendStreamingMessage` 或等价 Task control path。 |
| 接受投影 | MUST | runtime 创建或复用 Task 后，worker 必须发布 `INVOCATION_ACCEPTED` 和 taskId。 |
| 流准备投影 | MUST | 流式执行可用时，worker 必须发布 `INVOCATION_STREAM_READY` 或 streamRef，供 Gateway 桥接 SSE。 |
| INPUT_REQUIRED 投影 | MUST | runtime 等待输入或端侧能力结果时，worker 必须发布 `INVOCATION_INPUT_REQUIRED`。 |
| 用户续接消费 | MUST | worker 必须消费 `CLIENT_INPUT_PROVIDED` 并恢复等待中的 Task。 |
| 端侧结果消费 | MUST | worker 必须消费 `CLIENT_CAPABILITY_RESULT_PROVIDED` 并投递给 runtime pending call 恢复路径。 |
| 取消事件消费 | MUST | worker 必须消费 `CLIENT_INVOCATION_CANCEL_REQUESTED` 并调用 `CancelTask` 或等价取消入口。 |
| 幂等处理 | MUST | worker 必须基于 eventId、clientInvocationId、taskId、callbackId 和幂等键避免重复执行。 |
| 背压治理 | SHOULD | worker 应按 maxConcurrency、租户配额和运行健康限制消费速度。 |
| 死信处理 | SHOULD | 不可恢复错误或重试耗尽事件应进入 DLQ 并形成可观测告警。 |
| 私有 bus 执行入口 | OUT | 当前版本不允许 worker 绕过 runtime Task 状态机直接调用业务 Agent。 |
| token 写入总线 | OUT | 当前版本不允许 worker 把 token-by-token 或 SSE frame 写入 Event Bus。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| `CLIENT_INVOCATION_REQUESTED` | 订阅事件 | worker 消费用于创建或投递 runtime Task 的客户端调用事件。 |
| `/a2a SendStreamingMessage` | runtime 标准入口 | worker 投递调用的主路径，保持 A2A Task / SSE 语义。 |
| `CLIENT_INPUT_PROVIDED` | 订阅事件 | worker 消费用于恢复 INPUT_REQUIRED 等等待输入 Task。 |
| `CLIENT_CAPABILITY_RESULT_PROVIDED` | 订阅事件 | worker 消费用于恢复端侧工具 pending call。 |
| `CLIENT_INVOCATION_CANCEL_REQUESTED` | 订阅事件 | worker 消费用于调用 runtime CancelTask。 |
| `INVOCATION_ACCEPTED` | 发布事件 | runtime 接受任务后的投影，必须携带 taskId。 |
| `INVOCATION_STREAM_READY` | 发布事件 | runtime 流可桥接时的投影，必须只携带 streamRef 或引用。 |
| `INVOCATION_INPUT_REQUIRED` | 发布事件 | runtime 等待输入或端侧结果时的投影。 |
| `INVOCATION_TERMINAL` | 发布事件 | runtime Task completed、failed、canceled、rejected 后的投影。 |
| idempotent store | worker 状态 | 记录 eventId、clientInvocationId、taskId、callbackId 与处理结果。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 异步调用事件驱动执行 | Gateway 已发布调用事件 | worker 消费 `CLIENT_INVOCATION_REQUESTED` | worker 校验并投递 runtime 标准入口，发布 accepted、stream-ready 和 terminal 投影。 |
| 等待输入恢复 | runtime 已投影 INPUT_REQUIRED | client 提交 `CLIENT_INPUT_PROVIDED` | worker 校验 Task 可恢复并投递 resume 路径，runtime 继续执行。 |
| 端侧能力结果恢复 | runtime 存在 pending client tool call | client 提交 capability result 事件 | worker 校验 callbackId / deadline，将结果交给 runtime 恢复 Task。 |
| 取消异步任务 | client 发布取消事件 | worker 消费取消请求 | worker 定位 Task owner 并调用 CancelTask，发布取消或当前状态投影。 |
| 批量背压消费 | Event Bus 积压大量事件 | worker 按租户配额拉取 | 超出并发时停止抢占，重试失败进入 DLQ，重复事件不重复创建 Task。 |
| worker 崩溃恢复 | 事件重投或处理结果不明 | worker 重启后重新消费 | 通过幂等记录查询已有 Task 或恢复投影，不重复执行副作用。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 事件投递语义

- worker 消费事件后必须先做 schemaVersion、tenant、routeHandle、payloadRef、幂等和权限上下文校验。
- 调用事件应映射到 runtime 标准 A2A `/a2a` 或等价 Task control path。
- worker 可以作为部署适配器存在，但不改变 runtime 服务端 Task 语义。

#### 5.1.1 投影发布语义

- runtime 接受 Task 后发布 `INVOCATION_ACCEPTED`，终态后发布 `INVOCATION_TERMINAL`。
- 流式输出只发布 stream-ready 引用，实际 SSE 由 runtime / Gateway 桥接。
- INPUT_REQUIRED 和端侧工具等待必须投影为客户端可见事件，但 Event Bus 不保存业务恢复点正文。

#### 5.1.2 幂等、重试和背压语义

- eventId 用于事件投递去重，clientInvocationId 用于客户端调用关联，taskId 用于 runtime Task。
- 重试事件若 Task 已创建，worker 应查询或重订阅现有 Task，不重复创建。
- 不可恢复错误或重试耗尽应进入 DLQ，并发布可观察失败或告警。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| schema invalid | NACK 或失败投影，不投递 runtime。 |
| routeHandle 无效 | 返回 route_invalid 或 service_unavailable。 |
| runtime not ready | 可重试 NACK 或发布可重试失败投影。 |
| 重复调用事件 | 返回已有 taskId 或已处理结果。 |
| 续接任务已终态 | 返回 task_already_terminal，不恢复。 |
| 取消失败 | 发布当前 Task 状态或明确错误。 |
| DLQ | 保留 eventId、原因、tenant、clientInvocationId 和重试次数。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| runtime 私有 bus 入口 | worker 不绕过 A2A Task/SSE/error 语义直接执行 Agent。 |
| Event Bus TaskStore | 总线和 worker 不保存服务端 Task 权威状态。 |
| token 流投递 | 不把 token chunk、SSE frame 或大对象正文写入 Event Bus。 |
| 业务 checkpoint | worker 不把 Event Bus 当作 Agent memory、state 或 checkpoint。 |
| 强制取消底层模型 | 取消事件不承诺立即中断已进入模型客户端的阻塞调用。 |
| runtime 直接依赖 bus | runtime 可通过 worker/adapter 集成，不要求核心 runtime 直接依赖 agent-bus。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为 runtime 消费总线事件的事实来源，worker 是投递适配器，不是新的 Agent 执行入口。
- Gateway 总线转发、Event Bus、worker、runtime A2A controller 和 AgentRuntimeHandler 必须共享 eventId、clientInvocationId、taskId、routeHandle、schemaVersion、streamRef、callbackId 和幂等语义。
- 测试必须覆盖调用事件消费、A2A 投递、accepted 投影、stream-ready 投影、INPUT_REQUIRED 投影、用户续接、端侧结果恢复、取消、重试、幂等、背压、DLQ 和 token 不入总线。
- 开发指南不得建议 worker 直接调用业务 Agent 绕过 runtime Task 状态机。
- 任何对 runtime 私有 bus 执行口、Event Bus TaskStore、token 总线流或业务 checkpoint 的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：runtime worker、adapter、CLIENT_INVOCATION_REQUESTED、CLIENT_INPUT_PROVIDED、CLIENT_CAPABILITY_RESULT_PROVIDED、CLIENT_INVOCATION_CANCEL_REQUESTED、INVOCATION_ACCEPTED、INVOCATION_STREAM_READY、INVOCATION_TERMINAL、DLQ。

## 7. 关联文档

- `agent-sdk/Docs/Agent-runtime组件订阅消费总线事件消息特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-012-agent-gateway-client-invocation-bus-forwarding.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`