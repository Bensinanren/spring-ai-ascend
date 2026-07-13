---
version: 0715
module: agent-client
feature_type: functional
feature_id: FEAT-2026-007
status: active
---

# Agent-client 组件本地工具注册与远端驱动调用特性文档

## 1. 特性定位

FEAT-2026-007 定义 `agent-client` 当前版本承载客户端本地能力接入的事实：业务应用可以通过 SDK 声明当前 task / session / 页面范围内可用的本地 Observation / Action 能力，服务端 Agent 只能通过受治理 capability intent 或 S2C 投影请求这些能力，客户端执行后主动提交一次性结果。

本特性解决的问题是：客户端页面、企业终端、插件和人工确认能力需要进入智能体执行闭环，但不能被服务端当作可直接远程调用的函数、公网 endpoint 或服务端工具注册项。客户端能力必须先由 SDK 声明并随调用形成能力快照，再由 runtime 通过 Task、SSE、INPUT_REQUIRED 或 S2C 投影请求执行。执行结果作为外部输入经 Gateway / IngressGateway 回到服务端，runtime 校验后决定是否推进 Task。

在总体架构中，本特性位于 application / agent-client 与 agent-runtime 端侧能力请求之间。它不定义服务端工具注册中心，不替代 agent-middleware 的工具服务，也不让 Event Bus 承载客户端本地资源访问或工具结果正文。其事实边界是客户端 SDK 本地能力目录、能力快照、pending call 投影、用户确认、权限校验、一次性结果提交和本地调用引用。

本特性面向以下角色：

- 业务应用开发者：向 SDK 注册页面、终端、插件或人工确认能力。
- 企业终端集成方：把本地上下文和受控 Action 暴露给智能体调用链。
- agent-client SDK 开发者：实现本地能力注册、快照上报、intent / S2C 投影接收、执行调度和结果提交 facade。
- Gateway / IngressGateway 开发者：转发能力快照、capability intent / S2C 投影和结果提交，不直接访问客户端资源。
- agent-runtime 开发者：把端侧能力请求投影为 INPUT_REQUIRED / capability intent，并在收到客户端结果后校验恢复点和推进 Task。
- 测试与验收团队：验证能力声明、权限、超时、拒绝、迟到和重复提交语义。

本特性只定义客户端本地能力的声明、投影接收、单次执行和结果提交闭环。服务端如何产生端侧工具调用意图由 FEAT-2026-009 / FEAT-2026-010 承接；标准调用入口由 FEAT-2026-006 承接；Gateway 路由和总线转发由 FEAT-2026-011 / FEAT-2026-012 / FEAT-013 承接；全局工具服务、MCP、Skill Hub、agent-middleware 工具执行和 runtime TaskStore 不属于本特性。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 本地能力注册 | MUST | SDK 必须允许业务应用声明 Observation / Action 能力、名称、描述、`input_schema`、`output_schema`、`dataBoundary`（含 redactionPolicy）、权限、scope、placement、`confirmationRequired`（如有副作用）和有效期。注册时 SDK 校验声明完整性。 |
| 能力快照上报 | MUST | SDK 必须能在发起任务或补充输入时上报当前 task / session / 页面范围内的能力快照；快照只表达客户端可用能力，不是服务端工具注册中心。 |
| 远端驱动 intent 接收 | MUST | SDK 必须能从 Gateway / runtime SSE、Task 投影、INPUT_REQUIRED 或 S2C envelope 中识别 capability intent。 |
| Pending call 投影 | MUST | SDK 必须为每次端侧能力请求维护 taskId、callbackId、toolCallId、deadline、幂等键和本地状态；该状态只用于客户端执行和 UI 展示。 |
| Observation 执行 | MUST | 只读能力必须按 schema 返回最小必要结果，敏感内容应脱敏或通过 payloadRef / artifactRef / auditRef 传递。 |
| Action 执行确认 | MUST | 有副作用的 Action 必须具备用户确认、业务授权或等价控制依据；用户拒绝必须作为结构化结果返回。 |
| 单次结果提交 | MUST | 一次 capability intent 对应一次客户端结果提交；工具内部可以多步执行，但协议层结果必须通过 Gateway / IngressGateway 主动提交。 |
| 结果错误结构化 | MUST | 权限拒绝、能力缺失、用户拒绝、超时、上下文过期、重复提交和执行失败必须有结构化错误。 |
| 迟到与重复处理 | MUST | SDK 必须使用 callbackId、deadline、toolCallId 和幂等键识别迟到或重复结果，并把冲突交由平台 / runtime 判定。 |
| 状态边界 | MUST | SDK 只保存本地 pending call 投影，不拥有服务端 Task 权威状态，也不推进 runtime Task。 |
| 大载荷引用 | MUST | 大文本、文件、多模态或敏感结果必须支持 payloadRef / artifactRef / auditRef，不进入 Event Bus 事件正文。 |
| 任意公网端点暴露 | OUT | 当前版本不允许把客户端本地函数暴露为服务端可直接调用的公网 webhook 或 RPC endpoint。 |
| 服务端全局工具注册 | OUT | 当前版本不由 agent-client 定义服务端 Tool Registry、MCP server 或 Skill Hub 注册事实。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| `registerCapability` | SDK facade | 注册本地 Observation / Action 能力及 schema、权限、scope、placement、dataBoundary 和有效期。 |
| `publishCapabilities` | SDK facade | 将当前能力快照随 submit / resume 请求提交到平台入口；平台入口只转发快照，不直接调用本地能力。 |
| `handleCapabilityIntent` | SDK facade | 接收 runtime / Gateway 投影的端侧能力请求并创建 pending call 投影。 |
| `executeCapability` | SDK 本地能力入口 | 在本地读取上下文、执行动作、触发用户确认或返回不可用错误；具体实现由业务应用提供。 |
| `submitCapabilityResult` | SDK facade | 主动提交 result、error、payloadRef、artifactRef、auditRef、callbackId、toolCallId 和幂等信息。 |
| `listPendingCapabilities` | SDK facade | 查询当前客户端待处理能力请求，供 UI 展示。 |
| capability snapshot | 请求元数据 | 必须描述能力名称、schema、权限、scope、placement、dataBoundary、catalogVersion 和有效期。 |
| capability intent | S2C / Task 投影 | 必须携带 taskId、callbackId、toolCallId、参数、deadline、展示说明和风险信息。 |
| capability result | C2S 提交体 | 必须携带结果、错误、用户确认状态、审计引用、幂等键、callbackId、toolCallId 和 correlation 信息。 |
| Gateway / IngressGateway | HTTP/SSE 入口 | 必须承载能力快照、intent 投影和结果提交，不要求服务端直连客户端。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 本地能力声明随调用生效 | 应用已注册本地 Observation / Action | 应用通过 FEAT-006 标准调用提交任务，并携带当前 capability snapshot | Gateway 转发能力快照；runtime 只能基于该 task / session / 页面 scope 内的快照请求客户端能力。 |
| 页面上下文读取 | 应用注册只读 Observation，runtime 通过投影请求读取 | SDK 收到 capability intent 后校验页面上下文、scope 和权限 | SDK 返回最小必要字段、脱敏结果或 payloadRef；脱敏由注册时声明的 `dataBoundary.redactionPolicy` 约束，不是 SDK 运行时动态决定；Agent 通过 capability snapshot 获知 schema 和 dataBoundary，据此形成对脱敏结果的预期；runtime 按 output_schema 校验结果后决定是否推进 Task。 |
| 客户端动作确认 | 应用注册有副作用 Action，runtime 请求执行受控动作 | SDK 展示动作内容、风险和确认项，用户确认后执行 | "有无副作用"由业务在注册时声明（Observation=只读 / Action=有副作用），不由 SDK 判断；风险和确认 UI 由业务自定义，SDK 不弹窗、不渲染 UI；SDK 只提供声明机制（`confirmationRequired`、`riskLevel`）+ 回调机制（把 intent 传给业务 handler）+ 结果回传机制；SDK 强制约束：`confirmationRequired=true` 时结果必须含确认状态（approved/rejected）；SDK 主动提交 result / auditRef；用户拒绝作为结构化结果提交，runtime 按服务端语义处理。 |
| 本地能力不可用 | 插件未安装、页面关闭、scope 过期或权限不足 | SDK 收到 capability intent | SDK 返回 capability_not_found、stale_context、permission_denied、user_rejected 或 timeout；runtime 决定降级、重试或失败。 |
| 大结果返回 | 本地能力产生文件、截图、长文本或敏感内容 | SDK 执行能力后提交结果 | 大正文不内联，使用 payloadRef / artifactRef / auditRef 传递；Event Bus 不承载本地资源正文。 |
| 迟到或重复结果提交 | pending call 已过期、重复提交或 Task 已终态 | 客户端网络恢复后提交旧结果 | SDK 携带 callbackId、toolCallId 和幂等键提交；平台或 runtime 拒绝迟到 / 冲突结果，SDK 展示 expired、duplicate 或 task_already_terminal。 |
| 能力快照刷新 | 用户切换页面、插件状态变化或权限变化 | SDK 随下一次标准调用或补充输入发布新 catalogVersion | 后续服务端请求只能针对当前 scope 和版本内可用能力；旧快照不得自动跨 session 或跨 tenant 复用。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 能力声明语义

- 客户端能力必须由业务应用显式注册，不能由模型或服务端任意创建。
- 能力快照默认只在当前 task、session 或页面 scope 内有效。
- SDK 必须保留 catalogVersion、placement 和 dataBoundary，使服务端请求可与客户端能力目录对齐。

#### 5.1.1 远端驱动语义

- 服务端只能通过 capability intent / S2C 投影请求客户端能力，不能直接访问客户端 DOM、文件系统、插件或本地端口。
- intent 必须包含工具名称、参数、deadline、callbackId 和可展示说明。
- Action 类 intent 必须携带风险或确认信息，供业务 UI 决策。

#### 5.1.2 本地执行与结果提交语义

- Observation 不应产生业务副作用；Action 必须经过用户确认或授权策略。
- 客户端结果是外部输入，不是服务端事实，runtime 必须校验后才能决定是否推进 Task。
- 用户拒绝、权限不足、能力不可用都应作为结构化结果提交，而不是静默丢弃。
- 一次 capability intent 在协议层只对应一次结果提交；多步本地操作不得要求服务端直接驱动客户端内部步骤。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 能力未注册 | SDK 返回 capability_not_found。 |
| 权限不足 | SDK 返回 permission_denied 或 user_rejected。 |
| 页面变化 | SDK 返回 stale_context。 |
| 执行超时 | SDK 返回 timeout 或 expired，并停止本地执行。 |
| 重复提交 | SDK 通过 callbackId / 幂等键返回同一结果或冲突。 |
| 任务已终态 | SDK 不再提交结果，或提交后由平台拒绝。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 服务端直接调用客户端 | 不暴露客户端公网 webhook、RPC 或本地端口给服务端。 |
| 全局工具市场 | 不定义 Skill Hub、MCP server 或 agent-middleware 工具注册中心。 |
| 服务端 Task 权威状态 | SDK 不保存 TaskStore，只保存 pending call 投影。 |
| 自动上传本地敏感数据 | 本地数据默认最小化、脱敏或引用传递。 |
| 绕过 Gateway 提交结果 | 结果提交必须进入受治理入口。 |
| 跨租户能力共享 | 客户端能力默认不跨租户、用户或 session 共享。 |
| Event Bus 承载本地资源 | Event Bus 不承载客户端本地资源访问、工具执行过程或大结果正文。 |
| 客户端推进 Task | SDK 不决定服务端 Task 是否恢复、完成、失败或取消。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为客户端本地能力接入事实来源，不得把客户端能力描述为服务端可直接调用的函数。
- `agent-client`、Gateway / IngressGateway、runtime 端侧工具响应和 agent-core handoff 设计必须共享 capability snapshot、capability intent、callbackId、toolCallId、deadline、payloadRef、auditRef、catalogVersion、placement 和 dataBoundary 等字段语义。
- 测试必须覆盖能力注册、快照上报、只读观测、Action 确认、用户拒绝、权限不足、能力不可用、迟到结果、重复提交、大载荷引用、单次结果提交和 Task 终态冲突。
- 开发指南必须强调客户端结果是外部输入，需要 runtime 校验后才能回灌 Agent。
- 任何对客户端公网 webhook、服务端直接访问本地资源、全局工具注册、跨租户共享能力、Event Bus 承载本地资源或客户端推进 Task 状态的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：LocalCapability、Observation、Action、capability snapshot、capability intent、S2C projection、callbackId、toolCallId、deadline、payloadRef、auditRef、placement、dataBoundary。

## 7. 关联文档

- `agent-sdk/Docs/Agent-client组件本地工具注册与远端驱动调用特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-006-agent-client-standard-agent-service-invocation.md`
- `JAVA local working/version-scope/FEAT-011-client-invocation-route-forwarding.md`
- `JAVA local working/version-scope/FEAT-012-client-invocation-bus-forwarding.md`
- `JAVA local working/version-scope/FEAT-013-client-invocation-event-forwarding.md`
- `Docs/FEAT_Design/FEAT-2026-009-agent-runtime-client-side-tool-response.md`
- `Docs/FEAT_Design/FEAT-2026-010-agent-core-dynamic-client-side-tool-registration-invocation.md`
