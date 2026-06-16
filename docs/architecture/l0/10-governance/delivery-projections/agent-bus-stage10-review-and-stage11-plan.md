---
artifact_type: delivery_projection
version: agent-bus-stage10-review-and-stage11-plan
status: draft
source_commit: 50fc8685
source_stage10_plan: docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage9-review-and-stage10-plan.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
target_module: agent-bus
---

# agent-bus Stage 10 评审与 Stage 11 计划

## 0. 结论

最新提交 `50fc8685` 可以作为 Stage 10 的阶段性成果接受：worker lease 异常恢复（per-record try-catch `ForwardingLeaseException` → skip）、lease 续约（`DispatchLeasePolicy`）、`DispatchTickResult` 可观测（skipped + 计数自洽）、`ForwardingDispatchLoop` 骨架（`TickSource` / `IdleStrategy` 注入，无 scheduler / 线程）全部落地，**134 tests green**，并守住路径 B（不引入 JDBC / Flyway）。Stage 10 把 Stage 9 的 lease-safe 底座接入了 worker 运行态，dispatcher worker 从 skeleton 推进为「正确处理 lease 生命周期」的可运行 dispatch loop。

但诚实评审暴露了 3 个运行态裂缝（MI11-001..003），它们都是**接真实 DB / transport 前的必要前置**——若不先修，接了 JDBC adapter / 真实投递绑定也只是把缺陷物理化：

- **MI11-001**：续约触发时机依赖 tick 入参。`runOnce` 的 `remaining = leaseUntilMillisEpoch - nowMillisEpoch` 中两者都是 tick 入参（整个 tick 不变），而 `DispatchLoop.run` 每次 tick 都用 `leaseUntil = now + leaseDurationMillis` 构造 → `remaining` 恒等于 `leaseDurationMillis`，典型配置下续约在自然 loop 驱动路径下**永不触发**，目前只能靠 harness 构造「接近过期的 leaseUntil」覆盖续约代码路径。
- **MI11-002**：`runOnce` 的 try-catch 只捕获 `ForwardingLeaseException`。真实投递绑定（HTTP / gRPC）的 `deliver` 抛出非 lease 异常（IOException / 超时 / 反序列化）时会冒泡中断整个 tick，后续 record 不处理、`DispatchTickResult` 不构造。
- **MI11-003**：`DispatchLoop.run` 无 tick 异常契约——`runOnce` 冒泡的异常会中断整个 loop 并跳过聚合 / `IdleStrategy`。

简短判断：

- Stage 10 方向正确，worker 运行态的 lease 生命周期（异常恢复 + 续约 + 调度责任 + 可观测）已跑通，路径 B 再确认。
- 上述 3 个裂缝在 in-memory 路径下不显现（deliver 瞬时、无并发、时钟固定），但都是接真实运行时前的必要前置；继续推进 DB / transport 前必须先修。
- Stage 11 主轴经人类确认为**运行态完善批次**：修复这 3 个裂缝，保持路径 B（不接 DB / transport）；真实持久化 / 真实投递绑定 / agent-runtime 集成仍 deferred（Stage 12+ 候选）。

## 1. 本次提交审查

### 1.1 完成情况

本次提交（`50fc8685`，rebase 整合远端 `234f1640` agent-runtime L1 文档后 fast-forward 推送；`6be33959` 为同会话独立的 arch-docs-dir-rename 源码路径引用收尾 commit）完成：

- MI10-001 worker lease 异常恢复：`runOnce` per-record 处理外包 try-catch `ForwardingLeaseException` → `skipped++ continue`；`DispatchTickResult` 增 `skipped` 并校验 `claimed == acked + retried + dlqd + expired + skipped`。
- MI10-002 lease 续约：`DispatchLeasePolicy`（`renewBeforeExpiryMillis` / `leaseExtensionMillis`，`DISABLED = (0, 1)`），deliver 前检查剩余 TTL，不足则 `claimPort.renewLease(...)`；renew 返回 false（lease 被 reclaim / 不再 DISPATCHING）同 skip。
- MI10-003 `DispatchTickResult` 可观测：由 MI10-001 的 `skipped` + 自洽校验收口，不单列行为测试。
- MI10-004 dispatch 调度责任：`ForwardingDispatchLoop` 纯 Java 骨架（`TickSource` / `IdleStrategy` 注入，无 clock / scheduler / 线程），聚合 `DispatchTickResult` 满足与单 tick 相同自洽不变量。
- MI10-005 DB / migration 归属经人类再确认为**路径 B**（不引入 JDBC / Flyway；DDL / SQL 仍 contract / draft；in-memory lease-guard harness 作行为替身）。
- 文档同步：L1 × 4 + L2 × 2 + ICD + yaml + decision.md §8 + stage10-plan §7。

验收判断：

- MI10-001..005 全部收口，134 tests green（`AgentBusForwardingRuntimeContractTest` 34 个：29 基准 + 5 个 Stage 10 行为测试）。
- 生产代码仍纯 Java（无 JDBC / broker client / scheduler / 线程），由 ArchUnit（`AgentBusForwardingSpiPurityTest` / `AgentBusDependencyBoundaryTest`）强制。
- worker lease 异常恢复有 harness 覆盖（`worker_skips_record_when_lease_reclaimed_mid_tick`）；续约有 3 个 harness（renew / 不 renew / renew 失败）；dispatch loop 有 harness（`dispatch_loop_drives_ticks_from_injected_source_until_it_stops`）。
- **但续约触发时机（MI11-001）、deliver 非 lease 异常（MI11-002）、loop 异常契约（MI11-003）是接真实 DB / transport 前的必要前置**，Stage 11 必须先修，再谈真实持久化 / 投递绑定。

## 2. 当前修改意见

| 编号 | 问题 | 严重度 | 证据 | 修改意见 |
|---|---|---|---|---|
| MI11-001 | lease 续约触发时机依赖 tick 入参，自然 loop 驱动下永不触发：续约判断「剩余 TTL」用的 `remaining = leaseUntilMillisEpoch - nowMillisEpoch` 中 `nowMillisEpoch` 与 `leaseUntilMillisEpoch` 都是 `runOnce` 的 tick 入参（整个 tick 不变）；`ForwardingDispatchLoop.run` 每次 tick 用 `leaseUntil = now + leaseDurationMillis` → `remaining` 恒等于 `leaseDurationMillis`，典型配置下（`leaseDurationMillis ≥ renewBeforeExpiryMillis`）续约永远不触发。 | 中-高 | `ForwardingDispatcherWorker.runOnce` 行 120-129 续约逻辑，行 121 `long remaining = leaseUntilMillisEpoch - nowMillisEpoch`；`ForwardingDispatchLoop.run` 行 84-122（`worker.runOnce(tenantId, now, limit, leaseOwner, now + leaseDurationMillis)`）。现有 3 个续约 harness 只能靠**构造接近过期的 leaseUntil**（如 `NOW + 500`）触发续约代码路径，真实 loop 驱动下不会自然触发。 | 引入最小可注入 `EpochClock` 端口（纯 Java，`long epochMillis()`，默认 `SystemEpochClock` 走 `System::currentTimeMillis`），worker 续约判断改用 `clock.epochMillis()` 而非 tick 入参 `nowMillisEpoch`，使续约在真实运行时能基于 deliver 耗时 / 墙钟接近过期正确触发；in-memory 测试注入可控时钟覆盖续约时机（替代当前「构造接近过期 leaseUntil」的间接覆盖）。更新 `forwarding-persistence.md §5 / §5.1` 的「worker 无 clock」承诺为「worker 通过注入 `EpochClock` 端口获取时间用于 lease 续约判断，不持调度器 / 线程 / registry / transport」。 |
| MI11-002 | `deliver` 非 lease 异常未处理：`runOnce` 的 try-catch 只捕获 `ForwardingLeaseException`；真实投递绑定的 `deliver` 抛 RuntimeException（IOException / 超时 / 反序列化）会冒泡中断整个 tick，后续 claimed record 不处理，`DispatchTickResult` 不构造。 | 中 | `ForwardingDispatcherWorker.runOnce` 行 130 `ForwardingDeliveryResult result = deliveryPort.deliver(record, nowMillisEpoch)`；行 151 `} catch (ForwardingLeaseException e)`。按 ICD `deliver` 应返回 `ForwardingDeliveryResult`（含 outcome）不抛，但真实 HTTP / gRPC 绑定很可能违反。 | worker 对 `deliver` 的非 lease RuntimeException 兜底为 `skipped`（record 留 `DISPATCHING`，lease 过期后被 reclaim 重投，**不丢消息**），tick 不中断；契约主路径仍是「`deliver` 不抛非 lease 异常，底层异常由真实适配器映射为 `ForwardingDeliveryResult`（如 `delivery_timeout` / `receiver_unavailable`）」写入 ICD + javadoc。harness 覆盖 `deliver` 抛 RuntimeException → tick 不中断、record skipped、留 DISPATCHING。 |
| MI11-003 | `DispatchLoop.run` 无 tick 异常契约：`runOnce` 冒泡的异常（MI11-002 兜底后，正常 tick 不应再抛非 lease 异常；但仍需明确契约）会中断整个 loop 并跳过聚合 / `IdleStrategy`。 | 中-低 | `ForwardingDispatchLoop.run` 行 84-122 循环调 `worker.runOnce(...)`，无 try-catch；`runOnce` 行 94-104 在 `tenantId` / `leaseOwner` blank、`limit <= 0` 时抛 `IllegalArgumentException`。 | 明确 `runOnce` 异常契约：仅入参非法时抛 `IllegalArgumentException`（调用方 bug，fail-fast），tick 内 `deliver` / lease 异常已由 MI10-001 / MI11-002 兜底为 `skipped`、不抛。`DispatchLoop.run` 传播 fail-fast 是**正确语义**（不静默吞调用方 bug），不加兜底——契约写入 `forwarding-persistence.md §5.1` + worker / loop javadoc。 |

## 3. Stage 11 目标

Stage 11 的目标是把 Stage 10 的 dispatch-loop runtime **完善为接真实 DB / transport 前的正确运行态**：修复续约触发时机（引入 `EpochClock`）、`deliver` 非 lease 异常兜底、`runOnce` / loop 异常契约三个裂缝，使 worker 在真实并发 / 异常路径下的行为正确且可观测。Stage 11 主轴经人类确认为运行态完善批次，**保持路径 B**：

> 完成 MI11-001（`EpochClock` + 续约判断改墙钟）、MI11-002（`deliver` 非 lease 异常兜底为 skipped、契约 deliver 不抛）、MI11-003（`runOnce` 异常契约 + loop fail-fast 文档化），保持路径 B（不引入 JDBC / Flyway / transport）；真实持久化 / 真实投递绑定 / agent-runtime 集成 deferred Stage 12+。

Stage 11 仍作为较大批次执行，但顺序清楚：先修 worker 运行态的三个裂缝（MI11-001 → 002 → 003），再同步文档。**不接真实 DB / transport**——那是 Stage 12+ 的候选，需独立的 DB 产品 / migration 归属裁决。

## 4. Stage 11 开发切片

### 切片 1：MI11-001 lease 续约时机正确性（引入 EpochClock）

把续约判断从「tick 入参 leaseUntil − tick now（恒满租期、永不触发）」改为「基于注入时钟的真实墙钟」：

- 新增纯 Java `EpochClock` 端口（`forwarding/runtime/EpochClock.java`，`long epochMillis()`），默认实现 `SystemEpochClock`（`System::currentTimeMillis`）。
- `ForwardingDispatcherWorker` 加重载构造器接受 `EpochClock`（三参构造器内部用 `SystemEpochClock`，向后兼容）；`runOnce` 续约判断 `remaining = leaseUntilMillisEpoch - clock.epochMillis()`。
- 更新 `forwarding-persistence.md §5 / §5.1`「worker 无 clock」承诺为「worker 通过注入 `EpochClock` 端口获取时间用于 lease 续约判断，不持调度器 / 线程」。
- harness：注入可控时钟（`AtomicLong` 或 `long[]`），`leaseUntil = NOW + 30_000`（正常），但 deliver 前**推进时钟**到 `NOW + 29_500` → `remaining = 500 < 1_000` → renew；时钟充足（`NOW + 1_000`）→ 不 renew。替代现有「构造接近过期 leaseUntil」的间接覆盖（可改写或保留为回归）。

DoD：

- 续约判断基于注入 `EpochClock`（真实运行时 `System` 时钟能基于 deliver 耗时触发）。
- in-memory 注入时钟覆盖「时钟接近过期 → renew」「时钟充足 → 不 renew」「renew 失败 → skip」。
- `EpochClock` 是 JDK 纯端口，不违反 ArchUnit 纯度（`java.lang.System.currentTimeMillis` 不在禁止列表）。
- 诚实标注：真实「deliver 耗时驱动续约」的端到端验证 deferred 到接真实 deliver（Stage 12+）；in-memory 下 deliver 瞬时、靠注入时钟覆盖逻辑路径。

### 切片 2：MI11-002 deliver 非 lease 异常兜底

使 worker 在 `deliver` 抛非 lease 异常时不中断 tick：

- `runOnce` 的 deliver 阶段单独 try-catch RuntimeException（或扩展现有 catch）：`deliver` 抛非 lease RuntimeException → `skipped++`，不进 `mark*`，tick 继续其余 record。record 留 `DISPATCHING`，lease 过期后被 reclaim 重投（不丢消息）。
- 现有 `catch (ForwardingLeaseException)` → skipped 保留。
- 契约：`ICD-Agent-Bus-Forwarding-Runtime` + `ForwardingDeliveryPort.deliver` javadoc 明确「`deliver` 不抛非 lease 异常；底层异常（网络 / 超时 / 反序列化）由真实适配器映射为 `ForwardingDeliveryResult`（`delivery_timeout` / `receiver_unavailable` 等）」；worker 兜底是防御性的，不替代契约。
- harness：`deliver` 抛 RuntimeException → `tick.claimed == 1`、`tick.acked == 0`、`tick.skipped == 1`、record 留 `DISPATCHING`。

DoD：

- `deliver` 非 lease 异常不中断 tick，record 计 skipped 并留 DISPATCHING（待重投）。
- 契约写入 ICD + javadoc；真实适配器的异常映射责任明确。
- `DispatchTickResult` 自洽不变（skipped 统一计数 lease-skip 与 deliver-error-skip）。

### 切片 3：MI11-003 runOnce 异常契约 + loop fail-fast

明确「什么该兜底、什么该 fail-fast」：

- `runOnce` 异常契约写入 javadoc：仅入参非法（`tenantId` / `leaseOwner` blank、`limit <= 0`）抛 `IllegalArgumentException`（调用方 bug，fail-fast）；tick 内 `deliver` / lease 异常已由 MI10-001 / MI11-002 兜底为 `skipped`、不抛。
- `DispatchLoop.run` 传播 fail-fast 是**正确语义**：`runOnce` 仅在调用方 bug 时抛，loop 不静默吞（不加 tick 异常兜底，避免掩盖调用方 bug）。契约写入 `forwarding-persistence.md §5.1` + loop javadoc。

DoD：

- `runOnce` / loop 的异常契约文档化、可被 harness 断言（如 blank tenantId → `IllegalArgumentException`，loop 传播）。
- 不引入「loop 静默吞 tick 异常」的过度兜底。

### 切片 4：文档同步

同步：

- `architecture/L1-High-Level-Design/agent-bus/README.md`（Stage 11 引用 + 测试数）
- `architecture/L1-High-Level-Design/agent-bus/development.md`
- `architecture/L1-High-Level-Design/agent-bus/process.md`
- `architecture/L1-High-Level-Design/agent-bus/physical.md`
- `architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md`
- `architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md`（§5 续约改墙钟 / §5.1 无 clock 承诺更新 / §13 Stage 11 决策表）
- `docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md`（deliver 不抛契约）
- `docs/architecture/l0/05-contracts/machine-readable/agent-bus-forwarding-runtime.v1.yaml`
- `docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md`（§9 Stage 11 行）

同步重点：

- Stage 11 是运行态完善（lease 续约时机 + deliver 异常兜底 + 异常契约），**不**宣称真实 DB / transport / 集成已落地。
- 文档继续标注 DDL / SQL 为 contract / draft（路径 B 不变）。
- 「worker 无 clock」承诺更新为「通过注入 `EpochClock` 端口获取时间」。

## 5. Stage 11 可接受结果

可以接受：

- MI11-001：续约判断基于注入 `EpochClock`（真实墙钟），自然 loop 驱动下能基于 deliver 耗时触发；in-memory 注入时钟覆盖续约时机。
- MI11-002：`deliver` 非 lease 异常兜底为 skipped（record 留 DISPATCHING 待重投），tick 不中断；契约 deliver 不抛写入 ICD。
- MI11-003：`runOnce` 异常契约明确（入参非法 fail-fast），loop 传播是正确语义，文档化。
- 134 tests 保持 green，并新增 MI11 harness（续约时机、deliver 异常、异常契约断言）。
- 路径 B 不变：不引入 JDBC / Flyway / transport / scheduler。

不能接受：

- 续约仍依赖 tick 入参（自然 loop 下永不触发）。
- `deliver` 非 lease 异常中断 tick、静默丢消息。
- 引入真实 DB / transport / scheduler（超出 Stage 11）。
- 引入「loop 静默吞 tick 异常」的过度兜底，掩盖调用方 bug。
- 让 `agent-bus` 写 Task execution state；绕过 routeHandle；放 payload body。

## 6. 给施工智能体的提示

这轮任务继续是大批次，顺序：切片 1（`EpochClock` + 续约时机）→ 切片 2（deliver 异常兜底）→ 切片 3（异常契约）→ 切片 4（文档同步）。推荐一次提交包含全部切片。

关键约束：

- `EpochClock` 是纯 Java 端口（`long epochMillis()`），默认 `SystemEpochClock` 走 `System::currentTimeMillis`；不引入 Spring / JDBC / broker / scheduler。ArchUnit（`AgentBusForwardingSpiPurityTest`）必须保持 green。
- MI11-002 的兜底归入现有 `skipped` 计数，不新增 `DispatchTickResult` 字段（保持自洽校验不变）。
- MI11-003 **不加** loop 兜底——fail-fast 是正确语义，过度兜底会掩盖调用方 bug。
- 路径 B：不接真实 DB / transport / migration；DDL / SQL 仍 contract / draft。

测试基线：当前 134 tests green；Stage 11 完成后应保持 green 并新增 MI11 harness（续约时机 ~2、deliver 异常 ~1、异常契约 ~1，预期 137-138）。构建命令见 `build-env-maven-via-settings-xml`（system mvn + `~/.m2/settings.xml` + Red Hat JDK 21）。

如果 MI11-001 的 `EpochClock` 引入与 ArchUnit 纯度冲突，退回到「契约文档化 + 把真实续约时机验证 deferred 到接真实时钟」的方案 B，但必须先尝试引入 `EpochClock`（JDK 纯端口，预期不冲突）。

## 7. 执行记录

- 切片 1-4 一次提交完成。Stage 11 主轴为运行态完善批次，修复接真实 DB / transport 前的 3 个运行态裂缝，保持路径 B（不引入 JDBC / Flyway / transport）。
- **切片 1（MI11-001 lease 续约时机）**：新增纯 Java `EpochClock` 端口（`forwarding/runtime/EpochClock.java`，`long epochMillis()`，`SYSTEM = System::currentTimeMillis`）；`ForwardingDispatcherWorker` 增带 `EpochClock` 的重载构造器（原 3 参构造器内部用 `EpochClock.SYSTEM` 向后兼容）；`runOnce` 续约判断改 `remaining = leaseUntilMillisEpoch − clock.epochMillis()`（deliver 前读注入时钟的真实墙钟），使自然 dispatch loop 下耗时 deliver 接近 lease TTL 时续约能真正触发。续约 3 个 harness 改为注入 `long[]` 可控时钟覆盖（接近过期 → renew / 充足 → 不 renew / renew 失败 → skip），替代原「构造接近过期 leaseUntil」的间接覆盖。
- **切片 2（MI11-002 deliver 非 lease 异常兜底）**：`runOnce` deliver 阶段单独 try-catch `RuntimeException` → `skipped++ continue`（record 留 `DISPATCHING`，lease 过期后 reclaim 重投，不丢消息）；原 `catch (ForwardingLeaseException) → skipped` 保留；契约写入 `ForwardingDeliveryPort.deliver` javadoc + ICD（真实 transport 绑定应把网络 / 超时 / 反序列化异常映射为 `ForwardingDeliveryResult`，**不应抛**非 lease 异常，worker 兜底是防御性 fallback）。harness `worker_skips_record_when_delivery_throws` 覆盖（`UncheckedIOException` → claimed=1 / acked=0 / skipped=1 / 留 DISPATCHING）。
- **切片 3（MI11-003 runOnce 异常契约 + loop fail-fast）**：`runOnce` 仅入参非法（`tenantId` / `leaseOwner` blank、`limit <= 0`）抛 `IllegalArgumentException`（调用方 bug fail-fast）；`ForwardingDispatchLoop.run` 传播 fail-fast 是正确语义，不加 loop 级吞没（避免掩盖调用方 bug）；契约写入 worker / loop javadoc + `forwarding-persistence.md §5.1`。harness `run_once_fails_fast_on_blank_tenant_and_loop_propagates` 覆盖（blank tenant / blank owner / limit<=0 → `IllegalArgumentException`，loop 传播）。
- **切片 4（文档同步）**：L1 × 4（README / development / process / physical，Stage 11 引用 + 134→136 tests + `EpochClock` 引入）、L2 × 2（`forwarding-persistence` §5 续约改墙钟 / §5.1 时钟承诺更新 + MI11-003 契约 / 新增 §13 Stage 11 决策表；`forwarding-outbox-inbox` §8 端口投影 + §10 已交付段）、ICD（§边界 Stage 11 项 + deliver 不抛契约）、yaml（`stage11_scope` + 2 个 contract_tests）、decision.md（§8 Stage 11 行）。
- **验收**：MI11-001 / 002 / 003 全部收口，**136 tests green**（`AgentBusForwardingRuntimeContractTest` 36 个：29 基准 + 5 Stage 10 + 2 Stage 11 行为测试）。生产代码仍纯 Java（`EpochClock` 是 JDK 纯端口，`System::currentTimeMillis` 不在禁止列表，ArchUnit 纯度 green）。路径 B 不变：不引入 JDBC / Flyway / 真实投递绑定 / scheduler。
- **诚实标注**：真实「deliver 耗时驱动续约」的端到端验证 deferred 到接真实 deliver（Stage 12+）；in-memory 下 deliver 瞬时，靠注入时钟覆盖逻辑路径。
- **后续 deferred**：真实持久化（JDBC adapter / Flyway migration 归属 / lease store 物理实现 / polling / 并发抢占原语 / backpressure 参数）、真实投递绑定（dispatcher worker → receiver transport；HTTP / gRPC / 内部 RPC）、agent-runtime 集成 / 受控调用路径、数据库产品 + RLS 确认——均需独立 H2/H3 裁决（Stage 12+ 候选）。
