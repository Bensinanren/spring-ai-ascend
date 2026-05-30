#!/usr/bin/env python3
"""Gate check: L2-detail-sink — implementation detail that leaked into L0/L1 prose.

Authority: docs/governance/rules/rule-G-27.md (kernel Rule G-27), enforcer E195,
gate Rule 145 (advisory). Encodes the adjudicated layer-purity VERDICT: an
L0 / L1 architecture document is a STRUCTURAL boundary surface and MUST NOT
carry runtime L2 implementation detail. The detail belongs in
architecture/docs/L2/ (when those land) and the contract surfaces under
docs/contracts/ + the generated facts under architecture/facts/generated/.

This is the *document-prose* analog of Rule G-28's per-ADR altitude control:
G-28 rejects an L2-altitude `decision_type` in an L0/L1 normalized ADR view;
this rule reports L2-altitude *prose* (SQL/RLS/GUC, HTTP status+verb behaviour,
on-wire formats, method signatures + call chains, filter ordering, concrete
test-class inventories) in an L0/L1 ARCHITECTURE / view markdown.

VERDICT split (the keep-list is NOT reported — only the leak-list is):

  DEFENSIBLE (stays at L0/L1, never flagged):
    * naming a public SPI *type* as a boundary identity (a noun: `Orchestrator`,
      `Checkpointer`, `ResilienceContract`);
    * development-view package decomposition (`com.huawei.ascend..`,
      `<module>/src/main/java/..`);
    * citing an ArchUnit / enforcer mechanism (`enforcer E160`, `Rule R-C.e`,
      `*ArchTest`).

  LEAKED (belongs at L2 / contracts, reported here):
    * SQL / RLS / GUC / persistence DDL + semantics;
    * HTTP status code + route-verb + header runtime behaviour;
    * on-wire formats (OTLP, attribute namespaces, envelope field shapes);
    * Java method signatures + call chains (`A.b() -> C.d()`, CAS arg lists);
    * filter / interceptor ordering;
    * concrete test-class inventories used as evidence in L0/L1 prose.

Scope: architecture/docs/L0/*.md and architecture/docs/L1/**/*.md, EXCLUDING the
`_template/` scaffolds (placeholder docs, not authority). Fenced code blocks are
scanned too — a `SET LOCAL app.tenant_id` inside a fenced "L2 Boundary Contract"
zone is still leaked L2 detail per the VERDICT (a sanctioned *forward-declaration
heading* does not launder the detail it contains). A single finding can be
suppressed in place with an HTML comment on the same line or the line directly
above it:

    <!-- l2-detail-sink-allow: <reason> -->

(mirrors the `secret-allowlist:` inline opt-out convention used by Rule 28c).

Ratchet (mode):
  advisory               -- report every finding to stderr, always exit 0.
  changed-files-blocking -- exit 1 only if a finding lands on a file passed via
                            --changed (a PR may not add/worsen a leak on a file
                            it touches); other findings stay advisory.
  blocking               -- exit 1 if any finding exists (terminal rung, once the
                            L0/L1 corpus has been swept clean).

Usage:
    python3 gate/lib/check_l2_detail_sink.py                       # advisory
    python3 gate/lib/check_l2_detail_sink.py --mode blocking
    python3 gate/lib/check_l2_detail_sink.py --changed architecture/docs/L0/ARCHITECTURE.md \
        --mode changed-files-blocking
    python3 gate/lib/check_l2_detail_sink.py --repo /path/to/repo

Exit codes:
    0 -- mode satisfied (advisory always; *-blocking when no blocking finding)
    1 -- a blocking finding under the active mode, OR a fatal config error
"""
from __future__ import annotations

import argparse
import datetime
import re
import sys
from dataclasses import dataclass
from pathlib import Path

VALID_MODES = ("advisory", "changed-files-blocking", "blocking")

# In-line suppression token. A finding on a line is dropped when this token
# appears on that line or on the line immediately above it.
ALLOW_TOKEN = "l2-detail-sink-allow:"


def repo_root() -> Path:
    """Repository root — two directories above this script (gate/lib/..)."""
    return Path(__file__).resolve().parent.parent.parent


# ---------------------------------------------------------------------------
# Leak-signal corpus.
#
# Each family is (id, human_label, [compiled patterns]). Patterns are anchored
# to runtime-behaviour shapes, NOT to bare type/package names, so the
# VERDICT keep-list (SPI nouns, package paths, enforcer citations) is not
# reported. Patterns are intentionally conservative: a false negative (a leak
# that slips through) is preferable to a false positive on defensible prose,
# because this is an advisory ratchet that tightens over successive sweeps.
# ---------------------------------------------------------------------------
def _compile(*patterns: str) -> list[re.Pattern[str]]:
    return [re.compile(p, re.IGNORECASE) for p in patterns]


LEAK_FAMILIES: list[tuple[str, str, list[re.Pattern[str]]]] = [
    (
        "sql_persistence",
        "SQL / RLS / GUC / persistence DDL or semantics (belongs at L2 + Flyway)",
        _compile(
            r"\bCREATE\s+TABLE\b",
            r"\bALTER\s+TABLE\b",
            r"\bSET\s+LOCAL\b",
            # Postgres GUC tenant keys (the RLS session variable).
            r"\bapp\.(current_)?tenant(_id)?\b",
            # RLS policy DDL / enablement.
            r"\b(ENABLE\s+ROW\s+LEVEL\s+SECURITY|CREATE\s+POLICY|ROW\s+LEVEL\s+SECURITY)\b",
            # Flyway migration filenames (V<n>__name.sql / V?__name.sql).
            r"\bV\?*\d*__[A-Za-z0-9_]+\.sql\b",
            # SQL DML predicate on tenant_id (a query shape, not a constraint name).
            r"\bSELECT\b[^.\n]{0,80}\bWHERE\b[^.\n]{0,40}\btenant_id\b",
        ),
    ),
    (
        "http_runtime",
        "HTTP status + route-verb + header runtime behaviour (belongs at L2 + OpenAPI)",
        _compile(
            # A verb of producing a status code + a 3-digit code (runtime behaviour).
            r"\b(returns?|respond(s|ing)?|repl(y|ies|ying)|emit(s|ting)?|send(s|ing)?)\b[^.\n]{0,40}\bHTTP\s*[1-5]\d\d\b",
            r"\b(returns?|respond(s|ing)?|repl(y|ies|ying)|status(\s+code)?(\s+is)?)\b[^.\n]{0,30}\b[1-5]\d\d\s+(OK|Created|Accepted|No\s+Content|Bad\s+Request|Unauthorized|Forbidden|Not\s+Found|Conflict|Unprocessable|Too\s+Many\s+Requests|Internal\s+Server\s+Error)\b",
            # "within 200ms returns 202" timing+status runtime SLA prose.
            r"\b[1-5]\d\d\b[^.\n]{0,25}\bwithin\b[^.\n]{0,15}\bms\b",
            # Header-rewrite runtime semantics at the edge.
            r"\b(replace|overwrite|strip|rewrite)s?\b[^.\n]{0,25}\b(X-[A-Za-z-]+|header)\b",
        ),
    ),
    (
        "wire_format",
        "On-wire format / attribute namespace / envelope field shape (belongs at L2 + AsyncAPI/contracts)",
        _compile(
            r"\bOTLP/?(HTTP|gRPC)?\b",
            # Telemetry attribute namespaces as wire-level keys.
            r"\b(gen_ai|langfuse|otel|opentelemetry)\.[A-Za-z_.*]+",
            # W3C trace header on the wire.
            r"\btraceparent\b",
            # JSON wire envelope field-level shape callouts.
            r"\bwire\s+(format|shape|envelope)\b",
        ),
    ),
    (
        "method_signature",
        "Java method signature / call chain / CAS arg list (belongs at L2 + code facts)",
        _compile(
            # Method-call arrow chain: `foo() -> bar()` or `Foo.bar() → Baz.qux()`.
            r"\b[A-Za-z_][A-Za-z0-9_]*\s*\([^)\n]*\)\s*(->|→)\s*[A-Za-z_][A-Za-z0-9_]*\s*\(",
            # Compare-and-set runtime primitive with an arg list.
            r"\bcompareAndSet\s*\(",
            r"\b(expected|witness)\s*,\s*(update|next|new)\s*\)",
            # Atomic CAS phrased as method-level (the agent-service Run CAS leak).
            r"\bCAS\b[^.\n]{0,30}\b(fromStatus|expectedStatus|update)\b",
        ),
    ),
    (
        "filter_ordering",
        "Filter / interceptor ordering (belongs at L2 + code facts)",
        _compile(
            r"\bfilter\s+(order|ordering|chain\s+order|position)\b",
            r"\b@Order\s*\(",
            r"\b[A-Za-z][A-Za-z0-9]*Filter\b[^.\n]{0,30}\b(runs|executes|ordered)\b[^.\n]{0,15}\b(before|after)\b",
            r"\bFilterChain\b[^.\n]{0,25}\b(order|position|before|after)\b",
        ),
    ),
    (
        "test_inventory",
        "Concrete test-class inventory used as L0/L1 evidence (belongs at L2 + test facts)",
        _compile(
            # Inline run: three or more concrete test-class names on ONE line
            # (a comma/semicolon-separated catalogue inside a sentence).
            r"(\b[A-Z][A-Za-z0-9]*(Test|IT|Spec)\b[^.\n]{0,8}[,;)][^.\n]{0,8}){2,}\b[A-Z][A-Za-z0-9]*(Test|IT|Spec)\b",
            # Inventory STRUCTURE — one test class per line, the shape an
            # enumerated catalogue takes when it is NOT a single-line comma run.
            # These mirror the E194 (check_layer_purity) L8 probes one-for-one so
            # the two helpers that encode the same verdict cover the same leak
            # surface: a one-FQN-per-bullet Verification Matrix and a per-test
            # markdown table are the SAME test-inventory leak as a comma run.
            #
            # Bullet-list entry whose leading content is a test class (FQN or
            # simple name), e.g. `- com.x.y.RunHttpContractIT`.
            r"^\s*[-*]\s+`?(?:[a-z][\w.]*\.)?[A-Z]\w+(?:IT|Test|Spec)`?\s*$",
            # Markdown table row whose cells name a test class.
            r"^\s*\|.*`[A-Z]\w+(?:IT|Test|Spec)`",
            # A test class paired with an asserted-behaviour clause (em-dash /
            # colon + a behaviour verb) — a catalogue entry carrying its test's
            # runtime assertion into the prose.
            r"\b[A-Z]\w+(?:IT|Test|Spec)\b[^.\n]{0,40}?(?:[-—:]\s*)(?:asserts?|verif(?:y|ies)|checks?|covers?|ensures?|exercises?|proves?)\b",
        ),
    ),
]

# Defensible-content guards: a candidate line that matches one of these is a
# false-positive risk from the keep-list and is dropped *only* when the sole
# evidence on the line is a keep-list shape. Applied per-pattern below.
ENFORCER_CITATION_RE = re.compile(r"\b(enforcer\s+E\d+|Rule\s+[A-Z]-[\w.]+|[A-Z][A-Za-z0-9]*ArchTest)\b")
PACKAGE_PATH_RE = re.compile(r"\bcom\.huawei\.ascend\b|/src/main/java/")

# --------------------------------------------------------------------------
# Delegation-pointer guard.
#
# The layer-purity verdict KEEPS, at L0/L1, the DELEGATION of a forbidden
# category to its authoritative home: a sentence that NAMES a category noun
# only to say "this detail lives in <contract/L2/fact>, not here" is the
# OPPOSITE of a leak — it is the boundary doc doing its job. A purely
# topic-anchored regex (e.g. the bare noun "wire shape", "traceparent",
# "filter ordering") cannot tell that delegation pointer from an inlined
# format/ordering leak, so without this guard the cleanup waves' own
# delegation prose trips the scan. Two signals, BOTH required within a small
# neighbourhood (the markdown bullet/sentence a match sits in spans a few
# physical lines), mark a delegation pointer:
#
#   * an explicit DELEGATION cue — a verb/phrase that hands the detail off
#     ("delegated to", "owned downstream", "lives in", "not (re)stated here",
#     "does not carry", "is contract material", "verification material",
#     "governed by", "(authority)", a "Wire shape:" forward-heading); and
#   * a HOME reference — a contract path, an architecture/docs/L2/ path, a
#     generated-fact path, a `*.v1.yaml`, or an `ADR-NNNN` pointer.
#
# An inlined leak (a `SET LOCAL` GUC, an `ON CONFLICT` clause, a concrete
# `gen_ai.*` namespace, a `00-<trace_id>-<span_id>-01` grammar, a numeric
# `@Order`) carries the MECHANISM on the line and is not redeemed by a
# neighbouring pointer; the dated grandfather list remains the sole tolerance
# for any genuinely-leaked-but-not-yet-migrated block.
DELEGATION_CUE_RE = re.compile(
    r"(?:"
    r"delegat|"
    r"owned\s+(?:by|downstream|elsewhere|upstream|at\b)|"
    r"owned\b[^.\n]{0,30}\bdownstream|"
    r"lives?\s+(?:in|downstream)|live\s+downstream|"
    r"belongs?\s+(?:at|in|to)\b|"
    r"not\s+(?:re)?stated|not\s+carried\s+here|"
    r"does\s+not\s+carry|deliberately\s+does\s+not|"
    r"(?:contract|verification)\s+material|"
    r"governed\s+by|single\s+authority|\(authority\)|"
    r"wire\s+shape:"
    r")",
    re.IGNORECASE,
)
# A reference to the authoritative HOME the detail is delegated to. A bare
# `ADR-NNNN` counts as a pointer (the ADR is the decision home); the path forms
# are the concrete L2 / contract / fact homes; the bare layer token `L2` is the
# Rule G-1.1.c prose-delegation home ("... are **L2 / contract** material",
# "points at the ... L2 zone"). The bare `L2` only ever suppresses in
# conjunction with an explicit delegation CUE (see DELEGATION_CUE_RE), so a
# stray "L2" near an inlined leak cannot launder it.
HOME_REF_RE = re.compile(
    r"(?:docs/contracts/|architecture/docs/L2/|architecture/facts/generated/|\.v1\.yaml|\bADR-\d{4}\b|\bL2\b)"
)

# A `wire_format` match is uniquely noun-prone: the words "wire shape",
# "envelope", "traceparent" appear whenever a boundary doc POINTS at a wire
# contract (a reading-order list entry `docs/contracts/x.v1.yaml — ... wire
# shape.`, a per-frame "behaviour per ADR-NNNN" delegation cell). A genuine
# on-wire-FORMAT leak spells the encoding/namespace/grammar out and does NOT
# co-cite its own contract / ADR / enforcer on the same physical line (the
# grandfathered wire leaks — `gen_ai.*`, `DROP_OLDEST` buffer, the
# `00-<trace_id>-<span_id>-01` grammar — carry none). So for the wire_format
# family ONLY, a same-line pointer (contract/L2/fact path, `*.v1.yaml`,
# `ADR-NNNN`, or an enforcer citation) marks the match as a reference, not an
# inlined format.
WIRE_POINTER_RE = re.compile(
    r"(?:docs/contracts/|architecture/docs/L2/|architecture/facts/generated/|\.v1\.yaml|\bADR-\d{4}\b|enforcer[s]?\s+E\d+)"
)

# --------------------------------------------------------------------------
# Test-inventory D3-enforcer-citation guard (test_inventory family ONLY).
#
# The layer-purity VERDICT KEEPS, at L0/L1, the DEFENSIBLE act of "citing an
# ArchUnit / enforcer as the mechanism" (policy D3). The companion E194 helper
# spares such a citation from its L8 probe via _is_d3_enforcer_citation; E195
# spares the SAME citations so the two gates that encode one verdict do not
# disagree on a defensible enforcer identity.
#
# These shapes are VERDICT-EQUIVALENT to the E194 carve-out, not byte-for-byte
# identical, by necessity: E195 carries one extra positive probe E194 lacks — an
# inline COMMA-RUN of 2+ `*Test`/`*IT`/`*Spec` tokens on a single line (the
# `test_inventory` family's first pattern). E194's L8 probe only fires on an
# inventory STRUCTURE (a markdown table row, a test-leading bullet, or a
# named-test-plus-behaviour-verb clause), so E194 never even matches a bare
# comma-run of enforcer class names — e.g. the L0 §2 module-dependency line
# "... (enforced by ApiCompatibilityTest, RuntimeMustNotDependOnPlatformTest,
# OrchestrationSpiArchTest, ... ArchUnit rules)". E195's comma-run probe DOES
# match that line, so to reach E194's verdict (no finding) E195 needs one more
# spared shape than E194: an explicit ArchUnit-mechanism citation regardless of
# how many enforcer class names it lists. Without it, E195 over-fires a
# D3-defensible ArchUnit citation that E194 (and the verdict) treat as in-layer.
#
# Spared shapes:
#   1. every enumerated test token is an ArchUnit architecture-test (suffix
#      ArchTest / ArchUnitTest / PurityTest) — these ARE enforcers, never a
#      behaviour catalogue;
#   2. the line is an explicit ArchUnit-mechanism citation — it carries the
#      literal "ArchUnit" signal AND is NOT an inventory STRUCTURE (table row /
#      test-leading bullet) — so a "(enforced by X, Y, Z ArchUnit rules)" clause
#      that NAMES its enforcing ArchUnit rules is a mechanism citation regardless
#      of token count (this is the shape E194 never matches but E195's comma-run
#      probe does; it is the line-252 false positive this fix closes);
#   3. a line carrying a mechanism clause ("enforced by ...", "ArchUnit `...`",
#      "(enforcer E<n>)") that enumerates NO behaviour test is a pure
#      enforcer-id citation; and
#   4. a prose constraint that cites its enforcing test(s) via an explicit
#      enforcement / FQN-lock clause (Rule R-C.a), is NOT an inventory STRUCTURE,
#      and names at most _D3_CITATION_MAX_TESTS behaviour tests, is a mechanism
#      citation.
#
# A genuine integration-test INVENTORY — a table of tests, a bullet list of
# tests, or three-plus *IT/*Test BEHAVIOUR tests in one line that does NOT carry
# an ArchUnit-mechanism signal — stays a leak even beside an "enforced by"
# clause. Shape 2 is gated on the literal "ArchUnit" signal precisely so it
# spares only ArchUnit-mechanism citations, never an *IT behaviour catalogue.
_D3_ARCHUNIT_TOKEN_RE = re.compile(r"\b[A-Z]\w*(?:ArchTest|ArchUnitTest|PurityTest)\b")
_D3_MECHANISM_CLAUSE_RE = re.compile(r"enforced by|ArchUnit|\(enforcer\s+E\d+\)", re.IGNORECASE)
# The specifically-ArchUnit mechanism signal (Shape 2). Narrower than
# _D3_MECHANISM_CLAUSE_RE: it requires the literal word "ArchUnit", so an
# "enforced by <some *IT>" clause that names NO ArchUnit rule does NOT trip
# Shape 2 (it is handled by the count-bounded Shape 4 instead). An ArchUnit rule
# whose class name omits the `*ArchTest` suffix (e.g. ApiCompatibilityTest,
# RuntimeMustNotDependOnPlatformTest) is still recognised as a mechanism citation
# when it sits inside such an "... ArchUnit rules" clause.
_D3_ARCHUNIT_MECHANISM_RE = re.compile(r"\bArchUnit\b", re.IGNORECASE)
_D3_ENFORCEMENT_CLAUSE_RE = re.compile(
    r"enforced by|verified by|asserted by|locked here per rule|per rule\s+r-c\.a|class fqn locked",
    re.IGNORECASE,
)
_NON_ARCHUNIT_TEST_TOKEN_RE = re.compile(r"\b[A-Z]\w+(?:IT|Test|Spec)\b")
_TEST_INVENTORY_STRUCTURE_RE = re.compile(
    r"^\s*\|.*`?[A-Z]\w+(?:IT|Test|Spec)`?"          # table row naming a test
    r"|^\s*[-*]\s+`?(?:[a-z][\w.]*\.)?[A-Z]\w+(?:IT|Test|Spec)`?\b",  # test-leading bullet
)
# A constraint may name its primary enforcing test plus one deferred companion;
# a third enumerated test makes the line a catalogue, not a citation.
_D3_CITATION_MAX_TESTS = 2


def _is_d3_enforcer_citation(line: str) -> bool:
    """True when a test_inventory match on ``line`` is a D3-defensible citation.

    Verdict-equivalent to the E194 helper's _is_d3_enforcer_citation (it spares
    one extra shape, #2, to cover E195's additional inline comma-run probe — see
    the module comment above). Four D3 shapes are spared:
      1. every enumerated test token is an ArchUnit architecture-test;
      2. the line is an explicit ArchUnit-mechanism citation (carries the literal
         "ArchUnit" signal AND is NOT an inventory STRUCTURE) — a
         "(enforced by X, Y, Z ArchUnit rules)" clause that NAMES its enforcing
         rules, regardless of token count;
      3. the line carries a mechanism clause and enumerates NO behaviour test
         (pure ArchUnit / enforcer-id citation); or
      4. a prose constraint cites its enforcing test(s) via an explicit
         enforcement / FQN-lock clause, the line is NOT an inventory STRUCTURE,
         and it names at most ``_D3_CITATION_MAX_TESTS`` behaviour tests.
    """
    is_inventory_structure = bool(_TEST_INVENTORY_STRUCTURE_RE.search(line))
    tokens = _NON_ARCHUNIT_TEST_TOKEN_RE.findall(line)
    if not tokens:
        # No behaviour-test token at all (e.g. only an ArchTest, handled below) —
        # defer to the ArchUnit / mechanism signals.
        return bool(_D3_ARCHUNIT_TOKEN_RE.search(line) or _D3_MECHANISM_CLAUSE_RE.search(line))
    # Shape 1: every enumerated test token is an ArchUnit architecture-test -> D3.
    archunit_tokens = set(_D3_ARCHUNIT_TOKEN_RE.findall(line))
    if archunit_tokens and all(t in archunit_tokens for t in tokens):
        return True
    # Shape 2: an explicit ArchUnit-mechanism citation. The line cites ArchUnit as
    # the enforcing mechanism (literal "ArchUnit") and is NOT an inventory
    # structure, so the enforcer class names it lists are a mechanism citation
    # regardless of count — this is the shape E194's L8 probe never matches but
    # E195's comma-run probe does (the L0 §2 module-dependency line). Gated on the
    # ArchUnit signal so it never spares an *IT behaviour catalogue.
    if _D3_ARCHUNIT_MECHANISM_RE.search(line) and not is_inventory_structure:
        return True
    # Shape 4: a constraint's enforcing-test citation (Rule R-C.a). Spared only
    # when it is NOT an inventory structure AND names few tests AND carries an
    # explicit enforcement / FQN-lock clause.
    if (
        _D3_ENFORCEMENT_CLAUSE_RE.search(line)
        and not is_inventory_structure
        and len(tokens) <= _D3_CITATION_MAX_TESTS
    ):
        return True
    return False


# Repo-relative location of the closed, dated grandfather list (shared with the
# E194 layer_purity helper). E195 consumes the SAME tolerance surface so the two
# helpers that encode one verdict honour one allow-list. Loaded best-effort:
# PyYAML is NOT a hard dependency of this pure-regex helper, so an absent parser
# degrades to "no grandfather tolerance" (the delegation + wire-pointer guards
# still apply) rather than a config error.
VIOLATIONS_REL = "docs/governance/layer-purity-temporary-violations.yaml"

# Map each E195 signal family to the E194 leaked-category id(s) a grandfather row
# may cite for it. The grandfather list is authored in the E194 vocabulary
# (L1..L8); this projection lets an open row written for, e.g., `L3-sql-rls-
# persistence` tolerate this helper's `sql_persistence` finding on the same file.
FAMILY_TO_LP_CATEGORIES: dict[str, frozenset[str]] = {
    "sql_persistence": frozenset({"L3-sql-rls-persistence"}),
    "http_runtime": frozenset({"L4-http-status-route-verb"}),
    "wire_format": frozenset({"L6-wire-format"}),
    "method_signature": frozenset({"L1-method-call-chain", "L7-method-signature"}),
    "filter_ordering": frozenset({"L5-filter-ordering"}),
    "test_inventory": frozenset({"L8-test-class-inventory"}),
}

# --------------------------------------------------------------------------
# Locus parsing (mirrors the E194 check_layer_purity helper one-for-one).
#
# A grandfather row carries a `locus` cell — the smallest in-file anchor as a
# section label plus a line range. E194 (check_layer_purity.py) tolerates a leak
# ONLY when the leak's line falls inside one of the row's enumerated ranges (an
# anchored row), or the row is a deliberately whole-file `row-level pass
# deferred` entry whose locus carries no line number (an anchorless row, which
# falls back to file + category matching). E195 previously matched a row on file
# + category ALONE — the same blunt scope E194 has since shed — so a same-file,
# same-category leak ANYWHERE in a file collapsed under whichever row first
# declared that category for the file, granting tolerance the dated list never
# adjudicated. The verdict is by-authority-surface and MUST be uniform across the
# two gates that implement it, so E195 here parses the SAME `locus` grammar with
# the SAME strict, all-or-nothing rule and anchors its tolerance identically.
#
# A single comma-segment of a clean line spec is a bare line ("360") or a closed
# range ("520-535"). The ^...$ anchors are deliberate — a segment is a line spec
# ONLY when the WHOLE segment is numeric, so a textual fragment such as "3-track"
# (a stray digit inside prose) is NOT mistaken for a range.
_LOCUS_RANGE_SEG_RE = re.compile(r"^(\d+)\s*-\s*(\d+)$")
_LOCUS_SINGLE_SEG_RE = re.compile(r"^(\d+)$")
# A parenthetical note ("(P6)", "(row-level pass deferred)") is never a line
# spec; strip it before deciding whether the remainder is a clean spec.
_LOCUS_NOTE_RE = re.compile(r"\([^)]*\)")


def _parse_locus(value: object) -> list[tuple[int, int]]:
    """Parse a ``locus`` cell into a list of (start, end) line ranges.

    Returns an empty list when the locus is NOT a clean line spec — a
    deliberately whole-file ``row-level pass deferred`` entry or a malformed
    cell. Such a row is anchorless and falls back to file + category matching.
    Kept in step with the E194 helper's ``_parse_locus`` so both gates anchor an
    open row to the SAME lines:

      1. drop any ``(...)`` note (it holds human text, never a line number — so
         the ``6`` in ``(P6)`` and the ``1``/``6`` in ``(P1-P6)`` never leak in);
      2. take only the segment AFTER the LAST ``:`` when a label separator is
         present (so the digits in a section label, ``§4 #20`` -> ``4``/``20``,
         never leak in);
      3. split the remainder on commas; EVERY segment must be a bare integer or a
         closed ``int-int`` range. If any segment is non-numeric (a textual
         deferred locus such as ``matched RLS / 3-track / sandbox``), the locus
         is NOT a line spec and the result is empty (anchorless), rather than
         letting a stray in-prose digit anchor the row to a wrong line.
    """
    raw = "" if value is None else str(value)
    spec = _LOCUS_NOTE_RE.sub(" ", raw)
    if ":" in spec:
        spec = spec.rsplit(":", 1)[1]
    segments = [s.strip() for s in spec.split(",") if s.strip()]
    if not segments:
        return []
    ranges: list[tuple[int, int]] = []
    for seg in segments:
        rng = _LOCUS_RANGE_SEG_RE.match(seg)
        if rng:
            start, end = int(rng.group(1)), int(rng.group(2))
            if start > end:
                start, end = end, start
            ranges.append((start, end))
            continue
        single = _LOCUS_SINGLE_SEG_RE.match(seg)
        if single:
            n = int(single.group(1))
            ranges.append((n, n))
            continue
        # A non-numeric segment -> this locus is not a clean line spec; treat the
        # whole row as anchorless (whole-file deferred) rather than half-parsing.
        return []
    ranges.sort()
    return ranges


@dataclass(frozen=True)
class Finding:
    path: str          # repo-relative POSIX path
    line_no: int       # 1-based
    family: str
    label: str
    excerpt: str       # trimmed matching line


def _iter_target_files(root: Path) -> list[Path]:
    """L0 + L1 markdown authority docs, excluding _template scaffolds."""
    docs = root / "architecture" / "docs"
    out: list[Path] = []
    for level in ("L0", "L1"):
        base = docs / level
        if not base.is_dir():
            continue
        for md in sorted(base.rglob("*.md")):
            # _template/ docs are scaffolds, not authority — never scanned.
            if "_template" in md.relative_to(root).parts:
                continue
            out.append(md)
    return out


def _line_is_suppressed(lines: list[str], idx: int) -> bool:
    """True when an allow-token sits on this line or the line directly above."""
    if ALLOW_TOKEN in lines[idx]:
        return True
    if idx > 0 and ALLOW_TOKEN in lines[idx - 1]:
        return True
    return False


# Neighbourhood radius for the delegation-pointer guard. A markdown bullet that
# names a forbidden category and delegates it to a contract/L2/fact often spans
# the trigger line plus a continuation line or two (e.g. "... wire shape for X
# (the\n single authority ...; not restated here)."). Two lines on each side
# covers the observed delegation bullets without reaching across blank-line
# boundaries into an unrelated block.
_DELEGATION_WINDOW = 2


def _is_delegation_pointer(lines: list[str], idx: int) -> bool:
    """True when the match at ``idx`` sits inside a delegation pointer, not a leak.

    Requires BOTH an explicit delegation cue AND a home reference within the
    bullet/sentence neighbourhood (``±_DELEGATION_WINDOW`` lines, not crossing a
    blank-line paragraph boundary). The window stops at a blank line so a
    delegation pointer in one bullet cannot launder an inlined leak in the next.
    """
    lo = idx
    while lo > 0 and lo > idx - _DELEGATION_WINDOW and lines[lo - 1].strip():
        lo -= 1
    hi = idx
    last = len(lines) - 1
    while hi < last and hi < idx + _DELEGATION_WINDOW and lines[hi + 1].strip():
        hi += 1
    block = "\n".join(lines[lo : hi + 1])
    return bool(DELEGATION_CUE_RE.search(block) and HOME_REF_RE.search(block))


@dataclass(frozen=True)
class _GrandfatherRow:
    """One open temporary-violation row, projected for E195 suppression.

    Carries the row's ``locus`` line ranges so E195 anchors its tolerance the
    same way E194 does (a leak is tolerated only inside the row's enumerated
    ranges; an anchorless ``row-level pass deferred`` row covers the whole file).
    """

    file: str               # repo-relative POSIX path
    categories: frozenset[str]  # E194 leaked-category ids the row cites
    locus_ranges: tuple[tuple[int, int], ...] = ()  # enumerated (start, end) line ranges

    @property
    def locus_anchored(self) -> bool:
        """True when this row enumerates at least one parseable line range.

        An anchored row tolerates a leak only inside its ranges; an anchorless
        row (a whole-file ``row-level pass deferred`` entry whose ``locus``
        carries no line number) falls back to file + category matching.
        """
        return bool(self.locus_ranges)

    def covers_line(self, line_no: int) -> bool:
        """Whether ``line_no`` falls inside one of this row's enumerated ranges.

        An anchorless row covers every line by construction (it deliberately
        defers a whole-file pass); an anchored row covers only its ranges, so a
        same-file same-category leak OUTSIDE every range is a different leak the
        row never enumerated and is NOT tolerated.
        """
        if not self.locus_ranges:
            return True
        return any(start <= line_no <= end for start, end in self.locus_ranges)


def load_open_grandfather_rows(root: Path) -> list[_GrandfatherRow]:
    """Best-effort load of still-open grandfather rows from the shared allow-list.

    Returns the open rows (sunset today-or-later, UTC) with a parseable file +
    category set. A missing file, an unparseable file, or an absent PyYAML
    parser yields an EMPTY list — this pure-regex helper keeps PyYAML optional,
    so the grandfather tolerance simply does not apply when the schema cannot be
    read (the delegation + wire-pointer guards still run). The companion E194
    helper, which hard-requires PyYAML, is the authority that fails closed on a
    malformed allow-list; here we never upgrade a parse miss into a verdict.
    """
    path = root / VIOLATIONS_REL
    if not path.is_file():
        return []
    try:
        import yaml  # type: ignore[import-not-found]
    except ImportError:
        return []
    try:
        with path.open("r", encoding="utf-8") as fh:
            doc = yaml.safe_load(fh)
    except (OSError, ValueError, yaml.YAMLError):  # type: ignore[attr-defined]
        return []
    if not isinstance(doc, dict):
        return []
    today = datetime.datetime.now(datetime.timezone.utc).date()
    rows: list[_GrandfatherRow] = []
    for raw in doc.get("violations", []) or []:
        if not isinstance(raw, dict):
            continue
        vfile = str(raw.get("file", "")).strip().replace("\\", "/")
        if not vfile:
            continue
        cats: set[str] = set()
        if isinstance(raw.get("categories"), list):
            cats.update(str(x).strip() for x in raw["categories"] if str(x).strip())
        single = raw.get("category")
        if single is not None and str(single).strip():
            cats.add(str(single).strip())
        if not cats:
            continue
        sunset_raw = str(raw.get("sunset_date", "")).strip()
        try:
            sunset = datetime.date.fromisoformat(sunset_raw)
        except ValueError:
            # A missing/unparseable sunset cannot prove the row is still open;
            # treat as expired (do not suppress) — mirrors the E194 helper.
            continue
        if sunset < today:
            continue
        locus_ranges = tuple(_parse_locus(raw.get("locus")))
        rows.append(
            _GrandfatherRow(
                file=vfile,
                categories=frozenset(cats),
                locus_ranges=locus_ranges,
            )
        )
    return rows


def _grandfathered(finding: "Finding", rows: list[_GrandfatherRow]) -> bool:
    """True when an open grandfather row tolerates ``finding``.

    A row tolerates the finding when ALL of: the row's ``file`` equals the
    finding's document, the row cites the finding's leaked category, AND the
    row's ``locus`` COVERS the finding's line number — for an ANCHORED row the
    line MUST fall inside one of its enumerated ranges; an ANCHORLESS
    ``row-level pass deferred`` row covers every line by construction.

    This is the locus anchoring E194's ``suppressing_row`` already enforces: it
    stops a row from tolerating an unrelated same-category leak elsewhere in the
    same file (e.g. a removed §4 row no longer masks an incidental same-category
    token in §1/§2). Without it, every same-category leak in a file collapses
    under whichever row first declares that category for the file — the blunt
    file + category scope this fix sheds so the two gates reach one verdict.

    Match preference mirrors E194: an anchored row whose range covers the line is
    preferred; only when no anchored row covers it does an anchorless fallback
    grant tolerance.
    """
    lp_cats = FAMILY_TO_LP_CATEGORIES.get(finding.family, frozenset())
    if not lp_cats:
        return False
    anchorless_fallback = False
    for row in rows:
        if row.file != finding.path:
            continue
        if not (row.categories & lp_cats):
            continue
        if not row.covers_line(finding.line_no):
            # Anchored row whose ranges do not reach this line: not its
            # adjudicated leak, so it grants no tolerance here.
            continue
        if row.locus_anchored:
            return True
        # Remember an open anchorless row but keep scanning for a more specific
        # anchored row that actually enumerates this line.
        anchorless_fallback = True
    return anchorless_fallback


def _excerpt(line: str, limit: int = 160) -> str:
    trimmed = line.strip()
    if len(trimmed) > limit:
        return trimmed[: limit - 1] + "…"
    return trimmed


def scan_file(root: Path, path: Path) -> list[Finding]:
    """Return all (un-suppressed) leak findings for one markdown file."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return []
    rel = path.relative_to(root).as_posix()
    lines = text.splitlines()
    findings: list[Finding] = []
    for idx, line in enumerate(lines):
        # Markdown links and reference paths frequently embed contract/spi file
        # names; a leak claim must be in *prose*, so skip pure link-definition
        # lines (those whose only content is a `[..](..)` link or a bare path).
        stripped = line.strip()
        if not stripped:
            continue
        if _line_is_suppressed(lines, idx):
            continue
        for family, label, patterns in LEAK_FAMILIES:
            matched = any(p.search(line) for p in patterns)
            if not matched:
                continue
            # Keep-list guard: if the line's only structural signal is an
            # enforcer citation or a package path AND nothing else in the
            # leak corpus is present beyond that token, treat it as
            # defensible. We re-test the leak match after blanking the
            # keep-list tokens; if the leak no longer matches, it was a
            # citation/package false-positive.
            #
            # EXEMPT the test_inventory family from this generic blanking: a
            # leaked test class is a fully-qualified name (`com.huawei.ascend..
            # FooIT`), so its package prefix is PART of the leak, not a bare
            # development-view package-decomposition path. Blanking the package
            # here would dissolve every one-FQN-per-bullet Verification-Matrix
            # entry (the exact surface the E194 L8 probe catches), reopening the
            # two-helpers-one-verdict gap this family closes. The defensibility
            # of a test_inventory line is decided by its dedicated, E194-mirrored
            # _is_d3_enforcer_citation guard below (a single `*ArchTest` bullet,
            # an "enforced by `X`" clause), NOT by this package/enforcer blank.
            if family != "test_inventory":
                blanked = PACKAGE_PATH_RE.sub(" ", ENFORCER_CITATION_RE.sub(" ", line))
                if not any(p.search(blanked) for p in patterns):
                    continue
            # Wire-pointer guard (wire_format ONLY): the noun-prone wire family
            # fires whenever a boundary doc names a wire shape/envelope/header
            # while POINTING at its authoritative source. A same-line
            # contract/L2/fact path, `*.v1.yaml`, `ADR-NNNN`, or enforcer
            # citation marks the match as a reference, not an inlined format
            # (inlined wire leaks carry the encoding/grammar, never their own
            # contract pointer — see WIRE_POINTER_RE rationale).
            if family == "wire_format" and WIRE_POINTER_RE.search(line):
                continue
            # D3-enforcer-citation guard (test_inventory ONLY): a test-inventory
            # match that is really an ArchUnit / enforcer MECHANISM citation
            # (a single `*ArchTest` bullet, an "enforced by `X`" clause) is
            # in-layer at L0/L1 per the verdict's keep-list — skip it. This keeps
            # E195 in lockstep with the E194 L8 carve-out now that both helpers
            # cover the bullet/table inventory shape, so neither over-fires on a
            # defensible enforcer identity the other spares.
            if family == "test_inventory" and _is_d3_enforcer_citation(line):
                continue
            # Delegation-pointer guard (all families): a match inside a bullet
            # that NAMES the category only to delegate it to its home
            # ("delegated to / owned downstream / not restated here ...
            # <contract/L2/fact/ADR>") is the boundary doc doing its job, not a
            # leak. The dated grandfather list — not this guard — remains the
            # tolerance for a genuinely inlined-but-unmigrated block.
            if _is_delegation_pointer(lines, idx):
                continue
            findings.append(
                Finding(
                    path=rel,
                    line_no=idx + 1,
                    family=family,
                    label=label,
                    excerpt=_excerpt(stripped),
                )
            )
            break  # one finding per line is enough to flag it
    return findings


def _normalize_changed(root: Path, changed: list[str]) -> set[str]:
    """Normalize --changed args to repo-relative POSIX paths for comparison."""
    out: set[str] = set()
    for raw in changed:
        raw = raw.strip()
        if not raw:
            continue
        p = Path(raw)
        try:
            if p.is_absolute():
                out.add(p.resolve().relative_to(root).as_posix())
            else:
                out.add((root / p).resolve().relative_to(root).as_posix())
        except ValueError:
            # Path outside the repo — keep the raw form so an exact string
            # match can still work if the caller passed a repo-relative path.
            out.add(Path(raw).as_posix())
    return out


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="L2-detail-sink — L2 implementation detail leaked into L0/L1 prose (Rule G-27 / E195)"
    )
    parser.add_argument(
        "--mode",
        default="advisory",
        choices=VALID_MODES,
        help="Ratchet rung: advisory (default), changed-files-blocking, or blocking.",
    )
    parser.add_argument(
        "--changed",
        action="append",
        default=[],
        help="A changed file (repeatable). Only consulted in changed-files-blocking mode.",
    )
    parser.add_argument(
        "--repo",
        default=None,
        help="Repository root. Defaults to the script-derived root.",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(args.repo).resolve() if args.repo else repo_root()
    if not root.is_dir():
        print(f"ERROR: --repo {root} is not a directory", file=sys.stderr)
        return 1

    targets = _iter_target_files(root)
    raw_findings: list[Finding] = []
    for path in targets:
        raw_findings.extend(scan_file(root, path))

    # Partition against the shared dated grandfather list: a finding whose
    # (file, family->category) matches an OPEN row is a tolerated, not-yet-
    # migrated leak — reported as a grandfathered advisory, never counted for a
    # blocking exit. This is the same tolerance surface the E194 helper honours.
    grandfather_rows = load_open_grandfather_rows(root)
    all_findings: list[Finding] = []
    grandfathered: list[Finding] = []
    for f in raw_findings:
        if _grandfathered(f, grandfather_rows):
            grandfathered.append(f)
        else:
            all_findings.append(f)

    # Emit a deterministic, grep-friendly report to stderr.
    for f in all_findings:
        print(
            f"L2-DETAIL-SINK {f.path}:{f.line_no} [{f.family}] {f.label} :: {f.excerpt}",
            file=sys.stderr,
        )
    for f in grandfathered:
        print(
            f"L2-DETAIL-SINK GRANDFATHERED {f.path}:{f.line_no} [{f.family}] "
            f"tolerated by an open {VIOLATIONS_REL} row",
            file=sys.stderr,
        )

    # Family-level summary line (consumed by the gate's advisory grep on
    # 'finding(s)'); printed even at zero so the gate can confirm the helper ran.
    by_family: dict[str, int] = {}
    for f in all_findings:
        by_family[f.family] = by_family.get(f.family, 0) + 1
    if by_family:
        breakdown = ", ".join(f"{fam}={n}" for fam, n in sorted(by_family.items()))
        summary = (
            f"{len(all_findings)} L2-detail-sink finding(s) across "
            f"{len({f.path for f in all_findings})} L0/L1 doc(s): {breakdown}"
            f" ({len(grandfathered)} grandfathered)"
        )
    else:
        summary = (
            "0 L2-detail-sink finding(s): L0/L1 prose is altitude-clean"
            f" ({len(grandfathered)} grandfathered)"
        )
    print(summary, file=sys.stderr)

    # Mode-dependent exit.
    if args.mode == "advisory":
        return 0
    if args.mode == "blocking":
        return 1 if all_findings else 0
    # changed-files-blocking: block only on a finding in a changed file.
    changed = _normalize_changed(root, args.changed)
    blocking = [f for f in all_findings if f.path in changed]
    if blocking:
        print(
            f"BLOCKING: {len(blocking)} L2-detail-sink finding(s) on changed file(s); "
            "migrate the implementation detail to architecture/docs/L2/ + the contract surface",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
