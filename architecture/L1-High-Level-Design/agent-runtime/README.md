---
level: L1-HLD
TAG:
  - entry
  - governance
  - reading-path
  - architecture-fact
status: active
dependency:
  - overview.md
  - scenarios.md
  - logical.md
  - development.md
  - process.md
  - physical.md
  - spi-appendix.md
  - ../../../version-scope/agent-runtime-release-features.cn.md
---

# agent-runtime L1 高阶设计

## 目的

本目录是 `agent-runtime` 模块生效中的 L1 高阶设计入口，用于说明该模块在 L0 架构边界下的模块定位、职责范围、4+1 视图、主要能力、公共 SPI、依赖方向和阅读路径。

L1 文档回答“`agent-runtime` 是什么、为什么这样分层、对外承诺什么、与哪些模块协作”。它不展开单个特性的实现细节、配置清单、类级流程和错误处理矩阵；这些内容放在 `architecture/L2-Low-Level-Design/agent-runtime/` 下的详细设计文档中。

## 文档地图

| 文件 | 作用 |
|---|---|
| `README.md` | 入口、目的范围、文档地图和阅读路径。 |
| `overview.md` | 架构概览：模块目标、受众边界、问题领域和模块边界形态。 |
| `scenarios.md` | 场景视图：关键用户/系统场景，用于连接架构视图和功能验证。 |
| `logical.md` | 逻辑视图：领域模型、核心抽象、关键类型关系、状态机、层间交互和依赖方向。 |
| `development.md` | 开发视图：命名空间、包结构、模块依赖、禁止依赖、SPI 原则、自动装配和编码约束。 |
| `process.md` | 进程视图：线程模型、同步/异步边界、主执行流程、中断/恢复、取消和错误处理流程。 |
| `physical.md` | 物理视图：部署模式、进程边界、单实例/未来扩展拓扑、线程、内存和存储资源模型。 |
| `spi-appendix.md` | SPI 附录：公共 SPI、辅助类型、非 SPI 公共 API 和纯度约束。 |
| `../../../version-scope/agent-runtime-release-features.cn.md` | v0.1.0 版本范围和发布能力清单。 |

## 阅读路径

1. 阅读 `overview.md`，先建立模块定位、当前能力、公共契约和 L1 范围边界。
2. 阅读 4+1 视图文档：`scenarios.md` → `logical.md` → `development.md` → `process.md` → `physical.md`。
3. 阅读 `spi-appendix.md`，确认对外或跨特性的 SPI 语义、公共类型和纯度约束。
4. 阅读 `version-scope/agent-runtime-release-features.cn.md`，了解按版本实现的发布能力范围。
5. 下一版本候选特性不放在本 active 架构目录中；提案文档位于 `docs/architecture/l1/agent-runtime/proposals/`。
6. 当需要实现、测试或排查某个具体特性时，进入 `architecture/L2-Low-Level-Design/agent-runtime/` 阅读对应详设。

## 文档规范

`agent-runtime` 是可嵌入、可独立启动的 run-owning runtime SDK，负责通过框架无关的执行 SPI 承接 A2A 接入、Task 生命周期桥接、异构 Agent 框架适配、运行时输出、远程 Agent 调用协作和基础可观测性接缝。

本 L1 范围包括模块目标、成熟度、部署位面、当前发布能力、逻辑分层、核心抽象、状态与责任边界、并发模型、同步/异步边界、主要运行流程、部署拓扑、资源模型、包结构、模块依赖、SPI 设计原则、自动装配边界、关键场景、公共 SPI 清单和 L1 特性目录。

L1 与 L2 的边界按“模块地图”和“特性落地”划分：

- L1 保留模块级事实：职责、边界、视图、能力总览、稳定 SPI 和跨模块约束。
- L2 展开特性级事实：接口细节、类协作、配置模型、错误处理、E2E 流程和实现限制。

单个特性的类级协作、完整配置示例、错误处理表、A2A 方法分发、SSE 事件映射、远程 Agent 回灌、中断续接、OpenJiuwen / AgentScope / Versatile / Memory / Trajectory 等实现级设计，以及面向开发者的操作指南、curl 示例、E2E 验证脚本和运维手册，不放在本 L1 目录中，应进入 L2 详细设计或模块指南。

当前 `agent-runtime` 的 L2 详细设计位于 `architecture/L2-Low-Level-Design/agent-runtime/`，包括 A2A/S2C、异构框架兼容、Memory/State、远程 Agent 编排、Trajectory、接口设计和中间件解耦样例等文档。
