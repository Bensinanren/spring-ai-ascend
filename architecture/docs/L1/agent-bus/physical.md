---
level: L1
module: agent-bus
view: physical
status: draft
---

# agent-bus 物理视图

## 1. 部署平面

`agent-bus` 属于 `bus_state` 部署平面。当前分支只包含 SPI、契约和少量基础测试，不包含完整物理 bus 实现。

| 平面 | 模块 | 与 bus 的关系 |
|---|---|---|
| edge | `agent-client` | 通过 ingress 进入内部，通过 S2C 接收客户端能力调用。 |
| compute_control | `agent-service` | 拥有 Task 生命周期，消费 ingress/S2C/engine 契约。 |
| compute_control | `agent-execution-engine` | 实现或消费 engine SPI。 |
| bus_state | `agent-bus` | 拥有跨边界契约、治理表面和未来 bus runtime 的语义位置。 |

## 2. 当前物理事实

当前代码中的 `agent-bus` 是一个 Maven module。它不依赖 `agent-service`、`agent-execution-engine`、`agent-client`、`agent-middleware` 或 `agent-evolve` 的生产代码。

当前已经存在的物理文件包括：

- `agent-bus/src/main/java/com/huawei/ascend/bus/spi/ingress/**`
- `agent-bus/src/main/java/com/huawei/ascend/bus/spi/s2c/**`
- `agent-bus/src/main/java/com/huawei/ascend/bus/spi/federation/**`
- `agent-bus/src/main/java/com/huawei/ascend/bus/spi/engine/**`
- `agent-bus/src/test/java/**`

## 3. 物理边界

| 边界 | 当前策略 |
|---|---|
| 网络边界 | federation/reflection 仅有 SPI，不选择 broker 或网络协议。 |
| 租户边界 | ingress envelope 已携带 `tenantId`；S2C envelope 目标态需要增加 `tenantId`。 |
| 凭证边界 | 当前没有物理 credential 绑定。 |
| 存储边界 | bus 不拥有 Task state store。 |
| 队列边界 | mailbox/backpressure/tick 仍是设计态。 |
| 注册发现边界 | agent/service/capability 注册发现仍是设计态；未选择存储、健康检查、租户隔离和一致性策略。 |

## 4. S2C tenant 物理影响

给 `S2cCallbackEnvelope` 增加 `tenantId` 后，以下物理或部署相关能力会更稳定：

- 跨 service dispatch。
- 跨网络 federation。
- callback audit。
- DLQ / replay。
- client-side authorization。

但这也是破坏性契约变更，所以必须分离为迁移切片。

## 5. 尚未选择的物理实现

以下内容不属于当前 L1 草案的已实现事实：

- Kafka / NATS / 自研 broker。
- control/data/rhythm 三通道的具体 broker 映射。
- mailbox 存储。
- DLQ 和 replay 存储。
- backpressure runtime。
- tick engine runtime。
- agent/service/capability registry runtime。
- service discovery API。

## 6. Agent 注册发现的物理问题

注册发现进入实现前，至少需要回答：

- 注册表是否持久化。
- 注册信息由谁写入，谁可以删除。
- health / readiness 由 push、pull 还是 lease 表达。
- tenant 隔离如何保证。
- service 与 capability 的版本兼容如何表达。
- region / deployment plane 是否参与路由选择。
- 注册发现是否和 broker topic / route key 绑定。

这些问题没有回答前，不能把注册发现实现为 production runtime。

任何引入这些内容的实现都需要新的 H2/H3 审核。
