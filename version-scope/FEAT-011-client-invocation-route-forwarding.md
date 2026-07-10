---
version: 0715
module: agent-bus
feature_type: functional
feature_id: FEAT-2026-011
status: active
---

# agent-gateway 组件客户端调用路由转发特性文档

## 1. 特性定位

FEAT-2026-011 定义 `agent-gateway` 当前版本作为客户端同步 / 流式调用路由转发入口的事实：Gateway 必须接收 `agent-client` 的 C/S 请求，完成认证鉴权、租户识别、请求标准化、路由决策和 HTTP/SSE/A2A 桥接，再把请求投递到目标 agent-service、proxy-service 或 runtime 标准入口。

本特性解决的问题是：业务应用不应感知内部 agent-service、proxy-service、runtime 物理 endpoint，也不应自行选择未治理 URL。Gateway 作为 agent-bus 中的入口治理平面，必须根据 agentId、capabilityId、tenant、版本、健康状态和路由策略生成 routeHandle，并将响应、SSE、查询和取消统一桥接回客户端。

在总体架构中，本特性位于 agent-client 与 agent-service / proxy-service / agent-runtime 之间。Gateway 不执行 Agent、不调用模型、不读写 runtime TaskStore；runtime 或目标服务仍是 Task owner。Gateway 只保存路由、亲和、幂等、审计和流桥接投影。

本特性面向以下角色：

- Gateway 开发者：实现入口治理、RouteDecision、ForwardingTransport 和 SSE bridge。
- agent-client 接入方：通过统一 Gateway facade 调用 Agent 服务。
- R&D Center / 注册发现集成方：提供 Agent、能力、版本、健康和 routeHandle。
- agent-service / proxy-service 开发者：承接 Gateway 转发并对齐标准响应。
- 运行运维方：配置灰度、回退、流控、审计和故障处理策略。
- 测试与验收团队：按路由、转发、流桥接、查询、取消和错误语义验证。

本特性只定义客户端调用的直接路由转发路径。事件化异步总线转发由 FEAT-2026-012 承接；runtime 标准 A2A 服务入口由 FEAT-001 承接；Agent 执行、工具、记忆和 Task 生命周期不属于 Gateway 职责。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 客户端入口治理 | MUST | Gateway 必须处理认证鉴权、租户解析、基础参数校验、幂等和审计。 |
| agentId 路由 | MUST | Gateway 必须支持按明确 agentId 查找 routeHandle 并转发。 |
| capabilityId 路由 | MUST | Gateway 必须支持按 capabilityId 查询候选 Agent / proxy 并选择目标。 |
| routeHandle 抽象 | MUST | routeHandle 必须是受治理路由引用，不得向客户端暴露物理 endpoint。 |
| HTTP/A2A 转发 | MUST | Gateway 必须能把请求映射到目标 runtime `/a2a` 或受控 REST facade。 |
| SSE 桥接 | MUST | Gateway 必须支持桥接服务端 SSE / stream，但不得生成 Agent token 内容。 |
| 查询转发 | MUST | Gateway 必须能按 taskId、routeHandle 或亲和记录定位 Task owner 并转发查询。 |
| 取消转发 | MUST | Gateway 必须能把 cancel 请求映射到 runtime `CancelTask` 或 proxy 等价取消入口。 |
| 灰度与回退 | SHOULD | Gateway 应支持按租户、版本、比例、健康状态执行灰度和故障回退。 |
| 状态边界 | MUST | Gateway 不拥有服务端 Task，不写 runtime TaskStore。 |
| 拓扑隐藏 | MUST | 客户端不得感知内部服务 endpoint、实例、网络拓扑或 runtime 私有接口。 |
| Agent 执行 | OUT | Gateway 不执行推理、工具调用、记忆、知识库或业务 Agent 逻辑。 |
| 私有执行口 | OUT | Gateway 不为 agent-bus 或 client 创建绕过标准 runtime 入口的私有 Agent 执行协议。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| client invoke facade | HTTP/SSE 入口 | 接收 agent-client 标准调用请求，携带 agentId / capabilityId、tenant、user、session、input 和幂等键。 |
| client stream facade | HTTP/SSE 入口 | 建立客户端到目标服务的流式桥接，支持断线恢复所需标识。 |
| route lookup | Gateway 内部能力 | 按 agentId、capabilityId、tenant、version、health 查询候选目标。 |
| `RouteDecision` | 路由决策对象 | 必须记录 routeHandle、目标能力、协议族、选择原因、策略版本和审计信息。 |
| `ForwardingTransport` | 转发能力 | 将请求转为 A2A `/a2a`、REST facade 或 proxy 协议。 |
| SSE bridge | 流桥接能力 | 透传或映射目标服务流事件，不承载 Event Bus token 流。 |
| task route lookup | 查询/取消定位 | 根据 taskId、routeHandle、clientInvocationId 或亲和记录定位 Task owner。 |
| R&D Center | 注册发现依赖 | 提供 Agent 注册、能力索引、健康状态、版本和路由策略。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 按 agentId 调用 Agent | 目标 Agent 已注册并可访问 | client 提交 agentId 调用 | Gateway 校验权限，选择 routeHandle，转发到目标服务或 runtime 标准入口。 |
| 按 capabilityId 选 Agent | R&D Center 有能力索引 | client 只提交 capabilityId | Gateway 按租户、版本、健康和策略选择候选，返回或转发到最佳目标。 |
| 流式调用桥接 | 目标支持 SSE / A2A streaming | client 发起 stream | Gateway 建立桥接，客户端观察 runtime Task / SSE 投影。 |
| 存量服务接入 | proxy-service 已注册 | Gateway 将部分流量转发到 proxy | proxy 适配存量协议并返回标准响应；Gateway 隐藏内部拓扑。 |
| 查询长任务 | client 持有 taskId 或 clientInvocationId | client 调用 getTask facade | Gateway 定位原 Task owner 并转发查询，返回标准 Task 投影。 |
| 取消任务 | Task 仍可取消 | client 发起 cancel | Gateway 转发到 runtime CancelTask 或 proxy 取消能力，返回明确状态。 |
| 路由失败 | 无权限、无目标或目标不可用 | client 发起调用 | Gateway 返回 route_not_found、permission_denied 或 service_unavailable，不伪造 Task。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 入口治理语义

- Gateway 是客户端进入平台的治理入口，必须先完成认证鉴权、租户解析、幂等和基础校验。
- 租户身份真实性由前置认证和 Gateway 策略保障，runtime 只消费已传递的上下文。
- Gateway 可以记录审计和 route trace，但不改变 Agent 业务语义。

#### 5.1.1 路由决策语义

- routeHandle 是受治理路由引用，不是物理 URL。
- 按 capabilityId 选择目标时，Gateway 必须记录候选、过滤原因、选择策略和版本。
- 同一 session 可使用亲和策略避免上下文漂移。

#### 5.1.2 转发与桥接语义

- Gateway 转发必须落到 runtime 标准 A2A 入口或受控 REST / proxy facade。
- SSE bridge 只能桥接服务端流，不生成 Agent token、不修改 Task 终态。
- 查询和取消必须定位 Task owner；Gateway 缓存只用于定位，不是 TaskStore。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 认证失败 | 返回认证/授权错误，不进入路由。 |
| 无可用目标 | 返回 route_not_found 或 service_unavailable。 |
| 转发超时 | 返回 timeout 或 UNKNOWN，允许幂等恢复。 |
| 目标流中断 | Gateway 暴露流错误并允许 client 查询 Task。 |
| proxy 不支持取消 | 返回 capability_not_supported，不伪造 canceled。 |
| routeHandle 失效 | 重新查询注册发现或返回明确错误。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| Agent 执行 | Gateway 不执行模型、工具、记忆、知识库或业务 Agent。 |
| Task 权威状态 | Gateway 不写 TaskStore，不决定 Task 终态。 |
| 物理拓扑暴露 | 不向 client 暴露内部 endpoint、实例或 runtime 私有地址。 |
| Event Bus 异步入队 | 直接路由转发不等同于总线转发；异步入队由 FEAT-2026-012 定义。 |
| 私有执行协议 | 不新增绕过 runtime A2A / Task 语义的私有 Agent 执行口。 |
| 强制取消 | Gateway cancel 不承诺强制中断目标模型调用。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为客户端调用路由转发事实来源，Gateway 只能承担入口治理、路由、转发、桥接和审计职责。
- Gateway facade、RouteDecision、ForwardingTransport、R&D Center、agent-service、proxy-service 和 runtime A2A 入口必须共享 routeHandle、tenant、taskId、clientInvocationId、streamRef 和错误码语义。
- 测试必须覆盖 agentId 路由、capabilityId 路由、认证鉴权、租户隔离、SSE bridge、查询转发、取消转发、灰度回退、proxy 接入、routeHandle 隐藏和转发超时。
- 开发指南不得要求业务应用直接配置内部 runtime endpoint。
- 任何对 Gateway 执行 Agent、持有 TaskStore、暴露内部拓扑或私有执行协议的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：Gateway、RouteDecision、routeHandle、ForwardingTransport、SSE bridge、R&D Center、Task owner、proxy-service。

## 7. 关联文档

- `agent-sdk/Docs/agent-gateway组件客户端调用路由转发特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-006-agent-client-standard-agent-service-invocation.md`
- `Docs/FEAT_Design/FEAT-2026-012-agent-gateway-client-invocation-bus-forwarding.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`
