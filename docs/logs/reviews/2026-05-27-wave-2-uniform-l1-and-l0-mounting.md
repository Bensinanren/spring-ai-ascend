---
level: L0
view: scenarios
affects_level: L0, L1
affects_view: scenarios, logical, development
affects_artefact: [ARCHITECTURE.md]
status: amendment
authors:
  - chao
authority: "ADR-0152 (Uniform L1 per-view mechanism + L0 mounting)"
---

# Wave 2 — Root ARCHITECTURE.md move to `architecture/docs/L0/`

This review record satisfies Rule G-1 sub-clause .a's frozen-doc-edit-path
requirement for Wave 2's edits to the frozen root `ARCHITECTURE.md`
(freeze_id: `W1-russell-2026-05-14`).

## What changed

`git mv ARCHITECTURE.md architecture/docs/L0/ARCHITECTURE.md` — single
move; preserves frontmatter (`freeze_id`, `level: L0`, `view`); preserves
git blame (`git log --follow architecture/docs/L0/ARCHITECTURE.md` traces
the full history).

## Why

ADR-0152 (Wave 2 of the L1 Feature Registry plan) — see body. The user
directive 2026-05-27 (`L0 mounting Q2=A`):

  > "把 ARCHITECTURE.md 移到 architecture/docs/L0/ARCHITECTURE.md
  >（与 L1 模块根的 W8 迁移同形）"

Structurizr's `!docs docs` directive at `architecture/workspace.dsl`
(wired in W8 ADR-0150) imports from `architecture/docs/` only. L0
ARCHITECTURE.md outside that subtree was a Reading-path reference, not
a workspace node. The move puts L0 under `!docs docs` scope so
workspace.dsl reaches the L0 corpus directly — closing the asymmetry
between L0 (referenced) and L1 (imported).

## Scope

- L0 corpus content: unchanged. No section text edited; only the file
  path moved.
- L0 corpus frontmatter: unchanged. `freeze_id` carries over.
- L1 corpus shape (Wave 2 companion): see ADR-0152 — the 6
  non-agent-service modules' single-`.md` files converted to canonical
  directory shape; agent-service unchanged (already canonical).
- Rule G-1.a kernel + card path regex update: `^ARCHITECTURE\.md$` →
  `^architecture/docs/L0/ARCHITECTURE\.md$`; L1 regex narrows from
  `architecture/docs/L1/<module>{.md,/}` to
  `architecture/docs/L1/<module>/`.
- Rule G-1.1.d (NEW, advisory at W2): canonical L1 directory file-set
  parity with `architecture/docs/L1/_template/`.

## Cross-references

- ADR-0151 — L1 Feature Registry canonical schema (Wave 1).
- ADR-0152 — Uniform L1 mechanism + L0 mounting (this wave's authority).
- `docs/governance/rules/rule-G-1.md` sub-clause .a — amended L0/L1 path regex.
- `docs/governance/rules/rule-G-1.1.md` — sub-clause .d added.
- `architecture/docs/L1/_template/` — canonical L1 module skeleton.
- `architecture/docs/L1/<module>/` — 7 modules now share the canonical shape.
