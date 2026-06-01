# openJiuwen Echo Agent Sample

A minimal **layer ③ agent app** (see engine model design §1.1 / §10.5) that runs a
concrete openJiuwen `ReActAgent` through the engine's openJiuwen adapter against a
real LLM endpoint.

This module is intentionally **not** part of the root Maven reactor — it ships as a
sample. Build and test it explicitly with `-f`.

## Layers

- **① framework** — `com.openjiuwen:agent-core-java` provides `ReActAgent` / `Runner`.
- **② engine** — `agent-service` provides the `OpenJiuwenAgentHandler` (how to run an
  agent and map results to events) and the `OpenJiuwenAgentFactory` seam.
- **③ this sample** — `EchoOpenJiuwenAgentFactory` defines *what* the agent is: its
  prompt, iteration budget, and model.

## Configure the LLM

The factory reads connection settings from environment variables. Copy the template
and/or export the variables:

```bash
export OJW_MODEL_PROVIDER=openai
export OJW_API_BASE=http://localhost:4000/v1
export OJW_API_KEY=sk-REPLACE_ME
export OJW_MODEL_NAME=gpt-5.4-mini
export OJW_SSL_VERIFY=false
```

`apiconfig.json` is git-ignored; never commit a resolved key. Use
`apiconfig.json.template` as a reference for the framework's native config keys.

## Build (offline, no LLM)

```bash
# Ensure agent-service and the framework are installed to your local .m2 first:
mvn -q -pl agent-service -am -DskipTests install
mvn -q -f samples/openjiuwen-echo-agent/pom.xml compile
```

## Run the real-LLM smoke test

The smoke integration test is tagged `smoke` and drives a real ping through the
engine handler. With the environment variables above exported:

```bash
mvn -f samples/openjiuwen-echo-agent/pom.xml test -Dgroups=smoke
```

If the endpoint is unreachable the test is skipped (via `assumeTrue`), so it never
fails the build on machines without access to the gateway.
