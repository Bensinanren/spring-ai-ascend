---
scope: version-draft
module: agent-bus/r-and-d-center
feature_type: functional
feature_id: Feat-Func-015
status: draft
updated: 2026-07-09
authority:
  - ../../../AGENT.md
  - ../../architecture/L0-Top-Level-Design
  - ../../architecture/L1-High-Level-Design/agent-bus
  - ./Feat-Func-014-intent-runtime-facts-projection.md
drives:
  - ../../architecture/L1-High-Level-Design/agent-bus/logical.md
  - ../../architecture/L2-Low-Level-Design/agent-bus/Feat-Func-015-agent-card-registration-and-discovery.md
---

# Agent Card 注册与发现（Agent Card Registration and Discovery）

## 1. 特性定位

**特性名称**：Agent Card 注册与发现（Agent Card Registration and Discovery）。

**特性描述**：新增支持 Agent Card 的注册功能，支持 Agent Card 的查询功能。

本特性定义框架级 Agent Card 注册与发现能力：下游 AgentLoop / workflow capability 通过 Agent Card 对外声明身份、能力描述、调用契约、治理属性和可发现元数据；注册中心负责接收 Agent Card 注册，并按租户、调用方、能力类型、标签、描述和执行约束查询可执行智能体能力集合。

意图识别需求是该基础能力的一个使用场景。该场景下，Tool / Node 将用户输入归一化为标准 `IntentRouteRequest`，再由 `agent-runtime` 查询注册中心；注册中心基于 Agent Card 返回候选能力、推荐首选、分数、证据和 opaque `route_handle`。注册中心返回的仍然是可执行智能体能力集合及其意图匹配元数据，不是业务知识、业务意图库或文档召回结果。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 草稿要求 |
|---|---|---|
| Agent Card 注册 | MUST | 下游 AgentLoop、workflow、异构 Agent 或 adapter 适配能力必须能通过 Agent Card 注册为可发现、可调用能力。 |
| Agent Card 校验 | MUST | 注册时必须校验必填字段、契约版本、能力类型、租户范围和可调用引用，拒绝结构不完整或不可执行的 Agent Card。 |
| Agent Card 更新与失效 | MUST | 支持 Agent Card 的更新、下线、失效和重复注册处理，避免查询到过期或不可用能力。 |
| 租户与调用方边界 | MUST | 注册和查询必须带租户、调用方或可见性边界，禁止跨租户 fallback。 |
| Agent Card 查询 | MUST | 支持按能力类型、名称、描述、标签、domain、契约版本、租户、调用方和执行约束查询可执行 Agent Card 集合。 |
| 可用性过滤 | MUST | 查询结果必须结合健康状态、版本兼容性和治理约束过滤不可调用能力。 |
| 推荐首选 | SHOULD | 当查询条件用于自动调度或意图路由时，返回排序后的候选集并标注推荐首选。 |
| 候选与证据保留 | SHOULD | 查询结果应保留候选列表、分数、命中字段和选择原因，供审计、排查和质量评估使用。 |
| opaque route handle | MUST | 查询结果必须返回不暴露实际网络地址或服务地址的 `route_handle` 或等价调用引用。 |
| 无能力结果 | MUST | 无匹配、无健康实例、版本不兼容或租户拒绝时返回明确失败语义，不自动兜底调用。 |
| 可查询扩展元数据 | SHOULD | Agent Card 应支持业务域、标签、描述、样例表达、关键词、意图匹配元数据等扩展字段；意图识别场景使用这些字段完成动态能力匹配。 |

## 3. Agent Card 最小字段

| 字段 | 定义 |
|---|---|
| `tenant_scope` | capability 对哪些租户可见或可调用。 |
| `agent_id` | 下游 Agent 的逻辑标识。 |
| `service_id` | 承载该 Agent 的服务标识。 |
| `capability_id` | 能力标识，可区分同一 Agent 的多个能力。 |
| `capability_name` | 能力名称。 |
| `capability_type` | `agent_loop`、`workflow`、`external_agent`、`external_workflow` 等类型。 |
| `description` | 能力自然语言描述，用于过滤、排序、排查和后续语义检索。 |
| `intent_match_metadata` | 可选意图匹配扩展元数据，如样例表达、关键词、别名、标签、适用场景；意图识别场景使用。 |
| `tags` | 能力标签。 |
| `domain` | 能力所属业务域或平台域。 |
| `framework_type` | `jiuwen`、`AgentScope`、`Versatile`、`proxy-service` 等实现或适配类型。 |
| `contract_version` | 调用契约版本。 |
| `capability_version` | 能力版本。 |
| `route_handle` | opaque 路由引用。 |
| `health_status` | `healthy`、`degraded`、`unhealthy` 等状态。 |
| `supports_streaming` | 是否支持流式结果。 |
| `supports_hitl` | 是否可能产生 `INPUT_REQUIRED`。 |
| `requires_idempotency_key` | 是否要求调用传入幂等键。 |
| `priority` | 多候选排序提示。 |
| `risk_hint` | 可选风险提示；未声明时按风险未知处理。 |
| `cancelable_hint` | 可选取消提示；未声明时不自动取消。 |

## 4. 查询输入与输出

本节定义框架级 Agent Card 查询输入与输出。意图识别场景中的 `IntentRouteRequest`、`raw_user_input`、`route_query`、runtime facts 等补充输入，统一放在第 6 节说明。

| 输入字段 | 含义 |
|---|---|
| `tenant_id` | 租户边界，查询时必须携带。 |
| `caller_ref` | 发起查询的调用方标识，可用于可见性、授权和审计。 |
| `capability_type` | 期望查询的能力类型，如 `agent_loop`、`workflow`、`external_agent`、`external_workflow`。 |
| `query_text` | 面向名称、描述、标签、样例等可查询字段的文本查询条件，可为空。 |
| `tags` / `domain` | 标签和领域过滤条件。 |
| `contract_version` | 调用契约版本要求，用于过滤不兼容能力。 |
| `framework_type` | 可选实现或适配类型过滤，如 `jiuwen`、`AgentScope`、`Versatile`、`proxy-service`。 |
| `execution_constraints` | 执行约束，如是否要求 streaming、HITL、幂等键、风险提示或可取消能力。 |
| `health_requirement` | 健康状态过滤要求。 |
| `page` / `sort` | 分页和排序参数。 |

| 输出字段 | 含义 |
|---|---|
| `agent_cards` | 满足查询条件的 Agent Card 列表。 |
| `recommended_agent_card` | 推荐首选 Agent Card；用于自动调度或意图路由等需要单一推荐的场景。 |
| `score` | 查询排序分或推荐分。 |
| `evidence` | 命中的字段、标签、描述、健康状态、版本兼容性和排序依据。 |
| `route_handle` | 对应可调用能力的 opaque 调用引用，不暴露实际网络地址或服务地址。 |
| `failure_code` | 无匹配、不可用、版本不匹配、租户拒绝等失败语义。 |

## 5. 场景与用户旅程

| 场景 | 行为 | 期望结果 |
|---|---|---|
| Agent Card 注册 | 下游 AgentLoop / workflow capability 提交 Agent Card | 注册中心校验通过后，使该能力可被查询和调用。 |
| Agent Card 更新 / 下线 | 能力描述、契约版本、健康状态或可见范围发生变化 | 注册中心更新或失效 Agent Card，后续查询不返回过期或不可用能力。 |
| 按类型和标签查询 | 调用方按 `capability_type`、`tags`、`domain` 查询 | 返回满足过滤条件的可执行 Agent Card 列表。 |
| 租户隔离查询 | 调用方携带 `tenant_id` 和 `caller_ref` 查询 | 只返回当前租户和调用方可见、可调用的能力。 |
| 可用性过滤 | Agent Card 存在但健康状态异常或契约版本不兼容 | 返回明确失败语义或过滤该能力，不自动兜底调用。 |
| 异构能力接入 | 异构 AgentLoop / workflow 通过 adapter 暴露 Agent Card | 注册中心按标准 Agent Card 返回可调用能力和 route handle。 |
| 意图识别动态能力匹配 | runtime 携带意图识别场景查询条件查询注册中心 | 返回候选能力、推荐首选、证据和 route handle，供 runtime 调用下游。 |

## 6. 意图识别场景下与 Tool / Node 的协作边界

本节仅说明 Agent Card 注册与发现在意图识别链路中的使用方式，不改变本特性作为框架级基础能力的定位。意图识别场景中，Tool / Node 负责 AgentLoop / workflow 内部的插件入口、语义归一化、静态匹配接缝和子任务调用描述生成；注册中心负责基于已注册 Agent Card 查询可执行智能体能力集合，并返回候选、推荐首选、证据和 `route_handle`。

意图识别场景在通用 Agent Card 查询输入之上，补充以下查询参考条件：

| 输入字段 | 含义 |
|---|---|
| `raw_user_input` | 用户原始输入，必须作为检索参考条件之一。 |
| `route_query` | `Feat-Func-007` 生成的路由表达，用于 Agent Card 匹配和排序。 |
| `context_summary` | 标准路由请求中携带的可用短摘要或引用；第一阶段不要求 runtime 自动生成完整业务上下文快照。 |
| `runtime_facts` | conversation、task、agent、metadata、state key、memory scope、child task 引用等可用运行态事实。 |
| `caller_agent_ref` | 发起意图映射的上游智能体引用。 |
| `constraints` | 契约版本、能力类型、domain、tags、是否支持 HITL / streaming 等执行约束。 |

| 协作点 | `agent-core` Tool / Node | `agent-bus` 注册中心 |
|---|---|---|
| 入口位置 | 位于上游 AgentLoop / workflow 内，被 AgentLoop 作为 Tool 或被 workflow 作为 Node 调用。 | 位于动态注册发现平面，由 runtime 调用。 |
| 输入来源 | 用户原始输入或澄清后的 query、显式槽位、conversation、可用运行态事实和执行约束。 | runtime 传入的查询请求，来自标准 `IntentRouteRequest`，包含原始输入、`route_query`、可用上下文摘要、租户、调用方和约束。 |
| 第一阶段职责 | 加载静态 Agent Card / 能力描述，做最小匹配、保护校验和子任务调用描述生成。 | 不参与第一阶段静态端到端链路。 |
| 第二阶段职责 | 基于 `IntentRouteRequest` 生成动态查询约束，不直接访问 bus。 | 查询可执行 Agent Card，返回候选、推荐首选、分数、证据和 route handle。 |
| 输出消费者 | `agent-runtime`。 | `agent-runtime`。 |
| 明确不做 | 不做动态注册发现，不选择健康实例，不消费 route handle，不写 Task。 | 不提供 Tool / Node 插件入口，不归一化 AgentLoop 内部请求，不调用 LLM 改写路由语义，不写 Task。 |

这条边界用于避免重复建设：动态 Agent Card 查询只在注册中心做；AgentLoop / workflow 的插件装配、语义归一化和 core 侧静态匹配只在 Tool / Node 做。

意图识别场景下，候选列表用于审计和排查，执行时默认使用推荐首选。用户不选择 Agent；如果路由错误，用户后续输入在同一 `conversation_id` 下重新识别，并由 Tool / Node 重新构造查询条件。

## 7. 行为语义与边界

- 注册中心具备查询能力，但查询对象限定为已注册、可执行、可治理的 Agent Card。
- 注册中心不保存业务意图库，不拥有 Agent 业务定义，不写 Task 状态。
- 注册中心不替代业务运行时、AgentLoop / workflow 编排器或 Tool / Node 插件入口。
- 当前版本以标签、描述、元数据过滤和排序为主；语义检索是后续增强目标。
- 查询结果必须遵守租户、调用方、健康状态和契约版本边界，不允许跨租户 fallback。
- `route_handle` 是调用引用，不是 Task 状态，不暴露实际网络地址或服务地址。

## 8. L0 / L1 架构对齐

本特性归属 L0 `agent-bus` 的 R&D Center / 注册发现能力。agent-bus L1 已明确 Agent 注册发现是目标态能力，且 bus 不拥有 Task 生命周期。本特性把该目标态能力展开为框架级 Agent Card 注册、发现与查询平面，属于 `agent-bus L1 架构变更候选`。

在意图识别场景中，`agent-runtime` 是注册中心调用方和 route handle 消费方；`agent-core` 只产出动态查询约束，不直接访问 bus。其他框架场景也可以基于同一 Agent Card 注册与发现能力查询可执行智能体能力。

## 9. 模块责任拆解

| 模块 | 责任 |
|---|---|
| `agent-bus / R&D Center` | Agent Card 注册、查询、排序、候选集、推荐首选和 route handle 返回。 |
| `agent-runtime` | 在需要动态能力发现的场景中调用注册中心，消费 route handle 并发起下游调用；在意图识别场景中依据 core 的动态查询约束调用注册中心。 |
| `agent-core` | 在意图识别场景中提供 Tool / Node 插件入口、`IntentRouteRequest` 语义归一化、第一阶段静态匹配；第二阶段只产出动态查询约束，不直接调用注册中心。 |
| `proxy-service` / adapter | 将异构 AgentLoop 或 workflow 暴露为可注册、可发现、可调用能力。 |
| `agent-client` | 通常不直接感知注册中心；在意图识别场景中只接收路由结果、进度或失败展示。 |

## 10. 查询 / 路由失败语义

| 语义 | 说明 |
|---|---|
| `NO_CAPABILITY_MATCH` | 没有匹配的 capability。 |
| `NO_AVAILABLE_AGENT` | 有匹配能力，但当前没有健康可用 Agent。 |
| `CONTRACT_VERSION_MISMATCH` | 能力存在，但契约版本不满足调用方要求。 |
| `TENANT_SCOPE_DENIED` | 能力存在，但不属于当前租户范围。 |
| `AGENT_CARD_INVALID` | Agent Card 缺少必填字段、契约无效或不可执行。 |
| `ROUTE_CONFIDENCE_LOW` | 意图识别场景扩展语义：有候选，但匹配分低于可路由阈值。 |

## 11. 开发设计待细化事项

以下事项不在当前特性需求草稿中展开，后续进入 L2 / 开发设计文档时细化。

- Agent Card 注册入口、更新/失效机制、重复注册处理、排序规则和 route handle 约束。
- route confidence 阈值建议由注册中心给出推荐分，runtime 按调用链路阈值执行；复杂策略覆盖放入 `Feat-Func-010` 第三阶段。
- 语义检索、学习排序、历史效果反馈和评估优化。
