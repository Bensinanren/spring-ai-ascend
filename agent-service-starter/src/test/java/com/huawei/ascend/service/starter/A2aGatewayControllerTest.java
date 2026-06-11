package com.huawei.ascend.service.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.service.core.A2aGatewayForwardException;
import com.huawei.ascend.service.core.A2aGatewayStreamResponse;
import com.huawei.ascend.service.core.HmacRouteGrantService;
import com.huawei.ascend.service.core.InMemoryRuntimeRegistry;
import com.huawei.ascend.service.core.RuntimeA2aGateway;
import com.huawei.ascend.service.spi.GatewayErrorCode;
import com.huawei.ascend.service.spi.discovery.RoutingContext;
import com.huawei.ascend.service.spi.registry.RuntimeAgentRegistration;
import com.huawei.ascend.service.spi.registry.RuntimeInstanceId;
import com.huawei.ascend.service.spi.routing.RouteGrant;
import com.huawei.ascend.service.spi.routing.RouteGrantRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The runtime behind the gateway is third-party-adjacent: its status codes and
 * content types are not under platform control. These tests pin that a hostile
 * or sloppy runtime response is relayed (or surfaced as a gateway-side fault)
 * and can never leak the upstream stream or be blamed on the client as a 400.
 */
class A2aGatewayControllerTest {

    private final InMemoryRuntimeRegistry registry = new InMemoryRuntimeRegistry();
    private final List<A2aForwardObserver.A2aForwardCompletion> completions = new CopyOnWriteArrayList<>();
    private final A2aGatewayController controller = new A2aGatewayController(
            new RuntimeA2aGateway(registry),
            new HmacRouteGrantService(registry, "controller-test-secret"),
            completions::add);

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void nonEnumRuntimeStatusIsRelayedVerbatimNotMappedToClient400() throws Exception {
        startRuntime(599, "application/json", "{\"error\":\"upstream\"}");

        ResponseEntity<StreamingResponseBody> response = forward();

        assertThat(response.getStatusCode().value()).isEqualTo(599);
        assertThat(drain(response)).isEqualTo("{\"error\":\"upstream\"}");
        assertThat(completions).singleElement()
                .satisfies(completion -> assertThat(completion.status()).isEqualTo("OK"));
    }

    @Test
    void malformedRuntimeContentTypeFallsBackInsteadOfFailingTheRequest() throws Exception {
        startRuntime(200, "json-but-not-a-media-type", "{}");

        ResponseEntity<StreamingResponseBody> response = forward();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(drain(response)).isEqualTo("{}");
    }

    @Test
    void forwardStartHeaderCarriesTheForwardOffsetNotACopyOfFirstByte() {
        registerRuntime(URI.create("http://runtime-1.internal:8443/a2a"));
        A2aGatewayStreamResponse response = new A2aGatewayStreamResponse(
                200,
                "application/json",
                Duration.ofMillis(5),
                Duration.ofMillis(80),
                "runtime-1",
                new ByteArrayInputStream(new byte[0]));

        ResponseEntity<StreamingResponseBody> entity = controller.respond(
                response, grant(), "message/stream", null, null, 0, Instant.now(), 15);

        HttpHeaders headers = entity.getHeaders();
        assertThat(headers.getFirst("X-Ascend-Route-Resolve-Ms")).isEqualTo("5");
        assertThat(headers.getFirst("X-Ascend-First-Byte-Ms")).isEqualTo("80");
        assertThat(headers.getFirst("X-Ascend-Forward-Start-Ms")).isEqualTo("15");
    }

    @Test
    void postProcessingFailureClosesTheUpstreamStreamAndSurfacesAGatewayFault() {
        registerRuntime(URI.create("http://runtime-1.internal:8443/a2a"));
        TrackingInputStream upstream = new TrackingInputStream();
        // Status 99 is outside any representable HTTP status, so building the
        // response entity fails even after the lenient status handling.
        A2aGatewayStreamResponse response = new A2aGatewayStreamResponse(
                99, "application/json", Duration.ZERO, Duration.ZERO, "runtime-1", upstream);

        assertThatThrownBy(() -> controller.respond(
                response, grant(), "message/stream", null, null, 0, Instant.now(), 0))
                .isInstanceOf(A2aGatewayForwardException.class);

        assertThat(upstream.closed).isTrue();
        assertThat(completions).singleElement().satisfies(completion -> {
            assertThat(completion.status()).isEqualTo("FAILED");
            assertThat(completion.errorCode()).isEqualTo(GatewayErrorCode.GATEWAY_FORWARD_FAILED.name());
        });
    }

    private ResponseEntity<StreamingResponseBody> forward() {
        return controller.forwardA2a(
                "agent-weather",
                "tenant-a",
                null,
                null,
                "gateway-facade",
                "message/stream",
                new HttpHeaders(),
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    private RouteGrant grant() {
        return new HmacRouteGrantService(registry, "controller-test-secret").resolveGrant(new RouteGrantRequest(
                "tenant-a",
                "gateway-facade",
                "agent-weather",
                "message/stream",
                RoutingContext.empty(),
                Duration.ofSeconds(60)));
    }

    private static String drain(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private void startRuntime(int status, String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/a2a", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        registerRuntime(URI.create("http://localhost:" + server.getAddress().getPort() + "/a2a"));
    }

    private void registerRuntime(URI a2aEndpoint) {
        registry.register(new RuntimeAgentRegistration(
                RuntimeInstanceId.of("runtime-1"),
                "tenant-a",
                "agent-weather",
                agentCard(a2aEndpoint),
                a2aEndpoint,
                URI.create("http://localhost:1/health"),
                "1.0.0",
                Duration.ofSeconds(60),
                Map.of()));
    }

    private static AgentCard agentCard(URI a2aEndpoint) {
        return AgentCard.builder()
                .name("agent-weather")
                .description("agent-weather A2A runtime")
                .url(a2aEndpoint.toString())
                .version("1.0.0")
                .provider(new AgentProvider("spring-ai-ascend", a2aEndpoint.toString()))
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of())
                .supportedInterfaces(List.of(new AgentInterface(
                        TransportProtocol.JSONRPC.asString(), a2aEndpoint.toString())))
                .preferredTransport(TransportProtocol.JSONRPC.asString())
                .build();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {

        private volatile boolean closed;

        private TrackingInputStream() {
            super(new byte[0]);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
