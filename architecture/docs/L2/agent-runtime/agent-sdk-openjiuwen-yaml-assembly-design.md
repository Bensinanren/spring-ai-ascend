---
level: L2
module: agent-sdk
feature: openjiuwen-yaml-assembly
status: shipped
---

# Agent SDK OpenJiuwen YAML 装配设计

## 1. 范围

`agent-sdk` 是面向应用侧的轻量装配 SDK。它加载 `ascend-agent/v1` YAML，并创建原生 OpenJiuwen agent 实例。它不托管 agent、不路由 A2A 流量、不负责 Spring Boot runtime wiring，也不把 agent 抽象成 `agent-runtime` SPI。

已落地入口如下：

```java
public final class AgentFactory {
    public static ReActAgent toReactAgent(Path yamlPath);
    public static DeepAgent toDeepAgent(Path yamlPath);
    public static AgentFactoryBuilder builder();
}
```

`AgentFactoryBuilder` 提供代码级扩展点：

```java
AgentFactory.builder()
    .toolResolver(customResolver)
    .rail(customRail)
    .toReactAgent(path);

AgentFactory.builder()
    .toolResolver(customResolver)
    .rail(customRail)
    .toDeepAgent(path);
```

返回类型是 OpenJiuwen 原生 `ReActAgent` 和 `DeepAgent`。已发布 API 中没有返回 `Object` 的通用工厂。

## 2. 模块边界

`agent-sdk` 使用独立 `pom.xml`，主要依赖如下：

| 依赖 | 作用 |
|---|---|
| `com.openjiuwen:agent-core-java:0.1.12-jdk17` | 原生 ReActAgent、DeepAgent、tools、rails、MCP、harness 对象 |
| `org.yaml:snakeyaml` | YAML 加载 |
| `com.fasterxml.jackson.core:jackson-databind` | HTTP tool JSON 请求和响应处理 |
| JUnit 5 / AssertJ | SDK 测试 |

该模块继承仓库 parent，并使用仓库统一 Java target 编译。OpenJiuwen 依赖使用 JDK 17 classifier，这是依赖兼容性选择，不改变 SDK 的编译目标。

`agent-sdk` 不是服务端 runtime。若要通过 A2A 暴露装配出的 agent，应用代码需要将原生 OpenJiuwen 对象接入 `agent-runtime` handler，或直接编写 runtime handler。

## 3. 装配流程

ReAct 路径：

```text
agent.yaml
  -> AgentYamlLoader
  -> AgentYamlEnvironmentResolver
  -> AgentYamlParser
  -> AgentSpec
  -> OpenJiuwenReactAgentBuilder
  -> ReActAgent
```

DeepAgent 路径：

```text
deepagent.yaml
  -> AgentYamlLoader
  -> AgentYamlEnvironmentResolver
  -> AgentYamlParser
  -> AgentSpec
  -> OpenJiuwenDeepAgentBuilder
  -> DeepAgentConfig + Workspace
  -> HarnessFactory.createDeepAgent(...)
  -> DeepAgent
```

`AgentFactoryBuilder` 通过 YAML 字段校验目标原生类型：

| 工厂方法 | 必需 YAML 字段 |
|---|---|
| `toReactAgent` | `framework.type: openjiuwen`，`framework.agent: react` |
| `toDeepAgent` | `framework.type: openjiuwen`，`framework.agent: deepagent` |

其他组合会抛出 `UnsupportedFrameworkException`。

## 4. YAML 契约

YAML schema 固定为 `ascend-agent/v1`。

| YAML 字段 | 必填 | 行为 |
|---|---:|---|
| `schema` | 是 | 必须等于 `ascend-agent/v1` |
| `name` | 是 | agent id，映射到 OpenJiuwen `AgentCard.id` |
| `displayName` | 否 | 映射到 `AgentCard.name`，默认使用 `name` |
| `description` | 是 | 映射到 `AgentCard.description` |
| `framework.type` | 是 | 当前只支持 `openjiuwen` |
| `framework.agent` | 是 | `react` 或 `deepagent` |
| `framework.options` | 否 | 保存为 map，由所选 builder 消费 |
| `model` | 是 | 模型客户端、backend 和请求参数 |
| `prompt` | 否 | 由 inline text 和/或 `agentMd` 组成 system prompt |
| `skills.sources` | 否 | 文件系统 skill 来源 |
| `tools` | 否 | Java static method tool 或 HTTP tool |
| `rails` | 否 | class rail 或 function rail |
| `mcps` | 否 | 映射到 DeepAgent MCP server config |

当前 parser 忽略未知顶层 YAML 字段。未知 `framework.options` key 会保留在 options map 中；除非所选 builder 消费，否则不会产生行为。

环境变量占位符使用 `${ENV_NAME}`。缺少环境变量时，YAML 加载阶段失败。

## 5. 模型映射

示例 YAML：

```yaml
model:
  provider: OpenAI
  name: deepseek-chat
  baseUrl: https://api.deepseek.com
  apiKey: ${DEEPSEEK_API_KEY}
  sslVerify: true
  timeout: 45s
  maxRetries: 2
  headers:
    X-Tenant: demo
  request:
    temperature: 0.2
    topP: 0.8
    maxTokens: 1024
    stop: END
    seed: 7
```

ReAct 映射：

- `provider`、`apiKey`、`baseUrl`、`name` 和 headers 写入 OpenJiuwen model client 配置。
- `request.temperature`、`topP`、`maxTokens`、`stop`、`seed` 写入 `ModelRequestConfig`。
- 额外 request 字段保留到 OpenJiuwen request config extra fields。

DeepAgent 映射：

- `DeepAgentConfig.model` 接收 `model`、`temperature`、`top_p`、`max_tokens`、`stop`、`seed` 和 request extras。
- `DeepAgentConfig.backend` 接收 `provider`、`api_key`、`baseUrl`、`verifySsl`、headers、可选 timeout、可选 max retry count。
- duration 会归一为秒，例如 `500ms` 变为 `0.5`。

`model.sslVerify` 只影响模型 client/backend TLS 行为，不配置 HTTP tool 的 TLS 策略。

## 6. 框架选项

ReAct options：

| Option | 默认值 | OpenJiuwen 目标 |
|---|---|---|
| `maxIterations` | `5` | `ReActAgentConfig.configureMaxIterations(...)` |
| `sysOperationId` | `name` | `ReActAgentConfig.sysOperationId` |

DeepAgent options：

| Option | 默认值 | OpenJiuwen 目标 |
|---|---|---|
| `maxIterations` | `15` | `DeepAgentConfig.maxIterations` |
| `skillMode` | `all` | `DeepAgentConfig.skillMode` |
| `workspacePath` | `./` | `DeepAgentConfig.workspacePath` 和 `Workspace.rootPath` |
| `language` | `cn` | `DeepAgentConfig.language` 和 `Workspace.language` |
| `enableTaskLoop` | `false` | `DeepAgentConfig.enableTaskLoop` |
| `enableTaskPlanning` | `false` | `DeepAgentConfig.enableTaskPlanning` |
| `completionTimeout` | 空 | `DeepAgentConfig.completionTimeout` |

当前 DeepAgent builder 会同时把 workspace path 和 language 写入 `DeepAgentConfig` 与显式 `Workspace`，两处值由同一份 YAML 计算得出。

## 7. Prompt 加载

Prompt YAML 可以使用 inline `system`、文件型 `agentMd`，也可以两者同时使用：

```yaml
prompt:
  agentMd: ./AGENT.md
  system: |
    You are an order assistant.
```

规则如下：

- `agentMd` 按 YAML 文件所在目录解析相对路径。
- 文件内容按原文读取。
- 两个字段同时存在时，最终 system prompt 为 `agentMd + "\n\n" + system`。
- 两个字段都不存在时，system prompt 是空字符串。
- 文件读取失败会导致 YAML 加载失败。

## 8. 工具解析

Tool 声明支持对象形式和 shorthand 形式。

对象形式：

```yaml
tools:
  - name: queryOrder
    description: Query an order.
    inputSchema:
      type: object
    ref:
      type: file
      class: com.example.QueryOrderTool
      method: query
```

Shorthand：

```yaml
ref: "file:com.example.QueryOrderTool#query"
ref: "http:https://api.example.com/orders"
```

resolver 顺序固定：

1. 通过 `AgentFactoryBuilder.toolResolver(...)` 注册的自定义 resolver。
2. `JavaFileToolResolver`。
3. `HttpToolResolver`。

第一个 `supports(scheme)` 返回 true 的 resolver 负责处理该 tool。自定义 resolver 可以通过支持相同 scheme 覆盖内置 `file` 或 `http` 解析。

### 8.1 Java 静态方法工具

`file` scheme 表示 classpath Java static method，不是源码文件加载器。

必需方法签名：

```java
public static Object query(Map<String, Object> inputs)
```

`class` 和 `method` 必填。该 scheme 不支持 `path`。缺少类、缺少方法、方法不是 static、方法不可访问或方法调用失败，都会以 SDK 异常暴露。

### 8.2 HTTP 工具

HTTP tool YAML：

```yaml
ref:
  type: http
  url: https://api.example.com/orders
  method: POST
  headers:
    X-Tenant: demo
  timeout: 30s
  followRedirects: false
  maxResponseBytes: 1048576
  exposeErrorBody: false
```

当前行为如下：

| 字段 | 默认值 | 行为 |
|---|---|---|
| `url` | 无 | 必填，并按 URI 做语法校验 |
| `method` | `POST` | 转大写；GET/HEAD/DELETE 把 inputs 放入 query params，其他方法发送 JSON body |
| `headers` | `{}` | 转为 `Map<String,String>` |
| `timeout` | `30s` | 必须是正 duration |
| `followRedirects` | `false` | `false` 使用 `HttpClient.Redirect.NEVER`，`true` 使用 `NORMAL` |
| `maxResponseBytes` | `1048576` | response 受流式大小限制，超过限制后关闭 |
| `exposeErrorBody` | `false` | 非 2xx 错误默认隐藏 body；开启后展示截断 preview |

当 `content-type` 包含 `json` 时，响应按 JSON 解码。非法 JSON 会退回原始文本。非 JSON 响应返回原始文本。

已发布 SDK 不执行 URL allowlist、SSRF 检查、内网地址拦截或 scheme allowlist。声明 HTTP tool 的 YAML 必须由嵌入应用信任。

## 9. 技能加载

当前只支持文件系统 skill source：

```yaml
skills:
  sources:
    - ../skills/order-analysis
    - type: filesystem
      path: ../skills/report-writing
```

规则如下：

- source 目录直接包含 `SKILL.md` 时，该目录是一个 skill。
- source 目录没有直接 `SKILL.md` 时，该目录视为 skill root；每个包含 `SKILL.md` 的子目录是一个 skill，并按目录名排序。
- source 目录同时包含直接 `SKILL.md` 和子 skill 目录时快速失败。
- skill 名称来自目录名。
- 重复 skill 名称快速失败，并在错误中包含冲突路径。

ReAct 对每个 skill 目录调用 `agent.registerSkill(...)`。

DeepAgent 将 skill root 目录写入 `DeepAgentConfig.skillDirectories`，由 OpenJiuwen 原生 DeepAgent skill 工具加载。

## 10. Rail 映射

Class rail：

```yaml
rails:
  - name: orderAudit
    type: class
    class: com.example.OrderAuditRail
    priority: 100
    options:
      mode: strict
```

Function rail：

```yaml
rails:
  - name: example-after-tool-call
    type: function
    event: afterToolCall
    class: com.example.ExampleRailHooks
    method: afterToolCall
```

支持的 function event：

- `beforeModelCall`
- `afterModelCall`
- `beforeToolCall`
- `afterToolCall`

支持的 function signature：

```java
public static void afterToolCall(AgentCallbackContext context)
public static Map<String, Object> afterToolCall(Map<String, Object> extra)
```

ReAct 将映射后的 rails 注册到 `ReActAgent`。DeepAgent 将映射后的 rails 添加到 `DeepAgentConfig.rails`。builder 级 `.rail(...)` 会追加到 YAML rails 之后。

## 11. MCP 映射

`mcps[]` 只映射到 DeepAgent：

```yaml
mcps:
  - serverId: orders
    serverName: order-mcp
    serverPath: http://localhost:9000/sse
    clientType: sse
    params:
      tenant: test
    authHeaders:
      Authorization: Bearer token
    authQueryParams:
      token: query-token
```

| 字段 | 必填 | 默认值 | 行为 |
|---|---:|---|---|
| `serverId` | 否 | random UUID | `McpServerConfig.serverId` |
| `serverName` | 是 | 无 | `McpServerConfig.serverName` |
| `serverPath` | 是 | 无 | `McpServerConfig.serverPath` |
| `clientType` | 否 | `sse` | `McpServerConfig.clientType` |
| `params` | 否 | `{}` | 原始 map |
| `authHeaders` | 否 | `{}` | `Map<String,String>` |
| `authQueryParams` | 否 | `{}` | `Map<String,String>` |

ReAct 当前不消费 `mcps[]`。

## 12. 示例工程

可运行示例位于 `examples/agent-sdk-example/`：

```text
examples/agent-sdk-example/
  openjiuwen/
    agent.yaml
    deepagent.yaml
  skills/
    order-analysis/SKILL.md
    report-writing/SKILL.md
  scripts/
    run-openjiuwen.sh
    run-openjiuwen.ps1
  src/main/java/com/huawei/ascend/agentsdk/example/
    OpenJiuwenReactAgentSdkExample.java
    OpenJiuwenDeepAgentSdkExample.java
    OpenJiuwenExampleSupport.java
    tools/
    rails/
```

两份 YAML 都声明：

- Java tools：`readFile`、`queryOrder`、`calcDiscount`
- 文件系统 skills：`order-analysis`、`report-writing`
- function rails：`afterModelCall`、`afterToolCall`
- 通过 `${DEEPSEEK_API_KEY}` 配置的 DeepSeek 兼容 OpenAI 模型

示例在进程内验证模型、工具、skill、rail 行为；只有 proof counters 和 markers 都存在时，才打印 `verification: PASS`。

## 13. 验证

`agent-sdk` 通过自身模块 POM 验证：

```bash
mvn -f agent-sdk/pom.xml test
mvn -f agent-sdk/pom.xml -DskipTests install
mvn -f examples/agent-sdk-example/pom.xml compile
```

运行 live example 需要真实模型 key：

```bash
export DEEPSEEK_API_KEY="..."
bash examples/agent-sdk-example/scripts/run-openjiuwen.sh react
bash examples/agent-sdk-example/scripts/run-openjiuwen.sh deepagent
```

PowerShell：

```powershell
$env:DEEPSEEK_API_KEY = "..."
.\examples\agent-sdk-example\scripts\run-openjiuwen.ps1 -Agent react
.\examples\agent-sdk-example\scripts\run-openjiuwen.ps1 -Agent deepagent
```

相关测试：

| 测试 | 覆盖 |
|---|---|
| `AgentFactoryTest` | YAML 到 ReAct/DeepAgent、DeepAgent P1 options、model/rail 映射、builder rail 注入 |
| `AgentYamlLoaderTest` | YAML 加载与环境变量替换 |
| `ToolResolverTest` | Java 与 HTTP tool resolver 行为 |
| `OpenJiuwenRailMapperTest` | Class 与 function rail 映射 |
| `HttpToolExecutorTest` | HTTP tool 执行、响应大小限制、错误 body 行为 |
| `SkillSourceLoaderTest` | 文件系统 skill source 展开与重复处理 |

## 14. 当前边界

| 边界 | 当前行为 |
|---|---|
| Framework 支持 | 只支持 OpenJiuwen |
| 原生 agent 类型 | `react` 和 `deepagent` |
| Runtime 托管 | `agent-sdk` 不提供托管能力 |
| YAML schema | 只支持 `ascend-agent/v1` |
| HTTP tool 安全 | 只做 URI 语法校验和响应大小限制；信任策略由嵌入应用负责 |
| ReAct MCP | 未实现 |
| DeepAgent MCP | `mcps[]` 映射到 `DeepAgentConfig.mcps` |
| Tool resolver null 处理 | `toolResolver(null)` 当前会进入列表，并在后续 resolver 使用触达 null 时失败 |
| Rail options | 已解析到 `RailSpec`；class rail 对象初始化当前不消费 `options` |

---

## 15. 能力规格

### 15.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| YAML 文件加载 | 已落地 | `AgentYamlLoader` 从 `Path` 读取 YAML |
| 环境变量替换 | 已落地 | `${ENV_NAME}` 在 load 阶段解析，缺失时报错 |
| schema 校验 | 已落地 | 只接受 `ascend-agent/v1` |
| ReActAgent 装配 | 已落地 | `AgentFactory.toReactAgent(path)` 返回 OpenJiuwen `ReActAgent` |
| DeepAgent 装配 | 已落地 | `AgentFactory.toDeepAgent(path)` 返回 OpenJiuwen `DeepAgent` |
| 模型配置映射 | 已落地 | 映射到 ReAct model client/request config 与 DeepAgent model/backend |
| prompt 合并 | 已落地 | `agentMd` 文件内容与 inline `system` 合并 |
| Java static tool | 已落地 | `file:` scheme 解析 classpath static method |
| HTTP tool | 已落地 | 支持 method、headers、timeout、redirect、response limit、error body 策略 |
| 自定义 tool resolver | 已落地 | builder resolver 优先于内置 resolver |
| 文件系统 skills | 已落地 | 支持单 skill 目录和 skill root 目录 |
| class/function rail | 已落地 | 映射到 ReAct rails 或 DeepAgent config rails |
| DeepAgent MCP | 已落地 | `mcps[]` 映射到 `DeepAgentConfig.mcps` |
| SDK 示例 | 已落地 | `examples/agent-sdk-example/` 覆盖 ReAct 与 DeepAgent |

### 15.2 显式排除

| 排除项 | 原因 | 当前替代 |
|---|---|---|
| A2A endpoint 托管 | SDK 是装配层，不是 runtime 服务层 | 使用 `agent-runtime` handler 托管 |
| 多框架统一抽象 | 当前落地只支持 OpenJiuwen | 后续新增框架需新增 builder 与 schema 映射 |
| 动态加载 Java 源码文件 | `file` scheme 只表示 classpath static method | 将工具类编译进应用 classpath |
| HTTP tool SSRF 防护 | SDK 无法知道部署方网络信任边界 | 嵌入应用只加载受信任 YAML 或在自定义 resolver 中加策略 |
| ReAct MCP 装配 | 当前 OpenJiuwen ReAct builder 不消费 `mcps[]` | DeepAgent 使用 `mcps[]`，ReAct 使用 runtime MCP installer |
| YAML 到 A2A Agent Card 自动发布 | 发布属于 runtime access 层职责 | 在服务应用中配置 `agent-runtime.access.a2a.agent-card` |
| Workflow YAML 装配 | 本特性只覆盖 ReActAgent 与 DeepAgent | Workflow adapter 由 runtime handler 托管 Java 构建的 Workflow |

### 15.3 行为承诺

- 必须：`schema` 缺失或不等于 `ascend-agent/v1` 时加载失败。
- 必须：`framework.type` 不是 `openjiuwen` 时构造失败。
- 必须：`toReactAgent` 只接受 `framework.agent: react`。
- 必须：`toDeepAgent` 只接受 `framework.agent: deepagent`。
- 必须：环境变量缺失在 YAML load 阶段失败，不延迟到模型调用阶段。
- 必须：prompt 文件读取失败时加载失败。
- 必须：重复 skill name 快速失败。
- 必须：HTTP response 超过 `maxResponseBytes` 时中止读取并抛出 SDK 异常。
- 允许：未知顶层字段被忽略。
- 允许：未知 `framework.options` key 留在 options map 中但不产生行为。
- 允许：自定义 resolver 覆盖内置 `file` / `http` scheme。

---

## 16. 模块结构

### 16.1 包结构

```text
agent-sdk/src/main/java/com/huawei/ascend/agentsdk/
├── factory/
│   ├── AgentFactory.java                 # 静态入口
│   └── AgentFactoryBuilder.java          # resolver/rail 扩展入口
├── spec/
│   ├── AgentSpec.java                    # YAML 解析后的领域模型
│   ├── model/                            # model spec
│   ├── prompt/                           # prompt spec
│   ├── skill/                            # skill source loader
│   ├── tool/                             # tool spec 和 resolver SPI
│   └── yaml/                             # YAML loader/parser/environment resolver
├── adapter/
│   ├── OpenJiuwenModelMapper.java        # model/backend 映射
│   ├── OpenJiuwenRailMapper.java         # class/function rail 映射
│   ├── OpenJiuwenMcpMapper.java          # MCP 映射
│   ├── react/OpenJiuwenReactAgentBuilder.java
│   └── deepagent/OpenJiuwenDeepAgentBuilder.java
└── exception/
    └── UnsupportedFrameworkException.java
```

### 16.2 静态关系

```text
AgentFactory
  -> AgentFactoryBuilder
       -> AgentYamlLoader
            -> AgentYamlEnvironmentResolver
            -> AgentYamlParser
                 -> AgentSpec
       -> framework guard
       -> OpenJiuwenReactAgentBuilder / OpenJiuwenDeepAgentBuilder
            -> OpenJiuwenModelMapper
            -> OpenJiuwenRailMapper
            -> ToolResolver chain
            -> SkillSourceLoader
            -> OpenJiuwenMcpMapper (DeepAgent)
```

### 16.3 与 Runtime Adapter 的边界

| 维度 | `agent-sdk` | `agent-runtime` OpenJiuwen handlers |
|---|---|---|
| 配置输入 | YAML 文件 | Spring bean、application.yaml、A2A request |
| 输出对象 | OpenJiuwen 原生对象 | `AgentExecutionResult` stream |
| 运行协议 | 无 HTTP 协议 | A2A JSON-RPC / SSE |
| 远端 A2A tool | 不安装 | runtime 执行前安装 |
| trajectory | 不发 runtime trajectory | handler/rail 发 runtime trajectory |
| cancel | 不管理 task cancel | handler 管理 task cancel |
| Agent Card 发布 | 不负责 | runtime access 层负责 |

---

## 17. 核心流程

### 17.1 ReAct 装配

```text
AgentFactory.toReactAgent(path)
  -> builder().toReactAgent(path)
  -> AgentYamlLoader.load(path)
  -> resolve ${ENV}
  -> AgentYamlParser.parse(root)
  -> guard framework.type=openjiuwen, framework.agent=react
  -> OpenJiuwenReactAgentBuilder.buildAgent(spec)
       -> build AgentCard
       -> build model client config
       -> build request config
       -> resolve tools
       -> register skills
       -> map YAML rails + builder rails
  -> ReActAgent
```

### 17.2 DeepAgent 装配

```text
AgentFactory.toDeepAgent(path)
  -> builder().toDeepAgent(path)
  -> AgentYamlLoader.load(path)
  -> resolve ${ENV}
  -> AgentYamlParser.parse(root)
  -> guard framework.type=openjiuwen, framework.agent=deepagent
  -> OpenJiuwenDeepAgentBuilder.buildAgent(spec)
       -> build AgentCard
       -> build DeepAgentConfig
       -> write model/backend/options/skills/rails/mcps
       -> build Workspace
       -> HarnessFactory.createDeepAgent(card, config, workspace)
  -> DeepAgent
```

### 17.3 Tool resolver 链

```text
ToolSpec(ref.type or shorthand scheme)
  -> custom resolvers in registration order
  -> JavaFileToolResolver
  -> HttpToolResolver
  -> no resolver matched => SDK exception
```

resolver 行为是确定性的。自定义 resolver 注册越早，优先级越高。

### 17.4 Skill source 展开

```text
skills.sources[]
  -> resolve relative to YAML directory
  -> if source/SKILL.md exists:
         load one skill named source directory
     else:
         scan direct child directories with SKILL.md
         sort by directory name
  -> validate no duplicate names
  -> ReAct registerSkill(...) / DeepAgentConfig.skillDirectories
```

---

## 18. 配置模型

### 18.1 完整 YAML 示例

```yaml
schema: ascend-agent/v1
name: order-assistant
displayName: Order Assistant
description: 订单处理助手

framework:
  type: openjiuwen
  agent: deepagent
  options:
    maxIterations: 12
    skillMode: all
    workspacePath: ./target/order-workspace
    language: cn
    enableTaskLoop: true
    enableTaskPlanning: true

model:
  provider: OpenAI
  name: deepseek-chat
  baseUrl: https://api.deepseek.com
  apiKey: ${DEEPSEEK_API_KEY}
  sslVerify: true
  timeout: 45s
  maxRetries: 2
  headers:
    X-Tenant: demo
  request:
    temperature: 0.2
    topP: 0.8
    maxTokens: 1024

prompt:
  agentMd: ./AGENT.md
  system: |
    你是订单处理助手，输出必须简洁。

tools:
  - name: queryOrder
    description: 查询订单
    inputSchema:
      type: object
    ref:
      type: file
      class: com.example.OrderTools
      method: query

skills:
  sources:
    - ../skills/order-analysis

rails:
  - name: audit-after-tool
    type: function
    event: afterToolCall
    class: com.example.OrderRails
    method: afterToolCall

mcps:
  - serverName: order-mcp
    serverPath: http://localhost:9000/sse
    clientType: sse
```

### 18.2 字段责任表

| 字段 | 解析位置 | 消费位置 |
|---|---|---|
| `schema` | `AgentYamlParser` | schema guard |
| `name` / `displayName` / `description` | `AgentYamlParser` | OpenJiuwen `AgentCard` |
| `framework` | `AgentYamlParser` | `AgentFactoryBuilder` guard 与 builder options |
| `model` | `AgentYamlParser` | `OpenJiuwenModelMapper` |
| `prompt` | `AgentYamlLoader` / parser | ReAct system prompt 或 DeepAgentConfig system prompt |
| `tools` | parser | selected builder 的 resolver 链 |
| `skills.sources` | parser | `SkillSourceLoader` |
| `rails` | parser | `OpenJiuwenRailMapper` |
| `mcps` | parser | `OpenJiuwenMcpMapper`，仅 DeepAgent 消费 |

---

## 19. 对外呈现与用户场景

### 19.1 应用内直接构造 ReActAgent

```java
Path yaml = Path.of("config/agent.yaml");
ReActAgent agent = AgentFactory.toReactAgent(yaml);
```

适用场景：

- 应用已有自己的执行入口。
- 只需要 OpenJiuwen 原生对象。
- 不需要 A2A 托管。

### 19.2 应用内直接构造 DeepAgent

```java
Path yaml = Path.of("config/deepagent.yaml");
DeepAgent agent = AgentFactory.toDeepAgent(yaml);
```

适用场景：

- 需要 DeepAgent workspace、skillDirectories、MCP 配置。
- 应用自己管理执行时机。
- 后续可手动接入 `OpenJiuwenDeepAgentRuntimeHandler`。

### 19.3 与 agent-runtime 组合

```java
public final class YamlDeepAgentHandler extends OpenJiuwenDeepAgentRuntimeHandler {
    private final Path yamlPath;

    public YamlDeepAgentHandler(Path yamlPath) {
        super("yaml-deep-agent");
        this.yamlPath = yamlPath;
    }

    @Override
    protected DeepAgent createOpenJiuwenDeepAgent(AgentExecutionContext context) {
        return AgentFactory.toDeepAgent(yamlPath);
    }
}
```

该组合方式把 YAML 装配交给 `agent-sdk`，把 A2A endpoint、远端工具、trajectory 和 cancel 交给 `agent-runtime`。

---

## 20. 错误处理

| 错误场景 | 触发条件 | 行为 |
|---|---|---|
| YAML 文件不存在 | `Path` 不可读 | load 阶段抛出 SDK 异常 |
| schema 错误 | `schema` 不是 `ascend-agent/v1` | parser 拒绝 |
| 环境变量缺失 | `${ENV}` 未定义 | load 阶段失败 |
| framework 不支持 | type/agent 不匹配目标方法 | `UnsupportedFrameworkException` |
| prompt 文件读取失败 | `agentMd` 路径不存在或不可读 | load 阶段失败 |
| Java tool 类不存在 | `class` 不在 classpath | tool resolver 抛异常 |
| Java tool 方法非法 | 方法缺失、非 static、签名不匹配 | tool resolver 抛异常 |
| HTTP URL 非法 | `url` 缺失或 URI 语法错误 | HTTP resolver 抛异常 |
| HTTP response 超限 | 响应体超过 `maxResponseBytes` | HTTP executor 中止并抛异常 |
| skill source 冲突 | 同名 skill 重复 | `SkillSourceLoader` 快速失败 |
| MCP 配置缺失 | `serverName` 或 `serverPath` 缺失 | parser 拒绝 |

### 20.1 排障信号

| 现象 | 检查点 |
|---|---|
| `toReactAgent` 报 framework 不匹配 | 检查 `framework.agent` 是否为 `react` |
| `toDeepAgent` 报 framework 不匹配 | 检查 `framework.agent` 是否为 `deepagent` |
| 模型 key 没生效 | 检查 `${ENV}` 是否在启动进程环境中定义 |
| Java tool 找不到 | 检查工具类是否已编译进运行 classpath |
| HTTP tool 返回纯文本 | 检查响应 `content-type` 是否包含 `json` |
| DeepAgent skills 不生效 | 检查 source 目录结构是否符合 `SKILL.md` 规则 |
| MCP 不生效 | 检查当前 agent 是否是 DeepAgent，ReAct 不消费 `mcps[]` |

---

## 21. 验证矩阵

| 验证命令 | 覆盖目标 |
|---|---|
| `mvn -f agent-sdk/pom.xml test` | SDK parser、factory、resolver、rail、HTTP、skill 单测 |
| `mvn -f agent-sdk/pom.xml -DskipTests install` | 安装当前 SDK snapshot，供示例模块解析 |
| `mvn -f examples/agent-sdk-example/pom.xml compile` | 示例工程对 SDK API、YAML、工具类、rails 的编译级验证 |
| `bash examples/agent-sdk-example/scripts/run-openjiuwen.sh react` | ReAct live model/tool/skill/rail 验证 |
| `bash examples/agent-sdk-example/scripts/run-openjiuwen.sh deepagent` | DeepAgent live model/tool/skill/rail 验证 |

live 脚本依赖真实 `DEEPSEEK_API_KEY`，不属于默认离线验证。
