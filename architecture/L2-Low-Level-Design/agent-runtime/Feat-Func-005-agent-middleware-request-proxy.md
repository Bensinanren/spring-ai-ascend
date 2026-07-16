| level | L2-LLD |
|---|---|
| module | agent-service-adapters-agentcore-ext |
| feature_type | functional |
| feature_id | Feat-Func-005 |
| status | draft |
| dependency | https://github.com/chaosxingxc-orion/spring-ai-ascend/pull/415 https://gitcode.com/openJiuwen/agent-core-java (BaseAgent / DeepAgent / SkillManager) https://gitcode.com/openJiuwen/agent-runtime-java (MiddlewareProperties / JiuwenCoreAgentHandler) |

## SkillHub 中间件适配设计文档

> 目标模块：`agent-runtime-ext-java/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/`
> 需求来源：FEAT-005 智能体中间件请求代理特性文档（spring-ai-ascend PR #415，version 0715）
> 最后更新：2026-07-15

---

## 1. 概述

### 1.1 特性定位

FEAT-005 定义 `agent-runtime` 在部署或启动阶段代表 Agent 访问智能体中间件服务的当前版本事实，当前版本纳入范围的子特性是 **Skill Hub 代理**：runtime 读取部署态 middleware 配置，通过 Skill Hub SPI 访问 Skill Hub，下载 Agent 声明需要的 skill 包，校验完整性后把可注册的 skill 材料交给 `agent-core` 的 `BaseAgent.registerSkill()` 完成注册和使用。

本特性是 **runtime middleware 的一种**，配置模型、凭据处理、自动装配、诊断风格均对齐 runtime 已有 middleware（如 redis checkpointer）。实现归属 `agent-runtime-ext-java/agent-service-adapters-agentcore-ext`（ext 模块），通过扩展 `JiuwenCoreAgentExtHandler`（继承 runtime 的 `JiuwenCoreAgentHandler`）hook 到 agent 启动生命周期，不修改 runtime 仓库代码。

SkillHub 提供渐进式 skill 发现与加载能力：先列出轻量摘要，再按需加载完整定义和打包载荷，最终通过 `BaseAgent.registerSkill()` 安装到 agent 实例。

- **解决的问题**：Agent 的 skill 资产由外部 Skill Hub 管理，Agent 部署时只声明需要哪些 skill；runtime 需要在 Agent 启动前完成连接认证、访问控制、下载、完整性校验、诊断和移交，避免 Agent 开发者在业务代码中直接耦合 Skill Hub 服务 API 或凭据处理。
- **适用场景**：业务自建 `@Bean AgentHandler` 传入 `BaseAgent` 实例（如 `ReActAgent`）的场景。Agent 在 `start()` 阶段从 SkillHub 加载 skill 并注册到自身。
- **客户价值**：Agent 能力可以随部署环境、租户和业务场景进行配置化选择，技能目录可以由客户或平台统一治理。
- **非目标**：不是运行中动态 skill 调度能力；不是 `agent-core` 的 skill 解析、注册、执行或 prompt 组装规范；runtime 不解释 skill 包内容，不把 skill instructions 直接注入运行时 prompt，不接管框架内部 tool/skill 选择和执行，也不建立独立于 Skill Hub 的 skill 授权模型。

### 1.2 核心设计原则

1. **启动期安装** — Skill 安装在 `JiuwenCoreAgentExtHandler.start()` 阶段执行首次下载+校验+注册，不随每次请求重复注册
2. **稳定部署上下文** — Runtime 请求 SkillHub 时携带可稳定获得的部署态上下文（agentId、tenantId），不依赖每次请求的 user/session/task
3. **渐进式加载** — 摘要阶段不加载 instructions 或 skill 包；完整定义只在安装前按需加载
4. **Agent 自治** — `registerSkill` 已将 skill description 注入 prompt，适配层不重复注入 instructions，遵循 skill 懒加载设计原则
5. **SPI 分离** — `SkillHubProvider` 负责发现与加载（数据源无关），`SkillHubInstaller` 负责安装到 agent 实例（框架相关）
6. **配置归属分离** — Skill Hub 服务连接（endpoint、认证方式、加密凭据）由 runtime middleware 配置持有；Agent 配置不持有 Skill Hub 访问凭据
7. **分层失败语义**（PR #415）— required skill 的配置/认证/查找/移交失败 → fail fast 阻断 Agent ready；required skill 的下载或完整性校验失败 → 降级 ready + 后台重试；optional skill 任何失败 → 降级跳过。被跳过或未校验通过的 skill 不得注册为可用
8. **完整性校验 MUST**（PR #415）— runtime 必须在移交前校验下载材料：Skill Hub 支持 digest 时用 SHA-256 或更强摘要校验；不支持时回退常规校验（非空/大小/可读/必需文件）；校验方法必须记入日志；校验失败的材料不得注册
9. **请求链路外重试**（PR #415）— 下载/校验失败后由 `SkillHubRetryExecutor` 在请求链路外重试；重试成功后将 skill path 入队，由下一轮请求线程在入口串行注册（`drainAndRegister`，规避 SkillManager 非线程安全），skill 从下一轮新请求首次生效；首次有效注册后不再热刷新
10. **凭据与敏感信息保护** — token、认证头、密钥不得写入日志、错误响应、遥测数据；加密凭据经 `CredentialDecryptor` 解密后使用，明文不落盘、不进日志
11. **可选装配** — 仅当容器中存在 `SkillHubProvider` Bean 且配置启用时才激活 SkillHub 链路，不影响未使用 skill 的服务
12. **Skill 与 MCP 解耦** — SkillHub 只负责 skill 摘要、说明、依赖和包加载，不负责执行 MCP tool 或其他工具调用
13. **middleware 风格一致** — 配置 POJO + 静态嵌套类 + getter/setter，凭据 `encryptedToken` + `CredentialDecryptor`（对齐 runtime `MiddlewareProperties.RedisEndpoint.encryptedPassword` + `RedisMiddlewareAutoConfiguration` 解密模式），自动装配 `@ConditionalOnProperty` + `@ConditionalOnMissingBean` + `ObjectProvider`

### 1.3 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|--------|------|---------|------|
| Skill 数据模型 | Skill 摘要（含 required 标记）、定义、工具依赖、打包载荷 | `SkillSummary`, `SkillDefinition`, `SkillToolDependency`, `SkillPackage` | ✅ |
| 稳定部署上下文 | 携带 agentId、tenantId 等部署态信息 | `SkillHubContext` | ✅ |
| SkillHub middleware 配置 | runtime 持有 endpoint、认证方式、加密凭据 | `SkillHubMiddlewareProperties` | ✅ |
| SkillHub SPI | 运行时中性的 skill 发现与加载接口 | `SkillHubProvider` | ✅ |
| Agent 适配安装器 | 将 SkillHub skills 安装到 BaseAgent 实例；执行分层失败语义 | `SkillHubInstaller` | ✅ |
| 完整性校验 | 分层校验下载材料（SHA-256 优先 / 常规兜底） | `SkillIntegrityVerifier` | ✅ |
| 后台重试执行器 | 请求链路外重试下载+校验，成功后入队待注册 path | `SkillHubRetryExecutor` | ✅ |
| 错误诊断与分类 | 连接/认证/不存在/下载/校验/移交失败分类诊断 | `SkillHubErrorCategory` | ✅ |
| 自动装配 | 条件注册 SkillHub 链路 Bean | `SkillHubMiddlewareAutoConfiguration` | ✅ |
| openJiuwen 默认实现 | 对接 `openJiuwen/skillhub` 服务 API 的默认 Provider | `OpenJiuwenSkillHubProvider` | ✅ |
| Agent skill 选择配置驱动 | Agent 声明 `skills:[{id,version,required}]` 驱动获取 | — | ⬜ 第一期不做，listSkills 返回全部安装 |

---

## 2. 特性规格

### 2.1 能力清单

> FEAT-005 §2 能力要求映射（PR #415）：MUST = ✅；SHOULD = ✅（默认实现，可降级）；不承诺 = ⬜。

| 能力 | 状态 | 说明 |
|------|------|------|
| 部署/启动阶段代理访问 | ✅ MUST | runtime 在 Agent 部署或启动阶段读取配置并访问 Skill Hub，不在用户 query 请求过程中动态拉取 skill |
| Skill Hub 服务配置归 runtime | ✅ MUST | endpoint、认证方式、加密凭据和连接策略由 `SkillHubMiddlewareProperties` 持有；Agent 配置不持有 Skill Hub 明文访问凭据 |
| Skill Hub SPI | ✅ MUST | `SkillHubProvider` 可替换访问边界，默认实现和自定义实现可在不改变 Agent 业务代码的前提下替换 |
| openJiuwen Skill Hub 默认实现 | ✅ MUST | `OpenJiuwenSkillHubProvider` 对接 `openJiuwen/skillhub` 服务 API（`/plugins`、`/plugins/{id}/versions/{ver}`、`/artifacts/{id}`） |
| skill 包下载 | ✅ MUST | `SkillHubProvider.loadSkillPackage(ctx, skillId)` 返回 `SkillPackage`（zip 包含 `SKILL.md` + 引用文件） |
| 完整性校验 MUST | ✅ MUST | runtime 必须在移交前校验下载材料：Skill Hub 支持 digest 时用 SHA-256 或更强摘要（可信渠道提供）；不支持时回退常规校验（非空/大小/可读/必需文件）；校验方法记入日志；失败材料不得注册 |
| 注册材料移交 | ✅ MUST | 下载且通过完整性校验的 skill 材料移交给 `agent-core` 的 `BaseAgent.registerSkill(path)`；注册、解析、执行归属 `agent-core` |
| required 非下载失败 fail fast | ✅ MUST | required skill 的配置/认证/查找/移交失败时抛异常，阻断 `Runner.start()`，Agent 不 ready |
| required 下载/校验失败降级+重试 | ✅ MUST | required skill 的下载或完整性校验失败时 Agent 降级为 ready；runtime 在请求链路外重试；重试成功后 skill 从下一轮新请求首次生效 |
| optional skill 降级 | ✅ SHOULD | optional skill 失败时跳过该 skill 并继续启动，输出脱敏降级诊断；被跳过的 skill 不得注册为可用 |
| 凭据与敏感信息保护 | ✅ MUST | token 不写入日志、错误响应、遥测数据；加密凭据经 `CredentialDecryptor` 解密后使用（详见 §4.11） |
| 错误诊断 | ✅ MUST | 连接/认证/拒绝访问/不存在/下载/校验/移交失败时通过 `SkillHubErrorCategory` 输出明确且不泄露敏感信息的诊断 |
| Skill 摘要列表 | ✅ | `SkillHubProvider.listSkills(context)` 返回 `List<SkillSummary>`，含 `required` 字段；Provider 可按部署态上下文过滤 |
| Skill 完整定义加载 | ✅ | `SkillHubProvider.loadSkill(context, skillId)` 返回 `SkillDefinition`，按需加载 |
| 稳定部署上下文透传 | ✅ | `SkillHubContext` 携带 agentId、tenantId 等部署态信息，不依赖请求级 user/session/task |
| Skill path 注册 | ✅ | 从 `SkillDefinition.metadata()` 读取 `openjiuwen.skill.path` / `openjiuwen.skill.paths`，调用 `BaseAgent.registerSkill(path)` |
| 安装日志与诊断 | ✅ | 日志输出 tenantId、agentId、summary 数量、loaded 数量、installed 数量、skipped 数量、retrying 数量、skip reason 与错误分类 |
| 无 Provider 降级 | ✅ | 未配置 SkillHubProvider 时 Agent 正常启动和执行 |
| DeepAgent skill 安装 | ✅ | 保留 `install(DeepAgent)` 适配代码，取 inner ReActAgent 安装；当前项目无 DeepAgent 路径但兼容后演进 |
| 渐进式加载 | ✅ | 摘要阶段不加载 instructions；完整定义只在安装前按需加载 |
| 完整 instructions 不注入 prompt | ✅ | `registerSkill` 已注入 description，不重复注入 instructions |
| 首次有效注册后不热刷新 | ✅ | 后台重试成功首次注册后，同一 skill 不再重复注册或热替换 |
| 请求级动态 skill 过滤 | ⬜ | 当前版本不要求基于每次请求的 user/session/task 动态变更 skill 集合 |
| agent-id 场景 skill 安装 | ⬜ | adapter 层无法获取 agent 实例（详见 §9.2） |
| Agent skill 选择配置驱动 | ⬜ | 第一期不做 Agent 声明 `skills:[{id,version,required}]` 驱动；`listSkills` 返回的全部 skill 按其 `required` 字段决定 fail fast / 降级行为 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|--------|------|------|
| 请求级 skill 安装 | `registerSkill` 是累积注册，每次请求重复安装会导致重复；FEAT-005 明确不在用户 query 请求过程中动态拉取 skill | 启动期 `start()` 首次安装 + 后台重试 |
| `injectRuntimeSkillSection` prompt 注入 | FEAT-005 §5.1.4 明确：runtime 不把 skill instructions 注入运行时 prompt；`registerSkill` 已注入 description | 依赖 Agent 自身的 skill prompt 机制 |
| 请求级上下文（user/session/task） | FEAT-005 §5.2 明确不承诺请求级动态获取 | 使用稳定部署上下文（agentId、tenantId） |
| agent-id 字符串场景 | adapter 层无法获取 agent 实例，且注册的 agent 可能不是 `BaseAgent` | 仅支持 `BaseAgent` 实例场景（详见 §9.2） |
| Agent skill 选择配置驱动（第一期） | 第一期保持 `listSkills(ctx)` 返回全部安装的模型；Agent 声明 `skills:[{id,version,required}]` 驱动推迟到后续版本 | 每个 `SkillSummary` 携带 `required` 字段，由 Provider 实现按部署上下文决定 |
| 首次有效注册后热刷新 | FEAT-005 §5.2 明确不承诺运行中自动刷新、热替换、卸载或按策略切换 skill；但允许后台重试首次注册 | 后台重试成功后首次注册生效，之后不再重复注册 |
| Agent 自主决策获取 | FEAT-005 §5.2 不承诺 Agent 在推理过程中自主决定从 Skill Hub 获取新 skill | 启动期一次性安装 + 后台重试 |
| 独立 skill 授权模型 | FEAT-005 §5.1.1 明确：授权由 Skill Hub 根据 runtime 凭据判定，runtime 不维护独立 Agent-skill 授权规则 | 通过 `SkillHubContext` 的 agentId/tenantId 让 Skill Hub 侧判定 |
| Skill Hub 服务端能力 | FEAT-005 §5.2 不定义 Skill Hub 服务端的管理、审批、运营、存储、审计或发布流程 | 通过 Provider SPI 对接外部 Skill Hub |
| `agent-core` skill 语义 | FEAT-005 §5.2 不定义 `agent-core` 的 skill 格式、解析、注册、执行、prompt 注入、渐进加载或模型上下文处理策略 | 由 `agent-core` 框架自身处理 |
| MCP tool 执行 | SkillHub 不负责调用 MCP tool | 工具调用由 MCP Provider 或框架 tool 机制处理 |
| 缓存与重试策略固定 | FEAT-005 §5.2 不在 version-scope 固定下载缓存、分页、断点续传或本地落盘策略（后台重试次数/间隔由 runtime 配置） | 由 Provider 实现自行治理；后台重试策略由 `SkillHubRetryExecutor` 配置 |

### 2.3 接口契约

#### SkillHubContext（稳定部署上下文）

```java
/** 稳定部署上下文，在 Handler start() 阶段构建，传给 SkillHubProvider。 */
public record SkillHubContext(String agentId, String tenantId) {
    public SkillHubContext {
        agentId = agentId == null ? "" : agentId;
        tenantId = tenantId == null ? "default" : tenantId;
    }
}
```

#### SkillHubProvider SPI

> 模块/包归属：`agent-service-spec-ext` 项目，包 `com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider`（对齐 runtime `agent-service-spec.spec.spi.AgentHandler`，纯接口无实现依赖，no Spring）。

```java
/** 运行时中性的 SkillHub SPI，在 Handler start() 阶段以稳定部署上下文调用。 */
public interface SkillHubProvider {
    /** 返回当前稳定部署上下文可见的 skill 摘要列表（含 required 标记）。 */
    List<SkillSummary> listSkills(SkillHubContext context);

    /** 按 skillId 加载完整 skill 定义。 */
    SkillDefinition loadSkill(SkillHubContext context, String skillId);

    /** 按 skillId 加载打包 skill 载荷（可选）。 */
    default SkillPackage loadSkillPackage(SkillHubContext context, String skillId) {
        throw new UnsupportedOperationException("skill package loading is not supported");
    }
}
```

**SPI 异常约定**：Provider 实现应将连接失败、认证失败（401/403）、skill 不存在（404）、下载失败、校验失败等分类为 `IllegalStateException` 抛出（复用 JDK 异常，不新建异常类），分类信息通过 message 前缀 `SkillHub[CATEGORY]` 携带，`CATEGORY` 取自 `SkillHubErrorCategory`（详见 §4.10）。未分类的其他异常由 installer 兜底为 `UNKNOWN`。

#### SkillHubInstaller 安装契约

```java
/**
 * 将 SkillHub skills 安装到 agent 实例的安装器。
 * 对齐 RemoteA2aToolInstaller 模式：静态工厂 + install + 幂等。
 */
public class SkillHubInstaller {
    /**
     * 在 Handler start() 阶段安装 skills 到 agent 实例。
     *
     * 分层失败语义（PR #415）：
     *   - required skill 配置/认证/查找/移交失败 → 抛异常，调用方据此阻断 Runner.start()，Agent 不 ready
     *   - required skill 下载/完整性校验失败 → 降级（不抛异常），提交后台重试，Agent 可 ready
     *   - optional skill 任何失败 → 内部捕获并记录降级诊断，不抛异常，被跳过的 skill 不得注册为可用
     */
    public void installBeforeStart(Object agent, SkillHubContext context) { ... }

    /** 无操作 installer。 */
    public static SkillHubInstaller noop() { ... }
}
```

#### 行为承诺

- **必须**：`installBeforeStart` 仅在 `agent instanceof DeepAgent` 或 `agent instanceof BaseAgent` 时执行安装
- **必须**：skill path 从 `SkillDefinition.metadata()` 的 `openjiuwen.skill.path`（单个 String）或 `openjiuwen.skill.paths`（Iterable）读取
- **必须**：下载材料移交前通过 `SkillIntegrityVerifier` 校验；校验失败的材料不得注册，按 required/optional 语义处理
- **必须**：`SkillHubProvider.listSkills(context)` 返回 null 时按空列表处理，不抛异常
- **必须**：日志输出 tenantId、agentId、summary 数量、loaded 数量、installed 数量、skipped 数量、retrying 数量、skip reason 与错误分类
- **必须**：required skill 的配置/认证/查找/移交失败时 `installBeforeStart` 抛异常，`JiuwenCoreAgentExtHandler.start()` 据此不调用 `super.start()`（不调用 `Runner.start()`）
- **必须**：required skill 的下载/校验失败时 `installBeforeStart` 不抛异常，提交 `SkillHubRetryExecutor` 后台重试，Agent 降级 ready
- **必须**：optional skill 失败时跳过并输出脱敏降级诊断，该 skill 不得被注册为可用
- **必须**：后台重试成功后将 skill path 入队 `pendingSkillPaths`，由下一轮请求线程在入口 `drainAndRegister(agent)` 串行注册（规避 SkillManager 非线程安全），完成 first effective registration；同一 skill 不重复注册（重名时 `SkillManager.register` 抛 `IllegalStateException`，drainAndRegister 视为已注册并忽略）
- **必须**：日志、错误响应、遥测数据不得输出 API key、token、认证头、内部敏感地址或 skill 包中的敏感内容
- **必须**：未装配 SkillHubProvider 时不影响 Agent 启动和请求执行
- **必须**：`OpenJiuwenSkillHubProvider` 作为默认实现，对接 `openJiuwen/skillhub` 服务 API；业务方可通过自定义 `SkillHubProvider` Bean 覆盖
- **禁止**：在每次请求时重复调用 `installBeforeStart`
- **禁止**：向 prompt builder 注入 skill instructions（由 Agent 自身 skill 机制处理）
- **禁止**：在摘要阶段加载大量 instructions 或 skill 包
- **禁止**：将凭据明文写入持久化配置、日志或错误响应
- **禁止**：将未通过完整性校验的材料注册为可用
- **允许**：agent 非 `BaseAgent`/`DeepAgent` 时跳过安装并输出 warn 日志
- **允许**：Provider 自行缓存摘要、定义或远端响应

---

## 3. 模块结构

### 3.1 模块分布

FEAT-005 横跨两个 ext 项目，对齐 runtime 的 `agent-service-spec`（纯契约）↔ `agent-service-adapters`（实现）分层：

```
agent-solution/common/
├── agent-service-spec-ext/                         # 新增项目：纯契约（SPI + DTO），扩展 runtime agent-service-spec
│   └── src/main/java/com/openjiuwen/service/spec/ext/
│       └── skillhub/
│           ├── spi/
│           │   └── SkillHubProvider.java          # 运行时中性 SPI 接口（对齐 spec.spi.AgentHandler）
│           ├── dto/
│           │   ├── SkillSummary.java              # record（含 required 字段）
│           │   ├── SkillDefinition.java           # record
│           │   ├── SkillPackage.java              # record
│           │   └── SkillToolDependency.java       # record
│           ├── SkillHubContext.java               # 稳定部署上下文（record）
│           ├── SkillHubErrorCategory.java         # 错误分类枚举
│           └── package-info.java
│
└── agent-runtime-ext-java/                        # 已有项目：实现层
    └── agent-service-adapters/agent-service-adapters-agentcore-ext/
        └── src/main/java/com/openjiuwen/service/adapters/agentcore/ext/
            ├── agentfw/                           # 已有：JiuwenCoreAgentExtHandler
            ├── external/                          # 已有：RemoteA2aToolInstaller / RemoteA2aInterruptRail
            ├── autoconfigure/                     # 已有：AgentCoreExtAutoConfiguration
            └── middleware/                        # 新增：实现层（对齐 runtime agent-service-adapters 分层）
                └── skillhub/                      # skillhub 特性子包（对齐 runtime middleware/redis）
                    ├── SkillHubMiddlewareProperties.java  # POJO + 静态嵌套类（对齐 MiddlewareProperties 风格）
                    ├── SkillHubMiddlewareAutoConfiguration.java  # 自动装配（对齐 RedisMiddlewareAutoConfiguration）
                    ├── SkillHubInstaller.java     # 安装器（对齐 RemoteA2aToolInstaller 模式）
                    ├── SkillHubRetryExecutor.java # 后台重试执行器（请求链路外重试）
                    ├── SkillIntegrityVerifier.java # 完整性校验器（SHA-256 优先 / 常规兜底）
                    ├── openjiuwen/               # 默认实现子包（对齐 redis/ 内聚）
                    │   └── OpenJiuwenSkillHubProvider.java  # 默认实现：对接 openJiuwen/skillhub API
                    └── package-info.java
```

**模块坐标与依赖**：

| 模块 | groupId:artifactId | version | 依赖 | 对齐 runtime 模块 |
|---|---|---|---|---|
| `agent-service-spec-ext` | `com.openjiuwen:agent-service-spec-ext` | `0.1.0` | `com.openjiuwen:agent-service-spec:0.1.0`（纯契约，no Spring） | `agent-service-spec` |
| `agent-service-adapters-agentcore-ext` | `com.openjiuwen:agent-service-adapters-agentcore-ext` | `0.1.0` | `agent-service-spec-ext:0.1.0` + `agent-service-adapters-agentcore:0.1.0` + `agent-core-java:0.1.13` | `agent-service-adapters-agentcore` |

依赖方向（单向，无环）：
```
agent-service-spec-ext  →  agent-service-spec (runtime)
        ▲
        │ 依赖契约
        │
agent-service-adapters-agentcore-ext  →  agent-service-adapters-agentcore (runtime)
                                      →  agent-core-java
```

**分层说明**（对齐 runtime 模块分布）：

runtime 的模块分层为 `agent-service-spec`（SPI 接口 + DTO，纯契约无实现，no Spring）↔ `agent-service-adapters`（实现层）。FEAT-005 用两个 ext 项目对齐该分层，而非在单一 jar 内用包结构模拟：

| 项目 | 对齐 runtime 模块 | 职责 | Spring 依赖 |
|---|---|---|---|
| `agent-service-spec-ext`（`com.openjiuwen.service.spec.ext.skillhub`） | `agent-service-spec` | `SkillHubProvider` SPI 接口 + `SkillSummary`/`SkillDefinition`/`SkillPackage`/`SkillToolDependency` DTO + `SkillHubContext`/`SkillHubErrorCategory`（纯契约） | 无（no Spring，仅 jackson-annotations + lombok） |
| `agent-service-adapters-agentcore-ext`（`...ext.middleware.skillhub`） | `agent-service-adapters-agentcore` | `Installer`/`RetryExecutor`/`Verifier`/`Properties`/`AutoConfiguration`/`OpenJiuwenProvider`（实现） | 有（Spring Boot autoconfigure） |

**为什么新建独立 spec-ext 项目而非放进 agentcore-ext 包内**：
1. **契约纯净性**：`agent-service-spec-ext` 像 runtime `agent-service-spec` 一样是 no Spring 纯契约模块，业务方自定义 `SkillHubProvider` 实现时只需依赖 `agent-service-spec-ext`（轻量），不引入 Spring autoconfigure 和 runtime adapters 的重依赖
2. **对等定位**：runtime 把 spec 独立成 artifact，ext 同样把 spec-ext 独立成 artifact，分层定位完全对等
3. **复用性**：未来其他 ext 特性（不止 skillhub）的 SPI/DTO 也可放入 `agent-service-spec-ext` 的不同子包（`spec.ext.<feature>.spi` / `spec.ext.<feature>.dto`），形成统一的 ext 契约层

**为什么不能把 SPI/DTO 直接放进 runtime 的 `agent-service-spec` 模块**：ext 以 jar 依赖引入 runtime，不能修改 runtime 的 spec 模块。故新建 `agent-service-spec-ext` 作为 runtime spec 的扩展契约层。

**配置类说明**：`agent-service-adapters-agentcore-ext` 以 jar 依赖引入 runtime（`agent-runtime-java` 0.1.0）和 agent-core（0.1.13），不能修改 runtime 的 `MiddlewareProperties`。`SkillHubMiddlewareProperties` 在 agentcore-ext 独立定义，但风格（POJO + 静态嵌套类 + getter/setter + `encryptedToken`）和前缀（`openjiuwen.service.middleware.skillhub`）对齐 runtime middleware 约定。runtime 已提供 `CredentialDecryptor` 模块（`agent-service-adapters-common.credential`，含 `PassthroughCredentialDecryptor` 默认直通实现），agentcore-ext 通过 `agent-service-adapters-agentcore` → `agent-service-adapters-common` 传递依赖可访问。`SkillHubMiddlewareAutoConfiguration` 注入 `CredentialDecryptor` Bean，调用 `decryptor.decrypt(encryptedToken)` 获取明文 token（对齐 `RedisMiddlewareAutoConfiguration` 的 `decryptor.decrypt(endpoint.getEncryptedPassword())` 模式）。

### 3.2 核心类静态关系

```
«autoconfigure»                      «installer»
SkillHubMiddleware              →   SkillHubInstaller
AutoConfiguration                    │
  │ @ConditionalOnProperty           │ installBeforeStart(agent, ctx)
  │   openjiuwen.service.            │
  │   middleware.skillhub.enabled    ├─ agent instanceof DeepAgent
  │ @ConditionalOnMissingBean        │   ? → install(deepAgent.getAgent(), ctx)
  │   SkillHubProvider               │   : instanceof BaseAgent
  │  默认 = OpenJiuwen               │       ? → install(baseAgent, ctx)
  │  SkillHubProvider                │       : → skip (warn)
  │                                  │
  ▼                                  │ 分层失败语义：
SkillHubProvider                ←────┤   required 配置/认证/查找/移交失败 → throw
(OpenJiuwen 默认                     │   required 下载/校验失败 → degrade + retry
 或 业务 @Bean 覆盖)                 │   optional 任何失败 → warn + skip
                                     │
                                     ▼
                                SkillIntegrityVerifier
                                  .verify(package, digest)
                                  ├─ SHA-256（digest 可用时）
                                  └─ 常规校验（非空/大小/可读/必需文件）

                                SkillHubRetryExecutor
                                  .submitRetry(agent, skillId, ctx)
                                  └─ 后台线程重试下载+校验+注册
                                     成功 → agent.registerSkill(path)
                                     失败 → 继续重试（有上限）

«agentfw»
JiuwenCoreAgentExtHandler (extends JiuwenCoreAgentHandler)
  │
  ├─ start()   ← override
  │    ├─ skillHubInstaller.installBeforeStart(agent, ctx)   ← 新增
  │    │     throw → 不调用 super.start()，Agent 不 ready
  │    │     degrade → 继续调用 super.start()，后台重试
  │    └─ super.start()
  │         ├─ middlewareAdapterRegistrar.applyToRunnerConfig(...)
  │         ├─ externalSvcAdapterRegistrar.registerToRunner()
  │         └─ Runner.start()
  │
  └─ streamQuery / query  ← 无 skill 安装（继承父类，A2A install 不受影响）

«config»
SkillHubMiddlewareProperties  ← @ConfigurationProperties("openjiuwen.service.middleware.skillhub")
  ├─ enabled (boolean, default false)
  ├─ endpoint (String)
  ├─ authType (String, system-token | bearer)
  ├─ encryptedToken (String)   ← 加密凭据，经 CredentialDecryptor.decrypt() 解密（对齐 encryptedPassword）
  ├─ provider (String, openjiuwen | custom)
  └─ retry (Retry)             ← 静态嵌套类
       ├─ maxAttempts (int, default 3)
       ├─ initialDelayMs (long, default 5000)
       └─ maxDelayMs (long, default 60000)
```

---

## 4. 核心设计

### 4.1 Skill 安装时序（含降级 + 后台重试）

```
应用启动
  │
  ▼
JiuwenCoreAgentExtHandler.start()
  │
  ├─ SkillHubContext ctx = buildSkillHubContext(agentId, tenantId)
  │
  ├─ try { skillHubInstaller.installBeforeStart(agent, ctx) }
  │     │
  │     │  ┌─────────────────────────────────────────────────────────────┐
  │     │  │ installBeforeStart 内部逻辑                                  │
  │     │  │                                                             │
  │     │  ├─ agent instanceof DeepAgent ?                               │
  │     │  │   ├─ yes → innerAgent = deepAgent.getAgent()                │
  │     │  │   └─ no → instanceof BaseAgent ?                             │
  │     │  │       ├─ yes → target = baseAgent                            │
  │     │  │       └─ no → warn, skip (return)                            │
  │     │  │                                                             │
  │     │  ├─ SkillHubProvider.listSkills(ctx) → List<SkillSummary>       │
  │     │  │     catch CONNECT_FAILED/AUTH_FAILED →                       │
  │     │  │       required → throw (fail fast)                           │
  │     │  │       optional-only → warn, return (degrade)                 │
  │     │  │                                                             │
  │     │  ├─ for each summary:                                           │
  │     │  │   try {                                                      │
  │     │  │     loadSkill(ctx, skillId) → SkillDefinition                │
  │     │  │       catch NOT_FOUND →                                      │
  │     │  │         required → throw (fail fast)                         │
  │     │  │         optional → warn + skip                               │
  │     │  │                                                             │
  │     │  │     loadSkillPackage(ctx, skillId) → SkillPackage            │
  │     │  │       catch DOWNLOAD_FAILED →                                │
  │     │  │         required → degrade: submitRetry(agent, ...)          │
  │     │  │         optional → warn + skip                               │
  │     │  │                                                             │
  │     │  │     SkillIntegrityVerifier.verify(package, digest)           │
  │     │  │       catch CHECKSUM_MISMATCH →                              │
  │     │  │         required → degrade: submitRetry(agent, ...)          │
  │     │  │         optional → warn + skip                               │
  │     │  │                                                             │
  │     │  │     解压 package → skill path                                │
  │     │  │     target.registerSkill(path)                               │
  │     │  │       catch INSTALL_FAILED →                                 │
  │     │  │         required → throw (fail fast)                         │
  │     │  │         optional → warn + skip                               │
  │     │  │                                                             │
  │     │  │     log: installed skillId, integrityMethod                  │
  │     │  │   }                                                          │
  │     │  └─────────────────────────────────────────────────────────────┘
  │     │
  │     catch (非降级异常) → 不调用 super.start()，Agent 不 ready
  │     │   （降级异常已在内部处理，不会传播到此处）
  │     │
  │     ├─ 提交的后台重试任务（SkillHubRetryExecutor）异步执行：
  │     │     ┌──────────────────────────────────────────────────────────┐
  │     │     │ 后台线程（请求链路外）                                    │
  │     │     │                                                          │
  │     │     │ for attempt in 1..maxAttempts:                           │
  │     │     │   sleep(backoff)                                         │
  │     │     │   try {                                                  │
  │     │     │     loadSkillPackage(ctx, skillId) → pkg                  │
  │     │     │     SkillIntegrityVerifier.verify(pkg, digest)            │
  │     │     │     解压 → path                                          │
  │     │     │     pendingSkillPaths.offer(path)  ← 入队，不直接注册     │
  │     │     │     log: retry succeeded, path queued                     │
  │     │     │     return  ← 首次有效入队完成，停止重试                 │
  │     │     │   } catch (DOWNLOAD/CHECKSUM) {                           │
  │     │     │     continue  ← 继续重试                                  │
  │     │     │   } catch (其他) {                                        │
  │     │     │     log: retry failed permanently                         │
  │     │     │     return  ← 非下载/校验失败，停止重试                   │
  │     │     │   }                                                      │
  │     │     │                                                          │
  │     │     │ log: retry exhausted, skill never available               │
  │     │     └──────────────────────────────────────────────────────────┘
  │     │
  │     └─ "下一轮请求首次生效"：后台重试成功后 path 已入队 pendingSkillPaths，
  │        下一个进来的 streamQuery/query 请求入口调用 drainAndRegister 串行注册后即能用到的 skill
  │
  ├─ super.start()
  │     ├─ middlewareAdapterRegistrar.applyToRunnerConfig(...)
  │     ├─ externalSvcAdapterRegistrar.registerToRunner()
  │     └─ Runner.start()
  │
  ▼
请求处理（streamQuery / query）
  │
  └─ Runner.runAgentStreaming(agent, ...)   ← 无 skill 安装
```

**关键语义**：
- `installBeforeStart` 对每个 skill 独立 try/catch
- required skill 配置/认证/查找/移交失败 → 抛异常向上传播 → `start()` 不调用 `super.start()`，Agent 不 ready
- required skill 下载/校验失败 → 降级（不抛异常）+ 提交后台重试 → `start()` 继续 `super.start()`，Agent ready（但该 skill 暂不可用）
- optional skill 任何失败 → warn + skip + 降级诊断，该 skill 不注册为可用
- 后台重试成功 → path 入队 `pendingSkillPaths`，下一轮请求线程入口 `drainAndRegister(agent)` 串行注册，首次生效
- 后台重试首次成功入队后不再重复（幂等保护）；`drainAndRegister` 遇重名 `IllegalStateException` 视为已注册并忽略

### 4.2 稳定部署上下文

`SkillHubContext` 携带部署态可稳定获得的信息，不依赖请求级 user/session/task：

| 字段 | 来源 | 默认值 | 用途 |
|---|---|---|---|
| `agentId` | `ServiceProperties.agentId` 或 agent 实例的 `getCard().getId()` | `""` | Provider 按 agent 过滤 skill |
| `tenantId` | `ServiceProperties` 或配置 | `"default"` | Provider 按租户过滤 skill |

构建时机：`JiuwenCoreAgentExtHandler.start()` 阶段，从 Spring 容器注入的配置或 agent 实例中提取。

**与请求上下文的区别**：需求文档明确，当前版本不要求 Provider 基于每次请求的 user/session/task 动态变更 skill 集合。`SkillHubContext` 是部署态稳定的，不会随请求变化。如业务确需请求级权限过滤，应由网关、业务 Provider 预筛选或独立 Agent/Handler 实例承接。

### 4.3 Skill path 解析规则

`SkillDefinition.metadata()` 中的 skill path 通过两个 key 声明：

| Metadata Key | 类型 | 说明 |
|---|---|---|
| `openjiuwen.skill.path` | `String` | 单个 skill 路径（如 `"skills/hotel"`） |
| `openjiuwen.skill.paths` | `Iterable<?>` 或 `String` | 多个 skill 路径 |

解析顺序：先取 `openjiuwen.skill.path`，再取 `openjiuwen.skill.paths`。每个候选值仅当为非空白 `String` 时才加入路径列表。

### 4.4 完整性校验（MUST）

依据 PR #415：runtime 必须在移交前校验下载材料。`SkillIntegrityVerifier` 实施分层校验：

```
SkillPackage（下载材料）
  │
  ▼
SkillIntegrityVerifier.verify(package, digestInfo)
  │
  ├─ 分支 1：Skill Hub 提供 digest（checksumSha256 非空）
  │    ├─ 计算 package.content 的 SHA-256
  │    ├─ 对比计算值与 digestInfo.checksumSha256
  │    ├─ 匹配 → 校验通过，integrityMethod="sha256"
  │    └─ 不匹配 → 抛 CHECKSUM_MISMATCH
  │
  ├─ 分支 2：Skill Hub 不提供 digest（checksumSha256 为空）
  │    ├─ 常规校验：
  │    │   ├─ content 非空
  │    │   ├─ content.length > 0
  │    │   ├─ content.length == digestInfo.fileSize（若提供）
  │    │   ├─ content 可解压为 zip（若 mediaType=application/zip）
  │    │   └─ 解压后包含必需文件（SKILL.md）
  │    ├─ 全部通过 → 校验通过，integrityMethod="conventional"
  │    └─ 任一失败 → 抛 CHECKSUM_MISMATCH
  │
  └─ 返回 IntegrityResult(method, verified)
```

**校验方法记录**：无论哪个分支，日志必须记录 `integrityMethod`（`sha256` 或 `conventional`），以及校验结果。

**校验失败处理**：
- 校验失败的材料**不得注册**
- required skill 校验失败 → 降级 ready + 后台重试（与下载失败同等处理）
- optional skill 校验失败 → warn + skip

**digest 可信渠道**：digest 必须来自 Skill Hub 服务端返回的元数据（如 `/artifacts/{id}` 响应中的 `checksum_sha256`），不接受 skill 包内部自带的 digest 文件作为唯一校验源。

### 4.5 后台重试执行器（SkillHubRetryExecutor）

依据 PR #415：下载/校验失败后 runtime 在请求链路外重试。

```java
/**
 * 请求链路外的后台重试执行器。
 * 下载/校验失败的 required skill 提交至此，在后台线程重试下载+校验。
 * 重试成功后将 skill path 入队（pendingSkillPaths），由请求线程在入口 drainAndRegister 串行注册，
 * skill 从下一轮新请求首次生效。首次有效注册后不再重试（幂等保护）。
 */
public class SkillHubRetryExecutor {
    private final SkillHubProvider provider;
    private final SkillIntegrityVerifier verifier;
    private final ScheduledExecutorService scheduler;
    private final int maxAttempts;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final Set<String> retriedSkillIds = ConcurrentHashMap.newKeySet();

    /**
     * 后台重试成功后待注册的 skill path 队列（并发安全）。
     * 因 SkillManager 非线程安全（§9.3），后台线程不能直接调用 registerSkill，
     * 改为入队，由请求线程在 streamQuery/query 入口串行注册。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingSkillPaths =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 请求线程入口调用：drain 队列并串行注册。由 JiuwenCoreAgentExtHandler 在请求处理前调用。 */
    public void drainAndRegister(Object agent) {
        String path;
        while ((path = pendingSkillPaths.poll()) != null) {
            try {
                agent_registerSkill(agent, path);
            } catch (Exception ex) {
                // 重名时 SkillManager.register(overwrite=false) 抛 IllegalStateException，
                // 视为已注册成功，忽略
                if (!isAlreadyExists(ex)) {
                    log.warn("SkillHub drainAndRegister failed path={} reason={}", path, ex.getMessage());
                }
            }
        }
    }

    private static boolean isAlreadyExists(Exception ex) {
        return ex instanceof IllegalStateException
                && ex.getMessage() != null
                && ex.getMessage().contains("Skill already exists");
    }

    @SuppressWarnings("unchecked")
    private static void agent_registerSkill(Object agent, String path) {
        // 反射调用 BaseAgent.registerSkill(Object)，避免编译期依赖 agent-core
        try {
            java.lang.reflect.Method m = agent.getClass().getMethod("registerSkill", Object.class);
            m.invoke(agent, path);
        } catch (Exception ex) {
            throw new IllegalStateException("SkillHub[INSTALL_FAILED] registerSkill failed: " + ex.getMessage(), ex);
        }
    }

    // 可重试的错误分类（仅下载/校验这类瞬时故障重试；auth/lookup 等不重试）
    // 与 §4.10 异常约定一致：Provider 抛 IllegalStateException，message 前缀 "SkillHub[CATEGORY]"
    private static final Set<String> RETRYABLE_CATEGORIES =
            Set.of("DOWNLOAD_FAILED", "CHECKSUM_MISMATCH");
    private static final String CATEGORY_PREFIX = "SkillHub[";

    /**
     * 提交一个 skill 的后台重试任务。
     * 已提交过的 skillId 不重复提交（幂等）。
     */
    void submitRetry(Object agent, String skillId, SkillHubContext ctx) {
        if (!retriedSkillIds.add(skillId)) {
            return; // 已在重试中，不重复提交
        }
        scheduleAttempt(agent, skillId, ctx, 1);
    }

    private void scheduleAttempt(Object agent, String skillId, SkillHubContext ctx, int attempt) {
        long delay = Math.min(initialDelayMs * (1L << (attempt - 1)), maxDelayMs); // 指数退避
        scheduler.schedule(() -> attemptInstall(agent, skillId, ctx, attempt), delay, TimeUnit.MILLISECONDS);
    }

    private void attemptInstall(Object agent, String skillId, SkillHubContext ctx, int attempt) {
        try {
            SkillPackage pkg = provider.loadSkillPackage(ctx, skillId);
            verifier.verify(pkg, extractDigest(pkg));
            String path = extractSkillPath(pkg);
            // 线程安全：SkillManager 的 registry/updateAtCache/skillOrder 均为非线程安全集合
            // （LinkedHashMap/ArrayList，无同步保护），后台线程不能直接调用 registerSkill。
            // 改为将 path 放入并发安全队列，由请求线程在 streamQuery/query 入口串行注册（见 §9.3）。
            pendingSkillPaths.offer(path);  // ConcurrentLinkedQueue<String> pendingSkillPaths
            log.info("SkillHub retry succeeded skillId={} attempt={} pathQueued effectiveFromNextRequest",
                    skillId, attempt);
            retriedSkillIds.remove(skillId); // 首次有效注册完成
        } catch (Exception ex) {
            // 复用 JDK 异常（不新建异常类，见 §4.10）：Provider 抛 IllegalStateException
            // 通过 message 前缀 "SkillHub[CATEGORY]" 提取分类判断是否可重试
            String category = extractCategory(ex);
            if (RETRYABLE_CATEGORIES.contains(category)) {
                if (attempt < maxAttempts) {
                    log.warn("SkillHub retry failed skillId={} attempt={}/{} category={} willRetry",
                            skillId, attempt, maxAttempts, category);
                    scheduleAttempt(agent, skillId, ctx, attempt + 1);
                } else {
                    log.error("SkillHub retry exhausted skillId={} attempts={} category={} skillNeverAvailable",
                            skillId, maxAttempts, category);
                    retriedSkillIds.remove(skillId);
                }
            } else {
                // 非下载/校验失败（如 auth/lookup/unknown），不重试
                log.error("SkillHub retry stopped skillId={} attempt={} category={} reason=non-retryable",
                        skillId, attempt, category);
                retriedSkillIds.remove(skillId);
            }
        }
    }

    /** 从异常 message 前缀 "SkillHub[CATEGORY] ..." 提取 CATEGORY；无法识别时返回 "UNKNOWN"。 */
    private static String extractCategory(Exception ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.startsWith(CATEGORY_PREFIX)) {
            int end = msg.indexOf(']', CATEGORY_PREFIX.length());
            if (end > 0) {
                return msg.substring(CATEGORY_PREFIX.length(), end);
            }
        }
        return "UNKNOWN";
    }
}
```

**设计要点**：
- **请求链路外**：重试在 `ScheduledExecutorService` 后台线程执行，不阻塞请求
- **指数退避**：`initialDelayMs * 2^(attempt-1)`，上限 `maxDelayMs`
- **幂等保护**：`retriedSkillIds` 确保同一 skill 不重复提交；首次有效注册后移除
- **不新建异常类**：复用 `IllegalStateException`（与 §4.10 约定一致，符合 agent-runtime-ext-java 模块约束）。Provider 抛出时 message 前缀 `SkillHub[CATEGORY]`；RetryExecutor 通过 `extractCategory(ex)` 提取分类，仅 `DOWNLOAD_FAILED` / `CHECKSUM_MISMATCH` 可重试，auth/lookup/unknown 等非瞬时故障不重试
- **线程安全（已确认风险）**：agent 实例在 start() 后长期存在，后台线程持有 agent 引用调用 `registerSkill`。已核实 agent-core `SkillManager` 的 `registry`（`LinkedHashMap`）/`updateAtCache`（`LinkedHashMap`）/`skillOrder`（`ArrayList`）均为非线程安全集合，且 `register`/`refreshIncrementally`/`clearAll` 等方法无同步保护。后台重试线程与请求线程并发访问会污染 skill registry——**必须采用 §9.3 降级方案**（请求前串行注册），不能直接在后台线程调用 `registerSkill`

**"下一轮请求首次生效"语义**：后台重试成功后 skill path 入队 `pendingSkillPaths`，下一个进来的 `streamQuery`/`query` 请求在处理前由 `JiuwenCoreAgentExtHandler` 调用 `retryExecutor.drainAndRegister(agent)` 串行注册（规避 SkillManager 非线程安全），注册完成后该请求即可用到 skill。

### 4.6 安装结果验证

`BaseAgent` 的 skill 计数通过 `agent.getSkillUtil().getSkillManager().count()` 获取。安装前后计数对比：

| before | after | 判定 | 日志级别 |
|---|---|---|---|
| -1 | -1 | `SkillUtil` 或 `SkillManager` 为 null | warn（skill 运行时未初始化） |
| N | N | 计数未增长 | warn（SKILL.md 可能缺少 YAML frontmatter） |
| N | N+1 | 计数增长 | info（安装成功） |

**注意**：安装结果验证（skillCount 对比）是辅助诊断手段，不替代完整性校验（§4.4）。完整性校验在 registerSkill 之前执行；skillCount 对比在 registerSkill 之后执行，用于确认注册是否生效。

### 4.7 自动装配条件

```
SkillHubMiddlewareAutoConfiguration
  │
  ├─ @EnableConfigurationProperties(SkillHubMiddlewareProperties.class)
  ├─ @ConditionalOnProperty(
  │     prefix = "openjiuwen.service.middleware.skillhub",
  │     name = "enabled", havingValue = "true")
  ├─ @ConditionalOnClass(RunnerConfig.class)
  │
  ├─ @Bean SkillHubProvider
  │   @ConditionalOnMissingBean(SkillHubProvider.class)
  │   → String token = decryptor.decrypt(properties.getEncryptedToken())
  │   → new OpenJiuwenSkillHubProvider(properties.getEndpoint(), token, properties.getAuthType())
  │   （注入 CredentialDecryptor，对齐 RedisMiddlewareAutoConfiguration 的 decryptor.decrypt(endpoint.getEncryptedPassword())）
  │
  ├─ @Bean SkillIntegrityVerifier
  │   @ConditionalOnMissingBean
  │   → new SkillIntegrityVerifier()
  │
  ├─ @Bean SkillHubRetryExecutor
  │   @ConditionalOnMissingBean
  │   → new SkillHubRetryExecutor(provider, verifier, properties.getRetry())
  │
  ├─ @Bean SkillHubInstaller
  │   @ConditionalOnMissingBean
  │   → new SkillHubInstaller(provider, verifier, retryExecutor)
  │
  └─ 注入到 JiuwenCoreAgentExtHandler
      └─ @Autowired(required = false) SkillHubInstaller
          → 有则注入，无则 SkillHubInstaller.noop()
```

无 `SkillHubProvider` Bean 或 `enabled=false` 时，整条 SkillHub 链路不激活，`JiuwenCoreAgentExtHandler` 使用 noop installer，行为与无 SkillHub 时完全一致。

**对齐 runtime middleware 模式**：
- `@ConditionalOnProperty` 控制激活（同 `RedisMiddlewareAutoConfiguration`）
- `@ConditionalOnMissingBean` 允许业务覆盖（同 redis client bean）
- `OpenJiuwenSkillHubProvider` 构造时注入解密后的明文 token（`SkillHubMiddlewareAutoConfiguration` 调用 `decryptor.decrypt(properties.getEncryptedToken())`，对齐 `RedisMiddlewareAutoConfiguration` 的 `decryptor.decrypt(endpoint.getEncryptedPassword())`）
- `ObjectProvider` 用于可选依赖（同 `AgentCoreExtAutoConfiguration` 的 `A2ARemoteAgentCardRegistry`）

### 4.8 安装顺序与冲突语义

SkillHub installer 与 MCP installer、远程 Agent tool installer 在同一启动阶段执行时，顺序和冲突语义必须可诊断。当前项目中：
- middleware 注册（checkpointer，`applyToRunnerConfig`）→ SkillHub 安装（`installBeforeStart`）→ external 注册（MCP/Remote/Sandbox，`registerToRunner`）→ `Runner.start()`
- **required skill 非下载失败阻断后续流程**：抛异常后 `start()` 不调用 `super.start()`，**不执行** external 注册，**不调用** `Runner.start()`，Agent 不 ready
- **required skill 下载/校验失败不阻断**：降级 + 后台重试后继续 `super.start()`，进入 external 注册和 `Runner.start()`
- **optional skill 失败不阻断**：warn + skip 后继续处理下一个 skill；optional 全部失败后仍正常进入 external 注册和 `Runner.start()`
- external 注册（MCP/Remote/Sandbox）与 SkillHub 相互独立，external 失败的语义由 external 自身决定，不被 SkillHub 失败影响

### 4.9 required / optional 行为矩阵

| 字段 | 来源 | 默认值 | 用途 |
|---|---|---|---|
| `SkillSummary.required` | Provider 实现按部署上下文决定（如 metadata、远端权限、配置） | `false`（optional） | installer 据此决定 fail fast / 降级 / 重试 |

**失败行为矩阵**（PR #415 更新）：

| skill 类型 | 失败阶段 | 错误分类 | 行为 | Agent ready | 后台重试 |
|---|---|---|---|---|---|
| required | listSkills | `CONNECT_FAILED` | **throw**（fail fast） | ✗ 阻断 | ✗ |
| required | listSkills | `AUTH_FAILED` | **throw**（fail fast） | ✗ 阻断 | ✗ |
| required | listSkills | `ACCESS_DENIED` | **throw**（fail fast） | ✗ 阻断 | ✗ |
| required | loadSkill | `NOT_FOUND` | **throw**（fail fast） | ✗ 阻断 | ✗ |
| required | loadSkillPackage | `DOWNLOAD_FAILED` | **degrade + retry** | ✓ 降级 ready | ✅ |
| required | verify | `CHECKSUM_MISMATCH` | **degrade + retry** | ✓ 降级 ready | ✅ |
| required | registerSkill | `INSTALL_FAILED` | **throw**（fail fast） | ✗ 阻断 | ✗ |
| optional | 任一阶段 | 任一分类 | **warn + skip** | ✓ 继续 | ✗ |
| 无 Provider | — | — | noop，正常启动 | ✓ | ✗ |

**降级诊断要求**：optional skill 跳过时日志必须包含 tenantId、agentId、skillId、required=false、failureCategory、failureReason（脱敏，不含凭据或敏感内容）。required skill 降级时日志必须包含 tenantId、agentId、skillId、required=true、failureCategory、retryScheduled=true。

### 4.10 错误诊断与分类

`SkillHubErrorCategory` 枚举覆盖 FEAT-005 §5.1.5 的失败分类：

| Category | 触发条件 | required 行为 | optional 行为 | 后台重试 |
|---|---|---|---|---|
| `CONNECT_FAILED` | endpoint 缺失或不可达 | fail fast | 降级 skip | ✗ |
| `AUTH_FAILED` | 凭据缺失/无效/过期/401/403 | fail fast | 降级 skip | ✗ |
| `ACCESS_DENIED` | Skill Hub 拒绝访问 | fail fast | 降级 skip | ✗ |
| `NOT_FOUND` | skill 不存在或无权访问 | fail fast | 降级 skip | ✗ |
| `DOWNLOAD_FAILED` | 下载中断、包损坏 | **降级 + 重试** | 降级 skip | ✅ |
| `CHECKSUM_MISMATCH` | 完整性校验失败 | **降级 + 重试** | 降级 skip | ✅ |
| `INSTALL_FAILED` | registerSkill 后 skillCount 未增长 | fail fast | 降级 skip | ✗ |
| `UNSUPPORTED` | loadSkillPackage 不支持且 skill 需要 package | fail fast | 降级 skip | ✗ |
| `UNKNOWN` | 未分类异常兜底 | fail fast | 降级 skip | ✗ |

**异常约定**：复用 JDK 异常（`IllegalStateException`），不新建异常类。分类信息通过异常 message 前缀携带：

```java
// 不新建异常类，复用 IllegalStateException
throw new IllegalStateException("SkillHub[" + category + "] skillId=" + skillId + ": " + sanitizedMessage);
```

**诊断输出要求**：
- 日志可输出 adapter 名称、endpoint 摘要（不含路径后的 query/认证信息）、skill id、required/optional、failureCategory、integrityMethod、correlation 信息
- 日志不得输出明文 token、认证头、密钥、内部敏感地址或敏感 skill 内容
- 错误响应对外暴露时只返回分类和脱敏摘要，不暴露内部地址或凭据

### 4.11 凭据与敏感信息保护

依据 FEAT-005 §2 和 §5.1.5，对齐 runtime middleware 凭据处理模式。runtime 已提供 `CredentialDecryptor` 模块（`agent-service-adapters-common.credential`），ext 通过传递依赖访问。

| 项 | 要求 |
|---|---|
| 凭据存储 | `SkillHubMiddlewareProperties.encryptedToken` 持有密文 token（对齐 `MiddlewareProperties.RedisEndpoint.encryptedPassword`） |
| 凭据解密 | `SkillHubMiddlewareAutoConfiguration` 注入 `CredentialDecryptor` Bean，调用 `decryptor.decrypt(encryptedToken)` 获取明文 token（对齐 `RedisMiddlewareAutoConfiguration`）；默认实现 `PassthroughCredentialDecryptor` 直通返回（密文=明文），业务可通过自定义 Bean 覆盖为真实解密 |
| 凭据使用 | `OpenJiuwenSkillHubProvider` 构造时接收解密后的明文 token，作为 HTTP 认证头值；明文 token 不持久化、不进日志 |
| 日志 | 不得输出 token、API key、认证头、内部敏感地址、skill 包敏感内容；只记录 `credential=provided` 或 `credential=absent` |
| 错误响应 | 对外只返回 `SkillHubErrorCategory` 和脱敏摘要，不暴露 endpoint 完整路径、凭据片段或内部堆栈 |
| 遥测数据 | 同日志要求；如需 metric，只输出计数和分类，不输出值内容 |
| 持久化配置 | `encryptedToken` 密文可写入配置文件；明文 token 不得写入提交到代码仓库的配置文件 |
| 异常堆栈 | 异常 message 已脱敏；原始异常的 stacktrace 不输出到响应 |

**`OpenJiuwenSkillHubProvider` 凭据处理**：
- 构造时接收解密后的明文 token（由 `SkillHubMiddlewareAutoConfiguration` 通过 `CredentialDecryptor.decrypt()` 解密后传入）
- 注入 HTTP 头 `Authorization: Bearer <token>` 或 `X-System-Token: <token>`
- token 不进入日志、异常消息或 metric 标签
- 日志只记录 `credential=provided` 或 `credential=absent`

---

## 5. 数据模型

> **模块/包归属**：本节数据模型均为纯契约（record/enum），归属 `agent-service-spec-ext` 项目 `com.openjiuwen.service.spec.ext.skillhub` 及 `com.openjiuwen.service.spec.ext.skillhub.dto` 包（对齐 runtime `agent-service-spec.spec.dto`），no Spring，不含实现依赖。实现类（`Installer`/`Verifier` 等）在 `agent-service-adapters-agentcore-ext` 项目的 `...ext.middleware.skillhub` 包（§4）。

### 5.1 SkillHubContext

```java
public record SkillHubContext(String agentId, String tenantId) {
    public SkillHubContext {
        agentId = agentId == null ? "" : agentId;
        tenantId = tenantId == null ? "default" : tenantId;
    }
}
```

### 5.2 SkillSummary

```java
public record SkillSummary(
        String skillId,           // 必填，blank 抛 IllegalArgumentException
        String name,              // 可选，null/blank 时回退为 skillId
        String description,       // 可选，null → ""
        boolean required,         // 是否启动必需：true 时非下载失败阻断 ready；false 时降级跳过
        List<String> tags,        // 可选，null → List.of()，不可变副本
        Map<String, Object> metadata  // 可选，null → Map.of()，不可变副本
) { }
```

`required` 字段由 Provider 实现按部署上下文决定（如来自 Skill Hub 返回的权限标记、metadata 或部署配置）。installer 据此执行 §4.9 的分层失败语义。

### 5.3 SkillDefinition

```java
public record SkillDefinition(
        String skillId,                      // 必填
        String name,                         // 可选，回退为 skillId
        String description,                  // 可选
        String instructions,                 // 可选，完整使用指令，不默认注入 prompt
        List<String> referenceUris,          // 可选，不可变副本
        List<SkillToolDependency> toolDependencies,  // 可选，不可变副本
        Map<String, Object> metadata         // 可选，不可变副本
) { }
```

### 5.4 SkillToolDependency

```java
public record SkillToolDependency(
        String type,                    // 可选，null/blank → "unknown"
        String name,                    // 可选，null → ""
        Map<String, Object> metadata    // 可选，不可变副本
) { }
```

### 5.5 SkillPackage

```java
public record SkillPackage(
        String skillId,                 // 必填
        String mediaType,               // 可选，null/blank → "application/octet-stream"
        byte[] content,                 // 可选，null → empty，防御性拷贝
        Map<String, Object> metadata    // 可选，null → Map.of()，不可变副本；含 checksumSha256、fileSize 等
) {
    @Override
    public byte[] content() {           // 返回防御性拷贝
        return Arrays.copyOf(content, content.length);
    }
}
```

**完整性校验相关 metadata 字段**：
- `checksumSha256`（String，可空）：Skill Hub 服务端返回的 SHA-256 摘要；非空时触发 SHA-256 校验
- `fileSize`（Long，可空）：服务端返回的文件大小；用于常规校验的大小一致性检查

### 5.6 SkillHubMiddlewareProperties（middleware 配置模型）

对齐 runtime `MiddlewareProperties` 风格：POJO + 静态嵌套类 + getter/setter（非 record）。凭据字段 `encryptedToken` + `CredentialDecryptor` 解密（对齐 `MiddlewareProperties.RedisEndpoint.encryptedPassword`）。

```java
@ConfigurationProperties(prefix = "openjiuwen.service.middleware.skillhub")
public class SkillHubMiddlewareProperties {
    private boolean enabled = false;
    private String endpoint = "";
    private String authType = "system-token";   // system-token | bearer
    private String encryptedToken = "";          // 加密凭据，经 CredentialDecryptor.decrypt() 解密（对齐 encryptedPassword）
    private String provider = "openjiuwen";      // openjiuwen | custom
    private Retry retry = new Retry();

    // getter/setter 省略

    public static class Retry {
        private int maxAttempts = 3;
        private long initialDelayMs = 5000L;
        private long maxDelayMs = 60000L;

        // getter/setter 省略
    }
}
```

**配置归属说明**（依据 FEAT-005 §5.1.1）：
- Skill Hub 服务连接配置（endpoint、authType、encryptedToken、provider、retry）归 runtime middleware 配置持有
- Agent 配置不持有 Skill Hub 访问凭据
- `encryptedToken` 为密文，经 `CredentialDecryptor.decrypt()` 解密后使用（对齐 `encryptedPassword`）；默认 `PassthroughCredentialDecryptor` 直通返回，业务可通过自定义 Bean 覆盖为真实解密

**配置示例**：

```yaml
openjiuwen:
  service:
    middleware:
      skillhub:
        enabled: true
        endpoint: https://swarmskills.openjiuwen.com
        auth-type: system-token
        encrypted-token: ${SKILLHUB_ENCRYPTED_TOKEN:}   # 加密凭据，经 CredentialDecryptor 解密；默认直通实现时密文=明文
        provider: openjiuwen
        retry:
          max-attempts: 3
          initial-delay-ms: 5000
          max-delay-ms: 60000
```

---

## 6. JiuwenCoreAgentExtHandler 扩展

### 6.1 新增字段和 setter

扩展已有的 `JiuwenCoreAgentExtHandler`（继承 runtime `JiuwenCoreAgentHandler`），新增 SkillHubInstaller 注入：

```java
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();
    private SkillHubInstaller skillHubInstaller = SkillHubInstaller.noop();  // 新增

    // 现有构造器保留

    @Autowired(required = false)
    void setRemoteA2aToolInstaller(RemoteA2aToolInstaller remoteToolInstaller) {
        this.remoteToolInstaller = Objects.requireNonNull(remoteToolInstaller);
    }

    @Autowired(required = false)               // 新增
    void setSkillHubInstaller(SkillHubInstaller skillHubInstaller) {
        this.skillHubInstaller = Objects.requireNonNull(skillHubInstaller);
    }

    // ... 现有 streamQuery / query 不变（A2A installBeforeRun 保留）
}
```

### 6.2 start() 改动

```java
@Override
public void start() {
    SkillHubContext context = buildSkillHubContext();
    try {
        skillHubInstaller.installBeforeStart(getAgent(), context);
    } catch (Exception ex) {
        // required skill 非下载失败（config/auth/lookup/handover）时阻断 Agent ready
        // 下载/校验失败已在 installer 内部降级处理，不会传播到此处
        log.error("SkillHub install failed, agent not ready tenantId={} agentId={} reason={}",
                context.tenantId(), context.agentId(), sanitize(ex.getMessage()));
        throw ex;
    }
    super.start();  // 仅当 installBeforeStart 未抛异常时执行
}

private SkillHubContext buildSkillHubContext() {
    String agentId = resolveAgentId(getAgent());
    String tenantId = "default";  // 后续可从 ServiceProperties 或配置扩展
    return new SkillHubContext(agentId, tenantId);
}

private static String resolveAgentId(Object agent) {
    if (agent instanceof String s) return s;
    try {
        Method getter = agent.getClass().getMethod("getCard");
        Object card = getter.invoke(agent);
        if (card != null) {
            Method idGetter = card.getClass().getMethod("getId");
            Object id = idGetter.invoke(card);
            if (id != null) return String.valueOf(id);
        }
    } catch (ReflectiveOperationException ignored) { }
    return agent == null ? "" : agent.getClass().getSimpleName();
}
```

`streamQuery` / `query` 方法不变——skill 安装不在此层处理（A2A tool 安装仍在 `installBeforeRun` 中，与 SkillHub 互不影响）。

**分层失败语义说明**：
- `installBeforeStart` 抛异常时（required 非下载失败），`start()` 不调用 `super.start()`，Agent 不 ready
- `installBeforeStart` 降级返回时（required 下载/校验失败 或 optional 失败），`start()` 继续 `super.start()`，Agent ready（降级 skill 暂不可用，后台重试中）
- 日志输出 category、skillId、integrityMethod、retryScheduled，不输出凭据或敏感内容
- 异常向上传播，由 `InitPhaseExecutor` 决定是否阻断应用启动

---

## 7. 对外呈现 / 用户场景

### 7.1 业务接入方式

业务方自建 `@Bean AgentHandler` 传入 `BaseAgent` 实例，配置 SkillHub middleware 即可自动安装 skill：

```java
@Bean
public AgentHandler agentHandler(BaseAgent agent,
        MiddlewareAdapterRegistrar middlewareRegistrar,
        ExternalSvcAdapterRegistrar externalRegistrar) {
    // 使用 ExtHandler 而非 runtime 的 JiuwenCoreAgentHandler
    return new JiuwenCoreAgentExtHandler(agent, middlewareRegistrar, externalRegistrar);
}
```

```yaml
openjiuwen:
  service:
    middleware:
      skillhub:
        enabled: true
        endpoint: https://swarmskills.openjiuwen.com
        auth-type: system-token
        encrypted-token: ${SKILLHUB_ENCRYPTED_TOKEN:}   # 加密凭据，经 CredentialDecryptor 解密
```

Spring Boot 自动装配会：
1. 绑定 `SkillHubMiddlewareProperties`
2. 当 `enabled=true` 时创建 `OpenJiuwenSkillHubProvider`、`SkillIntegrityVerifier`、`SkillHubRetryExecutor`、`SkillHubInstaller` bean
3. 通过 `@Autowired(required = false)` 注入到 `JiuwenCoreAgentExtHandler`
4. `start()` 时自动执行 skill 下载+校验+注册

### 7.2 Skill 定义 metadata 约定

Skill Hub 侧的 skill 定义 metadata 应包含 skill path 声明：

```yaml
# SkillDefinition.metadata()
openjiuwen.skill.path: "skills/hotel-booking"      # 单个路径
# 或
openjiuwen.skill.paths:                             # 多个路径
  - "skills/hotel-booking"
  - "skills/hotel-cancellation"
```

### 7.3 日志输出示例

**正常安装**：
```
INFO  SkillHub install started tenantId=default agentId=hotel-agent summaryCount=2
INFO  SkillHub skill loaded skillId=hotel-booking definitionLoaded=true
INFO  SkillHub integrity verified skillId=hotel-booking method=sha256 verified=true
INFO  SkillHub skill registered skillId=hotel-booking skillCountBefore=0 skillCountAfter=1
INFO  SkillHub install completed tenantId=default agentId=hotel-agent installed=2 skipped=0 retrying=0
```

**required skill 下载失败降级 + 后台重试**：
```
WARN  SkillHub skill download failed skillId=hotel-booking required=true category=DOWNLOAD_FAILED retryScheduled=true
INFO  SkillHub install completed (degraded) tenantId=default agentId=hotel-agent installed=1 skipped=0 retrying=1
INFO  SkillHub retry succeeded skillId=hotel-booking attempt=2 integrityMethod=sha256 effectiveFromNextRequest=true
```

**optional skill 跳过**：
```
WARN  SkillHub skill skipped skillId=weather-lookup required=false category=DOWNLOAD_FAILED reason=download-interrupted
INFO  SkillHub install completed tenantId=default agentId=hotel-agent installed=1 skipped=1 retrying=0
```

**无 Provider 降级**：
```
INFO  SkillHub not active (no provider or disabled), agent starts normally
```

### 7.4 E2E 流程

1. 应用启动 → Spring 装配 `SkillHubMiddlewareProperties` + `OpenJiuwenSkillHubProvider` + `SkillHubInstaller`
2. `JiuwenCoreAgentExtHandler.start()` 调用 `installBeforeStart(agent, ctx)`
3. Installer 调用 `listSkills(ctx)` → 获取 skill 摘要列表
4. 对每个 skill：`loadSkill` → `loadSkillPackage` → `verify` → 解压 → `registerSkill`（启动期，主线程）
5. required 下载/校验失败 → 降级 + `submitRetry`；required 非下载失败 → throw；optional 失败 → skip
6. `super.start()` → `Runner.start()`（若未 throw）
7. 后台重试成功 → path 入队 `pendingSkillPaths`（不直接注册）
8. 请求处理 → `JiuwenCoreAgentExtHandler` 入口先 `retryExecutor.drainAndRegister(agent)` 串行注册（规避 SkillManager 非线程安全）→ `Runner.runAgentStreaming` → agent 使用已注册的 skill

### 7.5 无 Provider 降级

未配置 `SkillHubProvider` Bean 或 `enabled=false` 时：
- `SkillHubInstaller.noop()` 注入到 `JiuwenCoreAgentExtHandler`
- `installBeforeStart` 无操作
- Agent 正常启动和执行，无 skill 安装

### 7.6 `OpenJiuwenSkillHubProvider` API 映射

默认实现对接 `openJiuwen/skillhub` 服务 API（依据 TeamSkillsHub 接口参考）：

| SPI 方法 | HTTP API | 说明 |
|---|---|---|
| `listSkills(ctx)` | `GET /api/v1/plugins?plugin_type=skill` | 返回已通过审核的 skill 列表；`asset_id`→`skillId`，`name`/`display_name`→`name`，`moderation_status=APPROVED` 过滤 |
| `loadSkill(ctx, skillId)` | `GET /api/v1/plugins/{id}/versions/{ver}` + `GET /api/v1/plugins/{id}/versions/{ver}/files?with_content=SKILL.md` | 版本元数据 + SKILL.md 内容 → `instructions` |
| `loadSkillPackage(ctx, skillId)` | `GET /api/v1/artifacts/{id}?version={ver}` | 预签名 URL 下载 zip → `SkillPackage.content`（byte[]），`checksum_sha256`→metadata |

**认证**：
- `authType=system-token` → HTTP 头 `X-System-Token: <token>`（服务端集成，运维发放的静态 token）
- `authType=bearer` → HTTP 头 `Authorization: Bearer <token>`（通过外部 OAuth 流程获取后注入的 access_token）
- 两种方式的 token 均由 `SkillHubMiddlewareAutoConfiguration` 调用 `CredentialDecryptor.decrypt(properties.getEncryptedToken())` 解密后传入 `OpenJiuwenSkillHubProvider`，不进入日志
- runtime 只负责"使用解密后的 token 作为认证头"，不执行 OAuth 浏览器交互流程

**版本选择**：默认取 `latest_version`；若需要固定版本，通过 `SkillSummary.metadata().get("version")` 声明。

**required 字段来源**：`OpenJiuwenSkillHubProvider` 默认按 `metadata.get("required")` 或 Skill Hub 返回的 `plugin_type`/标签推断；业务方可通过 `providerProps` 自定义规则。

**完整性校验数据来源**：`/artifacts/{id}` 响应中的 `checksum_sha256` 和 `file_size` 写入 `SkillPackage.metadata()`，供 `SkillIntegrityVerifier` 使用。

### 7.7 场景覆盖（FEAT-005 §4）

| 场景 | 期望行为 |
|---|---|
| 启动时获取 required skill | runtime 通过 SkillHub SPI 认证访问，下载 skill 包，完整性校验通过后移交注册；Agent ready |
| required skill 下载/校验失败 | Agent 降级为 ready；runtime 在请求链路外重试；重试成功后 skill 从下一轮新请求首次生效 |
| required skill 配置/认证/查找/移交失败 | 启动失败；诊断说明 skill 不可用但不泄露凭据、内部地址或敏感内容 |
| required skill 不存在 | 启动失败（fail fast）；诊断说明 skill 不可用 |
| optional skill 下载失败 | runtime 跳过该 optional skill，输出脱敏降级诊断；Agent 可继续 ready，但该 skill 不注册 |
| 凭据缺失或无效 | required skill 场景 fail fast；optional skill 场景按降级规则处理；日志和错误不输出凭据 |
| 替换 Skill Hub 实现 | 业务方提供自定义 `@Bean SkillHubProvider` 覆盖默认实现；Agent 业务代码不需修改 |
| Skill Hub 不支持 digest | 回退常规校验（非空/大小/可读/必需文件），日志记录 integrityMethod=conventional |
| 后台重试耗尽 | 日志告警 skill never available；Agent 继续运行（已降级 ready）；该 skill 永远不注册 |

---

## 8. 测试矩阵（PR #415）

依据 PR #415 Change 3，测试必须覆盖以下场景：

| # | 测试场景 | 验证点 | 预期结果 |
|---|---|---|---|
| T1 | required skill 非下载失败（config/auth/lookup） | fail fast 阻断 ready | `start()` 抛异常，`Runner.start()` 未调用，`RUNNER_STARTED=false` |
| T2 | required skill 下载失败 | 降级 ready + 后台重试 | `start()` 正常返回，`Runner.start()` 已调用；`SkillHubRetryExecutor.submitRetry` 被调用 |
| T3 | required skill 校验失败（checksum mismatch） | 降级 ready + 后台重试 | 同 T2，且 `integrityMethod=sha256`，`CHECKSUM_MISMATCH` 分类 |
| T4 | 后台重试成功后下一轮请求首次生效 | first effective registration | 重试成功后 path 入队 `pendingSkillPaths`；下一轮 `streamQuery` 入口 `drainAndRegister` 串行注册；该请求能用到的 skill |
| T5 | SHA-256 校验通过 | digest 校验 | `integrityMethod=sha256`，`verified=true`，skill 注册成功 |
| T6 | 常规校验通过（无 digest） | 常规校验兜底 | `integrityMethod=conventional`，`verified=true`，skill 注册成功 |
| T7 | 校验失败拒绝注册 | 不注册未校验材料 | `CHECKSUM_MISMATCH`，skill 未注册，required 降级+重试 / optional skip |
| T8 | 首次有效注册后不热刷新 | 幂等保护 | 同一 skill 后台重试成功后不重复注册；再次重试不触发 |
| T9 | optional skill 任何失败 | 降级 skip | warn 日志 + 跳过，该 skill 不注册，Agent ready |
| T10 | 日志脱敏 | 不泄露敏感信息 | 日志无 token/认证头/敏感凭据，只有 `credential=provided/absent` |
| T11 | 无 Provider 降级 | noop 不影响启动 | `enabled=false` 或无 Provider 时 Agent 正常启动 |
| T12 | 后台重试耗尽 | 重试上限 | 重试 `maxAttempts` 次后停止，日志告警 skill never available |
| T13 | required 移交失败（INSTALL_FAILED） | fail fast | registerSkill 后 skillCount 未增长，`start()` 抛异常 |
| T14 | DeepAgent 适配 | 取 inner ReActAgent | `install(DeepAgent)` 取 `deepAgent.getAgent()` 安装 |

---

## 9. 限制与待补

### 9.1 为什么 SkillHub 不通过 Runner 配置 skill

FEAT-005 选择通过 `BaseAgent.registerSkill(path)`（agent 实例方法）安装 skill，而非在 `RunnerConfig` 中配置 skill 字段。原因如下（已核实 agent-core 0.1.13 代码）：

**1. RunnerConfig 无 skill 字段，且 ext 无法修改**

`RunnerConfig`（`com.openjiuwen.core.runner.RunnerConfig`）当前仅包含运行时基础设施字段：`checkpointerConfig`、`mcpServers`、`kvStoreConfig`、`vectorStoreConfig`、`objectStorageConfig`。无任何 skill 相关字段。ext 模块以 jar 依赖引入 agent-core，不能修改 `RunnerConfig` 添加 skill 字段。

**2. harness 层的 `skillDirectories` 是静态本地路径，非运行时远程下载**

agent-core 在 harness 配置层（`DeepAgentConfig.skillDirectories`、`SubAgentConfig.skillDirectories`）提供了 skill 目录配置，但这是**静态本地路径列表**：在 harness 构建期由 `HarnessConfigBuilder.skillDirectories(resolveSkillDirs(...))` 从配置文件解析本地目录，供 `SkillUseRail` 加载本地 skill 文件。该机制：
- 仅支持本地文件系统路径，不支持远程 Skill Hub 下载
- 在 harness 构建期解析，不在运行时请求链路中执行
- 不涉及完整性校验、降级重试等 FEAT-005 要求的运行时能力

因此 `skillDirectories` 无法满足 FEAT-005"运行时从远程 Skill Hub 下载 + 完整性校验 + 降级重试"的需求。

**3. skill 注册是 agent 实例方法，注册路径由 agent 实例决定**

`BaseAgent.registerSkill(Object)` 是 agent **实例方法**（非静态方法），注册目标是特定 agent 实例的 `SkillManager`。Runner 层不持有 agent 实例（agent 实例由 `Supplier<Object>` 工厂按需创建，见 §9.2），因此 Runner 层无法直接调用 `registerSkill`。

**4. middleware hook 的时机窗口在 handler `start()`，而非 Runner 配置链路**

runtime middleware 的安装 hook 在 `JiuwenCoreAgentExtHandler.start()` 阶段（`MiddlewareAdapterRegistrar.applyToRunnerConfig` 之后、`Runner.start()` 之前），此时 handler 持有 agent 实例（或 agent-id），是唯一能调用 `agent.registerSkill(path)` 的时机窗口。在 `RunnerConfig` 构建阶段（middleware adapter 注册期）agent 实例尚未创建，无法注册 skill。

**结论**：FEAT-005 通过 `JiuwenCoreAgentExtHandler.start()` hook 拿到 agent 实例后调用 `BaseAgent.registerSkill(path)` 安装 skill，是当前 agent-core API 下唯一可行的运行时远程 skill 安装路径。

**agent-core 为什么设计通过 agent 注册 skill（设计意图分析）**：

这是有意的领域模型设计，skill 是 agent 的私有能力而非全局基础设施。代码证据（agent-core 0.1.13）：

| 证据 | 位置 | 说明 |
|---|---|---|
| `SkillManager` 持有 `sysOperationId` | `SkillManager.java:35` | agent 实例级标识，用于文件操作隔离和操作追踪 |
| `SkillUtil` 构造时传入 `sysOperationId` 组合 `SkillManager` + `RemoteSkillUtil` | `SkillUtil.java:30-32` | skill 工具集与 agent 实例绑定 |
| `BaseAgent.lazyInitSkill()` 从 agent config 取 `sysOperationId` 创建 `SkillUtil` | `BaseAgent.java:55-67` | skill 在 agent 构造期初始化，是 agent 身份的一部分 |
| `ReActAgent.buildPrompt()` 用 `getSkillUtil().hasSkill()` 注入 skill section | `ReActAgent.java:513` | skill 直接影响该 agent 的 prompt |
| `SkillUtil.SKILL_PROMPT_CONTENT` 模板把 skill 内容注入系统提示词 | `SkillUtil.java:19-22` | skill 是 agent prompt 的组成部分 |

对比 `RunnerConfig` 的定位（`RunnerConfig.java` javadoc）："Runner global configuration... SPI injection fields for checkpointer, KV store, vector store, and object storage"。RunnerConfig 持有的是**无状态、跨 agent 共享**的基础设施 provider（checkpointer/KV/vector/objectStorage/mcpServers），而 skill 是**有状态、agent 私有**的能力（影响 prompt、绑定 sysOperationId）。Runner 不持有 agent 实例（`Runner.java:35-36`：`Runner.runAgent(agent, ...)` 的 agent 由调用方传入），因此 Runner 层无法直接注册 skill。

**后续演进路径**（若 agent-core 采纳某路径，FEAT-005 可平滑迁移）：

| 路径 | 改动量 | 方案 | 优点 | 缺点 |
|---|---|---|---|---|
| **A. SkillSourceProvider SPI** | 小 | agent-core 新增 `SkillSourceProvider` SPI 接口；`BaseAgent.lazyInitSkill()` 时主动调用 SPI 按 agent 身份拉取 skill 路径 | 改动最小；保持 skill 是 agent 私有状态的领域模型 | SPI 需能根据 AgentCard/sysOperationId 决定拉哪些 skill，agent-core 需暴露 agent 身份信息 |
| **B. RunnerConfig 增加 skill source SPI 字段** | 中 | `RunnerConfig` 新增 `Map<String, Object> skillSourceConfig`（对齐 checkpointerConfig 模式）；Runner 启动时创建 `SkillSourceProvider`；agent 构造时通过 ResourceMgr 拿到 provider 按 agent-id 拉取 skill | 对齐 RunnerConfig 现有 SPI 注入模式；runtime middleware 可像配置 checkpointer 一样配置 skill source | Runner 层需增加"把 provider 分发给 agent"的机制；skill 仍是 agent 私有但来源由 Runner 配置 |
| **C. Runner 直接管理 skill 注册** | 大 | `RunnerConfig` 新增 skill 字段；Runner 启动时统一下载/注册到所有 agent | 统一管理 | **不推荐**：违背"skill 是 agent 私有状态"的领域模型；不同 agent 需不同 skill 集合，Runner 层难以决策 |

**FEAT-005 的迁移策略**：当前通过 `handler.start()` hook 主动调用 `registerSkill` 是符合 agent-core 领域模型的方案。若未来 agent-core 采纳路径 A 或 B，FEAT-005 可把 `SkillHubInstaller` 的"主动调用 registerSkill"改为"通过 SPI 让 agent 主动拉取"，`SkillHubProvider` SPI 契约（§4.4）和 middleware 配置（§5.6）无需变更。

### 9.2 agent-id 字符串场景

当配置 `openjiuwen.service.agent-id` 为字符串时，`JiuwenCoreAgentHandler` 持有的是 agent-id 字符串，实际 agent 实例由 Core Runner 内部通过 `Runner.resourceMgr()` 注册的工厂函数（`Supplier<Object>`）创建和管理。

**创建机制**：

```
1. 外部注册阶段：
   Runner.resourceMgr().addAgent(AgentCard, Supplier<Object> agentFactory, tags)
   // agent 以 Supplier 形式注册，见 ResourceMgr.addAgent / AgentMgr.addAgent

2. 执行阶段（Core 内部）：
   Runner.runAgentStreaming("agent-id", inputs, session, null, streamModes)
   → Runner 按 agent-id 查找已注册的 AgentCard + Supplier
   → 调用 supplier.get() 创建 agent 实例（工厂模式，每次请求新建实例）
   → 执行 agent.stream(inputs, session, streamModes)
   → 返回 Iterator<Object>（输出流）

3. agent 实例获取（agent-core 已提供）：
   Runner.resourceMgr().getAgent("agent-id")
   → ResourceMgr.innerGetResourcesByProvider → AgentMgr.getAgent → AbstractManager.getResource
   → supplier.get()（同样每次调用返回新实例或缓存实例，取决于 Supplier 实现）
```

**adapter 层无法在启动期安装 skill 的根因**：

1. **agent-id 场景的 Supplier 通常是工厂模式**（如 demo 的 `SessionEchoAgent::new`），`supplier.get()` 每次返回新实例；即便 `Runner.resourceMgr().getAgent(agentId)` 已存在（agent-core 0.1.13），返回的也是 supplier 新建实例，对临时实例 `registerSkill` 不会作用于后续请求的实例
2. **`JiuwenCoreAgentHandler` 不缓存 agent 实例**：handler 的 `getAgent()` 直接返回构造时传入的 agent-id 字符串（非实例），请求时由 `Runner.runAgentStreaming(agent, ...)` 内部按 id 解析 supplier
3. **agent-id 场景注册的 agent 可能不是 `BaseAgent` 子类**（如 `SessionEchoAgent` 是普通 POJO，没有 `registerSkill` 方法），即使能拿到实例也无法注册 skill

**结论**：SkillHub 的 `registerSkill` 是 `BaseAgent` 的方法，仅适用于 agent 实例场景（Demo / 业务自建 `@Bean AgentHandler`）。agent-id 场景跳过 skill 安装，输出 warn 日志。若 agent-id 场景需要 skill 支持，需 Core 侧提供"单例 agent + 启动 hook"或"工厂注入 skill 路径"机制（超出本仓范围）。

**注**：旧版本文档曾写"`Runner.resourceMgr()` 不提供按 ID 获取 agent 实例的 API"——该描述已过时。agent-core 0.1.13 已提供 `getAgent(String agentId)`，但因其依赖 Supplier 工厂模式且 handler 不缓存实例，仍无法用于启动期 skill 安装。

### 9.3 其他限制

| 限制 | 影响范围 | 临时方案 |
|------|---------|---------|
| DeepAgent 适配代码保留 | `install(DeepAgent)` 已实现，取 inner ReActAgent 安装；当前项目无 DeepAgent 路径但兼容后演进 | 保留适配代码，按 `instanceof DeepAgent` 分支处理 |
| `SkillManager` 非线程安全（已确认） | 已核实 agent-core `SkillManager` 的 `registry`（`LinkedHashMap`）/`updateAtCache`（`LinkedHashMap`）/`skillOrder`（`ArrayList`）均非线程安全集合，`register`/`refreshIncrementally`/`clearAll` 等方法无同步保护。后台重试线程直接 `registerSkill` 会与请求线程并发污染 skill registry | 已采用降级方案：后台重试成功后不直接 `registerSkill`，而是将 path 入队 `ConcurrentLinkedQueue<String> pendingSkillPaths`；`JiuwenCoreAgentExtHandler` 在 `streamQuery`/`query` 入口调用 `retryExecutor.drainAndRegister(agent)` 由请求线程串行注册（保证单线程写）。重名时 `SkillManager.register(overwrite=false)` 抛 `IllegalStateException`，`drainAndRegister` 视为已注册并忽略 |
| `tenantId` 硬编码 `"default"` | §4.2 中 `buildSkillHubContext()` 的 `tenantId` 当前写死，无法按租户过滤 skill | 后续从 `ServiceProperties` 或配置扩展；当前不影响功能，仅影响多租户过滤精度 |
| Provider 缓存与重试策略未固定 | FEAT-005 §5.2 不在 version-scope 固定下载缓存、分页、断点续传或本地落盘策略 | 由 Provider 实现自行治理；后台重试次数/间隔由 `SkillHubMiddlewareProperties.retry` 配置 |