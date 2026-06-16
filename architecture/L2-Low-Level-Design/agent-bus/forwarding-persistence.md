---
level: L2
module: agent-bus
view: development
status: draft
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
source_l2_runtime: architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md
source_icd_runtime: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md
source_icd_design: docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding.md
target_module: agent-bus
---

# agent-bus L2 技术设计：C3 持久化（outbox / inbox schema、claim / lease、migration 草案）

> 命名说明：本设计架构语义使用 L0 逻辑名 `agent-runtime` / `agent-core`。`agent-runtime` 已落地为同名模块（原 `agent-service` 已重命名）；`agent-core` 当前实现落点为 `agent-execution-engine`。

## 1. 目标

把 Stage 7 的 C3「最小骨架」（领域模型 + 端口 + 状态机 + in-memory 替身）推进为「可落真实持久化的运行态底座」（Stage 8 计划 §3）：

- 补齐 `ForwardingOutboxRecord` / `ForwardingInboxRecord` 显式 record 模型，承载 runtime ICD 全部必填字段（MI8-002）。
- 把 due-message 查询从裸 `findRetryable(now)` 升级为 **claim / lease** 端口（`ForwardingOutboxClaimPort`），防多实例重复投递（MI8-001）。
- 提供 outbox / inbox 两张表的 schema 草案 + Postgres DDL 草稿 + migration / rollback 说明。
- 提供 dispatcher worker skeleton（claim → deliver → ACK / RETRY / DLQ / EXPIRED）与抽象 delivery 端口。
- 用 harness 锁住 record 字段一致性、claim / lease 语义、幂等、重试、DLQ、禁止 Task state、禁止 broker 依赖。

## 2. 非目标（Stage 8 §6 护栏）

> 「如果数据库产品或 migration 归属无法确认，则停在 schema 草案 + repository port + in-memory lease harness，不要直接引入生产数据库依赖。」

`agent-bus` 当前 **未接入 Flyway、未声明 JDBC driver / ORM 依赖**（`agent-bus/pom.xml` 仅含 `spring-boot-starter-test` + `archunit-junit5`）。因此 Stage 8 **不引入生产数据库依赖**：

- **不**把 JDBC driver / ORM / Flyway 加进 `agent-bus`。
- **不**把 DDL 当作已执行 migration（§7 的 DDL 是设计草稿，标注「未执行」）。
- **不**接真实 receiver transport（HTTP / gRPC / broker）；delivery 端口保持抽象，fake delivery 仅在 test fixture。
- **不**写 Task execution state；**不**绕过 `routeHandle`；**不**放 payload body。

真实 JDBC adapter、Flyway migration 归属、polling / lease store 的物理实现、真实 delivery 绑定是 **Stage 9+**，需先确认数据库产品 / migration 归属（decision §6.1 / Stage 8 计划 §6）。

## 3. 两张表 schema

两张表的字段与 [`ICD-Agent-Bus-Forwarding-Runtime`](../../../docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md) 的 record 字段逐一对齐，Java record（`ForwardingOutboxRecord` / `ForwardingInboxRecord`）是同一 schema 的运行态投影。

### 3.1 `agent_bus_forwarding_outbox`

发送端 durable queue。唯一键 `(tenant_id, message_id)`。

| Column | Type | Required | Mutable | 来源（record 字段） | 说明 |
|---|---|---|---|---|---|
| `tenant_id` | varchar | ✓ | ✗ | `tenantId` | Rule R-C.c；非空非 blank；必须等于 routeHandle 的 tenant scope。 |
| `message_id` | varchar | ✓ | ✗ | `messageId.value` | `ForwardingMessageId`；唯一键组成部分。 |
| `source_service_id` | varchar | ✓ | ✗ | `sourceServiceId` | 发起转发的 service instance（gateway 写入，MI8-002）。 |
| `target_service_id` | varchar | ✓ | ✗ | `targetServiceId` | 目标 service instance（discovery 经 routeHandle 投影，非物理 endpoint）。 |
| `route_handle` | varchar | ✓ | ✗ | `routeHandle.value` | opaque；封装 endpoint / topic / routeKey；转发方不暴露物理 endpoint（HD4）。 |
| `payload_ref` | varchar | 条件 | ✗ | `payloadRef` | `DATA_BEARING` 必填，`CONTROL_ONLY` 可空；载荷走 data reference path。 |
| `status` | varchar | ✓ | ✓ | `status`（enum） | `PENDING` / `DISPATCHING` / `ACKED` / `RETRY_SCHEDULED` / `DLQ` / `EXPIRED`；CHECK 约束。 |
| `attempt_count` | integer | ✓ | ✓ | `attemptCount` | 从 0 起，每次 RETRY 递增。 |
| `next_attempt_at` | bigint | 条件 | ✓ | `nextAttemptAtMillisEpoch` | 仅 `RETRY_SCHEDULED` 必填；claim due 选取条件（≤ now）。 |
| `created_at` | bigint | ✓ | ✗ | `createdAtMillisEpoch` | 入队时间（epoch millis）。 |
| `updated_at` | bigint | ✓ | ✓ | `updatedAtMillisEpoch` | 最近状态变更时间。 |
| `last_failure_code` | varchar | ✗ | ✓ | `lastFailureCode`（enum） | `ForwardingFailureCode`；终态 `ACKED` 时为 null。 |
| `lease_owner` | varchar | ✗ | ✓ | `lease.leaseOwner` | **Stage 8 additive**；claim 持有者；null 表示未被 claim。 |
| `lease_until` | bigint | ✗ | ✓ | `lease.leaseUntilMillisEpoch` | **Stage 8 additive**；claim 独占截止时间；过期可被重新 claim。 |

唯一约束：`(tenant_id, message_id)`。
状态 CHECK：`status IN ('PENDING','DISPATCHING','ACKED','RETRY_SCHEDULED','DLQ','EXPIRED')`。

### 3.2 `agent_bus_forwarding_inbox`

接收端去重 / 幂等 / 审计。去重键 `(tenant_id, message_id, consumer_service_id)`。

| Column | Type | Required | Mutable | 来源（record 字段） | 说明 |
|---|---|---|---|---|---|
| `tenant_id` | varchar | ✓ | ✗ | `tenantId` | Rule R-C.c；必须等于 envelope 的 tenantId。 |
| `message_id` | varchar | ✓ | ✗ | `messageId.value` | 与 outbox messageId 对应；去重键组成部分。 |
| `consumer_service_id` | varchar | ✓ | ✗ | `consumerServiceId` | 消费该消息的 service instance；不同 consumer 各自独立去重。 |
| `status` | varchar | ✓ | ✓ | `status`（enum） | `RECEIVED` / `DUPLICATE_SUPPRESSED` / `CONSUMED` / `REJECTED`；CHECK 约束。 |
| `received_at` | bigint | ✓ | ✗ | `receivedAtMillisEpoch` | 首次接收时间。 |
| `consumed_at` | bigint | ✗ | ✓ | `consumedAtMillisEpoch` | 仅 `CONSUMED` 必填。 |
| `failure_code` | varchar | ✗ | ✓ | `failureCode`（enum） | 仅 `REJECTED` / `DUPLICATE_SUPPRESSED` 必填。 |

唯一约束 / 去重键：`(tenant_id, message_id, consumer_service_id)`。
状态 CHECK：`status IN ('RECEIVED','DUPLICATE_SUPPRESSED','CONSUMED','REJECTED')`。

> `idempotency_key`（envelope 字段）**不进** inbox 去重键，降级为审计字段（MI8-004）：去重以 `(tenantId, messageId, consumerServiceId)` 为主。如需保留 `idempotency_key` 供审计，作为 inbox 的可选非键列后续追加，不改去重语义。

## 4. claim / lease 并发语义

替代裸 `findRetryable(now)`（MI8-001）。真实持久化下多个 dispatcher 实例会并发抢同一条消息，必须用 claim / lease 防重复投递：

- **claim tenant-scoped**：`claimDue(tenantId, now, limit, leaseOwner, leaseUntil)` 只在指定 tenant 内选取，跨 tenant 不命中（Rule R-C.c）。
- **单租约**：同一 outbox record 同一时刻最多被一个 `leaseOwner` 持有；在 `lease_until` 之前，其他 owner 的 claim 跳过它。
- **due 条件**：`status = PENDING`（fresh），或 `status = RETRY_SCHEDULED AND next_attempt_at <= now`（retry due），或 `status = DISPATCHING AND lease_until <= now`（stuck holder reclaim）。
- **terminal 不可 claim**：`ACKED` / `DLQ` / `EXPIRED` 永不 claimable。
- **原子性**：claim + 状态迁移（`PENDING`/`RETRY_SCHEDULED → DISPATCHING`，或 reclaim 原地续 lease）必须在同一事务 / 等价 CAS 中完成。JDBC 实现建议 `SELECT ... FOR UPDATE SKIP LOCKED` + `UPDATE ... SET status, lease_owner, lease_until`（Postgres）。
- **续约 / 释放**：`renewLease`（持有人延长）、`releaseLease`（持有人在 terminal 后释放）——只对当前持有人生效，他人持有时 no-op。

`lease_owner` / `lease_until` 是 Stage 8 additive 字段，在 record、schema、ICD 三处一致（§3.1 / [`ICD-Agent-Bus-Forwarding-Runtime`](../../../docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md) / yaml）。

## 5. dispatcher worker skeleton 与 delivery 端口

`ForwardingDispatcherWorker`（runtime 包，纯 Java）是 claim / deliver / ack / retry 半边生命周期，与 `ForwardingDispatcher`（accept / enqueue 网关角色）分离（MI8-003）。单次同步 tick `runOnce`：

1. `claimDue` 声明到期记录（已原子迁移到 `DISPATCHING` 并 stamped lease）。
2. **Stage 10（MI10-002）**：deliver 前按 `DispatchLeasePolicy` 检查剩余 lease TTL——低于阈值则 `claimPort.renewLease(...)` 续约；renew 返回 false（lease 已被 reclaim / 不再 DISPATCHING）则 skip 该 record，不投递。
3. 逐条调用抽象 `ForwardingDeliveryPort.deliver(record, now)`，仅消费 `routeHandle`（**不**暴露物理 endpoint）。
4. 按 `ForwardingDeliveryResult.outcome()` 路由：`ACKED → markAcked`、`RETRY_SCHEDULED → scheduleRetry`（更新 attemptCount / nextAttemptAt / lastFailureCode）、`DLQ → moveToDlq`、`EXPIRED → markExpired`。
5. **Stage 10（MI10-001）**：deliver / mark* 包在 per-record try-catch `ForwardingLeaseException` 中——lease guard 拒绝（reclaim / stale / foreign / expired owner）时 skip 该 record，tick 继续其余 record。`DispatchTickResult(claimed, acked, retried, dlqd, expired, skipped)` 校验 `claimed == acked + retried + dlqd + expired + skipped`，保证计数自洽、可观测。

边界：worker 无线程、无 scheduler、无 registry、无 transport；真实 polling cadence / threading / backpressure / 具体 delivery 绑定 deferred 后续阶段。worker 不写 Task execution state。fake delivery（`InMemoryForwardingDelivery`）仅在 test fixture，让 ACK / RETRY / DLQ / EXPIRED 与 lease 续约 / skip 路径在无网络下可被 harness 覆盖。

> **in-memory vs JDBC**：in-memory lease harness 按 owner（不按 `lease_until` 过期）裁决 lease-guarded mutation；故"renew-or-lose-the-ack"（`WHERE lease_until > now`）是 §7.2 的 SQL contract，不在 in-memory 断言。lease 续约的"renew 后超 TTL 仍丢 ack"语义同样由 §7.2 SQL 编码，真实 adapter 落地后补 Testcontainers 覆盖。

### 5.1 dispatch 调度责任（Stage 10，MI10-004）

`runOnce` 是单次 tick；谁驱动循环、idle 退避、并发分片，是调用方（`agent-runtime` 受控路径 / 真实 scheduler）的责任，不进 `agent-bus`。Stage 10 交付纯 Java `ForwardingDispatchLoop` 骨架，把这份责任契约化：

- `TickSource`（注入下一 tick 时刻；`OptionalLong.empty()` 表示停止）——loop 不持有 clock，可被 fixed-rate executor / scheduler bean / 测试驱动。
- `IdleStrategy`（空 tick 退避；`NO_BACKOFF` 为 no-op）——loop 不 sleep，退避策略外部注入。
- 聚合 `DispatchTickResult`（满足与单 tick 相同的自洽不变量）。
- loop 无线程、无 scheduler、无 transport、无 clock——真实 polling cadence / threading / 并发 worker 分片是调用方决策，deferred 后续阶段。claim / lease 已防多 worker 重复投递（§4），分片只减少空 claim。

## 6. MI8 决策（Stage 8 计划 §2 收口）

| MI | 决策 | 落点 |
|---|---|---|
| MI8-001 | due-message 查询升级为 claim / lease 端口，不补裸 `findRetryable(now)` | `ForwardingOutboxClaimPort.claimDue`；L2 §8 已改写；§4 并发语义 |
| MI8-002 | `sourceServiceId` / `targetServiceId` 在 record（gateway 写入），不进 envelope | `ForwardingOutboxRecord`；`enqueue` / `dispatch` 签名带 source/target |
| MI8-003 | 拆清 accept/enqueue（`ForwardingDispatcher`）与 claim/deliver/ack/retry（`ForwardingDispatcherWorker`） | dispatcher javadoc 修正 + worker 新增 |
| MI8-004 | `idempotencyKey` 降级为审计字段；去重键不变 `(tenantId, messageId, consumerServiceId)` | §3.2 注；ICD / L2 §5 同步 |
| MI8-005 | C3 最终确认为 `adopted-c3`；DB 产品 / migration 归属延期 Stage 9+ | decision.md；本文档 §2 护栏 |

## 7. Postgres DDL 草稿（未执行）

> 以下 DDL 是 **设计草稿**，Stage 8 **不执行**（`agent-bus` 未接 Flyway / JDBC）。真实 migration 归属确认后（Stage 9+），落入受治理的 migration 路径（如 `agent-bus/src/main/resources/db/migration/` 或共享 schema 模块），版本号 / 命名遵循该路径约定。

```sql
-- agent_bus_forwarding_outbox —— 发送端 durable queue
CREATE TABLE agent_bus_forwarding_outbox (
    tenant_id         VARCHAR(128)  NOT NULL,
    message_id        VARCHAR(128)  NOT NULL,
    source_service_id VARCHAR(128)  NOT NULL,
    target_service_id VARCHAR(128)  NOT NULL,
    route_handle      VARCHAR(512)  NOT NULL,
    payload_ref       VARCHAR(1024),
    status            VARCHAR(32)   NOT NULL,
    attempt_count     INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at   BIGINT,
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    last_failure_code VARCHAR(32),
    lease_owner       VARCHAR(128),
    lease_until       BIGINT,
    CONSTRAINT pk_outbox PRIMARY KEY (tenant_id, message_id),
    CONSTRAINT ck_outbox_status CHECK (
        status IN ('PENDING','DISPATCHING','ACKED','RETRY_SCHEDULED','DLQ','EXPIRED')),
    -- MI9-006: condition-field CHECK constraints mirror the Java record
    -- invariants (MI9-003) at the DB layer, so a buggy adapter cannot persist
    -- an illegal row even if it bypasses the constructor.
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_lease_paired CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_outbox_retry_has_next_attempt CHECK (
        status <> 'RETRY_SCHEDULED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_outbox_failure_code CHECK (
        (status = 'ACKED' AND last_failure_code IS NULL)
        OR (status IN ('DLQ','EXPIRED','RETRY_SCHEDULED') AND last_failure_code IS NOT NULL)
        OR (status IN ('PENDING','DISPATCHING'))),
    CONSTRAINT ck_outbox_lease_status CHECK (
        (status IN ('PENDING','ACKED','RETRY_SCHEDULED','DLQ','EXPIRED') AND lease_owner IS NULL)
        OR (status = 'DISPATCHING' AND lease_owner IS NOT NULL))
);

-- claim due: 非终态 + due + 未被有效 lease 持有；Postgres SKIP LOCKED 防多实例重复投递
CREATE INDEX ix_outbox_claim_due
    ON agent_bus_forwarding_outbox (tenant_id, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- agent_bus_forwarding_inbox —— 接收端去重 / 审计
CREATE TABLE agent_bus_forwarding_inbox (
    tenant_id          VARCHAR(128) NOT NULL,
    message_id         VARCHAR(128) NOT NULL,
    consumer_service_id VARCHAR(128) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    received_at        BIGINT       NOT NULL,
    consumed_at        BIGINT,
    failure_code       VARCHAR(32),
    CONSTRAINT pk_inbox PRIMARY KEY (tenant_id, message_id, consumer_service_id),
    CONSTRAINT ck_inbox_status CHECK (
        status IN ('RECEIVED','DUPLICATE_SUPPRESSED','CONSUMED','REJECTED')),
    -- MI9-006: condition-field CHECK constraints mirror the Java record (MI9-003)
    CONSTRAINT ck_inbox_consumed_at CHECK (
        status <> 'CONSUMED' OR consumed_at IS NOT NULL),
    CONSTRAINT ck_inbox_failure_code CHECK (
        (status IN ('REJECTED','DUPLICATE_SUPPRESSED') AND failure_code IS NOT NULL)
        OR (status IN ('RECEIVED','CONSUMED') AND failure_code IS NULL)),
    CONSTRAINT ck_inbox_dup_code CHECK (
        status <> 'DUPLICATE_SUPPRESSED' OR failure_code = 'duplicate_suppressed')
);
```

> 列宽 / 索引策略是草案；真实实现按实际值域与查询模式定。`SKIP LOCKED` 是 Postgres 9.5+ / MySQL 8.0+ 行为；如选用其它产品需等价并发抢占原语（advisory lock / `SELECT ... FOR UPDATE`）。

### 7.1 claim SQL（lease stamping, Postgres SKIP LOCKED）

`claimDue` 的等价 SQL（MI8-001）：原子地选出 due、tenant-scoped、非终态、lease 空闲/过期的记录，迁移到 `DISPATCHING` 并 stamped 独占 lease。`FOR UPDATE SKIP LOCKED` 让并发 dispatcher 跳过已被同伴锁住的行，不阻塞。

```sql
UPDATE agent_bus_forwarding_outbox AS o
SET status      = 'DISPATCHING',
    lease_owner = :leaseOwner,
    lease_until = :leaseUntil,
    updated_at  = :now
WHERE (o.tenant_id, o.message_id) IN (
    SELECT tenant_id, message_id
    FROM agent_bus_forwarding_outbox
    WHERE tenant_id = :tenantId
      AND ( (status = 'PENDING')
            OR (status = 'RETRY_SCHEDULED' AND next_attempt_at <= :now)
            OR (status = 'DISPATCHING' AND lease_until <= :now) )  -- stuck-holder reclaim
    ORDER BY next_attempt_at NULLS FIRST
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
RETURNING o.*;
```

### 7.2 lease-owner guarded state-update SQL

`markAcked` / `scheduleRetry` / `moveToDlq` / `markExpired` 的状态变更都必须被 lease-owner 守卫（MI9-001）：只有当前持有有效 `DISPATCHING` lease 的 owner 才能改状态。`WHERE` 子句编码守卫，0 行受影响即代表 stale/foreign/expired owner，adapter 抛 `ForwardingLeaseException`（record 不被改动）。ACK 为例（terminal 清 lease，MI9-002）：

```sql
UPDATE agent_bus_forwarding_outbox
SET status            = 'ACKED',
    last_failure_code = NULL,
    lease_owner       = NULL,     -- MI9-002: terminal clears the lease
    lease_until       = NULL,
    updated_at        = :now
WHERE tenant_id   = :tenantId
  AND message_id  = :messageId
  AND status      = 'DISPATCHING'
  AND lease_owner = :leaseOwner
  AND lease_until > :now;
-- scheduleRetry / moveToDlq / markExpired share the identical WHERE guard.
-- RETRY additionally: next_attempt_at = :nextAttemptAt, attempt_count + 1,
-- last_failure_code = :retryableCode, lease_owner/lease_until = NULL (MI9-002).
```

### 7.3 RLS（Postgres Row-Level Security）

若采用 Postgres 且启用 RLS 做 tenant 行级隔离：

- **何时启用**：在两张表上启用 RLS，policy 按 `tenant_id = current_setting('app.tenant_id')` 过滤；`app.tenant_id` 由应用连接设置。
- **迁移顺序**：先建表 + 数据 → 再 `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` + `CREATE POLICY`；应用层仍带 `tenantId` 过滤（纵深防御，RLS 不替代应用层隔离）。
- **回滚**：`DROP POLICY` + `ALTER TABLE ... DISABLE ROW LEVEL SECURITY`；不丢数据。
- **是否启用**：Stage 8 **不决定**；RLS 启用与否、与 registry 隔离策略的一致性，随数据库产品确认在 Stage 9+ 裁决（§2 护栏）。

## 8. migration / rollback 说明

- **归属未定**：`agent-bus` 当前无 Flyway。Stage 9+ 确认数据库产品与 migration 归属（agent-bus 自有 vs 共享 schema 模块 vs runtime 受控路径）后，再决定 Flyway / Liquibase / 手工 SQL。
- **版本与命名**：遵循所选 migration 工具约定（如 Flyway `V<n>__create_agent_bus_forwarding_outbox.sql`）；本草案不是最终文件名。
- **向前兼容**：`lease_owner` / `lease_until` 为 nullable additive 列，旧读写不受影响（yaml `compatibility.additive_fields_allowed: true`）。
- **回滚**：`DROP TABLE agent_bus_forwarding_inbox, agent_bus_forwarding_outbox`（回滚后 outbox/inbox 能力消失，回到 in-memory 替身；不影响其它模块）。
- **breaking change**：字段重命名 / 去除必填列 / 改唯一键属 breaking，需 ADR / review packet（yaml `compatibility.breaking_change_requires_adr: true`）。

## 9. 字段一致性表（ICD ↔ schema ↔ Java record ↔ DDL）

| ICD outbox 字段 | yaml key | Java `ForwardingOutboxRecord` | DDL column |
|---|---|---|---|
| tenantId | outbox_record.required.tenantId | tenantId | tenant_id |
| messageId | outbox_record.required.messageId | messageId | message_id |
| sourceServiceId | outbox_record.required.sourceServiceId | sourceServiceId | source_service_id |
| targetServiceId | outbox_record.required.targetServiceId | targetServiceId | target_service_id |
| routeHandle | outbox_record.required.routeHandle | routeHandle | route_handle |
| payloadRef（条件） | outbox_record.conditional.payloadRef | payloadRef | payload_ref |
| status | outbox_record.required.status | status | status |
| attemptCount | outbox_record.required.attemptCount | attemptCount | attempt_count |
| nextAttemptAt（条件） | outbox_record.conditional.nextAttemptAt | nextAttemptAtMillisEpoch | next_attempt_at |
| createdAt | outbox_record.required.createdAt | createdAtMillisEpoch | created_at |
| updatedAt | outbox_record.required.updatedAt | updatedAtMillisEpoch | updated_at |
| lastFailureCode（条件） | outbox_record.conditional.lastFailureCode | lastFailureCode | last_failure_code |
| —（Stage 8 additive） | lease（见 yaml stage8） | lease | lease_owner / lease_until |

inbox 字段同理一一对应（`consumerServiceId → consumer_service_id`、`receivedAt → received_at`、`consumedAt → consumed_at`、`failureCode → failure_code`）。

harness（`AgentBusForwardingRuntimeContractTest`）结构化校验 `ForwardingOutboxRecord` 的 record 组件名，ICD 文本与 yaml 字段由 contract test 镜像，三者漂移即构建失败。

## 10. DoD 自检

- ✓ record 模型补齐（`ForwardingOutboxRecord` / `ForwardingInboxRecord` / `ForwardingLease`），承载 ICD 必填字段（§3）。
- ✓ claim / lease 端口清晰（`ForwardingOutboxClaimPort`），替代 `findRetryable(now)`（§4）。
- ✓ 两张表 schema + DDL 草案 + migration / rollback 说明（§3 / §7 / §8）。
- ✓ dispatcher worker skeleton + 抽象 delivery 端口 + fake delivery 测试（§5）。
- ✓ MI8-001..005 收口（§6）。
- ✓ 无 concrete broker / MQ client；无 JDBC driver；无 Task execution state 写入；不绕 routeHandle；不放 payload body（§2 + harness 强制）。
- ✓ 未引入生产数据库依赖，停在 schema 草案 + repository port + in-memory lease harness（§2 护栏）。

## 11. Stage 9 决策（lease-safe / persistence-ready）

Stage 9（MI9-001..006）在 Stage 8 的持久化准备之上补齐**并发安全**与**约束完整性**，仍**不引入**真实 DB / migration——DB / migration 归属未由人类确认，按 Stage 9 计划 §5「路径 B」执行：交付完整 DDL CHECK 约束 + claim / state-update SQL contract + in-memory lease harness，JDBC / Flyway 不进 `agent-bus`。

| MI | 决策 | 落点 |
|---|---|---|
| MI9-001 | DISPATCHING → terminal/retry 状态变更全部升级为 lease-owner guarded mutation；`markDispatching` 移除（claim 是进入 DISPATCHING 的唯一路径） | `ForwardingOutboxPort` 四方法带 `leaseOwner`；`ForwardingLeaseException`（RECORD_NOT_FOUND/NO_LEASE/OWNER_MISMATCH/NOT_DISPATCHING）；in-mem `leaseGuardedMutate`；§7.2 SQL |
| MI9-002 | lease 生命周期闭环：ACKED/DLQ/EXPIRED/RETRY_SCHEDULED 清 lease；仅 DISPATCHING 持 lease | in-mem `leaseGuardedMutate` 清 lease；record 不变量；DDL `ck_outbox_lease_status` |
| MI9-003 | record 条件不变量固化到 Java 构造器 / schema / DDL CHECK / harness | `ForwardingOutboxRecord.validateStatusInvariants`、`ForwardingInboxRecord.validateStatusInvariants`；§7 CHECK；harness 非法构造测试 |
| MI9-004 | failure-code classification（retryable/non-retryable/dedup）；`retry(...)` 只接 retryable，`dlq(...)` 拒 dedup | `ForwardingFailureCode.Classification`；`ForwardingDeliveryResult` 构造校验；`scheduleRetry`/`moveToDlq` |
| MI9-005 | `targetServiceId` 来自 discovery metadata / gateway audit，不解包 routeHandle | `ForwardingOutboxRecord` javadoc；`enqueue` javadoc；yaml `owner: discovery-via-routeHandle` |
| MI9-006 | DDL 草案补完整条件 CHECK 约束 | §7 DDL CHECK；SQL contract harness 锁定 |

Stage 9 DoD 自检：

- ✓ lease-owner guarded mutation：stale worker（lease 被 reclaim）ACK 失败为 OWNER_MISMATCH（harness 覆盖）。
- ✓ lease 生命周期闭环：terminal + retry 清 lease，仅 DISPATCHING 持 lease（harness 覆盖）。
- ✓ record 条件不变量进入 Java 构造器 + DDL CHECK + harness（非法构造失败测试）。
- ✓ failure-code 分类明确，retry/dlq 路由在构造期被分类约束（harness 覆盖）。
- ✓ DB / migration 归属：路径 B——归属未由人类确认，不引入 JDBC / Flyway；交付完整 DDL CHECK 约束 + claim / state-update SQL contract + in-memory lease harness。
- ✓ 未引入生产数据库依赖；DDL / SQL 仍标注为 contract / draft（§2 护栏不变）。

## 12. Stage 10 决策（dispatch-loop runtime）

Stage 10（MI10-001..005）把 Stage 9 的 lease-safe 底座接入 worker 运行态，使 dispatcher worker 从 skeleton 推进为「正确处理 lease 生命周期」的可运行 dispatch loop；DB / migration 归属经人类再确认为 **路径 B**（不引入 JDBC / Flyway；DDL / SQL 仍 contract / draft）。真实持久化（JDBC adapter / Flyway / 真实投递绑定）deferred 后续阶段，独立批次处理。

| MI | 决策 | 落点 |
|---|---|---|
| MI10-001 | per-record try-catch `ForwardingLeaseException` → skip（兑现 `ForwardingLeaseException` javadoc 的 skip 承诺），tick 不因 lease 竞态中断；`DispatchTickResult` 增 `skipped` 并校验 `claimed == acked + retried + dlqd + expired + skipped` | `ForwardingDispatcherWorker.runOnce`（try-catch + skipped）；`DispatchTickResult` 6 字段 + 自洽校验；`worker_skips_record_when_lease_reclaimed_mid_tick` |
| MI10-002 | lease 续约契约：deliver 前按 `DispatchLeasePolicy`（renewBeforeExpiryMillis / leaseExtensionMillis）检查剩余 TTL，不足则 `renewLease`；renew 返回 false 同 skip；不续约且超 TTL 则 ack 失败为 lease guard 拒绝 | `DispatchLeasePolicy` record；`runOnce` deliver 前续约；`worker_renews_short_lease_before_delivery` / `worker_does_not_renew_when_lease_sufficient` / `worker_skips_when_lease_renew_fails` |
| MI10-004 | dispatch 调度责任定义：`ForwardingDispatchLoop` 骨架（`TickSource` / `IdleStrategy` 注入，无 clock / scheduler / 线程）；`runOnce` 调用契约写入 §5.1 / javadoc | `ForwardingDispatchLoop`；`dispatch_loop_drives_ticks_from_injected_source_until_it_stops` |
| MI10-005 | DB / migration 归属经人类再确认为 **路径 B** | 本文档 §2 / §11；decision.md §8；plan §7；不引入 JDBC / Flyway |

> MI10-003（`DispatchTickResult` 可观测）由 MI10-001 的 `skipped` 字段 + 自洽校验收口，不单列行为测试。

Stage 10 DoD 自检：

- ✓ worker lease 异常恢复：reclaimed record 不中断 tick，计入 skipped（harness 覆盖）。
- ✓ lease 续约：长 deliver 前按阈值 renew；renew 失败 / 不续约超 TTL 明确失败（harness 覆盖）。
- ✓ `DispatchTickResult` 可观测：skipped + 计数自洽（构造校验 + harness）。
- ✓ dispatch 调度责任：`ForwardingDispatchLoop` 无 scheduler / 线程，trigger 注入，harness 覆盖。
- ✓ DB / migration 归属：路径 B 再确认（人类裁决，Stage 10 切片 4）；不引入 JDBC / Flyway；DDL / SQL 仍 contract / draft。

相关文档：

- C3 运行态 L2：[`forwarding-outbox-inbox`](forwarding-outbox-inbox.md)（组件 / 状态机 / 失败语义）。
- runtime ICD：[`ICD-Agent-Bus-Forwarding-Runtime`](../../../docs/architecture/l0/05-contracts/human-readable/ICD-agent-bus-forwarding-runtime.md)。
- machine-readable schema：[`agent-bus-forwarding-runtime.v1.yaml`](../../../docs/architecture/l0/05-contracts/machine-readable/agent-bus-forwarding-runtime.v1.yaml)。
- 裁决：[`decision`](../../../docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md)。
- Stage 8 计划：[`agent-bus-stage7-review-and-stage8-plan`](../../../docs/architecture/l0/10-governance/delivery-projections/agent-bus-stage7-review-and-stage8-plan.md)。
