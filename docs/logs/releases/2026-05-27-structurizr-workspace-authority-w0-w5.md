---
level: L0
view: scenarios
status: shipped
authority: "ADR-0149 (W0..W5 shipped record)"
---

# 2026-05-27 — Structurizr Workspace Authority W0..W5 Shipped

> **Historical artifact frozen at SHA 825a8e2** (W7 closure commit on the W7 branch). Baseline counts in §0 reflect the W7 snapshot (134 ADRs / 623 nodes / 1188 edges). The current canonical baseline post-W8 (135 ADRs / 626 nodes / 1197 edges / 34 families) lives in `docs/governance/architecture-status.yaml#architecture_sync_gate.allowed_claim`; see `docs/logs/releases/2026-05-27-structurizr-w8-docs-consolidation.md` for the W8 update.

**Branch:** `feature/structurizr-workspace-authority`
**Commits:** `9611096..1026bbc` (six waves), plus `16cbe27` (W6 roadmap),
plus this commit (W7 closure ADR + release note).

## 0. Canonical baselines (per Gate Rule 28)

| Metric | Value |
|---|---|
| §4 constraints | 65 |
| ADRs | 134 |
| gate rules | 143 |
| self-test cases | 260 |
| Layer-0 governing principles | 13 |
| active engineering rules | 43 |
| enforcer rows | 176 |
| Maven XML-counted tests | 461 |
| architecture graph nodes | 623 |
| architecture graph edges | 1188 |
| recurring defect families | 34 |
| workspace elements | 555 |
| workspace relationships | 389 |

These match `docs/governance/architecture-status.yaml#architecture_sync_gate.baseline_metrics`.

**Canonical baseline phrasing** (matches Gate Rule 28 grep):
65 §4 constraints · 134 ADRs · 143 active gate rules · 260 gate self-tests ·
13 Layer-0 governing principles · 43 active engineering rules · 176 enforcer rows ·
461 Maven XML-counted tests · 623 architecture graph nodes / 1188 edges ·
34 recurring defect families.

## What shipped

The architecture authority chain pivots from "many source YAML files
generate `docs/governance/architecture-graph.yaml`" to
"`architecture/workspace.dsl` and its closure ARE the architecture
authority; graph YAMLs are generated compatibility projections".

| Wave | Commit | Headline deliverable |
|---|---|---|
| W0 | `9611096` | Spike on `architecture-spike/` proves DSL+profile+idempotency (4 PASS gates). ADR-0147 (authority transfer) + ADR-0148 (spike evidence) land. |
| W1 | `3a8d4fe` | `tools/architecture-workspace/` Maven module (NOT a reactor module) + profile YAMLs + workspace.dsl skeleton + advisory gate + 141-rule impact matrix. |
| W2 | `ebf7533` | Authored zone: 152 L1 capabilities mounted from architecture-status.yaml + 15 hand-authored function points + 7 test elements + L1 narrative for each module. |
| W3 | `d36303f` | Generated zone: 7 fragment emitters (modules / spi-catalog / enforcers / principles / rules / adr-graph / surface-classification) + byte-identical regeneration gate. 349 generated elements. |
| W4 | `8520aaa` | Compatibility projection (`workspace.dsl -> architecture-workspace-graph.yaml`) + informational equivalence check vs legacy graph (43.4% node overlap). |
| W5 | `1026bbc` | Authority transfer. Rule G-1.b amended to "Architecture Workspace Truth"; ARCHITECTURE.md §65 reworded; CLAUDE.md + CONTRIBUTING.md + AGENTS.md + SESSION-START-CONTEXT.md updated; gate/check_architecture_workspace.sh flips to BLOCKING (default); gate/check_architecture_sync.sh invokes the workspace gate after rule loop. |
| W6 doc | `16cbe27` | Sunset roadmap (4 sub-waves, 8.5-week schedule); reverse-emitter code blocked until 2026-06-10 (post-soak). |
| W7 (this) | TBD | Closure ADR-0149 + this release note + F-architecture-authority-fragmentation family pre-registration. Actual rule retirements (Rule 38 / 106.a) happen post-W6.d soak. |

## Baseline at W5

```
Workspace:
  Elements:                555 (547 custom + 8 native)
  Relationships:           389
  Generated fragments:     7 (byte-identical on re-run)
  Profile violations:      0

Coverage vs legacy graph:
  Workspace projection:    555 nodes / 374 edges
  Legacy graph:            622 nodes / 1182 edges
  Type overlap:            capability 152 vs 2 (workspace strong),
                           function_point 15 vs 0,
                           generated_projection 25 vs 0,
                           rule 43 vs 60 (legacy strong — sub-clauses),
                           enforcer 0 vs 175 (saa.kind taxonomy diff;
                                              reconciled in W6.d).
  Normalised id overlap:   270 shared (43.4% of legacy)
```

## Bugs caught and fixed

The migration shook out four classes of bug at the design boundary
(documented in ADR-0148 empirical findings and W2/W3/W5 commit
messages):

1. Original expert proposal's `customElement` DSL keyword is wrong —
   actual token is `element`.
2. `Model.getElements()` in structurizr-dsl 6.2.1 INCLUDES
   CustomElements (CustomElement extends GroupableElement extends
   Element). Walking both `getElements()` and `getCustomElements()`
   double-counts. Validator must walk `getElements()` exactly once.
3. `!identifiers hierarchical` scopes identifiers to parent containers;
   model-block-level relationships referencing nested-container short
   identifiers fail to resolve. W1+ uses default flat scope.
4. Structurizr DSL parser does not properly escape `{` inside quoted
   strings in relationship descriptions; doesn't accept single-line
   `properties { "k" "v" }` inside relationship blocks; doesn't
   support forward-references inside `!include` order.

The W5 commit landed the first idempotency win in production: editing
CLAUDE.md Rule G-1 changed RulesFragmentEmitter output by design, the
blocking gate detected the SHA-256 drift on the very first run, and
one re-emit command fixed it. This is the round-trip the migration was
designed to make trivial.

## What is NOT yet shipped

- W6 sub-wave reverse emitters (workspace -> enforcers.yaml etc.).
  Blocked until at least 2026-06-10 (W5 soak ends). See
  `docs/governance/structurizr-workspace-w6-sunset-roadmap.md`.
- L1 Jinja2 template rewire (originally W4; moved to W6.d).
- Rule 38 / Rule 106.a retirement candidates (post-W6.d).
- `gate/build_architecture_graph.py` removal (W7 cleanup, conditional
  on the Wave 1 impact-matrix consumer review).

## How to use the workspace

```bash
# Build the tools (one-time):
./mvnw -f tools/architecture-workspace/pom.xml clean install

# Re-emit the generated zone:
java -cp tools/architecture-workspace/target/classes:$(cat /tmp/cp.txt) \
  com.huawei.ascend.tools.architecture.fragment.AllFragmentsCli --repo .

# Validate the workspace:
./mvnw -f tools/architecture-workspace/pom.xml exec:java \
  -Dexec.args="validate architecture/workspace.dsl"

# Emit the compatibility projection:
./mvnw -f tools/architecture-workspace/pom.xml exec:java \
  -Dexec.args="project architecture/workspace.dsl docs/governance/architecture-workspace-graph.yaml"

# Run the blocking gate (Linux/WSL):
bash gate/check_architecture_workspace.sh
```

## Authority chain after W5

```
architecture/workspace.dsl
         |
         |--- profile/             (12 SAA tags, 15 saa.rel types, required-properties.yaml)
         |--- features/            (capabilities.dsl, function-points.dsl, verification.dsl) AUTHORED
         |--- docs/L1/             (one .md per module) AUTHORED
         |--- decisions/           (ADR import target) AUTHORED
         |--- views/               (L0-system-context.dsl, L1-development.dsl) AUTHORED
         |--- generated/           (modules.dsl + 6 more, NEVER hand-edit) GENERATED
         |
         |--- (W4 projection) ---> docs/governance/architecture-workspace-graph.yaml
         |--- (legacy, W7-retirement-candidate) ---> docs/governance/architecture-graph.yaml
         |--- (W6.a sunset target) <--- docs/governance/enforcers.yaml
         |--- (W6.b sunset target) <--- docs/governance/principle-coverage.yaml
         |--- (W6.c sunset target) <--- docs/governance/architecture-status.yaml#capabilities

gate/check_architecture_workspace.sh — blocking-mode default at W5+
gate/check_architecture_sync.sh    — invokes the workspace gate after rule loop
```

## Cross-references

- ADR-0147 — authority transfer decision
- ADR-0148 — Wave 0 spike measured evidence
- ADR-0149 — this release closure ADR
- D:\.claude\plans\structurizr-d-chao-workspace-spring-ai-adaptive-hellman.md — full migration plan
- docs/governance/structurizr-workspace-w6-sunset-roadmap.md — W6 sub-waves and soak schedule
- docs/governance/architecture-workspace-impact-matrix.yaml — 141 rules × authority-surface impact
