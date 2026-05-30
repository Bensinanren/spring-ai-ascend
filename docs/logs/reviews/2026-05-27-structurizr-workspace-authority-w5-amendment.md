---
level: L0
view: scenarios
affects_level: L0
affects_view: scenarios
affects_artefact: [ARCHITECTURE.md]
status: amendment
authors:
  - chao
authority: "ADR-0147 + ADR-0149"
---

# Wave 5 review — ARCHITECTURE.md §65 amendment per ADR-0147

This review record satisfies Rule G-1 sub-clause .a's "phase-released L0/L1 artefacts are read-only — further edits flow through `docs/logs/reviews/`" requirement.

## What changed

`ARCHITECTURE.md` §65 was rewritten from "Architecture-graph truth" to "Architecture workspace truth (amended W5 per ADR-0147; was 'Architecture-graph truth')". The section now names `architecture/workspace.dsl` and its closure as the machine-readable architecture authority and demotes `docs/governance/architecture-graph.yaml` to a generated compatibility projection.

## Why

ADR-0147 (Structurizr workspace authority) was accepted on 2026-05-27 after ADR-0148's Wave 0 spike produced four PASS gates. The 7-wave migration plan (W0..W7) requires Rule G-1.b to be amended at W5; this amendment is the load-bearing edit to the L0 corpus.

## Scope of the amendment

- L0 corpus surface touched: ARCHITECTURE.md §65 (one section; ±20 lines).
- L0 corpus surfaces NOT touched: ARCHITECTURE.md §1..§64 + §66+ (untouched).
- The amendment preserves the freeze_id frontmatter; this review record is the explicit edit-path-compliance receipt.

## Cross-references

- ADR-0147 — authority decision.
- ADR-0148 — Wave 0 spike PASS evidence.
- ADR-0149 — W0..W5 shipped + W6/W7 entry criteria.
- `docs/governance/rules/rule-G-1.md` sub-clause .b — amended text.
- `docs/governance/structurizr-workspace-w6-sunset-roadmap.md` — W6 sub-wave schedule.
- `docs/logs/releases/2026-05-27-structurizr-workspace-authority-w0-w5.md` — release note.
- Commit `1026bbc` — the W5 commit that landed the amendment.
