---
level: L2-LLD
module: agent-runtime
feature_type: functional
feature_id: Feat-Func-014
status: active
dependency:
  - ../../L1-High-Level-Design/agent-runtime/README.md
  - ../../L1-High-Level-Design/agent-runtime/development.md
  - ../../L1-High-Level-Design/agent-runtime/spi-appendix.md
  - ../../../version-scope/FEAT-014-runtime-redis-datasource-adaptation.md
  - ../../../version-scope/FEAT-004-middleware-memory-and-state.md
---

# Runtime Redis 数据源适配 — 设计文档

> 目标模块：`agent-runtime` 的 Redis 中间件接入层、A2A Task 存储和 Agent 状态持久化 Redis 使用方
> 最后更新：2026-07-08

---

## 1. 概述

### 1.1 特性定位

Runtime Redis 数据源适配把 `agent-runtime` 内部对 Redis 的使用从具体客户端实现中解耦出来。A2A Task 存储、Agent 状态持久化和后续 Redis 型中间件能力统一依赖 runtime Redis 操作抽象；原生 Redis 单机版、原生 Redis 集群版和客户封装 Redis 组件分别通过数据源策略或适配实现接入该抽象。

- **解决的问题**：现场需要接入客户自封装 Redis 组件，但当前运行时 Redis 使用方不应直接依赖客户 JAR，也不应分散绑定到 Jedis、Lettuce 或其他具体客户端。
- **适用场景**：需要将 Redis 资源纳入客户统一管理和监控体系；需要在不同客户环境中通过配置切换 Redis 数据源；需要让 A2A Task 与 Agent 状态持久化复用同一 Redis 接入策略。

### 1.2 当前事实边界

本文描述 Feat-Func-014 在 `agent-runtime` 中的已接受架构方案。面向调用方的黑盒行为、验收标准和外部边界以 `version-scope/FEAT-014-runtime-redis-datasource-adaptation.md` 为准；模块级 SPI 原则、依赖方向和自动装配边界以 L1 设计及 SPI 附录为准。

当前已有待合入实现 PR 以 runtime Redis client SPI 为核心重构方向：Redis 使用方不直接创建原生客户端，而是依赖共享 Redis 操作门面；默认实现可使用原生 Redis 客户端，客户实现可通过适配方式替换。本文不把该 PR 中的临时代码形态、测试命名或未覆盖需求点写成最终事实。

### 1.3 设计原则

1. **统一抽象** — 所有 runtime Redis 使用方依赖同一操作接口，不直接依赖具体 Redis 客户端。
2. **配置选择** — Redis 数据源类型和连接引用由配置驱动，部署切换不要求修改业务代码。
3. **客户适配外置** — 客户封装 Redis JAR 不进入产品仓库，通过适配实现接入 runtime 抽象。
4. **日志可确认且脱敏** — 启动日志能确认当前策略和关键配置，但不输出密码、密文或凭据。
5. **最小命令面** — 只抽象当前 runtime 消费方需要的 Redis 操作，避免把 Redis 全命令集变成长期兼容负担。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|---|---|---|---|
| Redis 数据源选择 | 根据配置选择原生单机、原生集群或客户适配策略 | Redis datasource properties / strategy | 设计确定 |
| Runtime Redis 操作接口 | 向 Redis 使用方暴露统一读写、TTL、删除、扫描等命令面 | Runtime Redis client SPI | 设计确定 |
| 默认原生 Redis 适配 | 在未提供客户适配时接入原生 Redis | Native Redis adapter | 设计确定 |
| 客户 Redis 适配 | 将客户封装 Redis 组件桥接到统一操作接口 | Customer Redis adapter | 现场适配 |
| Redis 使用方收敛 | A2A TaskStore、Agent checkpointer 等复用统一 Redis 门面 | Redis-backed runtime consumers | 设计确定 |
| 启动日志与脱敏 | 输出当前策略和非敏感配置摘要 | Redis datasource diagnostics | 需实现覆盖 |

---

## 2. 特性规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| 配置声明 Redis 数据源类型 | 设计确定 | 配置必须能区分原生单机、原生集群和客户适配模式。 |
| 命名 Redis 连接引用 | 设计确定 | Redis 使用方通过连接引用复用同一数据源定义。 |
| Runtime Redis 操作接口 | 设计确定 | 提供 get、set、setex、setnx、del、exists、expire、mget、scan 等当前必要操作。 |
| 默认原生单机适配 | 设计确定 | 使用产品内置原生 Redis 客户端实现统一操作接口。 |
| 默认原生集群适配 | 需补齐 | 使用与统一接口一致的集群客户端或集群策略实现。 |
| 客户封装组件适配 | 设计确定 | 客户 JAR 由现场或客户侧适配为统一操作接口实现。 |
| A2A Task 存储复用 | 设计确定 | Redis-backed TaskStore 依赖统一接口，不直接创建具体客户端。 |
| Agent 状态持久化复用 | 设计确定 | Redis checkpointer 配置注入统一接口，不直接创建具体客户端。 |
| 策略日志 | 需补齐 | 启动时输出数据源类型、连接引用、非敏感连接摘要和适配实现标识。 |
| 密码脱敏 | 需补齐 | 日志和错误信息不得输出明文密码、加密密文或 token。 |
| 内部单机/集群验收 | 需验证 | 内部环境分别搭建原生 Redis 单机和集群并执行读写验证。 |
| 客户模式内部复现 | 不适用 | 客户 JAR 不能外传，内部只验证扩展点和默认模式。 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|---|---|---|
| 内置工行 Redis JAR | 客户安全合规限制，JAR 不能外传 | 现场提供适配实现并注册到应用 |
| Redis 全命令 SPI | 命令面过大且长期兼容成本高 | 按 runtime 消费方需要扩展最小命令面 |
| Redis 数据迁移 | 数据迁移和 key schema 转换属于运维/交付活动 | 通过交付方案单独规划 |
| Redis 服务治理平台 | 客户已有统一治理和监控体系 | 通过客户 Redis 组件接入 |
| 运行中热切换 | 当前需求只要求修改配置后切换 | 配置变更后重启生效 |
| 多租户动态数据源路由 | 当前需求不是按请求动态路由多 Redis | 单实例使用当前生效数据源策略 |

### 2.3 行为承诺

- **必须**：Redis 使用方只依赖统一操作接口，不直接依赖具体 Redis 客户端。
- **必须**：配置选择的数据源策略与启动日志一致。
- **必须**：配置或适配缺失时给出明确错误，不静默切换到非预期数据源。
- **必须**：日志中不输出密码、密文或凭据。
- **允许**：客户适配实现由交付工程或客户工程在外部模块提供。
- **允许**：原生单机和原生集群使用不同底层客户端，只要上层操作语义一致。

---

## 3. 核心设计

### 3.1 逻辑分层

```
Redis 使用方
  ├─ A2A Task 存储
  ├─ Agent 状态持久化 / checkpointer
  └─ 后续 Redis 型中间件能力
        │
        ▼
Runtime Redis 操作接口
        │
        ├─ 原生 Redis 单机适配
        ├─ 原生 Redis 集群适配
        └─ 客户封装 Redis 适配
                │
                ▼
        Redis 服务或客户 Redis 治理组件
```

运行时只把 Redis 读写能力暴露为稳定操作接口。单机、集群和客户组件的连接、路由、池化、鉴权、监控接入和资源释放由各自适配层承接。

### 3.2 数据源选择模型

配置模型应包含三类语义：

| 配置语义 | 说明 |
|---|---|
| 数据源类型 | 指定当前启用 `standalone`、`cluster` 或 `customer` 等 Redis 数据源类型。 |
| 连接引用 | 指向一个命名 Redis 连接定义，供 checkpointer、TaskStore 等能力复用。 |
| 连接参数 | host、port、database、timeout、cluster nodes、认证材料引用或客户组件需要的等价参数。 |

装配顺序遵循以下原则：

1. 如果应用显式注册客户或第三方 Runtime Redis 操作接口实现，则使用该实现。
2. 如果未显式注册实现，则根据配置的数据源类型创建默认原生 Redis 实现。
3. 如果配置要求 Redis 但无法创建或找到匹配实现，则启动失败或在创建 Redis 使用方时明确失败。
4. 如果未启用 Redis 型中间件能力，则不强制创建 Redis 数据源。

### 3.3 Runtime Redis 操作接口

统一操作接口承载当前运行时需要的最小 Redis 命令面：

| 操作类型 | 语义 |
|---|---|
| 单 key 读取 | 按文本或二进制 key 读取值。 |
| 单 key 写入 | 写入文本或二进制值。 |
| TTL 写入 | 写入值并设置过期时间。 |
| 条件写入 | key 不存在时写入，用于幂等或锁类场景。 |
| 删除 | 删除一个或多个 key。 |
| 存在性判断 | 判断 key 是否存在。 |
| 过期时间刷新 | 为已有 key 设置或刷新 TTL。 |
| 批量读取 | 按多个 key 读取有序结果。 |
| 模式扫描 | 按 pattern 扫描 key，用于 Task 列表等低频管理场景。 |

接口实现必须满足线程安全或明确由容器为并发使用提供隔离。客户封装组件如没有某项命令的原生支持，适配层应使用客户组件提供的等价能力实现；无法保证语义一致时应显式失败。

### 3.4 默认原生 Redis 适配

默认原生 Redis 适配负责：

- 根据连接配置创建原生 Redis 客户端。
- 将原生 Redis 命令映射到统一操作接口。
- 处理连接超时、database 选择、集群节点和资源释放。
- 在启动日志中输出策略名称、连接引用和脱敏配置摘要。

原生单机和原生集群可以有不同底层客户端或连接策略，但它们对 Redis 使用方必须呈现同一操作语义。

### 3.5 客户封装 Redis 适配

客户封装 Redis 适配负责：

- 依赖客户提供的 Redis JAR 或客户侧 SDK。
- 将客户组件的读写、TTL、删除、扫描等能力映射到 Runtime Redis 操作接口。
- 接入客户的连接治理、监控、审计和安全策略。
- 避免把客户私有类型泄漏到 runtime 公共 SPI 和业务代码中。

客户 JAR 不进入产品仓库。产品提供的是稳定操作接口、装配优先级、配置选择和默认原生实现；现场或客户侧提供具体客户适配实现。

### 3.6 Redis 使用方收敛

Redis-backed 运行时能力通过统一接口获取 Redis 能力：

| 使用方 | Redis 用途 | 设计要求 |
|---|---|---|
| A2A Task 存储 | 保存、读取、删除、列表查询 Task 快照 | 通过统一接口执行读写和扫描，不直接创建 Redis 客户端。 |
| Agent 状态持久化 | 保存和恢复 Agent 执行状态 checkpoint | 通过统一接口或由其注入的连接对象完成 Redis 操作。 |
| 后续 Redis 型中间件 | session、缓存、幂等等可能能力 | 新增能力默认复用统一接口，不新增平行 Redis 客户端抽象。 |

Task 存储和 checkpointer 可以有各自的数据结构、key 前缀和 TTL 策略，但不应拥有各自独立的数据源选择逻辑。

---

## 4. 代码结构

### 4.1 包结构建议

```
service/spec/spi/
└── RuntimeRedisClient                    # runtime Redis 操作接口

service/adapters/common/middleware/
├── MiddlewareProperties                  # 中间件与 Redis 数据源配置
└── redis/
    ├── RedisMiddlewareAutoConfiguration  # Redis 数据源自动装配
    ├── NativeRedisRuntimeClient          # 原生 Redis 适配实现
    ├── RedisConnectionAssembler          # 连接配置解析与摘要构造
    └── RedisDatasourceDiagnostics        # 启动日志与脱敏诊断

service/app/controller/a2a/
└── RedisTaskStore                        # A2A TaskStore 的 Redis 使用方

service/adapters/agentcore/middleware/
└── AgentCoreCheckpointerConfigAssembler  # Agent 状态持久化的 Redis 使用方配置
```

具体类名可随实现演进调整，但包职责和依赖方向应保持：公共 SPI 位于 `service-spec`，默认适配位于 common middleware，业务使用方只依赖 SPI。

### 4.2 依赖方向

```
agent-service-app ─┐
                   ├─ depends on Runtime Redis SPI
adapters-agentcore ┘
        ▲
        │
adapters-common 提供默认原生 Redis 适配和自动装配
        ▲
        │
客户适配模块提供 Runtime Redis SPI 实现
```

- `service-spec` 不依赖任何 Redis 客户端或客户 JAR。
- `adapters-common` 可以依赖默认原生 Redis 客户端。
- 客户适配模块依赖客户 Redis JAR，但不把客户类型暴露给 `service-spec` 或业务使用方。
- `agent-service-app` 和 `adapters-agentcore` 只依赖 Runtime Redis SPI。

---

## 5. 运行流程

### 5.1 启动装配流程

```
应用启动
  │
  ├─ 绑定 Redis 数据源配置
  ├─ 查找显式注册的 Runtime Redis 操作接口实现
  │     ├─ 存在: 使用客户或第三方适配实现
  │     └─ 不存在: 根据配置创建默认原生 Redis 实现
  ├─ 输出数据源策略日志（脱敏）
  ├─ 将统一 Redis 操作接口注入 Redis 使用方
  │     ├─ A2A Task 存储
  │     └─ Agent 状态持久化 / checkpointer
  ▼
Runtime 就绪
```

### 5.2 读写流程

```
Redis 使用方
  │
  ├─ 调用 Runtime Redis 操作接口
  │
  ├─ 当前生效适配实现执行命令
  │     ├─ 原生 Redis 单机
  │     ├─ 原生 Redis 集群
  │     └─ 客户封装 Redis 组件
  │
  ▼
返回统一结果或明确异常
```

上层使用方不判断当前是单机、集群还是客户组件；差异在适配层内闭合。

### 5.3 错误、降级和诊断

| 场景 | 触发条件 | 行为 | 对外结果 |
|---|---|---|---|
| 数据源类型不支持 | 配置指定未知 Redis 类型 | 启动失败或 Redis 使用方创建失败 | 明确错误提示支持的类型。 |
| Redis 连接引用缺失 | Redis 型能力启用但找不到连接定义 | 启动失败或 Redis 使用方创建失败 | 明确指出缺失的连接引用。 |
| 客户适配未注册 | 配置为客户模式但无适配实现 | 启动失败 | 提示需要注册 Runtime Redis 操作接口实现。 |
| 默认客户端缺依赖 | 配置为原生模式但默认客户端不可用 | 启动失败或跳过默认装配并报错 | 不静默改用其他模式。 |
| 连接失败 | Redis 服务不可达或认证失败 | Redis 使用方初始化或首次操作失败 | 错误日志脱敏，提示连接摘要。 |
| 命令语义不支持 | 客户组件无法提供某项必要命令 | 适配层显式失败 | 调用方获得可诊断异常。 |
| 密码配置存在 | 配置包含密码或加密密码 | 日志仅输出密码已配置状态 | 不输出明文或密文。 |

当前版本不定义跨数据源自动降级。配置指定 Redis 时，系统不得因为 Redis 初始化失败而静默退回内存存储，除非调用方显式配置为内存模式。

---

## 6. 配置使用

### 6.1 配置语义示例

以下示例表达配置语义，不限定最终字段名：

```yaml
openjiuwen:
  service:
    middleware:
      redis:
        datasource:
          type: standalone
          ref: default
        default:
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: "${REDIS_PASSWORD_ENCRYPTED:}"
      checkpointer:
        type: redis
        redis-ref: default
```

客户模式示例：

```yaml
openjiuwen:
  service:
    middleware:
      redis:
        datasource:
          type: customer
          ref: icbc
        icbc:
          timeout-ms: 3000
          encrypted-password: "${ICBC_REDIS_PASSWORD_ENCRYPTED:}"
      checkpointer:
        type: redis
        redis-ref: icbc
```

客户模式需要同时注册客户适配实现。适配实现可以来自现场交付模块或客户私有模块，产品开源仓库不保存客户 JAR。

### 6.2 启动日志要求

启动日志应包含：

- Redis datasource type。
- Redis connection ref。
- 原生模式下的 host、port、database、timeout 或集群节点摘要。
- 客户模式下的适配实现标识和客户连接引用。
- 密码是否配置的布尔摘要。

启动日志不得包含：

- 明文密码。
- 加密密码原文。
- token、key、证书内容。
- 客户私有鉴权材料。

---

## 7. 验证策略

### 7.1 单元测试

| 测试项 | 验证点 |
|---|---|
| 数据源类型解析 | 支持单机、集群、客户模式；未知类型报错。 |
| 默认原生适配装配 | 未注册自定义实现时按配置创建默认实现。 |
| 客户适配优先级 | 已注册 Runtime Redis 操作接口实现时优先使用该实现。 |
| Redis 使用方依赖 | TaskStore 和 checkpointer 配置只依赖统一接口。 |
| 命令映射 | get、set、setex、setnx、del、exists、expire、mget、scan 语义正确。 |
| 日志脱敏 | 启动日志和错误日志不包含明文密码或加密密文。 |

### 7.2 集成测试

| 环境 | 验证点 |
|---|---|
| 原生 Redis 单机版 | A2A Task 存储和 Agent 状态持久化读写正常。 |
| 原生 Redis 集群版 | 同一业务代码在集群模式下读写正常。 |
| 配置切换 | 单机版与集群版之间只改配置即可切换。 |
| 客户适配替身 | 使用内部 fake / stub 适配实现验证客户模式装配链路。 |

### 7.3 验收限制

客户封装 Redis JAR 因安全合规要求不能外传，内部环境不直接复现工行客户模式。内部验收的证据应来自：

- 原生 Redis 单机版读写验证。
- 原生 Redis 集群版读写验证。
- 配置切换无需改业务代码的验证。
- 客户适配扩展点可被替身实现注册并被使用的验证。
- 日志脱敏检查。

客户模式的真实联调在客户或现场环境完成。

---

## 8. 当前限制

| 限制 | 影响范围 | 临时方案 |
|---|---|---|
| 客户 JAR 不能进入内部仓库 | 内部无法真实复现工行组件行为 | 使用适配替身验证装配链路，客户环境完成真实联调。 |
| PR 实现尚未覆盖全部需求点 | 策略日志、集群默认适配和验收脚本可能需补齐 | 按本文和 FEAT-014 继续补实现与测试。 |
| 统一接口只包含最小命令面 | 新 Redis 使用方可能需要新增命令 | 先评估是否为 runtime 通用需求，再扩展 SPI。 |
| 不支持运行中热切换 | 修改数据源后需重启应用 | 运维按配置变更流程重启。 |
| 不包含数据迁移 | 切换 Redis 数据源不会自动迁移旧数据 | 交付方案单独规划迁移或清理。 |
