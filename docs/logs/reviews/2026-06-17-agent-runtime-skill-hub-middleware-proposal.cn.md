# Agent Runtime SkillHub 中间件 Proposal

## 背景

当前 `agent-runtime` 已经把 State、Memory、MCP 等可选能力收敛为“公共窄 SPI + 框架本地 adapter/installer”的模式。下一步的 SkillHub 也应该沿用这个方向，但它和 MCP 不是同一类能力：

- MCP 是工具调用协议，解决“模型如何发现并调用远端工具”。
- SkillHub 是技能目录和渐进式加载机制，解决“模型/Agent 什么时候加载一段任务说明、工作流约束、参考资料和工具使用指引”。

因此 SkillHub 可以引用 MCP tool、远端 A2A tool 或本地文件能力，但 SkillHub 本身不应该被建模成 MCP。

## 目标

1. Runtime 支持可选 SkillHub 接入。
2. SkillHub 首屏只暴露技能摘要，避免一次性把所有技能全文放入上下文。
3. 具体 Agent 框架负责把技能接到自己的原生机制里。
4. 公共 SPI 不绑定 OpenJiuwen、AgentScope、MCP 或 Nacos 的包名。
5. Wave 1 先支持 OpenJiuwen 本地 skill 注册，后续再扩展远端 Skill Registry。

## 非目标

1. 不在本轮实现 Nacos Skill Registry。
2. 不把 SkillHub 做成 MCP marketplace。
3. 不实现企业级多租户权限模型。
4. 不实现动态技能热更新。
5. 不要求所有 Agent 框架共享同一个 Skill 类继承层次。

## 设计原则

### SkillHub 与 MCP 解耦

SkillHub 负责技能发现和技能内容加载。MCP 负责工具发现和工具调用。一个 skill 可以在 `toolDependencies` 中声明它建议使用的工具，例如：

```json
{
  "type": "mcp",
  "name": "get_current_time",
  "metadata": {
    "serverId": "localtime"
  }
}
```

但这只是声明依赖，不代表 SkillHub 自己执行工具调用。

### 渐进式加载

SkillHub Provider 暴露两层信息：

1. `SkillSummary`：轻量摘要，包含 skillId、name、description、tags、metadata。
2. `SkillDefinition`：完整定义，包含 instructions、referenceUris、toolDependencies、metadata。

Agent 框架可以先用 summary 帮模型判断是否需要某个技能；只有需要时再加载 definition。OpenJiuwen 当前原生 `registerSkill(...)` 已经有本地 skill 目录语义，Wave 1 先把 provider 中的本地 skill 路径交给 OpenJiuwen 注册。

## 公共 SPI 草案

### SkillHubProvider

```java
public interface SkillHubProvider {
    List<SkillSummary> listSkills(AgentExecutionContext context);

    SkillDefinition loadSkill(AgentExecutionContext context, String skillId);

    default SkillPackage loadSkillPackage(AgentExecutionContext context, String skillId);
}
```

### SkillSummary

| 字段 | 类型 | 说明 |
|---|---|---|
| `skillId` | `String` | Runtime 内唯一技能 ID |
| `name` | `String` | 面向模型和开发者的技能名 |
| `description` | `String` | 摘要描述，不应包含完整长提示词 |
| `tags` | `List<String>` | 检索标签 |
| `metadata` | `Map<String,Object>` | Provider 自定义元数据 |

### SkillDefinition

| 字段 | 类型 | 说明 |
|---|---|---|
| `skillId` | `String` | 与 summary 对应 |
| `name` | `String` | 技能名 |
| `description` | `String` | 技能描述 |
| `instructions` | `String` | 完整技能说明，可来自 SKILL.md |
| `referenceUris` | `List<String>` | 额外参考材料 URI |
| `toolDependencies` | `List<SkillToolDependency>` | 推荐工具依赖声明 |
| `metadata` | `Map<String,Object>` | 框架本地扩展信息 |

OpenJiuwen Wave 1 使用以下 metadata key：

| key | 类型 | 说明 |
|---|---|---|
| `openjiuwen.skill.path` | `String` | 单个本地 skill 目录 |
| `openjiuwen.skill.paths` | `List<String>` | 多个本地 skill 目录 |

### SkillPackage

| 字段 | 类型 | 说明 |
|---|---|---|
| `skillId` | `String` | 与 summary/definition 对应 |
| `mediaType` | `String` | 包格式，例如 `application/zip` |
| `content` | `byte[]` | 打包后的 skill payload |
| `metadata` | `Map<String,Object>` | 包大小、来源等可观测信息 |

`loadSkillPackage(...)` 是可选能力。它服务于 SkillHub 的下载/安装场景，不参与模型工具调用。

## OpenJiuwen 落地

新增 `OpenJiuwenSkillHubInstaller`：

1. 调用 `SkillHubProvider.listSkills(context)` 获取摘要。
2. 对每个 summary 调用 `loadSkill(context, skillId)`。
3. 从 definition metadata 中读取 `openjiuwen.skill.path(s)`。
4. 调用 `BaseAgent.registerSkill(path)` 注册给 OpenJiuwen。

这样做的取舍是：

- 公共 SPI 仍保持框架中立。
- OpenJiuwen 原生 skill 目录和 `registerSkill(...)` 继续由 OpenJiuwen 维护。
- Wave 1 先完成可插拔链路，不在 runtime 公共层实现 Skill 文件解析器。

## 公开 Skill 来源调研

可用于后续联调的公开来源包括：

1. GitHub 上的 `openai/skills`：包含 Codex/OpenAI 相关 `SKILL.md`，适合验证 Codex 风格 metadata 和 body。
2. GitHub 上的 `anthropics/skills`：包含 skill-creator、pptx 等目录，适合验证通用 `SKILL.md + 资源` 目录结构。
3. GitHub 上的 `huggingface/skills`：包含 Hugging Face 任务技能，适合验证带领域说明的 AI/ML skill。
4. `mcpservers.org/agent-skills`：提供 Agent Skills Library 页面，可作为远端 SkillHub 目录形态参考。
5. GitHub Copilot `gh skill`：GitHub 官方提供的搜索/安装入口，后续可评估是否做一个 GitHub-backed SkillHubProvider。

本轮不直接 vendoring 第三方 skill 内容，避免许可证和版本漂移问题；example 提供本地目录导入能力，测试团队可以按 README 将公开 skill 下载到 `skills/` 子目录后验证。

## 自动装配

当 classpath 存在 OpenJiuwen 且 Spring 容器中存在 `SkillHubProvider` bean 时：

1. 创建 `OpenJiuwenSkillHubInstaller`。
2. 注入所有 `OpenJiuwenAgentRuntimeHandler` bean。
3. 没有 `SkillHubProvider` 时不创建 installer，不影响已有 Agent 启动。

## 本轮实现范围

1. 增加公共 `SkillHubProvider`、`SkillSummary`、`SkillDefinition`、`SkillToolDependency`、`SkillPackage`。
2. 增加 `OpenJiuwenSkillHubInstaller`，读取 `openjiuwen.skill.path(s)` 并调用 OpenJiuwen 原生 `registerSkill(...)`。
3. 增加 Spring 自动装配：当存在 `SkillHubProvider` bean 和 OpenJiuwen classpath 时，将 installer 注入 OpenJiuwen handler。
4. 增加 `agent-runtime-middleware-skillhub-local` example，使用本地 `skills/` 目录演示 summary、definition、zip package 和 OpenJiuwen 注册链路。

## 后续计划

1. 调研 Nacos Skill Registry，并作为远端 SkillHubProvider 的一个实现。
2. 研究 AgentScope 的长期技能/工具包加载方式，确认是否需要 summary cache 和 change notification。
3. 若 OpenJiuwen 后续暴露更细粒度的按需 skill loading hook，再把 Wave 1 的“执行前注册本地 skill”升级为真正运行时按需加载。

## 验证计划

1. `SkillSummary`、`SkillDefinition`、`SkillToolDependency`、`SkillPackage` 的 null/default/copy 行为。
2. `OpenJiuwenSkillHubInstaller` 能把 provider 返回的本地 skill path 注册到 Agent。
3. 没有 SkillHubProvider 时 OpenJiuwen handler 正常运行。
4. remote A2A tool installer 与 SkillHub installer 共存。
5. `./mvnw -pl agent-runtime -am verify`。
