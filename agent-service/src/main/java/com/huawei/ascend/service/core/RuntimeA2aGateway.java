package com.huawei.ascend.service.core;

import com.huawei.ascend.service.spi.discovery.AgentDirectory;
import com.huawei.ascend.service.spi.discovery.RoutingContext;
import com.huawei.ascend.service.spi.discovery.RuntimeRoute;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Byte-level A2A forwarder behind a resolved route. A2A-NO-REWRITE invariant:
 * the JSON-RPC payload is forwarded as opaque bytes — never parsed, validated,
 * or rewritten; routing keys come exclusively from URL, query, and headers.
 * Only hop-by-hop headers are stripped, so a client's {@code Authorization}
 * header reaches the runtime and the tenant cross-check can re-validate the
 * same credential end-to-end.
 */
public final class RuntimeA2aGateway {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final AgentDirectory directory;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public RuntimeA2aGateway(AgentDirectory directory) {
        this(directory, HttpClient.newHttpClient(), Duration.ofSeconds(30));
    }

    public RuntimeA2aGateway(AgentDirectory directory, HttpClient httpClient, Duration requestTimeout) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    /**
     * Buffered forward: the configured request timeout bounds the whole
     * exchange — headers and body. {@link HttpRequest#timeout} alone covers
     * only receipt of the response headers, so the body is accumulated on the
     * client's own machinery and the calling thread waits with a deadline; a
     * runtime that returns headers promptly and then stalls mid-body can never
     * hold the caller past the timeout.
     */
    public A2aGatewayResponse forward(
            String agentId,
            String tenantId,
            RoutingContext routingContext,
            byte[] body,
            Map<String, List<String>> requestHeaders) {
        Instant routeStart = Instant.now();
        RuntimeRoute route = directory.resolveRoute(
                agentId,
                tenantId,
                routingContext == null ? RoutingContext.empty() : routingContext);
        Duration routeResolveLatency = Duration.between(routeStart, Instant.now());
        HttpRequest request = buildForwardRequest(route, body, requestHeaders);
        Instant forwardStart = Instant.now();
        AtomicReference<Instant> headersReceivedAt = new AtomicReference<>();
        CompletableFuture<HttpResponse<byte[]>> exchange = httpClient.sendAsync(request, responseInfo -> {
            headersReceivedAt.set(Instant.now());
            return HttpResponse.BodySubscribers.ofByteArray();
        });
        try {
            HttpResponse<byte[]> response = exchange.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            Instant firstByteAt = headersReceivedAt.get();
            Duration firstByteLatency =
                    Duration.between(forwardStart, firstByteAt == null ? Instant.now() : firstByteAt);
            Duration forwardLatency = Duration.between(forwardStart, Instant.now());
            return new A2aGatewayResponse(
                    response.statusCode(),
                    response.headers().firstValue("content-type").orElse("application/json"),
                    routeResolveLatency,
                    firstByteLatency,
                    forwardLatency,
                    route.runtimeInstanceId().value(),
                    response.body());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new A2aGatewayForwardException("Failed to forward A2A request to " + route.a2aEndpoint(), cause);
        } catch (TimeoutException ex) {
            exchange.cancel(true);
            throw new A2aGatewayForwardException(
                    "A2A forward to " + route.a2aEndpoint() + " exceeded the request timeout of " + requestTimeout, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exchange.cancel(true);
            throw new A2aGatewayForwardException("Interrupted while forwarding A2A request to " + route.a2aEndpoint(), ex);
        }
    }

    public A2aGatewayStreamResponse forwardStreaming(
            String agentId,
            String tenantId,
            RoutingContext routingContext,
            byte[] body,
            Map<String, List<String>> requestHeaders) {
        Instant routeStart = Instant.now();
        RuntimeRoute route = directory.resolveRoute(
                agentId,
                tenantId,
                routingContext == null ? RoutingContext.empty() : routingContext);
        Duration routeResolveLatency = Duration.between(routeStart, Instant.now());
        HttpRequest request = buildForwardRequest(route, body, requestHeaders);
        try {
            Instant forwardStart = Instant.now();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            Duration firstByteLatency = Duration.between(forwardStart, Instant.now());
            return new A2aGatewayStreamResponse(
                    response.statusCode(),
                    response.headers().firstValue("content-type").orElse("application/json"),
                    routeResolveLatency,
                    firstByteLatency,
                    route.runtimeInstanceId().value(),
                    response.body());
        } catch (IOException ex) {
            throw new A2aGatewayForwardException("Failed to forward A2A request to " + route.a2aEndpoint(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new A2aGatewayForwardException("Interrupted while forwarding A2A request to " + route.a2aEndpoint(), ex);
        }
    }

    private HttpRequest buildForwardRequest(
            RuntimeRoute route,
            byte[] body,
            Map<String, List<String>> requestHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(route.a2aEndpoint())
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body.clone()));
        copyHeaders(builder, requestHeaders);
        builder.header("X-Ascend-Runtime-Instance", route.runtimeInstanceId().value());
        if (!containsHeader(requestHeaders, "content-type")) {
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    private void copyHeaders(HttpRequest.Builder builder, Map<String, List<String>> requestHeaders) {
        if (requestHeaders == null) {
            return;
        }
        requestHeaders.forEach((name, values) -> {
            if (isForwardable(name) && values != null) {
                values.stream()
                        .filter(Objects::nonNull)
                        .forEach(value -> builder.header(name, value));
            }
        });
    }

    private boolean isForwardable(String name) {
        return name != null && !HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private boolean containsHeader(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return false;
        }
        return headers.keySet().stream()
                .filter(Objects::nonNull)
                .anyMatch(candidate -> candidate.equalsIgnoreCase(name));
    }
}
