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
- **Governance** — audit-grade evidence, posture-aware fail-closed defaults, and
  a Code-as-Contract gate that keeps the docs and the code honest.

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
  isolation, durable idempotency, and structured audit logging are first-class.
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
in-memory backends and only WARNs on missing config. The full
boot-and-first-run walkthrough is in [docs/quickstart.md](docs/quickstart.md).

## Architecture at a glance

The runtime is split across **8 Maven modules**, each pinned to exactly one of
five deployment planes so workloads with different runtime characteristics
(latency-sensitive HTTP, throughput-heavy ML, untrusted sandbox code) never
share infrastructure:

| Module | Plane | What it does |
|--------|-------|--------------|
| `agent-client` | Edge Access | Client SDK surface (skeleton; W3+) |
| `agent-service` | Compute & Control | HTTP edge + runtime kernel — `Run` / `RunStateMachine`, the run HTTP API, JWT/tenant/idempotency/posture, and the core SPIs |
| `agent-execution-engine` | Compute & Control | Engine adapter + orchestration SPIs, `EngineRegistry`, `EngineEnvelope` |
| `agent-middleware` | Compute & Control | `RuntimeMiddleware` SPI + hook dispatch |
| `agent-bus` | Bus & State Hub | Cross-plane control surfaces (client→server ingress, server→client callback) |
| `agent-evolve` | Evolution | ML / self-improvement pipeline (skeleton) |
| `spring-ai-ascend-dependencies` | (build-time) | Bill of Materials |
| `spring-ai-ascend-graphmemory-starter` | Bus & State Hub | Graph-memory auto-config starter |

Each module declares its identity in `module-metadata.yaml` (whose
`architecture_doc` field points at its L1 design under
`architecture/docs/L1/<module>/`, indexed by `architecture/docs/L1/README.md`),
and its five DFX dimensions in `docs/dfx/<module>.yaml`.
Cross-service traffic on the Bus & State Hub plane is sliced into three
physically isolated channels — `control` (PAUSE/KILL intents, never blocked),
`data` (run payloads), `rhythm` (heartbeats). The full system boundary, the
constraint corpus, and the SPI contracts live in [architecture/docs/L0/ARCHITECTURE.md](architecture/docs/L0/ARCHITECTURE.md);
the narrative tour is [docs/overview.md](docs/overview.md).

## Extending the platform

| You want to… | Do this | Entry point |
|---|---|---|
| Plug in a graph-memory backend | Implement `GraphMemoryRepository`; the starter auto-wires it | `spring-ai-ascend-graphmemory-starter` |
| Use Spring AI primitives directly | Use `ChatMemory` / `VectorStore` / `CrudRepository` without a starter | (no starter needed) |
| Pin versions and wire it yourself | Import the BoM only | `spring-ai-ascend-dependencies` |

## Posture model

| Posture | Behavior |
|---------|----------|
| `dev` (default) | Permissive — in-memory backends allowed; missing config emits WARN |
| `research` | Fail-closed — required config present or startup fails |
| `prod` | Fail-closed — same, with stricter enforcement planned |

Full matrix: [docs/cross-cutting/posture-model.md](docs/cross-cutting/posture-model.md).

## Reading path

> **AI agents and new engineers start here.** The canonical, product-first
> orientation order is [`docs/onboarding/ai-understanding-path.md`](docs/onboarding/ai-understanding-path.md)
> (machine-readable source of truth: [`docs/governance/ai-reading-path.yaml`](docs/governance/ai-reading-path.yaml);
> authority ADR-0159 + ADR-0160). It walks the eight-node systems-engineering
> deliverable chain **product-first** — Product Definition → Requirement
> Definition → L0 Architecture → EngineeringFrame → Feature/FunctionPoint →
> Contract Surface → Implementation Facts → Verification & Gate — so you reach
> code only after product, requirement, and architecture. **Skipping the product
> step to read code first is forbidden**: an agent that reads code before product
> builds the wrong thing efficiently.

The six reading steps below mirror that canonical path; the YAML is authoritative
if the two ever disagree. Each step names the surfaces to read and its authority
lane, so you don't conflate one slice with another. The one-way authority cascade
across every surface is **generated facts > DSL > Card/prose**: for any claim about
code, contracts, tests, dependencies, runtime behavior, or verification, read
`architecture/facts/generated/*.json` BEFORE prose and cite the fact id.

1. **Repository entry** (orientation) — `README.md` + `CLAUDE.md` + `AGENTS.md`. How this team (humans + AI) collaborates, which sources are authoritative, and when generated facts outrank prose.
2. **Product definition** (Product → Requirement, ISO/IEC/IEEE 29148) — `product/PRODUCT.md` + `product/claims.yaml` + `product/requirements.yaml` + `product/personas.yaml` + `product/journey.md`. The product outcome, the active value claims, in-scope requirements vs explicit non-goals, and where the v1.0 financial-vertical line is drawn.
3. **Architecture anchor** (L0, ISO/IEC/IEEE 42010 + C4) — `architecture/workspace.dsl` + `architecture/README.md` + `architecture/docs/L0/ARCHITECTURE.md` + `docs/adr/normalized/index.yaml` + `docs/adr/review-index.md`. The system boundary, architecture element identities from the DSL, the numbered constraint corpus (§4), and the current decision-authority state of the ADR corpus (ADR-0160 — use the normalized index for current authority, not raw historical prose).
4. **EngineeringFrame anchor** (C4 Component / arc42 L2 — a Java package-cluster anchor) — `architecture/features/engineering-frames.dsl` + `architecture/docs/L1/engineering-frames.md` + `architecture/docs/L1/frames/` + `architecture/docs/L1/` (per-module L1 design, indexed by `architecture/docs/L1/README.md`). Which EngineeringFrames exist, which package-cluster each anchors, each frame's stable boundary + usable SPI surface, and which FunctionPoints it anchors. A Feature *traverses* a frame; it never owns one (ADR-0157).
5. **Demand-to-behavior mapping** (Feature + FunctionPoint join) — `architecture/features/features.dsl` + `architecture/features/function-points.dsl` + `architecture/docs/L2/`. Which Features require which FunctionPoints (value axis), which EngineeringFrames anchor them (structure axis), and which L2 designs own the implementation detail.
6. **Contract and evidence** (Contract Surface → Implementation Facts → Verification & Gate) — `docs/contracts/contract-catalog.md` + `docs/contracts/` + `architecture/facts/generated/` + `gate/README.md` + `gate/check_architecture_sync.sh` + `docs/governance/architecture-status.yaml`. The runtime promises, the generated facts that prove current implementation state (cite fact ids, not prose), and the gates plus current baselines.

These six steps present **distinct slices** at descending altitude — orientation → product → L0 architecture → structural frame → behavioral join → contract/evidence. Loading them in order produces a complete, unbiased picture; loading any one in isolation produces a partial view. `docs/quickstart.md` (boot + first run) and `docs/overview.md` (narrative tour) are entered from step 6 once you need to run or narrate the system.

## Where to go next (cross-links beyond the Reading path)

- [docs/contracts/](docs/contracts/) — full contract corpus (each contract has authority ADR + enforcer).
- [docs/adr/README.md](docs/adr/README.md) — full Architecture Decision Records corpus (the canonical count lives in docs/governance/architecture-status.yaml#architecture_sync_gate.baseline_metrics.adr_count; that field is the sole authority — this entry intentionally carries no raw number so it cannot drift).
- [docs/governance/architecture-status.yaml](docs/governance/architecture-status.yaml) — per-capability shipped/deferred ledger.
- [docs/governance/SESSION-START-CONTEXT.md](docs/governance/SESSION-START-CONTEXT.md) — same Reading path, expressed as an always-load table for AI sessions.

## Project status & governance

**L1 module-level architecture shipped.** The W0 runtime kernel and L1 platform
composition (JWT validation, tenant cross-check, durable idempotency, posture
boot guard, the W1 run HTTP API, Code-as-Contract governance) are shipped; W2–W4
capabilities — including the Ascend/Kunpeng-optimised deployment path — remain
design contracts. Per-capability detail is the single source of truth in
[`docs/governance/architecture-status.yaml`](docs/governance/architecture-status.yaml).

A Code-as-Contract gate keeps the documentation and the code in lockstep and
fails closed on drift. Its current baseline:
**65 §4 constraints · 139 ADRs · 164 active gate rules · 340 gate self-tests**,
plus 13 Layer-0 governing principles, 62 active engineering rules, and 199 enforcer rows, with a 702-node / 1365-edge architecture graph — all maintained in
[`docs/governance/architecture-status.yaml#architecture_sync_gate.baseline_metrics`](docs/governance/architecture-status.yaml)
(the canonical source for every count); see [gate/README.md](gate/README.md) for
how it runs.

Release history and per-wave change declarations live in
[docs/logs/releases/](docs/logs/releases/).
