---
level: L1
view: [scenarios, logical, process, development, physical]
module: agent-execution-engine
affects_level: L0, L1
affects_view: [scenarios, logical, process, development, physical]
status: proposed
---

# 架构评审提案：agent-execution-engine L1 高级设计提案 (Wave 1.2)

> **日期:** 2026-05-25
> **作者:** LucioIT (核心架构师) & 急急 (智能体)
> **目标 Wave:** W0/W1 (立即执行)
> **关联军规:** Rule G-1.c (L1 深度与落地)

## 1. 背景与原则 (Background & Principles)

### 1.1 顶层设计背景 (L0 架构)

#### 1.1.1 六大核心模块
1. **智能体客户端 (agent-client)**：在 SaaS 应用与桌面应用中被集成，负责感知业务知识与状态，操作业务环境与工具，下发管理智能体配置，调用执行智能体服务。
2. **智能体服务端 (agent-service)**：负责把图模式执行 of workflow 智能体与循环模式执行 of ReAct 智能体封装成微服务。
3. **智能体执行引擎 (agent-execution-engine)**：**（本模块核心定界）** 负责提供两大类智能体的执行器，提供可供开发者使用的各种组件，如 workflow 会用到的 node、ReAct 会用到的 tool 和 hook。
4. **智能体总线 (agent-bus)**：负责连接南北向 of C/S 通信流量，连接东西向 of A2A 通信流量。
5. **智能体中间件 (agent-middleware)**：负责提供智能体需要的基础服务，如记忆服务、技能服务、知识服务、沙箱服务等。
6. **智能体演进平台 (agent-evolve)**：负责在线与离线的智能体自主演进。

#### 1.1.2 两种核心部署/集成模式
- **平台中心模式 (Platform-Centric Mode)**：业务侧仅集成 `agent-client`，其他所有模块均部署在平台端（集中托管与运行，降低业务集成心智负担）。
- **业务中心模式 (Business-Centric Mode)**：业务侧不仅集成 `agent-client`，还会在本地化（业务物理边界内）部署 `agent-service` 和 `agent-execution-engine`，实现就近计算；平台侧仅提供统一治理、互联互通及基础公共服务。

### 1.2 项目阶段背景与演进规划

### 1.3 设计原则与核心形态

## 2. 场景视图 (Scenarios View)

### 2.1 高性能内聚运行场景 (共进程模式)

### 2.2 异构存量智能体兼容集成场景 (服务化模式)

### 2.3 跨节点多智能体 A2A 异步协同场景

### 2.4 高频轻量访问快路径场景 (Fast-Path Loop)

### 2.5 异构引擎影子工具挂起与异步执行场景 (Shadow-Plugin Intercept Loop)

## 3. 逻辑视图 (Logical View)

### 3.1 多态派发器 (Polymorphic Dispatcher)

### 3.2 引擎适配器 (Engine Adapter)

### 3.3 内部事件队列（Internal Event Queue）

### 3.4 A2A 协议收发引擎组件（A2A Connector）

### 3.5 Task-Centric 状态控制体系与信号派发组件

### 3.6 逻辑分工边界映射组件（Logical Boundary Mapping）

## 4. 进程视图 (Process View)

### 4.1 异步任务发布/消费环路 (Asynchronous Task Loop)

### 4.2 跨节点多智能体协作与中断唤醒链路 (A2A Collaboration Loop)

### 4.3 4级状态全生命周期流转环流（Four-Layer Life Cycle Flow）

### 4.4 双轨快慢路径调度时序流程 (Fast-Path & Slow-Path Dispatch Loop)

### 4.5 异构框架影子工具拦截与恢复流程 (Heterogeneous Framework Shadow Interceptor Flow)

## 5. 开发视图 (Development View)

### 5.1 依赖开源与自研边界定界

### 5.2 自研代码包目录映射与依赖集成

## 6. 物理视图 (Physical View)

### 6.1 共进程内聚部署拓扑 (Embedded Deployment)

### 6.2 存量解耦/异构微服务部署拓扑 (Decoupled Service Deployment)

### 6.3 双轨路径的物理存储与计算边界 (Dual-Track Physical & Compute Boundaries)

## 7. 附录：核心 SPI 接口 (Appendix: Core SPI Interfaces)

### 7.1 A2A 标准任务生命周期与中断类型定义

### 7.2 StatelessEngineExecutor 引擎核心契约接口定义

### 7.3 Dual-Track Router 与快慢路径处理器接口定义

### 7.4 ShadowToolInterceptor 与异构适配器接口定义
