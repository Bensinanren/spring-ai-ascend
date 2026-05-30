---
level: L1
view: scenarios
status: template
authority: "ADR-0151 (L1 Feature Registry canonical schema), ADR-0152 (Uniform L1 mechanism)"
---

# `<module>` — Features and Function Points

<!-- W3-rendered: this file is RENDERED from architecture/features/features.dsl filtered by saa.owner==<module> via l1-features-catalog.md.j2. Do not hand-edit. -->

The feature inventory for `<module>` is mounted in the workspace at
`architecture/features/*.dsl`. Render this catalog by:

```bash
python3 gate/lib/render_template.py docs/governance/templates/l1-features-catalog.md.j2     --module <module>     --output architecture/docs/L1/<module>/features/README.md
```

For the schema, see [`architecture/features/README.md`](../../../../features/README.md).

## Feature inventory

(Rendered at W3.)
