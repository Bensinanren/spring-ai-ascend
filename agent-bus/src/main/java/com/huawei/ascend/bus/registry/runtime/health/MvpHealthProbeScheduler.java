package com.huawei.ascend.bus.registry.runtime.health;

import com.huawei.ascend.bus.registry.runtime.RegistryObservabilityConfig;
import com.huawei.ascend.bus.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.huawei.ascend.bus.registry.runtime.persistence.jdbc.AgentRegistryRepository.ProbeTarget;
import com.huawei.ascend.bus.registry.runtime.tenant.ThreadLocalTenantContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Background health-probe scheduler for the agent registry MVP (ADR-0160 +
 * HD3-004 lease/TTL).
 *
 * <p>Runs a {@code @Scheduled} sweep every 5 seconds (configurable via
 * {@code agent-bus.registry.mvp.probe-interval-ms}), calling
 * {@link AgentRegistryRepository#scanDueForProbe} to find {@code ONLINE}
 * entries whose {@code last_heartbeat} is older than the stale threshold
 * (default 5 s). For each target, HTTP {@code GET {endpoint_url}/health/agent-status}
 * via {@link RestClient}:
 * <ul>
 *   <li>2xx → {@code updateStatus(..., "ONLINE", refreshHeartbeat=true)} —
 *       heartbeat refreshed, status reaffirmed.</li>
 *   <li>5xx / connect exception → {@code updateStatus(..., "DEGRADED", false)}
 *       — status downgraded, heartbeat untouched. The 15-second visibility
 *       window in the discovery SQL eventually filters the entry out of
 *       results if the heartbeat stays stale (HD3-004).</li>
 * </ul>
 *
 * <p>Each probe is wrapped in {@link ThreadLocalTenantContext#bindForScope}
 * so the Stage 24 RLS wiring in {@code JdbcAgentRegistryRepository} sees the
 * correct tenant for the {@code updateStatus} call (ESC-2 design pivot,
 * ADR-0160 decision 6 — background scheduling paths bind tenant scope
 * explicitly, since no {@code TenantFilter} populates it at request entry).
 *
 * <p>Spring Web ({@code RestClient}) + Spring Context ({@code @Component} /
 * {@code @Scheduled}) are visible via {@code spring-boot-starter-web} at
 * {@code provided} scope (ADR-0160 decision 7). JDBC is forbidden in this
 * subpackage — the scheduler calls {@link AgentRegistryRepository} only.
 *
 * <p>{@code @EnableScheduling} lives in {@link com.huawei.ascend.bus.registry.runtime.RegistrySchedulingConfig}
 * (KF-1: agent-bus has no {@code @SpringBootApplication}; the runtime
 * consumer's component scan picks this {@code @Component} up).
 *
 * <p>Authority: ADR-0160 + HD3-004 + Rule R-C.c (Stage 24 RLS wiring).
 */
@Component
public class MvpHealthProbeScheduler {

    private static final String HEALTH_PATH = "/health/agent-status";

    private final AgentRegistryRepository repository;
    private final RegistryObservabilityConfig observability;
    private final RestClient httpClient;
    private final long staleBeforeMs;
    private final int scanLimit;

    public MvpHealthProbeScheduler(AgentRegistryRepository repository,
                                   RegistryObservabilityConfig observability,
                                   @Value("${agent-bus.registry.mvp.probe-stale-before-ms:5000}") long staleBeforeMs,
                                   @Value("${agent-bus.registry.mvp.probe-scan-limit:200}") int scanLimit) {
        this.repository = repository;
        this.observability = observability;
        this.httpClient = RestClient.create();
        this.staleBeforeMs = staleBeforeMs;
        this.scanLimit = scanLimit;
    }

    @Scheduled(fixedDelayString = "${agent-bus.registry.mvp.probe-interval-ms:5000}")
    public void probeOnlineAgents() {
        long staleBefore = System.currentTimeMillis() - staleBeforeMs;
        List<ProbeTarget> targets = repository.scanDueForProbe(staleBefore, scanLimit);
        for (ProbeTarget target : targets) {
            probeOne(target);
        }
    }

    private void probeOne(ProbeTarget target) {
        ThreadLocalTenantContext.bindForScope(target.tenantId(), () -> {
            String traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
            long start = System.nanoTime();
            String outcome = "probe_failed";
            String health = "DEGRADED";
            try {
                httpClient.get()
                        .uri(target.endpointUrl() + HEALTH_PATH)
                        .retrieve()
                        .toBodilessEntity();
                repository.updateStatus(target.tenantId(), target.agentId(), "ONLINE", true);
                outcome = "success";
                health = "ONLINE";
            } catch (RuntimeException ex) {
                repository.updateStatus(target.tenantId(), target.agentId(), "DEGRADED", false);
                // outcome / health already defaulted to probe_failed / DEGRADED
            } finally {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                observability.observeProbe(traceId, target.tenantId(), target.agentId(),
                        health, outcome, latencyMs);
                MDC.remove("traceId");
            }
        });
    }
}
