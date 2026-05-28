---
level: L1
view: governance
status: draft
---

# Document Artifact Catalog

## 目的

登记 `docs/architecture/` 下每个文档的主要内容、主要作用、归属 A2D 活动和质量检查点，避免文档职责漂移、重复承载或无人维护。

本文不是新的架构设计来源，而是文档质量索引。新增、删除、改名或改变文档职责时，必须同步更新本文。

## 适用读者

架构负责人、模块负责人、文档作者、评审者、AI agent、harness 生成器。

## 维护规则

- 每个 `docs/architecture/` 下的 Markdown 或机器可读 contract 文件都必须在本文有记录。
- 每条记录必须说明主要内容、主要作用、对应 A2D 活动和质量检查点。
- 如果一个文件承载多个职责，必须拆分为主职责和辅助职责；主职责只能有一个。
- 如果某个文件的实际内容与本文登记不一致，优先修正文档内容或更新本文记录，不允许长期漂移。
- 新增模块目录时，必须为该模块的 README、设计、状态、流程、harness、open issues 等文件增加记录。

## 字段说明

| 字段 | 含义 |
|---|---|
| 文件 | 文档相对 `docs/architecture/` 的路径。 |
| 主要内容 | 文件应该承载的信息范围。 |
| 主要作用 | 文件在 A2D 或评审中的用途。 |
| A2D 活动 | 主要由哪个 A2D 活动产出或维护。 |
| 质量检查点 | 评审时需要重点检查的约束。 |

## 根目录文档

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [README.md](../README.md) | 文档集入口、权威来源、文档地图和维护规则。 | 帮助读者定位文档体系和使用顺序。 | A10 版本归档 | 文档地图必须包含新增关键治理文档；不得把 draft 内容写成权威来源。 |
| [task.md](../task.md) | 原始任务说明、阶段讨论背景和早期输入。 | 承载 raw input，作为 A2D intake 前的暂存区。 | A0 需求进入 | 不应被当成 accepted 设计；关键结论应迁移到正式产物。 |
| [constraint-and-design-inventory.md](../constraint-and-design-inventory.md) | 约束、设计候选、冲突、开放问题和准入线索。 | 集中记录跨文件冲突和待裁决设计项。 | A1 架构准入判定 | Conflict / Open Issue 必须有影响范围、后续位置或 owner。 |

## 00 Overview

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [00-overview/architecture-overview.md](../00-overview/architecture-overview.md) | L0 系统运行架构、真实模块边界、核心控制流和数据流。 | 建立系统级心智模型，帮助读者理解模块如何协作。 | A1 架构准入判定 / A4 模块责任承接 | 只允许 Architecture Module 和必要 Runtime Component 进入模块边界；不得混入 BoM、starter、demo、fixture。 |
| [00-overview/glossary.md](../00-overview/glossary.md) | 核心术语、缩写、历史命名和当前口径。 | 防止 Task / Run、Gateway / Bus、Capability / Module 等术语混用。 | A1 架构准入判定 | 术语必须与 Overview、Module Cards、State Matrix 和 ICD 一致。 |
| [00-overview/system-principles.md](../00-overview/system-principles.md) | 系统原则、设计取舍和跨模块约束。 | 把权威规则翻译为可读的架构原则。 | A10 版本归档 | 不得替代 `CLAUDE.md`、`docs/adr/` 或治理 YAML；原则变化应进入 ADR / CR。 |

## 01 Capabilities

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [01-capabilities/capability-map.md](../01-capabilities/capability-map.md) | 能力地图、能力 owner、能力与场景/模块/验证方式的映射。 | 检查核心场景是否有能力承接，帮助模块并行开发。 | A3 能力拆解 | 能力不得伪装成模块；每个关键能力必须有 owner 或 Open Issue。 |

## 02 Modules

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [02-modules/module-responsibility-cards.md](../02-modules/module-responsibility-cards.md) | 核心模块职责、非职责、状态责任和协作边界。 | 作为模块边界评审和并行开发入口。 | A4 模块责任承接 | 主键必须是真实模块；支撑框架、依赖、starter 不得出现为 L0 模块。 |

## 02 Modules / agent-service

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [02-modules/agent-service/README.md](../02-modules/agent-service/README.md) | agent-service 模块设计包入口、文件地图和使用顺序。 | 帮助开发者定位该模块的设计、状态、流程、harness 和开放问题。 | A6 模块详细设计 | 必须指向当前有效文件；不得把旧 L1 文档直接当成当前基线。 |
| [02-modules/agent-service/accepted-design-map.md](../02-modules/agent-service/accepted-design-map.md) | 旧设计准入、保留、修正、下沉、废弃和开放问题映射。 | 说明从既有设计迁移到当前 A2D 设计包的依据。 | A6 模块详细设计 | 冲突项必须说明处理结果；不得为了兼容旧文档而保留错误边界。 |
| [02-modules/agent-service/logical-design.md](../02-modules/agent-service/logical-design.md) | agent-service 逻辑职责、内部组件和对外语义。 | 支撑开发者理解模块内部结构和外部协作关系。 | A6 模块详细设计 | 必须遵守 Task 是服务端状态、Run 仅为历史或 client 视角兼容的口径。 |
| [02-modules/agent-service/state-model.md](../02-modules/agent-service/state-model.md) | agent-service 管理的状态、状态流转和禁止路径。 | 支撑状态机实现、测试和评审。 | A5 状态与契约设计 / A6 模块详细设计 | 状态 owner、writer、reader 必须与 State Matrix 一致。 |
| [02-modules/agent-service/process-design.md](../02-modules/agent-service/process-design.md) | 请求进入、Task 执行、SSE 输出、暂停恢复等流程。 | 支撑流程实现、异常处理和场景验证。 | A6 模块详细设计 | 流程不得把 Bus 写成普通 payload 或 token stream 通道。 |
| [02-modules/agent-service/development-view.md](../02-modules/agent-service/development-view.md) | 代码组织、开发边界、依赖方向和实现落点。 | 帮助开发者把设计映射到代码结构。 | A6 模块详细设计 / A8 实现任务拆解 | 不得绕过模块边界直接创造跨模块语义。 |
| [02-modules/agent-service/development-slices.md](../02-modules/agent-service/development-slices.md) | 可并行开发的切片、输入、输出、验收标准。 | 支撑任务拆解、排期和 PR 审核。 | A8 实现任务拆解 | 每个切片必须能追溯到场景、能力、状态、契约或 harness。 |
| [02-modules/agent-service/harness-design.md](../02-modules/agent-service/harness-design.md) | agent-service 模块级测试、fixture、mock、golden trace 和失败注入建议。 | 支撑 harness 生成和模块质量保护。 | A7 Harness 设计与生成 | 必须覆盖核心状态流转、跨模块契约和关键失败路径。 |
| [02-modules/agent-service/4plus1-view.md](../02-modules/agent-service/4plus1-view.md) | agent-service 的 4+1 架构视图。 | 用多视角校验模块设计是否完整。 | A6 模块详细设计 | 视图必须与逻辑设计、状态模型、流程设计和开发视图互相一致。 |
| [02-modules/agent-service/open-issues.md](../02-modules/agent-service/open-issues.md) | agent-service 模块内开放问题、影响范围和后续动作。 | 防止未决问题散落在正文或聊天中。 | A6 模块详细设计 / A9 集成验证与架构评审 | 每个问题必须有影响、阻塞状态和后续位置。 |

## 03 State

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [03-state/state-ownership-matrix.md](../03-state/state-ownership-matrix.md) | 状态 owner、writer、reader、forbidden writer 和状态边界。 | 作为状态一致性和跨模块写入边界的主索引。 | A5 状态与契约设计 | 每个核心状态只能有一个 owner；禁止隐式多写。 |

## 04 ADR Drafts

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [04-adrs/ADR-001-run-lifecycle-ownership.md](../04-adrs/ADR-001-run-lifecycle-ownership.md) | Task / Run 生命周期归属和命名口径。 | 记录交付视角的状态归属决策草案。 | A5 状态与契约设计 | 必须说明 draft 身份；正式决策仍以 `docs/adr/` 为准。 |
| [04-adrs/ADR-002-contract-first-module-interaction.md](../04-adrs/ADR-002-contract-first-module-interaction.md) | 契约优先的模块交互方式。 | 约束跨模块交互先定义 ICD / contract / harness。 | A5 状态与契约设计 | 不得把草案写成 runtime enforced。 |
| [04-adrs/ADR-003-context-ownership.md](../04-adrs/ADR-003-context-ownership.md) | 上下文装配、上下文所有权和数据驻留边界。 | 支撑本地能力、平台托管和混合部署的上下文边界。 | A5 状态与契约设计 | 必须与强部门、弱部门和企业个人部署形态一致。 |
| [04-adrs/ADR-004-tool-skill-governance.md](../04-adrs/ADR-004-tool-skill-governance.md) | 工具、技能、审批和治理边界。 | 支撑工具调用、审批和本地 capability placement。 | A5 状态与契约设计 | 不得把工具治理误写成单一服务内部实现细节。 |
| [04-adrs/ADR-005-harness-first-core-modules.md](../04-adrs/ADR-005-harness-first-core-modules.md) | 核心模块 harness-first 设计原则。 | 约束设计必须能生成测试和验证证据。 | A7 Harness 设计与生成 | 每个关键设计项必须能追踪到 Verification Matrix。 |

## 05 Contracts / Human-readable

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [05-contracts/human-readable/ICD-gateway-workflow.md](../05-contracts/human-readable/ICD-gateway-workflow.md) | Gateway 到工作流入口的历史或草案交互语义。 | 保留需要迁移或校正的接口语义。 | A5 状态与契约设计 | 必须与当前 Gateway 只代理 agent-service 的口径对齐，必要时标记为待迁移。 |
| [05-contracts/human-readable/ICD-workflow-agent-service.md](../05-contracts/human-readable/ICD-workflow-agent-service.md) | 工作流与 agent-service 的历史或草案交互语义。 | 保留服务端 Task 语义迁移参考。 | A5 状态与契约设计 | 必须避免恢复独立 Run 服务端状态。 |
| [05-contracts/human-readable/ICD-agent-service-context-engine.md](../05-contracts/human-readable/ICD-agent-service-context-engine.md) | agent-service 与上下文能力的交互语义。 | 支撑上下文装配、本地能力和客户鉴权边界。 | A5 状态与契约设计 | 必须区分上下文数据路径和控制指令路径。 |
| [05-contracts/human-readable/ICD-agent-service-tool-gateway.md](../05-contracts/human-readable/ICD-agent-service-tool-gateway.md) | agent-service 与工具能力的交互语义。 | 支撑工具调用、审批、回调和本地执行。 | A5 状态与契约设计 | 必须说明工具执行位置和权限边界。 |
| [05-contracts/human-readable/ICD-workflow-observability.md](../05-contracts/human-readable/ICD-workflow-observability.md) | 执行链路与观测能力的交互语义。 | 支撑开发态 debug 和运行态 trace / metrics。 | A5 状态与契约设计 | 必须区分详细执行路径与宏观统计指标。 |
| [05-contracts/human-readable/ICD-cs-capability-placement.md](../05-contracts/human-readable/ICD-cs-capability-placement.md) | C-Side / S-Side 能力放置、数据驻留和本地执行边界。 | 支撑强部门、弱部门和企业个人部署形态。 | A5 状态与契约设计 | 必须与客户鉴权、平台成本统计和本地自由度口径一致。 |

## 05 Contracts / Machine-readable

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [05-contracts/machine-readable/gateway-workflow.yaml](../05-contracts/machine-readable/gateway-workflow.yaml) | Gateway / workflow 草案契约结构。 | 为 mock、stub、contract test 提供机器可读草案。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 必须保持 `status: draft`，并与对应 ICD 语义一致。 |
| [05-contracts/machine-readable/workflow-agent-service.yaml](../05-contracts/machine-readable/workflow-agent-service.yaml) | workflow / agent-service 草案契约结构。 | 支撑服务端 Task 交互的 harness 草案。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 不得声明 runtime enforced。 |
| [05-contracts/machine-readable/agent-service-context-engine.yaml](../05-contracts/machine-readable/agent-service-context-engine.yaml) | agent-service / context capability 草案契约结构。 | 支撑上下文装配相关 mock 和 contract test。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 字段语义必须能在对应 ICD 中找到解释。 |
| [05-contracts/machine-readable/agent-service-tool-gateway.yaml](../05-contracts/machine-readable/agent-service-tool-gateway.yaml) | agent-service / tool capability 草案契约结构。 | 支撑工具调用与审批路径的 contract test。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 必须体现本地工具和平台工具边界。 |
| [05-contracts/machine-readable/workflow-observability.yaml](../05-contracts/machine-readable/workflow-observability.yaml) | workflow / observability 草案契约结构。 | 支撑 trace、metrics、事件断言的 harness。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 必须区分开发态详细路径和运行态统计。 |
| [05-contracts/machine-readable/cs-capability-placement.yaml](../05-contracts/machine-readable/cs-capability-placement.yaml) | C-Side / S-Side 能力放置草案契约结构。 | 支撑部署形态和能力放置测试。 | A5 状态与契约设计 / A7 Harness 设计与生成 | 必须与 human-readable ICD 保持字段语义一致。 |

## 06 Scenarios

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [06-scenarios/README.md](../06-scenarios/README.md) | 场景目录入口、业务场景和技术子场景索引。 | 帮助读者按场景理解系统能力。 | A2 核心场景建模 | 必须区分 BA-* 业务活动和 technical sub-scenario。 |
| [06-scenarios/BA-001-agent-handles-business-request.md](../06-scenarios/BA-001-agent-handles-business-request.md) | Agent 处理业务请求的核心业务活动。 | 验证主路径能力、模块协作、开发态和运行态体验。 | A2 核心场景建模 | 必须覆盖直接用户、部署形态、状态变化、契约和 harness notes。 |
| [06-scenarios/BA-002-human-approval-tool-call.md](../06-scenarios/BA-002-human-approval-tool-call.md) | 需要人工审批的工具调用业务活动。 | 验证审批、暂停恢复、本地工具和权限边界。 | A2 核心场景建模 | 必须说明审批人、审批位置、恢复路径和失败路径。 |
| [06-scenarios/BA-003-multi-agent-delegation.md](../06-scenarios/BA-003-multi-agent-delegation.md) | 多 Agent 委托、跨部门或跨 service 协作场景。 | 验证 A2A、Bus 控制指令和数据引用边界。 | A2 核心场景建模 | 不得默认每 token stream 走 Bus；流式回传必须保持开放问题或单独方案。 |
| [06-scenarios/technical/S1-create-run.md](../06-scenarios/technical/S1-create-run.md) | 创建 Task / invocation 的技术机制场景。 | 验证入口、Task 创建和初始状态。 | A2 核心场景建模 | 文件名保留历史 Run 时，正文必须说明当前服务端以 Task 为准。 |
| [06-scenarios/technical/S2-execute-agent-step.md](../06-scenarios/technical/S2-execute-agent-step.md) | Agent step 执行机制。 | 验证 engine 同进程执行和 step 级状态/事件。 | A2 核心场景建模 | 必须与 service + engine 同进程决策一致。 |
| [06-scenarios/technical/S3-build-context-package.md](../06-scenarios/technical/S3-build-context-package.md) | 上下文包构建机制。 | 验证上下文装配、客户鉴权和数据驻留。 | A2 核心场景建模 | 不得把平台写成客户数据源细粒度权限定义方。 |
| [06-scenarios/technical/S4-tool-call-with-governance.md](../06-scenarios/technical/S4-tool-call-with-governance.md) | 带治理的工具调用机制。 | 验证审批、工具执行位置和回调控制。 | A2 核心场景建模 | 必须说明本地工具、平台公共服务和客户权限体系。 |
| [06-scenarios/technical/S5-suspend-resume.md](../06-scenarios/technical/S5-suspend-resume.md) | 暂停与恢复机制。 | 验证人工审批、外部回调和 Task 状态恢复。 | A2 核心场景建模 | 暂停原因、恢复条件和 forbidden writer 必须明确。 |
| [06-scenarios/technical/S6-child-run-federation.md](../06-scenarios/technical/S6-child-run-federation.md) | 子任务或跨 Agent federation 的历史机制场景。 | 验证 A2A 控制、跨 service 协作和引用传递。 | A2 核心场景建模 | 文件名如保留 Run，正文必须说明当前服务端状态以 Task 为准。 |

## 07 Invariants

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [07-invariants/architecture-invariants.md](../07-invariants/architecture-invariants.md) | 架构不变量、禁止路径和可检查约束。 | 支撑静态检查、评审和 harness 断言。 | A5 状态与契约设计 / A9 集成验证与架构评审 | 不变量必须可验证，并能追踪到场景、状态、契约或原则。 |

## 08 Harness

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [08-harness/workflow-harness-spec.md](../08-harness/workflow-harness-spec.md) | workflow 相关历史或机制 harness 规格。 | 支撑迁移期执行链路测试。 | A7 Harness 设计与生成 | 必须与当前 Task / service 口径保持一致。 |
| [08-harness/agent-service-harness-spec.md](../08-harness/agent-service-harness-spec.md) | agent-service harness 规格。 | 支撑 Task 生命周期、SSE、查询和回调测试。 | A7 Harness 设计与生成 | 必须覆盖 service 作为对外入口和状态 owner 的约束。 |
| [08-harness/context-engine-harness-spec.md](../08-harness/context-engine-harness-spec.md) | 上下文能力 harness 规格。 | 支撑上下文装配、客户鉴权和数据驻留测试。 | A7 Harness 设计与生成 | 必须区分本地上下文和平台侧上下文能力。 |
| [08-harness/tool-gateway-harness-spec.md](../08-harness/tool-gateway-harness-spec.md) | 工具能力 harness 规格。 | 支撑工具调用、审批、回调和本地执行测试。 | A7 Harness 设计与生成 | 必须覆盖本地工具与平台公共服务两类执行位置。 |
| [08-harness/observability-harness-spec.md](../08-harness/observability-harness-spec.md) | 观测能力 harness 规格。 | 支撑 trace、metrics、开发态 debug 和运行态统计测试。 | A7 Harness 设计与生成 | 必须分别覆盖详细执行路径和宏观统计指标。 |

## 09 Verification

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [09-verification/verification-matrix.md](../09-verification/verification-matrix.md) | 设计项、场景、契约、harness 和验证证据的追踪矩阵。 | 作为架构评审和实现验收的主索引。 | A9 集成验证与架构评审 | 每个关键设计项必须有验证方式或未覆盖说明。 |
| [09-verification/test-strategy.md](../09-verification/test-strategy.md) | 测试分层、测试类型和执行策略。 | 指导 harness 与实现测试如何组织。 | A7 Harness 设计与生成 / A9 集成验证与架构评审 | 必须与 Verification Matrix 和模块 harness spec 一致。 |

## 10 Governance

| 文件 | 主要内容 | 主要作用 | A2D 活动 | 质量检查点 |
|---|---|---|---|---|
| [10-governance/a2d-working-model.md](a2d-working-model.md) | A2D 活动流程、输入、产出物、责任人、归档位置和退出标准。 | 作为文档管理和 AI 协作的工作规程。 | A10 版本归档 | 每个活动的产出物与归档必须逐项对齐。 |
| [10-governance/document-artifact-catalog.md](document-artifact-catalog.md) | 每个文件的主要内容、主要作用、A2D 活动和质量检查点。 | 约束文档职责，防止文件漂移。 | A10 版本归档 | 新增、删除、改名或职责变化必须同步更新本文。 |
| [10-governance/architecture-documentation-constraints.md](architecture-documentation-constraints.md) | 文档结构、命名、分层、语言、准入和检查规则。 | 作为架构文档自身的质量约束。 | A10 版本归档 | 过程发现的新文档问题必须沉淀为约束。 |
| [10-governance/architecture-review-process.md](architecture-review-process.md) | 架构评审入口、检查清单、finding 格式和合并条件。 | 指导评审者如何审核 A2D 文档和变更。 | A9 集成验证与架构评审 | 评审发现不得只停留在评论中。 |
| [10-governance/change-governance.md](change-governance.md) | 变更分级、所需评审、文档更新和测试要求。 | 指导架构、契约、状态和 harness 变更如何升级。 | A9 集成验证与架构评审 / A10 版本归档 | Level 2 / Level 3 变更必须有对应文档、测试和 ADR / CR。 |

