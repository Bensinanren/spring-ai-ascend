---
level: L0
view: scenarios
status: shipped
authority: "ADR-0150 (W8 docs consolidation) + ADR-0152 (W2 uniform L1 + L0 mounting)"
---

# `architecture/docs/` — Architecture Documentation

This directory holds the **human-readable companion** to the machine-readable
workspace at [`../workspace.dsl`](../workspace.dsl). Three layers:

- **L0** — declarative system boundary + 65 §4 architectural constraints. See [L0/ARCHITECTURE.md](L0/ARCHITECTURE.md).
- **L1** — per-module design (every `kind: domain` module). See [L1/README.md](L1/README.md).
- **L2** — deeper subsystem designs. See [L2/README.md](L2/README.md).

For the architecture authority itself (the machine-readable model and its
typed graph), open [`../workspace.dsl`](../workspace.dsl). This `docs/`
subtree is the prose companion; the workspace is the canonical model.

## Reading path

1. [`../workspace.dsl`](../workspace.dsl) + [`../README.md`](../README.md) — architecture authority entry point.
2. [`L0/ARCHITECTURE.md`](L0/ARCHITECTURE.md) — declarative L0 constraints.
3. [`L1/<module>/`](L1/) — pick your module; read its canonical 8-file set (README, ARCHITECTURE, 4+1 views, spi-appendix, features/README).
4. [`L2/`](L2/) — deeper subsystem designs (where applicable).

The 7-step Reading path declared in repo-root [`../../README.md`](../../README.md#reading-path)
covers `architecture/docs/` as steps 2-4 of the full architecture surface.
