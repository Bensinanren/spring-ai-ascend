package com.huawei.ascend.examples.a2a.gateway;

import com.huawei.ascend.examples.a2a.gateway.config.GatewayTelemetryConfiguration;
import com.huawei.ascend.service.client.RuntimeRegistrationClient;
import com.huawei.ascend.service.client.RuntimeRegistrationOutcome;
import com.huawei.ascend.service.spi.discovery.RoutingContext;
import com.huawei.ascend.service.spi.registry.RuntimeAgentRegistration;
import com.huawei.ascend.service.spi.registry.RuntimeCapacitySnapshot;
import com.huawei.ascend.service.spi.registry.RuntimeInstanceId;
import com.huawei.ascend.service.spi.registry.RuntimeLeaseRenewal;
import com.huawei.ascend.service.spi.registry.RuntimeState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the service facade over real HTTP through the Spring-free
 * {@link RuntimeRegistrationClient}: a runtime self-registers, its heartbeat
 * renews the lease (moving the routable capacity snapshot), and closing the
 * client deregisters it — proving the client's hand-rolled wire mapping
 * matches what the service edge parses.
 */
@SpringBootTest(
        classes = RuntimeSelfRegistrationE2eTest.TestServiceFacade.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "agent-runtime.enabled=false",
                "spring.autoconfigure.exclude="
                + "com.huawei.ascend.runtime.session.SessionManageConfiguration,"
                + "com.huawei.ascend.runtime.queue.QueueAutoConfiguration,"
                + "com.huawei.ascend.runtime.control.TaskControlAutoConfiguration,"
                + "com.huawei.ascend.runtime.app.RuntimeWiringConfiguration,"
                + "com.huawei.ascend.runtime.access.AccessLayerConfiguration,"
                + "com.huawei.ascend.runtime.engine.EngineAutoConfiguration"})
class RuntimeSelfRegistrationE2eTest {

    private static final String TENANT = "tenant-selfreg";
    private static final String AGENT = "selfreg-agent";
    private static final String RUNTIME = "runtime-selfreg-1";

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void runtimeSelfRegistersHeartbeatsAndDeregistersThroughTheClient() throws InterruptedException {
        try (RuntimeRegistrationClient client = RuntimeRegistrationClient
                .builder(URI.create("http://localhost:" + port))
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {

            RuntimeRegistrationOutcome outcome = client.register(new RuntimeAgentRegistration(
                    RuntimeInstanceId.of(RUNTIME),
                    TENANT,
                    AGENT,
                    agentCard(),
                    URI.create("http://" + RUNTIME + ".local/a2a"),
                    URI.create("http://" + RUNTIME + ".local/v1/health"),
                    "1.0.0",
                    Duration.ofSeconds(30),
                    Map.of("zone", "az-1")));

            assertThat(outcome.registered()).isTrue();
            assertThat(outcome.result().runtimeInstanceId().value()).isEqualTo(RUNTIME);
            assertThat(outcome.result().expiresAt()).isAfter(Instant.now());

            HttpJsonResponse agents = get("/v1/agents?tenantId=" + TENANT);
            assertThat(agents.status()).isEqualTo(HttpStatus.OK.value());
            assertThat(agents.body()).hasSize(1);
            assertThat(agents.body().get(0).path("agentId").asText()).isEqualTo(AGENT);
            assertThat(agents.body().get(0).path("state").asText()).isEqualTo("READY");

            client.startHeartbeat(
                    RuntimeInstanceId.of(RUNTIME),
                    Duration.ofMillis(100),
                    () -> new RuntimeLeaseRenewal(
                            RuntimeInstanceId.of(RUNTIME),
                            RuntimeState.READY,
                            Duration.ofSeconds(30),
                            null,
                            new RuntimeCapacitySnapshot(
                                    1, 0, 8, 2, 0, 8, 40, 100, 250, 0, 0, 0.25, Instant.now()),
                            Map.of("reason", "heartbeat")));

            JsonNode route = awaitRouteWithRenewedCapacity();
            assertThat(route.path("runtimeInstanceId").path("value").asText()).isEqualTo(RUNTIME);
            assertThat(route.path("capacitySnapshot").path("llmInFlight").asInt()).isEqualTo(2);
            assertThat(route.path("capacitySnapshot").path("p95FirstTokenMs").asLong()).isEqualTo(100);
        }

        HttpJsonResponse afterClose = get("/v1/agents?tenantId=" + TENANT);
        assertThat(afterClose.status()).isEqualTo(HttpStatus.OK.value());
        assertThat(afterClose.body()).isEmpty();
    }

    /**
     * The heartbeat runs on the client's own scheduler, so the renewed
     * capacity becomes visible asynchronously: poll the route until the
     * snapshot sent by the renewal supplier shows up.
     */
    private JsonNode awaitRouteWithRenewedCapacity() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        HttpJsonResponse last = null;
        while (System.nanoTime() < deadline) {
            last = post(
                    "/v1/agents/" + AGENT + "/routes/resolve?tenantId=" + TENANT,
                    new RoutingContext("session-selfreg", "corr-selfreg", Map.of("message", "ping")));
            if (last.status() == HttpStatus.OK.value()
                    && last.body().path("capacitySnapshot").path("llmInFlight").asInt() == 2) {
                return last.body();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Lease renewal never reached the route; last response: "
                + (last == null ? "<none>" : last.status() + " " + last.body()));
    }

    private HttpJsonResponse get(String path) {
        return exchange(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpJsonResponse post(String path, Object body) {
        return exchange(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build());
    }

    private HttpJsonResponse exchange(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpJsonResponse(response.statusCode(), objectMapper.readTree(response.body()));
        } catch (java.io.IOException ex) {
            throw new AssertionError("HTTP request failed: " + request.uri(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("HTTP request interrupted: " + request.uri(), ex);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static AgentCard agentCard() {
        return AgentCard.builder()
                .name(AGENT)
                .description(AGENT + " runtime")
                .url("/a2a")
                .version("1.0.0")
                .provider(new AgentProvider("spring-ai-ascend", "http://localhost:8080"))
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .extendedAgentCard(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface(
                        TransportProtocol.JSONRPC.asString(),
                        "/a2a")))
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(GatewayTelemetryConfiguration.class)
    static class TestServiceFacade {
    }

    private record HttpJsonResponse(int status, JsonNode body) {
    }
}
