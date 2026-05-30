---
level: L1
view: development
status: template
authority: "ADR-0152 (Uniform L1 per-view mechanism + L0 mounting)"
---

# `_template/` — Canonical L1 Module Directory Skeleton

This directory is the **canonical shape** every `kind: domain` module's
L1 corpus follows. It is NOT a module itself — `_template/` is excluded
from gate path-globs that walk `architecture/docs/L1/<module>/`.

## When to copy this

Bootstrapping a new module's L1 corpus:

```bash
cp -r architecture/docs/L1/_template architecture/docs/L1/<new-module>
# Replace placeholder content; the file SHAPE stays — every module's
# L1 directory must contain the same basenames (Rule G-1.1.d).
```

## Canonical file set

| File | Role | Stub vs Authored |
|---|---|---|
| `README.md` | Module summary + navigation + cross-references | AUTHORED |
| `ARCHITECTURE.md` | Shipped-state grounding: dependencies, deployment plane, current capabilities, current SPIs, status | AUTHORED |
| `logical.md` | 4+1 logical view — domain model, key abstractions, internal partitioning | STUB until module matures |
| `process.md` | 4+1 process view — concurrency model, async/sync boundaries, execution flow | STUB until module matures |
| `physical.md` | 4+1 physical view — deployment plane, topology, resource model | STUB until module matures |
| `development.md` | 4+1 development view — directory tree at package level | RENDERED (DevTreeRenderer at W3) |
| `scenarios.md` | 4+1 scenarios — 3-5 key user/system scenarios as ordered step sequences | STUB until module matures |
| `spi-appendix.md` | SPI interfaces appendix: every public interface FQN | RENDERED (SpiAppendixEmitter at W3) |
| `features/README.md` | 9-section L1 Feature Catalog (rendered from features.dsl filtered by saa.owner) | RENDERED (l1-features-catalog.md.j2 at W3) |

## Authoring discipline

- The directory SHAPE is uniform across all 7 `kind: domain` modules.
- Content DEPTH varies by maturity. Stub view files (logical / process /
  physical / scenarios) carry an `<!-- W2-stub: content authored later -->`
  marker so readers know they are placeholders.
- `development.md` + `spi-appendix.md` are RENDERED outputs (DSL is
  source of truth). Do not hand-edit; re-emit from
  `tools/architecture-workspace/.../fragment/{DevTreeRenderer,SpiAppendixEmitter}`.
- `features/README.md` is RENDERED from
  `architecture/features/features.dsl` (W1) filtered by `saa.owner`.
  Do not hand-edit; re-render from the W3 catalog template.

## Authority

- ADR-0151 — L1 Feature Registry canonical schema (Wave 1).
- ADR-0152 — Uniform L1 mechanism + L0 mounting (Wave 2; this template lands here).
- Rule G-1.1 sub-clause .d — directory file-set parity with `_template/`.
