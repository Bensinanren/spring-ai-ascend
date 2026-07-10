---
version: 0715
module: agent-runtime
feature_type: functional
feature_id: FEAT-2026-009
status: active
---

# Agent-runtime 组件调用端侧工具响应特性文档

## 1. 特性定位

FEAT-2026-009 定义 `agent-runtime` 当前版本在服务端 Agent 执行中请求客户端本地能力的事实：runtime 必须把端侧工具调用意图投影为 A2A Task 可观察的 `INPUT_REQUIRED`、SSE 事件或受治理 S2C envelope，并在客户端主动提交结果后校验和恢复原 Task。

本特性解决的问题是：服务端 Agent 需要页面上下文、终端插件或人工确认时，不能直接访问客户端资源，也不能把等待客户端能力伪装成普通完成。runtime 必须把这种协作表现为 Task 中断 / 待输入状态，向 client 暴露工具名称、参数、deadline、callbackId、风险说明和恢复方式，直到客户端提交结果、拒绝、失败、超时或取消。

在总体架构中，本特性位于 `agent-runtime` A2A 服务入口、AgentRuntimeHandler、agent-core handoff 和 agent-client 本地能力执行之间。runtime 是服务端 Task owner，负责 pending client tool call 的状态、恢复点、超时、取消、错误投影和审计关联；客户端执行由 FEAT-2026-007 承接，core 动态工具可见性由 FEAT-2026-010 承接。

本特性面向以下角色：

- Runtime 模块开发者：实现 Task 中断、pending call、恢复和取消语义。
- Agent 框架适配方：把模型端侧工具调用转换为 runtime 可理解的 interrupt / handoff。
- agent-client 集成方：消费 capability intent 并主动提交结果。
- Gateway / IngressGateway 开发者：转发 S2C intent 和 C2S result。
- 测试与验收团队：验证 INPUT_REQUIRED、恢复、超时、迟到、取消和错误表面。

本特性不定义客户端工具真实执行，不定义 agent-core 动态工具目录，也不新增 runtime 私有客户端回调协议；当前主路径必须对齐 A2A Task / SSE / Task control 语义。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 工具调用意图接收 | MUST | runtime 必须能接收 AgentRuntimeHandler、agent-core 或异构框架产出的端侧工具 handoff / interrupt。 |
| INPUT_REQUIRED 投影 | MUST | 端侧工具等待必须投影为 A2A `INPUT_REQUIRED` 或等价可观察 Task 状态，不得伪装 completed。 |
| SSE / S2C 可见 | MUST | runtime 必须通过 SSE、Task 查询或受治理 S2C envelope 暴露 capability intent。 |
| Pending call 管理 | MUST | runtime 必须记录 taskId、callbackId、toolCallId、deadline、scope、状态和恢复点。 |
| 客户端结果接收 | MUST | runtime 必须接收客户端主动提交的 result、error、payloadRef、artifactRef 或 auditRef。 |
| 恢复执行 | MUST | runtime 校验结果后必须把 observation 回灌 Agent 执行并恢复原 Task。 |
| 超时处理 | MUST | deadline 到期后必须拒绝迟到结果，并允许 Agent 降级、失败或取消。 |
| 取消处理 | MUST | `CancelTask` 必须取消 pending client tool call，并向 Task 表面反映取消语义。 |
| 重复/迟到拒绝 | MUST | runtime 必须基于 callbackId、toolCallId、deadline 和 Task 状态识别重复或迟到结果。 |
| 安全边界 | MUST | 客户端结果必须作为外部输入校验，不能直接写为业务事实。 |
| 大载荷引用 | MUST | 大正文、文件、多模态和审计材料必须支持引用传递。 |
| 客户端公网 webhook | OUT | 当前版本不要求 runtime 主动调用普通 client 自报 webhook。 |
| 私有端侧执行口 | OUT | 当前版本不新增绕过 A2A Task/SSE/error 的端侧工具私有执行入口。 |

## 3. 外部接口与入口要求

| 入口 | 类型 | 事实要求 |
|---|---|---|
| AgentRuntimeHandler output | Java SPI 输出 | Handler / adapter 可产出 INTERRUPTED、handoff 或等价端侧工具调用意图。 |
| A2A Task `INPUT_REQUIRED` | Task 状态 | 必须表达等待客户端输入或本地能力结果的事实状态。 |
| SSE `jsonrpc` event | A2A 流事件 | 必须可携带 capability intent 或等待输入投影，供 client 消费。 |
| `GetTask` | JSON-RPC method | 必须能查询到当前 Task 的等待输入状态和必要 pending call 信息。 |
| result submit / resume | C2S 入口 | 客户端主动提交工具结果、错误、拒绝、payloadRef、auditRef 和幂等信息。 |
| `CancelTask` | JSON-RPC method | 必须取消或终止等待中的端侧工具请求，并返回 Task 表面。 |
| PendingClientToolCall | runtime 状态对象 | 必须绑定 taskId、callbackId、toolCallId、deadline、tenant、session 和恢复点。 |
| Gateway / IngressGateway | 转发入口 | 必须转发 runtime intent 和 client result，不改变 Task 权威归属。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 制造工单创建前确认 | Agent 需要高风险 Action | runtime 将工具调用投影为 INPUT_REQUIRED | 客户端展示工单内容和风险，用户确认后提交结果，runtime 校验后恢复 Task。 |
| 页面上下文只读观测 | Agent 需要当前页面摘要 | runtime 发出 Observation intent | 客户端脱敏返回必要字段或 payloadRef，runtime 作为 observation 回灌 Agent。 |
| 用户拒绝动作 | Action 需要人工确认 | 用户拒绝执行 | 客户端提交 user_rejected，runtime 恢复 Agent 降级或形成明确失败。 |
| 能力结果迟到 | deadline 已过或 Task 已终态 | 客户端提交旧结果 | runtime 返回 expired、duplicate 或 task_already_terminal，不恢复已过期 Task。 |
| 取消等待任务 | Task 正处于 INPUT_REQUIRED | client 调用 CancelTask | runtime 取消 pending call，停止等待结果并投射 canceled。 |
| 大载荷结果 | 客户端能力产生文件或长文本 | 客户端提交 artifactRef / payloadRef | runtime 记录引用并按 Task 查询或后续处理读取，不要求 S2C envelope 承载正文。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.0 Task 中断语义

- 端侧工具请求必须成为 Task 可观察状态，客户端和 Gateway 能查询、订阅和恢复。
- `INPUT_REQUIRED` 表示 Task 等待外部输入，不是失败，也不是完成。
- 同一 Task 可多次进入等待端侧能力状态，但每次 pending call 必须有独立 callbackId / toolCallId。

#### 5.1.1 Intent 投影语义

- capability intent 必须包含工具名称、参数、展示说明、deadline、callbackId、toolCallId、scope 和风险信息。
- SSE / S2C envelope 不承载 token-by-token 流，也不承载大对象正文。
- 客户端不可见的内部恢复点不得泄露为可伪造字段。

#### 5.1.2 结果恢复语义

- runtime 必须校验 tenant、taskId、callbackId、deadline、Task 状态和结果 schema 后再恢复 Task。
- 用户拒绝、权限不足和能力不可用应作为 observation 回灌，除非策略要求直接失败。
- 恢复后 Agent 可继续执行、再次请求端侧能力、完成、失败或取消。

#### 5.1.3 错误、状态与可观测结果

| 场景 | 事实要求 |
|---|---|
| 工具 intent 生成失败 | Task 进入 failed 或返回 JSON-RPC error。 |
| 客户端结果无效 | 返回 invalid_result，不恢复 Task。 |
| deadline 到期 | pending call 进入 expired，迟到结果被拒绝。 |
| CancelTask | pending call canceled，Task 表面反映取消或当前状态。 |
| Task 已终态 | 所有后续工具结果必须拒绝。 |
| runtime 异常 | 形成 failed Task，并保留 tenant/task/correlation 日志关联。 |

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| 客户端真实执行 | runtime 不执行客户端本地工具，不访问 DOM、插件、文件或本地端口。 |
| 普通 client webhook | 不承诺 runtime 主动调用业务应用自报 webhook。 |
| Agent-core 工具目录 | 动态工具可见性和 handoff 由 agent-core 特性承接。 |
| 服务端业务事实写入 | 客户端结果只是 Agent observation，不自动成为业务系统事实。 |
| 强制中断模型调用 | 取消不承诺立即打断已进入模型客户端的阻塞请求。 |
| 私有 bus 执行口 | 不新增绕过 A2A Task 的端侧工具执行入口。 |

## 6. 对下游设计与实现的约束

- L2 设计必须把本特性作为 runtime 请求客户端能力的事实来源，保持 A2A Task / INPUT_REQUIRED / SSE / CancelTask 语义一致。
- AgentRuntimeHandler、agent-core adapter、Gateway、agent-client 必须共享 capability intent、PendingClientToolCall、callbackId、toolCallId、deadline、payloadRef、auditRef 字段语义。
- 测试必须覆盖端侧工具 intent、INPUT_REQUIRED 投影、SSE 可见、GetTask 查询、结果提交、恢复执行、用户拒绝、超时、迟到、重复、取消和大载荷引用。
- 开发指南不得把端侧工具响应描述为 runtime 直连客户端或客户端公网 webhook。
- 任何新增端侧工具私有协议、客户端直连、webhook 推送或非 Task 状态机能力，都必须先更新本特性或新增 version-scope 特性。
- 本特性术语必须保持稳定：INPUT_REQUIRED、PendingClientToolCall、capability intent、callbackId、toolCallId、deadline、resume、CancelTask、payloadRef、auditRef。

## 7. 关联文档

- `agent-sdk/Docs/Agent-runtime组件调用端侧工具响应特性设计.md`
- `Docs/FEAT_Design/FEAT-2026-007-agent-client-local-tool-registration-remote-driven-invocation.md`
- `Docs/FEAT_Design/FEAT-2026-010-agent-core-dynamic-client-side-tool-registration-invocation.md`
- `Docs/FEAT_Design/FEAT-001-standardized-agent-service-entrypoint.md`
