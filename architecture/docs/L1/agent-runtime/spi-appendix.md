---
level: L1
view: development
module: agent-runtime
status: implemented
authority: "ADR-0152 (Uniform L1 per-view mechanism + L0 mounting)"
---

# `agent-runtime` — SPI 接口附录

## 1. SPI 接口清单

`agent-runtime` 产生一个 SPI 包（与 `module-metadata.yaml#spi_packages` 交叉验证）：

**SPI 包**：`com.huawei.ascend.runtime.engine.spi`

### 1.1 接口一览

| FQN | 类型 | 语义 | 生命周期 |
|---|---|---|---|
| `com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler` | interface | Agent 框架与引擎之间的唯一解耦面，执行一个 Agent 并输出框架无关的结果流 | 每个 agentId 注册一个实例，随 Runtime 生命周期存在 |
| `com.huawei.ascend.runtime.engine.spi.AgentRuntimeProvider` | interface | 可选生命周期钩子，在 handler 执行前后注入逻辑（状态恢复、沙箱准备、工具覆盖、追踪等） | 每次 execute 调用时通过 `handler.providers()` 获取列表，无状态 |
| `com.huawei.ascend.runtime.engine.spi.StreamAdapter` | @FunctionalInterface | 将框架原生结果流映射为引擎中立的 `Stream<AgentExecutionResult>` | 由 handler 提供，每次 execute 后调用 |
| `com.huawei.ascend.runtime.engine.spi.StateProvider` | interface | 标记接口（继承 `AgentRuntimeProvider`），需要手动状态桥接的框架实现 | 作为 provider 的一种，通过 `handler.providers()` 返回 |

Agent Card 供应接口 `AgentCardProvider`（返回中立 `AgentCardDescriptor`）随 ADR-0163 迁入中立 SPI 包 `com.huawei.ascend.runtime.engine.spi`（见 §2.5）；A2A SDK 类型的构造收敛到协议桥 `engine.a2a.A2aAgentCardMapper`（唯一投影点），默认工厂 `AgentCards` 亦薄化为描述符工厂。

### 1.2 辅助类与值对象（非 SPI 接口，但属于 SPI 包）

| FQN | 类型 | 语义 |
|---|---|---|
| `com.huawei.ascend.runtime.engine.spi.AgentExecutionResult` | final class | 引擎中立的执行结果值对象，4 种语义：`OUTPUT` / `COMPLETED` / `FAILED` / `INTERRUPTED` |
| `com.huawei.ascend.runtime.engine.spi.AgentRuntimeProviderChain` | final class | 工具类：按序执行所有 providers 的 `beforeExecute` → handler.execute() → 逆序执行 `afterExecute`，保证失败隔离 |
| `com.huawei.ascend.runtime.common.RuntimeMessage` | record | 协议中立的消息载体（`role` / `text` / `metadata`），SPI 输入模型的唯一消息类型；`AgentExecutionContext.lastUserText()` 提供规范的文本抽取 |
| `com.huawei.ascend.runtime.engine.spi.ErrorCategory` | enum | OTel-aligned 错误分类，用于轨迹事件 `error.category` 字段；never null（无映射时用 `UNKNOWN`） |
| `com.huawei.ascend.runtime.engine.spi.TenantContract` | final class | 租户隔离键名常量 |

### 1.3 可观测性管道扩展点（属于 SPI 包，非 shipped SPI）

因包边界约束位于 `engine.spi`，但属于可观测性管道扩展点，不计入核心 shipped SPI 数量：

| FQN | 类型 | 语义 |
|---|---|---|
| `com.huawei.ascend.runtime.engine.spi.Redactor` | interface | 载荷脱敏扩展点：对轨迹载荷字段执行脱敏/遮蔽 |
| `com.huawei.ascend.runtime.engine.spi.ValueRecognizingRedactor` | class | `Redactor` 实现：按值内容识别并脱敏 |
| `com.huawei.ascend.runtime.engine.spi.PayloadRefStore` | interface | 超阈值载荷引用化存储扩展点 |
| `com.huawei.ascend.runtime.engine.spi.LocalFsPayloadRefStore` | class | `PayloadRefStore` 实现：本地文件系统存储 |

## 2. 接口详细规范

### 2.1 AgentRuntimeHandler

```java
public interface AgentRuntimeHandler {
    /** 此 handler 服务的 agent ID。每个 handler 对应一个唯一的 agentId。 */
    String agentId();

    /** 健康检查。返回 false 时引擎拒绝调度此 handler。 */
    boolean isHealthy();

    /**
     * 执行 Agent。
     * @param context 最小执行上下文（与 A2A 协议解耦）
     * @return 框架原生的结果流（由 StreamAdapter 适配后消费）
     */
    Stream<?> execute(AgentExecutionContext context);

    /**
     * 可选的生命周期提供者列表。
     * 默认返回空列表。需要状态、沙箱、工具覆盖、追踪等功能的 handler
     * 通过返回 providers 来组合能力，而非增加继承层级。
     */
    default List<AgentRuntimeProvider> providers() { return List.of(); }

    /** 框架原生结果 → 引擎中立结果的适配器。 */
    StreamAdapter resultAdapter();
}
```

**契约**：
- `agentId()` 必须返回非空非空白字符串，作为 handler 的唯一标识
- `execute()` 返回的 `Stream<?>` 必须由调用者关闭（通过 try-with-resources）
- `resultAdapter()` 返回的 `StreamAdapter` 不能为 null
- `providers()` 返回的列表不可变，每次调用返回相同的 providers 集合

**实现约定**：
- 具体 Agent 框架提供抽象基类（如 `OpenJiuwenAgentRuntimeHandler`、`AbstractAgentScopeRuntimeHandler`），业务方继承并实现框架特定的执行逻辑
- `agentId()` 和 `resultAdapter()` 在基类中实现为 final，子类不可覆盖
- `isHealthy()` 默认返回 true，子类可覆盖添加框架特定的健康检查

### 2.2 AgentRuntimeProvider

```java
public interface AgentRuntimeProvider {
    /** 在 handler 执行前调用。可修改 context（如注入 Agent State）。 */
    default void beforeExecute(AgentExecutionContext context) { }

    /** 在结果流关闭后调用。可导出副作用（如保存 Agent State）。 */
    default void afterExecute(AgentExecutionContext context) { }
}
```

**契约**：
- `beforeExecute` 按注册顺序调用，`afterExecute` 按注册逆序调用
- `beforeExecute` 抛出异常 → 已完成的 providers 的 `afterExecute` 仍被调用 → 异常重新抛出
- `afterExecute` 抛出异常 → LOG.warn 记录，不影响其他 providers 的 `afterExecute`
- 实现必须是线程安全的（多个 task 可能并发使用同一 provider 实例）

**典型实现场景**：
- `SandboxProvider`：准备沙箱环境（before），清理沙箱（after）
- `ToolOverrideProvider`：根据租户/场景注入或覆盖工具配置（before）
- `TracingProvider`：注入 trace context（before），结束 span（after）

### 2.3 StreamAdapter

```java
@FunctionalInterface
public interface StreamAdapter {
    /** 将框架原生的结果流转换为引擎中立的结果流。 */
    Stream<AgentExecutionResult> adapt(Stream<?> rawResults);
}
```

**契约**：
- 输入流中的每个元素代表框架的一个结果项（可能是中间输出、最终结果、错误或中断信号）
- 输出流必须将每个结果项映射为一个 `AgentExecutionResult`
- 映射必须是确定的：相同输入总是产生相同输出
- 流不关闭（由调用者 `AgentRuntimeProviderChain` 管理生命周期）

### 2.4 AgentExecutionResult

```java
public final class AgentExecutionResult {
    public enum Type { OUTPUT, COMPLETED, FAILED, INTERRUPTED }

    // 工厂方法
    public static AgentExecutionResult output(String content);
    public static AgentExecutionResult completed(String content);
    public static AgentExecutionResult failed(String errorCode, String errorMessage);
    public static AgentExecutionResult interrupted(String prompt);

    // 访问器
    public Type type();
    public String outputContent();   // OUTPUT / COMPLETED 时有效
    public String errorCode();       // FAILED 时有效
    public String errorMessage();    // FAILED 时有效
    public String prompt();          // INTERRUPTED 时有效
}
```

**语义映射**（在 `A2aAgentExecutor.route()` 中）：

| AgentExecutionResult.Type | A2A AgentEmitter 回调 | Task 状态 | 说明 |
|---|---|---|---|
| `OUTPUT` | `emitter.sendMessage(text)` | WORKING（不变） | 流式输出的中间片段，task 保持 WORKING 状态 |
| `COMPLETED` | `emitter.complete(message)` 或 `emitter.complete()` | COMPLETED | 最终完成，可带或不带最终文本 |
| `FAILED` | `emitter.fail()` | FAILED | 执行失败，携带 errorCode + errorMessage |
| `INTERRUPTED` | `emitter.requiresInput()` | INPUT_REQUIRED | 需要人工输入，prompt 文本作为提示 |

### 2.5 AgentCardProvider（位于 `engine.spi`，中立 SPI — ADR-0163）

```java
public interface AgentCardProvider {
    /** 返回此运行时实例暴露的中立 Agent Card 描述符。 */
    AgentCardDescriptor describe();
}
```

**设计理由**：将 Agent Card（元数据描述）与 `AgentRuntimeHandler`（Agent 执行逻辑）分离。一个运行时可分别提供描述符和 Handler 作为独立 Bean，也可在业务 handler 中同时实现两个接口。**ADR-0163** 推翻 ADR-0162 的推迟决策：`AgentCardProvider` 现返回**中立** `AgentCardDescriptor`（零 `org.a2aproject` 依赖），与描述符类型同处中立 SPI 包，业务方实现自定义 Card 无需依赖 A2A spec 类型；A2A SDK 类型仅在协议桥 `engine.a2a.A2aAgentCardMapper` 这一唯一投影点构造。

### 2.6 StateProvider

```java
public interface StateProvider extends AgentRuntimeProvider { }
```

**设计理由**：
- 标记接口，用于框架需要手动状态桥接的场景（如 AgentScope）
- 继承 `AgentRuntimeProvider`，通过 `handler.providers()` 返回
- 框架有原生 checkpointer 的（如 openJiuwen 的 `conversation_id`），可以不使用此 provider，改用自身的 checkpointer 配置

## 3. 非 SPI 但公共的 API

以下接口属于 `runtime.engine.service` 包，是运行时内部 API（非 SPI），但业务方可直接使用：

| FQN | 类型 | 语义 |
|---|---|---|
| `com.huawei.ascend.runtime.engine.service.RemoteAgentCatalog` | class | 远端 runtime 的 agent-card 目录：解析/刷新部署配置声明的远端 URL，暴露可用工具规格 |
| `com.huawei.ascend.runtime.engine.service.RemoteAgentInvocationService` | class | 经 outbound adapter 调用远端 agent 的服务入口 |

这两个类不是 Agent 框架 SPI：它们支撑 remote A2A 工具调用编排。Agent 自身的 checkpoint 状态委托各框架的 checkpointer，runtime 不提供状态持久化接口；框架适配器可经 `AgentExecutionContext.getAgentState()` / `replaceAgentState()` 在执行上下文内传递小型状态引用。

## 4. A2A SDK 提供的非自有接口

以下接口由 A2A SDK（`org.a2aproject.sdk`）提供，agent-runtime 消费但不拥有：

| FQN | 类型 | 语义 | 消费方 |
|---|---|---|---|
| `org.a2aproject.sdk.server.agentexecution.AgentExecutor` | interface | A2A 协议层的 Agent 执行入口 | `A2aAgentExecutor` 实现此接口 |
| `org.a2aproject.sdk.server.requesthandlers.RequestHandler` | interface | A2A 协议请求分发器 | `A2aJsonRpcController` 调用 |
| `org.a2aproject.sdk.server.tasks.AgentEmitter` | interface | Task 状态变更的事件发射器 | `A2aAgentExecutor` 通过回调发射事件 |
| `org.a2aproject.sdk.server.events.MainEventBus` | class | 内部事件总线 | `RuntimeAutoConfiguration` 装配 |
| `org.a2aproject.sdk.server.events.QueueManager` | interface | 任务队列管理 | `RuntimeAutoConfiguration` 装配 |
| `org.a2aproject.sdk.server.tasks.InMemoryTaskStore` | class | Task 内存存储 | `RuntimeAutoConfiguration` 装配 |
| `org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler` | class | 默认 RequestHandler 实现 | `RuntimeAutoConfiguration` 装配 |
| `org.a2aproject.sdk.spec.AgentCard` | class | A2A Agent Card 模型 | `AgentCardController` + `engine.a2a` 的 `A2aAgentCardMapper`（唯一投影点）/ `AgentCards` + `engine.service` 的 `RemoteAgentCatalog`（远端卡解析）使用 |
| `org.a2aproject.sdk.spec.Task` | class | A2A Task 模型 | 通过 A2A SDK 内部使用 |
| `org.a2aproject.sdk.spec.Message` | class | A2A Message 模型 | 仅 `engine.a2a` 协议桥内部消费（`A2aAgentExecutor` / `Messages`）；SPI 侧消息载体是中立的 `common.RuntimeMessage` |

## 5. SPI 纯度约束

约束的唯一权威是可执行守卫 `RuntimePackageBoundaryTest`（`agent-runtime/src/test/java/com/huawei/ascend/runtime/architecture/`），本节只是其摘要；二者不一致时以测试为准：

- 白名单规则（`engineSpiDependsOnlyOnContractSafePackages`）：`engine.spi` 只能依赖 `engine.spi` 自身、engine 根包（`AgentExecutionContext`）、`common`（`RuntimeIdentity` / `RuntimeMessage`）、`java.*`、`org.slf4j`、`org.springframework.util`
- 禁止式规则（`protocolNeutralPackagesAreA2aSdkFree`）：engine 根包 / `engine.spi` / `engine.otel` / `common` / 框架适配器包（`engine.agentscope`、`engine.openjiuwen`）不得依赖 `org.a2aproject..` 任何类型（含 `sdk.spec`）
- 禁止式规则（`frameworkAdaptersDoNotDependOnTheA2aBridge`）：框架适配器包不得依赖协议桥包 `engine.a2a`
- 禁止式规则（`a2aServerMachineryStaysInBridgeAndBoot`）：`org.a2aproject.sdk.server..` 重型机械只允许 `engine.a2a` 与 `boot` 依赖
