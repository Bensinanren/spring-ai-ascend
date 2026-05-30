---
rule_id: G-34
title: "Status-Ledger Claim Altitude"
level: L1
view: development
principle_ref: P-C
authority_refs: [ADR-0159]
enforcer_refs: [E201]
status: active
scope_phase: design
kernel_cap: 8
governance_infra: true
scope_surfaces:
  - docs/governance/architecture-status.yaml
  - docs/governance/layer-purity-policy.yaml
  - docs/governance/layer-purity-status-ledger-grandfather.yaml
  - gate/lib/check_status_claim_altitude.py
  - gate/lib/check_layer_purity.py
kernel: |
  The free-form NARRATIVE fields of the capability-status ledger `docs/governance/architecture-status.yaml` — the `allowed_claim` values AND the sibling `note` values — are an altitude-governed surface, NOT un-governed prose. Per the authority cascade (generated facts > DSL > Card/prose) the capability ledger OUTRANKS the L0/L1 architecture prose, so the same layer-purity verdict Rule G-27 enforces on L0/L1 documents applies at least as strongly here: a narrative field MUST NOT carry L2 runtime implementation detail — method call chains, runtime sequences, SQL / RLS / GUC / persistence DDL or semantics, HTTP status / route-verb / header behaviour, filter ordering, wire formats, method signatures, or test-class inventories. That detail belongs at `architecture/docs/L2/<slug>/`, the contract surfaces (`docs/contracts/`), and the generated facts (`architecture/facts/generated/`); a narrative field should NAME the capability identity + cite its ADR / enforcer (D1/D2/D3, never reported) and POINT at the detail's home, not restate it. The single gate Rule 151 invokes `gate/lib/check_status_claim_altitude.py` (E201), which co-scans every `allowed_claim` AND `note` value (both are un-altitude-guarded narrative on the same surface, so scoping to `allowed_claim` alone left a by-file-type hole at field granularity), reuses the SAME closed leaked-category vocabulary as Rule G-27 (`docs/governance/layer-purity-policy.yaml`, leaked `L1`..`L8`) by IMPORTING the canonical trigger library from `gate/lib/check_layer_purity.py` (one verdict, one probe set, three surfaces), and reports a leak that is not redeemed by a still-open dated row in the per-surface grandfather list `docs/governance/layer-purity-status-ledger-grandfather.yaml`. Each grandfather row is CAPABILITY-PRECISE (it pins the ledger capability key it freezes) so a row tolerates exactly one claim and retiring it re-arms the gate on that claim — a row that omits its capability is a config error (every leak on this single surface shares one file, so file+category alone is too coarse). E201 invents no id and no relationship and never outranks a generated fact: it reads the policy as the vocabulary and the ledger as the data. It does NOT edit the ledger (owned by the status-ledger reconcile step) and does NOT scan the ledger's structural keys (`implementation` / `tests` file lists, `l0_decision`, enforcer rows) — those are the development-view evidence index (D2), not a behavioural claim. Runs CHANGED-FILES-BLOCKING: a PR that edits the ledger may not ADD or worsen a claim-altitude leak; pre-existing (grandfathered) leaks in an untouched ledger stay advisory. Ratchet: advisory → changed-files-blocking → full-blocking, the terminal rung gated by the same clean-workspace + fact-byte-identity condition as Rule G-27 per ADR-0159 §9.
---

# Rule G-34 — Status-Ledger Claim Altitude

## What

Extends the adjudicated layer-purity verdict (the "L0/L1 carries L2/code detail"
critique is TRUE) to the capability-status ledger
`docs/governance/architecture-status.yaml`. The ledger's free-form NARRATIVE
fields — `allowed_claim` (a capability's behavioural claim) and the sibling
`note` (its decision/status narrative) — are strings with no altitude guard, and
per the authority cascade `generated facts > DSL > Card/prose` the ledger
OUTRANKS the L0/L1 architecture prose — so a runtime-detail leak in either field
is at least as significant as the L0/L1 prose leaks Rule G-27 already gates.
Rule G-34 makes those fields altitude-governed: each MUST carry only the
capability identity (a structural noun + its ADR / enforcer citation) and POINT
at the detail's authoritative home, never restate the L2 mechanism. E201
co-scans both fields with one trigger library — scoping to `allowed_claim` alone
left a by-file-type hole at field granularity (a future `note` that inlined SQL /
route / method detail would be invisible while an identical `allowed_claim` leak
blocked).

The leaked categories are exactly the closed `L1`..`L8` set Rule G-27 fixes
(`docs/governance/layer-purity-policy.yaml`); the defensible-and-never-reported
set is the same `D1`/`D2`/`D3` (SPI boundary identity, development-view package /
file index, ArchUnit / enforcer citation).

## Why

Closes the systemic-remediation verdict's **by-authority-surface gap**. The two
existing layer-purity helpers scope EXCLUSIVELY to the L0/L1 markdown prose:

- `gate/lib/check_layer_purity.py` (E194) → `architecture/docs/L0|L1/**/*.md`
- `gate/lib/check_l2_detail_sink.py` (E195) → the same two roots

The only gate that touched `architecture-status.yaml` (enforcer E120) checks
deleted-MODULE-NAMES in `allowed_claim`, not claim ALTITUDE. So the `allowed_claim`
field — a higher-authority surface than the prose those helpers guard — was never
scanned for the leaked categories, and it organically accreted them: SQL /
`ON CONFLICT` / `PRIMARY KEY` (L3); HTTP status codes + route-verbs + wire error
tokens (L4); `@Order` tie-break + `Class.getName()` (L5); `DROP_OLDEST` buffer +
W3C `traceparent` + `OTLP/HTTP` (L6); method signatures + call chains (L1/L7);
enumerated `*IT` test inventories (L8). The 2026-05-29 convergence-scan round-2
verdict named ten such loci; a mechanical scan over the field finds nineteen
(the named set plus same-family siblings). The remediation's
`layer-purity-scan.md` EXPLICITLY declared this surface "out of scope … owned by
other waves/subtasks," but no wave took ownership and the two advisory gates only
read L0/L1 markdown — so the family was cleaned on the readable-interpretation
layer (prose) while the same categories persisted, ungoverned, on a
higher-authority surface. Rule G-34 lands the missing mechanism so the leak is a
gate-able defect with a permanent home for the migrated detail and a permanent
rule against re-introducing it, exactly as Rule G-27 does for the prose.

## Authority

`docs/adr/0159-progressive-learning-curve-and-authority-lanes.yaml` (status
`accepted`) §7 (layer purity as a lane invariant) + §9 (the enforcement-posture
ratchet). Rule G-34 is the status-ledger analogue of Rule G-27's L0/L1 prose
purity and Rule G-28's ADR-lane altitude control — the same verdict, applied to a
third surface. The policy surface and the helper cite `Authority: ADR-0159`.

## Governed artifacts

- `docs/governance/architecture-status.yaml` — the capability ledger; the scan
  target. Its `allowed_claim` AND `note` narrative fields are co-scanned; its
  structural keys are not. Owned by the status-ledger reconcile step; E201 never
  edits it.
- `docs/governance/layer-purity-policy.yaml` — the SHARED owns/forbids category
  vocabulary (leaked `L1`..`L8`, defensible `D1`..`D3`). Single-source with
  Rule G-27; E201 imports its executable trigger library from
  `gate/lib/check_layer_purity.py` rather than copy it.
- `docs/governance/layer-purity-status-ledger-grandfather.yaml` — the closed,
  per-entry-dated, per-surface grandfather list. Each row freezes one ledger
  claim's leak by capability key and declares a `sunset_date`. SEPARATE from the
  L0/L1 list (`layer-purity-temporary-violations.yaml`) because the two surfaces
  have different owners and gates; a row here is inert for E194/E195.
- `gate/lib/check_status_claim_altitude.py` (E201) — the executable consumer; it
  invents no id and no relationship and never outranks a generated fact.
- `gate/lib/check_layer_purity.py` — the source of the SHARED trigger library
  (`TRIGGERS` + the `_is_d3_enforcer_citation` carve-out) E201 imports.

## Required behavior

- Every narrative-field value (`allowed_claim` + `note`) names the capability
  identity (D1/D2) and, where it cites a mechanism, the enforcing ArchUnit test /
  gate rule (D3) — and POINTS at the detail's home (`docs/contracts/`,
  `architecture/docs/L2/`, `architecture/facts/generated/`) rather than restating
  it.
- Each grandfather row pins the ledger `capability` key it freezes, cites the
  leaked category E201 reports for that claim, and declares a `sunset_date`.
- E201 judges the ledger by the SAME leaked-category vocabulary and trigger
  library Rule G-27 applies to the L0/L1 prose, across BOTH narrative fields.

## Forbidden behavior

- A narrative field (`allowed_claim` or `note`) that restates an `L1`..`L8`
  leaked category (a call chain, a runtime sequence, SQL / RLS / GUC / persistence
  DDL, an HTTP status / route-verb / header behaviour, a filter order, a wire
  format, a method signature, or a test-class inventory) with no still-open
  grandfather row.
- A grandfather row that targets the ledger but omits its `capability` key — too
  coarse to attribute, so E201 rejects it (config error).
- Hand-widening the leaked-category vocabulary in E201 by copying a private
  trigger set: the probes MUST be the imported `check_layer_purity.py` library so
  the three surfaces stay one verdict (an un-importable library is a config
  error, never a private-copy scan).
- Scanning or editing the ledger's structural keys (`implementation` / `tests`
  file lists, `l0_decision`, enforcer rows) — those are the D2 evidence index.

## How it works

The single gate Rule 151 invokes one helper at the changed-files-blocking rung:

- `gate/lib/check_status_claim_altitude.py` (E201) — loads the leaked-category
  vocabulary from `layer-purity-policy.yaml`, IMPORTS the canonical trigger
  library (`TRIGGERS` + `_is_d3_enforcer_citation`) from
  `gate/lib/check_layer_purity.py`, walks the parsed ledger for every narrative
  string in `NARRATIVE_FIELDS` (`allowed_claim` + `note`), recording each value's
  capability key + leaking field + source line, and reports a leaked-category
  trigger not redeemed by a still-open capability-matched row in the per-surface
  grandfather list. The L8 D3 enforcer-citation carve-out is the imported one, so
  a narrative field that cites its enforcing `*ArchTest` / `*IT` as a mechanism
  (not a catalogue) is spared identically to the L0/L1 prose. PyYAML is required
  (the gate already hard-requires it); an un-importable trigger library, a
  zero-value scan, an unknown cited category, or a capability-less ledger row is a
  config error (exit 2) — the gate fails closed rather than scan vacuously or with
  a drifting private probe copy.

The helper neither edits an authority surface, mints an id, nor replaces a
generated fact — it classifies the ledger's claim prose against the shared policy
vocabulary only.

## Ratchet

advisory → **changed-files-blocking (this rung)**: a PR that edits the ledger may
not ADD or worsen a claim-altitude leak; a leak in an untouched ledger (and a
still-open grandfather row) stays advisory → full-blocking (the terminal posture
once every `allowed_claim` is altitude-clean and every status-ledger grandfather
row is retired). The helper `--mode` (`advisory` / `changed-files-blocking` /
`full-blocking`) flags implement the rungs; in changed-files-blocking the helper
blocks only when `docs/governance/architecture-status.yaml` is itself in the
changed set (vs `--base`, default `origin/main`), and fails closed (scans the
whole ledger) when git cannot resolve the base. Promotion to full-blocking is
gated by the same clean-workspace + fact-byte-identity condition as Rule G-27 per
ADR-0159 §9. A missing helper fails closed; a missing python interpreter is a
vacuous pass (Rule G-7 lists WSL as the canonical env).

## Enforcer command

The helper runs under WSL/Linux per Rule G-7. The gate (Rule 151) drives it at
the changed-files-blocking rung, but it is directly runnable:

```bash
# E201 — capability-ledger claim altitude (gate wires it changed-files-blocking)
python3 gate/lib/check_status_claim_altitude.py --mode advisory
python3 gate/lib/check_status_claim_altitude.py --mode changed-files-blocking --base origin/main
python3 gate/lib/check_status_claim_altitude.py --mode full-blocking

# As the gate runs it, inside the architecture sync gate
bash gate/check_architecture_sync.sh
```

## Failure and remediation

| Symptom (helper output) | Root cause | Remediation |
|---|---|---|
| `status-claim-altitude <ledger>:<line> [<capability> <field>] L<n> (...): <label>` (blocks when the ledger changed) | A narrative field (`allowed_claim` or `note`) restates a leaked `L1`..`L8` category | Move the detail to its home (`docs/contracts/` / `architecture/docs/L2/` / `architecture/facts/generated/`) and reduce the field to the capability identity + a pointer; or, if not yet migrated, add a dated capability-precise row to `layer-purity-status-ledger-grandfather.yaml` |
| `... row '<id>' targets <ledger> but declares no 'capability' key` (exit 2) | A grandfather row omits its capability key | Add `capability: <ledger key>` so the row freezes exactly one claim |
| `... cannot import the shared trigger library ... from gate/lib/check_layer_purity.py` (exit 2) | The canonical probe source is missing/renamed | Restore `gate/lib/check_layer_purity.py`; E201 refuses to scan with a private probe copy that could drift |
| `... discovered zero allowed_claim values ...` (exit 2) | A schema/path drift emptied the scan set | Restore the ledger schema (capability rows with `allowed_claim`); a vacuous scan is never a pass |
| `... row '<id>' cites category '<x>' absent from ... categories` (exit 2) | A typo'd leaked-category id in a row | Use a valid `L1`..`L8` id from `layer-purity-policy.yaml` |
| `... NOTE: grandfather row <id> expired ... and matched no live leak` | A sunset passed and the row tolerates nothing | Remove the dead row from `layer-purity-status-ledger-grandfather.yaml` |

## Test fixtures

  - VALID  : advisory mode reports the ledger leaks but ALWAYS exits 0 (soak).
  - INVALID: full-blocking with the grandfather list emptied fails closed — the
             scan is non-vacuous (it sees the live leaks).
  - INVALID: a leak that lives in a `note` field (not `allowed_claim`) is detected
             and fails full-blocking — both narrative fields are co-scanned.
  - VALID  : a capability-matched open row freezes a `note`-field leak exactly as
             it freezes an `allowed_claim` leak (the matcher is field-agnostic).
  - VALID  : full-blocking with every live leak frozen by a still-open,
             capability-matched row passes (0 findings, all grandfathered).
  - INVALID: a grandfather row targeting the ledger with no `capability` key is a
             config error (exit 2) — capability-precise matching is mandatory.
  - INVALID: a row whose `sunset_date` has passed no longer suppresses its leak;
             full-blocking fails closed and a prune NOTE is emitted.
  - INVALID: a row citing a leaked-category id absent from the policy vocabulary
             is a config error (exit 2) — the typo guard.
  - VALID  : an `allowed_claim` that NAMES a capability + cites its enforcing
             `*ArchTest` as the mechanism (not a catalogue) is D3-defensible and
             never reported (the imported E194 carve-out).
  - VALID  : changed-files-blocking with the ledger UNCHANGED leaves every leak
             advisory (exit 0); it blocks only when the ledger is in the changed
             set.

## Cross-references

  - ADR-0159 — Progressive Learning Curve and Authority Lanes (§7 layer purity,
    §9 enforcement-posture ratchet)
  - Rule G-27 — Layer Purity (the L0/L1 prose verdict this rule extends to the
    status ledger; the SHARED leaked-category vocabulary + trigger library)
  - Rule G-28 — ADR Normalization (the ADR-lane altitude analogue: a forbidden
    lower-altitude `decision_type` in a normalized view is the same leak as a
    forbidden category in an `allowed_claim`)
  - Rule G-15 — Fact-Layer Integrity (the cascade `generated facts > DSL >
    Card/prose` under which the capability ledger outranks the L0/L1 prose, so a
    leak here is at least as significant)
  - Rule G-7 — Linux-First Dev Environment (the helper runs via WSL/Linux; a
    missing python interpreter is a vacuous pass)
