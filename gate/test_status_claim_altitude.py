#!/usr/bin/env python3
"""Standalone unit harness for gate/lib/check_status_claim_altitude.py (Rule G-34 / E201).

Locks the CAPABILITY-PRECISE grandfather-tolerance matcher: because every leak on
the capability-status ledger shares ONE file (architecture-status.yaml), a
grandfather row must pin the ledger CAPABILITY key it freezes, and E201 tolerates
a leak only when an open row matches the leak's capability AND category. Before
that anchor a file+category match would let the first same-category row suppress
unrelated claims, and removing a cleaned claim's row would not re-arm the gate on
its leak while any same-category row survived.

The scenarios stage a synthetic repo (the shared policy vocabulary + a synthetic
ledger + the per-surface grandfather list) in a temp directory and assert the
verdict for each case. Mirrors the standalone harness pattern of
gate/test_layer_purity.py and the sibling map/readiness checks.

It also locks the CO-SCAN of the sibling ``note:`` field: both ``allowed_claim``
and ``note`` are un-altitude-guarded narrative on the same surface, so a leak in
a ``note`` is detected and grandfatherable exactly as one in an ``allowed_claim``
(the matcher is field-agnostic, keyed on capability + category).

Run:  python3 gate/test_status_claim_altitude.py
Exit: 0 when every case passes; 1 on the first failure.
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import check_status_claim_altitude as chk  # noqa: E402

_passed = 0
_failed = 0


def _check(name: str, cond: bool, detail: str = "") -> None:
    global _passed, _failed
    if cond:
        _passed += 1
        print(f"ok   - {name}")
    else:
        _failed += 1
        print(f"FAIL - {name}: {detail}", file=sys.stderr)


# The REAL shared policy vocabulary is what E201 imports its probes against, so
# the synthetic policy here only has to define the leaked categories the staged
# claims trigger. The probe library itself comes from check_layer_purity.py
# (imported by the helper), so these fixtures stage real-shaped leaks: a bare
# HTTP status code (L4) and a SQL ON CONFLICT clause (L3).
_POLICY_YAML = """\
schema_version: 2
authority: ADR-0159
status: advisory
categories:
  - id: L3-sql-rls-persistence
    kind: leaked
    title: SQL / RLS / GUC / persistence detail
    owned_at: [L2]
    forbidden_at: [L0, L1]
    home: "architecture/docs/L2/<frame>/ + Flyway migrations"
  - id: L4-http-status-route-verb
    kind: leaked
    title: HTTP status / route-verb / header behaviour
    owned_at: [L2]
    forbidden_at: [L0, L1]
    home: "docs/contracts/*.v1.yaml"
"""

# A synthetic ledger with two capabilities, each carrying one leaked claim:
#   cap_alpha -> a bare 409 (L4)
#   cap_beta  -> an ON CONFLICT clause (L3)
_LEDGER_YAML = """\
version: 1
generated_at: 2026-05-30
capabilities:
  cap_alpha:
    status: design_accepted
    shipped: false
    allowed_claim: "Design only -- cancel returns 409 illegal_state_transition on an already-terminal run."
  cap_beta:
    status: design_accepted
    shipped: false
    allowed_claim: "Design only -- dedup via INSERT ... ON CONFLICT (tenant_id, key) DO NOTHING."
  cap_clean:
    status: design_accepted
    shipped: false
    allowed_claim: "Design only -- a tenant-scoped dedup capability exists; the SPI identity is the boundary."
"""

# A second synthetic ledger where the leak lives in a ``note:`` field rather than
# ``allowed_claim:`` — the by-file-type hole the co-scan closes. ``cap_note`` has
# a clean allowed_claim but a note that restates an ON CONFLICT clause (L3); a
# bare ``note`` under a non-capability top-level key is also clean prose.
_LEDGER_NOTE_YAML = """\
version: 1
generated_at: 2026-05-30
repository_counts:
  note: "Canonical counts derived from the modules list; 8 reactor modules, no leaked detail."
capabilities:
  cap_note:
    status: design_accepted
    shipped: false
    note: "Status note -- dedup uses INSERT ... ON CONFLICT (tenant_id, key) DO NOTHING in the W2 migration."
    allowed_claim: "Design only -- a tenant-scoped dedup capability exists; the SPI identity is the boundary."
"""

# A sunset comfortably in the future so rows are open regardless of run date, and
# one comfortably in the past for the expiry case.
_FUTURE = "2099-12-31"
_PAST = "2000-01-01"


def _stage(root: Path, *, grandfather_yaml: str, ledger_yaml: str = _LEDGER_YAML) -> None:
    gov = root / "docs/governance"
    gov.mkdir(parents=True, exist_ok=True)
    (gov / "layer-purity-policy.yaml").write_text(_POLICY_YAML, encoding="utf-8")
    (gov / "architecture-status.yaml").write_text(ledger_yaml, encoding="utf-8")
    (gov / "layer-purity-status-ledger-grandfather.yaml").write_text(
        grandfather_yaml, encoding="utf-8"
    )
    # The helper imports check_layer_purity from gate/lib; the real one on this
    # repo's sys.path is used (it is import-only, no repo-relative I/O), so the
    # synthetic root does not need its own copy.


def _grandfather(rows_yaml: str) -> str:
    return (
        "schema_version: 1\n"
        "authority: ADR-0159\n"
        "last_updated: 2026-05-30\n"
        "status: advisory\n"
        "list_closed: true\n"
        "violations:\n" + rows_yaml
    )


def _row(capability: str, category: str, sunset: str = _FUTURE) -> str:
    return (
        f"  - id: LPV-SL-{capability}\n"
        f"    layer: STATUS-LEDGER\n"
        f"    file: docs/governance/architecture-status.yaml\n"
        f"    capability: {capability}\n"
        f"    category: {category}\n"
        f"    trigger: test\n"
        f"    migrate_to: docs/contracts/x.v1.yaml\n"
        f"    sunset_date: {sunset}\n"
    )


def _run(root: Path, mode: str) -> int:
    return chk.main(["--repo", str(root), "--mode", mode])


# ---------------------------------------------------------------------------
# Capability resolution (the unit the matcher rests on).
# ---------------------------------------------------------------------------
def test_capability_of_dotted_path() -> None:
    _check(
        "capability_of_takes_last_dotted_segment",
        chk._capability_of("capabilities.idempotency_store") == "idempotency_store",
        f"got {chk._capability_of('capabilities.idempotency_store')!r}",
    )


# ---------------------------------------------------------------------------
# Advisory always exits 0 (soak posture), even with leaks present and no rows.
# ---------------------------------------------------------------------------
def test_advisory_always_zero() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(root, grandfather_yaml=_grandfather("  []\n").replace("violations:\n  []", "violations: []"))
        rc = _run(root, "advisory")
        _check("advisory_exits_zero_with_open_leaks", rc == 0, f"rc={rc}")


# ---------------------------------------------------------------------------
# Non-vacuity: full-blocking FIRES when the live leaks are not grandfathered.
# ---------------------------------------------------------------------------
def test_full_blocking_fires_without_rows() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(root, grandfather_yaml="schema_version: 1\nauthority: ADR-0159\nlast_updated: 2026-05-30\nstatus: advisory\nlist_closed: true\nviolations: []\n")
        rc = _run(root, "full-blocking")
        _check("full_blocking_fires_non_vacuous", rc == 1, f"rc={rc} (expected 1; the two staged leaks are ungrandfathered)")


# ---------------------------------------------------------------------------
# Capability-precise tolerance: a row for cap_alpha (L4) must NOT suppress the
# cap_beta (L3) leak, and vice versa; both rows together clear the gate.
# ---------------------------------------------------------------------------
def test_capability_precise_suppression() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        # Only cap_alpha grandfathered: cap_beta's L3 leak still fires.
        _stage(root, grandfather_yaml=_grandfather(_row("cap_alpha", "L4-http-status-route-verb")))
        rc_partial = _run(root, "full-blocking")
        _check(
            "row_for_alpha_does_not_suppress_beta",
            rc_partial == 1,
            f"rc={rc_partial} (expected 1; cap_beta L3 leak is not covered by the cap_alpha row)",
        )
        # Both grandfathered: clean.
        _stage(
            root,
            grandfather_yaml=_grandfather(
                _row("cap_alpha", "L4-http-status-route-verb") + _row("cap_beta", "L3-sql-rls-persistence")
            ),
        )
        rc_full = _run(root, "full-blocking")
        _check(
            "both_capability_rows_clear_the_gate",
            rc_full == 0,
            f"rc={rc_full} (expected 0; both leaks now have a capability-matched row)",
        )


# ---------------------------------------------------------------------------
# A row whose category does not match the leak's reported category does not
# suppress it (the category half of the precise match).
# ---------------------------------------------------------------------------
def test_wrong_category_does_not_suppress() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        # cap_beta leaks L3, but the row cites L4 -> no suppression.
        _stage(
            root,
            grandfather_yaml=_grandfather(
                _row("cap_alpha", "L4-http-status-route-verb") + _row("cap_beta", "L4-http-status-route-verb")
            ),
        )
        rc = _run(root, "full-blocking")
        _check(
            "category_mismatch_leaves_beta_firing",
            rc == 1,
            f"rc={rc} (expected 1; the cap_beta row cites L4 but the leak is L3)",
        )


# ---------------------------------------------------------------------------
# An expired row no longer suppresses; full-blocking fires.
# ---------------------------------------------------------------------------
def test_expired_row_does_not_suppress() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(
            root,
            grandfather_yaml=_grandfather(
                _row("cap_alpha", "L4-http-status-route-verb", sunset=_PAST)
                + _row("cap_beta", "L3-sql-rls-persistence")
            ),
        )
        rc = _run(root, "full-blocking")
        _check(
            "expired_alpha_row_re_arms_the_gate",
            rc == 1,
            f"rc={rc} (expected 1; the cap_alpha row's sunset has passed)",
        )


# ---------------------------------------------------------------------------
# A ledger-targeting row with no capability is a config error (exit 2).
# ---------------------------------------------------------------------------
def test_capability_less_row_is_config_error() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        bad = _grandfather(
            "  - id: LPV-SL-nocap\n"
            "    layer: STATUS-LEDGER\n"
            "    file: docs/governance/architecture-status.yaml\n"
            "    category: L4-http-status-route-verb\n"
            "    sunset_date: 2099-12-31\n"
        )
        _stage(root, grandfather_yaml=bad)
        rc = _run(root, "advisory")
        _check(
            "capability_less_ledger_row_exits_two",
            rc == 2,
            f"rc={rc} (expected 2; a ledger row must pin its capability key)",
        )


# ---------------------------------------------------------------------------
# A row citing an unknown leaked-category id is a config error (typo guard).
# ---------------------------------------------------------------------------
def test_unknown_category_is_config_error() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(root, grandfather_yaml=_grandfather(_row("cap_alpha", "L9-does-not-exist")))
        rc = _run(root, "advisory")
        _check(
            "unknown_category_exits_two",
            rc == 2,
            f"rc={rc} (expected 2; L9-does-not-exist is absent from the policy vocabulary)",
        )


# ---------------------------------------------------------------------------
# Co-scan of the sibling ``note:`` field. The leak lives in a note, not an
# allowed_claim; the scan must SEE it (the by-file-type hole the widening closes)
# and full-blocking must fire when it is ungrandfathered.
# ---------------------------------------------------------------------------
def test_note_field_leak_is_detected() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(
            root,
            ledger_yaml=_LEDGER_NOTE_YAML,
            grandfather_yaml="schema_version: 1\nauthority: ADR-0159\nlast_updated: 2026-05-30\nstatus: advisory\nlist_closed: true\nviolations: []\n",
        )
        rc = _run(root, "full-blocking")
        _check(
            "note_field_l3_leak_fires_full_blocking",
            rc == 1,
            f"rc={rc} (expected 1; the cap_note ON CONFLICT leak lives in a note: field, which is now co-scanned)",
        )


# ---------------------------------------------------------------------------
# A capability-matched, open grandfather row tolerates a ``note:`` leak exactly
# as it does an ``allowed_claim`` leak — the matcher is field-agnostic, keyed on
# capability + category.
# ---------------------------------------------------------------------------
def test_note_field_leak_is_grandfatherable() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        _stage(
            root,
            ledger_yaml=_LEDGER_NOTE_YAML,
            grandfather_yaml=_grandfather(_row("cap_note", "L3-sql-rls-persistence")),
        )
        rc = _run(root, "full-blocking")
        _check(
            "note_field_leak_tolerated_by_capability_row",
            rc == 0,
            f"rc={rc} (expected 0; an open cap_note/L3 row freezes the note: leak)",
        )


def main() -> int:
    tests = [
        test_capability_of_dotted_path,
        test_advisory_always_zero,
        test_full_blocking_fires_without_rows,
        test_capability_precise_suppression,
        test_wrong_category_does_not_suppress,
        test_expired_row_does_not_suppress,
        test_capability_less_row_is_config_error,
        test_unknown_category_is_config_error,
        test_note_field_leak_is_detected,
        test_note_field_leak_is_grandfatherable,
    ]
    for t in tests:
        t()
    total = _passed + _failed
    print(f"\nstatus-claim-altitude self-test: {_passed}/{total} passed", file=sys.stderr)
    return 0 if _failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
