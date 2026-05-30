#!/usr/bin/env bash
# Auto-extracted from gate/check_architecture_sync.sh by gate/lib/extract_rules.sh
# Rule 151 — status_claim_altitude. DO NOT HAND-EDIT — re-run extract_rules.sh to refresh.
# Authority: PR-E5.

# Rule 151 — status_claim_altitude (enforcer E201, kernel Rule G-34)
#
# Authority: ADR-0159 (Progressive Learning Curve and Authority Lanes) §7 (layer
# purity as a lane invariant) + §9 (the enforcement-posture ratchet). One
# CHANGED-FILES-BLOCKING helper that extends the adjudicated layer-purity verdict
# (the "L0/L1 carries L2/code detail" critique is TRUE) to the capability-status
# ledger's free-form narrative fields (allowed_claim + the sibling note). Per the
# authority cascade (generated facts > DSL > Card/prose) the ledger OUTRANKS the
# L0/L1 prose Rule G-27 guards, so the same altitude verdict applies at least as
# strongly: a narrative field MUST NOT carry an L1..L8 leaked category, it should
# NAME the capability identity + cite its ADR/enforcer (D1/D2/D3) and POINT at the
# detail's home. The helper invents no id and no relationship and never outranks a
# generated fact (cascade: generated facts > DSL > Card/prose):
#   * gate/lib/check_status_claim_altitude.py (E201, slug status_claim_altitude) —
#     loads the SHARED leaked-category vocabulary from
#     docs/governance/layer-purity-policy.yaml and IMPORTS the canonical trigger
#     library (TRIGGERS + the _is_d3_enforcer_citation carve-out) from
#     gate/lib/check_layer_purity.py (one verdict, one probe set, three surfaces),
#     co-scans every allowed_claim AND note value (both are un-altitude-guarded
#     narrative on the same surface — scoping to allowed_claim alone left a
#     by-file-type hole at field granularity; recording each value's capability
#     key + leaking field + source line), and reports a leaked-category trigger
#     not redeemed by a still-open, capability-matched row in the per-surface
#     grandfather list
#     docs/governance/layer-purity-status-ledger-grandfather.yaml. Each grandfather
#     row is CAPABILITY-PRECISE (it pins the ledger capability key it freezes) so a
#     row tolerates exactly one claim and retiring it re-arms the gate on that
#     claim. It does NOT edit the ledger (owned by the status-ledger reconcile
#     step) and does NOT scan the ledger's structural keys (implementation/tests
#     file lists, l0_decision, enforcer rows — the D2 evidence index).
# Runs CHANGED-FILES-BLOCKING here (`--mode changed-files-blocking --base`): a PR
# that edits docs/governance/architecture-status.yaml may not ADD or worsen a
# claim-altitude leak; pre-existing (grandfathered) leaks in an untouched ledger
# stay advisory. This is the Phase-2 rung: advisory -> changed-files-blocking
# (this rung) -> full-blocking (the terminal rung once every allowed_claim is
# altitude-clean and every status-ledger grandfather row is retired; gated by the
# same clean-workspace + fact-byte-identity condition as Rule G-27 under the
# ADR-0159 §9 ratchet). The helper self-derives the in-scope decision from git against --base; base
# ref = BASE_REF (default origin/main) when resolvable, else HEAD. An un-importable
# trigger library, a zero-claim scan, an unknown cited category, or a
# capability-less ledger row is a config error (exit 2) — the gate fails closed
# rather than scan vacuously or with a drifting private probe copy. A missing
# helper fails closed; a missing python interpreter is a vacuous pass (Rule G-7
# lists WSL as canonical).
#
# scope_surfaces: docs/governance/architecture-status.yaml, docs/governance/layer-purity-policy.yaml, docs/governance/layer-purity-status-ledger-grandfather.yaml, gate/lib/check_status_claim_altitude.py, gate/lib/check_layer_purity.py
# ---------------------------------------------------------------------------
_r151_fail=0
_r151_helper="gate/lib/check_status_claim_altitude.py"
# Resolve the base ref for the changed-files scope (same pattern as Rule 149 / E199).
_r151_base="${BASE_REF:-origin/main}"
if ! { command -v git >/dev/null 2>&1 && git rev-parse --verify "$_r151_base" >/dev/null 2>&1; }; then
  _r151_base="HEAD"
fi
if [[ ! -f "$_r151_helper" ]]; then
  fail_rule "status_claim_altitude" "$_r151_helper missing -- Rule G-34 / E201"
  _r151_fail=1
elif [[ -z "$GATE_PYTHON_BIN" ]]; then
  : # vacuous pass on hosts without python (Rule G-7 lists WSL as canonical env)
else
  _r151_out=$("$GATE_PYTHON_BIN" "$_r151_helper" --mode changed-files-blocking --base "$_r151_base" 2>&1)
  _r151_rc=$?
  # A non-zero rc is EITHER a blocked in-scope finding (exit 1 — a non-grandfathered
  # claim-altitude leak while the ledger changed) OR a CONFIG ERROR (exit 2 — an
  # un-importable trigger library, a zero-claim scan, an unknown cited category, or
  # a capability-less ledger row). Both fail the rule; the config-error message is
  # surfaced verbatim when present.
  if [[ $_r151_rc -ne 0 ]]; then
    _r151_err=$(printf '%s' "$_r151_out" | grep -E 'config error' | head -1)
    if [[ -n "$_r151_err" ]]; then
      fail_rule "status_claim_altitude" "${_r151_err} -- Rule G-34 / E201"
    else
      _r151_sum=$(printf '%s' "$_r151_out" | grep -E 'finding\(s\)' | tail -1)
      _r151_hits=$(printf '%s' "$_r151_out" | grep -E '^status-claim-altitude .*\[capabilities\.' | grep -v 'GRANDFATHERED' | head -5)
      fail_rule "status_claim_altitude" "changed capability ledger adds a blockable claim-altitude leak (Rule G-34 / E201): ${_r151_sum:-status-claim-altitude helper exited $_r151_rc}${_r151_hits:+ || ${_r151_hits}}"
    fi
    _r151_fail=1
  else
    _r151_sum=$(printf '%s' "$_r151_out" | grep -E 'finding\(s\)' | tail -1)
    [[ -n "$_r151_sum" ]] && echo "OK (Rule G-34 / E201 changed-files-blocking): $_r151_sum"
  fi
fi
[[ $_r151_fail -eq 0 ]] && pass_rule "status_claim_altitude"

