---
level: L1
view: development
module: agent-runtime
status: implemented
authority: "ADR-0152 (Uniform L1 per-view mechanism + L0 mounting)"
---

# `agent-runtime` — 开发视图

## 1. 命名空间与包结构

命名空间根：`com.huawei.ascend.runtime`

```text
agent-runtime/
└── src/main/java/com/huawei/ascend/runtime/
    │
    ├── app/                          # 纯 Java 可嵌入入口（无 Spring 依赖）
    │   ├── RuntimeApp.java           #   RuntimeApp.create(handler).run(host) 入口
    │   ├── RuntimeHost.java          #   框架无关的 runtime host SPI
    │   ├── LocalA2aRuntimeHost.java  #   Spring Boot host 实现（唯一的 Spring 依赖点）
    │   ├── RunningRuntime.java       #   运行时句柄 (AutoCloseable + port())
    │   ├── RuntimeComponents.java    #   组件容器 record(AgentRuntimeHandler)
    │   └── package-info.java
    │
    ├── boot/                         # Spring Boot 自动配置 + HTTP 控制器
    │   ├── RuntimeAutoConfiguration.java       # 顶层装配入口
    │   ├── A2aInfrastructureConfiguration.java # A2A SDK 基础设施 Bean
    │   ├── RuntimeLifecycleConfiguration.java  # 运行时生命周期 Bean
    │   ├── A2aExecutionConfiguration.java      # A2A 执行 Bean
    │   ├── A2aJsonRpcController.java           # /a2a JSON-RPC 端点
    │   ├── AgentCardController.java            # /.well-known/agent-card.json 端点
    │   ├── AgentCardProperties.java            # @ConfigurationProperties: Agent Card 元数据（从 engine.a2a 迁入）
    │   ├── RemoteAgentProperties.java          # @ConfigurationProperties: 远端 Agent 部署配置（从 engine.a2a 迁入）
    │   ├── RuntimeAccessProperties.java        # @ConfigurationProperties: 接入配置
    │   ├── TrajectoryProperties.java           # @ConfigurationProperties: 轨迹观测配置
    │   ├── AgentRuntimeHealthIndicator.java    # Actuator 健康检查
    │   ├── AgentRuntimeLifecycle.java          # SmartLifecycle handler start/stop
    │   ├── RuntimeReadiness.java               # 就绪门控
    │   └── TrajectoryOtelConfiguration.java    # OTel 轨迹导出配置
    │
    ├── common/                       # 共享类型
    │   └── RuntimeIdentity.java      # record(tenantId, userId, sessionId, taskId, agentId)
    │
    └── engine/                       # 引擎实现
        │
        ├── AgentExecutionContext.java # 最小执行上下文（与 A2A SDK RequestContext 解耦）
        │
        ├── spi/                      # 框架无关的运行时 SPI（零外部依赖，仅 java.* + bus.spi.engine）
        │   ├── AgentRuntimeHandler.java        # Agent 执行 SPI: agentId()/isHealthy()/execute()/resultAdapter()
        │   ├── AgentRuntimeProvider.java       # 可选生命周期钩子: beforeExecute()/afterExecute()
        │   ├── AgentRuntimeProviderChain.java  # Handler + providers 编排 + 失败隔离
        │   ├── AgentExecutionResult.java       # 中立执行结果: OUTPUT/COMPLETED/FAILED/INTERRUPTED
        │   ├── StreamAdapter.java              # 函数式接口: Stream<?> → Stream<AgentExecutionResult>
        │   ├── StateProvider.java              # 框架状态桥接标记（继承 AgentRuntimeProvider）
        │   ├── ErrorCategory.java              # OTel-aligned 错误分类枚举（轨迹 error.category 字段）
        │   ├── Redactor.java                   # 可观测性扩展点: 载荷脱敏接口
        │   ├── ValueRecognizingRedactor.java   # Redactor 实现: 按值内容识别脱敏
        │   ├── PayloadRefStore.java            # 可观测性扩展点: 超阈值载荷引用化存储接口
        │   ├── LocalFsPayloadRefStore.java     # PayloadRefStore 实现: 本地文件系统
        │   ├── TenantContract.java             # 租户隔离键名常量
        │   ├── AgentCardProvider.java          # A2A Card 供应 SPI: describe(): AgentCardDescriptor（ADR-0163，迁自 engine.a2a）
        │   ├── AgentCardDescriptor.java        # 中立 Card 数据模型（零 org.a2aproject）+ Skill/Capabilities/Interface/SecurityScheme/Signature 嵌套描述符
        │   └── package-info.java
        │
        ├── a2a/                      # A2A SDK 桥接层
        │   ├── A2aAgentExecutor.java     # 实现 A2A SDK AgentExecutor，桥接 A2A 协议与 SPI
        │   ├── A2aAgentCardMapper.java   # 唯一 A2A 投影点: AgentCardDescriptor → A2A AgentCard
        │   ├── AgentCards.java           # 薄描述符工厂（保留 pre-ADR-0163 调用点）
        │   └── BuildVersion.java         # 从构建属性/manifest 解析版本（避免字面量版本号）
        │
        ├── openjiuwen/               # openJiuwen ReAct Agent 适配器
        │   ├── OpenJiuwenAgentRuntimeHandler.java       # 抽象基类
        │   ├── OpenJiuwenMessageAdapter.java            # AgentExecutionContext → openJiuwen 输入
        │   ├── OpenJiuwenStreamAdapter.java             # openJiuwen 结果 → AgentExecutionResult
        │   ├── OpenJiuwenReActHandler.java              # ReAct 模式 handler 实现
        │   ├── MemoryRuntimeRail.java                   # memory rail 集成（顶层类）
        │   ├── OpenJiuwenCheckpointerConfigurer.java    # Checkpointer 配置适配
        │   ├── OpenJiuwenExternalMemoryProviderAdapter.java  # 外部 MemoryProvider 适配器
        │   ├── OpenJiuwenMemoryMessageAdapter.java      # memory 消息格式适配
        │   ├── OpenJiuwenRemoteAgentInterruptRail.java  # 远端 agent 中断 rail
        │   ├── OpenJiuwenRemoteToolInstaller.java       # 远端工具安装器
        │   └── OpenJiuwenTrajectoryRail.java            # 轨迹 rail 集成
        │
        ├── agentscope/               # AgentScope 适配器
        │   ├── AbstractAgentScopeRuntimeHandler.java   # 抽象基类
        │   ├── AgentScopeAgentRuntimeHandler.java      # 本地 AgentScope Agent 处理器
        │   ├── AgentScopeRuntimeClientHandler.java     # 远程 AgentScope runtime client 处理器
        │   ├── AgentScopeHarnessRuntimeHandler.java    # AgentScope Harness 处理器（前 AgentScopeHarnessAgent 已合入）
        │   ├── AgentScopeStreamAdapter.java            # AgentScope 结果 → AgentExecutionResult
        │   ├── AgentScopeMessageAdapter.java           # AgentExecutionContext → AgentScope 输入
        │   ├── AgentScopeInvocation.java               # AgentScope 调用 DTO
        │   ├── AgentScopeAgent.java                    # AgentScope Agent 接口
        │   ├── AgentScopeRuntimeClient.java            # AgentScope runtime client 接口
        │   ├── AgentScopeEvent.java                    # AgentScope 事件类型
        │   ├── AgentScopeStatus.java                   # AgentScope 状态枚举/常量
        │   ├── AgentScopeErrorCategories.java          # AgentScope 错误分类常量
        │   └── AgentScopeRuntimeClientProperties.java  # Client 配置属性
        │
        └── service/                  # 引擎侧服务（运行时 API，非 SPI）
            ├── RemoteAgentCatalog.java           # 远端 runtime agent-card 目录（解析/刷新/工具规格）
            └── RemoteAgentInvocationService.java # 远端 agent A2A 调用服务
```

## 2. 模块依赖

### 2.1 Maven 依赖

```xml
<dependencies>
    <!-- 同级模块: agent-bus (中立 EnginePort/编排 SPI) -->
    <dependency>
        <groupId>com.huawei.ascend</groupId>
        <artifactId>agent-bus</artifactId>
    </dependency>
    <!-- Web + Actuator (northbound + bootable app) -->
    <!-- A2A SDK (org.a2aproject.sdk) 系列依赖 -->
</dependencies>
```

### 2.2 模块间依赖图

```
                    ┌──────────────┐
                    │  agent-bus   │
                    │ (中立 SPI)   │
                    └──────┬───────┘
                           │ 消费
                           ▼
     ┌──────────────────────────────────────┐
     │            agent-runtime              │
     │                                       │
     │  engine.spi ◄── engine.a2a           │
     │      ▲              ▲                 │
     │      │              │                 │
     │  engine.openjiuwen  │                 │
     │  engine.agentscope  │                 │
     │      ▲              │                 │
     │      │              │                 │
     │  boot (Spring Boot + A2A SDK)         │
     │      ▲                                │
     │      │                                │
     │  app (纯 Java, 无 Spring)             │
     │      ▲                                │
     └──────┼────────────────────────────────┘
            │ 消费
            ▼
     ┌──────────────┐
     │ agent-service │  (下游 serviceization)
     └──────────────┘
```

### 2.3 禁止依赖

- `agent-runtime → agent-service`：反向依赖禁止（Rule 10 / ArchUnit）
- `engine.spi` 不得依赖 Spring、Micrometer、OTel 或任何参考实现
- `app`（除 `LocalA2aRuntimeHost`）不得依赖 Spring Boot

## 3. SPI 设计原则

### 3.1 最小接口原则

`engine.spi` 包的核心 SPI 类型（4 个 shipped SPI 接口 + 辅助类型）：

| 类型 | 种类 | 语义 |
|---|---|---|
| `AgentRuntimeHandler` | interface | Agent 执行的唯一 SPI：一个 Agent ID 对应一个 Handler |
| `AgentRuntimeProvider` | interface | 可选生命周期钩子，通过组合扩展功能 |
| `AgentRuntimeProviderChain` | final class | 统一编排 handlers + providers 的执行和失败隔离 |
| `AgentExecutionResult` | final class | 中立执行结果，5 种类型（OUTPUT/COMPLETED/FAILED/INTERRUPTED/REMOTE_INVOCATION） |
| `StreamAdapter` | @FunctionalInterface | 框架结果 → 中立结果流的类型转换 |
| `StateProvider` | interface | 继承 AgentRuntimeProvider，框架需要手动状态桥接时使用 |
| `ErrorCategory` | enum | OTel-aligned 错误分类（轨迹 `error.category` 字段） |
| `Redactor` / `ValueRecognizingRedactor` | interface / class | 可观测性管道扩展点：载荷脱敏（非 shipped SPI，见注） |
| `PayloadRefStore` / `LocalFsPayloadRefStore` | interface / class | 可观测性管道扩展点：超阈值载荷引用化存储（非 shipped SPI，见注） |
| `TenantContract` | final class | 租户隔离键名常量 |

> `AgentCardProvider`（返回中立 `AgentCardDescriptor`）随 ADR-0163 迁入 `engine.spi`；A2A SDK 类型仅在 `engine.a2a.A2aAgentCardMapper` 这一唯一投影点构造。`Redactor` 与 `PayloadRefStore` 因包边界原因位于 `engine.spi`，是可观测性管道扩展点，不计入核心 shipped SPI 数量（核心 shipped SPI 仍为 `AgentRuntimeHandler` + `MemoryProvider` + `StreamAdapter` + `AgentCardProvider` 共 4 个）。

### 3.2 扩展原则：组合优于继承

```java
// ❌ 不推荐: 通过深层继承扩展功能
class MyHandler extends AbstractHandler extends BaseHandler { ... }

// ✅ 推荐: 通过 AgentRuntimeProvider 组合扩展
class MyHandler implements AgentRuntimeHandler {
    @Override
    public List<AgentRuntimeProvider> providers() {
        return List.of(
            new StateProviderImpl(),     // 状态恢复/导出
            new SandboxProvider(),       // 沙箱准备
            new TracingProvider()        // 追踪注入
        );
    }
}
```

提供者通过 `AgentRuntimeProviderChain` 按序执行：
- `beforeExecute` 按注册顺序执行
- `afterExecute` 按注册逆序执行（保证资源释放对称性）
- 任一 `beforeExecute` 异常 → 已进入的 providers 的 `afterExecute` 仍被执行 → 异常重新抛出
- 任一 `afterExecute` 异常 → LOG.warn 记录，不中断其他 providers

## 4. 自动装配

`RuntimeAutoConfiguration` 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配：

```java
@Configuration(proxyBeanMethods = false)
public class RuntimeAutoConfiguration {
    // A2A SDK 基础设施
    @Bean A2AConfigProvider       // 默认配置
    @Bean InMemoryTaskStore       // Task 存储（可替换）
    @Bean PushNotificationConfigStore  // 推送通知配置
    @Bean PushNotificationSender       // 推送发送器
    @Bean MainEventBus            // 内部事件总线
    @Bean QueueManager            // 队列管理器
    @Bean MainEventBusProcessor   // 后台事件处理器
    @Bean Executor                // 线程池

    // 业务层装配
    @Bean AgentExecutor           // A2aAgentExecutor(primary AgentRuntimeHandler)
    @Bean RequestHandler          // DefaultRequestHandler
    @Bean AgentCard               // A2aAgentCardMapper 投影 AgentCardProvider.describe() 或默认描述符
}
```

所有 Bean 均使用 `@ConditionalOnMissingBean`，允许业务方覆盖任意组件。

## 5. 编码规范

### 5.1 日志

采用 SLF4J + 结构化 key=value 格式：

```java
LOG.info("[A2A] execute start taskId={} sessionId={} agentId={}", taskId, sessionId, agentId);
LOG.error("[A2A] execute failed taskId={} errorClass={} message={}",
        taskId, e.getClass().getSimpleName(), e.getMessage(), e);
```

### 5.2 不可变数据结构

- `RuntimeIdentity`：Java record（不可变）
- `RuntimeComponents`：Java record（不可变）
- `AgentExecutionResult`：final class，工厂方法构造，无 setter
- `AgentExecutionContext.getAgentState()`：返回 `Map.copyOf()` 快照

### 5.3 空值处理

- 必填字段使用 `Objects.requireNonNull()` 或 `Assert.hasText()`
- 可选字段返回 `Optional<>`
- 集合字段返回不可变副本（`List.copyOf()` / `Map.copyOf()`）
