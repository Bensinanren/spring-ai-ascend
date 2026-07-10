---
version: 0715
module: agent-core
feature_type: functional
feature_id: FEAT-2026-010
status: active
---

# Agent-core 组件端侧工具动态注册与调用特性文档

## 1. 特性定位

FEAT-2026-010 定义 `agent-core` 当前版本在 Agent 执行过程中动态形成端侧工具可见面的事实：core 可以基于客户端能力快照、租户策略、用户权限、workflow 节点和当前 task 上下文生成任务级工具目录，并在模型选择端侧工具时产出中立 ToolCallHandoff / interrupt。

本特性解决的问题是：Agent 推理需要看到当前页面、终端插件或流程节点临时开放的工具，但这些工具不能成为全局工具，也不能由 core 直接执行。core 负责工具目录、策略裁剪、模型可见 schema、调用移交和结果回灌；runtime 负责 Task 状态和客户端协作，agent-client 负责本地真实执行。

在总体架构中，本特性位于 agent-instance 内的 `agent-core` 与 `agent-runtime` 之间。它承接客户端能力快照和运行上下文，生成 ReAct / Workflow / DeepAgent 可见工具集合；当模型触发工具调用时，core 只输出中立 handoff，由 runtime 或 host 投影为 A2A `INPUT_REQUIRED` 或 capability intent。

本特性面向以下角色：

- Agent 应用开发者：配置动态工具策略、allowlist 和 workflow scope。
- agent-core 框架开发者：实现任务级工具目录、安装器、handoff 和结果回灌。
- Runtime 集成开发者：接收 handoff 并投影为 Task 中断。
- agent-client 集成方：提供能力快照和执行结果。
- 测试与验收团队：验证动态工具可见性、策略裁剪、版本、过期和调用移交。

本特性不定义客户端本地工具执行，不定义 runtime TaskStore / CancelTask，也不定义平台全局 Tool Registry 或 MCP 服务。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 任务级工具目录 | MUST | core 必须能基于请求上下文维护 task / session / workflow node 范围内的工具目录。 |
| 能力快照接收 | MUST | core 必须能从 runtime / host 上下文读取客户端 capability snapshot 或等价变量。 |
| 策略裁剪 | MUST | 工具进入模型可见面前必须经过租户、用户、YAML allowlist、workflow scope 和风险策略过滤。 |
| 模型 schema 注入 | MUST | 可见工具必须以名称、描述、inputSchema、scope 和版本注入 Agent 工具列表。 |
| ToolCallHandoff | MUST | 模型调用端侧工具时，core 必须产出中立 handoff / interrupt，不得直接执行客户端工具。 |
| 结果回灌 | MUST | runtime / host 恢复后，core 必须把 tool observation 放回 Agent loop。 |
| 目录版本控制 | MUST | 多轮或上下文变化时必须维护 catalogVersion，旧版本调用应可受控拒绝或降级。 |
| 节点级有效期 | MUST | Workflow 节点工具必须随节点进入/退出生效或过期。 |
| 审计与可解释 | SHOULD | 被隐藏或拒绝的工具应记录策略原因，便于排障和审计。 |
| 直接客户端执行 | OUT | core 不访问客户端资源，不调用 agent-client 内部 API。 |
| 服务端 Task 管理 | OUT | core 不拥有 A2A Task、SSE、CancelTask 或服务端 TaskStore。 |
| 全局工具注册 | OUT | 动态端侧工具默认不注册为平台全局工具或 Skill Hub 能力。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| capability snapshot | Runtime 上下文输入 | 提供客户端声明的工具、schema、scope、权限和 catalogVersion。 |
| `TaskScopedToolRegistry` | core 内部能力 | 维护当前任务可候选、可见、隐藏、过期工具目录。 |
| `DynamicToolPolicy` | 策略能力 | 按 tenant、user、workflow node、allowlist 和风险等级裁剪工具。 |
| `AgentToolInstaller` | Agent loop 集成 | 将可见工具注入 ReAct / Workflow 工具列表。 |
| `ToolCallHandoff` | core 输出 | 中立表达模型请求端侧工具的名称、参数、toolCallId、schema 和恢复需求。 |
| `ToolResultRehydrator` | core 输入 | 将 runtime / host 回灌的结果转为 Agent loop observation。 |
| catalogVersion | 版本字段 | 标识动态工具目录版本，用于识别旧版本调用和多轮刷新。 |
| workflow scope | 作用域字段 | 限定节点级、回合级或任务级工具有效范围。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 当前页面工具注入 | client 上报页面能力快照 | core 构建任务级工具目录 | 通过策略的工具进入模型 schema；模型调用后 core 输出 handoff。 |
| 工作流节点临时动作 | Workflow 进入特定节点 | core 临时开放节点 Action | 工具只在当前节点可见，节点退出后过期，不再进入 prompt。 |
| 租户策略裁剪 | 不同租户有不同工具权限 | core 执行 DynamicToolPolicy | 未授权工具不进入模型上下文，裁剪原因可审计。 |
| 多轮能力刷新 | 客户端页面或插件状态变化 | 新 snapshot 进入下一轮上下文 | core 更新 catalogVersion，旧版本工具调用返回受控错误。 |
| 工具结果回灌 | runtime 接受客户端结果 | host 将 observation 交回 core | core 继续原 Agent loop，完成、失败或继续调用工具。 |
| 工具过期调用 | 模型尝试调用已过期工具 | core 检查 scope / catalogVersion | 返回 expired / unavailable observation，不直接执行。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 动态工具目录语义

- 动态工具目录属于当前 task、session、workflow node 或回合上下文，不是平台全局目录。
- 工具进入模型可见面前必须完成策略裁剪；未授权工具不能出现在 prompt 或 schema。
- 同名工具在不同租户、用户或节点下可以有不同 schema 和权限。

#### 5.1.1 调用移交语义

- 模型选择动态端侧工具时，core 只能产出 handoff / interrupt。
- handoff 必须包含工具名称、参数、toolCallId、catalogVersion、scope 和恢复所需元数据。
- runtime 或 host 决定如何投影为 A2A INPUT_REQUIRED、SSE 或 S2C intent。

#### 5.1.2 结果回灌语义

- core 接收的是 runtime / host 校验后的 tool observation。
- 用户拒绝、权限不足、能力不可用和过期都应能作为 observation 进入 Agent loop。
- core 内部恢复点只用于继续 Agent 执行，不替代 runtime Task 状态。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 未授权工具 | 不进入模型可见工具列表，并记录隐藏原因。 |
| 旧版本工具调用 | 返回 catalog_version_stale 或工具不可用 observation。 |
| 节点过期 | 工具从当前 schema 移除，调用返回 expired。 |
| handoff 失败 | Agent loop 形成可观察错误或失败结果。 |
| 结果 schema 不匹配 | 不回灌为成功 observation，返回结构化错误。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 客户端真实执行 | core 不执行页面读取、插件动作或人工确认。 |
| Runtime Task 管理 | core 不保存 TaskStore，不暴露 GetTask / CancelTask。 |
| 全局工具注册 | 动态端侧工具不自动成为 Skill Hub、MCP 或 middleware 工具。 |
| 绕过策略注入 | 模型或客户端不能绕过 policy 任意创建可见工具。 |
| 跨租户共享 | 动态工具目录不跨租户、用户或 session 共享。 |
| 业务事实写入 | tool observation 不自动写业务系统事实。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为 agent-core 动态端侧工具可见性和调用移交事实来源，不得把 core 描述为客户端工具执行器。
- agent-core、runtime 端侧工具响应和 agent-client capability 设计必须共享 ToolSpec、catalogVersion、scope、toolCallId、handoff 和 observation 语义。
- 测试必须覆盖初始注入、策略裁剪、workflow 节点工具、多轮刷新、旧版本调用、handoff 输出、结果回灌、工具过期和未授权隐藏。
- 开发指南必须强调 core 只负责模型可见工具和 handoff，不拥有 A2A Task 或客户端执行能力。
- 任何对全局工具市场、客户端直连执行、runtime Task 管理或绕过策略工具注入的新增承诺，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：ToolSpec、TaskScopedToolRegistry、DynamicToolPolicy、AgentToolInstaller、ToolCallHandoff、ToolResultRehydrator、catalogVersion、workflow scope。

## 7. 关联文档

- `agent-sdk/Docs/Agent-core组件端侧工具动态注册与调用特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-007-agent-client-local-tool-registration-remote-driven-invocation.md`
- `Docs/FEAT_Design/FEAT-2026-009-agent-runtime-client-side-tool-response.md`