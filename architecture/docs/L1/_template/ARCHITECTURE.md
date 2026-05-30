---
level: L1
view: development
status: template
authority: "ADR-0152 (Uniform L1 per-view mechanism + L0 mounting)"
---

# `<module>` — Shipped-state grounding

> Replace `<module>` placeholders with the module name (e.g.,
> `agent-bus`) when copying this template.

<!-- W2-stub: this is the canonical template; replace each section with module-specific content. -->

## Purpose

One paragraph: what does this module own + why it exists.

## Dependencies

| Direction | Dependency | Reason |
|---|---|---|
| upstream | `<list module-metadata.yaml#allowed_dependencies>` | … |

## Deployment plane

One of `edge` / `compute_control` / `bus_state` / `sandbox` /
`evolution` / `none` (matches `module-metadata.yaml#deployment_plane`).

## Current capabilities

List of capability ids from `architecture/features/capabilities.dsl`
filtered by `saa.owner == <module>`.

## Current SPIs

List of SPI packages from `module-metadata.yaml#spi_packages`. The
canonical detail lives at `spi-appendix.md` (rendered).

## Status

Module status from `module-metadata.yaml#kind` + the wave/state
narrative.
