package com.huawei.ascend.service.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.service.spi.discovery.AgentCardSummary;
import com.huawei.ascend.service.spi.discovery.AgentDirectory;
import com.huawei.ascend.service.spi.discovery.RoutingContext;
import com.huawei.ascend.service.spi.discovery.RuntimeRoute;
import com.huawei.ascend.service.spi.registry.RuntimeInstanceId;
import com.huawei.ascend.service.spi.registry.RuntimeState;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The buffered forward path materializes the whole runtime response, so the
 * configured request timeout must bound the entire exchange — a runtime that
 * returns headers promptly and then stalls mid-body must not be able to hold
 * the calling thread past the timeout the constructor advertises.
 */
class RuntimeA2aGatewayTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(250);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardReturnsTheBufferedRuntimeResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/a2a", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        RuntimeA2aGateway gateway = gateway();

        A2aGatewayResponse response = gateway.forward(
                "agent-weather", "tenant-a", RoutingContext.empty(), new byte[0], Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.runtimeInstanceId()).isEqualTo("runtime-1");
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }

    @Test
    void requestTimeoutBoundsTheWholeExchangeNotJustTheHeaders() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/a2a", exchange -> {
            // Headers arrive promptly, then the body stalls well past the
            // gateway's request timeout.
            exchange.sendResponseHeaders(200, 1024);
            OutputStream output = exchange.getResponseBody();
            output.write("partial".getBytes(StandardCharsets.UTF_8));
            output.flush();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        RuntimeA2aGateway gateway = gateway();

        Instant start = Instant.now();
        assertThatThrownBy(() -> gateway.forward(
                "agent-weather", "tenant-a", RoutingContext.empty(), new byte[0], Map.of()))
                .isInstanceOf(A2aGatewayForwardException.class);
        assertThat(Duration.between(start, Instant.now()))
                .as("forward() must give up at the configured request timeout, not wait out the stalled body")
                .isLessThan(Duration.ofMillis(2000));
    }

    private RuntimeA2aGateway gateway() {
        URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/a2a");
        AgentDirectory directory = new AgentDirectory() {
            @Override
            public AgentCard getAgentCard(String agentId, String tenantId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AgentCardSummary> listAgents(String tenantId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public RuntimeRoute resolveRoute(String agentId, String tenantId, RoutingContext routingContext) {
                return new RuntimeRoute(
                        agentId,
                        RuntimeInstanceId.of("runtime-1"),
                        endpoint,
                        RuntimeState.READY,
                        Instant.now(),
                        null,
                        null);
            }
        };
        return new RuntimeA2aGateway(directory, HttpClient.newHttpClient(), REQUEST_TIMEOUT);
    }
}
