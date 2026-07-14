| level | L2-LLD |
|---|---|
| module | agent-service-adapters-agentcore |
| feature_type | functional |
| feature_id | Feat-Func-005 |
| status | draft |
| dependency | https://github.com/chaosxingxc-orion/spring-ai-ascend/pull/400 D:/code/agent-core-java/agent-core-java (BaseAgent / DeepAgent / SkillUseRail) |

## SkillHub 中间件适配设计文档

> 目标模块：`agent-service-adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/skillhub/`
> 需求来源：FEAT-005 智能体中间件请求代理特性文档（spring-ai-ascend PR #400，version 0715）
> 最后更新：2026-07-13

---

## 1. 概述

### 1.1 特性定位

FEAT-005 定义 `agent-runtime` 在部署或启动阶段代表 Agent 访问智能体中间件服务的当前版本事实，当前版本纳入范围的子特性是 **Skill Hub 代理**：runtime 读取部署态 runtime 配置，通过 Skill Hub SPI 访问 Skill Hub，下载 Agent 声明需要的 skill 包，并把可注册的 skill 材料交给 `agent-core` 或框架适配入口完成注册和使用。

agent-service-adapters-agentcore 以 SkillHub 适配层为入口，将运行时中性的 SkillHub SPI 接入 OpenJiuwen Agent Core 的 `BaseAgent` 生命周期。SkillHub 提供渐进式 skill 发现与加载能力：先列出轻量摘要，再按需加载完整定义，最终通过 `BaseAgent.registerSkill()` 安装到 agent 实例。

- **解决的问题**：Agent 的 skill 资产由外部 Skill Hub 管理，Agent 部署时只声明需要哪些 skill；runtime 需要在 Agent 启动前完成连接认证、访问控制、下载、诊断和移交，避免 Agent 开发者在业务代码中直接耦合 Skill Hub 服务 API 或凭据处理。
- **适用场景**：业务自建 `@Bean AgentHandler` 传入 `BaseAgent` 实例（如 `ReActAgent`）的场景。Agent 在 `start()` 阶段从 SkillHub 加载 skill 并注册到自身。
- **客户价值**：Agent 能力可以随部署环境、租户和业务场景进行配置化选择，技能目录可以由客户或平台统一治理。
- **非目标**：不是运行中动态 skill 调度能力；不是 `agent-core` 的 skill 解析、注册、执行或 prompt 组装规范；runtime 不解释 skill 包内容，不把 skill instructions 直接注入运行时 prompt，不接管框架内部 tool/skill 选择和执行，也不建立独立于 Skill Hub 的 skill 授权模型。

### 1.2 核心设计原则

1. **启动期安装** — Skill 安装在 `JiuwenCoreAgentHandler.start()` 阶段执行一次，不随每次请求重复注册
2. **稳定部署上下文** — Runtime 请求 SkillHub 时携带可稳定获得的部署态上下文（agentId、tenantId），不依赖每次请求的 user/session/task
3. **渐进式加载** — 摘要阶段不加载 instructions 或 skill 包；完整定义只在安装前按需加载
4. **Agent 自治** — `registerSkill` 已将 skill description 注入 prompt，适配层不重复注入 instructions，遵循 skill 懒加载设计原则
5. **SPI 分离** — `SkillHubProvider` 负责发现与加载（数据源无关），`SkillHubAdapterRegistrar` 负责安装到 agent 实例（框架相关）
6. **配置归属分离** — Skill Hub 服务连接（endpoint、认证方式、凭据引用）由 runtime 部署配置持有；Agent 配置不持有 Skill Hub 明文访问凭据
7. **required fail fast / optional 降级** — required skill 认证、查找、下载、校验或移交失败时阻断 Agent ready；optional skill 失败时跳过并输出脱敏降级诊断，被跳过的 skill 不得注册为可用
8. **凭据与敏感信息保护** — 密码、token、认证头、密钥、凭据密文和解密后的敏感值不得写入日志、错误响应、遥测数据或持久化明文配置
9. **可选装配** — 仅当容器中存在 `SkillHubProvider` Bean 时才激活 SkillHub 链路，不影响未使用 skill 的服务
10. **Skill 与 MCP 解耦** — SkillHub 只负责 skill 摘要、说明、依赖和包加载，不负责执行 MCP tool 或其他工具调用

### 1.3 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|--------|------|---------|------|
| Skill 数据模型 | Skill 摘要（含 required 标记）、定义、工具依赖、打包载荷 | `SkillSummary`, `SkillDefinition`, `SkillToolDependency`, `SkillPackage` | ✅ |
| 稳定部署上下文 | 携带 agentId、tenantId 等部署态信息 | `SkillHubContext` | ✅ |
| SkillHub 连接配置 | runtime 持有 endpoint、认证方式、凭据引用 | `SkillHubProperties` | ✅ |
| SkillHub SPI | 运行时中性的 skill 发现与加载接口 | `SkillHubProvider` | ✅ |
| Agent 适配注册 | 将 SkillHub skills 安装到 BaseAgent 实例 | `SkillHubAdapterRegistrar`, `DefaultSkillHubAdapterRegistrar` | ✅ |
| Core 安装器 | 读取 metadata 中的 skill path，调用 `registerSkill`；执行 required fail fast / optional 降级 | `AgentCoreSkillHubInstaller` | ✅ |
| 错误诊断与分类 | 连接/认证/不存在/下载/校验/移交失败分类诊断 | `SkillHubException`, `SkillHubErrorCategory` | ✅ |
| 自动装配 | 条件注册 SkillHub 链路 Bean | `AgentCoreAdaptersAutoConfiguration` | ✅ |
| openJiuwen 默认实现 | 对接 `openJiuwen/skillhub` 服务 API 的默认 Provider | `OpenJiuwenSkillHubProvider` | ✅ |
| Agent skill 选择配置驱动 | Agent 声明 `skills:[{id,version,required}]` 驱动获取 | — | ⬜ 第一期不做，listSkills 返回全部安装 |

---

## 2. 特性规格

### 2.1 能力清单

> FEAT-005 §2 能力要求映射：MUST = ✅；SHOULD = ✅（默认实现，可降级）；不承诺 = ⬜。

| 能力 | 状态 | 说明 |
|------|------|------|
| 部署/启动阶段代理访问 | ✅ MUST | runtime 在 Agent 部署或启动阶段读取配置并访问 Skill Hub，不在用户 query 请求过程中动态拉取 skill |
| Skill Hub 服务配置归 runtime | ✅ MUST | endpoint、认证方式、连接凭据引用和连接策略由 `SkillHubProperties` 持有；Agent 配置不持有 Skill Hub 明文访问凭据 |
| Skill Hub SPI | ✅ MUST | `SkillHubProvider` 可替换访问边界，默认实现和自定义实现可在不改变 Agent 业务代码的前提下替换 |
| openJiuwen Skill Hub 默认实现 | ✅ MUST | `OpenJiuwenSkillHubProvider` 对接 `openJiuwen/skillhub` 服务 API（`/plugins`、`/plugins/{id}/versions/{ver}`、`/artifacts/{id}`） |
| skill 包下载 | ✅ MUST | `SkillHubProvider.loadSkillPackage(ctx, skillId)` 返回 `SkillPackage`（zip 包含 `SKILL.md` + 引用文件） |
| 注册材料移交 | ✅ MUST | 下载且通过基本校验的 skill 材料移交给 `agent-core` 的 `BaseAgent.registerSkill(path)`；注册、解析、执行归属 `agent-core` |
| required skill fail fast | ✅ MUST | required skill 认证/查找/下载/校验/移交失败时抛 `SkillHubException`，阻断 `Runner.start()`，Agent 不 ready |
| optional skill 降级 | ✅ SHOULD | optional skill 失败时跳过该 skill 并继续启动，输出脱敏降级诊断；被跳过的 skill 不得注册为可用 |
| 凭据与敏感信息保护 | ✅ MUST | 凭据密文、解密后敏感值不写入日志、错误响应、遥测数据或持久化明文配置（详见 §4.9） |
| 错误诊断 | ✅ MUST | 连接/认证/拒绝访问/不存在/下载/校验/移交失败时通过 `SkillHubErrorCategory` 输出明确且不泄露敏感信息的诊断 |
| Skill 摘要列表 | ✅ | `SkillHubProvider.listSkills(context)` 返回 `List<SkillSummary>`，含 `required` 字段；Provider 可按部署态上下文过滤 |
| Skill 完整定义加载 | ✅ | `SkillHubProvider.loadSkill(context, skillId)` 返回 `SkillDefinition`，按需加载 |
| 稳定部署上下文透传 | ✅ | `SkillHubContext` 携带 agentId、tenantId 等部署态信息，不依赖请求级 user/session/task |
| Skill path 注册 | ✅ | 从 `SkillDefinition.metadata()` 读取 `openjiuwen.skill.path` / `openjiuwen.skill.paths`，调用 `BaseAgent.registerSkill(path)` |
| 安装日志与诊断 | ✅ | 日志输出 tenantId、agentId、summary 数量、loaded 数量、installed 数量、skipped 数量、skip reason 与错误分类 |
| 无 Provider 降级 | ✅ | 未配置 SkillHubProvider 时 Agent 正常启动和执行 |
| DeepAgent skill 安装 | ✅ | 保留 `install(DeepAgent)` 适配代码，取 inner ReActAgent 安装；当前项目无 DeepAgent 路径但兼容后演进 |
| 渐进式加载 | ✅ | 摘要阶段不加载 instructions；完整定义只在安装前按需加载 |
| 完整 instructions 不注入 prompt | ✅ | `registerSkill` 已注入 description，不重复注入 instructions |
| 请求级动态 skill 过滤 | ⬜ | 当前版本不要求基于每次请求的 user/session/task 动态变更 skill 集合 |
| agent-id 场景 skill 安装 | ⬜ | adapter 层无法获取 agent 实例（详见 §8.1） |
| 动态热更新 | ⬜ | 当前版本不要求运行中热更新 skill 列表 |
| Agent skill 选择配置驱动 | ⬜ | 第一期不做 Agent 声明 `skills:[{id,version,required}]` 驱动；`listSkills` 返回的全部 skill 按其 `required` 字段决定 fail fast / 降级行为 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|--------|------|------|
| 请求级 skill 安装 | `registerSkill` 是累积注册，每次请求重复安装会导致重复；FEAT-005 明确不在用户 query 请求过程中动态拉取 skill | 启动期 `start()` 安装一次 |
| `injectRuntimeSkillSection` prompt 注入 | FEAT-005 §5.1.4 明确：runtime 不把 skill instructions 注入运行时 prompt；`registerSkill` 已注入 description | 依赖 Agent 自身的 skill prompt 机制 |
| 请求级上下文（user/session/task） | FEAT-005 §5.2 明确不承诺请求级动态获取 | 使用稳定部署上下文（agentId、tenantId） |
| agent-id 字符串场景 | adapter 层无法获取 agent 实例，且注册的 agent 可能不是 `BaseAgent` | 仅支持 `BaseAgent` 实例场景（详见 §8.1） |
| Agent skill 选择配置驱动（第一期） | 第一期保持 `listSkills(ctx)` 返回全部安装的模型；Agent 声明 `skills:[{id,version,required}]` 驱动推迟到后续版本 | 每个 `SkillSummary` 携带 `required` 字段，由 Provider 实现按部署上下文决定 |
| 运行中热刷新 | FEAT-005 §5.2 明确不承诺运行中自动刷新、热替换、卸载或按策略切换 skill | 配置变更通过重新部署或重启生效 |
| Agent 自主决策获取 | FEAT-005 §5.2 不承诺 Agent 在推理过程中自主决定从 Skill Hub 获取新 skill | 启动期一次性安装 |
| 独立 skill 授权模型 | FEAT-005 §5.1.1 明确：授权由 Skill Hub 根据 runtime 凭据判定，runtime 不维护独立 Agent-skill 授权规则 | 通过 `SkillHubContext` 的 agentId/tenantId 让 Skill Hub 侧判定 |
| Skill Hub 服务端能力 | FEAT-005 §5.2 不定义 Skill Hub 服务端的管理、审批、运营、存储、审计或发布流程 | 通过 Provider SPI 对接外部 Skill Hub |
| `agent-core` skill 语义 | FEAT-005 §5.2 不定义 `agent-core` 的 skill 格式、解析、注册、执行、prompt 注入、渐进加载或模型上下文处理策略 | 由 `agent-core` 框架自身处理 |
| MCP tool 执行 | SkillHub 不负责调用 MCP tool | 工具调用由 MCP Provider 或框架 tool 机制处理 |
| 缓存与重试策略固定 | FEAT-005 §5.2 不在 version-scope 固定下载缓存、重试、分页、断点续传或本地落盘策略 | 由 Provider 实现自行治理 |

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

**SPI 异常约定**：Provider 实现应将连接失败、认证失败（401/403）、skill 不存在（404）、下载失败、校验失败等分类为 `SkillHubException(category, skillId, message)` 抛出，`category` 取自 `SkillHubErrorCategory`（详见 §4.8）。未分类的其他异常由 installer 兜底为 `UNKNOWN`。

#### SkillHubAdapterRegistrar SPI

```java
/** 将 SkillHub skills 安装到 agent 实例的适配注册接口。 */
public interface SkillHubAdapterRegistrar {
    /**
     * 在 Handler start() 阶段安装 skills 到 agent 实例。
     *
     * 失败语义：
     *   - 任意 required skill 的认证/查找/下载/校验/移交失败 → 抛 SkillHubException
     *     调用方（JiuwenCoreAgentHandler.start）据此阻断 Runner.start()，Agent 不 ready
     *   - optional skill 失败 → 内部捕获并记录降级诊断，不抛异常，被跳过的 skill 不得注册为可用
     */
    void installToAgent(Object agent, SkillHubContext context);

    /** 无操作 registrar。 */
    static SkillHubAdapterRegistrar noop() {
        return (agent, context) -> { };
    }
}
```

#### 行为承诺

- **必须**：`installToAgent` 仅在 `agent instanceof DeepAgent` 或 `agent instanceof BaseAgent` 时执行安装
- **必须**：skill path 从 `SkillDefinition.metadata()` 的 `openjiuwen.skill.path`（单个 String）或 `openjiuwen.skill.paths`（Iterable）读取
- **必须**：安装后通过 `skillCount` 前后对比验证注册是否生效，未生效时按 required/optional 语义处理（required 抛异常，optional warn 并跳过）
- **必须**：`SkillHubProvider.listSkills(context)` 返回 null 时按空列表处理，不抛异常
- **必须**：日志输出 tenantId、agentId、summary 数量、loaded 数量、installed 数量、skipped 数量、skip reason 与错误分类
- **必须**：required skill 失败时 `installToAgent` 抛 `SkillHubException`，`JiuwenCoreAgentHandler.start()` 据此不调用 `Runner.start()` 并复位 `RUNNER_STARTED`
- **必须**：optional skill 失败时跳过并输出脱敏降级诊断，该 skill 不得被注册为可用
- **必须**：日志、错误响应、遥测数据不得输出 API key、token、认证头、凭据密文、解密后敏感值、内部敏感地址或 skill 包中的敏感内容
- **必须**：未装配 SkillHubProvider 时不影响 Agent 启动和请求执行
- **必须**：`OpenJiuwenSkillHubProvider` 作为默认实现，对接 `openJiuwen/skillhub` 服务 API；业务方可通过自定义 `SkillHubProvider` Bean 覆盖
- **禁止**：在每次请求时重复调用 `installToAgent`
- **禁止**：向 prompt builder 注入 skill instructions（由 Agent 自身 skill 机制处理）
- **禁止**：在摘要阶段加载大量 instructions 或 skill 包
- **禁止**：将凭据明文写入持久化配置、日志或错误响应
- **允许**：agent 非 `BaseAgent`/`DeepAgent` 时跳过安装并输出 warn 日志
- **允许**：Provider 自行缓存摘要、定义或远端响应

---

## 3. 模块结构

### 3.1 包结构

```
adapters-agentcore/src/main/java/com/openjiuwen/service/adapters/agentcore/
├── middleware/          # 已有：checkpointer → RunnerConfig
├── external/            # 已有：MCP/Remote/Sandbox → Core SPI
├── agentfw/             # 已有：JiuwenCoreAgentHandler
├── autoconfigure/       # 已有：自动装配
└── skillhub/            # 新增
    ├── SkillHubContext.java               # 稳定部署上下文
    ├── SkillHubProvider.java              # 运行时中性 SPI
    ├── SkillHubAdapterRegistrar.java      # 适配注册 SPI
    ├── DefaultSkillHubAdapterRegistrar.java
    ├── AgentCoreSkillHubInstaller.java    # Core 专属安装器（required fail fast / optional 降级）
    ├── SkillSummary.java                  # record（含 required 字段）
    ├── SkillDefinition.java               # record
    ├── SkillToolDependency.java           # record
    ├── SkillPackage.java                  # record
    ├── SkillHubException.java             # 分类异常（阻断 Runner.start）
    ├── SkillHubErrorCategory.java         # 错误分类枚举
    ├── SkillHubProperties.java            # @ConfigurationProperties：runtime 连接配置
    ├── OpenJiuwenSkillHubProvider.java    # 默认实现：对接 openJiuwen/skillhub API
    └── package-info.java
```

### 3.2 核心类静态关系

```
«autoconfigure»              «registrar»                     «installer»
AgentCoreAdapters        →  DefaultSkillHub           →   AgentCoreSkillHub
AutoConfiguration            AdapterRegistrar              Installer
  │                           │                              │
  │ @ConditionalOnMissingBean │ installToAgent(agent,        │ install(baseAgent, ctx)
  │ SkillHubProvider          │   context)                   │ install(deepAgent, ctx)
  │  默认 = OpenJiuwen        │                              │
  │  SkillHubProvider         │                              │ required 失败 → throw
  ▼                           ▼                              │ optional 失败 → warn + skip
SkillHubProvider         instanceof DeepAgent            ▼
(OpenJiuwen 默认          ? → install(deepAgent, ctx)  SkillHubProvider
 或 业务 @Bean 覆盖)      instanceof BaseAgent          .listSkills(ctx)
                           ? → install(baseAgent, ctx)   .loadSkill(ctx, skillId)
                           : → skip (warn)                    │
                                                              ▼
                                                         BaseAgent.registerSkill(path)

«agentfw»
JiuwenCoreAgentHandler
  │
  ├─ start()
  │    ├─ middlewareAdapterRegistrar.applyToRunnerConfig(...)
  │    ├─ SkillHubContext ctx = new SkillHubContext(agentId, tenantId)
  │    ├─ try { skillHubAdapterRegistrar.installToAgent(agent, ctx) }
  │    │     catch (SkillHubException) → 复位 RUNNER_STARTED, 不调用 Runner.start()
  │    └─ Runner.start()   ← 仅当 installToAgent 未抛异常时执行
  │
  └─ streamQuery / query  ← 无 skill 安装

«config»
SkillHubProperties  ← @ConfigurationProperties("openjiuwen.skillhub")
  ├─ endpoint
  ├─ authType (system-token | bearer)
  ├─ credentialRef   ← 引用外部 secret，不存明文
  └─ provider (openjiuwen | custom)
```

---

## 4. 核心设计

### 4.1 Skill 安装时序

```
应用启动
  │
  ▼
InitPhaseExecutor.run()
  │
  ├─ AgentHandler.start()
  │     │
  │     ├─ middlewareAdapterRegistrar.applyToRunnerConfig(RunnerConfig)
  │     │
  │     ├─ SkillHubContext ctx = new SkillHubContext(agentId, tenantId)   ← 构建稳定部署上下文
  │     │
  │     ├─ try { skillHubAdapterRegistrar.installToAgent(agent, ctx) }    ← 新增
  │     │     │   catch (SkillHubException) → 复位 RUNNER_STARTED, 不进入 Runner.start()
  │     │     │
  │     │     ├─ agent instanceof DeepAgent ?
  │     │     │   ├─ yes → AgentCoreSkillHubInstaller.install(deepAgent, ctx)
  │     │     │   │         ├─ innerAgent = deepAgent.getAgent()
  │     │     │   │         ├─ innerAgent.getSkillUtil() == null ? → warn, skip
  │     │     │   │         └─ install(innerAgent, innerAgent::registerSkill, ctx)
  │     │     │   │
  │     │     │   ├─ agent instanceof BaseAgent ?
  │     │     │   │   ├─ yes → AgentCoreSkillHubInstaller.install(baseAgent, ctx)
  │     │     │   │   │         ├─ SkillHubProvider.listSkills(ctx) → List<SkillSummary>
  │     │     │   │   │         ├─ for each summary:
  │     │     │   │   │         │   try {
  │     │     │   │   │         │     loadSkill(ctx, skillId) → SkillDefinition
  │     │     │   │   │         │     metadata.get("openjiuwen.skill.path") → paths
  │     │     │   │   │         │     for each path: baseAgent.registerSkill(path)
  │     │     │   │   │         │     skillCount before/after 对比 → log
  │     │     │   │   │         │   } catch (SkillHubException ex) {
  │     │     │   │   │         │     summary.required()
  │     │     │   │   │         │       ? rethrow                                    ← fail fast
  │     │     │   │   │         │       : warn + skip + 降级诊断                     ← optional 降级
  │     │     │   │   │         │   }
  │     │     │   │   │
  │     │     │   │   └─ no → warn log, skip
  │     │     │
  │     └─ Runner.start()   ← 仅当 installToAgent 未抛异常时执行
  │
  ▼
请求处理（streamQuery / query）
  │
  └─ Runner.runAgentStreaming(agent, ...)   ← 无 skill 安装
```

**关键语义**：
- `installToAgent` 对每个 skill 独立 try/catch
- required skill 任一失败 → 抛 `SkillHubException` 向上传播 → `start()` 捕获后复位 `RUNNER_STARTED`，不调用 `Runner.start()`，Agent 不 ready
- optional skill 失败 → 内部捕获，输出降级诊断，该 skill 不注册为可用，继续处理下一个 skill
- `Runner.start()` 仅在 `installToAgent` 正常返回时执行

### 4.2 稳定部署上下文

`SkillHubContext` 携带部署态可稳定获得的信息，不依赖请求级 user/session/task：

| 字段 | 来源 | 默认值 | 用途 |
|---|---|---|---|
| `agentId` | `ServiceProperties.agentId` 或 agent 实例的 `getCard().getId()` | `""` | Provider 按 agent 过滤 skill |
| `tenantId` | `ServiceProperties` 或配置 | `"default"` | Provider 按租户过滤 skill |

构建时机：`JiuwenCoreAgentHandler.start()` 阶段，从 Spring 容器注入的配置或 agent 实例中提取。

**与请求上下文的区别**：需求文档明确，当前版本不要求 Provider 基于每次请求的 user/session/task 动态变更 skill 集合。`SkillHubContext` 是部署态稳定的，不会随请求变化。如业务确需请求级权限过滤，应由网关、业务 Provider 预筛选或独立 Agent/Handler 实例承接。

### 4.3 Skill path 解析规则

`SkillDefinition.metadata()` 中的 skill path 通过两个 key 声明：

| Metadata Key | 类型 | 说明 |
|---|---|---|
| `openjiuwen.skill.path` | `String` | 单个 skill 路径（如 `"skills/hotel"`） |
| `openjiuwen.skill.paths` | `Iterable<?>` 或 `String` | 多个 skill 路径 |

解析顺序：先取 `openjiuwen.skill.path`，再取 `openjiuwen.skill.paths`。每个候选值仅当为非空白 `String` 时才加入路径列表。

### 4.4 安装结果验证

`BaseAgent` 的 skill 计数通过 `agent.getSkillUtil().getSkillManager().count()` 获取。安装前后计数对比：

| before | after | 判定 | 日志级别 |
|---|---|---|---|
| -1 | -1 | `SkillUtil` 或 `SkillManager` 为 null | warn（skill 运行时未初始化） |
| N | N | 计数未增长 | warn（SKILL.md 可能缺少 YAML frontmatter） |
| N | N+1 | 计数增长 | info（安装成功） |

### 4.5 自动装配条件

```
AgentCoreAdaptersAutoConfiguration
  │
  ├─ @Bean SkillHubAdapterRegistrar
  │   @ConditionalOnBean(SkillHubProvider.class)        ← 容器中有 Provider 才激活
  │   @ConditionalOnMissingBean(SkillHubAdapterRegistrar.class)
  │   → new DefaultSkillHubAdapterRegistrar(provider)
  │
  └─ @Bean AgentHandler (coreAgentHandler)
      └─ @Autowired(required = false) SkillHubAdapterRegistrar
          → 有则注入，无则 SkillHubAdapterRegistrar.noop()
```

无 `SkillHubProvider` Bean 时，整条 SkillHub 链路不激活，`JiuwenCoreAgentHandler` 使用 noop registrar，行为与无 SkillHub 时完全一致。

### 4.6 安装顺序与冲突语义

SkillHub installer 与 MCP installer、远程 Agent tool installer 在同一稳定安装点执行时，顺序和冲突语义必须可诊断。当前项目中：
- middleware 注册（checkpointer）→ SkillHub 安装 → external 注册（MCP/Remote/Sandbox）→ `Runner.start()`
- **required skill 安装失败阻断后续流程**：抛 `SkillHubException` 后 `start()` 复位 `RUNNER_STARTED`，**不执行** external 注册，**不调用** `Runner.start()`，Agent 不 ready
- **optional skill 失败不阻断**：warn + skip 后继续处理下一个 skill；optional 全部失败后仍正常进入 external 注册和 `Runner.start()`
- external 注册（MCP/Remote/Sandbox）与 SkillHub 相互独立，external 失败的语义由 external 自身决定，不被 SkillHub 失败影响

### 4.7 required / optional 行为矩阵

| 字段 | 来源 | 默认值 | 用途 |
|---|---|---|---|
| `SkillSummary.required` | Provider 实现按部署上下文决定（如 metadata、远端权限、配置） | `false`（optional） | installer 据此决定 fail fast / 降级 |

**失败行为矩阵**：

| skill 类型 | 失败阶段 | 行为 | Agent ready |
|---|---|---|---|
| required | listSkills | 抛 `SkillHubException(CONNECT_FAILED)` | ✗ 阻断 |
| required | loadSkill | 抛 `SkillHubException(NOT_FOUND)` | ✗ 阻断 |
| required | loadSkillPackage | 抛 `SkillHubException(DOWNLOAD_FAILED)` | ✗ 阻断 |
| required | registerSkill（skillCount 未增长） | 抛 `SkillHubException(INSTALL_FAILED)` | ✗ 阻断 |
| optional | 任一阶段 | warn + skip + 降级诊断，该 skill 不注册 | ✓ 继续 |
| 无 Provider | — | noop，正常启动 | ✓ |

**降级诊断要求**：optional skill 跳过时日志必须包含 tenantId、agentId、skillId、required=false、failureCategory、failureReason（脱敏，不含凭据或敏感内容）。

### 4.8 错误诊断与分类

`SkillHubErrorCategory` 枚举覆盖 FEAT-005 §5.1.5 的失败分类：

| Category | 触发条件 | 阻断 required | 阻断 optional |
|---|---|---|---|
| `CONNECT_FAILED` | endpoint 缺失或不可达 | ✓ | ✗（降级） |
| `AUTH_FAILED` | 凭据缺失/无效/过期/401/403 | ✓ | ✗（降级） |
| `ACCESS_DENIED` | Skill Hub 拒绝访问 | ✓ | ✗（降级） |
| `NOT_FOUND` | skill 不存在或无权访问 | ✓ | ✗（降级） |
| `DOWNLOAD_FAILED` | 下载中断、包损坏 | ✓ | ✗（降级） |
| `CHECKSUM_MISMATCH` | 校验失败 | ✓ | ✗（降级） |
| `INSTALL_FAILED` | registerSkill 后 skillCount 未增长 | ✓ | ✗（降级） |
| `UNSUPPORTED` | loadSkillPackage 不支持且 skill 需要 package | ✓ | ✗（降级） |
| `UNKNOWN` | 未分类异常兜底 | ✓ | ✗（降级） |

`SkillHubException` 字段：
```java
public class SkillHubException extends RuntimeException {
    private final SkillHubErrorCategory category;
    private final String skillId;          // 可空（如 listSkills 阶段）
    private final String sanitizedMessage; // 脱敏后的诊断信息
}
```

**诊断输出要求**：
- 日志可输出 adapter 名称、endpoint 摘要（不含路径后的 query/认证信息）、skill id、required/optional、failureCategory、correlation 信息
- 日志不得输出明文凭据、密文、认证头、密钥、内部敏感地址或敏感 skill 内容
- 错误响应对外暴露时只返回分类和脱敏摘要，不暴露内部地址或凭据

### 4.9 凭据与敏感信息保护

依据 FEAT-005 §2 和 §5.1.5：

| 项 | 要求 |
|---|---|
| 凭据存储 | `SkillHubProperties.credentialRef` 只持有外部 secret 引用，不存明文 |
| 日志 | 不得输出 token、API key、认证头、凭据密文、解密后敏感值、内部敏感地址、skill 包敏感内容 |
| 错误响应 | 对外只返回 `SkillHubErrorCategory` 和脱敏摘要，不暴露 endpoint 完整路径、凭据片段或内部堆栈 |
| 遥测数据 | 同日志要求；如需 metric，只输出计数和分类，不输出值内容 |
| 持久化配置 | 凭据明文不得写入 application.yml、properties 或其他明文配置文件 |
| 异常堆栈 | `SkillHubException.sanitizedMessage` 已脱敏；原始异常的 stacktrace 不输出到响应 |

**`OpenJiuwenSkillHubProvider` 凭据处理**：
- 从 `credentialRef` 指向的外部 secret 读取 token
- 注入 HTTP 头 `Authorization: Bearer <token>` 或 `X-System-Token: <token>`
- token 不进入日志、异常消息或 metric 标签

---

## 5. 数据模型

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
        boolean required,         // 是否启动必需：true 时失败阻断 Runner.start()；false 时降级跳过
        List<String> tags,        // 可选，null → List.of()，不可变副本
        Map<String, Object> metadata  // 可选，null → Map.of()，不可变副本
) { }
```

`required` 字段由 Provider 实现按部署上下文决定（如来自 Skill Hub 返回的权限标记、metadata 或部署配置）。installer 据此执行 §4.7 的 fail fast / 降级语义。

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
        Map<String, Object> metadata    // 可选，不可变副本
) {
    @Override
    public byte[] content() {           // 返回防御性拷贝
        return Arrays.copyOf(content, content.length);
    }
}
```

### 5.6 SkillHubProperties（runtime 连接配置）

```java
@ConfigurationProperties("openjiuwen.skillhub")
public record SkillHubProperties(
        boolean enabled,                // 默认 false；true 时激活 SkillHub 链路
        String endpoint,                // Skill Hub 服务地址，如 https://skillhub.example.internal
        String authType,                // system-token | bearer；默认 system-token
        String credentialRef,           // 外部 secret 引用（不存明文），如 vault://skillhub/token
        String provider,                // openjiuwen | custom；默认 openjiuwen
        Map<String, String> providerProps  // Provider 自定义参数（如 timeout、page-size）
) { }
```

**配置归属说明**（依据 FEAT-005 §5.1.1）：
- `endpoint`、`authType`、`credentialRef` 由 runtime 部署配置持有，Agent 配置不持有 Skill Hub 明文凭据
- `credentialRef` 只引用外部 secret（如 Vault、K8s Secret、配置中心加密值），由 Provider 实现负责解析为实际 token
- `provider=openjiuwen` 时自动使用 `OpenJiuwenSkillHubProvider`；`provider=custom` 时由业务方提供 `@Bean SkillHubProvider` 覆盖

**配置示例**：

```yaml
openjiuwen:
  skillhub:
    enabled: true
    endpoint: https://swarmskills.openjiuwen.com
    auth-type: system-token
    credential-ref: vault://secret/skillhub/system-token
    provider: openjiuwen
    provider-props:
      page-size: "50"
      connect-timeout-ms: "5000"
```

---

## 6. JiuwenCoreAgentHandler 改动

### 6.1 新增字段和构造器

```java
public class JiuwenCoreAgentHandler implements AgentHandler {
    private final SkillHubAdapterRegistrar skillHubAdapterRegistrar;  // 新增

    // 新增全参构造器
    public JiuwenCoreAgentHandler(Object agent,
                                  MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
                                  ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar,
                                  SkillHubAdapterRegistrar skillHubAdapterRegistrar) { ... }

    // 保留现有 3 参构造器，skillHub 默认 noop
    public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
        ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        this(agent, middlewareAdapterRegistrar, externalSvcAdapterRegistrar, SkillHubAdapterRegistrar.noop());
    }

    // 保留现有 2 参和 1 参构造器，链式调用
}
```

### 6.2 start() 改动

```java
@Override
public void start() {
    if (!RUNNER_STARTED.compareAndSet(false, true)) {
        return;
    }
    if (middlewareAdapterRegistrar != null) {
        middlewareAdapterRegistrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
    }
    // 新增：构建稳定部署上下文并安装 skill
    SkillHubContext context = buildSkillHubContext();
    try {
        skillHubAdapterRegistrar.installToAgent(agent, context);
    } catch (SkillHubException ex) {
        // required skill 失败时阻断 Agent ready
        RUNNER_STARTED.set(false);
        log.error("SkillHub install failed, agent not ready tenantId={} agentId={} category={} skillId={} reason={}",
                context.tenantId(), context.agentId(),
                ex.getCategory(), ex.getSkillId(), ex.getSanitizedMessage());
        throw ex;
    }
    log.info("Starting AgentCore Runner");
    try {
        externalSvcAdapterRegistrar.registerToRunner();
        Runner.start();
    } catch (RuntimeException | Error ex) {
        RUNNER_STARTED.set(false);
        throw ex;
    }
}

private SkillHubContext buildSkillHubContext() {
    String agentId = resolveAgentId(agent);
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

`streamQuery` / `query` 方法不变——skill 安装不在此层处理。

**fail fast 语义说明**：
- `installToAgent` 抛 `SkillHubException` 时，`start()` 复位 `RUNNER_STARTED`，**不调用** `externalSvcAdapterRegistrar.registerToRunner()` 和 `Runner.start()`
- 日志输出 category、skillId、sanitizedMessage，不输出凭据或敏感内容
- 异常向上传播，由 `InitPhaseExecutor` 决定是否阻断应用启动

---

## 7. 对外呈现 / 用户场景

### 7.1 业务接入方式

**方式 A：使用默认 `OpenJiuwenSkillHubProvider`（推荐）**

配置 `openjiuwen.skillhub.enabled=true` 并指定 endpoint/credential-ref，自动激活默认 Provider，对接 `openJiuwen/skillhub` 服务 API：

```yaml
openjiuwen:
  skillhub:
    enabled: true
    endpoint: https://swarmskills.openjiuwen.com
    auth-type: system-token
    credential-ref: vault://secret/skillhub/system-token
    provider: openjiuwen
```

`AgentCoreAdaptersAutoConfiguration` 检测到 `SkillHubProperties.enabled=true` 且 `provider=openjiuwen` 时，自动创建 `OpenJiuwenSkillHubProvider` Bean。

**方式 B：自定义 Provider 覆盖**

业务方提供 `@Bean SkillHubProvider`，自动覆盖默认实现（`@ConditionalOnMissingBean`）：

```java
@Bean
SkillHubProvider skillHubProvider() {
    return new MySkillHubProvider();  // 从数据库 / 配置中心 / 文件系统加载
}
```

### 7.2 Skill 定义 metadata 约定

SkillHubProvider 实现返回的 `SkillDefinition` 需在 `metadata` 中声明 OpenJiuwen skill path：

```java
new SkillDefinition(
    "hotel",
    "Hotel Booking",
    "Hotel booking skill",
    "Use hotel booking workflow.",
    List.of(),
    List.of(),
    Map.of("openjiuwen.skill.path", "skills/hotel")
    // 或多路径: Map.of("openjiuwen.skill.paths", List.of("skills/hotel", "skills/common"))
);
```

### 7.3 日志输出示例

**正常安装（含 required/optional 标记）**：

```
INFO  installed skill tenantId=default agentId=demo-react-agent skillId=hotel required=true path=skills/hotel
INFO  installed skill tenantId=default agentId=demo-react-agent skillId=chart-render required=false path=skills/chart
INFO  skillhub install finished tenantId=default agentId=demo-react-agent summaries=2 loaded=2 installed=2 skipped=0
```

**optional skill 降级**：

```
WARN  skill skipped tenantId=default agentId=demo-react-agent skillId=chart-render required=false category=DOWNLOAD_FAILED reason=checksum mismatch, sanitized
INFO  skillhub install finished tenantId=default agentId=demo-react-agent summaries=2 loaded=1 installed=1 skipped=1
```

**required skill fail fast（阻断启动）**：

```
ERROR skillhub install failed tenantId=default agentId=demo-react-agent skillId=hotel required=true category=NOT_FOUND reason=skill not found in SkillHub
ERROR agent not ready: SkillHub install failure category=NOT_FOUND skillId=hotel
```

### 7.4 E2E 流程

```
应用启动
  │
  ├─ Spring 容器初始化
  │   ├─ SkillHubProperties 加载（enabled=true, endpoint, credential-ref）
  │   └─ @ConditionalOnMissingBean → 创建 OpenJiuwenSkillHubProvider Bean
  │       （或业务方 @Bean SkillHubProvider 覆盖）
  │
  ├─ AgentCoreAdaptersAutoConfiguration
  │   └─ 检测到 SkillHubProvider → 创建 DefaultSkillHubAdapterRegistrar
  │
  ├─ JiuwenCoreAgentHandler Bean 创建
  │   └─ 注入 SkillHubAdapterRegistrar
  │
  ├─ InitPhaseExecutor.run()
  │   └─ handler.start()
  │       ├─ SkillHubContext ctx = new SkillHubContext(agentId, tenantId)
  │       ├─ try { skillHubAdapterRegistrar.installToAgent(agent, ctx) }
  │       │     ├─ listSkills(ctx) → [SkillSummary(required=true), SkillSummary(required=false)]
  │       │     ├─ for each summary:
  │       │     │   try { loadSkill → registerSkill }
  │       │     │   catch (SkillHubException):
  │       │     │     required → rethrow → 阻断 Runner.start()
  │       │     │     optional → warn + skip
  │       │     └─ 正常返回 → 继续
  │       └─ Runner.start()   ← 仅当未抛异常时执行
  │
  ▼
请求处理
  └─ Runner.runAgentStreaming(agent, ...)
      └─ Agent 使用已注册的 skills 执行 ReAct 循环
```

### 7.5 无 Provider 降级

未配置 SkillHubProvider（且 `SkillHubProperties.enabled=false` 或未配置）时：
- `AgentCoreAdaptersAutoConfiguration` 不创建 `SkillHubAdapterRegistrar` bean
- `JiuwenCoreAgentHandler` 使用 `SkillHubAdapterRegistrar.noop()`
- `start()` 中 `installToAgent` 无操作
- Agent 按原有配置正常启动和执行

### 7.6 `OpenJiuwenSkillHubProvider` API 映射

默认实现对接 `openJiuwen/skillhub` 服务 API（依据 [TeamSkillsHub 接口参考](file:///C:/Users/dml/AppData/Local/Temp/skillhub/docs/zh/接口文档/v1/TeamSkillsHub-接口参考.md)）：

| SPI 方法 | HTTP API | 说明 |
|---|---|---|
| `listSkills(ctx)` | `GET /api/v1/plugins?plugin_type=skill` | 返回已通过审核的 skill 列表；`asset_id`→`skillId`，`name`/`display_name`→`name`，`moderation_status=APPROVED` 过滤 |
| `loadSkill(ctx, skillId)` | `GET /api/v1/plugins/{id}/versions/{ver}` + `GET /api/v1/plugins/{id}/versions/{ver}/files?with_content=SKILL.md` | 版本元数据 + SKILL.md 内容 → `instructions` |
| `loadSkillPackage(ctx, skillId)` | `GET /api/v1/artifacts/{id}?version={ver}` | 预签名 URL 下载 zip → `SkillPackage.content`（byte[]），`checksum_sha256`→metadata |

**认证**：
- `authType=system-token` → HTTP 头 `X-System-Token: <token>`（服务端集成，运维发放的静态 token）
- `authType=bearer` → HTTP 头 `Authorization: Bearer <token>`（通过外部 OAuth 流程获取后注入的 access_token）
- 两种方式的 token 均从 `credentialRef` 指向的外部 secret 读取，不进入日志
- runtime 只负责"使用已注入的 token"，不执行 OAuth 浏览器交互流程

**版本选择**：默认取 `latest_version`；若需要固定版本，通过 `SkillSummary.metadata().get("version")` 声明。

**required 字段来源**：`OpenJiuwenSkillHubProvider` 默认按 `metadata.get("required")` 或 Skill Hub 返回的 `plugin_type`/标签推断；业务方可通过 `providerProps` 自定义规则。

### 7.7 场景覆盖（FEAT-005 §4）

| 场景 | 期望行为 |
|---|---|
| 启动时获取 required skill | runtime 通过 SkillHub SPI 认证访问，下载 skill 包并移交注册；Agent ready |
| required skill 不存在 | 启动失败；诊断说明 skill 不可用但不泄露凭据、内部地址或敏感内容 |
| optional skill 下载失败 | runtime 跳过该 optional skill，输出脱敏降级诊断；Agent 可继续 ready，但该 skill 不注册 |
| 凭据缺失或无效 | required skill 场景 fail fast；optional skill 场景按降级规则处理；日志和错误不输出凭据 |
| 替换 Skill Hub 实现 | 业务方提供自定义 `@Bean SkillHubProvider` 覆盖默认实现；Agent 业务代码不需修改 |

---

## 8. 限制与待补

### 8.1 agent-id 字符串场景

当配置 `openjiuwen.service.agent-id` 为字符串时，`JiuwenCoreAgentHandler` 持有的是 agent-id 字符串，实际 agent 实例由 Core Runner 内部通过 `Runner.resourceMgr()` 注册的工厂函数创建和管理。

**创建机制**：

```
1. 外部注册阶段：
   Runner.resourceMgr().addAgent(AgentCard, AgentFactory, tags)
   // 例如: .addAgent(card, SessionEchoAgent::new, null)

2. 执行阶段（Core 内部）：
   Runner.runAgentStreaming("agent-id", inputs, session, null, streamModes)
   → Core 按 agent-id 查找已注册的 AgentCard + 工厂
   → 调用工厂函数创建 agent 实例
   → 执行 agent.stream(inputs, session, streamModes)
   → 返回 Iterator<Object>（输出流）
```

**adapter 层无法获取 agent 实例的原因**：

1. `Runner.resourceMgr()` 只提供 `addAgent` / `removeAgent` 等**注册**操作，不提供按 ID **获取** agent 实例的 API
2. agent 实例在 `Runner.runAgentStreaming` 内部创建，返回的是 `Iterator<Object>`（输出流），不是 agent 实例
3. agent-id 场景注册的 agent 可能不是 `BaseAgent` 子类（如 `SessionEchoAgent` 是普通 POJO，没有 `registerSkill` 方法）

**结论**：SkillHub 的 `registerSkill` 是 `BaseAgent` 的方法，仅适用于 agent 实例场景（Demo / 业务自建 `@Bean AgentHandler`）。agent-id 场景跳过 skill 安装，输出 warn 日志。若 agent-id 场景需要 skill 支持，需 Core 侧提供 hook（超出本仓范围）。

### 8.2 其他限制

| 限制 | 影响范围 | 临时方案 |
|------|---------|---------|
| DeepAgent 适配代码保留 | `install(DeepAgent)` 已实现，取 inner ReActAgent 安装；当前项目无 DeepAgent 路径，代码保留以便后续演进 | 无需临时方案 |
| 不支持请求级 skill 过滤 | FEAT-005 §5.2 明确不承诺请求级动态获取 | Provider 基于 `SkillHubContext` 的 agentId/tenantId 过滤；请求级过滤由网关或业务 Provider 预筛选 |
| 不支持 prompt section 注入 | FEAT-005 §5.1.4 明确 runtime 不把 skill instructions 注入运行时 prompt | 依赖 `registerSkill` 自身的 description 注入 |
| 不支持动态热更新 | FEAT-005 §5.2 明确不承诺运行中自动刷新、热替换、卸载或按策略切换 skill | 配置变更通过重新部署或重启生效；Provider 内部缓存由 Provider 自行治理 |
| Agent skill 选择配置驱动（第一期不做） | 第一期保持 `listSkills(ctx)` 返回全部安装；不引入 Agent 声明 `skills:[{id,version,required}]` 驱动 | 每个 `SkillSummary` 携带 `required` 字段，由 Provider 实现按部署上下文决定 |
| 部署态 Agent 配置读取 | 属于独立特性（FEAT-005 部署态配置），当前项目 agent 由 `@Bean` 提供 | 后续按独立特性实现 |
| 缓存/重试/分页策略 | FEAT-005 §5.2 不在 version-scope 固定 | 由 Provider 实现自行治理（如 `OpenJiuwenSkillHubProvider` 内部 HTTP 客户端配置） |
| 校验完整性 | 当前仅做 skillCount 前后对比 | 后续可扩展 checksum、签名等完整性校验 |

---

## 9. 与 spring-ai-ascend 原版的映射关系

| spring-ai-ascend | 当前项目 | 说明 |
|---|---|---|
| `AgentExecutionContext`（请求上下文） | `SkillHubContext`（稳定部署上下文） | 从请求级改为部署态稳定上下文（agentId、tenantId），不依赖 user/session/task |
| `SkillHubProvider.listSkills(context)` / `loadSkill(context, skillId)` | `SkillHubProvider.listSkills(ctx)` / `loadSkill(ctx, skillId)` | context 类型从 `AgentExecutionContext` 改为 `SkillHubContext` |
| `SkillSummary` / `SkillDefinition` / `SkillToolDependency` / `SkillPackage` | 同名 record 原样移值 | `SkillSummary` 新增 `required` 字段（FEAT-005 §2 required/optional 语义） |
| `OpenJiuwenSkillHubInstaller` | `AgentCoreSkillHubInstaller` | 保留 DeepAgent 适配代码、移除 prompt 注入、context 类型改为 `SkillHubContext`；新增 required fail fast / optional 降级逻辑 |
| `OpenJiuwenSkillHubAutoConfiguration` | 融入 `AgentCoreAdaptersAutoConfiguration` | 复用现有自动装配 |
| `installRuntimeTools(agent, context)` 在 `doExecute` 内 | `installToAgent(agent, ctx)` 在 `start()` 内 | 从请求级改为启动期一次 |
| `injectRuntimeSkillSection` | **移除** | FEAT-005 §5.1.4 明确 runtime 不把 skill instructions 注入运行时 prompt；`registerSkill` 已注入 description |
| `OpenJiuwenAgentRuntimeHandler.setSkillHubInstaller` | `JiuwenCoreAgentHandler` 构造注入 `SkillHubAdapterRegistrar` | 无 setter，构造期定型 |
| —（原版无） | `SkillHubProperties` | 新增：FEAT-005 §3 runtime Skill Hub 服务连接配置（endpoint/credentialRef/authType） |
| —（原版无） | `SkillHubException` / `SkillHubErrorCategory` | 新增：FEAT-005 §5.1.5 错误诊断分类 |
| —（原版无） | `OpenJiuwenSkillHubProvider` | 新增：FEAT-005 §2 openJiuwen 默认实现 MUST，对接 `openJiuwen/skillhub` API |
| —（原版无） | `SkillSummary.required` 字段 + fail fast/降级逻辑 | 新增：FEAT-005 §2 required/optional 语义 |
