---
version: 0715
module: agent-bus
feature_type: functional
feature_id: FEAT-2026-012
status: active
---
# agent-gateway 组件客户端调用总线转发特性文档

## 1. 特性定位

FEAT-2026-012 定义 `agent-gateway` 当前版本把客户端调用事件化转发到 `Event Bus` 的事实：Gateway 可以将 agent-client 的异步调用标准化为控制事件，返回或复用 `clientInvocationId`，由后端 worker / agent-service 消费事件并投递到 runtime 标准入口，再通过状态投影事件把接受、流准备、等待输入和终态反馈给客户端。

本特性解决的问题是：长任务或流式任务不适合让客户端同步等待目标 runtime。Gateway 需要提供事件化入队、`clientInvocationId` 关联、幂等恢复、payloadRef、大载荷引用、状态投影和流桥接能力；Event Bus 只承载控制事件、状态投影和引用，不执行 Agent、不承载 token chunk、不持有 Task 权威状态。

在总体架构中，本特性位于 agent-client、Gateway、Event Bus、forwarding worker / agent-service 和 agent-runtime 之间。Gateway 是客户端入口和投影交付者，Event Bus 是发布订阅与缓存设施，runtime 仍是 Task owner。

本特性面向以下角色：

- Gateway 开发者：实现异步入队、clientInvocationId 关联、事件发布、状态投影交付和流桥接。
- Event Bus 集成方：提供发布订阅、缓存、投递重试和失败可观测。
- forwarding worker / agent-service 开发者：消费事件并投递 runtime 标准入口。
- agent-client 接入方：使用 clientInvocationId 观察异步调用状态，并在 UNKNOWN 后以同一幂等键恢复。
- 测试与验收团队：验证入队、接受、流准备、input_required、终态、取消、幂等和大载荷引用。

本特性只定义客户端调用的总线转发通道。直接路由转发由 FEAT-2026-011 承接；runtime worker 如何消费事件由 FEAT-2026-017 承接；Agent 执行、任务接续控制和 TaskStore 不属于 Event Bus 或 Gateway 职责。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 异步调用入队 | MUST | Client 必须携带或获得 `clientInvocationId` 作为 Gateway 侧调用关联；Gateway 发布 `CLIENT_INVOCATION_REQUESTED`，并向 client 返回可用于后续状态观察和幂等恢复的关联信息。 |
| 外层事件信封 | MUST | 调用事件必须携带 tenant、user、session、correlation、routeHandle、deadline、幂等键和 payload 描述；A2A JSON-RPC 只能作为 payload 或 payloadRef 出现。 |
| 大载荷引用 | MUST | 大输入、大结果、文件、多模态内容必须使用 payloadRef / artifactRef，不写入 Event Bus 事件正文。 |
| 服务端接受投影 | MUST | runtime 创建或复用 Task 后，消费者必须发布 `INVOCATION_ACCEPTED`，并携带 `taskId`、correlation 和幂等结果；`clientInvocationId` 不得替代 `taskId`。 |
| 流准备投影与桥接 | MUST | 流式任务必须通过 `INVOCATION_STREAM_READY` 表达可订阅事实；Gateway 仅在 client 连接存在或 client 后续基于 `taskId` 订阅时桥接 A2A SSE，Event Bus 不承载 token 流。 |
| 等待输入投影 | MUST | runtime 的 INPUT_REQUIRED 必须可投影为 `INVOCATION_INPUT_REQUIRED`；Gateway 只把新的 input_required 状态、所需输入描述和关联信息交付给 client，不负责管理任务接续状态机。 |
| 终态投影 | MUST | completed、failed、canceled、rejected 等结果必须通过 `INVOCATION_TERMINAL` 或等价投影表达；Task 终态仍由 runtime 拥有。 |
| UNKNOWN 与幂等恢复 | MUST | Gateway 在接受等待窗口内无法确认 runtime 是否创建 Task 时，必须返回 `UNKNOWN` 或等价状态，并提供同一 `idempotencyKey` / `clientInvocationId` 恢复路径；重复提交不得创建多个 Task。 |
| 取消请求事件 | MUST | Gateway 必须能发布 `CLIENT_INVOCATION_CANCEL_REQUESTED`；消费者最终将请求映射到 Task owner 的 `CancelTask`，不得只在总线侧标记取消。 |
| Event Bus 不执行 Agent | MUST | Event Bus 不得执行 Agent、调用模型、运行工具、保存业务 checkpoint 或决定 Task 终态。 |
| token 流入总线 | OUT | 当前版本不允许 token-by-token、SSE frame 或大对象正文进入 Event Bus。 |

## 3. 外部接口与入口要求

| 入口                                   | 类型               | 事实要求                                                                                    |
| -------------------------------------- | ------------------ | ------------------------------------------------------------------------------------------- |
| `CLIENT_INVOCATION_REQUESTED`        | Event Bus 控制事件 | 表示客户端调用已入队，携带 clientInvocationId、tenant、routeHandle、payloadRef 和幂等信息；client 可用该关联观察投影状态。 |
| `INVOCATION_ACCEPTED`                | Event Bus 投影事件 | 表示 runtime / 目标服务已接受调用，必须携带 taskId。                                        |
| `INVOCATION_STREAM_READY`            | Event Bus 投影事件 | 表示流可订阅，携带 streamRef 或桥接所需引用，不携带 token。                                 |
| `INVOCATION_INPUT_REQUIRED`          | Event Bus 投影事件 | 表示 runtime Task 进入等待输入状态，必须携带 taskId、correlation 和输入需求描述；Gateway 只负责向 client 呈现该新状态。 |
| `CLIENT_INVOCATION_CANCEL_REQUESTED` | Event Bus 控制事件 | 表示客户端请求取消，由消费者映射到 CancelTask。                                             |
| `INVOCATION_TERMINAL`                | Event Bus 投影事件 | 表示任务 completed、failed、canceled 或 rejected。                                          |
| payloadRef / artifactRef               | 引用字段           | 指向大载荷或结果，不要求总线保存正文。                                                      |
| clientInvocationId                     | 关联 id            | 由 client 携带或由 Gateway 生成 / 复用，用于入队、状态观察和幂等恢复，不能替代 taskId。       |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 异步提交长任务 | Gateway 和 Event Bus 可用，client 已生成或请求 Gateway 生成 `clientInvocationId` | client 带着 `clientInvocationId`、tenant、幂等键和 payloadRef 访问 Gateway；Gateway 发布 `CLIENT_INVOCATION_REQUESTED` | Gateway 返回入队接受或明确失败；后续 runtime 接受、流准备、input_required 和终态通过投影关联到同一 `clientInvocationId`，client 以该关联观察调用进展。 |
| 流式任务观察 | runtime 支持 A2A SSE，服务端已接受 Task 或稍后发布流准备投影 | 消费者发布 `INVOCATION_ACCEPTED` 和 `INVOCATION_STREAM_READY`；client 维持连接或后续基于 `taskId` 订阅 | Gateway 根据 `taskId` 和 streamRef 桥接服务端 A2A SSE；Event Bus 只转发流准备事实和引用，不承载 token chunk。 |
| input_required 投影处理 | runtime Task 进入 INPUT_REQUIRED，且服务端发布 `INVOCATION_INPUT_REQUIRED` | Gateway 消费 input_required 投影并按 `clientInvocationId` / `taskId` 通知 client | Gateway 只呈现新的 input_required 状态、输入需求和关联信息；任务接续、输入校验和 Task 状态推进由 runtime 控制管理。 |
| 取消异步任务 | client 已获得服务端 `taskId`，或可通过 `clientInvocationId` 关联到已接受 Task | client 通过 Gateway 发起取消请求 | Gateway 发布 `CLIENT_INVOCATION_CANCEL_REQUESTED`；消费者定位 Task owner 并调用 `CancelTask`；runtime 决定取消结果并发布终态或错误投影。 |
| 接受状态未知与幂等恢复 | Gateway 已发布调用事件，但在接受等待窗口内未观察到接受、拒绝或失败投影 | Gateway 向 client 返回 `UNKNOWN` 或等价状态；client 使用同一 `idempotencyKey` / `clientInvocationId` 重试或继续观察 | 若 runtime 已创建 Task，重复投递必须幂等返回同一 `taskId` 或接受投影；若未创建，则按新投递创建或明确拒绝，不得因重试创建多个 Task。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 入队与关联语义

- `clientInvocationId` 表示 Gateway 入队和投影关联，不表示 runtime 已创建 Task。
- 只有 `INVOCATION_ACCEPTED` 携带的 taskId 才是 runtime Task 句柄。
- Client 可以携带 `clientInvocationId` 访问 Gateway；Gateway 也可以在缺省时生成并返回该关联。
- 重复提交必须通过同一幂等键和 clientInvocationId 避免重复副作用。

#### 5.1.1 事件投影语义

- Event Bus 事件表达调用控制和状态投影，不表达 runtime 内部状态机全部细节。
- 流式内容、token、SSE frame 和大对象正文必须留在 runtime stream 或引用对象中。
- 投影事件必须携带 tenant、correlation、trace、schemaVersion、clientInvocationId 或可映射关联、taskId 和时间戳，便于审计和恢复。

#### 5.1.2 状态观察、取消和 input_required 语义

- 状态观察应区分 queued、accepted、stream_ready、input_required、terminal、rejected、failed 和 unknown。
- 取消请求必须最终路由到 Task owner，不得只在总线侧标记取消。
- input_required 是 runtime Task 状态投影；Gateway 只负责把新的等待输入状态和输入需求交付给 client。
- 任务接续、输入校验、callbackId 解释和 Task 状态推进由 runtime 或目标服务控制管理，不属于 Gateway 或 Event Bus 职责。
- `clientInvocationId` 不得作为 A2A `GetTask` / `SubscribeToTask` 的标准输入，也不得替代 `taskId`。

#### 5.1.3 UNKNOWN 与幂等语义

- Gateway 发布调用事件后，如果在接受等待窗口内未观察到 `INVOCATION_ACCEPTED`、`INVOCATION_REJECTED` 或 `INVOCATION_FAILED`，必须返回 `UNKNOWN` 或等价未知状态。
- `UNKNOWN` 不表示成功或失败，只表示 Gateway 无法确认 runtime 是否已创建 Task。
- Client 可以使用同一 `idempotencyKey` / `clientInvocationId` 重试原调用或继续观察投影。
- runtime 或消费者必须以 `tenantId + idempotencyKey` 约束创建类调用；已创建 Task 时应返回同一 `taskId` 或等价接受投影。

#### 5.1.4 错误、状态与可观测结果

| 场景           | 事实要求                                              |
| -------------- | ----------------------------------------------------- |
| 入队失败       | Gateway 返回明确错误，不生成成功 clientInvocationId。 |
| 消费失败       | 按策略重试，仍失败时发布失败投影或可观测错误。           |
| 重复事件       | 消费者幂等处理，不重复创建 Task 或副作用。            |
| streamRef 失效 | Gateway 返回流不可用并允许 GetTask 查询。             |
| input_required 投影 | Gateway 向 client 呈现等待输入状态，不推进 runtime Task。 |
| 接受阶段超时 | 返回 UNKNOWN 或等价未知状态，允许 client 用同一幂等键恢复。 |
| 终态投影丢失   | 可通过 taskId 查询 runtime 或重建投影。               |

### 5.2 显式边界与不承诺项

| 边界                 | 当前版本不承诺                                          |
| -------------------- | ------------------------------------------------------- |
| Event Bus 执行 Agent | 总线不调用模型、工具、memory、knowledge 或业务 Agent。  |
| Task 权威状态        | Event Bus 不保存 runtime TaskStore，不决定 Task 终态。  |
| token 流承载         | 不通过总线传 token chunk、SSE frame 或大对象正文。      |
| 物理 endpoint 暴露   | routeHandle 不等于 endpoint，不向 client 暴露内部拓扑。 |
| 强一致编排           | 总线投影是异步事实，不替代 runtime Task 查询。          |
| 私有 runtime 入口    | 消费者应投递标准 A2A 或等价 Task control path。         |
| 任务接续控制         | Gateway 和 Event Bus 不管理 input_required 后的 Task 状态推进。 |
| 调度治理策略         | 本特性不定义运行时调度治理策略。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为客户端调用总线转发事实来源，Event Bus 只能承载控制事件、状态投影和引用。
- Gateway、Event Bus、worker、agent-service 和 runtime 必须共享 clientInvocationId、taskId、routeHandle、payloadRef、streamRef、schemaVersion、eventId、correlation 和幂等语义。
- 测试必须覆盖带 clientInvocationId 的异步入队、接受投影、流准备、input_required 投影、取消、终态、UNKNOWN 后幂等恢复、重复事件、大载荷引用和 token 不入总线。
- 开发指南不得把 Event Bus 描述为 Agent 执行引擎或 TaskStore。
- 开发指南不得把 Gateway 描述为 input_required 接续控制器；接续控制和 Task 状态推进属于 runtime 或目标服务。
- 任何对总线承载 token 流、执行 Agent、保存业务 checkpoint、承担运行时调度治理或绕过 runtime 标准入口的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：CLIENT_INVOCATION_REQUESTED、INVOCATION_ACCEPTED、INVOCATION_STREAM_READY、INVOCATION_INPUT_REQUIRED、INVOCATION_TERMINAL、UNKNOWN、clientInvocationId、idempotencyKey、payloadRef、streamRef。

## 7. 关联文档

- `agent-sdk/Docs/agent-gateway组件客户端调用总线转发特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-011-agent-gateway-client-invocation-route-forwarding.md`
- `JAVA local working/version-scope/FEAT-013-client-invocation-event-forwarding.md`
- `Docs/FEAT_Design/FEAT-2026-017-agent-runtime-bus-event-subscription-consumption.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`
