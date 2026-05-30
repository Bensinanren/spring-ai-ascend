#!/usr/bin/env bash
# Auto-extracted from gate/check_architecture_sync.sh by gate/lib/extract_rules.sh
# Rule 149 — ai_understanding_map. DO NOT HAND-EDIT — re-run extract_rules.sh to refresh.
# Authority: PR-E5.

# Rule 149 — ai_understanding_map (enforcer E199, kernel Rule G-32)
#
# Authority: ADR-0157 (EngineeringFrame Ontology — the dual-track value/structure
# axes, the derived Feature --traverses--> EngineeringFrame reconciliation, and
# the claim-agnostic Frame). One CHANGED-FILES-BLOCKING helper that asserts the
# explicit dual-track understanding map under architecture/mappings/ (the readable
# projection joining VALUE / STRUCTURE / EVIDENCE per FunctionPoint) keeps its two
# value/structure axes DERIVED, never OWNED, over the merged authoring DSL
# architecture/features/{features,function-points,engineering-frames}.dsl. The map
# is a READABLE-INTERPRETATION layer: it records the JOIN over the axes; it invents
# no id and no relationship and never outranks a surface it reads (cascade:
# generated facts > DSL > Card/prose):
#   * gate/lib/check_ai_understanding_map.py (E199, slug ai_understanding_map) —
#     three checks: DERIVED TRAVERSE (every Feature--traverses-->Frame edge is
#     derivable from a shared FunctionPoint the Frame anchors; a Frame anchoring
#     nothing yet is vacuous; NON-DERIVED-TRAVERSE blocks only for a shipped source
#     Feature, advisory otherwise even under full-blocking), NO OWNERSHIP OF A
#     FRAME (a Feature source of a contains/anchors/owns edge into a Frame ->
#     FEATURE-OWNS-FRAME; a non-genModule_* contains source ->
#     NON-MODULE-CONTAINS-FRAME; a Frame carrying saa.productClaim/saa.requirement
#     -> FRAME-OWNS-VALUE), and WELL-TYPED AXES (anchors goes Frame->FunctionPoint,
#     requires goes Feature->FunctionPoint -> MALFORMED-EDGE). ADR-backed exceptions
#     live in gate/ai-understanding-map-allowlist.txt (ships empty).
# Runs CHANGED-FILES-BLOCKING here (`--mode changed-files-blocking --base`): a PR
# may not ADD or WORSEN a BLOCKABLE finding once it TOUCHES one of the three
# authoring DSL files (the map is a single shared surface, so a change to any
# re-scopes it); advisory_only findings (e.g. a NON-DERIVED-TRAVERSE from a
# not-yet-shipped Feature) never block, and pre-existing findings while the map
# surfaces are untouched stay advisory. This is the Phase-2 rung: advisory ->
# changed-files-blocking (this rung) -> full-blocking (the terminal rung once the
# map is clean and the soak window closes; a NON-DERIVED-TRAVERSE from a
# not-yet-shipped Feature stays advisory even there). The helper self-derives the
# in-scope decision from git against --base (same git-deriving pattern as Rule 146
# / E196); base ref = BASE_REF (default origin/main) when resolvable, else HEAD.
# The map is greenfield-
# vacuous until one of the three map DSL files exists; the instant any exists it
# MUST be readable or the helper fails closed (exit 2) in every mode — a missing
# authority is never an advisory condition. A missing helper fails closed; a
# missing python interpreter is a vacuous pass (Rule G-7 lists WSL as canonical).
#
# scope_surfaces: architecture/mappings/ai-understanding-map.yaml, architecture/mappings/ai-understanding-map.md, architecture/features/features.dsl, architecture/features/function-points.dsl, architecture/features/engineering-frames.dsl, gate/ai-understanding-map-allowlist.txt, gate/lib/check_ai_understanding_map.py
# ---------------------------------------------------------------------------
_r149_fail=0
_r149_helper="gate/lib/check_ai_understanding_map.py"
# Resolve the base ref for the changed-files scope (same pattern as Rule 146 / E196).
_r149_base="${BASE_REF:-origin/main}"
if ! { command -v git >/dev/null 2>&1 && git rev-parse --verify "$_r149_base" >/dev/null 2>&1; }; then
  _r149_base="HEAD"
fi
if [[ ! -f "$_r149_helper" ]]; then
  fail_rule "ai_understanding_map" "$_r149_helper missing -- Rule G-32 / E199"
  _r149_fail=1
elif [[ -z "$GATE_PYTHON_BIN" ]]; then
  : # vacuous pass on hosts without python (Rule G-7 lists WSL as canonical env)
else
  _r149_out=$("$GATE_PYTHON_BIN" "$_r149_helper" --mode changed-files-blocking --base "$_r149_base" 2>&1)
  _r149_rc=$?
  # A non-zero rc is EITHER a blocked in-scope finding (exit 1 — a BLOCKABLE finding
  # while a map authoring DSL file changed; advisory_only findings such as a
  # NON-DERIVED-TRAVERSE from a not-yet-shipped Feature never block) OR a CONFIG
  # ERROR (a map DSL file exists but is unreadable, exit 2). Both fail the rule; the
  # config-error message is surfaced verbatim when present.
  if [[ $_r149_rc -ne 0 ]]; then
    _r149_err=$(printf '%s' "$_r149_out" | grep -E 'config error' | head -1)
    if [[ -n "$_r149_err" ]]; then
      fail_rule "ai_understanding_map" "${_r149_err} -- Rule G-32 / E199"
    else
      _r149_sum=$(printf '%s' "$_r149_out" | grep -E 'finding\(s\)' | tail -1)
      _r149_hits=$(printf '%s' "$_r149_out" | grep -E '^ai-understanding-map \[' | grep -v '\[advisory\]$' | head -5)
      fail_rule "ai_understanding_map" "changed map surface adds a blockable finding (Rule G-32 / E199): ${_r149_sum:-ai-understanding-map helper exited $_r149_rc}${_r149_hits:+ || ${_r149_hits}}"
    fi
    _r149_fail=1
  else
    _r149_sum=$(printf '%s' "$_r149_out" | grep -E 'finding\(s\)' | tail -1)
    [[ -n "$_r149_sum" ]] && echo "OK (Rule G-32 / E199 changed-files-blocking): $_r149_sum"
  fi
fi
[[ $_r149_fail -eq 0 ]] && pass_rule "ai_understanding_map"

# ---------------------------------------------------------------------------
