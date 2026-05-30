---
rule_id: G-28
title: "ADR Normalization"
level: L1
view: scenarios
principle_ref: P-C
authority_refs: [ADR-0160]
enforcer_refs: [E192, E193]
status: active
scope_phase: design
kernel_cap: 8
governance_infra: true
scope_surfaces:
  - docs/governance/adr-taxonomy.yaml
  - docs/governance/adr-governance-policy.yaml
  - docs/governance/adr-remediation-ledger.yaml
  - docs/adr/normalized
  - architecture/facts/generated/adrs.json
  - gate/lib/check_adr_taxonomy.py
  - gate/lib/check_historical_adr_governance.py
kernel: |
  Architecture review reads the NORMALIZED ADR view, not raw historical prose. Every raw ADR enumerated in `architecture/facts/generated/adrs.json` MUST have a ledger entry in `docs/governance/adr-remediation-ledger.yaml`, and every `accepted` ADR MUST have a normalized view at `docs/adr/normalized/ADR-NNNN.yaml` — this ledger-totality + normalized-view-coverage assertion (E193) is BLOCKING: the normalization wave back-filled the corpus to total coverage. Each normalized view MUST satisfy `docs/governance/adr-governance-policy.yaml` (required fields + the closed five-value `current_state` set + per-state field invariants) AND `docs/governance/adr-taxonomy.yaml` (the per-`decision_level` `decision_type` altitude — a forbidden lower-altitude `decision_type` is layer-purity leakage and is rejected); this per-view altitude validation (E192) runs changed-files-blocking — a PR may not ADD or WORSEN a taxonomy violation in a normalized view it touches. Ratchet: E193 is at the full-blocking rung; E192 has been promoted advisory → changed-files-blocking (THIS rung), then full-blocking (deferred) once every view is altitude-clean and the soak window closes.
---

# Rule G-28 — ADR Normalization

## What

Pins the normalized-ADR review surface as the single authority an architecture
review may cite. Two assertions, both keyed off the apex authority for the raw
ADR set (`architecture/facts/generated/adrs.json`, a generated fact projection
under the ADR-0154 / Rule G-15 cascade `generated facts > DSL > Card/prose`):

1. **Ledger totality** — every raw ADR in `adrs.json` has a matching entry (by
   `adr` id) in `docs/governance/adr-remediation-ledger.yaml`.
2. **Normalized-view coverage** — every `accepted` ADR has a normalized view at
   `docs/adr/normalized/ADR-NNNN.yaml`.

Each normalized view is validated against the two hand-authored governance
policy surfaces that together fix its shape and altitude:

- `docs/governance/adr-governance-policy.yaml` — the required-field list, the
  closed five-value `current_state` set (`active_guidance`, `partial_guidance`,
  `superseded`, `historical_evidence`, `remediation_record`), and the per-state
  field-presence invariants.
- `docs/governance/adr-taxonomy.yaml` — the closed `decision_level` key set, the
  per-level `decision_type` allow / forbid lists (the layer-purity altitude
  control), and the closed `affected_level_vocabulary`.

## Why

Closes the ADR-0160 review-blocking condition: a future architecture review
cannot reliably distinguish current decision authority from historical evidence
when it reads raw ADR prose, because a single raw ADR may mix decision levels
and time states. The normalized view is the de-noised, altitude-pure projection
the review cites; the ledger guarantees no raw ADR is silently un-governed. The
`decision_type`-altitude half is the ADR-lane analogue of the Rule G-27 layer
-purity verdict: a forbidden lower-altitude `decision_type` declared at a higher
`decision_level` is the same leak, expressed as data instead of prose.

## Authority

`docs/adr/0160-adr-governance-model.yaml` (status `accepted`) — the ADR
Governance Model. It establishes that architecture review reads the normalized
view; it defines the orthogonal `decision_level` (where a decision is made) vs
`affected_levels` (what it touches) axes; and it ratifies the five-value
`current_state` set. ADR-0160 extends the fact-layer authority of ADR-0154
(`adrs.json` is the authoritative raw-ADR enumeration) and reuses the Level x
View taxonomy of ADR-0068; it sits inside the ADR-0159 progressive-learning
-curve authority lanes (governance lane L8-GOVERNANCE). The two policy surfaces
and both gate helpers cite `authority: ADR-0160` as their normative source.

## Governed artifacts

- `architecture/facts/generated/adrs.json` — the apex raw-ADR enumeration the two
  assertions read from. Generated (never hand-edited); a vanished file is fatal.
- `docs/governance/adr-remediation-ledger.yaml` — one ledger row per raw ADR; the
  ledger-totality target.
- `docs/adr/normalized/ADR-NNNN.yaml` — the per-ADR normalized views; the
  coverage target and the unit each policy surface validates.
- `docs/governance/adr-governance-policy.yaml` — required fields, the closed
  five-value `current_state` set, and the per-state field-presence invariants.
- `docs/governance/adr-taxonomy.yaml` — the closed `decision_level` set, the
  per-level `decision_type` allow/forbid lists, and the `affected_level_vocabulary`.
- `gate/lib/check_adr_taxonomy.py` (E192) and
  `gate/lib/check_historical_adr_governance.py` (E193) — the executable
  consumers; they cross-reference the surfaces above and invent no ADR id and no
  relationship.

## Required behavior

- Every raw ADR id in `adrs.json` resolves to exactly one ledger row in
  `docs/governance/adr-remediation-ledger.yaml`.
- Every `accepted` ADR has a normalized view file at the canonical path
  `docs/adr/normalized/ADR-NNNN.yaml`.
- Every normalized view carries the required fields of
  `adr-governance-policy.yaml` and a `current_state` drawn from the closed
  five-value set.
- Each `current_state` satisfies its field-presence invariant: a `superseded`
  view declares `superseded_by`; a `partial_guidance` view declares
  `non_authoritative_legacy_content`; a `historical_evidence` view carries no
  `active_guidance`.
- Each view's `decision_type` is in the `allowed_decision_types` of its declared
  `decision_level` per `adr-taxonomy.yaml`.
- An architecture review cites the normalized view as authority, not the raw
  historical ADR prose.

## Forbidden behavior

- A raw ADR present in `adrs.json` with no ledger entry (un-governed raw ADR).
- An `accepted` ADR with no normalized view (uncovered decision authority).
- A normalized view whose `decision_type` is in the `forbidden_decision_types`
  for its `decision_level` — the layer-purity leak in the ADR lane.
- A normalized view that violates a per-state invariant (e.g. `superseded`
  without `superseded_by`, `partial_guidance` without a legacy-content split, a
  `historical_evidence` view still carrying `active_guidance`).
- Hand-editing `adrs.json`; it is a generated fact and only the extractor writes
  it. Its disappearance is fatal in every mode so a vanished apex fact never
  green-washes.

## How it works

The single gate Rule 144 invokes two helpers at different ratchet rungs:

- `gate/lib/check_adr_taxonomy.py` (E192) — runs **changed-files-blocking**
  (`--mode changed-files-blocking --base`): a PR may not ADD or WORSEN a taxonomy
  violation in a normalized view it touches; pre-existing findings on untouched
  views stay advisory, and when git cannot resolve the base the helper falls back
  to validating the whole corpus (a safe superset).
  Validates each normalized view against the two policy surfaces. A
  `decision_type` that is forbidden at its `decision_level` is reported as
  lower-altitude leakage; a `superseded` view with no `superseded_by`, a
  `partial_guidance` view with no `non_authoritative_legacy_content`, or a
  `historical_evidence` view that still carries `active_guidance` each violate a
  per-state invariant. PyYAML is required; its absence is a config error.
- `gate/lib/check_historical_adr_governance.py` (E193) — runs **blocking**
  (`--mode blocking`): ledger totality + normalized-view coverage. The
  normalization wave back-filled the ledger and `docs/adr/normalized/` to total
  coverage, so a non-zero helper rc — a raw ADR with no ledger entry, an
  `accepted` ADR with no normalized view, OR a vanished apex `adrs.json` (fatal
  in every mode) — now fails the gate.

Neither helper invents an ADR id or a relationship — they cross-reference the
generated raw-ADR set, the ledger, and the on-disk normalized views only. A
missing helper fails closed; a missing python interpreter is a vacuous pass
(Rule G-7 lists WSL as the canonical env).

## Enforcer command

Both helpers run under WSL/Linux per Rule G-7. The gate (Rule 144) drives the
helpers at their wired rungs — E192 changed-files-blocking, E193 blocking — but
each helper is directly runnable:

```bash
# E192 — per-view taxonomy + state invariants (gate wires it changed-files-blocking)
python3 gate/lib/check_adr_taxonomy.py --mode advisory
python3 gate/lib/check_adr_taxonomy.py --mode changed-files-blocking --base origin/main
python3 gate/lib/check_adr_taxonomy.py --mode full-blocking

# E193 — ledger totality + normalized-view coverage (gate wires it blocking)
python3 gate/lib/check_historical_adr_governance.py --mode advisory
python3 gate/lib/check_historical_adr_governance.py --mode blocking

# Both as the gate runs them, inside the architecture sync gate
bash gate/check_architecture_sync.sh
```

Note the two helpers use different mode vocabularies: the taxonomy helper takes
`advisory` / `changed-files-blocking` / `full-blocking`; the historical helper
takes `advisory` / `changed-files` / `blocking`.

## Ratchet

advisory → changed-files-blocking (a PR may not add or worsen a finding on a
changed view) → full-blocking (the terminal posture once the corpus is complete).
The helper modes (`--mode advisory` / `changed-files-blocking` / `full-blocking`
for the taxonomy helper; `advisory` / `changed-files` / `blocking` for the
historical helper) implement the rungs. The two helpers now sit at different
rungs: **E193 (historical, ledger totality + view coverage) is at the
full-blocking rung** — its corpus is complete; **E192 (taxonomy, per-view
altitude) is at the changed-files-blocking rung** (THIS rung), promoted from
advisory, with full-blocking deferred until every view is altitude-clean and the
soak window closes.

## Failure and remediation

| Symptom (gate / helper output) | Root cause | Remediation |
|---|---|---|
| `BLOCKING: raw ADR ADR-NNNN has no ledger entry` (E193) | A raw ADR in `adrs.json` is not yet governed | Add an `adr: ADR-NNNN` row to `docs/governance/adr-remediation-ledger.yaml` with its remediation state |
| `BLOCKING: accepted ADR ADR-NNNN has no normalized view` (E193) | An `accepted` ADR is uncovered | Author `docs/adr/normalized/ADR-NNNN.yaml` satisfying `adr-governance-policy.yaml` and `adr-taxonomy.yaml` |
| `ERROR: adrs.json not found` (E193, any mode) | The apex generated fact vanished or was hand-deleted | Re-run the fact extractor in check mode to regenerate `architecture/facts/generated/adrs.json`; never hand-create it |
| `G-28 docs/adr/normalized/ADR-NNNN.yaml: decision_type '<x>' ... forbidden ...` (E192; blocks when the view changed) | Lower-altitude `decision_type` leaked into a higher-altitude view | Re-classify the view to the `decision_level` whose `allowed_decision_types` includes `<x>`, or split the lower-altitude clause out to an L2/contract surface |
| `G-28 docs/adr/normalized/ADR-NNNN.yaml: 'superseded' requires 'superseded_by'` (E192; blocks when the view changed) | A per-state invariant is unmet | Add `superseded_by: ADR-MMMM` (or the missing field the named `current_state` requires) to the view |
| `... helper missing -- Rule G-28 / E19x` (gate) | A helper script is absent | Restore `gate/lib/check_adr_taxonomy.py` / `gate/lib/check_historical_adr_governance.py`; a missing helper fails closed by design |

Example — closing an altitude leak (E192, advisory):

```text
$ python3 gate/lib/check_adr_taxonomy.py --mode advisory
ADVISORY: docs/adr/normalized/ADR-0144.yaml decision_level=L0
  decision_type='persistence_schema' is forbidden at L0
  (allowed at: L2). 1 finding(s).
# Fix: the persistence-schema clause is L2 detail. Either set
# decision_level: L2 if the view is genuinely an L2 decision, or move the
# clause to architecture/docs/L2/<slug>/ + docs/contracts/ and keep the L0
# view to its global constraint, then re-run — 0 finding(s).
```

## Test fixtures

  - VALID  : a complete, in-altitude `active_guidance` view passes full-blocking
             with zero findings.
  - INVALID: an L2-altitude `decision_type` declared at `decision_level: L0`
             fails closed (layer-purity leakage).
  - INVALID: a `superseded` view missing `superseded_by` fails (state invariant).
  - INVALID: a `partial_guidance` view with no legacy-content split fails.
  - INVALID: a `historical_evidence` view that still carries `active_guidance`
             fails.
  - VALID  : advisory mode reports the leak but never blocks (exit 0).
  - VALID  : an accepted ADR with a ledger entry + normalized view passes
             blocking with full coverage.
  - INVALID: a raw ADR with no ledger entry fails closed (blocking).
  - INVALID: an accepted ADR with no normalized view fails closed (blocking).
  - VALID  : historical advisory mode reports the gaps but never blocks.
  - INVALID: a missing `adrs.json` fails closed (exit 1) even in advisory mode.

## Cross-references

  - ADR-0160 — ADR Governance Model (the normalized-view + ledger authority)
  - ADR-0154 — Fact-Layer Authority (`adrs.json` is the apex raw-ADR fact)
  - ADR-0159 — Progressive Learning Curve and Authority Lanes (the governance
    lane this rule lives in)
  - Rule G-15 — Fact-Layer Integrity (adrs.json is the apex raw-ADR fact)
  - Rule G-27 — Layer Purity (the L0/L1 prose analogue of the ADR-lane altitude
    control this rule enforces)
  - Rule G-22 / G-23 — EngineeringFrame frame-map / anchor governance (sibling
    accepted-ADR coherence rules)
