# MCP Runtime Tool Middleware — 设计文档

> 适用目录：`architecture/docs/L2/agent-runtime/`
> 目标模块：`agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/mcp/`、`agent-runtime/src/main/java/com/huawei/ascend/runtime/engine/openjiuwen/`
> 最后更新：2026-06-22

---

## 1. 概述

### 1.1 特性定位

agent-runtime 的 MCP 接入定位为 **工具中间件**：runtime 通过 MCP Server 发现外部工具，再由具体 Agent 框架 adapter 把这些工具安装为框架原生工具。MCP 不进入 A2A protocol bridge，也不改变 `AgentRuntimeHandler` 的执行 SPI。

- **解决的问题**：业务不需要为每个远端工具手写 Java wrapper；MCP Server 暴露的工具可以被 Agent 自动发现和调用。
- **适用场景**：接入 ModelScope MCP Server、本地工具 MCP Server、企业内部 MCP Server。如果只是远端 Agent 互调，应使用远程 A2A tool，而不是 MCP。

### 1.2 核心设计原则

1. **公共层保持窄 SPI** — `McpProvider` 只表达工具发现和工具调用，不暴露 MCP SDK、OpenJiuwen 或 AgentScope 类型。
2. **框架本地安装** — OpenJiuwen 使用 `OpenJiuwenMcpToolInstaller` 把 MCP tool 安装成 OpenJiuwen `Tool`；其他框架未来使用自己的 Toolkit / Tool registry。
3. **MCP 与 A2A Tool 解耦** — MCP tool 是一次工具调用；远程 A2A tool 可能涉及 task、stream、input-required 和中断续接。
4. **无配置不影响启动** — 没有 MCP 配置或 `McpProvider` 时，不创建 installer，Agent 正常启动。

### 1.3 子特性全景

| 子特性 | 职责 | 关键抽象 | 状态 |
|---|---|---|---|
| MCP Provider SPI | 框架中立的 tools/list 与 tools/call | `McpProvider` | ✅ |
| MCP Tool 数据模型 | 对齐 MCP Tool / ToolResult | `McpToolSpec`、`McpToolResult` | ✅ |
| HTTP/SSE Provider | 通过 JSON-RPC over HTTP/SSE 连接 MCP Server | `HttpMcpProvider`、`McpProperties` | ✅ |
| OpenJiuwen 工具安装 | 将 MCP tool 注册进 OpenJiuwen Agent | `OpenJiuwenMcpToolInstaller` | ✅ |
| 自动装配 | 有 MCP 配置时自动创建 provider 并注入 OpenJiuwen handler | `McpAutoConfiguration` | ✅ |
| stdio 托管 | 启停本地 stdio MCP server 进程 | — | ⬜ |
| MCP resources/prompts | 接入 MCP 非 tool 能力 | — | ⬜ |

---

## 2. 功能规格

### 2.1 能力清单

| 能力 | 状态 | 说明 |
|---|---|---|
| MCP tool discovery | ✅ | `McpProvider.listTools(context)` 返回当前上下文可见工具 |
| MCP tool call | ✅ | `McpProvider.callTool(context, serverId, name, arguments)` 调用工具 |
| HTTP Streamable MCP | ✅ | 通过 `agent-runtime.mcp.servers[*].transport=streamable-http` 配置 |
| SSE MCP | ✅ | 通过 `agent-runtime.mcp.servers[*].transport=sse` 配置 |
| 静态 headers | ✅ | 支持配置 Authorization 等 server 级 Header |
| 多 server 聚合 | ✅ | `serverId` 用于路由和命名冲突处理 |
| 工具命名冲突处理 | ✅ | 同名工具跨 server 时生成 `mcp_<serverId>_<name>` |
| 工具发现失败降级 | ✅ | 发现失败只记录日志，不阻止 Agent 启动 |
| 动态鉴权 | ⬜ | 生产动态鉴权由业务自定义 `McpProvider` 扩展 |

### 2.2 显式排除

| 排除项 | 原因 | 替代 |
|---|---|---|
| 把 MCP SDK 类型放入 `engine.spi` | 会绑定具体 SDK 和框架，破坏中立 SPI | `McpToolSpec` / `McpToolResult` 保留标准字段 |
| 复用 A2A remote interrupt rail | MCP 是 tool call，不是远程 Agent task | 使用 OpenJiuwen 原生 `Tool` wrapper |
| stdio server 进程托管 | 涉及进程生命周期、日志、安全策略 | 后续单独设计 |
| MCP marketplace 管理后台 | 属于平台管理能力 | 后续由平台层或 SkillHub/Registry 处理 |

### 2.3 接口契约

#### McpProvider

```java
public interface McpProvider {
    List<McpToolSpec> listTools(AgentExecutionContext context);

    McpToolResult callTool(AgentExecutionContext context, String serverId,
            String name, Map<String, Object> arguments);
}
```

行为承诺：

- `listTools(...)` 返回当前执行上下文可见的工具。Provider 可以缓存，但必须保证 `serverId` 和 `name` 能用于后续调用。
- `callTool(...)` 调用指定 MCP tool。工具级错误应尽量返回 `McpToolResult.isError=true`，让模型有机会处理；协议级错误可映射为明确错误码。
- 生产环境如果需要租户级鉴权、动态 Header 或工具过滤，应通过自定义 `McpProvider` 实现。

#### McpToolSpec

`McpToolSpec` 对齐 MCP `Tool`：

| 字段 | 说明 |
|---|---|
| `serverId` | runtime 侧 MCP Server 标识 |
| `name` | MCP tool 原始名称 |
| `title` | 标题，可为空 |
| `description` | 工具描述 |
| `inputSchema` | MCP input schema |
| `outputSchema` | MCP output schema |
| `annotations` | MCP tool annotations |
| `meta` | MCP `_meta` |
| `metadata` | runtime 自定义元数据 |

#### McpToolResult

`McpToolResult` 对齐 MCP `ToolResult`：

| 字段 | 说明 |
|---|---|
| `content` | MCP 标准 content 列表 |
| `structuredContent` | 结构化结果 |
| `isError` | MCP 标准错误标记 |
| `errorCode` / `message` | runtime 可观测增强 |
| `meta` | MCP `_meta` |
| `metadata` | runtime 自定义元数据 |

---

## 3. 模块结构

```text
engine/spi/
├── McpProvider.java
├── McpToolSpec.java
└── McpToolResult.java

engine/mcp/
├── McpProperties.java
├── HttpMcpProvider.java
└── McpAutoConfiguration.java

engine/openjiuwen/
└── OpenJiuwenMcpToolInstaller.java
```

---

## 4. 核心流程

### 4.1 自动装配流程

```text
应用启动
  │
  ├─ 检测 agent-runtime.mcp.servers[0].url
  ├─ 创建 HttpMcpProvider
  ├─ 检测 OpenJiuwen classpath 与 McpProvider bean
  └─ 创建 OpenJiuwenMcpToolInstaller 并注入 OpenJiuwenAgentRuntimeHandler
```

### 4.2 执行期工具安装与调用

```text
A2A 请求
  │
  ├─ OpenJiuwenAgentRuntimeHandler.createOpenJiuwenAgent(context)
  ├─ installRuntimeTools(agent, context)
  │     └─ OpenJiuwenMcpToolInstaller.install(agent, context)
  │          ├─ mcpProvider.listTools(context)
  │          ├─ ToolCard 映射
  │          └─ Runner.resourceMgr().addTool(...)
  ├─ OpenJiuwen Runner 执行
  ├─ 模型选择 MCP tool
  └─ RuntimeMcpTool.invoke(...)
        └─ mcpProvider.callTool(context, serverId, name, arguments)
```

### 4.3 命名冲突处理

MCP 只保证单个 server 内 tool name 唯一。多个 server 聚合后，如果存在同名工具：

```text
原始: serverId=weather, name=query
框架内: mcp_weather_query
```

如果没有冲突，框架内名称保持原始 name，减少模型可见名称噪声。ToolCard properties 会保留 `runtime.mcp.serverId` 和 `runtime.mcp.toolName`。

---

## 5. 错误与可观测性

| 场景 | 行为 |
|---|---|
| Server 不可达 | discovery 阶段记录 warn；tool call 返回 `MCP_SERVER_UNAVAILABLE` |
| 鉴权失败 | 返回 `MCP_AUTH_FAILED` |
| 非法响应 | 返回 `MCP_BAD_RESPONSE` |
| 未配置 serverId | 返回 `MCP_SERVER_NOT_CONFIGURED` |
| 工具执行错误 | 返回 `McpToolResult.isError=true` 和错误内容 |

日志应至少包含 `serverId`、`toolName`、latency 和 `isError`，便于定位外部 MCP Server 问题。

---

## 6. 示例与验证

| Example | 用途 |
|---|---|
| `examples/agent-runtime-middleware-mcp-localtime` | 本地真实 date/time MCP Server，验证 tools/list 与 tools/call |
| `examples/agent-runtime-middleware-mcp-remote-json` | 从 JSON 文件接入远端 HTTP/SSE MCP Server |

验证入口：

```bash
./mvnw -f examples/agent-runtime-middleware-mcp-localtime/pom.xml verify
./mvnw -f examples/agent-runtime-middleware-mcp-remote-json/pom.xml verify
```

手工 curl 级验证步骤见各 example 的 `README.md` 和 `TUTORIAL.cn.md`。

---

## 7. 相关文档

- 开发指南：`agent-runtime/docs/guides/mcp-tools.md`
- Proposal：`docs/logs/reviews/2026-06-16-agent-runtime-mcp-middleware-proposal.cn.md`
- Example：`examples/agent-runtime-middleware-mcp-localtime/`
