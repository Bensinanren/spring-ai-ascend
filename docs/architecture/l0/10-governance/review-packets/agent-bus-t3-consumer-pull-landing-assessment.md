---
artifact_type: landing_assessment
version: agent-bus-t3-consumer-pull-landing-assessment
status: draft
source_commit: d511088e
source_transport_candidates: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-transport-candidates.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
source_icd_runtime: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md
source_l2: architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md
target_module: agent-bus (+ agent-runtime 跨模块影响)
---

# agent-bus T3 consumer-pull over DB 落地影响面评估（为 H2/H3 裁决备料）

## 0. 结论摘要（给 H2/H3 的 TL;DR）

[`transport-candidates`](agent-bus-forwarding-runtime-transport-candidates.md) 把 T3 列为「**强反压 + 不破 §6.2 + 低复杂度**」的非裁决推荐，代价标注为「dispatcher 归属从 sender 转到 receiver」。本评估在 Stage 15–21 落地后的**真实代码**上深挖 T3 落地影响面，得到一个对 H2/H3 裁决关键的补充结论：

> **T3 的落地代价主要不在持久层（持久层复用度高），而在 transport-candidates 未充分展开的两处工程现实：① 生产 scheduler 从无到有；② 模块依赖方向反转。两者都撞已记录的 deferred 护栏，需 H2/H3 显式裁决解除——同 Stage 12 解除「路径 B」的性质，不是工程决定，是架构治理决定。**

具体：

1. **持久层复用度高（成立）**：`claimDue` + `SKIP LOCKED` + lease-guarded mutation + RLS + retry policy 在 T3 下基本零改或小改。outbox 表已有 `target_service_id` 字段（[`V1 DDL`](../../../../../agent-bus/src/main/resources/db/migration/V1__create_agent_bus_forwarding_outbox_inbox.sql)），T3a 只需给 `claimDue` 加一行 `target_service_id` 过滤；T3b 把 outbox 的 lease-safe 模式（Stage 9）移植到 inbox。三条已验证生命线（retry 往返 / 租约回收 / 断路器，Stages 19–21）在 T3 下多数成立，且**断路器在 T3 下可退化甚至移除**（receiver 自调速 = 天然熔断）——这是 T3 的一个净收益。

2. **真正阻塞 ① —— scheduler 从无到有（撞 [`decision §6.1`](agent-bus-forwarding-runtime-decision.md) 第 3 项 + §8 deferred）**：`ForwardingDispatchLoop` 的 `TickSource` 在**生产代码零驱动**——所有使用点都在 `src/test`，生产代码无 `@Scheduled` / `ScheduledExecutorService`（代码实证见 §6.1）。T1 push 下这个问题被 PoC 测试驱动掩盖了；T3 的 receiver pull worker **必须有**真实 poll cadence 驱动，无法回避。

3. **真正阻塞 ② —— 模块依赖方向反转（撞 `AgentBusDependencyBoundaryTest` 守卫）**：T3 要 receiver（`agent-runtime`）访问持久层（outbox 或 inbox）。当前 `agent-runtime` 生产**零依赖** `agent-bus`，且有 `bus_does_not_depend_on_agent_runtime` 守卫（反向也隐含隔离）。T3 必须在三解法中择一：共享 DB schema / 反向暴露查询 API / 持久层下沉为公共依赖（见 §6.2）。

4. **inbox 当前闲置（MI13-R「receiver 端缺失」的实证）**：`JdbcForwardingInbox.receive` 在生产链路无调用点——T1 push 投递走 HTTP 直达（`A2aForwardingDeliveryPort` → agent-runtime `/a2a`），不经 inbox 表。inbox 是为 receiver 侧消费预留的去重+状态表，T3b 正是激活它的路径。

**H2/H3 裁决 T3 时，不能只裁决「投递模型选 pull」，必须同时裁决 §6.1 第 3 项 scheduler 解除 + 模块依赖边界方案**——否则裁决无法施工。本评估的施工智能体在 H2/H3 裁决前不写 T3 生产代码（性质同 Stage 13）。

## 1. 评估目的与边界

- **目的**：在 [`transport-candidates`](agent-bus-forwarding-runtime-transport-candidates.md) 的非裁决推荐（T3）基础上，深挖 T3 **真要落地**会遇到的影响面，为 H2/H3 的 push / pull / MQ 最终裁决提供工程级输入。
- **性质**：备料 / 评审，**不写生产代码**（裁决阶段，性质同 Stage 13 / Stage 5）。
- **不裁决**：T3 是否最终采用、T3a vs T3b 选哪个、scheduler / 模块边界方案——均留给 H2/H3。
- **禁止范围**：不解除 [`decision §6.2`](agent-bus-forwarding-runtime-decision.md)（始终不得）；不绑定 broker / MQ 产品；T3 不引 MQ（§6.2 不破），但触及 §6.1 第 3 项与模块边界，本评估**标注需 H2/H3 解除**，不自行解除。

## 2. T1 起点：当前物理拓扑基线（代码证据）

T3 迁移的起点是 Stage 15–21 落地后的 T1 push 实态：

| 维度 | T1 实态 | 代码证据 |
|---|---|---|
| sender | `agent-bus`，`ForwardingDispatcherWorker.runOnce` claim → lease renew → breaker → `deliveryPort.deliver` → `recordOutcome` → switch outcome | `agent-bus/.../forwarding/runtime/ForwardingDispatcherWorker.java`（runOnce） |
| dispatcher 驱动 | `ForwardingDispatchLoop` 注入 `TickSource` / `IdleStrategy`，**生产零驱动**，所有使用点在 `src/test` | §6.1 详述 |
| receiver | `agent-runtime`，`A2aJsonRpcController @PostMapping("/a2a")` 纯被动 HTTP server；**无 inbox / forwarding / poll / subscribe 任何代码** | `agent-runtime/.../boot/A2aJsonRpcController.java` |
| 投递通道 | 跨进程 HTTP：`A2aForwardingDeliveryPort` 同步阻塞 POST → receiver `/a2a`，等 Task 终态才 ACKED | `agent-bus/.../transport/a2a/A2aForwardingDeliveryPort.java` |
| inbox | 表存在但**生产闲置**：`JdbcForwardingInbox.receive` 仅测试调用；无 SELECT 拉取入口；T1 投递走 HTTP 不经 inbox | `agent-bus/.../persistence/jdbc/JdbcForwardingInbox.java` |
| 模块依赖 | `agent-runtime` 生产零依赖 `agent-bus`；`agent-bus` → `agent-runtime` 仅 **test-scope**（Stage 17 首次跨模块）；`AgentBusDependencyBoundaryTest.bus_does_not_depend_on_agent_runtime` 守卫生产方向 | `agent-bus/pom.xml`；`AgentBusDependencyBoundaryTest` |

**基线结论**：当前是典型 sender-agent-bus → receiver-agent-runtime 跨进程 push，receiver 完全被动、与 agent-bus 生产隔离。这正是 [`Stage 15 计划`](../delivery-projections/agent-bus-stage14-review-and-stage15-plan.md):26「PoC 选 T1 作为对接可行性最短路径，非最终裁决」的实态。

## 3. T3 两个子形态深挖

[`transport-candidates §2.3`](agent-bus-forwarding-runtime-transport-candidates.md) 提出 T3 有两个子形态，本节在代码上具体化。

### 3.1 T3a —— receiver 共享 outbox claim

**形态**：receiver 作为另一个 `leaseOwner` 直接 claim sender 写入的 **outbox** 表。outbox 成为跨 service 共享队列；sender 的 `ForwardingDispatcherWorker` 退化为「只入队」（enqueue + 接收 ack 回写），deliver / mark 由 receiver 侧 worker 承担。

| 项 | 评估 |
|---|---|
| 持久层改动 | `claimDue` 加 `AND target_service_id = :receiverId`（字段已存在）；其余 `SKIP LOCKED` / lease / RLS 零改 |
| 复用度 | **最高**：retry policy / lease reclaim / 状态机全复用；receiver 就是另一个 `leaseOwner` |
| 模块依赖障碍 | **重**：receiver 要读 sender 写的 outbox 表 → 共享一张跨 service 表（见 §6.2） |
| ordering | 多 receiver 抢同一 route 的顺序需明确 claim 边界（`SKIP LOCKED` 保证不重复，但不保证跨 receiver 的 route 内有序投递） |
| 语义代价 | 改变 outbox「发送方专有」语义 → 跨 service 共享队列 |

### 3.2 T3b —— sender push 到 inbox，receiver pull inbox

**形态**：sender dispatcher 把 outbox 记录「投递」为 receiver 的 **inbox** 行（本地 DB 写，sender 侧 `claim outbox → 写 inbox → markAcked outbox`），receiver 侧 worker 从 inbox claim 待消费记录处理。

| 项 | 评估 |
|---|---|
| 持久层改动 | inbox 加 claim / lease 语义（移植 outbox Stage 9 lease-safe 范式：`lease_owner` / `lease_until` / `status` 消费状态机 + claim `SKIP LOCKED` + lease-guarded `markConsumed`） |
| 复用度 | 中-高：lease-safe 模式是已验证范式（Stage 9/12），但 inbox 当前无 lease 字段，需 migration |
| 模块依赖障碍 | **重（同 T3a）**：receiver 仍要读 inbox 表；inbox 当前归 agent-bus Flyway，receiver 访问同样撞边界 |
| sender 投递动作 | 变「HTTP 同步等完成」为「本地 DB 写 inbox」→ sender 不被 receiver 拖慢、不依赖 receiver 在线 → **天然速率解耦**（接近 broker 解耦效果，承载物是 inbox 表） |
| ordering | inbox 天然 per-`(tenant, consumer_service)` 队列，ordering 边界比 T3a 清晰 |

### 3.3 T3a vs T3b 对照

| 维度 | T3a（共享 outbox） | T3b（push 到 inbox / pull inbox） |
|---|---|---|
| 持久层新增 | 几乎零（加过滤） | inbox migration 加 lease 语义 |
| 速率解耦 | 强（outbox 持久缓冲） | 强（inbox 持久缓冲 + sender 投递本地化） |
| sender 改动 | dispatcher 退化为入队 | dispatcher 投递目标 outbox→inbox |
| 模块依赖障碍 | 共享 outbox 表 | 共享 inbox 表（同等重） |
| receiver 补齐（MI13-R） | receiver 内置 pull worker 复用 `ForwardingDispatchLoop` 骨架 | 同 |
| ordering 清晰度 | 需额外定边界 | 天然 per-consumer 队列更清晰 |

> 两子形态都**不破 §6.2**（DB-poll + `SKIP LOCKED`，不引 MQ），都复用 Stage 12 持久层范式，都把 dispatcher 驱动力移到 receiver。差异集中在「receiver 拉哪张表」与「持久层改动量」。T3a 改动更小但语义代价更大（outbox 共享）；T3b 改动稍大但模块边界与 ordering 更清晰，且 sender 投递本地化带来更强的速率解耦。

## 4. 复用 Stage 12 持久层的可行性

代码调研确认 [`transport-candidates §2.3`](agent-bus-forwarding-runtime-transport-candidates.md)「与 Stage 12 契合高」的判断，并细化前置：

| Stage 12 资产 | T3 复用 | 改动 |
|---|---|---|
| `claimDue`（`FOR UPDATE SKIP LOCKED` + 两回收子句：DISPATCHING 过期 / RETRY_SCHEDULED 到期） | T3a 直接复用，receiver 是另一个 `leaseOwner`；两回收子句对 receiver 视角同样适用 | T3a 加 `target_service_id` 过滤；索引 `ix_outbox_claim_due` 需评估是否扩列 |
| lease-guarded mutation（`WHERE status='DISPATCHING' AND lease_owner=:owner AND lease_until>:now`） | 零改复用 | 无 |
| RLS（outbox / inbox 对称按 `tenant_id = current_setting('app.tenant_id')`） | 零改复用，sender / receiver 视角对称 | 无 |
| `ForwardingRetryPolicy`（overflow-safe 退避 + exhausted→DLQ） | 零改复用（重投治理与投递模型正交，[`Stage 14`](../delivery-projections/agent-bus-stage13-review-and-stage14-plan.md) 已论证） | 无 |
| inbox `receive`（幂等 `ON CONFLICT DO NOTHING` 去重）+ 状态机（RECEIVED→CONSUMED/REJECTED） | T3b 复用去重 + 状态机 | T3b 需补 claim / lease 字段与查询（当前 inbox 无 SELECT 拉取入口、无 lease） |
| `ForwardingEndpointResolver` / `MapEndpointResolver` | T3 下角色变化：push 时 sender 解析 endpoint；pull 时 receiver 不需解析 endpoint（它知道自己是谁），resolver 退化为 receiver 身份匹配 | T3a 用 `target_service_id` 匹配；T3b 用 `consumer_service_id` |

**结论**：持久层复用度**高**，前置仅两项——T3a 的 `claimDue` 过滤、T3b 的 inbox lease 语义移植——均为已验证范式的小改，非范式突破。

## 5. 三条生命线在 T3 下的成立性

Stages 19–21 验证的 C3 三条端到端生命线，在 T3 下的成立性：

| 生命线 | T3 下成立性 | 说明 |
|---|---|---|
| **retry 往返**（claimDue 的 RETRY_SCHEDULED 回收子句跨多 tick、attemptCount 攀升、exhausted→DLQ / 恢复→ACKED） | **成立** | receiver claim 失败 / 处理失败留 DISPATCHING 待 reclaim；`scheduleRetry` + attemptCount + 退避全复用。T3a 零改，T3b 仿 outbox |
| **租约回收**（DISPATCHING 过期 stuck-holder 回收 + RETRY_SCHEDULED 到期回收） | **成立** | 两回收子句对 receiver 视角同样适用——只要 receiver 是合法 `leaseOwner`，worker 崩溃留 DISPATCHING 照样被下一 tick 回收 |
| **断路器**（`RouteCircuitBreaker` CLOSED→OPEN→HALF_OPEN） | **冗余 / 可移除** | [`transport-candidates §2.3`](agent-bus-forwarding-runtime-transport-candidates.md) 已指出「T3 pull 下 receiver 不拉即天然熔断」——receiver 按能力控速，不会轰炸下游，显式 breaker 基本不触发。**T3 的净收益**：Stage 16 接入的 breaker 可退化为 `ALWAYS_CLOSED` 或移除，降低 per-route 状态复杂度 |

> 三条生命线在 T3 下两条成立、一条可退化。这意味着 Stages 19–21 的端到端验证资产**大部分可迁移**到 T3（retry / lease reclaim 测试改 `leaseOwner` 视角即可复用），迁移成本主要在「receiver 视角的 claimDue 过滤」与「scheduler 驱动」，而非重写生命线逻辑。

## 6. 关键张力（transport-candidates 未充分展开）

本节是本评估对 [`transport-candidates`](agent-bus-forwarding-runtime-transport-candidates.md) 的核心补充：T3「低复杂度」是抽象层面成立，落地层面有两处 transport-candidates 未展开的硬障碍。

### 6.1 阻塞 ① —— 生产 scheduler 从无到有（撞 §6.1 第 3 项）

[`decision §6.1`](agent-bus-forwarding-runtime-decision.md):139「让完整调度器接入真实服务调用链」是 Stage 7 起的「不得」项；[`§8`](agent-bus-forwarding-runtime-decision.md):169 显式 deferred「`ForwardingDispatchLoop` 接真实 scheduler / polling cadence」。

**代码实证**：`ForwardingDispatchLoop` 的 `TickSource`（函数式接口，`nextTickMillisEpoch()`）在生产代码**零驱动**——生产代码无 `@Scheduled` / `ScheduledExecutorService` / `Timer`，所有 `ForwardingDispatchLoop` 使用点（7 处）都在 `src/test`（如 `C3ForwardingEndToEndIntegrationTest`）。Stages 19–21 的多 tick 验证全部用 test-only `TickSource`（`+61s` step）+ `MutableEpochClock` 协调驱动，**绕开了真实 scheduler**。

**T1 push 如何掩盖了这个问题**：T1 的 dispatcher 在 sender（agent-bus），PoC 阶段用测试驱动 TickSource 就能验证端到端；生产部署时即便没有 scheduler，sender 侧的 push 也可以由「ingress 写 outbox 后同步触发一次 tick」之类的轻量入口兜底（未落地，但路径存在）。

**T3 为何无法掩盖**：T3 的 pull worker 在 receiver，receiver 必须主动、周期性地 `claimDue` 才能消费——**没有 poll cadence 就没有消费**。这不是可选优化，是 T3 的运行前提。因此 T3 落地**必须**解除 §6.1 第 3 项，引入真实 scheduler（poll cadence + lease 续约的真实时间驱动）。

**解除性质**：同 Stage 12 解除「路径 B / 不引入 JDBC」——需要新的评审包 + H2/H3 裁决，不是工程决定。

### 6.2 阻塞 ② —— 模块依赖方向反转（撞边界守卫）

**当前边界**：`agent-runtime` 生产零依赖 `agent-bus`；`agent-bus` → `agent-runtime` 仅 test-scope（Stage 17 首次跨模块，正是为端到端 IT）；`AgentBusDependencyBoundaryTest.bus_does_not_depend_on_agent_runtime` 守卫生产方向（`com.huawei.ascend.bus..` 生产不得 reach `com.huawei.ascend.runtime..`）。这条边界是 agent-bus「可独立演进、不耦合 runtime 实现」的核心保证。

**T3 的张力**：无论 T3a（receiver 读 outbox）还是 T3b（receiver 读 inbox），receiver（`agent-runtime`）都必须能访问 agent-bus 持有的持久层。这要求**反转或绕过**当前的单向依赖。

**三种解法（H2/H3 择一）**：

| 解法 | 形态 | 代价 |
|---|---|---|
| (a) 共享 DB schema | receiver 直连 agent-bus 的 Postgres 表（outbox / inbox），RLS 按 tenant 隔离 | 打破模块构建隔离；receiver 依赖 agent-bus 的 schema 版本；多 service 共享一张表的事务/竞争边界 |
| (b) 反向查询 API | agent-bus 暴露「receiver 拉取属于自己的消息」查询端点（REST / gRPC），receiver 调它拉取 | 引入新的跨模块 API 契约 + 网络 hop；但保模块构建隔离；最接近「真 bus」语义 |
| (c) 持久层下沉 | 把 outbox / inbox 的 schema + claim/lease 端口下沉为 agent-bus 与 agent-runtime 共享的公共依赖 | 重构面最大；需新模块或公共 artifact；但边界最干净 |

> 这三种解法都不破 §6.2（都不引 MQ），但都是架构治理决定，需 H2/H3 裁决。**这是 T3 落地最深的一层决策**——它实质上在回答「agent-bus 与 agent-runtime 的运行时耦合形态」，远超「投递模型 push/pull」本身。

### 6.3 inbox 闲置 + 无 claim/lease（T3b 前置，MI13-R 实证）

[`transport-candidates §0`](agent-bus-forwarding-runtime-transport-candidates.md):44 提到「receiver 端（Stage 12 review MI13-R 暴露的缺失）」。代码实证：`JdbcForwardingInbox.receive` 在生产链路无调用点（仅测试），inbox 无 SELECT 拉取入口、无 lease 字段。**T1 push 投递走 HTTP 直达不经 inbox**，inbox 是为 receiver 侧消费预留但未激活的设计态表。T3b 正是激活 inbox 的路径，但需 inbox migration 加 lease 语义（§4）+ 解决 receiver 访问（§6.2）。

## 7. 风险与开放问题

- **DB-poll 吞吐 / 延迟（T3 rejection criteria，需量化）**：[`transport-candidates §7.3`](agent-bus-forwarding-runtime-transport-candidates.md) 给 T3 的不可接受条件是「DB-poll 的吞吐 / 延迟无法满足目标投递量级（需量化）」。本评估无法量化——需 H2/H3 提供目标投递量级（TPS / 延迟 SLO），才能判定 DB-poll 是否够用。这是 T3 是否可行的**前置数据**。
- **多 receiver 抢同 route 的 ordering（T3a）**：`SKIP LOCKED` 保证不重复 claim，但不保证同一 route 内跨 receiver 的投递顺序。若 route 内有序投递是硬需求，T3a 需额外 ordering 机制（route 亲和单 receiver / 序号）；T3b 天然 per-consumer 队列更安全。
- **poll cadence 与 lease TTL 的协调**：T3 引入真实 scheduler 后，poll 间隔、`claimDue(limit)` 的 limit、lease TTL、retry 退避需协同调优（receiver 拉太多撑爆自己 / 拉太少 outbox 堆积）。
- **breaker 跨 receiver 实例（若保留）**：若 T3 下保留 breaker，per-route 状态需跨 receiver 实例共享（当前 `RouteCircuitBreaker` 是进程内 `ConcurrentHashMap`）。但 §5 论证 T3 下 breaker 冗余，更可能的选择是**移除**而非持久化。
- **T1 → T3 共存期**：若裁决 T3，迁移期间 outbox 可能同时被 sender push dispatcher 与 receiver pull worker claim，需明确切换策略（蓝绿 / 双写 / route 级切换），避免重复投递。

## 8. 给 H2/H3 的决策输入

### 8.1 推荐路径（非裁决性质，性质同 [`transport-candidates §7.2`](agent-bus-forwarding-runtime-transport-candidates.md)）

- **若反压是硬需求**：T3 是唯一不破 §6.2 的强反压候选，应作为首选。子形态倾向 **T3b**——ordering 更清晰、sender 投递本地化解耦更强、模块边界（inbox 归属）比 T3a 的「共享 outbox」更可裁决；代价是 inbox migration 加 lease 语义（已验证范式）。T3a 作为「最小持久层改动」备选。
- **若反压非硬需求**：维持 T1（当前 Stage 15–21 实态），零迁移成本。
- **T2 / T4（broker）**：仅在 T3 的 DB-poll 吞吐 / 延迟经量化不满足 + 团队承担 broker 运维 + H2/H3 解除 §6.2 后重启评审。

### 8.2 裁决 T3 时必须**同时**裁决的护栏项

这是本评估的核心提醒——**裁决「投递模型选 T3」本身不够**，H2/H3 必须在同一次裁决中处理：

1. **解除 [`§6.1`](agent-bus-forwarding-runtime-decision.md) 第 3 项**（scheduler 接入真实调用链）——T3 运行前提。
2. **裁决 §6.2 模块依赖方案**（§6.2 三解法 a/b/c 择一）——T3 访问持久层前提。
3. **提供目标投递量级**（TPS / 延迟 SLO）——判定 DB-poll 是否满足（T3 rejection criteria 量化）。

三者缺一，T3 裁决无法施工。建议 H2/H3 把这三项与「push/pull/MQ 选择」作为**一个裁决包**处理。

### 8.3 落地切片草案（裁决后用，非本评估承诺）

若 H2/H3 裁决走 T3，建议切片（供后续 stage plan 参考）：

- **切片 0（治理）**：decision §6.1 第 3 项解除 + 模块依赖方案裁决记录 + 投递模型最终裁决（T3a/T3b 选定）。
- **切片 1（scheduler）**：`ForwardingDispatchLoop` 接真实 scheduler（poll cadence + 真实 `EpochClock` 驱动），解除 §8 deferred「接真实 scheduler」。
- **切片 2（持久层）**：T3a 加 `claimDue` 的 `target_service_id` 过滤 + 索引评估；或 T3b 的 inbox migration 加 lease 语义（移植 Stage 9）。
- **切片 3（receiver worker）**：receiver 侧 pull worker（复用 `ForwardingDispatchLoop` 骨架），claim → 本地处理 → `markConsumed` / `markAcked`。
- **切片 4（模块边界）**：按 §6.2 裁决方案落地跨模块访问（共享 schema / 反向 API / 下沉）。
- **切片 5（迁移与验证）**：T1→T3 切换策略 + 三条生命线 receiver 视角端到端验证（迁移 Stages 19–21 测试）+ breaker 退化/移除。

### 8.4 rejection criteria 更新（补充 [`transport-candidates §7.3`](agent-bus-forwarding-runtime-transport-candidates.md)）

| 候选 | 不可接受条件（本评估补充） |
|---|---|
| T3 pull DB | (原) DB-poll 吞吐 / 延迟无法满足目标量级（需量化）；**(补) H2/H3 不同时解除 §6.1 第 3 项 scheduler + 裁决模块依赖方案**——二者缺一则 T3 无法施工 |
| T1 push RPC | (原) 反压是硬需求；**(补) 若维持 T1，应显式接受「无消费方控速」并记录为已知设计债**（当前 Stage 15–21 实态） |

## 9. 相关文档

- [`transport-candidates`](agent-bus-forwarding-runtime-transport-candidates.md) —— T1–T4 × 8 维度候选评审（本评估的前置，T3 非裁决推荐来源）。
- [`decision`](agent-bus-forwarding-runtime-decision.md) §6.1（第 3 项 scheduler）/ §6.2（始终不得）/ §8（Stage 13 transport 议题 + deferred scheduler）。
- [`Stage 15 计划`](../delivery-projections/agent-bus-stage14-review-and-stage15-plan.md):26 —— T1 push 是 PoC 临时选择、非最终裁决的原文。
- [`ICD-Agent-Bus-Forwarding`](../../05-contracts/human-readable/ICD-agent-bus-forwarding.md) —— HD4 broker-agnostic 投递契约（T3 不破）。
- [`forwarding-persistence`](../../../../../architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md) —— Stage 12 claim / lease / `SKIP LOCKED` / RLS 语义（T3 复用基础）。
