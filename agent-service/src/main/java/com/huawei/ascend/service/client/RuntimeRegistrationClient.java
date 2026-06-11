package com.huawei.ascend.service.client;

import com.huawei.ascend.service.spi.registry.RuntimeAgentRegistration;
import com.huawei.ascend.service.spi.registry.RuntimeInstanceId;
import com.huawei.ascend.service.spi.registry.RuntimeLeaseRenewal;
import com.huawei.ascend.service.spi.registry.RuntimeRegistrationResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Self-registration client a runtime instance points at the service facade:
 * {@link #register} announces the instance, {@link #startHeartbeat} keeps its
 * lease alive on a daemon scheduler, and {@link #close} best-effort
 * deregisters everything this client registered. The API speaks the
 * {@code spi.registry} records; the HTTP/JSON wire shape of the
 * {@code /v1/runtime-registrations} routes is mapped internally so the
 * Spring-free runtime side never needs the service's Spring edge on its
 * classpath.
 *
 * <p>Heartbeat failures are logged and retried on the next tick — a transient
 * registry outage must never kill the renewal loop, because a stopped loop
 * silently expires the lease and takes the runtime out of routing.
 *
 * <p>When the service ingress enforces JWT (cross-check model, ADR-0040),
 * supply a bearer-token supplier; the token is re-read per request so rotated
 * credentials propagate without rebuilding the client.
 */
public final class RuntimeRegistrationClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuntimeRegistrationClient.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Supplier<String> bearerTokenSupplier;
    private final String tenantId;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ScheduledExecutorService heartbeatScheduler;
    private final Set<String> registeredInstanceIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private RuntimeRegistrationClient(Builder builder) {
        this.baseUrl = stripTrailingSlash(builder.serviceBaseUrl.toString());
        this.httpClient = builder.httpClient;
        this.requestTimeout = builder.requestTimeout;
        this.bearerTokenSupplier = builder.bearerTokenSupplier;
        this.tenantId = builder.tenantId;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "runtime-registration-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static Builder builder(URI serviceBaseUrl) {
        return new Builder(serviceBaseUrl);
    }

    /**
     * Registers the runtime instance and, on acceptance, remembers it for
     * best-effort deregistration at {@link #close()}. A service-side rejection
     * is returned as data; only transport failures throw.
     */
    public RuntimeRegistrationOutcome register(RuntimeAgentRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        ensureOpen();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runtimeInstanceId", registration.runtimeInstanceId().value());
        body.put("tenantId", registration.tenantId());
        body.put("agentId", registration.agentId());
        // The agent card is a third-party A2A spec type with polymorphic
        // members (securitySchemes); its wire shape belongs to the SDK's own
        // serializer, never to a default-Jackson view of the record graph.
        body.put("agentCard", agentCardWireJson(registration));
        body.put("a2aEndpoint", registration.a2aEndpoint().toString());
        body.put("healthEndpoint", registration.healthEndpoint().toString());
        body.put("version", registration.version());
        body.put("ttlSeconds", registration.ttl().toSeconds());
        body.put("capacitySnapshot", registration.capacitySnapshot());
        body.put("metadata", registration.metadata());
        HttpResponse<String> response = send(request("/v1/runtime-registrations")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build());
        if (!isSuccess(response.statusCode())) {
            return rejected(response);
        }
        RuntimeRegistrationResult result = json.readValue(response.body(), RuntimeRegistrationResult.class);
        registeredInstanceIds.add(result.runtimeInstanceId().value());
        return RuntimeRegistrationOutcome.accepted(response.statusCode(), result);
    }

    /**
     * Renews the instance's lease every {@code interval} on the daemon
     * scheduler. The supplier is invoked per tick so each renewal carries the
     * runtime's current state and capacity snapshot. Renewal failures are
     * logged and retried on the next tick, never thrown out of the scheduler.
     */
    public void startHeartbeat(
            RuntimeInstanceId runtimeInstanceId,
            Duration interval,
            Supplier<RuntimeLeaseRenewal> renewalSupplier) {
        Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        Objects.requireNonNull(renewalSupplier, "renewalSupplier");
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        ensureOpen();
        heartbeats.compute(runtimeInstanceId.value(), (instanceId, existing) -> {
            if (existing != null && !existing.isDone()) {
                throw new IllegalStateException("Heartbeat already started for " + instanceId);
            }
            return heartbeatScheduler.scheduleWithFixedDelay(
                    () -> renewQuietly(runtimeInstanceId, renewalSupplier),
                    interval.toNanos(),
                    interval.toNanos(),
                    TimeUnit.NANOSECONDS);
        });
    }

    /**
     * Stops the heartbeat scheduler, then best-effort deregisters every
     * instance this client registered — in that order, so a racing renewal can
     * never resurrect a lease the close already deleted.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeatScheduler.shutdownNow();
        heartbeats.clear();
        for (String instanceId : registeredInstanceIds) {
            deregisterQuietly(instanceId);
        }
        registeredInstanceIds.clear();
    }

    private void renewQuietly(RuntimeInstanceId runtimeInstanceId, Supplier<RuntimeLeaseRenewal> renewalSupplier) {
        try {
            RuntimeLeaseRenewal renewal = renewalSupplier.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("state", renewal.state());
            body.put("ttlSeconds", renewal.ttl().toSeconds());
            body.put("slaSnapshot", renewal.slaSnapshot());
            body.put("capacitySnapshot", renewal.capacitySnapshot());
            body.put("metadata", renewal.metadata());
            HttpResponse<String> response = send(
                    request("/v1/runtime-registrations/" + runtimeInstanceId.value() + "/lease")
                            .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build());
            if (!isSuccess(response.statusCode())) {
                log.warn("Lease renewal for {} rejected with status {}; retrying next tick: {}",
                        runtimeInstanceId.value(), response.statusCode(), response.body());
            }
        } catch (RuntimeException ex) {
            log.warn("Lease renewal for {} failed; retrying next tick", runtimeInstanceId.value(), ex);
        }
    }

    private void deregisterQuietly(String instanceId) {
        try {
            HttpResponse<String> response = send(request("/v1/runtime-registrations/" + instanceId)
                    .DELETE()
                    .build());
            if (!isSuccess(response.statusCode())) {
                log.warn("Best-effort deregistration of {} returned status {}", instanceId, response.statusCode());
            }
        } catch (RuntimeException ex) {
            log.warn("Best-effort deregistration of {} failed", instanceId, ex);
        }
    }

    private JsonNode agentCardWireJson(RuntimeAgentRegistration registration) {
        try {
            return json.readTree(JsonUtil.toJson(registration.agentCard()));
        } catch (Exception ex) {
            throw new RuntimeRegistrationClientException(
                    "Failed to serialize the agent card for " + registration.agentId(), ex);
        }
    }

    private RuntimeRegistrationOutcome rejected(HttpResponse<String> response) {
        String code = null;
        String message = response.body();
        try {
            JsonNode node = json.readTree(response.body());
            if (node.path("code").isString()) {
                code = node.path("code").asText();
            }
            if (node.path("message").isString()) {
                message = node.path("message").asText();
            } else if (node.path("error").path("message").isString()) {
                // The JWT service ingress rejects with {"error":{"status","message"}}.
                message = node.path("error").path("message").asText();
            }
        } catch (RuntimeException ignored) {
            // Non-JSON error body: surface the raw payload as the message.
        }
        return RuntimeRegistrationOutcome.rejected(response.statusCode(), code, message);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json");
        if (bearerTokenSupplier != null) {
            String token = bearerTokenSupplier.get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        if (tenantId != null) {
            builder.header("X-Tenant-Id", tenantId);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new RuntimeRegistrationClientException(
                    "Failed to reach the service registry at " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeRegistrationClientException(
                    "Interrupted while calling the service registry at " + request.uri(), ex);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("RuntimeRegistrationClient is closed");
        }
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode / 100 == 2;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static final class Builder {

        private final URI serviceBaseUrl;
        private HttpClient httpClient = HttpClient.newHttpClient();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Supplier<String> bearerTokenSupplier;
        private String tenantId;

        private Builder(URI serviceBaseUrl) {
            this.serviceBaseUrl = Objects.requireNonNull(serviceBaseUrl, "serviceBaseUrl");
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Token is re-read per request; send no header when it yields blank. */
        public Builder bearerTokenSupplier(Supplier<String> bearerTokenSupplier) {
            this.bearerTokenSupplier = Objects.requireNonNull(bearerTokenSupplier, "bearerTokenSupplier");
            return this;
        }

        /** Sent as {@code X-Tenant-Id} for the ingress tenant cross-check. */
        public Builder tenantId(String tenantId) {
            this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
            return this;
        }

        public RuntimeRegistrationClient build() {
            return new RuntimeRegistrationClient(this);
        }
    }
}
