package com.huawei.ascend.bus.spi.registry;

/**
 * Read-only access to the tenant identifier of the current call scope.
 *
 * <p>Authority: ADR-0160 (Stage 4 Registry SPI Runtime Promotion) + HD3-003
 * (tenant isolation). The MVP implementation
 * {@code ThreadLocalTenantContext} (in {@code registry.runtime.tenant}) is
 * populated by {@code TenantFilter} from the {@code X-Tenant-Id} request
 * header; phase 2 may swap in a reactor-context-backed implementation
 * without touching this port.
 *
 * <p>Pure Java — no Spring / JDBC / Jackson / Consul imports (ADR-0160
 * decision 1).
 */
public interface TenantContext {

    /**
     * @return the tenant id bound to the current call scope; never {@code null}
     *         once {@code TenantFilter} has populated the context. Unbound
     *         scopes (background scheduling without a request) return
     *         {@code null} and discovery callers must reject such calls
     *         rather than fall back to a default tenant (HD3-003).
     */
    String current();
}
