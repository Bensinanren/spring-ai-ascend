---
level: L2
view: scenarios
status: scaffold
authority: "ADR-0068 (Layered 4+1 + Architecture Graph)"
---

# L2 — 兼容入口

L2 专题设计不再默认放在 `docs/L2/` 平铺目录。新的归属规则是：

```text
docs/architecture/l0/l1/<service>/l2/<topic>/    # L2 专题挂在父级 L1 服务下
```

本目录只作为旧路径兼容入口和迁移索引保留。

## Scope

L2 文档是专题级技术设计，用来把具体实现类、包、时序和部署细节绑定到上层 L0 / L1 约束。每个 L2 文档：

- 必须声明它细化的父级 L1 服务。
- 必须声明 `level: L2` 和 `view: {logical|development|process|physical|scenarios}` frontmatter。
- 必须链接到被细化的 L1 视图、边界合同或 ADR。
- 不得引入违反 L0 / L1 的服务依赖、状态 owner 或跨服务调用路径。

## Naming conventions

```text
docs/architecture/l0/l1/agent-service/l2/run-http-contract/logical.md
docs/architecture/l0/l1/agent-service/l2/idempotency-body-lifetime/process.md
```

单文件形式仍然允许，但必须放在对应父级目录下。

## Gate behaviour

- Gate 仍应检查 L2 文件 frontmatter。
- L2 文件必须可追溯到父级 L1 或 L0 节点。
- 触碰 L2 的评审记录必须说明影响层级。

## Current contents

待迁入的新位置：

| Slug | Trigger |
|---|---|
| `docs/architecture/l0/l1/agent-service/l2/run-http-contract/` | authenticated `POST /v1/runs` matrix |
| `docs/architecture/l0/l1/agent-service/l2/idempotency-body-lifetime/` | `IdempotencyHeaderFilter` body-wrapper fix |

## Authority

- Rule 33 — Layered 4+1 Discipline (`CLAUDE.md`)
- Rule 34 — Architecture-Graph Truth (`CLAUDE.md`)
- ADR-0068 — Layered 4+1 + Architecture Graph as Twin Sources of Truth (`docs/adr/0068-layered-4plus1-and-architecture-graph.yaml`)
