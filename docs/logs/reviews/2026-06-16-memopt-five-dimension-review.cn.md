# A2A 共享记忆 五维复检(鲁棒 / 弹性 / 韧性 / Ops·性能 / 经济)

> **已重构(2026-06-16)**:范围更新为独立中间件模块 **`a2a-shared-memory/`**(A2A 共享记忆 kit)。per-user 记忆 与 MemOpt 闭源引擎/gRPC 退到**后端阶段**,不在本期。下文按此口径,旧的 "memopt/" 路径以 `a2a-shared-memory/` 为准。

日期:2026-06-16 · 范围:`a2a-shared-memory/` 模块(A2A 共享记忆:run 内黑板 + 跨 run 经验)· 关联 [ADR-0162](../../adr/0162-a2a-shared-memory.yaml)

诚实前提:本模块是 **in-process 后端的 kit + SPI**(可离线评测);企业级持久/语义后端(MemOpt 闭源引擎,form C + gRPC)在**后端阶段**、本期未做。下面把**已在 kit 落地并测过**的 与 **属后端侧、本期未做** 的分开标。**A2A 共享记忆模块 27/27 通过。**

## 记分卡

| 维度 | 状态 | 证据 / 缺口 |
|---|---|---|
| 鲁棒性 | ✅ kit 侧 | **所有权违例 surface 不吞**(权限错≠基础设施错,`OwnershipViolationException` + 观测 degraded);后端错也 surface 交协作引擎 reclaim;append-log 不静默覆盖、并发原子。测试:`SharedMemoryKitTest` / `SharedMemoryConcurrencyTest` / `A2aSharedMemoryTest`。 |
| 弹性(上千 A2A) | ✅ 已验证 | 单一**按协作分区**的共享存储(非每协作一结构),`ConcurrentHashMap` 水平可扩。测试:`ScaleTest` — **2000 个并发协作**,零跨协作泄漏、零竞争错误。后端侧:真正水平扩容由后端(redis / MemOpt 容器)承担。 |
| 韧性(反压) | ✅ 客户端负反馈 / ⚠️ 引擎侧 | 客户端**熔断 = 负反馈**:引擎过载→调用失败→开路→自动甩载、不再打后端(`Circuit`)。重负载下的**服务端限流/有界队列属引擎侧**(形态 C 容器),本期未做,已在 ADR/设计稿标注。 |
| Ops 可观测 + 性能 | ✅ 双模可观测 / ✅ 瘦 kit 高性能 | `obs/`:`MemoryObserver` + `Slf4jMemoryObserver`(双模:routine→DEBUG,verbose→INFO,问题→WARN,`isEnabled` 守卫、MDC finally 清理)+ `MicrometerMemoryObserver`(`a2amem.ops`/`a2amem.op.latency`/`a2amem.degraded`,低基数)+ 组合(故障隔离)。已接入 `SharedMemoryKit`。测试:`MemoryObserverTest`(级别路由/扇出/隔离/MDC/kit 接入)。性能:kit 是瘦客户端,黑板操作 O(1);热路径无昂贵构造(守卫)。真正高性能持久/召回在后端。 |
| 经济性(token 节省) | ✅ 架构杠杆 | ① A2A 共享黑板让 agent **不重复发现**结论(交接带知识,不只带 payload);② 跨 run **经验召回**避免重推有效模式(省 LLM 轮次);③ 经验蒸馏 + PII 脱敏只留要点、不堆原文;④ 进一步语义压缩由后端做。这些都减少冗余 LLM 调用 = 省 token。 |

## 本期明确未做(引擎侧 / 后续,非缺陷而是范围)
- 闭源 Java 引擎本体(向量索引、语义召回、存储分层)+ 容器交付 + mTLS + gRPC `memopt.v1` wire(本期是 in-memory 后端 + 同一门面)。
- 服务端**限流/有界队列**(重负载反压的服务端半边)。
- `memopt-runtime-adapter`(接平台 `MemoryProvider` SPI;doushuai 示例已示范桥接模式)。
- 经验"任务签名"调优、并发写的更强一致策略(当前 append-log + 所有权已够)。

## 结论
A2A 共享记忆中间件 kit 在五个维度上都有**已测**的落地(鲁棒/弹性/韧性-kit 侧/可观测/经济),规模到**2000 并发协作**已验证,agent 间按 contextId 共享 + 所有权用真实 agent-runtime context 验证。剩余强项(高性能持久/召回、服务端反压)按设计**属后端**(redis / MemOpt 闭源引擎),边界清晰、已在 ADR-0162 标注。全套 **27/27** 测试通过。
