---
level: L2
view: process
status: draft
source_icd_design: ICD-agent-bus-forwarding.md
source_l2: architecture/docs/L2/agent-bus/forwarding-outbox-inbox.md
source_decision: docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-decision.md
---

# ICD-Agent-Bus-Forwarding-Runtime

> 命名说明：本 ICD 架构语义使用 L0 逻辑名 `agent-runtime` / `agent-core`（当前实现 / 兼容落点分别为 `agent-service` / `agent-execution-engine`）；代码路径、Maven artifact、`module-metadata.yaml`、forbidden dependencies 仍保留旧名。转发两端在架构语义上是 runtime-to-runtime；当表达一般服务实例时使用 `service instance` / 「服务实例」。

## 目的

定义 `agent-bus` 类 MQ 转发**运行态承载**（候选 C3，database outbox / inbox）的 record schema、唯一约束、禁止字段、失败码与状态机引用。本 ICD 是 Stage 7 切片 3 的契约产物：

- 把 [`ICD-Agent-Bus-Forwarding`](ICD-agent-bus-forwarding.md)（Stage 4 设计态语义）与 [`L2 forwarding-outbox-inbox`](../../../../architecture/docs/L2/agent-bus/forwarding-outbox-inbox.md)（Stage 7 组件 / 状态机）投影为可被 harness 字段级校验的 schema。
- 明确 outbox / inbox record 的每个字段：owner、是否必填、是否可变、脱敏要求。
- 锁定禁止字段，防止 payload body / token stream / Task execution state / 物理 endpoint 渗入 record。

本 ICD 是 **draft / 契约态**：Stage 7 交付 schema 草案、端口接口、状态机、harness、in-memory test double；**Stage 8 补齐 record 模型、claim / lease 端口、dispatcher worker skeleton 与 schema / migration 草案**（[`forwarding-persistence`](../../../../architecture/docs/L2/agent-bus/forwarding-persistence.md)）；**真实持久化实现（JDBC adapter / Flyway migration / polling / lease store 物理实现 / 真实投递绑定）是 Stage 9+**，需先确认数据库产品 / migration 归属（[`decision §5/§6.1`](../../10-governance/review-packets/agent-bus-forwarding-runtime-decision.md)、Stage 8 计划 §6 护栏）。

machine-readable schema 见 [`agent-bus-forwarding-runtime.v1.yaml`](../machine-readable/agent-bus-forwarding-runtime.v1.yaml)。

## 适用读者

`agent-bus` forwarding runtime owner、outbox / inbox 端口实现者、harness 生成器、`agent-runtime` owner、架构评审者。

## outbox record 字段

outbox 是发送端 durable queue，承载一条 forwarding 消息从入队到终态（ACKED / DLQ / EXPIRED）的完整生命周期。

| Field | Required | Mutable | Owner | 脱敏 | 说明 |
|---|---|---|---|---|---|
| `tenantId` | ✓ | ✗ | 调用方 / discovery（tenant scope） | tenant 标识，审计保留，不脱敏 | Rule R-C.c；强制非空非 blank；必须等于 `routeHandle` 的 tenant scope。 |
| `messageId` | ✓ | ✗ | forwarding 底座 | opaque 标识，不脱敏 | `ForwardingMessageId`；outbox 唯一键组成部分。 |
| `sourceServiceId` | ✓ | ✗ | 调用方 | 服务标识，审计保留 | 发起转发的 service instance。 |
| `targetServiceId` | ✓ | ✗ | discovery（经 routeHandle） | 服务标识，审计保留 | 目标 service instance；来自 Stage 3 discovery，非物理 endpoint。 |
| `routeHandle` | ✓ | ✗ | discovery | opaque，不脱敏 | `ForwardingRouteHandle`；opaque 封装 endpoint / topic / routeKey，转发方不暴露物理 endpoint。 |
| `payloadRef` | 条件必填 | ✗ | 调用方 | 引用，不内联正文 | MI5-003 方案 B：`DATA_BEARING` 消息必填，`CONTROL_ONLY` 可省略；一旦出现，载荷走 data reference path。 |
| `status` | ✓ | ✓ | dispatcher / 状态机 | 枚举，不脱敏 | `ForwardingStatus.Outbox`：PENDING / DISPATCHING / ACKED / RETRY_SCHEDULED / DLQ / EXPIRED。 |
| `attemptCount` | ✓ | ✓ | dispatcher | 计数，不脱敏 | 投递尝试次数；从 0 起，每次 RETRY 递增。 |
| `nextAttemptAt` | 条件必填 | ✓ | dispatcher worker | 时间戳，不脱敏 | 仅 `RETRY_SCHEDULED` 必填；`claimDue(now)` 的 due 选取条件（MI8-001）。 |
| `createdAt` | ✓ | ✗ | 底座 | 时间戳，不脱敏 | 入队时间。 |
| `updatedAt` | ✓ | ✓ | 底座 | 时间戳，不脱敏 | 最近状态变更时间。 |
| `lastFailureCode` | ✗ | ✓ | dispatcher | 枚举，不脱敏 | 最近失败码；`ForwardingFailureCode`；终态 ACKED 时为 null。 |
| `leaseOwner` | ✗ | ✓ | dispatcher worker（claim） | owner 标识，审计保留 | **Stage 8 additive**（MI8-001）；claim 持有者；null 表示未被 claim。 |
| `leaseUntil` | ✗ | ✓ | dispatcher worker（claim） | 时间戳，不脱敏 | **Stage 8 additive**（MI8-001）；claim 独占截止时间；过期可被重新 claim。 |

唯一约束：`(tenantId, messageId)`。`leaseOwner` / `leaseUntil` 是 Stage 8 additive 字段，运行态投影为 `ForwardingLease`（Java record 嵌套 `lease` 字段）；并发抢占语义见 [`forwarding-persistence §4`](../../../../architecture/docs/L2/agent-bus/forwarding-persistence.md)。

## inbox record 字段

inbox 是接收端去重 / 幂等 / 审计记录。

| Field | Required | Mutable | Owner | 脱敏 | 说明 |
|---|---|---|---|---|---|
| `tenantId` | ✓ | ✗ | 接收方校验 | tenant 标识，审计保留 | Rule R-C.c；必须等于消息 envelope 的 tenantId。 |
| `messageId` | ✓ | ✗ | forwarding 底座 | opaque 标识，不脱敏 | 与 outbox messageId 对应；inbox 去重键组成部分。 |
| `consumerServiceId` | ✓ | ✗ | 接收方 | 服务标识，审计保留 | 消费该消息的 service instance；不同 consumer 各自独立去重。 |
| `status` | ✓ | ✓ | inbox / 状态机 | 枚举，不脱敏 | `ForwardingStatus.Inbox`：RECEIVED / DUPLICATE_SUPPRESSED / CONSUMED / REJECTED。 |
| `receivedAt` | ✓ | ✗ | inbox | 时间戳，不脱敏 | 首次接收时间。 |
| `consumedAt` | ✗ | ✓ | 接收方 | 时间戳，不脱敏 | 仅 `CONSUMED` 必填。 |
| `failureCode` | ✗ | ✓ | inbox | 枚举，不脱敏 | 仅 `REJECTED` / `DUPLICATE_SUPPRESSED` 必填；`ForwardingFailureCode`。 |

唯一约束 / 去重键：`(tenantId, messageId, consumerServiceId)`。

> MI8-004：`idempotencyKey`（envelope 字段）**不进** inbox 去重键，降级为审计字段。去重以 `(tenantId, messageId, consumerServiceId)` 为主，不随 `idempotencyKey` 变化。如需保留 `idempotencyKey` 供审计，作为 inbox 可选非键列后续追加，不改去重语义。

## 禁止字段

outbox / inbox record **始终不得**包含：

- **payload body**：大载荷走 `payloadRef` data reference path，不进 record 正文（ICD-Agent-Bus-Forwarding Forbidden Payload）。
- **token stream**：token / 凭证流不进 record（归对应 owner / 走引用）。
- **Task execution state**：`agent-bus` 不写 Task execution state，Task lifecycle owner 不变（HD4 / registry 边界）。
- **物理 endpoint**：转发方只持 opaque `routeHandle`，不直接暴露或操作物理 endpoint / topic / IP（HD4）。

## 失败码

`ForwardingFailureCode`（wire 名见 [`L2 §7`](../../../../architecture/docs/L2/agent-bus/forwarding-outbox-inbox.md)）按分类（Stage 9，MI9-004）驱动 RETRY / DLQ / REJECT 路由：

- **non-retryable**（`nonRetryable()`）：`route_not_found`（→ DLQ）、`tenant_mismatch`（拒绝）、`payload_ref_invalid`（拒绝）。
- **retryable**（`retryable()`）：`delivery_timeout`、`receiver_unavailable`、`backpressure_rejected`（→ RETRY_SCHEDULED，耗尽 → DLQ / EXPIRED）。
- **dedup**（`dedup()`）：`duplicate_suppressed`（inbox 去重命中；不是投递失败，不驱动 RETRY / DLQ）。

`ForwardingDeliveryResult.retry(...)` 只接 retryable；`dlq(...)` 拒 dedup（接受 non-retryable 或 retryable 耗尽）。分类由 `ForwardingFailureCode.Classification` 在 Java 枚举上固化，schema / DDL / harness 一致。

## 状态机引用

outbox / inbox 状态机的完整迁移表（含触发条件、终态、失败码）见 [`L2 forwarding-outbox-inbox §4`](../../../../architecture/docs/L2/agent-bus/forwarding-outbox-inbox.md)。本 ICD 不重复迁移表，只锁定 record schema；状态机由 `ForwardingStateMachine`（runtime 包，纯函数）裁决，端口实现调用状态机后持久化。

## 边界（Stage 7 / Stage 8 / Stage 9 / Stage 10）

- Stage 7：schema 草案、端口接口、状态机、harness、in-memory test double。
- Stage 8：record 模型、claim / lease 端口、dispatcher worker skeleton、schema / migration 草案（DDL 草稿，**未执行**）、in-memory lease harness。
- Stage 9：lease-owner guarded mutation、lease 生命周期闭环、record 条件不变量（Java 构造器 + DDL CHECK + harness）、failure-code classification、claim / state-update SQL contract、in-memory lease-guard harness。
- Stage 10：worker lease 异常恢复（catch `ForwardingLeaseException` + skip，`DispatchTickResult.skipped` 计数自洽可观测）、lease 续约契约（`DispatchLeasePolicy`，deliver 前按阈值 renew，失败同 skip）、dispatch 调度责任（`ForwardingDispatchLoop` 骨架，`TickSource` / `IdleStrategy` 注入，无 scheduler / 线程 / 时钟）。
- **不引入** JDBC driver / 具体 ORM / Flyway / 真实数据库实现（DB / migration 归属经人类再确认为 **路径 B**，Stage 10；真实持久化 deferred 后续阶段）。
- **不引入** broker / MQ client（broker-agnostic；C4 是后续阶段可选上层）。
- 不改 Task lifecycle owner；不绕 routeHandle；不放 payload body / token stream；不暴露物理 endpoint。

## Contract Tests（harness 镜像）

- `forwarding_runtime_outbox_record_has_required_fields`
- `forwarding_runtime_inbox_record_has_required_fields`
- `forwarding_runtime_outbox_unique_key_is_tenant_and_message_id`
- `forwarding_runtime_inbox_dedup_key_includes_consumer`
- `forwarding_runtime_forbids_payload_body_token_stream_task_state_endpoint`
- `forwarding_runtime_status_values_match_l2_state_machine`
- `forwarding_runtime_failure_codes_cover_l2_semantics`
- `forwarding_runtime_outbox_record_carries_source_and_target_service_id`（Stage 8，MI8-002）
- `claim_due_returns_only_tenant_scoped_due_non_terminal_records`（Stage 8，MI8-001）
- `claim_due_grants_exclusive_lease_one_owner_at_a_time`（Stage 8，MI8-001）
- `expired_lease_can_be_reclaimed`（Stage 8，MI8-001）
- `terminal_outbox_record_is_not_claimable`（Stage 8，MI8-001）
- `dispatcher_worker_routes_ack_retry_dlq_expired_via_fake_delivery`（Stage 8，MI8-003）
- `forwarding_persistence_does_not_write_task_state_nor_introduce_broker`（Stage 8，决策 §6.1）
- `stale_worker_acks_after_lease_reclaimed_by_another_owner_fails`（Stage 9，MI9-001）
- `lease_guarded_mutation_reports_record_not_found_and_no_lease`（Stage 9，MI9-001）
- `terminal_and_retry_states_clear_lease_only_dispatching_holds_it`（Stage 9，MI9-002）
- `outbox_record_rejects_invalid_status_invariants`（Stage 9，MI9-003）
- `inbox_record_rejects_invalid_status_invariants`（Stage 9，MI9-003）
- `failure_code_classification_drives_retry_and_dlq_routing`（Stage 9，MI9-004）
- `forwarding_persistence_ddl_enforces_record_invariants`（Stage 9，MI9-006）
- `worker_skips_record_when_lease_reclaimed_mid_tick`（Stage 10，MI10-001）
- `worker_renews_short_lease_before_delivery`（Stage 10，MI10-002）
- `worker_does_not_renew_when_lease_sufficient`（Stage 10，MI10-002）
- `worker_skips_when_lease_renew_fails`（Stage 10，MI10-002）
- `dispatch_loop_drives_ticks_from_injected_source_until_it_stops`（Stage 10，MI10-004）

harness 方法名逐字镜像本节，防 ICD / harness 漂移（同 Stage 4 约定）。

## Open Issues

- 真实 JDBC adapter + Flyway migration 归属（agent-bus 自有 vs 共享 schema 模块 vs runtime 受控路径）—— Stage 10 经人类再确认为路径 B（不引入 JDBC / Flyway），deferred 后续阶段。
- polling 间隔、lease TTL、并发抢占实现（`SKIP LOCKED` / advisory lock）、backpressure 阈值 —— Stage 9+。
- 真实投递绑定（dispatcher worker → 接收方 transport；HTTP / gRPC / 内部 RPC）—— Stage 9+。
- 数据库产品确认 + 是否启用 Postgres RLS —— Stage 9+。
