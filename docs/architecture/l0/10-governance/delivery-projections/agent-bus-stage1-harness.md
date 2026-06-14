---
artifact_type: a2d_delivery_projection
version: "agent-bus-stage1-harness"
status: draft
source_review_packet: "docs/architecture/l0/10-governance/review-packets/agent-bus-architecture-review-draft.md"
source_l1: "architecture/docs/L1/agent-bus/README.md"
target_module: agent-bus
stage: 1
---

# agent-bus Stage 1 Harness 交付视图

## 1. 交付目标

Stage 1 的目标是给 `agent-bus` 建立第一批低风险、可执行的 harness 证据，验证已经稳定的模块边界和 ingress 契约。

本阶段只覆盖：

- 基础边界 harness。
- Ingress envelope / response harness。

本阶段不覆盖：

- S2C `tenantId` 迁移。
- Federation runtime。
- Reflection typed envelope / schema validator。
- EnginePort terminal event harness。
- Mailbox、admission、backpressure、tick、DLQ、ordering 等运行时 primitives。
- 类 MQ 转发底座。
- agent/service/capability 注册发现。

## 2. 架构边界

| 边界 | Stage 1 要求 |
|---|---|
| 模块边界 | `agent-bus` 生产代码不得依赖 sibling module。 |
| SPI 纯度 | `agent-bus` SPI 包不得引入 Spring、Reactor、Jackson、HTTP framework、broker runtime。 |
| Task 状态 | `agent-bus` 不得写 Task execution state。 |
| Ingress tenant | `IngressEnvelope.tenantId` 必须为 required field，且不能是 blank。 |
| Ingress trace | `IngressEnvelope.traceId` 必须是 32 位小写 hex。 |
| Ingress 幂等 | `IngressEnvelope.idempotencyKey` 必须为 required field。 |
| Ingress response | rejected 必须有非空 reason；accepted/deferred 语义必须可测试。 |

## 3. 开发切片

### Slice 1：模块边界与 SPI 纯度 harness

建议新增测试：

- `agent-bus/src/test/java/com/huawei/ascend/bus/architecture/AgentBusDependencyBoundaryTest.java`
- `agent-bus/src/test/java/com/huawei/ascend/bus/architecture/AgentBusSpiPurityTest.java`

验证内容：

- `agent-bus` 生产类不依赖以下包：
  - `com.huawei.ascend.service..`
  - `com.huawei.ascend.engine..`
  - `com.huawei.ascend.client..`
  - `com.huawei.ascend.middleware..`
  - `com.huawei.ascend.evolve..`
- `com.huawei.ascend.bus.spi..` 不依赖框架和 runtime 技术包：
  - `org.springframework..`
  - `reactor..`
  - `com.fasterxml.jackson..`
  - `io.micrometer..`
  - `io.opentelemetry..`
  - Kafka/NATS/broker 相关包。

实现方式建议：

- 优先复用项目既有测试依赖和测试风格。
- 如果当前 `agent-bus` 没有 ArchUnit 依赖，不要在 Stage 1 直接引入大型依赖；可以先用源码扫描或 classpath 轻量检查。
- 如果必须新增 ArchUnit，需要在提交说明中明确这是 test-scope 依赖，不得进入 production dependency。

完成定义：

- 新增测试能在 `agent-bus` 模块内独立运行。
- 失败信息能指出具体违规类或违规 import。
- 不修改 production code。

### Slice 2：IngressEnvelope harness

建议新增测试：

- `agent-bus/src/test/java/com/huawei/ascend/bus/spi/ingress/IngressEnvelopeTest.java`

验证内容：

- `requestId` 为 null 时失败。
- `tenantId` 为 null 时失败。
- `tenantId` 为空白时失败。
- `idempotencyKey` 为 null 时失败。
- `requestType` 为 null 时失败。
- `payload` 为 null 时失败。
- `traceId` 为 null 时失败。
- `traceId` 长度不是 32 时失败。
- `traceId` 包含大写字母或非 hex 字符时失败。
- `requestAttributes` 为 null 时规范化为空 immutable map。
- `requestAttributes` 构造后被外部修改时，envelope 内部不受影响。

完成定义：

- 正向用例覆盖最小合法 envelope。
- 负向用例覆盖所有 required field 和 trace 格式。
- 测试命名清楚表达契约意图。

### Slice 3：IngressResponse harness

建议新增测试：

- `agent-bus/src/test/java/com/huawei/ascend/bus/spi/ingress/IngressResponseTest.java`

验证内容：

- `requestId` 为 null 时失败。
- `status` 为 null 时失败。
- `REJECTED` 且 `rejectionReason` 为 null 时失败。
- `REJECTED` 且 `rejectionReason` 为空白时失败。
- `accepted(requestId, cursor)` 返回 `ACCEPTED`。
- `rejected(requestId, reason)` 返回 `REJECTED` 并携带 reason。
- `deferred(requestId)` 返回 `DEFERRED`。

待确认项：

- 当前 `IngressResponse.accepted(requestId, cursor)` 没有强制 cursor 非空。Stage 1 只记录现状，不擅自修改 production code。如果架构 owner 要求 accepted + RUN_CREATE 必须有 cursor，应进入后续契约修正切片。

完成定义：

- 测试覆盖三种 response status。
- 不修改 response 生产逻辑，除非 owner 另行批准契约修正。

## 4. 明确禁止范围

Stage 1 施工智能体不得：

- 修改 `S2cCallbackEnvelope` 增加 `tenantId`。
- 修改 `docs/contracts/s2c-callback.v1.yaml`。
- 修改 `agent-service`、`agent-execution-engine`、`agent-client`、`agent-middleware`、`agent-evolve` 的生产代码。
- 给 `agent-bus` 增加任何 production-scope sibling module dependency。
- 实现 broker、runtime bus、mailbox、backpressure、tick、DLQ、ordering。
- 实现类 MQ 转发底座、agent registry 或 service discovery API。
- 把 Gateway 写成 Task lifecycle owner。
- 把测试为了通过而放宽现有 envelope 校验。

如果施工中发现必须触碰以上范围，必须停止并升级给架构 owner。

## 5. 自动化投影计划

| Source fact | 生成/实现对象 | 可写路径 | 是否可自动提交 | 验证 |
|---|---|---|---|---|
| `agent-bus/module-metadata.yaml` forbidden dependencies | 模块边界测试 | `agent-bus/src/test/java/com/huawei/ascend/bus/architecture/**` | 可以 | `agent-bus` test |
| L1 development view SPI purity | SPI 纯度测试 | `agent-bus/src/test/java/com/huawei/ascend/bus/architecture/**` | 可以 | `agent-bus` test |
| `IngressEnvelope` Java record | Envelope 单元测试 | `agent-bus/src/test/java/com/huawei/ascend/bus/spi/ingress/**` | 可以 | `agent-bus` test |
| `IngressResponse` Java record | Response 单元测试 | `agent-bus/src/test/java/com/huawei/ascend/bus/spi/ingress/**` | 可以 | `agent-bus` test |

## 6. 验证命令

首选命令：

```powershell
.\mvnw.cmd -pl agent-bus test
```

如果 Stage 1 增加了跨模块 test-scope 依赖或需要 reactor 编译：

```powershell
.\mvnw.cmd -pl agent-bus -am test
```

验证报告需要记录：

- 使用的 Java 版本。
- Maven 命令。
- 测试通过/失败数量。
- 如失败，失败是否属于环境问题、现有问题或本次变更问题。

## 7. 验证矩阵增量

| Assertion ID | Stage 1 测试 | 必须成立 |
|---|---|---|
| HA-001 | `AgentBusDependencyBoundaryTest` | `agent-bus` production code 不依赖 sibling modules。 |
| HA-001 | `AgentBusSpiPurityTest` | SPI 包不引入框架/runtime 技术依赖。 |
| HA-002 | `IngressEnvelopeTest` | required fields、tenant、trace、idempotency 被校验。 |
| HA-003 | `IngressResponseTest` | response status 和 rejected reason 规则被校验。 |

## 8. 交接给施工智能体的提示

施工智能体应先读：

- `architecture/docs/L1/agent-bus/README.md`
- `architecture/docs/L1/agent-bus/development.md`
- `architecture/docs/L1/agent-bus/spi-appendix.md`
- `docs/architecture/l0/10-governance/review-packets/agent-bus-architecture-review-draft.md`

施工顺序建议：

1. 先补 ingress envelope / response 单元测试。
2. 再补模块边界和 SPI 纯度 harness。
3. 最后运行 `.\mvnw.cmd -pl agent-bus test`。
4. 如果 Java 环境不可用，只提交代码并在结果中明确说明未运行测试的原因。

## 9. 退出条件

Stage 1 完成时应满足：

- 新增 ingress harness。
- 新增基础边界 harness，或明确记录为什么当前阶段只能用轻量扫描替代。
- 不修改 production behavior。
- 不触碰 S2C tenant 迁移。
- 测试命令已运行，或环境阻塞已明确记录。

## 10. 后续阶段候选

Stage 1 完成后，后续不应直接跳到完整 runtime bus 实现。建议拆成：

| 阶段 | 候选范围 | 前置决策 |
|---|---|---|
| Stage 2 | S2C `tenantId` 契约迁移 harness | 完成冲突方通知，确认兼容策略。 |
| Stage 3 | Agent 注册与发现设计/harness | 明确 registry owner、tenant 隔离、health、version、route key 和一致性策略。 |
| Stage 4 | 类 MQ 转发底座设计/harness | 明确队列/主题模型、ack/retry、DLQ/replay、ordering/fairness、backpressure 和 broker 选择。 |
| Stage 5 | Federation/reflection runtime harness | 明确跨网络 credential、routing policy、payload validation 和 failure semantics。 |
