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
2. **统一配置** — 默认 Redis client 和客户自定义 Redis client 读取同一组 `openjiuwen.service.middleware.redis.<ref>` 配置，部署切换不要求修改业务代码。
3. **Bean 优先扩展** — 客户封装 Redis JAR 不进入产品仓库，开发者通过注册 `RuntimeRedisClient` Bean 覆盖默认 Bean。
4. **日志可确认且脱敏** — 启动日志能确认当前策略和关键配置，但不输出密码、密文或凭据。
5. **最小命令面** — 只抽象当前 runtime 消费方需要的 Redis 操作，避免把 Redis 全命令集变成长期兼容负担。

### 1.4 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|---|---|---|---|
| Redis 配置模型 | 通过 checkpointer 类型和 redis-ref 引用命名 Redis endpoint | `MiddlewareProperties` | 已由 PR 固化 |
| Runtime Redis 操作接口 | 向 Redis 使用方暴露统一读写、TTL、删除、扫描等命令面 | Runtime Redis client SPI | 设计确定 |
| 默认原生 Redis Bean | 未提供自定义 Bean 且启用 Redis checkpointer 时创建默认客户端 | `JedisPooledRuntimeRedisClient` | 已由 PR 固化 |
| 客户 Redis 适配 Bean | 将客户封装 Redis 组件桥接到统一操作接口 | 自定义 `RuntimeRedisClient` Bean | 现场适配 |
| Redis 使用方收敛 | A2A TaskStore、Agent checkpointer 等复用统一 Redis 门面 | Redis-backed runtime consumers | 已由 PR 固化 |
| 启动日志与脱敏 | 输出当前 Redis client Bean、连接引用和非敏感配置摘要 | Redis diagnostics | 需实现覆盖 |

---

## 2. 特性规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| Redis checkpointer 开关 | 已由 PR 固化 | `openjiuwen.service.middleware.checkpointer.type=redis` 启用 Redis 型运行时存储。 |
| 命名 Redis 连接引用 | 已由 PR 固化 | `openjiuwen.service.middleware.checkpointer.redis-ref` 指向 `openjiuwen.service.middleware.redis.<ref>`；默认值为 `default`。 |
| Redis endpoint 配置 | 已由 PR 固化 | `redis.<ref>` 当前包含 host、port、database、timeout-ms、encrypted-password。 |
| Runtime Redis 操作接口 | 已由 PR 固化 | 提供 get、set、setex、setnx、del、exists、expire、mget、scanIter 等当前必要操作。 |
| 默认原生单机适配 | 已由 PR 固化 | 未注册自定义 `RuntimeRedisClient` Bean 时，默认创建 `JedisPooledRuntimeRedisClient`。 |
| 原生集群接入 | 需补齐实现 | 当前 PR 未新增 cluster nodes 配置或默认集群客户端；集群可先通过统一接入地址或自定义 Bean 承接。 |
| 客户封装组件适配 | 设计确定 | 客户 JAR 由现场或客户侧实现 `RuntimeRedisClient` Bean，并读取同一 Redis endpoint 配置。 |
| A2A Task 存储复用 | 已由 PR 固化 | Redis-backed TaskStore 依赖 `RuntimeRedisClient`，不直接创建具体客户端。 |
| Agent 状态持久化复用 | 已由 PR 固化 | Redis checkpointer 配置把 `RuntimeRedisClient` 注入 agent-core Redis connection map。 |
| 策略日志 | 需补齐 | 启动时输出当前 `RuntimeRedisClient` Bean 类型、连接引用、非敏感连接摘要和适配实现标识。 |
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
- **必须**：当前生效的 `RuntimeRedisClient` Bean、Redis endpoint 配置与启动日志一致。
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

### 3.2 配置模型

当前 PR 已固化的配置入口不是单独的 `datasource.type`，而是通过中间件配置启用 Redis，并通过 `redis-ref` 引用命名 Redis endpoint：

| 配置语义 | 说明 |
|---|---|
| `openjiuwen.service.middleware.checkpointer.type` | 当前 Redis TaskStore 和 agent-core checkpointer 共用该开关；取值为 `redis` 时启用 Redis 型运行时存储，默认是 `in_memory`。 |
| `openjiuwen.service.middleware.checkpointer.redis-ref` | 指向 `openjiuwen.service.middleware.redis.<ref>`；未配置时默认使用 `default`。 |
| `openjiuwen.service.middleware.redis.<ref>.host` | Redis endpoint 主机名。对默认 Bean 是原生 Redis 单机地址；对客户自定义 Bean 可以是客户组件入口、代理地址或统一接入地址。 |
| `openjiuwen.service.middleware.redis.<ref>.port` | Redis endpoint 端口，默认 6379。 |
| `openjiuwen.service.middleware.redis.<ref>.database` | Redis database 编号，默认 0。 |
| `openjiuwen.service.middleware.redis.<ref>.timeout-ms` | 连接和读写超时时间，默认 3000。 |
| `openjiuwen.service.middleware.redis.<ref>.encrypted-password` | 加密后的 Redis 密码；由 `CredentialDecryptor` 解密后传给默认 Bean 或自定义 Bean。 |

单机和集群不通过额外的 `customer` / `cluster` 类型字段区分。默认 Bean 当前按单机 Redis endpoint 创建 `JedisPooled` 客户端；需要客户封装组件、集群客户端或客户统一接入代理时，开发者注册自定义 `RuntimeRedisClient` Bean，并读取同一 `MiddlewareProperties` 和 `redis-ref` 配置。这样默认实现和客户实现共享配置格式，业务使用方不感知底层是单机、集群还是客户组件。

当前配置模型尚未定义 cluster nodes 列表。如果后续要求产品默认 Bean 直连原生 Redis 集群，应在 `MiddlewareProperties.RedisEndpoint` 中扩展节点配置，并保持 `RuntimeRedisClient` SPI 和 Redis 使用方不变。

### 3.3 Runtime Redis 操作接口

`RuntimeRedisClient` 定义在 `service/agent-service-spec`，是 Redis 使用方唯一依赖的命令门面。当前 PR 中的方法面如下：

```java
public interface RuntimeRedisClient extends AutoCloseable {
    Object get(String key);
    byte[] get(byte[] key);
    String set(String key, String value);
    String set(String key, byte[] value);
    String set(byte[] key, byte[] value);
    String setex(String key, long seconds, String value);
    String setex(byte[] key, long seconds, byte[] value);
    long setnx(String key, String value);
    long setnx(byte[] key, byte[] value);
    long del(String... keys);
    long del(byte[]... keys);
    boolean exists(String key);
    boolean exists(byte[] key);
    long expire(String key, long seconds);
    long expire(byte[] key, long seconds);
    List<Object> mget(String... keys);
    List<String> scanIter(String pattern);
}
```

该接口承载当前运行时需要的最小 Redis 命令面：

| 操作类型 | SPI 方法 | 语义 |
|---|---|---|
| 单 key 读取 | `get` | 按文本或二进制 key 读取值。 |
| 单 key 写入 | `set` | 写入文本或二进制值。 |
| TTL 写入 | `setex` | 写入值并设置过期时间。 |
| 条件写入 | `setnx` | key 不存在时写入，用于幂等或锁类场景。 |
| 删除 | `del` | 删除一个或多个 key。 |
| 存在性判断 | `exists` | 判断 key 是否存在。 |
| 过期时间刷新 | `expire` | 为已有 key 设置或刷新 TTL。 |
| 批量读取 | `mget` | 按多个文本 key 读取有序结果。 |
| 模式扫描 | `scanIter` | 按 pattern 扫描 key，用于 Task 列表等低频管理场景。 |

接口实现必须满足线程安全或明确由容器为并发使用提供隔离。客户封装组件如没有某项命令的原生支持，适配层应使用客户组件提供的等价能力实现；无法保证语义一致时应显式失败。

### 3.4 Bean 装配与默认实现

`RedisMiddlewareAutoConfiguration` 是当前 Redis client Bean 的装配入口。它具备三个关键条件：

| 条件 | 设计含义 |
|---|---|
| `@ConditionalOnClass(JedisPooled.class)` | 默认实现依赖 Jedis pooled client，缺少该类时不创建默认 Bean。 |
| `@ConditionalOnMissingBean(RuntimeRedisClient.class)` | 开发者提供自定义 `RuntimeRedisClient` Bean 时，默认 Bean 自动让位。 |
| `@ConditionalOnProperty(prefix = "openjiuwen.service.middleware.checkpointer", name = "type", havingValue = "redis")` | 只有启用 Redis checkpointer 时才创建默认 Redis client Bean。 |

默认 Bean 的创建流程：

1. 读取 `MiddlewareProperties.getCheckpointer().getRedisRef()`，空值按 `default` 处理。
2. 通过 `RedisConnectionAssembler.resolveEndpoint(properties, redisRef)` 解析 `openjiuwen.service.middleware.redis.<ref>`。
3. 通过 `CredentialDecryptor` 解密 `encrypted-password`。
4. 使用 `RedisJedisClientFactory.createPooled(endpoint, password)` 创建 `JedisPooled`。
5. 包装为 `JedisPooledRuntimeRedisClient` 暴露给 Redis 使用方。

`JedisPooledRuntimeRedisClient` 负责把 `RuntimeRedisClient` 方法映射到 Jedis 命令，并实现 `close()` 释放底层 pooled client。它是当前默认原生单机 Redis 实现；它不是客户封装 Redis 组件，也不是原生 Redis 集群默认实现。

### 3.5 开发者自定义 Redis client

客户封装 Redis、原生 Redis 集群或客户统一接入代理通过自定义 `RuntimeRedisClient` Bean 接入。自定义 Bean 应复用当前中间件配置，而不是引入新的 `datasource.type` 配置。

```java
@Configuration
class CustomerRedisClientConfiguration {
    @Bean
    RuntimeRedisClient runtimeRedisClient(MiddlewareProperties properties, CredentialDecryptor decryptor) {
        String redisRef = properties.getCheckpointer().getRedisRef();
        MiddlewareProperties.RedisEndpoint endpoint =
            RedisConnectionAssembler.resolveEndpoint(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword());
        return new CustomerRuntimeRedisClient(endpoint, password);
    }
}
```

自定义实现负责：

- 依赖客户提供的 Redis JAR、原生集群客户端或客户侧 SDK。
- 读取同一 `openjiuwen.service.middleware.redis.<ref>` 配置，解释 host、port、database、timeout-ms 和 encrypted-password。
- 将客户组件或集群客户端的读写、TTL、删除、扫描等能力映射到 `RuntimeRedisClient`。
- 接入客户的连接治理、监控、审计和安全策略。
- 避免把客户私有类型泄漏到 `service-spec`、Redis 使用方或业务代码中。

客户 JAR 不进入产品仓库。产品提供的是稳定 SPI、统一配置、默认原生单机 Bean 和 Bean 覆盖机制；现场或客户侧提供具体客户适配 Bean。

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
    ├── RedisMiddlewareAutoConfiguration  # RuntimeRedisClient 默认 Bean 自动装配
    ├── JedisPooledRuntimeRedisClient     # 默认原生 Redis 单机适配实现
    ├── RedisJedisClientFactory           # 默认 Jedis client 创建
    ├── RedisConnectionAssembler          # 连接配置解析与摘要构造
    └── RedisDatasourceDiagnostics        # 启动日志与脱敏诊断（待补齐）

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
adapters-common 提供默认 JedisPooled RuntimeRedisClient 和自动装配
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
  ├─ 绑定 MiddlewareProperties
  ├─ 判断 checkpointer.type 是否为 redis
  │     └─ 否: 不创建默认 RuntimeRedisClient
  ├─ 查找显式注册的 RuntimeRedisClient Bean
  │     ├─ 存在: 使用客户、集群或第三方适配 Bean
  │     └─ 不存在: 创建默认 JedisPooledRuntimeRedisClient
  ├─ 解析 checkpointer.redis-ref 指向的 redis.<ref> endpoint
  ├─ 输出数据源策略日志（脱敏）
  ├─ 将 RuntimeRedisClient 注入 Redis 使用方
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
| checkpointer 类型不支持 | 配置指定未知 checkpointer 类型 | 启动失败或 Redis 使用方创建失败 | 明确错误提示支持 `in_memory` 和 `redis`。 |
| Redis 连接引用缺失 | Redis 型能力启用但找不到连接定义 | 启动失败或 Redis 使用方创建失败 | 明确指出缺失的连接引用。 |
| 客户适配未注册 | 现场期望客户组件接管，但未注册自定义 `RuntimeRedisClient` Bean | 默认 Bean 会接管或 Redis 使用方缺 Bean 时报错 | 运维日志必须能看出当前实际 Bean，避免误以为客户组件生效。 |
| 默认客户端缺依赖 | 启用 Redis 但默认客户端不可用，且没有自定义 Bean | Redis 使用方创建失败 | 不静默改用内存模式。 |
| 连接失败 | Redis 服务不可达或认证失败 | Redis 使用方初始化或首次操作失败 | 错误日志脱敏，提示连接摘要。 |
| 命令语义不支持 | 客户组件无法提供某项必要命令 | 适配层显式失败 | 调用方获得可诊断异常。 |
| 密码配置存在 | 配置包含密码或加密密码 | 日志仅输出密码已配置状态 | 不输出明文或密文。 |

当前版本不定义跨数据源自动降级。配置指定 Redis 时，系统不得因为 Redis 初始化失败而静默退回内存存储，除非调用方显式配置为内存模式。

---

## 6. 配置使用

### 6.1 配置语义示例

以下示例忠实采用当前 PR 的配置格式。默认原生 Redis client 和开发者自定义 Redis client 都读取这组配置。

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: default
      redis:
        default:
          host: 127.0.0.1
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: "${REDIS_PASSWORD_ENCRYPTED:}"
```

如果要切换到另一个命名 Redis endpoint，只修改 `redis-ref` 和对应 `redis.<ref>` 配置：

```yaml
openjiuwen:
  service:
    middleware:
      checkpointer:
        type: redis
        redis-ref: cluster
      redis:
        cluster:
          host: redis-cluster-entry.example.com
          port: 6379
          database: 0
          timeout-ms: 3000
          encrypted-password: "${REDIS_PASSWORD_ENCRYPTED:}"
```

上面的 `cluster` 是命名 endpoint，不是当前 PR 新增的数据源类型字段。默认 Bean 会把该 endpoint 当作 host/port 形式的 Redis 入口创建 `JedisPooled`；如果现场需要原生 Redis 集群客户端或客户封装组件，需要额外注册自定义 `RuntimeRedisClient` Bean，并让该 Bean 读取同一个 `redis-ref` 指向的 endpoint。

### 6.2 启动日志要求

启动日志应包含：

- Redis connection ref。
- 当前生效的 `RuntimeRedisClient` Bean 类型，例如默认 `JedisPooledRuntimeRedisClient` 或客户自定义实现。
- host、port、database、timeout。
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
| checkpointer 类型解析 | 支持 `in_memory` 和 `redis`；未知类型报错。 |
| Redis endpoint 解析 | `redis-ref` 默认到 `default`，缺失命名 endpoint 时报错。 |
| 默认原生适配装配 | `checkpointer.type=redis` 且未注册自定义 Bean 时创建 `JedisPooledRuntimeRedisClient`。 |
| 客户适配优先级 | 已注册 `RuntimeRedisClient` Bean 时默认 Bean backs off。 |
| Redis 使用方依赖 | TaskStore 和 checkpointer 配置只依赖统一接口。 |
| 命令映射 | get、set、setex、setnx、del、exists、expire、mget、scan 语义正确。 |
| 日志脱敏 | 启动日志和错误日志不包含明文密码或加密密文。 |

### 7.2 集成测试

| 环境 | 验证点 |
|---|---|
| 原生 Redis 单机版 | 使用默认 `JedisPooledRuntimeRedisClient` 时，A2A Task 存储和 Agent 状态持久化读写正常。 |
| 原生 Redis 集群版 | 使用统一接入地址或自定义 `RuntimeRedisClient` Bean 时，同一业务代码读写正常。 |
| 配置切换 | 单机 endpoint 与集群入口 endpoint 之间只改 `redis-ref` / `redis.<ref>` 配置即可切换。 |
| 客户适配替身 | 使用内部 fake / stub `RuntimeRedisClient` Bean 验证客户模式装配链路和默认 Bean 让位行为。 |

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
