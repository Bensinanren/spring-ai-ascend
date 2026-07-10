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

# 智能体中间件请求代理：Agent Skill 配置管理

## 1. 特性定位

FEAT-005 定义 `agent-runtime` 对 Agent skill 来源的配置管理需求：runtime 为使用 agent-core 开发的 Agent 提供运行环境，并在 Agent 装配和启动时读取 Agent 配置中的 skill 来源声明。

当前版本中，agent-core 支持两类 skill 来源：

- 本地 skill 路径。
- 远端 skill 路径，当前远端来源仅支持 GitHub。

因此，本特性的 runtime 边界是：读取、校验并传递 Agent 配置中的本地 skill 路径和 GitHub 远端 skill 路径，使 agent-core Agent 能够基于配置获得所需的 skill 来源。runtime 不定义 skill 内容格式，不实现 Skill Hub 平台，也不负责 skill 的解析、执行或模型上下文注入策略。

## 2. 需求目标

| 目标 | 说明 |
|---|---|
| 管理 Agent skill 配置 | Runtime 应能够读取 Agent 的 skill 来源配置，并把该配置纳入 Agent 运行环境。 |
| 支持本地 skill 路径 | Runtime 应支持在 Agent 配置中声明本地 skill 路径。 |
| 支持 GitHub 远端 skill 路径 | Runtime 应支持在 Agent 配置中声明 GitHub 远端 skill 路径，作为当前唯一远端 skill 来源。 |
| 降低代码内绑定 | Agent skill 来源调整应优先通过配置完成，避免仅为变更 skill 路径而修改 Agent 代码。 |
| 保持 runtime 边界 | 本特性不改变 Agent 服务入口、任务生命周期、skill 处理逻辑、远程 Agent 编排或工具调用执行边界。 |

## 3. 能力范围

| 能力 | 需求说明 |
|---|---|
| 读取 Agent 配置 | Runtime 应在 Agent 装配和启动阶段读取 Agent 配置，并识别其中的 skill 来源声明。 |
| 管理本地路径配置 | Runtime 应能识别配置中声明的本地 skill 路径，并作为 Agent 运行环境输入提供给 Agent 装配流程。 |
| 管理远端路径配置 | Runtime 应能识别配置中声明的远端 skill 路径；当前远端来源限定为 GitHub。 |
| 管理访问配置 | 当 GitHub 远端 skill 来源需要认证或访问参数时，Runtime 应支持读取对应的安全配置或凭据引用，并保证敏感值不进入日志。 |
| 配置错误可诊断 | skill 来源配置缺失、格式错误、远端来源不受支持或访问配置不完整时，应给出明确诊断。 |
| 配置传递 | Runtime 应把已读取的 skill 来源配置传递给 Agent 的后续装配或执行流程。 |

## 4. 典型场景

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 使用本地 skill 路径启动 Agent | Agent 配置中声明本地 skill 路径 | 运维部署或启动 runtime | Runtime 读取本地 skill 路径配置，并将其纳入 Agent 运行环境。 |
| 使用 GitHub 远端 skill 路径启动 Agent | Agent 配置中声明 GitHub 远端 skill 路径 | 运维部署或启动 runtime | Runtime 读取 GitHub 远端 skill 路径配置，并将其纳入 Agent 运行环境。 |
| 调整 Agent skill 来源 | Agent 需要更换本地路径或 GitHub 远端路径 | 运维修改 Agent 配置并重新部署或重启 | Runtime 按新配置读取 skill 来源，不要求修改 Agent 业务代码。 |
| 配置了不支持的远端来源 | Agent 配置中声明非 GitHub 远端来源 | Runtime 读取 Agent 配置 | Runtime 给出明确诊断，不静默当作受支持来源处理。 |

## 5. 行为要求

### 5.1 Agent 配置读取

- Agent 配置应作为 runtime 装配 Agent 的输入读取。
- 配置应能声明本地 skill 路径或 GitHub 远端 skill 路径。
- 当前版本不要求运行中自动热更新 Agent 配置或 skill 来源配置。
- 配置缺失、格式不合法或引用不存在时，应给出可诊断错误。

### 5.2 本地 skill 路径

- Runtime 应能读取一个或多个本地 skill 路径。
- Runtime 应把本地 skill 路径作为配置输入提供给 Agent 装配流程。
- Runtime 不负责定义本地 skill 文件结构或正文处理规则。

### 5.3 GitHub 远端 skill 路径

- Runtime 应能读取 GitHub 远端 skill 路径配置。
- 当前版本远端 skill 来源只承诺 GitHub；其他远端来源不属于本特性范围。
- GitHub 访问所需的认证信息应通过安全配置或凭据引用提供，不得在日志中输出原文。
- Runtime 不负责定义远端 skill 的文件结构、版本策略或内容处理规则。

### 5.4 运行时可观测性

- 日志应能帮助定位当前 Agent 是否读取到 skill 来源配置，以及使用的是本地路径还是 GitHub 远端路径。
- 日志只能输出非敏感摘要，不得输出 token、密钥、认证头或客户私有凭据。
- 错误信息应区分配置缺失、格式错误、不支持的远端来源和访问配置不完整等常见原因。

## 6. 边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| Skill Hub 平台建设 | 不承诺实现 Skill Hub 管理后台、审批流、权限系统或运营能力。 |
| 非 GitHub 远端来源 | 不承诺支持 GitLab、Gitee、GitCode、对象存储、HTTP 文件服务或其他远端 skill 来源。 |
| Skill 内容格式 | 不承诺定义 skill 文件结构、包格式、依赖文件组织、版本策略或正文解析规则。 |
| Skill 处理逻辑 | 不承诺定义 skill 获取、解析、执行、工具调用或模型上下文注入等处理策略。 |
| 运行中热更新 | 不承诺在进程运行中自动刷新 Agent 配置或 skill 来源。 |
| 请求级动态切换 | 不承诺按每次用户请求、会话或任务动态切换 skill 来源。 |
| 远程 Agent 编排 | 不承诺把远程 Agent 注入为本地工具，也不承接远程任务、中断续接或取消传播。 |

## 7. 验收关注

- 能够通过 Agent 配置声明本地 skill 路径。
- 能够通过 Agent 配置声明 GitHub 远端 skill 路径。
- Runtime 能够读取 skill 来源配置，并把配置提供给 Agent 的后续装配或执行流程。
- 配置缺失、格式错误、不支持的远端来源或访问配置不完整时有明确诊断。
- 日志不输出 token、密钥、认证头或客户私有凭据。
- 不改变标准 Agent 服务入口、任务生命周期、skill 处理逻辑、远程 Agent 编排和工具调用边界。

## 8. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/development.md`
