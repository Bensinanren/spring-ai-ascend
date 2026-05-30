#!/usr/bin/env python3
"""Standalone unit harness for gate/lib/check_l2_detail_sink.py (Rule G-27 / E195).

Locks the two precision properties that make E195's verdict UNIFORM with its
sibling E194 (gate/lib/check_layer_purity.py) — the two advisory helpers that
encode the one adjudicated layer-purity verdict. Before these properties E195's
grandfather match was per-file-per-category (the blunt scope E194 has since
shed), and its test_inventory D3 carve-out missed an all-ArchUnit "enforced by
... ArchUnit rules" mechanism citation that E194's L8 probe never even matches —
so the SAME L0/L1 line could be GRANDFATHERED under one helper and a FINDING
under the other, and a D3-defensible ArchUnit citation was a false positive
under E195 alone.

Two property groups are locked:

  A. LOCUS ANCHORING. A dated layer-purity-temporary-violations row tolerates an
     E195 finding only when the finding's line number falls inside one of the
     row's enumerated locus ranges (an ANCHORED row), or the row is a
     deliberately anchorless ``row-level pass deferred`` whole-file entry. A
     same-file, same-category finding OUTSIDE every range a row enumerates is NOT
     tolerated (it is a different leak the row never adjudicated). This is the
     anchoring E194 already enforces; E195 here matches it byte-for-byte on the
     shared ``locus`` grammar.

  B. D3 ARCHUNIT-MECHANISM CARVE-OUT. A test_inventory match that is really an
     explicit ArchUnit-mechanism citation — a line carrying the literal
     "ArchUnit" signal that is NOT an inventory STRUCTURE (table row /
     test-leading bullet) — is in-layer at L0/L1 per the verdict's keep-list
     ("citing an ArchUnit enforcer as the mechanism"), regardless of how many
     enforcer class names it lists. This is the shape E194's L8 probe never
     matches but E195's extra inline comma-run probe does (the L0 §2
     module-dependency line). A genuine *IT behaviour catalogue — with no
     ArchUnit signal, or in an inventory STRUCTURE — stays a leak.

The scenarios construct synthetic inputs (Finding / _GrandfatherRow objects, a
staged repo for the end-to-end smoke) and assert the verdict for each case.
Mirrors the standalone harness pattern of gate/test_layer_purity.py and
gate/test_adr_id_uniqueness.py.

Run:  python3 gate/test_l2_detail_sink.py
Exit: 0 when every case passes; 1 on the first failure.
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
import check_l2_detail_sink as chk  # noqa: E402

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


def _finding(line_no: int, family: str = "http_runtime") -> chk.Finding:
    return chk.Finding(
        path="architecture/docs/L0/ARCHITECTURE.md",
        line_no=line_no,
        family=family,
        label="test",
        excerpt="test",
    )


def _row(
    locus_ranges: tuple[tuple[int, int], ...],
    *,
    categories: frozenset[str] = frozenset({"L4-http-status-route-verb"}),
    file: str = "architecture/docs/L0/ARCHITECTURE.md",
) -> chk._GrandfatherRow:
    return chk._GrandfatherRow(
        file=file, categories=categories, locus_ranges=locus_ranges
    )


# ---------------------------------------------------------------------------
# A. Locus parser (mirrors the E194 _parse_locus contract one-for-one).
# ---------------------------------------------------------------------------
def test_parse_locus_section_prefix_range() -> None:
    _check(
        "parse_strips_section_label_keeps_range",
        chk._parse_locus("§4 #20 : 520-535") == [(520, 535)],
        "section-label digits (4, 20) must not leak in as single-line anchors",
    )


def test_parse_locus_single_line() -> None:
    _check(
        "parse_single_line",
        chk._parse_locus("§4 #3 : 360") == [(360, 360)],
        f"got {chk._parse_locus('§4 #3 : 360')}",
    )


def test_parse_locus_comma_list() -> None:
    _check(
        "parse_comma_separated_singles",
        chk._parse_locus("31, 39, 67") == [(31, 31), (39, 39), (67, 67)],
        f"got {chk._parse_locus('31, 39, 67')}",
    )


def test_parse_locus_range_with_paren_note() -> None:
    _check(
        "parse_range_with_digitfree_note",
        chk._parse_locus("259-286 (P6)") == [(259, 286)],
        "a digit-free (P6) note must contribute no range",
    )


def test_parse_locus_singles_with_hyphen_note() -> None:
    # A trailing note may carry a hyphenated token (P1-P6); range extraction
    # consumes only the real start-end pairs, so the note's 1-6 must NOT become a
    # range.
    got = chk._parse_locus("22, 89, 113 (P1-P6)")
    _check(
        "parse_singles_ignore_note_hyphen",
        got == [(22, 22), (89, 89), (113, 113)],
        f"got {got}",
    )


def test_parse_locus_anchorless() -> None:
    _check(
        "parse_textonly_deferred_is_anchorless",
        chk._parse_locus("matched SPI signatures (row-level pass deferred)") == [],
        "a digit-free deferred locus must yield no ranges",
    )
    # The critical case: a textual deferred locus that embeds a stray in-prose
    # digit ("3-track") MUST stay anchorless — the strict clean-spec rule rejects
    # the whole locus rather than anchoring the row to a wrong line 3.
    _check(
        "parse_noisy_deferred_stays_anchorless",
        chk._parse_locus("matched RLS / 3-track / sandbox (row-level pass deferred)") == [],
        "a stray in-prose digit ('3-track') must NOT anchor the row",
    )


# ---------------------------------------------------------------------------
# A. Grandfather matcher (the locus-awareness the residual fix adds to E195).
# ---------------------------------------------------------------------------
def test_in_range_finding_is_tolerated() -> None:
    _check(
        "finding_inside_locus_range_tolerated",
        chk._grandfathered(_finding(525), [_row(((520, 535),))]),
        "a finding at line 525 inside locus 520-535 must be tolerated",
    )


def test_out_of_range_finding_is_not_tolerated() -> None:
    # The exact bug: line 999 shares category L4 and the same file but is far
    # outside 520-535. It MUST NOT be tolerated (before locus-anchoring, E195's
    # file+category match tolerated it — the non-uniform-verdict divergence).
    _check(
        "finding_outside_locus_range_not_tolerated",
        not chk._grandfathered(_finding(999), [_row(((520, 535),))]),
        "a same-file same-category finding at line 999 outside locus 520-535 must "
        "NOT be tolerated (this was the file+category over-tolerance)",
    )


def test_comma_list_only_covers_listed_lines() -> None:
    row = _row(((31, 31), (39, 39), (67, 67)))
    _check(
        "comma_locus_covers_listed_line",
        chk._grandfathered(_finding(39), [row]),
        "line 39 is enumerated and must be tolerated",
    )
    _check(
        "comma_locus_skips_unlisted_line",
        not chk._grandfathered(_finding(40), [row]),
        "line 40 is NOT enumerated (31/39/67) and must stay a finding",
    )


def test_anchorless_row_falls_back_to_file_category() -> None:
    # An anchorless row (no parseable ranges) keeps the file + category escape
    # hatch for a deliberately whole-file ``row-level pass deferred`` entry.
    row = _row(())
    _check(
        "anchorless_row_is_not_anchored",
        not row.locus_anchored,
        "a row with no ranges must report anchorless",
    )
    _check(
        "anchorless_row_tolerates_any_line",
        chk._grandfathered(_finding(9999), [row]),
        "an anchorless deferred row keeps file + category matching",
    )


def test_category_must_match() -> None:
    # A locus-covered line whose family maps to a DIFFERENT category than the row
    # cites is not tolerated (the projection FAMILY_TO_LP_CATEGORIES gates it).
    row = _row(((1, 1000),), categories=frozenset({"L3-sql-rls-persistence"}))
    _check(
        "wrong_category_not_tolerated",
        not chk._grandfathered(_finding(500, family="http_runtime"), [row]),
        "an http_runtime (L4) finding is not tolerated by an L3-only row even "
        "when the line is inside the row's range",
    )
    _check(
        "right_category_tolerated",
        chk._grandfathered(_finding(500, family="sql_persistence"), [row]),
        "a sql_persistence (L3) finding inside the range of an L3 row is tolerated",
    )


def test_anchored_preferred_over_anchorless() -> None:
    # Two open rows match file + category: an anchored one covering 525 and an
    # anchorless deferred one. A covered line is tolerated by the anchored row;
    # an uncovered line falls back to the anchorless row.
    rows = [_row(((520, 535),)), _row(())]
    _check(
        "covered_line_tolerated_with_both_rows",
        chk._grandfathered(_finding(525), rows),
        "line 525 covered by the anchored row is tolerated",
    )
    _check(
        "uncovered_line_falls_back_to_anchorless",
        chk._grandfathered(_finding(999), rows),
        "line 999 not covered by the anchored row falls back to the anchorless row",
    )


# ---------------------------------------------------------------------------
# B. D3 ArchUnit-mechanism carve-out (the line-252 false positive the fix closes).
# ---------------------------------------------------------------------------
# The literal L0 §2 module-dependency line: an "(enforced by X, Y, Z ... ArchUnit
# rules)" citation that lists six enforcer class names (four *ArchTest, two plain
# *Test that are ArchUnit rules). E194's L8 probe never matches it (no inventory
# STRUCTURE / behaviour verb); E195's inline comma-run probe does, so E195 needs
# the ArchUnit-mechanism carve-out to reach E194's verdict (no finding).
_L0_LINE_252 = (
    "Module dependency direction (enforced by `ApiCompatibilityTest`, "
    "`RuntimeMustNotDependOnPlatformTest`, `OrchestrationSpiArchTest`, "
    "`MemorySpiArchTest`, `SpiPurityGeneralizedArchTest`, and "
    "`EdgeToComputeDirectLinkArchTest` ArchUnit rules - post-ADR-0078):"
)


def test_archunit_citation_spared() -> None:
    _check(
        "l0_line252_archunit_citation_spared",
        chk._is_d3_enforcer_citation(_L0_LINE_252),
        "an all-ArchUnit 'enforced by ... ArchUnit rules' citation (>2 tokens) "
        "must be spared regardless of count — the line-252 false positive",
    )


def test_archunit_citation_spared_via_full_scan() -> None:
    # End-to-end: a single-line doc carrying exactly the line-252 citation must
    # produce ZERO test_inventory findings under a real advisory scan.
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        l0 = root / "architecture/docs/L0"
        l0.mkdir(parents=True, exist_ok=True)
        (l0 / "ARCHITECTURE.md").write_text(_L0_LINE_252 + "\n", encoding="utf-8")
        findings = chk.scan_file(root, l0 / "ARCHITECTURE.md")
        test_inv = [f for f in findings if f.family == "test_inventory"]
        _check(
            "archunit_citation_no_test_inventory_finding",
            not test_inv,
            f"the ArchUnit citation must yield no test_inventory finding, got {test_inv}",
        )


def test_it_behaviour_catalogue_still_a_leak() -> None:
    # A genuine *IT behaviour catalogue with NO ArchUnit signal must stay a leak.
    catalogue = (
        "Covered by RunHttpContractIT, S2cCallbackRoundTripIT, IdempotencyReplayIT, "
        "TenantIsolationIT - assert the runtime behaviour."
    )
    _check(
        "it_behaviour_catalogue_not_spared",
        not chk._is_d3_enforcer_citation(catalogue),
        "a 4-test *IT behaviour catalogue with no ArchUnit signal must stay a leak",
    )


def test_it_inventory_table_still_a_leak() -> None:
    # An inventory STRUCTURE (table row) is a leak even with an ArchUnit token on
    # the line — the structure check beats the ArchUnit signal.
    table_row = "| `RunHttpContractIT` | asserts 409 replay (ArchUnit-adjacent) |"
    _check(
        "it_inventory_table_row_not_spared",
        not chk._is_d3_enforcer_citation(table_row),
        "an inventory table row is a leak even with an ArchUnit token present",
    )


def test_single_archtest_bullet_spared() -> None:
    # Shape 1 unchanged: a lone *ArchTest bullet is a defensible enforcer identity.
    _check(
        "single_archtest_bullet_spared",
        chk._is_d3_enforcer_citation("- `OrchestrationSpiArchTest`"),
        "a lone *ArchTest bullet is a defensible enforcer identity",
    )


def test_single_enforcing_it_citation_spared() -> None:
    # Shape 4 unchanged: a single enforcing *IT under an FQN-lock clause is a
    # citation, not a catalogue.
    line = (
        "The cancel-race is enforced by integration `RunCancelRaceIT` "
        "(class FQN locked here per Rule R-C.a)."
    )
    _check(
        "single_it_fqnlock_citation_spared",
        chk._is_d3_enforcer_citation(line),
        "a single enforcing *IT named under an FQN-lock clause is a citation",
    )


# ---------------------------------------------------------------------------
# End-to-end smoke: advisory mode never crashes and never blocks (exit 0).
# ---------------------------------------------------------------------------
def test_advisory_run_smoke() -> None:
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        l0 = root / "architecture/docs/L0"
        l0.mkdir(parents=True, exist_ok=True)
        (l0 / "ARCHITECTURE.md").write_text(
            "A structural boundary surface naming the `Orchestrator` SPI.\n",
            encoding="utf-8",
        )
        _check(
            "advisory_run_exits_0",
            chk.main(["--repo", str(root), "--mode", "advisory"]) == 0,
            "advisory mode must exit 0",
        )


def main() -> int:
    for fn in (
        test_parse_locus_section_prefix_range,
        test_parse_locus_single_line,
        test_parse_locus_comma_list,
        test_parse_locus_range_with_paren_note,
        test_parse_locus_singles_with_hyphen_note,
        test_parse_locus_anchorless,
        test_in_range_finding_is_tolerated,
        test_out_of_range_finding_is_not_tolerated,
        test_comma_list_only_covers_listed_lines,
        test_anchorless_row_falls_back_to_file_category,
        test_category_must_match,
        test_anchored_preferred_over_anchorless,
        test_archunit_citation_spared,
        test_archunit_citation_spared_via_full_scan,
        test_it_behaviour_catalogue_still_a_leak,
        test_it_inventory_table_still_a_leak,
        test_single_archtest_bullet_spared,
        test_single_enforcing_it_citation_spared,
        test_advisory_run_smoke,
    ):
        fn()
    print(f"\n{_passed} passed, {_failed} failed")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
