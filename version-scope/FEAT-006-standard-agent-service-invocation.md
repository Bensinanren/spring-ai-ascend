---
version: 0715
module: agent-client
feature_type: functional
feature_id: FEAT-2026-006
status: active
---

# Agent-client 组件标准化智能体服务调用特性文档

## 1. 特性定位

FEAT-2026-006 定义 `agent-client` 当前版本作为业务应用侧标准 Agent 服务调用 SDK 的入口事实：业务应用必须通过统一 client facade 提交智能体调用、观察执行过程、查询任务、取消任务和提交续接输入，而不是直接感知后端 `agent-runtime`、`agent-service`、`proxy-service` 或 A2A JSON-RPC 细节。

本特性解决的问题是：不同业务应用需要用同一套客户端调用模型接入智能体平台。应用侧只应看到 submit、stream、subscribe、getTask、cancel、resume 等稳定能力；平台侧可以通过 Gateway、IngressGateway、agent-service、proxy-service 或 runtime 标准 A2A 入口完成路由和执行。客户端本地只保存调用游标、订阅位置、幂等键和 UI 投影，不拥有服务端 Task 权威状态。

对总体设计而言，本特性是 C/S 流量进入智能体框架的客户端 SDK 入口约束。`agent-client` 位于 application 与 agent-bus / Gateway 之间，负责把业务输入、租户、用户、会话、幂等键和流式订阅意图标准化；服务端 Task、SSE、GetTask、CancelTask、SubscribeToTask 等事实语义由 `agent-runtime` 和 Gateway 投影承接。客户端 facade 可以是 REST 风格或 SDK 方法，但不能形成独立于 A2A Task 的第二套状态机。

本特性面向以下角色：

- 业务应用开发者：通过 `agent-client` 调用智能体服务并展示任务状态。
- 企业终端集成方：把业务页面、用户会话、租户上下文接入标准调用链路。
- agent-client SDK 开发者：实现调用 facade、SSE 消费、轮询、重订阅、取消和续接。
- Gateway / IngressGateway 开发者：把 client facade 请求映射到标准平台入口。
- agent-runtime 开发者：承接标准 A2A Task / SSE / CancelTask / SubscribeToTask 语义。
- 测试与验收团队：按客户端外部行为设计黑盒和集成验证。

本特性只定义 `agent-client` 面向业务应用的标准调用入口和本地调用投影。服务端 Agent Card、A2A JSON-RPC、Task 权威状态、runtime-to-runtime webhook、agent-bus forwarding 和端侧工具结果恢复由对应 runtime、gateway、bus 和 client capability 特性承接。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 标准调用 facade | MUST | `agent-client` 必须向业务应用提供统一调用入口，至少覆盖提交调用、流式订阅、状态查询、取消和续接输入。 |
| 业务上下文传递 | MUST | SDK 必须允许请求携带 tenant、user、session、agent/capability 标识、correlation、trace 和幂等键，并传递到 Gateway 或 IngressGateway。 |
| Gateway 入口对接 | MUST | SDK 请求必须进入受治理平台入口，不得要求业务应用直接调用 runtime 内部 API。 |
| Task 句柄区分 | MUST | SDK 必须区分 `clientInvocationId`、runtime `taskId`、本地 cursor / lastEventId；不得把客户端 id 当作服务端 Task id。 |
| 流式观测 | MUST | SDK 必须支持消费 SSE 或等价服务流，并把服务端事件映射为客户端可见状态。 |
| 阻塞调用 | MUST | SDK 必须支持适合一次性响应的调用模式；阻塞结果仍必须来自服务端标准 Task / Message 表面或 Gateway 投影。 |
| 异步查询 | MUST | SDK 必须支持按 `taskId` 或受治理映射查询服务端任务状态。 |
| 取消任务 | MUST | SDK 必须提供 cancel 能力，并通过 Gateway 映射到 runtime `CancelTask` 或等价 Task control path。 |
| 断线重订阅 | MUST | SDK 必须支持基于 `taskId`、cursor、lastEventId 或 clientInvocationId 恢复观察；断线不得被直接解释为任务失败。 |
| 用户续接输入 | MUST | SDK 必须支持提交用户补充输入、本地确认或客户端能力结果，并恢复等待中的服务端 Task。 |
| 幂等与状态未知处理 | MUST | SDK 必须支持提交幂等键，并能处理 Gateway 接受后尚未确认 runtime taskId 的 UNKNOWN 状态。 |
| 错误分类 | MUST | SDK 必须区分网络错误、路由错误、服务端 JSON-RPC / Task 错误、业务失败、取消和状态未知。 |
| 本地状态边界 | MUST | SDK 只能保存调用投影、订阅游标和待续接状态；不得实现服务端 TaskStore。 |
| 大载荷引用 | MUST | 大输入、大结果、文件或多模态内容必须支持 `payloadRef` / `artifactRef`，不得强制进入客户端内存或总线事件正文。 |
| 多轮会话 | SHOULD | SDK 应支持 session/context 复用，把后续输入提交到同一业务会话或服务端 Task 续接入口。 |
| 客户端任意 webhook | OUT | 当前版本不承诺普通业务应用通过 SDK 注册任意 webhook URL 让 runtime 主动回调。 |
| 直接 runtime 私有调用 | OUT | 当前版本不要求 SDK 暴露 runtime 内部 SPI、TaskStore 或 Agent-core 调用能力。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| `agentClient.submit` | SDK facade | 提交一次标准 Agent 调用，返回 accepted、clientInvocationId、taskId 或明确错误。 |
| `agentClient.stream` / `subscribe` | SDK facade | 建立服务流消费，映射 Gateway / runtime SSE 事件，并维护 cursor 或 lastEventId。 |
| `agentClient.getTask` | SDK facade | 查询服务端 Task 或 Gateway 受治理投影，不得只返回本地缓存状态。 |
| `agentClient.cancel` | SDK facade | 发起取消请求，由平台侧转发到 runtime `CancelTask` 或 proxy 等价取消接口。 |
| `agentClient.resume` | SDK facade | 提交用户补充输入、确认结果或客户端能力结果，用于恢复等待中的 Task。 |
| `agentClient.resubscribe` | SDK facade | 在断线后按 taskId、clientInvocationId、cursor 或 lastEventId 恢复观察。 |
| `tenantId` / `userId` / `sessionId` | 请求上下文 | 必须能随 SDK 请求进入平台入口，成为 runtime execution context、日志和状态关联来源。 |
| `clientInvocationId` | 客户端关联 id | Gateway 接受调用后可返回，用于入队、路由、状态未知和幂等恢复；不能替代 runtime taskId。 |
| `taskId` | 服务端任务 id | runtime 接受调用后返回，是查询、取消、订阅和续接服务端 Task 的权威句柄。 |
| `payloadRef` / `artifactRef` | 引用字段 | 用于传递大输入、大结果、文件、多模态内容或 artifact，不要求 SDK 直接承载正文。 |
| Gateway / IngressGateway endpoint | HTTP/SSE 入口 | SDK 的跨平面请求必须进入受治理入口，由其完成认证、租户、路由、幂等和协议映射。 |
| runtime A2A endpoint | 平台标准入口 | Gateway 或目标服务最终应映射到 `/a2a`、Task、SSE、GetTask、CancelTask、SubscribeToTask 等标准语义。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 业务应用发起标准调用 | 应用已集成 SDK，平台入口可用 | 应用调用 `submit` 并携带业务输入、tenant、user、session 和幂等键 | SDK 标准化请求并提交 Gateway；调用被接受后返回 clientInvocationId 或 taskId；服务端 Task 由 runtime 持有。 |
| 流式观察长任务 | 调用方需要实时状态，目标 runtime 支持 SSE | 应用调用 `stream` 或 `subscribe` | SDK 消费 SSE / 服务流，将 submitted、working、input_required、completed、failed、canceled 映射为业务可见状态。 |
| 阻塞获取结果 | 请求规模适合一次性响应 | 应用调用阻塞 facade | SDK 返回服务端 Task / Message 表面或 Gateway 投影；超时或状态未知时给出可恢复错误。 |
| 断线恢复观察 | 客户端 SSE 中断但持有 taskId 或 cursor | 应用调用 `resubscribe` 或 `getTask` | SDK 重新订阅或查询服务端 Task；断线本身不改变 Task 终态。 |
| 用户续接输入 | Task 进入 INPUT_REQUIRED 或等待确认 | 用户在业务界面补充信息或确认动作 | SDK 通过 `resume` 主动提交输入，平台侧恢复原 Task 或返回过期、拒绝、终态冲突。 |
| 客户端取消任务 | 用户希望停止执行中任务 | 应用调用 `cancel` | SDK 通过 Gateway 转发取消请求；runtime 尽力取消并返回 canceled 或当前 Task 状态。 |
| 状态未知重试 | Gateway 已接受但 runtime taskId 尚未确认 | SDK 使用 clientInvocationId 和幂等键查询或重试 | 平台返回已接受、已创建 taskId、失败、死信或 UNKNOWN；重复请求不得产生重复副作用。 |
| 多租户业务接入 | 前置网关已认证调用方 | SDK 携带或接收 `tenantId` 并提交请求 | 平台入口将租户标识纳入 route、execution context、日志和状态关联；SDK 不自行认证租户身份。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 客户端入口等价语义

- 不同业务应用通过 `agent-client` 调用 Agent 时，应看到同一组调用、观察、查询、取消和续接语义。
- SDK 可以根据部署选择 HTTP、SSE、Gateway facade 或总线异步入口，但客户端状态必须最终对齐服务端 Task。
- SDK 不得因为目标是 agent-service、proxy-service 或 runtime 而暴露互相漂移的业务状态机。

#### 5.1.1 调用提交语义

- 提交请求必须携带可关联的业务上下文、幂等键和输入内容。
- Gateway 接受请求后可以先返回 `clientInvocationId`；runtime 创建 Task 后才返回 `taskId`。
- 请求未确认是否被 runtime 接受时，SDK 必须进入 UNKNOWN 或可恢复状态，而不是伪造成功或失败。

#### 5.1.2 流式观测语义

- SDK 消费到的流式事件必须映射为客户端可理解状态，但状态来源仍是 Gateway / runtime 投影。
- SSE 中断不等于 Task 失败；SDK 应优先使用重订阅或查询恢复。
- 流式 token、progress、artifact 引用和终态必须与 runtime Task/SSE 语义保持一致。

#### 5.1.3 查询与取消语义

- `getTask` 必须查询服务端 Task 或受治理投影，不得只读取本地调用对象。
- `cancel` 必须映射到 runtime CancelTask 或等价 Task control path；底层执行是否立即中断由 runtime/adapter 能力决定。
- Task 进入 completed、failed、canceled 等终态后，SDK 不得继续提交普通续接输入。

#### 5.1.4 续接输入语义

- 用户补充输入、本地确认和客户端能力结果都必须由客户端主动提交。
- 续接请求必须携带 taskId、sessionId、callbackId、correlationId 或等价上下文，便于 runtime 校验恢复点。
- 过期、重复、跨租户、跨用户或终态后的续接必须返回明确错误。

#### 5.1.5 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 网络失败 | SDK 返回可重试网络错误，并保留幂等键用于恢复判断。 |
| 路由失败 | SDK 暴露 route not found、permission denied、service unavailable 等平台错误。 |
| 服务端失败 | SDK 展示 failed Task 或 JSON-RPC error，不把业务失败包装成网络失败。 |
| 接受状态未知 | SDK 返回 UNKNOWN，并允许按 clientInvocationId 查询或幂等重试。 |
| SSE 中断 | SDK 进入可恢复观察状态，可重订阅或查询。 |
| 用户取消 | SDK 转发取消请求，并以服务端 Task 状态为准。 |
| 大结果返回 | SDK 使用 artifactRef / payloadRef 展示或后续拉取，不强制内联正文。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 服务端 TaskStore | `agent-client` 不保存或复制服务端 Task 权威状态。 |
| 直接内部调用 | SDK 不直接调用 agent-runtime、agent-core、agent-middleware 内部 SPI。 |
| 客户端任意 webhook | 当前版本不承诺普通业务应用注册任意 webhook URL 接收 runtime 主动推送。 |
| 租户认证 | SDK 不认证租户身份；租户认证、清洗和注入由 Gateway 或企业入口完成。 |
| Agent 执行 | SDK 不执行 Agent 推理、工具调用、记忆、知识库或模型访问。 |
| 强制取消底层模型 | SDK cancel 不承诺强制中断已进入模型客户端的阻塞调用。 |
| 多 Agent 编排 | SDK 只发起和观察调用，不定义服务端多 Agent 路由、聚合和调度策略。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为业务应用侧标准 Agent 调用入口事实来源，不得把 `agent-client` 降级为示例代码或直接 runtime API 包装。
- `agent-client` SDK、Gateway facade、IngressGateway、agent-bus 转发和 runtime A2A 入口必须保持同一套 Task、SSE、取消、续接和错误语义。
- SDK 状态模型必须显式区分 clientInvocationId、taskId、cursor、lastEventId 和本地 UI 投影。
- 测试必须覆盖标准提交、流式观察、阻塞响应、异步查询、取消、断线重订阅、用户续接、状态未知、幂等重试、大载荷引用和错误分类。
- 开发指南不得要求业务应用理解 runtime 内部 handler、TaskStore、Agent-core loop 或 agent-middleware 工具实现。
- 任何对普通 client webhook、直接 runtime 私有调用、客户端 TaskStore、租户认证或服务端编排能力的新增承诺，都必须先回到本特性或新的 version-scope 特性文档更新事实要求。
- 本特性使用的术语必须保持稳定：agent-client、Gateway、IngressGateway、clientInvocationId、taskId、cursor、SSE、Task、Resume、Cancel、Tenant、PayloadRef。

## 7. 关联文档

- `agent-sdk/Docs/Agent-client组件标准化智能体服务调用特性设计.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`
- `Docs/FEAT_Design/FEAT-2026-011-agent-gateway-client-invocation-route-forwarding.md`
- `Docs/FEAT_Design/FEAT-2026-012-agent-gateway-client-invocation-bus-forwarding.md`