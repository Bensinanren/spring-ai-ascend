# spring-ai-ascend Developer Handbook

The single front-door manual for an industry (bank-side) developer building
agents and multi-agent systems on `spring-ai-ascend`. It is the **map plus the
contracts**: every class name, property, endpoint and snippet here is verified
against the source tree; where another document already explains a flow well,
this handbook links to it instead of copying it.

Version context: all reactor modules are `0.1.0-SNAPSHOT` (semver posture
`experimental` / `0.x` — see [§10](#10-upgrade--compatibility-promises)).
Artifacts are not yet on Maven Central; `./mvnw -q -DskipTests install` first.

**Reading order if you are new:** [§1](#1-what-the-platform-is) (the map) →
[quickstart](quickstart.md) (first runnable agent) → the section matching your
task below.

---

## 1. What the platform is

`spring-ai-ascend` is a self-hostable, multi-tenant agent platform: a
run-owning runtime that hosts agents built on heterogeneous frameworks behind
one A2A (Agent-to-Agent protocol) surface, a serviceization facade that fronts
fleets of runtimes, client SDKs for calling hosted agents, and an LLM egress
gateway so agents never hold provider credentials.

### The 8-module reactor

| Module | Kind | One-line role |
|---|---|---|
| `agent-runtime` | domain | Run-owning runtime SDK: framework-neutral engine SPI, Run lifecycle, A2A ingress, LLM egress gateway, bootable app |
| `agent-bus` | domain | Bus & State Hub plane: design-frozen `bus.spi.*` contracts plus the live capability surfaces `bus.memory` / `bus.knowledge` / `bus.messaging` |
| `agent-sdk` | domain | Declarative agent definition SDK: `ascend-agent/v1` YAML → runnable handler |
| `agent-service` | domain | Spring-free serviceization facade: registration / discovery / route-grant SPIs + in-memory references + byte-level A2A forwarder |
| `agent-service-starter` | starter | Spring Boot edge for `agent-service`: auto-configured HTTP controllers + JWT tenant cross-check filter |
| `springai-ascend-client` | sdk | Java A2A client SDK for external applications (Spring-free) |
| `springai-ascend-client-kotlin` | sdk | Kotlin coroutine/DSL idiom layer over the Java client |
| `spring-ai-ascend-dependencies` | bom | Bill of Materials pinning all consumable modules and OSS transitives |

Module by module (verified from each `module-metadata.yaml` and pom):

- **`agent-runtime`** drives Agent instances built on heterogeneous agent
  frameworks through a single framework-neutral SPI
  (`com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler` + optional
  `AgentCardProvider` + `StreamAdapter` + a narrow `MemoryProvider`), with
  openJiuwen, AgentScope and LangGraph adapters shipped. It owns engine
  dispatch, the Run lifecycle, A2A access with `(tenant, messageId)` idempotent
  `message/send`, the OpenAI-compatible LLM egress gateway
  (`com.huawei.ascend.runtime.llm.gateway`), and the bootable runtime
  application (`runtime.app.RuntimeApp` / `LocalA2aRuntimeHost`). It is the one
  module an agent-hosting application must depend on.
- **`agent-bus`** is the Bus & State Hub plane. Its `bus.spi.engine` /
  `bus.spi.s2c` packages are design-frozen contracts (not consumed from
  production code); its live capability surfaces are `bus.memory` (session
  working memory + business-fact emission), `bus.knowledge` (tenant-scoped
  retrieval seam) and `bus.messaging` (in-process async inter-agent messaging)
  — each an SPI with an in-memory reference implementation (ADR-0163). See
  [§5](#5-multi-agent-systems).
- **`agent-sdk`** loads declarative `ascend-agent/v1` YAML agent specs
  (framework, model, prompt, skills, tools with file/http/mcp refs), resolves
  tools, and adapts the spec into runnable openJiuwen agents exposed as
  `AgentRuntimeHandler`s. Its extension seam is
  `com.huawei.ascend.agentsdk.spec.tool.ToolResolver`.
- **`agent-service`** is the Spring-free enterprise serviceization facade:
  `RuntimeRegistry` lease/TTL state, tenant-scoped `AgentDirectory`,
  HMAC-signed `RouteGrantService`, in-memory reference implementations, a
  byte-level A2A pass-through forwarder (`service.core.RuntimeA2aGateway`),
  and the runtime self-registration client (`service.client`).
- **`agent-service-starter`** is the only Spring-aware layer of the facade:
  auto-configured registration/discovery/route-grant/A2A-forwarding HTTP
  controllers over the SPI, plus the JWT tenant cross-check filter
  (`agent-service.access.jwt.*`) at the service ingress.
- **`springai-ascend-client`** is the A2A client SDK for external Spring
  developers: a Spring-free facade over the OSS `a2a-java-sdk` JSON-RPC client
  with the platform's terminal-event / post-terminal-cancellation semantics
  built in, per-call W3C `traceparent` propagation + `traceresponse`
  correlation, and JWT bearer + `X-Tenant-Id` auth headers. It deliberately
  depends on **no** reactor sibling, so it embeds in customer apps without
  pulling platform server modules.
- **`springai-ascend-client-kotlin`** adds suspend `sendText`/`streamText`
  extensions (coroutine cancellation → thread interrupt), the
  `ascendA2aClient {}` builder DSL, and a named-argument `sendSpec` factory.
  Every call delegates to the Java facade; no protocol logic of its own.
- **`spring-ai-ascend-dependencies`** pins all seven consumable modules and the
  OSS transitive versions. Its stated policy: *all versions are exact patches;
  no ranges; no LATEST*.

### The three audiences

| Audience | You are… | You depend on |
|---|---|---|
| **A — agent builders** | a bank-side engineering team building and hosting agents | `agent-runtime` (+ `agent-sdk` for the YAML path, + `agent-service-starter` to front a fleet) |
| **B — agent callers** | an application team invoking hosted agents | `springai-ascend-client` (and/or `-kotlin`) — nothing else |
| **C — platform extenders** | a platform team replacing reference implementations | the SPI packages of `agent-runtime`, `agent-service`, `agent-bus`, wired by `@Bean` overrides |

An industry developer is usually A and B at once: host your agents with
`agent-runtime`, call them (and other teams' agents) with the client SDK. You
never need `agent-bus` or `agent-service` as direct dependencies — they arrive
transitively where relevant.

---

## 2. Build your first agent

**Start with [quickstart §3–5](quickstart.md#3-implement-an-echo-agent):** a
10-line echo `AgentRuntimeHandler`, booted with
`RuntimeApp.create(handler).run(LocalA2aRuntimeHost.port(8080))`, verified with
two curls. That covers the code path. This section covers the **declarative
YAML path** the quickstart only points at.

### The `ascend-agent/v1` YAML spec

`AgentHandlerFactory.fromYaml(Path)` (package
`com.huawei.ascend.agentsdk.factory`) loads a YAML spec and returns a ready
`AgentRuntimeHandler`. The builder form adds gateway settings and custom tool
resolvers:

```java
AgentRuntimeHandler handler = AgentHandlerFactory.builder()
        .gateway("http://localhost:8080", "team-default-minted-token") // model.alias only
        .toolResolver(myCustomResolver)                                // optional extra scheme
        .fromYaml(Path.of("agent.yaml"));
```

Top-level fields (verified against `AgentYamlParser`):

| Field | Required | Meaning |
|---|---|---|
| `schema` | yes | Must be exactly `ascend-agent/v1` |
| `name` | yes | Agent id; becomes the handler's `agentId()` |
| `displayName` | no | Defaults to `name` |
| `description` | yes | Human description |
| `metadata` | no | Free-form map |
| `cacheRoot` | no | Path; reserved for `localCache` materialization |
| `framework.type` | yes | Today: `openjiuwen` only |
| `framework.agent` | yes | `react` or `deepagent` |
| `framework.options` | no | Framework-specific options map (e.g. `executeMode`, `maxIterations`) |
| `model` | yes (one form) | Explicit form or alias form, never mixed (below) |
| `prompt.system` | no | System prompt text (defaults to empty) |
| `skills.sources` | no | List of skill-source dirs (string shorthand or `{type, path, localCache}`) |
| `tools` | no | List of tool declarations (below) |
| `mcpServers` | no | Named MCP server map (below) |

`${ENV_VAR}` placeholders anywhere in the file are resolved from the process
environment before parsing; an unset variable **fails loading** with
`Environment variable is not set: <name>` — there is no silent empty default.

**Model — two mutually exclusive forms.** Explicit names the upstream
directly; alias delegates all routing to the platform LLM gateway:

```yaml
# Explicit form — you own the credential:
model:
  provider: openai-compatible    # default if omitted
  name: deepseek-chat            # required
  baseUrl: https://api.deepseek.com   # required
  apiKey: ${DEEPSEEK_API_KEY}    # required
  sslVerify: true                # default true
  headers: {}                    # optional extra headers

# Alias form — the gateway owns the credential:
model:
  alias: team-default
```

A leftover `provider`/`name`/`baseUrl`/`apiKey` key next to `alias` is rejected
by name (the gateway would silently ignore it otherwise). The alias form
resolves against the builder's `.gateway(baseUrl, mintedToken)` values, falling
back to the `SAA_GATEWAY_BASE_URL` / `SAA_GATEWAY_TOKEN` environment variables;
the effective spec points the framework's OpenAI-compatible client at the
gateway's `/v1` surface with the minted token riding the existing `apiKey`
field — the framework needs no code change. See [§6](#6-llm-egress-gateway).

**Tools.** Each entry: `name` (unique — duplicates are rejected, the name is
the global tool registry key), `description` (required), optional
`inputSchema`/`outputSchema` (JSON-Schema maps), optional `localCache`
(boolean), and a `ref` in either string-shorthand or map form:

```yaml
tools:
  - name: queryOrder
    description: Look up an order.
    ref: file:com.example.QueryOrderTool#query    # Java class#method
  - name: weather
    description: Weather lookup.
    ref: http:https://api.example.com/weather     # HTTP endpoint
  - name: search
    description: Search the docs.
    ref: mcp:docs/search                          # MCP server/tool
  - name: queryOrderLong
    description: Map form of the same Java ref.
    ref:
      type: file
      class: com.example.QueryOrderTool
      method: query
```

Built-in resolvers: `file:` (`JavaFileToolResolver` — class + method on the
classpath, or a `path` to a source file resolved relative to the YAML),
`http:` (`HttpToolResolver` — real HTTP execution), `mcp:`
(`McpToolResolver` — must name a server declared in `mcpServers`). Register a
`ToolResolver` on the builder for new schemes.

**MCP servers.** Exactly one transport per server — `command` (stdio) or `url`
(HTTP/SSE) — with cross-transport keys rejected loudly:

```yaml
mcpServers:
  local-files:                  # stdio: command (+args, +env); headers rejected
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/data"]
    env:
      LOG_LEVEL: warn
  docs:                         # HTTP/SSE: url (+headers); args/env rejected
    url: https://mcp.example.com/sse
    headers:
      Authorization: Bearer ${MCP_TOKEN}
```

**Skills.** `skills.sources` entries are either a path string (filesystem
source, resolved relative to the YAML) or `{type: filesystem, path: …,
localCache: …}`. Skill directories are loaded by `SkillSourceLoader` and mapped
into the openJiuwen agent.

Runnable specs and a standalone example project live in
[`examples/agent-sdk-example/`](../examples/agent-sdk-example/README.md)
(including a proof mode that verifies wiring without calling a model).

---

## 3. Host heterogeneous engines

### The SPI contract

`AgentRuntimeHandler` (package `com.huawei.ascend.runtime.engine.spi`) is the
seam between the engine and a concrete agent framework — four methods, no
Spring, no A2A types in your business logic:

```java
public interface AgentRuntimeHandler {
    String agentId();
    boolean isHealthy();
    Stream<?> execute(AgentExecutionContext context);  // framework-specific results
    StreamAdapter resultAdapter();                     // maps them to neutral results
}

@FunctionalInterface
public interface StreamAdapter {
    Stream<AgentExecutionResult> adapt(Stream<?> rawResults);
}
```

`AgentExecutionResult` has exactly four factory shapes: `output(content)`
(intermediate token/chunk), `completed(content)` (terminal answer),
`failed(errorCode, errorMessage)`, and `interrupted(prompt)` (the run suspends
awaiting caller input — this is what surfaces as `awaitingInput()` on the
client, [§4](#4-call-agents)). The engine owns the Run lifecycle, task state
and A2A event mapping; your handler never touches them.

Two optional companion SPIs: `AgentCardProvider` (describe your agent's A2A
card yourself instead of the auto-generated default) and `MemoryProvider` (a
narrow init/search/save seam for frameworks that want runtime-provided memory).

`AgentExecutionContext` hands your handler the tenant/session/task scope
(`getScope()` → `RuntimeIdentity`), the A2A messages (`getMessages()`,
extract text with `runtime.engine.a2a.Messages.text(message)`), request
variables, per-task agent state, and — when the hosting application wires them
— the three agent-bus capability surfaces (`getSessionMemory()`,
`getKnowledge()`, `getMessageBus()`, each an `Optional`; [§5](#5-multi-agent-systems)).

### The three shipped adapter families

| Family | Package (`com.huawei.ascend.runtime.engine.…`) | Shape | What it expects |
|---|---|---|---|
| **openJiuwen** | `openjiuwen` | In-process: extend `OpenJiuwenAgentRuntimeHandler` (or let `agent-sdk` build it from YAML) | An openJiuwen `ReActAgent`/DeepAgent instance; the base class owns the execute flow, rail installation, message mapping and stable `conversation_id` |
| **AgentScope** | `agentscope` | In-process (`AgentScopeAgentRuntimeHandler` for SDK agents, `AgentScopeHarnessRuntimeHandler` for harness agents) **or** remote (`AgentScopeRuntimeClientHandler`) | Remote form: `new AgentScopeRuntimeClientHandler(agentId, new AgentScopeRuntimeClient(new AgentScopeRuntimeClientProperties(baseUrl)))` against an AgentScope Runtime REST/SSE endpoint |
| **LangGraph** | `langgraph` | Remote only: `LangGraphRuntimeClientHandler` | `new LangGraphRuntimeClientHandler(agentId, new LangGraphRuntimeClient(new LangGraphRuntimeClientProperties(baseUrl, assistantId)))` against LangGraph Platform or a `langgraph-api` dev server |

Working Spring wiring for all of these lives in the e2e example — see
[its README](../examples/agent-runtime-a2a-llm-e2e/README.md) and
`LangGraphE2eConfiguration` in that module.

### The seam guarantee

When an engine framework bumps its version, **nothing changes for you** unless
you wrote framework code yourself: the A2A wire surface, the Run lifecycle,
tenancy, idempotency, tracing and the client SDKs all sit on the
engine-neutral side of `AgentRuntimeHandler`/`StreamAdapter`. The adapter
absorbs the framework's dialect; your callers cannot tell which framework — or
which framework *version* — serves an agent. The remote adapter families take
this further: AgentScope Runtime and LangGraph agents run in their native
runtimes and the platform speaks only their public HTTP/SSE APIs.

---

## 4. Call agents

[Quickstart §5b](quickstart.md#5b-call-it-from-java-or-kotlin) shows the
minimal Java and Kotlin calls. The contract details:

### Java — `springai-ascend-client`

```java
try (AscendA2aClient client = AscendA2aClient.builder()
        .baseUrl("https://agents.example.com")
        .timeout(Duration.ofSeconds(60))                       // default 30s, whole call
        .auth(ClientAuth.jwtBearer(tokenSupplier, "bank-7"))   // optional
        .telemetry(otelTelemetry)                              // optional, closed with the client
        .build()) {

    A2aResponse reply = client.sendText(
            SendSpec.of("wealth-advisor", "session-42", "user-9", "ping"));

    if (reply.awaitingInput()) {
        // HITL: the run is suspended on input-required / auth-required.
        // reply.text() is the agent's prompt; answer it with a follow-up
        // send on the SAME sessionId.
    }
}
```

- `SendSpec(agentId, sessionId, userId, text, messageId, metadata)` — the first
  four are required; `messageId` is auto-generated when null (set it yourself
  to get idempotent retries, since `message/send` is deduplicated per
  `(tenant, messageId)` server-side); reserved routing keys
  (`userId`/`agentId`/`sessionId`) cannot be overridden via `metadata`.
- `sendText(spec)` — blocking JSON-RPC send, returns on the terminal event.
- `streamText(spec)` / `streamText(spec, listener)` — SSE; blocks until the
  turn-ending event, surfacing each `StreamingEventKind` to the listener.
- `A2aResponse` carries `text()` (extracted user-visible answer), `events()`
  (every raw A2A event, in arrival order), `trace()` (the
  `TraceCorrelation` — outbound `traceparent` + server `traceresponse`), and
  `awaitingInput()` (true when the run suspended on
  `input-required`/`auth-required` rather than finishing).
- `agentCard()` fetches the served agent card.
- `ClientAuth.jwtBearer(Supplier<String> token)` or
  `jwtBearer(token, tenantId)` — the token is a `Supplier`, re-evaluated per
  call, so rotating credentials plug in directly. With the tenant overload the
  `X-Tenant-Id` header is sent and **must** match the JWT's `tenant_id` claim
  (the ingress answers 403 on mismatch — see [§7](#7-security-and-multi-tenancy)).
- Built-in platform semantics: terminal-event detection, post-terminal
  cancellation handling, and per-call W3C trace propagation
  (`TracePropagation.sampled()` is the default; `notSampled()` available).

### Kotlin — `springai-ascend-client-kotlin`

```kotlin
val client = ascendA2aClient {
    baseUrl = "https://agents.example.com"
    auth = ClientAuth.jwtBearer({ token }, "bank-7")
}
val reply = client.sendTextSuspending(
    sendSpec(agentId = "wealth-advisor", sessionId = "session-42",
             userId = "user-9", text = "ping"))
```

`sendTextSuspending` / `streamTextSuspending` run the blocking call on
`Dispatchers.IO` via `runInterruptible`: cancelling the coroutine interrupts
the worker thread and surfaces as `CancellationException`. Unset DSL properties
keep the Java builder's defaults.

### Raw A2A as the fallback

Any JSON-RPC-capable client works without the SDK — the exact curl bodies for
`SendMessage` (blocking) and `SendStreamingMessage` (SSE), the agent-card
endpoint `/.well-known/agent-card.json`, and the response shapes are in
[quickstart §5](quickstart.md#5-verify-with-curl).

---

## 5. Multi-agent systems

Two complementary planes:

- **Cross-process** (agents on different runtimes): A2A through the
  **service facade** — registration, discovery, signed route grants,
  byte-level forwarding. This is the only cross-process agent-to-agent path
  (A2A-NO-REWRITE: the forwarder never rewrites payloads).
- **In-process** (agents co-hosted on one runtime JVM): the **agent-bus
  capability surfaces** — session memory, knowledge retrieval, async
  messaging (ADR-0163).

### 5.1 The service facade (cross-process)

Add `agent-service-starter` to any Spring Boot web app and the edge is live
(`agent-service.enabled` defaults to `true`). Minimal yaml, the three
controller surfaces, and the verify-curl are in
[quickstart §7](quickstart.md#7-front-the-runtime-with-the-service-facade);
endpoint summary:

| Controller | Endpoints |
|---|---|
| `RuntimeRegistryController` | `POST /v1/runtime-registrations`, `PUT /v1/runtime-registrations/{runtimeInstanceId}/lease`, `DELETE /v1/runtime-registrations/{runtimeInstanceId}`, `GET /v1/agents?tenantId=…`, `GET /v1/agents/{agentId}/card?tenantId=…`, `POST /v1/agents/{agentId}/routes/resolve?tenantId=…` |
| `RouteGrantController` | `POST /v1/route-grants/resolve`, `POST /v1/route-grants/validate` |
| `A2aGatewayController` | `POST /v1/agents/{agentId}/a2a?tenantId=…` (JSON or SSE per forwarded method) |

Key starter properties (`AgentServiceProperties`):

| Property | Default | Meaning |
|---|---|---|
| `agent-service.enabled` | `true` | Switches the whole auto-configuration off |
| `agent-service.route-grant-secret` | checked-in dev default | Signs HMAC route grants — **must** be provisioned per deployment ([§7](#7-security-and-multi-tenancy)) |
| `agent-service.public-base-url` | empty | When set, served agent cards are masked onto `<publicBaseUrl>/v1/agents/<agentId>/a2a` so back-end runtime topology never leaks (`MaskedAgentDirectory`); empty serves cards verbatim |
| `agent-service.access.jwt.{enabled,hmac-secret,clock-skew-seconds}` | disabled / — / `30` | JWT tenant cross-check at the service ingress, same shape as the runtime edge |

Every implementation sits behind `@ConditionalOnMissingBean`: contribute your
own `RuntimeRegistry`, `AgentDirectory`, or `RouteGrantService` bean to replace
the in-memory reference.

**Self-registration + heartbeats.** Runtimes announce themselves with the
Spring-free `RuntimeRegistrationClient` (module `agent-service`, package
`com.huawei.ascend.service.client`):

```java
RuntimeRegistrationClient client = RuntimeRegistrationClient.builder(serviceBaseUrl)
        .tenantId("bank-7")
        .bearerTokenSupplier(tokenSupplier)   // when the service ingress enforces JWT
        .build();
client.register(registration);   // RuntimeAgentRegistration: instance id, tenant, agent,
                                 // card, a2aEndpoint, healthEndpoint, version, ttl,
                                 // capacity snapshot, metadata
client.startHeartbeat(...);      // periodic lease renewal keeps the instance routable
// close() deregisters.
```

Lease semantics (in-memory reference `InMemoryRuntimeRegistry`): register puts
the instance at `READY`; an expired lease is observed as `UNREACHABLE`;
`AT_CAPACITY` is derived from the capacity snapshot at query time. A complete
runnable flow is `RuntimeSelfRegistrationE2eTest` in the e2e example, documented
in [its README](../examples/agent-runtime-a2a-llm-e2e/README.md).

**Session affinity.** Route resolution is sticky per session: when the
`RoutingContext` carries a non-blank `sessionId`, the first resolution pins
`(tenant, agent, session)` to the chosen instance; later resolutions return it
while it stays registered, lease-alive and `READY`. Pins drop on deregister and
lease expiry; the pin map is capped (10 000 entries, oldest evicted) so
abandoned sessions cannot grow memory.

**East-west calls.** A source runtime resolves a short-lived HMAC-signed
`RouteGrant` (grantId, tenant, source/target agent, allowed methods,
signature), calls the target directly, and the target validates the grant. The
facade's forwarder attaches `X-Ascend-Route-Grant-Id`,
`X-Ascend-Route-Grant-Signature`, `X-Ascend-Source-Agent` and
`X-Ascend-Tenant` headers on forwarded calls.

### 5.2 The agent-bus capability surfaces (in-process)

Three packages in `agent-bus` (each an SPI **with** an in-memory reference
implementation), reached from your handler through `AgentExecutionContext` —
the runtime auto-configures the references as `@ConditionalOnMissingBean`
beans, so they are present in every Spring-booted runtime and replaceable by
your own beans:

```java
Optional<SessionMemoryStore> memory   = context.getSessionMemory();
Optional<KnowledgeRegistry>  registry = context.getKnowledge();
Optional<AgentMessageBus>    bus      = context.getMessageBus();
```

**Session memory — `com.huawei.ascend.bus.memory`.**

```java
public interface SessionMemoryStore {
    void append(String tenantId, String sessionId, MemoryEntry entry);
    List<MemoryEntry> window(String tenantId, String sessionId, int maxEntries); // newest first
    void clear(String tenantId, String sessionId);
}
// MemoryEntry(role, text, timestamp, attributes)
```

Reference impl `InMemorySessionMemoryStore`: per-`(tenant, session)` bounded
windows (default 200 entries, oldest evicted) — a recency window, not an
archive. Tenant isolation is structural: the storage key includes the tenant
id, so there is no code path that reads another tenant's window.

**The ownership rule you must understand (ADR-0051).** The platform owns
*S-side working memory only* — conversation windows and trajectory-adjacent
session state. Business facts your agent discovers during execution
(preferences, entity state changes, anything with business meaning) are
**emitted to YOUR systems, never stored by the platform**: publish a
`BusinessFactEvent(tenantId, sessionId, runId, factType, payload,
placeholdersPreserved, occurredAt)` through the `BusinessFactPublisher` SPI and
decide on your side whether to accept, transform, store or discard it. The
in-repo `RecordingBusinessFactPublisher` is a bounded test/example log whose
`drain()` hands facts over exactly once — a real deployment plugs a bridge to
your systems behind the SPI. The platform does not claim factual authority,
and opaque identity placeholders (e.g. `[USER_ID_102]`) in payloads are
carried as symbols, never resolved platform-side.

**Knowledge seam — `com.huawei.ascend.bus.knowledge`.** The platform never
owns business knowledge content; it owns the retrieval seam:

```java
public interface KnowledgeSource {
    List<KnowledgeFragment> retrieve(KnowledgeQuery query);
}
// KnowledgeQuery(tenantId, query, topK, filters)
// KnowledgeFragment(sourceId, content, score, provenance)
```

`KnowledgeRegistry` registers named sources per tenant (duplicate names
rejected loudly); `new CompositeKnowledgeSource(registry).retrieve(query)` fans
the query across the tenant's sources and merges by score, descending, with
deterministic ordering (`topK` applied after the merge). The reference
`InMemoryKnowledgeSource` scores by naive token overlap over seeded documents
— honest about being a seam exerciser, not a vector store; semantic retrieval
plugs in through the same SPI.

**Async messaging — `com.huawei.ascend.bus.messaging`.** The in-process plane
for agents co-hosted on one runtime JVM (cross-process stays A2A through the
facade):

```java
public interface AgentMessageBus {
    void publish(AgentMessage message);
    Subscription subscribe(String tenantId, String topic, AgentMessageHandler handler);
}
// AgentMessage(messageId, tenantId, topic, fromAgentId,
//              correlationId?, traceparent?, payload, occurredAt)
// AgentMessage.of(tenantId, topic, fromAgentId, payload) auto-generates id + timestamp
// Subscription extends AutoCloseable; droppedCount() reports queue drops
```

Reference impl `InMemoryAgentMessageBus`: topics are tenant-scoped (a
subscriber never sees another tenant's messages, even on an identical topic
name); delivery per topic is ordered (single daemon dispatcher thread); each
subscriber owns a bounded FIFO queue (default 256) that drops the **oldest**
message when full — drops are counted on the subscription and the first drop
logs a warning, never silent; handler exceptions are logged and contained, so
one bad subscriber cannot break the topic. The bus is `AutoCloseable` and the
Spring context closes it at shutdown.

**Canonical usage** is `MultiAgentBusE2eTest`
([examples/agent-runtime-a2a-llm-e2e](../examples/agent-runtime-a2a-llm-e2e/src/test/java/com/huawei/ascend/examples/a2a/MultiAgentBusE2eTest.java)):
one booted `RuntimeApp`, no LLM — an A2A call to a "planner" handler records
the turn in session memory, answers from a seeded knowledge fragment, and
delegates the final wording to a co-hosted "worker" through an async
request/reply over the bus, correlated by `correlationId`. Note the pattern it
encodes: the A2A ingress is single-handler by design, so a co-hosted helper
agent is modelled as a bus subscriber, not a second A2A-exposed handler.

What the in-memory references are NOT: durable. Broker-backed messaging,
vector knowledge stores and durable memory backends are extension points
behind these SPIs, not shipped code ([§9](#9-operations)).

---

## 6. LLM egress gateway

Agents never hold provider keys: they call the runtime's OpenAI-compatible
gateway (`POST /v1/chat/completions`) with a **minted, agent-scoped token** and
a **model alias**; only the gateway's server-side routing table knows the real
upstream URL, credential and model. The end-to-end setup walkthrough is
[quickstart §8](quickstart.md#8-route-agent-llm-traffic-through-the-egress-gateway);
the contract:

```yaml
agent-runtime:
  llm:
    gateway:
      enabled: true                  # default false — whole surface off otherwise
      connect-timeout: 5s            # default; TCP connect to upstream
      request-timeout: 120s          # default; whole exchange (buffered) /
                                     # time-to-response-headers (streaming —
                                     # the SSE relay itself is unbounded by design)
      aliases:
        team-default:                              # the "model" callers send
          base-url: https://api.example.com/v1     # OpenAI-compatible root incl. /v1
          api-key: ${UPSTREAM_API_KEY}             # real credential; explicitly empty
                                                   #   declares a no-auth upstream
          provider: openai-compatible              # telemetry label (gen_ai.system)
          upstream-model: gpt-5.4-mini             # omit to forward the alias unchanged
          pricing:                                 # omit = unpriced (cost omitted, not zeroed)
            input-per-million-tokens-usd: 0.15
            output-per-million-tokens-usd: 0.60
      tokens:
        team-default-minted-token:                 # opaque token the agent sends
          tenant-id: bank-7
          agent-id: echo-agent
```

Request handling, in order (verified against `ChatCompletionsController`):

1. **Auth first** — the bearer token resolves against `tokens` to its
   provisioned `(tenant, agent)` identity from server-side config only; an
   unknown/missing token is `401` *before any upstream contact* (an
   unauthenticated caller never consumes provider quota).
2. **Validation** — non-JSON-object body → `400`; missing `model` → `400`;
   `model` not a configured alias → `404`.
3. **Forwarding** — the alias's upstream is called with the real key and
   (when set) the real `upstream-model`; `"stream": true` switches to SSE
   relay. An upstream `5xx` surfaces as `502 upstream_error`; transport
   failures are recorded and surfaced, never swallowed.
4. **Metering + emission** — usage tokens are extracted from the response,
   aggregate meters recorded ([§8](#8-observability)), one `SpendRecord(tenantId,
   agentId, modelAlias, day, inputTokens, outputTokens, costUsd)` appended to
   the `SpendLog` SPI (default `InMemorySpendLog` — in-process, does not
   survive restart), and one GENERATION span emitted through the
   `GenerationSpanSink` SPI (an `OtelGenerationSpanSink` auto-wires when an
   `OpenTelemetry` bean exists).

The observer seams (`runtime.llm.gateway.spi`: `LlmCallListener`,
`GenerationSpanSink`, `SpendLog`) are the **sole** emission path for
GENERATION spans and spend records — implement them to land cost data in your
own systems.

On the agent side, the YAML `model.alias` form ([§2](#2-build-your-first-agent))
is the matching client half. The e2e example flips both of its framework
samples onto the gateway path with one switch
(`SAA_SAMPLE_LLM_VIA_GATEWAY=true` / `sample.llm.via-gateway=true`) — see
["Routing Sample LLM Traffic Through the Egress
Gateway"](../examples/agent-runtime-a2a-llm-e2e/README.md) for exactly what
changes on the wire.

---

## 7. Security and multi-tenancy

### JWT tenant cross-check — the same scheme on both edges

Both ingress edges (runtime `/a2a` via `A2aTenantAuthFilter`; service facade
registration/discovery/grant endpoints via `ServiceTenantAuthFilter`) enforce
one model: `Authorization: Bearer <jwt>` whose HS256 signature verifies against
the configured shared secret, and whose `tenant_id` claim is cross-checked
against any explicit tenant attribution present (`X-Tenant-Id` header on both
edges; the `tenantId` query parameter additionally on the service edge).
Missing/invalid token → `401`; cross-check mismatch → `403`. Both filters are
disabled until the secret is provisioned:

| Edge | Property prefix | Filter applies to |
|---|---|---|
| Runtime A2A | `agent-runtime.access.a2a.jwt` | `/a2a`, `/a2a/*` |
| Service facade | `agent-service.access.jwt` | `/v1/runtime-registrations*`, `/v1/agents*`, `/v1/route-grants*` |

Both share `JwtTenantValidator` (`runtime.boot`) — one validator, one set of
semantics, both edges. `ClientAuth.jwtBearer` on the client SDK produces
exactly the headers these filters expect.

Tenant attribution precedence at the runtime ingress: JWT-authenticated tenant
(when enabled) > `X-Tenant-Id` header >
`agent-runtime.access.a2a.default-tenant-id` (default `default`).

### Tenant structural isolation — the actual mechanisms

Isolation is structural (keyed storage), not filter-a-shared-lookup policy:

| Surface | Mechanism |
|---|---|
| Run attribution | every run carries the resolved tenant in its `RuntimeIdentity` scope |
| `message/send` idempotency | deduplication key is `(tenant, messageId)` — tenants cannot collide or replay each other |
| Session memory | `InMemorySessionMemoryStore` keys windows by `(tenantId, sessionId)` record key |
| Knowledge | `KnowledgeQuery` carries `tenantId`; `KnowledgeRegistry` registers sources per tenant; reference source stores documents per tenant |
| Message bus | topics keyed `(tenantId, topic)` — identical topic names in different tenants never cross |
| Registry/discovery | candidate filtering is tenant-first, then agent; `GET /v1/agents` and route resolution are tenant-parameterized |
| LLM gateway | a minted token resolves to its provisioned `(tenant, agent)` from server-side config only — no caller-supplied identity is trusted |

### Route-grant secret discipline

`agent-service.route-grant-secret` defaults to a **checked-in, public**
development value. The starter's behavior (verified in
`AgentServiceAutoConfiguration`):

- default secret + JWT ingress disabled → startup **WARN**: grants signed with
  the public default provide no authorization; set a private value before
  exposing the edge beyond local development;
- default secret + `agent-service.access.jwt.enabled=true` → **fail-fast**
  `IllegalStateException` at startup: a JWT-provisioned deployment is
  production-posture, and forgeable route grants would silently void the
  edge's authorization model.

---

## 8. Observability

### Trace propagation: `traceparent` in, `traceresponse` out

`TraceParentFilter` (registered ahead of auth, so even rejections are
correlatable) accepts a W3C version-00 `traceparent` from every inbound
request — or originates a trace id when absent or unparseable — puts
`trace_id` / `span_id` into the Logback MDC, and answers every response with
`traceresponse: 00-<trace_id>-<server_span_id>-01`. Unparseable inbound headers
are counted on `springai_ascend_traceparent_invalid_total`.

### Client-side telemetry

The client SDK sends a fresh `traceparent` per call and exposes the server's
`traceresponse` on `A2aResponse.trace()`. Plug `Builder#telemetry` with:

- `OtelClientTelemetry` — spans through **your** `OpenTelemetry` instance: each
  call becomes a CLIENT span `a2a send <agentId>` / `a2a stream <agentId>` with
  `a2a.*`, `tenant.id`, `server.address` attributes; the outbound `traceparent`
  derives from the span context, so wire trace and local span share a trace id.
- `OtlpClientTelemetry` — a self-contained OTLP/HTTP exporter when your app has
  no OTel SDK of its own.

Both honor the client `Posture` enum — the platform's posture contract
mirrored client-side:

| Posture | Head sampling | Message text on telemetry |
|---|---|---|
| `DEV` | 100% | allowed |
| `RESEARCH` | 10% | allowed |
| `PROD` | 1% | **never set** — PII redaction is structural (the attributes are not written, rather than written and filtered) |

### Gateway meters

Tags are bounded vocabularies only (`model_alias`, `provider`, `outcome`,
`direction`) — never `tenant_id`, whose cardinality is unbounded; per-tenant
cost questions are answered by the spend log and GENERATION records, not
Prometheus:

| Meter | Type | Tags |
|---|---|---|
| `springai_ascend_llm_requests_total` | counter | `model_alias`, `provider`, `outcome` |
| `springai_ascend_llm_tokens_total` | counter | `model_alias`, `provider`, `direction` (`input`/`output`) |
| `springai_ascend_llm_upstream_latency_seconds` | timer | `model_alias`, `provider` |
| `springai_ascend_llm_cost_unpriced_total` | counter | `model_alias`, `provider` |
| `springai_ascend_traceparent_invalid_total` | counter | — |

Everything runs unmetered (no-op) when no Micrometer `MeterRegistry` is on the
classpath.

### OTel GENERATION bridge (server side)

When an `OpenTelemetry` bean exists in the gateway-enabled runtime,
`OtelGenerationSpanSink` turns each LLM invocation into a CLIENT span
`chat <model>` carrying `gen_ai.system`, `gen_ai.request.model`,
`gen_ai.usage.input_tokens`/`output_tokens`, `langfuse.cost_usd` (omitted
entirely when unpriced — a fabricated zero would read as "free"),
`langfuse.latency_ms`, and the mandatory `tenant.id`. The span is backdated so
its duration mirrors measured upstream latency, and the sink never throws
(observer-only seam).

---

## 9. Operations

### Configuration reference

**Runtime (`agent-runtime.*`):**

| Property | Default | Meaning |
|---|---|---|
| `agent-runtime.enabled` | `true` | The facade switch: set `false` in facade-only deployments (e.g. an `agent-service-starter` app that depends on `agent-runtime` solely for the shared JWT validator) to keep the whole A2A runtime kernel out of the context |
| `agent-runtime.access.a2a.default-tenant-id` | `default` | Tenant attributed to requests without `X-Tenant-Id` |
| `agent-runtime.access.a2a.jwt.enabled` | `false` | Tenant auth at the A2A ingress |
| `agent-runtime.access.a2a.jwt.hmac-secret` | — | Shared HS256 secret |
| `agent-runtime.access.a2a.jwt.clock-skew-seconds` | `30` | Tolerated skew for `exp`/`nbf` |
| `agent-runtime.llm.gateway.enabled` | `false` | The whole gateway surface on/off |
| `agent-runtime.llm.gateway.aliases.<alias>.{base-url,api-key,provider,upstream-model,pricing.*}` | — | Routing table ([§6](#6-llm-egress-gateway)) |
| `agent-runtime.llm.gateway.tokens.<token>.{tenant-id,agent-id}` | — | Minted-token directory |
| `agent-runtime.llm.gateway.connect-timeout` | `5s` | Upstream TCP connect |
| `agent-runtime.llm.gateway.request-timeout` | `120s` | Upstream response bound (headers-only on the streaming path) |

**Service facade (`agent-service.*`):** see the table in
[§5.1](#51-the-service-facade-cross-process).

**Agent-side gateway env vars (consumed by `agent-sdk`'s `model.alias`):**
`SAA_GATEWAY_BASE_URL`, `SAA_GATEWAY_TOKEN` — fallbacks for values not set on
the `AgentHandlerFactory` builder.

**Example/sample env vars** (`SAA_SAMPLE_LLM_API_KEY`, `SAA_SAMPLE_LLM_MODEL`,
`SAA_SAMPLE_OPENJIUWEN_API_BASE`, `SAA_SAMPLE_LLM_VIA_GATEWAY`, …) belong to
the e2e example only, with their precedence rules documented in
["Which Environment Values Are
Effective?"](../examples/agent-runtime-a2a-llm-e2e/README.md).

**Booting without Postgres:** the `agent-runtime` jar ships an
`application.yml` with datasource/Flyway keys; on a minimal classpath the JDBC
stack is `<optional>` and the keys are inert, but if your application adds its
own JDBC stack you must carry the documented `spring.autoconfigure.exclude`
list — the exact list and the caveat that setting it *replaces* the library's
own exclusions are in [quickstart §4](quickstart.md#booting-without-postgres).

Posture is selected by `APP_POSTURE` (`dev`/`research`/`prod`): `dev` is
permissive (in-memory backends, WARN on missing config); `research`/`prod`
fail closed at startup when required configuration is missing.

### What is in-memory today — and the SPI that replaces it

Honest current state: every stateful reference implementation is in-process
and does not survive a restart. Each sits behind an SPI +
`@ConditionalOnMissingBean` seam, so replacing it is a bean override, not a
platform patch:

| Concern | Shipped reference | Replace via |
|---|---|---|
| Run state | `InMemoryRunRepository` | `RunRepository` bean (Postgres tier is an extension point, not shipped) |
| `message/send` dedup | `InMemoryIdempotencyStore` | `IdempotencyStore` bean |
| A2A task store | `InMemoryTaskStore` (a2a-sdk) | `TaskStore` bean |
| Session memory | `InMemorySessionMemoryStore` | `SessionMemoryStore` bean |
| Knowledge retrieval | `InMemoryKnowledgeSource` (token overlap) | register real `KnowledgeSource`s (vector stores, Graphiti) on the `KnowledgeRegistry` |
| Inter-agent messaging | `InMemoryAgentMessageBus` | `AgentMessageBus` bean (broker-backed transports) |
| Business-fact emission | `RecordingBusinessFactPublisher` | `BusinessFactPublisher` bridging to your systems |
| Registry / discovery | `InMemoryRuntimeRegistry` | `RuntimeRegistry` / `AgentDirectory` beans |
| Route grants | `HmacRouteGrantService` (HMAC over shared secret) | `RouteGrantService` bean |
| LLM spend ledger | `InMemorySpendLog` | `SpendLog` bean |
| LLM upstream transport | `RestClientUpstreamModelClient` | `UpstreamModelClient` bean |

Secrets follow the same honesty: properties are Vault-*resolvable* like any
Spring property, but no Vault integration is shipped or required. The
per-capability shipped/deferred ledger is
[`docs/governance/architecture-status.yaml`](governance/architecture-status.yaml).

---

## 10. Upgrade & compatibility promises

What insulates your code when versions move:

- **The engine SPI seam.** Your business logic touches
  `AgentRuntimeHandler`/`StreamAdapter`/`AgentExecutionContext` only. When an
  agent framework (openJiuwen, AgentScope, LangGraph) bumps, the platform's
  adapter absorbs the change; your handlers and your callers see nothing
  ([§3](#3-host-heterogeneous-engines)).
- **`model.alias`.** Agents that adopt the alias form carry no provider URL,
  credential or real model name — re-pointing an alias at a new provider or
  model version is a gateway-side config change with zero agent redeploys
  ([§6](#6-llm-egress-gateway)).
- **The A2A wire.** The protocol surface is pinned to the OSS A2A Java SDK
  (`org.a2aproject.sdk`, single version pinned reactor-wide), and the facade's
  forwarder is byte-level pass-through (A2A-NO-REWRITE) — the platform never
  invents its own serialization of A2A payloads, so client and server can only
  drift together with the SDK pin, never independently.
- **The BoM single-pin policy.** `spring-ai-ascend-dependencies` pins all
  seven consumable modules at one version plus exact-patch versions for OSS
  transitives — no ranges, no LATEST. Import the BoM and a platform upgrade is
  one property bump; mixed-version module sets are structurally avoided.
- **Replaceable beans, not patches.** Every reference implementation in
  [§9](#9-operations) is a `@ConditionalOnMissingBean` seam: platform upgrades
  do not collide with your overrides, and your overrides never require forking
  platform source. If anything requires modifying platform source, that is a
  decoupling defect — file it as one.

What is honestly **not** promised yet: the modules are `0.1.0-SNAPSHOT` with
`semver_compatibility: experimental` (`agent-runtime`, `agent-bus`,
`agent-sdk`) or `0.x` (the rest) — public signatures can still change between
0.x releases, with changes declared per release in
[`docs/logs/releases/`](logs/releases/). The runtime contract surface that IS
tracked deliberately lives in
[`docs/contracts/contract-catalog.md`](contracts/contract-catalog.md); the
architecture-of-record is
[`architecture/docs/L0/ARCHITECTURE.md`](../architecture/docs/L0/ARCHITECTURE.md).
