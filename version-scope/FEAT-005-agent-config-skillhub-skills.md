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

该特性面向部署和运行阶段的 Agent 能力装配：部署人员在部署配置中声明 Agent 基础信息、模型与 skill 需求；运行时按该配置向 Skill Hub 获取当前 Agent 可使用的 skill，使 Agent 能力不再完全依赖代码内硬编码或本地固定目录。

本特性只描述需求事实：系统需要支持“部署态配置驱动 Agent 能力”和“从 Skill Hub 获取 skill”这两个能力。具体配置格式、接口形态、缓存策略、框架适配方式和内部模块设计由后续设计文档约束。

## 2. 需求目标

| 目标 | 说明 |
|---|---|
| 部署态声明 Agent 能力 | Agent 的基础信息、模型配置和 skill 需求应能够通过部署配置声明，减少重新发版才能调整 Agent 能力的情况。 |
| 从 Skill Hub 获取 skill | 系统应能根据部署态 Agent 配置，从 Skill Hub 获取该 Agent 需要的 skill 信息。 |
| 支持不同部署环境 | 同一 Agent 在不同环境、租户或业务场景下可以通过配置关联不同 skill 集合。 |
| 保持渐进加载 | 系统不应要求一次性把所有 skill 完整内容加载到模型上下文；应优先获取轻量信息，并在需要时获取完整内容。 |
| 保持架构边界 | 本特性不改变 Agent 服务入口、任务生命周期、远程 Agent 编排或工具调用边界。 |

## 3. 能力范围

| 能力 | 需求说明 |
|---|---|
| 读取部署态 Agent 配置 | 系统应在部署或启动阶段读取 Agent 配置，并识别 Agent 标识、描述、模型引用和 skill 需求。 |
| 识别 skill 需求 | Agent 配置应能表达该 Agent 需要使用哪些 skill，或表达用于筛选 skill 的稳定条件。 |
| 请求 Skill Hub | 系统应根据 Agent 配置向 Skill Hub 请求 skill，获取当前 Agent 可用的 skill 信息。 |
| 获取 skill 摘要 | 系统应支持获取 skill 的轻量摘要，例如 skill 标识、名称、描述或标签，用于能力发现和选择。 |
| 获取 skill 完整内容 | 当 Agent 执行或能力装配需要时，系统应能基于 skill 标识获取完整 skill 内容。 |
| 空结果处理 | 当 Skill Hub 未返回匹配 skill 时，系统应给出明确诊断信息；是否允许 Agent 继续运行由配置或设计约束。 |
| 错误可诊断 | 配置缺失、配置不合法、Skill Hub 不可用、skill 不存在或无权限时，应有明确错误或告警信息。 |
| 敏感信息保护 | 模型密钥、访问凭据、token、客户私有鉴权信息和 skill 敏感内容不得出现在日志中。 |

## 4. 典型场景

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 部署 Agent 并绑定 skill | 部署人员准备 Agent 配置和 Skill Hub 中的 skill | 部署应用并声明 Agent 需要的 skill | 系统读取配置，从 Skill Hub 获取对应 skill，使 Agent 具备配置声明的能力。 |
| 按环境切换 skill 集合 | 测试环境和生产环境使用不同 skill 集合 | 运维调整部署配置并重新部署或重启 | 系统按新配置获取 skill，不要求修改业务代码。 |
| Skill Hub 返回空集合 | Agent 配置存在，但 Skill Hub 没有匹配 skill | 系统请求 Skill Hub | 系统记录可诊断信息，并按配置或设计决定是否继续启动或执行。 |
| Skill Hub 不可用 | Skill Hub 网络异常、认证失败或服务不可达 | 系统尝试获取 skill | 系统给出明确错误或告警，不泄露凭据。 |
| 获取完整 skill 内容 | Agent 已发现 skill 摘要，执行时需要详细说明 | 系统按 skill 标识请求完整内容 | 系统获取完整 skill 内容并交给后续 Agent 能力装配或执行流程使用。 |

## 5. 行为要求

### 5.1 部署态配置

- Agent 配置应作为部署态输入读取，当前版本不要求运行中自动热更新。
- 配置应能标识目标 Agent，并声明该 Agent 需要的 skill 或 skill 筛选条件。
- 配置缺失、格式不合法或引用不存在时，应给出可诊断错误。
- 配置中的密钥、token、认证头等敏感值应通过外部化方式提供，日志不得输出原文。

### 5.2 Skill Hub 获取

- 系统应先获取 skill 轻量信息，再按需获取完整 skill 内容，避免一次性加载大量 skill 正文。
- Skill Hub 返回的 skill 集合应与部署态 Agent 配置匹配。
- 当前版本不要求根据每次用户请求、会话或任务动态切换 skill 集合。
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
| 工具调用执行 | Skill Hub 获取 skill 不等同于执行工具调用；工具调用能力由其他特性或框架能力承接。 |
| 远程 Agent 编排 | 不承诺把远程 Agent 注入为本地工具，也不承接远程任务、中断续接或取消传播。 |
| 私有 skill 包规范 | 不承诺定义客户私有 skill 包格式、签名、扫描或供应链规则。 |
| 不可信配置沙箱 | 不承诺对不可信配置中的文件路径、网络地址或扩展内容提供完整安全沙箱。 |

## 7. 验收关注

- 能够通过部署态配置声明 Agent 与 skill 需求。
- 能够根据配置从 Skill Hub 获取匹配 skill。
- 能够获取 skill 轻量摘要，并在需要时获取完整 skill 内容。
- 配置错误、Skill Hub 不可用、skill 不存在或无权限时有明确诊断。
- 不会把完整 skill 正文默认注入模型上下文。
- 不会把密钥、token、认证信息或敏感 skill 内容输出到日志。
- 不改变标准 Agent 服务入口、任务生命周期、远程 Agent 编排和工具调用边界。

## 8. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/development.md`
