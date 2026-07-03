package com.huawei.ascend.bus.registry.runtime.api;

import com.huawei.ascend.bus.registry.runtime.RegistryObservabilityConfig;
import com.huawei.ascend.bus.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.huawei.ascend.bus.spi.registry.AgentCard;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP entry point for the agent registry MVP (ADR-0160 decisions 4 / 6 / 7).
 *
 * <p>Exposes {@code POST /api/registry/register} (upsert an {@link AgentCard})
 * and {@code DELETE /api/registry/deregister?tenantId=...&agentId=...}. Both
 * endpoints read {@code tenantId} from the request body / request param and
 * pass it down explicitly — no {@code TenantFilter} populates a
 * {@code TenantContext} at Servlet entry (ESC-2 design pivot, ADR-0160
 * decision 6: three-layer tenant isolation — explicit parameter +
 * application-layer WHERE + RLS).
 *
 * <p>The controller is a thin adapter: it validates input, delegates to
 * {@link AgentRegistryRepository} (port in {@code runtime.persistence.jdbc}),
 * and emits audit + metrics via {@link RegistryObservabilityConfig}. No JDBC
 * imports — the {@code req-2026-003-jdbc-confined-to-persistence} gate
 * enforces that.
 *
 * <p>Spring Web annotations ({@code @RestController} / {@code @RequestMapping})
 * are visible at compile time via {@code spring-boot-starter-web} at
 * {@code provided} scope (ADR-0160 decision 7, ESC-2(b) option B); the
 * runtime consumer (agent-runtime) ships starter-web.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + HD3-001 / 002 / 003.
 */
@RestController
@RequestMapping("/api/registry")
public class MvpRegistryController {

    private final AgentRegistryRepository repository;
    private final RegistryObservabilityConfig observability;

    public MvpRegistryController(AgentRegistryRepository repository,
                                 RegistryObservabilityConfig observability) {
        this.repository = repository;
        this.observability = observability;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody AgentCard card) {
        if (card == null || !card.hasRegistryKey()) {
            throw new IllegalArgumentException(
                    "AgentCard must carry tenantId + agentId + capability (registry key)");
        }
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            repository.upsert(card);
            return ResponseEntity.ok().build();
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeRegister(traceId, card.getTenantId(), card.getAgentId(),
                    card.getServiceId(), card.getCapability(), card.getContractVersion(),
                    card.getCapabilityVersion(), "ONLINE", null, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    @DeleteMapping("/deregister")
    public ResponseEntity<Void> deregister(@RequestParam String tenantId,
                                           @RequestParam String agentId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentId are required query params");
        }
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            boolean deleted = repository.delete(tenantId, agentId);
            outcome = deleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(traceId, tenantId, agentId, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }
}
