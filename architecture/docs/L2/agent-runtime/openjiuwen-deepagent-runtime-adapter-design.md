---
level: L2
module: agent-runtime
feature: openjiuwen-deepagent-runtime-adapter
status: shipped
---

# OpenJiuwen DeepAgent Runtime 适配器设计

## 1. 范围

`agent-runtime` 通过独立的 runtime handler 托管 OpenJiuwen `DeepAgent`。该适配器不是现有 ReAct handler 的泛化版本，而是在保持 `OpenJiuwenAgentRuntimeHandler` 的 `BaseAgent` 路径不变的前提下，新增面向 OpenJiuwen harness 对象的 `OpenJiuwenDeepAgentRuntimeHandler`。

已落地执行链路如下：

```text
A2A request
  -> A2aAgentExecutor
  -> OpenJiuwenDeepAgentRuntimeHandler.execute(context)
  -> createOpenJiuwenDeepAgent(context): DeepAgent
  -> 安装运行时 A2A 远端工具
  -> 在 deepAgent.getAgent() 上安装 trajectory rail
  -> deepAgent.stream(input, null, streamModes)
  -> 复用 OpenJiuwenStreamAdapter 兼容结果映射
  -> AgentExecutionResult stream
```

原始实现缺口的根因是：`OpenJiuwenAgentRuntimeHandler` 的扩展点绑定在 `BaseAgent`，而 OpenJiuwen `DeepAgent` 是 harness 包装对象，内部持有 `ReActAgent`、任务循环、工作区、工具注册路径和协作中止能力。只运行 `deepAgent.getAgent()` 会绕过 DeepAgent harness 行为，因此 runtime 必须直接执行 `DeepAgent.stream(...)`。

## 2. 对外扩展点

应用侧继承 `OpenJiuwenDeepAgentRuntimeHandler`，实现一个方法：

```java
public abstract class OpenJiuwenDeepAgentRuntimeHandler
        extends AbstractOpenJiuwenRuntimeSupport {

    protected abstract DeepAgent createOpenJiuwenDeepAgent(AgentExecutionContext context);
}
```

handler 构造函数接收 runtime `agentId`。子类在每次执行时创建一个完整配置的 OpenJiuwen `DeepAgent`。已落地示例使用 `HarnessFactory.createDeepAgent(...)`、`DeepAgentConfig` 和 `Workspace` 创建 agent。

handler 只接受 map 形态的 OpenJiuwen 输入。`OpenJiuwenMessageAdapter` 将 `AgentExecutionContext` 转为 OpenJiuwen 输入对象，`OpenJiuwenDeepAgentRuntimeHandler` 将其归一为 `Map<String,Object>`；如果输入不是 map，则快速失败并返回 `OPENJIUWEN_RUN_ERROR`。

## 3. 执行生命周期

`OpenJiuwenDeepAgentRuntimeHandler#doExecute` 的固定顺序如下：

1. 调用 `createOpenJiuwenDeepAgent(context)` 创建新的 `DeepAgent`。
2. 按 `taskId` 将实例放入 `runningAgents`。
3. 如果存在 `OpenJiuwenRemoteToolInstaller`，安装运行时远端 A2A 工具。
4. 如果开启 trajectory，在 `deepAgent.getAgent()` 上注册 `OpenJiuwenTrajectoryRail`，因为 OpenJiuwen 的模型和工具回调发生在内部 ReAct agent。
5. 将 `AgentExecutionContext` 转为 OpenJiuwen 输入，并要求输入为 map。
6. 当输入缺少 `conversation_id` 时，使用 `context.getAgentStateKey()` 写入。
7. 调用 `deepAgent.stream(input, null, openJiuwenStreamModes(context))`。
8. 将 OpenJiuwen iterator 展平成 Java stream。
9. iterator 正常耗尽、抛错或 stream 关闭时，移除 `runningAgents` 中的实例。

默认 stream mode 是 `List.of(StreamMode.OUTPUT)`。handler 不调用 `Runner.runAgentStreaming(...)`，因为 DeepAgent 自己拥有 stream 和 session 生命周期。

## 4. 共享 runtime 支撑

`AbstractOpenJiuwenRuntimeSupport` 是 ReAct 与 DeepAgent 路径唯一共享的父类。它不承载 `BaseAgent`、`DeepAgent`、`AgentRail` 或工具注册语义，只负责 runtime 级通用行为：

| 方法 | 行为 |
|---|---|
| `toOpenJiuwenInput(context)` | 委托 `OpenJiuwenMessageAdapter` 做输入转换，并记录 tenant/session/task 身份 |
| `openJiuwenConversationId(context)` | 使用 `context.getAgentStateKey()` 作为稳定 OpenJiuwen conversation id |
| `flattenIterator(iterator)` | 将 OpenJiuwen iterator 转成有序 Java stream |
| `failedResult(context, trajectory, error)` | 发出 trajectory `ERROR` 并返回 `AgentExecutionResult.failed("OPENJIUWEN_RUN_ERROR", message)` |
| `resultAdapter()` | 通过 `mapRawResult` 映射 raw item，并丢弃 null 映射结果 |
| `mapRawResult(rawResult)` | 透传 `AgentExecutionResult`，用 `OpenJiuwenStreamAdapter` 映射 `OutputSchema`，将 null 映射为失败结果，将未知对象映射为文本输出 |

该拆分保留 `OpenJiuwenAgentRuntimeHandler#createOpenJiuwenAgent(AgentExecutionContext): BaseAgent` API，不把 DeepAgent 专有行为下沉到 ReAct 路径。

## 5. 远端 A2A 工具集成

远端 agent 工具由 `RemoteAgentCardCache` 发现，并表示为 `RemoteAgentToolSpec`。`A2aClientAutoConfiguration.OpenJiuwenRemoteToolConfiguration` 创建一个 `OpenJiuwenRemoteToolInstaller`，并注入所有可用的：

- `OpenJiuwenAgentRuntimeHandler`
- `OpenJiuwenDeepAgentRuntimeHandler`

对 `BaseAgent`，installer 会：

1. 将每个 `RemoteAgentToolSpec` 包装为占位 OpenJiuwen `Tool`。
2. 将 tool 加入 `Runner.resourceMgr()`。
3. 当 agent ability manager 中缺少该 tool card 时补充注册。
4. 在 `BaseAgent` 上注册 `OpenJiuwenRemoteAgentInterruptRail`。

对 `DeepAgent`，installer 会：

1. 将每个 `RemoteAgentToolSpec` 包装为同一个占位 `Tool`。
2. 通过 `deepAgent.registerHarnessTool(...)` 注册为 harness tool。
3. 在 `deepAgent.getAgent()` 上注册 `OpenJiuwenRemoteAgentInterruptRail`。

占位工具如果被直接执行，会返回 `REMOTE_AGENT_TOOL_NOT_INTERRUPTED`。正确路径是在占位工具执行前被 interrupt rail 拦截，然后通过 runtime A2A 远端调用链路转发。

## 6. 结果映射

DeepAgent 任务循环当前会转发内部 ReAct 路径产生的 OpenJiuwen `OutputSchema` chunk。因此已落地适配器复用 ReAct handler 的 `OpenJiuwenStreamAdapter` 映射：

| Raw item | Runtime result |
|---|---|
| `AgentExecutionResult` | 原样透传 |
| `OutputSchema` | 由 `OpenJiuwenStreamAdapter` 映射 |
| `null` | `failed(OPENJIUWEN_ERROR, "openjiuwen runner returned no result")` |
| 其他对象 | `output(String.valueOf(rawResult))` |

当前代码没有单独的 `OpenJiuwenDeepAgentStreamAdapter`。结果适配集中在 `AbstractOpenJiuwenRuntimeSupport`。

## 7. Trajectory 与取消

DeepAgent 支持与 ReAct OpenJiuwen handler 相同的 trajectory 事件类型：

- `RUN_START`
- `RUN_END`
- `MODEL_CALL_START`
- `MODEL_CALL_END`
- `TOOL_CALL_START`
- `TOOL_CALL_END`
- `ERROR`

trajectory rail 注册在内部 `ReActAgent` 上，因为 OpenJiuwen 模型和工具回调由内部 agent 发出。

取消是协作式的。`cancel(taskId)` 查找当前运行的 `DeepAgent` 并调用 `requestAbort()`。runtime 同时按 `AgentRuntimeHandler` 生命周期关闭 raw stream。如果 OpenJiuwen 正处于阻塞模型调用，中止会在下一次 OpenJiuwen 协作检查点生效。

## 8. 配置形态

runtime 适配器本身由 Spring bean 子类配置。示例模块暴露的样例属性如下：

| 属性 | 默认值 | 用途 |
|---|---|---|
| `sample.openjiuwen.model-provider` | `openai` | DeepAgent backend provider |
| `sample.openjiuwen.api-key` | `sk-local-placeholder` | DeepAgent backend API key |
| `sample.openjiuwen.api-base` | `http://localhost:4000/v1` | DeepAgent backend base URL |
| `sample.openjiuwen.model-name` | `gpt-5.4-mini` | DeepAgent model name |
| `sample.openjiuwen.ssl-verify` | `false` | Backend SSL verification |
| `sample.openjiuwen.workspace-path` | `./target/deepagent-workspace` | DeepAgent workspace root |
| `sample.openjiuwen.checkpointer` | `in-memory` | OpenJiuwen global checkpointer |
| `sample.openjiuwen.redis-url` | `redis://localhost:6379` | Redis checkpointer URL |

示例通过 `AgentCards.create(AGENT_ID, "Sample openJiuwen DeepAgent hosted by agent-runtime.")` 创建 A2A Agent Card。

## 9. 验证面

当前仓库通过共享 OpenJiuwen runtime 单测和 E2E 模块验证 DeepAgent 路径：

| 验证 | 路径 |
|---|---|
| 远端工具 installer 对 BaseAgent/DeepAgent 的行为 | `agent-runtime/src/test/java/com/huawei/ascend/runtime/engine/openjiuwen/OpenJiuwenRemoteToolInstallerTest.java` |
| 远端 interrupt/resume 映射 | `agent-runtime/src/test/java/com/huawei/ascend/runtime/engine/openjiuwen/OpenJiuwenRemoteAgentAdapterTest.java` |
| OpenJiuwen 基础 handler 回归覆盖 | `agent-runtime/src/test/java/com/huawei/ascend/runtime/engine/openjiuwen/OpenJiuwenAgentRuntimeHandlerTest.java` |
| DeepAgent A2A E2E | `examples/agent-runtime-a2a-openjiuwen-deepagent-e2e/src/test/java/com/huawei/ascend/examples/a2a/OpenJiuwenDeepAgentA2aE2eTest.java` |

该特性的最小本地验证命令：

```bash
mvn -pl agent-runtime -Dtest=OpenJiuwenRemoteToolInstallerTest,OpenJiuwenRemoteAgentAdapterTest,OpenJiuwenAgentRuntimeHandlerTest test
mvn -f examples/agent-runtime-a2a-openjiuwen-deepagent-e2e/pom.xml test
```

合并质量级 Java 验证仍然使用相关 reactor 范围的 `mvn clean verify`，不能只用 `mvn test` 代替。

## 10. 当前边界

已落地适配器的边界如下：

| 边界 | 当前行为 |
|---|---|
| ReAct 兼容性 | 既有 `OpenJiuwenAgentRuntimeHandler` 扩展点仍是 `BaseAgent createOpenJiuwenAgent(...)` |
| DeepAgent 生命周期 | 每次 runtime 执行创建一个 `DeepAgent`，stream 完成、关闭或异常后从 `runningAgents` 移除 |
| 远端工具 | 作为 DeepAgent harness tool 安装，并通过内部 ReAct rail 拦截 |
| Memory rail | ReAct 兼容 memory helper 仍在 `OpenJiuwenAgentRuntimeHandler`；DeepAgent 不自动安装 `MemoryRuntimeRail` |
| MCP / SkillHub installer | ReAct handler 有 runtime MCP/SkillHub installer hook；DeepAgent runtime adapter 当前只安装远端 A2A runtime tool |

---

## 11. DeepAgent 相比 ReAct Agent 缺失的适配能力

这一节只比较 `agent-runtime` 适配层已经接入的能力，不评价 OpenJiuwen DeepAgent 原生能力本身。代码对比对象是：

- `OpenJiuwenAgentRuntimeHandler`
- `OpenJiuwenDeepAgentRuntimeHandler`
- `OpenJiuwenRemoteToolInstaller`
- `A2aClientAutoConfiguration`
- `McpAutoConfiguration`
- `OpenJiuwenSkillHubAutoConfiguration`

### 11.1 能力差异总表

| 能力 | ReAct handler 当前状态 | DeepAgent handler 当前状态 | 影响 |
|---|---|---|---|
| runtime 自定义 rail 扩展点 | 有 `openJiuwenRails(context)`，执行前逐个 `agent.registerRail(...)` | 没有对应 `openJiuwenRails` 扩展点 | 应用不能通过继承 DeepAgent handler 注入 runtime 侧通用 `AgentRail` |
| runtime MemoryProvider 桥接 | 有 `memoryRuntimeRail(...)` 和 `openJiuwenExternalMemoryRail(...)` helper | 没有 helper，也没有默认安装路径 | DeepAgent handler 不能复用 runtime neutral `MemoryProvider` 做长期记忆注入/保存 |
| runtime MCP tool installer | 有 `setMcpToolInstaller(...)`，`installRuntimeTools(...)` 会调用 `mcpToolInstaller.install(agent, context)` | 没有 `setMcpToolInstaller(...)` 字段和安装调用 | 配置在 runtime MCP provider 中的 MCP tools 不会自动安装到 DeepAgent |
| SkillHub installer | 有 `setSkillHubInstaller(...)`，`installRuntimeTools(...)` 会调用 `skillHubInstaller.install(agent, context)` | 没有 `setSkillHubInstaller(...)` 字段和安装调用 | runtime SkillHub 中的 skills 不会自动注册到 DeepAgent |
| BaseAgent ability manager 同步 | 远端 tool 会进入 `Runner.resourceMgr()`，并补充到 `agent.getAbilityManager()` | 远端 tool 只通过 `deepAgent.registerHarnessTool(...)` 注册 | DeepAgent 不走 ReAct `abilityManager` 补卡逻辑，能力可见性依赖 harness tool 注册 |
| Runner 级执行入口 | 通过 `Runner.runAgentStreaming(agent, input, conversationId, null, streamModes)` 执行 | 直接 `deepAgent.stream(input, null, streamModes)` | 丢失 Runner 包装路径上的扩展点；换来 DeepAgent harness task loop |
| 输入形态宽容度 | `Runner.runAgentStreaming(...)` 接收 `Object input` | handler 强制 `Map<String,Object>` | DeepAgent 路径不接受非 map 的 OpenJiuwen 输入 |
| 取消语义 | 未覆盖 `cancel`，主要依赖 runtime 关闭 stream | 覆盖 `cancel` 并调用 `DeepAgent.requestAbort()` | 这里 DeepAgent 不是缺失，而是补齐了 harness abort；但仍是协作式取消 |

### 11.2 Rail 扩展点缺失

ReAct handler 暴露：

```java
protected List<AgentRail> openJiuwenRails(AgentExecutionContext context) {
    return List.of();
}
```

并在执行前调用：

```java
for (AgentRail rail : openJiuwenRails(context)) {
    if (rail != null) {
        agent.registerRail(rail);
    }
}
```

DeepAgent handler 当前只在两个固定位置注册 rail：

1. 远端 A2A tool installer 在 `deepAgent.getAgent()` 上注册 `OpenJiuwenRemoteAgentInterruptRail`。
2. trajectory 开启时在 `deepAgent.getAgent()` 上注册 `OpenJiuwenTrajectoryRail`。

它没有对应用开放 `openJiuwenRails(context)` 等价扩展点。因此，ReAct 子类可以通过覆写方法注入任意 OpenJiuwen `AgentRail`；DeepAgent 子类如果要注入 rail，只能在 `createOpenJiuwenDeepAgent(context)` 内部自行配置 DeepAgent 或访问内部 agent。

### 11.3 runtime MemoryProvider 桥接缺失

ReAct handler 提供两种 memory helper：

| Helper | 行为 |
|---|---|
| `memoryRuntimeRail(context, memoryProvider)` | 创建 `MemoryRuntimeRail`，在 `beforeInvoke` 搜索 runtime `MemoryProvider` 并注入 prompt，在 `afterInvoke` 保存用户/助手消息 |
| `openJiuwenExternalMemoryRail(context, memoryProvider)` | 创建 OpenJiuwen native `ExternalMemoryRail`，背后仍由 runtime neutral `MemoryProvider` 适配 |

DeepAgent handler 没有这些 helper，也没有 `openJiuwenRails(context)` 用来安装它们。结果是：

- runtime 的 `MemoryProvider` SPI 不能直接接入 DeepAgent handler。
- ReAct 路径里“搜索长期记忆 -> 注入 system/prompt builder -> 保存新轮次”的链路不会自动发生。
- DeepAgent 如果需要记忆，需要在应用构建 DeepAgent 时使用 OpenJiuwen 原生配置，或后续新增 DeepAgent 专用 memory 接入点。

### 11.4 runtime MCP tool installer 缺失

ReAct handler 的 `installRuntimeTools(...)` 顺序包含三类 installer：

```java
if (runtimeToolInstaller != null) {
    runtimeToolInstaller.install(agent, context);
}
if (mcpToolInstaller != null) {
    mcpToolInstaller.install(agent, context);
}
if (skillHubInstaller != null) {
    skillHubInstaller.install(agent, context);
}
```

DeepAgent handler 当前只有：

```java
if (runtimeToolInstaller != null) {
    runtimeToolInstaller.install(agent, context);
}
```

因此，`McpAutoConfiguration` 只会把 `OpenJiuwenMcpToolInstaller` 注入 `OpenJiuwenAgentRuntimeHandler`。DeepAgent handler 没有 `setMcpToolInstaller(...)`，也没有 installer 字段。runtime MCP provider 中发现的 tools 不会自动进入 DeepAgent。

这不等于 DeepAgent 原生不支持 MCP。`agent-sdk` 的 DeepAgent YAML 装配已经把 `mcps[]` 映射到 `DeepAgentConfig.mcps`。差异点是：runtime 层的 MCP 自动安装能力没有接入 DeepAgent adapter。

### 11.5 SkillHub installer 缺失

ReAct handler 有 `setSkillHubInstaller(...)`，`OpenJiuwenSkillHubAutoConfiguration` 会遍历 `OpenJiuwenAgentRuntimeHandler` 并注入 installer。DeepAgent handler 没有对应 setter，自动配置也没有遍历 DeepAgent handler。

结果是：

- runtime SkillHub provider 返回的 skills 不会通过 adapter 自动安装到 DeepAgent。
- DeepAgent 的 skill 来源需要在 `DeepAgentConfig.skillDirectories`、OpenJiuwen 原生构建逻辑或 `agent-sdk` YAML 中配置。
- 如果应用只把 SkillHub 配在 runtime 层，ReAct handler 能看到，DeepAgent handler 看不到。

### 11.6 远端 A2A 工具能力保留但路径不同

远端 A2A tool 是 DeepAgent 当前保留的 runtime 能力。`A2aClientAutoConfiguration` 会把同一个 `OpenJiuwenRemoteToolInstaller` 注入 ReAct handler 和 DeepAgent handler。

差异在安装路径：

| handler | 安装路径 |
|---|---|
| ReAct | `Runner.resourceMgr().addTool(...)`，必要时 `agent.getAbilityManager().add(...)`，再注册 interrupt rail |
| DeepAgent | `deepAgent.registerHarnessTool(...)`，再在 `deepAgent.getAgent()` 上注册 interrupt rail |

因此 DeepAgent 没有丢失远端 A2A tool 能力，但丢失了 ReAct 路径的 `Runner.resourceMgr()` / ability manager 同步方式。DeepAgent 使用 harness tool 注册作为等价入口。

### 11.7 输入形态更窄

ReAct handler 将 `OpenJiuwenMessageAdapter` 的结果作为 `Object input` 传给 `Runner.runAgentStreaming(...)`。DeepAgent handler 在执行前调用 `requireMap(...)`，只允许 map 输入：

```java
Map<String, Object> input = requireMap(toOpenJiuwenInput(context));
```

这意味着 DeepAgent adapter 当前只支持 map 形态输入。该约束来自 `DeepAgent.stream(...)` 的入参形态要求，也让 adapter 能写入 `conversation_id`。相比 ReAct 路径，它少了直接透传任意 OpenJiuwen input object 的能力。

### 11.8 适配结论

DeepAgent adapter 当前保留了 A2A 托管、trajectory、远端 A2A tool、结果映射和协作式取消；相对 ReAct handler，主要缺失集中在 runtime 侧横切能力：

1. runtime rail 自定义扩展点。
2. runtime neutral `MemoryProvider` 桥接。
3. runtime MCP tool 自动安装。
4. runtime SkillHub 自动安装。
5. ReAct `Runner.resourceMgr()` / ability manager 级 tool 注册路径。
6. 任意 `Object` 输入透传能力。

这些缺失不是 DeepAgent 原生能力缺失，而是当前 adapter 没有把 ReAct handler 已有的 runtime 横切集成完整搬到 DeepAgent harness 路径。

---

## 12. 能力规格

### 12.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| DeepAgent 进程内执行 | 已落地 | 通过 `DeepAgent.stream(...)` 执行 harness 任务循环 |
| A2A 托管 | 已落地 | 复用 `A2aAgentExecutor` 与 `AgentRuntimeHandler` 执行模型 |
| 运行时输入适配 | 已落地 | 复用 `OpenJiuwenMessageAdapter`，输入必须归一为 `Map<String,Object>` |
| conversation id 注入 | 已落地 | 缺少 `conversation_id` 时写入 `context.getAgentStateKey()` |
| 输出流映射 | 已落地 | 复用 `AbstractOpenJiuwenRuntimeSupport#mapRawResult` 与 `OpenJiuwenStreamAdapter` |
| 运行时远端 A2A 工具 | 已落地 | `OpenJiuwenRemoteToolInstaller#install(DeepAgent, context)` 注册 harness tool |
| 远端工具中断传播 | 已落地 | interrupt rail 注册到 `deepAgent.getAgent()` |
| 轨迹观测 | 已落地 | trajectory rail 注册到内部 ReAct agent |
| 取消 | 已落地 | `cancel(taskId)` 调用 `DeepAgent.requestAbort()` |
| 每次请求构建实例 | 已落地 | 每次 `execute()` 调用 `createOpenJiuwenDeepAgent(context)` |
| DeepAgent workspace | 应用侧配置 | runtime 不创建默认 workspace，由子类创建 `Workspace` |
| DeepAgent MCP | SDK 侧支持 | runtime handler 当前不注入 runtime MCP installer |

### 12.2 显式排除

| 排除项 | 原因 | 当前替代 |
|---|---|---|
| 将 DeepAgent 当作 `BaseAgent` 运行 | 会绕过 DeepAgent harness task loop、workspace 和 harness tool 机制 | 直接执行 `DeepAgent.stream(...)` |
| 新增 `OpenJiuwenDeepAgentStreamAdapter` | 当前 raw item 与 ReAct 路径共用 `OutputSchema`，共享映射已覆盖 | 使用 `AbstractOpenJiuwenRuntimeSupport#resultAdapter()` |
| 在 runtime adapter 中解析 YAML | YAML 装配属于 `agent-sdk` 职责 | 使用 `agent-sdk` 生成 DeepAgent，或在 handler 子类中直接构造 |
| 在 runtime adapter 中创建默认模型配置 | 模型 provider、api key、workspace 是应用部署配置 | 子类从 Spring properties 读取并构造 `DeepAgentConfig` |
| 在 runtime adapter 中安装 SkillHub/MCP | 当前落地范围只包含 A2A runtime remote tools | ReAct handler 保持原有 SkillHub/MCP hook；DeepAgent MCP 由 SDK YAML 装配 |
| 缓存 DeepAgent 实例跨请求复用 | DeepAgent 持有 task/workspace/session 状态，跨请求复用会扩大状态泄漏风险 | 每次 execute 创建实例，执行完成后移除 |

### 12.3 行为承诺

- 必须：`createOpenJiuwenDeepAgent(context)` 返回非 null `DeepAgent`，否则 handler 返回失败结果。
- 必须：runtime 传入 DeepAgent 的 input 是 `Map<String,Object>`；非 map 输入失败，不进入 DeepAgent。
- 必须：每个 task 在 `runningAgents` 中最多持有一个 DeepAgent 实例。
- 必须：stream 关闭、耗尽或异常时清理 `runningAgents`，避免取消表残留。
- 必须：远端工具安装发生在 `deepAgent.stream(...)` 之前。
- 必须：trajectory rail 注册到 `deepAgent.getAgent()`，不注册到 harness wrapper。
- 允许：子类根据 `AgentExecutionContext` 动态选择 prompt、workspace、模型、tools 和 DeepAgentConfig。
- 禁止：通过修改 generated facts 声明新类存在；事实变更只能来自 extractor。

---

## 13. 模块结构

### 13.1 包结构

```text
agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/openjiuwen/
├── AbstractOpenJiuwenRuntimeSupport.java          # ReAct/DeepAgent 共享 runtime 支撑
├── OpenJiuwenAgentRuntimeHandler.java            # ReAct BaseAgent handler
├── OpenJiuwenDeepAgentRuntimeHandler.java        # DeepAgent harness handler
├── OpenJiuwenMessageAdapter.java                 # runtime message -> OpenJiuwen input
├── OpenJiuwenStreamAdapter.java                  # OutputSchema -> AgentExecutionResult
├── OpenJiuwenTrajectoryRail.java                 # OpenJiuwen callback -> trajectory
├── OpenJiuwenRemoteToolInstaller.java            # runtime remote A2A tool installer
├── OpenJiuwenRemoteAgentInterruptRail.java       # remote tool interrupt rail
├── OpenJiuwenCheckpointerConfigurer.java         # OpenJiuwen 全局 checkpointer 配置
└── OpenJiuwenMemoryMessageAdapter.java           # ReAct memory message adapter
```

### 13.2 静态关系

```text
AgentRuntimeHandler
  └── AbstractAgentRuntimeHandler
        ├── OpenJiuwenAgentRuntimeHandler
        │     └── 用户子类实现 createOpenJiuwenAgent(context): BaseAgent
        └── OpenJiuwenDeepAgentRuntimeHandler
              └── 用户子类实现 createOpenJiuwenDeepAgent(context): DeepAgent

AbstractOpenJiuwenRuntimeSupport
  ├── 被 OpenJiuwenAgentRuntimeHandler 继承
  └── 被 OpenJiuwenDeepAgentRuntimeHandler 继承

OpenJiuwenRemoteToolInstaller
  ├── install(BaseAgent, context)
  └── install(DeepAgent, context)
```

### 13.3 与 Workflow Adapter 的对齐关系

| 维度 | Workflow Adapter | DeepAgent Adapter |
|---|---|---|
| 用户扩展点 | `createOpenJiuwenWorkflow(context)` | `createOpenJiuwenDeepAgent(context)` |
| 原生对象 | `Workflow` | `DeepAgent` |
| 执行入口 | `workflow.invoke(inputs, session, null)` | `deepAgent.stream(input, null, streamModes)` |
| 中断来源 | `QuestionerComponent` / Workflow graph interrupt | 远端 A2A tool interrupt rail |
| session 状态 | OpenJiuwen graph checkpointer | DeepAgent/OpenJiuwen conversation id + harness 内部状态 |
| runtime resume | Workflow 专用 resume context | 不提供用户输入 resume 分支 |
| 远端 tool | 可作为被调远端 agent | 可作为主调 agent 使用 remote A2A tools |
| 轨迹 | Workflow component 事件 | 内部 ReAct model/tool callback |

---

## 14. 核心流程

### 14.1 首次执行

```text
A2A client
  -> POST /a2a SendStreamingMessage
  -> A2aAgentExecutor
  -> AgentExecutionContext(inputType=USER_MESSAGE)
  -> OpenJiuwenDeepAgentRuntimeHandler.execute(context)
  -> createOpenJiuwenDeepAgent(context)
  -> runningAgents.put(taskId, deepAgent)
  -> installRuntimeTools(deepAgent, context)
  -> install trajectory rail on deepAgent.getAgent()
  -> toOpenJiuwenInput(context)
  -> ensure Map input and conversation_id
  -> deepAgent.stream(input, null, List.of(StreamMode.OUTPUT))
  -> resultAdapter()
  -> AgentExecutionResult stream
  -> A2A artifact/status events
```

### 14.2 远端工具调用

```text
DeepAgent task loop
  -> internal ReActAgent sees remote tool card
  -> model emits tool call
  -> OpenJiuwenRemoteAgentInterruptRail intercepts before tool execution
  -> Runtime returns AgentExecutionResult.INTERRUPTED(remote invocation)
  -> A2A remote invocation orchestrator calls remote agent
  -> remote result resumes local runtime stream
  -> DeepAgent receives tool result and continues task loop
```

关键点：

- 占位 tool 必须先注册到 DeepAgent harness，模型才能看到 tool card。
- 真正的远端调用不由占位 tool 执行，而由 interrupt rail 把 tool call 转成 runtime remote invocation。
- 远端调用恢复后，DeepAgent 继续由原 task loop 消费结果。

### 14.3 取消流程

```text
client cancel / runtime cancel
  -> OpenJiuwenDeepAgentRuntimeHandler.cancel(taskId)
  -> runningAgents.get(taskId)
  -> DeepAgent.requestAbort()
  -> OpenJiuwen 在下一次协作检查点停止
  -> raw stream close
  -> runningAgents.remove(taskId)
```

取消不强杀线程，也不直接中断阻塞 I/O。该行为与 OpenJiuwen DeepAgent 的协作式 abort 语义一致。

### 14.4 错误流程

```text
createOpenJiuwenDeepAgent throws / returns null
  -> failedResult(context, trajectory, error)
  -> AgentExecutionResult.failed("OPENJIUWEN_RUN_ERROR", message)

toOpenJiuwenInput returns non-map
  -> IllegalStateException
  -> failedResult(...)

deepAgent.stream throws
  -> failedResult(...)

raw result is null
  -> AgentExecutionResult.failed("OPENJIUWEN_ERROR", "openjiuwen runner returned no result")
```

---

## 15. 配置模型

### 15.1 示例配置

DeepAgent runtime adapter 没有全局强制配置类；应用子类从自身配置读取参数并构造 `DeepAgentConfig`。示例模块采用如下配置形态：

```yaml
sample:
  openjiuwen:
    model-provider: ${LLM_PROVIDER:openai}
    api-key: ${LLM_API_KEY:sk-local-placeholder}
    api-base: ${LLM_API_BASE:http://localhost:4000/v1}
    model-name: ${LLM_MODEL:gpt-5.4-mini}
    ssl-verify: ${LLM_SSL_VERIFY:false}
    workspace-path: ${DEEPAGENT_WORKSPACE:./target/deepagent-workspace}
    checkpointer: ${OPENJIUWEN_CHECKPOINTER:in-memory}
    redis-url: ${OPENJIUWEN_REDIS_URL:redis://localhost:6379}

agent-runtime:
  remote-agents:
    - url: http://localhost:8082
```

### 15.2 配置职责分布

| 配置类别 | 所属层 | 说明 |
|---|---|---|
| 模型 provider/api key/base URL | 应用示例层 | 用于构造 `DeepAgentConfig.backend` |
| 模型名称和采样参数 | 应用示例层 | 用于构造 `DeepAgentConfig.model` |
| workspace path | 应用示例层 | 同时写入 `DeepAgentConfig.workspacePath` 与 `Workspace.rootPath` |
| checkpointer | OpenJiuwen 全局配置 | 通过 `OpenJiuwenCheckpointerConfigurer` 设置 |
| remote agents | agent-runtime 配置 | 由 `RemoteAgentCardCache` 发现并注入 installer |
| A2A agent card | agent-runtime access 配置或 Bean | 声明被外部发现的 agent 能力 |

### 15.3 Agent Card 要求

DeepAgent handler 本身只声明 runtime 执行能力。被其他 agent 发现和调用时，需要通过 A2A Agent Card 暴露身份与 skills：

```yaml
agent-runtime:
  access:
    a2a:
      default-agent-id: my-deep-agent
      agent-card:
        name: my-deep-agent
        description: DeepAgent served by agent-runtime
        version: "1.0"
        skills:
          - id: plan_and_execute
            name: plan_and_execute
            description: 使用 DeepAgent 工作区和工具完成多步骤任务
```

如果没有声明 skills，远端 agent card 仍可被读取，但邻接 agent 缺少可调用 tool 的语义入口。

---

## 16. 对外呈现与用户场景

### 16.1 托管单个 DeepAgent

最小用户路径：

1. 继承 `OpenJiuwenDeepAgentRuntimeHandler`。
2. 在 `createOpenJiuwenDeepAgent(context)` 中创建 `AgentCard`、`DeepAgentConfig` 和 `Workspace`。
3. 注册 handler Spring bean。
4. 暴露 A2A Agent Card。
5. 通过 `/a2a` 发送 `SendStreamingMessage`。

### 16.2 主调远端 A2A Agent

当 DeepAgent 需要调用其他 agent：

1. 在当前服务中配置 `agent-runtime.remote-agents[].url`。
2. runtime 启动时读取远端 Agent Card。
3. `OpenJiuwenRemoteToolInstaller` 将远端 skill 注册为 DeepAgent harness tool。
4. DeepAgent 内部 ReAct agent 的 interrupt rail 捕获 tool call。
5. runtime A2A remote invocation orchestrator 调用远端 agent。
6. 远端结果回填到 DeepAgent task loop。

### 16.3 被其他 Agent 作为 Tool 调用

当 DeepAgent 服务作为被调方：

1. DeepAgent 服务暴露 `/.well-known/agent-card.json` 和 `/a2a`。
2. Agent Card 中声明 skills。
3. 主调 agent 将 DeepAgent 服务配置到 `remote-agents`。
4. 主调 agent 的 LLM 看到 DeepAgent skill 对应的 tool。
5. 主调 agent 调用 tool 后，A2A remote invocation 到达 DeepAgent 服务。
6. DeepAgent 完成后返回 output/completed 事件给主调 agent。

---

## 17. 错误处理

| 错误场景 | 触发条件 | runtime 行为 | 对外结果 |
|---|---|---|---|
| DeepAgent 构建失败 | 子类抛异常或返回 null | 捕获异常并发出 `ERROR` trajectory | `FAILED("OPENJIUWEN_RUN_ERROR")` |
| 输入不是 map | message adapter 结果不是 `Map<String,Object>` | 不调用 DeepAgent，直接失败 | `FAILED("OPENJIUWEN_RUN_ERROR")` |
| DeepAgent stream 抛错 | OpenJiuwen harness 执行异常 | 捕获并映射失败结果 | `FAILED("OPENJIUWEN_RUN_ERROR")` |
| raw result 为 null | OpenJiuwen iterator 产出 null | 映射为 OpenJiuwen 空结果错误 | `FAILED("OPENJIUWEN_ERROR")` |
| 远端 tool 未被 rail 拦截 | 占位 tool 直接执行 | 返回占位错误文本 | `REMOTE_AGENT_TOOL_NOT_INTERRUPTED` |
| 远端 agent card 拉取失败 | remote agent URL 不可达 | 远端 tool 不会安装 | DeepAgent 只能使用本地工具 |
| 取消时 task 不存在 | `runningAgents` 无该 taskId | 无操作 | 不产生额外失败事件 |
| 模型调用阻塞 | OpenJiuwen 正在等待模型响应 | `requestAbort()` 等待协作检查 | 取消延迟生效 |

### 17.1 排障信号

| 现象 | 检查点 |
|---|---|
| 模型看不到远端 tool | 检查 `agent-runtime.remote-agents`、远端 Agent Card skills、`OpenJiuwenRemoteToolInstaller` 日志 |
| 远端 tool 返回 `REMOTE_AGENT_TOOL_NOT_INTERRUPTED` | 检查 `OpenJiuwenRemoteAgentInterruptRail` 是否注册到 `deepAgent.getAgent()` |
| 没有 trajectory model/tool 事件 | 检查 trajectory 是否开启，以及 rail 是否注册到内部 ReAct agent |
| conversation 无法延续 | 检查输入中是否覆盖了 `conversation_id`，以及 `context.getAgentStateKey()` 是否稳定 |
| 取消无即时效果 | 检查是否处于阻塞模型调用；DeepAgent abort 是协作式 |

---

## 18. 完整示例

示例模块：`examples/agent-runtime-a2a-openjiuwen-deepagent-e2e/`

关键文件：

| 文件 | 作用 |
|---|---|
| `OpenJiuwenDeepAgentConfiguration.java` | 注册 DeepAgent handler、Agent Card、样例配置 |
| `OpenJiuwenDeepAgentA2aE2eTest.java` | 通过 A2A 端到端验证 DeepAgent 服务 |
| `application.yaml` | 样例 OpenJiuwen 模型、workspace、checkpointer 配置 |
| `pom.xml` | 示例模块依赖与测试配置 |

示例中的 handler 做了以下事情：

1. 从 `sample.openjiuwen.*` 读取模型和 workspace 配置。
2. 构造 OpenJiuwen `AgentCard`。
3. 构造 `DeepAgentConfig`。
4. 构造 `Workspace`。
5. 调用 `HarnessFactory.createDeepAgent(card, config, workspace)`。
6. 由 runtime handler 接管 A2A 执行。

---

## 19. 与 Agent SDK 的边界

`agent-sdk` 与 DeepAgent runtime adapter 面向不同层：

| 维度 | `agent-sdk` | DeepAgent runtime adapter |
|---|---|---|
| 输入 | `ascend-agent/v1` YAML | `AgentExecutionContext` |
| 输出 | 原生 `DeepAgent` 对象 | `AgentExecutionResult` stream |
| 是否托管 A2A | 否 | 是 |
| 是否管理 Spring bean | 否 | 是，由应用注册 handler bean |
| 是否安装 runtime remote tools | 否 | 是 |
| 是否负责 YAML schema | 是 | 否 |
| 是否负责 HTTP endpoint | 否 | 复用 `agent-runtime` A2A endpoint |

应用可以先用 `agent-sdk` 从 YAML 构造 `DeepAgent`，再在 `OpenJiuwenDeepAgentRuntimeHandler` 子类中返回该对象；此时仍由 runtime adapter 负责 A2A 执行、远端工具安装、trajectory 和取消。
