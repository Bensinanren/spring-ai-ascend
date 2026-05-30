# 0019. SuspendSignal: Checked-Exception Primitive and Sealed SuspendReason Taxonomy

**Status:** accepted (variant names AND the verbatim signature shape superseded by ADR-0146 — see "Post-2026-05-27 alignment note" below; the original Java sealed-type source block is drained to delegation per the note)
**Deciders:** architecture
**Date:** 2026-05-12
**Post-2026-05-27 alignment note:** Per the 2026-05-27 agent-service L1 architecture audit, both the variant **names** and the verbatim **signature shape** in this ADR's §Decision Outcome (Part 2) are superseded by [ADR-0146](0146-suspend-reason-taxonomy-alignment-2026-05-22.yaml), which codifies the canonical 6-variant set `{AwaitClientCallback, AwaitChildRun, AwaitToolResult, AwaitTimer, RequiresApproval, RateLimited}` per the 2026-05-22 expansion-proposal-response doc line 141. Name mapping: `ChildRun` → `AwaitChildRun`; `AwaitExternal` → `AwaitToolResult`; `AwaitApproval` → `RequiresApproval`. Fan-out variant `AwaitChildren(JoinPolicy)` is deferred to a follow-up ADR (not part of the canonical L1 set). User precedence rule: doc > ADR. ADR-0146 §6 records that ADR-0146 §1 supersedes this ADR's original 6-variant block; ADR-0146 consequences scheduled a Wave-3 amendment of this ADR to cross-link and stop carrying the stale shape. This note completes that amendment: the original `sealed interface SuspendReason permits …` source block (constructor parameter lists + `JoinPolicy` / `ChildFailurePolicy` enums) was a hand-frozen L7 signature snapshot that has since drifted from the as-built type — see the drain note in §Decision Outcome Part 2. The design *intent* (a sealed reason taxonomy, a per-reason deadline accessor, per-variant resume payloads) is retained here as historical record; the authoritative *signature shape* now lives only in the generated fact, the L2 spec, and ADR-0146.
**Technical story:** Third architecture reviewer raised two issues: (Issue 1) SuspendSignal as a checked exception poisons functional composition; (Issue 2) composition model supports only sequential parent→child nesting and cannot express fan-out. Self-audit surfaced three additional gaps: (HD-A.1) no suspend deadline, (HD-A.2) child failure propagation undefined, (HD-A.3) per-reason resume-payload schema missing. This ADR addresses all five through one cohesive design move.

## Context

`SuspendSignal extends Exception` (checked). It is thrown from `NodeFunction.apply`, `Reasoner.reason`,
`RunContext.suspendForChild`, `GraphExecutor.execute`, and `AgentLoopExecutor.execute` — then caught
exclusively inside `Orchestrator` (the catch/checkpoint/dispatch/resume loop). This is the runtime's
one interrupt primitive for both `GraphExecutor` (deterministic graph) and `AgentLoopExecutor`
(ReAct-style).

**Issue 1 claim**: exception semantics are "fundamentally at odds" with continuation/yield semantics,
and the checked form pollutes `throws` signatures and blocks higher-order composition.

**Issue 2 claim**: the composition primitive is unary (one parent waits for one child), blocking real
workflows that fan out to N parallel subtasks.

**Self-audit hidden defects:**
- **HD-A.1**: No max-suspend duration. A run suspended for an external approval that never arrives parks forever.
- **HD-A.2**: Child-run failure propagation to parent is undefined. `SyncOrchestrator.executeLoop` handles only the success path.
- **HD-A.3**: Per-reason resume-payload schema is unspecified — different reasons need structurally different resume keys (a child terminal, no payload, an approver decision, …), yet all resume payloads are currently typed as a bare `Object`. (The authoritative per-variant resume-payload contract that closes this gap is ADR-0146 §3; it is not restated here — see the drain note in §Decision Outcome Part 2.)

## Decision Drivers

- Java 21 (LTS) has no first-class coroutine or continuation primitive.
- Suspend points in this architecture are NOT arbitrary — they occur only at explicitly bounded
  `RunContext.suspendForChild` call sites. This is not "arbitrary yield."
- The `throws SuspendSignal` declaration on SPI boundary methods is a design feature: it forces callers
  (executors) to explicitly handle or propagate the signal, making suspend compile-time visible at the SPI.
- Functional composition (`map`, `filter`, `Stream`) over `NodeFunction` lambdas that throw checked
  exceptions is infeasible in Java. But the architecture does not require such composition — graph nodes
  are dispatched by name, not composed functionally.

## Considered Options

1. **Checked exception (current; this decision)** — keep `SuspendSignal extends Exception`; add sealed
   `SuspendReason` for typed-reason dispatch.
2. **Unchecked exception** (`extends RuntimeException`) — removes `throws` clause; makes suspend invisible
   to the compiler; suspend can propagate silently from any code path.
3. **Result-monad return type** — `NodeFunction.apply` returns `Result<Object, SuspendRequest>`. Forces
   every call site to handle `SuspendRequest`; high migration cost; no reduction in explicit handling burden.
4. **Virtual-thread parking** (Project Loom) — executor parks the carrier thread; requires a Loom
   scheduler; breaks the property that "executors do not persist or wait"; not portable to W4 Temporal.

## Decision Outcome

**Chosen option:** Option 1 — keep checked exception; add sealed `SuspendReason` taxonomy.

### Part 1 — Checked exception (Issue 1 verdict: reject primitive change / accept scoping)

`SuspendSignal` remains `extends Exception` (checked). The reviewer's claim that this is "fundamentally
at odds" with continuation semantics holds philosophically, but in Java this is the only option that:
- Makes every suspend site compile-time visible via the `throws` clause.
- Forces the orchestrator catch point to be exhaustive.
- Prevents accidental suspend propagation out of the orchestrator boundary into non-executor code.

**What we accept from Issue 1**: restrict `throws SuspendSignal` to five designated SPI boundary methods
only. An ArchUnit rule (`SuspendSignalBoundaryTest`) added at W2 alongside `HookChainConformanceTest`
asserts no class outside the orchestration SPI surface declares `throws SuspendSignal`.

### Part 2 — Sealed SuspendReason taxonomy (Issue 2 + HD-A.1 + HD-A.2 + HD-A.3)

Introduce `sealed interface SuspendReason` as the **boundary identity** for the suspend-reason
taxonomy: a sealed type, permitting one record variant per reason a run can park on, with a
per-reason deadline accessor (HD-A.1) and a per-variant resume-payload schema (HD-A.3). That
boundary identity — "a sealed `SuspendReason` carried by `SuspendSignal`, every variant exposing
when the suspension expires" — is the standing L0/L1 commitment (L0 §4 #19).

> **Drained — the variant signature shape is NOT carried here (layer-purity / Rule D-9).** This
> ADR originally inlined a full Java `sealed interface SuspendReason permits …` source block — the
> permits clause, every record's constructor parameter list, and the `JoinPolicy` /
> `ChildFailurePolicy` enums. That was a hand-frozen L7 method-signature snapshot. It is removed
> from this ADR body for two reasons: (1) ADR-0146 §6 superseded this ADR's original 6-variant
> block, renaming `ChildRun → AwaitChildRun`, `AwaitExternal → AwaitToolResult`,
> `AwaitApproval → RequiresApproval`, deferring `AwaitChildren` fan-out (and with it the `JoinPolicy`
> enum) to a follow-up ADR; (2) the as-built type has since diverged from the snapshot (the shipped
> records are `AwaitClientCallback`, `AwaitChild`, `AwaitTimer`, `AwaitExternal`, `AwaitApproval`,
> `RateLimited` with constructor shapes that the frozen block does not match). A frozen source block
> in an ADR cannot track that drift and would assert a stale, authoritative-looking signature shape.
>
> The authoritative signature shape lives ONLY in the layers below this ADR, per the L0 keep-list
> `migrate_to` for §4 #19:
> - **Generated facts (binding factual authority)** — the sealed interface and its variant records
>   are the SPI-shape facts `code-symbol/com-huawei-ascend-service-runtime-resilience-spi-suspendreason`
>   and its `…-suspendreason-<variant>` siblings in
>   `architecture/facts/generated/code-symbols.json`. The `deadline()` accessor name/return type and
>   each record's constructor descriptor are the `public_methods[]` / `record_components[]` entries
>   there, never an ADR commitment.
> - **Canonical names + per-variant resume payloads (L1 decision authority)** —
>   [ADR-0146](0146-suspend-reason-taxonomy-alignment-2026-05-22.yaml) §1 (the canonical 6-variant
>   set and its A2A `InterruptType` mapping) and §3 (per-variant resume-payload contract).
> - **Runtime sequence + collaboration anchors (L2 detail home)** —
>   `architecture/docs/L2/fp-suspend-resume/README.md`, which cites the same generated facts for the
>   suspend → resume method hops.

`SuspendSignal` is updated at W2 to carry a `SuspendReason` alongside its existing fields, and the
`final` modifier is removed so it becomes a concrete non-final class — the W0 constructor is retained
for backward compatibility and a second constructor accepting `SuspendReason` is added at W2 (the
exact constructor descriptors are the `SuspendSignal` SPI-shape fact, not restated here).

**W0 scope**: only the single-child-await variant is implemented in `SyncOrchestrator` (originally
named `ChildRun`; the canonical name is `AwaitChildRun` per ADR-0146). All other variants are
contract-level at W0. The sealed interface is defined in code at W2 alongside the async orchestrator.

The per-reason resume-payload schema (HD-A.3) is variant-specific (each reason implies its own resume
key — none, a child terminal, a tool result, an approver decision, …). The original per-variant
mapping table that stood here named the pre-ADR-0146 variants and is **not** restated, because it is
superseded: the authoritative per-variant resume-payload contract is
[ADR-0146](0146-suspend-reason-taxonomy-alignment-2026-05-22.yaml) §3, grounded in the
`SuspendReason` record-component facts in `architecture/facts/generated/code-symbols.json`.

### Consequences

**Positive:** (as argued at decision time, 2026-05-12; the fan-out variant was later deferred by ADR-0146 — see the alignment note)
- Fan-out was intended to be expressible at the contract level from W0 (the `AwaitChildren` fan-out variant; ADR-0146 subsequently deferred it to a follow-up ADR, so this benefit is not realized in the canonical L1 set).
- Every suspension carries a deadline — enabling a W2 watchdog sweeper to expire stuck runs.
- An explicit child-failure policy makes child-failure semantics per-call-site configurable.
- Per-variant resume-payload schema is self-documenting; orchestrators pattern-match on `SuspendReason`.

**Negative:**
- W0 reference impl cannot demonstrate fan-out (deferred to W2); taxonomy is design-only at W0.
- W2 `SuspendSignal` constructor update requires updating all `RunContext.suspendForChild` call sites.

### Reversal cost

Medium — changing the suspend primitive requires updating all executor implementations that throw
`SuspendSignal`. The sealed interface is additive; new variants can be added without breaking existing code.

## Pros and Cons of Options

### Option 1: Checked exception + sealed SuspendReason (chosen)
- Pro: compile-time visibility at every suspend site.
- Pro: typed taxonomy enables per-reason orchestrator logic.
- Con: `throws SuspendSignal` in lambda signatures blocks direct use with `java.util.function.Function`.

### Option 2: Unchecked exception
- Pro: no `throws` clause in SPI signatures.
- Con: suspend propagates silently through any code path, including non-executor code.

### Option 3: Result monad
- Pro: purely functional; no exceptions.
- Con: every node must return `Result<O, SuspendRequest>`; existing executor code must be rewritten.

### Option 4: Virtual-thread parking
- Pro: looks like blocking code; no special return type or throws.
- Con: requires Loom scheduler; complex lifecycle; not portable to W4 Temporal.

## References

- Third-reviewer document: `docs/logs/reviews/Architectural Perspective Review` (Issues 1, 2)
- Response document: `docs/logs/reviews/2026-05-12-third-reviewer-response.en.md` (Cat-A)
- §4 #19 (fan-out, suspend-reason taxonomy, suspend-deadline contract)
- `architecture-status.yaml` rows: `suspend_reason_taxonomy`, `parallel_child_dispatch`, `suspend_deadline_watchdog`
- W2 wave plan: `docs/archive/2026-05-13-plans-archived/engineering-plan-W0-W4.md` §4.2 (archived per ADR-0037)

### Authoritative `SuspendReason` shape (this ADR's signature block was drained here)

- Canonical variant names + per-variant resume-payload contract: [ADR-0146](0146-suspend-reason-taxonomy-alignment-2026-05-22.yaml) §1 / §3 (supersedes this ADR's original 6-variant block per ADR-0146 §6).
- Binding SPI-shape facts (sealed interface + variant records, deadline accessor, constructor descriptors): `architecture/facts/generated/code-symbols.json` — fact `code-symbol/com-huawei-ascend-service-runtime-resilience-spi-suspendreason` and its `…-suspendreason-<variant>` siblings.
- Runtime suspend → resume sequence + collaboration anchors: `architecture/docs/L2/fp-suspend-resume/README.md`.
