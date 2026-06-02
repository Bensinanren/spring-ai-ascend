# Agent Service OpenJiuwen A2A E2E Sample

This sample models the intended two-sided use of `agent-service`.

For the agent developer, the application depends on two frameworks:

1. `agent-service`, which hosts the service runtime and exposes A2A.
2. `com.openjiuwen:agent-core-java`, which builds the actual ReAct agent.

For the final user, the service exposes only A2A:

1. Discover the agent card from `/.well-known/agent-card.json`.
2. Send a JSON-RPC `message/stream` request to `/a2a`.
3. Read the SSE stream until the agent emits its terminal response.

The test starts the sample service, hosts a real openJiuwen `ReActAgent` inside
agent-service, and calls it through an A2A JSON-RPC client. The client never
uses agent-service internal Java APIs or polls internal task state.

## Manual Verification

Install the current `agent-service` snapshot first. This sample is outside the
root Maven reactor, so it resolves `agent-service` from the local Maven
repository:

```bash
./mvnw -pl agent-service -am -DskipTests install
```

Start the sample service in one terminal:

```bash
export SAA_SAMPLE_LLM_API_KEY="<your local gateway key>"
./mvnw -f samples/agent-service-a2a-llm-e2e/pom.xml spring-boot:run
```

Start the A2A console client in another terminal:

```bash
./mvnw -f samples/agent-service-a2a-llm-e2e/pom.xml \
  exec:java \
  -Dexec.mainClass=com.huawei.ascend.samples.a2a.A2aConsoleClientApplication
```

Then type:

```text
ping
```

The client discovers `/.well-known/agent-card.json`, sends the message through
the A2A SDK streaming client, and prints the agent response. Type `exit` to
close the client.

If the service runs on a different address, pass it as the first client
argument:

```bash
./mvnw -f samples/agent-service-a2a-llm-e2e/pom.xml \
  exec:java \
  -Dexec.mainClass=com.huawei.ascend.samples.a2a.A2aConsoleClientApplication \
  -Dexec.args="http://localhost:18080"
```

## Automated Test

The sample is not part of the root Maven reactor. Run it explicitly:

```bash
export SAA_SAMPLE_LLM_API_KEY="<your local gateway key>"
./mvnw -f samples/agent-service-a2a-llm-e2e/pom.xml test
```

Defaults match the local OpenAI-compatible endpoint used for this E2E:

```text
SAA_SAMPLE_OPENJIUWEN_MODEL_PROVIDER=openai
SAA_SAMPLE_OPENJIUWEN_API_BASE=http://localhost:4000/v1
SAA_SAMPLE_LLM_MODEL=gpt-5.4-mini
SAA_SAMPLE_OPENJIUWEN_SSL_VERIFY=false
```

The test is skipped when `SAA_SAMPLE_LLM_API_KEY` is not set, so normal builds do
not require a local LLM gateway.
