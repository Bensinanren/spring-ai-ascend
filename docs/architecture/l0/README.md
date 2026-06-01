---
level: L0
view: governance
status: draft
authority: "draft/proposal area only; canonical L0 is architecture/docs/L0/ARCHITECTURE.md"
document_role: proposal_collection
source_of_truth: false
canonical_l0: ../../../architecture/docs/L0/ARCHITECTURE.md
---

# Draft L0 Delivery Views

This directory contains draft delivery views and proposal material. It is not
the canonical L0 root. The accepted L0 architecture is
[`../../../architecture/docs/L0/ARCHITECTURE.md`](../../../architecture/docs/L0/ARCHITECTURE.md).

## Directory Roles

| Path | Draft Role | Promotion Target |
|---|---|---|
| `00-overview/` | Draft overview and glossary material. | `architecture/docs/L0/` or accepted product/governance docs. |
| `01-capabilities/` | Draft capability mapping. | `architecture/features/` and canonical L0/L1 prose. |
| `02-scenarios/` | Draft business and technical scenarios. | `architecture/docs/L0/`, `architecture/docs/L1/`, or accepted harness specs. |
| `03-adrs/` | Draft decision notes. | `docs/adr/` after formal acceptance. |
| `04-modules/` | Draft module responsibility packets. | `architecture/docs/L1/<module>/` after acceptance. |
| `05-contracts/` | Draft ICD and machine-readable contract sketches. | `docs/contracts/` plus catalog/ADR/test binding. |
| `06-state/` | Draft state ownership analysis. | Canonical L0/L1 state sections. |
| `07-invariants/` | Draft invariants. | Canonical L0/L1 constraints or governance rules. |
| `08-harness/` | Draft harness specs. | Accepted harness or test documentation. |
| `09-verification/` | Draft verification matrix. | Canonical verification DSL, tests, or governance ledgers. |
| `10-governance/` | Draft A2D process material. | `docs/governance/` only after acceptance. |
| `l1/` | Historical/draft L1 copies. | `architecture/docs/L1/<module>/` after triage; otherwise archive. |

## Reading Rule

Read this directory only after the canonical architecture path has been read.
When this directory conflicts with `architecture/`, `docs/contracts/`,
`docs/adr/`, or `docs/governance/`, the canonical source wins.

No new accepted L1 or L2 material should be authored here. New accepted L1 lives
under `architecture/docs/L1/`; accepted L2 lives under `architecture/docs/L2/`.
