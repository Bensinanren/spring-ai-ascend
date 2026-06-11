package com.huawei.ascend.service.client;

import com.huawei.ascend.service.spi.registry.RuntimeAgentRegistration;
import com.huawei.ascend.service.spi.registry.RuntimeCapacitySnapshot;
import com.huawei.ascend.service.spi.registry.RuntimeInstanceId;
import com.huawei.ascend.service.spi.registry.RuntimeLeaseRenewal;
import com.huawei.ascend.service.spi.registry.RuntimeState;
import com.huawei.ascend.service.testsupport.AgentCards;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-contract tests against a {@code com.sun.net.httpserver} stub: the JSON
 * field names and header propagation asserted here are exactly what the
 * service edge's registration routes parse, so a drift in the client's
 * internal mapping fails here before any cross-module test runs.
 */
class RuntimeRegistrationClientTest {

    private static final String REGISTRATION_PATH = "/v1/runtime-registrations";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<CapturedRequest> captured = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private RuntimeRegistrationClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void registerSendsWireContractJsonWithAuthHeadersAndParsesLease() {
        startServer(exchange -> respond(exchange, 200, registrationResultJson("runtime-1")));
        client = RuntimeRegistrationClient.builder(baseUri())
                .bearerTokenSupplier(() -> "token-123")
                .tenantId("tenant-a")
                .build();

        RuntimeRegistrationOutcome outcome = client.register(registration("runtime-1"));

        assertThat(outcome.registered()).isTrue();
        assertThat(outcome.httpStatus()).isEqualTo(200);
        assertThat(outcome.result().runtimeInstanceId().value()).isEqualTo("runtime-1");
        assertThat(outcome.result().tenantId()).isEqualTo("tenant-a");
        assertThat(outcome.result().agentId()).isEqualTo("weather-agent");
        assertThat(outcome.result().state()).isEqualTo(RuntimeState.READY);
        assertThat(outcome.result().expiresAt()).isEqualTo(Instant.parse("2026-06-11T00:00:30Z"));

        assertThat(captured).hasSize(1);
        CapturedRequest request = captured.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo(REGISTRATION_PATH);
        assertThat(request.authorization()).isEqualTo("Bearer token-123");
        assertThat(request.tenantHeader()).isEqualTo("tenant-a");
        assertThat(request.contentType()).isEqualTo("application/json");

        JsonNode body = json.readTree(request.body());
        assertThat(body.path("runtimeInstanceId").asText()).isEqualTo("runtime-1");
        assertThat(body.path("tenantId").asText()).isEqualTo("tenant-a");
        assertThat(body.path("agentId").asText()).isEqualTo("weather-agent");
        assertThat(body.path("agentCard").path("name").asText()).isEqualTo("weather-agent");
        assertThat(body.path("a2aEndpoint").asText()).isEqualTo("http://runtime-1.local/a2a");
        assertThat(body.path("healthEndpoint").asText()).isEqualTo("http://runtime-1.local/v1/health");
        assertThat(body.path("version").asText()).isEqualTo("1.2.3");
        assertThat(body.path("ttlSeconds").asLong()).isEqualTo(30);
        assertThat(body.path("capacitySnapshot").path("llmInFlight").asInt()).isEqualTo(3);
        assertThat(body.path("capacitySnapshot").path("p95FirstTokenMs").asLong()).isEqualTo(100);
        assertThat(body.path("metadata").path("zone").asText()).isEqualTo("az-1");
    }

    @Test
    void agentCardTravelsAsTheA2aSdkSerializersJsonIncludingSecuritySchemes() throws Exception {
        startServer(exchange -> respond(exchange, 200, registrationResultJson("runtime-1")));
        client = RuntimeRegistrationClient.builder(baseUri()).build();
        AgentCard card = AgentCard.builder(AgentCards.agentCard("weather-agent"))
                .securitySchemes(Map.of("api-key", APIKeySecurityScheme.builder()
                        .location(APIKeySecurityScheme.Location.HEADER)
                        .name("X-Api-Key")
                        .description("tenant API key")
                        .build()))
                .build();

        RuntimeRegistrationOutcome outcome = client.register(new RuntimeAgentRegistration(
                RuntimeInstanceId.of("runtime-1"),
                "tenant-a",
                "weather-agent",
                card,
                URI.create("http://runtime-1.local/a2a"),
                URI.create("http://runtime-1.local/v1/health"),
                "1.2.3",
                Duration.ofSeconds(30),
                Map.of()));

        assertThat(outcome.registered()).isTrue();
        // The card's wire shape is the SDK serializer's output, byte-for-tree:
        // a Jackson view of the third-party record graph would both drift on
        // SDK layout changes and be unable to carry the polymorphic
        // securitySchemes members at all.
        JsonNode sentCard = json.readTree(captured.get(0).body()).path("agentCard");
        assertThat(sentCard).isEqualTo(json.readTree(JsonUtil.toJson(card)));
        assertThat(sentCard.path("securitySchemes").path("api-key").isObject()).isTrue();
    }

    @Test
    void registerSurfacesServiceRejectionAsErrorOutcomeAndDoesNotTrackTheInstance() {
        startServer(exchange -> respond(exchange, 400,
                "{\"code\":\"BAD_REQUEST\",\"message\":\"agentId is required\"}"));
        client = RuntimeRegistrationClient.builder(baseUri()).build();

        RuntimeRegistrationOutcome outcome = client.register(registration("runtime-rejected"));
        client.close();

        assertThat(outcome.registered()).isFalse();
        assertThat(outcome.result()).isNull();
        assertThat(outcome.httpStatus()).isEqualTo(400);
        assertThat(outcome.errorCode()).isEqualTo("BAD_REQUEST");
        assertThat(outcome.errorMessage()).isEqualTo("agentId is required");
        // A rejected instance was never registered, so close() must not try to
        // deregister it.
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).method()).isEqualTo("POST");
    }

    @Test
    void heartbeatSendsWireContractRenewalsAndKeepsFiringAfterServerError() throws InterruptedException {
        AtomicInteger leaseCalls = new AtomicInteger();
        startServer(exchange -> {
            if (leaseCalls.incrementAndGet() == 1) {
                respond(exchange, 500, "{\"code\":\"INTERNAL\",\"message\":\"boom\"}");
            } else {
                respond(exchange, 200, leaseResultJson("runtime-1"));
            }
        });
        client = RuntimeRegistrationClient.builder(baseUri()).build();

        client.startHeartbeat(
                RuntimeInstanceId.of("runtime-1"),
                Duration.ofMillis(25),
                () -> new RuntimeLeaseRenewal(
                        RuntimeInstanceId.of("runtime-1"),
                        RuntimeState.READY,
                        Duration.ofSeconds(30),
                        null,
                        new RuntimeCapacitySnapshot(
                                1, 0, 8, 2, 0, 8, 40, 100, 250, 0, 0, 0.25,
                                Instant.parse("2026-06-11T00:00:00Z")),
                        Map.of("reason", "tick")));

        awaitAtLeastCaptured(2);

        // The first renewal got a 500; a second one proves the scheduler survived
        // it. Snapshot the live list: the heartbeat keeps appending behind us.
        List<CapturedRequest> firstTwo = List.of(captured.get(0), captured.get(1));
        for (CapturedRequest request : firstTwo) {
            assertThat(request.method()).isEqualTo("PUT");
            assertThat(request.path()).isEqualTo(REGISTRATION_PATH + "/runtime-1/lease");
            assertThat(request.authorization()).isNull();
            assertThat(request.tenantHeader()).isNull();
            JsonNode body = json.readTree(request.body());
            assertThat(body.path("state").asText()).isEqualTo("READY");
            assertThat(body.path("ttlSeconds").asLong()).isEqualTo(30);
            assertThat(body.path("slaSnapshot").path("firstTokenSlaBreached").asBoolean()).isFalse();
            assertThat(body.path("capacitySnapshot").path("llmInFlight").asInt()).isEqualTo(2);
            assertThat(body.path("metadata").path("reason").asText()).isEqualTo("tick");
        }
    }

    @Test
    void closeDeregistersEveryRegisteredInstanceExactlyOnce() {
        startServer(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String instanceId = json.readTree(captured.get(captured.size() - 1).body())
                        .path("runtimeInstanceId").asText();
                respond(exchange, 200, registrationResultJson(instanceId));
            } else {
                respond(exchange, 200, "{\"runtimeInstanceId\":{\"value\":\"x\"},"
                        + "\"state\":\"DEREGISTERED\",\"removed\":true}");
            }
        });
        client = RuntimeRegistrationClient.builder(baseUri()).build();
        client.register(registration("runtime-a"));
        client.register(registration("runtime-b"));

        client.close();
        client.close();

        List<CapturedRequest> deletes = captured.stream()
                .filter(request -> "DELETE".equals(request.method()))
                .toList();
        assertThat(deletes).extracting(CapturedRequest::path).containsExactlyInAnyOrder(
                REGISTRATION_PATH + "/runtime-a",
                REGISTRATION_PATH + "/runtime-b");
        assertThatThrownBy(() -> client.register(registration("runtime-c")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startingASecondHeartbeatForTheSameInstanceIsRejected() {
        startServer(exchange -> respond(exchange, 200, leaseResultJson("runtime-1")));
        client = RuntimeRegistrationClient.builder(baseUri()).build();
        RuntimeInstanceId instanceId = RuntimeInstanceId.of("runtime-1");
        client.startHeartbeat(instanceId, Duration.ofSeconds(30), () -> renewal(instanceId));

        assertThatThrownBy(() -> client.startHeartbeat(instanceId, Duration.ofSeconds(30), () -> renewal(instanceId)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RuntimeLeaseRenewal renewal(RuntimeInstanceId instanceId) {
        return new RuntimeLeaseRenewal(
                instanceId, RuntimeState.READY, Duration.ofSeconds(30), null, Map.of());
    }

    private RuntimeAgentRegistration registration(String instanceId) {
        return new RuntimeAgentRegistration(
                RuntimeInstanceId.of(instanceId),
                "tenant-a",
                "weather-agent",
                AgentCards.agentCard("weather-agent"),
                URI.create("http://runtime-1.local/a2a"),
                URI.create("http://runtime-1.local/v1/health"),
                "1.2.3",
                Duration.ofSeconds(30),
                new RuntimeCapacitySnapshot(
                        1, 2, 8, 3, 0, 8, 40, 100, 250, 0, 0, 0.25,
                        Instant.parse("2026-06-11T00:00:00Z")),
                Map.of("zone", "az-1"));
    }

    private static String registrationResultJson(String instanceId) {
        return "{\"runtimeInstanceId\":{\"value\":\"" + instanceId + "\"},"
                + "\"tenantId\":\"tenant-a\",\"agentId\":\"weather-agent\","
                + "\"state\":\"READY\",\"expiresAt\":\"2026-06-11T00:00:30Z\"}";
    }

    private static String leaseResultJson(String instanceId) {
        return "{\"runtimeInstanceId\":{\"value\":\"" + instanceId + "\"},"
                + "\"state\":\"READY\",\"expiresAt\":\"2026-06-11T00:00:30Z\"}";
    }

    private void startServer(CapturingHandler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext(REGISTRATION_PATH, exchange -> {
                capture(exchange);
                handler.handle(exchange);
            });
            server.start();
        } catch (IOException ex) {
            throw new AssertionError("Failed to start stub registry", ex);
        }
    }

    private URI baseUri() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private void capture(HttpExchange exchange) throws IOException {
        captured.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Tenant-Id"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void awaitAtLeastCaptured(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (captured.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Expected at least " + count + " requests, saw " + captured.size());
            }
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface CapturingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record CapturedRequest(
            String method,
            String path,
            String authorization,
            String tenantHeader,
            String contentType,
            String body) {
    }
}
