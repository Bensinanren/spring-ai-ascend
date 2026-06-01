---
level: L0
view: governance
status: draft
authority: "draft/proposal area only; canonical architecture authority is architecture/workspace.dsl"
document_role: proposal_collection
source_of_truth: false
canonical_authority: ../../architecture/workspace.dsl
canonical_prose: ../../architecture/docs/
---

# Draft Architecture Material

`docs/architecture/` is a draft and delivery-view area. It is intentionally
outside the architecture-of-record. Canonical architecture truth lives in
[`../../architecture/`](../../architecture/) and starts at
[`../../architecture/workspace.dsl`](../../architecture/workspace.dsl).

Use this directory for:

- draft architecture proposals that are not accepted yet;
- delivery-oriented views used to prepare implementation or review packets;
- trustworthy-AI, gateway, or governance assessments pending triage;
- material that may later be promoted into `architecture/`, `docs/contracts/`,
  `docs/adr/`, or `docs/governance/`.

Do not use this directory for:

- canonical L0/L1/L2 architecture facts;
- accepted module boundaries;
- authoritative runtime contracts;
- accepted ADRs;
- generated facts or workspace projections.

## Canonical Reading Path

Human contributors should read accepted architecture from:

1. [`../../architecture/README.md`](../../architecture/README.md)
2. [`../../architecture/workspace.dsl`](../../architecture/workspace.dsl)
3. [`../../architecture/docs/L0/ARCHITECTURE.md`](../../architecture/docs/L0/ARCHITECTURE.md)
4. [`../../architecture/docs/L1/README.md`](../../architecture/docs/L1/README.md)
5. [`../../docs/contracts/contract-catalog.md`](../contracts/contract-catalog.md)
6. [`../../docs/adr/`](../adr/) and [`../../docs/governance/`](../governance/)
   when cited by canonical architecture.

AI assistants must read accepted architecture from:

1. [`../../architecture/facts/generated/`](../../architecture/facts/generated/)
2. [`../../architecture/workspace.dsl`](../../architecture/workspace.dsl)
3. [`../../architecture/docs/L0/ARCHITECTURE.md`](../../architecture/docs/L0/ARCHITECTURE.md)
4. the relevant [`../../architecture/docs/L1/`](../../architecture/docs/L1/)
   module directory;
5. supporting contracts, ADRs, and governance files only for specific claims.

## Promotion Rule

Material in this directory can become authoritative only through an explicit
promotion change:

1. resolve open conflicts and mark the proposal accepted;
2. move or rewrite the accepted content into `architecture/`, `docs/contracts/`,
   `docs/adr/`, or `docs/governance/` as appropriate;
3. update cross-links, module metadata, contract catalog entries, and validation
   gates where required;
4. leave this directory with either a short historical pointer or an archived
   proposal record.

Until that promotion happens, conflicts are resolved in favor of
`architecture/`, accepted contracts, accepted ADRs, and governance ledgers.
