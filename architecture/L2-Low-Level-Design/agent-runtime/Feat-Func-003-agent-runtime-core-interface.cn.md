---
level: L2-LLD
module: agent-runtime
feature_type: functional
feature_id: Feat-Func-003
status: active
dependency:
  - ../../L1-High-Level-Design/agent-runtime/README.md
  - ../../L1-High-Level-Design/agent-runtime/development.md
  - ../../L1-High-Level-Design/agent-runtime/process.md
  - ../../../version-scope/Feat-Func-003-agent-runtime-core-interface.cn.md
---

# agent-runtime 核心接口与状态边界 — 设计文档

## 1. 概述

### 1.1 特性定位

本文合并 `agent-runtime` 接口设计说明与 2026-06-09 Agent State 中间件提案，作为当前 L1 设计入口。生成事实仍以 `architecture/facts/generated/*.json` 为准；本文只解释当前代码边界、模块交互和后续扩展原则。

### 1.2 当前事实边界

本文只描述 Feat-Func-003 在当前 `agent-runtime` 模块中的核心接口承接、状态边界和 OpenJiuwen adapter 内部实现事实。模块级 API/SPI 的完整契约统一维护在 L1 API/SPI 附录中；本文不重复维护接口签名。面向使用方的接入说明迁移到 `version-scope/Feat-Func-003-agent-runtime-core-interface.cn.md`。

### 1.3 设计原则

`agent-runtime` 的职责是把一个业务 Agent 包装成单 Agent runtime，并通过 A2A 协议暴露执行能力。当前设计遵循四条边界：

1. A2A 层只做协议桥接、上下文构造、结果发射和任务状态映射。
2. `engine.spi` 只保留跨 Agent 框架稳定成立的窄接口。
3. 具体 Agent 框架的执行、装饰、状态恢复和结果转换留在对应 adapter 内部。
4. 框架已有原生 checkpoint / session / callback 机制时，runtime 优先桥接原生机制，不重复实现状态后端。

这意味着 runtime 不提供全局 provider chain，也不要求所有 Agent 框架继承同一个抽象基类。新能力优先在具体 adapter 内组合；只有跨框架语义稳定后，才提升为公共 SPI。

### 1.4 子特性全景

| Feature | Status | Notes |
|---|---|---|
| `AgentRuntimeHandler` 执行 SPI | Implemented | 单 Agent runtime 执行入口 |
| `StreamAdapter` 结果转换 SPI | Implemented | 框架原生结果转 `AgentExecutionResult` |
| `AgentCardProvider` 可选元数据 provider | Implemented | 执行职责和 Agent Card 声明分离 |
| 业务自定义 state key | Implemented | `agentStateKey` / `stateKey`，fallback `taskId` |
| `MemoryProvider` 预留 SPI | Implemented | 定义 `init` / `search` / `save` 基础语义 |
| OpenJiuwen native checkpointer 桥接 | Implemented | 使用稳定 `conversation_id` |
| OpenJiuwen Rail 扩展点 | Implemented | 默认不安装 Rail；可选 `MemoryRuntimeRail` 支持 memory search 注入与执行后写回 |
| Snapshot / revision / fencing | Deferred | durable backend 时补齐 |
| Mem 正式集成 | Deferred | 后续单独设计和实现 |

## 2. 特性规格

### 2.1 能力清单

| Feature | Status | Notes |
|---|---|---|
| `AgentRuntimeHandler` 执行 SPI | Implemented | 单 Agent runtime 执行入口 |
| `StreamAdapter` 结果转换 SPI | Implemented | 框架原生结果转 `AgentExecutionResult` |
| `AgentCardProvider` 可选元数据 provider | Implemented | 执行职责和 Agent Card 声明分离 |
| 业务自定义 state key | Implemented | `agentStateKey` / `stateKey`，fallback `taskId` |
| `MemoryProvider` 预留 SPI | Implemented | 定义 `init` / `search` / `save` 基础语义 |
| OpenJiuwen native checkpointer 桥接 | Implemented | 使用稳定 `conversation_id` |
| OpenJiuwen Rail 扩展点 | Implemented | 默认不安装 Rail；可选 `MemoryRuntimeRail` 支持 memory search 注入与执行后写回 |
| Snapshot / revision / fencing | Deferred | durable backend 时补齐 |
| Mem 正式集成 | Deferred | 后续单独设计和实现 |

### 2.2 显式排除

- 不在 L2 中维护模块级 API/SPI 签名；完整契约以 L1 API/SPI 附录为准。
- 不提供全局 provider chain，也不要求所有 Agent 框架继承同一个抽象基类。
- 不重复实现具体 Agent 框架已有的 checkpoint、session 或 callback 状态后端。

### 2.3 行为承诺

- A2A 层只做协议桥接、上下文构造、结果发射和任务状态映射。
- `engine.spi` 只保留跨 Agent 框架稳定成立的窄接口。
- 具体 Agent 框架的执行、装饰、状态恢复和结果转换留在对应 adapter 内部。
- 框架已有原生状态机制时，runtime 优先桥接原生机制。

## 3. 核心实现

### 3.1 A2A 执行链路

`A2aAgentExecutor` 是 A2A SDK 的 `AgentExecutor` 实现，负责把 A2A 请求桥接到 runtime handler。

```text
A2A RequestContext
  -> A2aAgentExecutor.execute(...)
  -> AgentExecutionContext
  -> AgentRuntimeHandler.execute(context)
  -> StreamAdapter.adapt(rawResults)
  -> AgentEmitter task state / message output
```

`A2aAgentExecutor` 做：

- 从 A2A `RequestContext` 提取 `taskId`、`contextId`、消息文本与 metadata。
- 构造 `AgentExecutionContext`。
- 调用 `handler.execute(context)`。
- 使用 `handler.resultAdapter()` 转换结果。
- 将 `OUTPUT`、`COMPLETED`、`FAILED`、`INTERRUPTED` 映射为 A2A emitter 行为。

`A2aAgentExecutor` 不做：

- 不创建具体 Agent。
- 不安装 OpenJiuwen Rail。
- 不理解 OpenJiuwen checkpointer。
- 不持有状态存储。
- 不承载通用 provider chain。

这样可以避免 A2A 协议桥变成所有框架能力的集中点。

### 3.2 OpenJiuwen adapter

OpenJiuwen 的框架适配收敛在 `runtime.engine.openjiuwen` 包内。

### 3.2.1 `OpenJiuwenAgentRuntimeHandler`

`OpenJiuwenAgentRuntimeHandler` 实现 `AgentRuntimeHandler`，固定 OpenJiuwen 执行主流程：

```text
AgentExecutionContext
  -> createOpenJiuwenAgent(context)
  -> openJiuwenRails(context)
  -> BaseAgent.registerRail(...)
  -> toOpenJiuwenInput(context)
  -> Runner.runAgent(agent, input, conversationId, null)
  -> OpenJiuwenStreamAdapter
```

子类只需要实现具体 Agent 创建：

```java
protected abstract BaseAgent createOpenJiuwenAgent(AgentExecutionContext context);
```

业务侧负责“如何创建和配置 OpenJiuwen 的 `BaseAgent`”；runtime adapter 负责执行协议、Rail 安装、输入转换、结果转换和错误映射。

### 3.2.2 OpenJiuwen Rail 扩展点

OpenJiuwen adapter 使用 OpenJiuwen 0.1.12 的 `BaseAgent.registerRail(...)` 与 `AgentRail` 作为框架本地扩展点。默认不安装 Rail；需要接入 Mem、工具治理或沙箱时，由子类覆盖 `openJiuwenRails(context)`，返回需要注册到 OpenJiuwen Agent 的 Rail。

OpenJiuwen 原生 memory 接入优先使用 OpenJiuwen 0.1.12 的 external memory rail。`OpenJiuwenAgentRuntimeHandler.openJiuwenExternalMemoryRail(...)` 会把 runtime 中立 `MemoryProvider` 适配成 OpenJiuwen 的 external memory provider，再交给 `ExternalMemoryRail` 处理 prefetch、工具声明和执行后同步。这个适配器只放在 `runtime.engine.openjiuwen` 包内；如果 OpenJiuwen 后续把 memory 模块拆成独立仓，只需要替换该包内适配代码，公共 `engine.spi.MemoryProvider` 不绑定 OpenJiuwen memory 包名。

同时，当前还保留 `OpenJiuwenAgentRuntimeHandler.MemoryRuntimeRail` 作为 OpenJiuwen 本地兼容桥。它不是公共 runtime SPI，主要用于普通 ReActAgent 或不完整支持 harness external-memory rail 的路径：

- `beforeInvoke(...)` 调用 runtime 中立 `MemoryProvider.init(context)`。
- `beforeInvoke(...)` 使用最新用户输入调用 `MemoryProvider.search(context, query, limit)`，并把检索结果作为自然语言 system note 合并进 OpenJiuwen `ModelContext` 的 system prompt；如果当前上下文已经有 `SystemMessage`，只合并内容，不额外新增第二个 system prompt。
- `afterInvoke(...)` 从 OpenJiuwen callback context 中取出 `BaseMessage` 列表，但只把干净的 `user` / `assistant` turn 转换成 `MemoryProvider.MemoryRecord` 并调用 `MemoryProvider.save(context, records)`。业务已有的 system prompt、runtime 注入的 memory note、tool 消息都不进入长期记忆写回；长期记忆的抽取、压缩、去重仍由具体 `MemoryProvider` 负责。
- OpenJiuwen `BaseMessage` 与 `MemoryRecord` 的转换由 `OpenJiuwenMemoryMessageAdapter` 负责，留在 `runtime.engine.openjiuwen` 包内。
- `InMemoryMemoryProvider` 是 examples 内的轻量实现，按 `agentStateKey` 做最小隔离；生产 memory 后端、向量召回、压缩和跨租户治理仍由具体 `MemoryProvider` 实现负责。

兼容桥的 system prompt 处理只是兜底路径。优先路径仍是 OpenJiuwen 0.1.12 原生 external memory rail：OpenJiuwen 自己用 provider 的 `systemPromptBlock()`、`prefetch(...)` 与 `syncTurn(...)` 管理 memory prompt、检索片段和执行后同步，runtime 只提供窄 `MemoryProvider` 适配，不把 OpenJiuwen 的 prompt template / memory 包名提升到公共 SPI。

适合放入 Rail 的能力：

- 模型调用前后的 trace、耗时采样和异常观测。
- 工具调用前后的管控、沙箱校验和审计。
- Mem 检索增强与执行后写回。
- OpenJiuwen 原生 callback context 的轻量增强。

不建议放入 Rail 的能力：

- Agent 构造：仍由 `createOpenJiuwenAgent(context)` 负责。
- `Runner.runAgent(...)` 调用：仍由 handler 负责。
- `conversation_id / agentStateKey` 决策：仍由 handler / message adapter 负责。
- Agent Card：属于启动期元数据，不是执行期生命周期 hook。
- Checkpointer 配置：属于 OpenJiuwen runtime / sample wiring，不应在执行期 Rail 中切换全局状态后端。

> 版本约束：本文按 OpenJiuwen `agent-core-java:0.1.12` 的 API 设计。0.1.12 仍提供 `BaseAgent.registerRail(...)`、`AgentRail`、`Runner.runAgent(...)` 与 `CheckpointerFactory`，因此当前 adapter 以这些 API 为边界；不要把其他分支上的新运行时模型当成本文依据。
>
> Sample 约束：如果某个 examples 模块仍显式依赖 OpenJiuwen `agent-core-java:0.1.7`，则它与本文定义的 OpenJiuwen 0.1.12 native memory rail 不是同一条运行路径。该 sample 可以继续覆盖基础 A2A / AgentScope / OpenJiuwen ReAct 兼容路径，但不能用来说明 `openJiuwenExternalMemoryRail(...)` 可运行；启用该 rail 前应先把 sample 依赖升级到 0.1.12 或提供等价的 OpenJiuwen memory 包。

### 3.2.3 `OpenJiuwenMessageAdapter`

`OpenJiuwenMessageAdapter` 把 `AgentExecutionContext` 转成 OpenJiuwen input。关键点是：

```text
query = 最新用户输入
conversation_id = context.getAgentStateKey()
```

OpenJiuwen 自身通过 `conversation_id` 与原生 checkpointer 完成 session 保存和恢复。runtime 不在每次调用后手工 `dumpState()` / `updateState(...)` 搬运 OpenJiuwen 内部状态。

### 3.2.4 OpenJiuwen native checkpointer

OpenJiuwen 的状态主路径是原生 Runner / Checkpointer：

```text
AgentExecutionContext.getAgentStateKey()
  -> OpenJiuwen input["conversation_id"]
  -> Runner.runAgent(..., conversationId, ...)
  -> OpenJiuwen Checkpointer restore/save
```

业务方只需要保证 `agentStateKey` 稳定。OpenJiuwen 内部保存哪些字段、如何序列化、何时恢复，交给 OpenJiuwen `Runner` / `Checkpointer` 处理。

当前 OpenJiuwen adapter 提供 `OpenJiuwenCheckpointerConfigurer` 作为启动期配置入口：

- `setDefault(Checkpointer)`：业务 wiring 自行创建 OpenJiuwen 原生 checkpointer，并交给 runtime.engine.openjiuwen 包统一设置到 `CheckpointerFactory`。
- `setInMemoryDefault()`：本地开发和轻量端到端运行使用的默认路径。

该入口只属于 OpenJiuwen adapter，不进入公共 `engine.spi`。Redis、持久化或业务自定义 checkpointer 都应在业务 / examples wiring 中实例化，然后通过 `setDefault(...)` 注册；不要在每次 `execute(...)` 或每个请求里切换全局 checkpointer。

### 3.2.5 `OpenJiuwenStreamAdapter`

`OpenJiuwenStreamAdapter` 把 OpenJiuwen 返回的 map 结构转换为 `AgentExecutionResult`：

- answer / output -> `OUTPUT` 或 `COMPLETED`
- error -> `FAILED`
- interrupt / input required -> `INTERRUPTED`

A2A 层只处理转换后的 `AgentExecutionResult`，不直接理解 OpenJiuwen 原始结果。

### 3.3 状态与记忆原则

当前状态设计分为两类：

1. 框架原生 checkpoint：优先使用，例如 OpenJiuwen。
2. runtime 预留窄 SPI：给没有原生 checkpoint 或需要 runtime 辅助的框架使用。

不要把正文记忆、大 payload 或完整业务状态都塞进 `AgentExecutionContext`。`AgentExecutionContext` 更适合承载执行身份、输入消息、metadata、状态 key 或小型状态引用。完整状态后端、记忆压缩和检索策略应由对应中间件或框架后端负责。

Mem 后续接入建议：

- Mem 不复用 Agent State 后端存正文记忆。
- Agent State 后端只保存 `memoryRef`、`checkpointRef`、`cursor` 等小对象；当前代码不再发布单独的 Agent 状态存储公共接口。
- Mem 的 compact、budget、vector retrieval、长期检索由 Mem backend 负责。
- OpenJiuwen 可优先通过 Rail 或具体 `createOpenJiuwenAgent(context)` 的 agent 配置接入 Mem。

## 4. 代码结构

### 4.1 模块职责

| 模块 / 包 | 当前职责 | 不承担的职责 |
|---|---|---|
| `runtime.engine.a2a` | A2A 请求接入、上下文构造、结果映射到 emitter | 不创建具体 Agent，不安装框架装饰，不管理状态存储 |
| `runtime.engine.spi` | 定义跨 Agent 框架稳定成立的窄 SPI | 不放具体框架实现，不承载 provider chain |
| `runtime.engine.openjiuwen` | OpenJiuwen adapter、Agent 创建入口、Rail 安装、Runner 调用、输入/输出转换 | 不要求其他框架复用 OpenJiuwen 机制 |
| `examples/*` | 提供具体业务 Agent 示例、配置和样例 provider 实现 | 不定义 runtime 核心执行边界 |

### 4.2 核心类职责

核心类职责由 L1 SPI 附录定义公共契约，由本特性的 adapter 实现承接到具体框架。本文保留实现归属和协作事实，不重复接口签名。

### 4.3 类协作关系

A2A bridge 构造 `AgentExecutionContext` 后调用当前 `AgentRuntimeHandler`；handler 返回框架原生流；`StreamAdapter` 将原生流转换为 runtime 中立结果；A2A 层再映射为 Task、Artifact 或 SSE 事件。

## 5. 运行流程

### 5.1 主流程

主流程见第 3.1 节 A2A 执行链路。

### 5.2 分支流程

OpenJiuwen、Memory 和 State 分支流程见第 3.2 与第 3.3 节。接入新 Agent 框架的外部说明已迁移到 `version-scope/Feat-Func-003-agent-runtime-core-interface.cn.md`。

### 5.3 错误、取消、降级处理

- state load 失败：由具体 Provider / checkpointer fail closed，不调用 handler 或让框架返回明确失败。
- handler 执行失败：转换成 `FAILED`，保持 A2A 单出口语义。
- 执行后辅助写入失败：记录 warn，不把已经完成的任务反转成失败，避免双终态。
- state save 失败：不覆盖业务执行结果；生产态后续需要告警、重试或补偿队列。

## 6. 配置使用

### 6.1 配置示例

本特性不新增独立配置示例。具体 adapter、memory、state 或 A2A 配置由对应特性文档维护。

### 6.2 配置属性

本特性不维护独立配置属性表。

### 6.3 默认值与开关语义

默认值与开关语义由具体 adapter 或中间件特性文档维护。

## 7. 当前限制

- 公共 SPI 只保留跨框架稳定语义，不承载具体框架的高级扩展点。
- 状态和记忆能力优先桥接框架原生机制，不在 runtime 公共层内建统一后端。
- 本文不保留测试或验证类内容；相关材料按项目治理要求不进入 `architecture/` 架构事实树。
