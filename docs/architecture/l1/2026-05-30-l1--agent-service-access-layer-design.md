# 06. agent-service L1 Access Layer

## 1. 职责

- 接收 A2A service 调用和 MQ 入站消息
- 将原始请求归一为 `AccessIntent`
- 通过 L1 `TaskHandler` 调用 L4 `TaskControlClient.runTask`
- 基于 L3 队列接口创建并持有面向用户回消息的队列实例
- 管理运行时连接、订阅和 `EgressBinding`
- 对 L4/L5 暴露 `NotificationPort.notify(frame)`，接收用户可见消息帧
- 通过 A2A / MQ 出站适配器把 `NotificationFrame` 写回外部调用方

---

## 2. 包结构

```text
service/
  access/
    gateway/
      AccessGateway.java
    config/
      AccessLayerConfiguration.java
    model/
      AccessIntent.java
      AccessAcceptedResponse.java
      AccessOperation.java
      ReplyChannel.java
      NotificationFrame.java
      NotificationType.java
      EgressBinding.java
    port/
      TaskHandler.java
      NotificationPort.java
    mq/
      MqIngressQueue.java
      MqEnvelope.java
      MqIngressAdapter.java
      MqEgressAdapter.java
      MqOutputSink.java
    a2a/
      A2aAccessController.java
      A2aWellKnownAgentCardController.java
      A2aAccessService.java
      A2aAgentCardService.java
      A2aAgentCard.java
      A2aAgentSkill.java
      A2aAgentInterface.java
      A2aEnvelope.java
      A2aReplyMode.java
      A2aAcceptedResponse.java
      A2aIngressAdapter.java
      A2aAgentCardAdapter.java
      A2aEgressAdapter.java
      A2aOutput.java
      A2aOutputSink.java
      A2aOutputHandle.java
      A2aOutputRegistry.java
      A2aPushNotificationClient.java
      HttpA2aPushNotificationClient.java
      DefaultA2aOutputSink.java
    egress/
      EgressAdapter.java
      EgressQueueRegistry.java
      DefaultEgressQueueRegistry.java
      DefaultNotificationPort.java
      EgressDispatcher.java
      EgressDeliveryException.java
```

---

## 3. 内部调用接口

```java
// L1 内部任务提交入口，适配 L4 TaskControlClient
public interface TaskHandler {
    CompletionStage<AccessAcceptedResponse> runTask(AccessIntent intent);
}

// L1 暴露给 L4/L5
public interface NotificationPort {
    void notify(NotificationFrame frame);
}
```

| 方法 | 入参 | 返回值 | 描述 |
|---|---|---|---|
| `TaskHandler.runTask` | `AccessIntent intent` | `CompletionStage<AccessAcceptedResponse>` | 调用 L4 `TaskControlClient.runTask`，由 L4 根据 `AccessIntent.operation` 判断提交、恢复、取消或其它操作语义。 |
| `NotificationPort.notify` | `NotificationFrame frame` | `void` | L4/L5 调用该方法，把用户可见消息交给 L1 投递。 |

---

## 4. Egress 接口

```java
public interface EgressAdapter {
    ReplyChannel channel();
    void deliver(EgressBinding binding, NotificationFrame frame);
}

public interface EgressQueueRegistry {
    Queue getOrCreate(EgressBinding binding);
    Optional<Queue> find(String tenantId, String sessionId, String taskId);
    Optional<EgressBinding> findBinding(String tenantId, String sessionId, String taskId);
    void remove(String tenantId, String sessionId, String taskId);
}

public final class EgressDispatcher {
    void start(EgressBinding binding);
    void stop(EgressBinding binding);
}
```

| 方法 | 入参 | 返回值 | 描述 |
|---|---|---|---|
| `EgressAdapter.channel` | 无 | `ReplyChannel` | 声明该出站适配器负责的回消息通道。 |
| `EgressAdapter.deliver` | `EgressBinding binding, NotificationFrame frame` | `void` | 将内部通知帧投递到具体外部通道。 |
| `EgressQueueRegistry.getOrCreate` | `EgressBinding binding` | `Queue` | 按交付绑定获取或通过 L3 `QueueFactory.createQueue` 创建回消息队列。 |
| `EgressQueueRegistry.find` | `tenantId/sessionId/taskId` | `Optional<Queue>` | 查询已有回消息队列。 |
| `EgressQueueRegistry.findBinding` | `tenantId/sessionId/taskId` | `Optional<EgressBinding>` | 查询已有交付绑定，用于调试、观测或后续补充出站状态查询。 |
| `EgressQueueRegistry.remove` | `tenantId/sessionId/taskId` | `void` | terminal 后清理队列。 |
| `EgressDispatcher.start` | `EgressBinding binding` | `void` | 启动该绑定对应队列的消费循环。 |
| `EgressDispatcher.stop` | `EgressBinding binding` | `void` | 停止该绑定对应队列的消费循环。 |

`Queue` 表示 L3 定义的通用队列对象。L1 不重新定义队列能力，也不继承 L3 队列接口；L1 只是通过组合方式持有 L3 `QueueFactory.createQueue(...)` 创建出来的队列实例。`NotificationPort.notify(frame)` 通过 `QueuePublisher.push(frame)` 入队，`EgressDispatcher` 通过 `QueueConsumer.poll(...)` 消费，并依赖 `Map<ReplyChannel, EgressAdapter>` 选择具体出站适配器。

`NotificationPort.notify(frame)` 按 `tenantId + sessionId + taskId` 从 `EgressQueueRegistry` 查询回消息队列。若队列不存在，第一版不自动创建队列，因为自动创建缺少 `replyChannel/deliveryMode/targetRef` 等交付信息；实现应记录投递失败并返回，或按工程约定抛出明确异常。`EgressQueueRegistry` 同时维护 `EgressBinding` 与 `Queue` 的内存索引，第一版不新增独立 `EgressBindingStore`。

---

## 5. 实现类关键方法

```java
public final class AccessGateway {
    AccessIntent acceptA2a(A2aEnvelope envelope);
    AccessIntent acceptMq(MqEnvelope envelope);
    CompletionStage<AccessAcceptedResponse> dispatch(AccessIntent intent);
    EgressBinding bindEgress(AccessIntent intent, AccessAcceptedResponse accepted);
}

public final class A2aIngressAdapter implements A2aAccessService {
    A2aAcceptedResponse send(A2aEnvelope envelope);
    A2aAcceptedResponse resume(A2aEnvelope envelope);
    A2aAcceptedResponse cancel(A2aEnvelope envelope);
}

public final class MqIngressAdapter implements MqIngressQueue {
    void enqueue(MqEnvelope envelope);
}

public final class MqEgressAdapter implements EgressAdapter {
    ReplyChannel channel();
    void deliver(EgressBinding binding, NotificationFrame frame);
}

public final class A2aEgressAdapter implements EgressAdapter {
    ReplyChannel channel();
    void deliver(EgressBinding binding, NotificationFrame frame);
    Object toA2aOutput(NotificationFrame frame);
}
```

| 实现类 | 方法 | 描述 |
|---|---|---|
| `AccessGateway` | `acceptA2a` | 接收 A2A 请求，执行校验和归一化。 |
| `AccessGateway` | `acceptMq` | 接收 MQ 入站消息，执行校验和归一化。 |
| `AccessGateway` | `dispatch` | 将 `AccessIntent` 交给 `TaskHandler.runTask`，并返回内部接收结果。 |
| `AccessGateway` | `bindEgress` | 根据内部接收结果创建 `EgressBinding` 和 L3 回消息队列，并启动该 binding 对应的 `EgressDispatcher` 消费循环。 |
| `A2aIngressAdapter` | `send/resume/cancel` | A2A 对外 service 实现，内部委托给 `AccessGateway`。 |
| `MqIngressAdapter` | `enqueue` | MQ 入站队列实现，内部委托给 `AccessGateway`。 |
| `MqEgressAdapter` | `channel` | 返回 `ReplyChannel.MQ`。 |
| `MqEgressAdapter` | `deliver` | MQ 出站适配器，将通知帧写到 reply topic/queue。 |
| `A2aEgressAdapter` | `channel` | 返回 `ReplyChannel.A2A`。 |
| `A2aEgressAdapter` | `deliver` | A2A 出站适配器，将通知帧写回 A2A 通道。 |
| `A2aEgressAdapter` | `toA2aOutput` | 将 `NotificationFrame` 映射为 A2A 的 TaskStatus、Message、Artifact 或 error 输出。 |

---

## 6. POJO

```java
public enum AccessOperation {
    SUBMIT, RESUME, CANCEL, QUERY, SUBSCRIBE, CALLBACK
}

public enum ReplyChannel {
    A2A, MQ
}

public enum A2aReplyMode {
    SYNC, STREAM, PUSH_NOTIFICATION
}

public record AccessIntent(
    AccessOperation operation,
    String tenantId,
    String userId,
    String agentId,
    String sessionId,
    String query,
    String idempotencyKey,
    Object payload
) {}

public record AccessAcceptedResponse(
    String tenantId,
    String userId,
    String agentId,
    String sessionId,
    String taskId,
    boolean accepted,
    String message
) {}

public enum NotificationType {
    ACK, TOOL_RESULT, LLM_RESULT, ERROR
}

public record EgressBinding(
    String tenantId,
    String sessionId,
    String taskId,
    ReplyChannel replyChannel,
    String deliveryMode,
    String targetRef,
    String correlationId
) {}

public record NotificationFrame(
    String tenantId,
    String sessionId,
    String taskId,
    NotificationType type,
    Object payload,
    boolean terminal
) {}
```

| 类型 | 字段/枚举值 | 描述 |
|---|---|---|
| `AccessOperation` | `SUBMIT` | 提交新任务。 |
| `AccessOperation` | `RESUME` | 恢复已有任务或回传外部结果。 |
| `AccessOperation` | `CANCEL` | 请求取消任务。 |
| `AccessOperation` | `QUERY` | 查询任务或会话状态。 |
| `AccessOperation` | `SUBSCRIBE` | 注册流式或推送订阅。 |
| `AccessOperation` | `CALLBACK` | 外部能力回调结果进入系统。 |
| `ReplyChannel` | `A2A` | 通过 A2A 通道写回。 |
| `ReplyChannel` | `MQ` | 通过 MQ reply topic/queue 写回。 |
| `A2aReplyMode` | `SYNC` | A2A 同步返回；适合短请求或接收确认。 |
| `A2aReplyMode` | `STREAM` | A2A 流式返回；适合 token、状态变化、进度帧。 |
| `A2aReplyMode` | `PUSH_NOTIFICATION` | A2A 异步推送；适合长任务终态、离线或回调式通知。 |
| `AccessIntent` | `operation` | L1 归一后的操作类型。 |
| `AccessIntent` | `tenantId` | 租户标识。 |
| `AccessIntent` | `userId` | 用户标识，用于用户隔离、权限判断和下游记忆隔离。 |
| `AccessIntent` | `agentId` | 目标 Agent 标识，用于下游选择 Agent 或能力。 |
| `AccessIntent` | `sessionId` | 会话标识，可为空。 |
| `AccessIntent` | `query` | 本轮用户请求文本或规范化查询意图；查询/对话类请求建议显式填写。 |
| `AccessIntent` | `idempotencyKey` | 幂等键，可为空。 |
| `AccessIntent` | `payload` | 结构化扩展载荷，例如附件引用、回调结果、控制参数等。 |
| `AccessAcceptedResponse` | `tenantId/userId/agentId/sessionId` | L4 接收请求后确认的运行上下文。 |
| `AccessAcceptedResponse` | `taskId` | L4 创建或定位到的任务标识。 |
| `AccessAcceptedResponse` | `accepted` | 是否已被 L4 接受进入内部处理。 |
| `AccessAcceptedResponse` | `message` | 接收结果说明。 |
| `NotificationType` | `ACK` | 内部处理已接收，用于让 L1 生成接收确认或提交状态。 |
| `NotificationType` | `TOOL_RESULT` | 工具、检索、规划等非 LLM 直接生成的结果或中间结果。 |
| `NotificationType` | `LLM_RESULT` | LLM 生成的文本、结构化内容或流式结果片段。 |
| `NotificationType` | `ERROR` | 内部执行失败或业务错误。 |
| `EgressBinding` | `tenantId/sessionId/taskId` | 定位具体回消息队列和运行上下文。 |
| `EgressBinding` | `replyChannel` | 交付通道类型。 |
| `EgressBinding` | `deliveryMode` | 交付模式；A2A 场景取 `SYNC / STREAM / PUSH_NOTIFICATION`，MQ 场景可取 `MQ`。 |
| `EgressBinding` | `targetRef` | 具体交付目标。 |
| `EgressBinding` | `correlationId` | 外部请求关联标识。 |
| `NotificationFrame` | `type` | 消息帧类型。 |
| `NotificationFrame` | `payload` | 消息内容。 |
| `NotificationFrame` | `terminal` | 是否最后一帧。 |

`NotificationType` 是 L4/L5 给 L1 的内部通知语义，不直接等同于 A2A 或 MQ 的协议类型。L1 出站适配器根据该枚举和 `terminal` 标记决定最终协议输出。后续如需要更细粒度状态，可在该枚举上扩展。

---

## 7. 核心流程

```java
// 入站
A2A service / MQ ingress -> AccessIntent -> TaskHandler.runTask -> TaskControlClient.runTask -> AccessAcceptedResponse -> EgressBinding

// 出站：L4/L5 主动通知用户可见消息
notify(frame) -> L3 Queue.push(NotificationFrame) -> EgressDispatcher -> Queue.poll(...) -> EgressAdapter(A2A / MQ)
```

L3 提供 `QueueFactory.createQueue(...)`、`QueuePublisher.push(...)`、`QueueConsumer.poll(...)`、`QueueManager.findQueueBySession(...)` 这组队列接口。L1 通过 L3 创建并持有回消息队列实例，但不重新定义队列接口，也不要求 L3 提供入队回调。L1 自己的 `EgressDispatcher` 负责消费队列，并按 `EgressBinding.replyChannel` 选择对应的 `EgressAdapter` 投递。

`EgressBinding` 由 `AccessGateway` 在 L4 返回 `AccessAcceptedResponse` 后创建。`taskId` 由 L4 返回，L1 不负责创建 Task；L1 只使用 `tenantId + sessionId + taskId` 绑定 L3 回消息队列和外部回包目标。A2A 场景下，`deliveryMode` 来自 `A2aReplySpec.mode`，`targetRef` 来自 `A2aReplySpec.target` 或运行时连接；MQ 场景下，`deliveryMode` 可取 `MQ`，`targetRef` 来自 MQ `replyTopic`。

`AccessGateway.bindEgress(...)` 创建或获取回消息队列后，调用 `EgressDispatcher.start(binding)` 启动消费。`EgressDispatcher` 消费到 `NotificationFrame.terminal == true` 后，完成最后一次投递，然后调用 `EgressDispatcher.stop(binding)` 并通过 `EgressQueueRegistry.remove(tenantId, sessionId, taskId)` 清理该回消息队列索引。

L1 不解释 `AccessIntent.operation` 的业务语义。`AccessGateway.dispatch(...)` 不按 `SUBMIT / RESUME / CANCEL / QUERY / SUBSCRIBE / CALLBACK` 写分支，只把完整 `AccessIntent` 透传给 `TaskHandler.runTask(...)`；具体如何处理由 L4 `TaskControlClient.runTask` 判断。

---

## 8. A2A 出站映射

`NotificationFrame` 是 L4/L5 返回给 L1 的内部统一消息帧，不等于 A2A 对外输出。`A2aEgressAdapter` 必须先把它转换成 A2A 客户端能理解的协议输出，再写回 A2A 通道。

| `NotificationType` | A2A 输出 | 描述 |
|---|---|---|
| `ACK` | `TaskStatus(SUBMITTED)` 或接收确认 `Message` | 表示请求已进入内部处理。 |
| `TOOL_RESULT` | `Artifact` 或 `Message` | 表示工具、检索、规划等结果；结构化结果优先映射为 `Artifact`。 |
| `LLM_RESULT` | `Message` 或增量 `Artifact` | 表示 LLM 输出；流式片段可映射为增量 `Artifact`。 |
| `ERROR` | A2A error 或 `TaskStatus(FAILED)` + error `Message` | 表示执行失败；如果已经建立 task，则同时更新 task 状态。 |

流式场景下，L1 不应该把每个 `NotificationFrame` 都直接包装成最终 `Message`。更合理的方式是：`LLM_RESULT` 和 `TOOL_RESULT` 的中间输出可映射为增量 `Artifact`；当 `frame.terminal == true` 时，由 `A2aEgressAdapter` 发送 A2A complete/fail/cancel 语义，结束该 task 的输出流。

同步场景下，`A2aEgressAdapter` 可以聚合多个 `NotificationFrame`，最终返回一个 `Message` 或终态 `TaskStatus`。如果中间帧包含结构化产物，仍应按 `Artifact` 语义保留，避免把文件、表格、工具结果全部压成纯文本。

---

## 9. 对外暴露接口

L1 对外只暴露两种访问方式：

1. A2A service
2. MQ 入站队列

另外，L1 可以暴露 A2A Agent Card 查询接口，用于服务发现、能力声明和调用约束说明。Agent Card 不是第三种业务调用入口；外部读取 card 后，真正提交请求仍然走 A2A service。外部调用方不能直接调用 L4/L5，也不能直接读写 L1 持有的 L3 回消息队列。外部入口报文不是 `AccessIntent`；`AccessIntent` 是 L1 内部归一化后的对象。

### 9.1 A2A Service

```java
public interface A2aAccessService {
    A2aAcceptedResponse send(A2aEnvelope envelope);
    A2aAcceptedResponse resume(A2aEnvelope envelope);
    A2aAcceptedResponse cancel(A2aEnvelope envelope);
}
```

| 方法 | 入参 | 返回值 | 描述 |
|---|---|---|---|
| `send` | `A2aEnvelope envelope` | `A2aAcceptedResponse` | A2A 外部请求入口；L1 归一为 `AccessIntent(SUBMIT)`。 |
| `resume` | `A2aEnvelope envelope` | `A2aAcceptedResponse` | A2A 恢复或回调结果入口；L1 归一为 `AccessIntent(RESUME/CALLBACK)`。 |
| `cancel` | `A2aEnvelope envelope` | `A2aAcceptedResponse` | A2A 取消入口；L1 归一为 `AccessIntent(CANCEL)`。 |

第一版 HTTP 暴露路径：

| HTTP 方法 | 路径 | 请求体 | 响应体 | 描述 |
|---|---|---|---|---|
| `POST` | `/a2a/tasks/send` | `A2aEnvelope` | `A2aAcceptedResponse` | 新任务提交入口。 |
| `POST` | `/a2a/tasks/resume` | `A2aEnvelope` | `A2aAcceptedResponse` | 恢复或回调结果入口。 |
| `POST` | `/a2a/tasks/cancel` | `A2aEnvelope` | `A2aAcceptedResponse` | 取消入口。 |
| `GET` | `/a2a/agent-card` | 无 | `A2aAgentCard` | 查询当前 Agent Card。 |
| `GET` | `/.well-known/agent-card.json` | 无 | `A2aAgentCard` | 标准发现路径，返回同一个 Agent Card。 |
| `GET` | `/a2a/tasks/{tenantId}/{sessionId}/{taskId}/outputs` | 无 | `List<A2aOutput>` | 查询同步或已缓存的 A2A 出站输出。 |
| `GET` | `/a2a/tasks/{tenantId}/{sessionId}/{taskId}/stream` | 无 | SSE stream | 订阅流式 A2A 出站输出。 |

HTTP 请求头第一版只要求：

| 请求头 | 是否必填 | 描述 |
|---|---|---|
| `Content-Type: application/json` | 是 | A2A 请求体使用 JSON。 |
| `Accept: application/json` | 否 | 期望返回 JSON。 |

鉴权、租户网关头、签名头后续由平台接入层统一补齐；当前 L1 入口先从 `A2aEnvelope.context` 读取租户、用户、Agent、会话和幂等信息。

`send/resume/cancel` 第一版都返回接收确认 `A2aAcceptedResponse`，不在方法返回值里承载完整最终结果。同步、流式和异步 push 的用户可见输出统一由 `NotificationPort.notify(frame)` 进入 L1 出站链路，再由 `A2aEgressAdapter` 按 `A2aReplyMode` 和 A2A 协议语义投递。

```java
public record A2aEnvelope(
    A2aContext context,
    A2aMessage message,
    A2aReplySpec reply
) {
    public record A2aContext(
        String tenantId,
        String userId,
        String agentId,
        String sessionId,
        String contextId,
        String idempotencyKey,
        String correlationId
    ) {}

    public record A2aMessage(
        String text,
        List<Object> parts,
        Map<String, Object> metadata
    ) {}

    public record A2aReplySpec(
        A2aReplyMode mode,
        String target
    ) {}
}

public record A2aAcceptedResponse(
    String tenantId,
    String userId,
    String agentId,
    String sessionId,
    String taskId,
    boolean accepted,
    String message
) {}
```

A2A 外部请求体示例：

```json
{
  "context": {
    "tenantId": "tenant-001",
    "userId": "user-001",
    "agentId": "travel-agent",
    "sessionId": "session-123",
    "contextId": "ctx-001",
    "idempotencyKey": "idem-001",
    "correlationId": "corr-001"
  },
  "message": {
    "text": "帮我规划一个三天上海行程",
    "parts": [],
    "metadata": {}
  },
  "reply": {
    "mode": "STREAM",
    "target": null
  }
}
```

三种 A2A 回消息模式的处理：

| `reply.mode` | `reply.target` | L1 行为 |
|---|---|---|
| `SYNC` | 可为空 | `A2aOutputSink` 将输出写入 `A2aOutputRegistry`，调用方可通过 `/outputs` 查询最终或已产生的输出。 |
| `STREAM` | 可为空 | `A2aOutputSink` 将输出写入 `A2aOutputRegistry`，同时 `/stream` SSE 订阅者会收到增量 `A2aOutput`。 |
| `PUSH_NOTIFICATION` | 必填，外部 HTTP 回调地址 | `A2aOutputSink` 将输出写入 `A2aOutputRegistry`，并通过 `A2aPushNotificationClient` HTTP POST 到 `target`。 |

`target` 是 A2A 出站目标。`STREAM` 和 `SYNC` 当前使用 L1 内部输出 registry 承接，`PUSH_NOTIFICATION` 使用外部回调地址承接。L1 内部用 `EgressBinding.deliveryMode` 保存三种回消息模式，用 `EgressBinding.targetRef` 保存具体出站目标，避免把模式和目标混在同一个字段里。无论哪种模式，L4/L5 都只写 `NotificationPort.notify(frame)`，不直接感知 A2A 出站细节。

A2A 接收确认响应示例：

```json
{
  "tenantId": "tenant-001",
  "userId": "user-001",
  "agentId": "travel-agent",
  "sessionId": "session-123",
  "taskId": "task-001",
  "accepted": true,
  "message": "Accepted"
}
```

A2A 到内部对象的映射：

```java
AccessIntent(
    operation = SUBMIT,
    tenantId = envelope.context.tenantId,
    userId = envelope.context.userId,
    agentId = envelope.context.agentId,
    sessionId = envelope.context.sessionId,
    query = envelope.message.text,
    idempotencyKey = envelope.context.idempotencyKey,
    payload = Map.of(
        "parts", envelope.message.parts,
        "metadata", envelope.message.metadata,
        "reply", envelope.reply,
        "contextId", envelope.context.contextId,
        "correlationId", envelope.context.correlationId
    )
)
```

| 类型 | 字段 | 描述 |
|---|---|---|
| `A2aEnvelope` | `context` | A2A 调用上下文，承载租户、Agent、会话、幂等和关联信息。 |
| `A2aEnvelope` | `message` | 用户消息主体。 |
| `A2aEnvelope` | `reply` | A2A 回消息模式要求。 |
| `A2aContext` | `tenantId` | 租户标识。 |
| `A2aContext` | `userId` | 用户标识，用于用户隔离、权限判断和记忆隔离。 |
| `A2aContext` | `agentId` | 目标 Agent 标识。 |
| `A2aContext` | `sessionId` | 会话标识，可为空。 |
| `A2aContext` | `contextId` | A2A 上下文标识，用于承接协议侧 context 维度；可与内部 sessionId 不同。 |
| `A2aContext` | `idempotencyKey` | 幂等键。 |
| `A2aContext` | `correlationId` | 外部关联标识。 |
| `A2aMessage` | `text` | 用户请求文本或规范化查询意图。 |
| `A2aMessage` | `parts` | 多模态片段、附件引用或扩展消息块。 |
| `A2aMessage` | `metadata` | A2A 消息元数据。 |
| `A2aReplySpec` | `mode` | A2A 回消息模式：同步、流式或异步 push。 |
| `A2aAcceptedResponse` | `tenantId/userId/agentId/sessionId` | A2A 接收确认中的运行上下文，来自 `AccessAcceptedResponse`。 |
| `A2aAcceptedResponse` | `taskId` | A2A 接收确认中的任务标识，来自 `AccessAcceptedResponse`。 |
| `A2aAcceptedResponse` | `accepted` | 是否已被 L1 接受进入内部处理。 |
| `A2aAcceptedResponse` | `message` | 接收结果说明。 |

`A2aAcceptedResponse` 是 A2A 协议侧确认响应，由 `A2aIngressAdapter` 根据内部 `AccessAcceptedResponse` 转换生成。MQ 入站不依赖 `A2aAcceptedResponse`。

### 9.2 A2A Agent Card

```java
public interface A2aAgentCardService {
    A2aAgentCard getAgentCard();
}
```

| 方法 | 入参 | 返回值 | 描述 |
|---|---|---|---|
| `getAgentCard` | 无 | `A2aAgentCard` | 返回当前 Agent 的 A2A 能力声明、入口地址、输入输出模式和安全要求。 |

```java
public record A2aAgentCard(
    String name,
    String description,
    String url,
    String version,
    String documentationUrl,
    String provider,
    List<String> defaultInputModes,
    List<String> defaultOutputModes,
    List<A2aAgentSkill> skills,
    List<A2aAgentInterface> interfaces,
    String preferredTransport,
    Map<String, Object> securitySchemes,
    List<Map<String, List<String>>> security,
    Map<String, Object> metadata
) {}

public record A2aAgentSkill(
    String id,
    String name,
    String description,
    List<String> inputModes,
    List<String> outputModes,
    Map<String, Object> metadata
) {}

public record A2aAgentInterface(
    String transport,
    String url,
    Map<String, Object> metadata
) {}
```

A2A Agent Card 示例：

```json
{
  "name": "travel-agent",
  "description": "面向出行规划的 Agent",
  "url": "https://agent.example.com/a2a",
  "version": "1.0.0",
  "documentationUrl": "https://agent.example.com/docs",
  "provider": "spring-ai-ascend",
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text", "artifact"],
  "skills": [
    {
      "id": "travel-plan",
      "name": "旅行规划",
      "description": "生成行程、预算和交通建议",
      "inputModes": ["text"],
      "outputModes": ["text", "artifact"],
      "metadata": {}
    }
  ],
  "interfaces": [
    {
      "transport": "JSON-RPC",
      "url": "https://agent.example.com/a2a",
      "metadata": {}
    }
  ],
  "preferredTransport": "JSON-RPC",
  "securitySchemes": {},
  "security": [],
  "metadata": {}
}
```

| 类型 | 字段 | 描述 |
|---|---|---|
| `A2aAgentCard` | `name` | Agent 对外名称。 |
| `A2aAgentCard` | `description` | Agent 能力描述。 |
| `A2aAgentCard` | `url` | 默认 A2A 调用地址。 |
| `A2aAgentCard` | `version` | Agent Card 或服务版本。 |
| `A2aAgentCard` | `documentationUrl` | 文档地址，可为空。 |
| `A2aAgentCard` | `provider` | 服务提供方标识。 |
| `A2aAgentCard` | `defaultInputModes` | 默认支持的输入模式，例如 `text`。 |
| `A2aAgentCard` | `defaultOutputModes` | 默认支持的输出模式，例如 `text`、`artifact`。 |
| `A2aAgentCard` | `skills` | Agent 对外声明的能力列表。 |
| `A2aAgentCard` | `interfaces` | 可用 A2A 传输接口列表。 |
| `A2aAgentCard` | `preferredTransport` | 推荐调用方优先使用的传输协议。 |
| `A2aAgentCard` | `securitySchemes/security` | 认证和授权要求。 |
| `A2aAgentCard` | `metadata` | 扩展元数据。 |
| `A2aAgentSkill` | `id/name/description` | 能力标识、名称和说明。 |
| `A2aAgentSkill` | `inputModes/outputModes` | 该能力支持的输入输出模式。 |
| `A2aAgentInterface` | `transport/url` | 具体传输协议和调用地址。 |

默认实现可以提供 `/.well-known/agent-card.json` 这类查询入口，便于标准 A2A 客户端或注册中心发现服务。后续如果接入 Nacos 等注册中心，也应该发布这个 card，而不是让注册中心直接感知 L1 内部对象。

### 9.3 MQ 入站队列

```java
public interface MqIngressQueue {
    void enqueue(MqEnvelope envelope);
}
```

| 方法 | 入参 | 返回值 | 描述 |
|---|---|---|---|
| `enqueue` | `MqEnvelope envelope` | `void` | 外部系统将 MQ 消息投递给 L1；L1 消费后归一为 `AccessIntent`。 |

```java
public record MqEnvelope(
    MqHeaders headers,
    MqBody body
) {
    public record MqHeaders(
        String tenantId,
        String userId,
        String agentId,
        String sessionId,
        AccessOperation operation,
        String idempotencyKey,
        String correlationId,
        String replyTopic
    ) {}

    public record MqBody(
        String query,
        Object payload
    ) {}
}
```

MQ 外部消息体示例：

```json
{
  "headers": {
    "tenantId": "tenant-001",
    "userId": "user-001",
    "agentId": "travel-agent",
    "sessionId": "session-123",
    "operation": "SUBMIT",
    "idempotencyKey": "idem-001",
    "correlationId": "corr-001",
    "replyTopic": "agent.reply.tenant-001"
  },
  "body": {
    "query": "帮我规划一个三天上海行程",
    "payload": {}
  }
}
```

MQ 到内部对象的映射：

```java
AccessIntent(
    operation = envelope.headers.operation,
    tenantId = envelope.headers.tenantId,
    userId = envelope.headers.userId,
    agentId = envelope.headers.agentId,
    sessionId = envelope.headers.sessionId,
    query = envelope.body.query,
    idempotencyKey = envelope.headers.idempotencyKey,
    payload = Map.of(
        "payload", envelope.body.payload,
        "replyTopic", envelope.headers.replyTopic,
        "correlationId", envelope.headers.correlationId
    )
)
```

| 类型 | 字段 | 描述 |
|---|---|---|
| `MqEnvelope` | `headers` | MQ 路由和控制信息。 |
| `MqEnvelope` | `body` | MQ 业务内容。 |
| `MqHeaders` | `tenantId` | 租户标识。 |
| `MqHeaders` | `userId` | 用户标识，用于用户隔离、权限判断和记忆隔离。 |
| `MqHeaders` | `agentId` | 目标 Agent 标识。 |
| `MqHeaders` | `sessionId` | 会话标识，可为空。 |
| `MqHeaders` | `operation` | MQ 消息对应的操作类型。 |
| `MqHeaders` | `idempotencyKey` | 幂等键。 |
| `MqHeaders` | `correlationId` | 外部关联标识。 |
| `MqHeaders` | `replyTopic` | MQ 回消息目标 topic/queue。 |
| `MqBody` | `query` | 用户请求文本或规范化查询意图。 |
| `MqBody` | `payload` | 结构化扩展载荷。 |

---

## 10. 落地完整性与边界

当前 L1 方案已经可以作为后续代码落地依据，核心对象、接口、抽象和目录层级可以一一对齐。实现时需要守住以下边界，避免把其它层职责提前放进 L1。

### 10.1 必须保持的实现约束

| 约束 | 说明 |
|---|---|
| 外部入口只允许 A2A service 和 MQ 入站队列 | 外部调用方不能直接传 `AccessIntent`，也不能直接访问 L3 回消息队列。 |
| `AccessIntent` 只作为 L1 内部归一化对象 | A2A / MQ 原始请求先由 L1 适配，再进入内部层。 |
| `taskId` 必须来自 L4 | L1 不创建 Task，不判断 Task 执行阶段，只使用 `AccessAcceptedResponse` 返回的标识建立回消息绑定。 |
| 回消息队列必须由 L1 持有 | L1 通过 L3 `QueueFactory.createQueue(...)` 创建 `Queue`，并由 `EgressQueueRegistry` 管理生命周期。 |
| L4/L5 只通过 `NotificationPort.notify(frame)` 回消息 | L4/L5 不关心 A2A / MQ 出站细节，也不直接操作 L3 队列。 |
| `NotificationFrame.terminal` 负责表达结束帧 | `NotificationType` 只表达消息语义，是否结束由 `terminal` 单独表示。 |
| A2A 出站必须做协议映射 | `NotificationFrame` 不能直接暴露给 A2A 客户端，必须映射为 `TaskStatus / Message / Artifact / error / terminal` 等 A2A 语义。 |
| `AccessOperation` 必须透传给 L4 | L1 不解释操作语义，`AccessGateway.dispatch` 统一调用 `TaskHandler.runTask(intent)`。 |
| `NotificationPort.notify` 不自动创建队列 | 队列创建依赖 `EgressBinding` 的交付信息；缺失队列时应记录投递失败或抛出明确异常。 |

### 10.2 当前不需要继续增加的抽象

| 不增加的抽象 | 原因 |
|---|---|
| 统一入站 `AccessAdapter` 抽象 | A2A service 和 MQ 队列的调用模型不同，强行统一会让入口更绕；当前用 `A2aIngressAdapter` 和 `MqIngressAdapter` 更清晰。 |
| 独立 A2A 出站映射类 | 当前映射复杂度还可以放在 `A2aEgressAdapter.toA2aOutput(frame)`；后续 A2A 输出规则明显变复杂时再拆。 |
| L1 自定义队列接口或继承 L3 队列 | 队列能力由 L3 统一提供，L1 只组合持有 `Queue`，避免重复定义队列语义。 |
| L3 入队回调机制 | L1 可以由 `EgressDispatcher` 主动消费队列，不要求 L3 在每次入队时触发回调。 |
| 额外请求标识字段 | 当前 `correlationId + idempotencyKey + taskId` 已能覆盖外部关联、幂等和内部任务定位。 |
| `artifactId / sequence / metadata` 作为 `NotificationFrame` 必填字段 | 这些更适合由 L1 出站适配阶段补齐，避免要求 L4/L5 构造协议细节。 |

### 10.3 代码落地时的最小实现顺序

1. 先实现 `model` 包下的 POJO/枚举，以及 `port` 包下的内部调用端口。
2. 再实现 `A2aIngressAdapter`，保证 A2A 请求可以归一为 `AccessIntent`。
3. 再实现 `TaskHandler`，直接用 `AccessIntent` 调用 L4 `TaskControlClient.runTask`。
4. 再实现 `AccessGateway.dispatch`，将 `AccessIntent` 交给 `TaskHandler.runTask` 并接收 `AccessAcceptedResponse`。
5. 再实现 `EgressQueueRegistry`，通过 L3 `QueueFactory.createQueue(...)` 创建和索引 `Queue`，并维护 `EgressBinding` 与队列的内存映射。
6. 再实现 `AccessGateway.bindEgress`，创建/获取队列后启动 `EgressDispatcher.start(binding)`。
7. 再实现 `NotificationPort.notify(frame)`，按 `tenantId + sessionId + taskId` 查找队列，并通过 `QueuePublisher.push(frame)` 写入 L3 队列。
8. 最后实现 `EgressDispatcher`、`A2aEgressAdapter` 和 `MqEgressAdapter`，通过 `QueueConsumer.poll(...)` 完成出站交付，并在 terminal 后停止消费和清理队列索引。

### 10.4 L3 队列接口对齐

L1 当前只假设 L3 提供 `Queue`、`QueueFactory`、`QueuePublisher`、`QueueConsumer` 和 `QueueManager`。后续写代码时，`EgressQueueRegistry` 只适配这组 L3 队列接口；其它 L1 对象和接口不应该因为 L3 底层是内存队列、Redis、开源 MQ 或其它实现而变化。

---

## 11. 备注：文件职责说明

| 文件 | 职责 |
|---|---|
| `model/AccessIntent.java` | L1 归一后的内部请求对象，屏蔽 A2A / MQ 原始协议差异。 |
| `model/AccessAcceptedResponse.java` | L4 接收 `AccessIntent` 后返回给 L1 的内部接收结果，包含 task 标识。 |
| `model/AccessOperation.java` | 定义 `SUBMIT / RESUME / CANCEL / QUERY / SUBSCRIBE / CALLBACK` 等入口操作。 |
| `model/ReplyChannel.java` | 定义用户回消息通道类型，例如 A2A 响应、MQ reply、推送通道。 |
| `model/NotificationFrame.java` | L4/L5 返回给用户的统一消息帧，包含内部通知语义、载荷和 terminal 标记。 |
| `model/NotificationType.java` | 定义内部通知语义枚举，当前包含 `ACK / TOOL_RESULT / LLM_RESULT / ERROR`。 |
| `model/EgressBinding.java` | L1 内部交付路由，记录消息应投递到哪个外部目标。 |
| `port/TaskHandler.java` | L1 内部任务处理入口，签名对齐 L4 `AccessIntent / AccessAcceptedResponse`，并调用 `TaskControlClient`。 |
| `port/NotificationPort.java` | L1 暴露给 L4/L5 的通知端口；内部层通过它把用户消息交回 L1。 |
| `MqIngressQueue.java` | L1 对外暴露的 MQ 入站队列接口。 |
| `MqEnvelope.java` | MQ 外部入口消息信封，内部包含 `MqHeaders` 和 `MqBody`。 |
| `MqIngressAdapter.java` | MQ 入站适配器，把 `MqEnvelope` 转换为 `AccessIntent`。 |
| `A2aAccessService.java` | L1 对外暴露的 A2A service 接口。 |
| `A2aAgentCardService.java` | L1 对外暴露的 A2A Agent Card 查询接口，用于服务发现和能力声明。 |
| `A2aAgentCard.java` | A2A Agent Card 对象，声明 Agent 名称、能力、入口、传输和安全要求。 |
| `A2aAgentSkill.java` | A2A Agent Card 中的能力声明对象。 |
| `A2aAgentInterface.java` | A2A Agent Card 中的传输接口声明对象。 |
| `A2aEnvelope.java` | A2A 外部入口消息信封，内部包含 `A2aContext`、`A2aMessage` 和 `A2aReplySpec`。 |
| `A2aReplyMode.java` | A2A 回消息模式枚举，支持同步、流式和异步 push。 |
| `A2aAcceptedResponse.java` | A2A 接收确认响应对象。 |
| `A2aIngressAdapter.java` | A2A service 适配器，把 `A2aEnvelope` 转换为 `AccessIntent`。 |
| `A2aAgentCardAdapter.java` | A2A Agent Card 查询适配器，返回当前 Agent 的对外能力声明。 |
| `EgressAdapter.java` | 出站适配器 SPI，统一 A2A / MQ 等回消息通道的投递接口。 |
| `EgressQueueRegistry.java` | 按 `tenantId + sessionId + taskId` 管理和查找 L3 回消息队列实例。 |
| `DefaultEgressQueueRegistry.java` | `EgressQueueRegistry` 默认实现，维护 `EgressBinding` 与回消息队列的内存索引。 |
| `EgressDispatcher.java` | 消费 L3 回消息队列，并根据 `EgressBinding.replyChannel` 选择 `EgressAdapter`。 |
| `DefaultNotificationPort.java` | `NotificationPort` 默认实现，按 `tenantId + sessionId + taskId` 查找队列并写入 `NotificationFrame`。 |
| `EgressDeliveryException.java` | L1 出站投递失败异常，用于表达缺少队列、通道适配器或队列内容类型错误。 |
| `MqEgressAdapter.java` | `EgressAdapter` 的 MQ 实现，把 `NotificationFrame` 写到 MQ reply topic/queue。 |
| `MqOutputSink.java` | MQ 出站真实发送端口，后续由具体 MQ 客户端实现。 |
| `A2aAccessController.java` | A2A HTTP 控制器，暴露任务提交、恢复、取消、Agent Card 查询、输出查询和 SSE 订阅入口。 |
| `A2aWellKnownAgentCardController.java` | A2A 标准发现控制器，暴露 `/.well-known/agent-card.json`。 |
| `A2aEgressAdapter.java` | `EgressAdapter` 的 A2A 实现，把 `NotificationFrame` 写回 A2A 响应或 A2A 侧订阅通道。 |
| `A2aOutput.java` | A2A 出站统一输出对象，承载 TaskStatus、Message、Artifact 或 error 映射结果。 |
| `A2aOutputSink.java` | A2A 出站发送端口，由默认实现统一承接 `SYNC / STREAM / PUSH_NOTIFICATION`。 |
| `A2aOutputHandle.java` | A2A 出站输出索引键，按 `tenantId + sessionId + taskId` 定位输出。 |
| `A2aOutputRegistry.java` | A2A 出站输出注册表，支持同步查询和 SSE 订阅。 |
| `A2aPushNotificationClient.java` | A2A push notification 发送端口。 |
| `HttpA2aPushNotificationClient.java` | 基于 HTTP POST 的 A2A push notification 默认实现。 |
| `DefaultA2aOutputSink.java` | A2A 出站默认实现，按 `A2aReplyMode` 分发到查询缓存、SSE 订阅或 HTTP push。 |
| `gateway/AccessGateway.java` | L1 主入口编排器，协调入站归一、egress binding 建立和 L4 调用。 |
| `config/AccessLayerConfiguration.java` | L1 Spring 装配类，注册 access gateway、ingress adapter、notification port、egress dispatcher 和临时队列工厂；依赖 `TaskHandler` 的入口 Bean 在 L4 提供后才装配。 |

---

## 12. 备注：代码结构与设计对象对齐

| 设计对象 | 代码位置 | 对齐说明 |
|---|---|---|
| 外部 A2A 入口 | `service/access/a2a/A2aAccessService.java` + `A2aIngressAdapter.java` | 只负责接收 `A2aEnvelope` 并转换为内部 `AccessIntent`。 |
| A2A 能力发现 | `service/access/a2a/A2aAgentCardService.java` + `A2aAgentCardAdapter.java` | 只返回对外能力和调用约束，不进入业务执行流程。 |
| 外部 MQ 入口 | `service/access/mq/MqIngressQueue.java` + `MqIngressAdapter.java` | 只负责接收 `MqEnvelope` 并转换为内部 `AccessIntent`。 |
| 内部归一化请求 | `service/access/model/AccessIntent.java` | A2A / MQ 入口最终都必须生成同一个对象。 |
| 内部接收结果 | `service/access/model/AccessAcceptedResponse.java` | L4 返回 task 标识后，L1 基于它创建 `EgressBinding`。 |
| L4 控制入口 | `service/access/port/TaskHandler.java` | L1 通过该入口调用 `TaskControlClient`，方法签名对齐 L4 `AccessIntent / AccessAcceptedResponse`。 |
| 用户回消息入口 | `service/access/port/NotificationPort.java` | L4/L5 只通过该端口把消息交还给 L1。 |
| 回消息队列 | L3 `Queue` | 由 L3 定义和创建，L1 通过 `EgressQueueRegistry` 持有和索引，外部不能直接访问。 |
| 回消息路由 | `service/access/model/EgressBinding.java` | 记录消息写回 A2A / MQ 的目标。 |
| 出站适配 SPI | `service/access/egress/EgressAdapter.java` | 统一出站投递接口，供 `EgressDispatcher` 按 `ReplyChannel` 选择实现。 |
| A2A 出站适配 | `service/access/a2a/A2aEgressAdapter.java` | 实现 `EgressAdapter`，根据 `EgressBinding` 把 `NotificationFrame` 投递到 A2A 通道。 |
| A2A 出站映射 | `service/access/a2a/A2aEgressAdapter.java` | 负责把内部通知帧转换为 A2A 协议语义。后续如果映射复杂，再拆出独立 mapper。 |
| MQ 出站适配 | `service/access/mq/MqEgressAdapter.java` | 实现 `EgressAdapter`，根据 `EgressBinding` 把 `NotificationFrame` 投递到 MQ reply topic/queue。 |

