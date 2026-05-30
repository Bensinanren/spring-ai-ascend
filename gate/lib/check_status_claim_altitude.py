#!/usr/bin/env python3
"""Gate check: status-claim altitude — L2/code detail leaked into the capability ledger.

Authority: docs/governance/rules/rule-G-34.md (kernel Rule G-34), enforcer E201,
gate Rule 151 (changed-files-blocking). Extends the adjudicated layer-purity
VERDICT (the "L0/L1 carries L2/code detail" critique is TRUE) to the HIGHER-
authority capability-status ledger.

Motivation. The two existing layer-purity helpers scope EXCLUSIVELY to the L0/L1
markdown prose:

  * gate/lib/check_layer_purity.py  (E194) -> architecture/docs/L0/**/*.md +
    architecture/docs/L1/**/*.md
  * gate/lib/check_l2_detail_sink.py (E195) -> the same two roots

But the ``allowed_claim`` strings in docs/governance/architecture-status.yaml are
a free-form narrative field with no altitude guard, and they have organically
accreted exactly the categories the verdict named — SQL / ``ON CONFLICT`` /
``PRIMARY KEY`` (L3), HTTP status codes + route-verbs + wire error tokens (L4),
``@Order`` tie-break + ``Class.getName()`` (L5), ``DROP_OLDEST`` buffer + W3C
``traceparent`` + ``OTLP/HTTP`` (L6), method signatures + ``A.b() -> c`` call
chains (L1/L7), and enumerated ``*IT`` test inventories (L8). Per the authority
cascade (generated facts > DSL > Card/prose) this ledger OUTRANKS the L0/L1
prose, so a leak here is at least as significant as the prose leaks the two
helpers above already gate — yet nothing scanned it. This helper closes that
by-authority-surface gap.

What this check is. A READABLE-INTERPRETATION classifier over the ledger's
``allowed_claim`` strings, the status-ledger analogue of check_layer_purity.py.
It reuses the SAME closed category vocabulary
(docs/governance/layer-purity-policy.yaml: leaked L1..L8) and the SAME closed,
per-entry dated grandfather list
(docs/governance/layer-purity-temporary-violations.yaml) those helpers consume.
It invents no id and no relationship and it never outranks a generated fact —
it reads the policy as the vocabulary and the ledger as the data, and reports a
leaked-category trigger in an ``allowed_claim`` value that is not redeemed by a
still-open grandfather row.

Shared trigger library. The leaked-category trigger probes are IMPORTED
verbatim from gate/lib/check_layer_purity.py (its ``TRIGGERS`` dict + the
``_is_d3_enforcer_citation`` D3 carve-out), so the status ledger is judged by
exactly the same mechanism the L0/L1 prose is — one verdict, one probe set,
three surfaces. If the import fails (the sibling helper is absent), this is a
config error (exit 2): the gate fails closed rather than scan with a private,
drifting copy of the probes.

Scope of the scan. Every FREE-FORM NARRATIVE field of
docs/governance/architecture-status.yaml — the ``allowed_claim:`` values AND the
sibling ``note:`` values. Both are un-altitude-guarded prose on the SAME
authority surface: an ``allowed_claim`` is a capability's behavioural claim, a
``note`` is its decision/status narrative (module materialization history, ADR
pointers, "SQL artifact present; @Disabled tests" status), and either can
restate an L1..L8 leaked category. Scoping to ``allowed_claim`` alone left a
by-file-type hole at field granularity: a future ``note`` that inlined SQL /
route / method detail would have been invisible to this gate while an identical
``allowed_claim`` leak blocked. Both fields are now co-scanned by the SAME
imported trigger library at no extra cost, so the by-authority-surface closure
is complete at the field level.

The structural ledger keys (``status``, ``shipped``, ``implementation`` /
``tests`` file lists, the ``l0_decision`` pointer, enforcer-row citations) are
NOT scanned: a path to a ``*.sql`` migration under ``implementation:`` or a test
FQN under ``tests:`` is the ledger's development-view evidence index (D2 package
decomposition), not a behavioural claim. Only the prose narrative fields
(``allowed_claim`` + ``note``) — the fields that have no altitude guard and
organically leak — are in scope.

Grandfather rows for this surface. They live in a DEDICATED, per-surface dated
allow-list, docs/governance/layer-purity-status-ledger-grandfather.yaml, with the
SAME row schema and the SAME closed-list discipline as the L0/L1 list
(docs/governance/layer-purity-temporary-violations.yaml) but scoped to this one
surface. A row tolerates a ledger leak when its ``file`` equals
docs/governance/architecture-status.yaml, it cites the leaked category, and its
``sunset_date`` is today-or-later (UTC). Such rows carry ``layer: STATUS-LEDGER``
(a free-form label, distinct from L0/L1).

Why a SEPARATE allow-list rather than appending to the L0/L1 list: the two
surfaces have different owners and different gates (E194/E195 scan the L0/L1
documents; E201 scans this ledger), so a per-surface tolerance file keeps each
gate's allow-list cohesive and avoids cross-surface write contention on one
file. The CATEGORY VOCABULARY stays shared and single-source
(docs/governance/layer-purity-policy.yaml, imported below), so the two surfaces
are judged by one verdict; only the per-surface tolerance rows are split. A
STATUS-LEDGER row is INERT for E194/E195 regardless (those helpers never read
this file), so the split introduces no risk of cross-suppression.

Modes (``--mode``):

  advisory                 Scan every ``allowed_claim`` value, print findings +
                           grandfathered notes to stderr, and ALWAYS exit 0.
                           The soak posture.
  changed-files-blocking   Block only when docs/governance/architecture-status.yaml
                           is itself in the changed set (vs ``--base``, default
                           ``origin/main``): a PR that edits the ledger may not
                           ADD or worsen a claim-altitude leak; pre-existing
                           (grandfathered) leaks stay advisory. When the ledger
                           is untouched, every finding stays advisory. This is
                           the ratchet posture.
  full-blocking            Exit 1 on any non-grandfathered finding. The terminal
                           posture once every ``allowed_claim`` is altitude-clean
                           and every status-ledger grandfather row is retired.

Usage:
    python3 gate/lib/check_status_claim_altitude.py --mode advisory
    python3 gate/lib/check_status_claim_altitude.py --mode changed-files-blocking
    python3 gate/lib/check_status_claim_altitude.py --mode changed-files-blocking --base origin/main
    python3 gate/lib/check_status_claim_altitude.py --mode full-blocking
    python3 gate/lib/check_status_claim_altitude.py --mode full-blocking --repo /path/to/repo

Exit codes:
    0 — passed (always, in advisory mode); or no in-scope findings in a blocking
        mode
    1 — one or more in-scope, non-grandfathered findings in a blocking mode
    2 — usage / configuration error (bad mode, unreadable/empty policy or ledger,
        un-importable shared trigger library, zero claims discovered, etc.)

Authority: ADR-0159 (Progressive Learning Curve and Authority Lanes) §7 + §9.
"""
from __future__ import annotations

import argparse
import datetime
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


# ---------------------------------------------------------------------------
# Canonical surface locations (repo-relative, forward slash).
# ---------------------------------------------------------------------------
STATUS_LEDGER_REL = "docs/governance/architecture-status.yaml"
POLICY_REL = "docs/governance/layer-purity-policy.yaml"
# Per-surface dated grandfather list for the status ledger. Separate from the
# L0/L1 prose allow-list (docs/governance/layer-purity-temporary-violations.yaml)
# so each gate's tolerance rows stay cohesive and write-contention-free; the
# CATEGORY VOCABULARY (POLICY_REL) remains shared and single-source.
VIOLATIONS_REL = "docs/governance/layer-purity-status-ledger-grandfather.yaml"

# The grandfather-row layer label this surface uses. Distinct from L0/L1 so a
# status-ledger row never matches an E194/E195 L0/L1-document leak.
STATUS_LEDGER_LAYER = "STATUS-LEDGER"

# The ledger's free-form NARRATIVE fields — the only keys scanned for leaked
# categories. ``allowed_claim`` is a capability's behavioural claim; ``note`` is
# its decision/status narrative. Both are un-altitude-guarded prose on the same
# surface, so both are co-scanned by the same trigger library (scoping to
# ``allowed_claim`` alone left a by-file-type hole: a ``note`` that inlined L2
# detail would be invisible). Every OTHER key is the structural D2 evidence index
# (file lists, l0_decision, enforcer rows) and is never scanned.
NARRATIVE_FIELDS = ("allowed_claim", "note")


def repo_root() -> Path:
    """Repository root — two directories above this script (gate/lib/..)."""
    return Path(__file__).resolve().parent.parent.parent


def _rel(path: Path, root: Path) -> str:
    try:
        return str(path.relative_to(root)).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


def _utc_today() -> datetime.date:
    """Today's date in UTC.

    The freshness/sunset convention in this repo is UTC: a local +08 date can be
    one day ahead of the CI clock, so a sunset compared against ``date.today()``
    could expire a row a day early on a local run and pass on CI. Anchoring on
    UTC keeps local and CI verdicts identical.
    """
    return datetime.datetime.now(datetime.timezone.utc).date()


# ===========================================================================
# Shared trigger library import.
#
# The leaked-category probes (TRIGGERS) and the D3 enforcer-citation carve-out
# (_is_d3_enforcer_citation) are the EXECUTABLE form of the verdict's rubric.
# They MUST be the same ones check_layer_purity.py applies to the L0/L1 prose, so
# the status ledger is judged identically — one verdict, one probe set, three
# surfaces. We import them rather than copy them; a private copy would drift.
# The import puts gate/lib on sys.path so the sibling module resolves whatever
# the caller's CWD is.
# ===========================================================================
def _import_shared_triggers() -> tuple[object, object] | tuple[None, None]:
    """Return ``(TRIGGERS, _is_d3_enforcer_citation)`` from check_layer_purity.

    Returns ``(None, None)`` when the sibling helper cannot be imported; the
    caller treats that as a config error (exit 2) — we never fall back to a
    private probe copy that could silently diverge from the canonical verdict.
    """
    lib_dir = str(Path(__file__).resolve().parent)
    if lib_dir not in sys.path:
        sys.path.insert(0, lib_dir)
    try:
        import check_layer_purity  # type: ignore[import-not-found]
    except ImportError:
        return None, None
    triggers = getattr(check_layer_purity, "TRIGGERS", None)
    d3 = getattr(check_layer_purity, "_is_d3_enforcer_citation", None)
    if not isinstance(triggers, dict) or not callable(d3):
        return None, None
    return triggers, d3


# ===========================================================================
# YAML loading. PyYAML is a hard dependency of the gate (Rule 38 fails fast
# without it); a missing parser for a policy/ledger surface here is therefore a
# config error (exit 2) — fail closed rather than pass vacuously without schema.
# ===========================================================================
def _load_yaml(path: Path) -> tuple[object | None, str]:
    """Load ``path`` as YAML. Returns ``(data, error)``; ``error`` is '' on success."""
    if not path.is_file():
        return None, f"missing file {path}"
    try:
        import yaml  # type: ignore[import-not-found]
    except ImportError:
        return None, (
            "PyYAML is not installed; cannot parse "
            f"{path} (run: pip install -r gate/requirements.txt)"
        )
    try:
        with path.open("r", encoding="utf-8") as fh:
            return yaml.safe_load(fh), ""
    except yaml.YAMLError as exc:  # type: ignore[attr-defined]
        return None, f"{path} failed YAML parse: {exc}"
    except (OSError, ValueError) as exc:
        return None, f"cannot read {path}: {exc}"


# ===========================================================================
# Policy model (the leaked-category vocabulary + the dated grandfather list).
# We reuse only the slices this helper needs: the leaked category ids/titles/
# homes and the open-row tolerance set.
# ===========================================================================
@dataclass
class Category:
    id: str
    kind: str  # "defensible" | "leaked"
    title: str
    home: str


@dataclass
class Violation:
    """One row from the status-ledger grandfather list (a dated grandfather)."""

    id: str
    layer: str
    file: str  # repo-relative, forward slash
    capability: str  # the ledger capability key this row freezes ("" if unset)
    categories: set[str]
    sunset_date: datetime.date | None
    raw_sunset: str

    def is_open(self, today: datetime.date) -> bool:
        """A row tolerates a leak only while its sunset is today-or-later (UTC).

        A missing/unparseable sunset is ALREADY-EXPIRED (it cannot prove it is
        still open), so a malformed allow-list row never silently suppresses.
        """
        return self.sunset_date is not None and self.sunset_date >= today


def _parse_sunset(value: object) -> tuple[datetime.date | None, str]:
    raw = "" if value is None else str(value)
    if not raw.strip():
        return None, raw
    try:
        return datetime.date.fromisoformat(raw.strip()), raw
    except ValueError:
        return None, raw


def load_leaked_categories(root: Path) -> tuple[dict[str, Category] | None, list[str]]:
    """Load the leaked-category vocabulary (L1..L8) from the policy surface."""
    errors: list[str] = []
    policy_doc, perr = _load_yaml(root / POLICY_REL)
    if perr:
        return None, [f"policy: {perr}"]
    if not isinstance(policy_doc, dict):
        return None, [f"policy: top-level of {POLICY_REL} must be a mapping"]
    rows = policy_doc.get("categories")
    if not isinstance(rows, list) or not rows:
        return None, [f"policy: {POLICY_REL} missing non-empty 'categories' list"]
    leaked: dict[str, Category] = {}
    for idx, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f"policy: categories[{idx}] must be a mapping")
            continue
        cid = str(row.get("id", "")).strip()
        kind = str(row.get("kind", "")).strip()
        if not cid:
            errors.append(f"policy: categories[{idx}] missing 'id'")
            continue
        if kind == "leaked":
            leaked[cid] = Category(
                id=cid,
                kind=kind,
                title=str(row.get("title", cid)).strip(),
                home=str(row.get("home", "")).strip(),
            )
    if errors:
        return None, errors
    if not leaked:
        return None, [f"policy: {POLICY_REL} defines no leaked categories"]
    return leaked, []


def load_violations(root: Path, known_categories: set[str]) -> tuple[list[Violation] | None, list[str]]:
    """Load the status-ledger grandfather list. Returns ``(violations, config_errors)``."""
    errors: list[str] = []
    doc, verr = _load_yaml(root / VIOLATIONS_REL)
    if verr:
        return None, [f"grandfather: {verr}"]
    if not isinstance(doc, dict):
        return None, [f"grandfather: top-level of {VIOLATIONS_REL} must be a mapping"]
    rows = doc.get("violations")
    if rows is None:
        rows = []
    if not isinstance(rows, list):
        return None, [f"grandfather: {VIOLATIONS_REL} 'violations' must be a list"]
    violations: list[Violation] = []
    for idx, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f"grandfather: violations[{idx}] must be a mapping")
            continue
        vid = str(row.get("id", f"<row-{idx}>")).strip()
        layer = str(row.get("layer", "")).strip()
        vfile = str(row.get("file", "")).strip().replace("\\", "/")
        capability = str(row.get("capability", "")).strip()
        cats: set[str] = set()
        if isinstance(row.get("categories"), list):
            cats.update(str(x).strip() for x in row["categories"] if str(x).strip())
        single = row.get("category")
        if single is not None and str(single).strip():
            cats.add(str(single).strip())
        sunset, raw_sunset = _parse_sunset(row.get("sunset_date"))
        # Cross-check each cited category against the policy vocabulary so a typo
        # in the allow-list cannot silently over-suppress.
        for c in cats:
            if c not in known_categories:
                errors.append(
                    f"grandfather: row {vid!r} cites category {c!r} "
                    f"absent from {POLICY_REL} categories"
                )
        # All 19 leaks share ONE file (the ledger), so file+category alone is too
        # coarse to attribute a row to its specific claim: a row MUST pin the
        # capability key it freezes, else suppression would leak across unrelated
        # same-category claims and removing one row would not un-suppress its
        # leak. Require a capability on every row that targets the ledger.
        if vfile == STATUS_LEDGER_REL and not capability:
            errors.append(
                f"grandfather: row {vid!r} targets {STATUS_LEDGER_REL} but declares "
                "no 'capability' key (capability-precise matching is required so a "
                "row freezes exactly one claim)"
            )
        violations.append(
            Violation(
                id=vid,
                layer=layer,
                file=vfile,
                capability=capability,
                categories=cats,
                sunset_date=sunset,
                raw_sunset=raw_sunset,
            )
        )
    if errors:
        return None, errors
    return violations, []


# ===========================================================================
# Claim extraction.
#
# We walk the parsed ledger structurally and collect every NARRATIVE-field string
# value (``allowed_claim`` + ``note``, per NARRATIVE_FIELDS), no matter how deep
# it nests, recording its dotted key path + which field it came from for the
# report. We deliberately do NOT scan any other key — the structural keys are the
# ledger's evidence index (D2), not a behavioural claim.
#
# Line numbers: the parsed mapping loses source positions, so we recover the
# 1-based source line of each claim by a second pass over the raw text, matching
# the narrative-field key occurrences in document order. Both the collector and
# the line-anchor walk in document order (PyYAML preserves mapping key order, and
# the raw scan is top-to-bottom), so the two lists align positionally even when a
# capability carries BOTH an ``allowed_claim`` and a ``note``. This keeps the
# report line-anchored (the grandfather list and the verdict both cite line
# ranges) without a positional YAML loader.
# ===========================================================================
@dataclass
class Claim:
    key_path: str   # dotted path to the field's parent, e.g. capabilities.idempotency_store
    line_no: int    # 1-based source line of the narrative-field key (0 if unknown)
    text: str       # the field string value
    field: str = "allowed_claim"  # which narrative field this came from (allowed_claim | note)


def _collect_claim_values(node: object, path: str, out: list[tuple[str, str, str]]) -> None:
    """Depth-first collect (key_path, field, value) for every narrative-field string.

    A narrative field is any key in NARRATIVE_FIELDS (``allowed_claim`` or
    ``note``) whose value is a string. Both are co-scanned because both are
    un-altitude-guarded prose on this surface; every other key is the structural
    D2 evidence index and is skipped.
    """
    if isinstance(node, dict):
        for key, value in node.items():
            child_path = f"{path}.{key}" if path else str(key)
            if key in NARRATIVE_FIELDS and isinstance(value, str):
                out.append((path or "<root>", str(key), value))
            else:
                _collect_claim_values(value, child_path, out)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            _collect_claim_values(item, f"{path}[{i}]", out)


def _claim_source_lines(text: str) -> list[int]:
    """Return, in document order, the 1-based line of each narrative-field key.

    Matches a YAML key line for any field in NARRATIVE_FIELDS (``allowed_claim:``
    or ``note:``) at any indent, in the same top-to-bottom order the structural
    collector visits them, so the two lists pair positionally.
    """
    prefixes = tuple(f"{name}:" for name in NARRATIVE_FIELDS)
    lines: list[int] = []
    for line_no, raw in enumerate(text.splitlines(), start=1):
        # Match a YAML key line `   allowed_claim:` / `   note:` (any indent), not
        # a substring inside another value.
        stripped = raw.lstrip()
        if stripped.startswith(prefixes):
            lines.append(line_no)
    return lines


def load_claims(root: Path) -> tuple[list[Claim] | None, list[str]]:
    """Load every narrative-field value (``allowed_claim`` + ``note``), line-anchored."""
    path = root / STATUS_LEDGER_REL
    doc, lerr = _load_yaml(path)
    if lerr:
        return None, [f"status-ledger: {lerr}"]
    if not isinstance(doc, dict):
        return None, [f"status-ledger: top-level of {STATUS_LEDGER_REL} must be a mapping"]
    triples: list[tuple[str, str, str]] = []
    _collect_claim_values(doc, "", triples)
    try:
        raw_text = path.read_text(encoding="utf-8")
    except OSError as exc:
        return None, [f"status-ledger: cannot re-read {path} for line anchors: {exc}"]
    source_lines = _claim_source_lines(raw_text)
    # Pair parsed claims with source lines positionally (both are in document
    # order). If the counts disagree (an unusual multi-doc or anchor case), fall
    # back to line 0 rather than mis-anchor.
    aligned = len(source_lines) == len(triples)
    claims: list[Claim] = []
    for i, (key_path, field, value) in enumerate(triples):
        line_no = source_lines[i] if aligned and i < len(source_lines) else 0
        claims.append(Claim(key_path=key_path, line_no=line_no, text=value, field=field))
    return claims, []


# ===========================================================================
# Scanning.
# ===========================================================================
@dataclass
class Leak:
    """One detected leaked-category hit in a narrative-field value."""

    key_path: str
    capability: str  # the ledger capability key (last dotted segment of key_path)
    line_no: int
    category_id: str
    label: str
    excerpt: str
    field: str = "allowed_claim"  # the narrative field that leaked (allowed_claim | note)


def _capability_of(key_path: str) -> str:
    """The ledger capability key a claim belongs to (the last dotted segment).

    ``capabilities.idempotency_store`` -> ``idempotency_store``. A list-indexed
    or ``<root>`` path degrades to the whole string (it still equality-matches a
    row that pins the same handle).
    """
    seg = key_path.rsplit(".", 1)[-1]
    return seg.strip()


def scan_claim(
    claim: Claim,
    leaked: dict[str, Category],
    triggers: dict,
    is_d3_enforcer_citation,
) -> Leak | None:
    """Scan one claim string for a leaked-category trigger; first match wins.

    Mirrors check_layer_purity.scan_document's per-line logic: probe each leaked
    category in policy order, honour the L8 D3 enforcer-citation carve-out, and
    report at most one (leaked, non-exempt) category for the claim. The claim is
    treated as a single logical line (the verdict's trigger probes were authored
    against one-line excerpts and the ledger packs each claim onto one line).
    """
    line = claim.text
    for category_id in leaked:  # dict preserves policy order
        matched_label = None
        for pattern, label in triggers.get(category_id, ()):  # type: ignore[union-attr]
            if pattern.search(line):
                matched_label = label
                break
        if matched_label is None:
            continue
        # D3-defensible carve-out: an L8 match that is really an ArchUnit /
        # enforcer MECHANISM citation is in-layer everywhere — skip it and let a
        # genuinely-leaked lower-priority category (if any) still report.
        if category_id == "L8-test-class-inventory" and is_d3_enforcer_citation(line):
            continue
        excerpt = line.strip()
        if len(excerpt) > 160:
            excerpt = excerpt[:157] + "..."
        return Leak(
            key_path=claim.key_path,
            capability=_capability_of(claim.key_path),
            line_no=claim.line_no,
            category_id=category_id,
            label=matched_label,
            excerpt=excerpt,
            field=claim.field,
        )
    return None


def suppressing_row(leak: Leak, violations: list[Violation], today: datetime.date) -> Violation | None:
    """Return the open grandfather row that tolerates ``leak``, or None.

    A row suppresses a status-ledger leak when ALL hold:
      * the row's ``file`` is the status ledger, and
      * the row's ``capability`` equals the leak's capability key, and
      * the row cites the leak's leaked category, and
      * the row is still open (sunset today-or-later, UTC).

    The capability match is what makes a row freeze exactly ONE claim: because
    every leak on this surface shares the same ``file``, file+category alone
    would let the first same-category row suppress unrelated claims (and removing
    a cleaned claim's row would not un-suppress its leak while any same-category
    row survived). Pinning the capability key keeps each row's tolerance scoped
    to its own claim, so retiring a row re-arms the gate on that claim if it
    still leaks. The row's ``layer`` is not consulted (the file + capability
    already scope it).
    """
    for row in violations:
        if row.file != STATUS_LEDGER_REL:
            continue
        if row.capability != leak.capability:
            continue
        if leak.category_id not in row.categories:
            continue
        if row.is_open(today):
            return row
    return None


# ===========================================================================
# Changed-file scoping.
# ===========================================================================
def _git_run(args: list[str], cwd: Path) -> tuple[int, str]:
    """Run git; return ``(returncode, stdout)``. Forces UTF-8 (Windows GBK-safe)."""
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=str(cwd),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        return result.returncode, (result.stdout or "")
    except OSError:
        return 1, ""


def status_ledger_changed(root: Path, base: str) -> tuple[bool | None, str]:
    """Return True when the status ledger changed vs ``base``, or ``(None, reason)``.

    Scope = (committed diff merge-base...HEAD) UNION (uncommitted tracked changes).
    A ``None`` return means git could not resolve the base ref; the caller fails
    closed in a blocking mode (scans the whole ledger as a safe superset).
    """
    rc, _ = _git_run(["rev-parse", "--git-dir"], root)
    if rc != 0:
        return None, "not a git repository (cannot scope changed files)"

    diff_spec = base
    rc, mb = _git_run(["merge-base", base, "HEAD"], root)
    if rc == 0 and mb.strip():
        diff_spec = mb.strip()
    else:
        rc_verify, _ = _git_run(["rev-parse", "--verify", "--quiet", base], root)
        if rc_verify != 0:
            return None, f"base ref {base!r} is not resolvable in this clone"

    names: set[str] = set()
    rc, out = _git_run(["diff", "--name-only", diff_spec, "HEAD"], root)
    if rc == 0:
        names.update(line.strip().replace("\\", "/") for line in out.splitlines() if line.strip())
    rc, out = _git_run(["diff", "--name-only", "HEAD"], root)
    if rc == 0:
        names.update(line.strip().replace("\\", "/") for line in out.splitlines() if line.strip())
    return (STATUS_LEDGER_REL in names), ""


# ===========================================================================
# CLI.
# ===========================================================================
def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="status-claim altitude — detect L2/code detail leaked into the "
        "architecture-status.yaml narrative fields (allowed_claim + note), honouring "
        "the shared dated grandfather allow-list (ADR-0159, Rule G-34).",
    )
    parser.add_argument(
        "--mode",
        choices=("advisory", "changed-files-blocking", "full-blocking"),
        default="advisory",
        help="advisory (report, never block); changed-files-blocking (block only "
        "when the ledger itself changed); full-blocking (block on any). "
        "Default: advisory.",
    )
    parser.add_argument(
        "--base",
        default="origin/main",
        help="Base ref for changed-files-blocking scope. Default: origin/main.",
    )
    parser.add_argument(
        "--repo",
        default=None,
        help="Repository root. Defaults to script-derived root.",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(args.repo) if args.repo else repo_root()
    if not root.is_dir():
        print(f"status-claim-altitude: --repo {root} is not a directory", file=sys.stderr)
        return 2

    triggers, is_d3_enforcer_citation = _import_shared_triggers()
    if triggers is None or is_d3_enforcer_citation is None:
        print(
            "status-claim-altitude config error: cannot import the shared trigger "
            "library (TRIGGERS / _is_d3_enforcer_citation) from "
            "gate/lib/check_layer_purity.py; refusing to scan with a private probe "
            "copy that could drift from the canonical verdict",
            file=sys.stderr,
        )
        return 2

    leaked, cfg_err = load_leaked_categories(root)
    if leaked is None:
        for err in cfg_err:
            print(f"status-claim-altitude config error: {err}", file=sys.stderr)
        return 2

    # Every leaked category MUST have a trigger probe set in the shared library,
    # else the scan is silently blind to it (non-vacuity guard for an
    # auto-discovering rule). This mirrors check_layer_purity.main's guard.
    missing_probes = [cid for cid in leaked if not triggers.get(cid)]
    if missing_probes:
        print(
            "status-claim-altitude config error: the shared trigger library has no "
            f"probes for leaked categor/ies {sorted(missing_probes)} (scan would be "
            "blind to them)",
            file=sys.stderr,
        )
        return 2

    violations, cfg_err = load_violations(root, set(leaked))
    if violations is None:
        for err in cfg_err:
            print(f"status-claim-altitude config error: {err}", file=sys.stderr)
        return 2

    claims, cfg_err = load_claims(root)
    if claims is None:
        for err in cfg_err:
            print(f"status-claim-altitude config error: {err}", file=sys.stderr)
        return 2
    # Non-vacuity guard: this rule auto-discovers its inputs by walking the ledger
    # for narrative-field (allowed_claim + note) values. Zero values means a
    # path/format/schema drift emptied the scan set — fail closed rather than pass
    # vacuously.
    if not claims:
        print(
            "status-claim-altitude config error: discovered zero narrative-field "
            f"(allowed_claim/note) values in {STATUS_LEDGER_REL} (schema/path "
            "drift? scan would be vacuous)",
            file=sys.stderr,
        )
        return 2

    today = _utc_today()
    findings: list[Leak] = []
    grandfathered: list[tuple[Leak, Violation]] = []
    for claim in claims:
        leak = scan_claim(claim, leaked, triggers, is_d3_enforcer_citation)
        if leak is None:
            continue
        row = suppressing_row(leak, violations, today)
        if row is not None:
            grandfathered.append((leak, row))
        else:
            findings.append(leak)

    # --- report ------------------------------------------------------------
    # The finding line names the leaking field (allowed_claim | note) inside the
    # key-path bracket so a note leak is unambiguous; the capability-prefixed path
    # stays first so the gate's hit-sampler grep still matches.
    for leak in findings:
        cat = leaked.get(leak.category_id)
        home = f" -> migrate to {cat.home}" if cat and cat.home else ""
        title = cat.title if cat else leak.category_id
        print(
            f"status-claim-altitude {STATUS_LEDGER_REL}:{leak.line_no} "
            f"[{leak.key_path} {leak.field}] {leak.category_id} ({title}): {leak.label}{home}\n"
            f"    > {leak.excerpt}",
            file=sys.stderr,
        )

    for leak, row in grandfathered:
        print(
            f"status-claim-altitude GRANDFATHERED {STATUS_LEDGER_REL}:{leak.line_no} "
            f"[{leak.key_path} {leak.field}] {leak.category_id}: tolerated by {row.id} "
            f"(sunset {row.raw_sunset})",
            file=sys.stderr,
        )

    # --- expired-row advisory (housekeeping; never affects exit code) ------
    matched_row_ids = {row.id for _, row in grandfathered}
    expired_unused = [
        row
        for row in violations
        if row.file == STATUS_LEDGER_REL
        and row.sunset_date is not None
        and row.sunset_date < today
        and row.id not in matched_row_ids
    ]
    for row in sorted(expired_unused, key=lambda r: r.id):
        print(
            f"status-claim-altitude NOTE: grandfather row {row.id} expired "
            f"({row.raw_sunset}) and matched no live ledger leak; remove it from "
            f"{VIOLATIONS_REL}",
            file=sys.stderr,
        )

    summary = (
        f"status-claim-altitude [{args.mode}]: scanned {len(claims)} narrative-field "
        f"(allowed_claim/note) value(s) in {STATUS_LEDGER_REL}; {len(findings)} "
        f"finding(s), {len(grandfathered)} grandfathered"
    )
    print(summary, file=sys.stderr)

    # --- mode-dependent exit ----------------------------------------------
    if args.mode == "advisory":
        return 0
    if args.mode == "full-blocking":
        return 1 if findings else 0
    # changed-files-blocking: block only when the ledger itself changed.
    changed, reason = status_ledger_changed(root, args.base)
    if changed is None:
        # Fail closed: when scoping is unavailable, treat the ledger as changed so
        # a real leak still blocks (a safe superset, matching the E194/E195 wrap).
        print(
            f"status-claim-altitude: changed-file scoping unavailable ({reason}); "
            "treating the ledger as changed (fail-closed)",
            file=sys.stderr,
        )
        changed = True
    if changed and findings:
        print(
            f"BLOCKING: {len(findings)} status-claim-altitude finding(s) on the "
            "changed capability ledger; migrate the L2/code detail out of the "
            "allowed_claim narrative to architecture/docs/L2/ + docs/contracts/ + "
            "the generated facts (or grandfather it with a dated row)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
