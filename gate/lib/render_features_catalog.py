#!/usr/bin/env python3
"""
Render the 9-section L1 Feature Catalog per module from
`architecture/features/features.dsl`.

Authority: ADR-0151 (L1 Feature Registry canonical schema, W1) +
ADR-0152 (uniform L1 + W3 catalog rendering).

This is a focused renderer (not the general-purpose
`gate/lib/render_template.py`) because the catalog source is DSL,
not yaml/json — parsing happens here and the Jinja2 template
receives a per-module list of dicts.

Output: `architecture/docs/L1/<module>/features/README.md` per module.
The output is byte-identical on re-emit (Rule G-13.b).

Usage:
    python3 gate/lib/render_features_catalog.py            # render all 7 modules
    python3 gate/lib/render_features_catalog.py --check    # diff mode (exit 1 on drift)
    python3 gate/lib/render_features_catalog.py --module agent-service
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Dict, List

REPO = Path(__file__).resolve().parents[2]
FEATURES_DSL = REPO / "architecture" / "features" / "features.dsl"
TEMPLATE = REPO / "docs" / "governance" / "templates" / "l1-features-catalog.md.j2"

MODULES = [
    "agent-bus",
    "agent-client",
    "agent-evolve",
    "agent-execution-engine",
    "agent-middleware",
    "agent-service",
    "graphmemory-starter",
]


def parse_features(dsl_text: str) -> List[Dict[str, str]]:
    """Parse FEAT- elements from features.dsl into list of dicts."""
    features: List[Dict[str, str]] = []
    # Match: identifier = element "Name" "Type" "Description" "SAA Feature" { ... properties { ... } ... }
    element_re = re.compile(
        r'^\s*(\w+)\s*=\s*element\s+"([^"]+)"\s+"([^"]+)"\s+"([^"]+)"\s+"([^"]+)"\s*\{\s*\n(.*?)^\s*\}\s*$',
        re.MULTILINE | re.DOTALL,
    )
    for m in element_re.finditer(dsl_text):
        identifier, name, type_, description, tag, body = m.groups()
        if tag != "SAA Feature":
            continue
        props = {}
        prop_re = re.compile(r'^\s*"([^"]+)"\s+"([^"]*)"\s*$', re.MULTILINE)
        for pm in prop_re.finditer(body):
            props[pm.group(1)] = pm.group(2)
        props["_identifier"] = identifier
        props["_name"] = name
        props["_description"] = description
        features.append(props)
    return features


def split_list(value: str | None) -> List[str]:
    if not value:
        return []
    return [v.strip() for v in value.split("|") if v.strip()]


def render_catalog(module: str, features: List[Dict[str, str]]) -> str:
    """Render the 9-section catalog Markdown for one module."""
    own = [f for f in features if f.get("saa.owner") == module]
    own.sort(key=lambda f: f.get("saa.id", ""))

    lines: List[str] = []
    lines.append("---")
    lines.append("level: L1")
    lines.append("view: scenarios")
    lines.append("status: shipped")
    lines.append('authority: "ADR-0151 (L1 Feature Registry canonical schema) + ADR-0152 (uniform L1 + W3 catalog rendering)"')
    lines.append("---")
    lines.append("")
    lines.append("<!-- DO NOT HAND-EDIT. Rendered from architecture/features/features.dsl by gate/lib/render_features_catalog.py. Re-emit via that script; render-idempotency is enforced by Rule G-13.b. -->")
    lines.append("")
    lines.append(f"# `{module}` — L1 Feature Catalog (9-section)")
    lines.append("")
    lines.append("This catalog is the **rendered** human-readable view of the")
    lines.append(f"`{module}`-owned features registered in")
    lines.append("[`architecture/features/features.dsl`](../../../../features/features.dsl).")
    lines.append("The structured source is the DSL; this Markdown is byte-identical")
    lines.append("on re-emit. The 9 sections follow the user-supplied L1 Feature")
    lines.append("Catalog template (ADR-0151).")
    lines.append("")
    if not own:
        lines.append("## 0. No features registered yet for this module")
        lines.append("")
        lines.append(f"The module `{module}` has no SAA Feature elements declared in")
        lines.append("`architecture/features/features.dsl` at this commit. As features")
        lines.append("are authored (see [`architecture/features/README.md`](../../../../features/README.md)")
        lines.append("for the recipe), they will appear here on re-emit.")
        lines.append("")
        return "\n".join(lines) + "\n"

    lines.append("## 1. Feature Metadata")
    lines.append("")
    lines.append("| Feature ID | Name | Status | Capability Domain |")
    lines.append("|---|---|---|---|")
    for f in own:
        lines.append(
            f"| `{f.get('saa.id','')}` | {f.get('_name','')} | `{f.get('saa.status','')}` | `{f.get('saa.capabilityDomain','')}` |"
        )
    lines.append("")

    lines.append("## 2. Architecture Binding")
    lines.append("")
    for f in own:
        lines.append(f"### `{f.get('saa.id','')}`")
        lines.append("")
        dev_paths = split_list(f.get("saa.devPaths"))
        if dev_paths:
            lines.append("**Development paths:**")
            for p in dev_paths:
                lines.append(f"- `{p}`")
            lines.append("")
        lines.append(f"**Source ADR:** `{f.get('saa.sourceAdr','(none)')}`")
        lines.append("")

    lines.append("## 3. Functional Decomposition")
    lines.append("")
    lines.append("This module's features and their function-point membership are wired")
    lines.append("by `contains` relationships in")
    lines.append("[`architecture/features/features.dsl`](../../../../features/features.dsl).")
    lines.append("Walk the workspace projection from each feature ID to traverse the")
    lines.append("function-point inventory.")
    lines.append("")
    for f in own:
        lines.append(f"- `{f.get('saa.id','')}` contains the function points listed")
        lines.append(f"  under `feat... -> fp... \"contains\"` relationships in features.dsl.")
    lines.append("")

    lines.append("## 4. Contract Surface")
    lines.append("")
    lines.append("Runtime promise surfaces touched by this module's features. For the")
    lines.append("full catalog, see")
    lines.append("[`docs/contracts/contract-catalog.md`](../../../../../docs/contracts/contract-catalog.md).")
    lines.append("")

    lines.append("## 5. Runtime Behavior")
    lines.append("")
    for f in own:
        lines.append(f"### `{f.get('saa.id','')}`")
        lines.append("")
        synopsis = f.get("saa.synopsis", "")
        # Wrap synopsis at ~80 chars for readability
        lines.append(synopsis)
        lines.append("")

    lines.append("## 6. DFX Requirements")
    lines.append("")
    # Module → docs/dfx/<stem>.yaml mapping. graphmemory-starter uses the
    # full Maven artifact id; the agent-* modules use their short name.
    dfx_stem = "spring-ai-ascend-graphmemory-starter" if module == "graphmemory-starter" else module
    dfx_path = REPO / "docs" / "dfx" / f"{dfx_stem}.yaml"
    if dfx_path.exists():
        lines.append(f"DFX dimensions for `{module}` are declared in")
        lines.append(f"[`docs/dfx/{dfx_stem}.yaml`](../../../../../docs/dfx/{dfx_stem}.yaml).")
    else:
        lines.append(f"DFX dimensions for `{module}` are not yet declared in `docs/dfx/`.")
    lines.append("Per-feature DFX deltas (if any) are tracked alongside the FEAT-")
    lines.append("element in `architecture/features/features.dsl`.")
    lines.append("")

    lines.append("## 7. AI Execution Boundary")
    lines.append("")
    lines.append("Machine-readable AI boundary per feature (5 saa.aiBoundary.* sub-keys).")
    lines.append("AI agents acting on this module MUST consult these before auto-modifying:")
    lines.append("")
    lines.append("| Feature | Can modify code | Can modify contracts | Allowed transitions | Requires human review at | Sandbox policy |")
    lines.append("|---|---|---|---|---|---|")
    for f in own:
        cmc = f.get("saa.aiBoundary.canModifyCode", "false")
        cmContracts = f.get("saa.aiBoundary.canModifyContracts", "false")
        ast = f.get("saa.aiBoundary.allowedStatusTransitions", "")
        rhr = f.get("saa.aiBoundary.requiresHumanReviewAt", "")
        spr = f.get("saa.aiBoundary.sandboxPolicyRef", "")
        ast_disp = ", ".join(split_list(ast)) or "(none)"
        rhr_disp = ", ".join(split_list(rhr)) or "(none)"
        lines.append(
            f"| `{f.get('saa.id','')}` | `{cmc}` | `{cmContracts}` | `{ast_disp}` | `{rhr_disp}` | `{spr}` |"
        )
    lines.append("")

    lines.append("## 8. Verification Matrix")
    lines.append("")
    lines.append("Tests + commands that verify each feature. AI agents MUST run these")
    lines.append("commands after auto-modifying the feature's owning code.")
    lines.append("")
    for f in own:
        lines.append(f"### `{f.get('saa.id','')}`")
        lines.append("")
        test_fqns = split_list(f.get("saa.verificationTestFqns"))
        if test_fqns:
            lines.append("**Verification test FQNs:**")
            for t in test_fqns:
                lines.append(f"- `{t}`")
            lines.append("")
        cmds = split_list(f.get("saa.verificationCommands"))
        if cmds:
            lines.append("**Verification commands:**")
            for c in cmds:
                lines.append(f"- `{c}`")
            lines.append("")

    lines.append("## 9. Lifecycle / Governance")
    lines.append("")
    lines.append("Feature lifecycle state machine (Rule G-14):")
    lines.append("")
    lines.append("```")
    lines.append("proposed -> accepted -> design_only -> ready_for_impl")
    lines.append("                                    -> implemented_unverified")
    lines.append("                                    -> test_verified -> shipped")
    lines.append("                                    -> deprecated -> removed")
    lines.append("```")
    lines.append("")
    lines.append("Current state per feature:")
    lines.append("")
    for f in own:
        lines.append(f"- `{f.get('saa.id','')}` — `{f.get('saa.status','')}`")
    lines.append("")
    lines.append("Status transitions are governed by Rule G-14 (advisory at W1, blocking")
    lines.append("at W5 after soak). Forward-only by default; backward transitions")
    lines.append("require an ADR `extends:` or `relates_to:` the feature's source ADR.")
    lines.append("")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="diff mode (exit 1 on drift)")
    parser.add_argument("--module", help="render a single module only")
    args = parser.parse_args()

    dsl_text = FEATURES_DSL.read_text(encoding="utf-8")
    features = parse_features(dsl_text)
    print(f"Parsed {len(features)} SAA Feature elements from {FEATURES_DSL.relative_to(REPO)}")

    modules = [args.module] if args.module else MODULES
    drift = False
    for m in modules:
        rendered = render_catalog(m, features)
        out = REPO / "architecture" / "docs" / "L1" / m / "features" / "README.md"
        if args.check:
            current = out.read_text(encoding="utf-8") if out.exists() else ""
            if current != rendered:
                drift = True
                print(f"DRIFT: {out.relative_to(REPO)}")
            else:
                print(f"OK:    {out.relative_to(REPO)}")
        else:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(rendered, encoding="utf-8", newline="\n")
            print(f"wrote {out.relative_to(REPO)}")
    if args.check and drift:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
