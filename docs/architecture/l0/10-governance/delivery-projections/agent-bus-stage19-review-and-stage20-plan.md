---
artifact_type: delivery_projection
version: agent-bus-stage19-review-and-stage20-plan
status: stage-20-completed
source_commit: 689e926a
stage20_planned: 2026-06-23
source_stage19_plan: docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage18-review-and-stage19-plan.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
source_icd_runtime: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md
source_l2: architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md
target_module: agent-bus (纯测试 / 验证阶段，无生产代码；不 boot runtime)
---

# agent-bus Stage 19 评审与 Stage 20 计划（验证回填：租约过期 reclaim + 断路器真实链路端到端）

## 0. 结论

提交 `689e926a`（Stage 19）可以作为 Stage 19 的阶段性成果接受：首次端到端打穿 C3 outbox 的**重投往返生命周期** —— `JdbcForwardingOutbox.claimDue` 的 `RETRY_SCHEDULED` 回收子句（`next_attempt_at <= :now`）在真实持久化上跨多 tick 触发，`attempt_count` 在重投间真实攀升，两出口（恒失败→exhausted→DLQ / 间歇恢复→ACKED）闭环。**186 tests green**，ArchUnit green，§6.2 不变，无生产代码改动。**接受**。

但 Stage 19（以及 Stage 18 评审）明示留下的两个端到端盲区仍在：

1. **租约过期 reclaim（卡住持有者回收）未端到端**：`claimDue` SQL 的第三类 reclaim（`status='DISPATCHING' AND lease_until <= :now`）—— 一个 worker claim 后崩溃/卡住、lease 过期、另一个 worker（或同 worker 下一 tick）接手 —— 此前只有 `ForwardingJdbcIntegrationTest` 的 fake/contract 覆盖（`stuck-holder reclaim` 单测），**从未在端到端 dispatch 生命周期上验证**。这是 C3 outbox 的「不丢消息」承诺的核心：worker 崩溃不会让 DISPATCHING 记录永久卡死，lease 过期后被自动回收重投。

2. **断路器真实链路未端到端**：Stage 16 `RouteCircuitBreaker` 三态机（CLOSED→OPEN→HALF_OPEN→CLOSED）此前只有 `RouteCircuitBreakerTest` 纯状态机单元 + worker 契约覆盖，**从未在真实失败链路 + 真实持久化上验证**（连续真实/注入失败→OPEN 短路留 DISPATCHING→冷却→HALF_OPEN 探测→恢复→CLOSED）。

Stage 20 = **验证回填**（用户在 Stage 19 收尾后选定「先做验证补全」）。复用 Stage 19 的全部测试基础设施（`MutableEpochClock` + `advanceableTickSource` + `fakeDeliveryPort` + `CONTROL_ONLY` envelope + `@Isolated` + boot recipe），**零新增前置工程、零生产代码**。两个新场景各闭合一个盲区。**188 tests green**，§6.2 不变。

**核心论点**：Stage 19 验证了「retryable 失败 → 重投往返到出口」，但 claimDue reclaim 的**另一个子句**（卡住持有者 DISPATCHING）和 **breaker 短路后的留置记录如何被回收**，二者与 retry reclaim 的协同 —— 在真实 SQL 上从未一起跑过。Stage 20 场景 B（最高价值）把三者编织：断路器 OPEN 短路在 DISPATCHING 记录上（worker skip 不 deliver、不释放 lease 路径）、冷却后 HALF_OPEN 探测、其间租约过期回收把 DISPATCHING 记录拉回重投 —— 证明两个 reclaim 子句（RETRY_SCHEDULED + 卡住持有者 DISPATCHING）与三条 skip 路径（lease 续约失败 / breaker OPEN / deliver 异常）在真实 Postgres 上正确组合，`probeInFlight` 不泄漏。

Stage 20 **不裁决** Stage 13 的 push/pull/MQ 哲学张力（仍 H2/H3）—— 验证回填是 outbox/worker/breaker 层语义，与投递是 push 还是 pull 无关。§6.2 不变（两场景用 `CONTROL_ONLY` envelope，可控时钟/`TickSource`/fake port/`simulateCrashedDispatch` 都是 test-scope 纯 JDK 辅助，不引入 concrete broker/MQ、不写 payload 正文/token 流/Task execution state、不跨租户回退）。

## 1. Stage 19 评审（commit `689e926a`）

Stage 19 在 Stage 17/18 真实 runtime + Stage 12 真实持久化在手时，闭合了 Stage 18 评审明示的最大盲区：**重投往返生命周期从未端到端**。Stage 18 场景 2 只验证了 RETRY_SCHEDULED 的**入口**（route 不可达 → retry → `attempt_count=1`），Stage 19 继续往后跑完整生命周期。

**4 个优点：**

1. **闭合 outbox 价值主张的核心链路**：C3（database outbox）相对「同步直投 + 内存重试」的全部存在理由是「持久化 + 自动重驱动到成功或 DLQ」。这条主张由 `ForwardingRetryPolicy`（`nextAttemptAt` + `exhausted`，Stage 14）+ `claimDue` RETRY_SCHEDULED reclaim（Stage 9/12）+ worker RETRY_SCHEDULED 分支（Stage 14）三者协同实现，但**协同从未端到端验证**。Stage 19 在真实 Postgres 上驱动同一条 record 越过 `next_attempt_at` 跨多 tick，看 claimDue 真的 reclaim、`attempt_count` 真的攀升、exhausted 真的在正确计数触发、两出口真的闭环。任何接线错误（`nextAttemptAt` 单位/时区错、claimDue `<=` 写成 `<`、`attemptCount` 传 ++ 前的值、exhausted 阈值差一）都会被现有 fake-delivery 测试放过，但被真实 SQL IT 抓住。
2. **时间可控性设计严谨（两时钟协调 + 真实时钟约束）**：`MutableEpochClock`（test-only 纯 JDK）注入 worker + 协调多 tick `TickSource`（`+61s` step > 任何 backoff），无需真实 sleep/scheduler（§6.1「总线无调度器」守恒）。关键洞察是**两时钟必须同源协调** —— TickSource yield 前 `advanceTo` MutableEpochClock，使 worker `clockNow`（`nextAttemptAt` 基准）与 claimDue `:now`（tick instant）一致；否则 reclaim 不可预测。再加一条**真实时钟约束**：`leaseGuardedUpdate` 用真实 `System.currentTimeMillis()`，故 tick instant 从 T0 单调递增、`lease_until = instant+60s` 始终 > 真实时钟，租约防护不误判过期。
3. **场景 B 用 fake delivery port 的语义正确性**：Stage 18 把 handler 业务失败（FAILED）映射为 `dlq(REMOTE_TASK_FAILED)`（非 retry），故「重投到恢复」不能用 handler 业务失败模拟（第 1 次就 DLQ）。retryable 失败只能是 transport 层。Stage 19 场景 B 用 fake delivery port 注入 `[retry, retry, acked]`，语义贴合「transport 间歇性失败后恢复」—— 场景 A 用真实 transport 失败（不可达 route socket 拒连）担保了 deliver 真实性，场景 B 只需证明 ACKED 出口的持久化层接线，deliver 注入合理。
4. **`@Isolated` flaky 修复（超出原计划的发现）**：构建验证发现两个 context-boot IT（`C3ForwardingEndToEndIntegrationTest` / `C3ForwardingFailurePathIntegrationTest`）在 parent pom surefire 4 路并发下 flaky（Spring Boot 4 `SpringApplication.run` 非线程安全 + `*IntegrationTest` 命名绕过 failsafe `**/*IT.java` 串行 include → `ConcurrentModificationException` + 全局 `spring.autoconfigure.exclude` 踩踏）。最小修复：两 IT 加 JUnit5 `@Isolated`（独占执行），根治 flaky。原计划未列此项 —— 它是构建验证阶段的真实发现，也成了 Stage 20 两场景 IT 沿用的稳定性基线。

**2 个盲区（观察，驱动 Stage 20）：**

1. **租约过期 reclaim（卡住持有者）未端到端**：`claimDue` SQL 的第三类 reclaim（`status='DISPATCHING' AND lease_until <= :now`，worker 崩溃留 DISPATCHING + lease 过期后被回收）此前只有 `ForwardingJdbcIntegrationTest.stuck-holder reclaim` 单测（直接构造一条 DISPATCHING + 过期 lease 行测 claim SQL），**从未在端到端 dispatch 生命周期上验证**（模拟 worker 崩溃 → 记录卡 DISPATCHING → lease 过期 → 下一 tick claimDue 回收 → 重投到出口）。
2. **breaker 真实链路未端到端**：Stage 16 `RouteCircuitBreaker` 三态机（CLOSED→OPEN→HALF_OPEN）在真实失败链路（连续失败触发 OPEN 短路 → 留 DISPATCHING → 冷却 → HALF_OPEN 探测 → 恢复→CLOSED）从未跑过，只有 `RouteCircuitBreakerTest` 纯状态机单元 + worker 契约覆盖。尤其未验证：breaker OPEN 短路时 worker skip **留 DISPATCHING**（不释放 lease），这条记录随后如何被 lease 过期 reclaim 拉回 —— 两个 reclaim 子句 + breaker skip 的协同从未端到端。

Stage 19 DoD：186 tests green，ArchUnit green，§6.2 不变。**接受**。

## 2. Stage 20 范围与设计

### 2.1 为什么（补两个端到端盲区）

两个盲区都是 C3 outbox 的「不丢消息 + 自愈」承诺的组成部分，但端到端验证缺位：

| 盲区 | 涉及组件 | 已有覆盖 | 端到端缺位 |
|---|---|---|---|
| 租约过期 reclaim | `claimDue` 卡住持有者子句（`status='DISPATCHING' AND lease_until <= :now`） | `ForwardingJdbcIntegrationTest` fake/contract 单测 | 模拟 worker 崩溃留 DISPATCHING + lease 过期 → 下一 tick claimDue 回收 → 重投到出口，**未端到端** |
| breaker 真实链路 | `RouteCircuitBreaker`（Stage 16）三态机 + worker 7 参接入 | `RouteCircuitBreakerTest` 纯状态机单元 + worker 契约 | 连续失败→OPEN→留 DISPATCHING→冷却→HALF_OPEN→恢复→CLOSED，**未在真实持久化上端到端** |

Stage 20 = 验证回填，两个场景各闭合一个。复用 Stage 19 全部测试基础设施，零新增前置工程、零生产代码。

### 2.2 场景 A — 租约过期卡住持有者回收 → 重投 → ACKED（闭合盲区 1）

**模拟 worker 崩溃**：`simulateCrashedDispatch(tenant, messageId, "worker-dead", crashedAt)` —— test-only 纯 JDK 辅助，用 raw JDBC `UPDATE ... SET status='DISPATCHING', lease_owner=?, lease_until=?` 把一条 PENDING 记录推成「某 worker 已 claim 并卡住」的状态，`lease_until` 设为 tick instant **之前**（已过期）。DDL CHECK 约束（`ck_outbox_lease_status` 要求 DISPATCHING ⇒ lease_owner 非空；`ck_outbox_lease_paired` 要求 lease_owner/lease_until 成对）允许这个 UPDATE（字段配对合法），故不需碰生产代码或 migration。

**链路（单 tick，`MutableEpochClock` = T0）**：

```
setup:  enqueue PENDING(tenant, messageId)
        simulateCrashedDispatch(...) → status=DISPATCHING, lease_owner='worker-dead', lease_until=T0-60_000（已过期）
assert: SELECT → DISPATCHING ✓（卡住持有者状态）
tick 1 (clock=T0):  claimDue :now=T0
                    → WHERE (status='PENDING' OR ... OR (status='DISPATCHING' AND lease_until<=T0)) ← 命中卡住持有者子句
                    → claim（SKIP LOCKED，lease_owner 改为本 tick worker）
                    → deliver → fakeDeliveryPort([acked])
                    → markAcked → ACKED
```

**断言**（聚合 `DispatchTickResult` + raw JDBC `outboxRow` 投影读，复用 Stage 18/19 helper）：

- `tick.claimed() == 1`（只有卡住持有者 reclaim 子句可以申领这条 DISPATCHING 记录 —— PENDING/RETRY_SCHEDULED 子句都不匹配）；
- `tick.acked() == 1`；
- 自洽不变量 `claimed == acked+retried+dlqd+expired+skipped`；
- `outboxRow`：`status == ACKED`、`attempt_count == 0`（从未失败重投）、`last_failure_code == null`。

**关键证据**：`tick.claimed() == 1` 证明 claimDue 的**第三类 reclaim 子句**（`status='DISPATCHING' AND lease_until <= :now`）真的在端到端 tick 上被触发并申领了卡住的 DISPATCHING 记录 —— 这是 Stage 19 盲区 1 的直接闭合，此前只有 fake/contract 单测覆盖。

### 2.3 场景 B — 断路器全状态机真实链路 + 租约回收交织（闭合盲区 2，最高价值）

场景 B 是 Stage 20 价值最高的测试：它把**断路器三态机 + 租约过期回收**编织，证明两个 reclaim 子句（RETRY_SCHEDULED + 卡住持有者 DISPATCHING）与三条 skip 路径（lease 续约失败 / breaker OPEN / deliver 异常）在真实 Postgres 上正确组合，且 `probeInFlight` 单探测标记不泄漏。

**注入**：`RouteCircuitBreaker(2, 122_000, clock)`（`failureThreshold=2` 连续失败→OPEN，`cooldownMillis=122_000`）+ `fakeDeliveryPort` 注入结果序列 `[retry, retry, acked]` + worker 7 参构造器（注入 breaker）。4 ticks，step `61_000`（两 tick 越过 122s 冷却期）。

**链路（4 ticks）**：

```
tick 1 (clock=T0):          claimDue PENDING → DISPATCHING
                            → breaker.allowsDelivery CLOSED → true（放行）
                            → deliver → fakePort[0]=retry(RECEIVER_UNAVAILABLE)
                            → breaker.recordOutcome(retry) → consecutiveFailures=1（< 2，仍 CLOSED）
                            → scheduleRetry → RETRY_SCHEDULED, attempt_count=1
tick 2 (clock=T0+61000):    claimDue reclaim RETRY_SCHEDULED (next_attempt_at<=now) → DISPATCHING
                            → breaker.allowsDelivery CLOSED → true
                            → deliver → fakePort[1]=retry
                            → breaker.recordOutcome(retry) → consecutiveFailures=2 (>=2) → OPEN, openedAt=T0+61000
                            → scheduleRetry → RETRY_SCHEDULED, attempt_count=2
tick 3 (clock=T0+122000):   claimDue reclaim RETRY_SCHEDULED → DISPATCHING
                            → breaker.allowsDelivery OPEN → (now-openedAt=61000 < cooldown 122000) → false（短路）
                            → skipped++（留 DISPATCHING，不 deliver，不消耗 attempt_count）
tick 4 (clock=T0+183000):   claimDue reclaim DISPATCHING (lease_until<=now，卡住持有者子句) → DISPATCHING（被回收）
                            → breaker.allowsDelivery OPEN → (now-openedAt=122000 >= cooldown 122000) → HALF_OPEN + probeInFlight=true（探测放行）
                            → deliver → fakePort[2]=acked
                            → breaker.recordOutcome(acked) → HALF_OPEN 探测成功 → CLOSED（清零计数 + probeInFlight）
                            → markAcked → ACKED
```

**断言**：

- `tick.claimed() == 4`（tick 1 PENDING + tick 2/3 RETRY_SCHEDULED reclaim + tick 4 卡住持有者 DISPATCHING reclaim）；
- `tick.retried() == 2`（tick 1/2 失败 retry）；
- `tick.skipped() == 1`（tick 3 breaker OPEN 短路）；
- `tick.acked() == 1`（tick 4 HALF_OPEN 探测成功）；
- 自洽不变量；
- `breaker.stateOf(routeHandle) == CLOSED`（探测成功后恢复）；
- `outboxRow`：`status == ACKED`、`attempt_count == 2`（两次 retry 后第三次成功，breaker skip 不递增）。

**关键证据**：tick 3（breaker OPEN 短路留 DISPATCHING）→ tick 4（卡住持有者 reclaim 子句把 DISPATCHING 拉回 + HALF_OPEN 探测放行 + ACKED + CLOSED）。这证明：(a) breaker 短路留下的 DISPATCHING 不会永久卡死（lease 过期被回收）；(b) 回收后 breaker 已过冷却期转 HALF_OPEN 放行探测；(c) 探测成功后 `probeInFlight` 清除、状态恢复 CLOSED；(d) `attempt_count` 在 breaker skip 期间不递增（== 2）—— 四个组件（claimDue 两 reclaim 子句 + breaker 三态机 + worker skip 路径 + retry policy 计数语义）协同正确。

### 2.4 时间控制复用（Stage 19 基础设施，零新增）

场景 A/B 全部复用 Stage 19 的 `MutableEpochClock` + `advanceableTickSource(clock, baseInstant, stepMillis, ticks)` + `fakeDeliveryPort` + `CONTROL_ONLY` envelope + `@Isolated`。**零新增时间控制基础设施** —— Stage 19 已把两时钟协调 + 真实时钟约束封装进 `advanceableTickSource` 助手，Stage 20 直接复用。

- 场景 A：单 tick，`MutableEpochClock` = T0（`advanceableTickSource` 产 1 个 tick）。
- 场景 B：4 ticks，step `61_000`（`advanceableTickSource(clock, T0, 61_000, 4)`）；`RouteCircuitBreaker` 共享同一 `MutableEpochClock`（冷却判断与 tick instant 同源）。

### 2.5 边界 + ArchUnit + governance

- **§6.2 不变**：两场景用 `CONTROL_ONLY` envelope；`MutableEpochClock`/`advanceableTickSource`/`fakeDeliveryPort`/`simulateCrashedDispatch`/`RouteCircuitBreaker` 实例都是 **test-scope 纯 JDK 辅助**，不引入 concrete broker/MQ、不写 payload 正文/token 流/Task execution state、不跨租户回退。
- **ArchUnit**：`AgentBusForwardingSpiPurityTest` 扫**生产源码**；Stage 20 **不动生产代码**（只加测试），无需新 ArchUnit 豁免。worker 7 参构造器（Stage 16 引入）注入 breaker 是现有 API 用法。
- **§6.1 不受影响**：Stage 20 用注入 `TickSource` 推进时间，**无真实 scheduler**（§6.1「总线无调度器」+ H2/H3 裁决均不受影响）。
- **decision §8**：加 Stage 20 bullet（正向：闭合 Stage 19 两个盲区、breaker + lease reclaim 协同端到端；反向：§6.2 不变、不动生产代码、不裁决 push/pull/MQ、不 boot runtime）。
- **L2 `forwarding-persistence`**：新增 §21（Stage 20 决策：验证回填），含两场景链路图、`simulateCrashedDispatch` 设计、复用 Stage 19 时间控制。
- **L1 4+1 视图**：6 视图 + README/ARCHITECTURE 全部纳入 Stage 20 回灌（沿用 `agent-bus-4plus1-view-rebound` 教训）。
- **ICD + yaml**：`stage20_scope`（delivers `lease-expiry-stuck-holder-reclaim-end-to-end` / `circuit-breaker-full-state-machine-end-to-end` / `breaker-skip-leases-dispatching-reclaim-interplay`；not_delivers `real-retry-scheduler-polling-cadence` / `circuit-breaker-state-persistence` / `multi-worker-concurrent-claim` / `production-code-changes`）；顶部 description 追加 Stage 20 句。

## 3. 关键发现（前置分析）

| # | 发现 | 验证 | 结论 |
|---|---|---|---|
| 1 | claimDue 的卡住持有者 reclaim 子句存在 | 读 `JdbcForwardingOutbox.claimDue` | `WHERE (status='PENDING' OR (status='RETRY_SCHEDULED' AND next_attempt_at<=:now) OR (status='DISPATCHING' AND lease_until<=:now)) ... FOR UPDATE SKIP LOCKED`。第三类子句存在，可被场景 A 触发 |
| 2 | DDL CHECK 允许 simulateCrashedDispatch UPDATE | 读 `V1__create_agent_bus_forwarding_outbox.sql` CHECK 约束 | `ck_outbox_lease_status`（DISPATCHING ⇒ lease_owner 非空）、`ck_outbox_lease_paired`（lease_owner/lease_until 成对）只校验配对，不校验语义。`UPDATE SET status='DISPATCHING', lease_owner=?, lease_until=?` 字段配对合法，通过 CHECK，**不需碰生产代码/migration** |
| 3 | `RouteCircuitBreaker.stateOf` 可观测 | 读 `RouteCircuitBreaker.java` | `public State stateOf(ForwardingRouteHandle)` 返回 CLOSED（未追踪）或存储状态，供场景 B 断言恢复 CLOSED |
| 4 | worker 7 参构造器注入 breaker | 读 `ForwardingDispatcherWorker` 构造器链（Stage 16） | 7 参（outbox, outbox, delivery, DispatchLeasePolicy, clock, retryPolicy, breaker）存在；breaker OPEN 时 `allowsDelivery` 短路 `skipped++`（留 DISPATCHING），`recordOutcome` 反馈驱动三态机 |
| 5 | breaker skip 留 DISPATCHING 不释放 lease 路径 | 读 worker `runOnce` breaker 接入点 | breaker `allowsDelivery=false` → `skipped++; continue`，**不调 deliver、不调 markAcked/scheduleRetry**，记录停在 DISPATCHING（lease_until 已设）。下一 tick lease 过期后被卡住持有者子句回收 |
| 6 | `probeInFlight` 不变量（Stage 16 已验证） | 读 `RouteCircuitBreaker` + worker 接入顺序 | HALF_OPEN 探测的 `probeInFlight` 在 `recordOutcome` 时清除；worker 接入顺序（allowsDelivery→deliver→recordOutcome）保证三个 skip 路径 + switch 内异常都不泄漏。场景 B tick 4 探测成功后 probeInFlight 清除 |
| 7 | attempt_count 在 breaker skip 不递增 | 读 worker RETRY_SCHEDULED 分支 + moveToDlq/markAcked | scheduleRetry 递增；breaker skip 走 continue 不进 scheduleRetry；markAcked 不递增。场景 B `attempt_count==2`（tick1/2 retry 递增到 2，tick3 skip 不变，tick4 acked 不变） |
| 8 | 两场景都不需真实 runtime | 场景 A 用 `simulateCrashedDispatch` + fakePort([acked])；场景 B 用 fakePort([retry,retry,acked]) + breaker | **Stage 20 IT 不 boot `LocalA2aRuntimeHost`**（两场景都用 fake delivery port，不连真实 server）。只需 embedded-postgres + Flyway + JdbcForwardingOutbox，比 Stage 17/18 更轻 |
| 9 | Stage 19 时间控制基础设施可零改动复用 | 读 `C3ForwardingRetryLifecycleIntegrationTest` helper | `MutableEpochClock` + `advanceableTickSource` + `fakeDeliveryPort` + `CONTROL_ONLY` envelope + `@Isolated` + boot recipe 全部可复用。Stage 20 零新增时间控制代码 |
| 10 | `@Isolated` 沿用稳定性基线 | Stage 19 `@Isolated` 修复 | Stage 19 给 context-boot IT 加 `@Isolated` 根治 flaky；Stage 20 两场景虽不 boot runtime（更轻），但仍加 `@Isolated` 沿用独占执行稳定性（parent pom surefire 4 路并发 + Spring Boot 4 非线程安全的风险面仍在） |

## 4. 切片 + MI 表

| MI | 切片 | 产出 |
|---|---|---|
| MI20-001 | 1 场景 A IT（租约过期 reclaim） | `C3ForwardingLeaseReclaimAndBreakerIntegrationTest` 场景 A `scenario_a_lease_expiry_reclaims_stuck_dispatching_row`：enqueue PENDING → `simulateCrashedDispatch`（raw JDBC 推 DISPATCHING + 过期 lease）→ 断言 DISPATCHING → 单 tick（`MutableEpochClock`=T0）→ `fakeDeliveryPort([acked])` → 断言 `claimed==1, acked==1` + 自洽 + `outboxRow`（status=ACKED, attempt_count=0, last_failure_code=null）。复用 Stage 19 helper；boot：embedded-postgres + Flyway + JdbcForwardingOutbox（不 boot runtime） |
| MI20-002 | 2 场景 B IT（断路器全状态机 + lease 回收交织） | 场景 B `scenario_b_circuit_breaker_full_state_machine_with_lease_reclaim`：`RouteCircuitBreaker(2, 122_000, clock)` + `fakeDeliveryPort([retry,retry,acked])` + worker 7 参构造器 + 4 ticks（step 61_000）→ 断言 `claimed==4, retried==2, skipped==1, acked==1` + 自洽 + `breaker.stateOf(routeHandle)==CLOSED` + `outboxRow`（status=ACKED, attempt_count=2） |
| MI20-003 | 3 文档同步 | decision §8 加 Stage 20 bullet + §9 链接；ICD（边界标题加 Stage 20 + Stage 20 边界条 + Open Issues 两盲区标记已验证）；yaml（`stage20_scope` + 顶部 description）；L2 `forwarding-persistence` 新增 §21（两场景设计 + simulateCrashedDispatch + 时间控制复用）；L2 `forwarding-outbox-inbox` §10；L1 4+1 视图 6 文件（README/physical/scenarios/development/process/logical/ARCHITECTURE）按 `agent-bus-4plus1-view-rebound` 回灌；本双语文档 |
| MI20-004 | 4 构建验证 + 提交 | `mvn -f agent-bus/pom.xml test` green（186 + 2 验证回填 IT ≈ 188）；ArchUnit green；§6.2 文本扫描不触发；commit + push（experimental，用户已授权自主推进） |

## 5. deferred + 风险（明示边界）

**风险（需关注）：**

- **场景 B 4-tick 断路器 + 租约回收交织的接线正确性**：这是 Stage 20 最复杂的场景，多个组件协同（breaker 三态 + claimDue 两 reclaim 子句 + worker 三 skip 路径 + attempt_count 语义）。任何一环接线错（breaker 冷却判断的 clock 与 tick instant 不同源、claimDue 两子句 OR 优先级、breaker skip 后 lease_until 是否真过期）都会让 4-tick 链路偏离预期。设计阶段已逐 tick 推演（§2.3 链路图），IT 实现时若断言不符，按链路图逐 tick 排查。
- **`simulateCrashedDispatch` 的 raw JDBC 不破 DDL CHECK**：UPDATE 必须满足 `ck_outbox_lease_status`（DISPATCHING ⇒ lease_owner 非空）+ `ck_outbox_lease_paired`（lease_owner/lease_until 成对）。设计已确认字段配对合法（§3 发现 2），IT 实现时 lease_owner 必须非空、lease_until 必须与 lease_owner 同设。
- **breaker `cooldownMillis` 与 tick step 的协调**：场景 B `cooldownMillis=122_000`、step `61_000`，需 tick 3（T0+122000）时 `now-openedAt` 仍 < cooldown（61000 < 122000 → 短路）、tick 4（T0+183000）时 >= cooldown（122000 >= 122000 → HALF_OPEN）。边界刚好相等（122000 >= 122000 true）—— 若实测发现边界差一，微调 cooldown 或 step。

**deferred（明示边界，不在 Stage 20 范围）：**

- **真实 scheduler / polling cadence**：Stage 20 用注入 `TickSource` 推进时间，**无真实 scheduler**（§6.1 + H2/H3）。
- **breaker 状态持久化**：`RouteCircuitBreaker` 状态在内存（`ConcurrentHashMap`），进程重启丢失。Stage 20 验证的是单进程内三态机 + lease 回收协同，跨重启 OPEN 状态保持 deferred。
- **多 worker 并发 claim 真实竞争**：Stage 20 单 worker + 注入 TickSource，多 worker 并发（SKIP LOCKED 真实竞争）未验证。场景 A 的「卡住持有者被另一 worker 回收」用「同 worker 下一 tick」模拟，真实多 worker 接手 deferred。
- **breaker 参数调优 / per-route 配置**：`failureThreshold`/`cooldownMillis` 用测试注入值（2/122s），生产默认值、per-route 配置 deferred。
- **`MapEndpointResolver` → registry resolver**：生产 resolver 由 Stage 3 registry 集成实现（registry runtime 物理实现仍 H2/H3）；Stage 20 IT 用 `MapEndpointResolver`。
- **`openjiuwen.version` 构建债**（Stage 17 盲区，沿用）：同事 `20dc622f` 引入 `com.openjiuwen:agent-core-java` 依赖但 property 未定义。Stage 20 不 boot runtime → 不触发该依赖链，但仍需 `mvn -f agent-bus/pom.xml test` 走 m2 旧 jar 绕过 broken workspace pom。
- **push/pull/MQ 最终裁决**：仍 H2/H3。

## 6. Stage 20 落地总结（**已完成**，2026-06）

Stage 20 按 §2 / §4 计划落地，实际结果与计划一致，**无偏差、无超出原计划的发现**（Stage 19 的 flaky 修复已在 Stage 19 完成，Stage 20 沿用 `@Isolated` 即可）：

- **场景 A（租约过期卡住持有者回收）**：`simulateCrashedDispatch`（raw JDBC UPDATE 推 DISPATCHING + 过期 lease，通过 DDL CHECK）+ 单 tick `MutableEpochClock`=T0 → `fakeDeliveryPort([acked])` → claimDue 第三类 reclaim 子句（`status='DISPATCHING' AND lease_until<=:now`）申领 → ACKED。断言 `claimed==1, acked==1` + `outboxRow`（ACKED, attempt_count=0, last_failure_code=null）。闭合 Stage 19 盲区 1。
- **场景 B（断路器全状态机 + 租约回收交织，最高价值）**：`RouteCircuitBreaker(2, 122_000, clock)` + `fakeDeliveryPort([retry,retry,acked])` + worker 7 参构造器 + 4 ticks（step 61_000）。断言 `claimed==4, retried==2, skipped==1, acked==1` + `breaker.stateOf(routeHandle)==CLOSED` + `outboxRow`（ACKED, attempt_count=2）。链路按 §2.3 链路图逐 tick 成立：tick1/2 连续 retry 触发 OPEN、tick3 OPEN 短路留 DISPATCHING（skipped）、tick4 卡住持有者 reclaim 子句回收 + HALF_OPEN 探测放行 + ACKED + CLOSED。闭合 Stage 19 盲区 2，并证明两个 reclaim 子句 + breaker 三态 + worker skip 路径 + attempt_count 语义在真实 Postgres 上协同正确，`probeInFlight` 不泄漏。
- **结果**：**188 tests green**（Stage 19 的 186 + 2 验证回填 IT），ArchUnit green，**无生产代码改动**（纯测试阶段），§6.2 不变，§6.1 不受影响（注入 TickSource，无真实 scheduler）。
- **§4 MI20-003 文档同步**已按计划完成：decision §8 Stage 20 bullet + §9 链接、ICD（边界标题 + Stage 20 边界条 + Open Issues 两盲区收口）、yaml（description + `stage20_scope`）、L2 `forwarding-persistence` §21、L2 `forwarding-outbox-inbox` §10、L1 4+1 视图 6 文件（README/physical/scenarios/development/process/logical/ARCHITECTURE）+ 本双语文档 §6。grep 验证全部 12 文档含 Stage 20；所有 `186 tests` 残留均为 Stage 19 时点快照（增量审计惯例），无过时引用。
- **下一步**（未裁决）：Stage 20 是纯验证回填阶段，无生产代码增量；C3 outbox 的「retry 往返 + 租约回收 + 断路器」三条端到端生命线至此全部验证通过。§5 的 deferred（真实 scheduler、breaker 状态持久化、多 worker 并发、registry resolver、push/pull/MQ 裁决）仍是独立后续 Stage 候选，待用户裁决方向。

## 相关文档

- Stage 19 计划：[`agent-bus-stage18-review-and-stage19-plan`](agent-bus-stage18-review-and-stage19-plan.md)（重投往返生命周期端到端 —— Stage 20 复用其全部时间控制基础设施，闭合其明示的两个 deferred 盲区）。
- C3 裁决：[`agent-bus-forwarding-runtime-decision`](../review-packets/agent-bus-forwarding-runtime-decision.md)（`adopted-c3`；§8 Stage 20 许可段 + §9 链接）。
- runtime 契约：[`ICD-Agent-Bus-Forwarding-Runtime`](../../05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md)（Stage 20 边界条 + Open Issues 两盲区收口）。
- 持久化 L2：[`forwarding-persistence`](../../../../architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md)（Stage 20 决策：验证回填，§21）。
- outbox-inbox L2：[`forwarding-outbox-inbox`](../../../../architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md)（§10 Stage 20 要点）。
- 验证机制源头：
  - `agent-bus/src/main/java/com/huawei/ascend/bus/forwarding/runtime/persistence/jdbc/JdbcForwardingOutbox.java` `claimDue`（卡住持有者 reclaim 子句 `status='DISPATCHING' AND lease_until<=:now`）；
  - `…/runtime/RouteCircuitBreaker.java`（Stage 16 三态机 CLOSED→OPEN→HALF_OPEN + `stateOf` 可观测 + `probeInFlight` 单探测）；
  - `…/runtime/ForwardingDispatcherWorker.java`（7 参构造器注入 breaker + 三 skip 路径）；
  - `agent-bus/src/test/java/com/huawei/ascend/bus/forwarding/runtime/C3ForwardingRetryLifecycleIntegrationTest.java`（Stage 19 helper 复用源：`MutableEpochClock`/`advanceableTickSource`/`fakeDeliveryPort`/`outboxRow`）。
