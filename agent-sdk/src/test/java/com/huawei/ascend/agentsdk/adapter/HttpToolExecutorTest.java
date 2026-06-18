package com.huawei.ascend.agentsdk.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.ascend.agentsdk.spec.tool.HttpExecutionHandle;
import com.huawei.ascend.agentsdk.support.ToolExecutionException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HttpToolExecutorTest {

    private static HttpServer server;
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastContentType = new AtomicReference<>();
    private static final AtomicInteger largeBytesWritten = new AtomicInteger();
    private static final int LARGE_RESPONSE_BYTES = 5 * 1024 * 1024;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo-json", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] payload = "{\"status\":\"ok\",\"count\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/text", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] payload = "plain answer".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/fail", exchange -> {
            byte[] payload = "upstream exploded".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/text");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] payload = "1234567890".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.createContext("/very-large", exchange -> {
            largeBytesWritten.set(0);
            byte[] chunk = "x".repeat(8192).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, LARGE_RESPONSE_BYTES);
            try (OutputStream out = exchange.getResponseBody()) {
                while (largeBytesWritten.get() < LARGE_RESPONSE_BYTES) {
                    out.write(chunk);
                    largeBytesWritten.addAndGet(chunk.length);
                }
            } catch (IOException ignored) {
                // The client is expected to close the stream once maxResponseBytes is exceeded.
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void postSendsInputsAsJsonAndDecodesJsonResponse() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/echo-json"), "POST", Map.of(), Duration.ofSeconds(5));

        Object result = new HttpToolExecutor().execute(handle, Map.of("orderId", "o-9"));

        assertThat(lastBody.get()).contains("\"orderId\":\"o-9\"");
        assertThat(lastContentType.get()).isEqualTo("application/json");
        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> decoded = (Map<?, ?>) result;
        assertThat(decoded.get("status")).isEqualTo("ok");
        assertThat(decoded.get("count")).isEqualTo(2);
    }

    @Test
    void getCarriesInputsAsQueryParametersAndReturnsTextBody() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/text"), "GET", Map.of(), Duration.ofSeconds(5));

        Object result = new HttpToolExecutor().execute(handle, Map.of("q", "wealth advice"));

        assertThat(lastQuery.get()).isEqualTo("q=wealth+advice");
        assertThat(result).isEqualTo("plain answer");
    }

    @Test
    void nonSuccessStatusFailsLoudlyWithStatusAndBodyPreview() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/fail"), "POST", Map.of(), Duration.ofSeconds(5),
                false, 1024 * 1024, true);

        assertThatThrownBy(() -> new HttpToolExecutor().execute(handle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("502")
                .hasMessageContaining("upstream exploded");
    }

    @Test
    void nonSuccessStatusHidesBodyUnlessExplicitlyExposed() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/fail"), "POST", Map.of(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> new HttpToolExecutor().execute(handle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("502")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("upstream exploded"));
    }

    @Test
    void redirectsAreNotFollowedUnlessExplicitlyEnabled() {
        HttpExecutionHandle defaultHandle = new HttpExecutionHandle(
                uri("/redirect"), "GET", Map.of(), Duration.ofSeconds(5));
        HttpExecutionHandle redirectingHandle = new HttpExecutionHandle(
                uri("/redirect"), "GET", Map.of(), Duration.ofSeconds(5),
                true, 1024 * 1024, false);

        assertThatThrownBy(() -> new HttpToolExecutor().execute(defaultHandle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("302");
        assertThat(new HttpToolExecutor().execute(redirectingHandle, Map.of()))
                .isEqualTo("plain answer");
    }

    @Test
    void responseLargerThanConfiguredLimitFailsFast() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/large"), "GET", Map.of(), Duration.ofSeconds(5),
                false, 4, false);

        assertThatThrownBy(() -> new HttpToolExecutor().execute(handle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("maxResponseBytes");
    }

    @Test
    void responseLargerThanConfiguredLimitStopsReadingStream() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                uri("/very-large"), "GET", Map.of(), Duration.ofSeconds(5),
                false, 1024, false);

        assertThatThrownBy(() -> new HttpToolExecutor().execute(handle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("maxResponseBytes");
        assertThat(largeBytesWritten.get()).isLessThan(LARGE_RESPONSE_BYTES);
    }

    @Test
    void connectionFailureSurfacesAsToolExecutionException() {
        HttpExecutionHandle handle = new HttpExecutionHandle(
                URI.create("http://127.0.0.1:1/unreachable"), "POST", Map.of(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> new HttpToolExecutor().execute(handle, Map.of()))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("request failed");
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
}
