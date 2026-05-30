# architecture/generated/

**GENERATED ZONE — DO NOT HAND EDIT.**

Files under this directory are produced by Java emitters in
`tools/architecture-workspace/src/main/java/com/huawei/ascend/tools/architecture/fragment/`
(landing in Wave 3). Every file in this directory starts with a
`// DO NOT HAND EDIT` header naming the emitter that produced it.

The gate verifies byte-identical regeneration:

```bash
python gate/lib/check_workspace_fragment_idempotency.py
```

Wave 3 emitters:

| Fragment | Emitter | Source | What gets emitted |
|---|---|---|---|
| `modules.dsl` | `ModulesFragmentEmitter` | `*/module-metadata.yaml` | One `container "module" ... "SAA Module"` per Maven module; `depends_on` relationships from `allowed_dependencies`. |
| `spi-catalog.dsl` | `SpiCatalogFragmentEmitter` | `module-metadata.yaml#spi_packages` + Java scan | One `element ... "SAA SPI"` per SPI package + per public interface; `declares_spi` relationships. |
| `enforcers.dsl` | `EnforcersFragmentEmitter` | `docs/governance/enforcers.yaml` | One `element ... "SAA Enforcer"` per row; `verifies` and `enforced_by` relationships. |
| `principles.dsl` | `PrinciplesFragmentEmitter` | `docs/governance/principle-coverage.yaml` | One `element ... "SAA Principle"` per principle; `operationalised_by` relationships to rules. |
| `rules.dsl` | `RulesFragmentEmitter` | `CLAUDE.md` headers + `docs/governance/rules/rule-*.md` | One `element ... "SAA Rule"` per rule. |
| `adr-graph.dsl` | `AdrGraphFragmentEmitter` | `docs/adr/*.yaml` | One `element ... "SAA ADR"` per ADR; `supersedes` / `extends` / `relates_to` relationships. |
| `surface-classification.dsl` | `SurfaceClassificationFragmentEmitter` | `docs/governance/templates/surface-classification.yaml` | One `element ... "SAA GeneratedProjection"` per templated/hybrid surface; `projects_to` relationships. |

W1 ships this README only. The files arrive in Wave 3.
