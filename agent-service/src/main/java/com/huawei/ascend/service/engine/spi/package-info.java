/**
 * Engine outbound and extension SPI surface.
 *
 * <p>SPI (outbound/extension) interfaces are <em>defined</em> by the engine and
 * <em>implemented</em> by external services or plugins — the inverse direction of
 * the inbound API in {@link com.huawei.ascend.service.engine.api}.
 *
 * <p>Per the engine model design (§2.1 directional definition, §3 layout), this
 * package holds:
 * <ul>
 *   <li>{@code AgentHandler} — the agent-framework extension point, implemented by
 *       the openJiuwen adapter and other plugins.</li>
 *   <li>{@code EngineQueueGateway} and {@code EngineCommandConsumer} — the
 *       internal-event-queue infrastructure port, implemented by the queue binding.</li>
 *   <li>{@code TaskControlClient} — the outbound port the engine calls to write
 *       task status back, implemented by the task-centric-control adapter.</li>
 *   <li>{@code AccessLayerClient} — the outbound port the engine calls to send
 *       user-visible output, implemented by the access-layer adapter.</li>
 * </ul>
 * These types are introduced in later build phases; their concrete implementations
 * land in {@code com.huawei.ascend.service.engine.adapter}.
 *
 * <p>The inbound dispatch entry point ({@code EngineDispatchApi}) is an API, not an
 * SPI, and lives in {@link com.huawei.ascend.service.engine.api}.
 *
 * <p>Design authority:
 * {@code 2026-05-30-l1--agent-service-engine-model-design.md}.
 *
 * <p>SPI purity (Rule R-D): interfaces only; imports limited to {@code java.*},
 * own siblings, and {@code com.huawei.ascend.service.engine.model} /
 * {@code com.huawei.ascend.service.engine.event} carriers.
 */
package com.huawei.ascend.service.engine.spi;
