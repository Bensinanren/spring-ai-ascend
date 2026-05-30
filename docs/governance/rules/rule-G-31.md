---
rule_id: G-31
title: "AI Reading Path Integrity"
level: L1
view: scenarios
principle_ref: P-C
authority_refs: [ADR-0159]
enforcer_refs: [E198]
status: active
scope_phase: design
kernel_cap: 8
governance_infra: true
scope_surfaces:
  - docs/governance/ai-reading-path.yaml
  - docs/onboarding/ai-understanding-path.md
  - README.md
  - AGENTS.md
  - CLAUDE.md
  - docs/governance/SESSION-START-CONTEXT.md
  - gate/lib/check_ai_reading_path.py
kernel: |
  The product-first eight-node entry path an AI agent (or a new engineer) reads to reach a top-down, unbiased understanding of the platform is declared in the data file `docs/governance/ai-reading-path.yaml` and mirrored in its human-readable companion `docs/onboarding/ai-understanding-path.md`. Rule G-31 asserts that path is MATERIALIZABLE and that the entry documents route a reader onto it; it invents no id and no relationship and never outranks a surface it points at (cascade: generated facts > DSL > Card/prose). It runs three checks: SURFACE EXISTENCE — every `orientation_learning_path` surface marked `presence: present` resolves to a file or directory on disk (a `presence: planned` surface MAY be absent — it names a governed target landing in a later wave), and the `companion_human_form` mirror plus every `entry_contract.factual_claim_switch.read_before_prose` generated-fact file exist (`MISSING-SURFACE` / `MISSING-COMPANION` / `MISSING-FACT-FILE`); ENTRY-DOC ROUTING — every step-1 `repository_entry` document (`README.md` / `CLAUDE.md` / `AGENTS.md`) plus the always-load `docs/governance/SESSION-START-CONTEXT.md` references either the data file or its companion, so a reader who lands on any entry doc finds the canonical order (`MISSING-MARKER`); YAML↔COMPANION LOCKSTEP — the companion back-references the data file and carries a heading for every step the YAML declares, so step coverage cannot silently drift (`LOCKSTEP-BROKEN` / `LOCKSTEP-STEP`). The single gate Rule 148 invokes `gate/lib/check_ai_reading_path.py` (E198), which reads the data file + the companion as the navigation/identity authority and the disk as the factual authority. `docs/governance/ai-reading-path.yaml` is greenfield-vacuous when it is absent; the instant it exists it MUST parse, and its companion + factual-claim-switch fact files MUST be readable, or the check fails closed (exit 2) in every mode — a missing authority is never an advisory condition. Runs CHANGED-FILES-BLOCKING at this rung per the ADR-0159 §13.3 ratchet (advisory → changed-files-blocking → full-blocking, the terminal rung once the entry-doc corpus has migrated from the legacy architecture-first reading path to this product-first chain): a PR may not ADD or WORSEN a finding once it touches the path's authoring surfaces (the data file, the companion, or a step-1 entry doc), and a finding blocks only when those surfaces changed relative to `--base` (default `origin/main`, else `HEAD`) — the path is a single shared navigation surface, so a change to any of them re-scopes the whole path; pre-existing findings while those surfaces are untouched stay advisory. `full-blocking` (deferred) blocks on any finding. A missing helper fails closed; a missing python interpreter is a vacuous pass (Rule G-7 lists WSL as the canonical env).
---

# Rule G-31 — AI Reading Path Integrity

## What

Asserts that the product-first eight-node reading path — the order an AI agent or
a new engineer follows to reach an unbiased, top-down picture of the platform — is
**materializable on disk** and that the **entry documents route a reader onto it**.
The path is the executable form of the ADR-0159 eight-node chain
`ProductClaim → Requirement → L0 Constraint/ADR → EngineeringFrame (package-cluster)
→ FunctionPoint (method) → Contract → CodeFact/TestFact → Gate`, read product-first
so an agent that reaches code does so only after product, requirement, and
architecture. The order, the per-node external standard, and the surfaces that
realise each node are declared in the data file `docs/governance/ai-reading-path.yaml`
(the machine-readable source) and mirrored in `docs/onboarding/ai-understanding-path.md`
(the human-readable companion); this rule reads them, never mints them.

The rule runs three checks over those two surfaces and the disk:

- SURFACE EXISTENCE. Every surface listed under `orientation_learning_path` whose
  `presence` is `present` resolves to an existing file or directory; a surface
  marked `presence: planned` MAY be absent (a governed target that lands in a later
  wave is still listed so the path stays stable across the wave that creates it).
  The `companion_human_form` mirror and every
  `entry_contract.factual_claim_switch.read_before_prose` generated-fact file MUST
  exist. → `MISSING-SURFACE` / `MISSING-COMPANION` / `MISSING-FACT-FILE`.
- ENTRY-DOC ROUTING. Every step-1 `repository_entry` document (`README.md` /
  `CLAUDE.md` / `AGENTS.md`) and the always-load
  `docs/governance/SESSION-START-CONTEXT.md` references either the data file
  (`docs/governance/ai-reading-path.yaml`) or its companion
  (`docs/onboarding/ai-understanding-path.md`), so a reader who lands on any entry
  doc can find the canonical order. → `MISSING-MARKER`.
- YAML↔COMPANION LOCKSTEP. The companion back-references the data file, and carries
  a heading for every step the YAML declares (the two files declare in their own
  headers that they stay in lockstep — same steps, same order, same surfaces). →
  `LOCKSTEP-BROKEN` (no back-reference) / `LOCKSTEP-STEP` (a declared step has no
  companion heading).

The authority direction is fixed and one-way; the rule reads two navigation
surfaces and the disk, and asserts none of its own:

    ADR-0159
      -> docs/governance/ai-reading-path.yaml          (the path: order + surfaces)
        -> docs/onboarding/ai-understanding-path.md     (the human-readable mirror)
          -> the disk (every `present` surface + fact file resolves)
            -> the entry docs (route onto the path)
              -> gate Rule 148 / E198                   (this check)

## Why

ADR-0159 makes the progressive learning curve the binding delivery model and fixes
the reading order product-first: an agent that reads code before product builds the
wrong thing efficiently. The 2026-05-29 engineering-governance systemic-remediation
review (section 7) accepted that model only with a standing guard — without one, the
declared reading path could name a surface that does not exist, an entry document
could fail to route a reader onto the path (leaving the legacy architecture-first
order in place), or the machine-readable YAML and its human-readable companion could
drift apart, and the corpus would claim an onboarding discipline it had not earned.
This rule closes those risks structurally: a dangling reading-path surface, an entry
doc that does not point at the canonical order, and a YAML/companion lockstep break
are findings the gate reports (and, at the blocking rungs, a PR may not ADD), rather
than silent gaps. The reading path stays subordinate to the authority spine — it
records the ORDER over the lanes; it never asserts a fact, and it never outranks a
surface it points at (cascade: generated facts > DSL > Card/prose).

## How it works

The single gate Rule 148 invokes one helper:

- `gate/lib/check_ai_reading_path.py` (E198) — loads the data file, projects the
  `orientation_learning_path` steps (each step's `surfaces[]` carry a `path` + a
  `presence`), the `companion_human_form`, and the
  `entry_contract.factual_claim_switch.read_before_prose` fact files, then runs the
  three checks above against the disk and the entry docs. It reports, surface- and
  doc-oriented, a short machine code per finding (`MISSING-SURFACE`,
  `MISSING-COMPANION`, `MISSING-FACT-FILE`, `MISSING-MARKER`, `LOCKSTEP-BROKEN`,
  `LOCKSTEP-STEP`). It invents no ID and no relationship and never outranks a
  generated fact — it is a classifier over the declared path against the disk.

Entry documents. The entry-doc set is DERIVED from the data file: it is the
`surfaces[]` of the `repository_entry` step (step 1), plus the always-load
`docs/governance/SESSION-START-CONTEXT.md` appended (it is an orientation surface
the data file does not list as a step-1 surface). The marker is satisfied by a
reference to EITHER the data file OR its companion — either pointer routes a reader
onto the canonical order.

Greenfield / vacuity posture. `docs/governance/ai-reading-path.yaml` is vacuously
clean when it does not exist (the path has not been authored yet). The instant it
exists it MUST parse, and its companion + factual-claim-switch fact files MUST be
readable, or the check fails closed (exit 2) in EVERY mode including advisory — a
path cannot be judged against authorities that vanished, so a missing authority is
never an advisory condition.

## Ratchet

advisory → changed-files-blocking (THIS rung: a PR may not ADD or WORSEN a finding
once it touches the path's authoring surfaces — the data file, the companion, or a
step-1 entry doc; because the path is a single shared navigation surface, a change
to any of them re-scopes the whole path; the scope derives from `--base`, default
`origin/main`, else `HEAD` — the same git-deriving pattern as Rule 145 / E194
`check_layer_purity.py`, Rule 146 / E196 `check_frame_card_consistency.py`, and
Rule 147 / E197 `check_feature_readiness.py`; pre-existing findings while those
surfaces are untouched stay advisory) → full-blocking (DEFERRED — the terminal
posture once the entry-doc corpus has migrated from the legacy architecture-first
reading path to this product-first chain and the path is clean). The helper
`--mode` flags (`advisory` / `changed-files-blocking` / `full-blocking`) implement
the rungs. Promotion to the full-blocking rung lands when the entry-doc corpus is
fully migrated and the soak window closes; a missing helper fails closed; a missing
python interpreter is a vacuous pass (Rule G-7 lists WSL as the canonical env).

## Test fixtures

  - VALID  : an absent data file is vacuously clean (greenfield) — every mode passes
             with zero findings.
  - VALID  : a complete repo (every `present` surface resolves, the companion +
             fact files exist, every entry doc references the path, the companion
             back-references the YAML and covers every step) passes full-blocking
             with zero findings.
  - VALID  : a `presence: planned` surface that is absent on disk produces no
             finding (planned surfaces are not required to exist yet).
  - INVALID: a missing `present` surface yields `MISSING-SURFACE`; a missing
             companion yields `MISSING-COMPANION`; a missing
             `read_before_prose` fact file yields `MISSING-FACT-FILE`.
  - INVALID: an entry doc (or the session doc) that references neither the data file
             nor the companion yields `MISSING-MARKER`.
  - INVALID: a companion that does not back-reference the data file yields
             `LOCKSTEP-BROKEN`; a companion missing a heading for a declared step
             yields `LOCKSTEP-STEP`.
  - VALID  : advisory mode reports findings but never blocks (exit 0) — the
             helper retains its advisory rung even though the gate now wires it
             changed-files-blocking.
  - VALID  : the canonical gate `gate/check_architecture_sync.sh` invokes the helper
             `--mode changed-files-blocking --base` (never `--mode advisory`) — the
             wrapper-posture lock for the W27 promotion (full-blocking deferred).
  - INVALID: a present-but-malformed data file (e.g.
             `orientation_learning_path` not a list) fails closed (exit 2) in every
             mode including advisory — a missing/broken authority is never advisory.

## Cross-references

  - ADR-0159 — Progressive Learning Curve and Authority Lanes (the authority this
    rule enforces: the product-first eight-node chain and the §13.3 ratchet, now at
    the changed-files-blocking rung)
  - ADR-0160 — ADR Normalization (the normalized-ADR index the path routes step 3
    to for current decision authority)
  - ADR-0154 — Fact-Layer Authority (the cascade `generated facts > DSL > Card/prose`
    the path never outranks; the factual-claim switch the path declares routes a
    reader to the generated facts before prose)
  - Rule G-30 — FunctionPoint Readiness (the sibling readiness gate over the same
    eight-node chain; this rule guards the reading ORDER, that one guards the per-node
    completeness)
  - Rule G-27 — Layer Purity (the layer-purity lane invariant the path's "not here"
    annotations operationalise: L0/L1 surfaces carry boundaries, not L2/code detail)
  - Rule G-7 — Linux-First Dev Environment (the helper is run via WSL/Linux; a
    missing python interpreter is a vacuous pass)
