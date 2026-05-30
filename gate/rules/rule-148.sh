#!/usr/bin/env bash
# Auto-extracted from gate/check_architecture_sync.sh by gate/lib/extract_rules.sh
# Rule 148 — ai_reading_path. DO NOT HAND-EDIT — re-run extract_rules.sh to refresh.
# Authority: PR-E5.

# Rule 148 — ai_reading_path (enforcer E198, kernel Rule G-31)
#
# Authority: ADR-0159 (Progressive Learning Curve and Authority Lanes — the
# product-first eight-node entry chain). One CHANGED-FILES-BLOCKING helper that
# checks the reading path declared in docs/governance/ai-reading-path.yaml (and its
# human-readable companion docs/onboarding/ai-understanding-path.md) is
# materializable and the entry docs route a reader onto it. The reading-path
# data file invents no id and no relationship — it records the ORDER over the
# lanes; this check asserts none of its own and never outranks a surface it
# points at (cascade: generated facts > DSL > Card/prose):
#   * gate/lib/check_ai_reading_path.py (E198, slug ai_reading_path) — three
#     checks: SURFACE EXISTENCE (every orientation_learning_path surface marked
#     presence: present resolves on disk; planned surfaces may be absent; the
#     companion mirror + every factual_claim_switch.read_before_prose fact file
#     exist), ENTRY-DOC ROUTING (each step-1 repository_entry doc + the always-
#     load docs/governance/SESSION-START-CONTEXT.md references the data file or
#     its companion), YAML<->COMPANION LOCKSTEP (the companion back-references
#     the YAML and carries a heading per declared step). Findings:
#     MISSING-SURFACE / MISSING-COMPANION / MISSING-FACT-FILE / MISSING-MARKER /
#     LOCKSTEP-BROKEN / LOCKSTEP-STEP.
# Runs CHANGED-FILES-BLOCKING here (`--mode changed-files-blocking --base`): a PR
# may not ADD or WORSEN a path finding once it TOUCHES the reading-path authoring
# surfaces (the data file / its companion / a step-1 entry doc); pre-existing
# findings while those surfaces are untouched stay advisory. This is the Phase-2
# rung: advisory -> changed-files-blocking (this rung) -> full-blocking (the
# terminal rung once the entry-doc corpus has migrated from the legacy
# architecture-first reading path to this product-first chain, the path is clean,
# and the soak window closes). The helper self-derives the in-scope decision from
# git against --base (same git-deriving pattern as Rule 146 / E196); base ref =
# BASE_REF (default origin/main) when resolvable, else HEAD.
# docs/governance/ai-reading-path.yaml is greenfield-vacuous until it is authored;
# the instant it exists it MUST parse and its companion + factual-claim-switch fact
# files MUST be readable or the helper fails closed (exit 2) in every mode — a
# missing authority is never an advisory condition. A missing helper fails closed;
# a missing python interpreter is a vacuous pass (Rule G-7 lists WSL as the
# canonical env).
#
# scope_surfaces: docs/governance/ai-reading-path.yaml, docs/onboarding/ai-understanding-path.md, README.md, AGENTS.md, CLAUDE.md, docs/governance/SESSION-START-CONTEXT.md, gate/lib/check_ai_reading_path.py
# ---------------------------------------------------------------------------
_r148_fail=0
_r148_helper="gate/lib/check_ai_reading_path.py"
# Resolve the base ref for the changed-files scope (same pattern as Rule 146 / E196).
_r148_base="${BASE_REF:-origin/main}"
if ! { command -v git >/dev/null 2>&1 && git rev-parse --verify "$_r148_base" >/dev/null 2>&1; }; then
  _r148_base="HEAD"
fi
if [[ ! -f "$_r148_helper" ]]; then
  fail_rule "ai_reading_path" "$_r148_helper missing -- Rule G-31 / E198"
  _r148_fail=1
elif [[ -z "$GATE_PYTHON_BIN" ]]; then
  : # vacuous pass on hosts without python (Rule G-7 lists WSL as canonical env)
else
  _r148_out=$("$GATE_PYTHON_BIN" "$_r148_helper" --mode changed-files-blocking --base "$_r148_base" 2>&1)
  _r148_rc=$?
  # A non-zero rc is EITHER a blocked in-scope finding (exit 1 — a finding while the
  # reading-path authoring surfaces changed) OR a CONFIG ERROR (the data file exists
  # but is unparseable, or its companion / fact files vanished, exit 2). Both fail
  # the rule; the config-error message is surfaced verbatim when present.
  if [[ $_r148_rc -ne 0 ]]; then
    _r148_err=$(printf '%s' "$_r148_out" | grep -E 'config error' | head -1)
    if [[ -n "$_r148_err" ]]; then
      fail_rule "ai_reading_path" "${_r148_err} -- Rule G-31 / E198"
    else
      _r148_sum=$(printf '%s' "$_r148_out" | grep -E 'finding\(s\)' | tail -1)
      _r148_hits=$(printf '%s' "$_r148_out" | grep -E '^ai-reading-path \[' | head -5)
      fail_rule "ai_reading_path" "changed reading-path surface adds a path finding (Rule G-31 / E198): ${_r148_sum:-ai-reading-path helper exited $_r148_rc}${_r148_hits:+ || ${_r148_hits}}"
    fi
    _r148_fail=1
  else
    _r148_sum=$(printf '%s' "$_r148_out" | grep -E 'finding\(s\)' | tail -1)
    [[ -n "$_r148_sum" ]] && echo "OK (Rule G-31 / E198 changed-files-blocking): $_r148_sum"
  fi
fi
[[ $_r148_fail -eq 0 ]] && pass_rule "ai_reading_path"

# ---------------------------------------------------------------------------
