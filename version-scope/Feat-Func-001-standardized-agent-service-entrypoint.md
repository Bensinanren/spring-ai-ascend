---
version: 0715
module: agent-runtime
feature_type: functional
feature_id: Feat-Func-001
status: active
---

# 标准化智能体服务入口特性文档

## 1. 特性定位

Feat-Func-001 定义 `agent-runtime` 当前版本作为标准化 Agent 服务端的入口事实：runtime 必须以 A2A JSON-RPC over HTTP 暴露可发现、可调用、可查询、可取消的 Agent 服务面，以 Agent Card 作为能力发现入口，并通过 A2A Task / Message / SSE 语义承载 Agent 执行过程。

本特性解决的问题是：不同调用来源需要以同一套标准服务入口访问 Agent，而不是按调用方拆出多套私有入口。普通 client、其他 agent-runtime、agent-bus forwarding 在进入 `agent-runtime` 时都必须看到同一个标准化 Agent 服务面：发现 Agent、提交消息、接收流式结果、查询 Task、取消 Task、获得一致错误与状态语义。

对下游设计和实现而言，本特性是 `agent-runtime` inbound 服务入口的范围契约。A2A 是当前版本承载该入口的协议标准，但特性边界不是“A2A 方法清单”本身，而是“runtime 作为 Agent 服务端必须如何被调用和观察”。L2 设计、controller、SDK bridge、agent-bus 转发适配验证、测试和 guide 都必须与这里描述的外部行为一致。

本特性面向以下角色：

- 普通 Agent client：通过 HTTP / JSON-RPC / SSE 调用 Agent。
- 其他 agent-runtime：把本 runtime 当作远端 A2A Agent 调用。
- agent-bus forwarding runtime：把转发消息投递到本 runtime 的标准 Agent 服务入口。
- Agent 开发者：通过 handler 和 Agent Card 声明把自己的 Agent 暴露成 A2A Agent。
- 平台集成方：把 runtime 放到网关、服务发现、租户认证和多 Agent 协作链路中。
- 测试与验收团队：按本特性定义的外部行为和边界设计黑盒场景。

本特性只定义 `agent-runtime` 作为服务端被调用的 inbound 入口。`agent-runtime` 主动发现并代理发起其他 Agent 调用的 outbound 编排语义由 `Feat-Func-005` 承接；具体 handler、adapter、state、memory、trajectory 的内部设计由对应特性和 L2 文档承接。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| A2A Agent Card 发现 | MUST | runtime 必须提供 `GET /.well-known/agent-card.json`，并兼容 `GET /.well-known/agent.json`。返回的 Agent Card 必须能表达 Agent 名称、描述、版本、A2A endpoint、capabilities、skills、input/output modes 等当前版本公开事实。 |
| A2A JSON-RPC 统一入口 | MUST | runtime 必须通过 `POST /a2a` 和 `POST /a2a/` 承载 A2A JSON-RPC 请求，按 JSON-RPC `method` 分发，而不是为每个 A2A method 暴露独立业务 URL。 |
| 普通 client inbound 调用 | MUST | runtime 必须允许外部 client 直接通过标准 A2A 服务入口调用 Agent；该入口不得要求调用方了解底层 Agent 框架。 |
| runtime-to-runtime inbound 调用 | MUST | runtime 必须允许其他 agent-runtime 通过 Agent Card 和 `/a2a` 把本 runtime 当作远端 Agent 调用；该路径与普通 client 共享同一入口语义。 |
| agent-bus forwarding inbound 投递 | MUST | agent-bus 将消息转发到 runtime 时，必须落到同一标准 Agent 服务入口；runtime 侧不得为 agent-bus 暴露另一套私有执行入口。 |
| 流式调用 | MUST | `SendStreamingMessage` 必须作为 Agent 全流程主调用入口，调用方通过 `Accept: text/event-stream` 观察 Task 状态、artifact/progress 和最终终态。 |
| 阻塞调用 | MUST | `SendMessage` 必须接受与流式调用一致的 message 输入，并由 A2A 层收集 handler stream 后返回 JSON-RPC result。 |
| 异步查询 | MUST | `GetTask` 必须允许调用方按 task id 查询 Task 状态和结果。 |
| 取消任务 | MUST | `CancelTask` 必须允许调用方请求取消执行中任务；runtime 必须停止继续消费本次执行结果，并向 Task 表面反映取消语义。底层模型调用是否立即中断由具体 adapter 能力决定。 |
| 任务列表 | MUST | `ListTasks` 必须作为 A2A Task 查询能力的一部分暴露。 |
| 重新订阅 | MUST | `SubscribeToTask` 必须允许调用方按 task id 重新订阅 SSE 事件流。 |
| Push Notification 配置 CRUD | SHOULD | runtime 应支持 A2A SDK 层的 Create/Get/List/Delete push notification config 请求，使协议面完整；实际 webhook 推送不构成本版本事实要求。 |
| Agent Card 配置 | MUST | Agent Card 必须支持由 YAML 配置、handler 默认信息或 `AgentCardProvider` 声明生成；配置中未声明的字段必须有可解释的默认值。 |
| Agent Card skills | MUST | 如果 Agent 希望被其他 Agent 发现并作为工具调用，Agent Card 必须能声明 skills；无 skills 的 Agent Card 不应被远程工具安装链误认为可调用工具集合。 |
| Agent Card capabilities | MUST | Agent Card 必须能声明 streaming、pushNotifications、extendedAgentCard 等 A2A capability 状态；capability 声明必须反映当前版本对外承诺，不得夸大未激活能力。 |
| JSON-RPC 错误表面 | MUST | 非法 JSON、非法 request shape、未知 method、SDK/handler 异常必须以 JSON-RPC error response 或 SSE error frame 返回；错误 response 必须尽量保留原 request id。 |
| 租户标识传递 | MUST | runtime 必须允许调用上下文携带 `tenantId`，并把租户标识纳入 execution context、日志 MDC、state key、memory scope 和 trajectory 关联链路。runtime 不负责认证租户身份。 |
| HTTP + SSE 传输 | MUST | 当前版本的 inbound A2A 传输以 HTTP JSON-RPC 和 SSE 为事实要求。 |
| gRPC 传输 | OUT | 当前版本不要求 runtime 暴露 gRPC northbound 传输。 |
| Push Notification 实际推送 | OUT | 当前版本不要求 runtime 主动向 webhook 推送 task notification；调用方应使用 SSE 或 `GetTask` 轮询。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| `GET /.well-known/agent-card.json` | HTTP endpoint | 必须返回当前 Agent 的 A2A Agent Card。若配置了 `agent-runtime.access.a2a.public-base-url`，card 中相对 URL 必须按该公开 base 解析；否则按当前请求地址解析。 |
| `GET /.well-known/agent.json` | HTTP endpoint | 必须作为 Agent Card 兼容发现入口，返回与标准 card endpoint 等价的 Agent Card 表面。 |
| `POST /a2a` / `POST /a2a/` | HTTP endpoint | 必须承载 A2A JSON-RPC 请求。`Accept: application/json` 进入阻塞 JSON-RPC 分支；`Accept: text/event-stream` 进入 SSE 分支。 |
| `SendStreamingMessage` | JSON-RPC method | 必须返回 SSE 事件流；每个 SSE event 必须使用 `event: jsonrpc`，data 为 JSON-RPC envelope。 |
| `SubscribeToTask` | JSON-RPC method | 必须通过 SSE 重新订阅已有 task 的事件。 |
| `SendMessage` | JSON-RPC method | 必须返回单个 JSON-RPC result，结果是 A2A Task 或 Message 表面。 |
| `GetTask` | JSON-RPC method | 必须返回指定 task 的当前快照。 |
| `CancelTask` | JSON-RPC method | 必须触发 runtime cancel 语义，并返回取消后的 Task 表面。 |
| `ListTasks` | JSON-RPC method | 必须返回任务列表查询结果。 |
| `Create/Get/List/DeleteTaskPushNotificationConfig` | JSON-RPC method | 应暴露 SDK 层 push config 管理入口；不意味着 webhook 已激活。 |
| `X-Tenant-Id` | HTTP header | 必须作为网关注入租户标识的入口之一。多租户场景中，调用方不能依赖 runtime 完成认证。 |
| `agent-runtime.access.a2a.*` | YAML 配置 | 必须承载默认租户、公开 base URL、Agent Card 元数据、skills、capabilities 等 northbound 暴露配置。 |
| `AgentRuntimeHandler` | Java SPI | 必须作为 Agent 执行接入点被 A2A bridge 调用；handler 始终以 stream 方式产出结果。 |
| `AgentCardProvider` | Java SPI | 必须允许应用以代码方式提供 Agent Card，并覆盖或补充 YAML/default card 声明。 |
| agent-bus forwarding delivery | 外部系统调用场景 | agent-bus 对 runtime 的转发投递必须使用标准 A2A 服务入口和相同 Task/SSE/error 语义。runtime 不为转发路径定义额外私有协议。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 发现 Agent 能力 | runtime 已启动并存在可发布的 Agent Card | A2A client 请求 `/.well-known/agent-card.json` | client 获得 Agent 名称、描述、endpoint、capabilities 和 skills；相对 URL 被解析成可访问 URL。 |
| 普通 client 流式调用 Agent | client 已获得 `/a2a` endpoint，runtime 处于 ready 状态 | client 发送 `SendStreamingMessage` 并声明 `Accept: text/event-stream` | runtime 返回 SSE stream；调用方按顺序观察 Task 接收、执行、artifact/progress 和最终 `COMPLETED` / `FAILED` / `CANCELED` / interrupted 状态。 |
| 其他 runtime 调用本 runtime | 调用方 runtime 已通过 Agent Card 发现本 runtime，且本 Agent Card 声明了可用 endpoint/skills | 调用方 runtime 作为 A2A client 向本 runtime 的 `/a2a` 发起请求 | 本 runtime 按同一服务入口建立 Task、执行 handler 并返回 Task/SSE/error；不得区分“来自 runtime”而改变协议表面。 |
| agent-bus forwarding 投递 | agent-bus 已解析 route 并获得本 runtime A2A endpoint | agent-bus forwarding delivery port 向 `/a2a` 发起标准 A2A 请求并携带租户上下文 | 本 runtime 按标准 Agent 服务入口处理请求；完成、失败、超时、取消等语义对 agent-bus 仍表现为 A2A Task/SSE/error 表面。 |
| 阻塞调用 Agent | 请求规模适合一次性响应 | client 发送 `SendMessage` | runtime 调用同一 handler stream，收集结果后返回单个 JSON-RPC response。若超出阻塞等待窗口，行为必须按核心语义处理。 |
| 查询长任务 | client 已获得 task id | client 调用 `GetTask` | runtime 返回该 task 当前状态、artifact 和 terminal message 等可见信息。 |
| 取消执行中任务 | task 仍在执行或可取消 | client 调用 `CancelTask` | runtime 标记取消，停止继续消费本次执行结果，并尽力触发 handler/远端调用取消。 |
| 断线重连 | client 在 SSE 流中断后仍持有 task id | client 调用 `SubscribeToTask` | runtime 按 SDK task/event 语义继续推送该 task 的后续事件或当前状态。 |
| 多租户网关接入 | 前置网关已认证调用方 | 网关注入或覆盖 `X-Tenant-Id` 后转发 `/a2a` 请求 | runtime 在 execution context、日志、state/memory/trajectory 关联链路中使用该租户标识；runtime 不自行判断该租户是否真实授权。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 入口来源等价语义

- 普通 client、其他 agent-runtime、agent-bus forwarding 进入 `agent-runtime` 时必须共享同一个 Agent 服务入口事实：Agent Card 发现、`/a2a` JSON-RPC、Task 状态、SSE 事件、错误表面和租户上下文规则。
- runtime 可以在日志、metadata、trace 或网关层区分调用来源，但不得为不同来源定义互相漂移的执行语义。
- agent-bus forwarding 是标准入口的调用方之一，不是 `agent-runtime` 内部执行 SPI 的绕行入口。
- 其他 agent-runtime 调用本 runtime 时，本 runtime 只承担服务端职责；主动发现远端 runtime、安装远程工具和发起 outbound 调用由 `Feat-Func-005` 约束。

#### 5.1.1 Agent Card 发现语义

- Agent Card 必须是调用方进入 runtime 的能力目录，而不是内部配置转储。
- Agent Card 的 endpoint URL 必须能被外部调用方解析：配置了 `public-base-url` 时使用配置值；未配置时由请求地址推导。
- Agent Card 的 `skills` 是跨 Agent 工具发现的事实入口。声明 skills 表示该 Agent 希望被其他 Agent 作为工具发现；不声明 skills 表示不对外承诺远程工具能力。
- Agent Card 的 `capabilities.pushNotifications` 可以声明 SDK 支持能力，但当前版本不得把“实际 webhook 推送已激活”作为事实要求。

#### 5.1.2 JSON-RPC 分发语义

- `/a2a` 必须先解析 JSON-RPC request，再根据具体 A2A wrapper 分发。
- `SendStreamingMessage` 和 `SubscribeToTask` 必须进入 streaming 分支。
- `SendMessage`、`GetTask`、`ListTasks`、`CancelTask` 和 push config CRUD 必须进入 blocking JSON 分支。
- 未知 method 必须返回 JSON-RPC method-not-found 错误。
- 非法 JSON 必须返回 parse error；合法 JSON 但 shape 不匹配 A2A request 时必须返回 invalid request。

#### 5.1.3 流式 S2C 语义

- `SendStreamingMessage` 的 SSE event 名必须为 `jsonrpc`。
- SSE data 必须是 JSON-RPC response envelope，result 承载 A2A SDK `StreamingEventKind`。
- runtime 必须通过 Task 状态和 artifact/progress 向调用方呈现执行过程。
- 对 `SendStreamingMessage`，当状态进入 final 或 interrupted 状态时，当前 message stream 必须关闭。final 包括 completed/failed/canceled/rejected；interrupted 包括 input required / auth required。
- 流开始后发生异常时，runtime 必须尽力追加一帧 JSON-RPC error event，而不是让调用方只看到裸连接中断。

#### 5.1.4 阻塞 S2C 语义

- handler 始终以 stream 方式产出结果；`SendMessage` 的阻塞语义由 A2A 层消费 stream 并聚合响应形成。
- `SendMessage` 和 `SendStreamingMessage` 必须接受一致的 message 结构。
- 阻塞等待不能无限挂起。超过 agent 执行等待窗口时，runtime 可以返回当前 Task 快照；超过消费等待窗口时，runtime 必须返回 JSON-RPC error。

#### 5.1.5 Task 状态语义

- runtime 必须把一次 Agent 调用投射到 A2A Task 生命周期。
- 正常执行至少应经历 submitted/working，并以 completed/failed/canceled 或 interrupted 类状态收束。
- handler 输出 `COMPLETED` 时必须形成 completed Task 表面。
- handler 输出 `FAILED` 或执行异常时必须形成 failed Task 表面，并携带可供客户端程序化判断的错误信息。
- handler 输出需要用户输入的中断时，Task 必须进入 input-required 类语义，而不是伪装成 completed。
- cancel 请求必须投射为 canceled Task 表面；底层 adapter 的协作式中止能力不得被夸大为强制中断能力。

#### 5.1.6 输入与元数据语义

- runtime 执行上下文只消费 A2A Message 中的 text parts；非 text parts 不得静默伪装成文本输入。
- 空文本输入且不是远程 continuation 时，runtime 必须拒绝或失败该 task，而不是把空输入交给 Agent 任意解释。
- request-level metadata 必须作为 runtime identity、state、memory、trajectory 等运行时字段的事实来源；message-level metadata 可保留给业务 adapter，但不得覆盖 runtime 身份字段。
- 租户、用户、session、agent、correlation、trace 等上下文字段必须能进入 `AgentExecutionContext`，并派生默认 `agentStateKey` 与 `memoryScope`。

#### 5.1.7 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| JSON parse failure | 返回 JSON-RPC parse error；SSE 分支返回一帧 error event。 |
| request shape invalid | 返回 JSON-RPC invalid request。 |
| method unsupported | 返回 JSON-RPC method-not-found。 |
| handler/runtime exception | 形成 A2A failed Task 或 JSON-RPC internal error；可形成 Task 的路径应携带结构化错误 payload。 |
| runtime not ready | 必须拒绝执行，错误语义应表达为可重试的 runtime-not-ready。 |
| no handler registered | 必须拒绝执行，错误语义应表达为不可执行的 no-handler。 |
| cancel requested | 必须尝试通知 handler、停止消费当前 stream，并向 A2A Task 表面反映 canceled。 |
| mid-stream failure | 必须尽力以 SSE JSON-RPC error frame 结束。 |
| tenant/correlation observability | 执行窗口内日志、trajectory 和上下文派生字段必须能关联 tenant、context、task、agent。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 多 Agent 路由 | 一个 runtime 实例不承诺按 agent id 路由多个 handler。多 Agent 部署应拆分为多个 runtime 实例或由上层路由。 |
| 租户认证 | runtime 不认证 `X-Tenant-Id` 或 metadata 中的租户声明。多租户部署必须由前置网关认证、清洗并注入租户标识。 |
| gRPC northbound | 当前版本不承诺 A2A gRPC 暴露面。 |
| Push Notification 实际推送 | 当前版本不承诺主动 webhook 推送；push config CRUD 只是协议配置管理入口。 |
| 非文本输入语义 | 当前版本不承诺把 file/data parts 转成 Agent 输入；文本输入是主路径。 |
| 强制中断底层 LLM | `CancelTask` 不承诺能立即打断已经进入模型客户端的阻塞调用。 |
| outbound 远程 Agent 编排 | 本特性只要求本 runtime 作为服务端发布自己的 Agent Card 并接受调用；远程 Agent 目录、缓存、工具安装、调用发起、结果回灌由 `Feat-Func-005` 承接。 |
| agent-bus 专用私有入口 | runtime 不承诺为 agent-bus 暴露绕过 A2A Task/SSE/error 表面的私有执行接口。 |
| 认证授权协议 | A2A auth 扩展、OAuth、签名校验等不在本特性事实要求中。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为标准化 Agent 服务入口事实来源，不得把本特性的外部行为降级为实现细节。
- `A2aJsonRpcController`、SDK `RequestHandler` bridge、Agent Card controller 和相关 auto-configuration 必须共同满足第 2-5 节事实要求。
- 开发指南只能解释如何使用这些事实要求，不得引入与本特性冲突的新 method、endpoint、状态语义或 capability 承诺。
- 测试必须覆盖三类 S2C 模式：blocking、streaming、async query/cancel，并覆盖 parse error、method not found、invalid request、mid-stream error、tenant header/context、Agent Card discovery。
- agent-bus 对 runtime 的 forwarding 集成验证必须以标准 A2A 服务入口为边界，不能要求 runtime 增加 agent-bus 专用执行口。
- Agent Card skills/capabilities 的设计和实现必须与 `Feat-Func-005` 保持一致：skills 是远程工具发现入口，capabilities 是能力声明，不是运行时自动证明。
- 任何对 push notification、gRPC、多 handler 路由、非文本输入或认证能力的新增承诺，都必须先回到本特性或新的 version-scope 特性文档更新事实要求，再进入 L2 和实现。
- 本特性使用的术语必须保持稳定：A2A、Agent Card、JSON-RPC、SSE、Task、Message、Artifact、Capability、Skill、Tenant、Runtime Readiness。

## 7. 关联文档

- `architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-001-standardized-agent-service-entrypoint.md`
