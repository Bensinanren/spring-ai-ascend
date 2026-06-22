# SkillHub Runtime Middleware — 设计文档

> 适用目录：`architecture/docs/L2/agent-runtime/`
> 目标模块：`agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/spi/`、`agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/openjiuwen/`
> 最后更新：2026-06-22

---

## 1. 概述

### 1.1 特性定位

agent-runtime 的 SkillHub 接入定位为 **渐进式技能加载中间件**。SkillHub 先暴露轻量技能摘要，只有在具体框架需要安装或使用某个技能时，才加载完整技能定义或技能包。

SkillHub 和 MCP 是解耦能力：

- MCP 负责工具发现和工具调用。
- SkillHub 负责技能目录、说明、参考资料和可选包加载。
- 一个 skill 可以声明推荐工具依赖，例如某个 MCP tool，但 SkillHub 本身不执行工具调用。

### 1.2 核心设计原则

1. **渐进式加载** — `SkillSummary` 用于发现，`SkillDefinition` 用于完整说明，`SkillPackage` 用于可选下载/安装。
2. **公共 SPI 中立** — `SkillHubProvider` 不绑定 OpenJiuwen、AgentScope、MCP 或 Nacos 包名。
3. **框架本地安装** — OpenJiuwen 使用 `BaseAgent.registerSkill(...)`；其他框架未来使用自己的 skill/knowledge 机制。
4. **无 Provider 不影响启动** — 没有 `SkillHubProvider` 时，不创建 installer，Agent 正常执行。

### 1.3 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|---|---|---|---|
| SkillHub Provider SPI | 技能摘要、完整定义、可选包加载 | `SkillHubProvider` | ✅ |
| 技能摘要模型 | 首屏轻量发现 | `SkillSummary` | ✅ |
| 技能完整定义 | instructions、referenceUris、toolDependencies | `SkillDefinition` | ✅ |
| 技能包模型 | 可下载/安装 payload | `SkillPackage` | ✅ |
| OpenJiuwen 安装 | 读取 metadata 中的本地路径并注册技能 | `OpenJiuwenSkillHubInstaller` | ✅ |
| 自动装配 | 有 `SkillHubProvider` 时自动注入 OpenJiuwen handler | `OpenJiuwenSkillHubAutoConfiguration` | ✅ |
| Nacos Skill Registry | 远端注册中心实现 | — | ⬜ |
| 动态技能热更新 | 运行中刷新技能目录 | — | ⬜ |

---

## 2. 功能规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| 列出技能摘要 | ✅ | `listSkills(context)` 返回 compact summary |
| 加载完整技能 | ✅ | `loadSkill(context, skillId)` 返回 instructions 等完整定义 |
| 加载技能包 | ✅ | `loadSkillPackage(context, skillId)` 是可选能力 |
| OpenJiuwen 本地技能安装 | ✅ | 通过 `openjiuwen.skill.path(s)` metadata 调用 `registerSkill(...)` |
| 本地目录样例 | ✅ | 从本地 `SKILL.md` 目录加载 |
| 远端 JSON 目录样例 | ✅ | 通过 JSON catalog 模拟远端 SkillHub |
| 工具依赖声明 | ✅ | `SkillToolDependency` 描述推荐工具，不负责执行 |
| Nacos Registry | ⬜ | 后续单独调研和实现 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|---|---|---|
| 把 SkillHub 建模成 MCP tool | SkillHub 是技能加载，不是工具调用协议 | Skill 可以声明 MCP tool 依赖 |
| 在公共 SPI 暴露 OpenJiuwen skill 类型 | 会破坏多框架接入 | 通过 metadata 表达 OpenJiuwen 本地路径 |
| 企业级权限模型 | 依赖租户、用户、组织和平台策略 | 由业务自定义 `SkillHubProvider` 过滤 |
| 动态热更新 | 需要处理模型上下文和已安装技能一致性 | 后续独立设计 |

### 2.3 接口契约

#### SkillHubProvider

```java
public interface SkillHubProvider {
    List<SkillSummary> listSkills(AgentExecutionContext context);

    SkillDefinition loadSkill(AgentExecutionContext context, String skillId);

    default SkillPackage loadSkillPackage(AgentExecutionContext context, String skillId);
}
```

行为承诺：

- `listSkills(...)` 应返回当前上下文可见的轻量摘要，不应包含大量 instructions。
- `loadSkill(...)` 按 `skillId` 返回完整定义。Provider 可以基于 tenant、user、agentId 做过滤。
- `loadSkillPackage(...)` 是可选能力，不支持时可以抛出 `UnsupportedOperationException`。

#### SkillDefinition metadata

OpenJiuwen Wave 1 使用以下 metadata key：

| key | 类型 | 说明 |
|---|---|---|
| `openjiuwen.skill.path` | `String` | 单个本地 skill 目录 |
| `openjiuwen.skill.paths` | `List<String>` 或单个 `String` | 多个本地 skill 目录 |

这些 key 是 OpenJiuwen adapter 本地约定，不是公共 SkillHub 协议的必填字段。

---

## 3. 模块结构

```text
engine/spi/
├── SkillHubProvider.java
├── SkillSummary.java
├── SkillDefinition.java
├── SkillToolDependency.java
└── SkillPackage.java

engine/openjiuwen/
├── OpenJiuwenSkillHubInstaller.java
└── OpenJiuwenSkillHubAutoConfiguration.java
```

---

## 4. 核心流程

### 4.1 自动装配流程

```text
应用启动
  │
  ├─ 业务或 example 创建 SkillHubProvider bean
  ├─ 检测 OpenJiuwen classpath
  ├─ 创建 OpenJiuwenSkillHubInstaller
  └─ 注入所有 OpenJiuwenAgentRuntimeHandler
```

### 4.2 执行期技能安装

```text
A2A 请求
  │
  ├─ OpenJiuwenAgentRuntimeHandler.createOpenJiuwenAgent(context)
  ├─ installRuntimeTools(agent, context)
  │     └─ OpenJiuwenSkillHubInstaller.install(agent, context)
  │          ├─ skillHubProvider.listSkills(context)
  │          ├─ skillHubProvider.loadSkill(context, skillId)
  │          ├─ 读取 openjiuwen.skill.path(s)
  │          └─ agent.registerSkill(path)
  └─ OpenJiuwen Runner 执行
```

SkillHub installer 当前每次执行前安装技能。Provider 可以自行缓存 summary/definition，业务也可以根据上下文返回不同技能集合。

---

## 5. SkillHub 与 MCP 的关系

| 维度 | SkillHub | MCP |
|---|---|---|
| 核心对象 | Skill summary / definition / package | Tool / ToolResult |
| 主要用途 | 渐进式加载任务说明和参考资料 | 发现和调用外部工具 |
| 调用时机 | Agent 执行前安装/加载 | 模型推理中触发 tool call |
| 公共 SPI | `SkillHubProvider` | `McpProvider` |
| OpenJiuwen 接入 | `registerSkill(path)` | `Tool` wrapper + `Runner.resourceMgr()` |

一个 skill 可以通过 `toolDependencies` 声明它依赖某个 MCP tool，但这只是提示和依赖描述，不代表 SkillHub 自己调用工具。

---

## 6. 示例与验证

| Example | 用途 |
|---|---|
| `examples/agent-runtime-middleware-skillhub-local` | 本地 `skills/` 目录作为 SkillHub |
| `examples/agent-runtime-middleware-skillhub-remote-json` | 远端 HTTP JSON catalog 作为 SkillHub |

验证入口：

```bash
./mvnw -f examples/agent-runtime-middleware-skillhub-local/pom.xml verify
./mvnw -f examples/agent-runtime-middleware-skillhub-remote-json/pom.xml verify
```

手工 curl 级验证步骤见各 example 的 `README.md` 和 `TUTORIAL.cn.md`。

---

## 7. 相关文档

- 开发指南：`agent-runtime/docs/guides/skillhub.md`
- Proposal：`docs/logs/reviews/2026-06-17-agent-runtime-skill-hub-middleware-proposal.cn.md`
- 测试设计：`architecture/docs/L1/agent-runtime/features/skillhub-middleware-test-design.cn.md`
- Example：`examples/agent-runtime-middleware-skillhub-local/`
