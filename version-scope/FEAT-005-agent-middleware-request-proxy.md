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
  - ../architecture/L1-High-Level-Design/agent-runtime/spi-appendix.md
  - ../agent-runtime/docs/guides/skillhub.md
---

# 智能体中间件请求代理特性文档

## 1. 特性定位

FEAT-005 定义 `agent-runtime` 在部署态读取 Agent 配置，并通过智能体中间件请求代理从 SkillHub 获取该 Agent 所需 skill 的统一接入要求。该特性使 Agent 的模型、提示词、工具、rail、skill 来源和运行时中间件能力可以从部署配置或配置中心声明，而不是固化在应用代码中；运行时在处理请求时基于当前 Agent、租户、用户、会话和任务上下文向 SkillHub 请求可见技能，并把技能安装到具体 Agent 框架可消费的位置。

本特性解决的问题是：当前智能体能力往往由 Java Bean、应用配置和本地 skill 目录共同决定，部署人员难以在不重新发版的情况下调整 Agent 能力组合；同时 skill 来源可能从本地目录扩展到远端 SkillHub、企业配置中心或平台级技能目录，运行时不能把业务请求直接绑定到某一种 skill 存储形态、OpenJiuwen 原生 skill 机制或 MCP tool 调用机制。

本特性对客户的价值是：Agent 能力可以随部署环境、租户、用户和业务场景进行配置化选择，技能目录可以由客户或平台统一治理。对产品的价值是：通过窄 SPI 和请求代理边界接入 SkillHub，避免在各 Agent adapter 中重复实现技能发现、加载和安装逻辑，为后续 Nacos/注册中心、远端 SkillHub 和多租户权限过滤留下稳定扩展点。

对下游设计和实现而言，本特性是 `agent-runtime` 智能体中间件请求代理与 SkillHub 接入的事实来源。L2 设计、SkillHub SPI、OpenJiuwen adapter 安装逻辑、部署配置读取、日志、测试和指南必须以本文定义的外部行为、边界和验收要求为准；main 分支中已经存在但本文未声明的 registry、热更新、权限模型、工具调用或客户专用能力，不能自动成为当前版本对外事实承诺。

本特性面向以下角色：

- 现场交付人员：在部署态声明 Agent 配置、SkillHub Provider 和 skill 选择策略，确认应用启动后生效。
- 客户平台团队：提供 SkillHub、配置中心或本地目录等技能来源，并按租户、用户、Agent 或业务场景过滤技能。
- Runtime 开发者：通过统一 SkillHub SPI 与 adapter 安装钩子接入技能，不把 A2A 执行链路绑定到具体技能仓库。
- Agent 开发者：继续使用具体 Agent 框架的原生 skill/tool 能力，同时接受 runtime 在执行前注入的部署态技能。
- 运维人员：通过启动和执行日志确认当前 Agent 配置、SkillHub 接入状态、技能摘要数量、安装数量和异常原因。
- 集成测试团队：验证部署配置读取、SkillHub 摘要加载、完整 skill 加载、框架安装、缺省降级和异常诊断。

本特性只定义部署态 Agent 配置读取、SkillHub 请求代理、技能发现与加载、框架安装、日志和验收边界。SkillHub 平台建设、企业权限系统、MCP tool 执行、远程 A2A Agent 编排、动态热更新、模型网关治理和客户私有 skill 包格式不属于当前版本承诺。

## 2. main 分支实现事实参考

本需求在 main 分支已有可参考实现，当前需求描述抽象其对外行为，不直接复制类级设计：

| 实现事实 | main 分支参考点 | 需求抽象 |
|---|---|---|
| 部署/文件态 Agent 装配 | `agent-sdk` 支持从 `ascend-agent/v1` YAML 构造 OpenJiuwen ReActAgent 或 DeepAgent | Agent 的模型、prompt、tools、rails、skills 等能力应能从部署配置读取并构造成运行时可执行 Agent。 |
| YAML skill source | `skills.sources[]` 支持本地 filesystem skill source，并展开包含 `SKILL.md` 的目录 | 部署态配置必须能表达 Agent 所需 skill 来源；当前版本至少承认本地目录来源，远端 SkillHub 通过 Provider 扩展。 |
| SkillHub SPI | `SkillHubProvider.listSkills(context)`、`loadSkill(context, skillId)`、可选 `loadSkillPackage(...)` | Runtime 通过框架中立 SPI 代理请求 SkillHub，先取摘要，再加载完整定义或包。 |
| OpenJiuwen 安装 | `OpenJiuwenSkillHubInstaller` 读取 `openjiuwen.skill.path(s)` metadata 并调用 `registerSkill(...)` | 具体框架安装逻辑应封装在 adapter 内，公共 SkillHub 协议不暴露框架私有类型。 |
| ReAct prompt 可见性 | ReActAgent 会额外注入 `runtime_skillhub` prompt section | 对无法被原生 skill manager 完整识别的 skill，运行时应尽量保证 instructions 对模型可见。 |
| 自动装配 | 存在 `SkillHubProvider` 和 OpenJiuwen handler 时自动注入 installer | 无 SkillHub Provider 时 Agent 正常启动；有 Provider 时运行时自动接入请求代理。 |
| 上下文过滤 | Provider 接收 `AgentExecutionContext` | SkillHub 可以基于 tenant、user、agentId、sessionId、taskId 返回不同 skill 集合。 |

## 3. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 部署态 Agent 配置读取 | MUST | 系统必须支持从部署文件或等价配置来源读取 Agent 定义，并据此装配 Agent 的基础属性、模型、prompt、工具、rail 和 skill 来源。 |
| 配置 schema 约束 | MUST | 部署态 Agent 配置必须有明确 schema/version 约束；不支持的 schema、框架类型或 Agent 类型必须快速失败并给出可诊断错误。 |
| 环境变量占位符 | MUST | 配置中的敏感值应支持通过环境变量或等价外部化机制注入；缺失值必须快速失败，日志不得输出密钥原文。 |
| SkillHub Provider SPI | MUST | 系统必须提供框架中立的 SkillHub SPI，支持列出当前上下文可见的 skill 摘要并按 skillId 加载完整定义。 |
| 渐进式 skill 加载 | MUST | Runtime 不应在摘要阶段加载大量 instructions 或 skill 包；完整定义只在安装或使用前按需加载。 |
| 请求上下文透传 | MUST | Runtime 请求 SkillHub 时必须携带执行上下文，使 Provider 能按 tenant、user、session、task 和 agentId 做过滤。 |
| OpenJiuwen skill 安装 | MUST | 当前版本必须支持把 SkillHub 返回的 OpenJiuwen 本地 skill 路径安装到 OpenJiuwen ReActAgent 或 DeepAgent 可消费位置。 |
| ReAct instructions 注入 | SHOULD | 对 ReActAgent，系统应把 SkillHub 完整定义中的 instructions 注入运行时 prompt section，保证模型可见。 |
| 无 Provider 降级 | MUST | 未配置或未装配 SkillHub Provider 时，Agent 必须按原有配置正常执行，不得因缺少 SkillHub 阻断启动或请求。 |
| Provider 自定义实现 | MUST | 本地目录、远端 HTTP JSON、Nacos/注册中心或客户自定义 SkillHub 应通过 Provider 实现接入，上层 Agent adapter 不应感知具体来源。 |
| 安装日志与诊断 | MUST | 日志必须输出非敏感的 provider/handler 接入状态、skill 摘要数量、加载数量、安装数量和跳过原因，便于定位配置或 skill 包问题。 |
| 配置与请求代理解耦 | MUST | 部署态 Agent 配置读取和 SkillHub 请求代理必须保持边界清晰：配置负责声明 Agent 与来源，SkillHub 负责按上下文返回 skill 内容。 |
| Skill 与 MCP 解耦 | MUST | SkillHub 只负责 skill 摘要、说明、依赖和包加载，不负责执行 MCP tool 或其他工具调用。 |
| 动态热更新 | MAY | 当前版本不要求运行中热更新 Agent 配置或 skill 列表；如 Provider 内部缓存或刷新，由 Provider 自行治理。 |

## 4. 外部接口与入口要求

| 入口 / 配置 | 类型 | 事实要求 |
|---|---|---|
| Agent 部署配置 | configuration | 必须能够表达 Agent id、展示名、描述、框架类型、Agent 类型、模型、prompt、tools、rails 和 skill 来源。当前 main 分支参考 schema 为 `ascend-agent/v1`。 |
| Agent 配置加载 API | SDK/API | 应提供从配置路径或配置内容构造框架原生 Agent 的入口；构造出的 Agent 可由 `agent-runtime` handler 托管。 |
| Skill 来源配置 | configuration | 应支持声明本地 filesystem skill source；远端 SkillHub 或配置中心通过注册 `SkillHubProvider` 接入，不要求在公共 schema 中暴露客户私有字段。 |
| SkillHub Provider | SPI | 必须提供 `listSkills(context)` 和 `loadSkill(context, skillId)` 等价能力；`loadSkillPackage(...)` 为可选能力。 |
| SkillSummary | data model | 必须表达 skillId、name、description、tags 和 metadata，用于轻量发现和过滤。 |
| SkillDefinition | data model | 必须表达 skillId、name、description、instructions、referenceUris、toolDependencies 和 metadata，用于完整安装和 prompt 注入。 |
| 框架 metadata | data model | 对 OpenJiuwen，metadata 可包含 `openjiuwen.skill.path` 或 `openjiuwen.skill.paths`，由 OpenJiuwen adapter 消费；该字段不是公共 SkillHub 协议必填项。 |
| Runtime handler 安装钩子 | adapter hook | 具体 Agent adapter 必须有在执行前安装 runtime 中间件能力的钩子，使 SkillHub、MCP、远程工具等能力可以独立注入。 |
| 自动装配入口 | Spring Boot autoconfiguration | 当 classpath、SkillHub Provider 和支持的 Agent handler 同时存在时，系统应自动创建 SkillHub installer 并注入 handler。 |
| 执行日志 | observability | 必须输出 tenantId、sessionId、taskId、agentId、skill 数量、安装结果和跳过原因等非敏感信息；不得输出 API key、token、凭据或 skill 包中的敏感内容。 |

## 5. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 从部署配置启动 Agent | 应用提供合法 Agent YAML 或等价配置 | 运维部署应用并指定配置路径 | Runtime 或应用侧 SDK 读取配置，构造 Agent，并通过标准 A2A 入口对外服务。 |
| 本地目录 skill 装配 | 配置声明 filesystem skill source，目录中存在 `SKILL.md` | 应用启动或请求创建 Agent | ReActAgent 注册对应 skill 目录；DeepAgent 获得 skill root；重复 skill 或目录形态冲突时快速失败。 |
| 远端 SkillHub 返回可见技能 | 应用注册 SkillHubProvider，Provider 可访问远端目录 | Runtime 在请求执行前调用 `listSkills(context)` | Provider 按 tenant/user/agentId 过滤，返回轻量摘要；无可见 skill 时 Agent 正常执行。 |
| 按 skillId 加载完整定义 | SkillHub 摘要返回 skillId | Runtime 对每个可安装 skill 调用 `loadSkill(context, skillId)` | Runtime 获取 instructions、referenceUris、toolDependencies 和 metadata，并交给 adapter 安装。 |
| OpenJiuwen ReAct skill 安装 | SkillDefinition metadata 包含 OpenJiuwen 本地路径 | Runtime 调用 OpenJiuwen adapter 安装逻辑 | Adapter 调用原生 skill 注册能力，并把 instructions 注入 `runtime_skillhub` prompt section。 |
| OpenJiuwen DeepAgent skill 安装 | DeepAgent 内部 ReAct skill runtime 已配置 | Runtime 调用 DeepAgent adapter 安装逻辑 | Adapter 把 skill 安装到 DeepAgent 内部 ReAct agent；未配置 skill runtime 时记录 warn 并跳过，不重建 DeepAgent。 |
| 无 SkillHub Provider | 应用未配置 SkillHubProvider | Runtime 启动和处理请求 | 不创建 SkillHub installer，Agent 按自身配置正常执行。 |
| SkillHub 不可用或配置错误 | Provider 访问远端失败或返回非法定义 | Runtime 加载或安装 skill | 系统给出可诊断错误或降级日志，不输出敏感信息；是否阻断请求由 L2 设计按错误类型明确。 |

## 6. 行为语义与边界

### 6.1 核心行为语义

#### 6.1.1 部署态配置语义

- Agent 配置属于部署态事实，应用启动或创建 Agent 时读取；当前版本不要求运行中自动热更新。
- 配置必须能明确区分框架类型和 Agent 类型；不支持的组合必须快速失败。
- 配置中的相对路径按配置文件所在目录解析，避免部署目录变化导致不可预测行为。
- 配置中的模型 API key、token、认证头等敏感值必须通过环境变量、密文或等价外部化机制注入，日志只允许输出是否配置和非敏感摘要。
- Agent 配置读取只负责构造 Agent 及其本地能力，不直接拥有 A2A Task 生命周期；托管、请求、取消、恢复和 SSE 输出仍由 `agent-runtime` 标准入口负责。

#### 6.1.2 SkillHub 请求代理语义

- Runtime 通过 SkillHub Provider SPI 代理请求 SkillHub，不直接依赖本地目录、HTTP JSON、Nacos、数据库或客户私有注册中心实现。
- `listSkills(context)` 返回当前执行上下文可见的轻量摘要；Provider 可以根据租户、用户、Agent、会话和任务过滤结果。
- `loadSkill(context, skillId)` 返回完整定义；当 skillId 不可见、已删除或无权限时，Provider 应返回明确错误或空结果，由 Runtime 记录可诊断日志。
- `loadSkillPackage(context, skillId)` 是可选能力；当前版本不要求所有 Provider 支持 skill 包下载。
- Provider 可以自行缓存摘要、定义或远端响应；缓存一致性、刷新周期和失效策略由 Provider 或后续 L2 设计约束。

#### 6.1.3 框架安装语义

- 公共 SkillHub SPI 不暴露 OpenJiuwen、AgentScope、MCP 或其他框架私有类型。
- 具体框架 adapter 负责把 `SkillDefinition` 转换为框架可消费的 skill、prompt section、knowledge asset 或等价对象。
- OpenJiuwen 当前使用 metadata 中的 `openjiuwen.skill.path` / `openjiuwen.skill.paths` 安装本地 skill 路径；缺少这些 metadata 时，adapter 不应臆造路径。
- 对 ReActAgent，runtime 应尽量把完整 skill instructions 注入到独立 prompt section，避免原生 skill manager 因 `SKILL.md` 格式不完整导致模型看不到技能说明。
- 对 DeepAgent，adapter 不应在执行期重建或重新配置内部 skill runtime；当前实例未初始化 skill runtime 时应记录跳过原因。

#### 6.1.4 SkillHub 与 MCP / 远程 Agent 的关系

- SkillHub 管理 skill 摘要、说明、参考资料、工具依赖描述和可选包加载。
- MCP 管理工具发现和工具调用；SkillHub 可以声明 toolDependencies，但不执行工具调用。
- 远程 Agent 编排管理 A2A Agent Card、远程工具、远程任务、中断续接和取消传播；SkillHub 不替代远程 Agent 编排。
- 三类中间件能力可以在同一 Agent 执行前安装，但必须保持 SPI、配置和错误边界独立。

#### 6.1.5 错误与日志语义

- 配置 schema 错误、必填字段缺失、环境变量缺失、路径不存在和重复 skill 名称应快速失败。
- SkillHub Provider 不存在不是错误；远端 SkillHub 不可用、返回非法定义或安装失败必须记录可诊断日志。
- 日志必须包含非敏感上下文和计数信息，例如 tenantId、sessionId、taskId、agentId、summary 数量、loaded 数量、installed 数量、injected 数量和 skip reason。
- 日志不得输出模型 API key、Authorization header、token、密钥、客户凭据、skill 包敏感内容或配置密文原文。

### 6.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| SkillHub 平台建设 | 不承诺实现企业级 SkillHub 管理后台、注册中心、权限系统或审批流。 |
| 动态热更新 | 不承诺运行中自动刷新 Agent 配置、替换 Agent 实例或重新安装 skill。 |
| 全框架 skill 安装 | 当前版本必须支持 OpenJiuwen；其他框架如何消费 SkillHub 定义由后续 adapter 设计承接。 |
| Skill 包格式治理 | 不承诺定义客户私有 skill 包格式、签名、扫描、审批或供应链策略。 |
| MCP tool 执行 | SkillHub 不负责调用 MCP tool；工具调用由 MCP Provider 或框架 tool 机制处理。 |
| 远程 Agent 编排 | 不负责把远程 A2A Agent 注入为本地工具；该能力属于远程 Agent 编排特性。 |
| 企业权限模型 | 不承诺内置多租户权限规则；权限过滤由业务或客户自定义 Provider 实现。 |
| 不可信配置安全沙箱 | 不承诺对不可信 YAML 中声明的 HTTP tool、文件路径或 classpath 扩展做完整安全沙箱。 |
| 客户私有配置中心 | 不承诺内置某个客户配置中心协议；应通过 Provider 或部署侧适配接入。 |

## 7. 对下游设计与实现的约束

- L2 设计必须把部署态 Agent 配置读取、SkillHub 请求代理和框架 adapter 安装逻辑拆分为清晰边界，不得让 A2A 协议层直接依赖 SkillHub 具体实现。
- L2 设计必须明确 Agent 配置 schema、必填字段、环境变量替换、路径解析、错误类型和敏感信息脱敏策略。
- L2 设计必须明确 SkillHub Provider 的调用时机、异常处理、空结果语义、缓存责任和上下文过滤约定。
- 实现必须保证未装配 SkillHub Provider 时不影响 Agent 启动和请求执行。
- 实现必须保证 OpenJiuwen adapter 在执行前安装 SkillHub skills，且与 MCP installer、远程 Agent tool installer 的顺序和冲突语义可诊断。
- 测试必须覆盖合法配置装配、非法 schema 快速失败、环境变量缺失、filesystem skill 展开、重复 skill 冲突、Provider 不存在降级、Provider 返回摘要与定义、OpenJiuwen skill path 安装、ReAct instructions 注入、DeepAgent skill runtime 缺失跳过和日志脱敏。
- 开发者指南应说明如何编写部署态 Agent 配置、如何注册 SkillHub Provider、如何在 SkillDefinition metadata 中声明 OpenJiuwen skill path、如何确认安装日志，以及 SkillHub 与 MCP/远程 Agent 的边界。

## 8. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/development.md`
- `architecture/L1-High-Level-Design/agent-runtime/spi-appendix.md`
- `agent-runtime/docs/guides/skillhub.md`
- main 分支参考：`architecture/docs/L2/agent-runtime/skillhub-runtime-middleware-design.md`
- main 分支参考：`architecture/docs/L2/agent-runtime/agent-sdk-openjiuwen-yaml-assembly-design.md`
- main 分支参考：`agent-runtime/docs/guides/agent-sdk-openjiuwen-yaml.md`
