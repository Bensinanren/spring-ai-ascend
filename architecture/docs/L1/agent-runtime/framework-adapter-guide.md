---
level: L1
view: development
module: agent-runtime
status: implemented
authority: "ADR-0159 (agent-runtime consolidation)"
---

# `agent-runtime` — 框架适配器集成指南

本文面向需要将新 Agent 框架接入 `agent-runtime` 的开发者。阅读前请先了解
[`development.md`](development.md)（包结构与 SPI 设计原则）和
[`spi-appendix.md`](spi-appendix.md)（完整 SPI 契约）。

---

## 1. 两种集成风格

`agent-runtime` 当前实现了两种适配路径，分别针对不同框架的原生能力。

### 1.1 Rail-based（轨道回调式）

**适用场景：** 框架以回调钩子（"rails"）的形式暴露其执行生命周期。openJiuwen
的 `AgentRail` API 就是典型示例：框架在模型调用前后、工具调用前后会主动回调已注册的 rail。

**适配机制：**

runtime 将一个 `OpenJiuwenTrajectoryRail`（实现 `AgentRail`）在执行前注册到 agent 上，
在执行后的 `finally` 块中移除它。该 rail 直接在框架的原生回调里调用 `TrajectoryEmitter.emit()`，
将 `MODEL_CALL_START`/`MODEL_CALL_END`/`TOOL_CALL_START`/`TOOL_CALL_END`/`ERROR` 等
`TrajectoryDraft` 推向北向。`RUN_START`/`RUN_END` 生命周期由 `AbstractAgentRuntimeHandler`
统一管理，rail 只映射内部步骤。

**选用入口：**

| 场景 | 入口类 | 必须实现的方法 |
|---|---|---|
| 通用 openJiuwen agent | `OpenJiuwenAgentRuntimeHandler` | `createOpenJiuwenAgent(AgentExecutionContext)` |
| 标准 `ReActAgent` | `OpenJiuwenReActHandler` | `createReActAgent(AgentExecutionContext)` |

`OpenJiuwenReActHandler` 是 `OpenJiuwenAgentRuntimeHandler` 的便利子类：它将
`createOpenJiuwenAgent` 实现为 `final`，只需实现 `createReActAgent`，减少样板代码。

**可选扩展点：**

```java
// 在执行前向 agent 注册额外的 rail（默认返回空列表）
@Override
protected List<AgentRail> openJiuwenRails(AgentExecutionContext context) {
    return List.of(memoryRuntimeRail(context, memoryProvider));
}

// 注入 runtime 管理的工具（如 A2A 远端工具），默认为空操作
@Override
protected void installRuntimeTools(BaseAgent agent, AgentExecutionContext context) {
    // 可选覆盖
}
```

Rail 生命周期契约（见 `OpenJiuwenAgentRuntimeHandler#openJiuwenRails` javadoc）：
- `openJiuwenRails()` 返回的 rail 先于 `installRuntimeTools`、先于轨迹 rail 安装。
- 轨迹 rail 由基类统一管理，每次调用绑定一个独立的 emitter 实例。
- 缓存同一个 `BaseAgent` 实例跨调用复用时，`openJiuwenRails()` 中的 rail 必须保证幂等
  （推荐使用单例 rail 列表），否则会在每次执行时累加 rail。
- openJiuwen SDK 不支持同一 agent 实例并发调用；执行模型为顺序化。

### 1.2 Event-stream-based（事件流式）

**适用场景：** 框架将执行结果以事件流的形式吐出，而非通过回调通知。AgentScope 的 runtime
API 即为此类：一次调用返回一个 `Stream<AgentScopeEvent>`，其中包含输出片段、工具调用和
终态事件。

**适配机制：**

`AbstractAgentScopeRuntimeHandler.doExecute()` 通过 `Stream.peek()` 观察框架原生事件流，
将 `OUTPUT` 事件映射为 `PROGRESS` 轨迹（以及首个非空输出时的 `MODEL_CALL_FIRST_TOKEN`
时间点事件），将 `FAILED` 事件映射为 `ERROR` 轨迹。peek 是纯观察操作，不改变流本身。
`AgentScopeStreamAdapter` 同时作为 peek 的映射逻辑和 `resultAdapter()` 的实现，确保轨迹
与结果流永不分叉。`RUN_START`/`RUN_END` 同样由 `AbstractAgentRuntimeHandler` 统一管理。

**选用入口：**

| 场景 | 入口类 | 必须实现或提供 |
|---|---|---|
| 本地 AgentScope agent（进程内） | `AgentScopeAgentRuntimeHandler` | `AgentScopeAgent`（函数式接口） |
| 远端 AgentScope runtime（HTTP/SSE） | `AgentScopeRuntimeClientHandler` | `AgentScopeRuntimeClient` |
| AgentScope Harness agent | `AgentScopeHarnessRuntimeHandler` | `AgentScopeAgent`（Harness 变体） |

`AgentScopeAgent` 是一个函数式接口：

```java
@FunctionalInterface
public interface AgentScopeAgent {
    Stream<AgentScopeEvent> streamEvents(AgentScopeInvocation invocation);
}
```

最简本地集成只需提供一个 lambda 或匿名类实现该 SAM，传入 `AgentScopeAgentRuntimeHandler`
的构造器。

---

## 2. 决策表

```
框架暴露了生命周期回调钩子（如 beforeModelCall / afterToolCall）？
    ├── 是 → Rail-based
    │         └── 使用 ReActAgent？ → OpenJiuwenReActHandler（更少样板）
    │                              → 否 → OpenJiuwenAgentRuntimeHandler
    │
    └── 否 → 框架返回事件流 / SSE 流？
              ├── 是，进程内 → AgentScopeAgentRuntimeHandler（提供 AgentScopeAgent SAM）
              ├── 是，远端 HTTP/SSE → AgentScopeRuntimeClientHandler（提供 AgentScopeRuntimeClient）
              └── 是，远端 REST 工作流（非 AgentScope 协议） → VersatileAgentRuntimeHandler
                    （直接实例化，无需继承；适用于 Dify 等 REST+SSE 工作流服务）
```

---

## 3. 两种风格共同实现的中立契约

两种适配路径都建立在相同的 SPI 上，差异仅在框架交互层。

### 3.1 AgentRuntimeHandler SPI

```java
public interface AgentRuntimeHandler {
    String agentId();                                  // 唯一 agent ID
    boolean isHealthy();                               // 健康检查
    Stream<?> execute(AgentExecutionContext context);  // 执行入口
    StreamAdapter resultAdapter();                     // 框架结果 → 中立结果
    default void start() { }                           // 资源初始化（可选）
    default void stop() { }                            // 资源释放（可选）
    default void cancel(String taskId) { }             // 协作取消（可选）
}
```

两种适配路径中，业务方通常继承中间基类（`OpenJiuwenAgentRuntimeHandler` 或
`AbstractAgentScopeRuntimeHandler`），而非直接实现 `AgentRuntimeHandler`。
`VersatileAgentRuntimeHandler` 是例外——它是可直接实例化的 final class。

### 3.2 StreamAdapter

```java
@FunctionalInterface
public interface StreamAdapter {
    Stream<AgentExecutionResult> adapt(Stream<?> rawResults);
}
```

每种适配器都提供了对应的 `StreamAdapter` 实现：
`OpenJiuwenStreamAdapter`（openJiuwen）和 `AgentScopeStreamAdapter`（AgentScope）。
业务方无需自行实现；基类已通过 `resultAdapter()` 返回对应实例。

### 3.3 轨迹分工

| 责任方 | 负责的轨迹事件 |
|---|---|
| `AbstractAgentRuntimeHandler`（基类） | `RUN_START`、`RUN_END`、关联标记（seq / contextId / taskId） |
| Rail-based 适配器（`OpenJiuwenTrajectoryRail`） | `MODEL_CALL_START`、`MODEL_CALL_END`、`TOOL_CALL_START`、`TOOL_CALL_END`、`ERROR`（由框架回调触发） |
| Event-stream-based 适配器（`AbstractAgentScopeRuntimeHandler`） | `PROGRESS`（来自 OUTPUT 事件）、`MODEL_CALL_FIRST_TOKEN`（首个非空 OUTPUT）、`ERROR`（来自 FAILED 事件） |

适配器通过调用 `TrajectoryEmitter.emit(TrajectoryDraft)` 推送事件；基类的
`StampingTrajectoryEmitter` 负责补全关联字段并按 `supportedKinds()` 做能力门控，过滤
未声明的事件种类。适配器不持有关联身份，不决定事件是否过滤——这两件事由基类和 emitter
负责。

### 3.4 Spring Bean 注册

无论哪种风格，最终只需将 `AgentRuntimeHandler` 的具体实现注册为 Spring Bean：

```java
@Bean
OpenJiuwenAgentRuntimeHandler myHandler(...) {
    return new MyOpenJiuwenHandler(...);
}
```

`RuntimeAutoConfiguration` 自动发现该 Bean，将其绑定到 A2A JSON-RPC 端点，并基于
`agentId()` + handler 能力钩子 + `AgentCardProperties` YAML overlay 组装默认 `AgentCard`
（可通过实现 `AgentCardProvider.describe()` 返回中立 `AgentCardDescriptor` 完全覆盖）。

### 3.5 Agent Card 能力声明必须诚实

card 上广告的能力必须与 handler 的**实际行为**一致——A2A client 据此选择协议路径，谎报会造成兼容性 bug。能力字段默认 fail-safe（opt-in），由 handler 显式声明：

| 钩子 | 默认 | 何时 override |
|---|---|---|
| `supportsStreaming()` | `false` | handler 真发**多帧增量**结果（`execute` 返回多元素 `Stream`）时返回 `true`；单次返回最终结果的 handler 保持 `false`（否则 client 走 `message/stream` 期待 SSE 增量帧却只收一帧，违反 A2A 协议契约） |
| `skills()` | 空 | 声明 handler 提供的 `AgentSkillDescriptor` 列表 |
| `defaultOutputModes()` | `["text"]` | handler 真实支持其它 mode（如 `"artifact"`）时追加 |

`capabilities.pushNotifications` 由运行时按 push-store 持久性派生（默认 `InMemoryPushNotificationConfigStore` → `false`），handler 无需声明。

> **security on card**：runtime 经 `X-Tenant-Id` header 识别租户，但 card 默认**不** emit security scheme——当前 A2A SDK 的 `A2ACardResolver` 以 protobuf-JSON 解析 card，无法 unmarshal 带 security 的 card（spec-JSON 的空 scope 数组 `[]` 触发 "Expect message object" 解析失败），emit 会使整张 card 不可发现。security 为 opt-in，经 `AgentCardProvider`/YAML 显式声明。

---

## 4. 实例工程参考

| 工程路径 | 集成风格 | 要点 |
|---|---|---|
| `examples/agent-runtime-openjiuwen-simple/` | Rail-based | 最简示例：`OpenJiuwenAgentRuntimeHandler` + `createOpenJiuwenAgent`，无记忆、无额外 rail |
| `examples/agent-runtime-a2a-openjiuwen-e2e/` | Rail-based | 完整示例：`OpenJiuwenAgentRuntimeHandler` + `openJiuwenRails`（含 `MemoryRuntimeRail`）+ Redis checkpointer |
| `examples/agent-runtime-a2a-versatile-e2e/` | Event-stream-based（REST 变体） | 直接实例化 `VersatileAgentRuntimeHandler`，代理远端 REST/SSE 工作流服务 |

**openjiuwen-simple 核心片段**（`OpenJiuwenSimpleAgentConfiguration`）：

```java
static final class SimpleOpenJiuwenAgentHandler extends OpenJiuwenAgentRuntimeHandler {
    SimpleOpenJiuwenAgentHandler(...) { super(AGENT_ID); }

    @Override
    protected BaseAgent createOpenJiuwenAgent(AgentExecutionContext context) {
        ReActAgent agent = new ReActAgent(card);
        agent.configure(config);
        return agent;
    }
}
```

**openjiuwen-e2e 扩展片段**（`OpenJiuwenReactAgentConfiguration`）——演示带记忆 rail：

```java
@Override
protected List<AgentRail> openJiuwenRails(AgentExecutionContext context) {
    return List.of(memoryRuntimeRail(context, memoryProvider));
}
```

**versatile 片段**（`VersatileAgentConfiguration`）——演示 REST 代理风格：

```java
@Bean
AgentRuntimeHandler versatileAgentRuntimeHandler(VersatileProperties props) {
    return new VersatileAgentRuntimeHandler(
            AGENT_ID, "Versatile Agent", "...",
            new VersatileClient(props),
            new VersatileMessageAdapter(props),
            new VersatileStreamAdapter());
}
```

---

## 5. 接入新框架的最小步骤

以下步骤适用于当前两种风格之外的新框架（如框架既有 rail 又有流，或两者皆无）：

1. **判断框架的原生能力**（见第 2 节决策表）。
2. **Rail-based**：在 `engine/openjiuwen/` 参考 `OpenJiuwenTrajectoryRail` 的模式，
   用框架自身的回调接口替换 `AgentRail`；基类模板（`OpenJiuwenAgentRuntimeHandler`）
   管理 rail 注册和生命周期，新框架复用同一模板。
3. **Event-stream-based**：在 `engine/agentscope/` 参考 `AbstractAgentScopeRuntimeHandler`
   的 `doExecute` 模式，将 `AgentScopeEvent` 替换为框架自己的事件类型，在 peek 中
   映射到对应的 `TrajectoryDraft` 工厂方法（`TrajectoryDraft.progress()`、
   `TrajectoryDraft.error()` 等）。
4. **编写 `StreamAdapter`**：将框架原生结果映射为 `AgentExecutionResult.output()` /
   `AgentExecutionResult.completed()` / `AgentExecutionResult.failed()` /
   `AgentExecutionResult.interrupted()`。
5. **注册为 Spring Bean**，runtime 自动接入 A2A 端点。

包纯度约束（由 `RuntimePackageBoundaryTest` 强制执行）：新框架适配器包不得依赖
`engine.a2a`（协议桥），也不得引入 A2A SDK 的 `org.a2aproject..` 类型。
