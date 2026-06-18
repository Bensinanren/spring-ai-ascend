# Agent SDK 设计方案

## 1. 模块定位

`agent-sdk` 是一个面向客户侧配置的轻量装配 SDK。当前实现只支持 OpenJiuwen，核心职责是把 `ascend-agent/v1` YAML 装配成 OpenJiuwen 原生 agent 实例：

1. 读取 YAML 并解析环境变量占位符。
2. 解析为内部 `AgentSpec`。
3. 按调用入口显式构造 OpenJiuwen `ReActAgent` 或 `DeepAgent`。
4. 将 YAML 声明的 model、prompt、tool、skill、rail、MCP 映射到 OpenJiuwen 对应 API。
5. 保持 `agent-runtime` / `agent-service` 解耦，不承担托管、路由、A2A 或 Spring Boot 运行时职责。

当前 `agent-sdk` 是独立 Maven module 目录，不在根 reactor `<modules>` 中。根 `architecture/facts/generated/*` 当前没有 `agent-sdk` 事实条目，因此本文档的事实来源是 `agent-sdk` 源码、测试、`agent-sdk/pom.xml` 和 `examples/agent-sdk-example`。

依赖边界：

| 依赖 | 作用 |
|---|---|
| `com.openjiuwen:agent-core-java:0.1.12-jdk17` | 提供 `ReActAgent`、`DeepAgent`、`DeepAgentConfig`、`HarnessFactory`、`Tool`、`LocalFunction`、`AgentRail`、`McpServerConfig` 等 OpenJiuwen API。 |
| `snakeyaml` | 读取 YAML。 |
| `jackson-databind` | HTTP tool 请求体序列化、响应体 JSON 解码。 |
| JUnit 5 / AssertJ | SDK 单元测试。 |

SDK 继承根 parent，按 JDK 21 编译。OpenJiuwen 依赖使用 JDK17 变体是依赖兼容选择，不改变 SDK 编译目标。

## 2. 对外 API

`AgentFactory` 只暴露显式转换入口，不提供返回 `Object` 的泛化工厂：

```java
public final class AgentFactory {
    public static ReActAgent toReactAgent(Path yamlPath);
    public static DeepAgent toDeepAgent(Path yamlPath);
    public static AgentFactoryBuilder builder();
}
```

`AgentFactoryBuilder` 提供两个代码级扩展点：

```java
ReActAgent reactAgent = AgentFactory.builder()
    .toolResolver(customResolver)
    .rail(customRail)
    .toReactAgent(Path.of("openjiuwen/agent.yaml"));

DeepAgent deepAgent = AgentFactory.builder()
    .toolResolver(customResolver)
    .rail(customRail)
    .toDeepAgent(Path.of("openjiuwen/deepagent.yaml"));
```

边界说明：

- 返回类型是 OpenJiuwen 原生 `ReActAgent` / `DeepAgent`，不是 framework-neutral runtime abstraction。
- `framework.type` 只支持 `openjiuwen`。
- `framework.agent` 只支持 `react` 和 `deepagent`。
- `toolResolver(...)` 当前没有 `requireNonNull`，传入 `null` 会在后续 resolver stream 中失败；`rail(...)` 已做 `requireNonNull`。

## 3. 转换流程

ReActAgent：

```text
agent.yaml
  -> AgentYamlLoader
  -> AgentYamlEnvironmentResolver
  -> AgentYamlParser
  -> AgentSpec
  -> OpenJiuwenReactAgentBuilder
  -> ReActAgent
```

DeepAgent：

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

Resolver 语义：

- custom tool resolver 注册在内置 resolver 之前。
- ReAct 和 DeepAgent 路径都使用 `stream().filter(...).findFirst()`，即 first-match-wins。
- custom resolver 可以覆盖内置 `file` / `http` scheme；命中第一个 resolver 后不会继续尝试。
- 内置 resolver 顺序为 `JavaFileToolResolver`、`HttpToolResolver`。

## 4. YAML Schema

YAML schema 固定为 `ascend-agent/v1`。这是客户侧 agent 装配格式，不是通用工作流 DSL。

### 4.1 顶层字段

| YAML 字段 | AgentSpec 字段 | 当前行为 |
|---|---|---|
| `schema` | `schema` | 必填，必须为 `ascend-agent/v1`。 |
| `name` | `name` | 必填，映射为 `AgentCard.id`；生产建议全局唯一，ReAct 路径会用它参与 OpenJiuwen 全局 tool 注册。 |
| `displayName` | `displayName` | 可选，默认取 `name`，映射为 `AgentCard.name`。 |
| `description` | `description` | 必填，映射为 `AgentCard.description`。 |
| `framework.type` | `frameworkType` | 必填，当前只支持 `openjiuwen`。 |
| `framework.agent` | `agentType` | 必填，当前支持 `react` / `deepagent`。 |
| `framework.options` | `frameworkOptions` | 可选，原样存为 map，由对应 builder 读取子集。 |
| `model` | `modelSpec` | 必填；见 §5。 |
| `prompt` | `promptSpec` | 可选；缺省时 system prompt 为空字符串。 |
| `skills.sources` | `skillSpecs` | 可选；只支持 filesystem source。 |
| `tools` | `toolSpecs` | 可选；tool name 必须唯一。 |
| `rails` | `railSpecs` | 可选；rail name 必须唯一。 |
| `mcps` | `mcpSpecs` | 可选；当前映射到 DeepAgent config。 |

未知顶层字段当前不会被显式拒绝；未知 `framework.options` 会保留在 map 中，但未被 builder 消费的字段不应被理解为生效。

### 4.2 环境变量

YAML 中的 `${ENV_NAME}` 会由 `AgentYamlEnvironmentResolver` 替换为当前进程环境变量值。环境变量不存在时抛 `ValidationException`。示例使用 `${DEEPSEEK_API_KEY}`，不要把真实 key 写入 YAML 或代码。

### 4.3 prompt

`prompt` 支持两种来源：

```yaml
prompt:
  agentMd: ./AGENT.md
  system: |
    你是一个订单助手。
```

当前行为：

- `agentMd` 相对 YAML 所在目录解析。
- `agentMd` 文件内容原样读取，不 trim。
- `agentMd` 与 `system` 同时存在时，按 `agentMd + "\n\n" + system` 拼接。
- 只填其一时直接作为最终 system prompt。
- 两者都不填时 system prompt 是空字符串。
- 文件读取失败会在 YAML load 阶段 fail-fast。

## 5. Model

### 5.1 YAML 字段

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

| 字段 | 必填 | 默认 | 当前行为 |
|---|---:|---|---|
| `provider` | 否 | `openai-compatible` | 映射到 OpenJiuwen client provider / DeepAgent backend provider。 |
| `name` | 是 | - | ReAct `modelName`；DeepAgent model map 的 `model`。 |
| `baseUrl` | 是 | - | ReAct `apiBase`；DeepAgent backend map 的 `baseUrl`。 |
| `apiKey` | 是 | - | ReAct / DeepAgent backend 均使用。 |
| `sslVerify` | 否 | `true` | 仅控制模型 client SSL 校验，不影响 HTTP tool。非布尔值 fail-fast。 |
| `headers` | 否 | `{}` | 转为 `Map<String,String>`；null value 被跳过。 |
| `timeout` | 否 | OpenJiuwen 默认 | 支持裸数字秒、`ms`、`s`、`m`、ISO-8601；必须为正。 |
| `maxRetries` | 否 | OpenJiuwen 默认 | 整数。 |
| `request.temperature` | 否 | OpenJiuwen 默认 | ReAct 映射到 `ModelRequestConfig.temperature`；DeepAgent map key 为 `temperature`。 |
| `request.topP` | 否 | OpenJiuwen 默认 | ReAct `topP`；DeepAgent `top_p`。 |
| `request.maxTokens` | 否 | OpenJiuwen 默认 | ReAct `maxTokens`；DeepAgent `max_tokens`。 |
| `request.stop` | 否 | OpenJiuwen 默认 | 字符串 stop。 |
| `request.seed` | 否 | OpenJiuwen 默认 | 整数 seed。 |
| `request.*` 其它字段 | 否 | `{}` | 保留在 `extra`；ReAct 进 `ModelRequestConfig.extraFields`，DeepAgent 合并进 model map。 |

不支持 `sslCert` / `backend.sslCert` / `model.sslCert` YAML 字段。

### 5.2 ReAct 映射

`OpenJiuwenReactAgentBuilder` 会同时设置 ReAct config 的扁平字段和 config 对象：

- `modelProvider`、`apiKey`、`apiBase`、`modelName`、`customHeaders`：供 OpenJiuwen ReAct 内部 diff / reset 逻辑使用。
- `ModelClientConfig`：由 `OpenJiuwenModelMapper.toModelClientConfig(...)` 构造。
- `ModelRequestConfig`：由 `OpenJiuwenModelMapper.toModelRequestConfig(...)` 构造。

### 5.3 DeepAgent 映射

DeepAgent 使用 map：

- `DeepAgentConfig.model`：包含 `model`、`temperature`、`top_p`、`max_tokens`、`stop`、`seed` 和 request extra。
- `DeepAgentConfig.backend`：包含 `provider`、`apiKey`、`baseUrl`、`verifySsl`、`headers`、可选 `timeout`、可选 `max_retries`。

`Duration` 会转为 double 秒，例如 `500ms` 转为 `0.5`。

## 6. Framework Options

### 6.1 ReAct

```yaml
framework:
  type: openjiuwen
  agent: react
  options:
    maxIterations: 6
    sysOperationId: sdk-openjiuwen-example-agent
```

| option | 默认 | OpenJiuwen 落点 |
|---|---|---|
| `maxIterations` | `5` | `ReActAgentConfig.configureMaxIterations(...)` |
| `sysOperationId` | `name` | `ReActAgentConfig.sysOperationId` |

### 6.2 DeepAgent

```yaml
framework:
  type: openjiuwen
  agent: deepagent
  options:
    maxIterations: 8
    skillMode: all
    workspacePath: ./
    language: cn
    enableTaskLoop: false
    enableTaskPlanning: false
    completionTimeout: 120
```

| option | 默认 | OpenJiuwen 落点 |
|---|---|---|
| `maxIterations` | `15` | `DeepAgentConfig.maxIterations` |
| `skillMode` | `all` | `DeepAgentConfig.skillMode` |
| `workspacePath` | `./` | `DeepAgentConfig.workspacePath` 与显式 `Workspace.rootPath` |
| `language` | `cn` | `DeepAgentConfig.language` 与显式 `Workspace.language` |
| `enableTaskLoop` | `false` | `DeepAgentConfig.enableTaskLoop` |
| `enableTaskPlanning` | `false` | `DeepAgentConfig.enableTaskPlanning` |
| `completionTimeout` | `null` | `DeepAgentConfig.completionTimeout` |

当前 DeepAgent builder 同时构造 `DeepAgentConfig` 和 `Workspace`，并用同一份 `workspacePath` / `language` 填二者。这避免了值漂移，但仍与 OpenJiuwen `HarnessFactory` 的默认 workspace 解析存在轻微重复，见 §13。

## 7. Tool

### 7.1 YAML 形态

对象形式：

```yaml
tools:
  - name: queryOrder
    description: 查询本地示例订单状态。
    inputSchema:
      type: object
      properties:
        orderId:
          type: string
      required:
        - orderId
    ref:
      type: file
      class: com.example.tools.QueryOrderTool
      method: query
```

字符串简写：

```yaml
ref: "file:com.example.tools.QueryOrderTool#query"
ref: "http:https://api.example.com/orders"
```

当前 parser 展开规则：

| shorthand | 展开 |
|---|---|
| `file:com.example.Class#method` | `{ type: file, class: com.example.Class, method: method }` |
| `http:https://example.com` | `{ type: http, url: https://example.com }` |
| 其它 scheme | `{ type: <scheme>, value: <rawValue> }`，需 custom resolver 自行解释。 |

约束：

- `tools[].name` 必须唯一，重复时 YAML load 阶段 fail-fast。
- `tools[].ref` 必填。
- `inputSchema` 作为 OpenJiuwen tool card 的参数描述透传，不做 JSON Schema validator。

### 7.2 Java file tool

`file` scheme 实际表示“classpath 上的 Java 静态方法”，不是从源码文件加载执行。

| 属性 | 当前行为 |
|---|---|
| `class` | 必填，全限定类名。 |
| `method` | 必填，运行时通过 `getMethod(methodName, Map.class)` 查找。 |
| `path` | 不支持；出现时 `JavaFileToolResolver` 抛 `UnsupportedToolRefException`。 |

方法要求：

```java
public static Object query(Map<String, Object> inputs)
```

方法不存在、类不存在、方法不可访问、非 static 或执行抛错，都会以 SDK 异常形式暴露。签名校验发生在 tool 执行阶段，不是在 YAML load 阶段。

### 7.3 HTTP tool

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

| 属性 | 默认 | 当前行为 |
|---|---|---|
| `url` | - | 必填，只做 URI 语法解析；当前不限制 scheme、host、内网地址或 allowlist。 |
| `method` | `POST` | 转大写；GET/HEAD/DELETE 参数进 query，其它方法发 JSON body。 |
| `headers` | `{}` | 转 `Map<String,String>`。 |
| `timeout` | `30s` | 支持裸数字秒、`ms`、`s`、`m`、ISO-8601；必须为正。 |
| `followRedirects` | `false` | 默认 `HttpClient.Redirect.NEVER`；为 true 时使用 `NORMAL`。 |
| `maxResponseBytes` | `1048576` | 必须为正；响应体用 `ofInputStream()` 流式读取，超过上限立即抛错并关闭流。 |
| `exposeErrorBody` | `false` | 非 2xx 默认只暴露状态；为 true 时错误消息附带最多 500 字符 body preview。 |

响应解码：

- `content-type` 包含 `json` 且 body 非空时，尝试解析为 Java 对象。
- JSON 解析失败时返回原始文本，不抛错。
- 非 JSON 响应返回原始文本。

安全边界：

- 当前没有 SSRF 防护、host allowlist、内网地址拒访或 scheme 白名单。
- `model.sslVerify` 不影响 HTTP tool 的 TLS 行为。
- HTTP tool 适合可信 YAML 场景；如果 YAML 来自不可信来源，需要在后续版本增加 URL policy 或 SDK 级 policy 注入点。

### 7.4 OpenJiuwen 落点

`OpenJiuwenToolMapper` 将 SDK `ResolvedTool` 包成 OpenJiuwen `LocalFunction`：

- `ToolCard.id/name` 均取 `tools[].name`。
- `ToolCard.description` 取 `tools[].description`。
- `ToolCard.inputParams` 取 `tools[].inputSchema`。
- 执行时按 `ExecutionHandle` 分派到 Java method 或 HTTP executor。

ReAct 注册到两个位置：

1. `agent.getAbilityManager().add(tool.getCard())`
2. `Runner.resourceMgr().addTool(tool, agentId)`

第二个是 OpenJiuwen 全局资源注册副作用，因此生产 YAML 的 `name` 应保持稳定且全局唯一。DeepAgent 路径不直接调用 `Runner.resourceMgr().addTool(...)`，而是把 tools 放入 `DeepAgentConfig.tools`。

## 8. Skill

当前只支持本地 filesystem skill：

```yaml
skills:
  sources:
    - ../skills/order-analysis
    - type: filesystem
      path: ../skills/report-writing
```

`SkillSourceLoader` 支持两种 source 形态：

1. source 目录自身包含 `SKILL.md`：该目录就是一个 skill。
2. source 目录不含直接 `SKILL.md`，但子目录包含 `SKILL.md`：每个子目录是一个 skill，按目录名排序。

如果 source 目录同时包含直接 `SKILL.md` 和子 skill 目录，会 fail-fast，避免“单 skill”和“skill root”语义混用。

其它约束：

- source type 只支持 `filesystem`。
- source 路径相对 YAML 所在目录解析。
- skill name 来自 skill 目录名。
- 所有 source 汇总后做重复 skill name 检测；重复时 fail-fast，错误消息包含冲突路径。

OpenJiuwen 落点：

| Agent | 落点 |
|---|---|
| ReAct | 对每个 skill 目录调用 `agent.registerSkill(skillDirectory)`。 |
| DeepAgent | 将 skill 目录归并为 parent root，写入 `DeepAgentConfig.skillDirectories`，由 OpenJiuwen 原生 skill rail / skill tool 读取。 |

## 9. Rail

Rail 已作为一等扩展点暴露，支持 YAML 注入和 builder 代码级注入。当前没有 trusted mode 概念；直接支持 classpath class 注入，安全策略后续专题处理。

### 9.1 class rail

```yaml
rails:
  - name: orderAudit
    type: class
    class: com.example.agent.OrderAuditRail
    priority: 100
    options:
      mode: strict
```

字段约束：

| 字段 | 当前行为 |
|---|---|
| `name` | 必填，rails 内唯一。 |
| `type` | 可选，默认 `class`。 |
| `class` | 必填，必须在 classpath 上。 |
| `method` | `class` rail 禁止声明。 |
| `priority` | 可选；声明后调用 `AgentRail.setPriority(...)`。 |
| `options` | 解析并保存在 `RailSpec`，当前 class 实例化路径未自动注入 options。 |

实现要求：

- 当前 `OpenJiuwenRailMapper` 要求 class rail 实现 `AgentRail`。
- DeepAgent 路径当前也接受 `AgentRail`；`toDeepAgentRail(...)` 与 `toAgentRail(...)` 目前等价，保留为未来 DeepAgent 专属校验入口。
- class rail 通过无参构造函数实例化。

### 9.2 function rail

函数 rail 是轻量 hook 形式：

```yaml
rails:
  - name: example-after-tool-call
    type: function
    event: afterToolCall
    class: com.example.agent.ExampleRailHooks
    method: afterToolCall
```

事件白名单固定为：

- `beforeModelCall`
- `afterModelCall`
- `beforeToolCall`
- `afterToolCall`

当前不开放 `beforeInvoke`、`afterInvoke`、`onModelException`、`onToolException`。

方法签名只支持两种：

```java
public static void afterToolCall(AgentCallbackContext context)
```

```java
public static Map<String, Object> afterToolCall(Map<String, Object> extra)
```

语义：

- `AgentCallbackContext -> void` 用于审计、日志、计数，方法可直接读取 callback context。
- `Map -> Map` 用于轻量改写 `context.extra`；SDK 会传入当前 `extra` 的副本，并用返回值覆盖 `context.extra`，返回 null 时写入空 map。
- 方法必须是 public static。

`FunctionRail` 通过覆写 `AgentRail.getCallbacks()` 返回单事件 callback map，被 OpenJiuwen `AgentCallbackManager` 消费。

### 9.3 OpenJiuwen 落点

| Agent | 落点 |
|---|---|
| ReAct | YAML rails 经 `OpenJiuwenRailMapper.toAgentRail(...)` 后调用 `agent.registerRail(...)`；builder 注入 rails 随后也注册到 agent。 |
| DeepAgent | YAML rails 经 `toDeepAgentRail(...)` 后加入 `DeepAgentConfig.rails`；builder 注入 rails 也加入同一 list。 |

## 10. MCP

MCP 当前通过 YAML `mcps[]` 解析为 `McpSpec`，并在 DeepAgent 路径映射为 OpenJiuwen `McpServerConfig`：

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

| 字段 | 必填 | 默认 | 当前行为 |
|---|---:|---|---|
| `serverId` | 否 | UUID | 为空时生成随机 UUID。 |
| `serverName` | 是 | - | 写入 `McpServerConfig.serverName`。 |
| `serverPath` | 是 | - | 写入 `McpServerConfig.serverPath`。 |
| `clientType` | 否 | `sse` | 写入 `McpServerConfig.clientType`。 |
| `params` | 否 | `{}` | 原样 map。 |
| `authHeaders` | 否 | `{}` | 转 `Map<String,String>`。 |
| `authQueryParams` | 否 | `{}` | 转 `Map<String,String>`。 |

当前 ReAct builder 不消费 `mcps[]`。如果需要 ReAct MCP 支持，应单独设计 OpenJiuwen ReAct 的 MCP tool-provider 注册路径。

## 11. Example

示例工程位于：

```text
examples/agent-sdk-example/
  openjiuwen/
    agent.yaml
    deepagent.yaml
  skills/
    order-analysis/SKILL.md
    report-writing/SKILL.md
  scripts/
    run-openjiuwen.ps1
    run-openjiuwen.sh
  src/main/java/com/huawei/ascend/agentsdk/example/
    OpenJiuwenReactAgentSdkExample.java
    OpenJiuwenDeepAgentSdkExample.java
    OpenJiuwenExampleSupport.java
    tools/
    rails/
```

两份 YAML 都注册：

- 三个 Java tool：`readFile`、`queryOrder`、`calcDiscount`。
- 两个 local skill：`order-analysis`、`report-writing`。
- 两个 function rail：`afterModelCall`、`afterToolCall`。
- DeepSeek OpenAI-compatible model endpoint，通过 `${DEEPSEEK_API_KEY}` 注入。

示例验证逻辑：

- 真实调用大模型。
- 检查 `queryOrder` / `calcDiscount` 调用次数大于 0。
- 检查最终回答包含 tool proof 字段。
- 检查最终回答包含 skill proof marker：`ORDER_ANALYSIS_SKILL_USED`、`REPORT_WRITING_SKILL_USED`。
- 检查 `afterModelCall` / `afterToolCall` rail 计数大于 0。
- 成功时输出 `verification: PASS`。

DeepAgent 示例中 `readFile invocations: 0` 可以是正常结果，因为 DeepAgent 可能通过 OpenJiuwen 原生 `skill_tool` 读取 skill，而不是调用示例自定义 `readFile` tool。

## 12. 验证命令

`agent-sdk` 不在 root reactor 中，验证时必须显式指定 module POM：

```bash
mvn -f agent-sdk/pom.xml test
mvn -f agent-sdk/pom.xml -DskipTests install
mvn -f examples/agent-sdk-example/pom.xml compile
```

真实 example 运行需要先 install SDK，并设置真实模型 key：

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

`examples/agent-sdk-example/README.md` 记录了 Bash / PowerShell 的 UTF-8 设置、运行步骤、预期结果和如何判断 model/tool/skill/rail 被触发。

## 13. 当前已知非阻塞债务

| 位置 | 现状 | 影响 | 建议 |
|---|---|---|---|
| `AgentFactoryBuilder.toolResolver(...)` | 未拒绝 null resolver。 | 传 null 会在 build 阶段 NPE，错误不够前置。 | 增加 `Objects.requireNonNull(resolver, "resolver")`。 |
| `OpenJiuwenRailMapper.toDeepAgentRail(...)` | 与 `toAgentRail(...)` 当前等价，均要求 `AgentRail`。 | 功能正确，但 DeepAgent 专属 rail 校验入口尚未分化。 | 保留入口，后续若 OpenJiuwen 要求 `DeepAgentRail` 再收紧。 |
| `OpenJiuwenDeepAgentBuilder` | 同时填 `DeepAgentConfig.workspacePath/language` 和显式 `Workspace.rootPath/language`。 | 当前值一致，无功能错误；但 SDK 与 HarnessFactory 存在重复解析。 | 如果 OpenJiuwen API 允许，后续让 workspace 解析更集中。 |
| HTTP tool URL policy | 当前只做 URI 语法解析，不做 SSRF / allowlist / 内网拒访。 | 不可信 YAML 场景存在风险。 | 增加 SDK 级 `HttpToolPolicy` 或 builder policy 注入点，必要字段再进 YAML。 |
| class rail options | `options` 已解析但未注入 class rail 实例。 | 需要 options 的 class rail 目前只能自行通过外部机制配置。 | 后续定义受控初始化接口，例如 `ConfigurableRail.init(Map)`，避免任意对象图反序列化。 |
| ReAct MCP | `mcps[]` 当前只在 DeepAgent 路径消费。 | ReAct YAML 声明 MCP 不会生效。 | 明确 ReAct MCP 注册路径后再支持。 |

这些项不影响当前已实现的 YAML 到 OpenJiuwen ReAct/DeepAgent 装配主路径。

## 14. 当前结论

1. `agent-sdk` 当前是 OpenJiuwen customer-side YAML 装配器，不是运行时托管框架。
2. 必要主能力已经落地：model、tool、rail、skill、MCP、DeepAgent workspace/language/skillMode、task loop/planning 开关、HTTP redirect/响应体上限/错误体回显策略。
3. ReAct 与 DeepAgent 共用同一套 `AgentSpec`，但映射落点不同；文档和测试应持续按两条路径分别核实。
4. Java tool 的 `file` scheme 是历史命名，真实语义是 classpath static method；短期应保留兼容，不建议直接改名。
5. HTTP tool 已从全量读取改为流式限流，但 URL 安全 policy 仍是后续安全专题。
6. Rail 已是一等扩展点；当前支持 class rail、function rail 和 builder-level `rail(...)` 注入，不存在 trusted mode 开关。
7. MCP 当前是 DeepAgent tool-provider 能力；ReAct MCP 尚未实现。

## 15. 核实来源

本文件基于以下当前工作区文件重刷；后续代码漂移时应重新核实：

- `agent-sdk/pom.xml`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/factory/AgentFactory.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/factory/AgentFactoryBuilder.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/AgentSpec.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/yaml/AgentYamlParser.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/model/ModelSpec.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/model/ModelRequestSpec.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/tool/*`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/skill/SkillSourceLoader.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/rail/RailSpec.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/spec/mcp/McpSpec.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/adapter/*`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/adapter/react/OpenJiuwenReactAgentBuilder.java`
- `agent-sdk/src/main/java/com/huawei/ascend/agentsdk/adapter/deepagent/OpenJiuwenDeepAgentBuilder.java`
- `agent-sdk/src/test/java/com/huawei/ascend/agentsdk/**`
- `examples/agent-sdk-example/**`
