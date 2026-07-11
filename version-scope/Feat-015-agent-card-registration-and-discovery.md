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

# Agent Card 注册与发现特性文档

## 1. 特性定位

FEAT-015 定义 `agent-bus` 逻辑域中 registry-discovery-center 单元对标准 A2A Agent Card 关联的运行时可路由能力进行主动注册、持续维护和发现的事实要求。该特性使 registry-discovery-center 能够通过可替换的 `DeploymentDiscoveryProvider` 获取部署环境中的物理实例事实，依据可信内部 `baseUrl` 主动抓取 `agent-runtime` 暴露的标准 A2A Agent Card，并通过事件监听、主动探测和周期全量对账建立、更新、降级或移除 runtime route index；gateway、event-bus 和经过授权的 `agent-runtime` 调用方能够按租户、能力、契约版本、健康状态和执行约束查询零个或多个候选 Agent Card 及其 opaque `route_handle`。

本特性解决的问题是：当平台中的 Agent 调用不能长期依赖静态配置、固定服务地址、运行实例初始化时的一次性上报或调用方本地维护的能力清单时，部署实例事实、Agent 身份、可调用能力、租户范围、契约版本、健康状态、租约有效性和路由引用必须通过统一机制持续收敛。调用方不应感知具体服务实例、物理 endpoint、部署发现源、注册表存储或健康检查机制；部署事件丢失、发现源暂时不可用、Agent Card 抓取失败、跨租户查询、租约过期、版本不兼容、能力不可用和注册中心不可用也必须具有明确结果，不得通过静默 fallback 返回错误目标。

本特性所说的 registry-discovery-center 和 `DeploymentDiscoveryProvider` 都是逻辑契约，不要求与 gateway、event-bus 或 `agent-runtime` 由同一个进程、制品或部署单元承载。产品可以提供默认注册发现实现和 Kubernetes 等默认 provider，企业客户也可以适配企业 PaaS、存量注册系统、sidecar 或 CMDB 等部署事实源；替换前提是适配实现满足本文定义的实例观察、Agent Card 主动抓取、registry reconciliation、租户隔离、版本、健康、lease、候选结果、opaque `route_handle` 和失败语义。

对下游设计和实现而言，本特性是当前版本 Agent Card 注册与发现能力的事实来源。L2 设计、部署发现源适配、`agent-runtime` Agent Card 暴露、registry reconciliation、gateway / event-bus / runtime 查询接入、测试和指南必须以本文定义的外部行为、所有权边界和失败语义为准；实现中已经存在但本文未声明的注册表存储、健康检查、实例选择、缓存或路由能力，不能自动成为当前版本对外事实承诺。

本特性面向以下角色：

- `DeploymentDiscoveryProvider`：从 Kubernetes、企业 PaaS、存量注册系统、sidecar、CMDB 或等价可信来源提供运行实例的全量快照和增量事件，包括租户、服务、实例、内部 `baseUrl`、部署版本、readiness 和 source revision 等物理事实；不定义 Agent 身份、能力或 skill。
- `agent-runtime`：在标准地址暴露标准 A2A Agent Card，供 registry-discovery-center 主动抓取；不主动注册、更新、续租或注销 registry entry。作为经过授权的查询方时，可以查询候选能力并自行完成候选筛选与选择。
- `agent-bus` registry-discovery-center 单元：消费可信部署实例事实，主动抓取并校验 Agent Card，通过事件监听、主动探测、lease 和周期全量对账维护租户隔离的 runtime route index，处理发现与路由引用解析请求，返回候选 Agent Card、治理元数据及 opaque `route_handle`，但不替调用方选择最终目标。
- `agent-bus` gateway 单元：在客户端请求进入平台时查询可处理目标能力的候选集合，并依据自身入口路由规则选择目标。
- `agent-bus` event-bus 单元：在跨服务调用转发时查询目标 service、Agent 或 capability 的候选集合，并依据自身路由治理规则选择目标。
- 平台集成方：将企业部署事实源和存量注册发现系统适配到本文定义的实例观察、主动抓取、持续对账、发现和路由引用契约。
- 测试与验收团队：按本特性定义的黑盒行为验证实例发现、Agent Card 抓取与校验、持续对账、更新、下线、失效、租户隔离、版本兼容、健康状态、候选查询和失败结果。

本特性只定义 `agent-bus` registry-discovery-center 单元基于可信部署实例事实主动抓取 Agent Card、维护运行时路由索引和提供候选发现的语义。标准 A2A Agent Card 仍是 Agent 身份、能力和调用协议的声明载体，`DeploymentDiscoveryProvider` 只提供物理实例事实，registry entry 只保存二者关联形成的租户、版本、健康、lease 和路由治理事实；`agent-bus` 不拥有 Agent 业务定义、服务实现或 Task / Run execution state，不向普通调用方暴露物理 endpoint，也不替 gateway、event-bus 或 `agent-runtime` 完成最终候选选择。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 部署实例发现适配 | MUST | registry-discovery-center 必须能够通过可替换的 `DeploymentDiscoveryProvider` 获取可信部署环境中的实例全量快照和增量事件；provider 至少提供 `tenantId`、`serviceId`、`instanceId`、内部 `baseUrl`、部署版本、readiness、source identity 和 source revision，不提供 Agent 身份、能力或 skill 定义。 |
| 标准 A2A Agent Card 主动抓取 | MUST | registry-discovery-center 必须依据 provider 给出的可信内部 `baseUrl`，从标准地址 `/.well-known/agent-card.json` 主动抓取 `agent-runtime` 暴露的标准 A2A Agent Card；抓取和校验通过后，注册中心构造关联 registry entry 并使对应运行时能力进入可发现状态，`agent-runtime` 不主动提交注册请求。 |
| Agent Card 与 registry entry 分离 | MUST | 标准 A2A Agent Card 继续表达 Agent 身份、能力和调用协议元数据；registry entry 单独承载租户、运行实例、版本、健康、lease 和路由治理事实，注册中心必须维护二者之间的稳定关联。 |
| 主动抓取与注册校验 | MUST | 注册中心必须校验 provider 来源及实例事实、抓取目标的网络边界，以及标准 A2A Agent Card 的必填协议字段、schema、可选签名和与实例事实的关联一致性；校验失败的实例不得创建可见 registry entry，也不得生成可用 route handle。 |
| Agent Card 安全抓取 | MUST | 主动抓取只能访问可信 provider 提供且符合平台策略的内部地址，并必须限制 scheme、目标网络、重定向、响应大小和超时；平台可以按部署要求使用 mTLS 或 Agent Card 签名验证，不得将未校验响应写入有效索引。 |
| 多实例注册 | MUST | 同一租户、Agent 和 capability 必须能够同时注册多个运行实例；每个实例具有稳定的 registry entry 身份，并可以作为独立候选返回。 |
| 实例观察与对账幂等 | MUST | 同一 `tenantId + serviceId + instanceId` 的重复实例事件、全量快照或相同 source revision 必须得到等价结果，不得产生重复 registry entry 或重复可见候选。 |
| 事件监听与周期全量对账 | MUST | 注册中心必须同时支持 provider 增量事件监听和周期全量 reconciliation；事件中断、source revision 缺口或注册中心重启后，必须能够通过全量快照补注册缺失实例、更新变化实例，并使已确认删除的实例进入下线流程。 |
| 更新、下线与失效 | MUST | provider 事实或 Card 内容变化时，注册中心必须重新抓取并原子更新有效快照；计划下线的实例先进入 `DRAINING` 并退出默认候选集，确认删除、grace period 结束、持续不可达或 lease / TTL 到期后移除。已经存在的实例刷新失败时，必须保留最后一次校验成功的快照、标记降级并受控重试。 |
| 多版本共存 | MUST | 同一 capability 必须能够同时存在多个 `contractVersion` 和 `capabilityVersion` 的有效运行实例；查询必须按调用方声明的版本约束过滤，无兼容版本时返回明确结果，不得静默降级到其他版本。 |
| 注册发现单元与 provider 可替换 | MUST | registry-discovery-center 必须作为 `agent-bus` 逻辑域内可独立实现、构建、部署或由企业存量注册发现系统替换的运行时单元；`DeploymentDiscoveryProvider` 必须支持按部署环境替换。不同实现必须遵守同一实例观察、主动抓取、持续对账、发现和路由引用契约。 |
| 租户与调用方边界 | MUST | provider 实例事实、Card 抓取结果和 registry entry 必须关联可信 `tenantId`；查询必须携带可信调用方身份上下文，并用于可见性、授权和审计。跨租户访问必须明确拒绝，不得跨租户 fallback。 |
| 确定性结构化查询 | MUST | gateway、event-bus 和经过授权的 `agent-runtime` 必须能够按 tenant、capability / skill、契约版本、能力版本、健康状态和显式执行约束查询可路由能力。 |
| lease 与健康状态 | MUST | registry-discovery-center 必须结合 provider readiness、主动探测、Card 刷新结果和平台 lease 策略计算 entry 的 `healthy`、`degraded`、`unhealthy` 或 `draining` 状态；lease / TTL 到期的 entry 必须退出可见候选集，调用方可以按健康条件过滤仍然有效的候选。 |
| 候选集合返回与调用方选择 | MUST | 注册中心必须返回零个或多个满足硬约束的候选 Agent Card 及 registry entry 治理事实；gateway、event-bus 或 `agent-runtime` 调用方依据自身策略完成候选筛选和最终选择。 |
| opaque route handle | MUST | 每个可路由候选必须返回 opaque `route_handle` 或等价调用引用；调用方不得依赖实际网络地址、服务地址、topic 或内部 route key。 |
| selection hint | SHOULD | 候选结果应能够携带 weight、region、deployment variant 等 `selectionHint`，供调用方执行负载均衡、就近路由或部署策略；调用方可以根据自身策略使用这些提示。 |
| 明确失败结果 | MUST | 部署发现源不可用、Agent Card 不可达或校验失败、无匹配能力、租户拒绝、lease 失效、版本不兼容、健康约束无可用候选和注册中心不可用必须具有可区分的结构化结果，不得统一折叠为“未找到”。 |
| 审计与可观测 | SHOULD | 实例观察、Card 抓取、校验、reconciliation、主动探测、状态变更、发现和 route handle 解析操作应产生可审计记录，至少关联 tenant、source、registry operation、Agent / capability、版本、健康状态、trace、结果和耗时。 |
| runtime route index 状态边界 | MUST | registry-discovery-center 只维护 Agent / service / capability 的 runtime route index 和 discovery view；发现结果不得携带或写入 Task / Run execution state。 |
| 物理实现透明 | MUST | 部署事实接入方、`agent-runtime` 和查询方不得依赖注册表使用的具体存储、缓存、健康检查、lease 调度或集群实现；这些物理机制由默认实现或企业适配实现自行决定。 |

## 3. 外部接口与入口要求

| 入口 / 契约 | 类型 | 事实要求 |
|---|---|---|
| `RegistryRequestContext` | request context | 调用方执行发现或受信任路由解析时必须携带统一治理上下文，至少包含 `tenantId`、`callerRef`、`traceId`、`requestId` 和 `deadline`。不同传输适配不得丢失这些治理语义；部署实例观察使用 provider 自身的可信 source identity 和 source revision 建立来源上下文。 |
| `DeploymentDiscoveryProvider` | deployment discovery port | registry-discovery-center 用于接入部署事实源的可替换入口。provider 必须支持读取当前实例全量快照和监听实例增量事件；可以由 Kubernetes、企业 PaaS、存量注册系统、sidecar、CMDB 或等价适配实现，但不得提供 Agent 身份、能力或 skill 定义。 |
| `DeploymentInstanceObservation` | deployment fact | 表达 provider 观察到的单个物理运行实例，至少包含 `tenantId`、`serviceId`、`instanceId`、内部 `baseUrl`、部署版本、readiness / terminating 状态、`sourceId`、`sourceRevision` 和 `observedAt`。`tenantId + serviceId + instanceId` 构成实例对账身份。 |
| `ListDeploymentInstances` | provider-to-registry operation | registry-discovery-center 在启动、周期校验、事件中断或 source revision 缺口后拉取指定 provider 的权威全量实例快照。结果必须携带 source identity、快照 revision 和零个或多个 `DeploymentInstanceObservation`，用于补注册、更新和已确认缺失实例的下线判断。 |
| `WatchDeploymentInstances` | provider-to-registry operation | provider 按 source revision 向 registry-discovery-center 提供 `ADDED`、`MODIFIED`、`TERMINATING` 和 `DELETED` 等增量实例事件，并支持从可恢复 revision 继续监听。事件不可恢复或 revision 不连续时，注册中心必须转入全量 reconciliation。 |
| `FetchAgentCard` | registry-to-runtime operation | registry-discovery-center 根据可信 `DeploymentInstanceObservation.baseUrl` 从 `/.well-known/agent-card.json` 同步抓取标准 A2A Agent Card。操作必须限制 scheme、目标网络、重定向、响应大小和超时，执行 schema 校验，并支持按部署策略使用 mTLS 或签名验证；失败时返回结构化抓取或校验结果。 |
| 标准 A2A `AgentCard` | protocol metadata | 作为 registry-discovery-center 主动抓取的 Agent 级能力声明快照，表达标准 A2A 身份、能力、skill 和调用协议元数据。Card 中的对外地址必须是平台逻辑入口；实例物理路由目标来自可信部署实例事实，并由 `RegistryEntry` 单独承载。一个 Card 可以声明多个 skill，这些 skill 作为同一运行实例的可查询索引。 |
| `RegistryEntry` | managed registry record | 表达部署实例事实与有效 Agent Card 快照关联形成的运行实例注册和路由治理事实，至少包含 `registryEntryId`、`tenantId`、`agentId`、`serviceId`、`instanceId`、`sourceId`、`sourceRevision`、Card digest、`contractVersion`、`capabilityVersion`、内部 `routeTarget`、lease、`revision`、`effectiveHealth` 和可选 `selectionHint`。该记录由 registry-discovery-center 维护，不作为 runtime 主动写入接口。 |
| `ReconcileDeploymentInstances` | registry internal operation | registry-discovery-center 将 provider 全量快照或增量事件与现有 registry entry 按实例对账身份进行幂等比较，并按需抓取 Agent Card、创建或更新 entry、转换为 `DRAINING`、标记 source stale 或移除已确认失效的 entry。重复 observation 和相同 source revision 不得产生重复记录。 |
| `ReconciliationResult` | reconciliation result | 返回 source identity、处理到的 source revision、创建 / 更新 / 未变化 / 降级 / 下线 / 移除数量，以及每个失败项的结构化原因和 `traceId`。provider 不可用时必须表达 source stale，不得把暂时无法取得快照解释为全部实例已删除。 |
| `DiscoveryQuery` | discovery request | gateway、event-bus 或经过授权的 `agent-runtime` 使用该请求查询候选。请求必须携带 `RegistryRequestContext`，并包含 `capability` 或 `skillId` 至少一项；可以附带 `contractVersion`、`capabilityVersion`、健康状态、显式 `executionConstraints`、`limit` 和 `continuationToken`。 |
| `DiscoverAgentCards` | caller-to-registry operation | 注册中心同步执行租户、调用方、capability / skill、版本、健康和执行约束过滤，并按稳定、确定性的顺序返回 `DiscoveryResult`。查询只覆盖 lease 有效且对当前 tenant 与 caller 可见的 entry。 |
| `DiscoveryCandidate` | discovery candidate | 每个候选必须包含当前可见的 Agent Card 快照、命中的 `capability` / `skillId`、opaque `routeHandle`、`effectiveHealth`、`contractVersion`、`capabilityVersion` 和可选 `selectionHint`。注册生命周期字段和物理 `routeTarget` 不进入候选结果。 |
| `DiscoveryResult` | discovery result | 返回结构化 `outcome`、零个或多个 `DiscoveryCandidate`、`nextToken` 和 `traceId`。`SUCCESS` 表达已返回候选，`NO_MATCH` 表达查询成功但没有满足约束的候选；二者不得与注册中心不可用混淆。 |
| `ResolveRouteHandle` | trusted routing operation | 只有 gateway、event-bus 或等价受信任路由层可以使用 `RegistryRequestContext + routeHandle` 同步解析内部路由目标。必须重新校验 tenant、调用方权限、entry 可见性和句柄有效性；`agent-runtime` 选择句柄后只把它传入 bus 调用链，不自行解码。 |
| `RouteResolution` | internal routing result | 向受信任路由层返回内部 `routeTarget`、route key 或等价 transport binding、契约版本、能力版本和必要的有效期事实。该结果不得暴露给普通业务调用方，也不得携带 Task / Run execution state。 |
| `RegistryFailure` | failure result | 所有操作使用统一失败结构，至少包含 `failureCode`、可编程说明、`retryable` 和 `traceId`。当前版本必须区分 `DEPLOYMENT_SOURCE_UNAVAILABLE`、`SOURCE_REVISION_GAP`、`AGENT_CARD_FETCH_FAILED`、`AGENT_CARD_SOURCE_REJECTED`、`AGENT_CARD_INVALID`、`AGENT_CARD_SIGNATURE_INVALID`、`REGISTRY_ENTRY_INVALID`、`CALLER_NOT_AUTHORIZED`、`TENANT_SCOPE_DENIED`、`ENTRY_NOT_FOUND`、`LEASE_EXPIRED`、`VERSION_UNAVAILABLE`、`HEALTH_UNAVAILABLE`、`MALFORMED_ROUTE_HANDLE`、`DEADLINE_EXCEEDED` 和 `REGISTRY_UNAVAILABLE`。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 部署实例首次发现与注册 | 可信 `DeploymentDiscoveryProvider` 观察到 ready 的 Agent Service 实例，并提供 tenant、service、instance 和内部访问地址等部署事实 | registry-discovery-center 从标准地址主动抓取并校验 A2A Agent Card，建立该实例的 registry entry | 校验成功的实例进入当前 tenant 与授权调用方的可见候选集，并可以生成 opaque `routeHandle`。 |
| Agent Card 抓取或校验失败 | 部署实例已经被发现，但 Agent Card 不可达、内容非法、来源不可信或没有平台支持的可路由接口 | 注册中心记录结构化失败，并按平台策略继续尝试获取有效 Card | 该实例在校验成功前不进入候选集，也不生成可用 `routeHandle`；后续获取有效 Card 后可以转为可发现状态。 |
| 扩缩容、滚动升级与多版本共存 | 同一 Agent Service 增加、终止或替换运行实例，新旧版本可能同时存在 | provider 报告实例集合变化，注册中心为各实例独立维护 entry、版本、生命周期和健康事实 | ready 的新实例进入候选集，计划退出的旧实例进入下线流程；不同有效版本可以共存，查询只返回满足显式版本约束的候选。 |
| Agent Card 更新 | 已注册实例仍然存在，但其 Agent Card 内容发生变化 | 注册中心重新获取并校验 Card，更新该实例对应的有效 Card 快照和发现索引 | 校验成功后，后续查询使用新 Card 事实；校验失败时继续使用最后一次有效快照，并反映当前降级状态。 |
| 部署发现中断与恢复 | provider 事件中断、注册中心重启、部署发现源暂时不可用或观察事实不连续 | 注册中心保留最后有效 registry view；部署发现恢复后，重新获取完整实例事实并执行 reconciliation | 发现链路中断不会把该来源的全部实例误判为已删除；恢复后 registry view 根据权威部署事实重新收敛。 |
| 计划下线 | provider 报告实例 terminating、缩容或计划删除 | 注册中心将 entry 转为 `DRAINING` 并停止其进入默认候选集；实例删除或下线期限结束后移除 entry | 新的发现结果不再返回该实例；entry 被移除后不再作为新的路由目标。 |
| 实例异常失联与恢复 | provider 未报告计划下线，但实例健康检查、readiness 或 Card 获取持续失败 | 注册中心更新 `effectiveHealth` 并继续根据可用部署事实和 lease 判断实例有效性；实例恢复时重新校验 Card 和健康状态 | 不满足健康约束或 lease 已失效的实例退出相应候选集；恢复且仍有效的实例可以重新进入候选集。 |
| 结构化发现及零候选结果 | gateway、event-bus 或经过授权的 `agent-runtime` 持有可信 `RegistryRequestContext`，并提供 `agentId`、`serviceId` 或 `a2aSkillId` 及可选硬约束 | 调用方执行 `DiscoverAgentCards`，注册中心按 tenant、caller、生命周期、lease、版本、A2A capabilities、输入输出模式、安全兼容性和健康条件过滤 | 满足条件时返回零个或多个 `DiscoveryCandidate`；没有候选时返回可区分的结构化 outcome，不放宽请求中的硬约束。 |
| 跨租户或无权限查询 | 请求 tenant 与可信调用方上下文不一致，或 caller 无权查看目标注册事实 | 注册中心在候选过滤和 route handle 解析前执行 tenant 与 caller 校验 | 请求被明确拒绝，不返回 Agent Card、候选数量、route handle 或物理路由信息，并记录可审计失败结果。 |
| 受信任路由层解析 routeHandle | gateway、event-bus 或等价受信任路由层持有当前 tenant 下的 `routeHandle` | 路由层调用 `ResolveRouteHandle`，注册中心校验 tenant、caller、entry 生命周期、lease 和句柄有效性 | 有效句柄解析为内部 `RouteResolution`；无效、过期或跨租户句柄返回结构化失败，物理路由只对受信任路由层可见。 |

## 5. 行为语义与边界

### 5.1 核心行为语义

#### 5.1.1 事实来源与所有权语义

- `DeploymentDiscoveryProvider` 是部署实例事实来源，只提供 `tenantId`、`serviceId`、`instanceId`、内部 `baseUrl`、部署版本、readiness / terminating 状态、source identity、source revision 和观察时间；provider 不定义 Agent 名称、A2A 能力声明或调用协议。
- `agent-runtime` 是标准 A2A Agent Card 的事实来源，只负责在标准地址暴露当前有效 Card；runtime 不主动创建、更新、续租或注销 registry entry。
- registry-discovery-center 负责主动抓取和校验 Agent Card，将部署实例事实与校验成功的 Card 快照关联为 `RegistryEntry`，并据此维护 runtime route index。注册中心不得修改原始 Card、补写缺失字段或推断 Agent 未声明的能力。
- 当前版本按“一个 `serviceId` 暴露一张 Agent Card”建立逻辑身份。注册中心基于可信 `tenantId + serviceId` 形成稳定 `agentId`，Card 的 `name` 只作为可变的展示信息；同一 service 的多个 `instanceId` 属于同一 Agent。
- 事实冲突时，租户、service、instance、内部地址和部署状态以可信 provider 为准，Agent 名称、描述、A2A capabilities、A2A 能力声明和协议接口以校验成功的 Card 为准；版本、生命周期、健康、freshness、lease 和 route handle 是注册中心基于二者派生的治理事实。
- Agent Card、部署实例事实和 registry entry 都不得携带或写入 Task / Run execution state。注册中心只拥有运行时路由索引和发现视图。

#### 5.1.2 Agent Card 与字段语义

当前版本的 Agent Card 表面固定到项目采用的 `org.a2aproject.sdk:a2a-java-sdk-server-common:1.0.0.CR1`。注册中心必须按该版本及平台启用的 A2A profile 校验 Card；Card 缺少标准必填字段、字段类型错误、A2A 能力声明非法或没有平台支持的可路由接口时，不得创建可见 registry entry。

| Agent Card 字段 | 要求 | 事实语义 |
|---|---|---|
| `name` | MUST | Agent 的人类可读名称，必须存在且非空；仅用于展示，不作为稳定 `agentId`。 |
| `description` | MUST | Agent 的人类可读说明，必须存在且非空；注册中心不基于该字段执行自由文本匹配或相关度排序。 |
| `supportedInterfaces` | MUST | 必须存在且至少包含一个结构合法的接口声明；运行时可路由还要求至少存在一个平台支持、来源可信且版本兼容的 `JSONRPC` interface。 |
| `version` | MUST | Agent Card 级版本，注册中心将其映射为 `capabilityVersion`；该版本作用于整张 Card 及其中全部 A2A 能力声明。 |
| `capabilities` | MUST | 标准 A2A 协议和执行特征声明，如 streaming、push notifications 和 extended card；声明必须与 runtime 实际能力一致。 |
| `defaultInputModes` | MUST | Agent 默认支持的输入媒体类型；必须存在且至少包含一种当前路由链可处理的模式。 |
| `defaultOutputModes` | MUST | Agent 默认支持的输出媒体类型；必须存在且至少包含一种当前路由链可处理的模式。 |
| `skills` | MUST | 标准 A2A `AgentSkill` 列表，字段必须存在但可以为空；空列表不影响按 `agentId` / `serviceId` 精确查询，但不能进入 A2A 能力声明查询。 |
| `provider`、文档地址、图标、安全声明、签名及其他标准可选字段 | MAY | 注册中心按原始快照保存并在授权边界内提供；这些字段不得覆盖可信部署事实或直接生成内部 route target。 |
| 兼容 `url` | MAY | SDK 兼容字段，不作为权威物理路由地址；存在时仍须经过来源和网络边界校验，不能绕过 `supportedInterfaces` 与 provider `baseUrl`。 |

`AgentSkill` 是 A2A 标准类型。为避免与 Agent 内部可直接执行的 Skill / Tool 混淆，本文中文统一称其为“A2A 能力声明”。

| `AgentSkill` 字段 | 要求 | 事实语义 |
|---|---|---|
| `id` | MUST | 当前 Card 内稳定且唯一的 A2A 能力声明标识；发现接口使用语义名 `a2aSkillId` 与其对应。 |
| `name` | MUST | A2A 能力声明的人类可读名称。 |
| `description` | MUST | 对该 Agent 擅长处理事项的描述，不是可直接执行的函数契约。 |
| `tags` | MUST | 结构化标签集合；注册中心只执行显式标签硬约束匹配，不执行自由文本相关度计算。 |
| `examples` | MAY | 该能力适用的示例表达；当前版本保存但不用于全文检索、打分或推荐。 |
| `inputModes` / `outputModes` | MAY | 对 Card 默认输入输出模式的覆盖；未声明时继承 Card 默认模式。 |
| 其他标准可选字段 | MAY | 按 A2A profile 校验和保存，不得被解释为 Agent 内部 Tool schema 或独立调用入口。 |

- `AgentSkill` 不提供独立 endpoint，也不保证 Agent 内部存在同名 Skill / Tool。查询命中 `a2aSkillId` 后，调用仍然通过 Agent Service 的 A2A interface 完成。
- 同一 Card 中的多个 `AgentSkill` 共用该 Card 关联实例的路由治理事实；`routeHandle` 始终绑定 Agent Service 实例及具体协议接口，不绑定或直接调用某个内部 Skill / Tool。
- 注册中心只校验和保存 runtime 暴露的 Card，不为缺失的 description、mode、capability、skill、接口或协议版本补默认值。首次抓取无效时实例不可见；已注册实例刷新失败时保留最后一次校验成功的 Card 快照。

#### 5.1.3 主动注册与抓取语义

- 当前版本唯一正式注册路径是 `DeploymentDiscoveryProvider + registry reconciliation`。runtime 初始化上报、runtime heartbeat 续租和 runtime 主动注销都不是 registry entry 的事实来源。
- 注册中心必须同时支持 provider 全量实例快照和增量实例事件。观察到 ready 实例后，注册中心从可信 `baseUrl + /.well-known/agent-card.json` 主动抓取标准 Agent Card。
- 抓取只能访问可信 provider 提供且符合平台策略的内部地址，必须限制 scheme、目标网络、重定向、响应大小和超时，并执行 schema、接口、版本、安全声明和可选签名校验；平台可以按部署策略使用 mTLS。
- 只有部署实例事实完整、实例处于可接入状态、Card 校验成功且至少存在一个平台支持的兼容 JSON-RPC interface 时，注册中心才创建可见 registry entry 和可解析 route handle。
- `tenantId + serviceId + instanceId` 是实例观察、对账和 registry entry 的稳定身份。同一实例的重复事件、重复全量快照和相同 source revision 必须得到等价结果，不得产生重复 entry 或重复候选。
- 注册中心必须保存校验成功的原始 Card 快照及内容摘要。Card 快照用于发现元数据和变更判断，provider `baseUrl` 与经过校验的接口路径用于派生内部 route target，二者不得互相替代。

#### 5.1.4 更新、对账与幂等语义

- provider 事件监听与周期全量 reconciliation 必须同时存在。注册中心重启、事件监听中断、source revision 不连续或观察到不可恢复的 revision 时，必须重新拉取权威全量快照并与现有 entry 对账。
- 对账必须按 `tenantId + serviceId + instanceId` 幂等处理。低于已处理 source revision 的陈旧事件不得覆盖新事实；同一 revision 携带冲突内容时必须拒绝更新、记录冲突并触发全量 reconciliation。
- provider source revision 变化、实例部署版本变化或周期刷新发现 Card digest 变化时，注册中心必须重新抓取并校验 Card。校验成功后，Card 快照、A2A 能力声明索引、版本、route target、revision 和相关治理事实必须原子更新。
- 已注册实例的 Card 刷新失败时，注册中心必须保留最后一次校验成功的 Card 和 route target，将健康状态降级并按受控退避重试；失败响应不得覆盖有效快照，也不得生成新的可用接口。
- 首次抓取失败的实例只保留内部待处理观察事实，不进入普通发现结果；后续抓取成功后才转为 `ACTIVE` registry entry。
- provider 暂时不可用时，注册中心必须将相关来源标记为 stale，停止依据“本次未观察到”执行批量删除，并继续结合最后部署快照、主动探测、Card 刷新结果和 lease 判断实例状态。provider 恢复后必须先执行全量 reconciliation 再收敛状态。
- provider 确认实例 terminating 或删除时，注册中心必须按计划下线语义推进生命周期；runtime 不需要也不得通过另一个写接口重复提交更新或注销。

#### 5.1.5 生命周期、健康与新鲜度语义

生命周期、健康状态和部署事实新鲜度是三条正交状态轴，不得相互替代。

| `lifecycleStatus` | 事实语义 |
|---|---|
| `PENDING` | 实例已被 provider 观察，但 Card 尚未成功抓取、校验或形成可路由接口；不进入普通候选集。 |
| `ACTIVE` | 实例事实、Card、接口和 lease 均有效，可以按查询约束进入候选集。 |
| `DRAINING` | provider 已报告 terminating、缩容或计划删除；立即退出默认候选集，但在 grace period 内保留既有 route handle 所需的受控收尾语义。 |
| `REMOVED` | 实例已确认删除、grace period 已结束或 lease 已失效；不再可发现或解析为新的调用目标，只保留必要审计事实。 |

| `effectiveHealth` | 事实语义 |
|---|---|
| `HEALTHY` | provider readiness、主动探测和 Card 刷新结果均满足平台健康策略。 |
| `DEGRADED` | 实例仍可受控使用，但存在 Card 刷新失败、部分探测异常或其他降级事实。 |
| `UNHEALTHY` | 当前不满足可调用健康要求；只有显式允许该状态的受信任查询才可观察，默认不进入候选集。 |
| `UNKNOWN` | 暂无足够证据判断健康状态；不得被自动等同于 `HEALTHY`。 |

| `freshness` | 事实语义 |
|---|---|
| `FRESH` | entry 基于当前可访问 provider 的连续 revision 或最近全量 reconciliation 收敛。 |
| `STALE_SOURCE` | provider 暂时不可用或 revision 连续性待修复，entry 正使用最后有效部署快照；该状态不是健康失败。 |

- `DRAINING` 不是健康状态。实例可以“健康但正在下线”；新调用先按 `lifecycleStatus = ACTIVE` 过滤，再应用健康约束，健康探测成功不得把 `DRAINING` 实例重新放回默认候选集。
- lease 由 registry-discovery-center 根据 provider 观察、主动探测、Card 刷新和平台策略计算，不依赖 runtime 主动续租。lease / TTL 到期后 entry 必须退出可见候选集并进入移除流程。
- source stale 时，只要 entry 仍为 `ACTIVE`、lease 有效且满足健康约束，查询可以继续返回候选，并附带 `freshness = STALE_SOURCE` 与 `lastValidatedAt`；普通调用方不得获得 provider identity、source revision 或内部地址。
- 异常失联的实例可以按策略由 `DEGRADED` 转为 `UNHEALTHY`。实例恢复、lease 仍有效且未进入 `DRAINING` 时，注册中心重新校验 Card 和健康事实后可以恢复为健康候选。

#### 5.1.6 查询、过滤与候选语义

当前版本只支持确定性的结构化查询，不支持自由文本检索、语义检索、相关度打分或推荐首选。调用方必须至少提供 `agentId`、`serviceId` 或 `a2aSkillId` 中的一项作为目标选择条件；`a2aSkillId` 对应标准 Agent Card 的 `skills[].id`，只用于发现过滤，不是直接调用方法名。

| 查询字段 | 事实语义 |
|---|---|
| `RegistryRequestContext` | 必须提供可信 `tenantId`、`callerRef`、`traceId`、`requestId` 和 `deadline`；租户与调用方可见性校验先于任何候选信息返回。 |
| `agentId` / `serviceId` / `a2aSkillId` | 至少提供一项；多项同时存在时按 AND 语义组合，不得静默忽略冲突条件。 |
| `requiredSkillTags` | 可选结构化标签硬约束；候选命中的 A2A 能力声明必须包含全部指定标签。 |
| `contractVersion` | 可选 A2A 协议版本硬约束，只保留存在兼容 JSON-RPC interface 的实例。 |
| `capabilityVersion` | 可选 Card 级版本硬约束，对应 `AgentCard.version`；不表达单个 `AgentSkill` 的独立版本。 |
| `requiredCapabilities` | 可选 A2A capability 硬约束，如 streaming；只按 Card 显式声明过滤，不推断未声明能力。 |
| `requiredInputModes` / `requiredOutputModes` | 可选媒体类型硬约束；优先使用命中 `AgentSkill` 的覆盖值，未覆盖时使用 Card 默认值。 |
| `requiredSecuritySchemes` | 可选安全兼容性硬约束；只判断调用链是否支持所需认证方式，不携带实际凭据。 |
| `healthRequirement` | 可选健康状态约束；未声明时只返回平台默认允许的健康状态。 |
| `limit` / `continuationToken` | 可选分页约束；token 必须绑定 tenant、caller、查询条件和 registry view，不得跨查询复用。 |

- 查询必须依次应用 tenant / caller 可见性、目标选择条件、`ACTIVE` 与 lease 有效性、版本、A2A capability、标签、输入输出模式、安全兼容性和健康约束。不得通过跨租户、忽略版本、降低安全要求或放宽健康条件进行静默 fallback。
- `skills = []` 的 Card 可以通过 `agentId` 或 `serviceId` 精确查询，但不得响应 `a2aSkillId` 或 skill tag 查询；注册中心不得根据 Card 名称或描述生成隐式 A2A 能力声明。
- `DiscoveryCandidate` 必须包含授权调用方可见的 Agent Card 元数据、opaque `routeHandle`、`contractVersion`、`capabilityVersion`、`lifecycleStatus`、`effectiveHealth`、`freshness`、`lastValidatedAt`、可选的命中 `a2aSkillId` 和可选 `selectionHint`。按 `agentId` / `serviceId` 精确查询且没有命中具体 A2A 能力声明时，`a2aSkillId` 可以为空。候选不得包含 provider `baseUrl`、内部 `routeTarget`、source identity 或 source revision。
- 候选中可见的 Agent Card interface 只能是平台批准的逻辑入口或公开地址；注册中心不得把内部抓取地址作为普通发现结果返回。原始 Card 中未经批准的地址只用于拒绝或内部审计，不进入候选视图。
- 候选结果必须使用稳定、确定性的顺序和分页语义；该顺序只保证结果可复现，不表示推荐优先级。gateway、event-bus 或经过授权的 runtime 根据自身策略和可选 `selectionHint` 完成最终选择。
- 查询结果不得包含 `recommendedAgentCard`、相关度 `score`、匹配 `evidence` 或由注册中心生成的置信度。

#### 5.1.7 路由、接口、版本与安全语义

- provider 提供的内部 `baseUrl` 是物理地址信任根。Card interface 使用相对 URL 时必须基于该 `baseUrl` 解析；使用绝对 URL 时只有与 provider 地址同源或命中平台 allowlist 才可成为内部 route target。
- 当前版本只有 `JSONRPC` binding 可以形成可路由候选。Card 中的其他标准 interface 可以保留在原始快照中，但不得因为被声明就视为 agent-bus 已支持，也不得生成可用 route handle。
- 同一 Card 存在多个 JSON-RPC interface 时，注册中心先按查询 `contractVersion` 和平台协议支持范围进行硬过滤，再按 Card 声明顺序选择第一个兼容接口。该过程只确定传输绑定，不替调用方选择 Agent 实例。
- registry `contractVersion` 映射所选 interface 的有效 A2A `protocolVersion`。兼容 Card 未显式给出时，只有平台能够唯一绑定到配置的 A2A 协议基线才可记录该基线；无法确定时 Card 不可路由。registry `capabilityVersion` 映射 `AgentCard.version`，当前版本不支持单个 `AgentSkill` 独立版本。
- `routeHandle` 必须绑定 tenant、registry entry、具体 JSON-RPC interface、有效版本和必要有效期事实，并具备完整性保护。普通调用方只能传递 handle，不得解码或从中推导 endpoint、topic、route key 或 provider 信息。
- `ResolveRouteHandle` 只能由 gateway、event-bus 或等价受信任路由层调用。解析时必须重新校验 tenant、caller、entry 生命周期、lease、handle 完整性和有效期；解析失败不得返回部分物理信息。
- 注册中心必须保存并校验 Card 的 `securitySchemes` / `securityRequirements` 关联，发现时可以按调用链支持的认证方式做硬约束过滤。`RouteResolution` 可以向受信任路由层返回所需认证方式，但注册中心不得保存、签发或注入 token、密钥、证书私钥等实际凭据。
- 调用凭据的获取、轮换和注入由 gateway / event-bus 的安全组件或企业凭据基础设施负责；发现成功不等于调用方已经获得调用授权。

#### 5.1.8 结果、错误与可观测语义

查询成功但没有候选是正常的结构化业务结果，不使用 `RegistryFailure`。当前版本按以下过滤阶段返回唯一、可编程的 `DiscoveryResult.outcome`：

| outcome | 事实语义 |
|---|---|
| `SUCCESS` | 查询成功并返回一个或多个满足全部硬约束的候选。 |
| `NO_MATCH` | 当前 tenant 与 caller 的可见范围内不存在匹配 `agentId`、`serviceId` 或 `a2aSkillId` 的注册事实。 |
| `NO_ACTIVE_INSTANCE` | 存在匹配注册事实，但没有 lifecycle 为 `ACTIVE` 且 lease 有效的实例。 |
| `VERSION_UNAVAILABLE` | 存在有效目标实例，但没有实例同时满足 `contractVersion` / `capabilityVersion` 约束。 |
| `CONSTRAINT_UNAVAILABLE` | 版本匹配，但没有实例同时满足 A2A capabilities、skill tags、输入输出模式或安全兼容性等执行约束。 |
| `HEALTH_UNAVAILABLE` | 前述约束均存在匹配实例，但没有实例满足调用方声明的健康状态要求。 |

- `NO_MATCH`、`NO_ACTIVE_INSTANCE`、`VERSION_UNAVAILABLE`、`CONSTRAINT_UNAVAILABLE` 和 `HEALTH_UNAVAILABLE` 不应触发针对 registry 的故障重试；调用方是否调整自身业务条件由调用方策略决定。
- `RegistryFailure` 只表达请求非法、权限拒绝、注册维护失败、路由句柄失败或系统不可用，至少包含 `failureCode`、可编程说明、`retryable` 和 `traceId`。

| 场景 | 失败语义 |
|---|---|
| 查询缺少目标选择条件、字段组合非法或分页 token 不匹配 | `INVALID_QUERY`；不得执行无边界全量扫描。 |
| caller 未授权或 tenant 不匹配 | `CALLER_NOT_AUTHORIZED` 或 `TENANT_SCOPE_DENIED`；不得返回候选数量、Card、handle 或目标存在性。 |
| provider 不可访问或 source revision 不连续 | reconciliation 返回 `DEPLOYMENT_SOURCE_UNAVAILABLE` 或 `SOURCE_REVISION_GAP`；已有有效查询视图按 source stale 语义处理，不自动折叠为查询失败。 |
| Card 不可达、来源拒绝、schema 非法或签名非法 | 分别返回 `AGENT_CARD_FETCH_FAILED`、`AGENT_CARD_SOURCE_REJECTED`、`AGENT_CARD_INVALID` 或 `AGENT_CARD_SIGNATURE_INVALID`；首次失败实例不可见，刷新失败保留最后有效快照。 |
| route handle 不存在、畸形、过期或 lease 已失效 | 返回 `ENTRY_NOT_FOUND`、`MALFORMED_ROUTE_HANDLE` 或 `LEASE_EXPIRED`；不得返回 route target。 |
| 操作超过 deadline 或 registry 自身不可用 | 返回 `DEADLINE_EXCEEDED` 或 `REGISTRY_UNAVAILABLE`，并准确标记 `retryable`。 |

- 实例观察、Card 抓取、校验、reconciliation、主动探测、生命周期和健康变化、发现查询及 route handle 解析都必须产生可审计事实，至少关联 tenant、caller 或 source、operation、Agent / service / instance、Card digest、版本、生命周期、健康、freshness、trace、结果和耗时。
- 日志、指标和审计不得记录 token、密钥、证书私钥、未脱敏 Card 敏感扩展、provider 内部凭据或普通调用方无权观察的物理地址。
- Card 刷新失败、source stale、lease 临近到期、进入 `DRAINING`、持续 `UNHEALTHY` 和 reconciliation 冲突必须具有独立指标或事件，不得只依赖查询失败间接发现。

### 5.2 显式边界与不承诺项

| 边界 | 当前版本不承诺 |
|---|---|
| Agent Card 所有权 | 注册中心不创建、补写、修复或修改 runtime 暴露的标准 Agent Card，也不把 registry 治理字段写回 Card。 |
| 部署事实所有权 | `DeploymentDiscoveryProvider` 不定义 Agent 名称、A2A capabilities、A2A 能力声明或业务类型；注册中心不要求 provider 识别 Agent 内部实现框架。 |
| runtime 主动注册 | 当前版本不承诺 runtime-to-registry 注册、更新、heartbeat 续租或主动注销接口；正式注册路径是 provider 实例发现与 registry reconciliation。 |
| Agent 内部 Skill / Tool | 标准 `AgentSkill` 只作为 A2A 能力声明参与发现，不等于 Agent 内部可直接执行的 Skill / Tool；注册中心不管理 Tool schema、Tool 生命周期或独立 Tool endpoint。 |
| 推荐与最终选择 | 注册中心不执行自由文本检索、语义匹配、相关度打分、推荐首选或最终实例选择，只返回满足结构化硬约束的候选集合。 |
| 单个 A2A 能力声明版本 | 当前版本不定义 `AgentSkill` 独立版本；同一 Card 中全部 A2A 能力声明共享 `AgentCard.version` 映射的 `capabilityVersion`。 |
| 非 JSON-RPC binding | 当前版本不因 Card 声明 gRPC、HTTP+JSON 或其他 interface 就承诺 agent-bus 已具备对应投递和错误映射能力。 |
| 凭据管理 | 注册中心不保存、签发、轮换或注入 token、密钥和证书私钥；安全声明发现与实际凭据使用是不同责任。 |
| Task 生命周期 | 注册中心不创建、不写入、不推进 Task / Run 状态；`routeHandle`、`lifecycleStatus` 和 `effectiveHealth` 都不得成为 Task execution state。 |
| 运行时与编排替代 | 注册中心不替代业务 runtime、编排器或任何插件执行入口。 |
| 跨租户兜底 | 查询失败时不跨租户 fallback，也不绕过 caller 可见性、版本、安全、执行或健康约束。 |
| 物理地址公开 | 普通发现结果不暴露 provider `baseUrl`、内部 `routeTarget`、topic、route key、source identity 或 source revision；物理路由只对受信任解析层可见。 |
| source stale 批量下线 | provider 暂时不可用不等于全部实例已删除；注册中心不根据缺失事件执行来源级批量下线。 |
| 具体基础设施 | 本特性不规定具体 provider 产品、注册表存储、缓存、数据库、探测器、lease 调度器或集群实现。 |

## 6. 对下游设计与实现的约束

- 下游设计必须把本文作为 Agent Card 注册与发现的方案事实来源，并保持部署实例发现、Agent Card 主动抓取、持续对账、生命周期、健康、查询、路由引用和失败语义一致。
- `DeploymentDiscoveryProvider` 适配、`agent-runtime` Agent Card 暴露和 registry-discovery-center 维护必须遵守统一事实边界：provider 提供部署实例事实，runtime 提供标准 Agent Card，注册中心派生并维护 registry entry 与 runtime route index。
- 注册发现实现必须同时支持 provider 增量事件和周期全量 reconciliation，并按 `tenantId + serviceId + instanceId` 幂等处理；source revision 缺口、注册中心重启和 provider 恢复后必须通过全量快照重新收敛。
- 查询实现必须在所有入口强制执行 tenant、caller、`agentId` / `serviceId` / `a2aSkillId`、生命周期、lease、版本、A2A capabilities、输入输出模式、安全兼容性和健康约束，并返回本文定义的结构化候选及 outcome。
- `lifecycleStatus`、`effectiveHealth` 和 `freshness` 必须作为独立治理事实实现；Card 更新必须原子替换有效快照，计划下线必须经过 `DRAINING`，source stale 必须保留最后有效视图并持续结合探测和 lease 收敛状态。
- `routeHandle` 必须保持 opaque，并由受信任路由层通过 `ResolveRouteHandle` 解析；普通发现结果只提供平台批准的逻辑地址和治理元数据，内部 `baseUrl`、`routeTarget`、source identity 与 Task / Run execution state 必须保持在各自边界内。
- 安全实现必须校验 Agent Card 的 security scheme 与 requirement 关联，并由 gateway / event-bus 的安全组件或企业凭据基础设施完成实际凭据获取、轮换和注入。
- 测试必须覆盖实例首次发现、Card 抓取与校验失败、多实例扩容、Card 更新、滚动升级、漏事件对账、provider 不可用、计划下线、异常失联与恢复、lease 到期、结构化发现、无候选 outcome、租户拒绝和 route handle 解析等场景。
- 文档和指南必须统一使用标准 Agent Card、A2A 能力声明、registry entry、`a2aSkillId`、`routeHandle`、生命周期、健康和 freshness 术语，并明确 A2A `AgentSkill` 与 Agent 内部 Skill / Tool 的语义区别。

## 7. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-bus/README.md`
- `architecture/L1-High-Level-Design/agent-bus/logical.md`
- `architecture/L1-High-Level-Design/agent-bus/process.md`
- `architecture/L1-High-Level-Design/agent-bus/scenarios.md`
- `architecture/L1-High-Level-Design/agent-bus/features/README.md`
- `version-scope/FEAT-001-standardized-agent-service-entrypoint.md`
- `version-scope/FEAT-013-client-invocation-event-forwarding.md`
- `version-scope/FEAT-014-a2a-call-event-forwarding.md`
