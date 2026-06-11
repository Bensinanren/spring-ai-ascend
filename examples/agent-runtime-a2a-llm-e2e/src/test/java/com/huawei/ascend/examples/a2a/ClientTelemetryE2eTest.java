package com.huawei.ascend.examples.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.ascend.client.A2aResponse;
import com.huawei.ascend.client.AscendA2aClient;
import com.huawei.ascend.client.SendSpec;
import com.huawei.ascend.client.telemetry.OtelClientTelemetry;
import com.huawei.ascend.client.telemetry.Posture;
import com.huawei.ascend.runtime.app.LocalA2aRuntimeHost;
import com.huawei.ascend.runtime.app.RunningRuntime;
import com.huawei.ascend.runtime.app.RuntimeApp;
import com.huawei.ascend.runtime.engine.AgentExecutionContext;
import com.huawei.ascend.runtime.engine.spi.AgentExecutionResult;
import com.huawei.ascend.runtime.engine.spi.AgentRuntimeHandler;
import com.huawei.ascend.runtime.engine.spi.StreamAdapter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * End-to-end trace coherence between the client SDK's business span and the
 * real runtime's trace edge: a consumer-supplied OpenTelemetry SDK is wired
 * through {@link OtelClientTelemetry} into {@code AscendA2aClient}, one
 * streaming call rides the booted {@code RuntimeApp}, and the server's
 * {@code traceresponse} (answered by the runtime's traceparent filter from
 * the inbound header) must carry the SAME trace-id as the one exported CLIENT
 * span — wire trace and local span are one trace, observed from both ends.
 *
 * <p>The stub-server variant of these assertions lives in the SDK module
 * ({@code OtelClientTelemetryTest}); this test is the cross-module proof
 * against the real five-layer runtime boot, so it lives in the example
 * module whose classpath is DB-free.
 *
 * <p>{@code @Isolated} for the same reason as {@link RuntimeAppBootTest}:
 * Spring Boot's logging re-initialization resets the JVM-global logback
 * LoggerContext, which is not safe under concurrent context boots.
 */
@Isolated
class ClientTelemetryE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AttributeKey<String> A2A_REQUEST_TEXT =
            AttributeKey.stringKey("a2a.request.text");

    @Test
    void clientSpanAndServerTraceresponseShareOneTraceId() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (RunningRuntime runtime = RuntimeApp.create(new StubHandler()).run(LocalA2aRuntimeHost.port(0));
                OpenTelemetrySdk otel = OpenTelemetrySdk.builder()
                        .setTracerProvider(SdkTracerProvider.builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build())
                        .build()) {

            A2aResponse response = streamPing(runtime.port(), otel, Posture.DEV);

            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            SpanData span = exporter.getFinishedSpanItems().get(0);
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getName()).isEqualTo("a2a stream smoke-agent");

            // End-to-end coherence: the runtime answered a traceresponse derived
            // from the inbound traceparent, which the client minted from this
            // very span — both ends observed the same trace-id.
            assertThat(response.trace().traceresponse())
                    .isNotNull()
                    .contains(span.getTraceId());
            assertThat(response.trace().traceparent()).contains(span.getTraceId());

            // DEV posture allows message text on telemetry.
            assertThat(span.getAttributes().get(A2A_REQUEST_TEXT)).isEqualTo("ping");

            // PROD posture against the same running runtime: the PII attribute
            // is structurally never set. (Sampling of a consumer-supplied SDK
            // stays with the consumer's sampler, so the span still exports.)
            exporter.reset();
            streamPing(runtime.port(), otel, Posture.PROD);
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            assertThat(exporter.getFinishedSpanItems().get(0).getAttributes().get(A2A_REQUEST_TEXT))
                    .isNull();
        }
    }

    private static A2aResponse streamPing(int port, OpenTelemetrySdk otel, Posture posture)
            throws InterruptedException {
        try (AscendA2aClient client = AscendA2aClient.builder()
                .baseUrl("http://localhost:" + port)
                .timeout(TIMEOUT)
                .telemetry(OtelClientTelemetry.create(otel, posture))
                .build()) {
            return client.streamText(
                    SendSpec.of("smoke-agent", "session-telemetry", "sample-user", "ping"));
        }
    }

    /** Minimal framework-neutral handler: enough to wire the registry and answer one stream. */
    private static final class StubHandler implements AgentRuntimeHandler {
        @Override
        public String agentId() {
            return "smoke-agent";
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public Stream<?> execute(AgentExecutionContext context) {
            return Stream.of(Map.of("result_type", "answer", "output", "ok"));
        }

        @Override
        public StreamAdapter resultAdapter() {
            return rawResults -> rawResults.map(raw -> AgentExecutionResult.completed("ok"));
        }
    }
}
