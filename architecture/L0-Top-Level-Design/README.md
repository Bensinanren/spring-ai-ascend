---
level: L0-TLD
TAG:
  - entry
  - governance
  - reading-path
  - architecture-fact
status: active
dependency:
  - overview.md
  - views.md
  - boundaries.md
  - constraints.md
  - governance.md
  - glossary.md
---

# L0 架构顶层设计

## 目的

本目录是生效中的 L0 顶层架构事实，说明系统全景、顶层 4+1 视图、模块与状态边界、切面约束、治理规则以及全局术语。下层设计、代码实现需遵循此设计，严格保持一致。

## 文档地图

| 文件 | 作用 |
|---|---|
| `README.md` | 入口、文档地图、阅读路径和文档规范。 |
| `overview.md` | 系统目标、受众、问题领域、核心架构原则、逻辑模块边界形态和质量属性。 |
| `views.md` | L0 4+1 架构视图：逻辑视图、开发视图、运行视图、物理视图和场景视图。 |
| `boundaries.md` | 逻辑模块准入、模块职责、下游制品处理方式和状态所有权。 |
| `constraints.md` | 切面纵向能力、不变量和架构约束。 |
| `governance.md` | 事实治理、文档规范、分层更新协议、可追溯性和待决事项。 |
| `glossary.md` | 全局术语和禁止混淆的概念。 |

## 阅读路径

1. 阅读 `overview.md`，了解系统形态。
2. 阅读 `views.md`，了解 L0 4+1 视图模型。
3. 在变更模块、状态所有权或运行时职责之前，阅读 `boundaries.md`。
4. 在变更切面行为之前，阅读 `constraints.md`。
5. 在提升草稿材料或变更多个层级之前，阅读 `governance.md`。
6. 当涉及 Task、Session、Platform Gateway、Service Task API、Context Engine、Tool Gateway、C-Side 或 S-Side 等术语时，阅读 `glossary.md`。

## 文档规范

`docs/architecture/` 下存放架构设计草稿与提案，`docs/archive/` 下存放归档后的架构设计，`docs/logs/reviews/` 下存放架构审视意见提案。

- 架构设计提案与架构审视意见，在与当前架构事实和代码事实进行冲突审查之后，分块吸收到 `architecture/` 目录下生效中的架构事实文档中。
- 架构设计提案与架构审视意见中与范围、场景、特性、测试框架或交付材料相关的部分，不在 `architecture/` 架构事实文档范围，应分块合入到 `version-scope`。
- 具有历史价值、但不再描述当前架构的材料归档到 `docs/archive/`。
