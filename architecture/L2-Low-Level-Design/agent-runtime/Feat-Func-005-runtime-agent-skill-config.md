| level         | L2-LLD                                                                                                                                                                                                                                                                                                |
| ------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| module        | agent-service-adapters-agentcore-ext                                                                                                                                                                                                                                                                  |
| feature_type  | functional                                                                                                                                                                                                                                                                                            |
| feature_id    | Feat-Func-005                                                                                                                                                                                                                                                                                         |
| status        | draft                                                                                                                                                                                                                                                                                                 |
| dependency    | ./agent-service-adapters-agentcore-ext-design.md https://github.com/chaosxingxc-orion/spring-ai-ascend/pull/400 D:/code/agent-core-java/agent-core-java (SkillUseRail / RemoteSkillSource / BaseAgent)                                                                                                |

## Runtime Agent Skill 配置管理设计文档

[](#runtime-agent-skill-配置管理设计文档)

> 目标模块：`common/agent-runtime-ext-java/agent-service-adapters/agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/external/`
> 最后更新：2026-07-12

---

## 1. 概述

[](#1-概述)

### 1.1 特性定位

[](#11-特性定位)

`agent-runtime` 在 Agent 装配和启动阶段读取 Agent 配置中的 skill 来源声明，读取、校验并传递给后续装配/执行流程。来源分为两类：

- **本地 skill 路径**：磁盘目录，内含 `SKILL.md` 的 skill 包。
- **远端 skill 路径**：当前远端来源**仅支持 GitHub**，通过 `owner`/`repo`/`ref`/`directory` 定位仓库子路径。

runtime 边界严格限定为"读、校验、传递"。skill 的实际下载、解析、执行、模型上下文注入由 `agent-core` 的 `SkillUseRail` / `RemoteSkillUtil` / `SkillManager` 完成，不在本特性边界内。

* **解决的问题**：skill 来源配置散落在 demo 前缀（`openjiuwen.demo.*.skill-directories`），仅支持本地目录，无统一配置入口、无远端来源、无凭据安全、无诊断。
* **适用场景**：所有需要为 Agent 装配 skill 来源的 runtime 部署。

### 1.2 当前事实边界

[](#12-当前事实边界)

本文只描述 Feat-Func-005 在当前 `agent-service-adapters-agentcore-ext` 模块中的设计实现。面向调用方的当前版本事实要求由 `version-scope/FEAT-005-runtime-agent-skill-config.md`（PR #400）驱动。`agent-core` 的 skill 内部机制（`SkillManager`、`RemoteSkillUtil.uploadSkillFromGitHub`、`SkillUseRail.syncRemoteSkills`）属 `agent-core-java` 能力，本文仅作为消费方引用。

### 1.3 设计原则

[](#13-设计原则)

1. **沿用安装器模式** — 与同目录 `RemoteA2aToolInstaller` 对齐：静态 `create()` 工厂 + `install(Object agent)` 入口 + 幂等安装。
2. **配置模式对齐 `versatile` 模块** — `Properties` 放 `autoconfigure/` 包，POJO 风格（getter/setter + `static class` 嵌套），用 `@EnableConfigurationProperties` 注册；`external/` 安装器直接持有 `Properties` 引用，不引入独立中间配置模型。
3. **只读不拉** — runtime 只读取配置、校验格式、解析凭据引用；GitHub 内容下载由 `agent-core` 的 `SkillUseRail` 完成。runtime 不定义 skill 获取策略。
4. **直接复用 agent-core 接口** — GitHub 来源直接转换为 `SkillUseRail.RemoteSkillSource`，不自定义中间 record，减少适配层。
5. **凭据不落日志** — 凭据以引用名声明，运行时解析为明文 token 传入 `RemoteSkillSource.token`；日志只记录 `credential=provided/absent` 布尔摘要。

### 1.4 子特性全景

[](#14-子特性全景)

| 子特性            | 职责                              | 关键抽象                                   | 状态 |
| ---------------- | --------------------------------- | ------------------------------------------ | ---- |
| skill 来源配置   | 读取 YAML 声明的本地/GitHub 来源 | SkillSourceProperties                      | 草案 |
| 来源校验与诊断   | 区分缺失/格式错/不支持来源/凭据   | SkillSourceInstaller (resolve + validate) | 草案 |
| 安装器           | 把已校验来源安装到 Agent         | SkillSourceInstaller.install(agent)       | 草案 |

---

## 2. 特性规格

[](#2-特性规格)

### 2.1 能力清单

[](#21-能力清单)

| 能力                      | 状态 | 说明                                                                                |
| ------------------------- | ---- | ----------------------------------------------------------------------------------- |
| 声明本地 skill 路径       | 草案 | 通过 `openjiuwen.service.skill.sources[].type=local` 声明一个或多个本地目录        |
| 声明 GitHub 远端 skill    | 草案 | 通过 `type=github` + `owner`/`repo`/`ref`/`directory` 声明远端 skill 来源          |
| 凭据引用                  | 草案 | `credential-ref` 引用名，运行时解析为 token，传入 `RemoteSkillSource.token`        |
| 配置校验                  | 草案 | 校验 type 枚举、local path 非空、github owner/repo 格式、directory 路径逃逸         |
| 诊断信息                  | 草案 | 区分四类错误：配置缺失/格式错误/不支持远端来源/凭据不完整                            |
| 安装到 ReActAgent         | 草案 | 调用 `BaseAgent.registerSkill(localPath)` 注册本地 skill                            |
| 安装到 DeepAgent          | 草案 | 构造 `SkillUseRail`（含 `remoteSkillSources`）并注册为 rail                         |
| skill-mode 透传           | 草案 | 透传 `all`/`auto_list`/`none` 给 `SkillUseRail.skillMode`                           |
| 幂等安装                  | 草案 | 同一 Agent 重复 `install()` 不重复注册                                              |
| 非敏感日志                | 草案 | 日志不输出 token/密钥/认证头；GitHub 来源只记 `credential=provided/absent`          |
| 非 GitHub 远端来源        | ⬜   | 当前不支持 GitLab/Gitee/对象存储等                                                  |
| skill 热更新              | ⬜   | 配置在装配阶段读取一次，进程内不变更                                                |
| GitHub 内容拉取           | ⬜   | 由 `agent-core` 的 `SkillUseRail.syncRemoteSkills()` 完成，非本特性                  |
| Skill Hub 平台            | ⬜   | 不实现审批流、权限系统、版本管理                                                    |

### 2.2 显式排除

[](#22-显式排除)

| 排除项                  | 原因                                                                   | 替代                                       |
| ----------------------- | ---------------------------------------------------------------------- | ------------------------------------------ |
| 非 GitHub 远端来源      | 需求明确"当前远端来源仅支持 GitHub"                                    | GitLab/Gitee 等后续特性扩展                |
| GitHub 拉取实现         | `agent-core` 的 `RemoteSkillUtil` 已实现，runtime 不重复               | 传 `RemoteSkillSource` 给 `SkillUseRail`  |
| skill 文件结构定义      | 需求明确不定义 skill 内容格式、包格式                                  | 由 `agent-core` 的 `SkillManager` 解析     |
| 请求级动态切换          | 配置在装配阶段读取一次，进程内不变更                                    | 重启生效                                   |
| 凭据明文存储            | 凭据以引用名声明，实际值由 Spring placeholder/环境变量注入              | `${GH_TOKEN:}` placeholder                 |

### 2.3 行为承诺

[](#23-行为承诺)

* **必须**：`type=local` 时 `path` 非空，否则抛诊断异常
* **必须**：`type=github` 时 `owner`/`repo` 非空且为单段（不含 `/`、`\`），与 `RemoteSkillSource` compact constructor 约束一致
* **必须**：`directory` 禁止绝对路径和 `../` 路径逃逸
* **必须**：`type` 不在 `{local, github}` 枚举内时抛"不支持远端来源"诊断，不静默当 github 处理
* **必须**：日志不输出 token、密钥、认证头、`credential-ref` 解析后的明文
* **必须**：同一 Agent 实例重复 `install()` 不重复注册 skill
* **允许**：本地路径不存在时仅 warn 不阻断装配（对齐 `SkillManager` 软校验行为）
* **允许**：`ref` 缺省 `HEAD`，`directory` 缺省空（仓库根）

---

## 3. 核心实现

[](#3-核心实现)

### 3.1 agent-core skill 机制（消费方引用）

[](#31-agent-core-skill-机制消费方引用)

本特性不实现 skill 下载/解析，仅消费 `agent-core` 已有能力：

```
agent-core-java 能力链
│
├─ BaseAgent (ReActAgent 层)
│   ├─ registerSkill(Object skillPath)           → 本地 skill 注册
│   └─ registerRemoteSkills(dir, GitHubTree, token) → GitHub 下载到本地（不自动注册）
│
├─ SkillUseRail (harness 层，DeepAgent 使用)
│   ├─ RemoteSkillSource record: (owner, repo, ref, directory, token)
│   │   └─ compact constructor 校验: owner/repo 非空单段, directory 禁止逃逸, ref 缺省 HEAD
│   ├─ 构造: SkillUseRail(skillDirs, skillMode, enabled, disabled, remoteSkillSources, enableCache)
│   ├─ init(agent) → syncRemoteSkills()          → 逐个 RemoteSkillSource 调 RemoteSkillUtil 下载
│   └─ 下载结果写入 skillsRoot, 后续 list_skill/skill_tool 工具注册
│
└─ RemoteSkillUtil.uploadSkillFromGitHub(tree, skillsDir, token)
    └─ 调 GitHub API /repos/{owner}/{repo}/git/trees/{ref}?recursive=1
```

### 3.2 SkillSourceInstaller 安装流程

[](#32-skillsourceinstaller-安装流程)

安装器参照 `RemoteA2aToolInstaller` 模式，直接持有 `SkillSourceProperties` 引用（对齐 `VersatileAgentHandler` 直接持有 `VersatileProperties` 的约定），入口 `install(Object agent)`：

```
SkillSourceInstaller.install(agent)
  │
  ├─ properties 为空或 sources 为空? → return (noop)
  ├─ resolveBaseAgent(agent)
  │   ├─ agent instanceof BaseAgent → target = agent
  │   ├─ agent instanceof DeepAgent → target = deepAgent.getAgent()
  │   └─ 其他 → warn "Unsupported agent type" + return
  │
  ├─ 幂等检查: installedAgents.contains(target)?
  │   └─ 是 → return
  │
  ├─ 注册本地 skill:
  │   for each source where type == "local":
  │     target.registerSkill(source.getPath())
  │     log.debug "Registered local skill path={}", source.getPath()
  │
  ├─ 注册 GitHub 远端 skill (ReActAgent 模式):
  │   for each source where type == "github":
  │     RemoteSkillSource rs = toRemoteSkillSource(source)  // 复用 agent-core
  │     String skillDir = resolveSkillDir()                  // 系统临时目录或配置目录
  │     target.registerRemoteSkills(skillDir, rs.toGitHubTree(), rs.token())
  │     target.registerSkill(skillDir)                      // 下载后注册
  │     log.debug "Registered github skill owner={} repo={} ref={} credential={}",
  │               rs.owner(), rs.repo(), rs.ref(),
  │               rs.token().isBlank() ? "absent" : "provided"
  │
  └─ installedAgents.add(target)
```

> **DeepAgent 模式**：DeepAgent 内部用 `SkillUseRail`，GitHub 来源应在 `DeepAgentConfig` 构造时传入 `remoteSkillSources`。安装器对 DeepAgent 的处理是构造 `SkillUseRail`（含 `remoteSkillSources`）并注册为 rail，或直接在 wrapper 装配阶段把 `properties` 解析出的 `RemoteSkillSource` 列表传给 `DeepAgentConfig.builder().rails(...)`。

### 3.3 配置校验流程

[](#33-配置校验流程)

`SkillSourceInstaller.create()` 在工厂方法内执行校验，校验失败抛 `SkillSourceConfigException`：

```
create(SkillSourceProperties properties, Environment environment)
  │
  ├─ properties 为空或 sources 为空 → return noop()
  │
  ├─ for each source at index N:
  │   ├─ type == null → 抛 "missing type at index N"
  │   ├─ type == "local"
  │   │   ├─ path 为空 → 抛 "local source path must not be blank at index N"
  │   │   └─ path 不存在 → warn (不抛)
  │   ├─ type == "github"
  │   │   ├─ owner 为空/含分隔符 → 抛 "github owner must be single segment at index N"
  │   │   ├─ repo 为空/含分隔符 → 抛 "github repo must be single segment at index N"
  │   │   ├─ directory 含 ../ 或绝对路径 → 抛 "directory path traversal not allowed at index N"
  │   │   └─ credential-ref 为空且仓库可能需认证 → warn "github source may require credential"
  │   ├─ type 其他 → 抛 "unsupported remote source type '{type}' at index N; only local/github supported"
  │   └─ 解析凭据: environment.resolvePlaceholders("${" + credentialRef + ":}") → 明文 token
  │       （token 不写入日志、不写入异常 message）
  │
  ├─ 构建 RemoteSkillSource (复用 agent-core):
  │   new SkillUseRail.RemoteSkillSource(owner, repo, ref, directory, token)
  │   └─ compact constructor 再次校验（agent-core 的防御性校验）
  │
  └─ return new SkillSourceInstaller(properties, resolvedGithubSources)
```

> **凭据解析**：`SkillSourceProperties` 只持有 `credential-ref` 引用名（非明文）。`create()` 通过 Spring `Environment.resolvePlaceholders()` 把引用解析为明文 token，传入 `RemoteSkillSource.token`。明文 token 只存在安装器实例字段中，**不写入日志、不写入异常 message**。日志只输出 `credential=provided/absent` 布尔摘要。

### 3.4 凭据解析

[](#34-凭据解析)

```
credential-ref = "gh-token"   (yaml 声明)
  │
  ├─ SkillSourceProperties 只持有引用名 credential-ref（不存明文 token）
  ├─ create() 调用 environment.resolvePlaceholders("${" + credentialRef + ":}")
  │   └─ Spring placeholder 解析 → 明文 token (运行时)
  ├─ 明文 token 传入 RemoteSkillSource.token (agent-core record)
  └─ 日志: credential=provided/absent (不记 token 值)
```

凭据实际值由部署侧通过 Spring placeholder（`${GH_TOKEN:}`）或环境变量注入。明文 token **不写入日志、不写入异常 message**。

---

## 4. 代码结构

[](#4-代码结构)

### 4.1 包结构

[](#41-包结构)

```
agent-service-adapters-agentcore-ext/src/main/java/com/openjiuwen/service/adapters/agentcore/ext/
├── autoconfigure/
│   ├── AgentCoreExtAutoConfiguration.java        # 扩展：@EnableConfigurationProperties(SkillSourceProperties.class)
│   └── SkillSourceProperties.java                 # 新增：@ConfigurationProperties(prefix="openjiuwen.service.skill")
└── external/
    ├── RemoteA2aToolInstaller.java               # 已有：远程 A2A 工具安装器
    ├── RemoteA2aInterruptRail.java               # 已有：远程 A2A 中断 rail
    ├── SkillSourceInstaller.java                  # 新增：skill 来源安装器（持有 SkillSourceProperties）
    └── SkillSourceConfigException.java            # 新增：配置诊断异常
```

> **对齐 `versatile` 约定**：`SkillSourceProperties` 放 `autoconfigure/` 包，POJO 风格（getter/setter + `static class` 嵌套），用 `@EnableConfigurationProperties` 注册；`SkillSourceInstaller` 直接持有 `SkillSourceProperties` 引用，不引入独立中间配置模型类。`RemoteA2aToolInstaller` 本身不持有 Properties（它消费 `A2ARemoteAgentCardRegistry`），本特性因需读取 YAML 配置而新增 Properties。

### 4.2 核心类静态关系

[](#42-核心类静态关系)

```
«autoconfigure»                    «properties»
AgentCoreExtAutoConfiguration  →  SkillSourceProperties
  │  @EnableConfigurationProperties   (@ConfigurationProperties prefix=openjiuwen.service.skill
  │                                   POJO: getters/setters + static class Source)
  │  注册 bean
  ▼
«installer»
SkillSourceInstaller
  │  create(properties, environment)   # 校验 + 凭据解析
  │  install(agent)                      # 幂等安装
  │  持有 SkillSourceProperties 引用
  │
  ▼
agent-core (消费方)
  ├─ BaseAgent.registerSkill(localPath)
  ├─ BaseAgent.registerRemoteSkills(dir, GitHubTree, token)
  └─ SkillUseRail.RemoteSkillSource(owner, repo, ref, directory, token)
```

### 4.3 新增类设计

[](#43-新增类设计)

#### SkillSourceInstaller.java

```java
package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.SkillUseRail.RemoteSkillSource;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.SkillSourceProperties;
import com.openjiuwen.service.adapters.agentcore.ext.autoconfigure.SkillSourceProperties.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.*;

/**
 * Installs skill sources (local + GitHub) into supported AgentCore agents.
 * Holds SkillSourceProperties reference (aligns with VersatileAgentHandler pattern).
 * @since 2026-07-12
 */
public class SkillSourceInstaller {
    private static final Logger log = LoggerFactory.getLogger(SkillSourceInstaller.class);

    private final SkillSourceProperties properties;
    private final List<RemoteSkillSource> githubSources;  // 已解析凭据
    private final Map<BaseAgent, Set<String>> installedAgents = Collections.synchronizedMap(new WeakHashMap<>());

    private SkillSourceInstaller(SkillSourceProperties properties, List<RemoteSkillSource> githubSources) {
        this.properties = properties;
        this.githubSources = githubSources;
    }

    public static SkillSourceInstaller create(SkillSourceProperties properties, Environment environment) {
        // 校验 + 凭据解析（见第 3.3 节）
    }

    public static SkillSourceInstaller noop() {
        return new SkillSourceInstaller(null, List.of());
    }

    public void install(Object agent) {
        if (properties == null || properties.getSources() == null || properties.getSources().isEmpty()) {
            return;
        }
        Optional<BaseAgent> target = resolveBaseAgent(agent);
        if (target.isEmpty()) {
            log.warn("Unsupported agent type for skill source install: {}",
                    agent == null ? "null" : agent.getClass().getName());
            return;
        }
        BaseAgent baseAgent = target.get();
        Set<String> installed = installedAgents.computeIfAbsent(baseAgent, key -> new LinkedHashSet<>());
        synchronized (installed) {
            registerLocalSkills(baseAgent, installed);
            registerGitHubSkills(baseAgent, installed);
        }
    }

    private void registerLocalSkills(BaseAgent agent, Set<String> installed) {
        properties.getSources().stream()
            .filter(s -> "local".equalsIgnoreCase(s.getType()))
            .map(Source::getPath)
            .filter(path -> !installed.contains(path))
            .forEach(path -> {
                agent.registerSkill(path);
                installed.add(path);
                log.debug("Registered local skill path={}", path);
            });
    }

    private void registerGitHubSkills(BaseAgent agent, Set<String> installed) {
        // 对每个 github source 构造 GitHubTree，调 registerRemoteSkills + registerSkill
        // 日志: credential=provided/absent（不记 token 值）
    }

    // resolveBaseAgent: 同 RemoteA2aToolInstaller (BaseAgent | DeepAgent.getAgent())
}
```

#### SkillSourceProperties.java

```java
package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for Agent skill sources.
 * @since 2026-07-12
 */
@ConfigurationProperties(prefix = "openjiuwen.service.skill")
public class SkillSourceProperties {
    private String skillMode = "all";
    private List<Source> sources = new ArrayList<>();

    public String getSkillMode() { return skillMode; }
    public void setSkillMode(String skillMode) { this.skillMode = skillMode; }

    public List<Source> getSources() { return sources; }
    public void setSources(List<Source> sources) {
        this.sources = sources != null ? new ArrayList<>(sources) : new ArrayList<>();
    }

    /** Skill source declaration (local or github). */
    public static class Source {
        private String type;          // "local" | "github"
        private String path;          // local: 目录路径
        private String owner;         // github: 仓库 owner
        private String repo;          // github: 仓库名
        private String ref = "HEAD";  // github: 分支/tag/commit
        private String directory;     // github: 仓库内子路径
        private String credentialRef;// github: 凭据引用名

        // getters/setters 省略
    }
}
```

#### SkillSourceConfigException.java

```java
package com.openjiuwen.service.adapters.agentcore.ext.external;

/** Diagnostics exception for skill source config errors. */
public class SkillSourceConfigException extends RuntimeException {
    public SkillSourceConfigException(String message) { super(message); }
}
```

#### AgentCoreExtAutoConfiguration 扩展

```java
@AutoConfiguration
@EnableConfigurationProperties(SkillSourceProperties.class)   // 新增：对齐 versatile 约定
public class AgentCoreExtAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    RemoteA2aToolInstaller remoteA2aToolInstaller(ObjectProvider<A2ARemoteAgentCardRegistry> registry) {
        return RemoteA2aToolInstaller.create(registry.getIfAvailable());   // 已有
    }

    @Bean
    @ConditionalOnMissingBean
    SkillSourceInstaller skillSourceInstaller(
            ObjectProvider<SkillSourceProperties> properties,
            Environment environment) {
        SkillSourceProperties props = properties.getIfAvailable();
        if (props == null || props.getSources() == null || props.getSources().isEmpty()) {
            return SkillSourceInstaller.noop();
        }
        return SkillSourceInstaller.create(props, environment);  // 校验 + 凭据解析
    }
}
```

---

## 5. 运行流程

[](#5-运行流程)

### 5.1 主流程

[](#51-主流程)

```
application.yml
  openjiuwen.service.skill.sources[]
  │
  ▼ Spring 绑定
SkillSourceProperties (autoconfigure 包, POJO)
  │
  ▼ AgentCoreExtAutoConfiguration
SkillSourceInstaller.create(properties, environment)   # 校验 + 凭据解析
  │
  ▼ SkillSourceInstaller (bean, 直接持有 properties 引用)
wrapper 层装配 (如 DeepAgentRuntimeApplication)
  installer.install(agent)
  │
  ▼ agent-core
BaseAgent.registerSkill(localPath)          → 本地 skill 注册
BaseAgent.registerRemoteSkills(dir, tree, token) → GitHub 下载
SkillUseRail.init(agent).syncRemoteSkills() → DeepAgent 远端同步
```

### 5.2 分支流程

[](#52-分支流程)

#### ReActAgent 分支

[](#reactagent-分支)

ReActAgent 直接用 `BaseAgent.registerSkill` + `registerRemoteSkills`：
- 本地路径：遍历 `properties.getSources()` 中 `type=local` 的项，`registerSkill(path)` 逐个注册
- GitHub 来源：遍历 `type=github` 的项，构造 `RemoteSkillSource` → `registerRemoteSkills(skillDir, gitHubTree, token)` 下载到临时目录 → `registerSkill(skillDir)` 注册

#### DeepAgent 分支

[](#deepagent-分支)

DeepAgent 内部 Agent 是 `BaseAgent`，同时支持 `SkillUseRail`：
- 方式 A：安装器直接 `install(deepAgent)` → `resolveBaseAgent` 取 `deepAgent.getAgent()` → 走 ReActAgent 分支
- 方式 B（推荐）：wrapper 装配阶段把 `installer.getGithubSources()` 传给 `DeepAgentConfig.builder().rails(new SkillUseRail(localPaths, skillMode, ..., githubSources, true))`，让 `SkillUseRail.init()` 统一处理本地 + 远端

### 5.3 错误、取消、降级处理

[](#53-错误取消降级处理)

| 错误场景             | 触发条件                              | 行为                     | 对外结果                                       |
| -------------------- | ------------------------------------- | ------------------------ | ---------------------------------------------- |
| 配置缺失             | `sources` 为空且未禁用 skill          | `noop()` 安装器          | Agent 正常启动，无 skill 加载                  |
| type 缺失            | `sources[].type` 为 null             | 抛 `SkillSourceConfigException` | 启动失败，异常 message 含索引定位             |
| local path 为空      | `type=local` 且 `path` 为 blank       | 抛 `SkillSourceConfigException` | 启动失败                                       |
| local path 不存在    | 路径不存在                            | warn，不阻断              | Agent 正常启动，该路径 skill 不可用            |
| github owner 格式错  | `owner` 含 `/` 或为空                 | 抛 `SkillSourceConfigException` | 启动失败                                       |
| directory 路径逃逸   | `directory` 含 `../` 或绝对路径        | 抛 `SkillSourceConfigException` | 启动失败                                       |
| 不支持远端来源       | `type` 非 `local`/`github`            | 抛 `SkillSourceConfigException` | 启动失败，message 含 `unsupported remote source type` |
| 凭据不完整           | github 仓库需认证但 `credential-ref` 为空 | warn，不阻断           | GitHub API 返回 401，`RemoteSkillUtil` 记录错误 |
| GitHub 下载失败      | 网络/API 错误                         | `agent-core` `RemoteSkillUtil` 抛出 | Agent 装配阶段失败                            |
| 重复 install         | 同一 Agent 实例再次调用 `install()`   | 跳过，不重复注册          | 无副作用                                       |

---

## 6. 配置使用

[](#6-配置使用)

### 6.1 完整配置示例

[](#61-完整配置示例)

```yaml
openjiuwen:
  service:
    skill:
      skill-mode: all
      sources:
        - type: local
          path: "common/example/agentcore-ext-remote-a2a-tool-demo/skills"
        - type: local
          path: "/etc/agent/skills/common"
        - type: github
          owner: "openjiuwen"
          repo: "agent-skills"
          ref: "main"
          directory: "skills/banking"
          credential-ref: "gh-token"
```

凭据通过 Spring placeholder 注入：

```yaml
# application.yml 或环境变量
# 环境变量: GH_TOKEN=ghp_xxxx
# 或 application.yml:
# openjiuwen.service.skill.sources[2].credential-ref 解析为 ${GH_TOKEN:}
```

wrapper 装配示例（demo 迁移后）：

```java
// DeepAgentRuntimeApplication.java
@Bean
AgentHandler deepAgentHandler(DeepAgentLlmProperties llmProps,
                              SkillSourceInstaller skillSourceInstaller,
                              ...) {
    SkillSourceProperties skillProps = skillSourceInstaller.getProperties();
    DeepAgent agent = DeepAgent.builder()
        .config(DeepAgentConfig.builder()
            .skillDirectories(localPathsFrom(skillProps))   // type=local 的 path 列表
            .skillMode(skillProps.getSkillMode())
            .rails(List.of(new SkillUseRail(
                localPathsFrom(skillProps),
                skillProps.getSkillMode(),
                List.of(), List.of(),
                skillSourceInstaller.getGithubSources(),    // 已解析凭据的 RemoteSkillSource 列表
                true)))
            .build())
        .build();
    skillSourceInstaller.install(agent);
    return new JiuwenCoreAgentExtHandler(agent);
}
```

### 6.2 配置属性表

[](#62-配置属性表)

| 属性路径                                         | 类型   | 默认值 | 说明                                                          |
| ------------------------------------------------ | ------ | ------ | ------------------------------------------------------------- |
| openjiuwen.service.skill.skill-mode              | String | all    | skill 模式：all/auto_list/none，透传给 `SkillUseRail.skillMode` |
| openjiuwen.service.skill.sources[].type          | String | —      | 来源类型：`local` \| `github`（必填）                         |
| openjiuwen.service.skill.sources[].path          | String | —      | local: 本地目录路径（local 必填）                             |
| openjiuwen.service.skill.sources[].owner         | String | —      | github: 仓库 owner，单段不含 `/`（github 必填）               |
| openjiuwen.service.skill.sources[].repo           | String | —      | github: 仓库名，单段不含 `/`（github 必填）                   |
| openjiuwen.service.skill.sources[].ref           | String | HEAD   | github: 分支/tag/commit                                       |
| openjiuwen.service.skill.sources[].directory     | String | (空)   | github: 仓库内子路径，禁止 `../` 和绝对路径                   |
| openjiuwen.service.skill.sources[].credential-ref | String | (空)  | github: 凭据引用名，运行时解析为 token                         |

---

## 7. 当前限制

[](#7-当前限制)

| 限制                        | 影响范围                           | 临时方案                              |
| --------------------------- | ---------------------------------- | ------------------------------------- |
| 仅支持 GitHub 远端来源      | GitLab/Gitee/对象存储不可用       | 后续特性扩展                          |
| GitHub 拉取依赖 agent-core  | runtime 不控制拉取重试/缓存策略    | 由 `SkillUseRail.syncRemoteSkills` 处理 |
| 配置不热更新                | 修改 skill 来源需重启              | 重启生效                              |
| 凭据仅支持 Spring placeholder | 无 secrets manager 集成           | `${GH_TOKEN:}` placeholder            |
| local path 存在性为软校验   | 路径不存在时 skill 静默不可用      | warn 日志，运维需检查                 |
| demo 迁移                   | 现有 `openjiuwen.demo.*.skill-directories` 需迁移到新前缀 | 一次性迁移或保留 demo 字段作 fallback |

---

## 8. 关联文档

[](#8-关联文档)

| 文档                                                              | 关系             |
| ----------------------------------------------------------------- | ---------------- |
| [agent-service-adapters-agentcore-ext-design.md](./agent-service-adapters-agentcore-ext-design.md) | 模块总体设计     |
| PR #400 `version-scope/FEAT-005-runtime-agent-skill-config.md`    | 需求驱动文档     |
| `agent-core-java` `SkillUseRail.java` / `RemoteSkillSource`       | 消费方接口       |
| `agent-core-java` `BaseAgent.registerSkill` / `registerRemoteSkills` | 消费方接口       |
| `agent-core-java` `RemoteSkillUtil.uploadSkillFromGitHub`         | GitHub 下载实现  |
| `agent-core-java` 文档 `高阶用法/Agent Skills.md`                 | skill 使用指南   |
