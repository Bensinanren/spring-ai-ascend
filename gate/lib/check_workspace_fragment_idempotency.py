#!/usr/bin/env python3
"""
Wave 3 byte-identical regeneration gate for architecture/generated/*.dsl.

Authority: ADR-0147 (Structurizr Workspace Authority); Rule G-13 (Single-Source
Rendering Coherence).

Steps:
  1. Snapshot the current contents of architecture/generated/.
  2. Run AllFragmentsCli to re-emit every fragment.
  3. Diff the re-emitted files against the snapshot.
  4. Exit 0 if byte-identical; exit non-zero on any drift.

Wave 5 will plug this into gate/check_architecture_workspace.sh's blocking
mode. Until then it is invoked manually or by the W1 advisory gate.

Invocation (run inside Linux/WSL per Rule G-7):
    python3 gate/lib/check_workspace_fragment_idempotency.py
"""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GENERATED_DIR = REPO / "architecture" / "generated"
TOOL_POM = REPO / "tools" / "architecture-workspace" / "pom.xml"
TOOL_CLASSES = REPO / "tools" / "architecture-workspace" / "target" / "classes"
CLI_MAIN = "com.huawei.ascend.tools.architecture.fragment.AllFragmentsCli"

# Fragments expected under architecture/generated/. The README is not emitted.
EXPECTED_FRAGMENTS = [
    "modules.dsl",
    "spi-catalog.dsl",
    "enforcers.dsl",
    "principles.dsl",
    "rules.dsl",
    "adr-graph.dsl",
    "surface-classification.dsl",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def snapshot() -> dict[str, str]:
    return {name: sha256(GENERATED_DIR / name) for name in EXPECTED_FRAGMENTS}


def build_classpath() -> str:
    """Run mvn dependency:build-classpath if classes/ exists; else mvn compile first."""
    if not TOOL_CLASSES.exists():
        subprocess.run(
            ["./mvnw", "-B", "-q", "-f", str(TOOL_POM), "compile"],
            cwd=REPO,
            check=True,
        )
    cp_file = REPO / "out" / "architecture" / "tool-classpath.txt"
    cp_file.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "./mvnw",
            "-B",
            "-q",
            "-f",
            str(TOOL_POM),
            "dependency:build-classpath",
            f"-Dmdep.outputFile={cp_file}",
        ],
        cwd=REPO,
        check=True,
    )
    sep = ";" if os.name == "nt" else ":"
    return str(TOOL_CLASSES) + sep + cp_file.read_text(encoding="utf-8").strip()


def re_emit() -> None:
    cp = build_classpath()
    subprocess.run(
        ["java", "-cp", cp, CLI_MAIN, "--repo", str(REPO)],
        cwd=REPO,
        check=True,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="Fail if re-emit produces different bytes. Default.")
    parser.parse_args()  # no flags accepted yet; reserved for W5 extension

    # Ensure all expected fragments currently exist.
    missing = [name for name in EXPECTED_FRAGMENTS if not (GENERATED_DIR / name).is_file()]
    if missing:
        print(f"FAIL: missing fragments under {GENERATED_DIR}: {missing}", file=sys.stderr)
        return 2

    # Snapshot, re-emit, diff.
    before = snapshot()
    re_emit()
    after = snapshot()

    drift = []
    for name in EXPECTED_FRAGMENTS:
        if before[name] != after[name]:
            drift.append(name)

    if drift:
        print(f"FAIL: drift detected in {len(drift)} fragment(s):", file=sys.stderr)
        for name in drift:
            print(f"  - {name}: was {before[name][:12]}..., now {after[name][:12]}...",
                  file=sys.stderr)
        return 1

    print(f"PASS: all {len(EXPECTED_FRAGMENTS)} fragments byte-identical after re-emission")
    return 0


if __name__ == "__main__":
    sys.exit(main())
