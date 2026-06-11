# spring-ai-ascend

> An open-source, enterprise-grade **agent platform** built for the Huawei **Ascend (NPU)** + **Kunpeng (CPU)** stack — on Spring AI, Spring Boot, and Java 21.

`spring-ai-ascend` lets a team stand up its own governed agent runtime the way it
would stand up a Spring Boot service: import the BoM, override the SPI beans you
care about, and ship. It is designed for self-hosting on Huawei silicon —
**Kunpeng** (ARM64) for the JVM service tier and **Ascend** NPUs for model
serving — so an enterprise can run the whole agent stack on its own hardware,
OSS-first, with no proprietary-cloud lock-in.

> **What runs today vs. what's on the roadmap.** The shipped runtime is a
> hardware-agnostic Spring AI / Java kernel — it runs on any JVM, and natively on
> Kunpeng/ARM64, so you develop and test anywhere. Ascend-NPU-optimised model
> serving and Kunpeng-tuned deployment profiles are the platform's **design
> target**, not yet shipped code. This boundary is marked honestly throughout;
> the machine-readable, per-capability ledger is
> [`docs/governance/architecture-status.yaml`](docs/governance/architecture-status.yaml).

## Why it's built this way

The platform optimises four pillars:

- **Performance** — a non-blocking run spine and parallel module build; the
  deployment target pairs Ascend NPU model serving with Kunpeng ARM throughput.
- **Cost** — OSS-first integration and self-hosting on commodity Kunpeng/Ascend
  hardware instead of metered proprietary services.
- **Developer onboarding** — extend via `@Bean` SPI overrides, exactly like
  Spring Boot; a runnable quickstart reaches a first agent run with no
  platform-team hand-holding.
- **Governance** — audit-grade evidence and posture-aware fail-closed defaults.
  Governance (gates, ADRs, enforcers) constrains the engineering **main-path**.

Measured baselines: [`docs/governance/competitive-baselines.yaml`](docs/governance/competitive-baselines.yaml).

## What you can build on it

- **Dual-mode orchestration.** One runtime runs both deterministic **graph**
  state machines and ReAct-style **agent loops**, sharing a single interrupt
  primitive (`SuspendSignal`). A graph node can call an agent loop, which can
  call another graph — arbitrary bidirectional nesting, one `Run` lineage
  throughout.
- **Pluggable by SPI, not by patching.** Memory, run persistence, model gateway,
  tool authorization, and resilience are SPI surfaces you implement and wire by
  dependency injection; in-memory reference implementations ship for local dev.
- **Multi-tenant + audit-grade.** Every run carries a tenant id; storage-engine
  isolation, tenant-scoped idempotency, and structured audit logging are
  first-class design concerns (the shipped idempotency tier is in-memory).
- **Posture-aware.** `dev` is permissive for fast iteration; `research`/`prod`
  fail closed at startup when required configuration is missing.

## Quick start

```bash
# Compile + unit + integration tests + the quality gate (the canonical command)
./mvnw -T 1C -Pquality verify
```

Use `verify`, not `test` — `test` skips the `*IT.java` integration enforcers.
`-T 1C` builds the reactor modules in parallel. Posture is selected by the
`APP_POSTURE` environment variable (`dev` / `research` / `prod`); `dev` allows
in-memory backends and only WARNs on missing config.

To run your first agent, follow [docs/quickstart.md](docs/quickstart.md): a
10-line echo `AgentRuntimeHandler`, booted with
`RuntimeApp.create(handler).run(LocalA2aRuntimeHost.port(8080))` and verified
with two `curl` calls against the A2A surface — no database, no LLM key.

## Architecture at a glance

The reactor ships **8 Maven modules**, each pinned to a deployment plane so
workloads with different runtime characteristics (latency-sensitive HTTP,
throughput-heavy ML, untrusted sandbox code) never share infrastructure:

| Module | Plane | What it does |
|--------|-------|--------------|
| `agent-runtime` | Compute & Control | Run-owning runtime SDK — framework-neutral engine SPI (`engine.spi.AgentRuntimeHandler` + `StreamAdapter`; openJiuwen, AgentScope, LangGraph adapters), `Run` lifecycle, A2A access, the OpenAI-compatible LLM egress gateway (`POST /v1/chat/completions`), and the bootable runtime app (`app.RuntimeApp` / `LocalA2aRuntimeHost`) |
| `agent-service` | Compute & Control | Spring-free serviceization façade — registration/discovery/route-grant SPIs with in-memory references, a byte-level A2A pass-through forwarder, and the runtime self-registration client |
| `agent-service-starter` | Compute & Control | Spring Boot edge for `agent-service` — auto-configured registration, discovery, route-grant, and A2A-forwarding HTTP controllers behind `@ConditionalOnMissingBean` seams |
| `agent-sdk` | Compute & Control | Declarative agent definition SDK — builds runnable `AgentRuntimeHandler`s from `ascend-agent/v1` YAML specs (model, prompt, tools, skills) |
| `agent-bus` | Bus & State Hub | Server→client callback surface (`bus.spi.s2c`) + the neutral `bus.spi.engine` execution contract |
| `springai-ascend-client` | (customer process) | Java A2A client SDK — `AscendA2aClient` facade with terminal-event semantics, auth helpers, trace propagation, and optional OTLP span telemetry |
| `springai-ascend-client-kotlin` | (customer process) | Kotlin idiom layer over the Java client SDK — coroutine `suspend` send/stream and the `ascendA2aClient {}` builder DSL |
| `spring-ai-ascend-dependencies` | (build-time) | Bill of Materials pinning all consumable reactor modules |

Each module declares its identity in `module-metadata.yaml` and its L1 design
under `architecture/docs/L1/<module>/`; platform modules additionally declare
five DFX dimensions in `docs/dfx/<module>.yaml`.
Cross-service traffic on the Bus & State Hub plane is sliced into three
physically isolated channels — `control` (PAUSE/KILL intents, never blocked),
`data` (run payloads), `rhythm` (heartbeats). The full system boundary, the
constraint corpus, and the SPI contracts live in [architecture/docs/L0/ARCHITECTURE.md](architecture/docs/L0/ARCHITECTURE.md);
the narrative tour is [docs/overview.md](docs/overview.md).

## Extending the platform

| You want to… | Do this | Entry point |
|---|---|---|
| Call a hosted agent from your application | Use `AscendA2aClient` (Java) or the `ascendA2aClient {}` DSL (Kotlin) — see [docs/quickstart.md §5b](docs/quickstart.md) | `springai-ascend-client`, `springai-ascend-client-kotlin` |
| Front a fleet of runtimes with a service edge | Add the starter; registration/discovery/route-grant controllers auto-configure | `agent-service-starter` |
| Declare an agent in YAML instead of code | Write an `ascend-agent/v1` spec; `AgentHandlerFactory.fromYaml(Path)` builds the handler | `agent-sdk` |
| Use Spring AI primitives directly | Use `ChatMemory` / `VectorStore` / `CrudRepository` without a starter | (no starter needed) |
| Pin versions and wire it yourself | Import the BoM only | `spring-ai-ascend-dependencies` |

## Posture model

| Posture | Behavior |
|---------|----------|
| `dev` (default) | Permissive — in-memory backends allowed; missing config emits WARN |
| `research` | Fail-closed — required config present or startup fails |
| `prod` | Fail-closed — same, with stricter enforcement planned |

Full matrix: [docs/architecture/l0/cross-cutting/posture-model.md](docs/architecture/l0/cross-cutting/posture-model.md).

## Architecture facts and version scope

The project keeps two related systems separate.

The architecture fact system is organized by L0/L1/L2 and 4+1 views. It answers
what the system is, which modules and state owners are allowed, which
cross-cutting constraints cannot be violated, and which ADRs or generated facts
are authoritative. This system lives under [`architecture/`](architecture/).

## Reading path

Architecture truth starts in [`architecture/`](architecture/). The
[`docs/`](docs/) tree contains contracts, ADRs, governance, evidence, proposals,
and history; it can support architecture claims, but it does not replace the
architecture-of-record.

Human contributors should read:

1. [`architecture/README.md`](architecture/README.md) - authority model,
   directory roles, and edit policy.
2. [`architecture/workspace.dsl`](architecture/workspace.dsl) - canonical model
   of structure and relationships.
3. [`architecture/docs/L0/ARCHITECTURE.md`](architecture/docs/L0/ARCHITECTURE.md)
   - declarative system boundary and platform constraints.
4. [`architecture/docs/L1/README.md`](architecture/docs/L1/README.md) - module
   design index.
5. [`docs/contracts/contract-catalog.md`](docs/contracts/contract-catalog.md),
   [`docs/adr/`](docs/adr/), and [`docs/governance/`](docs/governance/) when
   cited by canonical architecture.
6. [`docs/quickstart.md`](docs/quickstart.md) for operational onboarding.

AI assistants should read:

1. [`architecture/facts/generated/`](architecture/facts/generated/) before prose
   for factual claims about code, contracts, tests, modules, or runtime config.
2. [`architecture/workspace.dsl`](architecture/workspace.dsl), then
   [`architecture/docs/L0/ARCHITECTURE.md`](architecture/docs/L0/ARCHITECTURE.md)
   and the relevant [`architecture/docs/L1/`](architecture/docs/L1/) module.
3. [`docs/contracts/`](docs/contracts/), [`docs/adr/`](docs/adr/), and
   [`docs/governance/`](docs/governance/) only as supporting authority for the
   specific claim being checked.
4. [`docs/architecture/`](docs/architecture/), [`docs/logs/`](docs/logs/),
   [`docs/reviews/`](docs/reviews/), and [`docs/archive/`](docs/archive/) only
   as non-overriding context unless a canonical architecture file explicitly
   promotes a specific artifact.

## Legacy reading path (superseded)

Whether you are a new human contributor or an AI assistant, follow this order for an unbiased architecture picture. Each step names the surface's **rhetorical stance** so you don't conflate it with another slice.

1. **`architecture/workspace.dsl`** + **`architecture/README.md`** — the architecture authority (`唯一主入口` / sole main entry; ADR-0147 + ADR-0150). Structurizr DSL workspace carrying system/container/component structure, Feature/Capability/FunctionPoint instances, dependencies, contracts, decisions, and views.
2. **`architecture/docs/L0/ARCHITECTURE.md`** (this repo root, L0 frozen) — **declarative** L0 system boundary + 65 numbered architectural constraints (§4 #1..#65). What the platform commits to structurally.
3. **`CLAUDE.md`** — **enforceable** Layer-0 governing principles (P-A..P-M) + Layer-1 engineering rules (D-/R-/G-/M- namespace). Each rule cites the §4 constraint it enforces.
4. **`architecture/docs/L1/README.md`** — L1 module design index. Pick the module you're working on; read its `.md` or 4+1 directory.
5. **`docs/contracts/contract-catalog.md`** — **runtime promise** surface (HTTP API + SPI + envelopes + OpenAPI). What the system commits to at runtime.
6. **`docs/quickstart.md`** — **operational** onboarding (boot, post `POST /v1/runs`, observe).
7. **`docs/overview.md`** — narrative tour (after-the-fact prose for non-architecture readers).

These 7 surfaces present **distinct slices**: workspace (structure) → constraints (declarative) → rules (enforceable) → L1 (module design) → contracts (runtime) → quickstart (boot) → overview (narrative). Loading all 7 in order produces a complete, unbiased architecture understanding. Loading any one in isolation produces a partial view.

## Where to go next (cross-links beyond the Reading path)

- [docs/developer-handbook.md](docs/developer-handbook.md) — the single front-door manual for industry developers building agents and multi-agent systems on the platform.
- [docs/contracts/](docs/contracts/) — full contract corpus (each contract has authority ADR + enforcer).
- [docs/adr/README.md](docs/adr/README.md) — full Architecture Decision Records corpus (the canonical count lives in docs/governance/architecture-status.yaml#architecture_sync_gate.baseline_metrics; currently 65 active+locked ADRs but only the baseline_metrics number is authoritative).
- [docs/governance/architecture-status.yaml](docs/governance/architecture-status.yaml) — per-capability shipped/deferred ledger.
- [docs/governance/SESSION-START-CONTEXT.md](docs/governance/SESSION-START-CONTEXT.md) — same Reading path, expressed as an always-load table for AI sessions.

## Project status & governance

**L1 module-level architecture shipped.** The runtime kernel and L1 platform
composition (the A2A runtime surface with JWT validation + tenant cross-check,
idempotent `message/send` (in-memory tier), the LLM egress gateway, the
serviceization edge, the client SDKs, Code-as-Contract governance) are shipped;
later-wave capabilities — including durable Postgres-backed state and the
Ascend/Kunpeng-optimised deployment path — remain design contracts. Per-capability detail is the single source of truth in
[`docs/governance/architecture-status.yaml`](docs/governance/architecture-status.yaml).

A Code-as-Contract gate keeps the documentation and the code in lockstep and
fails closed on drift. Its current baseline:
**65 §4 constraints · 65 ADRs · 32 active gate rules · 102 gate self-tests**,
plus 13 Layer-0 governing principles, 53 active engineering rules, 41 enforcer
rows, and a 381-node / 573-edge architecture graph — all maintained in
[`docs/governance/architecture-status.yaml#architecture_sync_gate.baseline_metrics`](docs/governance/architecture-status.yaml)
(the canonical source for every count); see [gate/README.md](gate/README.md) for
how it runs.

Release history and per-wave change declarations live in
[docs/logs/releases/](docs/logs/releases/).
