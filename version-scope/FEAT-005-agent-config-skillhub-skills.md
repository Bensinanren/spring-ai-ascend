---
version: 0715
module: agent-runtime
feature_type: functional
feature_id: FEAT-005
status: active
related_docs:
  - ../architecture/L0-Top-Level-Design/boundaries.md
  - ../architecture/L0-Top-Level-Design/glossary.md
  - ../architecture/L1-High-Level-Design/agent-runtime/README.md
  - ../architecture/L1-High-Level-Design/agent-runtime/development.md
---

# 智能体中间件请求代理特性文档

## 1. 特性定位

FEAT-005 定义智能体中间件请求代理在部署态读取 Agent 配置，并根据配置从 Skill Hub 获取相应 skill 的需求范围。

该特性面向 `agent-runtime` 的 Agent 启动与能力装配入口：部署人员在 Agent 配置中声明 Agent 基础信息、模型引用与 skill 需求；runtime 读取该配置，并按配置向 Skill Hub 获取当前 Agent 需要的 skill，使 Agent 的 skill 集合可以由配置驱动。

本特性只描述 runtime 侧需求事实：runtime 需要支持“读取 Agent 配置”和“按配置从 Skill Hub 获取 skill”。具体配置格式、Skill Hub 接口、缓存策略、skill 内容组织方式和内部模块设计由后续设计文档约束。

## 2. 需求目标

| 目标 | 说明 |
|---|---|
| 配置声明 Agent skill 需求 | Agent 配置应能声明当前 Agent 需要从 Skill Hub 获取哪些 skill 或哪一组 skill。 |
| Runtime 按配置获取 skill | Runtime 应根据读取到的 Agent 配置向 Skill Hub 获取相应 skill，并把获取结果提供给后续 Agent 能力装配流程。 |
| 减少代码内硬编码 | Agent skill 集合调整应优先通过配置变化完成，避免仅为变更 skill 绑定关系而修改业务代码。 |
| 保持 runtime 边界 | 本特性不改变 Agent 服务入口、任务生命周期、远程 Agent 编排、工具调用执行或 Skill Hub 平台治理边界。 |

## 3. 能力范围

| 能力 | 需求说明 |
|---|---|
| 读取 Agent 配置 | Runtime 应在 Agent 启动或装配阶段读取配置，并识别 Agent 标识、模型引用和 skill 需求。 |
| 解析 skill 绑定关系 | Runtime 应从 Agent 配置中解析需要向 Skill Hub 请求的 skill 标识、skill 组或等价选择条件。 |
| 发起 Skill Hub 获取请求 | Runtime 应基于解析结果向 Skill Hub 获取相应 skill。 |
| 接收 Skill Hub 返回结果 | Runtime 应接收 Skill Hub 返回的 skill 信息，并将其作为 Agent 能力装配输入。 |
| 获取过程可诊断 | 配置缺失、配置不合法、Skill Hub 请求失败或返回不可用结果时，应有明确错误或告警信息。 |
| 敏感信息保护 | 模型密钥、访问凭据、token、客户私有鉴权信息和 skill 敏感内容不得出现在日志中。 |

## 4. 典型场景

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 部署 Agent 并绑定 skill | 部署人员准备 Agent 配置和 Skill Hub 中的 skill | 部署应用并声明 Agent 需要的 skill | 系统读取配置，从 Skill Hub 获取对应 skill，使 Agent 具备配置声明的能力。 |
| 调整 Agent skill 绑定 | Agent 需要更换或增减关联 skill | 运维或交付人员修改 Agent 配置并重新部署或重启 | Runtime 按新配置重新获取 skill，不要求修改业务代码。 |
| Skill Hub 不可用 | Skill Hub 网络异常、认证失败或服务不可达 | 系统尝试获取 skill | 系统给出明确错误或告警，不泄露凭据。 |

## 5. 行为要求

### 5.1 Agent 配置读取

- Agent 配置应作为 runtime 装配 Agent 的输入读取，当前版本不要求运行中自动热更新。
- 配置应能标识目标 Agent，并声明该 Agent 需要的 skill 或 skill 筛选条件。
- 配置缺失、格式不合法或引用不存在时，应给出可诊断错误。
- 配置中的密钥、token、认证头等敏感值应通过外部化方式提供，日志不得输出原文。

### 5.2 Skill Hub 获取

- Runtime 应根据 Agent 配置发起 Skill Hub 获取请求，并接收 Skill Hub 返回的 skill 信息。
- Runtime 不在本特性中定义 skill 的内部格式、分层加载策略或完整内容读取策略。
- Skill Hub 返回结果应与 Agent 配置中的 skill 需求对应。
- 当前版本不要求 runtime 根据每次用户请求、会话或任务动态切换 skill 集合。
- 如后续需要请求级 skill 权限过滤，应作为独立能力设计其生命周期、性能和一致性边界。

### 5.3 运行时可观测性

- 日志应能帮助定位当前 Agent 是否读取到配置、是否成功请求 Skill Hub、获取到多少 skill，以及失败原因。
- 日志只能输出非敏感摘要，不得输出凭据或完整敏感内容。
- 错误信息应区分配置错误、Skill Hub 访问错误、skill 不存在和权限不足等常见原因。

## 6. 边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| Skill Hub 平台建设 | 不承诺实现 Skill Hub 管理后台、审批流、权限系统或运营能力。 |
| 运行中热更新 | 不承诺在进程运行中自动刷新 Agent 配置或 skill 集合。 |
| 请求级动态切换 | 不承诺按每次用户请求、会话或任务动态注册、取消注册或切换 skill。 |
| Skill 内部加载策略 | 不承诺定义 skill 摘要、正文、依赖文件或包结构的分层加载规则。 |
| 工具调用执行 | Skill Hub 获取 skill 不等同于执行工具调用；工具调用能力由其他特性或框架能力承接。 |
| 远程 Agent 编排 | 不承诺把远程 Agent 注入为本地工具，也不承接远程任务、中断续接或取消传播。 |
| 私有 skill 包规范 | 不承诺定义客户私有 skill 包格式、签名、扫描或供应链规则。 |
| 不可信配置沙箱 | 不承诺对不可信配置中的文件路径、网络地址或扩展内容提供完整安全沙箱。 |

## 7. 验收关注

- 能够通过部署态配置声明 Agent 与 skill 需求。
- 能够根据配置从 Skill Hub 获取匹配 skill。
- Runtime 能够把 Skill Hub 返回的 skill 信息提供给后续 Agent 能力装配流程。
- 配置错误、Skill Hub 不可用或返回不可用结果时有明确诊断。
- 不会把完整 skill 正文默认注入模型上下文。
- 不会把密钥、token、认证信息或敏感 skill 内容输出到日志。
- 不改变标准 Agent 服务入口、任务生命周期、远程 Agent 编排和工具调用边界。

## 8. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/development.md`
